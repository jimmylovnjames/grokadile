import { describe, it, expect } from 'vitest';
import app from '../src/index';

/** Minimal in-memory stand-in for the D1 binding used by the handlers. */
const fakeDb = {
  prepare() {
    return {
      bind() {
        return this;
      },
      async all() {
        return { results: [] as unknown[] };
      },
      async run() {
        return { success: true };
      },
    };
  },
};

/** Stateful fake D1 for the memory endpoints: honors INSERT OR IGNORE + SELECT. */
function memoryDb() {
  const rows: { agent_id: string; fact: string; learned_at: string; created_at: number }[] = [];
  return {
    prepare(sql: string) {
      let bound: unknown[] = [];
      const stmt = {
        bind(...args: unknown[]) {
          bound = args;
          return stmt;
        },
        async all() {
          const agentId = bound[0] as string;
          return {
            results: rows
              .filter((r) => r.agent_id === agentId)
              .map((r) => ({ fact: r.fact, learned_at: r.learned_at })),
          };
        },
        async run() {
          if (sql.includes('INSERT OR IGNORE INTO memory')) {
            const [agent_id, fact, learned_at, created_at] = bound as [
              string,
              string,
              string,
              number,
            ];
            if (!rows.some((r) => r.agent_id === agent_id && r.fact === fact)) {
              rows.push({ agent_id, fact, learned_at, created_at });
            }
          }
          return { success: true };
        },
      };
      return stmt;
    },
  };
}

describe('worker routes', () => {
  it('health is public and ok', async () => {
    const res = await app.request('/health');
    expect(res.status).toBe(200);
    const body = (await res.json()) as { status: string };
    expect(body.status).toBe('ok');
  });

  it('rejects protected routes without the token', async () => {
    const res = await app.request(
      '/agents/echo/tasks',
      {},
      { APP_AUTH_TOKEN: 'secret', DB: fakeDb } as never,
    );
    expect(res.status).toBe(401);
  });

  it('allows protected routes with the correct token', async () => {
    const res = await app.request(
      '/agents/echo/tasks',
      { headers: { Authorization: 'Bearer secret' } },
      { APP_AUTH_TOKEN: 'secret', DB: fakeDb } as never,
    );
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual([]);
  });

  it('unions memory facts across devices and serves them back', async () => {
    const db = memoryDb();
    const put = (facts: unknown) =>
      app.request(
        '/agents/jimmy/memory',
        {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ facts }),
        },
        { DB: db } as never,
      );

    // phone pushes its profile
    let res = await put([{ fact: "User's name is Jimmy", when: '2026-07-18T10:00:00' }]);
    expect(res.status).toBe(200);

    // laptop pushes an overlapping profile - duplicates must not multiply
    res = await put([
      { fact: "User's name is Jimmy", when: '2026-07-18T11:00:00' },
      { fact: 'User likes crocodiles', when: '2026-07-18T11:01:00' },
    ]);
    expect(res.status).toBe(200);

    const get = await app.request('/agents/jimmy/memory', {}, { DB: db } as never);
    expect(get.status).toBe(200);
    const body = (await get.json()) as { facts: { fact: string; when: string }[] };
    expect(body.facts.map((f) => f.fact)).toEqual([
      "User's name is Jimmy",
      'User likes crocodiles',
    ]);
  });

  it('memory ignores blank and malformed facts', async () => {
    const db = memoryDb();
    const res = await app.request(
      '/agents/jimmy/memory',
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ facts: ['  ', { nope: true }, { fact: 'real one' }] }),
      },
      { DB: db } as never,
    );
    expect(res.status).toBe(200);
    const get = await app.request('/agents/jimmy/memory', {}, { DB: db } as never);
    const body = (await get.json()) as { facts: { fact: string }[] };
    expect(body.facts.map((f) => f.fact)).toEqual(['real one']);
  });

  it('memory requires the token when configured', async () => {
    const res = await app.request(
      '/agents/jimmy/memory',
      {},
      { APP_AUTH_TOKEN: 'secret', DB: fakeDb } as never,
    );
    expect(res.status).toBe(401);
  });

  it('enqueues a task and echoes it back', async () => {
    const res = await app.request(
      '/agents/echo/tasks',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: 'hello', priority: 'HIGH' }),
      },
      { DB: fakeDb } as never,
    );
    expect(res.status).toBe(201);
    const task = (await res.json()) as Record<string, unknown>;
    expect(task.title).toBe('hello');
    expect(task.priority).toBe('HIGH');
    expect(task.agent_id).toBe('echo');
    expect(typeof task.id).toBe('string');
  });
});

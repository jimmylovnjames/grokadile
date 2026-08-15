import { describe, it, expect } from 'vitest';
import app from '../src/index';

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

describe('worker routes', () => {
  it('health is public and ok', async () => {
    const res = await app.request('/health');
    expect(res.status).toBe(200);
    const body = (await res.json()) as { status: string; version: string };
    expect(body.status).toBe('ok');
    expect(body.version).toBeTruthy();
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

  it('devices list requires auth when token set', async () => {
    const res = await app.request(
      '/devices',
      {},
      { APP_AUTH_TOKEN: 'secret', DB: fakeDb } as never,
    );
    expect(res.status).toBe(401);
  });

  it('devices heartbeat requires device_id', async () => {
    const res = await app.request(
      '/devices/heartbeat',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer secret' },
        body: JSON.stringify({ label: 'phone' }),
      },
      { APP_AUTH_TOKEN: 'secret', DB: fakeDb } as never,
    );
    expect(res.status).toBe(400);
  });

  it('devices heartbeat accepts device_id', async () => {
    const res = await app.request(
      '/devices/heartbeat',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer secret' },
        body: JSON.stringify({ device_id: 'android-test-1', label: 'Pixel', agents: ['echo'] }),
      },
      { APP_AUTH_TOKEN: 'secret', DB: fakeDb } as never,
    );
    expect(res.status).toBe(200);
    const body = (await res.json()) as Record<string, unknown>;
    expect(body.device_id).toBe('android-test-1');
  });
});

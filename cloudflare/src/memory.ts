import type { Context } from 'hono';
import type { Env } from './types';

/** One remembered fact about the user, shared across devices and surfaces. */
export interface MemoryFact {
  fact: string;
  when: string; // ISO timestamp from whichever device learned it
}

const MAX_FACT_LENGTH = 500;
const MAX_FACTS_PER_PUT = 50;
const MAX_FACTS_RETURNED = 200;

/**
 * GET /agents/:agentId/memory — the full remembered profile, oldest first.
 * Response: `{ "facts": [{ "fact": string, "when": string }] }`.
 */
export async function getMemory(c: Context<{ Bindings: Env }>): Promise<Response> {
  const agentId = c.req.param('agentId')!; // guaranteed by the route
  const result = await c.env.DB.prepare(
    `SELECT fact, learned_at FROM memory
      WHERE agent_id = ?1
      ORDER BY created_at ASC
      LIMIT ${MAX_FACTS_RETURNED}`,
  )
    .bind(agentId)
    .all<{ fact: string; learned_at: string }>();

  const facts: MemoryFact[] = (result.results ?? []).map((r) => ({
    fact: r.fact,
    when: r.learned_at,
  }));
  return c.json({ facts });
}

/**
 * PUT /agents/:agentId/memory — merge facts into the shared profile. Body:
 * `{ "facts": [{ "fact": string, "when"?: string }] }`. Facts are unioned
 * (duplicates ignored), so any device can push its whole local profile and
 * every device converges on the same memory.
 */
export async function putMemory(c: Context<{ Bindings: Env }>): Promise<Response> {
  const agentId = c.req.param('agentId')!; // guaranteed by the route
  const body = await c.req.json<{ facts?: unknown }>().catch(() => ({ facts: undefined }));
  const raw = Array.isArray(body.facts) ? body.facts.slice(0, MAX_FACTS_PER_PUT) : [];
  const now = Date.now();

  let stored = 0;
  for (const entry of raw) {
    const fact =
      typeof entry === 'string'
        ? entry
        : typeof (entry as MemoryFact)?.fact === 'string'
          ? (entry as MemoryFact).fact
          : '';
    if (!fact.trim()) continue;
    const when =
      typeof (entry as MemoryFact)?.when === 'string'
        ? (entry as MemoryFact).when
        : new Date(now).toISOString();
    await c.env.DB.prepare(
      `INSERT OR IGNORE INTO memory (agent_id, fact, learned_at, created_at)
       VALUES (?1, ?2, ?3, ?4)`,
    )
      .bind(agentId, fact.trim().slice(0, MAX_FACT_LENGTH), when, now)
      .run();
    stored += 1;
  }

  return c.json({ received: stored });
}

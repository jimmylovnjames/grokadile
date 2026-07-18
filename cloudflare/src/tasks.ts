import type { Context } from 'hono';
import type { Env, RemoteTask } from './types';
import { normalizePriority } from './util';

// Order HIGH > NORMAL > LOW, then oldest first.
const PRIORITY_RANK = `CASE priority WHEN 'HIGH' THEN 2 WHEN 'LOW' THEN 0 ELSE 1 END`;
const MAX_BATCH = 50;

/**
 * GET /agents/:agentId/tasks — returns pending tasks for the agent and atomically
 * marks them DELIVERED so they aren't handed out twice.
 */
export async function pullTasks(c: Context<{ Bindings: Env }>): Promise<Response> {
  const agentId = c.req.param('agentId')!; // guaranteed by the route
  const now = Date.now();

  const result = await c.env.DB.prepare(
    `SELECT id, agent_id, title, payload, priority
       FROM tasks
      WHERE agent_id = ?1 AND status = 'PENDING'
      ORDER BY ${PRIORITY_RANK} DESC, created_at ASC
      LIMIT ${MAX_BATCH}`,
  )
    .bind(agentId)
    .all<RemoteTask>();

  const tasks = result.results ?? [];
  if (tasks.length > 0) {
    const ids = tasks.map((t) => t.id);
    const placeholders = ids.map((_, i) => `?${i + 2}`).join(',');
    await c.env.DB.prepare(
      `UPDATE tasks SET status = 'DELIVERED', delivered_at = ?1 WHERE id IN (${placeholders})`,
    )
      .bind(now, ...ids)
      .run();
  }

  return c.json(tasks);
}

/**
 * POST /agents/:agentId/tasks — enqueue a task for a device to pull. Body:
 * `{ "title": string, "payload"?: string|object, "priority"?: "LOW|NORMAL|HIGH" }`.
 */
export async function enqueueTask(c: Context<{ Bindings: Env }>): Promise<Response> {
  const agentId = c.req.param('agentId')!; // guaranteed by the route
  const body = await c.req.json<Record<string, unknown>>().catch((): Record<string, unknown> => ({}));

  const id = crypto.randomUUID();
  const now = Date.now();
  const title = typeof body.title === 'string' && body.title ? body.title : 'untitled';
  const payload =
    typeof body.payload === 'string' ? body.payload : JSON.stringify(body.payload ?? {});
  const priority = normalizePriority(body.priority);

  await c.env.DB.prepare(
    `INSERT INTO tasks (id, agent_id, title, payload, priority, status, created_at)
     VALUES (?1, ?2, ?3, ?4, ?5, 'PENDING', ?6)`,
  )
    .bind(id, agentId, title, payload, priority, now)
    .run();

  const task: RemoteTask = { id, agent_id: agentId, title, payload, priority };
  return c.json(task, 201);
}

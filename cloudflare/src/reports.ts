import type { Context } from 'hono';
import type { Env } from './types';

const DONE_STATUSES = new Set(['SUCCEEDED', 'DONE', 'done', 'success']);

export async function postReport(c: Context<{ Bindings: Env }>): Promise<Response> {
  const agentId = c.req.param('agentId')!;
  const body = await c.req.json<Record<string, unknown>>().catch((): Record<string, unknown> => ({}));

  const status = String(body.status ?? 'unknown');
  const taskId = typeof body.task_id === 'string' ? body.task_id : null;
  const detail = typeof body.detail === 'string' ? body.detail : null;
  const deviceId =
    typeof body.device_id === 'string'
      ? body.device_id
      : typeof body.deviceId === 'string'
        ? body.deviceId
        : null;
  const createdAt = typeof body.timestamp === 'number' ? body.timestamp : Date.now();

  try {
    await c.env.DB.prepare(
      `INSERT INTO reports (agent_id, task_id, status, detail, device_id, created_at)
       VALUES (?1, ?2, ?3, ?4, ?5, ?6)`,
    )
      .bind(agentId, taskId, status, detail, deviceId, createdAt)
      .run();
  } catch {
    await c.env.DB.prepare(
      `INSERT INTO reports (agent_id, task_id, status, detail, created_at)
       VALUES (?1, ?2, ?3, ?4, ?5)`,
    )
      .bind(agentId, taskId, status, detail, createdAt)
      .run();
  }

  if (taskId && DONE_STATUSES.has(status)) {
    await c.env.DB.prepare(`UPDATE tasks SET status = 'DONE' WHERE id = ?1`).bind(taskId).run();
  }

  return c.body(null, 204);
}

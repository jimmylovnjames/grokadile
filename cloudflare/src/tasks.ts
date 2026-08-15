import type { Context } from 'hono';
import type { Env, RemoteTask } from './types';
import { normalizePriority } from './util';
import { listOnlineDevices } from './devices';

const PRIORITY_RANK = `CASE priority WHEN 'HIGH' THEN 2 WHEN 'LOW' THEN 0 ELSE 1 END`;
const MAX_BATCH = 50;

export async function pullTasks(c: Context<{ Bindings: Env }>): Promise<Response> {
  const agentId = c.req.param('agentId')!;
  const deviceId =
    c.req.query('device_id')?.trim() ||
    c.req.query('deviceId')?.trim() ||
    c.req.header('X-Device-Id')?.trim() ||
    null;
  const now = Date.now();

  let tasks: RemoteTask[] = [];
  if (deviceId) {
    const targeted = await c.env.DB.prepare(
      `SELECT id, agent_id, title, payload, priority, target_device_id, claimed_by
         FROM tasks
        WHERE agent_id = ?1 AND status = 'PENDING' AND target_device_id = ?2
        ORDER BY ${PRIORITY_RANK} DESC, created_at ASC
        LIMIT ${MAX_BATCH}`,
    )
      .bind(agentId, deviceId)
      .all<RemoteTask>();
    tasks = targeted.results ?? [];

    const remaining = MAX_BATCH - tasks.length;
    if (remaining > 0) {
      const pool = await c.env.DB.prepare(
        `SELECT id, agent_id, title, payload, priority, target_device_id, claimed_by
           FROM tasks
          WHERE agent_id = ?1 AND status = 'PENDING' AND target_device_id IS NULL
          ORDER BY ${PRIORITY_RANK} DESC, created_at ASC
          LIMIT ${remaining}`,
      )
        .bind(agentId)
        .all<RemoteTask>();
      tasks = tasks.concat(pool.results ?? []);
    }
  } else {
    const result = await c.env.DB.prepare(
      `SELECT id, agent_id, title, payload, priority, target_device_id, claimed_by
         FROM tasks
        WHERE agent_id = ?1 AND status = 'PENDING' AND target_device_id IS NULL
        ORDER BY ${PRIORITY_RANK} DESC, created_at ASC
        LIMIT ${MAX_BATCH}`,
    )
      .bind(agentId)
      .all<RemoteTask>();
    tasks = result.results ?? [];
  }

  if (tasks.length > 0) {
    const ids = tasks.map((t) => t.id);
    const placeholders = ids.map((_, i) => `?${i + 3}`).join(',');
    await c.env.DB.prepare(
      `UPDATE tasks
          SET status = 'DELIVERED', delivered_at = ?1, claimed_by = ?2
        WHERE id IN (${placeholders})`,
    )
      .bind(now, deviceId, ...ids)
      .run();
  }

  return c.json(tasks);
}

export async function enqueueTask(c: Context<{ Bindings: Env }>): Promise<Response> {
  const agentId = c.req.param('agentId')!;
  const body = await c.req.json<Record<string, unknown>>().catch((): Record<string, unknown> => ({}));

  const title = typeof body.title === 'string' && body.title ? body.title : 'untitled';
  const payload =
    typeof body.payload === 'string' ? body.payload : JSON.stringify(body.payload ?? {});
  const priority = normalizePriority(body.priority);

  const explicitTarget =
    (typeof body.target_device_id === 'string' && body.target_device_id) ||
    (typeof body.targetDeviceId === 'string' && body.targetDeviceId) ||
    null;
  const targetMode =
    typeof body.target === 'string' ? body.target.trim().toLowerCase() : null;

  const now = Date.now();

  if (targetMode === 'all' || targetMode === 'broadcast') {
    const online = await listOnlineDevices(c.env.DB, now);
    if (online.length === 0) {
      const task = await insertOne(c.env.DB, agentId, title, payload, priority, null, now);
      return c.json({ mode: 'broadcast', delivered: 0, tasks: [task] }, 201);
    }
    const created: RemoteTask[] = [];
    for (const d of online) {
      created.push(
        await insertOne(c.env.DB, agentId, title, payload, priority, d.device_id, now),
      );
    }
    return c.json({ mode: 'broadcast', delivered: created.length, tasks: created }, 201);
  }

  const targetDeviceId =
    explicitTarget ||
    (targetMode && targetMode !== 'any' && targetMode !== 'pool' ? targetMode : null);

  const task = await insertOne(
    c.env.DB,
    agentId,
    title,
    payload,
    priority,
    targetDeviceId,
    now,
  );
  return c.json(task, 201);
}

async function insertOne(
  db: D1Database,
  agentId: string,
  title: string,
  payload: string,
  priority: string,
  targetDeviceId: string | null,
  now: number,
): Promise<RemoteTask> {
  const id = crypto.randomUUID();
  await db
    .prepare(
      `INSERT INTO tasks
         (id, agent_id, title, payload, priority, status, target_device_id, created_at)
       VALUES (?1, ?2, ?3, ?4, ?5, 'PENDING', ?6, ?7)`,
    )
    .bind(id, agentId, title, payload, priority, targetDeviceId, now)
    .run();
  return {
    id,
    agent_id: agentId,
    title,
    payload,
    priority: priority as RemoteTask['priority'],
    target_device_id: targetDeviceId,
  };
}

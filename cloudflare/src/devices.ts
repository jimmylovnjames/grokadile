import type { Context } from 'hono';
import type { Env, DeviceInfo } from './types';

const ONLINE_WINDOW_MS = 5 * 60 * 1000;

export async function deviceHeartbeat(c: Context<{ Bindings: Env }>): Promise<Response> {
  const body = await c.req.json<Record<string, unknown>>().catch((): Record<string, unknown> => ({}));
  const deviceId =
    typeof body.device_id === 'string' && body.device_id.trim()
      ? body.device_id.trim()
      : typeof body.deviceId === 'string' && body.deviceId.trim()
        ? body.deviceId.trim()
        : null;
  if (!deviceId) {
    return c.json({ error: 'device_id_required' }, 400);
  }

  const label = typeof body.label === 'string' ? body.label.slice(0, 120) : '';
  const agents = Array.isArray(body.agents)
    ? JSON.stringify(body.agents.filter((a) => typeof a === 'string').slice(0, 64))
    : '[]';
  const meta =
    body.meta && typeof body.meta === 'object'
      ? JSON.stringify(body.meta)
      : typeof body.meta === 'string'
        ? body.meta
        : '{}';
  const now = Date.now();

  await c.env.DB.prepare(
    `INSERT INTO devices (device_id, label, agents, meta, last_seen_at)
     VALUES (?1, ?2, ?3, ?4, ?5)
     ON CONFLICT(device_id) DO UPDATE SET
       label = excluded.label,
       agents = excluded.agents,
       meta = excluded.meta,
       last_seen_at = excluded.last_seen_at`,
  )
    .bind(deviceId, label, agents, meta, now)
    .run();

  const peers = await listOnlineDevices(c.env.DB, now);
  return c.json({
    device_id: deviceId,
    label,
    last_seen_at: now,
    online_peers: peers.length,
    peers: peers.map(summarize),
  });
}

export async function listDevices(c: Context<{ Bindings: Env }>): Promise<Response> {
  const all = c.req.query('all') === '1' || c.req.query('all') === 'true';
  const windowMs = Number(c.req.query('window_ms') ?? ONLINE_WINDOW_MS) || ONLINE_WINDOW_MS;
  const now = Date.now();
  const cutoff = all ? 0 : now - windowMs;

  const result = await c.env.DB.prepare(
    `SELECT device_id, label, agents, meta, last_seen_at
       FROM devices
      WHERE last_seen_at >= ?1
      ORDER BY last_seen_at DESC
      LIMIT 200`,
  )
    .bind(cutoff)
    .all<DeviceRow>();

  const devices = (result.results ?? []).map((row) => ({
    ...summarize(row),
    online: row.last_seen_at >= now - ONLINE_WINDOW_MS,
  }));

  return c.json({ count: devices.length, devices });
}

export async function listOnlineDevices(db: D1Database, now = Date.now()): Promise<DeviceRow[]> {
  const result = await db
    .prepare(
      `SELECT device_id, label, agents, meta, last_seen_at
         FROM devices
        WHERE last_seen_at >= ?1
        ORDER BY last_seen_at DESC
        LIMIT 200`,
    )
    .bind(now - ONLINE_WINDOW_MS)
    .all<DeviceRow>();
  return result.results ?? [];
}

interface DeviceRow {
  device_id: string;
  label: string;
  agents: string;
  meta: string;
  last_seen_at: number;
}

function summarize(row: DeviceRow): DeviceInfo {
  let agents: string[] = [];
  try {
    agents = JSON.parse(row.agents);
  } catch {
    agents = [];
  }
  return {
    device_id: row.device_id,
    label: row.label,
    agents,
    last_seen_at: row.last_seen_at,
  };
}

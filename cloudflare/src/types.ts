/** Worker bindings (see wrangler.toml + secrets). */
export interface Env {
  DB: D1Database;
  GROK_BASE_URL: string;
  GROK_API_KEY?: string;
  APP_AUTH_TOKEN?: string;
}

export type Priority = 'LOW' | 'NORMAL' | 'HIGH';

export interface RemoteTask {
  id: string;
  agent_id: string;
  title: string;
  payload: string;
  priority: Priority;
  target_device_id?: string | null;
  claimed_by?: string | null;
}

export interface DeviceInfo {
  device_id: string;
  label: string;
  agents: string[];
  last_seen_at: number;
}

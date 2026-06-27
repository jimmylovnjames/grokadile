/** Worker bindings (see wrangler.toml + secrets). */
export interface Env {
  /** D1 database holding the task queue and reports. */
  DB: D1Database;
  /** Upstream Grok base URL, e.g. https://api.x.ai */
  GROK_BASE_URL: string;
  /** xAI API key (secret) — used server-side so the device never holds it. */
  GROK_API_KEY?: string;
  /** Shared bearer token the device must present. If unset, the worker is open. */
  APP_AUTH_TOKEN?: string;
}

export type Priority = 'LOW' | 'NORMAL' | 'HIGH';

/** Mirrors the app's RemoteTaskDto (snake_case on the wire). */
export interface RemoteTask {
  id: string;
  agent_id: string;
  title: string;
  payload: string;
  priority: Priority;
}

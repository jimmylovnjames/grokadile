-- Grokadile control-plane schema (Cloudflare D1 / SQLite).
-- Apply with:  npm run db:init        (remote)
--              npm run db:init:local   (local dev)

CREATE TABLE IF NOT EXISTS tasks (
    id               TEXT PRIMARY KEY,
    agent_id         TEXT NOT NULL,
    title            TEXT NOT NULL,
    payload          TEXT NOT NULL DEFAULT '{}',
    priority         TEXT NOT NULL DEFAULT 'NORMAL',
    status           TEXT NOT NULL DEFAULT 'PENDING',
    target_device_id  TEXT,
    claimed_by       TEXT,
    created_at       INTEGER NOT NULL,
    delivered_at     INTEGER
);

CREATE INDEX IF NOT EXISTS idx_tasks_agent_status
    ON tasks (agent_id, status);

CREATE INDEX IF NOT EXISTS idx_tasks_target
    ON tasks (target_device_id, status);

CREATE TABLE IF NOT EXISTS reports (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    agent_id   TEXT NOT NULL,
    task_id    TEXT,
    status     TEXT NOT NULL,
    detail     TEXT,
    device_id  TEXT,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_reports_agent_time
    ON reports (agent_id, created_at);

CREATE TABLE IF NOT EXISTS devices (
    device_id    TEXT PRIMARY KEY,
    label        TEXT NOT NULL DEFAULT '',
    agents       TEXT NOT NULL DEFAULT '[]',
    meta         TEXT NOT NULL DEFAULT '{}',
    last_seen_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_devices_seen
    ON devices (last_seen_at);

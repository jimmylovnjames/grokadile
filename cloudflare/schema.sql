-- Grokadile control-plane schema (Cloudflare D1 / SQLite).
-- Apply with:  npm run db:init        (remote)
--              npm run db:init:local   (local dev)

CREATE TABLE IF NOT EXISTS tasks (
    id           TEXT PRIMARY KEY,
    agent_id     TEXT NOT NULL,
    title        TEXT NOT NULL,
    payload      TEXT NOT NULL DEFAULT '{}',
    priority     TEXT NOT NULL DEFAULT 'NORMAL',   -- LOW | NORMAL | HIGH
    status       TEXT NOT NULL DEFAULT 'PENDING',  -- PENDING | DELIVERED | DONE
    created_at   INTEGER NOT NULL,
    delivered_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_tasks_agent_status
    ON tasks (agent_id, status);

CREATE TABLE IF NOT EXISTS reports (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    agent_id   TEXT NOT NULL,
    task_id    TEXT,
    status     TEXT NOT NULL,
    detail     TEXT,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_reports_agent_time
    ON reports (agent_id, created_at);

-- Shared profile memory: lasting facts about the user, unioned across
-- devices so every surface talks to the same "person".
CREATE TABLE IF NOT EXISTS memory (
    agent_id   TEXT NOT NULL,
    fact       TEXT NOT NULL,
    learned_at TEXT NOT NULL,     -- ISO timestamp from the device that learned it
    created_at INTEGER NOT NULL,  -- server receive time
    PRIMARY KEY (agent_id, fact)
);

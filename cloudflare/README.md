# Grokadile Worker

Cloudflare Worker backend for Grokadile. Three jobs:

1. **Grok chat proxy** — `POST /v1/chat/completions` forwards to xAI with a
   server-held API key, so the device never carries it (OpenAI-compatible,
   streaming pass-through).
2. **On-device control plane** — a D1-backed task queue + activity log the
   Android app pulls from and reports to.
3. **Screen-agent orchestrator** — tasks + live WebSocket control + telemetry
   for the host-side screen-acting runner (ADB/Playwright), persisted to D1,
   with failure screenshots in R2.

Built with [Hono](https://hono.dev), [D1](https://developers.cloudflare.com/d1/),
[Durable Objects](https://developers.cloudflare.com/durable-objects/), and R2.

## Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET  | `/health` | public | Liveness — `{ status, version, time }` |
| POST | `/v1/chat/completions` | bearer | Proxy to Grok (injects `GROK_API_KEY`) |
| POST | `/v1/agent/tasks` | bearer | Create a screen-agent task ⇒ `{ taskId }` |
| GET  | `/v1/agent/tasks/:id` | bearer | Task record + status |
| POST | `/v1/agent/tasks/:id/control` | bearer | `{ command:"pause\|resume\|cancel\|answer", payload? }` |
| GET  | `/v1/agent/connect?agentId=` | bearer | **WebSocket** upgrade → the agent's Durable Object |
| PUT  | `/v1/agent/screenshots/:id` | bearer | Upload a failure screenshot to R2 |
| GET  | `/v1/agent/screenshots/:id` | bearer | Fetch a stored screenshot |
| GET  | `/agents/:agentId/tasks` | bearer | (on-device) Pull pending tasks |
| POST | `/agents/:agentId/tasks` | bearer | (on-device) Enqueue `{ title, payload?, priority? }` |
| POST | `/agents/:agentId/report` | bearer | (on-device) Record activity |

Protected routes require `Authorization: Bearer <APP_AUTH_TOKEN>`. If
`APP_AUTH_TOKEN` is unset the worker runs open (local dev only).

### Screen-agent contract

The contract (zod-validated, shared with the runner) lives in `src/contract.ts`.

**Create a task:**
```json
{ "agentId":"pixel-7",
  "goal":"In Settings, enable Battery Saver",
  "target":{"platform":"android","deviceId":"emulator-5554"},
  "constraints":{"maxSteps":12,"allowDestructive":false,"timeoutSec":120},
  "skills":["android.settings"] }
```

**WebSocket messages** — agent→worker: `hello · status · observation · action ·
escalation · result · heartbeat`; worker→agent: `assign · control · ack · error`.

**Grounded action schema** (the runner rejects any `elementId` not in the
current observation — the core anti-hallucination guard):
```json
{"type":"tap","elementId":"e12","reason":"…"}
{"type":"type","elementId":"e3","text":"…"}
{"type":"swipe","direction":"up"} | {"type":"key","key":"back"}
{"type":"wait","ms":500} | {"type":"done"} | {"type":"escalate","reason":"…"}
```
Telemetry persists to `agent_traces` (full replay), screenshots to R2.

## Setup & deploy

```bash
cd cloudflare
npm install

# 1. Create the D1 database, then paste the printed database_id into wrangler.toml
npx wrangler d1 create grokadile

# 1b. Create the R2 bucket for failure screenshots (binding already in wrangler.toml)
npx wrangler r2 bucket create grokadile-screenshots

# 2. Apply the schema (local + remote)
npm run db:init:local
npm run db:init

# 3. Set secrets
npx wrangler secret put GROK_API_KEY     # your xAI key
npx wrangler secret put APP_AUTH_TOKEN   # a long random string

# 4. Run locally / deploy
cp .dev.vars.example .dev.vars           # fill in for `wrangler dev`
npm run dev
npm run deploy
```

## Wiring the Android app to it

After deploy you get a URL like `https://grokadile-worker.<subdomain>.workers.dev`.
Point the app at it via `local.properties` (or CI env), and use `APP_AUTH_TOKEN`
as the app's **Settings → API key**:

```properties
# grokadile/local.properties
GROK_BASE_URL=https://grokadile-worker.<subdomain>.workers.dev/
CLOUDFLARE_BASE_URL=https://grokadile-worker.<subdomain>.workers.dev/
```

The app's `GrokApi` hits `…/v1/chat/completions` and `CloudflareApi` hits
`…/health`, `…/agents/{id}/tasks`, `…/agents/{id}/report` — all served here.

## Develop

```bash
npm run typecheck   # tsc --noEmit
npm test            # vitest (route + util tests)
```

## Extending

- New control-plane route → add a handler under `src/` and register it in
  `src/index.ts` (put it behind `bearerAuth`).
- Swap/extend storage by changing the D1 queries in `src/tasks.ts` /
  `src/reports.ts`; the schema lives in `schema.sql`.
- For per-device tokens or rate limiting, extend `src/auth.ts` (e.g. look the
  token up in D1/KV instead of comparing a single shared secret).

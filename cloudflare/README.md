# Grokadile Worker

Cloudflare Worker backend for the Grokadile Android app. Two jobs:

1. **Grok chat proxy** — `POST /v1/chat/completions` forwards to xAI with a
   server-held API key, so the device never carries it (OpenAI-compatible,
   streaming pass-through).
2. **Agent control plane** — a D1-backed task queue + activity log the device
   pulls from and reports to.

Built with [Hono](https://hono.dev) + [D1](https://developers.cloudflare.com/d1/).

## Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET  | `/health` | public | Liveness — `{ status, version, time }` |
| POST | `/v1/chat/completions` | bearer | Proxy to Grok (injects `GROK_API_KEY`) |
| GET  | `/agents/:agentId/tasks` | bearer | Pull pending tasks (marks them DELIVERED) |
| POST | `/agents/:agentId/tasks` | bearer | Enqueue a task: `{ title, payload?, priority? }` |
| POST | `/agents/:agentId/report` | bearer | Record activity: `{ status, task_id?, detail?, timestamp? }` |
| GET  | `/agents/:agentId/memory` | bearer | Shared profile memory: `{ facts: [{ fact, when }] }` |
| PUT  | `/agents/:agentId/memory` | bearer | Union-merge facts pushed by a device (duplicates ignored) |

Protected routes require `Authorization: Bearer <APP_AUTH_TOKEN>`. If
`APP_AUTH_TOKEN` is unset the worker runs open (local dev only).

## Setup & deploy

```bash
cd cloudflare
npm install

# 1. Create the D1 database, then paste the printed database_id into wrangler.toml
npx wrangler d1 create grokadile

# 2. Apply the schema (local + remote). Idempotent - rerun it after pulling
#    updates that add tables (e.g. the shared `memory` table).
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

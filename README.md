# Grokadile

A Grok-powered autonomous Android agent platform. Grokadile runs a queue of
agent "tasks" on-device through a persistent foreground service, talks to the
Grok (xAI) API directly or via a Cloudflare Worker proxy, and is built to be
extended with new agent logic by adding a single class.

## Priority Order (Capability Roadmap)

See **[PRIORITY.md](PRIORITY.md)** for the locked execution sequence ranked by leverage:

1. **Screen-reading agent (accessibility)** — ✅ LANDED
2. **ScreenTap / UI Automator agent** — ✅ LANDED
3. **Remote dispatch via Cloudflare C2** — ✅ LANDED
4. **Termux-API tool expansion** — ✅ LANDED
5. **SchedulerAgent with cron triggers** — ✅ LANDED
6. **NotificationListenerAgent** — ✅ LANDED
7. **Swarm coordination (multi-device)** — ✅ LANDED
8. **Vector memory** — High · Long-term intelligence  ← next

## Architecture

Clean, layered, and modular — UI → domain → data, with a runtime engine in the
middle that drives pluggable agents.

Built-in agents include Echo, GrokChat, Heartbeat, ScreenReading, ScreenTap,
Scheduler, NotificationListener, and **Swarm** (multi-device coordination).

### Multi-device swarm

Each phone heartbeats a stable `device_id` to the Cloudflare worker. Tasks can
be targeted to **any** device, **all** online devices (broadcast), or a **specific**
device id. See `PRIORITY.md` and `cloudflare/README.md`.

## Build

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
cd cloudflare && npm test && npm run deploy
```

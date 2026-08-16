# Grokadile

A Grok-powered autonomous Android agent platform. Grokadile runs a queue of
agent "tasks" on-device through a persistent foreground service, talks to the
Grok (xAI) API directly or via a Cloudflare Worker proxy, and is built to be
extended with new agent logic by adding a single class.

**Current release: 0.3.2**

## Priority Order (Capability Roadmap)

See **[PRIORITY.md](PRIORITY.md)** for the locked execution sequence ranked by leverage:

1. **Screen-reading agent (accessibility)** — ✅ LANDED
2. **ScreenTap / UI Automator agent** — ✅ LANDED
3. **Remote dispatch via Cloudflare C2** — ✅ LANDED
4. **Termux-API tool expansion** — ✅ LANDED
5. **SchedulerAgent with cron triggers** — ✅ LANDED
6. **NotificationListenerAgent** — ✅ LANDED
7. **Swarm coordination (multi-device)** — ✅ LANDED
8. **Vector memory** — ✅ LANDED
9. **Planner + memory-grounded chat + device tools** — ✅ LANDED (0.3.0)
10. **ScreenSummaryAgent (text vision)** — ✅ LANDED (0.3.1)
11. **SmartActionAgent (observe → decide → act)** — ✅ LANDED (0.3.2)

## Architecture

Clean, layered, and modular — UI → domain → data, with a runtime engine in the
middle that drives pluggable agents.

Built-in agents include Echo, GrokChat (RAG + remember), Heartbeat, ScreenReading,
ScreenTap, **ScreenSummary**, **SmartAction**, Scheduler, NotificationListener, Swarm, VectorMemory, **Planner**,
**Clipboard**, **AppLaunch**, and **DeviceHealth**.

### 0.3.2 highlights

- **SmartActionAgent** — observe screen via accessibility dump, ask Grok for one concrete UI action toward a goal, then execute it (click_text / tap / swipe / type / global / none)
- Closes the tighter observe → decide → act loop after ScreenSummary
- Planner catalog includes `smart_action` for natural goals like “tap the login button”

### 0.3.1 highlights

- **ScreenSummaryAgent** — accessibility dump → Grok summary of the current UI, key elements, and suggested actions
- Planner catalog includes `screen_summary` so natural goals can plan a “see the screen” step

### 0.3.0 highlights

- **PlannerAgent** — Grok decomposes a goal into a short list of agent tasks and enqueues them
- **Memory-grounded chat** — GrokChat and voice/chat search vector memory, then store the Q&A
- **Clipboard / App launch / Device health** — read-write clipboard, open apps, battery/network snapshot, retry failed tasks
- **Share sheet** — share text into Grokadile to remember it (`plan: …` runs the planner)
- **Quick Settings tile** — toggle autonomy from the shade
- Dashboard memory search + device vitals + health/retry/plan actions

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

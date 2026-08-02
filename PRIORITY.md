# Grokadile Capability Priority Order

Ordered by **leverage / unlock sequence**. Execute top-down.  
Effort estimates assume single high-velocity prompt+build cycles on phone + Termux + Fable/Grok.

| # | Move | Effort | Leverage | Why this order |
|---|------|--------|----------|----------------|
| 1 | **Screen-reading agent (accessibility)** | Medium | **Unlocks everything** | Foundation. Turns the existing stub `GrokadileAccessibilityService` into a real observation surface. Every later agent that needs to "see" the phone depends on this. |
| 2 | **ScreenTap / UI Automator agent** | Medium | Full phone control | Observation + action. With screen reading + gesture injection you get true UI automation (tap, swipe, type, long-press). Closes the loop. |
| 3 | **Remote dispatch via Cloudflare C2** | Low | Control from anywhere | The worker already has task pull + report endpoints. Wire a clean remote → on-device task path + optional reverse tunnel so Grokadile becomes a controllable node from any browser / other agent. |
| 4 | **Termux-API tool expansion** | Low | Massive capability jump | Battery, location, SMS, camera, sensors, notifications, clipboard, etc. via `termux-api`. Instant real-world sensors + actuators without writing new Android services. |
| 5 | **SchedulerAgent with cron triggers** | Low | Autonomous scheduled execution | Native WorkManager / AlarmManager + cron-style expressions. Enables "every morning do X", "at 3am check Y" without constant polling. |
| 6 | **NotificationListenerAgent** | Medium | Reactive real-world triggers | Listen to system notifications → turn them into tasks. Bank alerts, WhatsApp, delivery updates, etc. become agent triggers. |
| 7 | **Swarm coordination (multi-device)** | Low | Scale to a phone farm | Once remote dispatch exists, add device registry + task fan-out + result aggregation across multiple phones. |
| 8 | **Vector memory** | High | Long-term intelligence | Embeddings + local/remote vector store on top of the existing Room `AgentMemory`. Only after the agent can act and observe does long-term memory become high-ROI. |

## Current baseline (as of main)

- OrchestrationEngine + Task queue + retry/backoff ✅
- Pluggable Agent interface + Hilt multibinding ✅
- Stub `GrokadileAccessibilityService` (events not yet routed) ✅
- Cloudflare Worker with task enqueue / pull / report ✅
- Termux single-file agent (`termux/grokadile.py`) with basic tools ✅
- Foreground service + WorkManager heartbeat + boot receiver ✅

## Execution notes

1. **Screen-reading first** — implement `onAccessibilityEvent` routing + a proper `ScreenReadingAgent` that can dump view hierarchy / text / focused node into memory or as task results.
2. Keep every new agent pure: declare capabilities (`ACCESSIBILITY`, `NOTIFICATIONS`, etc.), stay stateless across tasks, put durable state in `AgentContext.memory`.
3. Prefer expanding the existing Termux tool registry in parallel for quick capability wins while the native a11y path matures.
4. Remote dispatch should reuse the existing Cloudflare D1 task table; avoid inventing a second control plane.

---

*Last updated: 2026-08-02 — priority order locked from velocity session.*

# Grokadile Capability Priority Order

Ordered by **leverage / unlock sequence**. Execute top-down.  
Effort estimates assume single high-velocity prompt+build cycles on phone + Termux + Fable/Grok.

| # | Move | Effort | Leverage | Status |
|---|------|--------|----------|--------|
| 1 | **Screen-reading agent (accessibility)** | Medium | **Unlocks everything** | ✅ **LANDED** |
| 2 | **ScreenTap / UI Automator agent** | Medium | Full phone control | next |
| 3 | **Remote dispatch via Cloudflare C2** | Low | Control from anywhere | |
| 4 | **Termux-API tool expansion** | Low | Massive capability jump | |
| 5 | **SchedulerAgent with cron triggers** | Low | Autonomous scheduled execution | |
| 6 | **NotificationListenerAgent** | Medium | Reactive real-world triggers | |
| 7 | **Swarm coordination (multi-device)** | Low | Scale to a phone farm | |
| 8 | **Vector memory** | High | Long-term intelligence | |

## Current baseline (as of main)

- OrchestrationEngine + Task queue + retry/backoff ✅
- Pluggable Agent interface + Hilt multibinding ✅
- **Live `GrokadileAccessibilityService` + `ScreenReadingAgent`** ✅ (hierarchy / text / focused dumps, memory store)
- Cloudflare Worker with task enqueue / pull / report ✅
- Termux single-file agent (`termux/grokadile.py`) with basic tools ✅
- Foreground service + WorkManager heartbeat + boot receiver ✅

## How to use Screen Reader

1. Enable **Settings → Accessibility → Grokadile Agent Control**
2. Enqueue a task with `agentId = "screen_reader"`

Payload examples:
```json
{ "mode": "hierarchy" }          // default – full tree
{ "mode": "text" }                // flat visible text only
{ "mode": "focused", "maxDepth": 8 }
```

Result is the dump string. Also written to agent memory under `last_screen_dump`.

## Execution notes

1. ~~Screen-reading first~~ → done. Next is ScreenTap / gesture injection on the same service (`canPerformGestures=true` already set).
2. Keep every new agent pure: declare capabilities, stay stateless across tasks, put durable state in `AgentContext.memory`.
3. Prefer expanding the existing Termux tool registry in parallel for quick capability wins.
4. Remote dispatch should reuse the existing Cloudflare D1 task table.

---

*Last updated: 2026-08-02 — #1 Screen-reading agent shipped.*

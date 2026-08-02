# Grokadile Capability Priority Order

Ordered by **leverage / unlock sequence**. Execute top-down.  
Effort estimates assume single high-velocity prompt+build cycles on phone + Termux + Fable/Grok.

| # | Move | Effort | Leverage | Status |
|---|------|--------|----------|--------|
| 1 | **Screen-reading agent (accessibility)** | Medium | **Unlocks everything** | ✅ **LANDED** |
| 2 | **ScreenTap / UI Automator agent** | Medium | Full phone control | ✅ **LANDED** |
| 3 | **Remote dispatch via Cloudflare C2** | Low | Control from anywhere | next |
| 4 | **Termux-API tool expansion** | Low | Massive capability jump | |
| 5 | **SchedulerAgent with cron triggers** | Low | Autonomous scheduled execution | |
| 6 | **NotificationListenerAgent** | Medium | Reactive real-world triggers | |
| 7 | **Swarm coordination (multi-device)** | Low | Scale to a phone farm | |
| 8 | **Vector memory** | High | Long-term intelligence | |

## Current baseline (as of main)

- OrchestrationEngine + Task queue + retry/backoff ✅
- Pluggable Agent interface + Hilt multibinding ✅
- **Live `GrokadileAccessibilityService`** ✅
  - `ScreenReadingAgent` (`screen_reader`) — hierarchy / text / focused dumps
  - `ScreenTapAgent` (`screen_tap`) — tap, long-press, swipe, click_text, click_id, type, global actions
- Cloudflare Worker with task enqueue / pull / report ✅
- Termux single-file agent (`termux/grokadile.py`) with basic tools ✅
- Foreground service + WorkManager heartbeat + boot receiver ✅

## How to use Screen Reader + Screen Tap

1. Enable **Settings → Accessibility → Grokadile Agent Control**
2. Read: enqueue `agentId = "screen_reader"`
3. Act: enqueue `agentId = "screen_tap"`

### Screen Reader payloads
```json
{ "mode": "hierarchy" }
{ "mode": "text" }
{ "mode": "focused", "maxDepth": 8 }
```

### Screen Tap payloads
```json
{ "action": "tap", "x": 540, "y": 1200 }
{ "action": "long_press", "x": 540, "y": 1200 }
{ "action": "swipe", "fromX": 100, "fromY": 800, "toX": 100, "toY": 200 }
{ "action": "click_text", "text": "Login" }
{ "action": "click_id", "viewId": "submit" }
{ "action": "type", "text": "hello" }
{ "action": "global", "name": "BACK" }
```

Results land in task output + agent memory (`last_screen_dump` / `last_ui_action`).

## Execution notes

1. ~~Screen-reading~~ + ~~ScreenTap~~ → observation + action loop closed.
2. Next high-leverage low-effort: **Remote dispatch via Cloudflare C2** (task pull already exists on the worker).
3. Keep agents pure: declare capabilities, stateless across tasks, state in `AgentContext.memory`.
4. Termux-API expansion can run in parallel for sensors / SMS / battery without touching the native a11y path.

---

*Last updated: 2026-08-02 — #2 ScreenTap / UI Automator agent shipped.*

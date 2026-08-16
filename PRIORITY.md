# Grokadile Capability Priority Order

Ordered by **leverage / unlock sequence**. Execute top-down.

| # | Move | Effort | Leverage | Status |
|---|------|--------|----------|--------|
| 1 | **Screen-reading agent (accessibility)** | Medium | **Unlocks everything** | ✅ **LANDED** |
| 2 | **ScreenTap / UI Automator agent** | Medium | Full phone control | ✅ **LANDED** |
| 3 | **Remote dispatch via Cloudflare C2** | Low | Control from anywhere | ✅ **LANDED** |
| 4 | **Termux-API tool expansion** | Low | Massive capability jump | ✅ **LANDED** |
| 5 | **SchedulerAgent with cron triggers** | Low | Autonomous scheduled execution | ✅ **LANDED** |
| 6 | **NotificationListenerAgent** | Medium | Reactive real-world triggers | ✅ **LANDED** |
| 7 | **Swarm coordination (multi-device)** | Low | Scale to a phone farm | ✅ **LANDED** |
| 8 | **Vector memory** | High | Long-term intelligence | ✅ **LANDED** |
| 9 | **Planner + memory chat + device tools** | Medium | Compose all agents; act on the phone | ✅ **LANDED** |
| 10 | **ScreenSummaryAgent (text vision)** | Low | Understand current UI via Grok | ✅ **LANDED** |
| 11 | **ScreenActAgent (goal → dump → decide → act)** | Low | Closed-loop UI automation from natural goals | ✅ **LANDED** |

## ScreenActAgent (#11)

Closes the tighter observation → decision → action loop. Given a natural-language goal, dumps the accessibility tree, asks Grok for a single ScreenTap-compatible action JSON, then executes it (or dry-runs).

- **ScreenActAgent** (`screen_act`) — `goal` · `mode` · `dryRun` · `model`
- Planner catalog includes `screen_act` so goals like “tap Login” or “go back” can plan a single act step
- Unit tests cover happy-path click_text, dry-run, none, accessibility retry, missing goal, network retry, and markdown-wrapped JSON tolerance

### Example

```bash
curl -X POST "$WORKER/agents/screen_act/tasks" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"act","payload":{"goal":"Open Settings","dryRun":false}}'
```

Voice / chat: “tap the login button”, “open settings”, “go back”.

## Execution notes

1–11 closed for the text-vision + single-step act layer. Next leverage remains true screenshot → Grok vision multimodal, multi-step tool-calling loops with verification, or richer planner feedback.

*Last updated: 2026-08-17 — #11 ScreenActAgent shipped.*

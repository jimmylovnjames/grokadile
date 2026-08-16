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

## ScreenSummaryAgent (#10)

Grokadile now has a text-based vision step: dump the accessibility tree and ask Grok to summarize the UI, extract key elements, and suggest actions.

- **ScreenSummaryAgent** (`screen_summary`) — `mode` · `prompt` · `store`
- Planner catalog updated so goals like “what’s on my screen?” or “find the settings button” can plan a summary step
- Unit tests cover happy path, accessibility-down retry, dump errors, custom prompts, and network retry

### Example

```bash
curl -X POST "$WORKER/agents/screen_summary/tasks" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"see","payload":{"mode":"hierarchy","prompt":"What can I tap to open settings?"}}'
```

Voice / chat: “summarize the screen”, “what’s on my screen?”, “describe this UI”.

## Execution notes

1–10 closed for the text-vision layer. Next leverage remains true screenshot → Grok vision multimodal, or tighter tool-calling loops that chain summary → tap automatically.

*Last updated: 2026-08-16 — #10 ScreenSummaryAgent shipped.*

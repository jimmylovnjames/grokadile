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
| 11 | **SmartActionAgent (observe → decide → act)** | Medium | Tighter tool-calling loops | ✅ **LANDED** |

## SmartActionAgent (#11)

Closes the observation → decision → action loop in a single agent call.

- **SmartActionAgent** (`smart_action`) — takes a high-level `goal`, dumps the screen, asks Grok for one concrete UI action (click_text / tap / swipe / type / global / none), then executes it via the accessibility action provider.
- Planner catalog and schema updated so goals like “tap the login button” or “go back” can be planned as a smart_action step.
- Unit tests cover success path (click_text + fence stripping), accessibility-down retry, dump ERROR, missing goal, network retry, and none/no-op.

### Example

```bash
curl -X POST "$WORKER/agents/smart_action/tasks" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"settings","payload":{"goal":"tap the Open Settings button","mode":"hierarchy"}}'
```

Voice / chat: “tap the settings button”, “click login”, “smart action: go back”.

## Execution notes

1–11 closed for the observe-decide-act loop. Next leverage remains true screenshot → Grok vision multimodal, multi-step autonomous tool-calling, or richer planner self-correction.

*Last updated: 2026-08-17 — #11 SmartActionAgent shipped.*

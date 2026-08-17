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
| 11 | **ScreenWaitAgent (sync UI chains)** | Low | Reliable multi-step automation | ✅ **LANDED** |

## ScreenWaitAgent (#11)

Polls the accessibility tree until a condition is satisfied (or timeout). Closes race conditions between UI actions so planner / voice / C2 chains can be reliable.

- **ScreenWaitAgent** (`screen_wait`) — `mode` · `text` · `packageName` · `timeoutMs` · `pollMs` · `exact`
- Modes: `appear` (wait for text), `disappear` (wait until gone), `package` (wait for active package)
- Planner catalog includes `screen_wait` so goals can insert explicit wait steps
- Unit tests cover appear / disappear / package / a11y-down / validation / timeout / exact match

### Example

```bash
curl -X POST "$WORKER/agents/screen_wait/tasks" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"wait-settings","payload":{"mode":"appear","text":"Settings","timeoutMs":10000,"pollMs":400}}'
```

Typical plan fragment after a tap:

1. `screen_tap` click “Open”
2. `screen_wait` appear “Success” (or package change)
3. next action

## Execution notes

1–11 closed for the sync layer of the observe → act loop. Next leverage remains true screenshot → Grok vision multimodal, or a multi-step ScreenAct / SmartAction loop that auto-chains summary + wait + tap.

*Last updated: 2026-08-18 — #11 ScreenWaitAgent shipped.*

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
| 12 | **ScreenActAgent (observe → decide → act loop)** | Medium | Closed-loop UI automation from a natural goal | ✅ **LANDED** |

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

## ScreenActAgent (#12)

Closes the multi-step UI automation loop. Given a natural-language goal, the agent observes the current screen, asks Grok for the single best next action, executes it via `ScreenActionProvider`, optionally waits for the expected change, and repeats until success / max steps / timeout.

- **ScreenActAgent** (`screen_act`) — `goal` · `maxSteps` · `timeoutMs` · `model` · `store` · `confirmWithWait`
- Composes landed tools: accessibility dump (ScreenReading), Grok decision (ScreenSummary-style), tap/type/swipe/global (ScreenTap), optional confirm (ScreenWait)
- Planner catalog includes `screen_act` so a goal like “Open Settings and turn Wi-Fi off” can be one dispatchable step
- **Vision-ready:** `perception=vision` captures a screenshot via `AccessibilityService.takeScreenshot` (API 30+, not MediaProjection) and sends it to `grok-2-vision-latest` as a multimodal image part. The accessibility dump stays as text grounding. If capture fails, the loop falls back to text.

### Example

```bash
curl -X POST "$WORKER/agents/screen_act/tasks" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"wifi-off","payload":{"goal":"Open Settings and turn Wi-Fi off","maxSteps":8,"timeoutMs":90000,"confirmWithWait":true}}'
```

## Execution notes

1–12 closed for the observe → decide → act → confirm loop, including screenshot → Grok vision when `perception=vision`.

*Last updated: 2026-08-18 — #12 ScreenActAgent shipped (vision path via accessibility screenshot).*

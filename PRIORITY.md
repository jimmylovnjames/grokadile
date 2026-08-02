# Grokadile Capability Priority Order

Ordered by **leverage / unlock sequence**. Execute top-down.  
Effort estimates assume single high-velocity prompt+build cycles on phone + Termux + Fable/Grok.

| # | Move | Effort | Leverage | Status |
|---|------|--------|----------|--------|
| 1 | **Screen-reading agent (accessibility)** | Medium | **Unlocks everything** | ✅ **LANDED** |
| 2 | **ScreenTap / UI Automator agent** | Medium | Full phone control | ✅ **LANDED** |
| 3 | **Remote dispatch via Cloudflare C2** | Low | Control from anywhere | ✅ **LANDED** |
| 4 | **Termux-API tool expansion** | Low | Massive capability jump | next |
| 5 | **SchedulerAgent with cron triggers** | Low | Autonomous scheduled execution | |
| 6 | **NotificationListenerAgent** | Medium | Reactive real-world triggers | |
| 7 | **Swarm coordination (multi-device)** | Low | Scale to a phone farm | |
| 8 | **Vector memory** | High | Long-term intelligence | |

## Current baseline (as of main)

- OrchestrationEngine + Task queue + retry/backoff ✅
- Pluggable Agent interface + Hilt multibinding ✅
- **Live `GrokadileAccessibilityService`** ✅
  - `ScreenReadingAgent` (`screen_reader`)
  - `ScreenTapAgent` (`screen_tap`)
- **Remote dispatch via Cloudflare Worker** ✅
  - `RemoteTaskSync` pulls pending tasks for every registered agent
  - HeartbeatWorker + engine start trigger pulls
  - Terminal results (`SUCCEEDED` / `FAILED`) reported back to the worker
- Cloudflare Worker task enqueue / pull / report endpoints ✅
- Termux single-file agent (`termux/grokadile.py`) with basic tools ✅
- Foreground service + WorkManager heartbeat + boot receiver ✅

## How to remote-control a device

1. Deploy the worker (`cloudflare/`) and set `CLOUDFLARE_BASE_URL` + `APP_AUTH_TOKEN` (the same token goes into the app Settings as API key).
2. Enable autonomy on the device.
3. From anywhere:

```bash
# Enqueue a screen dump
curl -X POST "https://your-worker.workers.dev/agents/screen_reader/tasks" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"dump hierarchy","payload":{"mode":"hierarchy"},"priority":"HIGH"}'

# Enqueue a tap
curl -X POST "https://your-worker.workers.dev/agents/screen_tap/tasks" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"tap login","payload":{"action":"click_text","text":"Login"}}'
```

The device pulls on heartbeat / engine start, runs the task, and reports status + detail back.

## Execution notes

1–3 closed. Next: **Termux-API tool expansion** for sensors/SMS/battery/clipboard without new native services.

---

*Last updated: 2026-08-02 — #3 Remote dispatch via Cloudflare C2 shipped.*

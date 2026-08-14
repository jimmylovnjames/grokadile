# Grokadile Capability Priority Order

Ordered by **leverage / unlock sequence**. Execute top-down.  
Effort estimates assume single high-velocity prompt+build cycles on phone + Termux + Fable/Grok.

| # | Move | Effort | Leverage | Status |
|---|------|--------|----------|--------|
| 1 | **Screen-reading agent (accessibility)** | Medium | **Unlocks everything** | ✅ **LANDED** |
| 2 | **ScreenTap / UI Automator agent** | Medium | Full phone control | ✅ **LANDED** |
| 3 | **Remote dispatch via Cloudflare C2** | Low | Control from anywhere | ✅ **LANDED** |
| 4 | **Termux-API tool expansion** | Low | Massive capability jump | ✅ **LANDED** |
| 5 | **SchedulerAgent with cron triggers** | Low | Autonomous scheduled execution | ✅ **LANDED** |
| 6 | **NotificationListenerAgent** | Medium | Reactive real-world triggers | next |
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
- **Termux agent (`termux/grokadile.py` v0.9) with expanded Termux-API tools** ✅
  - battery, clipboard get/set, location, wifi info, device info
  - sensor list/read, volume, torch, brightness
  - existing notify + TTS retained
  - 14 unit tests covering success, missing-api, validation, and error paths
- **SchedulerAgent (`scheduler`) — interval + 5-field cron** ✅
  - Enqueues any target agent on a schedule; re-arms itself
  - Pure-Kotlin cron next-fire (no extra deps); maxRuns / enabled guards
  - Unit tests for interval, cron matching, validation, termination
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

# Enqueue a daily 08:00 screen dump via the scheduler
curl -X POST "https://your-worker.workers.dev/agents/scheduler/tasks" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "daily morning dump",
    "payload": {
      "targetAgentId": "screen_reader",
      "targetTitle": "Morning hierarchy",
      "targetPayload": "{\"mode\":\"hierarchy\"}",
      "schedule": { "type": "cron", "expression": "0 8 * * *" }
    },
    "priority": "NORMAL"
  }'
```

The device pulls on heartbeat / engine start, runs the task, and reports status + detail back.

## Termux-API quick examples (on-device)

```bash
# After pkg install termux-api
python grokadile.py --goal "Check battery percentage and speak it"
python grokadile.py --goal "Read clipboard, then set it to the current time"
python grokadile.py --goal "Get my approximate location via network provider"
python grokadile.py --goal "List sensors and read the light sensor once"
```

## Execution notes

1–5 closed. Next: **NotificationListenerAgent** for reactive real-world triggers.

---

*Last updated: 2026-08-15 — #5 SchedulerAgent with interval + cron triggers shipped + unit tests.*

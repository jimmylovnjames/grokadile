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
| 6 | **NotificationListenerAgent** | Medium | Reactive real-world triggers | ✅ **LANDED** |
| 7 | **Swarm coordination (multi-device)** | Low | Scale to a phone farm | next |
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
- **SchedulerAgent (`scheduler`) with cron + interval triggers** ✅
  - Fires any registered agent on a recurring schedule
  - 5-field cron (`minute hour dom month dow`) with `*`, lists, ranges, steps
  - Interval mode (`intervalMillis`) for simple periodic work
  - Optional `maxFires` hard stop; durable fire state in agent memory
  - Self-rearming via `Task.scheduledAt` (no extra WorkManager needed)
  - Unit tests for interval/cron paths + pure CronNext math
- Foreground service + WorkManager heartbeat + boot receiver ✅
- Siri-style voice activation + dashboard command chat ✅
- **NotificationListenerAgent (`notification_listener`)** ✅
  - Reads active/recent notifications via NotificationListenerService
  - Filter by package / title / text
  - Reaction rules: register → poll_and_react enqueues target agents (deduped)
  - Pair with SchedulerAgent for near-real-time reactive loops
  - Unit tests for list/match/rules/poll/dedupe

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

## SchedulerAgent quick examples

Seed a schedule by enqueuing a task for agent id `scheduler`:

```bash
# Every day at 09:00 local — ask Grok for a morning brief
curl -X POST "https://your-worker.workers.dev/agents/scheduler/tasks" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Morning brief schedule",
    "payload": {
      "scheduleId": "morning-brief",
      "type": "cron",
      "expression": "0 9 * * *",
      "targetAgentId": "grok.chat",
      "targetTitle": "Morning brief",
      "targetPayload": "{\"prompt\":\"Give me a concise morning briefing.\"}"
    }
  }'
```

## NotificationListenerAgent quick examples

```bash
# List active notifications
curl -X POST "https://your-worker.workers.dev/agents/notification_listener/tasks" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"list notifs","payload":{"mode":"list","limit":20}}'

# Register OTP reaction rule
curl -X POST "https://your-worker.workers.dev/agents/notification_listener/tasks" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{
    "title":"register otp rule",
    "payload":{
      "mode":"register_rule",
      "ruleId":"bank-otp",
      "packageFilter":"bank",
      "textContains":"code",
      "targetAgentId":"grok.chat",
      "targetTitle":"OTP received",
      "targetPayload":"{\"prompt\":\"Extract the one-time code and say it clearly.\"}",
      "targetPriority":"HIGH"
    }
  }'

# Poll rules (pair with scheduler every 30s for near-real-time)
curl -X POST "https://your-worker.workers.dev/agents/notification_listener/tasks" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"poll react","payload":{"mode":"poll_and_react"}}'
```

## Termux-API quick examples (on-device)

```bash
python grokadile.py --goal "Check battery percentage and speak it"
```

## Execution notes

1–6 closed. Next: **Swarm coordination (multi-device)** to scale across a phone farm.

---

*Last updated: 2026-08-15 — #6 NotificationListenerAgent shipped (list/match/rules/poll + unit tests).*

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
| 8 | **Vector memory** | High | Long-term intelligence | next |

## Current baseline

1–7 closed including multi-device swarm:
- DeviceIdentity + worker `/devices` registry + heartbeat
- Device-targeted pull (`?device_id=`) and claim tracking
- Enqueue targets: `any` | `all` (broadcast) | specific device
- SwarmAgent (`swarm`): whoami / list / heartbeat / broadcast / dispatch

## Swarm quick examples

```bash
curl -H "Authorization: Bearer $TOKEN" "https://your-worker.workers.dev/devices"

curl -X POST "https://your-worker.workers.dev/agents/screen_reader/tasks" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"farm hierarchy","payload":{"mode":"hierarchy"},"target":"all","priority":"HIGH"}'

curl -X POST "https://your-worker.workers.dev/agents/echo/tasks" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"ping","payload":{},"target_device_id":"android-XXXXXXXX"}'
```

On-device: agent `swarm` with `{"mode":"list"}` or `{"mode":"broadcast","targetAgentId":"screen_reader",...}`.

After deploy: `cd cloudflare && npm run db:init` to apply devices table + targeting columns.

## Execution notes

1–7 closed. Next: **Vector memory** for long-term intelligence.

*Last updated: 2026-08-15 — #7 Swarm coordination shipped.*

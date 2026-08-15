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

## Planner + device tools (#9)

Grokadile 0.3.0 — the original 1–8 list is closed; this wave composes them.

- **PlannerAgent** (`planner`) — Grok returns a JSON step list; only catalogued agent ids are enqueued
- **GrokChatAgent** — optional RAG from `vector_memory`, then remembers the Q&A
- **ClipboardAgent** (`clipboard`) — `get` · `set` · `clear`
- **AppLaunchAgent** (`app_launch`) — `launch` · `list` · `find`
- **DeviceHealthAgent** (`device_health`) — `status` · `retry_failed` · `prune`
- Share target + Quick Settings autonomy tile + voice commands for the new agents

### Examples

```bash
curl -X POST "$WORKER/agents/planner/tasks" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"brief","payload":{"goal":"Check device health then remember a one-line status"}}'

curl -X POST "$WORKER/agents/clipboard/tasks" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"copy","payload":{"mode":"set","text":"4821"}}'

curl -X POST "$WORKER/agents/app_launch/tasks" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"maps","payload":{"mode":"launch","query":"Maps"}}'

curl -X POST "$WORKER/agents/device_health/tasks" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"vitals","payload":{"mode":"status"}}'
```

Voice / chat: “remember the gate code is 4821”, “recall gate code”, “plan a morning brief”,
“open Maps”, “what’s on the clipboard”, “device health”, “retry failed tasks”.

Share a note into Grokadile to remember it. Prefix with `plan:` to run the planner.

## Execution notes

1–9 closed. Next leverage is vision (screenshot → Grok) or tighter tool-calling loops.

*Last updated: 2026-08-15 — #9 Planner + device tools shipped (0.3.0).*

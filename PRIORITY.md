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

## Vector memory (#8)

On-device semantic memory — no network required.

- `HashingEmbeddingEncoder` — 256-dim offline hashing (tokens + bigrams + trigrams), L2-normalized
- Room table `vector_memory` (DB v2) + `VectorMemoryRepository`
- **VectorMemoryAgent** (`vector_memory`): `remember` · `search` · `forget` · `stats` · `clear`
- Unit tests: VectorMath, embedding ranking, agent roundtrip

### Examples

```bash
curl -X POST "$WORKER/agents/vector_memory/tasks" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"note","payload":{"mode":"remember","text":"Bank OTP channel is SMS only","source":"policy"}}'

curl -X POST "$WORKER/agents/vector_memory/tasks" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"recall","payload":{"mode":"search","query":"how do bank codes arrive","limit":5}}'
```

## Execution notes

1–8 closed. Original priority list complete.

*Last updated: 2026-08-15 — #8 Vector memory shipped.*

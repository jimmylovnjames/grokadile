# Grokadile

A Grok-powered autonomous Android agent platform. Grokadile runs a queue of
agent "tasks" on-device through a persistent foreground service, talks to the
Grok (xAI) API directly or via a Cloudflare Worker proxy, and is built to be
extended with new agent logic by adding a single class.

## Architecture

Clean, layered, and modular — UI → domain → data, with a runtime engine in the
middle that drives pluggable agents.

```
com.grokadile
├── core/                 cross-cutting: logging, crash handling, Result, dispatchers
│   ├── common/           AppResult / AppError, DispatcherProvider
│   └── logging/          GrokLogger (Logcat + Room), CrashHandler
├── domain/               pure Kotlin: no Android, no frameworks
│   ├── model/            Task, LogEntry, AgentMemoryEntry, Chat, AgentSettings
│   ├── agent/            Agent, AgentContext, AgentResult, AgentRegistry  ← plugin API
│   └── repository/       Task/Log/Memory/Grok/Settings repository interfaces
├── data/                 implementations
│   ├── local/            Room: entities, DAOs, GrokadileDatabase, mappers
│   ├── remote/           Retrofit/OkHttp: GrokApi, CloudflareApi, DTOs, AuthInterceptor
│   └── repository/       repository impls
├── agent/
│   ├── runtime/          OrchestrationEngine, AgentRegistry, AgentContext factory
│   ├── builtin/          EchoAgent, GrokChatAgent, HeartbeatAgent  ← example plugins
│   └── AgentController    UI-facing facade (start/stop autonomy, enqueue)
├── service/              AgentForegroundService, AccessibilityService, Boot receiver, notifications
├── worker/               WorkManager heartbeat (resilience net) + scheduler
├── permission/           PermissionManager + PermissionType (overlay/battery/notifications/a11y)
├── di/                   Hilt modules (Core, Database, Network, Repository, Agent)
└── ui/                   Compose + Material3: theme, navigation, Dashboard/Tasks/Logs/Settings
```

### How it runs

1. The user enables autonomy (Dashboard toggle) → `AgentController` persists the
   preference, starts `AgentForegroundService`, and schedules the WorkManager heartbeat.
2. `OrchestrationEngine` runs a single dispatch loop: it atomically **claims** the
   highest-priority runnable `Task` from Room, builds a scoped `AgentContext`, and
   runs it on the matching `Agent` with **bounded concurrency** (a `Semaphore`).
3. The `AgentResult` is mapped back to task state: `Success` → done, `Retry` →
   re-queued with **exponential backoff + jitter**, `Failure` → terminal.
4. The foreground notification reflects live engine/queue state. WorkManager + the
   boot receiver + `START_STICKY` re-arm everything after process death or reboot.

### Resilience & logging

- Every agent/system event is written through `GrokLogger` to **both Logcat and
  Room**, surfaced on the in-app Logs screen and trimmed by the heartbeat worker.
- `CrashHandler` persists uncaught exceptions **synchronously** before the process
  dies, so crashes are visible after restart.

## Tech stack

| Concern        | Choice |
|----------------|--------|
| UI             | Jetpack Compose + Material 3 (dynamic color) |
| DI             | Hilt (KSP) |
| Local store    | Room (tasks, logs, agent memory) + Preferences DataStore |
| Background     | Foreground Service (`specialUse`) + WorkManager |
| Networking     | Retrofit + OkHttp + kotlinx.serialization |
| Concurrency    | Kotlin Coroutines / Flow |
| Min / target   | SDK 26 / 35 |

## Adding an agent (the extension point)

Agents are the unit of capability. To add one:

```kotlin
@Singleton
class WeatherAgent @Inject constructor(/* deps */) : Agent {
    override val descriptor = AgentDescriptor(
        id = "weather",
        name = "Weather",
        description = "Fetches the forecast and remembers it.",
        capabilities = setOf(AgentCapability.NETWORK),
    )

    override suspend fun execute(task: Task, context: AgentContext): AgentResult {
        // context.grok, context.memory, context.logger, context.enqueue(...) available
        return AgentResult.success("done")
    }
}
```

Then register it with one line in `di/AgentModule.kt`:

```kotlin
@Binds @IntoSet abstract fun bindWeatherAgent(agent: WeatherAgent): Agent
```

That's it — the registry, orchestrator, queue, retry/backoff, logging, and UI
listing all pick it up automatically.

## Configuration

Secrets are read from `local.properties` or environment variables at build time
(never committed). All have safe defaults so the project builds without them.

```properties
# local.properties
GROK_BASE_URL=https://api.x.ai/
CLOUDFLARE_BASE_URL=https://your-worker.workers.dev/
```

The Grok API key is set at **runtime** in the Settings screen (stored in DataStore,
excluded from backup). When proxying through a Cloudflare Worker that holds the key
server-side, no on-device key is needed.

## Build

```bash
./gradlew assembleDebug          # build the debug APK
./gradlew testDebugUnitTest      # run unit tests
```

CI (`.github/workflows/build.yml`) sets up the Android SDK, runs the unit tests,
assembles the debug APK, and uploads it as an artifact on every push/PR.

## Permissions

Requested/managed from the Settings screen via `PermissionManager`:

- **Notifications** (runtime, API 33+) — required for the foreground service.
- **Display over other apps** — optional, for an on-screen agent HUD.
- **Ignore battery optimization** — optional, helps long-running agents survive Doze.
- **Accessibility service** — optional, only for agents that observe/act on screen.

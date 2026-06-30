# Grokadile Agent Runner

Host-side, **state-machine-driven** screen-acting agent. It observes the screen,
asks Grok for the *next single action*, validates that action against the real
screen (anti-hallucination), executes it (ADB on Android, Playwright on desktop),
verifies the result, and loops — coordinated by the Grokadile Cloudflare Worker.

> Status: **core loop landed and tested.** The platform adapters (ADB/Playwright
> observer+executor, OCR, Grok reasoner, Worker WebSocket client) are the next
> layer and plug into the `ports.ts` interfaces.

## Why a state machine (not an LLM loop)

`src/stateMachine.ts` is the deterministic core:

```
init → observe → plan → validate → act → settle → verify → observe → … → done
                          │  │                         (step++, reset recoveries)
            recover ◄─────┘  └─ done / escalate
              │ (≤maxRecoveries) → observe
              └ (>maxRecoveries) → escalate
```

The LLM only feeds the `plan` step. Its proposal becomes an **event** that the
pure reducer accepts or rejects:

- **Grounding** (`groundAction`): a `tap`/`type` whose `elementId` isn't in the
  current observation is rejected → `recover`.
- **Safety** (`SafetyGate`): destructive actions (delete/purchase/send/…) are
  blocked unless `allowDestructive` → `recover`.
- **Failure/empty/error**: → `recover`; exhausting `maxRecoveries` → `escalate`
  (human fallback). `maxSteps` bounds the whole run.

So a hallucinating or stuck model can never drive the device off the rails.

## Layout

```
src/
  stateMachine.ts   pure FSM (states, events, reducer)   ← tested
  agentLoop.ts      drives the FSM via the ports          ← tested
  ports.ts          Observer · Executor · Reasoner · Verifier · Telemetry
  safety.ts         destructive-action gate               ← tested
  verifier.ts       screen-change progress check
  replayBuffer.ts   bounded failure-replay buffer
  contract.ts       action/safety types (mirror of cloudflare/src/contract.ts)
  types.ts          Observation / ObservedElement / RunnerConfig
  logger.ts         structured JSON logger
test/               state machine · safety · end-to-end loop (with fakes)
```

## Implementing the adapters (next layer)

Each port is a small interface to implement:

| Port | Android | Desktop |
|---|---|---|
| `Observer` | `adb exec-out screencap` + `uiautomator dump` → elements (+ OCR fallback) | Playwright accessibility snapshot + screenshot |
| `Executor` | `adb shell input tap/swipe/text`, keyevents | Playwright `click/fill/press` |
| `Reasoner` | Grok via the Worker `POST /v1/chat/completions` (strict JSON action) | same |
| `Telemetry` | Worker WebSocket `/v1/agent/connect` (status/observation/action/result) | same |

Grounding uses real UIAutomator ids/bounds, so the model targets actual nodes —
not guessed coordinates.

## Example task

Created via the Worker (`POST /v1/agent/tasks`):

```json
{ "agentId": "pixel-7",
  "goal": "In Settings, enable Battery Saver",
  "target": { "platform": "android", "deviceId": "emulator-5554" },
  "constraints": { "maxSteps": 12, "allowDestructive": false, "timeoutSec": 120 },
  "skills": ["android.settings"] }
```

## Develop

```bash
cd agent-runner
npm install
npm run typecheck
npm test
```

Requirements for live runs (once adapters land): a host with `adb` + a connected
device/emulator (Android) and/or a desktop for Playwright, plus the Worker URL +
`APP_AUTH_TOKEN`.

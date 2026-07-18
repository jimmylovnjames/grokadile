# Grokadile Termux Core (v0.10)

Your phone as a person. Single-file AI companion + autonomous agent for
Android (Termux) + Grok 4.5 (or any OpenAI-compatible LLM endpoint):
talk to it out loud, it talks back, remembers who you are across sessions,
and goes off to do real work on the phone mid-conversation via a
JSON-action ReAct loop with streaming, tool safety guards, and demo mode.

## Quickstart (Termux on Android)

```bash
pkg update -y && pkg install -y python git termux-api
cd ~/grokadile/termux   # or copy files
bash setup.sh
export GROK_API_KEY="your_xai_key_here"
export GROK_MODEL="grok-4.5"

# Companion mode - talk to your phone (voice in/out via termux-api)
python grokadile.py --voice

# Same conversation, typed
python grokadile.py --chat

# One-shot task mode: edit ~/grokadile/goal.txt (created on first run), then
python grokadile.py

# Or pass the goal directly
python grokadile.py --goal "List files in current dir and create a note with today's date"

# Offline demo (no API key) — validates the whole loop
python grokadile.py --demo --goal "smoke test"

# Inspect persistent state
python grokadile.py --state
```

- State persists in `~/grokadile/state/state.json`; logs in `~/grokadile/logs/`.
- `CF_WORKER_BASE=https://your-worker.workers.dev` enables the `cf_call` tool
  (pairs with the Cloudflare Worker backend in `../cloudflare/`).

## Companion mode

`--chat` / `--voice` turn Grokadile from a task runner into someone you talk
to. Each exchange the model decides three things: what to **say** back, what
to **remember** about you (name, preferences, projects — stored in a profile
that survives restarts and greets you by name next launch), and whether you
just asked for a **task** — in which case it hands the goal to the autonomous
ReAct engine, does the work, and tells you the result out loud. Voice input
uses `termux-speech-to-text` and falls back to typing if unavailable; say
"bye" to leave. Works offline with `--demo`.

## Tools available to the agent

| Tool | Purpose |
|---|---|
| `shell` | Safe shell commands (30s timeout, destructive patterns blocked) |
| `read_file` / `write_file` | File I/O restricted to `$HOME` |
| `list_dir` / `grep` | Directory listing and recursive regex search |
| `http_get` / `http_post` | Public HTTP requests |
| `memory_retrieve` | Keyword search over persistent facts |
| `cf_call` | Call your Cloudflare Worker control plane |
| `python_exec` | Restricted Python eval (math/json/lists only) |
| `swarm_status` | Detect other Grokadile instances |
| `termux_notify` / `termux_tts` | Android notifications and voice output (termux-api) |

## Testing

```bash
python -m unittest test_grokadile -v
```

No network or API key needed: the suite runs the demo loop end-to-end and
exercises the real streaming path against a local mock SSE server (delta
accumulation, early JSON parse, `stream: true` payload). CI runs this on
every push touching `termux/` (`.github/workflows/termux.yml`).

## Safety notes

- Shell tool: 30s timeout, output capture only, destructive command patterns
  blocked. Never point it at anything you can't afford to lose anyway.
- FS tools are restricted to paths under `$HOME`. No root, no system changes.
- `python_exec` blocks imports, dunder access, and I/O builtins.
- API keys are read from env and never logged.

## Architecture

- `grokadile.py` — single-file agent: ReAct/JSON loop, streaming LLM client,
  13 tools, persistent state + facts memory, metrics, demo mode.
- `setup.sh` — idempotent Termux bootstrap.
- `test_grokadile.py` — regression suite (parsing, demo loop, streaming loop
  via mock SSE server, tool safety guards).
- Companions: Kotlin Android app (`../app/`) and Cloudflare Worker backend
  (`../cloudflare/`).

## Version history

- v0.10 — companion mode (`--chat` / `--voice`): ongoing conversation with
  voice in/out, persistent profile memory (remembers your name and facts
  across sessions), and mid-conversation task dispatch to the ReAct engine.
- v0.9 — fixed five bugs that broke every execution path (missing `main()`
  entrypoint, generator-poisoned `call_llm`, missing `stream: true`,
  early-exit accepting partial buffers, `NameError` in CLI); added
  regression test suite + CI.
- v0.8 — goal.txt support, first-run auto setup, `termux_tts`.
- v0.7 — streaming deltas wired into the ReAct loop with early JSON parse.
- v0.6 — streaming LLM client.
- v0.5 — `termux_notify`, auto tool-error recovery.
- v0.4 — `python_exec`, `swarm_status`, metrics, packaging.
- v0.3 — `memory_retrieve`, `cf_call`.
- v0.2 — `list_dir`, `grep`, `http_post`, LLM retry/backoff.
- v0.1 — core ReAct loop, 4 tools, demo mode, persistent state.

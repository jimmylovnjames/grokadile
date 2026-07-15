# Grokadile v0.1

Autonomous AI agent toolbox for Android (Termux) + Grok 4.5 (or compatible LLM).

**Core module priority (INFERRED from standard reliable agent framework dependencies):**

1. Environment bootstrap + launcher (setup.sh, dirs, deps)
2. Core autonomous loop + tool dispatch + JSON action parsing (grokadile.py)
3. Safe tool registry and executors (shell/fs/net with timeouts + path guards)
4. Persistent memory / state layer (json files + in-prompt retrieval)
5. Configurable LLM client (Grok API + multi-model fallback + demo mode)
6. Swarm / multi-agent coordinator hooks (future extension point)

This deliverable implements priorities 1 and 2 as a single runnable package. Higher priorities are stubbed for extension.

## Quickstart (Termux on Android)

```bash
# On your phone in Termux
pkg update -y && pkg install -y python git
cd ~/grokadile/termux   # or copy files
bash setup.sh
export GROK_API_KEY="your_xai_key_here"
export GROK_MODEL="grok-4.5"
python grokadile.py --goal "List files in current dir and create a test note.txt with timestamp"
```

- Use `--demo` for offline testing (no API key needed, simulates loop).
- State persists in `state/state.json`.
- Logs in `logs/`.
- Edit `grokadile.py` constants for custom paths or add tools.

## Safety & Notes (GUESSED for Termux constraints)
- Shell tool runs with 30s timeout, captures output only. Never run destructive cmds without review.
- FS tools restricted to user-writable paths under $HOME by default.
- No root. No system modification.
- API calls use HTTPS; key never logged.
- For production revenue agents (ARAS/HustleForge integration) add auth + rate limits in v0.2.
- This is foundation code. Extend tool registry and prompt for your specific workflows.

## Architecture (one file for v0.1 portability)
- setup.sh: idempotent Termux env bootstrap.
- grokadile.py: single-file agent with ReAct/JSON loop, 4 core tools, file state, demo mode.
- Future: split into package, add Durable Objects sync, swarm via Cloudflare Workers.

Built for high-velocity iteration on phone. Run, observe logs/state, refine prompt/tools.

## Next (after validation)
Implement priority 3 (expanded safe tools + better parsing) then 4 (vector memory stub or simple fact index).
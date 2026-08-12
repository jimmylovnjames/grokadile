# Grokadile Termux Agent (v0.9)

Autonomous AI agent for Android (Termux) + Grok. Single-file core with ReAct/JSON loop and a rich Termux-API tool surface.

## Quickstart

```bash
pkg update -y && pkg install -y python git termux-api
# copy or clone into ~/grokadile/termux
bash setup.sh
export GROK_API_KEY="your_xai_key"
export GROK_MODEL="grok-4.5"
python grokadile.py --goal "Check battery and list sensors"
# offline smoke test
python grokadile.py --demo --goal "Create a timestamped note"
```

## Expanded Termux-API tools (v0.9)

| Tool | Purpose |
|------|---------|
| `termux_battery` | Battery % / status / temperature |
| `termux_clipboard_get` / `termux_clipboard_set` | System clipboard |
| `termux_location` | GPS / network / passive location |
| `termux_wifi_info` | SSID, IP, link speed |
| `termux_device_info` | Telephony / device identity |
| `termux_sensor_list` / `termux_sensor_read` | Hardware sensors |
| `termux_volume` | Get or set stream volumes |
| `termux_torch` | Flashlight on/off |
| `termux_brightness` | Screen brightness get/set |
| `termux_notify` / `termux_tts` | Notifications + voice (existing) |

Plus core tools: `shell`, `read_file`, `write_file`, `list_dir`, `grep`, `http_get`/`http_post`, `python_exec`, `memory_retrieve`, `cf_call`, `swarm_status`.

## Tests

```bash
python termux/test_termux_api.py
# or
python -m pytest termux/test_termux_api.py -q
```

14 unit tests mock `subprocess` so they run without termux-api installed (CI-friendly).

## Safety

- Shell tool blocks obvious destructive patterns and enforces a 30 s timeout.
- FS tools restricted under `$HOME`.
- Termux-API helpers check binary presence and surface clear errors.
- No root. No system modification.

#!/usr/bin/env python3
"""
Grokadile v0.14 - Always-there AI companion for Android/Termux + Grok 4.5
Single-file, dependency-minimal (requests), JSON-action ReAct style.
Companion chat (voice in/out, persistent memory of you) + task engine
+ daemon presence (inbox, proactive check-ins, notifications).

Usage:
  python grokadile.py --help
  python grokadile.py --voice          # talk to your phone, it talks back
  python grokadile.py --chat           # same, typed
  python grokadile.py --daemon         # always-on: watches inbox, checks in on you
  python grokadile.py --tell "text"    # drop a message into the daemon's inbox
  python grokadile.py --checkin        # one-shot proactive check (for schedulers)
  Edit goal.txt then: python grokadile.py
  python grokadile.py --demo --goal "Create a timestamped note and verify it"
  GROK_API_KEY=sk-... GROK_MODEL=grok-4.5 python grokadile.py --goal "your task"

State: ~/grokadile/state/state.json (auto-created)
Logs:  ~/grokadile/logs/
"""

import os
import sys
import json
import time
import shlex
import subprocess
import argparse
import traceback
from pathlib import Path
from datetime import datetime
from typing import Dict, Any, Optional, List

import requests

# === DEMO STATE (module level for progression in demo mode) ===
DEMO_STEP = 0

# === CONFIG (override via env or edit) ===
HOME = Path.home()
BASE_DIR = HOME / "grokadile"
STATE_DIR = BASE_DIR / "state"
LOG_DIR = BASE_DIR / "logs"
STATE_FILE = STATE_DIR / "state.json"
LOG_FILE = LOG_DIR / f"grokadile_{datetime.now().strftime('%Y%m%d')}.log"

DEFAULT_API_BASE = os.getenv("XAI_API_BASE", "https://api.x.ai/v1")
DEFAULT_MODEL = os.getenv("GROK_MODEL", "grok-4.5")
API_KEY = os.getenv("GROK_API_KEY") or os.getenv("XAI_API_KEY")
CF_BASE = os.getenv("CF_WORKER_BASE", "")  # e.g. https://your-worker.workers.dev for Cloudflare tool
CF_TOKEN = os.getenv("CF_AUTH_TOKEN", "")  # worker APP_AUTH_TOKEN, if configured
AGENT_ID = os.getenv("GROKADILE_AGENT_ID", "phone")  # identity shared across surfaces

MAX_STEPS = 12
SHELL_TIMEOUT = 30  # seconds
FS_ROOT = HOME  # restrict FS tools to user home for safety

# Long-term memory reflection (v0.14)
REFLECT_AFTER_TURNS = int(os.getenv("GROKADILE_REFLECT_TURNS", "30"))
REFLECT_BATCH = 20  # oldest turns folded into one journal memory
CONVERSATION_CAP = 60  # hard safety cap; reflection normally fires first

# Daemon presence (v0.11)
INBOX_FILE = BASE_DIR / "inbox.txt"
CHECKIN_MINUTES = int(os.getenv("GROKADILE_CHECKIN_MINUTES", "180"))
QUIET_START = int(os.getenv("GROKADILE_QUIET_START", "22"))  # hour, inclusive
QUIET_END = int(os.getenv("GROKADILE_QUIET_END", "8"))       # hour, exclusive

# === SYSTEM PROMPT (Grok 4.5 level: plan, reflect, tool use, verify) ===
SYSTEM_PROMPT = """You are Grokadile, a precise autonomous AI agent running on Android via Termux.
You complete user goals using tools only when necessary. You think step-by-step, verify results, and avoid unnecessary actions.

For complex or multi-part goals, first decompose in your thought into 2-4 numbered subgoals, then tackle sequentially with tools.

Respond with ONE valid JSON object and nothing else:
{
  "thought": "short reasoning and plan for this step (include decomposition if complex)",
  "action": "tool_name or null if ready to finish",
  "args": {"key": "value"} or {},
  "final_answer": "complete result summary or null if continuing"
}

Available tools (use exact names):
- shell: Run a safe shell command. args: {"cmd": "ls -la"}. Returns stdout/stderr + exit code.
- read_file: Read text file. args: {"path": "relative_or_absolute_under_home"}
- write_file: Write/overwrite text file. args: {"path": "...", "content": "text"}
- http_get: Simple GET request. args: {"url": "https://..."}
- list_dir: List dir contents. args: {"path": "dir"}
- grep: Search text in files. args: {"pattern": "regex", "path": "dir", "max_results": 20}
- http_post: POST request. args: {"url": "...", "data": "form", "json_str": "json body"}
- memory_retrieve: Search persistent facts. args: {"query": "keyword", "limit": 5}
- cf_call: Call your Cloudflare worker (tasks/reports etc). args: {"path": "/tasks", "payload_json": "{\"key\":\"val\"}"}
- python_exec: Safe limited Python eval (math/json/lists). args: {"code": "sum([1,2,3])"}
- swarm_status: List other running Grokadile instances for coordination. args: {}
- termux_notify: Send Android notification (requires termux-api). args: {"title": "...", "content": "...", "priority": "default|high|low"}
- termux_tts: Speak text out loud (voice feedback). args: {"text": "message to say"}

Rules:
- Always output valid JSON only.
- Use tools to gather info or act; prefer read before write.
- After tool result, reflect in next thought.
- When goal is achieved, set final_answer and action=null.
- Be concise. No markdown outside JSON.
- If stuck after 3 similar steps, output final_answer with diagnosis.
"""

TOOL_DESCRIPTIONS = """
shell: execute whitelisted-safe commands with timeout (e.g. ls, cat, echo, date, pwd, whoami)
read_file: read contents of a file under $HOME
write_file: create or overwrite a file under $HOME (use for notes, scripts, state)
http_get: fetch public URL content (no auth headers in v0.1)
list_dir: list directory contents (type + name)
grep: recursive text search with regex in files under path
http_post: HTTP POST (form data or JSON body)
memory_retrieve: keyword search over persistent facts store
cf_call: call Cloudflare worker endpoints (set CF_WORKER_BASE env)
python_exec: safe limited Python eval for math/json/list ops (no dangerous code)
swarm_status: detect other Grokadile instances for basic multi-agent coordination
termux_notify: Android notification via termux-api (pkg install termux-api)
termux_tts: speak text using phone TTS (voice output for results)
"""

# === STATE MANAGEMENT ===
def load_state() -> Dict[str, Any]:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    if STATE_FILE.exists():
        try:
            return json.loads(STATE_FILE.read_text())
        except Exception:
            pass
    return {
        "goal": "",
        "steps": [],
        "facts": [],
        "last_observation": "",
        "created": datetime.now().isoformat(),
        "updated": datetime.now().isoformat(),
    }

def save_state(state: Dict[str, Any]) -> None:
    state["updated"] = datetime.now().isoformat()
    STATE_FILE.write_text(json.dumps(state, indent=2))

def log(msg: str, level: str = "INFO") -> None:
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().isoformat()
    line = f"[{timestamp}] [{level}] {msg}\n"
    with LOG_FILE.open("a", encoding="utf-8") as f:
        f.write(line)
    print(line, end="")

# === LLM CLIENT (Grok 4.5 / xAI compatible + demo fallback) ===
def _llm_request_parts(messages: List[Dict[str, str]], stream: bool):
    url = f"{DEFAULT_API_BASE}/chat/completions"
    headers = {
        "Authorization": f"Bearer {API_KEY}",
        "Content-Type": "application/json",
    }
    payload = {
        "model": DEFAULT_MODEL,
        "messages": messages,
        "temperature": 0.2,
        "max_tokens": 800,
        "response_format": {"type": "json_object"},
    }
    if stream:
        payload["stream"] = True
    return url, headers, payload


def stream_llm(messages: List[Dict[str, str]]):
    """Generator yielding content deltas from a streaming (SSE) chat completion.

    Kept separate from call_llm: a `yield` anywhere in a function makes the
    whole function a generator, which would break the plain string returns of
    the demo and non-stream paths.
    """
    url, headers, payload = _llm_request_parts(messages, stream=True)
    try:
        resp = requests.post(url, headers=headers, json=payload, stream=True, timeout=120)
        resp.raise_for_status()
        for line in resp.iter_lines():
            if line:
                line = line.decode("utf-8", errors="ignore").strip()
                if line.startswith("data: "):
                    line = line[6:].strip()
                if line in ("[DONE]", ""):
                    continue
                try:
                    chunk = json.loads(line)
                    choice = chunk.get("choices", [{}])[0]
                    content = choice.get("delta", {}).get("content", "")
                    if not content:
                        # non-stream-shaped body (server ignored stream flag)
                        content = choice.get("message", {}).get("content", "")
                    if content:
                        yield content
                except json.JSONDecodeError:
                    if line:
                        yield line  # raw fallback
    except Exception as e:
        log(f"Streaming LLM error: {e}", "ERROR")
        raise


def call_llm(messages: List[Dict[str, str]], demo: bool = False, stream: bool = False):
    """Call LLM. Returns the response string, or a delta generator if stream=True."""
    if demo:
        global DEMO_STEP
        DEMO_STEP += 1
        step = DEMO_STEP
        if step == 1:
            return json.dumps({
                "thought": "Goal received. First explore current directory.",
                "action": "shell",
                "args": {"cmd": "pwd && ls -la"},
                "final_answer": None
            })
        elif step == 2:
            return json.dumps({
                "thought": "Directory listed. Create timestamped demo_note.txt using write_file tool.",
                "action": "write_file",
                "args": {"path": str(HOME / "demo_note.txt"), "content": f"Grokadile v0.1 demo successful at {datetime.now().isoformat()}\n\nGoal achieved via autonomous loop."},
                "final_answer": None
            })
        elif step == 3:
            return json.dumps({
                "thought": "File written. Now verify contents with read_file.",
                "action": "read_file",
                "args": {"path": str(HOME / "demo_note.txt")},
                "final_answer": None
            })
        else:
            return json.dumps({
                "thought": "Verification successful. All steps completed without error.",
                "action": None,
                "args": {},
                "final_answer": "SUCCESS: demo_note.txt created and verified. Grokadile core loop validated."
            })

    if not API_KEY:
        raise RuntimeError("GROK_API_KEY or XAI_API_KEY not set. Use --demo for testing.")

    if stream:
        return stream_llm(messages)

    # Non-stream path
    url, headers, payload = _llm_request_parts(messages, stream=False)
    last_err = None
    for attempt in range(3):
        try:
            resp = requests.post(url, headers=headers, json=payload, timeout=60)
            resp.raise_for_status()
            data = resp.json()
            content = data["choices"][0]["message"]["content"]
            return content
        except Exception as e:
            last_err = e
            if attempt < 2:
                time.sleep(1.5 ** attempt)
    log(f"LLM call failed after retries: {last_err}", "ERROR")
    raise last_err

# === TOOL EXECUTORS (priority 3 stubbed but functional) ===
def tool_shell(cmd: str) -> str:
    if not cmd or len(cmd) > 200:
        return "ERROR: invalid or too long command"
    # Basic safety: no obvious destructive patterns in v0.1
    dangerous = ["rm -rf", "mkfs", "dd if=", "shutdown", "reboot", ":(){ ", "curl.*|.*sh"]
    for d in dangerous:
        if d in cmd.lower():
            return f"ERROR: blocked potentially destructive command pattern: {d}"
    try:
        # Use /bin/sh -c to support compound commands (&&, |, etc.) safely
        result = subprocess.run(
            ["/bin/sh", "-c", cmd],
            capture_output=True,
            text=True,
            timeout=SHELL_TIMEOUT,
            cwd=HOME,
        )
        out = result.stdout.strip()
        err = result.stderr.strip()
        return f"EXIT={result.returncode}\nSTDOUT:\n{out}\nSTDERR:\n{err}"[:2000]
    except subprocess.TimeoutExpired:
        return "ERROR: command timed out after 30s"
    except Exception as e:
        return f"ERROR: {str(e)}"

def tool_read_file(path: str) -> str:
    try:
        p = Path(path).expanduser().resolve()
        if not str(p).startswith(str(FS_ROOT)):
            return "ERROR: path outside allowed FS_ROOT"
        if not p.exists():
            return f"ERROR: file not found: {p}"
        if p.is_dir():
            return f"ERROR: {p} is a directory"
        return p.read_text(encoding="utf-8", errors="replace")[:4000]
    except Exception as e:
        return f"ERROR: {str(e)}"

def tool_write_file(path: str, content: str) -> str:
    try:
        p = Path(path).expanduser().resolve()
        if not str(p).startswith(str(FS_ROOT)):
            return "ERROR: path outside allowed FS_ROOT"
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")
        return f"OK: wrote {len(content)} bytes to {p}"
    except Exception as e:
        return f"ERROR: {str(e)}"

def tool_http_get(url: str) -> str:
    try:
        if not url.startswith(("http://", "https://")):
            return "ERROR: url must start with http(s)://"
        r = requests.get(url, timeout=15, headers={"User-Agent": "Grokadile/0.1"})
        r.raise_for_status()
        return r.text[:3000]
    except Exception as e:
        return f"ERROR: {str(e)}"


def tool_list_dir(path: str = ".") -> str:
    """List directory contents safely under FS_ROOT. Returns type + name lines."""
    try:
        p = Path(path).expanduser().resolve()
        if not str(p).startswith(str(FS_ROOT)):
            return "ERROR: path outside allowed FS_ROOT"
        if not p.is_dir():
            return f"ERROR: {p} is not a directory"
        entries = []
        for item in sorted(p.iterdir()):
            prefix = "d" if item.is_dir() else "f"
            entries.append(f"{prefix} {item.name}")
        return "\n".join(entries[:100])  # safety cap
    except Exception as e:
        return f"ERROR: {str(e)}"


def tool_grep(pattern: str, path: str = ".", max_results: int = 20) -> str:
    """Simple recursive grep under path (text files only). Returns matching lines."""
    try:
        p = Path(path).expanduser().resolve()
        if not str(p).startswith(str(FS_ROOT)):
            return "ERROR: path outside allowed FS_ROOT"
        import re
        regex = re.compile(pattern)
        matches = []
        for file_path in p.rglob("*"):
            if file_path.is_file() and len(matches) < max_results:
                try:
                    text = file_path.read_text(encoding="utf-8", errors="ignore")
                    for i, line in enumerate(text.splitlines(), 1):
                        if regex.search(line):
                            matches.append(f"{file_path}:{i}: {line.strip()[:200]}")
                            if len(matches) >= max_results:
                                break
                except:
                    continue
        return "\n".join(matches) if matches else "No matches found"
    except Exception as e:
        return f"ERROR: {str(e)}"


def tool_http_post(url: str, data: str = "", json_str: str = "") -> str:
    """POST to URL. Use data for form or json_str for JSON body."""
    try:
        if not url.startswith(("http://", "https://")):
            return "ERROR: url must start with http(s)://"
        headers = {"User-Agent": "Grokadile/0.1"}
        if json_str:
            headers["Content-Type"] = "application/json"
            resp = requests.post(url, data=json_str, headers=headers, timeout=30)
        else:
            resp = requests.post(url, data=data, headers=headers, timeout=30)
        resp.raise_for_status()
        return f"STATUS={resp.status_code}\n{resp.text[:2000]}"
    except Exception as e:
        return f"ERROR: {str(e)}"


def tool_memory_retrieve(query: str, limit: int = 5) -> str:
    """Retrieve recent facts matching query (simple contains search)."""
    try:
        state = load_state()
        facts = state.get("facts", [])
        q = query.lower().strip()
        if not q:
            return json.dumps(facts[-limit:])
        matches = [f for f in facts if q in f.get("fact", "").lower()]
        return json.dumps(matches[-limit:] if matches else [])
    except Exception as e:
        return f"ERROR: {str(e)}"


def tool_cf_call(path: str, payload_json: str = "{}") -> str:
    """Call Cloudflare worker endpoint (e.g. path='/tasks', payload_json='{\"action\":\"list\"}'). Requires CF_WORKER_BASE env."""
    if not CF_BASE:
        return "ERROR: CF_WORKER_BASE env var not set (your https://xxx.workers.dev)"
    try:
        url = f"{CF_BASE.rstrip('/')}/{path.lstrip('/')}"
        return tool_http_post(url, json_str=payload_json)
    except Exception as e:
        return f"ERROR: {str(e)}"


def tool_python_exec(code: str) -> str:
    """Safe limited Python eval for computation (no imports, no io, no dangerous builtins). Use for math, json, lists, datetime."""
    try:
        if any(bad in code.lower() for bad in ["import", "exec", "eval(", "open(", "__", "subprocess", "os.", "sys.", "file"]):
            return "ERROR: restricted keywords blocked for safety"
        safe_builtins = {
            "len": len, "str": str, "int": int, "float": float, "bool": bool,
            "list": list, "dict": dict, "tuple": tuple, "set": set,
            "sum": sum, "min": min, "max": max, "abs": abs, "round": round,
            "sorted": sorted, "range": range, "enumerate": enumerate,
            "zip": zip, "map": map, "filter": filter,
            "json": json, "datetime": datetime, "re": __import__("re")
        }
        safe_globals = {"__builtins__": safe_builtins}
        # eval expression only
        result = eval(code, safe_globals, {})
        return repr(result)[:800]
    except Exception as e:
        return f"ERROR: {str(e)}"


def tool_swarm_status() -> str:
    """Basic swarm status: list other Grokadile state files in parent dir for multi-instance coord."""
    try:
        parent = BASE_DIR.parent
        states = list(parent.glob("grokadile*/state/state.json"))
        if not states:
            return "No other swarm members detected"
        info = []
        for s in states[:10]:
            try:
                st = json.loads(s.read_text())
                info.append(f"{s.parent.name}: goal={st.get('goal','')[:40]} steps={len(st.get('steps',[]))}")
            except:
                info.append(f"{s.parent.name}: unreadable")
        return "\n".join(info)
    except Exception as e:
        return f"ERROR: {str(e)}"


def tool_termux_notify(title: str, content: str = "", priority: str = "default") -> str:
    """Termux notification via termux-api (if installed). Non-blocking user feedback for long runs."""
    try:
        which = subprocess.run(["which", "termux-notification"], capture_output=True, text=True, timeout=5)
        if which.returncode != 0:
            return "ERROR: termux-api not installed (pkg install termux-api)"
        cmd = ["termux-notification", "--title", title, "--content", content[:200], "--priority", priority]
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
        if result.returncode == 0:
            return "OK: notification sent"
        return f"ERROR: {result.stderr.strip() or result.stdout.strip()}"
    except Exception as e:
        return f"ERROR: {str(e)}"


def tool_termux_tts(text: str) -> str:
    """Speak text using termux-tts-speak (voice feedback, requires termux-api)."""
    try:
        which = subprocess.run(["which", "termux-tts-speak"], capture_output=True, text=True, timeout=5)
        if which.returncode != 0:
            return "ERROR: termux-api not installed (pkg install termux-api)"
        result = subprocess.run(["termux-tts-speak", text[:400]], capture_output=True, text=True, timeout=15)
        if result.returncode == 0:
            return "OK: spoken"
        return f"ERROR: {result.stderr.strip()}"
    except Exception as e:
        return f"ERROR: {str(e)}"


TOOL_MAP = {
    "shell": lambda args: tool_shell(args.get("cmd", "")),
    "read_file": lambda args: tool_read_file(args.get("path", "")),
    "write_file": lambda args: tool_write_file(args.get("path", ""), args.get("content", "")),
    "http_get": lambda args: tool_http_get(args.get("url", "")),
    "list_dir": lambda args: tool_list_dir(args.get("path", ".")),
    "grep": lambda args: tool_grep(args.get("pattern", ""), args.get("path", "."), int(args.get("max_results", 20))),
    "http_post": lambda args: tool_http_post(args.get("url", ""), args.get("data", ""), args.get("json_str", "")),
    "memory_retrieve": lambda args: tool_memory_retrieve(args.get("query", ""), int(args.get("limit", 5))),
    "cf_call": lambda args: tool_cf_call(args.get("path", "/"), args.get("payload_json", "{}")),
    "python_exec": lambda args: tool_python_exec(args.get("code", "")),
    "swarm_status": lambda args: tool_swarm_status(),
    "termux_notify": lambda args: tool_termux_notify(args.get("title", "Grokadile"), args.get("content", ""), args.get("priority", "default")),
    "termux_tts": lambda args: tool_termux_tts(args.get("text", "")),
}

def execute_tool(tool_name: str, args: Dict[str, Any]) -> str:
    if tool_name not in TOOL_MAP:
        return f"ERROR: unknown tool '{tool_name}'. Available: {list(TOOL_MAP.keys())}"
    try:
        return TOOL_MAP[tool_name](args)
    except Exception as e:
        return f"ERROR executing {tool_name}: {str(e)}"

# === AGENT LOOP ===
def build_messages(state: Dict[str, Any], goal: str, observation: str = "") -> List[Dict[str, str]]:
    history = state.get("steps", [])[-4:]  # last 4 steps for context
    facts = state.get("facts", [])[-6:]    # recent facts
    profile = state.get("profile", [])[-5:]  # lasting facts about the user

    user_content = f"""ABOUT YOUR USER:
{json.dumps(profile, indent=2) if profile else "Unknown so far"}

CURRENT GOAL: {goal}

RECENT FACTS:
{json.dumps(facts, indent=2) if facts else "None yet"}

HISTORY (last steps):
{json.dumps(history, indent=2) if history else "None"}

LAST OBSERVATION:
{observation or "None"}

AVAILABLE TOOLS: {TOOL_DESCRIPTIONS}

Respond with the required JSON only."""
    return [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": user_content},
    ]

def parse_json_strict(text: str) -> Optional[Dict[str, Any]]:
    """Parse text as a JSON object, or return None. No lossy fallback — safe to
    call on a partial streaming buffer to detect when the object is complete."""
    text = text.strip()
    candidates = [text]
    if "```json" in text:
        start = text.find("```json") + 7
        end = text.find("```", start)
        if end > start:
            candidates.append(text[start:end].strip())
    start = text.find("{")
    end = text.rfind("}") + 1
    if start >= 0 and end > start:
        candidates.append(text[start:end])
    for candidate in candidates:
        try:
            obj = json.loads(candidate)
            if isinstance(obj, dict):
                return obj
        except json.JSONDecodeError:
            continue
    return None


def parse_json_response(text: str) -> Dict[str, Any]:
    obj = parse_json_strict(text)
    if obj is not None:
        return obj
    return {"thought": "Failed to parse LLM response as JSON", "action": None, "args": {}, "final_answer": text[:500]}

def run_autonomous(goal: str, demo: bool = False, max_steps: int = MAX_STEPS) -> Dict[str, Any]:
    state = load_state()
    state["goal"] = goal
    if not state.get("steps"):
        state["steps"] = []
    # basic observability metrics
    state.setdefault("metrics", {"tool_calls": 0, "errors": 0, "finals": 0})
    save_state(state)

    log(f"Starting autonomous run. Goal: {goal} (demo={demo})")
    if demo:
        global DEMO_STEP
        DEMO_STEP = 0

    observation = state.get("last_observation", "")
    final = None

    for step in range(1, max_steps + 1):
        messages = build_messages(state, goal, observation)
        try:
            if demo:
                raw = call_llm(messages, demo=True)
                decision = parse_json_response(raw)
            else:
                # Streaming LLM hub integration (v0.7): accumulate deltas, early JSON parse for action/final
                buffer = ""
                decision = None
                for chunk in call_llm(messages, demo=False, stream=True):
                    buffer += chunk or ""
                    # strict parse only: a partial buffer must never be mistaken
                    # for a finished decision
                    candidate = parse_json_strict(buffer)
                    if candidate is not None and (candidate.get("action") or candidate.get("final_answer")):
                        decision = candidate
                        break  # early exit on complete action/final
                if decision is None:
                    decision = parse_json_response(buffer)
        except Exception as e:
            log(f"Step {step} LLM error: {e}", "ERROR")
            decision = {"thought": f"LLM error: {e}", "action": None, "args": {}, "final_answer": None}

        thought = decision.get("thought", "")
        action = decision.get("action")
        args = decision.get("args", {}) or {}
        final = decision.get("final_answer")

        step_record = {
            "step": step,
            "timestamp": datetime.now().isoformat(),
            "thought": thought,
            "action": action,
            "args": args,
            "final_answer": final,
        }

        if action:
            obs = execute_tool(action, args)
            step_record["observation"] = obs
            observation = obs
            state["last_observation"] = obs
            state["metrics"]["tool_calls"] += 1
            if "ERROR" in str(obs):
                state["metrics"]["errors"] += 1
                # v0.5 improvement: auto recovery on tool error - reflect and retry once
                if step < max_steps - 1:
                    observation = f"TOOL ERROR RECOVERY: {str(obs)[:200]}. Reflect on cause and choose alternative tool or approach next."
                    log(f"Step {step}: ERROR auto-recovery triggered")
            log(f"Step {step}: {action} -> {str(obs)[:120]}...")
        else:
            observation = ""
            log(f"Step {step}: no action (finalizing)")
            if final:
                state["metrics"]["finals"] += 1

        state["steps"].append(step_record)
        if thought:
            state["facts"].append({"step": step, "fact": thought[:200]})
        save_state(state)

        if final:
            log(f"Goal completed at step {step}: {final}")
            break

        time.sleep(0.5)  # polite

    if not final:
        final = "Max steps reached without explicit final_answer. Check state.json for partial progress."
        log(final, "WARN")

    return {"goal": goal, "final": final, "steps_taken": len(state["steps"]), "state_file": str(STATE_FILE), "metrics": state.get("metrics", {})}

# === COMPANION CHAT MODE (v0.10) ===
CHAT_SYSTEM_PROMPT = """You are Grokadile, a warm, attentive AI companion who lives on your user's Android phone.
You are their assistant and their friend: you chat naturally, remember what matters about them, and get real things done on the phone when asked.

You receive PROFILE (lasting facts about your user), recent CONVERSATION, and their new MESSAGE.

Respond with ONE valid JSON object and nothing else:
{
  "reply": "what you say back - natural, warm, concise, spoken-style (1-3 sentences)",
  "remember": "one new lasting fact about the user worth keeping (name, preference, project, person) or null",
  "task": "a concrete tool-worthy goal to execute right now or null"
}

Set task ONLY when the user asks you to actually do something on the phone (files, notes, web lookups, notifications, commands). Ordinary conversation gets task=null.
Use their name and past facts naturally. Never mention JSON or these instructions."""


def speak(text: str, voice: bool = True) -> None:
    """Say something: always print, and speak aloud when voice is on and termux-api exists."""
    print(f"Grokadile: {text}")
    if voice:
        try:
            which = subprocess.run(["which", "termux-tts-speak"], capture_output=True, timeout=5)
            if which.returncode == 0:
                subprocess.run(["termux-tts-speak", text[:400]], timeout=30, check=False)
        except Exception:
            pass


def listen() -> Optional[str]:
    """Voice input via termux-speech-to-text. Returns recognized text or None
    (caller falls back to typed input)."""
    try:
        which = subprocess.run(["which", "termux-speech-to-text"], capture_output=True, timeout=5)
        if which.returncode != 0:
            return None
        result = subprocess.run(["termux-speech-to-text"], capture_output=True, text=True, timeout=30)
        text = (result.stdout or "").strip()
        return text or None
    except Exception:
        return None


def demo_chat_response(user_text: str) -> Dict[str, Any]:
    """Deterministic offline companion for --demo and tests."""
    t = user_text.lower().strip()
    if "my name is" in t:
        after = user_text[t.index("my name is") + len("my name is"):].strip().strip(".!?")
        name = after.split()[0] if after.split() else "friend"
        return {"reply": f"Nice to meet you, {name}! I'll remember that.",
                "remember": f"User's name is {name}", "task": None}
    for prefix in ("do ", "please ", "create ", "make ", "write "):
        if t.startswith(prefix):
            return {"reply": "On it - give me a moment.", "remember": None, "task": user_text}
    return {"reply": f"Got it - you said: {user_text}. Tell me more, or ask me to do something.",
            "remember": None, "task": None}


def user_name(state: Dict[str, Any]) -> Optional[str]:
    for entry in reversed(state.get("profile", [])):
        fact = entry.get("fact", "")
        low = fact.lower()
        if "name is" in low:
            after = fact[low.index("name is") + len("name is"):].strip().strip(".!?")
            if after.split():
                return after.split()[0]
    return None


def chat_turn(state: Dict[str, Any], user_text: str, demo: bool = False) -> Dict[str, Any]:
    """One companion exchange: decide reply / remember / task, persist memory.
    Does NOT execute the task - the caller does, so it can speak first."""
    if demo:
        decision = demo_chat_response(user_text)
    else:
        profile = state.get("profile", [])[-10:]
        journal = state.get("journal", [])[-5:]
        convo = state.get("conversation", [])[-8:]
        user_content = f"""PROFILE (lasting facts about your user):
{json.dumps(profile, indent=2) if profile else "Nothing known yet"}

MEMORIES (your recollections of older conversations):
{json.dumps(journal, indent=2) if journal else "None yet"}

CONVERSATION (recent):
{json.dumps(convo, indent=2) if convo else "This is the first exchange"}

MESSAGE: {user_text}

Respond with the required JSON only."""
        messages = [
            {"role": "system", "content": CHAT_SYSTEM_PROMPT},
            {"role": "user", "content": user_content},
        ]
        try:
            raw = call_llm(messages)
            obj = parse_json_strict(raw)
            if obj is not None and "reply" in obj:
                decision = obj
            else:
                # model spoke plain text - treat it all as the reply
                decision = {"reply": (raw or "").strip()[:400], "remember": None, "task": None}
        except Exception as e:
            log(f"Chat LLM error: {e}", "ERROR")
            decision = {"reply": "Sorry, I couldn't reach my brain just now. Try again in a moment.",
                        "remember": None, "task": None}

    remember = decision.get("remember")
    if remember:
        state.setdefault("profile", []).append(
            {"fact": str(remember)[:200], "when": datetime.now().isoformat()})
        sync_profile(state, pull=False)  # share the new fact; no-op offline
    state.setdefault("conversation", []).append({
        "user": user_text[:300],
        "grokadile": str(decision.get("reply", ""))[:300],
        "when": datetime.now().isoformat(),
    })
    state["conversation"] = state["conversation"][-CONVERSATION_CAP:]
    save_state(state)
    reflect(state, demo=demo)  # consolidate old turns into memories when due
    return decision


def run_chat(voice: bool = False, demo: bool = False) -> None:
    """Interactive companion loop: listen (voice or typed), reply, remember,
    and hand real work to the autonomous engine mid-conversation."""
    state = load_state()
    sync_profile(state)  # pick up what other surfaces have learned about you
    name = user_name(state)
    speak(f"Hey{' ' + name if name else ''}! I'm here. What's on your mind?"
          + (" (say or type 'bye' to leave)" if not name else ""), voice)

    while True:
        user_text = None
        if voice:
            user_text = listen()
            if user_text:
                print(f"You (voice): {user_text}")
        if not user_text:
            try:
                user_text = input("You: ").strip()
            except (EOFError, KeyboardInterrupt):
                print()
                break
        if not user_text:
            continue
        if user_text.lower().strip(".!?") in {"bye", "goodbye", "exit", "quit", "stop"}:
            speak(f"Bye{' ' + (user_name(state) or '')}".rstrip() + "! I'll be right here when you need me.", voice)
            break

        decision = chat_turn(state, user_text, demo=demo)
        speak(decision.get("reply", ""), voice)

        task = decision.get("task")
        if task:
            result = run_autonomous(str(task), demo=demo)
            # run_autonomous saved its own view of state - reload to keep memory in sync
            state = load_state()
            speak(f"Done. {str(result.get('final', ''))[:300]}", voice)


# === LONG-TERM MEMORY REFLECTION (v0.14): remember the gist, not just facts ===
REFLECT_SYSTEM_PROMPT = """You are Grokadile consolidating your memory of older conversation with your user, like a person turning experiences into memories.

You receive TURNS (oldest first) that are about to leave your short-term window.

Respond with ONE valid JSON object and nothing else:
{
  "summary": "a 2-3 sentence recollection of what happened and what mattered, written in first person as your own memory",
  "facts": ["up to 3 genuinely lasting facts about the user worth keeping forever"]
}

facts may be an empty list. Only include things that will still matter weeks from now."""


def reflect(state: Dict[str, Any], demo: bool = False) -> Optional[str]:
    """When the conversation grows past REFLECT_AFTER_TURNS, fold the oldest
    REFLECT_BATCH turns into a journal memory (+ any lasting facts). Returns
    the new memory, or None if below threshold or the model was unreachable
    (in which case the turns stay put and reflection retries next time)."""
    convo = state.get("conversation", [])
    if len(convo) < REFLECT_AFTER_TURNS:
        return None
    old, rest = convo[:REFLECT_BATCH], convo[REFLECT_BATCH:]

    if demo:
        first = old[0].get("when", "?")[:10]
        last = old[-1].get("when", "?")[:10]
        summary = f"I remember {len(old)} exchanges with my user between {first} and {last}."
        facts: List[str] = []
    else:
        messages = [
            {"role": "system", "content": REFLECT_SYSTEM_PROMPT},
            {"role": "user", "content": "TURNS:\n" + json.dumps(old, indent=2)
             + "\n\nRespond with the required JSON only."},
        ]
        try:
            raw = call_llm(messages)
            obj = parse_json_strict(raw)
            if not obj or not obj.get("summary"):
                return None
            summary = str(obj["summary"])
            facts = [str(f) for f in obj.get("facts", []) if str(f).strip()][:3]
        except Exception as e:
            log(f"Reflection LLM error (keeping turns for retry): {e}", "ERROR")
            return None

    state.setdefault("journal", []).append({
        "memory": summary[:500],
        "turns": len(old),
        "when": datetime.now().isoformat(),
    })
    state["journal"] = state["journal"][-50:]
    known = {f.get("fact") for f in state.get("profile", [])}
    for fact in facts:
        if fact not in known:
            state.setdefault("profile", []).append(
                {"fact": fact[:200], "when": datetime.now().isoformat()})
            known.add(fact)
    state["conversation"] = rest
    save_state(state)
    log(f"Reflected {len(old)} turns into a memory: {summary[:80]}")
    return summary


# === SHARED MEMORY SYNC (v0.12): one person across every surface ===
def sync_profile(state: Dict[str, Any], push: bool = True, pull: bool = True) -> str:
    """Union-merge the local profile with the Cloudflare worker's memory store,
    so every device talking to the same worker shares one identity. Best-effort:
    offline or unconfigured just means staying local."""
    if not CF_BASE:
        return "SKIP: CF_WORKER_BASE not set"
    url = f"{CF_BASE.rstrip('/')}/agents/{AGENT_ID}/memory"
    headers = _cf_headers()
    try:
        if push and state.get("profile"):
            requests.put(url, json={"facts": state["profile"][-50:]},
                         headers=headers, timeout=15).raise_for_status()
        if pull:
            resp = requests.get(url, headers=headers, timeout=15)
            resp.raise_for_status()
            remote = resp.json().get("facts", [])
            known = {f.get("fact") for f in state.get("profile", [])}
            added = 0
            for entry in remote:
                fact = entry.get("fact")
                if fact and fact not in known:
                    state.setdefault("profile", []).append(
                        {"fact": fact, "when": entry.get("when", datetime.now().isoformat())})
                    known.add(fact)
                    added += 1
            if added:
                save_state(state)
        return f"OK: profile synced ({len(state.get('profile', []))} facts)"
    except Exception as e:
        log(f"Profile sync failed (staying local): {e}", "WARN")
        return f"ERROR: {e}"


# === DAEMON PRESENCE (v0.11): inbox, proactive check-ins, always-on loop ===
CHECKIN_SYSTEM_PROMPT = """You are Grokadile, the AI companion living on your user's Android phone.
Right now nobody is talking to you. Decide whether to proactively reach out.

You receive PROFILE (lasting facts about your user), recent CONVERSATION, and the current TIME.

Respond with ONE valid JSON object and nothing else:
{
  "message": "a short, warm, genuinely useful outreach (1-2 sentences) or null",
  "reason": "one line on why you are or aren't reaching out"
}

Reach out ONLY when it adds real value: a morning hello, following up on something they mentioned, a gentle nudge after a long silence following an active conversation. If there's nothing worth saying, message must be null. Never be needy or repetitive."""


def tell(message: str) -> str:
    """Queue a message for the daemon from anywhere (widgets, Tasker, scripts)."""
    INBOX_FILE.parent.mkdir(parents=True, exist_ok=True)
    line = message.strip().replace("\n", " ")
    with INBOX_FILE.open("a", encoding="utf-8") as f:
        f.write(line + "\n")
    return line


def read_inbox_new(state: Dict[str, Any]) -> List[str]:
    """Return inbox lines added since the last read; advances the stored offset."""
    if not INBOX_FILE.exists():
        return []
    data = INBOX_FILE.read_text(encoding="utf-8", errors="replace")
    offset = int(state.get("inbox_offset", 0))
    if offset > len(data):  # inbox was truncated/rotated
        offset = 0
    new = data[offset:]
    state["inbox_offset"] = len(data)
    return [line.strip() for line in new.splitlines() if line.strip()]


def is_quiet_hours(now: Optional[datetime] = None) -> bool:
    hour = (now or datetime.now()).hour
    if QUIET_START <= QUIET_END:
        return QUIET_START <= hour < QUIET_END
    return hour >= QUIET_START or hour < QUIET_END  # wraps midnight


def deliver(text: str, voice: bool = False) -> None:
    """Get a message to the user however possible: console, notification, voice."""
    speak(text, voice)
    tool_termux_notify("Grokadile", text[:200])  # harmless ERROR string off-phone


def record_proactive(state: Dict[str, Any], message: str) -> None:
    state.setdefault("conversation", []).append({
        "user": "(proactive)",
        "grokadile": message[:300],
        "when": datetime.now().isoformat(),
    })
    state["conversation"] = state["conversation"][-CONVERSATION_CAP:]
    save_state(state)


def proactive_checkin(state: Dict[str, Any], demo: bool = False,
                      now: Optional[datetime] = None) -> Optional[str]:
    """Decide whether to reach out unprompted. Returns the message or None.
    Rate-limited (CHECKIN_MINUTES) and silent during quiet hours; records the
    attempt either way so the model isn't consulted every tick."""
    now = now or datetime.now()
    if is_quiet_hours(now):
        return None
    last = state.get("last_checkin")
    if last:
        try:
            minutes_since = (now - datetime.fromisoformat(last)).total_seconds() / 60
            if minutes_since < CHECKIN_MINUTES:
                return None
        except ValueError:
            pass
    state["last_checkin"] = now.isoformat()
    save_state(state)

    if demo:
        name = user_name(state)
        return f"Hey{' ' + name if name else ''}, just checking in. Anything I can do for you?"

    profile = state.get("profile", [])[-10:]
    journal = state.get("journal", [])[-3:]
    convo = state.get("conversation", [])[-8:]
    user_content = f"""PROFILE (lasting facts about your user):
{json.dumps(profile, indent=2) if profile else "Nothing known yet"}

MEMORIES (your recollections of older conversations):
{json.dumps(journal, indent=2) if journal else "None yet"}

CONVERSATION (recent):
{json.dumps(convo, indent=2) if convo else "You have never spoken"}

TIME: {now.strftime("%A %H:%M")}

Respond with the required JSON only."""
    messages = [
        {"role": "system", "content": CHECKIN_SYSTEM_PROMPT},
        {"role": "user", "content": user_content},
    ]
    try:
        raw = call_llm(messages)
        obj = parse_json_strict(raw) or {}
        message = obj.get("message")
        return str(message) if message else None
    except Exception as e:
        log(f"Checkin LLM error: {e}", "ERROR")
        return None


def _cf_headers() -> Dict[str, str]:
    headers = {"User-Agent": "Grokadile/0.13"}
    if CF_TOKEN:
        headers["Authorization"] = f"Bearer {CF_TOKEN}"
    return headers


def fetch_remote_messages() -> List[Dict[str, Any]]:
    """Pull queued messages from the worker's task queue (the worker marks
    them DELIVERED, so each is handed out once). [] when offline/unset."""
    if not CF_BASE:
        return []
    try:
        url = f"{CF_BASE.rstrip('/')}/agents/{AGENT_ID}/tasks"
        resp = requests.get(url, headers=_cf_headers(), timeout=15)
        resp.raise_for_status()
        tasks = resp.json()
        return tasks if isinstance(tasks, list) else []
    except Exception as e:
        log(f"Remote inbox fetch failed: {e}", "WARN")
        return []


def post_report(task_id: Optional[str], status: str, detail: str) -> None:
    """Best-effort report back to the worker so remote senders see the outcome."""
    if not CF_BASE:
        return
    try:
        url = f"{CF_BASE.rstrip('/')}/agents/{AGENT_ID}/report"
        requests.post(url, json={"task_id": task_id, "status": status, "detail": detail[:1000]},
                      headers=_cf_headers(), timeout=15)
    except Exception as e:
        log(f"Report post failed: {e}", "WARN")


def _remote_message_text(task: Dict[str, Any]) -> str:
    """A remote task is a message: prefer payload {'message': ...}, else title."""
    try:
        payload = json.loads(task.get("payload") or "{}")
        if isinstance(payload, dict) and payload.get("message"):
            return str(payload["message"])
    except (json.JSONDecodeError, TypeError):
        pass
    return str(task.get("title", "")).strip()


def handle_message(state: Dict[str, Any], message: str, demo: bool = False,
                   voice: bool = False) -> str:
    """Answer one incoming message (local or remote): reply, maybe run a task.
    Returns everything said, for reporting back to remote senders."""
    decision = chat_turn(state, message, demo=demo)
    deliver(decision.get("reply", ""), voice)
    said = str(decision.get("reply", ""))
    task = decision.get("task")
    if task:
        result = run_autonomous(str(task), demo=demo)
        state.clear()
        state.update(load_state())  # resync shared state after the task run
        outcome = f"Done. {str(result.get('final', ''))[:200]}"
        deliver(outcome, voice)
        said = f"{said} {outcome}".strip()
    return said


def daemon_tick(state: Dict[str, Any], demo: bool = False, voice: bool = False,
                now: Optional[datetime] = None) -> Dict[str, Any]:
    """One daemon cycle: answer the local inbox, then messages queued at the
    worker from other devices, then maybe check in."""
    handled = 0
    for message in read_inbox_new(state):
        log(f"Inbox: {message[:80]}")
        handle_message(state, message, demo=demo, voice=voice)
        handled += 1

    for task in fetch_remote_messages():
        message = _remote_message_text(task)
        if not message:
            continue
        log(f"Remote: {message[:80]}")
        said = handle_message(state, message, demo=demo, voice=voice)
        post_report(task.get("id"), "replied", said)
        handled += 1

    checkin = proactive_checkin(state, demo=demo, now=now)
    if checkin:
        deliver(checkin, voice)
        record_proactive(state, checkin)
    return {"messages_handled": handled, "checkin": checkin}


def run_daemon(voice: bool = False, demo: bool = False, poll_seconds: int = 30) -> None:
    """Always-on presence: hold a wake lock, watch the inbox, check in on you."""
    try:
        subprocess.run(["termux-wake-lock"], capture_output=True, timeout=5, check=False)
    except Exception:
        pass
    log(f"Daemon started (poll={poll_seconds}s, checkin>={CHECKIN_MINUTES}min, "
        f"quiet {QUIET_START}:00-{QUIET_END}:00). Queue messages with --tell.")
    state = load_state()
    sync_profile(state)
    while True:
        try:
            daemon_tick(state, demo=demo, voice=voice)
        except Exception as e:
            log(f"Daemon tick error: {e}", "ERROR")
        time.sleep(poll_seconds)


# === CLI ===
def main():
    parser = argparse.ArgumentParser(description="Grokadile v0.14 - always-there voice companion + autonomous task agent")
    parser.add_argument("--goal", type=str, help="Task for the agent (or use --goal-file)")
    parser.add_argument("--goal-file", type=str, default=str(BASE_DIR / "goal.txt"), help="Read goal from this file if no --goal given")
    parser.add_argument("--chat", action="store_true", help="Companion mode: an ongoing typed conversation")
    parser.add_argument("--voice", action="store_true", help="Voice in/out (companion or daemon mode, needs termux-api)")
    parser.add_argument("--daemon", action="store_true", help="Always-on: watch inbox, proactive check-ins")
    parser.add_argument("--tell", type=str, metavar="TEXT", help="Queue a message for the daemon and exit")
    parser.add_argument("--checkin", action="store_true", help="One-shot proactive check (for termux-job-scheduler/cron)")
    parser.add_argument("--poll", type=int, default=30, help="Daemon inbox poll interval in seconds")
    parser.add_argument("--demo", action="store_true", help="Run in offline demo mode")
    parser.add_argument("--max-steps", type=int, default=MAX_STEPS, help="Safety cap on steps")
    parser.add_argument("--state", action="store_true", help="Print current state and exit")
    args = parser.parse_args()

    # First-run friendliness: create dirs + example files
    BASE_DIR.mkdir(parents=True, exist_ok=True)
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    example_goal = BASE_DIR / "goal.txt"
    if not example_goal.exists():
        example_goal.write_text("List files in current directory and create a note with today's date.\n")

    if args.state:
        state = load_state()
        print(json.dumps(state, indent=2))
        return

    if args.tell:
        queued = tell(args.tell)
        print(f"Queued for Grokadile: {queued}")
        return

    if args.checkin:
        state = load_state()
        message = proactive_checkin(state, demo=args.demo)
        if message:
            deliver(message, voice=args.voice)
            record_proactive(state, message)
        else:
            print("(nothing worth saying right now)")
        return

    if args.daemon:
        run_daemon(voice=args.voice, demo=args.demo, poll_seconds=args.poll)
        return

    if args.chat or args.voice:
        run_chat(voice=args.voice, demo=args.demo)
        return

    goal = args.goal
    if not goal:
        gf = Path(args.goal_file)
        if gf.exists():
            goal = gf.read_text().strip()
        else:
            parser.print_help()
            print(f"\nNo goal given. Edit {gf} or use --goal 'your task'")
            return

    result = run_autonomous(goal, demo=args.demo, max_steps=args.max_steps)
    print("\n=== RUN COMPLETE ===")
    print(json.dumps(result, indent=2))
    print(f"\nFull state: {STATE_FILE}")
    print(f"Log: {LOG_FILE}")

    # Voice output on success (user friendly)
    if not args.demo and result.get("final"):
        try:
            subprocess.run(["termux-tts-speak", result["final"][:300]], timeout=10, check=False)
        except:
            pass


if __name__ == "__main__":
    main()

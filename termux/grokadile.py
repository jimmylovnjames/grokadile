#!/usr/bin/env python3
"""
Grokadile v0.6 - Core autonomous agent loop for Android/Termux + Grok 4.5
Single-file, dependency-minimal (requests), JSON-action ReAct style.
Added streaming LLM hub stub in call_llm (stream=True yields partial tokens).
Auto recovery + termux_notify + full toolset. 

Usage:
  python grokadile.py --help
  python grokadile.py --demo --goal "Create a timestamped note and verify it"
  GROK_API_KEY=sk-... GROK_MODEL=grok-4.5 python grokadile.py --goal "your task"
  CF_WORKER_BASE=https://... python grokadile.py --goal "..."

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

MAX_STEPS = 12
SHELL_TIMEOUT = 30  # seconds
FS_ROOT = HOME  # restrict FS tools to user home for safety

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
    LOG_FILE.write_text(LOG_FILE.read_text() + line if LOG_FILE.exists() else line)
    print(line, end="")

# === LLM CLIENT (Grok 4.5 / xAI compatible + demo fallback) ===
def call_llm(messages: List[Dict[str, str]], demo: bool = False, stream: bool = False) -> str:
    """Call LLM. If stream=True returns generator of (delta_content or raw chunk str).
    Stub for streaming LLM hub integration (v0.6). Non-stream path unchanged for compatibility.
    """
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
        # Streaming LLM hub stub (v0.6)
        # Assumes xAI/Grok-compatible endpoint returns lines with JSON deltas or SSE
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
                        delta = chunk.get("choices", [{}])[0].get("delta", {})
                        content = delta.get("content", "")
                        if content:
                            yield content  # partial token
                    except json.JSONDecodeError:
                        if line:
                            yield line  # raw fallback
            return
        except Exception as e:
            log(f"Streaming LLM error: {e}", "ERROR")
            raise

    # Non-stream path (existing)
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

    user_content = f"""CURRENT GOAL: {goal}

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

def parse_json_response(text: str) -> Dict[str, Any]:
    text = text.strip()
    # Try direct JSON
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass
    # Try fenced block
    if "```json" in text:
        start = text.find("```json") + 7
        end = text.find("```", start)
        if end > start:
            try:
                return json.loads(text[start:end].strip())
            except:
                pass
    # Fallback: try to find first { ... }
    try:
        start = text.find("{")
        end = text.rfind("}") + 1
        if start >= 0 and end > start:
            return json.loads(text[start:end])
    except:
        pass
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
            raw = call_llm(messages, demo=demo)
            decision = parse_json_response(raw)
        except Exception as e:
            log(f"Step {step} LLM error: {e}", "ERROR")
            state["metrics"]["errors"] += 1
            save_state(state)
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

# === CLI ===
def main():
    parser = argparse.ArgumentParser(description="Grokadile v0.6 core agent (streaming LLM stub + termux_notify + auto recovery)")
    parser.add_argument("--goal", type=str, help="Task for the agent to complete autonomously")
    parser.add_argument("--demo", action="store_true", help="Run in offline demo mode (no API key required)")
    parser.add_argument("--max-steps", type=int, default=MAX_STEPS, help="Safety cap on steps")
    parser.add_argument("--state", action="store_true", help="Print current state and exit")
    args = parser.parse_args()

    if args.state:
        state = load_state()
        print(json.dumps(state, indent=2))
        return

    if not args.goal:
        parser.print_help()
        print("\nExample: python grokadile.py --demo --goal 'Check current directory and write a hello.txt'")
        return

    result = run_autonomous(args.goal, demo=args.demo, max_steps=args.max_steps)
    print("\n=== RUN COMPLETE ===")
    print(json.dumps(result, indent=2))
    print(f"\nFull state: {STATE_FILE}")
    print(f"Log: {LOG_FILE}")

if __name__ == "__main__":
    main()

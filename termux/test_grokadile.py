#!/usr/bin/env python3
"""Regression tests for grokadile.py (no network, no API key needed).

Run from this directory:  python -m unittest test_grokadile -v

Uses an isolated temp HOME and a local mock SSE server. The streaming tests
exist because demo mode alone cannot catch streaming bugs: v0.6-v0.8 shipped
with a generator-poisoned call_llm, a missing stream:true flag, and an
early-exit parser that accepted partial chunks as final answers.
"""
import json
import os
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

# HOME must be isolated before import: grokadile resolves its state/log paths
# at module level.
_TEST_HOME = tempfile.mkdtemp(prefix="grokadile-test-home-")
os.environ["HOME"] = _TEST_HOME

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import grokadile  # noqa: E402


class MockLLMHandler(BaseHTTPRequestHandler):
    """Serves scripted decisions as SSE deltas, split into small chunks so a
    partial buffer is never valid JSON — exercising the early-exit parser."""

    decisions = []
    calls = 0
    payloads = []

    def do_POST(self):
        cls = MockLLMHandler
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        cls.payloads.append(body)
        idx = min(cls.calls, len(cls.decisions) - 1)
        cls.calls += 1
        content = json.dumps(cls.decisions[idx])
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        third = max(1, len(content) // 3)
        for i in range(0, len(content), third):
            chunk = {"choices": [{"delta": {"content": content[i:i + third]}}]}
            self.wfile.write(f"data: {json.dumps(chunk)}\n\n".encode())
        self.wfile.write(b"data: [DONE]\n\n")

    def log_message(self, *args):
        pass


class GrokadileTestCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), MockLLMHandler)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        grokadile.API_KEY = "test-key"
        grokadile.DEFAULT_API_BASE = f"http://127.0.0.1:{cls.server.server_address[1]}/v1"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def setUp(self):
        if grokadile.STATE_FILE.exists():
            grokadile.STATE_FILE.unlink()
        MockLLMHandler.decisions = []
        MockLLMHandler.calls = 0
        MockLLMHandler.payloads = []


class TestParsing(GrokadileTestCase):
    def test_strict_accepts_complete_json(self):
        obj = grokadile.parse_json_strict('{"action": "shell", "args": {}}')
        self.assertEqual(obj, {"action": "shell", "args": {}})

    def test_strict_rejects_partial_buffer(self):
        # a truncated streaming buffer must not parse
        self.assertIsNone(grokadile.parse_json_strict('{"thought": "check dir", "action'))

    def test_strict_rejects_non_object(self):
        self.assertIsNone(grokadile.parse_json_strict("[1, 2, 3]"))
        self.assertIsNone(grokadile.parse_json_strict("plain text"))

    def test_strict_accepts_fenced_block(self):
        obj = grokadile.parse_json_strict('```json\n{"action": null}\n```')
        self.assertEqual(obj, {"action": None})

    def test_response_falls_back_on_garbage(self):
        obj = grokadile.parse_json_response("not json at all")
        self.assertIsNone(obj["action"])
        self.assertIn("not json", obj["final_answer"])


class TestCallLLM(GrokadileTestCase):
    def test_demo_returns_string_not_generator(self):
        grokadile.DEMO_STEP = 0
        raw = grokadile.call_llm([], demo=True)
        self.assertIsInstance(raw, str)
        self.assertIn("thought", json.loads(raw))


class TestDemoLoop(GrokadileTestCase):
    def test_demo_run_completes(self):
        result = grokadile.run_autonomous("demo goal", demo=True, max_steps=8)
        self.assertIn("SUCCESS", result["final"])
        self.assertTrue(grokadile.STATE_FILE.exists())


class TestStreamingLoop(GrokadileTestCase):
    def test_streaming_run_executes_tool_and_finishes(self):
        MockLLMHandler.decisions = [
            {"thought": "run a command", "action": "shell",
             "args": {"cmd": "echo streaming-ok"}, "final_answer": None},
            {"thought": "done", "action": None, "args": {},
             "final_answer": "STREAM FINAL"},
        ]
        result = grokadile.run_autonomous("mock goal", demo=False, max_steps=5)
        self.assertEqual(result["final"], "STREAM FINAL")
        self.assertGreaterEqual(MockLLMHandler.calls, 2)

    def test_streaming_payload_requests_stream(self):
        MockLLMHandler.decisions = [
            {"thought": "done", "action": None, "args": {}, "final_answer": "ok"},
        ]
        grokadile.run_autonomous("mock goal", demo=False, max_steps=2)
        self.assertTrue(MockLLMHandler.payloads)
        self.assertIs(MockLLMHandler.payloads[0].get("stream"), True)

    def test_partial_chunks_never_taken_as_final(self):
        # the final answer must arrive intact, not as a truncated buffer
        MockLLMHandler.decisions = [
            {"thought": "a long thought that streams in several chunks",
             "action": None, "args": {},
             "final_answer": "COMPLETE ANSWER WITH ENOUGH LENGTH TO SPAN CHUNKS"},
        ]
        result = grokadile.run_autonomous("mock goal", demo=False, max_steps=2)
        self.assertEqual(result["final"],
                         "COMPLETE ANSWER WITH ENOUGH LENGTH TO SPAN CHUNKS")


class TestToolSafety(GrokadileTestCase):
    def test_read_file_blocked_outside_home(self):
        self.assertIn("ERROR", grokadile.tool_read_file("/etc/passwd"))

    def test_write_file_blocked_outside_home(self):
        self.assertIn("ERROR", grokadile.tool_write_file("/etc/evil", "x"))

    def test_shell_blocks_destructive_patterns(self):
        self.assertIn("blocked", grokadile.tool_shell("rm -rf /"))

    def test_python_exec_blocks_imports(self):
        self.assertIn("ERROR", grokadile.tool_python_exec("import os"))

    def test_python_exec_allows_math(self):
        self.assertEqual(grokadile.tool_python_exec("sum([1, 2, 3])"), "6")

    def test_write_then_read_roundtrip(self):
        path = os.path.join(_TEST_HOME, "roundtrip.txt")
        self.assertIn("OK", grokadile.tool_write_file(path, "hello"))
        self.assertEqual(grokadile.tool_read_file(path), "hello")


if __name__ == "__main__":
    unittest.main(verbosity=2)

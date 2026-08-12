#!/usr/bin/env python3
"""
Unit tests for the Termux-API expansion in grokadile.py (v0.9).
Mocks subprocess so tests run on any host without termux-api installed.
"""
from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

# Prefer the just-built expanded module; fall back to repo layout after push.
CANDIDATES = [
    Path(__file__).resolve().parent / "grokadile.py",
    Path("/tmp/grokadile_v09.py"),
]
MOD = None
for cand in CANDIDATES:
    if cand.exists():
        spec = importlib.util.spec_from_file_location("grokadile", cand)
        MOD = importlib.util.module_from_spec(spec)
        sys.modules["grokadile"] = MOD
        spec.loader.exec_module(MOD)
        break
if MOD is None:
    raise SystemExit("Could not locate grokadile.py")


class TestTermuxApiExpansion(unittest.TestCase):
    def _mock_which_ok_then(self, stdout: str, returncode: int = 0, stderr: str = ""):
        return [
            MagicMock(returncode=0, stdout="termux-binary\n", stderr=""),
            MagicMock(returncode=returncode, stdout=stdout, stderr=stderr),
        ]

    @patch("subprocess.run")
    def test_battery_ok(self, mock_run):
        mock_run.side_effect = self._mock_which_ok_then('{"percentage":87,"status":"CHARGING"}')
        out = MOD.tool_termux_battery()
        self.assertIn("percentage", out)
        self.assertIn("87", out)

    @patch("subprocess.run")
    def test_missing_termux_api(self, mock_run):
        mock_run.return_value = MagicMock(returncode=1, stdout="", stderr="")
        out = MOD.tool_termux_battery()
        self.assertTrue(out.startswith("ERROR: termux-api not installed"))

    @patch("subprocess.run")
    def test_clipboard_get(self, mock_run):
        mock_run.side_effect = self._mock_which_ok_then("hello clipboard")
        self.assertEqual(MOD.tool_termux_clipboard_get(), "hello clipboard")

    def test_clipboard_set_requires_text(self):
        self.assertEqual(MOD.tool_termux_clipboard_set(""), "ERROR: text required")

    @patch("subprocess.run")
    def test_clipboard_set_ok(self, mock_run):
        mock_run.side_effect = self._mock_which_ok_then("")
        self.assertEqual(MOD.tool_termux_clipboard_set("payload"), "OK")

    def test_location_provider_validation(self):
        out = MOD.tool_termux_location("satellite")
        self.assertTrue(out.startswith("ERROR: provider must"))

    @patch("subprocess.run")
    def test_location_ok(self, mock_run):
        mock_run.side_effect = self._mock_which_ok_then('{"latitude":-36.85,"longitude":174.76}')
        out = MOD.tool_termux_location("network")
        self.assertIn("latitude", out)

    def test_sensor_read_requires_name(self):
        self.assertTrue(MOD.tool_termux_sensor_read("").startswith("ERROR: sensor name"))

    @patch("subprocess.run")
    def test_sensor_list(self, mock_run):
        mock_run.side_effect = self._mock_which_ok_then("accelerometer\ngyroscope\nlight")
        out = MOD.tool_termux_sensor_list()
        self.assertIn("accelerometer", out)

    def test_torch_invalid_state(self):
        self.assertTrue(MOD.tool_termux_torch("blink").startswith("ERROR: state must"))

    @patch("subprocess.run")
    def test_torch_on(self, mock_run):
        mock_run.side_effect = self._mock_which_ok_then("")
        self.assertEqual(MOD.tool_termux_torch("on"), "OK")

    @patch("subprocess.run")
    def test_volume_get(self, mock_run):
        mock_run.side_effect = self._mock_which_ok_then("music: 8\nring: 5")
        out = MOD.tool_termux_volume()
        self.assertIn("music", out)

    @patch("subprocess.run")
    def test_command_error_propagates(self, mock_run):
        mock_run.side_effect = self._mock_which_ok_then("", returncode=1, stderr="permission denied")
        out = MOD.tool_termux_wifi_info()
        self.assertTrue(out.startswith("ERROR:"))
        self.assertIn("permission denied", out)

    def test_tool_map_contains_new_tools(self):
        expected = {
            "termux_battery", "termux_clipboard_get", "termux_clipboard_set",
            "termux_location", "termux_wifi_info", "termux_device_info",
            "termux_sensor_list", "termux_sensor_read", "termux_volume",
            "termux_torch", "termux_brightness",
        }
        missing = expected - set(MOD.TOOL_MAP.keys())
        self.assertEqual(missing, set(), f"Missing from TOOL_MAP: {missing}")


if __name__ == "__main__":
    unittest.main(verbosity=2)

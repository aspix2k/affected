"""Tests for the IDE smoke check against a fake MCP server."""

from __future__ import annotations

import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import Any

from scripts import ide_smoke


class FakeMcp(BaseHTTPRequestHandler):
    """Answer the MCP calls the smoke check makes."""

    replies: dict[str, dict[str, Any]] = {}
    event_stream = False

    def do_POST(self) -> None:  # noqa: N802
        """Reply to one JSON-RPC message."""
        length = int(self.headers.get("Content-Length", "0"))
        message = json.loads(self.rfile.read(length))
        if message.get("method") == "initialize":
            result: dict[str, Any] | None = {"capabilities": {}}
        elif message.get("method") == "tools/call":
            result = self.replies[message["params"]["name"]]
            self.server.projects.append(message["params"]["arguments"]["projectPath"])  # type: ignore[attr-defined]
        else:
            result = None
        body = "" if result is None else json.dumps({"jsonrpc": "2.0", "id": message.get("id"), "result": result})
        payload = (f"event: message\ndata: {body}\n\n" if self.event_stream and body else body).encode("utf-8")
        self.send_response(200)
        self.send_header("Mcp-Session-Id", "session")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
        """Keep test output quiet."""


def tool(content: dict[str, Any], error: bool = False) -> dict[str, Any]:
    """Shape a tool result the way the IDE does."""
    return {"structuredContent": content, "content": [{"type": "text", "text": "text"}], "isError": error}


class IdeSmokeTest(unittest.TestCase):
    """Verify expectations, failures and transport handling."""

    def setUp(self) -> None:
        """Start a fake server on a free port."""
        FakeMcp.replies = {
            "affected_changed_files": tool({"files": ["core/Core.java"]}),
            "affected_verification_plan": tool({"tasks": [":core:test"]}),
            "affected_run_verification": tool({"passed": True}),
        }
        FakeMcp.event_stream = False
        self.server = HTTPServer(("127.0.0.1", 0), FakeMcp)
        self.server.projects = []  # type: ignore[attr-defined]
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def tearDown(self) -> None:
        """Stop the fake server."""
        self.server.shutdown()
        self.server.server_close()

    def test_matching_plan_passes_and_sends_the_project_path(self) -> None:
        """Accept the expected files and tasks and run the verification."""
        report = ide_smoke.check(self.port, "/repo", [":core:test"], ["core/Core.java"], run=True)

        self.assertEqual(["changed files: core/Core.java", "planned tasks: :core:test", "verification: passed"], report)
        self.assertEqual(["/repo", "/repo", "/repo"], self.server.projects)  # type: ignore[attr-defined]

    def test_event_stream_replies_are_decoded(self) -> None:
        """Read replies sent as server-sent events."""
        FakeMcp.event_stream = True

        self.assertEqual(0, ide_smoke.main(["--port", str(self.port), "--project", "/repo", "--task", ":core:test"]))

    def test_unexpected_task_fails(self) -> None:
        """Reject a plan that differs from the expectation."""
        FakeMcp.replies["affected_verification_plan"] = tool({"tasks": [":core:test", ":core:check"]})

        with self.assertRaisesRegex(ide_smoke.SmokeError, "planned tasks are"):
            ide_smoke.check(self.port, "/repo", [":core:test"], [], run=False)

    def test_tool_error_and_failed_run_fail(self) -> None:
        """Reject a tool error and a verification that did not pass."""
        FakeMcp.replies["affected_run_verification"] = tool({"passed": False})
        with self.assertRaisesRegex(ide_smoke.SmokeError, "did not pass"):
            ide_smoke.check(self.port, "/repo", [], [], run=True)

        FakeMcp.replies["affected_changed_files"] = tool({}, error=True)
        self.assertEqual(1, ide_smoke.main(["--port", str(self.port), "--project", "/repo"]))

    def test_unreachable_server_fails(self) -> None:
        """Report a closed port instead of raising."""
        self.server.shutdown()
        self.server.server_close()
        closed = self.port
        self.server = HTTPServer(("127.0.0.1", 0), FakeMcp)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

        self.assertEqual(1, ide_smoke.main(["--port", str(closed), "--project", "/repo"]))


if __name__ == "__main__":
    unittest.main()

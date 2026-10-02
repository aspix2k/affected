#!/usr/bin/env python3
"""Check Affected inside a running IDE through the IDE's MCP server."""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from typing import Any

PROTOCOL = "2025-03-26"
TIMEOUT_SECONDS = 900


class SmokeError(Exception):
    """Report an unreachable server, a failed tool or an unmet expectation."""


def post(port: int, session: str | None, payload: dict[str, Any]) -> tuple[str | None, dict[str, Any] | None]:
    """Send one JSON-RPC message and return the session id with the decoded reply."""
    headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
    if session:
        headers["Mcp-Session-Id"] = session
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}/stream",
        data=json.dumps(payload).encode("utf-8"),
        headers=headers,
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:  # noqa: S310
            body = response.read().decode("utf-8")
            session = response.headers.get("Mcp-Session-Id") or session
    except (urllib.error.URLError, OSError) as error:
        raise SmokeError(f"MCP server on port {port} is not reachable: {error}") from error
    return session, decode(body)


def decode(body: str) -> dict[str, Any] | None:
    """Read a plain JSON reply or the last data line of an event stream."""
    events = [line[5:].strip() for line in body.splitlines() if line.startswith("data:")]
    text = events[-1] if events else body.strip()
    return json.loads(text) if text.startswith("{") else None


def connect(port: int) -> str | None:
    """Open an MCP session and return its id."""
    session, reply = post(
        port,
        None,
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": PROTOCOL,
                "capabilities": {},
                "clientInfo": {"name": "affected-ide-smoke", "version": "1"},
            },
        },
    )
    if reply is None or "result" not in reply:
        raise SmokeError(f"MCP server on port {port} rejected initialize: {reply}")
    post(port, session, {"jsonrpc": "2.0", "method": "notifications/initialized"})
    return session


def call(port: int, session: str | None, tool: str, project: str) -> dict[str, Any]:
    """Call one Affected tool for the project and return its structured content."""
    _, reply = post(
        port,
        session,
        {
            "jsonrpc": "2.0",
            "id": 2,
            "method": "tools/call",
            "params": {"name": tool, "arguments": {"projectPath": project}},
        },
    )
    result = (reply or {}).get("result")
    if result is None:
        raise SmokeError(f"{tool} returned no result: {reply}")
    text = " ".join(item.get("text", "") for item in result.get("content", []))
    if result.get("isError"):
        raise SmokeError(f"{tool} failed: {text}")
    return result.get("structuredContent") or {"text": text}


def check(port: int, project: str, tasks: list[str], files: list[str], run: bool) -> list[str]:
    """Compare the live plan with the expectations and return the report lines."""
    session = connect(port)
    changed = call(port, session, "affected_changed_files", project).get("files", [])
    planned = call(port, session, "affected_verification_plan", project).get("tasks", [])
    report = [f"changed files: {', '.join(changed) or 'none'}", f"planned tasks: {', '.join(planned) or 'none'}"]
    if files and sorted(files) != sorted(changed):
        raise SmokeError(f"changed files are {changed}, expected {files}")
    if tasks and sorted(tasks) != sorted(planned):
        raise SmokeError(f"planned tasks are {planned}, expected {tasks}")
    if run:
        outcome = call(port, session, "affected_run_verification", project)
        if outcome.get("passed") is not True:
            raise SmokeError(f"verification did not pass: {outcome}")
        report.append("verification: passed")
    return report


def main(argv: list[str] | None = None) -> int:
    """Run the smoke check and print the report."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, required=True, help="port of the IDE MCP server")
    parser.add_argument("--project", required=True, help="absolute path of the project open in the IDE")
    parser.add_argument("--task", action="append", default=[], help="expected planned task; repeat for several")
    parser.add_argument("--file", action="append", default=[], help="expected changed file; repeat for several")
    parser.add_argument("--run", action="store_true", help="also run the verification and require it to pass")
    arguments = parser.parse_args(argv)
    try:
        report = check(arguments.port, arguments.project, arguments.task, arguments.file, arguments.run)
    except SmokeError as error:
        print(f"ide-smoke: ERROR: {error}", file=sys.stderr)
        return 1
    print("\n".join(report))
    return 0


if __name__ == "__main__":
    sys.exit(main())

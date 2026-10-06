"""Tests for invoking Gradle after the distribution is already cached."""

from __future__ import annotations

import os
import stat
import subprocess
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory


SCRIPT = Path(__file__).resolve().parents[1] / "run_gradle.sh"


class RunGradleTest(unittest.TestCase):
    """Fetch the zip once; retry only transient repository HTTP errors."""

    def test_runs_the_requested_tasks_once_when_gradle_succeeds(self) -> None:
        """A green task graph is not retried."""
        calls, completed = self.run_wrapper(
            """#!/usr/bin/env bash
set -euo pipefail
echo "$*" >> "$CALLS"
echo ran
exit 0
"""
        )
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual([":test"], calls)

    def test_does_not_retry_a_failed_task_graph(self) -> None:
        """Compilation and test failures still fail the job once."""
        calls, completed = self.run_wrapper(
            """#!/usr/bin/env bash
set -euo pipefail
echo "$*" >> "$CALLS"
echo "FAILURE: There were failing tests"
exit 1
"""
        )
        self.assertEqual(1, completed.returncode)
        self.assertEqual([":test"], calls)

    def test_retries_a_cache_redirector_bad_gateway_on_maven_central(self) -> None:
        """A 502 from the JetBrains mirror must not fail the required job."""
        calls, completed = self.run_wrapper(
            """#!/usr/bin/env bash
set -euo pipefail
echo "$* prefer=${AFFECTED_PREFER_MAVEN_CENTRAL:-0}" >> "$CALLS"
if [[ "${AFFECTED_PREFER_MAVEN_CENTRAL:-}" != 1 ]]; then
  echo "Could not GET 'https://cache-redirector.jetbrains.com/repo1.maven.org/maven2/okio-bom.pom'."
  echo "Received status code 502 from server: Bad Gateway"
  exit 1
fi
echo ran
exit 0
"""
        )
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual([":test prefer=0", ":test prefer=1"], calls)
        self.assertIn("Maven Central first", completed.stderr)

    def test_retries_a_maven_central_rate_limit_on_the_jetbrains_mirror(self) -> None:
        """A 429 from repo.maven.apache.org must not fail the required job."""
        calls, completed = self.run_wrapper(
            """#!/usr/bin/env bash
set -euo pipefail
echo "$* prefer=${AFFECTED_PREFER_MAVEN_CENTRAL:-0}" >> "$CALLS"
if [[ "${AFFECTED_PREFER_MAVEN_CENTRAL:-}" == 1 ]]; then
  echo "Unable to load Maven meta-data from https://repo.maven.apache.org/maven2/com/jetbrains/intellij/platform/test-framework/maven-metadata.xml."
  echo "Received status code 429 from server: Too Many Requests"
  exit 1
fi
echo ran
exit 0
""",
            env_updates={"AFFECTED_PREFER_MAVEN_CENTRAL": "1"},
        )
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual([":test prefer=1", ":test prefer=0"], calls)
        self.assertIn("cache-redirector first", completed.stderr)

    def test_a_silent_build_is_dumped_and_terminated(self) -> None:
        """A hung Gradle run leaves thread dumps and exits with 124."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            dumps = root / "dumps"
            tools = root / "tools"
            tools.mkdir()
            jcmd = tools / "jcmd"
            jcmd.write_text(
                """#!/usr/bin/env bash
if [[ "$1" == "-l" ]]; then
  echo "4242 org.gradle.Fake"
else
  echo "thread dump of $1"
fi
""",
                encoding="utf-8",
            )
            jcmd.chmod(jcmd.stat().st_mode | stat.S_IEXEC)
            calls, completed = self.run_wrapper(
                """#!/usr/bin/env bash
echo "$*" >> "$CALLS"
echo started
sleep 300
""",
                env_updates={
                    "AFFECTED_GRADLE_HANG_DIR": str(dumps),
                    "AFFECTED_GRADLE_IDLE_SECONDS": "1",
                    "AFFECTED_GRADLE_IDLE_POLL": "1",
                    "PATH": f"{tools}{os.pathsep}{os.environ['PATH']}",
                },
                cwd=root,
            )
            self.assertEqual(124, completed.returncode, completed.stderr)
            self.assertEqual([":test"], calls)
            self.assertIn("thread dump of 4242", (dumps / "threads-4242.txt").read_text(encoding="utf-8"))
            self.assertTrue((dumps / "processes.txt").exists())

    def test_a_chatty_build_is_not_terminated(self) -> None:
        """Output keeps the watchdog quiet."""
        with TemporaryDirectory() as directory:
            dumps = Path(directory) / "dumps"
            calls, completed = self.run_wrapper(
                """#!/usr/bin/env bash
for _ in 1 2 3 4; do
  echo tick
  sleep 1
done
""",
                env_updates={
                    "AFFECTED_GRADLE_HANG_DIR": str(dumps),
                    "AFFECTED_GRADLE_IDLE_SECONDS": "10",
                    "AFFECTED_GRADLE_IDLE_POLL": "1",
                },
            )
            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertEqual([], calls)
            self.assertFalse(dumps.exists())

    def test_missing_wrapper_fails_before_gradle(self) -> None:
        """Refuse to exec a missing wrapper."""
        with TemporaryDirectory() as directory:
            completed = subprocess.run(
                ["bash", str(SCRIPT), ":test"],
                cwd=directory,
                check=False,
                capture_output=True,
                text=True,
                env=self.env(),
            )
            self.assertEqual(1, completed.returncode)
            self.assertIn("wrapper is missing", completed.stderr)

    def run_wrapper(
        self,
        script: str,
        env_updates: dict[str, str] | None = None,
        cwd: Path | None = None,
    ) -> tuple[list[str], subprocess.CompletedProcess[str]]:
        """Run run_gradle.sh against a fake wrapper and return its call log."""
        with TemporaryDirectory() as directory:
            root = cwd or Path(directory)
            calls = root / "calls.log"
            wrapper = root / "gradlew"
            wrapper.write_text(script.replace("$CALLS", str(calls)), encoding="utf-8")
            wrapper.chmod(wrapper.stat().st_mode | stat.S_IEXEC)
            completed = subprocess.run(
                ["bash", str(SCRIPT), ":test"],
                cwd=root,
                check=False,
                capture_output=True,
                text=True,
                env=self.env(env_updates),
            )
            lines = calls.read_text(encoding="utf-8").splitlines() if calls.exists() else []
            return lines, completed

    def env(self, updates: dict[str, str] | None = None) -> dict[str, str]:
        """Skip the distribution fetch and do not sleep between retries."""
        env = os.environ.copy()
        env["AFFECTED_SKIP_GRADLE_FETCH"] = "1"
        env["AFFECTED_GRADLE_RETRY_SLEEP"] = "0"
        if updates:
            env.update(updates)
        return env


if __name__ == "__main__":
    unittest.main()

"""Tests for retrying only Gradle distribution fetch."""

from __future__ import annotations

import os
import stat
import subprocess
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory


SCRIPT = Path(__file__).resolve().parents[1] / "run_gradle.sh"


class RunGradleTest(unittest.TestCase):
    """Retry wrapper download failures, never retry a finished task graph."""

    def test_retries_a_transient_wrapper_failure_then_runs_once(self) -> None:
        """A SocketException during --version is retried; the real tasks run once."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            wrapper = root / "gradlew"
            log = root / "calls.log"
            wrapper.write_text(
                f"""#!/usr/bin/env bash
set -euo pipefail
echo \"$*\" >> "{log}"
count=$(wc -l < "{log}" | tr -d ' ')
if [[ "$*" == "--version" && "$count" == "1" ]]; then
  echo "java.net.SocketException: Unexpected end of file from server" >&2
  exit 1
fi
if [[ "$*" == "--version" ]]; then
  echo "Gradle 9.7.0"
  exit 0
fi
echo ran
exit 0
""",
                encoding="utf-8",
            )
            wrapper.chmod(wrapper.stat().st_mode | stat.S_IEXEC)
            env = os.environ.copy()
            env["TMPDIR"] = directory
            completed = subprocess.run(
                ["bash", str(SCRIPT), ":test"],
                cwd=root,
                check=False,
                capture_output=True,
                text=True,
                env=env,
            )
            self.assertEqual(0, completed.returncode, completed.stderr)
            calls = log.read_text(encoding="utf-8").splitlines()
            self.assertEqual(["--version", "--version", ":test"], calls)

    def test_non_transient_warmup_failure_is_not_retried(self) -> None:
        """A real Gradle configuration error must fail immediately."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            wrapper = root / "gradlew"
            log = root / "calls.log"
            wrapper.write_text(
                f"""#!/usr/bin/env bash
echo \"$*\" >> "{log}"
echo "FAILURE: Build failed with an exception." >&2
echo "What went wrong:" >&2
exit 1
""",
                encoding="utf-8",
            )
            wrapper.chmod(wrapper.stat().st_mode | stat.S_IEXEC)
            env = os.environ.copy()
            env["TMPDIR"] = directory
            completed = subprocess.run(
                ["bash", str(SCRIPT), ":test"],
                cwd=root,
                check=False,
                capture_output=True,
                text=True,
                env=env,
            )
            self.assertEqual(1, completed.returncode)
            self.assertEqual(["--version"], log.read_text(encoding="utf-8").splitlines())
            self.assertNotIn("Gradle distribution fetch failed", completed.stderr)


if __name__ == "__main__":
    unittest.main()

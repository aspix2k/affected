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
    """Do not retry a finished task graph; fetching the zip is a separate step."""

    def test_runs_the_requested_tasks_once(self) -> None:
        """After the cache is seeded, Gradle is invoked exactly once."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            wrapper = root / "gradlew"
            log = root / "calls.log"
            wrapper.write_text(
                f"""#!/usr/bin/env bash
set -euo pipefail
echo \"$*\" >> "{log}"
echo ran
exit 0
""",
                encoding="utf-8",
            )
            wrapper.chmod(wrapper.stat().st_mode | stat.S_IEXEC)
            env = os.environ.copy()
            env["AFFECTED_SKIP_GRADLE_FETCH"] = "1"
            completed = subprocess.run(
                ["bash", str(SCRIPT), ":test"],
                cwd=root,
                check=False,
                capture_output=True,
                text=True,
                env=env,
            )
            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertEqual([":test"], log.read_text(encoding="utf-8").splitlines())

    def test_missing_wrapper_fails_before_gradle(self) -> None:
        """Refuse to exec a missing wrapper."""
        with TemporaryDirectory() as directory:
            env = os.environ.copy()
            env["AFFECTED_SKIP_GRADLE_FETCH"] = "1"
            completed = subprocess.run(
                ["bash", str(SCRIPT), ":test"],
                cwd=directory,
                check=False,
                capture_output=True,
                text=True,
                env=env,
            )
            self.assertEqual(1, completed.returncode)
            self.assertIn("wrapper is missing", completed.stderr)


if __name__ == "__main__":
    unittest.main()

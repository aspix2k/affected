"""Tests for the local commit and push gates."""

from __future__ import annotations

import stat
import subprocess
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from scripts import local_gate


class LocalGateTest(unittest.TestCase):
    """Fail closed on cheap CI gates before a commit or push is published."""

    def test_unknown_mode_fails(self) -> None:
        """Only commit and push are valid hook modes."""
        with self.assertRaisesRegex(local_gate.LocalGateError, "Unknown"):
            local_gate.checks_for("merge")

    def test_commit_runs_detekt_before_script_gates(self) -> None:
        """Detekt is the first local gate because it is what turns plugin CI red."""
        names = [name for name, _command, _timeout in local_gate.checks_for("commit")]
        self.assertEqual(
            ["detekt", "script tests", "CI contracts", "analyzer policy"],
            names,
        )
        detekt = local_gate.checks_for("commit")[0][1]
        self.assertEqual("detekt", detekt[-1])
        self.assertTrue(detekt[0].endswith("run_gradle.sh"))

    def test_push_adds_shellcheck_to_the_commit_gates(self) -> None:
        """A push must still catch shell defects that the scripts job rejects."""
        commit = [name for name, _command, _timeout in local_gate.checks_for("commit")]
        push = [name for name, _command, _timeout in local_gate.checks_for("push")]
        self.assertEqual(commit + ["shell"], push)

    def test_failed_check_stops_the_gate(self) -> None:
        """The first red check is enough; later checks must not hide it."""
        calls: list[list[str]] = []

        def runner(command: list[str], **_kwargs: object) -> subprocess.CompletedProcess[str]:
            calls.append(command)
            if command[-1] == "detekt":
                return subprocess.CompletedProcess(command, 1, "", "Analysis failed with 16 weighted issues.")
            return subprocess.CompletedProcess(command, 0, "", "")

        with self.assertRaisesRegex(local_gate.LocalGateError, "detekt"):
            local_gate.run("commit", runner=runner)
        self.assertEqual(1, len(calls))

    def test_missing_shellcheck_fails_push_before_quality_sh(self) -> None:
        """Do not skip the shell gate when the binary is absent."""
        with patch.object(local_gate.shutil, "which", return_value=None):
            with self.assertRaisesRegex(local_gate.LocalGateError, "shellcheck"):
                local_gate.run("push", runner=lambda *_args, **_kwargs: None)

    def test_install_points_git_at_tracked_hooks(self) -> None:
        """A clone must opt into .githooks through core.hooksPath."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(["git", "init"], cwd=root, check=True, capture_output=True)
            hooks = root / ".githooks"
            hooks.mkdir()
            for name in ("pre-commit", "pre-push"):
                path = hooks / name
                path.write_text("#!/bin/sh\n", encoding="utf-8")
                path.chmod(path.stat().st_mode | stat.S_IEXEC)
            local_gate.install(root)
            configured = subprocess.run(
                ["git", "-C", str(root), "config", "--local", "--get", "core.hooksPath"],
                check=True,
                capture_output=True,
                text=True,
            )
            self.assertEqual(".githooks", configured.stdout.strip())

    def test_install_refuses_a_missing_hook(self) -> None:
        """Do not point Git at an incomplete hook directory."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(["git", "init"], cwd=root, check=True, capture_output=True)
            with self.assertRaisesRegex(local_gate.LocalGateError, "hooks are missing"):
                local_gate.install(root)

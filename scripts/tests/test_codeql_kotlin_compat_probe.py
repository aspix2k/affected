"""Lifecycle regressions for the CodeQL Kotlin compatibility probe."""

from __future__ import annotations

import os
import signal
import subprocess
import sys
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

from scripts import codeql_kotlin_compat_probe


class CodeqlKotlinCompatProbeTest(unittest.TestCase):
    """Keep probe cancellation bounded across the entire process group."""

    def test_sigterm_enters_structured_cancellation(self) -> None:
        """Workflow cancellation must unwind through the owned Gradle cleanup."""
        with self.assertRaises(codeql_kotlin_compat_probe.ProbeCancelled):
            codeql_kotlin_compat_probe.cancel_probe(signal.SIGTERM, None)

    def test_cancellation_after_spawn_cleans_the_published_process_group(self) -> None:
        """A signal between OS spawn and handle return cannot orphan Gradle."""
        original_popen = subprocess.Popen
        spawned: list[subprocess.Popen[str]] = []

        def cancel_after_spawn(*arguments: object, **options: object) -> subprocess.Popen[str]:
            """Publish a real child, then inject cancellation before Popen returns."""
            process = original_popen(*arguments, **options)
            spawned.append(process)
            os.kill(os.getpid(), signal.SIGTERM)
            return process

        previous_sigterm = signal.signal(
            signal.SIGTERM,
            codeql_kotlin_compat_probe.cancel_probe,
        )
        try:
            with mock.patch.object(
                codeql_kotlin_compat_probe.subprocess,
                "Popen",
                side_effect=cancel_after_spawn,
            ), self.assertRaises(codeql_kotlin_compat_probe.ProbeCancelled):
                codeql_kotlin_compat_probe.run_owned_process(
                    [sys.executable, "-c", "import time; time.sleep(60)"],
                    Path.cwd(),
                    10,
                )
        finally:
            signal.signal(signal.SIGTERM, previous_sigterm)
            for process in spawned:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                try:
                    process.communicate(timeout=3)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.communicate(timeout=3)
        self.assertEqual(1, len(spawned))
        self.assertFalse(self.process_exists(spawned[0].pid))

    def test_termination_kills_a_descendant_ignoring_sigterm_and_holding_stdout(self) -> None:
        """A dead Gradle parent cannot hide a live descendant behind an inherited pipe."""
        child = (
            "import os,signal,time; "
            "signal.signal(signal.SIGTERM, signal.SIG_IGN); "
            "print(os.getpid(), flush=True); "
            "time.sleep(60)"
        )
        parent = (
            "import subprocess,sys,time; "
            f"subprocess.Popen([sys.executable, '-c', {child!r}]); "
            "time.sleep(60)"
        )
        process = subprocess.Popen(
            [sys.executable, "-c", parent],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            start_new_session=True,
        )
        self.assertIsNotNone(process.stdout)
        descendant = int(process.stdout.readline().strip())
        started = time.monotonic()
        previous_sigterm = signal.signal(
            signal.SIGTERM,
            codeql_kotlin_compat_probe.cancel_probe,
        )

        def cancel_during_cleanup() -> None:
            """Send the runner's escalation while the descendant holds the grace period."""
            signal.pthread_sigmask(
                signal.SIG_BLOCK,
                codeql_kotlin_compat_probe.CANCELLATION_SIGNALS,
            )
            time.sleep(0.2)
            os.kill(os.getpid(), signal.SIGTERM)

        sender = threading.Thread(target=cancel_during_cleanup)
        sender.start()
        try:
            with self.assertRaises(codeql_kotlin_compat_probe.ProbeCancelled):
                codeql_kotlin_compat_probe.terminate_process_group(process)
            sender.join(timeout=2)
            signal.signal(signal.SIGTERM, previous_sigterm)
            process.communicate(timeout=3)
            self.assertLess(time.monotonic() - started, 8)
            self.assertTrue(self.wait_until_absent(descendant, 2))
        finally:
            sender.join(timeout=2)
            signal.signal(signal.SIGTERM, previous_sigterm)
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            try:
                process.communicate(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.communicate(timeout=3)

    @staticmethod
    def process_exists(process_id: int) -> bool:
        """Return whether a process identifier is still visible to the OS."""
        try:
            os.kill(process_id, 0)
        except ProcessLookupError:
            return False
        return True

    @classmethod
    def wait_until_absent(cls, process_id: int, timeout: float) -> bool:
        """Boundedly wait for the OS to reap a terminated descendant."""
        deadline = time.monotonic() + timeout
        while cls.process_exists(process_id) and time.monotonic() < deadline:
            time.sleep(0.05)
        return not cls.process_exists(process_id)


if __name__ == "__main__":
    unittest.main()

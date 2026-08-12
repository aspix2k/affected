"""Tests for fail-closed CI dependency acquisition and Gradle execution."""

from __future__ import annotations

import hashlib
import io
import os
import re
import signal
import stat
import sys
import time
import unittest
import urllib.error
import zipfile
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import call, patch

from scripts import ci_gradle


class FakeRunner:
    """Record commands and return deterministic process outcomes."""

    def __init__(self, outcomes: list[ci_gradle.ProcessResult]) -> None:
        """Store ordered process outcomes."""
        self.outcomes = outcomes
        self.calls: list[tuple[list[str], dict[str, str]]] = []

    def __call__(
        self, command: list[str], environment: dict[str, str]
    ) -> ci_gradle.ProcessResult:
        """Record one invocation and return its configured outcome."""
        self.calls.append((command, environment))
        return self.outcomes.pop(0)


class FakeResponse:
    """Expose configured chunks through a context-managed HTTP response."""

    def __init__(self, chunks: list[bytes | Exception]) -> None:
        """Store chunks returned by successive reads."""
        self.chunks = chunks

    def __enter__(self) -> object:
        """Enter the fake response context."""
        return self

    def __exit__(self, *_: object) -> None:
        """Leave the fake response context without suppressing errors."""

    def read(self, _: int) -> bytes:
        """Return or raise the next configured chunk."""
        if not self.chunks:
            return b""
        chunk = self.chunks.pop(0)
        if isinstance(chunk, Exception):
            raise chunk
        return chunk


class FakeOpener:
    """Return configured HTTP responses or transport failures."""

    def __init__(self, outcomes: list[FakeResponse | Exception]) -> None:
        """Store ordered open outcomes."""
        self.outcomes = outcomes
        self.calls = 0

    def open(self, request: object, timeout: int) -> FakeResponse:
        """Return or raise the next configured outcome."""
        self.calls += 1
        outcome = self.outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return outcome


class CiGradleTest(unittest.TestCase):
    """Exercise acquisition retry, checksum, and execution boundaries."""

    def test_wrapper_retries_only_verified_distribution_downloads(self) -> None:
        """Require a bounded wrapper retry while retaining the official checksum."""
        properties = ci_gradle.read_wrapper_properties()
        self.assertEqual("2", properties["retries"])
        self.assertEqual("500", properties["retryBackOffMs"])
        self.assertEqual(ci_gradle.GRADLE_9_SHA256, properties["distributionSha256Sum"])
        self.assertEqual("true", properties["validateDistributionUrl"])

    def test_gradle_preflight_and_repository_switch_are_wired(self) -> None:
        """Require task-input acquisition and the same repository switch in every build."""
        root_build = (ci_gradle.ROOT / "build.gradle.kts").read_text(encoding="utf-8")
        collector_build = (ci_gradle.ROOT / "collector" / "build.gradle.kts").read_text(
            encoding="utf-8"
        )
        self.assertIn('tasks.register("acquireCiDependencies")', root_build)
        self.assertIn('gradleProperty("affected.ci.tasks")', root_build)
        self.assertIn("task.inputs.files.files", root_build)
        self.assertIn(
            "org.jetbrains.intellij.platform.useCacheRedirector", collector_build
        )
        self.assertIn(
            "https://cache-redirector.jetbrains.com/intellij-dependencies",
            collector_build,
        )
        self.assertIn(
            "https://packages.jetbrains.team/maven/p/ij/intellij-dependencies",
            collector_build,
        )

    def test_source_gradle_workflows_use_the_acquisition_driver(self) -> None:
        """Keep source-controlled Gradle jobs behind one exact fail-closed boundary."""
        workflow_directory = ci_gradle.ROOT / ".github" / "workflows"
        direct_gradle = r"""(?m)(?:^|[\s/\\])gradle(?:w(?:\.bat)?)?(?=[\s"'])"""
        for command in (
            "${{ github.workspace }}/gradlew test",
            '"$GITHUB_WORKSPACE/gradlew" test',
            r"& .\gradlew.bat test",
            r'& "$env:GITHUB_WORKSPACE\gradlew.bat" test',
            "/opt/gradle/bin/gradle test",
        ):
            with self.subTest(command=command):
                self.assertRegex(command, direct_gradle)
        paths = (*workflow_directory.glob("*.yml"), *workflow_directory.glob("*.yaml"))
        for path in paths:
            content = path.read_text(encoding="utf-8")
            with self.subTest(workflow=path.name, boundary="no direct wrapper"):
                self.assertNotRegex(content, direct_gradle)
            invocations = content.count("python scripts/ci_gradle.py")
            bounded = re.findall(
                r"python scripts/ci_gradle\.py\s+--timeout\s+[0-9]+\s+--task",
                content,
            )
            with self.subTest(workflow=path.name, boundary="bounded driver"):
                self.assertEqual(invocations, len(bounded))

        workflows = (
            "ci.yml",
            "codeql.yml",
            "conformance.yml",
            "mutation.yml",
            "quality.yml",
        )
        for workflow in workflows:
            with self.subTest(workflow=workflow):
                content = (workflow_directory / workflow).read_text(encoding="utf-8")
                self.assertIn("python scripts/ci_gradle.py", content)
                tasks = re.findall(r"--task\s+([^\s\\]+)", content)
                self.assertTrue(tasks)
                self.assertTrue(all(task.startswith(":") for task in tasks))

        dependency_graph = (workflow_directory / "dependency-graph.yml").read_text(
            encoding="utf-8"
        )
        self.assertNotIn("scripts/ci_gradle.py", dependency_graph)
        self.assertIn("gradle/actions/dependency-submission@", dependency_graph)

    @unittest.skipIf(os.name == "nt", "POSIX process-group contract")
    def test_gradle_processes_are_isolated_and_terminated_as_a_tree(self) -> None:
        """Ensure a timeout reaches Gradle descendants instead of only the wrapper."""
        self.assertEqual({"start_new_session": True}, ci_gradle.process_group_options())
        process = unittest.mock.Mock(pid=7319)
        with patch.object(ci_gradle.os, "killpg") as kill_group:
            ci_gradle.stop_timed_out_process(process)
        self.assertEqual(
            [
                call(7319, ci_gradle.signal.SIGTERM),
                call(7319, ci_gradle.signal.SIGKILL),
            ],
            kill_group.call_args_list,
        )
        self.assertEqual(
            [call(timeout=5), call(timeout=5)], process.wait.call_args_list
        )

    def test_windows_gradle_process_tree_uses_bounded_taskkill(self) -> None:
        """Keep Windows descendants in scope when terminating a timed-out wrapper."""
        process = unittest.mock.Mock(pid=8123)
        with (
            patch.object(ci_gradle.os, "name", "nt"),
            patch.object(
                ci_gradle.subprocess, "CREATE_NEW_PROCESS_GROUP", 0x200, create=True
            ),
            patch.object(
                ci_gradle.subprocess,
                "run",
                return_value=unittest.mock.Mock(returncode=0),
            ) as run,
        ):
            self.assertEqual(
                {"creationflags": 0x200}, ci_gradle.process_group_options()
            )
            ci_gradle.terminate_process_tree(process, force=True)
        run.assert_called_once_with(
            ["taskkill", "/PID", "8123", "/T", "/F"],
            check=False,
            stdout=ci_gradle.subprocess.DEVNULL,
            stderr=ci_gradle.subprocess.DEVNULL,
            timeout=10,
        )

    def test_windows_taskkill_failure_is_not_ignored(self) -> None:
        """Fail explicitly when Windows cannot terminate a live Gradle tree."""
        for poll in (None, 0):
            process = unittest.mock.Mock(pid=8123)
            process.poll.return_value = poll
            with (
                self.subTest(wrapper_returncode=poll),
                patch.object(ci_gradle.os, "name", "nt"),
                patch.object(
                    ci_gradle.subprocess,
                    "run",
                    return_value=unittest.mock.Mock(returncode=1),
                ),
                self.assertRaises(ci_gradle.CiGradleError),
            ):
                ci_gradle.terminate_process_tree(process, force=True)

    def test_cancelled_driver_terminates_the_isolated_gradle_tree(self) -> None:
        """Clean up a Gradle subprocess when CI cancellation interrupts its wait."""
        process = unittest.mock.Mock(pid=9124, stdout=io.StringIO(""))
        process.wait.side_effect = KeyboardInterrupt
        with (
            patch.object(ci_gradle.subprocess, "Popen", return_value=process),
            patch.object(ci_gradle, "stop_timed_out_process") as stop,
            self.assertRaises(KeyboardInterrupt),
        ):
            ci_gradle.run_process(["gradle"], {}, 30)
        stop.assert_called_once_with(process)

    @unittest.skipIf(os.name == "nt", "POSIX process-group contract")
    def test_timeout_kills_a_descendant_after_its_leader_exits(self) -> None:
        """Kill descendants that survive after the timed-out wrapper handles SIGTERM."""
        with TemporaryDirectory() as directory:
            pid_file = Path(directory) / "child.pid"
            script = "\n".join(
                (
                    "import pathlib, signal, subprocess, sys, time",
                    "child = subprocess.Popen([sys.executable, '-c', "
                    "'import signal,time; signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(60)'])",
                    f"pathlib.Path({str(pid_file)!r}).write_text(str(child.pid))",
                    "signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))",
                    "time.sleep(60)",
                )
            )
            with self.assertRaisesRegex(ci_gradle.CiGradleError, "exceeded 1 seconds"):
                ci_gradle.run_process(
                    [sys.executable, "-c", script], dict(os.environ), 1
                )
            child_pid = int(pid_file.read_text(encoding="utf-8"))
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                try:
                    os.kill(child_pid, 0)
                except ProcessLookupError:
                    break
                time.sleep(0.05)
            else:
                os.kill(child_pid, signal.SIGKILL)
                self.fail(f"Timed-out descendant {child_pid} remained alive")

    def test_gradle_eight_fixture_uses_the_verified_local_distribution(self) -> None:
        """Forward the acquired distribution into TestKit instead of downloading by version."""
        collector_build = (ci_gradle.ROOT / "collector" / "build.gradle.kts").read_text(
            encoding="utf-8"
        )
        injection_test = (
            ci_gradle.ROOT
            / "collector"
            / "src"
            / "test"
            / "java"
            / "com"
            / "aspix2k"
            / "affected"
            / "collector"
            / "GradleInjectionTest.java"
        ).read_text(encoding="utf-8")
        self.assertIn("affected.test.gradle8Distribution", collector_build)
        self.assertIn("affected.test.gradle8Distribution", injection_test)
        self.assertIn("withGradleDistribution", injection_test)
        self.assertIn("affected.test.gradleTestKitDir", injection_test)
        self.assertIn("withTestKitDir", injection_test)
        self.assertIn('arguments.add("--offline")', injection_test)

    def test_main_keeps_generated_fixture_properties_out_of_user_arguments(
        self,
    ) -> None:
        """Add verified fixture paths only after validating the requested invocation."""
        with (
            TemporaryDirectory() as directory,
            patch.dict(os.environ, {"RUNNER_TEMP": directory}),
            patch.object(ci_gradle, "download_verified"),
            patch.object(
                ci_gradle,
                "extract_gradle_distribution",
                return_value=Path(directory) / "gradle-8.14.5" / "bin" / "gradle",
            ),
            patch.object(
                ci_gradle,
                "warm_gradle_fixture",
                return_value=Path(directory) / "testkit",
            ),
            patch.object(ci_gradle, "run_gradle", return_value=0) as run_gradle,
        ):
            self.assertEqual(
                0,
                ci_gradle.main(["--task", ":collector:test", "--", ":collector:test"]),
            )
            arguments = run_gradle.call_args.args[1]
            prepare = run_gradle.call_args.args[4]
            self.assertEqual([":collector:test"], arguments)
            prepared = prepare({"GRADLE_USER_HOME": str(Path(directory) / "home")})
            self.assertEqual(
                {
                    f"-Paffected.test.gradle8Distribution={(Path(directory) / 'affected-ci-gradle' / 'gradle-8.14.5-bin.zip').as_uri()}",
                    f"-Paffected.test.gradleTestKitDir={Path(directory) / 'testkit'}",
                },
                set(prepared),
            )

    def test_gradle_fixture_warmup_resolves_dependencies_without_tests(self) -> None:
        """Warm the exact committed fixture graph with every nested Gradle line."""
        runner = FakeRunner(
            [
                ci_gradle.ProcessResult(0, "Gradle 9 dependencies ready"),
                ci_gradle.ProcessResult(0, "Gradle 8 dependencies ready"),
            ]
        )
        with TemporaryDirectory() as directory:
            root = Path(directory)
            environment = {"GRADLE_USER_HOME": str(root / "selected-home")}
            gradle_eight = root / "gradle-8.14.5" / "bin" / "gradle"
            testkit = ci_gradle.warm_gradle_fixture(
                runner,
                root,
                environment,
                [Path(ci_gradle.gradle_wrapper()), gradle_eight],
            )
            self.assertEqual((root / "selected-home").resolve(), testkit)
            self.assertEqual(2, len(runner.calls))
            self.assertEqual(ci_gradle.gradle_wrapper(), runner.calls[0][0][0])
            self.assertEqual(str(gradle_eight), runner.calls[1][0][0])
            for command, command_environment in runner.calls:
                self.assertIn("acquireFixtureDependencies", command)
                self.assertIn("-Paffected.fixtureAcquisition=true", command)
                self.assertEqual(str(testkit), command_environment["GRADLE_USER_HOME"])
            self.assertEqual(
                ci_gradle.GRADLE_FIXTURE_PREAMBLE.read_bytes(),
                (root / "gradle-eight-fixture" / "build.gradle").read_bytes(),
            )

    def test_transient_acquisition_switches_home_and_execution_runs_once(self) -> None:
        """Retry only acquisition, then execute once offline from the successful home."""
        runner = FakeRunner(
            [
                ci_gradle.ProcessResult(
                    1,
                    "Could not GET https://cache-redirector.jetbrains.com/artifact: HTTP 502 Bad Gateway",
                ),
                ci_gradle.ProcessResult(0, "dependencies ready"),
                ci_gradle.ProcessResult(17, "test failure"),
            ]
        )
        with (
            TemporaryDirectory() as directory,
            patch.object(ci_gradle.time, "sleep") as sleep,
        ):
            result = ci_gradle.run_gradle(
                [":core:test"],
                [":core:test", "--rerun-tasks"],
                runner,
                Path(directory),
            )

        self.assertEqual(17, result)
        self.assertEqual(3, len(runner.calls))
        self.assertNotIn("--offline", runner.calls[0][0])
        self.assertIn(
            "org.jetbrains.intellij.platform.useCacheRedirector=true",
            " ".join(runner.calls[0][0]),
        )
        self.assertIn(
            "org.jetbrains.intellij.platform.useCacheRedirector=false",
            " ".join(runner.calls[1][0]),
        )
        self.assertIn("--offline", runner.calls[2][0])
        self.assertIn("--no-daemon", runner.calls[2][0])
        self.assertEqual(
            1,
            sum(
                ":core:test" in command and "--offline" in command
                for command, _ in runner.calls
            ),
        )
        self.assertNotEqual(
            runner.calls[0][1].get("GRADLE_USER_HOME"),
            runner.calls[1][1]["GRADLE_USER_HOME"],
        )
        self.assertEqual(
            runner.calls[1][1]["GRADLE_USER_HOME"],
            runner.calls[2][1]["GRADLE_USER_HOME"],
        )
        self.assertEqual([call(1)], sleep.call_args_list)

    def test_unknown_or_non_redirector_failure_is_not_retried(self) -> None:
        """Do not route deterministic, wrapper, or other repository errors elsewhere."""
        for output in (
            "Could not compile build file",
            "Downloading https://services.gradle.org/distributions: Unexpected end of file",
            "Could not GET https://repo.maven.apache.org/artifact: HTTP 503 Service Unavailable",
        ):
            with self.subTest(output=output):
                runner = FakeRunner([ci_gradle.ProcessResult(23, output)])
                with TemporaryDirectory() as directory:
                    result = ci_gradle.run_gradle(
                        [":core:test"], [":core:test"], runner, Path(directory)
                    )
                self.assertEqual(23, result)
                self.assertEqual(1, len(runner.calls))

    def test_task_inventory_requires_absolute_paths(self) -> None:
        """Reject Gradle selectors whose implicit subproject expansion cannot be preflighted."""
        for tasks, arguments in (
            (["test"], ["test"]),
            ([":acquireCiDependencies"], [":acquireCiDependencies"]),
            ([":core:test", ":core:test"], [":core:test", ":core:test"]),
            ([":core:test"], [":core:test", ":verifyPlugin"]),
            ([":core:test"], ["test", ":core:test"]),
        ):
            with (
                self.subTest(tasks=tasks, arguments=arguments),
                self.assertRaises(ci_gradle.CiGradleError),
            ):
                ci_gradle.validate_task_names(tasks, arguments)

    def test_task_inventory_accepts_only_known_option_values_beside_exact_tasks(
        self,
    ) -> None:
        """Accept test filters and properties without treating them as task selectors."""
        ci_gradle.validate_task_names(
            [":core:test"],
            [
                ":core:test",
                "--tests",
                "*Cli*ConformanceTest",
                "-Paffected.cliConformance=true",
                "--max-workers=1",
            ],
        )

    def test_task_inventory_rejects_topology_and_internal_driver_overrides(
        self,
    ) -> None:
        """Keep acquisition and execution on the same root, home and trusted inputs."""
        for override in (
            "--project-dir=/tmp/other",
            "-p=/tmp/other",
            "--settings-file=other.settings.gradle",
            "--include-build=/tmp/other",
            "--gradle-user-home=/tmp/other",
            "-Paffected.ci.tasks=:verifyPlugin",
            "-Paffected.test.gradleTestKitDir=/tmp/other",
            "-Porg.jetbrains.intellij.platform.useCacheRedirector=false",
            "-Dorg.gradle.project.affected.test.gradleTestKitDir=/tmp/other",
            "-p/tmp/other",
            "-I/tmp/init.gradle",
            "--dry-run",
            "-m",
            "--help",
            "--version",
            "--exclude-task=:core:test",
            "-x:core:test",
        ):
            with (
                self.subTest(override=override),
                self.assertRaises(ci_gradle.CiGradleError),
            ):
                ci_gradle.validate_task_names([":core:test"], [":core:test", override])

    def test_gradle_status_code_format_enables_only_transient_fallback(self) -> None:
        """Recognize the status wording emitted by Gradle dependency resolution."""
        for status in (429, 500, 502, 503, 504):
            with self.subTest(status=status):
                self.assertTrue(
                    ci_gradle.redirector_acquisition_failure(
                        "Could not GET https://cache-redirector.jetbrains.com/artifact. "
                        f"Received status code {status} from server"
                    )
                )
        self.assertFalse(
            ci_gradle.redirector_acquisition_failure(
                "Could not GET https://cache-redirector.jetbrains.com/artifact. "
                "Received status code 404 from server"
            )
        )

    def test_total_deadline_bounds_each_stage_and_fails_when_exhausted(self) -> None:
        """Give every acquisition and execution stage only the remaining total budget."""
        with patch.object(ci_gradle.time, "monotonic", return_value=90.25):
            self.assertEqual(10, ci_gradle.remaining_timeout(100.0, 300))
        with (
            patch.object(ci_gradle.time, "monotonic", return_value=100.0),
            self.assertRaises(ci_gradle.CiGradleError),
        ):
            ci_gradle.remaining_timeout(100.0, 300)

    def test_main_shares_one_timeout_between_preflight_and_execution(self) -> None:
        """Reduce the execution timeout by time already spent acquiring dependencies."""
        with (
            patch.object(
                ci_gradle.time,
                "monotonic",
                side_effect=(1_000.0, 1_001.0, 1_050.0),
            ),
            patch.object(
                ci_gradle,
                "run_process",
                side_effect=(
                    ci_gradle.ProcessResult(0, "acquired"),
                    ci_gradle.ProcessResult(0, "executed"),
                ),
            ) as run_process,
        ):
            self.assertEqual(
                0,
                ci_gradle.main(
                    [
                        "--timeout",
                        "100",
                        "--task",
                        ":printVersion",
                        "--",
                        ":printVersion",
                    ]
                ),
            )
        self.assertEqual(
            [99, 50],
            [item.args[2] for item in run_process.call_args_list],
        )

    def test_each_redirector_fallback_uses_a_new_empty_gradle_home(self) -> None:
        """Never reuse stale partial state from an earlier direct-source fallback."""
        outcomes = [
            ci_gradle.ProcessResult(1, "cache-redirector.jetbrains.com HTTP 502"),
            ci_gradle.ProcessResult(0, "ready"),
            ci_gradle.ProcessResult(0, "done"),
        ] * 2
        runner = FakeRunner(outcomes)
        with TemporaryDirectory() as directory, patch.object(ci_gradle.time, "sleep"):
            for _ in range(2):
                self.assertEqual(
                    0,
                    ci_gradle.run_gradle(
                        [":core:test"], [":core:test"], runner, Path(directory)
                    ),
                )
        first_home = runner.calls[1][1]["GRADLE_USER_HOME"]
        second_home = runner.calls[4][1]["GRADLE_USER_HOME"]
        self.assertNotEqual(first_home, second_home)

    def test_redirector_fallback_removes_its_temporary_gradle_home(self) -> None:
        """Remove direct-source dependency state after the requested work finishes."""
        runner = FakeRunner(
            [
                ci_gradle.ProcessResult(1, "cache-redirector.jetbrains.com HTTP 502"),
                ci_gradle.ProcessResult(0, "ready"),
                ci_gradle.ProcessResult(0, "done"),
            ]
        )
        with TemporaryDirectory() as directory, patch.object(ci_gradle.time, "sleep"):
            fallback_root = Path(directory)
            self.assertEqual(
                0,
                ci_gradle.run_gradle(
                    [":core:test"], [":core:test"], runner, fallback_root
                ),
            )
            self.assertEqual([], list(fallback_root.iterdir()))

    def test_redirector_fallback_reports_cleanup_failure(self) -> None:
        """Fail explicitly instead of silently leaking the temporary Gradle home."""
        runner = FakeRunner(
            [
                ci_gradle.ProcessResult(1, "cache-redirector.jetbrains.com HTTP 502"),
                ci_gradle.ProcessResult(0, "ready"),
                ci_gradle.ProcessResult(0, "done"),
            ]
        )
        with (
            TemporaryDirectory() as directory,
            patch.object(ci_gradle.time, "sleep"),
            patch.object(ci_gradle.shutil, "rmtree", side_effect=PermissionError),
            self.assertRaises(ci_gradle.CiGradleError),
        ):
            ci_gradle.run_gradle(
                [":core:test"], [":core:test"], runner, Path(directory)
            )

    def test_exhausted_transient_acquisition_never_executes_requested_tasks(
        self,
    ) -> None:
        """Stop after the bounded preflight attempts and do not run the build."""
        runner = FakeRunner(
            [
                ci_gradle.ProcessResult(
                    1,
                    "https://cache-redirector.jetbrains.com/artifact: HTTP 503 Service Unavailable",
                ),
                ci_gradle.ProcessResult(1, "Connection reset"),
            ]
        )
        with (
            TemporaryDirectory() as directory,
            patch.object(ci_gradle.time, "sleep") as sleep,
        ):
            result = ci_gradle.run_gradle(
                [":collector:test"], [":collector:test"], runner, Path(directory)
            )
        self.assertEqual(1, result)
        self.assertEqual(2, len(runner.calls))
        self.assertTrue(all("--offline" not in command for command, _ in runner.calls))
        self.assertEqual([call(1)], sleep.call_args_list)

    def test_distribution_download_retries_interrupted_stream_and_replaces_atomically(
        self,
    ) -> None:
        """Discard a partial archive, retry transport, and publish only verified bytes."""
        data = b"verified Gradle distribution"
        opener = FakeOpener(
            [
                FakeResponse([b"partial", ConnectionResetError("connection reset")]),
                FakeResponse([data]),
            ]
        )
        with (
            TemporaryDirectory() as directory,
            patch.object(ci_gradle.time, "sleep") as sleep,
        ):
            target = Path(directory) / "gradle.zip"
            ci_gradle.download_verified(
                ci_gradle.GRADLE_8_URL,
                hashlib.sha256(data).hexdigest(),
                target,
                opener,
            )
            self.assertEqual(data, target.read_bytes())
            self.assertFalse(target.with_suffix(".zip.part").exists())
        self.assertEqual(2, opener.calls)
        self.assertEqual([call(1)], sleep.call_args_list)

    def test_distribution_checksum_mismatch_is_terminal(self) -> None:
        """Never retry or publish an archive whose checksum does not match."""
        opener = FakeOpener([FakeResponse([b"wrong"]), FakeResponse([b"unused"])])
        with (
            TemporaryDirectory() as directory,
            patch.object(ci_gradle.time, "sleep") as sleep,
        ):
            target = Path(directory) / "gradle.zip"
            with self.assertRaises(ci_gradle.CiGradleError):
                ci_gradle.download_verified(
                    ci_gradle.GRADLE_8_URL,
                    hashlib.sha256(b"expected").hexdigest(),
                    target,
                    opener,
                )
            self.assertFalse(target.exists())
            self.assertFalse(target.with_suffix(".zip.part").exists())
        self.assertEqual(1, opener.calls)
        sleep.assert_not_called()

    def test_existing_distribution_verification_is_bounded_by_size_and_deadline(
        self,
    ) -> None:
        """Do not trust or hash an unbounded persistent Gradle archive."""
        with TemporaryDirectory() as directory:
            archive = Path(directory) / "gradle.zip"
            archive.write_bytes(b"oversized")
            self.assertFalse(
                ci_gradle.verified_file(
                    archive,
                    hashlib.sha256(b"oversized").hexdigest(),
                    maximum_bytes=1,
                )
            )
            with (
                patch.object(ci_gradle.time, "monotonic", return_value=100.0),
                self.assertRaises(ci_gradle.CiGradleError),
            ):
                ci_gradle.verified_file(
                    archive,
                    hashlib.sha256(b"oversized").hexdigest(),
                    maximum_bytes=100,
                    deadline=100.0,
                )

    def test_verified_distribution_is_extracted_to_its_exact_gradle_executable(
        self,
    ) -> None:
        """Extract only the pinned layout needed for Gradle 8 fixture acquisition."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "gradle.zip"
            with zipfile.ZipFile(archive, "w") as target:
                executable = zipfile.ZipInfo("gradle-8.14.5/bin/gradle")
                executable.external_attr = 0o100755 << 16
                target.writestr(executable, b"#!/bin/sh\n")
                target.writestr("gradle-8.14.5/bin/gradle.bat", b"@echo off\r\n")
            extracted = ci_gradle.extract_gradle_distribution(archive, root / "out")
            self.assertEqual(
                root.resolve() / "out" / "gradle-8.14.5" / "bin" / "gradle",
                extracted,
            )
            self.assertTrue(extracted.is_file())

    def test_distribution_extraction_rejects_paths_outside_the_version_root(
        self,
    ) -> None:
        """Reject a verified archive whose entries escape the expected Gradle root."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "gradle.zip"
            with zipfile.ZipFile(archive, "w") as target:
                target.writestr("../outside", b"unsafe")
            with self.assertRaises(ci_gradle.CiGradleError):
                ci_gradle.extract_gradle_distribution(archive, root / "out")
            self.assertFalse((root / "outside").exists())

    def test_distribution_extraction_rejects_windows_backslash_traversal(self) -> None:
        """Reject entries Windows would normalize outside the expected staging root."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "gradle.zip"
            with zipfile.ZipFile(archive, "w") as target:
                target.writestr(r"gradle-8.14.5/bin/..\..\..\outside", b"unsafe")
            with self.assertRaises(ci_gradle.CiGradleError):
                ci_gradle.extract_gradle_distribution(archive, root / "out")

    def test_distribution_extraction_rejects_corrupt_and_oversized_archives(
        self,
    ) -> None:
        """Normalize malformed ZIPs and enforce the extracted byte budget."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            corrupt = root / "corrupt.zip"
            corrupt.write_bytes(b"not a zip")
            with self.assertRaises(ci_gradle.CiGradleError):
                ci_gradle.extract_gradle_distribution(corrupt, root / "corrupt-out")

            oversized = root / "oversized.zip"
            with zipfile.ZipFile(oversized, "w") as target:
                target.writestr("gradle-8.14.5/bin/gradle", b"too large")
            with (
                patch.object(ci_gradle, "MAX_EXTRACTED_BYTES", 1),
                self.assertRaises(ci_gradle.CiGradleError),
            ):
                ci_gradle.extract_gradle_distribution(oversized, root / "large-out")

    def test_distribution_extraction_obeys_the_total_deadline(self) -> None:
        """Stop verified archive extraction when the invocation budget is exhausted."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "gradle.zip"
            with zipfile.ZipFile(archive, "w") as target:
                target.writestr("gradle-8.14.5/bin/gradle", b"executable")
            with (
                patch.object(ci_gradle.time, "monotonic", return_value=100.0),
                self.assertRaises(ci_gradle.CiGradleError),
            ):
                ci_gradle.extract_gradle_distribution(
                    archive,
                    root / "out",
                    deadline=100.0,
                )

    def test_distribution_extraction_rejects_symlink_entries_and_targets(self) -> None:
        """Never extract or reuse a Gradle distribution through a symbolic link."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "symlink.zip"
            with zipfile.ZipFile(archive, "w") as target:
                link = zipfile.ZipInfo("gradle-8.14.5/bin/gradle")
                link.external_attr = (stat.S_IFLNK | 0o777) << 16
                target.writestr(link, b"../../outside")
            with self.assertRaises(ci_gradle.CiGradleError):
                ci_gradle.extract_gradle_distribution(archive, root / "entry-out")

            destination = root / "target-out"
            destination.symlink_to(root / "external", target_is_directory=True)
            with self.assertRaises(ci_gradle.CiGradleError):
                ci_gradle.extract_gradle_distribution(archive, destination)

    def test_distribution_download_enforces_the_streamed_byte_budget(self) -> None:
        """Abort an oversized response before publishing a Gradle archive."""
        opener = FakeOpener([FakeResponse([b"oversized"]), FakeResponse([b"unused"])])
        with (
            TemporaryDirectory() as directory,
            patch.object(ci_gradle, "MAX_DOWNLOAD_BYTES", 1),
            patch.object(ci_gradle.time, "sleep") as sleep,
            self.assertRaises(ci_gradle.CiGradleError),
        ):
            target = Path(directory) / "gradle.zip"
            ci_gradle.download_verified(
                ci_gradle.GRADLE_8_URL,
                hashlib.sha256(b"oversized").hexdigest(),
                target,
                opener,
            )
        self.assertEqual(1, opener.calls)
        sleep.assert_not_called()

    def test_local_download_write_failure_is_terminal(self) -> None:
        """Do not classify a local disk failure as retryable network acquisition."""
        opener = FakeOpener([FakeResponse([b"bytes"]), FakeResponse([b"unused"])])
        with (
            TemporaryDirectory() as directory,
            patch.object(Path, "open", side_effect=PermissionError("read-only disk")),
            patch.object(ci_gradle.time, "sleep") as sleep,
            self.assertRaises(ci_gradle.CiGradleError),
        ):
            ci_gradle.download_verified(
                ci_gradle.GRADLE_8_URL,
                hashlib.sha256(b"bytes").hexdigest(),
                Path(directory) / "gradle.zip",
                opener,
            )
        self.assertEqual(1, opener.calls)
        sleep.assert_not_called()

    def test_distribution_redirect_must_stay_on_the_official_endpoint(self) -> None:
        """Reject a distribution redirect to an untrusted host or path."""
        handler = ci_gradle.GradleRedirectHandler()
        request = ci_gradle.urllib.request.Request(ci_gradle.GRADLE_8_URL, method="GET")
        for url in (
            "https://example.test/gradle.zip",
            "https://downloads.gradle.org/other/gradle.zip",
            "https://downloads.gradle.org/distributions/gradle-8.14.4-bin.zip",
            "https://github.com/gradle/gradle-distributions/releases/download/"
            "v8.14.4/gradle-8.14.4-bin.zip",
        ):
            with self.subTest(url=url), self.assertRaises(ci_gradle.CiGradleError):
                handler.redirect_request(request, None, 302, "Found", {}, url)

    def test_distribution_redirect_allows_only_the_gradle_release_asset_chain(
        self,
    ) -> None:
        """Accept the official version-matched GitHub asset and its fixed repository."""
        ci_gradle.validate_download_url(
            "https://github.com/gradle/gradle-distributions/releases/download/"
            "v8.14.5/gradle-8.14.5-bin.zip"
        )
        ci_gradle.validate_download_url(
            "https://release-assets.githubusercontent.com/"
            "github-production-release-asset/696192900/"
            "2d5b2574-364c-44ec-89d9-5f6c462fa74e?sig=bounded"
        )
        for url in (
            "https://github.com/gradle/gradle-distributions/releases/download/"
            "v8.14.4/gradle-8.14.5-bin.zip",
            "https://release-assets.githubusercontent.com/"
            "github-production-release-asset/1/2d5b2574-364c-44ec-89d9-5f6c462fa74e",
        ):
            with self.subTest(url=url), self.assertRaises(ci_gradle.CiGradleError):
                ci_gradle.validate_download_url(url)

    def test_non_retryable_http_status_is_terminal(self) -> None:
        """Reject a permanent HTTP failure without a second request."""
        opener = FakeOpener(
            [
                urllib.error.HTTPError(
                    ci_gradle.GRADLE_8_URL,
                    404,
                    "Not Found",
                    {},
                    io.BytesIO(),
                )
            ]
        )
        with (
            TemporaryDirectory() as directory,
            patch.object(ci_gradle.time, "sleep") as sleep,
            self.assertRaises(ci_gradle.CiGradleError),
        ):
            ci_gradle.download_verified(
                ci_gradle.GRADLE_8_URL,
                hashlib.sha256(b"expected").hexdigest(),
                Path(directory) / "gradle.zip",
                opener,
            )
        self.assertEqual(1, opener.calls)
        sleep.assert_not_called()


if __name__ == "__main__":
    unittest.main()

"""Tests for the live IDE check against a fake MCP server and a fake machine."""

from __future__ import annotations

import dataclasses
import os
import subprocess
import threading
import unittest
from http.server import HTTPServer
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Any

from scripts import live_ide
from scripts.tests.test_ide_smoke import FakeMcp, tool

GRADLE = live_ide.SCENARIOS["idea-gradle"]
PYTHON = live_ide.SCENARIOS["pycharm-python"]


def git_output(project: Path, *arguments: str) -> str:
    """Return the output of a git command in the project."""
    return subprocess.run(["git", *arguments], cwd=project, check=True, capture_output=True, text=True).stdout


class FakeRepository:
    """Lay out a minimal repository root with a fixture, a wrapper and a script."""

    def __init__(self, root: Path) -> None:
        """Create the files the fixture preparation copies."""
        (root / "conformance/live-ide/gradle/core").mkdir(parents=True)
        (root / "conformance/live-ide/gradle/core/Core.java").write_text("class Core {}\n", encoding="utf-8")
        (root / "conformance/cli-fixtures/python/packages/beta").mkdir(parents=True)
        (root / "conformance/cli-fixtures/python/packages/beta/beta.py").write_text("value = 2\n", encoding="utf-8")
        (root / "gradle/wrapper").mkdir(parents=True)
        for name in ("gradle-wrapper.jar", "gradle-wrapper.properties"):
            (root / "gradle/wrapper" / name).write_text(name, encoding="utf-8")
        (root / "gradlew").write_text("#!/bin/sh\n", encoding="utf-8")


class SwitchingReplies(dict):  # type: ignore[type-arg]
    """Answer the plan with the dependents' tasks once the configure reply has been served."""

    def __getitem__(self, key: str) -> Any:
        """Switch the plan after affected_configure is read."""
        reply = super().__getitem__(key)
        if key == "affected_configure":
            self["affected_verification_plan"] = tool({"tasks": list(GRADLE.dependent_tasks)})
        return reply


class FixtureTest(unittest.TestCase):
    """Verify the fixture repository, the wrapper copy and the virtual environment."""

    def test_gradle_fixture_is_a_feature_branch_with_one_edit_and_ignored_output(self) -> None:
        """Commit on main, branch feature, leave one modification and ignore build output."""
        scenario = dataclasses.replace(GRADLE, edited_file="core/Core.java")
        with TemporaryDirectory() as directory:
            root = Path(directory) / "repo"
            root.mkdir()
            FakeRepository(root)
            project = Path(directory) / "project"
            live_ide.prepare_fixture(scenario, project, root)
            self.assertEqual("feature", git_output(project, "branch", "--show-current").strip())
            self.assertEqual(" M core/Core.java", git_output(project, "status", "--short").strip("\n"))
            self.assertIn("main", git_output(project, "branch", "--list"))
            (project / "build").mkdir()
            (project / "build/out.class").write_text("x", encoding="utf-8")
            self.assertNotIn("build", git_output(project, "status", "--short"))
            self.assertTrue(os.access(project / "gradlew", os.R_OK))
            self.assertTrue((project / "gradle/wrapper/gradle-wrapper.jar").is_file())

    def test_python_fixture_gets_a_virtual_environment_with_pytest(self) -> None:
        """Create the venv inside the copy and install pytest into it before the commit."""
        commands: list[list[str]] = []

        def runner(command: list[str], **keywords: Any) -> subprocess.CompletedProcess[str]:
            """Record venv and pip calls and run git for real."""
            commands.append(command)
            if command[0] == "git":
                return subprocess.run(command, **keywords)
            return subprocess.CompletedProcess(command, 0, "", "")

        with TemporaryDirectory() as directory:
            root = Path(directory) / "repo"
            root.mkdir()
            FakeRepository(root)
            project = Path(directory) / "project"
            live_ide.prepare_fixture(PYTHON, project, root, runner)
            self.assertEqual("venv", commands[0][2])
            self.assertEqual(["install", "--quiet", "pytest"], commands[1][-3:])
            self.assertIn("# live smoke change", (project / "packages/beta/beta.py").read_text(encoding="utf-8"))

    def test_missing_fixture_is_reported(self) -> None:
        """Fail with the missing path."""
        with TemporaryDirectory() as directory, self.assertRaisesRegex(live_ide.LiveIdeError, "Fixture is missing"):
            live_ide.prepare_fixture(GRADLE, Path(directory) / "project", Path(directory))

    def test_failed_command_reports_its_tail(self) -> None:
        """Raise with the exit code and the error output."""
        failing = subprocess.CompletedProcess(["x"], 3, "", "boom")
        with self.assertRaisesRegex(live_ide.LiveIdeError, "exited with 3: boom"):
            live_ide.execute(["x"], Path("."), 1, lambda *args, **keywords: failing)

    def test_repository_fixtures_exist_for_every_scenario(self) -> None:
        """Keep every scenario pointing at a committed fixture."""
        for scenario in live_ide.SCENARIOS.values():
            with self.subTest(scenario=scenario.name):
                self.assertTrue((live_ide.FIXTURES / scenario.fixture / scenario.edited_file).is_file())


class SandboxTest(unittest.TestCase):
    """Verify the sandbox config, the process lookup and the display wrapper."""

    def test_config_enables_mcp_and_trusts_the_project_from_scratch(self) -> None:
        """Replace an old config with one that holds only the two option files."""
        with TemporaryDirectory() as directory:
            config = Path(directory) / "config_runIde"
            (config / "options").mkdir(parents=True)
            (config / "options/stale.xml").write_text("old", encoding="utf-8")
            live_ide.write_sandbox_config(config, Path("/work/project"))
            self.assertEqual(["mcpServer.xml", "trusted-paths.xml"], sorted(path.name for path in (config / "options").iterdir()))
            self.assertIn('name="enableMcpServer" value="true"', (config / "options/mcpServer.xml").read_text(encoding="utf-8"))
            self.assertIn('key="/work/project"', (config / "options/trusted-paths.xml").read_text(encoding="utf-8"))

    def test_sandbox_dir_picks_the_newest_directory_with_plugins(self) -> None:
        """Choose the sandbox that was prepared last."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            for index, name in enumerate(("IU-1", "PC-2")):
                (root / name / "plugins_runIde").mkdir(parents=True)
                os.utime(root / name / "plugins_runIde", (index, index))
            self.assertEqual(root / "PC-2", live_ide.sandbox_dir("runIde", root))
            with self.assertRaisesRegex(live_ide.LiveIdeError, "No sandbox"):
                live_ide.sandbox_dir("runIdeProduct", root)

    def test_process_lookup_matches_only_the_exact_config_path(self) -> None:
        """Ignore other sandboxes whose path starts the same."""
        config = Path("/s/config_runIde")
        listing = (
            "  10 java -Didea.config.path=/s/config_runIde -Xmx2g\n"
            "  11 java -Didea.config.path=/s/config_runIde2 -Xmx2g\n"
            "  12 java -Didea.config.path=/s/config_runIde\n"
            f"  {os.getpid()} python -Didea.config.path=/s/config_runIde\n"
            "  x other\n"
        )
        self.assertEqual([10, 12], live_ide.ide_pids(config, listing))

    def test_lsof_output_is_read_as_ports(self) -> None:
        """Parse the -Fn field lines of lsof."""
        done = subprocess.CompletedProcess([], 0, "p1\nn127.0.0.1:63343\nn[::1]:64342\n", "")
        self.assertEqual([63343, 64342], live_ide.listening_ports(1, lambda *args, **keywords: done))

        def missing(*args: Any, **keywords: Any) -> subprocess.CompletedProcess[str]:
            """Behave like a machine without lsof."""
            raise FileNotFoundError

        self.assertEqual([], live_ide.listening_ports(1, missing))

    def test_headless_linux_gets_xvfb_and_others_do_not(self) -> None:
        """Wrap only on Linux without a display, and say what to install when xvfb is missing."""
        command = ["./gradlew", "runIde"]
        self.assertEqual(command, live_ide.with_display(command, "Darwin", None, lambda name: None))
        self.assertEqual(command, live_ide.with_display(command, "Linux", ":0", lambda name: None))
        wrapped = live_ide.with_display(command, "Linux", None, lambda name: "/usr/bin/xvfb-run")
        self.assertEqual(["xvfb-run", "--auto-servernum"], wrapped[:2])
        self.assertEqual(command, wrapped[-2:])
        with self.assertRaisesRegex(live_ide.LiveIdeError, "xvfb"):
            live_ide.with_display(command, "Linux", None, lambda name: None)

    def test_gradle_command_loads_the_init_script_and_product_properties(self) -> None:
        """Pass the init script, the task, the product properties and the arguments."""
        command = live_ide.gradle_command(PYTHON, "runIdeProduct", ["--args=/p"])
        self.assertEqual(["-I", str(live_ide.INIT_SCRIPT), "runIdeProduct"], command[2:5])
        self.assertEqual(["-Paffected.runIde.type=PyCharm", "-Paffected.runIde.version=2026.2", "--args=/p"], command[5:])


class ExpectationsTest(unittest.TestCase):
    """Verify readiness and expectations against the fake MCP server."""

    def setUp(self) -> None:
        """Start a fake server that lists modules and plans."""
        FakeMcp.event_stream = False
        FakeMcp.replies = {
            "get_project_modules": tool({"modules": [{"name": "live-gradle.core"}, {"name": "live-gradle.app"}]}),
            "affected_changed_files": tool({"files": list(GRADLE.files)}),
            "affected_verification_plan": tool({"tasks": [":core:test"]}),
            "affected_run_verification": tool({"passed": True}),
            "affected_configure": tool({"testDependents": True}),
        }
        self.server = HTTPServer(("127.0.0.1", 0), FakeMcp)
        self.server.projects = []  # type: ignore[attr-defined]
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def tearDown(self) -> None:
        """Stop the fake server."""
        self.server.shutdown()
        self.server.server_close()

    def test_ready_when_modules_are_listed_and_the_plan_answers(self) -> None:
        """Return a session once the model and the plan are there."""
        session = live_ide.wait_ready(self.port, "/p", GRADLE, 5, lambda: True, lambda seconds: None)
        self.assertEqual("session", session)

    def test_missing_module_times_out_with_the_listed_modules(self) -> None:
        """Say which module never appeared."""
        scenario = dataclasses.replace(GRADLE, modules=("live-gradle.lib",))
        with self.assertRaisesRegex(live_ide.LiveIdeError, "live-gradle.lib.*live-gradle.core"):
            live_ide.wait_ready(self.port, "/p", scenario, 0.2, lambda: True, lambda seconds: None)

    def test_dead_ide_stops_the_wait(self) -> None:
        """Do not keep polling after the IDE exits."""
        with self.assertRaisesRegex(live_ide.LiveIdeError, "exited"):
            live_ide.wait_ready(self.port, "/p", GRADLE, 5, lambda: False, lambda seconds: None)

    def test_plan_ignores_busy_answers_until_the_tasks_match(self) -> None:
        """Retry an error answer and a wrong plan, then accept the expected tasks."""
        answers = [tool({}, error=True), tool({"tasks": [":x:test"]}), tool({"tasks": [":core:test"]})]
        FakeMcp.replies["affected_verification_plan"] = answers[0]
        calls: list[float] = []

        def pause(seconds: float) -> None:
            """Advance to the next canned answer."""
            calls.append(seconds)
            FakeMcp.replies["affected_verification_plan"] = answers[len(calls)]

        planned = live_ide.plan(self.port, "session", "/p", [":core:test"], 5, pause)
        self.assertEqual([":core:test"], planned)
        self.assertEqual(2, len(calls))

    def test_wrong_plan_fails_after_the_timeout(self) -> None:
        """Report the planned tasks when they never match."""
        with self.assertRaisesRegex(live_ide.LiveIdeError, r"planned tasks are \[':core:test'\]"):
            live_ide.plan(self.port, "session", "/p", [":app:test"], 0, lambda seconds: None)

    def test_gradle_expectations_cover_dependents(self) -> None:
        """Run once, enable dependents, expect the dependents' plan and run again."""
        FakeMcp.replies = SwitchingReplies(FakeMcp.replies)
        report = live_ide.expectations(self.port, "session", "/p", GRADLE, 5)
        self.assertEqual("planned tasks with dependents: :core:test, :app:test", report[-2])
        self.assertEqual("verification with dependents: passed", report[-1])

    def test_wrong_changed_files_fail(self) -> None:
        """Reject a different file list."""
        FakeMcp.replies["affected_changed_files"] = tool({"files": ["other"]})
        with self.assertRaisesRegex(live_ide.LiveIdeError, "changed files are"):
            live_ide.expectations(self.port, "session", "/p", GRADLE, 1)

    def test_failed_run_fails(self) -> None:
        """Reject a verification that did not pass."""
        FakeMcp.replies["affected_run_verification"] = tool({"passed": False})
        with self.assertRaisesRegex(live_ide.LiveIdeError, "did not pass"):
            live_ide.expectations(self.port, "session", "/p", GRADLE, 1)


class FakePlatform(live_ide.Platform):
    """A machine whose IDE is a fake MCP server and whose launch is a no-op."""

    log = "idea log line"
    stopped = False
    port_number = 0
    prepared = False
    project_seen: Path | None = None

    def prepare_sandbox(self, project: Path) -> None:
        """Pretend to prepare the sandbox."""
        FakePlatform.prepared = True
        FakePlatform.project_seen = project
        self.config = self.work / "sandbox" / "config_runIdeProduct"
        self.config.mkdir(parents=True)
        (self.config.parent / "log_runIdeProduct").mkdir()
        (self.config.parent / "log_runIdeProduct" / "idea.log").write_text(self.log, encoding="utf-8")

    def launch(self, project: Path) -> None:
        """Pretend to start the IDE."""

    def alive(self) -> bool:
        """Report the IDE as running."""
        return True

    def port(self) -> int | None:
        """Return the fake server port."""
        return FakePlatform.port_number

    def stop(self) -> None:
        """Remember that the IDE was stopped."""
        FakePlatform.stopped = True


class RunScenarioTest(unittest.TestCase):
    """Verify the run lifecycle: report, log collection, shutdown and cleanup."""

    def setUp(self) -> None:
        """Start a fake MCP server and reset the fake platform."""
        FakeMcp.event_stream = False
        FakeMcp.replies = {
            "get_project_modules": tool({"modules": [{"name": "m"}]}),
            "affected_changed_files": tool({"files": ["packages/beta/beta.py"]}),
            "affected_verification_plan": tool({"tasks": ["affected-python-beta:test"]}),
            "affected_run_verification": tool({"passed": True}),
        }
        self.server = HTTPServer(("127.0.0.1", 0), FakeMcp)
        self.server.projects = []  # type: ignore[attr-defined]
        FakePlatform.port_number = self.server.server_address[1]
        FakePlatform.stopped = False
        FakePlatform.project_seen = None
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.scenario = dataclasses.replace(PYTHON, venv=False)

    def tearDown(self) -> None:
        """Stop the fake server."""
        self.server.shutdown()
        self.server.server_close()

    def test_success_reports_stops_and_removes_the_temporary_directory(self) -> None:
        """Return the report, quit the IDE and leave no temporary state."""
        with TemporaryDirectory() as directory:
            report_dir = Path(directory)
            report = live_ide.run_scenario(self.scenario, report_dir, 5, FakePlatform, 5)
            self.assertIn("verification: passed", report)
            self.assertTrue(FakePlatform.stopped)
            self.assertFalse(FakePlatform.project_seen and FakePlatform.project_seen.parent.exists())
            self.assertEqual(["pycharm-python-report.txt"], [path.name for path in report_dir.iterdir()])

    def test_failure_collects_the_ide_log_and_still_cleans_up(self) -> None:
        """Copy the IDE log and report, stop the IDE and remove temporary state on failure."""
        FakeMcp.replies["affected_run_verification"] = tool({"passed": False})
        with TemporaryDirectory() as directory:
            report_dir = Path(directory)
            with self.assertRaisesRegex(live_ide.LiveIdeError, "did not pass"):
                live_ide.run_scenario(self.scenario, report_dir, 5, FakePlatform, 5)
            self.assertIn("idea log line", (report_dir / "pycharm-python-idea.log").read_text(encoding="utf-8"))
            self.assertIn("FAILED", (report_dir / "pycharm-python-report.txt").read_text(encoding="utf-8"))
            self.assertTrue(FakePlatform.stopped)
            self.assertFalse(FakePlatform.project_seen and FakePlatform.project_seen.parent.exists())

    def test_main_runs_every_scenario_for_all_and_fails_when_one_fails(self) -> None:
        """Run all scenarios and exit non-zero when any fails."""
        names: list[str] = []

        def runner(scenario: live_ide.Scenario, report_dir: Path | None, timeout: float) -> list[str]:
            """Fail the Maven scenario only."""
            names.append(scenario.name)
            if scenario.name == "idea-maven":
                raise live_ide.LiveIdeError("boom")
            return ["ok"]

        original = live_ide.run_scenario
        live_ide.run_scenario = runner  # type: ignore[assignment]
        try:
            self.assertEqual(1, live_ide.main(["all"]))
            self.assertEqual(0, live_ide.main(["idea-gradle"]))
        finally:
            live_ide.run_scenario = original
        self.assertEqual(["idea-gradle", "idea-maven", "pycharm-python", "idea-gradle"], names)

    def test_smoke_error_is_a_failure_of_the_scenario(self) -> None:
        """Wrap tool failures from the MCP server in the live check error."""
        FakeMcp.replies["affected_changed_files"] = tool({}, error=True)
        with TemporaryDirectory() as directory, self.assertRaisesRegex(live_ide.LiveIdeError, "FAILED"):
            live_ide.run_scenario(self.scenario, Path(directory), 5, FakePlatform, 1)


if __name__ == "__main__":
    unittest.main()

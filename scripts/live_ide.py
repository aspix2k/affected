#!/usr/bin/env python3
"""Start sandbox IDEs with the built plugin and check it through the IDE's MCP server."""

from __future__ import annotations

import argparse
import os
import platform as host
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from pathlib import Path

try:
    from scripts import ide_smoke
except ImportError:  # run as a file: python3 scripts/live_ide.py
    import ide_smoke  # type: ignore[no-redef]

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "conformance"
INIT_SCRIPT = ROOT / "scripts" / "live-ide.init.gradle"
SANDBOXES = ROOT / ".intellijPlatform" / "sandbox" / "affected"
JVM_ARGS_ENV = "AFFECTED_LIVE_IDE_JVM_ARGS"
IDE_JVM_ARGS = (
    "-Djb.consents.confirmation.enabled=false",
    "-Djb.privacy.policy.text=<!--999.999-->",
    "-Dide.experimental.ui.onboarding=false",
)
THREAD_DUMP_SECONDS = 3
BASE_CHECK_SECONDS = 900
PROBE_SECONDS = 5
POLL_SECONDS = 3
IGNORED = (
    "build/",
    "target/",
    ".gradle/",
    ".idea/",
    ".kotlin/",
    ".venv/",
    "__pycache__/",
    ".pytest_cache/",
    "*.iml",
)
MCP_SETTINGS = """<application>
  <component name="McpServerSettings">
    <option name="enableMcpServer" value="true" />
  </component>
</application>
"""
TRUSTED_PATHS = """<application>
  <component name="Trusted.Paths">
    <option name="TRUSTED_PROJECT_PATHS">
      <map>
        <entry key="{project}" value="true" />
      </map>
    </option>
  </component>
</application>
"""
JDK_TABLE = """<application>
  <component name="ProjectJdkTable">
    <jdk version="2">
      <name value="live-jdk" />
      <type value="JavaSDK" />
      <version value="live-jdk" />
      <homePath value="{home}" />
      <roots>
        <annotationsPath>
          <root type="composite">
            <root url="jar://$APPLICATION_HOME_DIR$/plugins/java/lib/resources/jdkAnnotations.jar!/" type="simple" />
          </root>
        </annotationsPath>
        <classPath>
          <root type="composite">
            <root url="jrt://{home}!/java.base" type="simple" />
          </root>
        </classPath>
        <javadocPath>
          <root type="composite" />
        </javadocPath>
        <sourcePath>
          <root type="composite" />
        </sourcePath>
      </roots>
      <additional />
    </jdk>
  </component>
</application>
"""
PROJECT_JDK = """<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="ProjectRootManager" version="2" project-jdk-name="live-jdk" project-jdk-type="JavaSDK" />
</project>
"""
Runner = Callable[..., subprocess.CompletedProcess[str]]


class LiveIdeError(Exception):
    """Report a failed preparation, start, wait or expectation of a live IDE check."""


@dataclass(frozen=True)
class Scenario:
    """One product, one fixture project and what the plugin must report for it."""

    name: str
    fixture: str
    gradle_task: str
    gradle_properties: tuple[str, ...]
    edited_file: str
    modules: tuple[str, ...]
    files: tuple[str, ...]
    tasks: tuple[str, ...]
    dependent_tasks: tuple[str, ...] = ()
    wrapper: bool = False
    venv: bool = False
    jdk: bool = False
    regression: tuple[str, str] | None = None


SCENARIOS = {
    scenario.name: scenario
    for scenario in (
        Scenario(
            name="idea-gradle",
            fixture="live-ide/gradle",
            gradle_task="runIde",
            gradle_properties=(),
            edited_file="core/src/main/java/live/Core.java",
            modules=("live-gradle.core", "live-gradle.app"),
            files=("core/src/main/java/live/Core.java",),
            tasks=(":core:test",),
            dependent_tasks=(":core:test", ":app:test"),
            wrapper=True,
            jdk=True,
            regression=("return 1;", "return 2;"),
        ),
        Scenario(
            name="idea-maven",
            fixture="live-ide/maven",
            gradle_task="runIde",
            gradle_properties=(),
            edited_file="core/src/main/java/live/Core.java",
            modules=("live-core", "live-app"),
            files=("core/src/main/java/live/Core.java",),
            tasks=("live:live-core:test",),
            dependent_tasks=("live:live-core:test", "live:live-app:test"),
            jdk=True,
        ),
        Scenario(
            name="pycharm-python",
            fixture="cli-fixtures/python",
            gradle_task="runIdeProduct",
            gradle_properties=("-Paffected.runIde.type=PyCharm", "-Paffected.runIde.version=2026.2"),
            edited_file="packages/beta/beta.py",
            modules=(),
            files=("packages/beta/beta.py",),
            tasks=("affected-python-beta:test",),
            venv=True,
        ),
    )
}


def execute(command: Sequence[str], cwd: Path, timeout: float, runner: Runner = subprocess.run) -> str:
    """Run a command to completion and return its output, or raise with the tail of it."""
    try:
        completed = runner(
            list(command), cwd=str(cwd), check=False, capture_output=True, text=True, timeout=timeout
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise LiveIdeError(f"{' '.join(command[:3])} did not finish: {error}") from error
    if completed.returncode != 0:
        tail = (completed.stderr or completed.stdout or "").strip()[-2000:]
        raise LiveIdeError(f"{' '.join(command[:3])} exited with {completed.returncode}: {tail}")
    return completed.stdout


def git(project: Path, *arguments: str, runner: Runner = subprocess.run) -> None:
    """Run git in the fixture with a fixed identity and without hooks or signing."""
    settings = ["-c", "user.name=live-ide", "-c", "user.email=live-ide@example.invalid"]
    settings += ["-c", "commit.gpgsign=false", "-c", f"core.hooksPath={os.devnull}"]
    execute(["git", *settings, *arguments], project, 60, runner)


def prepare_fixture(scenario: Scenario, project: Path, root: Path = ROOT, runner: Runner = subprocess.run) -> None:
    """Copy the fixture, commit it on main, branch off feature and leave one uncommitted edit."""
    source = root / "conformance" / scenario.fixture
    if not source.is_dir():
        raise LiveIdeError(f"Fixture is missing: {source}")
    shutil.copytree(source, project)
    if scenario.wrapper:
        wrapper = project / "gradle" / "wrapper"
        wrapper.mkdir(parents=True)
        shutil.copy2(root / "gradlew", project / "gradlew")
        for name in ("gradle-wrapper.jar", "gradle-wrapper.properties"):
            shutil.copy2(root / "gradle" / "wrapper" / name, wrapper / name)
    if scenario.venv:
        execute([sys.executable, "-m", "venv", str(project / ".venv")], project, 300, runner)
        execute([str(project / ".venv" / "bin" / "python"), "-m", "pip", "install", "--quiet", "pytest"], project, 600, runner)
    if scenario.jdk:
        (project / ".idea").mkdir()
        (project / ".idea" / "misc.xml").write_text(PROJECT_JDK, encoding="utf-8")
    (project / ".gitignore").write_text("\n".join(IGNORED) + "\n", encoding="utf-8")
    git(project, "init", "--quiet", "--initial-branch=main", runner=runner)
    git(project, "add", "--all", runner=runner)
    git(project, "commit", "--quiet", "--message=base", runner=runner)
    git(project, "checkout", "--quiet", "-b", "feature", runner=runner)
    with (project / scenario.edited_file).open("a", encoding="utf-8") as edited:
        edited.write("# live smoke change\n" if scenario.edited_file.endswith(".py") else "// live smoke change\n")


def java_home(environment: dict[str, str] | os._Environ[str] = os.environ, runner: Runner = subprocess.run) -> str:
    """Return JAVA_HOME, or the home of the java on PATH when it is not set."""
    configured = environment.get("JAVA_HOME")
    if configured:
        return configured
    completed = runner(["java", "-XshowSettings:properties", "-version"], check=False, capture_output=True, text=True, timeout=60)
    match = re.search(r"^\s*java\.home = (.+)$", completed.stdout + completed.stderr, re.MULTILINE)
    if match is None:
        raise LiveIdeError("JAVA_HOME is not set and java.home could not be read from java")
    return match.group(1).strip()


def write_sandbox_config(config: Path, project: Path, jdk_home: str | None = None) -> None:
    """Start the sandbox from an empty config that enables the MCP server, trusts the project and registers the JDK."""
    shutil.rmtree(config, ignore_errors=True)
    options = config / "options"
    options.mkdir(parents=True)
    (options / "mcpServer.xml").write_text(MCP_SETTINGS, encoding="utf-8")
    (options / "trusted-paths.xml").write_text(TRUSTED_PATHS.format(project=project), encoding="utf-8")
    if jdk_home is not None:
        (options / "jdk.table.xml").write_text(JDK_TABLE.format(home=jdk_home), encoding="utf-8")


def gradle_command(scenario: Scenario, task: str, arguments: Sequence[str] = ()) -> list[str]:
    """Build the repository Gradle command for a task with the unattended-start init script."""
    return [str(ROOT / "gradlew"), "--console=plain", "-I", str(INIT_SCRIPT), task, *scenario.gradle_properties, *arguments]


def with_display(command: list[str], system: str, display: str | None, which: Callable[[str], str | None]) -> list[str]:
    """Wrap the command in xvfb-run on a Linux machine without a display."""
    if system != "Linux" or display:
        return command
    if which("xvfb-run") is None:
        raise LiveIdeError("A headless Linux run needs xvfb-run (apt install xvfb)")
    return ["xvfb-run", "--auto-servernum", "--server-args=-screen 0 1920x1080x24", *command]


def sandbox_dir(task: str, root: Path = SANDBOXES) -> Path:
    """Return the newest sandbox directory that holds plugins for the Gradle run task."""
    candidates = sorted(root.glob(f"*/plugins_{task}"), key=lambda path: path.stat().st_mtime)
    if not candidates:
        raise LiveIdeError(f"No sandbox for {task} under {root}")
    return candidates[-1].parent


def ide_pids(config: Path, listing: str) -> list[int]:
    """Pick the processes of a `ps -eo pid=,args=` listing that use this sandbox config directory."""
    marker = f"-Didea.config.path={config}"
    pids = []
    for line in listing.splitlines():
        pid, _, arguments = line.strip().partition(" ")
        if pid.isdigit() and int(pid) != os.getpid() and re.search(re.escape(marker) + r"(\s|$)", arguments):
            pids.append(int(pid))
    return pids


def listening_ports(pid: int, runner: Runner = subprocess.run) -> list[int]:
    """List the TCP ports a process listens on, empty when lsof is unavailable."""
    try:
        completed = runner(
            ["lsof", "-nP", "-a", "-p", str(pid), "-iTCP", "-sTCP:LISTEN", "-Fn"],
            check=False, capture_output=True, text=True, timeout=30,
        )
    except (OSError, subprocess.TimeoutExpired):
        return []
    return [int(match) for match in re.findall(r"^n.*:(\d+)$", completed.stdout, re.MULTILINE)]


def find_mcp_port(config: Path, runner: Runner = subprocess.run) -> int | None:
    """Return the listening port of the sandbox IDE that completes an MCP initialize, if any."""
    listing = runner(["ps", "-eo", "pid=,args="], check=False, capture_output=True, text=True, timeout=30).stdout
    for pid in ide_pids(config, listing):
        for port in listening_ports(pid, runner):
            try:
                ide_smoke.connect(port, PROBE_SECONDS)
            except (ide_smoke.SmokeError, ValueError):
                continue
            return port
    return None


def signal_ide(config: Path, number: int, runner: Runner = subprocess.run) -> int:
    """Send a signal to every process of the sandbox IDE and return how many received it."""
    listing = runner(["ps", "-eo", "pid=,args="], check=False, capture_output=True, text=True, timeout=30).stdout
    delivered = 0
    for pid in ide_pids(config, listing):
        try:
            os.kill(pid, number)
        except OSError:
            continue
        delivered += 1
    return delivered


def stop_ide(config: Path, process: subprocess.Popen[str] | None, runner: Runner = subprocess.run) -> None:
    """Ask the sandbox IDE to quit, then end the Gradle run that started it."""
    signal_ide(config, signal.SIGTERM, runner)
    if process is None:
        return
    try:
        process.wait(timeout=60)
    except subprocess.TimeoutExpired:
        process.terminate()
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def wait_ready(
    port: int, project: str, scenario: Scenario, timeout: float, alive: Callable[[], bool], pause: Callable[[float], None] = time.sleep
) -> str | None:
    """Wait until the project model lists the modules and Affected can plan, then return the session."""
    deadline = time.monotonic() + timeout
    problem = "the IDE has not answered yet"
    while time.monotonic() < deadline:
        if not alive():
            raise LiveIdeError("The IDE exited before the project was ready")
        try:
            session = ide_smoke.connect(port, PROBE_SECONDS * 6)
            names = [item.get("name", "") for item in ide_smoke.call(port, session, "get_project_modules", project).get("modules", [])]
            missing = [module for module in scenario.modules if module not in names]
            if not missing:
                ide_smoke.call(port, session, "affected_verification_plan", project)
                return session
            problem = f"modules {missing} are not in the project model {names}"
        except (ide_smoke.SmokeError, ValueError) as error:
            problem = str(error)
        pause(POLL_SECONDS)
    raise LiveIdeError(f"The project was not ready after {timeout:.0f}s: {problem}")


def plan(port: int, session: str | None, project: str, tasks: Sequence[str], timeout: float, pause: Callable[[float], None] = time.sleep) -> list[str]:
    """Poll the verification plan until it equals the expected tasks or the time is over."""
    deadline = time.monotonic() + timeout
    planned: list[str] = []
    problem = ""
    while True:
        try:
            planned = ide_smoke.call(port, session, "affected_verification_plan", project).get("tasks", [])
            problem = ""
        except ide_smoke.SmokeError as error:
            problem = str(error)
        if not problem and sorted(planned) == sorted(tasks):
            return planned
        if time.monotonic() >= deadline:
            raise LiveIdeError(problem or f"planned tasks are {planned}, expected {list(tasks)}")
        pause(POLL_SECONDS)


def expect_run(port: int, session: str | None, project: str) -> str:
    """Run the verification and require it to pass."""
    outcome = ide_smoke.call(port, session, "affected_run_verification", project)
    if outcome.get("passed") is not True:
        raise LiveIdeError(f"verification did not pass: {outcome}")
    return "passed"


def expectations(port: int, session: str | None, project: str, scenario: Scenario, timeout: float) -> list[str]:
    """Check changed files, the plan, a passing run and, when configured, the dependents' plan and run."""
    changed = ide_smoke.call(port, session, "affected_changed_files", project).get("files", [])
    report = [f"changed files: {', '.join(changed) or 'none'}"]
    if sorted(changed) != sorted(scenario.files):
        raise LiveIdeError(f"changed files are {changed}, expected {list(scenario.files)}")
    report.append(f"planned tasks: {', '.join(plan(port, session, project, scenario.tasks, timeout))}")
    report.append(f"verification: {expect_run(port, session, project)}")
    if scenario.dependent_tasks:
        ide_smoke.call(port, session, "affected_configure", project, {"testDependents": True})
        report.append(f"planned tasks with dependents: {', '.join(plan(port, session, project, scenario.dependent_tasks, timeout))}")
        report.append(f"verification with dependents: {expect_run(port, session, project)}")
    if scenario.regression:
        report.append(f"base check: {expect_regression(port, session, project, scenario)}")
    return report


def expect_regression(port: int, session: str | None, project: str, scenario: Scenario) -> str:
    """Break the edited file, require the run to fail and the base check to blame the change."""
    old, new = scenario.regression or ("", "")
    edited = Path(project) / scenario.edited_file
    source = edited.read_text(encoding="utf-8")
    if old not in source:
        raise LiveIdeError(f"{scenario.edited_file} does not contain {old!r}")
    edited.write_text(source.replace(old, new, 1), encoding="utf-8")
    try:
        outcome = ide_smoke.call(port, session, "affected_run_verification", project)
    except ide_smoke.SmokeError:
        outcome = None
    if outcome is not None:
        raise LiveIdeError(f"verification passed with a breaking change: {outcome}")
    verdicts = ide_smoke.call(port, session, "affected_check_on_base", project, timeout=BASE_CHECK_SECONDS)
    found = [group.get("verdict") for group in verdicts.get("groups", [])]
    if found != ["regression"]:
        raise LiveIdeError(f"base check verdicts are {found}, expected ['regression']: {verdicts}")
    return "regression"


class Platform:
    """The real machine: Gradle, the sandbox directories and the IDE process."""

    def __init__(self, scenario: Scenario, work: Path) -> None:
        """Remember the scenario and the temporary directory the run lives in."""
        self.scenario = scenario
        self.work = work
        self.config: Path | None = None
        self.process: subprocess.Popen[str] | None = None
        self.gradle_log = work / "gradle.log"

    def prepare_sandbox(self, project: Path) -> None:
        """Build the plugin, fetch the IDE, and replace the sandbox config with a fresh one."""
        task = self.scenario.gradle_task
        execute(gradle_command(self.scenario, f"prepareSandbox_{task}"), ROOT, 3600)
        sandbox = sandbox_dir(task)
        for kind in ("system", "log"):
            shutil.rmtree(sandbox / f"{kind}_{task}", ignore_errors=True)
        self.config = sandbox / f"config_{task}"
        write_sandbox_config(self.config, project, java_home() if self.scenario.jdk else None)

    def launch(self, project: Path) -> None:
        """Start the IDE through Gradle, with xvfb-run on a headless Linux machine."""
        command = gradle_command(self.scenario, self.scenario.gradle_task, [f"--args={project}"])
        command = with_display(command, host.system(), os.environ.get("DISPLAY"), shutil.which)
        environment = {**os.environ, JVM_ARGS_ENV: " ".join((*IDE_JVM_ARGS, os.environ.get(JVM_ARGS_ENV, "")))}
        with self.gradle_log.open("w", encoding="utf-8") as output:
            self.process = subprocess.Popen(  # noqa: S603
                command, cwd=ROOT, env=environment, stdout=output, stderr=subprocess.STDOUT, text=True, start_new_session=True
            )

    def alive(self) -> bool:
        """Tell whether the Gradle run that owns the IDE is still going."""
        return self.process is not None and self.process.poll() is None

    def port(self) -> int | None:
        """Return the MCP port of the sandbox IDE once it answers."""
        return find_mcp_port(self.config) if self.config else None

    def dump_threads(self) -> None:
        """Make the IDE print its threads into the Gradle output, which shows what a stuck start waits for."""
        if self.config and host.system() != "Windows" and signal_ide(self.config, signal.SIGQUIT):
            time.sleep(THREAD_DUMP_SECONDS)

    def stop(self) -> None:
        """Quit the IDE and the Gradle run."""
        if self.config:
            stop_ide(self.config, self.process)

    def collect(self, destination: Path) -> None:
        """Copy the IDE log and the Gradle output next to the report."""
        destination.mkdir(parents=True, exist_ok=True)
        if self.gradle_log.is_file():
            shutil.copy2(self.gradle_log, destination / f"{self.scenario.name}-gradle.log")
        if self.config:
            log = self.config.parent / f"log_{self.scenario.gradle_task}" / "idea.log"
            if log.is_file():
                shutil.copy2(log, destination / f"{self.scenario.name}-idea.log")
            dumps = log.parent / "bg-wa"
            if dumps.is_dir():
                shutil.copytree(dumps, destination / f"{self.scenario.name}-thread-dumps", dirs_exist_ok=True)


def run_scenario(
    scenario: Scenario,
    report_dir: Path | None,
    ready_timeout: float,
    factory: Callable[[Scenario, Path], Platform] = Platform,
    run_timeout: float = 900,
) -> list[str]:
    """Run one scenario end to end and return its report lines; always stop the IDE and clean up."""
    work = Path(tempfile.mkdtemp(prefix=f"affected-live-{scenario.name}-")).resolve()
    machine = factory(scenario, work)
    failure: LiveIdeError | None = None
    report: list[str] = []
    try:
        project = work / "project"
        prepare_fixture(scenario, project)
        machine.prepare_sandbox(project)
        machine.launch(project)
        deadline = time.monotonic() + ready_timeout
        port = machine.port()
        while port is None:
            if not machine.alive():
                raise LiveIdeError("The IDE exited before its MCP server answered")
            if time.monotonic() > deadline:
                raise LiveIdeError(f"The MCP server did not answer within {ready_timeout:.0f}s")
            time.sleep(POLL_SECONDS)
            port = machine.port()
        report.append(f"mcp port: {port}")
        session = wait_ready(port, str(project), scenario, max(deadline - time.monotonic(), ready_timeout / 2), machine.alive)
        report += expectations(port, session, str(project), scenario, run_timeout)
    except (LiveIdeError, ide_smoke.SmokeError) as error:
        failure = error if isinstance(error, LiveIdeError) else LiveIdeError(str(error))
        report.append(f"FAILED: {failure}")
        machine.dump_threads()
    finally:
        machine.stop()
        if report_dir is not None:
            if failure is not None:
                machine.collect(report_dir)
            report_dir.mkdir(parents=True, exist_ok=True)
            (report_dir / f"{scenario.name}-report.txt").write_text("\n".join(report) + "\n", encoding="utf-8")
        shutil.rmtree(work, ignore_errors=True)
    if failure is not None:
        raise LiveIdeError("\n".join(report))
    return report


def main(argv: list[str] | None = None) -> int:
    """Run the named scenarios, or all of them, and print one report per scenario."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenarios", nargs="+", choices=[*SCENARIOS, "all"], help="scenario names, or all")
    parser.add_argument("--report-dir", type=Path, help="write reports, and on failure the IDE log, here")
    parser.add_argument("--ready-timeout", type=float, default=900, help="seconds to wait for the MCP server and the project model")
    arguments = parser.parse_args(argv)
    names = list(SCENARIOS) if "all" in arguments.scenarios else arguments.scenarios
    failed = 0
    for name in names:
        print(f"== {name}", flush=True)
        try:
            print("\n".join(run_scenario(SCENARIOS[name], arguments.report_dir, arguments.ready_timeout)), flush=True)
        except LiveIdeError as error:
            failed += 1
            print(f"live-ide: ERROR: {error}", file=sys.stderr, flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

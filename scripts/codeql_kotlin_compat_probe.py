#!/usr/bin/env python3
"""Prove the bounded Kotlin compiler rewrite used by manual CodeQL builds."""

from __future__ import annotations

import logging
import os
import signal
import subprocess
import sys
import time
from pathlib import Path
from tempfile import TemporaryDirectory

ROOT = Path(__file__).resolve().parents[1]
WRAPPER = ROOT / "gradlew"
SHIM = ROOT / "scripts/codeql-kotlin-compat.init.gradle"
COMPATIBILITY_VERSION = "2.4.10"
SOURCE_VERSION = "2.4.20-RC"
MARKER = (
    "CodeQL Kotlin compatibility: analyzing "
    f"{SOURCE_VERSION} with {COMPATIBILITY_VERSION}"
)
TIMEOUT_SECONDS = 180
TERMINATION_GRACE_SECONDS = 5
PIPE_DRAIN_SECONDS = 5
CANCELLATION_SIGNALS = {signal.SIGINT, signal.SIGTERM}


class ProbeError(RuntimeError):
    """Describe a behavioral compatibility proof failure."""


class ProbeCancelled(RuntimeError):
    """Stop a cancelled probe through its structured cleanup path."""


def verify_preconditions() -> None:
    """Reject missing or unsafe inputs before starting Gradle."""
    for path in (WRAPPER, SHIM):
        if not path.is_file() or path.is_symlink() or not os.access(path, os.R_OK):
            raise ProbeError(f"Required compatibility input is unavailable: {path}")
    if not os.access(WRAPPER, os.X_OK):
        raise ProbeError(f"Gradle wrapper is not executable: {WRAPPER}")


def write_fixture(project: Path, kotlin_version: str) -> None:
    """Write one isolated Kotlin project requesting the supplied plugin version."""
    source = project / "src/main/kotlin/fixture/Probe.kt"
    source.parent.mkdir(parents=True, exist_ok=True)
    (project / "settings.gradle").write_text(
        """pluginManagement {
    repositories {
        maven { url = uri('https://cache-redirector.jetbrains.com/plugins.gradle.org') }
        gradlePluginPortal()
    }
}
rootProject.name = 'codeql-kotlin-compat-probe'
""",
        encoding="utf-8",
    )
    (project / "build.gradle").write_text(
        f"""plugins {{
    id 'org.jetbrains.kotlin.jvm' version '{kotlin_version}'
}}

repositories {{
    maven {{ url = uri('https://cache-redirector.jetbrains.com/repo1.maven.org/maven2') }}
    mavenCentral()
}}

kotlin {{
    jvmToolchain(21)
}}

tasks.register('printKotlinPluginVersion') {{
    doLast {{
        def plugin = plugins.getPlugin('org.jetbrains.kotlin.jvm')
        println "PROBE_KOTLIN_PLUGIN=${{plugin.class.protectionDomain.codeSource.location}}"
    }}
}}
""",
        encoding="utf-8",
    )
    source.write_text("package fixture\n\nclass Probe\n", encoding="utf-8")


def process_group_exists(group_id: int) -> bool:
    """Return whether the owned process group still has a visible member."""
    try:
        os.killpg(group_id, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def signal_process_group(group_id: int, requested_signal: signal.Signals) -> None:
    """Signal the owned process group unless it has already disappeared."""
    try:
        os.killpg(group_id, requested_signal)
    except ProcessLookupError:
        pass


def block_cancellation_signals() -> set[signal.Signals]:
    """Defer SIGINT and SIGTERM across process ownership transitions."""
    return signal.pthread_sigmask(signal.SIG_BLOCK, CANCELLATION_SIGNALS)


def restore_signal_mask(previous: set[signal.Signals]) -> None:
    """Restore the caller's signal mask and deliver pending cancellation."""
    signal.pthread_sigmask(signal.SIG_SETMASK, previous)


def terminate_process_group(process: subprocess.Popen[str]) -> str:
    """Boundedly terminate Gradle, its process group and inherited output pipes."""
    previous_mask = block_cancellation_signals()
    try:
        group_id = process.pid
        signal_process_group(group_id, signal.SIGTERM)
        deadline = time.monotonic() + TERMINATION_GRACE_SECONDS
        while process_group_exists(group_id) and time.monotonic() < deadline:
            process.poll()
            time.sleep(0.05)
        if process_group_exists(group_id):
            signal_process_group(group_id, signal.SIGKILL)
        try:
            output, _ = process.communicate(timeout=PIPE_DRAIN_SECONDS)
        except subprocess.TimeoutExpired as error:
            signal_process_group(group_id, signal.SIGKILL)
            if process.stdout is not None:
                process.stdout.close()
            try:
                process.wait(timeout=1)
            except subprocess.TimeoutExpired:
                pass
            raise ProbeError(
                "Gradle compatibility cleanup could not drain its output pipe"
            ) from error
        return output
    finally:
        restore_signal_mask(previous_mask)


def cancel_probe(_signal: int, _frame: object) -> None:
    """Turn SIGTERM into structured cancellation so Gradle is reaped first."""
    raise ProbeCancelled("CodeQL Kotlin compatibility probe was cancelled")


def run_owned_process(
    arguments: list[str],
    cwd: Path,
    timeout_seconds: int,
) -> tuple[int, str]:
    """Start one process atomically with cancellation and await it boundedly."""
    previous_mask = block_cancellation_signals()
    try:
        process = subprocess.Popen(
            arguments,
            cwd=cwd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            start_new_session=True,
        )
    except BaseException:
        restore_signal_mask(previous_mask)
        raise
    try:
        restore_signal_mask(previous_mask)
        output, _ = process.communicate(timeout=timeout_seconds)
    except subprocess.TimeoutExpired as error:
        output = terminate_process_group(process)
        raise ProbeError(
            f"Gradle compatibility probe timed out after {timeout_seconds}s\n{output}"
        ) from error
    except (ProbeCancelled, KeyboardInterrupt, OSError):
        terminate_process_group(process)
        raise
    return process.returncode, output


def run_gradle(
    project: Path,
    kotlin_version: str,
    compatibility_version: str | None,
    build_cache: bool,
    tasks: tuple[str, ...],
) -> tuple[int, str]:
    """Run one bounded isolated Gradle case and capture its complete output."""
    write_fixture(project, kotlin_version)
    arguments = [
        str(WRAPPER),
        "-p",
        str(project),
        "--init-script",
        str(SHIM),
        "--no-daemon",
        "--console=plain",
        "--build-cache" if build_cache else "--no-build-cache",
    ]
    if compatibility_version is not None:
        arguments.append(
            f"-Paffected.codeql.kotlinPluginVersion={compatibility_version}"
        )
    arguments.extend(tasks)
    return run_owned_process(arguments, ROOT, TIMEOUT_SECONDS)


def require_success(project: Path) -> None:
    """Prove RC resolution, one marker, the selected plugin and compiled bytecode."""
    status, output = run_gradle(
        project,
        SOURCE_VERSION,
        COMPATIBILITY_VERSION,
        False,
        ("clean", "printKotlinPluginVersion", "compileKotlin"),
    )
    if status != 0:
        raise ProbeError(f"RC compatibility compilation failed\n{output}")
    if output.count(MARKER) != 1:
        raise ProbeError("RC compatibility must report exactly one rewrite marker")
    plugin_line = next(
        (line for line in output.splitlines() if line.startswith("PROBE_KOTLIN_PLUGIN=")),
        "",
    )
    if "kotlin-gradle-plugin-2.4.10" not in plugin_line:
        raise ProbeError(f"RC compatibility resolved an unexpected plugin: {plugin_line}")
    compiled = project / "build/classes/kotlin/main/fixture/Probe.class"
    if not compiled.is_file() or compiled.is_symlink():
        raise ProbeError("RC compatibility did not compile the Kotlin fixture")


def require_failure(
    project: Path,
    kotlin_version: str,
    compatibility_version: str | None,
    build_cache: bool,
    expected: str,
) -> None:
    """Prove one unsupported compatibility configuration fails with its reason."""
    status, output = run_gradle(
        project,
        kotlin_version,
        compatibility_version,
        build_cache,
        ("help",),
    )
    if status == 0 or expected not in output:
        raise ProbeError(
            f"Compatibility case did not fail closed with {expected!r}\n{output}"
        )


def probe() -> None:
    """Execute the successful rewrite and every bounded failure mode."""
    verify_preconditions()
    with TemporaryDirectory(prefix="affected-codeql-kotlin-") as directory:
        project = Path(directory)
        require_success(project)
        require_failure(
            project,
            SOURCE_VERSION,
            None,
            False,
            "CodeQL Kotlin compatibility version must be 2.4.10",
        )
        require_failure(
            project,
            SOURCE_VERSION,
            SOURCE_VERSION,
            False,
            "CodeQL Kotlin compatibility version must be 2.4.10",
        )
        require_failure(
            project,
            "2.4.0",
            COMPATIBILITY_VERSION,
            False,
            "Unsupported Kotlin version for CodeQL compatibility: 2.4.0",
        )
        require_failure(
            project,
            SOURCE_VERSION,
            COMPATIBILITY_VERSION,
            True,
            "CodeQL Kotlin compatibility requires the build cache to be disabled",
        )


def main() -> int:
    """Run the probe with one actionable terminal result."""
    logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
    previous_sigterm = signal.signal(signal.SIGTERM, cancel_probe)
    try:
        probe()
    except (KeyboardInterrupt, OSError, ProbeCancelled, ProbeError) as error:
        logging.error("%s", error)
        return 1
    finally:
        signal.signal(signal.SIGTERM, previous_sigterm)
    logging.info("CodeQL Kotlin compatibility probe passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())

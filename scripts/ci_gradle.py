"""Acquire immutable Gradle inputs, then execute CI work once and offline."""

from __future__ import annotations

import argparse
import hashlib
import math
import os
import re
import signal
import shutil
import stat
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import TextIO

ROOT = Path(__file__).resolve().parent.parent
WRAPPER_PROPERTIES = ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties"
GRADLE_FIXTURE_PREAMBLE = (
    ROOT
    / "collector"
    / "src"
    / "test"
    / "resources"
    / "gradle-injection-preamble.gradle"
)
GRADLE_9_SHA256 = "84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae"
GRADLE_8_VERSION = "8.14.5"
GRADLE_8_SHA256 = "6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854"
GRADLE_8_URL = (
    f"https://downloads.gradle.org/distributions/gradle-{GRADLE_8_VERSION}-bin.zip"
)
MAX_DOWNLOAD_BYTES = 256 * 1024 * 1024
MAX_DOWNLOAD_SECONDS = 180
MAX_LOG_BYTES = 1024 * 1024
MAX_ARCHIVE_ENTRIES = 50_000
MAX_EXTRACTED_BYTES = 1024 * 1024 * 1024
TASK = re.compile(r"^:[A-Za-z0-9_.:-]+$")
HTTP_STATUS = re.compile(
    r"(?i)\b(?:HTTP(?: response code:)?\s*|received status code\s+)([45][0-9]{2})\b"
)
TRANSIENT_MARKERS = (
    "bad gateway",
    "connection reset",
    "connection timed out",
    "could not resolve host",
    "gateway timeout",
    "remote host terminated the handshake",
    "service unavailable",
    "temporary failure",
    "unexpected end of file",
)
TERMINAL_MARKERS = (
    "checksum verification failed",
    "verification failed for",
    "does not match the expected sha-256",
)
RESTRICTED_ARGUMENTS = (
    "--build-file",
    "-b",
    "--daemon",
    "--gradle-user-home",
    "-g",
    "--include-build",
    "--init-script",
    "-I",
    "--project-cache-dir",
    "--project-dir",
    "-p",
    "--settings-file",
    "-c",
)
RESTRICTED_ARGUMENT_PREFIXES = (
    "-Dgradle.user.home=",
    "-Paffected.ci.tasks=",
    "-Paffected.test.gradle8Distribution=",
    "-Paffected.test.gradleTestKitDir=",
    "-Porg.jetbrains.intellij.platform.useCacheRedirector=",
)
ALLOWED_ARGUMENTS = {
    "-q",
    "--quiet",
    "--rerun-tasks",
    "--no-build-cache",
    "--no-daemon",
    "--no-parallel",
    "--max-workers=1",
}
ALLOWED_PROPERTIES = (
    re.compile(r"^-Paffected\.cliConformance=true$"),
    re.compile(r"^-Paffected\.conformance=true$"),
    re.compile(r"^-Paffected\.test\.gradle8=(?:true|false)$"),
    re.compile(r"^-Paffected\.test\.javaVersion=[0-9]+$"),
    re.compile(r"^-Paffected\.test\.symlinkMode=(?:optional|required)$"),
    re.compile(r"^-Daffected\.phpunitVersion=[0-9]+\.[0-9]+\.[0-9]+$"),
)


class CiGradleError(RuntimeError):
    """Describe a fail-closed CI acquisition or invocation error."""


class GradleRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Allow a distribution redirect only within the fixed official endpoint."""

    def redirect_request(
        self,
        request: object,
        fp: object,
        code: int,
        message: str,
        headers: object,
        new_url: str,
    ) -> object:
        """Validate the redirect target before following it."""
        validate_download_url(new_url)
        return super().redirect_request(request, fp, code, message, headers, new_url)


@dataclass(frozen=True)
class ProcessResult:
    """Contain one process exit code and its bounded diagnostic tail."""

    returncode: int
    output: str


Runner = Callable[[list[str], dict[str, str]], ProcessResult]
Prepare = Callable[[dict[str, str]], list[str]]


def process_group_options() -> dict[str, bool | int]:
    """Return platform options that isolate the Gradle process tree."""
    if os.name == "nt":
        return {"creationflags": getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0x200)}
    return {"start_new_session": True}


def remaining_timeout(deadline: float, maximum: int) -> int:
    """Return a positive whole-second timeout within one total deadline."""
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise CiGradleError("CI Gradle invocation exceeded its total timeout")
    return min(maximum, max(1, math.ceil(remaining)))


def terminate_process_tree(process: subprocess.Popen[str], force: bool) -> None:
    """Terminate one isolated Gradle process tree without widening the target."""
    if os.name == "nt":
        command = ["taskkill", "/PID", str(process.pid), "/T"]
        if force:
            command.append("/F")
        try:
            result = subprocess.run(
                command,
                check=False,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=10,
            )
            if result.returncode != 0:
                raise CiGradleError(
                    f"Unable to terminate Gradle process tree {process.pid}"
                )
        except (OSError, subprocess.TimeoutExpired) as error:
            raise CiGradleError(
                f"Unable to terminate Gradle process tree {process.pid}"
            ) from error
        return
    try:
        os.killpg(process.pid, signal.SIGKILL if force else signal.SIGTERM)
    except ProcessLookupError:
        return


def stop_timed_out_process(process: subprocess.Popen[str]) -> None:
    """Stop a timed-out Gradle process and every descendant after a short grace."""
    if os.name == "nt":
        terminate_process_tree(process, force=True)
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired as error:
            raise CiGradleError(
                f"Gradle process tree {process.pid} did not terminate"
            ) from error
        return
    terminate_process_tree(process, force=False)
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        pass
    finally:
        terminate_process_tree(process, force=True)
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired as error:
        raise CiGradleError(
            f"Gradle process tree {process.pid} did not terminate"
        ) from error


def read_wrapper_properties() -> dict[str, str]:
    """Read the committed non-symlink Gradle wrapper properties."""
    if WRAPPER_PROPERTIES.is_symlink() or not WRAPPER_PROPERTIES.is_file():
        raise CiGradleError("Missing or unsafe Gradle wrapper properties")
    properties: dict[str, str] = {}
    for raw_line in WRAPPER_PROPERTIES.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not key:
            raise CiGradleError(f"Malformed Gradle wrapper property: {raw_line}")
        if key in properties:
            raise CiGradleError(f"Duplicate Gradle wrapper property: {key}")
        properties[key] = value
    return properties


def validate_task_names(tasks: list[str], arguments: list[str]) -> None:
    """Require a bounded exact task inventory present in the real invocation."""
    if (
        not tasks
        or len(tasks) > 32
        or len(set(tasks)) != len(tasks)
        or any(
            not TASK.fullmatch(task) or task == ":acquireCiDependencies"
            for task in tasks
        )
    ):
        raise CiGradleError(
            "CI Gradle tasks must be a non-empty bounded exact inventory"
        )
    if len(arguments) > 128 or any(
        not argument or len(argument) > 4_096 for argument in arguments
    ):
        raise CiGradleError("CI Gradle arguments exceed their bounded inventory")
    actual: list[str] = []
    consume_value = False
    for argument in arguments:
        restricted = (
            argument in RESTRICTED_ARGUMENTS
            or any(argument.startswith(f"{option}=") for option in RESTRICTED_ARGUMENTS)
            or any(
                argument.startswith(prefix) for prefix in RESTRICTED_ARGUMENT_PREFIXES
            )
        )
        if restricted:
            raise CiGradleError(
                f"CI Gradle argument is controlled by the driver: {argument}"
            )
        if consume_value:
            consume_value = False
            continue
        if argument == "--tests":
            consume_value = True
            continue
        if TASK.fullmatch(argument):
            actual.append(argument)
            continue
        if argument in ALLOWED_ARGUMENTS or any(
            pattern.fullmatch(argument) for pattern in ALLOWED_PROPERTIES
        ):
            continue
        raise CiGradleError(f"Unsupported CI Gradle argument: {argument}")
    if consume_value:
        raise CiGradleError("Gradle option --tests requires a value")
    if actual != tasks:
        raise CiGradleError(
            f"Acquisition tasks {tasks} do not match invocation tasks {actual}"
        )


def transport_acquisition_failure(output: str) -> bool:
    """Recognize a bounded transport-only dependency acquisition failure."""
    lowered = output.lower()
    if any(marker in lowered for marker in TERMINAL_MARKERS):
        return False
    statuses = [int(match) for match in HTTP_STATUS.findall(output)]
    if statuses:
        return all(status == 429 or status >= 500 for status in statuses)
    return any(marker in lowered for marker in TRANSIENT_MARKERS)


def redirector_acquisition_failure(output: str) -> bool:
    """Recognize a transient failure from the JetBrains cache redirector only."""
    return (
        "cache-redirector.jetbrains.com" in output.lower()
        and transport_acquisition_failure(output)
    )


def gradle_wrapper() -> str:
    """Return the repository wrapper executable for the current platform."""
    return str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))


def acquisition_command(tasks: list[str], direct: bool) -> list[str]:
    """Build the dependency-only Gradle preflight command."""
    return [
        gradle_wrapper(),
        "acquireCiDependencies",
        f"-Paffected.ci.tasks={','.join(tasks)}",
        f"-Porg.jetbrains.intellij.platform.useCacheRedirector={'false' if direct else 'true'}",
        "--no-daemon",
        "--no-parallel",
        "--max-workers=1",
    ]


def execution_command(arguments: list[str], direct: bool) -> list[str]:
    """Build the single deterministic offline Gradle invocation."""
    command = [gradle_wrapper(), *arguments]
    if "--offline" not in command:
        command.append("--offline")
    if "--no-daemon" not in command:
        command.append("--no-daemon")
    command.append(
        f"-Porg.jetbrains.intellij.platform.useCacheRedirector={'false' if direct else 'true'}"
    )
    return command


def run_gradle(
    tasks: list[str],
    arguments: list[str],
    runner: Runner,
    fallback_root: Path,
    prepare: Prepare | None = None,
) -> int:
    """Acquire dependencies with bounded fallback, then execute once offline."""
    validate_task_names(tasks, arguments)

    def execute(environment: dict[str, str], direct: bool) -> int:
        """Prepare specialized inputs, then run the requested Gradle work once."""
        prepared_arguments = [*arguments, *(prepare(environment) if prepare else [])]
        return runner(
            execution_command(prepared_arguments, direct), environment
        ).returncode

    base_environment = dict(os.environ)
    first = runner(acquisition_command(tasks, direct=False), base_environment)
    if first.returncode == 0:
        return execute(base_environment, direct=False)
    if not redirector_acquisition_failure(first.output):
        return first.returncode

    fallback_root = fallback_root.absolute()
    if fallback_root.is_symlink():
        raise CiGradleError(f"Fallback root must not be a symlink: {fallback_root}")
    fallback_root.mkdir(parents=True, exist_ok=True)
    fallback_root = fallback_root.resolve(strict=True)
    fallback_home = Path(tempfile.mkdtemp(prefix="gradle-home-", dir=fallback_root))
    if not fallback_home.is_dir() or not os.access(fallback_home, os.W_OK):
        raise CiGradleError(f"Fallback Gradle home is not writable: {fallback_home}")
    fallback_environment = dict(base_environment)
    fallback_environment["GRADLE_USER_HOME"] = str(fallback_home)
    try:
        time.sleep(1)
        fallback = runner(acquisition_command(tasks, direct=True), fallback_environment)
        if fallback.returncode != 0:
            return fallback.returncode
        return execute(fallback_environment, direct=True)
    finally:
        try:
            shutil.rmtree(fallback_home)
        except OSError as error:
            raise CiGradleError(
                f"Unable to remove fallback Gradle home: {fallback_home}"
            ) from error


def validate_download_url(url: str) -> None:
    """Restrict downloads to the official Gradle release-asset redirect chain."""
    parsed = urllib.parse.urlparse(url)
    safe = False
    if parsed.scheme == "https" and not parsed.username and not parsed.password:
        if parsed.hostname == "downloads.gradle.org":
            safe = bool(
                parsed.path == f"/distributions/gradle-{GRADLE_8_VERSION}-bin.zip"
                and not parsed.query
                and not parsed.fragment
            )
        elif parsed.hostname == "github.com":
            match = re.fullmatch(
                r"/gradle/gradle-distributions/releases/download/v([0-9.]+)/"
                r"gradle-([0-9.]+)-bin\.zip",
                parsed.path,
            )
            safe = bool(
                match
                and match.group(1) == match.group(2)
                and match.group(1) == GRADLE_8_VERSION
                and not parsed.query
                and not parsed.fragment
            )
        elif parsed.hostname == "release-assets.githubusercontent.com":
            safe = bool(
                re.fullmatch(
                    r"/github-production-release-asset/696192900/"
                    r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                    parsed.path,
                )
                and not parsed.fragment
            )
    if not safe:
        raise CiGradleError(f"Untrusted Gradle distribution URL: {url}")


def verified_file(
    path: Path,
    expected_sha256: str,
    maximum_bytes: int = MAX_DOWNLOAD_BYTES,
    deadline: float | None = None,
) -> bool:
    """Return whether a regular non-symlink file has the expected SHA-256."""
    if path.is_symlink() or not path.is_file():
        return False
    if path.stat().st_size > maximum_bytes:
        return False
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            if deadline is not None:
                remaining_timeout(deadline, 30)
            size += len(chunk)
            if size > maximum_bytes:
                return False
            digest.update(chunk)
    return digest.hexdigest() == expected_sha256


def retryable_download_error(error: Exception) -> bool:
    """Return whether an official distribution transport error is transient."""
    if isinstance(error, urllib.error.HTTPError):
        return error.code == 429 or error.code >= 500
    return isinstance(
        error,
        (
            urllib.error.URLError,
            TimeoutError,
            ConnectionError,
        ),
    )


def download_verified(
    url: str,
    expected_sha256: str,
    target: Path,
    opener: urllib.request.OpenerDirector | object | None = None,
    deadline: float | None = None,
) -> None:
    """Download an official distribution atomically with bounded transport retries."""
    validate_download_url(url)
    if not re.fullmatch(r"[0-9a-f]{64}", expected_sha256):
        raise CiGradleError(
            "Expected Gradle distribution checksum must be lowercase SHA-256"
        )
    target = target.absolute()
    if target.is_symlink():
        raise CiGradleError(
            f"Gradle distribution target must not be a symlink: {target}"
        )
    target.parent.mkdir(parents=True, exist_ok=True)
    target = target.parent.resolve(strict=True) / target.name
    if target.exists():
        if verified_file(target, expected_sha256, deadline=deadline):
            return
        if target.is_dir():
            raise CiGradleError(f"Gradle distribution target is a directory: {target}")
        target.unlink()
    if not target.parent.is_dir() or not os.access(target.parent, os.W_OK):
        raise CiGradleError(
            f"Gradle distribution directory is not writable: {target.parent}"
        )
    partial = target.with_name(f"{target.name}.part")
    if partial.is_symlink():
        raise CiGradleError(
            f"Gradle distribution partial file must not be a symlink: {partial}"
        )
    if partial.exists():
        partial.unlink()
    transport = opener or urllib.request.build_opener(GradleRedirectHandler())
    request = urllib.request.Request(
        url, headers={"Accept": "application/zip"}, method="GET"
    )
    started = time.monotonic()
    download_deadline = min(
        started + MAX_DOWNLOAD_SECONDS,
        deadline if deadline is not None else float("inf"),
    )
    for attempt in range(3):
        digest = hashlib.sha256()
        size = 0
        try:
            with (
                transport.open(
                    request,
                    timeout=remaining_timeout(download_deadline, 30),
                ) as response,
                partial.open("xb") as output,
            ):
                while True:
                    remaining_timeout(download_deadline, 30)
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    size += len(chunk)
                    if size > MAX_DOWNLOAD_BYTES:
                        raise CiGradleError(
                            f"Gradle distribution exceeds {MAX_DOWNLOAD_BYTES} bytes"
                        )
                    digest.update(chunk)
                    output.write(chunk)
                output.flush()
                os.fsync(output.fileno())
            if digest.hexdigest() != expected_sha256:
                partial.unlink(missing_ok=True)
                raise CiGradleError(
                    "Gradle distribution does not match the expected SHA-256"
                )
            partial.replace(target)
            return
        except CiGradleError:
            partial.unlink(missing_ok=True)
            raise
        except Exception as error:
            partial.unlink(missing_ok=True)
            if not retryable_download_error(error) or attempt == 2:
                raise CiGradleError(
                    f"Unable to download Gradle distribution: {error}"
                ) from error
            delay = 2**attempt
            if time.monotonic() + delay >= download_deadline:
                raise CiGradleError(
                    "Gradle distribution download exhausted its total timeout"
                ) from error
            time.sleep(delay)
    raise CiGradleError("Unable to download Gradle distribution")


def extract_gradle_distribution(
    archive: Path,
    destination: Path,
    deadline: float | None = None,
) -> Path:
    """Extract a verified Gradle archive atomically within a bounded directory."""
    if archive.is_symlink() or not archive.is_file():
        raise CiGradleError(f"Missing or unsafe Gradle distribution: {archive}")
    destination = destination.absolute()
    if destination.is_symlink():
        raise CiGradleError(f"Gradle installation must not be a symlink: {destination}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination = destination.parent.resolve(strict=True) / destination.name
    executable_name = "gradle.bat" if os.name == "nt" else "gradle"
    executable = destination / f"gradle-{GRADLE_8_VERSION}" / "bin" / executable_name
    if destination.exists():
        if executable.is_file() and not executable.is_symlink():
            return executable
        raise CiGradleError(f"Incomplete Gradle installation: {destination}")
    staging = destination.with_name(f"{destination.name}.part")
    if staging.is_symlink() or staging.exists():
        raise CiGradleError(f"Unsafe Gradle extraction staging path: {staging}")
    staging.mkdir()
    try:
        with zipfile.ZipFile(archive) as source:
            entries = source.infolist()
            if (
                not entries
                or len(entries) > MAX_ARCHIVE_ENTRIES
                or sum(entry.file_size for entry in entries) > MAX_EXTRACTED_BYTES
            ):
                raise CiGradleError("Gradle distribution extraction exceeds bounds")
            expected_root = f"gradle-{GRADLE_8_VERSION}"
            for entry in entries:
                if deadline is not None:
                    remaining_timeout(deadline, 30)
                relative = PurePosixPath(entry.filename)
                mode = entry.external_attr >> 16
                if (
                    entry.flag_bits & 1
                    or relative.is_absolute()
                    or "\\" in entry.filename
                    or not relative.parts
                    or relative.parts[0] != expected_root
                    or any(part in ("", ".", "..") for part in relative.parts)
                    or stat.S_ISLNK(mode)
                ):
                    raise CiGradleError(
                        f"Unsafe Gradle distribution entry: {entry.filename}"
                    )
                target = staging.joinpath(*relative.parts)
                if entry.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.open(entry) as input_stream, target.open("xb") as output:
                    while True:
                        if deadline is not None:
                            remaining_timeout(deadline, 30)
                        chunk = input_stream.read(1024 * 1024)
                        if not chunk:
                            break
                        output.write(chunk)
                if os.name != "nt" and mode:
                    target.chmod(mode & 0o777)
        staged_executable = staging / expected_root / "bin" / executable_name
        if staged_executable.is_symlink() or not staged_executable.is_file():
            raise CiGradleError("Gradle distribution has no executable")
        staging.replace(destination)
        return executable
    except Exception as error:
        shutil.rmtree(staging, ignore_errors=True)
        if isinstance(error, CiGradleError):
            raise
        if isinstance(error, (OSError, zipfile.BadZipFile)):
            raise CiGradleError(f"Malformed Gradle distribution: {archive}") from error
        raise


def needs_gradle_eight(tasks: list[str], arguments: list[str]) -> bool:
    """Return whether the selected collector tests require the Gradle 8 fixture."""
    return any(
        task.endswith(":collector:test") or task == ":collector:test" for task in tasks
    ) and not any(argument == "-Paffected.test.gradle8=false" for argument in arguments)


def needs_gradle_fixture(tasks: list[str]) -> bool:
    """Return whether selected tests execute a nested Gradle fixture."""
    return ":collector:test" in tasks


def warm_gradle_fixture(
    runner: Runner,
    root: Path,
    environment: dict[str, str],
    executables: list[Path],
) -> Path:
    """Resolve the exact nested fixture graph for every selected Gradle line."""
    if GRADLE_FIXTURE_PREAMBLE.is_symlink() or not GRADLE_FIXTURE_PREAMBLE.is_file():
        raise CiGradleError("Missing or unsafe Gradle fixture dependency declaration")
    workspace = root.resolve() / "gradle-eight-fixture"
    workspace.mkdir(parents=True, exist_ok=True)
    if not workspace.is_dir() or not os.access(workspace, os.W_OK):
        raise CiGradleError(f"Gradle fixture workspace is not writable: {workspace}")
    (workspace / "settings.gradle").write_text(
        "rootProject.name = 'acquire-fixture'\n", encoding="utf-8"
    )
    (workspace / "build.gradle").write_bytes(GRADLE_FIXTURE_PREAMBLE.read_bytes())
    testkit = Path(
        environment.get("GRADLE_USER_HOME", Path.home() / ".gradle")
    ).resolve()
    testkit.mkdir(parents=True, exist_ok=True)
    fixture_environment = dict(environment)
    fixture_environment["GRADLE_USER_HOME"] = str(testkit)
    if not executables or len(executables) > 2:
        raise CiGradleError("Gradle fixture acquisition requires one or two versions")
    for executable in executables:
        command = [
            str(executable),
            "--project-dir",
            str(workspace),
            "acquireFixtureDependencies",
            "-Paffected.fixtureAcquisition=true",
            "--no-daemon",
            "--no-parallel",
            "--max-workers=1",
        ]
        last = ProcessResult(1, "fixture acquisition did not start")
        for attempt in range(3):
            if attempt:
                time.sleep(2 ** (attempt - 1))
            last = runner(command, fixture_environment)
            if last.returncode == 0:
                break
            if not transport_acquisition_failure(last.output):
                raise CiGradleError(
                    f"Gradle fixture acquisition failed with exit code {last.returncode}"
                )
        else:
            raise CiGradleError(
                "Gradle fixture acquisition failed after 3 attempts: "
                f"{last.output[-1000:]}"
            )
    return testkit


def read_output(stream: TextIO, destination: TextIO, tail: bytearray) -> None:
    """Stream process output while retaining only a bounded UTF-8 diagnostic tail."""
    for line in iter(stream.readline, ""):
        destination.write(line)
        destination.flush()
        tail.extend(line.encode("utf-8", errors="replace"))
        if len(tail) > MAX_LOG_BYTES:
            del tail[: len(tail) - MAX_LOG_BYTES]


def run_process(
    command: list[str], environment: dict[str, str], timeout_seconds: int
) -> ProcessResult:
    """Run one Gradle process with streamed diagnostics and a hard timeout."""
    print(f"INFO: running {' '.join(command)}", flush=True)
    process = subprocess.Popen(
        command,
        cwd=ROOT,
        env=environment,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        **process_group_options(),
    )
    if process.stdout is None:
        process.kill()
        raise CiGradleError("Gradle process output pipe was not created")
    tail = bytearray()
    reader = threading.Thread(
        target=read_output, args=(process.stdout, sys.stdout, tail), daemon=True
    )
    reader.start()
    try:
        returncode = process.wait(timeout=timeout_seconds)
    except subprocess.TimeoutExpired as error:
        stop_timed_out_process(process)
        raise CiGradleError(
            f"Gradle process exceeded {timeout_seconds} seconds"
        ) from error
    except BaseException:
        stop_timed_out_process(process)
        raise
    finally:
        reader.join(timeout=5)
        if reader.is_alive():
            process.stdout.close()
            reader.join(timeout=5)
        else:
            process.stdout.close()
        if reader.is_alive():
            raise CiGradleError("Gradle process output did not close")
    return ProcessResult(returncode, tail.decode("utf-8", errors="replace"))


def parse_arguments(arguments: list[str]) -> argparse.Namespace:
    """Parse exact acquisition tasks and the unmodified Gradle invocation."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--task", action="append", required=True, dest="tasks")
    parser.add_argument("--timeout", type=int, default=2400)
    parser.add_argument("gradle_arguments", nargs=argparse.REMAINDER)
    parsed = parser.parse_args(arguments)
    if parsed.gradle_arguments[:1] == ["--"]:
        parsed.gradle_arguments = parsed.gradle_arguments[1:]
    if not parsed.gradle_arguments or parsed.timeout < 1 or parsed.timeout > 3600:
        parser.error(
            "Gradle arguments and a timeout from 1 to 3600 seconds are required"
        )
    validate_task_names(parsed.tasks, parsed.gradle_arguments)
    return parsed


def main(arguments: list[str] | None = None) -> int:
    """Run the acquisition-only preflight and one offline Gradle invocation."""
    parsed = parse_arguments(arguments if arguments is not None else sys.argv[1:])
    deadline = time.monotonic() + parsed.timeout
    testkit_version = _governed_testkit_version()
    if testkit_version != GRADLE_8_VERSION:
        raise CiGradleError(
            f"Gradle TestKit pin {testkit_version} does not match acquired distribution {GRADLE_8_VERSION}"
        )
    properties = read_wrapper_properties()
    if (
        properties.get("retries") != "2"
        or properties.get("retryBackOffMs") != "500"
        or properties.get("distributionSha256Sum") != GRADLE_9_SHA256
        or properties.get("validateDistributionUrl") != "true"
    ):
        raise CiGradleError("Gradle wrapper retry or checksum policy is not current")
    temp_root = (
        Path(os.environ.get("RUNNER_TEMP", tempfile.gettempdir()))
        / "affected-ci-gradle"
    )
    temp_root.mkdir(parents=True, exist_ok=True)
    gradle_arguments = list(parsed.gradle_arguments)
    gradle_eight_executable: Path | None = None
    prepared_arguments: list[str] = []

    def runner(command: list[str], environment: dict[str, str]) -> ProcessResult:
        """Use only the remaining total budget for each subprocess."""
        acquisition = any("acquire" in argument.lower() for argument in command)
        return run_process(
            command,
            environment,
            remaining_timeout(deadline, 300 if acquisition else parsed.timeout),
        )

    if needs_gradle_eight(parsed.tasks, gradle_arguments):
        distribution = temp_root / f"gradle-{GRADLE_8_VERSION}-bin.zip"
        download_verified(
            GRADLE_8_URL,
            GRADLE_8_SHA256,
            distribution,
            deadline=deadline,
        )
        gradle_eight_executable = extract_gradle_distribution(
            distribution,
            temp_root / f"gradle-{GRADLE_8_VERSION}-install",
            deadline=deadline,
        )
        prepared_arguments.append(
            f"-Paffected.test.gradle8Distribution={distribution.as_uri()}"
        )

    def prepare(environment: dict[str, str]) -> list[str]:
        """Warm nested Gradle dependencies in the selected verified cache."""
        if not needs_gradle_fixture(parsed.tasks):
            return list(prepared_arguments)
        executables = [Path(gradle_wrapper())]
        if gradle_eight_executable is not None:
            executables.append(gradle_eight_executable)
        testkit = warm_gradle_fixture(runner, temp_root, environment, executables)
        return [
            *prepared_arguments,
            f"-Paffected.test.gradleTestKitDir={testkit}",
        ]

    return run_gradle(
        parsed.tasks,
        gradle_arguments,
        runner,
        temp_root / "fallback",
        prepare,
    )


def _governed_testkit_version() -> str:
    """Extract the one governed Gradle TestKit compatibility version."""
    source = (
        ROOT
        / "collector"
        / "src"
        / "test"
        / "java"
        / "com"
        / "aspix2k"
        / "affected"
        / "collector"
        / "GradleInjectionTest.java"
    )
    if source.is_symlink() or not source.is_file():
        raise CiGradleError("Missing or unsafe Gradle TestKit source")
    values = set(
        re.findall(
            r'execute\([^;]*?,\s*"([0-9]+\.[0-9.]+)"\s*\)',
            source.read_text(encoding="utf-8"),
            re.DOTALL,
        )
    )
    if len(values) != 1:
        raise CiGradleError(
            f"Expected one Gradle TestKit version, found {sorted(values)}"
        )
    return values.pop()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except CiGradleError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1) from error

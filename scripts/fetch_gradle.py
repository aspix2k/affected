"""Seed the Gradle wrapper cache from GitHub before services.gradle.org."""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import sys
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from pathlib import Path

CHUNK = 1024 * 1024
MAX_ATTEMPTS = 4
OFFICIAL_HOST = "services.gradle.org"
GITHUB_DISTRIBUTIONS = "https://github.com/gradle/gradle-distributions/releases/download"
WRAPPER = Path("gradle/wrapper/gradle-wrapper.properties")
DIST_NAME = re.compile(r"gradle-([0-9][0-9A-Za-z.-]+)-(bin|all)\.zip$")


class FetchGradleError(RuntimeError):
    """Describe a fail-closed Gradle distribution fetch failure."""


class TransientFetchError(FetchGradleError):
    """A retryable download or CDN failure."""


def gradle_user_home() -> Path:
    """Return the Gradle user home that the wrapper will search."""
    override = os.environ.get("GRADLE_USER_HOME")
    return Path(override) if override else Path.home() / ".gradle"


def unescape_property(value: str) -> str:
    """Undo Java properties escaping used in gradle-wrapper.properties."""
    return value.replace(r"\:", ":").replace(r"\=", "=").replace(r"\\", "\\")


def read_wrapper(root: Path) -> tuple[str, str]:
    """Read the official distribution URL and its SHA-256 from the wrapper."""
    path = root / WRAPPER
    if not path.is_file() or path.is_symlink():
        raise FetchGradleError(f"Missing Gradle wrapper properties: {path}")
    url = ""
    digest = ""
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line.startswith("distributionUrl="):
            url = unescape_property(line.split("=", 1)[1])
        elif line.startswith("distributionSha256Sum="):
            digest = line.split("=", 1)[1].strip().lower()
    if not url.startswith("https://"):
        raise FetchGradleError("Gradle distributionUrl must be an https URL")
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise FetchGradleError("Gradle wrapper is missing a SHA-256 checksum")
    return url, digest


def distribution_hash(url: str) -> str:
    """Return the wrapper cache directory name Gradle uses for a URL."""
    digest = hashlib.md5(url.encode("ascii"), usedforsecurity=False).digest()
    value = int.from_bytes(digest, "big")
    alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
    chars: list[str] = []
    while value:
        value, remainder = divmod(value, 36)
        chars.append(alphabet[remainder])
    return "".join(reversed(chars)) or "0"


def cache_dir(user_home: Path, url: str) -> Path:
    """Return ~/.gradle/wrapper/dists/<name>/<url-hash>."""
    filename = url.rsplit("/", 1)[-1]
    return user_home / "wrapper" / "dists" / filename.removesuffix(".zip") / distribution_hash(url)


def github_mirror(url: str) -> str:
    """Map an official Gradle zip URL onto the GitHub distributions release."""
    filename = url.rsplit("/", 1)[-1]
    match = DIST_NAME.fullmatch(filename)
    if match is None:
        raise FetchGradleError(f"Unsupported Gradle distribution name: {filename}")
    return f"{GITHUB_DISTRIBUTIONS}/v{match.group(1)}/{filename}"


def candidate_urls(official: str) -> list[str]:
    """Prefer GitHub release assets, then the official Gradle CDN."""
    urls = [github_mirror(official)]
    if official not in urls:
        urls.append(official)
    return urls


def sha256_file(path: Path) -> str:
    """Hash one file as lowercase hex SHA-256."""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(CHUNK)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def already_cached(directory: Path, filename: str, expected: str) -> bool:
    """Return whether the wrapper can reuse an existing verified zip or unpack."""
    archive = directory / filename
    marker = directory / f"{filename}.ok"
    unpacked = directory / filename.removesuffix("-bin.zip").removesuffix("-all.zip")
    if archive.is_file() and sha256_file(archive) == expected:
        return True
    return marker.is_file() and unpacked.is_dir()


def download(url: str, destination: Path, opener: urllib.request.OpenerDirector) -> None:
    """Download one URL to destination, raising TransientFetchError on CDN faults."""
    request = urllib.request.Request(url, headers={"User-Agent": "affected-ci"})
    try:
        with opener.open(request, timeout=60) as response:
            status = getattr(response, "status", 200)
            if status >= 500:
                raise TransientFetchError(f"{url} returned HTTP {status}")
            if status != 200:
                raise FetchGradleError(f"{url} returned HTTP {status}")
            partial = destination.with_name(destination.name + ".part")
            with partial.open("wb") as handle:
                while True:
                    chunk = response.read(CHUNK)
                    if not chunk:
                        break
                    handle.write(chunk)
            partial.replace(destination)
    except urllib.error.HTTPError as error:
        if error.code >= 500:
            raise TransientFetchError(f"{url} returned HTTP {error.code}") from error
        raise FetchGradleError(f"{url} returned HTTP {error.code}") from error
    except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as error:
        raise TransientFetchError(f"{url} failed: {error}") from error


def fetch(
    root: Path,
    user_home: Path | None = None,
    opener: urllib.request.OpenerDirector | None = None,
    sleeper: Callable[[float], None] = time.sleep,
) -> Path:
    """Ensure the wrapper distribution exists locally and matches the checksum."""
    url, expected = read_wrapper(root)
    filename = url.rsplit("/", 1)[-1]
    directory = cache_dir(user_home or gradle_user_home(), url)
    directory.mkdir(parents=True, exist_ok=True)
    archive = directory / filename
    if already_cached(directory, filename, expected):
        return archive
    client = opener or urllib.request.build_opener()
    last_error: Exception | None = None
    for candidate in candidate_urls(url):
        for attempt in range(1, MAX_ATTEMPTS + 1):
            try:
                download(candidate, archive, client)
                actual = sha256_file(archive)
                if actual != expected:
                    archive.unlink(missing_ok=True)
                    raise FetchGradleError(
                        f"Gradle zip checksum mismatch for {candidate}: {actual}"
                    )
                return archive
            except TransientFetchError as error:
                last_error = error
                if attempt < MAX_ATTEMPTS:
                    sleeper(2 ** attempt)
    raise FetchGradleError(
        f"Could not fetch {filename} after {MAX_ATTEMPTS} attempts: {last_error}"
    )


def main(arguments: list[str] | None = None) -> int:
    """Seed the local Gradle wrapper cache from a GitHub-first URL list."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    options = parser.parse_args(arguments)
    try:
        archive = fetch(options.root)
    except FetchGradleError as error:
        print(f"Gradle fetch error: {error}", file=sys.stderr)
        return 1
    print(f"Gradle distribution is ready: {archive}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

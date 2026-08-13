#!/usr/bin/env python3

"""Fail a release when a governed direct dependency pin is stale or unverifiable."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / "config" / "release-currentness.json"
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
MAX_TOTAL_BYTES = 32 * 1024 * 1024
MAX_RELEASE_ASSET_BYTES = 32 * 1024 * 1024
MAX_ENTRIES = 128
ALLOWED_HOSTS = {
    "api.github.com",
    "api.nuget.org",
    "artifacts-caching-proxy.aws.intellij.net",
    "azuresearch-usnc.nuget.org",
    "builds.dotnet.microsoft.com",
    "cache.ruby-lang.org",
    "getcomposer.org",
    "go.dev",
    "jb.gg",
    "nodejs.org",
    "packages.jetbrains.team",
    "plugins.gradle.org",
    "pypi.org",
    "registry.npmjs.org",
    "repo.maven.apache.org",
    "repo.packagist.org",
    "rubygems.org",
    "services.gradle.org",
    "static.rust-lang.org",
    "teamcity.jetbrains.com",
    "www.php.net",
    "www.python.org",
}
UNSTABLE = re.compile(
    r"(?i)(?:^|[.\-])(?:a(?=[.\-]?\d)|b(?=[.\-]?\d)|alpha|beta|rc|preview|eap|milestone|snapshot|dev|canary|m(?=\d))(?:[.\-]|\d|$)"
)
VERSION = re.compile(r"^[vV]?(\d+(?:\.\d+)+(?:[-+][0-9A-Za-z.-]+)?)$")
SHA = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")


class CurrentnessError(RuntimeError):
    """Describe a fail-closed currentness validation failure."""


class SafeRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Reject redirects outside the fixed official-host allowlist."""

    def redirect_request(self, req: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str) -> Any:
        """Validate the redirect target before urllib follows it."""
        validate_url(newurl)
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected is not None and urllib.parse.urlparse(newurl).hostname != "api.github.com":
            redirected.remove_header("Authorization")
        return redirected


class Transport:
    """Perform bounded GET requests against official release endpoints."""

    def __init__(self) -> None:
        """Create an empty bounded-response accounting state."""
        self.total_bytes = 0
        self.cache: dict[str, bytes] = {}
        self.opener = urllib.request.build_opener(SafeRedirectHandler())

    def read(self, url: str) -> bytes:
        """Read one official endpoint with bounded retries, time, and bytes."""
        validate_url(url)
        if url in self.cache:
            return self.cache[url]
        headers = {"Accept": "application/json, application/xml, text/xml, text/plain"}
        token = os.environ.get("GH_TOKEN")
        if token and urllib.parse.urlparse(url).hostname == "api.github.com":
            headers["Authorization"] = f"Bearer {token}"
        request = urllib.request.Request(url, headers=headers, method="GET")
        last: Exception | None = None
        for attempt in range(3):
            try:
                with self.opener.open(request, timeout=30) as response:
                    data = response.read(MAX_RESPONSE_BYTES + 1)
                if len(data) > MAX_RESPONSE_BYTES:
                    raise CurrentnessError(f"Response exceeds {MAX_RESPONSE_BYTES} bytes: {url}")
                self.total_bytes += len(data)
                if self.total_bytes > MAX_TOTAL_BYTES:
                    raise CurrentnessError("Currentness responses exceed the aggregate byte limit")
                self.cache[url] = data
                return data
            except CurrentnessError:
                raise
            except urllib.error.HTTPError as error:
                last = error
                if error.code != 429 and error.code < 500:
                    break
            except (urllib.error.URLError, TimeoutError, OSError) as error:
                last = error
            if attempt < 2:
                time.sleep(2**attempt)
        raise CurrentnessError(f"Unable to read official release endpoint {url}: {last}")

    def json(self, url: str) -> Any:
        """Read and parse a bounded JSON response."""
        try:
            return json.loads(self.read(url))
        except (json.JSONDecodeError, UnicodeDecodeError) as error:
            raise CurrentnessError(f"Invalid JSON from {url}: {error}") from error

    def text(self, url: str) -> str:
        """Read and decode a bounded UTF-8 text response."""
        try:
            return self.read(url).decode("utf-8")
        except UnicodeDecodeError as error:
            raise CurrentnessError(f"Invalid UTF-8 from {url}: {error}") from error


def validate_url(url: str) -> None:
    """Require HTTPS and a fixed official host for every request and redirect."""
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or parsed.hostname not in ALLOWED_HOSTS or parsed.username or parsed.password:
        raise CurrentnessError(f"Untrusted release endpoint: {url}")


def read_text(path: str) -> str:
    """Read a tracked repository file without following a symlink."""
    target = (ROOT / path).resolve()
    if not target.is_relative_to(ROOT) or not target.is_file() or (ROOT / path).is_symlink():
        raise CurrentnessError(f"Missing or unsafe currentness input: {path}")
    return target.read_text(encoding="utf-8")


def one(values: list[str], description: str) -> str:
    """Return one unique non-empty extracted value or fail closed."""
    unique = sorted({value.strip().strip('"\'') for value in values if value.strip()})
    if len(unique) != 1:
        raise CurrentnessError(f"Expected one {description}, found {unique}")
    return unique[0]


def workflow_pin_values(local: dict[str, Any], pattern: str, description: str) -> list[str]:
    """Extract a workflow pin from every declared file with exact multiplicity."""
    occurrences = local.get("occurrences")
    if occurrences is None:
        path = local.get("path")
        occurrences = {path: 1} if isinstance(path, str) else None
    if (
        not isinstance(occurrences, dict)
        or not occurrences
        or any(not isinstance(path, str) or not isinstance(count, int) or count < 1 for path, count in occurrences.items())
    ):
        raise CurrentnessError(f"{description} lacks a valid workflow occurrence inventory")
    values: list[str] = []
    for path, expected_count in occurrences.items():
        found = re.findall(pattern, read_text(path))
        if len(found) != expected_count:
            raise CurrentnessError(
                f"Expected {expected_count} {description} occurrence(s) in {path}, found {len(found)}"
            )
        values.extend(found)
    return values


def normalize_version(value: str) -> str:
    """Normalize a stable release version for equality and ordering."""
    value = value.strip()
    if value.startswith(("v", "V")):
        value = value[1:]
    match = VERSION.fullmatch(value)
    if not match or UNSTABLE.search(value):
        raise CurrentnessError(f"Expected a stable release version, found {value!r}")
    return value


def version_key(value: str) -> tuple[tuple[int, Any], ...]:
    """Create a deterministic mixed numeric/text key for stable versions."""
    normalized = normalize_version(value).split("+", 1)[0]
    parts = re.findall(r"\d+|[A-Za-z]+", normalized)
    return tuple((0, int(part)) if part.isdigit() else (1, part.lower()) for part in parts)


def newest(values: list[str], series: str | None = None) -> str:
    """Return the newest stable version, optionally within a declared series."""
    stable: list[str] = []
    for value in values:
        try:
            normalized = normalize_version(value)
        except CurrentnessError:
            continue
        if series and not (normalized == series or normalized.startswith(f"{series}.")):
            continue
        stable.append(normalized)
    if not stable:
        raise CurrentnessError(f"Official source returned no stable releases for series {series or 'latest'}")
    return max(stable, key=version_key)


def local_version(local: dict[str, Any]) -> tuple[str, str | None]:
    """Extract a governed local version and optional immutable GitHub SHA."""
    kind = local.get("type")
    path = local.get("path")
    name = local.get("name")
    if kind == "gradle-wrapper":
        text = read_text("gradle/wrapper/gradle-wrapper.properties")
        version = one(re.findall(r"gradle-([0-9][^-]*)-bin\.zip", text), "Gradle wrapper version")
        return version, None
    if kind == "property":
        text = read_text(path)
        return one(re.findall(rf"(?m)^{re.escape(name)}=(.+)$", text), f"property {name}"), None
    if kind == "gradle-plugin":
        text = read_text(path)
        if name == "org.jetbrains.kotlin.jvm":
            values = re.findall(r"kotlin\(\s*\"jvm\"\s*\)\s+version\s+\"([^\"]+)\"", text)
        else:
            values = re.findall(rf"id\(\s*\"{re.escape(name)}\"\s*\)\s+version\s+\"([^\"]+)\"", text)
        return one(values, f"Gradle plugin {name}"), None
    if kind == "gradle-variable":
        text = read_text(path)
        return one(re.findall(rf"(?m)^val\s+{re.escape(name)}\s*=\s*\"([^\"]+)\"", text), f"Gradle variable {name}"), None
    if kind == "gradle-setting":
        text = read_text(path)
        return one(re.findall(rf"(?m)^\s*{re.escape(name)}\s*=\s*\"([^\"]+)\"", text), f"Gradle setting {name}"), None
    if kind == "kotlin-toolchain":
        values: list[str] = []
        paths = local.get("paths")
        if not isinstance(paths, list) or not paths or any(not isinstance(path, str) for path in paths):
            raise CurrentnessError("Kotlin JVM toolchain lacks an explicit module path inventory")
        if len(paths) != len(set(paths)):
            raise CurrentnessError("Kotlin JVM toolchain module paths must be unique")
        for module_path in paths:
            text = read_text(module_path)
            values.append(
                one(
                    re.findall(r"jvmToolchain\(\s*(\d+)\s*\)", text),
                    f"Kotlin JVM toolchain in {module_path}",
                )
            )
        return one(list(set(values)), "consistent Kotlin JVM toolchain"), None
    if kind == "java-test-toolchain":
        text = read_text(path)
        return one(
            re.findall(rf'gradleProperty\(\s*"{re.escape(name)}"\s*\)\.orElse\(\s*"(\d+)"\s*\)', text),
            f"Java test toolchain {name}",
        ), None
    if kind == "gradle-testkit":
        text = read_text(path)
        return one(
            re.findall(r'execute\([^;]*?,\s*"([0-9]+\.[0-9.]+)"\s*\)', text, re.DOTALL),
            "Gradle TestKit version",
        ), None
    if kind == "gradle-verifier":
        text = read_text(path)
        value = str(local.get("value", ""))
        versions = re.findall(r'create\(IntelliJPlatformType\.[A-Za-z]+,\s*"([^"]+)"\)', text)
        if versions.count(value) != 1:
            raise CurrentnessError(f"Missing or duplicate verifier version {value}")
        return value, None
    if kind in {"maven", "maven-classifier"}:
        group, artifact = name.split(":", 1)
        classifier = local.get("classifier")
        values: list[str] = []
        for file in ROOT.rglob("*.gradle.kts"):
            if "build" in file.parts or file.is_symlink():
                continue
            text = file.read_text(encoding="utf-8")
            suffix = rf":{re.escape(classifier)}" if classifier else ""
            values += re.findall(rf"{re.escape(group)}:{re.escape(artifact)}:([^\"$:\s]+){suffix}", text)
        return one(values, f"Maven coordinate {name}"), None
    if kind == "github-action":
        refs: list[str] = []
        versions: list[str] = []
        workflow_files = [
            file for pattern in ("*.yml", "*.yaml") for file in (ROOT / ".github" / "workflows").glob(pattern)
        ]
        for file in workflow_files:
            text = file.read_text(encoding="utf-8")
            for match in re.finditer(rf"(?m)^\s*(?:-\s*)?uses:\s*{re.escape(name)}(?:/[^@\s]+)?@([0-9a-f]{{40}})\s+#\s*(\S+)\s*$", text):
                refs.append(match.group(1))
                versions.append(match.group(2))
        version = one(versions, f"GitHub Action version {name}")
        unique_refs = sorted(set(refs))
        if not unique_refs or any(not SHA.fullmatch(ref) for ref in unique_refs):
            raise CurrentnessError(f"Invalid GitHub Action SHA for {name}")
        return version, ",".join(unique_refs)
    if kind in {"workflow-value", "workflow-env"}:
        values = workflow_pin_values(
            local,
            rf"(?m)^\s*{re.escape(name)}:\s*\"?([^\"\s$]+)\"?\s*$",
            f"workflow value {name}",
        )
        return one(values, f"workflow value {name}"), None
    if kind == "github-release-asset":
        digest_name = local.get("digestName")
        if not isinstance(digest_name, str):
            raise CurrentnessError("GitHub release asset pin lacks a digest variable")
        version = one(
            workflow_pin_values(
                local,
                rf"(?m)^\s*{re.escape(name)}:\s*\"?([^\"\s$]+)\"?\s*$",
                f"workflow value {name}",
            ),
            f"workflow value {name}",
        )
        digest = one(
            workflow_pin_values(
                local,
                rf"(?m)^\s*{re.escape(digest_name)}:\s*\"?([0-9a-f]+)\"?\s*$",
                f"workflow digest {digest_name}",
            ),
            f"workflow digest {digest_name}",
        )
        if not SHA256.fullmatch(digest):
            raise CurrentnessError(f"Invalid SHA-256 workflow digest {digest_name}")
        return version, digest
    if kind == "workflow-tool":
        values = workflow_pin_values(
            local,
            rf"(?m)^\s*tools:\s*.*\b{re.escape(name)}:([^,\s]+)",
            f"workflow tool {name}",
        )
        return one(values, f"workflow tool {name}"), None
    if kind == "pip-command":
        values = workflow_pin_values(
            local,
            rf"\b{re.escape(name)}==([^\s]+)",
            f"pip package {name}",
        )
        return one(values, f"pip package {name}"), None
    if kind == "json-dependency":
        data = json.loads(read_text(path))
        values = [section[name] for key in ("dependencies", "devDependencies", "require", "require-dev") if isinstance((section := data.get(key)), dict) and name in section]
        return one(values, f"manifest dependency {name}"), None
    if kind == "gem":
        text = read_text(path)
        return one(re.findall(rf"gem\s+\"{re.escape(name)}\"\s*,\s*\"([^\"]+)\"", text), f"gem {name}"), None
    if kind == "nuget":
        values: list[str] = []
        for file in (ROOT / "conformance" / "cli-fixtures" / "dotnet").rglob("*.csproj"):
            values += re.findall(rf"<PackageReference\s+Include=\"{re.escape(name)}\"\s+Version=\"([^\"]+)\"", file.read_text(encoding="utf-8"), re.IGNORECASE)
        return one(values, f"NuGet package {name}"), None
    if kind == "workflow-matrix":
        text = read_text(path)
        value = str(local.get("value", ""))
        if not re.search(rf"(?m)\b{re.escape(name)}:\s*(?:\[[^\]]*\b{re.escape(value)}\b[^\]]*\]|\"{re.escape(value)}\")", text):
            raise CurrentnessError(f"Missing workflow matrix value {name}={value}")
        return value, None
    if kind == "cmake-minimum":
        text = read_text(path)
        return one(re.findall(r"cmake_minimum_required\(\s*VERSION\s+([^\s)]+)", text), "minimum CMake version"), None
    if kind == "go-directive":
        text = read_text(path)
        return one(re.findall(r"(?m)^go\s+(\S+)\s*$", text), "Go module language version"), None
    if kind == "dotnet-target-framework":
        value = str(local.get("value", ""))
        files = sorted(ROOT.glob(path))
        found = []
        for file in files:
            found += re.findall(r"<TargetFramework(?:\s+[^>]*)?>(net[^<$]+)</TargetFramework>", file.read_text(encoding="utf-8"))
        if value not in found:
            raise CurrentnessError(f"Missing .NET target framework {value} under {path}")
        return value.removeprefix("net"), None
    raise CurrentnessError(f"Unknown local extractor: {kind}")


def metadata_versions(transport: Transport, base: str, name: str) -> list[str]:
    """Read stable candidates from Maven-compatible metadata."""
    group, artifact = name.split(":", 1)
    path = f"{group.replace('.', '/')}/{artifact}"
    url = f"{base}/{path}/maven-metadata.xml"
    try:
        root = ET.fromstring(transport.read(url))
    except ET.ParseError as error:
        raise CurrentnessError(f"Invalid Maven metadata for {name}: {error}") from error
    return [node.text or "" for node in root.findall("./versioning/versions/version")]


def github_tags(transport: Transport, repository: str) -> list[dict[str, Any]]:
    """Read the bounded first page of GitHub tags for an action or tool."""
    data = transport.json(f"https://api.github.com/repos/{repository}/git/matching-refs/tags/")
    if not isinstance(data, list) or len(data) > 1000:
        raise CurrentnessError(f"Invalid GitHub tag list for {repository}")
    return data


def github_ref_shas(transport: Transport, repository: str, ref: dict[str, Any]) -> set[str]:
    """Return the tag object and recursively peeled commit SHAs."""
    obj = ref.get("object")
    if not isinstance(obj, dict) or not SHA.fullmatch(str(obj.get("sha", ""))):
        raise CurrentnessError(f"Invalid GitHub ref object for {repository}")
    shas = {obj["sha"]}
    for _ in range(4):
        if obj.get("type") != "tag":
            return shas
        obj = transport.json(f"https://api.github.com/repos/{repository}/git/tags/{obj['sha']}").get("object")
        if not isinstance(obj, dict) or not SHA.fullmatch(str(obj.get("sha", ""))):
            raise CurrentnessError(f"Invalid annotated tag for {repository}")
        shas.add(obj["sha"])
    raise CurrentnessError(f"Annotated tag chain is too deep for {repository}")


def github_latest(transport: Transport, repository: str) -> tuple[str, set[str]]:
    """Resolve the newest stable v-prefixed tag and its immutable identities."""
    refs = github_tags(transport, repository)
    versions: dict[str, dict[str, Any]] = {}
    for ref in refs:
        name = str(ref.get("ref", "")).removeprefix("refs/tags/")
        try:
            versions[normalize_version(name)] = ref
        except CurrentnessError:
            continue
    version = newest(list(versions))
    return version, github_ref_shas(transport, repository, versions[version])


def github_release_asset_latest(
    transport: Transport,
    repository: str,
    tag_prefix: str,
    asset_template: str,
    series: str | None,
) -> tuple[str, set[str]]:
    """Resolve the latest stable GitHub release asset and its published SHA-256."""
    if (
        not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository)
        or not tag_prefix
        or asset_template.count("{version}") != 1
        or any(character in asset_template.replace("{version}", "") for character in "{}")
    ):
        raise CurrentnessError(f"Invalid GitHub release asset source for {repository!r}")
    ref_prefix = f"{tag_prefix}{series}." if series else tag_prefix
    encoded_prefix = urllib.parse.quote(ref_prefix, safe="")
    refs = transport.json(
        f"https://api.github.com/repos/{repository}/git/matching-refs/tags/{encoded_prefix}"
    )
    if not isinstance(refs, list) or len(refs) > 1000:
        raise CurrentnessError(f"Invalid GitHub release tag list for {repository}")
    versions: list[str] = []
    for ref in refs:
        tag_ref = str(ref.get("ref", "")) if isinstance(ref, dict) else ""
        if not tag_ref.startswith("refs/tags/"):
            raise CurrentnessError(f"Invalid GitHub release tag for {repository}")
        tag = tag_ref.removeprefix("refs/tags/")
        if not tag.startswith(tag_prefix):
            raise CurrentnessError(f"Unexpected GitHub release tag {tag}")
        try:
            version = normalize_version(tag.removeprefix(tag_prefix))
        except CurrentnessError:
            continue
        if version in versions:
            raise CurrentnessError(f"Duplicate stable GitHub release {tag}")
        versions.append(version)
    version = newest(versions, series)
    tag = f"{tag_prefix}{version}"
    encoded_tag = urllib.parse.quote(tag, safe="")
    release = transport.json(
        f"https://api.github.com/repos/{repository}/releases/tags/{encoded_tag}"
    )
    if (
        not isinstance(release, dict)
        or release.get("tag_name") != tag
        or release.get("draft") is not False
        or release.get("prerelease") is not False
    ):
        raise CurrentnessError(f"Invalid stable GitHub release {tag}")
    assets = release.get("assets")
    if not isinstance(assets, list) or len(assets) > 256:
        raise CurrentnessError(f"Invalid GitHub release assets for {repository}@{version}")
    asset_name = asset_template.format(version=version)
    matching = [asset for asset in assets if isinstance(asset, dict) and asset.get("name") == asset_name]
    if len(matching) != 1:
        raise CurrentnessError(f"Expected one official GitHub release asset {asset_name}")
    asset = matching[0]
    size = asset.get("size")
    if asset.get("state") != "uploaded" or not isinstance(size, int) or not 0 < size <= MAX_RELEASE_ASSET_BYTES:
        raise CurrentnessError(f"Invalid official GitHub release asset {asset_name}")
    digest = str(asset.get("digest", ""))
    if not digest.startswith("sha256:") or not SHA256.fullmatch(digest.removeprefix("sha256:")):
        raise CurrentnessError(f"Missing or invalid official SHA-256 for {asset_name}")
    return version, {digest.removeprefix("sha256:")}


def remote_version(source: dict[str, Any], policy: str, series: str | None, transport: Transport) -> tuple[str, set[str] | None]:
    """Resolve the official stable version for one governed source."""
    kind = source.get("type")
    name = source.get("name")
    if kind == "gradle":
        if series:
            data = transport.json("https://services.gradle.org/versions/all")
            if not isinstance(data, list):
                raise CurrentnessError("Invalid Gradle release response")
            return newest([str(item.get("version", "")) for item in data], series), None
        data = transport.json("https://services.gradle.org/versions/current")
        return normalize_version(str(data.get("version", ""))), None
    if kind == "gradle-plugin":
        versions = metadata_versions(transport, "https://plugins.gradle.org/m2", f"{name}:{name}.gradle.plugin")
        return newest(versions, series), None
    if kind == "maven":
        versions = metadata_versions(transport, "https://repo.maven.apache.org/maven2", name)
        return newest(versions, series), None
    if kind == "jetbrains-maven":
        versions = metadata_versions(transport, "https://packages.jetbrains.team/maven/p/ij/intellij-dependencies", name)
        return newest(versions, series), None
    if kind in {"github", "github-release"}:
        return github_latest(transport, name)
    if kind == "github-release-asset":
        return github_release_asset_latest(
            transport,
            str(name),
            str(source.get("tagPrefix", "")),
            str(source.get("asset", "")),
            series,
        )
    if kind == "github-branch":
        branch = source.get("branch")
        data = transport.json(f"https://api.github.com/repos/{name}/git/ref/heads/{branch}")
        sha = str(data.get("object", {}).get("sha", ""))
        if not SHA.fullmatch(sha):
            raise CurrentnessError(f"Invalid branch head for {name}#{branch}")
        return str(branch), {sha}
    if kind == "npm":
        data = transport.json(f"https://registry.npmjs.org/{urllib.parse.quote(name, safe='@')}/latest")
        return normalize_version(str(data.get("version", ""))), None
    if kind == "pypi":
        try:
            root = ET.fromstring(transport.read(f"https://pypi.org/rss/project/{urllib.parse.quote(name)}/releases.xml"))
        except ET.ParseError as error:
            raise CurrentnessError(f"Invalid PyPI release feed for {name}: {error}") from error
        return newest([node.text or "" for node in root.findall("./channel/item/title")], series), None
    if kind == "packagist":
        data = transport.json(f"https://repo.packagist.org/p2/{name}.json")
        packages = data.get("packages", {}).get(name)
        if not isinstance(packages, list):
            raise CurrentnessError(f"Invalid Packagist response for {name}")
        return newest([str(item.get("version", "")) for item in packages], series), None
    if kind == "rubygems":
        data = transport.json(f"https://rubygems.org/api/v1/versions/{name}.json")
        if not isinstance(data, list):
            raise CurrentnessError(f"Invalid RubyGems response for {name}")
        values = [str(item.get("number", "")) for item in data if not item.get("prerelease") and not item.get("yanked")]
        return newest(values, series), None
    if kind == "nuget":
        query = urllib.parse.urlencode({"q": f"packageid:{name}", "prerelease": "false", "semVerLevel": "2.0.0"})
        data = transport.json(f"https://azuresearch-usnc.nuget.org/query?{query}")
        matches = [item for item in data.get("data", []) if str(item.get("id", "")).lower() == name.lower()]
        if len(matches) != 1:
            raise CurrentnessError(f"Expected one listed NuGet package {name}")
        listed = [str(value.get("version", "")) for value in matches[0].get("versions", []) if isinstance(value, dict)]
        return newest(listed + [str(matches[0].get("version", ""))], series), None
    if kind == "android-studio":
        data = transport.json("https://jb.gg/android-studio-releases-list.json")
        items = data.get("content", {}).get("item", [])
        values = [str(item.get("version", "")) for item in items if item.get("channel") in {"Release", "Patch"}]
        return newest(values), None
    if kind == "node":
        data = transport.json("https://nodejs.org/dist/index.json")
        return newest([str(item.get("version", "")) for item in data]), None
    if kind == "go":
        data = transport.json("https://go.dev/dl/?mode=json")
        return newest([str(item.get("version", "")).removeprefix("go") for item in data if item.get("stable")]), None
    if kind == "rust":
        text = transport.text("https://static.rust-lang.org/dist/channel-rust-stable.toml")
        return one(re.findall(r'(?ms)^\[pkg\.rust\].*?^version\s*=\s*"([0-9.]+)', text), "stable Rust version"), None
    if kind == "dotnet" or kind == "dotnet-series":
        data = transport.json("https://builds.dotnet.microsoft.com/dotnet/release-metadata/releases-index.json")
        rows = [
            row
            for row in data.get("releases-index", [])
            if isinstance(row, dict) and row.get("support-phase") != "preview"
        ] if isinstance(data, dict) else []
        if not rows:
            raise CurrentnessError("Invalid .NET release response")
        target = source.get("series") if kind == "dotnet-series" else max((str(row.get("channel-version", "")) for row in rows), key=version_key)
        matches = [row for row in rows if row.get("channel-version") == target]
        if len(matches) != 1:
            raise CurrentnessError(f"Expected one stable .NET channel {target}")
        return normalize_version(str(matches[0].get("latest-sdk", ""))), None
    if kind == "python":
        data = transport.json("https://www.python.org/api/v2/downloads/release/?is_published=true&pre_release=false")
        return newest([str(item.get("name", "")).removeprefix("Python ") for item in data if item.get("is_published") and not item.get("pre_release")]), None
    if kind == "ruby":
        text = transport.text("https://cache.ruby-lang.org/pub/ruby/index.txt")
        return newest(re.findall(r"(?m)^ruby-(\d+\.\d+\.\d+)\t", text)), None
    if kind == "php":
        data = transport.json("https://www.php.net/releases/index.php?json")
        if not isinstance(data, dict):
            raise CurrentnessError("Invalid PHP release response")
        return newest([str(item.get("version", "")) for item in data.values() if isinstance(item, dict)]), None
    if kind == "composer":
        data = transport.json("https://getcomposer.org/versions")
        stable = data.get("stable")
        if not isinstance(stable, list):
            raise CurrentnessError("Invalid Composer release response")
        return newest([str(item.get("version", "")) for item in stable if not item.get("lts")]), None
    raise CurrentnessError(f"Unknown official source adapter: {kind}")


def validate_gradle_checksum(local: str, transport: Transport) -> None:
    """Require the wrapper distribution checksum published for current Gradle."""
    data = transport.json("https://services.gradle.org/versions/current")
    expected = str(data.get("checksum", ""))
    text = read_text("gradle/wrapper/gradle-wrapper.properties")
    actual = one(re.findall(r"(?m)^distributionSha256Sum=([0-9a-f]{64})$", text), "Gradle distribution checksum")
    if normalize_version(str(data.get("version", ""))) != normalize_version(local) or actual != expected:
        raise CurrentnessError("Gradle wrapper version or SHA-256 does not match the official current distribution")


def validate_entry(entry: dict[str, Any], transport: Transport) -> str:
    """Validate one governed local pin against its declared policy."""
    identifier = str(entry.get("id", ""))
    if not re.fullmatch(r"[a-z0-9][a-z0-9.-]*", identifier):
        raise CurrentnessError(f"Invalid currentness entry id: {identifier!r}")
    local, local_sha = local_version(entry.get("local", {}))
    policy = entry.get("policy")
    if policy not in {"latest", "series", "compatibility", "branch"}:
        raise CurrentnessError(f"Currentness entry {identifier} has an invalid policy: {policy!r}")
    if policy == "series" and not isinstance(entry.get("series"), str):
        raise CurrentnessError(f"Series pin {identifier} lacks a declared series")
    if policy in {"compatibility", "series"}:
        reason = entry.get("reason")
        evidence = entry.get("evidence")
        if not isinstance(reason, str) or len(reason.strip()) < 20 or not isinstance(evidence, list) or not evidence:
            raise CurrentnessError(f"Compatibility pin {identifier} lacks a concrete reason and evidence")
        for path in evidence:
            read_text(str(path))
    if policy == "compatibility":
        expected = entry.get("expected")
        if not isinstance(expected, str) or not expected:
            raise CurrentnessError(f"Compatibility pin {identifier} lacks an approved expected value")
        if local != expected:
            raise CurrentnessError(f"Compatibility pin {identifier} drifted: local {local}, approved {expected}")
        return f"{identifier}: {local} (compatibility: {reason})"
    source = entry.get("source")
    if not isinstance(source, dict):
        raise CurrentnessError(f"Currentness entry {identifier} has no official source")
    expected, expected_shas = remote_version(source, str(policy), entry.get("series"), transport)
    if policy == "branch":
        local_shas = set(local_sha.split(",")) if local_sha else set()
        if local != expected or not local_shas or not local_shas.issubset(expected_shas or set()):
            raise CurrentnessError(f"{identifier} is not pinned to current {expected}: {local}@{local_sha}")
    else:
        if normalize_version(local) != normalize_version(expected):
            raise CurrentnessError(f"{identifier} is stale: local {local}, official {expected}")
        local_shas = set(local_sha.split(",")) if local_sha else set()
        if local_shas and not local_shas.issubset(expected_shas or set()):
            raise CurrentnessError(f"{identifier} SHA {local_sha} does not identify official {expected}")
    if identifier == "gradle":
        validate_gradle_checksum(local, transport)
    suffix = f" (compatibility: {entry['reason']})" if policy == "series" else ""
    return f"{identifier}: {local}{suffix}"


def load_config() -> list[dict[str, Any]]:
    """Load the bounded repository-owned currentness inventory."""
    try:
        data = json.loads(read_text(str(CONFIG.relative_to(ROOT))))
    except json.JSONDecodeError as error:
        raise CurrentnessError(f"Invalid currentness inventory: {error}") from error
    entries = data.get("entries") if isinstance(data, dict) and data.get("schema") == 1 else None
    if not isinstance(entries, list) or not entries or len(entries) > MAX_ENTRIES:
        raise CurrentnessError("Currentness inventory is missing, empty, or too large")
    ids = [entry.get("id") for entry in entries if isinstance(entry, dict)]
    if len(ids) != len(entries) or len(set(ids)) != len(ids):
        raise CurrentnessError("Currentness inventory entries must be objects with unique ids")
    return entries


def inventory_keys(local: dict[str, Any]) -> set[str]:
    """Map a governed local extractor to independently discoverable pin keys."""
    kind = local.get("type")
    name = local.get("name")
    path = local.get("path")
    value = local.get("value")
    if kind == "gradle-wrapper":
        return {"gradle-wrapper"}
    if kind == "kotlin-toolchain":
        return {"kotlin-toolchain"}
    if kind == "java-test-toolchain":
        return {f"java-test-toolchain:{path}:{name}"}
    if kind == "gradle-plugin":
        return {f"plugin:{name}"}
    if kind == "github-action":
        return {f"action:{name}"}
    if kind in {"maven", "maven-classifier"}:
        return {f"maven:{name}"}
    if kind == "gradle-variable":
        return {f"gradle-variable:{path}:{name}"}
    if kind == "gradle-setting":
        return {f"gradle-setting:{path}:{name}"}
    if kind == "property":
        return {f"property:{path}:{name}"}
    if kind == "json-dependency":
        return {f"json:{path}:{name}"}
    if kind == "gem":
        return {f"gem:{name}"}
    if kind == "nuget":
        return {f"nuget:{str(name).lower()}"}
    if kind in {"workflow-value", "workflow-env", "workflow-tool", "pip-command"}:
        occurrences = local.get("occurrences")
        paths = occurrences.keys() if isinstance(occurrences, dict) else [path]
        return {f"{kind}:{workflow_path}:{name}" for workflow_path in paths if isinstance(workflow_path, str)}
    if kind == "github-release-asset":
        occurrences = local.get("occurrences")
        paths = occurrences.keys() if isinstance(occurrences, dict) else [path]
        return {
            f"workflow-env:{workflow_path}:{name}"
            for workflow_path in paths
            if isinstance(workflow_path, str)
        }
    if kind == "workflow-matrix":
        return {f"workflow-matrix:{path}:{name}:{value}"}
    if kind in {"gradle-testkit", "cmake-minimum", "go-directive"}:
        return {f"{kind}:{path}"}
    if kind in {"gradle-verifier", "dotnet-target-framework"}:
        return {f"{kind}:{path}:{value}"}
    return set()


def iter_gradle_scripts() -> list[Path]:
    """Yield tracked Gradle scripts, or a local scan when git is unavailable."""
    if (ROOT / ".git").exists():
        try:
            listed = subprocess.check_output(
                ["git", "-C", str(ROOT), "ls-files", "-z", "*.gradle.kts"],
                text=True,
            )
            return [ROOT / line for line in listed.split("\0") if line]
        except (OSError, subprocess.CalledProcessError):
            pass
    skip = {"build", "fixtures", "superpowers"}
    return [
        file
        for file in ROOT.rglob("*.gradle.kts")
        if not file.is_symlink() and not any(part in skip for part in file.parts)
    ]


def discovered_pin_keys() -> set[str]:
    """Discover governed direct-pin surfaces independently from the inventory."""
    keys: set[str] = set()
    keys.add("gradle-wrapper")
    kotlin_toolchains: set[str] = set()
    for file in iter_gradle_scripts():
        relative = file.relative_to(ROOT).as_posix()
        text = file.read_text(encoding="utf-8")
        keys.update(f"plugin:{name}" for name in re.findall(r'id\(\s*"([^"]+)"\s*\)\s+version\s+"[^"]+"', text))
        if re.search(r'kotlin\(\s*"jvm"\s*\)\s+version\s+"[^"]+"', text):
            keys.add("plugin:org.jetbrains.kotlin.jvm")
        keys.update(
            f"maven:{group}:{artifact}"
            for group, artifact in re.findall(r'"([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):[^"$\s]+"', text)
        )
        kotlin_toolchains.update(re.findall(r"jvmToolchain\(\s*(\d+)\s*\)", text))
        for name in re.findall(r'gradleProperty\(\s*"([^"]+)"\s*\)\.orElse\(\s*"\d+"\s*\)', text):
            keys.add(f"java-test-toolchain:{relative}:{name}")
        keys.update(
            f"gradle-variable:{relative}:{name}"
            for name in re.findall(r'(?m)^val\s+([A-Za-z0-9]+Version)\s*=\s*"[^"]+"', text)
        )
        keys.update(
            f"gradle-setting:{relative}:{name}"
            for name in re.findall(r'(?m)^\s*([A-Za-z0-9]+Version)\s*=\s*"[^"]+"', text)
        )
    if kotlin_toolchains:
        if len(kotlin_toolchains) != 1:
            raise CurrentnessError(f"Kotlin modules use inconsistent JVM toolchains: {sorted(kotlin_toolchains)}")
        keys.add("kotlin-toolchain")
    workflow_files = sorted(
        file for pattern in ("*.yml", "*.yaml") for file in (ROOT / ".github" / "workflows").glob(pattern)
    )
    for file in workflow_files:
        relative = file.relative_to(ROOT).as_posix()
        text = file.read_text(encoding="utf-8")
        for action, reference in re.findall(r"(?m)^\s*(?:-\s*)?uses:\s*([^@\s]+)@([^\s#]+)", text):
            parts = action.split("/")
            if len(parts) < 2:
                raise CurrentnessError(f"Invalid GitHub Action use in {file.relative_to(ROOT)}: {action}")
            keys.add(f"action:{parts[0]}/{parts[1]}")
            if not SHA.fullmatch(reference):
                raise CurrentnessError(f"GitHub Action is not pinned to a full SHA in {relative}: {action}@{reference}")
        for name in re.findall(r'(?m)^\s*([A-Z][A-Z0-9_]*_VERSION):\s*"?\d[^\s"]*"?\s*$', text):
            keys.add(f"workflow-env:{relative}:{name}")
        for name in re.findall(r'(?m)^\s*([A-Za-z0-9_.-]+-version|toolchain|bundler):\s*"?\d[^\s"$]*"?\s*$', text):
            keys.add(f"workflow-value:{relative}:{name}")
        for name in re.findall(r"(?m)^\s*tools:\s*.*\b([A-Za-z0-9_.-]+):\d[^,\s]*", text):
            keys.add(f"workflow-tool:{relative}:{name}")
        for name in re.findall(r"\b([A-Za-z0-9_.-]+)==\d[^\s]+", text):
            keys.add(f"pip-command:{relative}:{name}")
        for match in re.finditer(r'(?m)^\s+(?:-\s*)?(java|dotnet-sdk|php|phpunit):\s*(.+)$', text):
            name, raw = match.groups()
            if "${{" in raw:
                continue
            for value in re.findall(r'"?(\d+(?:\.\d+)*(?:[-+][0-9A-Za-z.-]+)?)"?', raw):
                keys.add(f"workflow-matrix:{relative}:{name}:{value}")
    properties = read_text("gradle.properties")
    keys.update(
        f"property:gradle.properties:{name}"
        for name in re.findall(r"(?m)^(affected\.[A-Za-z0-9_.-]+\.version)=", properties)
    )
    for path in ("conformance/cli-fixtures/node/package.json", "conformance/cli-fixtures/composer/composer.json"):
        data = json.loads(read_text(path))
        for section_name in ("dependencies", "devDependencies", "require", "require-dev"):
            section = data.get(section_name)
            if not isinstance(section, dict):
                continue
            for name in section:
                if name.startswith(("@affected/", "affected/fixture-")):
                    continue
                keys.add(f"json:{path}:{name}")
    gemfile = read_text("conformance/cli-fixtures/ruby/Gemfile")
    keys.update(f"gem:{name}" for name in re.findall(r'gem\s+"([^"]+)"\s*,\s*"[^"]+"', gemfile))
    for file in (ROOT / "conformance" / "cli-fixtures" / "dotnet").rglob("*.csproj"):
        text = file.read_text(encoding="utf-8")
        keys.update(f"nuget:{name.lower()}" for name in re.findall(r'<PackageReference\s+Include="([^"]+)"\s+Version="[^"]+"', text, re.IGNORECASE))
    testkit = "collector/src/test/java/com/aspix2k/affected/collector/GradleInjectionTest.java"
    if re.search(r'execute\([^;]*?,\s*"[0-9]+\.[0-9.]+"\s*\)', read_text(testkit), re.DOTALL):
        keys.add(f"gradle-testkit:{testkit}")
    verifier = "build.gradle.kts"
    for value in re.findall(
        r'create\(IntelliJPlatformType\.[A-Za-z]+,\s*"([^"]+)"\)',
        read_text(verifier),
    ):
        keys.add(f"gradle-verifier:{verifier}:{value}")
    cmake = "conformance/cli-fixtures/cmake/CMakeLists.txt"
    if "cmake_minimum_required" in read_text(cmake):
        keys.add(f"cmake-minimum:{cmake}")
    go_mod = "conformance/cli-fixtures/go/go.mod"
    if re.search(r"(?m)^go\s+\S+", read_text(go_mod)):
        keys.add(f"go-directive:{go_mod}")
    dotnet_globs = (
        "conformance/cli-fixtures/dotnet/**/*.csproj",
        "core/src/main/dotnet/**/*.csproj",
    )
    for pattern in dotnet_globs:
        for file in ROOT.glob(pattern):
            text = file.read_text(encoding="utf-8")
            for value in re.findall(r"<TargetFramework(?:\s+[^>]*)?>(net[^<$]+)</TargetFramework>", text):
                keys.add(f"dotnet-target-framework:{pattern}:{value}")
    return keys


def validate_inventory_coverage(entries: list[dict[str, Any]]) -> None:
    """Reject newly introduced direct pins that lack an explicit release policy."""
    governed = {key for entry in entries for key in inventory_keys(entry.get("local", {}))}
    missing = sorted(discovered_pin_keys() - governed)
    if missing:
        raise CurrentnessError(f"Direct pins missing from currentness inventory: {', '.join(missing)}")
    stale = sorted(governed - discovered_pin_keys())
    if stale:
        raise CurrentnessError(f"Stale currentness inventory entries: {', '.join(stale)}")


def run(transport: Transport | None = None) -> list[str]:
    """Validate every governed direct pin and return a printable report."""
    active = transport or Transport()
    entries = load_config()
    validate_inventory_coverage(entries)
    report = []
    for entry in entries:
        try:
            report.append(validate_entry(entry, active))
        except CurrentnessError as error:
            raise CurrentnessError(f"{entry.get('id', '<unknown>')}: {error}") from error
    return report


def main() -> int:
    """Run the fail-closed currentness gate from the repository root."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    try:
        report = run()
    except CurrentnessError as error:
        print(f"release-currentness: ERROR: {error}", file=sys.stderr)
        return 1
    print("Release pins are current or carry a tested compatibility reason:")
    for line in report:
        print(f"  {line}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

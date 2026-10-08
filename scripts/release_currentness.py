#!/usr/bin/env python3

"""Fail a release when a governed direct dependency pin is stale or unverifiable, or apply the mechanical updates."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / "config" / "release-currentness.json"
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
MAX_TOTAL_BYTES = 32 * 1024 * 1024
MAX_RELEASE_ASSET_BYTES = 64 * 1024 * 1024
MAX_ENTRIES = 192
ALLOWED_HOSTS = {
    "api.github.com",
    "api.nuget.org",
    "artifacts-caching-proxy.aws.intellij.net",
    "azuresearch-usnc.nuget.org",
    "builds.dotnet.microsoft.com",
    "cache-redirector.jetbrains.com",
    "cache.ruby-lang.org",
    "getcomposer.org",
    "go.dev",
    "jb.gg",
    "nodejs.org",
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
    "raw.githubusercontent.com",
    "www.jetbrains.com",
}
MAVEN_CENTRAL_METADATA_BASES = (
    "https://cache-redirector.jetbrains.com/repo1.maven.org/maven2",
    "https://repo.maven.apache.org/maven2",
)
TRANSIENT_METADATA_CODES = {403, 429, 502, 503, 504}
UNSTABLE = re.compile(
    r"(?i)(?:^|[.\-])(?:a(?=[.\-]?\d)|b(?=[.\-]?\d)|alpha|beta|rc|preview|eap|milestone|snapshot|dev|canary|m(?=\d))(?:[.\-]|\d|$)"
)
VERSION = re.compile(r"^[vV]?(\d+(?:\.\d+)+(?:[-+][0-9A-Za-z.-]+)?)$")
SHA = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
JETBRAINS_UPDATES_URL = "https://www.jetbrains.com/updates/updates.xml"


class CurrentnessError(RuntimeError):
    """Describe a fail-closed currentness validation failure."""


class StalePin(CurrentnessError):
    """Describe a local pin that differs from its official source."""

    def __init__(
        self,
        message: str,
        *,
        local: str,
        local_sha: str | None,
        expected: str,
        expected_shas: set[str] | None,
        identity_only: bool = False,
    ) -> None:
        """Keep the structured local and official values next to the printable message."""
        super().__init__(message)
        self.local = local
        self.local_sha = local_sha
        self.expected = expected
        self.expected_shas = expected_shas or set()
        self.identity_only = identity_only


class CurrentnessFailure(CurrentnessError):
    """Carry every stale or unverifiable pin found in one run."""

    def __init__(self, errors: list[str]) -> None:
        """Join the per-pin error lines into one multi-line message."""
        super().__init__("\n".join(errors))
        self.errors = errors


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


def support_matrix_verifier_slot(local: dict[str, Any]) -> tuple[str, str, str]:
    """Read one product verifier version, support series, and exact build."""
    path = local.get("path")
    product_name = local.get("product")
    endpoint_id = local.get("endpoint")
    if not all(
        isinstance(value, str) and value
        for value in (path, product_name, endpoint_id)
    ):
        raise CurrentnessError("Support matrix verifier lacks a product endpoint")
    try:
        matrix = json.loads(read_text(path))
    except json.JSONDecodeError as error:
        raise CurrentnessError(f"Invalid support matrix JSON: {error}") from error
    products = (
        matrix.get("products")
        if isinstance(matrix, dict) and matrix.get("schema") == 1
        else None
    )
    if not isinstance(products, list) or not products or len(products) > MAX_ENTRIES:
        raise CurrentnessError("Invalid support matrix product inventory")
    matches = [
        product
        for product in products
        if isinstance(product, dict)
        and product.get("name") == product_name
        and product.get("support") == "platform"
        and isinstance(product.get("verifier"), dict)
        and product["verifier"].get("type") == product_name
    ]
    if len(matches) != 1:
        raise CurrentnessError(
            f"Expected one support matrix verifier product {product_name}"
        )
    since = matches[0].get("since")
    if not isinstance(since, str) or re.fullmatch(r"\d{4}\.\d+", since) is None:
        raise CurrentnessError(
            f"Invalid support matrix verifier series for {product_name}"
        )
    endpoints = matches[0]["verifier"].get("endpoints")
    if not isinstance(endpoints, list) or not endpoints or len(endpoints) > 16:
        raise CurrentnessError(
            f"Invalid support matrix verifier endpoints for {product_name}"
        )
    selected = [
        endpoint
        for endpoint in endpoints
        if isinstance(endpoint, dict) and endpoint.get("id") == endpoint_id
    ]
    if len(selected) != 1:
        raise CurrentnessError(
            f"Expected one support matrix verifier endpoint {product_name}/{endpoint_id}"
        )
    build = selected[0].get("build")
    if not isinstance(build, str) or re.fullmatch(r"\d+(?:\.\d+){1,3}", build) is None:
        raise CurrentnessError(
            f"Invalid support matrix verifier build for {product_name}/{endpoint_id}"
        )
    return normalize_version(str(selected[0].get("version", ""))), since, build


def gradle_script_files() -> list[Path]:
    """List non-build, non-symlink Gradle scripts that may declare Maven coordinates."""
    return [
        file
        for file in ROOT.rglob("*.gradle.kts")
        if "build" not in file.relative_to(ROOT).parts and not file.is_symlink()
    ]


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
    if kind == "support-matrix-verifier":
        version, _series, build = support_matrix_verifier_slot(local)
        return version, build
    if kind in {"maven", "maven-classifier"}:
        group, artifact = name.split(":", 1)
        classifier = local.get("classifier")
        values: list[str] = []
        for file in gradle_script_files():
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
        for file in dotnet_fixture_projects():
            versions = re.findall(rf"<PackageReference\s+Include=\"{re.escape(name)}\"\s+Version=\"([^\"]+)\"", file.read_text(encoding="utf-8"), re.IGNORECASE)
            values += [exact_nuget_version(version) for version in versions]
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
    """Read bounded version candidates from Maven-compatible metadata."""
    group, artifact = name.split(":", 1)
    path = f"{group.replace('.', '/')}/{artifact}"
    url = f"{base}/{path}/maven-metadata.xml"
    try:
        root = ET.fromstring(transport.read(url))
    except ET.ParseError as error:
        raise CurrentnessError(f"Invalid Maven metadata for {name}: {error}") from error
    return [node.text or "" for node in root.findall("./versioning/versions/version")]


def is_transient_metadata_error(error: CurrentnessError) -> bool:
    """Allow a fallback host only for transport failures, never for bad metadata."""
    message = str(error)
    if "Unable to read official release endpoint" not in message:
        return False
    match = re.search(r"HTTP Error (\d+)", message)
    if match is None:
        return True
    return int(match.group(1)) in TRANSIENT_METADATA_CODES


def maven_central_versions(transport: Transport, name: str) -> list[str]:
    """Read Maven Central metadata from the JetBrains mirror, then official Central."""
    last: CurrentnessError | None = None
    for index, base in enumerate(MAVEN_CENTRAL_METADATA_BASES):
        try:
            return metadata_versions(transport, base, name)
        except CurrentnessError as error:
            last = error
            if index == len(MAVEN_CENTRAL_METADATA_BASES) - 1 or not is_transient_metadata_error(error):
                raise
    raise CurrentnessError(f"Unable to read Maven metadata for {name}: {last}")


def github_tags(transport: Transport, repository: str) -> list[dict[str, Any]]:
    """Read the bounded first page of GitHub tags for an action or tool."""
    data = transport.json(f"https://api.github.com/repos/{repository}/git/matching-refs/tags/")
    if not isinstance(data, list) or len(data) > 1000:
        raise CurrentnessError(f"Invalid GitHub tag list for {repository}")
    return data


def github_ref_chain(transport: Transport, repository: str, ref: dict[str, Any]) -> list[str]:
    """Return the tag object SHA followed by every peeled SHA, commit last."""
    obj = ref.get("object")
    if not isinstance(obj, dict) or not SHA.fullmatch(str(obj.get("sha", ""))):
        raise CurrentnessError(f"Invalid GitHub ref object for {repository}")
    chain = [obj["sha"]]
    for _ in range(4):
        if obj.get("type") != "tag":
            return chain
        obj = transport.json(f"https://api.github.com/repos/{repository}/git/tags/{obj['sha']}").get("object")
        if not isinstance(obj, dict) or not SHA.fullmatch(str(obj.get("sha", ""))):
            raise CurrentnessError(f"Invalid annotated tag for {repository}")
        chain.append(obj["sha"])
    raise CurrentnessError(f"Annotated tag chain is too deep for {repository}")


def github_ref_shas(transport: Transport, repository: str, ref: dict[str, Any]) -> set[str]:
    """Return the tag object and recursively peeled commit SHAs."""
    return set(github_ref_chain(transport, repository, ref))


def github_latest_chain(transport: Transport, repository: str) -> tuple[str, list[str]]:
    """Resolve the newest stable v-prefixed tag and its ordered immutable identities."""
    refs = github_tags(transport, repository)
    versions: dict[str, dict[str, Any]] = {}
    for ref in refs:
        name = str(ref.get("ref", "")).removeprefix("refs/tags/")
        try:
            versions[normalize_version(name)] = ref
        except CurrentnessError:
            continue
    version = newest(list(versions))
    return version, github_ref_chain(transport, repository, versions[version])


def github_latest(transport: Transport, repository: str) -> tuple[str, set[str]]:
    """Resolve the newest stable v-prefixed tag and its immutable identities."""
    version, chain = github_latest_chain(transport, repository)
    return version, set(chain)


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
        or asset_template.count("{version}") > 1
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


def jetbrains_updates_version(
    source: dict[str, Any], series: str | None, transport: Transport
) -> tuple[str, set[str]]:
    """Resolve one exact product's stable release channel from updates.xml."""
    name = source.get("name")
    code = source.get("code")
    if not isinstance(name, str) or not name or not isinstance(code, str) or not code:
        raise CurrentnessError("JetBrains updates source lacks a product name or code")
    try:
        root = ET.fromstring(transport.read(JETBRAINS_UPDATES_URL))
    except ET.ParseError as error:
        raise CurrentnessError(f"Invalid JetBrains updates XML: {error}") from error
    products = root.findall("product") if root.tag == "products" else []
    if not products or len(products) > MAX_ENTRIES:
        raise CurrentnessError("Invalid JetBrains updates product inventory")
    matches = [product for product in products if product.get("name") == name]
    if len(matches) != 1:
        raise CurrentnessError(f"Expected one JetBrains updates product {name}")
    product = matches[0]
    codes = [node.text.strip() for node in product.findall("code") if node.text]
    if (
        not codes
        or len(codes) > 8
        or len(codes) != len(set(codes))
        or any(re.fullmatch(r"[A-Z][A-Z0-9]{1,7}", value) is None for value in codes)
        or codes.count(code) != 1
    ):
        raise CurrentnessError(f"JetBrains updates product code drifted for {name}")
    channel_id = f"{code}-RELEASE-licensing-RELEASE"
    channels = product.findall("channel")
    if not channels or len(channels) > 32:
        raise CurrentnessError(f"Invalid JetBrains update channels for {name}")
    release_channels = [channel for channel in channels if channel.get("id") == channel_id]
    if len(release_channels) != 1:
        raise CurrentnessError(f"Expected one exact JetBrains release channel for {name}")
    release_channel = release_channels[0]
    if (
        release_channel.get("name") != f"{name} RELEASE"
        or release_channel.get("status") != "release"
        or release_channel.get("licensing") != "release"
    ):
        raise CurrentnessError(f"Invalid JetBrains release channel for {name}")
    builds = release_channel.findall("build")
    if not builds or len(builds) > 1024:
        raise CurrentnessError(f"Invalid JetBrains release builds for {name}")
    versions = [normalize_version(str(build.get("version", ""))) for build in builds]
    if len(versions) != len(set(versions)):
        raise CurrentnessError(f"Duplicate JetBrains stable release for {name}")
    selected = newest(versions, series)
    matching = [
        str(build.get("fullNumber", ""))
        for version, build in zip(versions, builds)
        if version == selected
    ]
    if len(matching) != 1:
        raise CurrentnessError(f"Expected one JetBrains stable build for {name} {selected}")
    if re.fullmatch(r"\d+(?:\.\d+){1,3}", matching[0]) is None:
        raise CurrentnessError(f"Invalid JetBrains stable build identity for {name}")
    return selected, {matching[0]}


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
        versions = maven_central_versions(transport, name)
        return newest(versions, series), None
    if kind == "jetbrains-maven":
        versions = metadata_versions(transport, "https://cache-redirector.jetbrains.com/intellij-dependencies", name)
        return newest(versions, series), None
    if kind == "jetbrains-updates":
        return jetbrains_updates_version(source, series, transport)
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
        return newest([str(item.get("version", "")) for item in data], series), None
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
            if isinstance(row, dict) and row.get("support-phase") in {"active", "maintenance"}
        ] if isinstance(data, dict) else []
        if not rows:
            raise CurrentnessError("Invalid .NET release response")
        target = source.get("series") if kind == "dotnet-series" else max((str(row.get("channel-version", "")) for row in rows), key=version_key)
        matches = [row for row in rows if row.get("channel-version") == target]
        if len(matches) != 1:
            raise CurrentnessError(f"Expected one stable .NET channel {target}")
        return normalize_version(str(matches[0].get("latest-sdk", ""))), None
    if kind == "python":
        data = transport.json("https://raw.githubusercontent.com/actions/python-versions/main/versions-manifest.json")
        if not isinstance(data, list):
            raise CurrentnessError("Invalid actions/python-versions manifest")
        return newest([str(item.get("version", "")) for item in data if isinstance(item, dict) and item.get("stable") is True]), None
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
    local_config = entry.get("local")
    if not isinstance(local_config, dict):
        raise CurrentnessError(f"Currentness entry {identifier} has invalid local metadata")
    verifier_series = None
    if local_config.get("type") == "support-matrix-verifier":
        local, verifier_series, local_sha = support_matrix_verifier_slot(local_config)
    else:
        local, local_sha = local_version(local_config)
    policy = entry.get("policy")
    if policy not in {"latest", "series", "compatibility", "branch"}:
        raise CurrentnessError(f"Currentness entry {identifier} has an invalid policy: {policy!r}")
    if local_config.get("type") == "support-matrix-verifier":
        endpoint_policies = {"minimum": "series", "current": "latest"}
        endpoint = local_config.get("endpoint")
        if endpoint_policies.get(endpoint) != policy:
            raise CurrentnessError(
                f"Product verifier endpoint {endpoint!r} has invalid policy {policy!r}"
            )
        if endpoint == "minimum" and entry.get("series") != verifier_series:
            raise CurrentnessError(
                f"Product verifier minimum must match support series {verifier_series}"
            )
    if policy == "series" and not isinstance(entry.get("series"), str):
        raise CurrentnessError(f"Series pin {identifier} lacks a declared series")
    if policy != "series" and "series" in entry:
        raise CurrentnessError(
            f"Currentness entry {identifier} cannot narrow {policy} with a series"
        )
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
    if local_config.get("type") == "support-matrix-verifier" and (
        source.get("type") != "jetbrains-updates"
        or local_config.get("product") != source.get("name")
    ):
        raise CurrentnessError(
            f"Product verifier {identifier} is not bound to its official product"
        )
    series = entry.get("series") if policy == "series" else None
    expected, expected_shas = remote_version(source, str(policy), series, transport)
    if policy == "branch":
        local_shas = set(local_sha.split(",")) if local_sha else set()
        if local != expected or not local_shas or not local_shas.issubset(expected_shas or set()):
            raise StalePin(
                f"{identifier} is not pinned to current {expected}: {local}@{local_sha}",
                local=local,
                local_sha=local_sha,
                expected=expected,
                expected_shas=expected_shas,
            )
    else:
        if normalize_version(local) != normalize_version(expected):
            raise StalePin(
                f"{identifier} is stale: local {local}, official {expected}",
                local=local,
                local_sha=local_sha,
                expected=expected,
                expected_shas=expected_shas,
            )
        local_shas = set(local_sha.split(",")) if local_sha else set()
        if local_shas and not local_shas.issubset(expected_shas or set()):
            identity = "build" if local_config.get("type") == "support-matrix-verifier" else "SHA"
            raise StalePin(
                f"{identifier} {identity} {local_sha} does not identify official {expected}",
                local=local,
                local_sha=local_sha,
                expected=expected,
                expected_shas=expected_shas,
                identity_only=True,
            )
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
    if kind == "support-matrix-verifier":
        product = local.get("product")
        endpoint = local.get("endpoint")
        if not isinstance(path, str) or not isinstance(product, str) or not isinstance(endpoint, str):
            raise CurrentnessError("Invalid support matrix verifier inventory key")
        return {f"support-matrix-verifier:{path}:{product}:{endpoint}"}
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
        if not file.is_symlink() and not any(part in skip for part in file.relative_to(ROOT).parts)
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
    for path in (
        "conformance/cli-fixtures/node/package.json",
        "conformance/cli-fixtures/composer/composer.json",
        "conformance/cli-fixtures/pest/composer.json",
    ):
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
    for file in dotnet_fixture_projects():
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
    try:
        support_matrix = json.loads(read_text("config/support-matrix.json"))
    except json.JSONDecodeError as error:
        raise CurrentnessError(f"Invalid support matrix discovery JSON: {error}") from error
    products = (
        support_matrix.get("products")
        if isinstance(support_matrix, dict) and support_matrix.get("schema") == 1
        else None
    )
    if not isinstance(products, list) or not products or len(products) > MAX_ENTRIES:
        raise CurrentnessError("Invalid support matrix discovery inventory")
    verifier_slots: list[str] = []
    for product in products:
        if not isinstance(product, dict) or product.get("support") != "platform":
            continue
        verifier_config = product.get("verifier")
        product_name = product.get("name")
        endpoints = (
            verifier_config.get("endpoints")
            if isinstance(verifier_config, dict)
            else None
        )
        if (
            not isinstance(product_name, str)
            or not isinstance(verifier_config, dict)
            or verifier_config.get("type") != product_name
            or not isinstance(endpoints, list)
            or not endpoints
            or len(endpoints) > 16
        ):
            raise CurrentnessError("Invalid support matrix verifier discovery slot")
        for endpoint in endpoints:
            endpoint_id = endpoint.get("id") if isinstance(endpoint, dict) else None
            if not isinstance(endpoint_id, str) or not endpoint_id:
                raise CurrentnessError("Invalid support matrix verifier endpoint discovery")
            verifier_slots.append(
                f"support-matrix-verifier:config/support-matrix.json:{product_name}:{endpoint_id}"
            )
    if not verifier_slots or len(verifier_slots) != len(set(verifier_slots)):
        raise CurrentnessError("Duplicate or empty support matrix verifier discovery")
    keys.update(verifier_slots)
    cmake = "conformance/cli-fixtures/cmake/CMakeLists.txt"
    if "cmake_minimum_required" in read_text(cmake):
        keys.add(f"cmake-minimum:{cmake}")
    go_mod = "conformance/cli-fixtures/go/go.mod"
    if re.search(r"(?m)^go\s+\S+", read_text(go_mod)):
        keys.add(f"go-directive:{go_mod}")
    dotnet_globs = (
        "conformance/cli-fixtures/dotnet/**/*.csproj",
        "conformance/cli-fixtures/dotnet-mtp-xunit4/**/*.csproj",
        "core/src/main/dotnet/**/*.csproj",
    )
    for pattern in dotnet_globs:
        for file in ROOT.glob(pattern):
            text = file.read_text(encoding="utf-8")
            for value in re.findall(r"<TargetFramework(?:\s+[^>]*)?>(net[^<$]+)</TargetFramework>", text):
                keys.add(f"dotnet-target-framework:{pattern}:{value}")
    return keys


def dotnet_fixture_projects() -> list[Path]:
    """Return every public .NET fixture project governed by currentness."""
    roots = (
        ROOT / "conformance" / "cli-fixtures" / "dotnet",
        ROOT / "conformance" / "cli-fixtures" / "dotnet-mtp-xunit4",
    )
    return sorted(file for root in roots for file in root.rglob("*.csproj"))


def exact_nuget_version(value: str) -> str:
    """Normalize a plain or exact-bracket NuGet package version."""
    match = re.fullmatch(r"\[([^,\[\]]+)\]", value)
    return match.group(1) if match else value


def validate_inventory_coverage(entries: list[dict[str, Any]]) -> None:
    """Reject newly introduced direct pins that lack an explicit release policy."""
    governed = {key for entry in entries for key in inventory_keys(entry.get("local", {}))}
    missing = sorted(discovered_pin_keys() - governed)
    if missing:
        raise CurrentnessError(f"Direct pins missing from currentness inventory: {', '.join(missing)}")
    stale = sorted(governed - discovered_pin_keys())
    if stale:
        raise CurrentnessError(f"Stale currentness inventory entries: {', '.join(stale)}")




class OfflineRequest(Exception):
    """Mark the point where a pin check would need an official endpoint."""


class OfflineTransport:
    """Stop a pin check at its first network request."""

    def read(self, url: str) -> bytes:
        """Refuse network access."""
        raise OfflineRequest(url)

    def json(self, url: str) -> Any:
        """Refuse network access."""
        raise OfflineRequest(url)

    def text(self, url: str) -> str:
        """Refuse network access."""
        raise OfflineRequest(url)


@dataclass
class Outcome:
    """Hold the result of checking one governed pin."""

    entry: dict[str, Any]
    line: str | None = None
    error: str | None = None
    stale: StalePin | None = None


def collect(entries: list[dict[str, Any]], transport: Any) -> list[Outcome]:
    """Check every entry and keep going after a stale or unverifiable pin."""
    outcomes: list[Outcome] = []
    for entry in entries:
        identifier = entry.get("id", "<unknown>")
        try:
            outcomes.append(Outcome(entry, line=validate_entry(entry, transport)))
        except OfflineRequest:
            outcomes.append(Outcome(entry, line=f"{identifier}: local pin readable"))
        except StalePin as error:
            outcomes.append(Outcome(entry, error=f"{identifier}: {error}", stale=error))
        except CurrentnessError as error:
            outcomes.append(Outcome(entry, error=f"{identifier}: {error}"))
    return outcomes


def report_lines(outcomes: list[Outcome]) -> list[str]:
    """Return the passing report or raise one failure carrying every bad pin."""
    errors = [outcome.error for outcome in outcomes if outcome.error]
    if errors:
        raise CurrentnessFailure(errors)
    return [outcome.line or "" for outcome in outcomes]


def run(transport: Transport | None = None) -> list[str]:
    """Validate every governed direct pin and return a printable report."""
    active = transport or Transport()
    entries = load_config()
    validate_inventory_coverage(entries)
    return report_lines(collect(entries, active))


def run_offline() -> list[str]:
    """Validate inventory coverage and every local pin without network access."""
    entries = load_config()
    validate_inventory_coverage(entries)
    return report_lines(collect(entries, OfflineTransport()))


SUPPORT_MATRIX = "config/support-matrix.json"
SUPPORT_DOCS = ("docs/SUPPORT.md", "README.md", "src/main/resources/META-INF/plugin.xml")
CONFIG_PATH = "config/release-currentness.json"
COMPOSER_FIXTURE = "conformance/cli-fixtures/composer"
STEP_TIMEOUT_SECONDS = 900


class ApplyError(CurrentnessError):
    """Describe a pin that cannot be rewritten mechanically."""


@dataclass
class Edit:
    """Replace capture groups of one regular expression inside one repository file."""

    path: str
    pattern: str
    values: dict[int, Any]
    flags: int = 0
    optional: bool = False


@dataclass
class Step:
    """Describe one real tool invocation that regenerates a lock file."""

    tool: str
    argv: list[str]
    cwd: str
    outputs: list[str]
    image: str | None = None


@dataclass
class Update:
    """Describe what one stale pin needs: a rewrite, a manual step, or a decision."""

    entry: dict[str, Any]
    old: str
    new: str
    new_sha: str | None
    status: str = "auto"
    reason: str = ""
    commands: list[str] = field(default_factory=list)
    flags: list[str] = field(default_factory=list)
    files: list[str] = field(default_factory=list)
    new_id: str | None = None
    line: str = ""

    @property
    def identifier(self) -> str:
        """Return the governed entry id."""
        return str(self.entry["id"])

    @property
    def kind(self) -> str:
        """Return the local extractor type."""
        return str(self.entry.get("local", {}).get("type"))

    @property
    def label(self) -> tuple[str, str]:
        """Return the old and new values as a reader should see them."""
        if self.entry.get("policy") == "branch":
            return (self.old[:12], str(self.new_sha)[:12])
        return (self.old, self.new)


@dataclass
class Toolbox:
    """Resolve real regeneration tools from PATH, or Docker when explicitly allowed."""

    docker: bool = False
    run: Callable[..., Any] = subprocess.run
    which: Callable[[str], str | None] = shutil.which

    def command(self, step: Step, cwd: Path) -> list[str] | None:
        """Return the command line to run for a step, or None when no tool is available."""
        if self.which(step.tool):
            return step.argv
        if self.docker and step.image and self.which("docker"):
            return [
                "docker", "run", "--rm", "--user", f"{os.getuid()}:{os.getgid()}",
                "-e", "COMPOSER_HOME=/tmp/composer", "-v", f"{cwd}:/work", "-w", "/work",
                step.image, *step.argv[1:],
            ]
        return None

    def execute(self, step: Step, cwd: Path) -> None:
        """Run one step with a timeout and raise a readable error on failure."""
        argv = self.command(step, cwd)
        if argv is None:
            raise ApplyError(f"{step.tool} is not available")
        try:
            result = self.run(argv, cwd=str(cwd), capture_output=True, text=True, timeout=STEP_TIMEOUT_SECONDS, check=False)
        except subprocess.TimeoutExpired as error:
            raise ApplyError(f"{shlex.join(step.argv)} timed out after {STEP_TIMEOUT_SECONDS}s") from error
        except OSError as error:
            raise ApplyError(f"{shlex.join(step.argv)} could not start: {error}") from error
        if result.returncode != 0:
            tail = " ".join(str(result.stderr or result.stdout or "").split())[-400:]
            raise ApplyError(f"{shlex.join(step.argv)} exited {result.returncode}: {tail}")


class Workspace:
    """Write repository files with snapshots so a failed update can be rolled back."""

    def __init__(self, dry: bool = False) -> None:
        """Start with no touched files; a dry workspace never writes to disk."""
        self.dry = dry
        self.originals: dict[str, str | None] = {}
        self.pending: dict[str, str | None] = {}

    def snapshot(self, path: str) -> None:
        """Remember the first on-disk content of a path."""
        if path not in self.originals:
            target = ROOT / path
            self.originals[path] = target.read_text(encoding="utf-8") if target.is_file() else None

    def read(self, path: str) -> str:
        """Read the pending or on-disk content of a tracked file."""
        if self.dry and path in self.pending and self.pending[path] is not None:
            return str(self.pending[path])
        return read_text(path)

    def write(self, path: str, text: str) -> None:
        """Store new content for a path, on disk unless the workspace is dry."""
        self.snapshot(path)
        if self.dry:
            self.pending[path] = text
        else:
            (ROOT / path).write_text(text, encoding="utf-8")

    def delete(self, path: str) -> None:
        """Remove a path, on disk unless the workspace is dry."""
        self.snapshot(path)
        if self.dry:
            self.pending[path] = None
        elif (ROOT / path).exists():
            (ROOT / path).unlink()

    def current(self, path: str) -> str | None:
        """Return the content a path has now."""
        if self.dry and path in self.pending:
            return self.pending[path]
        target = ROOT / path
        return target.read_text(encoding="utf-8") if target.is_file() else None

    def changed(self) -> list[str]:
        """List the touched paths whose content really changed."""
        return sorted(path for path, original in self.originals.items() if self.current(path) != original)

    def rollback(self) -> None:
        """Restore every touched path to its snapshot."""
        for path, original in self.originals.items():
            target = ROOT / path
            if original is None:
                if target.exists():
                    target.unlink()
            else:
                target.write_text(original, encoding="utf-8")
        self.pending.clear()


def replace_groups(match: re.Match[str], values: dict[int, Any]) -> str:
    """Rebuild a match with selected capture groups replaced."""
    text, base, last, parts = match.group(0), match.start(0), 0, []
    for group in sorted(values):
        if match.start(group) < 0:
            continue
        start, end = match.start(group) - base, match.end(group) - base
        value = values[group]
        parts.append(text[last:start])
        parts.append(value(match.group(group)) if callable(value) else value)
        last = end
    parts.append(text[last:])
    return "".join(parts)


def apply_edit(workspace: Workspace, edit: Edit) -> int:
    """Apply one regular-expression edit and return how many places changed."""
    text = workspace.read(edit.path)
    updated, count = re.subn(edit.pattern, lambda match: replace_groups(match, edit.values), text, flags=edit.flags)
    if count and updated != text:
        workspace.write(edit.path, updated)
    return count


def occurrence_paths(local: dict[str, Any]) -> list[str]:
    """Return every workflow file a workflow pin is declared in."""
    occurrences = local.get("occurrences")
    if isinstance(occurrences, dict):
        return [path for path in occurrences if isinstance(path, str)]
    return [str(local["path"])] if isinstance(local.get("path"), str) else []


def workflow_paths() -> list[str]:
    """List every workflow file as a repository-relative path."""
    directory = ROOT / ".github" / "workflows"
    return sorted(file.relative_to(ROOT).as_posix() for pattern in ("*.yml", "*.yaml") for file in directory.glob(pattern))


def edits_for(update: Update) -> list[Edit]:
    """Translate a local descriptor into the exact file edits that move its pin."""
    local = update.entry["local"]
    kind, name, path = local.get("type"), str(local.get("name", "")), str(local.get("path", ""))
    new, sha = update.new, update.new_sha
    if kind == "gradle-plugin":
        if name == "org.jetbrains.kotlin.jvm":
            pattern = r'kotlin\(\s*"jvm"\s*\)\s+version\s+"([^"]+)"'
        else:
            pattern = rf'id\(\s*"{re.escape(name)}"\s*\)\s+version\s+"([^"]+)"'
        return [Edit(path, pattern, {1: new})]
    if kind == "property":
        return [Edit(path, rf"(?m)^{re.escape(name)}=(.+)$", {1: new})]
    if kind == "gradle-variable":
        return [Edit(path, rf'(?m)^val\s+{re.escape(name)}\s*=\s*"([^"]+)"', {1: new})]
    if kind == "gradle-setting":
        return [Edit(path, rf'(?m)^\s*{re.escape(name)}\s*=\s*"([^"]+)"', {1: new})]
    if kind == "gradle-testkit":
        return [Edit(path, r'execute\([^;]*?,\s*"([0-9]+\.[0-9.]+)"\s*\)', {1: new}, re.DOTALL)]
    if kind == "maven":
        group, artifact = name.split(":", 1)
        pattern = rf'{re.escape(group)}:{re.escape(artifact)}:([^"$:\s]+)'
        return [Edit(file.relative_to(ROOT).as_posix(), pattern, {1: new}, optional=True) for file in gradle_script_files()]
    if kind == "github-action":
        pattern = rf"(?m)^\s*(?:-\s*)?uses:\s*{re.escape(name)}(?:/[^@\s]+)?@([0-9a-f]{{40}})\s+#\s*(\S+)\s*$"
        values: dict[int, Any] = {1: str(sha)}
        if update.entry.get("policy") != "branch":
            values[2] = lambda comment: ("v" if comment[:1] in {"v", "V"} else "") + new
        return [Edit(workflow, pattern, values, optional=True) for workflow in workflow_paths()]
    if kind in {"workflow-value", "workflow-env"}:
        pattern = rf'(?m)^\s*{re.escape(name)}:\s*"?([^"\s$]+)"?\s*$'
        return [Edit(workflow, pattern, {1: new}) for workflow in occurrence_paths(local)]
    if kind == "github-release-asset":
        version = rf'(?m)^\s*{re.escape(name)}:\s*"?([^"\s$]+)"?\s*$'
        digest = rf'(?m)^\s*{re.escape(str(local.get("digestName")))}:\s*"?([0-9a-f]+)"?\s*$'
        return [
            edit
            for workflow in occurrence_paths(local)
            for edit in (Edit(workflow, version, {1: new}), Edit(workflow, digest, {1: str(sha)}))
        ]
    if kind == "workflow-tool":
        return [Edit(workflow, rf"(?m)^\s*tools:\s*.*\b{re.escape(name)}:([^,\s]+)", {1: new}) for workflow in occurrence_paths(local)]
    if kind == "pip-command":
        return [Edit(workflow, rf"\b{re.escape(name)}==([^\s]+)", {1: new}) for workflow in occurrence_paths(local)]
    if kind == "json-dependency":
        return [Edit(path, rf'"{re.escape(name)}"\s*:\s*"([^"]+)"', {1: new})]
    if kind == "gem":
        return [Edit(path, rf'gem\s+"{re.escape(name)}"\s*,\s*"([^"]+)"', {1: new})]
    if kind == "nuget":
        pattern = rf'<PackageReference\s+Include="{re.escape(name)}"\s+Version="([^"]+)"'
        bracketed = lambda current: f"[{new}]" if current.startswith("[") else new
        return [Edit(file.relative_to(ROOT).as_posix(), pattern, {1: bracketed}, re.IGNORECASE, optional=True) for file in dotnet_fixture_projects()]
    if kind == "workflow-matrix":
        value = re.escape(update.old)
        pattern = rf'\b{re.escape(name)}:\s*(?:\[[^\]]*?\b({value})\b[^\]]*\]|"({value})")'
        return [Edit(path, pattern, {1: new, 2: new})]
    if kind == "support-matrix-verifier":
        product, endpoint = re.escape(str(local.get("product"))), re.escape(str(local.get("endpoint")))
        pattern = rf'"verifier": \{{"type": "{product}"[^\n]*?\{{"id": "{endpoint}", "version": "([^"]+)", "build": "([^"]+)"'
        return [Edit(path, pattern, {1: new, 2: str(sha)})]
    if kind == "gradle-wrapper":
        raise ApplyError("the wrapper properties, scripts and jar move together; use the wrapper task")
    raise ApplyError(f"no mechanical rewrite exists for {kind}; edit the pin by hand")


def steps_for(update: Update) -> list[Step]:
    """Return the real tool runs whose output must move together with a pin."""
    local = update.entry["local"]
    kind, name, path = local.get("type"), str(local.get("name", "")), str(local.get("path", ""))
    directory = Path(path).parent.as_posix() if path else ""
    if kind == "json-dependency" and path.endswith("package.json"):
        argv = ["npm", "install", "--package-lock-only", "--ignore-scripts", "--no-audit", "--no-fund"]
        return [Step("npm", argv, directory, [f"{directory}/package-lock.json"])]
    if kind == "json-dependency" and path.endswith("composer.json"):
        return [Step("composer", composer_update(name), directory, [f"{directory}/composer.lock"], "composer:2")]
    if kind == "gem":
        return [Step("bundle", ["bundle", "lock", "--update", name], directory, [f"{directory}/Gemfile.lock"])]
    if kind == "nuget":
        steps = []
        for project in dotnet_fixture_projects():
            lock = project.parent / "packages.lock.json"
            if lock.is_file() and name.lower() in project.read_text(encoding="utf-8").lower():
                relative = lock.relative_to(ROOT).as_posix()
                steps.append(Step("dotnet", ["dotnet", "restore", "--force-evaluate"], Path(relative).parent.as_posix(), [relative]))
        return steps
    if kind == "workflow-matrix" and name == "phpunit":
        return [Step("composer", composer_update("phpunit/phpunit"), COMPOSER_FIXTURE, [], "composer:2")]
    return []


def composer_update(package: str) -> list[str]:
    """Build the Composer command that re-resolves one fixture package without installing."""
    return [
        "composer", "update", package, "--with-all-dependencies", "--no-install",
        "--no-plugins", "--no-scripts", "--no-interaction", "--no-progress",
    ]


def refresh_phpunit_lock(workspace: Workspace, toolbox: Toolbox, update: Update) -> None:
    """Resolve the PHPUnit matrix lock in a scratch copy and rename it to the new version."""
    old_lock = f"{COMPOSER_FIXTURE}/locks/phpunit-{update.old}.lock"
    new_lock = f"{COMPOSER_FIXTURE}/locks/phpunit-{update.new}.lock"
    seed = ROOT / (old_lock if (ROOT / old_lock).is_file() else f"{COMPOSER_FIXTURE}/composer.lock")
    scratch_root = ROOT / "build"
    scratch_root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(dir=scratch_root, prefix="currentness-") as temporary:
        work = Path(temporary)
        shutil.copytree(ROOT / COMPOSER_FIXTURE, work, dirs_exist_ok=True, symlinks=True, ignore=shutil.ignore_patterns("vendor", "locks"))
        manifest = work / "composer.json"
        text = manifest.read_text(encoding="utf-8")
        pinned, count = re.subn(r'("phpunit/phpunit"\s*:\s*")[^"]+(")', lambda m: f"{m.group(1)}{update.new}{m.group(2)}", text)
        if count == 0:
            raise ApplyError("the Composer fixture does not pin phpunit/phpunit")
        manifest.write_text(pinned, encoding="utf-8")
        shutil.copyfile(seed, work / "composer.lock")
        toolbox.execute(Step("composer", composer_update("phpunit/phpunit"), "", [], "composer:2"), work)
        lock_text = (work / "composer.lock").read_text(encoding="utf-8")
    workspace.write(new_lock, lock_text)
    if old_lock != new_lock and (ROOT / old_lock).is_file():
        workspace.delete(old_lock)


def rewrite_config_entry(workspace: Workspace, update: Update) -> None:
    """Move the matrix value kept in the inventory and rename an id that embeds its minor."""
    text = workspace.read(CONFIG_PATH)
    lines = text.split("\n")
    marker = f'"id":"{update.identifier}"'
    hits = [index for index, line in enumerate(lines) if marker in line]
    if len(hits) != 1:
        raise ApplyError(f"cannot find the inventory line of {update.identifier}")
    line = lines[hits[0]]
    moved = line.replace(f'"value":"{update.old}"', f'"value":"{update.new}"', 1)
    if moved == line:
        raise ApplyError(f"cannot find the matrix value of {update.identifier}")
    old_minor, new_minor = minor_of(update.old), minor_of(update.new)
    new_id = update.identifier.replace(old_minor, new_minor) if old_minor != new_minor and old_minor in update.identifier else update.identifier
    lines[hits[0]] = moved.replace(marker, f'"id":"{new_id}"', 1)
    update.new_id = new_id
    workspace.write(CONFIG_PATH, "\n".join(lines))


def minor_of(version: str) -> str:
    """Return the major.minor prefix of a version."""
    return ".".join(re.findall(r"\d+", version)[:2])


def propagate_prose(workspace: Workspace, update: Update) -> bool:
    """Move an exact tested version quoted in a support-matrix versions string."""
    if "." not in update.old or update.kind == "support-matrix-verifier":
        return False
    text = workspace.read(SUPPORT_MATRIX)
    pattern = re.compile(rf"(?<![\d.–])({re.escape(update.old)})(?![\d.]|[–+])")
    changed = 0
    lines = []
    for line in text.split("\n"):
        if '"versions":' in line:
            line, count = pattern.subn(update.new, line)
            changed += count
        lines.append(line)
    if changed:
        workspace.write(SUPPORT_MATRIX, "\n".join(lines))
    return bool(changed)


CODEQL_KOTLIN_SHIM = "scripts/codeql-kotlin-compat.init.gradle"
CODEQL_KOTLIN_PROBE = "scripts/codeql_kotlin_compat_probe.py"
CI_CONTRACTS = "scripts/ci_contracts.py"
CODEQL_KOTLIN_FLAG = "CodeQL Kotlin shim moved; the CodeQL build must pass before release"


def move_codeql_kotlin_shim(workspace: Workspace, update: Update) -> None:
    """Move the CodeQL Kotlin shim, its probe and their pinned digests with the Kotlin plugin."""
    pins = (
        (CODEQL_KOTLIN_SHIM, 'details.requested.version != "{}"', "CODEQL_KOTLIN_COMPAT_SHA256"),
        (CODEQL_KOTLIN_PROBE, 'SOURCE_VERSION = "{}"', "CODEQL_KOTLIN_PROBE_SHA256"),
    )
    contracts = workspace.read(CI_CONTRACTS)
    for path, template, constant in pins:
        text = workspace.read(path)
        old = template.format(update.old)
        if text.count(old) != 1:
            raise ApplyError(f"expected exactly one {old!r} in {path}")
        moved = text.replace(old, template.format(update.new))
        digest = hashlib.sha256(moved.encode()).hexdigest()
        contracts, count = re.subn(rf'^({constant} = ")[0-9a-f]{{64}}(")$', rf"\g<1>{digest}\g<2>", contracts, flags=re.MULTILINE)
        if count != 1:
            raise ApplyError(f"expected exactly one {constant} in {CI_CONTRACTS}")
        workspace.write(path, moved)
    workspace.write(CI_CONTRACTS, contracts)
    if CODEQL_KOTLIN_FLAG not in update.flags:
        update.flags.append(CODEQL_KOTLIN_FLAG)


def apply_update(update: Update, workspace: Workspace, toolbox: Toolbox) -> None:
    """Rewrite one pin and regenerate what must move with it, or raise ApplyError."""
    edits = edits_for(update)
    total = 0
    for edit in edits:
        count = apply_edit(workspace, edit)
        if count == 0 and not edit.optional:
            raise ApplyError(f"pattern for {update.identifier} not found in {edit.path}")
        total += count
    if total == 0:
        raise ApplyError(f"no occurrence of {update.identifier} was rewritten")
    if update.kind == "workflow-matrix":
        rewrite_config_entry(workspace, update)
    if update.identifier == "kotlin":
        move_codeql_kotlin_shim(workspace, update)
    if propagate_prose(workspace, update) and "support-matrix prose moved" not in update.flags:
        update.flags.append("support-matrix prose moved")
    if workspace.dry:
        return
    for step in steps_for(update):
        cwd = ROOT / step.cwd
        for output in step.outputs:
            workspace.snapshot(output)
        toolbox.execute(step, cwd)
    if update.kind == "workflow-matrix" and str(update.entry["local"].get("name")) == "phpunit":
        refresh_phpunit_lock(workspace, toolbox, update)


def manifest_hint(update: Update) -> str:
    """Describe the manual manifest edit that precedes a lock regeneration."""
    local = update.entry["local"]
    if update.kind == "workflow-matrix":
        return f"set {local.get('name')} to {update.new} in the workflow matrix and as value in {CONFIG_PATH}"
    return f"set {local.get('name')} to {update.new} in {local.get('path')}"


def manual_commands(update: Update, transport: Any) -> list[str]:
    """Return the exact commands for a pin that needs a human or a missing tool."""
    local = update.entry["local"]
    if local.get("type") == "gradle-wrapper":
        try:
            checksum = str(transport.json("https://services.gradle.org/versions/current").get("checksum", "<sha256>"))
        except (CurrentnessError, OfflineRequest, AttributeError):
            checksum = "<sha256>"
        return [
            f"./gradlew wrapper --gradle-version {update.new} --distribution-type bin --gradle-distribution-sha256-sum {checksum}"
        ]
    if update.kind == "workflow-matrix" and str(local.get("name")) == "phpunit":
        fixture = shlex.quote(str(ROOT / COMPOSER_FIXTURE))
        script = (
            f"tmp=$(mktemp -d) && cp -R {fixture}/. \"$tmp\" && cd \"$tmp\" && rm -rf vendor locks"
            f" && perl -pi -e 's/(\"phpunit\\/phpunit\": \")[^\"]+/${{1}}{update.new}/' composer.json"
            f" && cp {fixture}/locks/phpunit-{update.old}.lock composer.lock"
            f" && {shlex.join(composer_update('phpunit/phpunit'))}"
            f" && cp composer.lock {fixture}/locks/phpunit-{update.new}.lock"
            f" && rm {fixture}/locks/phpunit-{update.old}.lock"
        )
        return [script]
    return [f"cd {step.cwd} && {shlex.join(step.argv)}" for step in steps_for(update)]


def named_by_support_matrix(entry: dict[str, Any]) -> bool:
    """Tell whether a pin's tool is named in a support-matrix versions string."""
    try:
        matrix = json.loads(read_text(SUPPORT_MATRIX))
    except (CurrentnessError, json.JSONDecodeError):
        return False
    claims: list[str] = []

    def walk(node: Any) -> None:
        """Collect every versions string of the matrix."""
        if isinstance(node, dict):
            for key, value in node.items():
                if key == "versions" and isinstance(value, str):
                    claims.append(value)
                else:
                    walk(value)
        elif isinstance(node, list):
            for item in node:
                walk(item)

    walk(matrix)
    text = "\n".join(claims).lower()
    local, source = entry.get("local", {}), entry.get("source", {})
    for raw in (local.get("name"), source.get("name"), entry.get("id")):
        token = re.split(r"[/:]", str(raw or "").lower())[-1].replace("_", "-")
        token = re.sub(r"-version$", "", token)
        if len(token) >= 3 and re.search(rf"(?<![\w.-]){re.escape(token)}(?![\w-])", text):
            return True
    return False


def version_numbers(value: str) -> list[int] | None:
    """Return the numeric release parts of a version, or None for non-versions."""
    try:
        normalized = normalize_version(value).split("+", 1)[0].split("-", 1)[0]
    except CurrentnessError:
        return None
    return [int(part) for part in normalized.split(".") if part.isdigit()]


def major_key(parts: list[int]) -> tuple[int, ...]:
    """Return the compatibility-breaking prefix of a version."""
    return tuple(parts[:2]) if parts[0] == 0 else (parts[0],)


def decision_reason(update: Update, stale: StalePin) -> str | None:
    """Return why a pin must not move without a human decision, if it must not."""
    if stale.identity_only:
        return f"the pinned identity does not match official {stale.expected}; verify the pin before changing it"
    if update.entry.get("policy") == "branch":
        return None
    old, new = version_numbers(update.old), version_numbers(update.new)
    if not old or not new:
        return None
    if major_key(new) != major_key(old):
        return f"major version change {update.old} -> {update.new}"
    if version_key(update.new) < version_key(update.old):
        return f"official {update.new} is older than the pinned {update.old}"
    return None


def resolve_new_sha(entry: dict[str, Any], stale: StalePin, transport: Any) -> str | None:
    """Pick the immutable identity a rewritten pin must carry."""
    source = entry.get("source") or {}
    if source.get("type") == "github" and entry.get("local", {}).get("type") == "github-action":
        return github_latest_chain(transport, str(source.get("name")))[1][-1]
    if source.get("type") in {"github-release-asset", "github-branch", "jetbrains-updates"} and len(stale.expected_shas) == 1:
        return next(iter(stale.expected_shas))
    return None


def plan_updates(outcomes: list[Outcome], transport: Any, toolbox: Toolbox) -> list[Update]:
    """Classify every stale pin as auto, manual or decision without writing anything."""
    updates: list[Update] = []
    for outcome in outcomes:
        stale = outcome.stale
        if stale is None:
            continue
        entry = outcome.entry
        branch = entry.get("policy") == "branch"
        update = Update(entry, str(stale.local_sha) if branch else stale.local, stale.expected, None, line=str(outcome.error))
        updates.append(update)
        reason = decision_reason(update, stale)
        if reason:
            update.status, update.reason = "decision", reason
            continue
        try:
            update.new_sha = resolve_new_sha(entry, stale, transport)
        except CurrentnessError as error:
            update.status, update.reason = "manual", f"cannot resolve the official identity: {error}"
            continue
        crosses_minor = bool(version_numbers(update.new) and version_numbers(update.old)) and minor_of(update.new) != minor_of(update.old)
        if crosses_minor and named_by_support_matrix(entry):
            update.flags.append("named by the support matrix: review its version claims")
        dry = Workspace(dry=True)
        try:
            apply_update(update, dry, toolbox)
        except ApplyError as error:
            update.status, update.reason = "manual", str(error)
            update.commands = manual_commands(update, transport)
            continue
        update.files = dry.changed()
        missing = [step.tool for step in steps_for(update) if toolbox.command(step, ROOT / step.cwd) is None]
        if missing:
            update.status = "manual"
            update.reason = f"{manifest_hint(update)}, then regenerate with {', '.join(sorted(set(missing)))} (not on PATH; --docker can use composer:2)"
            update.commands = manual_commands(update, transport)
            update.files = []
    return updates


def apply_updates(updates: list[Update], toolbox: Toolbox, transport: Any) -> list[str]:
    """Apply every auto update, rolling a failed one back into a manual step, and return generated files."""
    generated: list[str] = []
    touched_matrix = False
    for update in updates:
        if update.status != "auto":
            continue
        workspace = Workspace()
        try:
            apply_update(update, workspace, toolbox)
        except (ApplyError, OSError) as error:
            workspace.rollback()
            update.status, update.reason, update.files = "manual", f"automatic update failed and was rolled back: {error}", []
            update.commands = manual_commands(update, transport)
            continue
        except BaseException:
            workspace.rollback()
            raise
        update.status = "updated"
        update.files = workspace.changed()
        touched_matrix = touched_matrix or SUPPORT_MATRIX in update.files
    if touched_matrix:
        generated = regenerate_support_docs(toolbox)
    return generated


def regenerate_support_docs(toolbox: Toolbox) -> list[str]:
    """Regenerate the documents derived from the support matrix and return the ones that changed."""
    before = {path: (ROOT / path).read_text(encoding="utf-8") for path in SUPPORT_DOCS}
    for flag in ("--write", "--check"):
        toolbox.execute(Step("python3", [sys.executable, "scripts/support_matrix.py", flag], "", []), ROOT)
    return [path for path, text in before.items() if (ROOT / path).read_text(encoding="utf-8") != text]


def build_summary(mode: str, outcomes: list[Outcome], updates: list[Update], extra_errors: list[str], generated: list[str]) -> dict[str, Any]:
    """Build the machine-readable summary every renderer and exit code derives from."""
    bound = {update.identifier for update in updates}
    summary: dict[str, Any] = {
        "mode": mode,
        "current": [outcome.entry.get("id") for outcome in outcomes if outcome.line is not None and outcome.error is None],
        "updates": [],
        "manual": [],
        "decisions": [],
        "errors": [],
        "generated": generated,
    }
    for update in updates:
        old, new = update.label
        base = {"id": update.new_id or update.identifier, "type": update.kind, "old": old, "new": new}
        if update.status in {"updated", "auto"}:
            summary["updates"].append({**base, "files": update.files, "flags": update.flags, "applied": update.status == "updated"})
        elif update.status == "manual":
            summary["manual"].append({**base, "reason": update.reason, "commands": update.commands, "flags": update.flags})
        else:
            summary["decisions"].append({**base, "reason": update.reason})
    for outcome in outcomes:
        identifier = str(outcome.entry.get("id"))
        if outcome.error and identifier not in bound:
            summary["errors"].append({"id": identifier, "message": outcome.error})
    summary["errors"] += [{"id": "after-write", "message": message} for message in extra_errors]
    summary["ok"] = not (summary["manual"] or summary["decisions"] or summary["errors"] or any(not item["applied"] for item in summary["updates"]))
    return summary


def render_summary(summary: dict[str, Any]) -> str:
    """Render the summary as the concise text people and issues read."""
    lines: list[str] = []
    applied = summary["mode"] == "write"
    updates = summary["updates"]
    if updates:
        lines.append(f"{'Updated' if applied else 'Applicable with --write'} ({len(updates)}):")
        for item in updates:
            suffix = f" [{'; '.join(item['flags'])}]" if item["flags"] else ""
            lines.append(f"  {item['id']}: {item['old']} -> {item['new']} ({', '.join(item['files']) or 'no file change'}){suffix}")
    if summary["generated"]:
        lines.append(f"Regenerated: {', '.join(summary['generated'])}")
    if summary["manual"]:
        lines.append(f"Needs manual update ({len(summary['manual'])}):")
        for item in summary["manual"]:
            lines.append(f"  {item['id']}: {item['old']} -> {item['new']}: {item['reason']}")
            lines.extend(f"      $ {command}" for command in item["commands"])
    if summary["decisions"]:
        lines.append(f"Needs a decision ({len(summary['decisions'])}):")
        lines.extend(f"  {item['id']}: {item['old']} -> {item['new']}: {item['reason']}" for item in summary["decisions"])
    if summary["errors"]:
        lines.append(f"Unverifiable or drifted ({len(summary['errors'])}):")
        lines.extend(f"  {item['message']}" for item in summary["errors"])
    return "\n".join(lines)


def write_file(path: str, text: str) -> None:
    """Write an output file, creating its directory."""
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


def recheck(transport: Any, expected_ids: set[str]) -> list[str]:
    """Re-run the live check after a write and return failures nobody deferred on purpose."""
    try:
        entries = load_config()
        validate_inventory_coverage(entries)
    except CurrentnessError as error:
        return [str(error)]
    return [
        str(outcome.error)
        for outcome in collect(entries, transport)
        if outcome.error and str(outcome.entry.get("id")) not in expected_ids
    ]


def main(arguments: list[str] | None = None) -> int:
    """Run the fail-closed currentness gate, optionally applying mechanical updates."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true", help="check local pins and inventory without network access")
    parser.add_argument("--write", action="store_true", help="rewrite stale pins that need no manual step and print what is left")
    parser.add_argument("--docker", action="store_true", help="with --write, regenerate Composer locks in the composer:2 image when composer is not on PATH")
    parser.add_argument("--report", metavar="PATH", help="also write the plain-text summary to PATH")
    parser.add_argument("--json", metavar="PATH", help="also write the machine-readable summary to PATH")
    options = parser.parse_args(arguments)
    if options.write and options.offline:
        parser.error("--write needs the live check; drop --offline")
    try:
        entries = load_config()
        validate_inventory_coverage(entries)
    except CurrentnessError as error:
        print(f"release-currentness: ERROR: {error}", file=sys.stderr)
        if options.report:
            write_file(options.report, f"Unverifiable or drifted (1):\n  {error}\n")
        return 1
    transport: Any = OfflineTransport() if options.offline else Transport()
    outcomes = collect(entries, transport)
    toolbox = Toolbox(docker=options.docker)
    updates = [] if options.offline else plan_updates(outcomes, transport, toolbox)
    generated: list[str] = []
    extra: list[str] = []
    mode = "write" if options.write else "check"
    if options.write:
        try:
            generated = apply_updates(updates, toolbox, transport)
        except CurrentnessError as error:
            extra.append(f"support documents could not be regenerated: {error}")
        deferred = {update.identifier for update in updates if update.status != "updated"} | {
            str(outcome.entry.get("id")) for outcome in outcomes if outcome.error and outcome.stale is None
        }
        extra += recheck(transport, deferred)
    summary = build_summary(mode, outcomes, updates, extra, generated)
    text = render_summary(summary)
    if options.report:
        write_file(options.report, f"{text}\n" if text else "")
    if options.json:
        write_file(options.json, json.dumps(summary, indent=2) + "\n")
    if text:
        print(text)
    if summary["ok"]:
        print("Release pins are current or carry a tested compatibility reason:")
        for outcome in outcomes:
            print(f"  {outcome.line or str(outcome.entry.get('id')) + ': updated'}")
        return 0
    if options.write:
        problems = [update.line for update in updates if update.status != "updated"] + [item["message"] for item in summary["errors"]]
    else:
        problems = [outcome.error or "" for outcome in outcomes if outcome.error]
    for problem in problems:
        print(f"release-currentness: ERROR: {problem}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())

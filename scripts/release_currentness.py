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
    "www.python.org",
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
SECURITY_PREVIEW = re.compile(r"^(\d+)\.(\d+)\.(\d+)-(Alpha|Beta|RC)([1-9]\d*)?$")
STABLE_RELEASE = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
KOTLIN_SECURITY = {
    "advisory": "GHSA-r937-wjx7-w2jp",
    "cve": "CVE-2026-53914",
    "repository": "JetBrains/kotlin",
    "package": "org.jetbrains.kotlin:kotlin-gradle-plugin",
    "fixCommit": "bf51df665b458fda7c3eaf436c4d88dc119d7ec6",
}
KOTLIN_PLUGIN_ID = "org.jetbrains.kotlin.jvm"
JETBRAINS_UPDATES_URL = "https://www.jetbrains.com/updates/updates.xml"


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


def security_preview_key(value: str) -> tuple[int, int, int, int, int]:
    """Order one strict Alpha, Beta, or RC release without treating it as stable."""
    match = SECURITY_PREVIEW.fullmatch(value)
    if match is None or (match.group(4) != "RC" and match.group(5) is None):
        raise CurrentnessError(f"Expected an ordered security preview version, found {value!r}")
    major, minor, patch = (int(match.group(index)) for index in range(1, 4))
    stage = {"Alpha": 0, "Beta": 1, "RC": 2}[match.group(4)]
    sequence = int(match.group(5) or "1")
    return major, minor, patch, stage, sequence


def stable_release_key(value: str) -> tuple[int, int, int]:
    """Parse one strict major.minor.patch stable replacement."""
    match = STABLE_RELEASE.fullmatch(value)
    if match is None:
        raise CurrentnessError(f"Expected a stable replacement version, found {value!r}")
    return tuple(int(match.group(index)) for index in range(1, 4))


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


def metadata_version_state(
    transport: Transport,
    base: str,
    name: str,
) -> tuple[list[str], tuple[tuple[str, ...], tuple[str, ...], tuple[str, ...]]]:
    """Read bounded Maven versions plus the declared current-version headers."""
    group, artifact = name.split(":", 1)
    path = f"{group.replace('.', '/')}/{artifact}"
    url = f"{base}/{path}/maven-metadata.xml"
    try:
        root = ET.fromstring(transport.read(url))
    except ET.ParseError as error:
        raise CurrentnessError(f"Invalid Maven metadata for {name}: {error}") from error
    versions = [node.text or "" for node in root.findall("./versioning/versions/version")]
    header_groups = (
        tuple(node.text or "" for node in root.findall("./version")),
        tuple(node.text or "" for node in root.findall("./versioning/latest")),
        tuple(node.text or "" for node in root.findall("./versioning/release")),
    )
    return versions, header_groups


def metadata_versions(transport: Transport, base: str, name: str) -> list[str]:
    """Read bounded version candidates from Maven-compatible metadata."""
    return metadata_version_state(transport, base, name)[0]


def github_tag_commit(transport: Transport, repository: str, version: str) -> str:
    """Resolve one exact GitHub tag ref to its bounded, peeled commit SHA."""
    expected_ref = f"refs/tags/v{version}"
    data = transport.json(f"https://api.github.com/repos/{repository}/git/ref/tags/v{version}")
    if not isinstance(data, dict) or data.get("ref") != expected_ref:
        raise CurrentnessError(f"Invalid exact GitHub tag ref {expected_ref}")
    obj = data.get("object")
    seen: set[str] = set()
    for _ in range(4):
        if not isinstance(obj, dict):
            raise CurrentnessError(f"Invalid GitHub tag object for {expected_ref}")
        kind = obj.get("type")
        sha = obj.get("sha")
        if type(sha) is not str or not SHA.fullmatch(sha) or sha in seen:
            raise CurrentnessError(f"Invalid GitHub tag identity for {expected_ref}")
        if kind == "commit":
            return sha
        if kind != "tag":
            raise CurrentnessError(f"Invalid GitHub tag object type for {expected_ref}")
        seen.add(sha)
        tag = transport.json(f"https://api.github.com/repos/{repository}/git/tags/{sha}")
        if not isinstance(tag, dict) or tag.get("sha") != sha:
            raise CurrentnessError(f"Invalid annotated GitHub tag for {expected_ref}")
        obj = tag.get("object")
    raise CurrentnessError(f"Annotated GitHub tag chain is too deep for {expected_ref}")


def validate_fix_ancestry(
    transport: Transport,
    repository: str,
    fix_commit: str,
    tag_commit: str,
) -> None:
    """Require GitHub to prove that an official release tag contains the exact fix."""
    data = transport.json(
        f"https://api.github.com/repos/{repository}/compare/"
        f"{fix_commit}...{tag_commit}?per_page=1"
    )
    if not isinstance(data, dict):
        raise CurrentnessError(f"Invalid GitHub ancestry response for {tag_commit}")
    status = data.get("status")
    ahead = data.get("ahead_by")
    behind = data.get("behind_by")
    base = data.get("base_commit")
    merge_base = data.get("merge_base_commit")
    valid_distance = (
        status == "ahead"
        and type(ahead) is int
        and ahead > 0
        and type(behind) is int
        and behind == 0
    ) or (
        status == "identical"
        and tag_commit == fix_commit
        and type(ahead) is int
        and ahead == 0
        and type(behind) is int
        and behind == 0
    )
    if (
        not valid_distance
        or not isinstance(base, dict)
        or base.get("sha") != fix_commit
        or not isinstance(merge_base, dict)
        or merge_base.get("sha") != fix_commit
    ):
        raise CurrentnessError(f"GitHub does not prove fix {fix_commit} is an ancestor of {tag_commit}")


def validate_kotlin_security_proof(
    entry: dict[str, Any],
    minimum: str,
    expected: str,
    transport: Transport,
) -> None:
    """Bind the reviewed Kotlin advisory to the exact fix and official release tags."""
    security = entry.get("security")
    if security != KOTLIN_SECURITY:
        raise CurrentnessError("Kotlin security preview lacks the exact reviewed proof metadata")
    advisory = transport.json(f"https://api.github.com/advisories/{KOTLIN_SECURITY['advisory']}")
    if not isinstance(advisory, dict):
        raise CurrentnessError("Invalid reviewed Kotlin advisory response")
    vulnerabilities = advisory.get("vulnerabilities")
    references = advisory.get("references")
    expected_vulnerability = {
        "package": {"ecosystem": "maven", "name": KOTLIN_SECURITY["package"]},
        "vulnerable_version_range": f"< {minimum}",
        "first_patched_version": minimum,
    }
    if (
        advisory.get("ghsa_id") != KOTLIN_SECURITY["advisory"]
        or advisory.get("cve_id") != KOTLIN_SECURITY["cve"]
        or advisory.get("type") != "reviewed"
        or "withdrawn_at" not in advisory
        or advisory.get("withdrawn_at") is not None
        or not isinstance(vulnerabilities, list)
        or len(vulnerabilities) != 1
        or not isinstance(vulnerabilities[0], dict)
        or any(vulnerabilities[0].get(key) != value for key, value in expected_vulnerability.items())
        or not isinstance(references, list)
        or any(type(reference) is not str for reference in references)
    ):
        raise CurrentnessError("Reviewed Kotlin advisory no longer establishes the declared patched floor")
    repository = KOTLIN_SECURITY["repository"]
    fix_commit = KOTLIN_SECURITY["fixCommit"]
    required_references = {
        f"https://github.com/{repository}/commit/{fix_commit}",
        f"https://github.com/{repository}/releases/tag/v{minimum}",
    }
    if not required_references.issubset(set(references)):
        raise CurrentnessError("Reviewed Kotlin advisory lacks the exact fix and patched release references")
    for version in (minimum, expected):
        tag_commit = github_tag_commit(transport, repository, version)
        validate_fix_ancestry(transport, repository, fix_commit, tag_commit)


def validate_security_preview(
    entry: dict[str, Any],
    local: str,
    source: dict[str, Any],
    transport: Transport,
) -> str:
    """Validate a temporary patched preview and expire it at a stable replacement."""
    expected = entry.get("expected")
    minimum = entry.get("minimumPatched")
    replacement = entry.get("stableReplacement")
    local_config = entry.get("local")
    if (
        type(expected) is not str
        or type(minimum) is not str
        or type(replacement) is not str
        or not isinstance(local_config, dict)
        or source.get("type") != "gradle-plugin"
        or source.get("name") != local_config.get("name")
        or source.get("name") != KOTLIN_PLUGIN_ID
    ):
        raise CurrentnessError("Security preview policy has invalid version or source metadata")
    expected_key = security_preview_key(expected)
    minimum_key = security_preview_key(minimum)
    replacement_line = stable_release_key(replacement)
    replacement_order = version_key(replacement)
    if expected_key[:3] != replacement_line or minimum_key[:3] != replacement_line:
        raise CurrentnessError("Security preview policy versions must share one stable release line")
    if expected_key < minimum_key:
        raise CurrentnessError(f"Security preview {expected} is below patched minimum {minimum}")
    if local != expected:
        raise CurrentnessError(f"Security preview pin drifted: local {local}, approved {expected}")

    validate_kotlin_security_proof(entry, minimum, expected, transport)

    name = str(source.get("name"))
    versions, header_groups = metadata_version_state(
        transport,
        "https://plugins.gradle.org/m2",
        f"{name}:{name}.gradle.plugin",
    )
    if any(len(group) != 1 for group in header_groups):
        raise CurrentnessError("Official security preview metadata has missing or duplicate headers")
    headers = tuple(group[0] for group in header_groups)
    if (
        not versions
        or len(versions) > 10_000
        or len(versions) != len(set(versions))
        or any(not version or version.strip() != version for version in versions)
    ):
        raise CurrentnessError("Official security preview metadata is empty, too large, or ambiguous")
    if versions.count(expected) != 1 or versions.count(minimum) != 1:
        raise CurrentnessError("Expected and minimum security previews must be officially published once")

    stable = []
    for version in (*versions, *headers):
        try:
            stable.append(normalize_version(version))
        except CurrentnessError:
            pass
    if any(version_key(version) >= replacement_order for version in stable):
        raise CurrentnessError(f"Security preview expired because stable {replacement} or newer is published")
    if (
        any(not header or header.strip() != header for header in headers)
        or len(set(headers)) != 1
        or headers[0] != expected
        or headers[0] not in versions
    ):
        raise CurrentnessError("Official security preview headers contradict the published version list")

    prefix = f"{replacement}-"
    previews: list[tuple[tuple[int, int, int, int, int], str]] = []
    for version in versions:
        if not version.startswith(prefix):
            continue
        key = security_preview_key(version)
        previews.append((key, version))
    if not previews or len({key for key, _ in previews}) != len(previews):
        raise CurrentnessError("Official security previews have ambiguous ordering")
    newest_patched = max((item for item in previews if item[0] >= minimum_key), default=None)
    if newest_patched is None or newest_patched[1] != expected:
        raise CurrentnessError(f"Security preview {expected} is not the newest patched preview")
    return f"{entry['id']}: {local} (security preview until stable {replacement})"


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
    local_config = entry.get("local")
    if not isinstance(local_config, dict):
        raise CurrentnessError(f"Currentness entry {identifier} has invalid local metadata")
    verifier_series = None
    if local_config.get("type") == "support-matrix-verifier":
        local, verifier_series, local_sha = support_matrix_verifier_slot(local_config)
    else:
        local, local_sha = local_version(local_config)
    policy = entry.get("policy")
    if policy not in {"latest", "series", "compatibility", "branch", "security-preview"}:
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
    if policy in {"compatibility", "series", "security-preview"}:
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
    if policy == "security-preview":
        return validate_security_preview(entry, local, source, transport)
    series = entry.get("series") if policy == "series" else None
    expected, expected_shas = remote_version(source, str(policy), series, transport)
    if policy == "branch":
        local_shas = set(local_sha.split(",")) if local_sha else set()
        if local != expected or not local_shas or not local_shas.issubset(expected_shas or set()):
            raise CurrentnessError(f"{identifier} is not pinned to current {expected}: {local}@{local_sha}")
    else:
        if normalize_version(local) != normalize_version(expected):
            raise CurrentnessError(f"{identifier} is stale: local {local}, official {expected}")
        local_shas = set(local_sha.split(",")) if local_sha else set()
        if local_shas and not local_shas.issubset(expected_shas or set()):
            identity = "build" if local_config.get("type") == "support-matrix-verifier" else "SHA"
            raise CurrentnessError(
                f"{identifier} {identity} {local_sha} does not identify official {expected}"
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

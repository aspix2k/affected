"""Tests for the fail-closed release currentness gate."""

from __future__ import annotations

import unittest
import urllib.error
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from scripts import release_currentness as currentness


class FakeTransport:
    """Return deterministic official-source fixtures without network access."""

    def __init__(self, documents: dict[str, object]) -> None:
        """Store fixture documents by exact URL."""
        self.documents = documents

    def json(self, url: str) -> object:
        """Return a JSON fixture for an expected URL."""
        try:
            return self.documents[url]
        except KeyError as error:
            raise AssertionError(f"Unexpected URL: {url}") from error


class EmptyTransport:
    """Return structurally empty source documents for adapter failure tests."""

    def json(self, url: str) -> object:
        """Return an empty JSON object for every official endpoint."""
        return {}

    def read(self, url: str) -> bytes:
        """Return an empty binary document for XML adapters."""
        return b""

    def text(self, url: str) -> str:
        """Return an empty text document for line-oriented adapters."""
        return ""


class RecordingReadTransport:
    """Record Maven metadata reads and return fixtures or configured errors."""

    def __init__(self, documents: dict[str, bytes] | None = None, errors: dict[str, str] | None = None) -> None:
        """Store fixture documents and fail-closed errors by exact URL."""
        self.documents = documents or {}
        self.errors = errors or {}
        self.reads: list[str] = []

    def read(self, url: str) -> bytes:
        """Return a fixture document or raise the configured currentness error."""
        self.reads.append(url)
        if url in self.errors:
            raise currentness.CurrentnessError(self.errors[url])
        try:
            return self.documents[url]
        except KeyError as error:
            raise AssertionError(f"Unexpected URL: {url}") from error


def maven_metadata(*versions: str) -> bytes:
    """Build a minimal Maven metadata document for the given versions."""
    header = versions[-1] if versions else ""
    items = "".join(f"<version>{version}</version>" for version in versions)
    return (
        f"<metadata><version>{header}</version><versioning>"
        f"<latest>{header}</latest><release>{header}</release><versions>"
        f"{items}"
        "</versions></versioning></metadata>"
    ).encode()


def maven_metadata_with_header(
    header: str,
    *versions: str,
    latest: str | None = None,
    release: str | None = None,
) -> bytes:
    """Build Maven metadata whose top-level release state can contradict its list."""
    items = "".join(f"<version>{version}</version>" for version in versions)
    latest_value = header if latest is None else latest
    release_value = header if release is None else release
    return (
        "<metadata>"
        f"<version>{header}</version>"
        "<versioning>"
        f"<latest>{latest_value}</latest><release>{release_value}</release>"
        f"<versions>{items}</versions>"
        "</versioning>"
        "</metadata>"
    ).encode()


KOTLIN_PLUGIN_METADATA = (
    "https://plugins.gradle.org/m2/org/jetbrains/kotlin/jvm/"
    "org.jetbrains.kotlin.jvm.gradle.plugin/maven-metadata.xml"
)


def kotlin_security_entry(
    expected: str = "2.4.20-RC",
    minimum_patched: str = "2.4.20-Beta1",
    stable_replacement: str = "2.4.20",
) -> dict[str, object]:
    """Build one governed Kotlin security-preview policy fixture."""
    return {
        "id": "kotlin",
        "local": {
            "type": "gradle-plugin",
            "path": "build.gradle.kts",
            "name": "org.jetbrains.kotlin.jvm",
        },
        "source": {"type": "gradle-plugin", "name": "org.jetbrains.kotlin.jvm"},
        "policy": "security-preview",
        "expected": expected,
        "minimumPatched": minimum_patched,
        "stableReplacement": stable_replacement,
        "reason": "The stable Kotlin plugin is vulnerable until the patched release line becomes stable.",
        "evidence": ["build.gradle.kts"],
    }


class FakeResponse:
    """Provide a bounded context-managed response to Transport tests."""

    def __init__(self, data: bytes) -> None:
        """Store response bytes returned from read."""
        self.data = data

    def __enter__(self) -> FakeResponse:
        """Enter the fake response context."""
        return self

    def __exit__(self, *_: object) -> None:
        """Leave the fake response context without suppressing errors."""

    def read(self, _: int) -> bytes:
        """Return the configured response bytes."""
        return self.data


class FakeOpener:
    """Return or raise configured outcomes for bounded retry tests."""

    def __init__(self, outcomes: list[object]) -> None:
        """Store ordered response and exception outcomes."""
        self.outcomes = outcomes
        self.calls = 0

    def open(self, request: object, timeout: int) -> FakeResponse:
        """Return the next response or raise the next transport error."""
        self.calls += 1
        outcome = self.outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        if not isinstance(outcome, FakeResponse):
            raise AssertionError(f"Unexpected fake outcome: {outcome!r}")
        return outcome


class ReleaseCurrentnessTest(unittest.TestCase):
    """Exercise version policy, provenance, and failure boundaries."""

    def test_newest_ignores_unstable_releases(self) -> None:
        """Choose the highest stable version and reject preview-only sets."""
        self.assertEqual("2.4.10", currentness.newest(["2.4.9", "2.4.20-RC", "3.0.0-M1", "2.4.10"]))
        with self.assertRaises(currentness.CurrentnessError):
            currentness.newest(["3.0.0-beta.1", "3.0.0-rc-1"])

    def test_repository_kotlin_pin_uses_the_current_patched_preview(self) -> None:
        """Reject a repository pin that remains on the vulnerable stable Kotlin plugin."""
        entry = next(item for item in currentness.load_config() if item["id"] == "kotlin")
        transport = RecordingReadTransport(
            {
                KOTLIN_PLUGIN_METADATA: maven_metadata(
                    "2.4.10",
                    "2.4.19",
                    "2.4.20-Beta1",
                    "2.4.20-Beta2",
                    "2.4.20-RC",
                )
            }
        )

        result = currentness.validate_entry(entry, transport)

        self.assertEqual(
            "kotlin: 2.4.20-RC (security preview until stable 2.4.20)",
            result,
        )

    def test_security_preview_requires_official_current_patched_release(self) -> None:
        """Accept only the newest officially published preview at or above the advisory floor."""
        transport = RecordingReadTransport(
            {
                KOTLIN_PLUGIN_METADATA: maven_metadata(
                    "2.4.10",
                    "2.4.19",
                    "2.4.20-Beta1",
                    "2.4.20-Beta2",
                    "2.4.20-RC",
                )
            }
        )
        with patch.object(currentness, "local_version", return_value=("2.4.20-RC", None)):
            result = currentness.validate_entry(kotlin_security_entry(), transport)

        self.assertEqual(
            "kotlin: 2.4.20-RC (security preview until stable 2.4.20)",
            result,
        )

    def test_security_preview_rejects_local_pin_drift(self) -> None:
        """Reject a local plugin pin that differs from the reviewed security preview."""
        with patch.object(
            currentness,
            "local_version",
            return_value=("2.4.20-Beta2", None),
        ), self.assertRaises(currentness.CurrentnessError):
            currentness.validate_entry(
                kotlin_security_entry(),
                RecordingReadTransport(
                    {
                        KOTLIN_PLUGIN_METADATA: maven_metadata(
                            "2.4.20-Beta1",
                            "2.4.20-Beta2",
                            "2.4.20-RC",
                        )
                    }
                ),
            )

    def test_security_preview_fails_closed_for_unpublished_or_superseded_preview(self) -> None:
        """Reject missing expected publication and any newer preview on the patched line."""
        cases = {
            "unpublished": ("2.4.20-RC", ("2.4.20-Beta1", "2.4.20-Beta2")),
            "superseded": (
                "2.4.20-RC",
                ("2.4.20-Beta1", "2.4.20-Beta2", "2.4.20-RC", "2.4.20-RC2"),
            ),
        }
        for name, (local, versions) in cases.items():
            with self.subTest(name=name), patch.object(
                currentness,
                "local_version",
                return_value=(local, None),
            ), self.assertRaises(currentness.CurrentnessError):
                currentness.validate_entry(
                    kotlin_security_entry(),
                    RecordingReadTransport({KOTLIN_PLUGIN_METADATA: maven_metadata(*versions)}),
                )

    def test_security_preview_rejects_below_advisory_floor(self) -> None:
        """Reject a published preview whose order is below the declared patched minimum."""
        entry = kotlin_security_entry(expected="2.4.20-Alpha1")
        with patch.object(
            currentness,
            "local_version",
            return_value=("2.4.20-Alpha1", None),
        ), self.assertRaises(currentness.CurrentnessError):
            currentness.validate_entry(
                entry,
                RecordingReadTransport(
                    {
                        KOTLIN_PLUGIN_METADATA: maven_metadata(
                            "2.4.20-Alpha1",
                            "2.4.20-Beta1",
                        )
                    }
                ),
            )

    def test_security_preview_expires_when_the_patched_stable_is_published(self) -> None:
        """Reject the temporary preview policy as soon as its stable replacement exists."""
        for stable in ("2.4.20", "2.4.21", "2.5.0"):
            with self.subTest(stable=stable), patch.object(
                currentness,
                "local_version",
                return_value=("2.4.20-RC", None),
            ), self.assertRaises(currentness.CurrentnessError):
                currentness.validate_entry(
                    kotlin_security_entry(),
                    RecordingReadTransport(
                        {
                            KOTLIN_PLUGIN_METADATA: maven_metadata(
                                "2.4.10",
                                "2.4.20-Beta1",
                                "2.4.20-RC",
                                stable,
                            )
                        }
                    ),
                )

    def test_security_preview_rejects_malformed_or_ambiguous_policy(self) -> None:
        """Reject policy shapes whose provenance, release line, or ordering is ambiguous."""
        cases: dict[str, dict[str, object]] = {
            "missing expected": {"expected": None},
            "missing minimum": {"minimumPatched": None},
            "missing replacement": {"stableReplacement": None},
            "boolean expected": {"expected": True},
            "boolean minimum": {"minimumPatched": True},
            "boolean replacement": {"stableReplacement": True},
            "stable expected": {"expected": "2.4.20"},
            "stable minimum": {"minimumPatched": "2.4.20"},
            "unnumbered beta": {"expected": "2.4.20-Beta"},
            "zero sequence": {"expected": "2.4.20-RC0"},
            "padded sequence": {"expected": "2.4.20-RC01"},
            "preview replacement": {"stableReplacement": "2.4.20-RC"},
            "four-part replacement": {"stableReplacement": "2.4.20.1"},
            "different release line": {"expected": "2.4.21-RC"},
            "invalid local metadata": {"local": "build.gradle.kts"},
            "wrong source type": {"source": {"type": "maven", "name": "org.jetbrains.kotlin.jvm"}},
            "wrong source name": {
                "source": {"type": "gradle-plugin", "name": "org.jetbrains.kotlin.android"}
            },
        }
        for name, changes in cases.items():
            entry = kotlin_security_entry()
            entry.update(changes)
            with self.subTest(name=name), patch.object(
                currentness,
                "local_version",
                return_value=(str(entry.get("expected")), None),
            ), self.assertRaises(currentness.CurrentnessError):
                currentness.validate_entry(
                    entry,
                    RecordingReadTransport(
                        {
                            KOTLIN_PLUGIN_METADATA: maven_metadata(
                                "2.4.20-Beta1",
                                "2.4.20-RC",
                            )
                        }
                    ),
                )

    def test_security_preview_rejects_duplicate_or_unknown_release_metadata(self) -> None:
        """Fail closed when official metadata cannot identify one ordered preview release."""
        cases = {
            "duplicate expected": ("2.4.20-Beta1", "2.4.20-RC", "2.4.20-RC"),
            "duplicate minimum": ("2.4.20-Beta1", "2.4.20-Beta1", "2.4.20-RC"),
            "ambiguous RC alias": ("2.4.20-Beta1", "2.4.20-RC", "2.4.20-RC1"),
            "unknown same-line token": ("2.4.20-Beta1", "2.4.20-M1", "2.4.20-RC"),
        }
        for name, versions in cases.items():
            with self.subTest(name=name), patch.object(
                currentness,
                "local_version",
                return_value=("2.4.20-RC", None),
            ), self.assertRaises(currentness.CurrentnessError):
                currentness.validate_entry(
                    kotlin_security_entry(),
                    RecordingReadTransport({KOTLIN_PLUGIN_METADATA: maven_metadata(*versions)}),
                )

    def test_security_preview_rejects_contradictory_or_superseding_headers(self) -> None:
        """Reject stale lists, missing headers and a newer declared official release."""
        versions = ("2.4.20-Beta1", "2.4.20-RC")
        valid = maven_metadata_with_header("2.4.20-RC", *versions)
        documents = {
            "superseding preview": maven_metadata_with_header("2.4.20-RC2", *versions),
            "inconsistent headers": maven_metadata_with_header(
                "2.4.20-RC",
                *versions,
                latest="2.4.20-RC2",
            ),
            "missing latest": maven_metadata_with_header(
                "2.4.20-RC",
                *versions,
                latest="",
            ),
            "stable with stale list": maven_metadata_with_header("2.4.20", *versions),
            "new release line": maven_metadata_with_header(
                "2.5.0-Alpha1",
                *versions,
                "2.5.0-Alpha1",
            ),
            "duplicate root version": valid.replace(
                b"<versioning>",
                b"<version>2.4.20-RC2</version><versioning>",
            ),
            "duplicate latest": valid.replace(
                b"</versioning>",
                b"<latest>2.4.20-RC2</latest></versioning>",
            ),
            "duplicate release": valid.replace(
                b"</versioning>",
                b"<release>2.4.20-RC2</release></versioning>",
            ),
        }
        for name, document in documents.items():
            with self.subTest(name=name), patch.object(
                currentness,
                "local_version",
                return_value=("2.4.20-RC", None),
            ), self.assertRaises(currentness.CurrentnessError):
                currentness.validate_entry(
                    kotlin_security_entry(),
                    RecordingReadTransport({KOTLIN_PLUGIN_METADATA: document}),
                )

    def test_security_preview_rejects_empty_malformed_or_oversized_metadata(self) -> None:
        """Bound and validate the complete official metadata document before trusting it."""
        oversized = tuple(f"1.0.{index}" for index in range(10_001)) + (
            "2.4.20-Beta1",
            "2.4.20-RC",
        )
        documents = {
            "empty versions": maven_metadata(),
            "blank version": maven_metadata("", "2.4.20-Beta1", "2.4.20-RC"),
            "malformed XML": b"<metadata>",
            "too many versions": maven_metadata(*oversized),
        }
        for name, document in documents.items():
            with self.subTest(name=name), patch.object(
                currentness,
                "local_version",
                return_value=("2.4.20-RC", None),
            ), self.assertRaises(currentness.CurrentnessError):
                currentness.validate_entry(
                    kotlin_security_entry(),
                    RecordingReadTransport({KOTLIN_PLUGIN_METADATA: document}),
                )

    def test_series_never_escapes_declared_compatibility_line(self) -> None:
        """Select the newest patch only within the requested compatibility line."""
        self.assertEqual("11.5.56", currentness.newest(["11.5.55", "11.5.56", "12.0.1"], "11.5"))

    def test_php_latest_is_selected_across_stable_major_lines(self) -> None:
        """Do not freeze the latest PHP policy to today's major or minor line."""
        transport = FakeTransport(
            {
                "https://www.php.net/releases/index.php?json": {
                    "8": {"version": "8.5.9"},
                    "9": {"version": "9.0.1"},
                }
            }
        )
        version, _ = currentness.remote_version({"type": "php"}, "latest", None, transport)
        self.assertEqual("9.0.1", version)

    def test_untrusted_request_and_redirect_hosts_are_rejected(self) -> None:
        """Reject credentials, HTTP, and hosts outside the official allowlist."""
        for url in ("http://pypi.org/simple", "https://example.test/data", "https://token@api.github.com/repos"):
            with self.subTest(url=url), self.assertRaises(currentness.CurrentnessError):
                currentness.validate_url(url)
        handler = currentness.SafeRedirectHandler()
        request = currentness.urllib.request.Request("https://api.github.com/repos", method="GET")
        with self.assertRaises(currentness.CurrentnessError):
            handler.redirect_request(request, None, 302, "Found", {}, "https://example.test/redirect")

    def test_transport_retries_transient_errors_with_a_bound(self) -> None:
        """Retry transient failures twice and stop after the third attempt."""
        transport = currentness.Transport()
        opener = FakeOpener(
            [
                urllib.error.URLError("first"),
                urllib.error.URLError("second"),
                FakeResponse(b"{}"),
            ]
        )
        transport.opener = opener
        with patch.object(currentness.time, "sleep") as sleep:
            self.assertEqual(b"{}", transport.read("https://services.gradle.org/versions/current"))
        self.assertEqual(3, opener.calls)
        self.assertEqual([unittest.mock.call(1), unittest.mock.call(2)], sleep.call_args_list)

    def test_maven_metadata_prefers_cache_redirector(self) -> None:
        """Read Jackson BOM metadata from the JetBrains Central mirror first."""
        redirector = (
            "https://cache-redirector.jetbrains.com/repo1.maven.org/maven2/"
            "com/fasterxml/jackson/jackson-bom/maven-metadata.xml"
        )
        transport = RecordingReadTransport({redirector: maven_metadata("2.22.0", "2.22.1")})

        version, _ = currentness.remote_version(
            {"type": "maven", "name": "com.fasterxml.jackson:jackson-bom"},
            "latest",
            None,
            transport,
        )

        self.assertEqual("2.22.1", version)
        self.assertEqual([redirector], transport.reads)

    def test_maven_metadata_falls_back_to_central_after_redirector_429(self) -> None:
        """A cache-redirector 429 must not skip the official Central metadata."""
        redirector = (
            "https://cache-redirector.jetbrains.com/repo1.maven.org/maven2/"
            "com/fasterxml/jackson/jackson-bom/maven-metadata.xml"
        )
        central = (
            "https://repo.maven.apache.org/maven2/"
            "com/fasterxml/jackson/jackson-bom/maven-metadata.xml"
        )
        transport = RecordingReadTransport(
            {central: maven_metadata("2.22.1")},
            {
                redirector: (
                    "Unable to read official release endpoint "
                    f"{redirector}: HTTP Error 429: Too Many Requests"
                )
            },
        )

        version, _ = currentness.remote_version(
            {"type": "maven", "name": "com.fasterxml.jackson:jackson-bom"},
            "latest",
            None,
            transport,
        )

        self.assertEqual("2.22.1", version)
        self.assertEqual([redirector, central], transport.reads)

    def test_maven_metadata_does_not_hide_a_redirector_404(self) -> None:
        """A missing mirror document is a real failure, not a reason to guess Central."""
        redirector = (
            "https://cache-redirector.jetbrains.com/repo1.maven.org/maven2/"
            "com/fasterxml/jackson/jackson-bom/maven-metadata.xml"
        )
        transport = RecordingReadTransport(
            errors={
                redirector: (
                    "Unable to read official release endpoint "
                    f"{redirector}: HTTP Error 404: Not Found"
                )
            }
        )

        with self.assertRaises(currentness.CurrentnessError):
            currentness.remote_version(
                {"type": "maven", "name": "com.fasterxml.jackson:jackson-bom"},
                "latest",
                None,
                transport,
            )
        self.assertEqual([redirector], transport.reads)

    def test_maven_metadata_fails_closed_when_every_official_source_is_rate_limited(self) -> None:
        """Keep the currentness gate when both official Maven hosts return 429."""
        redirector = (
            "https://cache-redirector.jetbrains.com/repo1.maven.org/maven2/"
            "com/fasterxml/jackson/jackson-bom/maven-metadata.xml"
        )
        central = (
            "https://repo.maven.apache.org/maven2/"
            "com/fasterxml/jackson/jackson-bom/maven-metadata.xml"
        )
        transport = RecordingReadTransport(
            errors={
                redirector: (
                    "Unable to read official release endpoint "
                    f"{redirector}: HTTP Error 429: Too Many Requests"
                ),
                central: (
                    "Unable to read official release endpoint "
                    f"{central}: HTTP Error 429: Too Many Requests"
                ),
            }
        )

        with self.assertRaises(currentness.CurrentnessError) as raised:
            currentness.remote_version(
                {"type": "maven", "name": "com.fasterxml.jackson:jackson-bom"},
                "latest",
                None,
                transport,
            )
        self.assertIn("HTTP Error 429", str(raised.exception))
        self.assertEqual([redirector, central], transport.reads)

    def test_transport_rejects_oversized_and_malformed_responses(self) -> None:
        """Fail before parsing oversized bytes and reject malformed JSON."""
        transport = currentness.Transport()
        transport.opener = FakeOpener([FakeResponse(b"x" * (currentness.MAX_RESPONSE_BYTES + 1))])
        with self.assertRaises(currentness.CurrentnessError):
            transport.read("https://services.gradle.org/versions/current")

        malformed = currentness.Transport()
        malformed.opener = FakeOpener([FakeResponse(b"{")])
        with self.assertRaises(currentness.CurrentnessError):
            malformed.json("https://services.gradle.org/versions/current")

    def test_every_configured_source_rejects_an_empty_response(self) -> None:
        """Require each official adapter in the inventory to fail closed on empty data."""
        seen: set[str] = set()
        for entry in currentness.load_config():
            source = entry.get("source")
            if not isinstance(source, dict):
                continue
            identity = repr(sorted(source.items()))
            if identity in seen:
                continue
            seen.add(identity)
            with self.subTest(source=source), self.assertRaises(currentness.CurrentnessError):
                currentness.remote_version(source, entry["policy"], entry.get("series"), EmptyTransport())

    def test_annotated_github_tag_accepts_object_and_commit_sha(self) -> None:
        """Resolve both immutable identities of an official annotated action tag."""
        repository = "owner/action"
        tag_sha = "a" * 40
        commit_sha = "b" * 40
        transport = FakeTransport(
            {
                f"https://api.github.com/repos/{repository}/git/matching-refs/tags/": [
                    {"ref": "refs/tags/v2.0.0", "object": {"type": "tag", "sha": tag_sha}}
                ],
                f"https://api.github.com/repos/{repository}/git/tags/{tag_sha}": {
                    "object": {"type": "commit", "sha": commit_sha}
                },
            }
        )
        version, identities = currentness.github_latest(transport, repository)
        self.assertEqual("2.0.0", version)
        self.assertEqual({tag_sha, commit_sha}, identities)

    def test_github_release_asset_selects_latest_stable_series_and_digest(self) -> None:
        """Bind a tool pin to the newest stable release asset and its official digest."""
        repository = "nextest-rs/nextest"
        source = {
            "type": "github-release-asset",
            "name": repository,
            "tagPrefix": "cargo-nextest-",
            "asset": "cargo-nextest-{version}-x86_64-unknown-linux-gnu.tar.gz",
        }
        transport = FakeTransport(
            {
                f"https://api.github.com/repos/{repository}/git/matching-refs/tags/cargo-nextest-0.9.": [
                    {"ref": "refs/tags/cargo-nextest-0.9.142"},
                    {"ref": "refs/tags/cargo-nextest-0.9.143"},
                    {"ref": "refs/tags/cargo-nextest-0.9.144-rc.1"},
                    {"ref": "refs/tags/cargo-nextest-0.9.144-b.1"},
                ],
                f"https://api.github.com/repos/{repository}/releases/tags/cargo-nextest-0.9.143": {
                    "tag_name": "cargo-nextest-0.9.143",
                    "draft": False,
                    "prerelease": False,
                    "assets": [
                        {
                            "name": "cargo-nextest-0.9.143-x86_64-unknown-linux-gnu.tar.gz",
                            "state": "uploaded",
                            "size": 1_000_000,
                            "digest": f"sha256:{'b' * 64}",
                        }
                    ],
                },
            },
        )

        version, digests = currentness.remote_version(source, "series", "0.9", transport)

        self.assertEqual("0.9.143", version)
        self.assertEqual({"b" * 64}, digests)

    def test_github_release_asset_rejects_missing_or_malformed_digest(self) -> None:
        """Fail closed when the selected official asset has no usable SHA-256 digest."""
        repository = "nextest-rs/nextest"
        source = {
            "type": "github-release-asset",
            "name": repository,
            "tagPrefix": "cargo-nextest-",
            "asset": "cargo-nextest-{version}-x86_64-unknown-linux-gnu.tar.gz",
        }
        refs_endpoint = (
            f"https://api.github.com/repos/{repository}/git/matching-refs/tags/cargo-nextest-0.9."
        )
        release_endpoint = (
            f"https://api.github.com/repos/{repository}/releases/tags/cargo-nextest-0.9.143"
        )
        for digest in (None, "sha256:not-a-digest"):
            release = {
                "tag_name": "cargo-nextest-0.9.143",
                "draft": False,
                "prerelease": False,
                "assets": [
                    {
                        "name": "cargo-nextest-0.9.143-x86_64-unknown-linux-gnu.tar.gz",
                        "state": "uploaded",
                        "size": 1_000_000,
                        "digest": digest,
                    }
                ],
            }
            with self.subTest(digest=digest), self.assertRaises(currentness.CurrentnessError):
                currentness.remote_version(
                    source,
                    "series",
                    "0.9",
                    FakeTransport(
                        {
                            refs_endpoint: [{"ref": "refs/tags/cargo-nextest-0.9.143"}],
                            release_endpoint: release,
                        }
                    ),
                )

    def test_github_release_asset_rejects_incomplete_release_metadata(self) -> None:
        """Require explicit stable release and uploaded bounded asset metadata."""
        repository = "nextest-rs/nextest"
        source = {
            "type": "github-release-asset",
            "name": repository,
            "tagPrefix": "cargo-nextest-",
            "asset": "cargo-nextest-{version}-x86_64-unknown-linux-gnu.tar.gz",
        }
        refs_endpoint = (
            f"https://api.github.com/repos/{repository}/git/matching-refs/tags/cargo-nextest-0.9."
        )
        release_endpoint = (
            f"https://api.github.com/repos/{repository}/releases/tags/cargo-nextest-0.9.143"
        )
        valid = {
            "tag_name": "cargo-nextest-0.9.143",
            "draft": False,
            "prerelease": False,
            "assets": [{
                "name": "cargo-nextest-0.9.143-x86_64-unknown-linux-gnu.tar.gz",
                "state": "uploaded",
                "size": 1_000_000,
                "digest": f"sha256:{'d' * 64}",
            }],
        }
        invalid = []
        for field in ("draft", "prerelease"):
            release = dict(valid)
            release.pop(field)
            invalid.append(release)
        for field, value in (("state", "new"), ("size", 0), ("size", 33 * 1024 * 1024)):
            release = dict(valid)
            release["assets"] = [dict(valid["assets"][0], **{field: value})]
            invalid.append(release)

        for release in invalid:
            with self.subTest(release=release), self.assertRaises(currentness.CurrentnessError):
                currentness.remote_version(
                    source,
                    "series",
                    "0.9",
                    FakeTransport({
                        refs_endpoint: [{"ref": "refs/tags/cargo-nextest-0.9.143"}],
                        release_endpoint: release,
                    }),
                )

    def test_release_asset_local_pin_reads_version_and_digest_from_one_workflow(self) -> None:
        """Require both installation values to occur exactly where the inventory declares."""
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            workflow = root / ".github" / "workflows" / "conformance.yml"
            workflow.parent.mkdir(parents=True)
            workflow.write_text(
                "env:\n"
                '  CARGO_NEXTEST_VERSION: "0.9.143"\n'
                f'  CARGO_NEXTEST_SHA256: "{"c" * 64}"\n',
                encoding="utf-8",
            )
            local = {
                "type": "github-release-asset",
                "name": "CARGO_NEXTEST_VERSION",
                "digestName": "CARGO_NEXTEST_SHA256",
                "occurrences": {".github/workflows/conformance.yml": 1},
            }

            with patch.object(currentness, "ROOT", root.resolve()):
                self.assertEqual(("0.9.143", "c" * 64), currentness.local_version(local))

    def test_stale_pin_fails_with_entry_identity(self) -> None:
        """Report the governed entry when its local and official versions differ."""
        entries = [
            {
                "id": "tool",
                "local": {"type": "property", "path": "ignored", "name": "tool"},
                "source": {"type": "pypi", "name": "tool"},
                "policy": "latest",
            }
        ]
        with (
            patch.object(currentness, "load_config", return_value=entries),
            patch.object(currentness, "validate_inventory_coverage"),
            patch.object(currentness, "local_version", return_value=("1.0.0", None)),
            patch.object(currentness, "remote_version", return_value=("1.0.1", None)),
            self.assertRaisesRegex(currentness.CurrentnessError, "tool: tool is stale"),
        ):
            currentness.run(FakeTransport({}))

    def test_github_pin_rejects_mismatched_version_or_sha(self) -> None:
        """Require the pinned action comment and SHA to identify the same official tag."""
        entry = {
            "id": "action",
            "local": {"type": "github-action"},
            "source": {"type": "github", "name": "owner/action"},
            "policy": "latest",
        }
        with (
            patch.object(currentness, "local_version", return_value=("v2.0.0", "a" * 40)),
            patch.object(currentness, "remote_version", return_value=("2.0.0", {"b" * 40})),
            self.assertRaises(currentness.CurrentnessError),
        ):
            currentness.validate_entry(entry, FakeTransport({}))

    def test_compatibility_pin_requires_reason_and_evidence(self) -> None:
        """Reject undocumented compatibility exceptions before any network access."""
        entry = {
            "id": "compatibility",
            "local": {"type": "property"},
            "policy": "compatibility",
            "reason": "too short",
            "evidence": [],
        }
        with (
            patch.object(currentness, "local_version", return_value=("1.0.0", None)),
            self.assertRaises(currentness.CurrentnessError),
        ):
            currentness.validate_entry(entry, FakeTransport({}))

    def test_compatibility_pin_rejects_version_drift(self) -> None:
        """Bind a reviewed compatibility exception to its exact approved value."""
        entry = {
            "id": "compatibility",
            "local": {"type": "property"},
            "policy": "compatibility",
            "expected": "21",
            "reason": "The tested compatibility contract requires this exact toolchain version.",
            "evidence": ["build.gradle.kts"],
        }
        with (
            patch.object(currentness, "local_version", return_value=("17", None)),
            self.assertRaisesRegex(currentness.CurrentnessError, "drifted"),
        ):
            currentness.validate_entry(entry, FakeTransport({}))

    def test_kotlin_toolchain_requires_every_declared_module(self) -> None:
        """Reject a project-wide toolchain when any expected module drops its declaration."""
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "build.gradle.kts").write_text("kotlin { jvmToolchain(21) }\n", encoding="utf-8")
            (root / "core").mkdir()
            (root / "core" / "build.gradle.kts").write_text("plugins {}\n", encoding="utf-8")
            local = {
                "type": "kotlin-toolchain",
                "paths": ["build.gradle.kts", "core/build.gradle.kts"],
            }

            with patch.object(currentness, "ROOT", root), self.assertRaises(currentness.CurrentnessError):
                currentness.local_version(local)

    def test_workflow_pin_requires_every_declared_occurrence(self) -> None:
        """Reject moving or deleting one of several governed workflow pins."""
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            workflows = root / ".github" / "workflows"
            workflows.mkdir(parents=True)
            (workflows / "conformance.yml").write_text("tools: composer:2.10.2\n", encoding="utf-8")
            local = {
                "type": "workflow-tool",
                "name": "composer",
                "occurrences": {".github/workflows/conformance.yml": 2},
            }

            with patch.object(currentness, "ROOT", root), self.assertRaises(currentness.CurrentnessError):
                currentness.local_version(local)

    def test_unknown_policy_fails_before_remote_resolution(self) -> None:
        """Reject a misspelled policy instead of silently treating it as latest."""
        entry = {
            "id": "tool",
            "local": {"type": "property"},
            "source": {"type": "pypi", "name": "tool"},
            "policy": "lates",
        }
        with (
            patch.object(currentness, "local_version", return_value=("1.0.0", None)),
            self.assertRaises(currentness.CurrentnessError),
        ):
            currentness.validate_entry(entry, FakeTransport({}))

    def test_series_pin_requires_compatibility_evidence(self) -> None:
        """A stale major cannot be legalized by naming its series alone."""
        entry = {
            "id": "tool",
            "local": {"type": "property"},
            "source": {"type": "pypi", "name": "tool"},
            "policy": "series",
            "series": "1.0",
        }
        with (
            patch.object(currentness, "local_version", return_value=("1.0.0", None)),
            self.assertRaises(currentness.CurrentnessError),
        ):
            currentness.validate_entry(entry, FakeTransport({}))

    def test_repository_direct_pins_have_inventory_entries(self) -> None:
        """Keep the independent direct-pin scan and typed inventory in lockstep."""
        currentness.validate_inventory_coverage(currentness.load_config())

    def test_dotnet_mtp_fixture_pins_are_discovered_and_extracted(self) -> None:
        """Govern the sibling xUnit 4 MTP project instead of scanning only legacy fixtures."""
        keys = currentness.discovered_pin_keys()
        self.assertIn("nuget:xunit.v3", keys)
        self.assertIn(
            "dotnet-target-framework:conformance/cli-fixtures/dotnet-mtp-xunit4/**/*.csproj:net10.0",
            keys,
        )
        version, _ = currentness.local_version(
            {"type": "nuget", "name": "xunit.v3"},
        )
        self.assertEqual("4.0.0", version)

    def test_unpinned_action_fails_discovery(self) -> None:
        """Reject a new Action reference before inventory comparison."""
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            workflows = root / ".github" / "workflows"
            workflows.mkdir(parents=True)
            (root / "gradle").mkdir()
            (root / "gradle" / "wrapper").mkdir()
            (root / "gradle" / "wrapper" / "gradle-wrapper.properties").write_text("", encoding="utf-8")
            (workflows / "new.yaml").write_text(
                "jobs:\n  gate:\n    uses: actions/checkout@v7\n",
                encoding="utf-8",
            )

            with patch.object(currentness, "ROOT", root), self.assertRaises(currentness.CurrentnessError):
                currentness.discovered_pin_keys()

    def test_release_currentness_is_skipped_only_for_an_existing_release(self) -> None:
        """Keep tag-only recovery behind the gate while allowing an exact release retry."""
        workflow = (currentness.ROOT / ".github" / "workflows" / "release.yml").read_text(encoding="utf-8")
        inspect = workflow.split("- name: Inspect whether this is a new release", 1)[1].split(
            "- uses: actions/setup-python", 1
        )[0]
        self.assertIn('gh release view "$RELEASE_TAG"', inspect)
        self.assertIn('currentness=false', inspect)
        self.assertIn('currentness=true', inspect)
        self.assertIn('current_commit=$(git rev-parse HEAD)', inspect)
        self.assertIn('trusted_main=$(gh api "repos/$GITHUB_REPOSITORY/commits/main" --jq .sha)', inspect)
        self.assertIn('if [ "$current_commit" != "$trusted_main" ]', inspect)


if __name__ == "__main__":
    unittest.main()

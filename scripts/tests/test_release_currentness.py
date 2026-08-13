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

    def read(self, url: str) -> bytes:
        """Return a byte fixture for an expected URL."""
        value = self.documents.get(url)
        if not isinstance(value, bytes):
            raise AssertionError(f"Unexpected byte URL: {url}")
        return value


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

    def test_jetbrains_maven_uses_the_official_direct_repository(self) -> None:
        """Resolve IntelliJ dependency metadata without the cache redirector."""
        endpoint = (
            "https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/"
            "org/jetbrains/intellij/deps/asm-all/maven-metadata.xml"
        )
        transport = FakeTransport(
            {
                endpoint: (
                    b"<metadata><versioning><versions>"
                    b"<version>9.10.0</version><version>9.10.1</version>"
                    b"</versions></versioning></metadata>"
                )
            }
        )

        version, _ = currentness.remote_version(
            {"type": "jetbrains-maven", "name": "org.jetbrains.intellij.deps:asm-all"},
            "latest",
            None,
            transport,
        )

        self.assertEqual("9.10.1", version)

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

    def test_kotlin_pin_contains_the_build_cache_security_fix(self) -> None:
        """Keep the Kotlin compiler beyond the unsafe build-cache deserialization range."""
        entry = next(item for item in currentness.load_config() if item["id"] == "kotlin")

        self.assertEqual("security-preview", entry["policy"])
        self.assertEqual("2.4.20-Beta2", entry["expected"])
        self.assertEqual("2.4.20-Beta1", entry["minimumPatched"])
        self.assertEqual("2.4.20", entry["stableReplacement"])
        self.assertIn("GHSA-r937-wjx7-w2jp", entry["reason"])
        self.assertEqual((entry["expected"], None), currentness.local_version(entry["local"]))

    def test_security_preview_requires_official_patch_and_expires_on_stable(self) -> None:
        """Accept only a published patched preview while no patched stable release exists."""
        endpoint = (
            "https://plugins.gradle.org/m2/org/jetbrains/kotlin/jvm/"
            "org.jetbrains.kotlin.jvm.gradle.plugin/maven-metadata.xml"
        )
        entry = {
            "id": "kotlin",
            "local": {"type": "gradle-plugin"},
            "source": {"type": "gradle-plugin", "name": "org.jetbrains.kotlin.jvm"},
            "policy": "security-preview",
            "expected": "2.4.20-Beta2",
            "minimumPatched": "2.4.20-Beta1",
            "stableReplacement": "2.4.20",
            "reason": "The security advisory requires a patched compiler before the next stable release.",
            "evidence": ["build.gradle.kts"],
        }

        def validate(versions: list[str], local: str = "2.4.20-Beta2") -> str:
            metadata = "<metadata><versioning><versions>" + "".join(
                f"<version>{version}</version>" for version in versions
            ) + "</versions></versioning></metadata>"
            with patch.object(currentness, "local_version", return_value=(local, None)):
                return currentness.validate_entry(entry, FakeTransport({endpoint: metadata.encode()}))

        self.assertIn("security preview", validate(["2.4.10", "2.4.20-Beta1", "2.4.20-Beta2"]))
        with self.assertRaisesRegex(currentness.CurrentnessError, "not published"):
            validate(["2.4.10", "2.4.20-Beta1"])
        with self.assertRaisesRegex(currentness.CurrentnessError, "below the patched minimum"):
            entry["expected"] = "2.4.20-Beta0"
            validate(["2.4.10", "2.4.20-Beta0", "2.4.20-Beta1"], "2.4.20-Beta0")
        entry["expected"] = "2.4.20-Beta2"
        with self.assertRaisesRegex(currentness.CurrentnessError, "stable replacement"):
            validate(["2.4.10", "2.4.20-Beta1", "2.4.20-Beta2", "2.4.20"])
        entry["stableReplacement"] = "9.0.0"
        with self.assertRaisesRegex(currentness.CurrentnessError, "same release line"):
            validate(["2.4.10", "2.4.20-Beta1", "2.4.20-Beta2"])

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

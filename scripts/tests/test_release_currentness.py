"""Tests for the fail-closed release currentness gate."""

from __future__ import annotations

import contextlib
import hashlib
import io
import json
import re
import subprocess
import unittest
import urllib.error
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Any
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

    def json(self, url: str) -> object:
        """Decode one recorded JSON fixture through the same exact URL boundary."""
        return json.loads(self.read(url))


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


def jetbrains_updates(*products: tuple[str, str, tuple[str, ...]]) -> bytes:
    """Build a bounded official product release feed fixture."""
    rows = []
    for name, code, versions in products:
        builds = "".join(
            f'<build number="{index}.0" fullNumber="{index}.0.0" version="{version}"/>'
            for index, version in enumerate(versions, start=1)
        )
        rows.append(
            f'<product name="{name}"><code>{code}</code>'
            f'<channel id="{code}-RELEASE-licensing-RELEASE" '
            f'name="{name} RELEASE" status="release" licensing="release">'
            f"{builds}</channel></product>"
        )
    return f"<products>{''.join(rows)}</products>".encode()


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

    def test_offline_mode_checks_local_pins_without_network(self) -> None:
        """Pull requests catch local pin drift without calling release endpoints."""
        report = currentness.run_offline()

        self.assertTrue(report)
        with patch.object(currentness, "load_config", return_value=[
            {
                "id": "drifted",
                "local": {"type": "workflow-value", "name": "java-version", "occurrences": {".github/workflows/ci.yml": 99}},
                "policy": "compatibility",
                "expected": "21",
                "reason": "Offline mode must fail on a stale occurrence count.",
                "evidence": [".github/workflows/ci.yml"],
            },
        ]), patch.object(currentness, "validate_inventory_coverage"):
            with self.assertRaisesRegex(currentness.CurrentnessError, "drifted"):
                currentness.run_offline()

    def test_series_never_escapes_declared_compatibility_line(self) -> None:
        """Select the newest patch only within the requested compatibility line."""
        self.assertEqual("11.5.56", currentness.newest(["11.5.55", "11.5.56", "12.0.1"], "11.5"))

    def test_support_matrix_verifier_reads_the_exact_product_endpoint(self) -> None:
        """Read one product slot without collapsing equal versions across products."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / "config"
            config.mkdir()
            (config / "support-matrix.json").write_text(
                json.dumps(
                    {
                        "schema": 1,
                        "products": [
                            {
                                "name": "Rider",
                                "support": "platform",
                                "since": "2025.3",
                                "verifier": {
                                    "type": "Rider",
                                    "endpoints": [
                                        {
                                            "id": "minimum",
                                            "version": "2025.3.5",
                                            "build": "253.33813.59",
                                        },
                                        {
                                            "id": "current",
                                            "version": "2026.2.0.2",
                                            "build": "262.8665.400",
                                        },
                                    ],
                                },
                            },
                            {
                                "name": "GoLand",
                                "support": "platform",
                                "since": "2025.3",
                                "verifier": {
                                    "type": "GoLand",
                                    "endpoints": [
                                        {
                                            "id": "minimum",
                                            "version": "2025.3.5.1",
                                            "build": "253.33813.70",
                                        },
                                        {
                                            "id": "current",
                                            "version": "2026.2.1",
                                            "build": "262.9437.195",
                                        },
                                    ],
                                },
                            },
                        ],
                    }
                ),
                encoding="utf-8",
            )

            with patch.object(currentness, "ROOT", root.resolve()):
                self.assertEqual(
                    ("2025.3.5", "253.33813.59"),
                    currentness.local_version(
                        {
                            "type": "support-matrix-verifier",
                            "path": "config/support-matrix.json",
                            "product": "Rider",
                            "endpoint": "minimum",
                        }
                    ),
                )

    def test_jetbrains_updates_selects_the_exact_product_current_release(self) -> None:
        """Ignore sibling products while selecting the current stable release channel."""
        url = "https://www.jetbrains.com/updates/updates.xml"
        transport = RecordingReadTransport(
            {
                url: jetbrains_updates(
                    ("Rider", "RD", ("2025.3.5", "2026.2.0.2")),
                    ("GoLand", "GO", ("2025.3.5.1", "2026.2.1")),
                )
            }
        )

        version, builds = currentness.remote_version(
            {"type": "jetbrains-updates", "name": "Rider", "code": "RD"},
            "latest",
            None,
            transport,
        )

        self.assertEqual("2026.2.0.2", version)
        self.assertEqual({"2.0.0"}, builds)
        self.assertEqual([url], transport.reads)

    def test_python_follows_the_versions_setup_python_can_install(self) -> None:
        """A python.org release that actions/setup-python cannot install yet is not required."""
        url = "https://raw.githubusercontent.com/actions/python-versions/main/versions-manifest.json"
        manifest = [
            {"version": "3.15.0-rc.2", "stable": False},
            {"version": "3.14.7", "stable": True},
            {"version": "3.14.6", "stable": True},
        ]
        transport = RecordingReadTransport({url: json.dumps(manifest).encode()})

        version, _ = currentness.remote_version({"type": "python"}, "latest", None, transport)

        self.assertEqual("3.14.7", version)

    def test_node_series_stays_on_the_declared_major_line(self) -> None:
        """A Node pin narrowed to a series ignores releases of newer major lines."""
        url = "https://nodejs.org/dist/index.json"
        index = [{"version": "v26.10.0"}, {"version": "v24.21.0"}, {"version": "v24.18.0"}]
        transport = RecordingReadTransport({url: json.dumps(index).encode()})

        latest, _ = currentness.remote_version({"type": "node"}, "latest", None, transport)
        series, _ = currentness.remote_version({"type": "node"}, "series", "24", transport)

        self.assertEqual("26.10.0", latest)
        self.assertEqual("24.21.0", series)

    def test_dotnet_ignores_go_live_release_candidates(self) -> None:
        """Select the newest supported SDK while a go-live release candidate is listed first."""
        url = "https://builds.dotnet.microsoft.com/dotnet/release-metadata/releases-index.json"
        index = {
            "releases-index": [
                {"channel-version": "11.0", "support-phase": "go-live", "latest-sdk": "11.0.100-rc.1.26425.128"},
                {"channel-version": "10.0", "support-phase": "active", "latest-sdk": "10.0.401"},
                {"channel-version": "9.0", "support-phase": "maintenance", "latest-sdk": "9.0.318"},
            ]
        }
        transport = RecordingReadTransport({url: json.dumps(index).encode()})

        latest, _ = currentness.remote_version({"type": "dotnet"}, "latest", None, transport)
        series, _ = currentness.remote_version(
            {"type": "dotnet-series", "series": "9.0"}, "series", "9.0", transport
        )

        self.assertEqual("10.0.401", latest)
        self.assertEqual("9.0.318", series)

    def test_jetbrains_updates_selects_latest_patch_inside_supported_series(self) -> None:
        """Keep the minimum boundary on the newest patch of its declared line."""
        transport = RecordingReadTransport(
            {
                "https://www.jetbrains.com/updates/updates.xml": jetbrains_updates(
                    ("Rider", "RD", ("2025.3.4.1", "2025.3.5", "2026.2.0.2")),
                )
            }
        )

        version, builds = currentness.remote_version(
            {"type": "jetbrains-updates", "name": "Rider", "code": "RD"},
            "series",
            "2025.3",
            transport,
        )

        self.assertEqual("2025.3.5", version)
        self.assertEqual({"2.0.0"}, builds)

    def test_jetbrains_updates_fails_closed_on_product_or_channel_drift(self) -> None:
        """Reject ambiguous products, non-release channels, and malformed versions."""
        valid = jetbrains_updates(("Rider", "RD", ("2025.3.5", "2026.2.0.2")))
        duplicate_channel_id = valid.replace(
            b"</product>",
            b'<channel id="RD-RELEASE-licensing-RELEASE" '
            b'name="Rider RELEASE" status="eap" licensing="release">'
            b'<build number="3" version="2026.3-EAP"/>'
            b"</channel></product>",
        )
        cases = {
            "wrong code": valid.replace(b"<code>RD</code>", b"<code>GO</code>"),
            "duplicate product": valid.replace(b"</products>", valid[10:-11] + b"</products>"),
            "EAP channel": valid.replace(b'status="release"', b'status="eap"'),
            "duplicate channel id": duplicate_channel_id,
            "missing selected full build": valid.replace(
                b' fullNumber="2.0.0"', b""
            ),
            "duplicate version": jetbrains_updates(
                ("Rider", "RD", ("2025.3.5", "2025.3.5"))
            ),
            "unstable release": jetbrains_updates(
                ("Rider", "RD", ("2026.2-RC1",))
            ),
            "malformed XML": b"<products>",
        }
        for name, document in cases.items():
            with self.subTest(name=name), self.assertRaises(
                currentness.CurrentnessError
            ):
                currentness.remote_version(
                    {"type": "jetbrains-updates", "name": "Rider", "code": "RD"},
                    "latest",
                    None,
                    RecordingReadTransport(
                        {"https://www.jetbrains.com/updates/updates.xml": document}
                    ),
                )

    def test_jetbrains_updates_accepts_one_exact_code_among_official_aliases(self) -> None:
        """PyCharm may publish PYA beside the exact PY verifier product code."""
        document = jetbrains_updates(("PyCharm", "PY", ("2025.3.6.1",))).replace(
            b"<code>PY</code>", b"<code>PY</code><code>PYA</code>"
        )

        version, _ = currentness.remote_version(
            {"type": "jetbrains-updates", "name": "PyCharm", "code": "PY"},
            "series",
            "2025.3",
            RecordingReadTransport(
                {"https://www.jetbrains.com/updates/updates.xml": document}
            ),
        )

        self.assertEqual("2025.3.6.1", version)

    def test_product_verifier_local_slot_is_bound_to_its_official_product(self) -> None:
        """Reject a Rider local cell checked against a sibling product feed."""
        entry = {
            "id": "rider-verifier-current",
            "local": {
                "type": "support-matrix-verifier",
                "path": "config/support-matrix.json",
                "product": "Rider",
                "endpoint": "current",
            },
            "source": {"type": "jetbrains-updates", "name": "GoLand", "code": "GO"},
            "policy": "latest",
        }

        with (
            patch.object(currentness, "local_version", return_value=("2026.2.1", None)),
            patch.object(currentness, "remote_version", return_value=("2026.2.1", None)),
            self.assertRaisesRegex(currentness.CurrentnessError, "product"),
        ):
            currentness.validate_entry(entry, EmptyTransport())

    def test_product_verifier_inventory_is_product_and_endpoint_qualified(self) -> None:
        """Shared IDE versions must not collapse independently governed cells."""
        rider = currentness.inventory_keys(
            {
                "type": "support-matrix-verifier",
                "path": "config/support-matrix.json",
                "product": "Rider",
                "endpoint": "current",
            }
        )
        goland = currentness.inventory_keys(
            {
                "type": "support-matrix-verifier",
                "path": "config/support-matrix.json",
                "product": "GoLand",
                "endpoint": "current",
            }
        )

        self.assertEqual(
            {"support-matrix-verifier:config/support-matrix.json:Rider:current"},
            rider,
        )
        self.assertEqual(
            {"support-matrix-verifier:config/support-matrix.json:GoLand:current"},
            goland,
        )
        self.assertTrue(rider.isdisjoint(goland))

    def test_product_verifier_endpoint_is_bound_to_its_release_policy(self) -> None:
        """Minimum means a declared series and current means latest, never vice versa."""
        cases = (("minimum", "latest", None), ("current", "series", "2025.3"))
        for endpoint, policy, series in cases:
            with self.subTest(endpoint=endpoint):
                entry = {
                    "id": f"rider-verifier-{endpoint}",
                    "local": {
                        "type": "support-matrix-verifier",
                        "path": "config/support-matrix.json",
                        "product": "Rider",
                        "endpoint": endpoint,
                    },
                    "source": {
                        "type": "jetbrains-updates",
                        "name": "Rider",
                        "code": "RD",
                    },
                    "policy": policy,
                }
                if series is not None:
                    entry.update(
                        {
                            "series": series,
                            "reason": "The supported release line remains explicitly bounded.",
                            "evidence": ["config/support-matrix.json"],
                        }
                    )
                with (
                    patch.object(
                        currentness, "local_version", return_value=("2025.3.5", None)
                    ),
                    patch.object(
                        currentness,
                        "remote_version",
                        return_value=("2025.3.5", None),
                    ),
                    self.assertRaisesRegex(currentness.CurrentnessError, "endpoint"),
                ):
                    currentness.validate_entry(entry, EmptyTransport())

    def test_product_verifier_minimum_series_matches_the_support_claim(self) -> None:
        """Reject narrower or broader currentness series than the platform since line."""
        for declared_series in ("2025.3.5", "2025"):
            with self.subTest(series=declared_series), TemporaryDirectory() as directory:
                root = Path(directory)
                config = root / "config"
                config.mkdir()
                (config / "support-matrix.json").write_text(
                    json.dumps(
                        {
                            "schema": 1,
                            "products": [
                                {
                                    "name": "Rider",
                                    "support": "platform",
                                    "since": "2025.3",
                                    "verifier": {
                                        "type": "Rider",
                                        "endpoints": [
                                            {
                                                "id": "minimum",
                                                "version": "2025.3.5",
                                                "build": "253.33813.59",
                                            }
                                        ],
                                    },
                                }
                            ],
                        }
                    ),
                    encoding="utf-8",
                )
                entry = {
                    "id": "rider-verifier-minimum",
                    "local": {
                        "type": "support-matrix-verifier",
                        "path": "config/support-matrix.json",
                        "product": "Rider",
                        "endpoint": "minimum",
                    },
                    "source": {
                        "type": "jetbrains-updates",
                        "name": "Rider",
                        "code": "RD",
                    },
                    "policy": "series",
                    "series": declared_series,
                    "reason": "The minimum product verifier tracks the supported IDE line.",
                    "evidence": ["config/support-matrix.json"],
                }

                with patch.object(currentness, "ROOT", root.resolve()), self.assertRaisesRegex(
                    currentness.CurrentnessError, "support series"
                ):
                    currentness.validate_entry(entry, EmptyTransport())

    def test_product_verifier_current_rejects_a_series_override(self) -> None:
        """Keep the current endpoint on the global latest release policy."""
        entry = {
            "id": "rider-verifier-current",
            "local": {
                "type": "support-matrix-verifier",
                "path": "config/support-matrix.json",
                "product": "Rider",
                "endpoint": "current",
            },
            "source": {
                "type": "jetbrains-updates",
                "name": "Rider",
                "code": "RD",
            },
            "policy": "latest",
            "series": "2025.3",
        }

        with (
            patch.object(
                currentness,
                "support_matrix_verifier_slot",
                return_value=("2025.3.5", "2025.3", "253.33813.59"),
            ),
            patch.object(
                currentness,
                "remote_version",
                return_value=("2025.3.5", None),
            ),
            self.assertRaisesRegex(currentness.CurrentnessError, "series"),
        ):
            currentness.validate_entry(entry, EmptyTransport())

    def test_product_verifier_build_matches_the_official_release(self) -> None:
        """Reject the right marketing version paired with another product build."""
        entry = {
            "id": "rider-verifier-current",
            "local": {
                "type": "support-matrix-verifier",
                "path": "config/support-matrix.json",
                "product": "Rider",
                "endpoint": "current",
            },
            "source": {
                "type": "jetbrains-updates",
                "name": "Rider",
                "code": "RD",
            },
            "policy": "latest",
        }

        with (
            patch.object(
                currentness,
                "support_matrix_verifier_slot",
                return_value=("2026.2.0.2", "2025.3", "262.8665.399"),
            ),
            patch.object(
                currentness,
                "remote_version",
                return_value=("2026.2.0.2", {"262.8665.400"}),
            ),
            self.assertRaisesRegex(currentness.CurrentnessError, "build"),
        ):
            currentness.validate_entry(entry, EmptyTransport())

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
        currentness.validate_url("https://www.jetbrains.com/updates/updates.xml")
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

    def test_github_release_asset_accepts_an_unversioned_asset_name(self) -> None:
        """Bind a tool pin to a release asset whose file name carries no version."""
        repository = "denoland/deno"
        source = {
            "type": "github-release-asset",
            "name": repository,
            "tagPrefix": "v",
            "asset": "deno-x86_64-unknown-linux-gnu.zip",
        }
        transport = FakeTransport(
            {
                f"https://api.github.com/repos/{repository}/git/matching-refs/tags/v": [
                    {"ref": "refs/tags/v2.9.6"},
                    {"ref": "refs/tags/v2.9.7"},
                ],
                f"https://api.github.com/repos/{repository}/releases/tags/v2.9.7": {
                    "tag_name": "v2.9.7",
                    "draft": False,
                    "prerelease": False,
                    "assets": [
                        {
                            "name": "deno-x86_64-unknown-linux-gnu.zip",
                            "state": "uploaded",
                            "size": 1_000_000,
                            "digest": f"sha256:{'c' * 64}",
                        }
                    ],
                },
            },
        )

        version, digests = currentness.remote_version(source, "latest", None, transport)

        self.assertEqual("2.9.7", version)
        self.assertEqual({"c" * 64}, digests)

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
        for field, value in (("state", "new"), ("size", 0), ("size", 65 * 1024 * 1024)):
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
        self.assertEqual("4.0.1", version)

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

    def test_release_skips_only_already_released_versions_without_an_artifact(self) -> None:
        """Let unbuilt merges pass only when their version is already published."""
        workflow = (currentness.ROOT / ".github" / "workflows" / "release.yml").read_text(encoding="utf-8")
        resolve = workflow.split("  resolve:", 1)[1].split("  release:", 1)[0]
        self.assertIn('select(.name == "plugin" and .expired == false)', resolve)
        self.assertIn('[ -z "$REQUESTED_RUN_ID" ] && [ -n "$version" ] && gh release view "v$version"', resolve)
        self.assertIn("promote=false", resolve)
        self.assertIn("exit 1", resolve.split("promote=false", 1)[1])
        self.assertIn("if: needs.resolve.outputs.promote == 'true'", workflow.split("  release:", 1)[1])


SHA_OLD = "a" * 40
SHA_NEW = "b" * 40


def write_tree(root: Path, files: dict[str, str]) -> None:
    """Create a small repository fixture under a temporary root."""
    for relative, text in files.items():
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")


def stale_entry(identifier: str, local: dict[str, Any], policy: str = "latest", **extra: Any) -> dict[str, Any]:
    """Build an inventory entry for a stale-pin fixture."""
    return {"id": identifier, "local": local, "source": {"type": "pypi", "name": identifier}, "policy": policy, **extra}


def stale_pin(local: str, expected: str, shas: set[str] | None = None, local_sha: str | None = None) -> currentness.StalePin:
    """Build the structured stale-pin error a check raises."""
    return currentness.StalePin("stale", local=local, local_sha=local_sha, expected=expected, expected_shas=shas)


class FakeToolbox(currentness.Toolbox):
    """Pretend a lock tool exists and record its invocations without running it."""

    def __init__(self, available: set[str], fail: bool = False) -> None:
        """Choose the tools on PATH and whether runs fail."""
        super().__init__(which=lambda name: f"/bin/{name}" if name in available else None)
        self.calls: list[tuple[list[str], str]] = []
        self.fail = fail

    def execute(self, step: currentness.Step, cwd: Path) -> None:
        """Record the call and write the lock files a real tool would regenerate."""
        argv = self.command(step, cwd)
        if argv is None:
            raise currentness.ApplyError(f"{step.tool} is not available")
        self.calls.append((argv, str(cwd)))
        if self.fail:
            raise currentness.ApplyError(f"{step.tool} exited 1: resolver failure")
        for output in step.outputs:
            (currentness.ROOT / output).write_text("regenerated\n", encoding="utf-8")


class ReportAllPinsTest(unittest.TestCase):
    """One run reports every stale or unverifiable pin and keeps the failing exit."""

    def entries(self) -> list[dict[str, Any]]:
        """Return three entries: current, stale and unverifiable."""
        return [stale_entry("fresh", {"type": "property"}), stale_entry("old", {"type": "property"}), stale_entry("broken", {"type": "property"})]

    def remote(self, source: dict[str, Any], policy: str, series: str | None, transport: Any) -> tuple[str, None]:
        """Resolve official versions, failing for one source."""
        if source["name"] == "broken":
            raise currentness.CurrentnessError("Unable to read official release endpoint")
        return "2.0.0", None

    def local(self, local: dict[str, Any]) -> tuple[str, None]:
        """Return the local version by entry."""
        return "2.0.0", None

    def test_run_collects_every_failure_in_one_exception(self) -> None:
        """Every bad pin is named once, in the single-line entry format."""
        versions = {"fresh": "2.0.0", "old": "1.0.0", "broken": "2.0.0"}
        with (
            patch.object(currentness, "load_config", return_value=self.entries()),
            patch.object(currentness, "validate_inventory_coverage"),
            patch.object(currentness, "local_version", side_effect=lambda local: (versions.pop(next(iter(versions))), None)),
            patch.object(currentness, "remote_version", side_effect=self.remote),
            self.assertRaises(currentness.CurrentnessFailure) as caught,
        ):
            currentness.run(FakeTransport({}))
        self.assertEqual(
            ["old: old is stale: local 1.0.0, official 2.0.0", "broken: Unable to read official release endpoint"],
            caught.exception.errors,
        )

    def test_offline_collects_every_local_drift(self) -> None:
        """Offline mode reports all drifted compatibility pins, not only the first."""
        drifted = [
            {"id": name, "local": {"type": "property"}, "policy": "compatibility", "expected": "1",
             "reason": "A reviewed compatibility exception for the offline test.", "evidence": ["build.gradle.kts"]}
            for name in ("one", "two")
        ]
        with (
            patch.object(currentness, "load_config", return_value=drifted),
            patch.object(currentness, "validate_inventory_coverage"),
            patch.object(currentness, "local_version", return_value=("2", None)),
            self.assertRaises(currentness.CurrentnessFailure) as caught,
        ):
            currentness.run_offline()
        self.assertEqual(2, len(caught.exception.errors))

    def test_main_keeps_the_error_format_and_exit_code(self) -> None:
        """Each bad pin prints one ERROR line on stderr and the run exits non-zero."""
        entries = self.entries()
        stderr, stdout = io.StringIO(), io.StringIO()
        with TemporaryDirectory() as temporary:
            report, summary = Path(temporary) / "report.txt", Path(temporary) / "summary.json"
            with (
                patch.object(currentness, "load_config", return_value=entries),
                patch.object(currentness, "validate_inventory_coverage"),
                patch.object(currentness, "Transport", return_value=FakeTransport({})),
                patch.object(currentness, "local_version", side_effect=[("2.0.0", None), ("1.0.0", None), ("2.0.0", None)]),
                patch.object(currentness, "remote_version", side_effect=self.remote),
                patch.object(currentness, "plan_updates", return_value=[]),
                contextlib.redirect_stderr(stderr),
                contextlib.redirect_stdout(stdout),
            ):
                code = currentness.main(["--report", str(report), "--json", str(summary)])
            self.assertEqual(1, code)
            lines = stderr.getvalue().splitlines()
            self.assertEqual("release-currentness: ERROR: old: old is stale: local 1.0.0, official 2.0.0", lines[0])
            self.assertEqual(2, len(lines))
            self.assertIn("Unverifiable or drifted (2)", report.read_text(encoding="utf-8"))
            self.assertFalse(json.loads(summary.read_text(encoding="utf-8"))["ok"])

    def test_write_cannot_run_offline(self) -> None:
        """Applying updates needs the live check."""
        with self.assertRaises(SystemExit) as caught, contextlib.redirect_stderr(io.StringIO()):
            currentness.main(["--write", "--offline"])
        self.assertEqual(2, caught.exception.code)

    def test_pull_request_and_release_callers_keep_their_contract(self) -> None:
        """Workflows call the script with no flags or with --offline and nothing else."""
        text = "".join(path.read_text(encoding="utf-8") for path in (currentness.ROOT / ".github" / "workflows").glob("*.yml"))
        invocations = re.findall(r"(?<!`)python3 scripts/release_currentness\.py[^\n`]*", text)
        self.assertTrue(invocations)
        self.assertTrue(all("--write" not in line and "--docker" not in line for line in invocations))


class MechanicalUpdateTest(unittest.TestCase):
    """The rewriter moves exactly the pin a local descriptor names."""

    def apply(self, root: Path, entry: dict[str, Any], old: str, new: str, sha: str | None = None, toolbox: currentness.Toolbox | None = None) -> tuple[currentness.Update, currentness.Workspace]:
        """Apply one update inside a fixture root."""
        update = currentness.Update(entry, old, new, sha)
        workspace = currentness.Workspace()
        if not (root / currentness.SUPPORT_MATRIX).exists():
            write_tree(root, {currentness.SUPPORT_MATRIX: "{}\n"})
        with patch.object(currentness, "ROOT", root):
            currentness.apply_update(update, workspace, toolbox or FakeToolbox(set()))
            update.files = workspace.changed()
        return update, workspace

    def test_gradle_plugin_property_and_maven_coordinate(self) -> None:
        """Gradle and property pins change in place and nowhere else."""
        with TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            write_tree(root, {
                "build.gradle.kts": 'plugins { id("info.solidsoft.pitest") version "1.0.0"\n id("other") version "1.0.0" }\n',
                "core/build.gradle.kts": 'dependencies { implementation("org.tomlj:tomlj:2.1.1") }\n',
                "gradle.properties": "affected.studio.version=2026.1.4.8\nother=2026.1.4.8\n",
            })
            self.apply(root, stale_entry("p", {"type": "gradle-plugin", "path": "build.gradle.kts", "name": "info.solidsoft.pitest"}), "1.0.0", "1.1.0")
            tomlj, _ = self.apply(root, stale_entry("t", {"type": "maven", "name": "org.tomlj:tomlj"}), "2.1.1", "2.2.0")
            self.apply(root, stale_entry("s", {"type": "property", "path": "gradle.properties", "name": "affected.studio.version"}), "2026.1.4.8", "2026.2.1.8")
            self.assertEqual(["core/build.gradle.kts"], tomlj.files)
            self.assertEqual('plugins { id("info.solidsoft.pitest") version "1.1.0"\n id("other") version "1.0.0" }\n', (root / "build.gradle.kts").read_text(encoding="utf-8"))
            self.assertIn("tomlj:2.2.0", (root / "core/build.gradle.kts").read_text(encoding="utf-8"))
            self.assertEqual("affected.studio.version=2026.2.1.8\nother=2026.1.4.8\n", (root / "gradle.properties").read_text(encoding="utf-8"))

    KOTLIN_ENTRY = {"type": "gradle-plugin", "path": "build.gradle.kts", "name": "org.jetbrains.kotlin.jvm"}

    def kotlin_tree(self, shim_version: str) -> dict[str, str]:
        """Build the files a Kotlin plugin update has to move together."""
        return {
            "build.gradle.kts": 'plugins { kotlin("jvm") version "2.4.20" }\n',
            currentness.CODEQL_KOTLIN_SHIM: f'if (details.requested.version != "{shim_version}") {{}}\n',
            currentness.CODEQL_KOTLIN_PROBE: 'SOURCE_VERSION = "2.4.20"\n',
            currentness.CI_CONTRACTS: f'CODEQL_KOTLIN_COMPAT_SHA256 = "{"a" * 64}"\nCODEQL_KOTLIN_PROBE_SHA256 = "{"b" * 64}"\n',
        }

    def test_kotlin_update_moves_the_codeql_shim_and_its_digests(self) -> None:
        """The reviewed CodeQL compiler mapping follows the Kotlin plugin and stays pinned by digest."""
        with TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            write_tree(root, self.kotlin_tree("2.4.20"))
            update, _ = self.apply(root, stale_entry("kotlin", self.KOTLIN_ENTRY), "2.4.20", "2.4.21")
            shim = (root / currentness.CODEQL_KOTLIN_SHIM).read_text(encoding="utf-8")
            probe = (root / currentness.CODEQL_KOTLIN_PROBE).read_text(encoding="utf-8")
            self.assertEqual('if (details.requested.version != "2.4.21") {}\n', shim)
            self.assertEqual('SOURCE_VERSION = "2.4.21"\n', probe)
            self.assertEqual(
                f'CODEQL_KOTLIN_COMPAT_SHA256 = "{hashlib.sha256(shim.encode()).hexdigest()}"\n'
                f'CODEQL_KOTLIN_PROBE_SHA256 = "{hashlib.sha256(probe.encode()).hexdigest()}"\n',
                (root / currentness.CI_CONTRACTS).read_text(encoding="utf-8"),
            )
            self.assertIn(currentness.CODEQL_KOTLIN_FLAG, update.flags)
            self.assertEqual(
                sorted(["build.gradle.kts", currentness.CODEQL_KOTLIN_SHIM, currentness.CODEQL_KOTLIN_PROBE, currentness.CI_CONTRACTS]),
                sorted(update.files),
            )

    def test_kotlin_update_refuses_a_shim_on_another_version(self) -> None:
        """A shim that does not name the old version is left for a human instead of being guessed."""
        with TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            write_tree(root, self.kotlin_tree("2.4.10"))
            with self.assertRaisesRegex(currentness.ApplyError, "codeql-kotlin-compat"):
                self.apply(root, stale_entry("kotlin", self.KOTLIN_ENTRY), "2.4.20", "2.4.21")
            self.assertEqual('SOURCE_VERSION = "2.4.20"\n', (root / currentness.CODEQL_KOTLIN_PROBE).read_text(encoding="utf-8"))

    def test_workflow_pip_pin_action_sha_and_branch_comment(self) -> None:
        """Action SHAs move with their version comment; branch pins keep the branch name."""
        workflow = (
            "jobs:\n  a:\n    steps:\n"
            f"      - uses: actions/checkout@{SHA_OLD} # v7.0.1\n"
            f"      - uses: dtolnay/rust-toolchain@{SHA_OLD} # stable\n"
            "      - run: python -m pip install ruff==0.16.9 uv==0.12.21\n"
        )
        with TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            write_tree(root, {".github/workflows/ci.yml": workflow})
            self.apply(root, stale_entry("ruff", {"type": "pip-command", "name": "ruff", "path": ".github/workflows/ci.yml"}), "0.16.9", "0.16.10")
            self.apply(root, stale_entry("checkout", {"type": "github-action", "name": "actions/checkout"}), "v7.0.1", "7.1.0", SHA_NEW)
            self.apply(root, stale_entry("rust", {"type": "github-action", "name": "dtolnay/rust-toolchain"}, "branch"), SHA_OLD, "stable", SHA_NEW)
            text = (root / ".github/workflows/ci.yml").read_text(encoding="utf-8")
            self.assertIn(f"actions/checkout@{SHA_NEW} # v7.1.0", text)
            self.assertIn(f"dtolnay/rust-toolchain@{SHA_NEW} # stable", text)
            self.assertIn("ruff==0.16.10 uv==0.12.21", text)

    def test_release_asset_moves_version_and_digest_together(self) -> None:
        """A release asset pin carries its digest."""
        with TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            write_tree(root, {".github/workflows/c.yml": f'env:\n  BUN_VERSION: "1.4.2"\n  BUN_SHA256: "{"c" * 64}"\n'})
            entry = stale_entry("bun", {"type": "github-release-asset", "name": "BUN_VERSION", "digestName": "BUN_SHA256", "occurrences": {".github/workflows/c.yml": 1}})
            self.apply(root, entry, "1.4.2", "1.5.0", "d" * 64)
            self.assertEqual(f'env:\n  BUN_VERSION: "1.5.0"\n  BUN_SHA256: "{"d" * 64}"\n', (root / ".github/workflows/c.yml").read_text(encoding="utf-8"))

    def test_matrix_value_moves_in_workflow_and_inventory_and_renames_the_id(self) -> None:
        """A matrix pin updates the workflow, the inventory value and an id naming its minor."""
        config = (
            '{"schema":1,"entries":[\n'
            '  {"id":"phpunit-13.4-matrix","local":{"type":"workflow-matrix","path":".github/workflows/c.yml","name":"phpunit","value":"13.4.0"},"policy":"latest"}\n]}\n'
        )
        with TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            write_tree(root, {
                ".github/workflows/c.yml": '        include:\n          - php: "8.4"\n            phpunit: "13.4.0"\n',
                currentness.CONFIG_PATH: config,
                "conformance/cli-fixtures/composer/composer.json": '{"require-dev": {"phpunit/phpunit": "13.4.0"}}\n',
                "conformance/cli-fixtures/composer/composer.lock": "lock\n",
                "conformance/cli-fixtures/composer/locks/phpunit-13.4.0.lock": "old lock\n",
            })
            entry = {"id": "phpunit-13.4-matrix", "local": {"type": "workflow-matrix", "path": ".github/workflows/c.yml", "name": "phpunit", "value": "13.4.0"}, "policy": "latest"}
            toolbox = FakeToolbox({"composer"})
            fixture = root / "conformance/cli-fixtures/composer"

            def fake_execute(step: currentness.Step, cwd: Path) -> None:
                """Write the lock the scratch resolution produces."""
                (cwd / "composer.lock").write_text("new lock\n", encoding="utf-8")

            toolbox.execute = fake_execute  # type: ignore[method-assign]
            update, _ = self.apply(root, entry, "13.4.0", "13.5.0", toolbox=toolbox)
            self.assertEqual("phpunit-13.5-matrix", update.new_id)
            self.assertIn('"id":"phpunit-13.5-matrix"', (root / currentness.CONFIG_PATH).read_text(encoding="utf-8"))
            self.assertIn('"value":"13.5.0"', (root / currentness.CONFIG_PATH).read_text(encoding="utf-8"))
            self.assertIn('phpunit: "13.5.0"', (root / ".github/workflows/c.yml").read_text(encoding="utf-8"))
            self.assertEqual("new lock\n", (fixture / "locks/phpunit-13.5.0.lock").read_text(encoding="utf-8"))
            self.assertFalse((fixture / "locks/phpunit-13.4.0.lock").exists())
            self.assertEqual('{"require-dev": {"phpunit/phpunit": "13.4.0"}}\n', (fixture / "composer.json").read_text(encoding="utf-8"))
            self.assertIn("conformance/cli-fixtures/composer/locks/phpunit-13.5.0.lock", update.files)

    def test_support_matrix_verifier_moves_version_and_build(self) -> None:
        """A product verifier endpoint changes only its own version and build."""
        line = (
            '      "verifier": {"type": "Rider", "endpoints": [{"id": "minimum", "version": "2025.3.5.2", "build": "253.1.1", "gradle": "present"}, '
            '{"id": "current", "version": "2026.2.3.1", "build": "262.1.1", "gradle": "present"}]},\n'
        )
        with TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            write_tree(root, {currentness.SUPPORT_MATRIX: line})
            entry = stale_entry("rider", {"type": "support-matrix-verifier", "path": currentness.SUPPORT_MATRIX, "product": "Rider", "endpoint": "current"})
            self.apply(root, entry, "2026.2.3.1", "2026.2.4", "262.2.2")
            text = (root / currentness.SUPPORT_MATRIX).read_text(encoding="utf-8")
            self.assertIn('"version": "2026.2.4", "build": "262.2.2"', text)
            self.assertIn('"version": "2025.3.5.2", "build": "253.1.1"', text)

    def test_tested_version_prose_moves_but_range_lower_bounds_do_not(self) -> None:
        """Exact tested versions follow the pin; a range bound that equals it stays."""
        matrix = '      "versions": "Rust; cargo-nextest 0.9.143\u20130.9.x; Pest 5 tested at Pest 5.2.1 and 0.9.143",\n'
        with TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            write_tree(root, {currentness.SUPPORT_MATRIX: matrix, "package.json": '{"devDependencies": {"jest": "0.9.143"}}\n'})
            entry = stale_entry("jest", {"type": "json-dependency", "path": "package.json", "name": "jest"})
            write_tree(root, {"package-lock.json": "old\n"})
            update, _ = self.apply(root, entry, "0.9.143", "0.9.146", toolbox=FakeToolbox({"npm"}))
            text = (root / currentness.SUPPORT_MATRIX).read_text(encoding="utf-8")
            self.assertIn("0.9.143\u20130.9.x", text)
            self.assertIn("and 0.9.146", text)
            self.assertIn("support-matrix prose moved", update.flags)

    def test_lock_regeneration_runs_the_real_tool_in_the_manifest_directory(self) -> None:
        """An npm bump regenerates the lock through the tool, not by editing it."""
        with TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            write_tree(root, {"node/package.json": '{"devDependencies": {"jest": "30.5.2"}}\n', "node/package-lock.json": "old\n"})
            toolbox = FakeToolbox({"npm"})
            entry = stale_entry("jest", {"type": "json-dependency", "path": "node/package.json", "name": "jest"})
            update, _ = self.apply(root, entry, "30.5.2", "30.6.0", toolbox=toolbox)
            self.assertEqual(["node/package-lock.json", "node/package.json"], update.files)
            self.assertEqual("npm", toolbox.calls[0][0][0])
            self.assertEqual(str(root / "node"), toolbox.calls[0][1])

    def test_failed_tool_rolls_the_manifest_back(self) -> None:
        """A resolver failure leaves no half-updated pin behind."""
        with TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            write_tree(root, {"node/package.json": '{"devDependencies": {"jest": "30.5.2"}}\n', "node/package-lock.json": "old\n", currentness.SUPPORT_MATRIX: "{}\n"})
            entry = stale_entry("jest", {"type": "json-dependency", "path": "node/package.json", "name": "jest"})
            update = currentness.Update(entry, "30.5.2", "30.6.0", None, line="jest: stale")
            with patch.object(currentness, "ROOT", root):
                currentness.apply_updates([update], FakeToolbox({"npm"}, fail=True), FakeTransport({}))
            self.assertEqual("manual", update.status)
            self.assertIn("rolled back", update.reason)
            self.assertEqual('{"devDependencies": {"jest": "30.5.2"}}\n', (root / "node/package.json").read_text(encoding="utf-8"))
            self.assertEqual("old\n", (root / "node/package-lock.json").read_text(encoding="utf-8"))


class UpdatePolicyTest(unittest.TestCase):
    """Policies decide whether a stale pin moves, waits for a human, or needs a decision."""

    def plan(self, entry: dict[str, Any], stale: currentness.StalePin, toolbox: currentness.Toolbox | None = None, files: dict[str, str] | None = None) -> currentness.Update:
        """Plan one stale entry inside a fixture root."""
        outcome = currentness.Outcome(entry, error=f"{entry['id']}: stale", stale=stale)
        with TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            write_tree(root, {currentness.SUPPORT_MATRIX: "{}\n", **(files or {})})
            with patch.object(currentness, "ROOT", root):
                return currentness.plan_updates([outcome], FakeTransport({}), toolbox or FakeToolbox(set()))[0]

    def test_major_version_change_needs_a_decision(self) -> None:
        """A latest pin never crosses a major automatically."""
        entry = stale_entry("jest", {"type": "property", "path": "p", "name": "jest"})
        update = self.plan(entry, stale_pin("30.5.2", "31.0.0"), files={"p": "jest=30.5.2\n"})
        self.assertEqual("decision", update.status)
        self.assertIn("major version change", update.reason)

    def test_calendar_year_change_and_zero_minor_are_major(self) -> None:
        """IDE years and 0.x minors count as compatibility boundaries."""
        for old, new in (("2026.2.1.8", "2027.1.1.1"), ("0.9.146", "0.10.0"), ("1.4.2", "1.3.9")):
            entry = stale_entry("tool", {"type": "property", "path": "p", "name": "tool"})
            update = self.plan(entry, stale_pin(old, new), files={"p": f"tool={old}\n"})
            self.assertEqual("decision", update.status, (old, new))

    def test_same_version_with_a_different_identity_needs_a_decision(self) -> None:
        """A moved tag is investigated, not rewritten."""
        entry = stale_entry("checkout", {"type": "github-action", "name": "actions/checkout"})
        stale = currentness.StalePin("sha", local="v7.0.1", local_sha=SHA_OLD, expected="7.0.1", expected_shas={SHA_NEW}, identity_only=True)
        self.assertEqual("decision", self.plan(entry, stale).status)

    def test_minor_of_a_named_tool_is_applied_and_flagged(self) -> None:
        """A support-matrix runner gets its minor applied with a review flag."""
        entry = stale_entry("vitest", {"type": "property", "path": "p", "name": "vitest"})
        files = {currentness.SUPPORT_MATRIX: '{"products": [{"versions": "Vitest 2\u20135"}]}\n', "p": "vitest=5.0.3\n"}
        update = self.plan(entry, stale_pin("5.0.3", "5.1.0"), files=files)
        self.assertEqual("auto", update.status)
        self.assertTrue(any("support matrix" in flag for flag in update.flags))

    def test_patch_of_an_unnamed_tool_is_not_flagged(self) -> None:
        """Routine patches stay quiet."""
        entry = stale_entry("ruff", {"type": "property", "path": "p", "name": "ruff"})
        update = self.plan(entry, stale_pin("0.16.9", "0.16.10"), files={"p": "ruff=0.16.9\n"})
        self.assertEqual(("auto", []), (update.status, update.flags))

    def test_missing_lock_tool_leaves_the_pin_for_a_manual_command(self) -> None:
        """Without the real tool the plan lists the exact command and touches nothing."""
        entry = stale_entry("jest", {"type": "json-dependency", "path": "node/package.json", "name": "jest"})
        files = {"node/package.json": '{"devDependencies": {"jest": "30.5.2"}}\n'}
        update = self.plan(entry, stale_pin("30.5.2", "30.6.0"), files=files)
        self.assertEqual("manual", update.status)
        self.assertEqual(["cd node && npm install --package-lock-only --ignore-scripts --no-audit --no-fund"], update.commands)
        self.assertIn("npm", update.reason)

    def test_docker_makes_composer_available_only_when_asked(self) -> None:
        """composer:2 is used only with --docker."""
        step = currentness.Step("composer", ["composer", "update"], "d", [], "composer:2")
        host = FakeToolbox({"docker"})
        self.assertIsNone(host.command(step, Path("/x")))
        host.docker = True
        self.assertEqual(["docker", "run"], host.command(step, Path("/x"))[:2])  # type: ignore[index]

    def test_gradle_wrapper_is_manual_with_the_wrapper_command(self) -> None:
        """The wrapper moves with its jar and scripts, so it is never rewritten in place."""
        entry = {"id": "gradle", "local": {"type": "gradle-wrapper"}, "source": {"type": "gradle"}, "policy": "latest"}
        outcome = currentness.Outcome(entry, error="gradle: stale", stale=stale_pin("9.8.0", "9.9.0"))
        transport = FakeTransport({"https://services.gradle.org/versions/current": {"checksum": "f" * 64}})
        with TemporaryDirectory() as temporary, patch.object(currentness, "ROOT", Path(temporary).resolve()):
            update = currentness.plan_updates([outcome], transport, FakeToolbox(set()))[0]
        self.assertEqual("manual", update.status)
        self.assertEqual([f"./gradlew wrapper --gradle-version 9.9.0 --distribution-type bin --gradle-distribution-sha256-sum {'f' * 64}"], update.commands)

    def test_compatibility_pin_is_never_planned(self) -> None:
        """A compatibility pin that drifted stays an error and produces no update."""
        entry = {"id": "c", "local": {"type": "property"}, "policy": "compatibility", "expected": "1",
                 "reason": "A reviewed compatibility exception for the policy test.", "evidence": ["build.gradle.kts"]}
        with patch.object(currentness, "local_version", return_value=("2", None)):
            outcomes = currentness.collect([entry], FakeTransport({}))
        self.assertIsNone(outcomes[0].stale)
        self.assertEqual([], currentness.plan_updates(outcomes, FakeTransport({}), FakeToolbox(set())))

    def test_series_update_stays_inside_its_series(self) -> None:
        """The official version a series pin moves to is resolved inside the series."""
        self.assertEqual("11.5.60", currentness.newest(["11.5.60", "12.0.1", "11.6.0"], "11.5"))

    def test_branch_pin_moves_to_the_branch_head_sha(self) -> None:
        """A branch pin takes the head SHA reported by the source."""
        entry = {"id": "rust", "local": {"type": "github-action", "name": "dtolnay/rust-toolchain"},
                 "source": {"type": "github-branch", "name": "dtolnay/rust-toolchain", "branch": "stable"}, "policy": "branch"}
        stale = stale_pin("stable", "stable", {SHA_NEW}, SHA_OLD)
        workflow = f"      - uses: dtolnay/rust-toolchain@{SHA_OLD} # stable\n"
        update = self.plan(entry, stale, files={".github/workflows/c.yml": workflow})
        self.assertEqual(("auto", SHA_NEW), (update.status, update.new_sha))
        self.assertEqual((SHA_OLD[:12], SHA_NEW[:12]), update.label)

    def test_summary_lists_updates_manual_steps_and_decisions(self) -> None:
        """The machine summary and its text agree and drive the exit status."""
        auto = currentness.Update(stale_entry("a", {"type": "property"}), "1.0.0", "1.0.1", None, files=["f"])
        manual = currentness.Update(stale_entry("m", {"type": "property"}), "1", "1.1", None, status="manual", reason="needs npm", commands=["npm ci"])
        decision = currentness.Update(stale_entry("d", {"type": "property"}), "1", "2.0", None, status="decision", reason="major version change 1 -> 2.0")
        summary = currentness.build_summary("check", [], [auto, manual, decision], [], [])
        self.assertFalse(summary["ok"])
        text = currentness.render_summary(summary)
        for expected in ("Applicable with --write (1)", "a: 1.0.0 -> 1.0.1 (f)", "Needs manual update (1)", "$ npm ci", "Needs a decision (1)"):
            self.assertIn(expected, text)
        self.assertEqual(["a"], [item["id"] for item in summary["updates"]])
        self.assertTrue(currentness.build_summary("check", [], [], [], [])["ok"])


class WorkflowTest(unittest.TestCase):
    """The scheduled workflow reports the full list but never publishes changes."""

    def test_scheduled_failure_issue_carries_the_report_and_no_pull_request_is_opened(self) -> None:
        """The issue body embeds the report; the workflow cannot push or open pull requests."""
        text = (currentness.ROOT / ".github" / "workflows" / "currentness.yml").read_text(encoding="utf-8")
        self.assertIn("--report", text)
        self.assertIn("--body-file", text)
        self.assertIn("release_currentness.py --write", text)
        self.assertNotRegex(text, r"(?<!`)python3 scripts/release_currentness\.py[^\n`]*--write")
        self.assertNotIn("pull-requests: write", text)
        self.assertNotIn("contents: write", text)
        self.assertNotIn("gh pr", text)
        self.assertNotIn("git push", text)


class SubprocessToolboxTest(unittest.TestCase):
    """The toolbox turns a failing tool into a readable error."""

    def test_non_zero_exit_names_the_command(self) -> None:
        """A failing tool surfaces its exit code and output."""

        def runner(*args: Any, **kwargs: Any) -> subprocess.CompletedProcess[str]:
            """Return a failed process result."""
            return subprocess.CompletedProcess(args, 3, "", "resolver conflict")

        toolbox = currentness.Toolbox(run=runner, which=lambda name: "/bin/x")
        with self.assertRaisesRegex(currentness.ApplyError, "exited 3: resolver conflict"):
            toolbox.execute(currentness.Step("npm", ["npm", "ci"], "", []), Path("/"))


if __name__ == "__main__":
    unittest.main()

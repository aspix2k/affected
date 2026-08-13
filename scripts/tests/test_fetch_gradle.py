"""Tests for seeding the Gradle wrapper cache without services.gradle.org."""

from __future__ import annotations

import hashlib
import io
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from urllib.error import HTTPError
from urllib.request import OpenerDirector

from scripts import fetch_gradle


OFFICIAL = "https://services.gradle.org/distributions/gradle-9.7.0-bin.zip"
PAYLOAD = b"gradle-distribution"
DIGEST = hashlib.sha256(PAYLOAD).hexdigest()


class FakeResponse(io.BytesIO):
    """Minimal urlopen response with a status code."""

    def __init__(self, payload: bytes, status: int = 200) -> None:
        super().__init__(payload)
        self.status = status

    def __enter__(self) -> FakeResponse:
        return self

    def __exit__(self, *arguments: object) -> None:
        self.close()


class FakeOpener(OpenerDirector):
    """Return canned payloads or raise the configured errors per URL."""

    def __init__(self, responses: dict[str, object]) -> None:
        super().__init__()
        self.responses = responses
        self.calls: list[str] = []

    def open(self, request, timeout=None):  # type: ignore[no-untyped-def]
        url = request.full_url if hasattr(request, "full_url") else str(request)
        self.calls.append(url)
        result = self.responses[url]
        if isinstance(result, Exception):
            raise result
        if isinstance(result, list):
            item = result.pop(0)
            if isinstance(item, Exception):
                raise item
            return FakeResponse(item)
        return FakeResponse(result)


class FetchGradleTest(unittest.TestCase):
    """Prefer GitHub, verify SHA-256, and reuse an existing wrapper cache."""

    def test_github_mirror_matches_the_official_zip_name(self) -> None:
        """CI should hit GitHub release assets, not services.gradle.org first."""
        self.assertEqual(
            fetch_gradle.github_mirror(OFFICIAL),
            "https://github.com/gradle/gradle-distributions/releases/download/v9.7.0/gradle-9.7.0-bin.zip",
        )
        self.assertEqual(
            fetch_gradle.candidate_urls(OFFICIAL)[0],
            fetch_gradle.github_mirror(OFFICIAL),
        )

    def test_wrapper_hash_matches_gradle_layout(self) -> None:
        """The seeded path must be the directory Gradle already uses."""
        self.assertEqual(
            fetch_gradle.distribution_hash(OFFICIAL),
            "d4tj7w02tcgubx9zk9hbippn6",
        )

    def test_existing_verified_zip_is_not_downloaded(self) -> None:
        """A cache hit must not open the network."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            home = root / "home"
            self.write_wrapper(root)
            archive = self.cache_archive(home)
            archive.parent.mkdir(parents=True)
            archive.write_bytes(PAYLOAD)
            opener = FakeOpener({})
            fetched = fetch_gradle.fetch(root, user_home=home, opener=opener)
            self.assertEqual(archive, fetched)
            self.assertEqual([], opener.calls)

    def test_github_success_skips_services_gradle_org(self) -> None:
        """A healthy GitHub asset must never fall through to the flaky CDN."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            home = root / "home"
            self.write_wrapper(root)
            opener = FakeOpener({fetch_gradle.github_mirror(OFFICIAL): PAYLOAD})
            fetched = fetch_gradle.fetch(root, user_home=home, opener=opener, sleeper=lambda _: None)
            self.assertEqual(DIGEST, fetch_gradle.sha256_file(fetched))
            self.assertEqual([fetch_gradle.github_mirror(OFFICIAL)], opener.calls)

    def test_github_503_falls_back_to_official_url(self) -> None:
        """If GitHub is down, the official URL is still tried."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            home = root / "home"
            self.write_wrapper(root)
            opener = FakeOpener(
                {
                    fetch_gradle.github_mirror(OFFICIAL): HTTPError(
                        fetch_gradle.github_mirror(OFFICIAL),
                        503,
                        "Unavailable",
                        hdrs=None,
                        fp=None,
                    ),
                    OFFICIAL: PAYLOAD,
                }
            )
            fetched = fetch_gradle.fetch(root, user_home=home, opener=opener, sleeper=lambda _: None)
            self.assertEqual(DIGEST, fetch_gradle.sha256_file(fetched))
            self.assertIn(OFFICIAL, opener.calls)

    def test_checksum_mismatch_is_not_installed(self) -> None:
        """A corrupt zip must not be left where the wrapper will trust it."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            home = root / "home"
            self.write_wrapper(root)
            opener = FakeOpener({fetch_gradle.github_mirror(OFFICIAL): b"nope"})
            with self.assertRaisesRegex(fetch_gradle.FetchGradleError, "checksum"):
                fetch_gradle.fetch(root, user_home=home, opener=opener, sleeper=lambda _: None)
            self.assertFalse(self.cache_archive(home).exists())

    def write_wrapper(self, root: Path) -> None:
        """Write a wrapper properties file with the production checksum field."""
        path = root / "gradle/wrapper/gradle-wrapper.properties"
        path.parent.mkdir(parents=True)
        path.write_text(
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.0-bin.zip\n"
            f"distributionSha256Sum={DIGEST}\n",
            encoding="utf-8",
        )

    def cache_archive(self, home: Path) -> Path:
        """Return the wrapper cache path for the official 9.7.0 URL."""
        return fetch_gradle.cache_dir(home, OFFICIAL) / "gradle-9.7.0-bin.zip"


if __name__ == "__main__":
    unittest.main()

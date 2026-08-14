"""Assemble Marketplace changelog notes from unique product fragments."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import ci_scope

FRAGMENT_DIR = Path("docs/changelog.d")
CHANGELOG = Path("docs/CHANGELOG.md")
TYPES = {
    "added": "Added",
    "changed": "Changed",
    "deprecated": "Deprecated",
    "removed": "Removed",
    "fixed": "Fixed",
    "security": "Security",
}
FILENAME = re.compile(
    r"^([a-z0-9]+(?:-[a-z0-9]+)*)\.(" + "|".join(TYPES) + r")\.md$"
)
UNRELEASED = re.compile(r"(?ms)^## \[Unreleased\]\n(.*?)(?=^## |\Z)")
INFRASTRUCTURE = re.compile(
    r"(?i)\b("
    r"kover|pitest|codeql|rebase|auto-?merge|minBound|"
    r"coverage floor|github actions|github-actions|"
    r"merge train|changelog\.d|koverVerify"
    r")\b|(?<![A-Za-z])CI(?![A-Za-z])"
)


class ChangelogError(RuntimeError):
    """Describe a fail-closed changelog fragment problem."""


@dataclass(frozen=True)
class Fragment:
    """One Marketplace-facing Unreleased bullet."""

    slug: str
    kind: str
    heading: str
    bullet: str
    path: Path


def check_paths(paths: list[str], root: Path = ROOT) -> None:
    """Reject assembled changelog edits and validate any product fragments."""
    normalized = [ci_scope.normalize(path) for path in paths]
    if str(CHANGELOG) in normalized:
        raise ChangelogError(
            "Pull requests must not edit docs/CHANGELOG.md. "
            "Add docs/changelog.d/<slug>.<type>.md for a product change."
        )
    for path in normalized:
        relative = Path(path)
        if relative.parent != FRAGMENT_DIR:
            continue
        if relative.name in {".gitkeep"} or relative.name.startswith("."):
            continue
        parse_fragment(root / relative)


def check(root: Path = ROOT, *, paths: list[str] | None = None, base: str | None = None) -> None:
    """Classify a path list, a git range, or the current GitHub event."""
    check_paths(resolve_changed_paths(root, paths=paths, base=base), root=root)


def render(root: Path = ROOT) -> bool:
    """Fold pending fragments into Unreleased and delete the consumed files."""
    fragments = list_fragments(root)
    if not fragments:
        return False
    path = root / CHANGELOG
    if not path.is_file() or path.is_symlink():
        raise ChangelogError("docs/CHANGELOG.md is missing")
    path.write_text(apply_fragments(path.read_text(encoding="utf-8"), fragments), encoding="utf-8")
    for fragment in fragments:
        fragment.path.unlink()
    return True


def list_fragments(root: Path) -> list[Fragment]:
    """Read every pending product fragment, newest slug last."""
    directory = root / FRAGMENT_DIR
    if not directory.is_dir():
        return []
    fragments: list[Fragment] = []
    for path in sorted(directory.iterdir()):
        if path.name == ".gitkeep" or path.name.startswith("."):
            continue
        if path.is_dir() or path.is_symlink():
            raise ChangelogError(f"{FRAGMENT_DIR / path.name} must be a regular fragment file")
        fragments.append(parse_fragment(path))
    return fragments


def parse_fragment(path: Path) -> Fragment:
    """Accept one Keep a Changelog product bullet and reject infrastructure notes."""
    match = FILENAME.fullmatch(path.name)
    if match is None:
        raise ChangelogError(
            f"{display(path)} must be <slug>.<added|fixed|changed|removed|deprecated|security>.md"
        )
    slug, kind = match.groups()
    text = path.read_text(encoding="utf-8").strip()
    if not text:
        raise ChangelogError(f"{display(path)} is empty")
    if text.lstrip().startswith("#") or not text.startswith("- "):
        raise ChangelogError(f"{display(path)} must be a Keep a Changelog bullet, not a heading")
    banned = INFRASTRUCTURE.search(text)
    if banned is not None:
        raise ChangelogError(
            f"{display(path)} is infrastructure, not Marketplace product news: {banned.group(0)}"
        )
    return Fragment(slug=slug, kind=kind, heading=TYPES[kind], bullet=text, path=path)


def apply_fragments(changelog: str, fragments: list[Fragment]) -> str:
    """Prepend product bullets under Unreleased headings."""
    match = UNRELEASED.search(changelog)
    if match is None:
        raise ChangelogError("docs/CHANGELOG.md has no Unreleased section")
    body = match.group(1)
    grouped: dict[str, list[str]] = {}
    for fragment in fragments:
        grouped.setdefault(fragment.heading, []).append(fragment.bullet)
    for heading, bullets in grouped.items():
        insertion = "\n".join(bullets) + "\n"
        heading_line = f"### {heading}"
        if re.search(rf"(?m)^{re.escape(heading_line)}$", body):
            body = re.sub(
                rf"(?m)^{re.escape(heading_line)}\n+",
                f"{heading_line}\n\n{insertion}\n",
                body,
                count=1,
            )
        else:
            body = body.rstrip() + f"\n\n{heading_line}\n\n{insertion}\n"
    if not body.endswith("\n"):
        body += "\n"
    return changelog[: match.start(1)] + body + changelog[match.end(1) :]


def resolve_changed_paths(
    root: Path,
    *,
    paths: list[str] | None,
    base: str | None,
) -> list[str]:
    """Return the pull-request path list from explicit names, a git base, or Actions."""
    if paths is not None:
        return paths
    if base is not None:
        return ci_scope.git_changed_files(root, rev_parse(root, base), rev_parse(root, "HEAD"))
    event_name = os.environ.get("GITHUB_EVENT_NAME", "")
    event_path = os.environ.get("GITHUB_EVENT_PATH", "")
    if not event_name or not event_path:
        raise ChangelogError("Pass --paths, --base, or run in GitHub Actions")
    try:
        event = json.loads(Path(event_path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ChangelogError("Cannot read the GitHub event payload") from error
    if not isinstance(event, dict):
        raise ChangelogError("Cannot read the GitHub event payload")
    rang = ci_scope.event_range(event_name, event)
    if rang is None:
        raise ChangelogError("Cannot resolve the pull-request file range")
    return ci_scope.git_changed_files(root, *rang)


def rev_parse(root: Path, revision: str) -> str:
    """Resolve a git revision to a commit SHA."""
    result = subprocess.run(
        ["git", "-C", str(root), "rev-parse", revision],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise ChangelogError(result.stderr.strip() or f"Cannot resolve {revision}")
    sha = result.stdout.strip()
    if not ci_scope.SHA.fullmatch(sha):
        raise ChangelogError(f"Cannot resolve {revision}")
    return sha


def display(path: Path) -> str:
    """Prefer the repo-relative fragment path in errors."""
    try:
        return str(path.resolve().relative_to(ROOT.resolve()))
    except ValueError:
        return str(FRAGMENT_DIR / path.name)


def main(arguments: list[str] | None = None) -> int:
    """Check a pull request or fold fragments into Unreleased."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("check", "render"))
    parser.add_argument("--root", type=Path, default=ROOT, help="Repository root")
    parser.add_argument("--base", help="Git base revision for check")
    parser.add_argument("--paths", nargs="*", help="Classify these repo-relative paths")
    options = parser.parse_args(arguments)
    try:
        if options.command == "check":
            check(options.root, paths=options.paths, base=options.base)
            print("Changelog fragments are valid.")
        elif render(options.root):
            print("Applied product changelog fragments.")
        else:
            print("No changelog fragments to apply.")
    except ChangelogError as error:
        print(f"Changelog error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

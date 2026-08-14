# Contributing

## Building

The build compiles against an IDE that bundles every integration the plugin
supports. Android Studio ships Gradle but not Maven, so the build downloads the
IntelliJ IDEA version pinned in `gradle.properties`.

You can point it at an installed IDE, as long as that IDE bundles both:

```properties
# local.properties
ide.path=/Applications/IntelliJ IDEA.app
```

The same path works as `-Paffected.ide.path=...` or as `AFFECTED_IDE_PATH`.

```sh
./gradlew detekt test runIde buildPlugin verifyPlugin
./gradlew pitest :core:pitest
./gradlew :collector:spotbugsMain :collector:spotbugsMaven buildHealth
scripts/quality.sh analyzers
scripts/quality.sh shell
scripts/quality.sh workflows
python3 scripts/release_currentness.py
python3 scripts/support_matrix.py --check
python3 scripts/ci_contracts.py --check
python3 scripts/mcp_capabilities.py --check
python3 scripts/local_gate.py install
```

After clone, run `python3 scripts/local_gate.py install` so `core.hooksPath` is
`.githooks`. `pre-commit` runs detekt, script tests, CI contracts and the
analyzer policy. `pre-push` adds ShellCheck. This is the cheap half of CI, not
`verifyPlugin` or the full test suite. Do not use `--no-verify`.

`scripts/run_gradle.sh` seeds the wrapper zip, verifies SHA-256, then starts
Gradle. A cache-redirector 5xx is retried with Maven Central first; compilation
and test failures still run once.

Pull-request `CI` is the required fast gate. `scripts` always run. Plugin work
and `buildHealth` run only when `scripts/ci_scope.py` says the diff can affect
them. The required check `verify` always reports. CodeQL `pull-request` and
dependency `review` keep their names. Unknown paths fail closed. Weekly `pitest`
fails on meaningful survivors.

Enqueue ready PRs with `gh pr merge --auto --squash`. Do not merge by hand.
Keep GitHub "Automatically delete head branches" on. After a squash lands,
delete leftover heads and worktrees.

`config/support-matrix.json` is the source of truth for products, build systems,
runners and OS evidence. `docs/SUPPORT.md` and the README / Marketplace summaries
are generated from it: `python3 scripts/support_matrix.py --write`.

`config/release-currentness.json` governs direct pins. Compatibility entries
need an exact value, a reason and repository-owned evidence. Update the direct
manifest and regenerate its lock; do not inventory transitive versions.

Every IDE the build or verifier unpacks lives under `~/.gradle/caches`. Old
transforms are never removed; deleting that cache is safe and it rebuilds.

`detekt`, ShellCheck, actionlint, SpotBugs and Gradle dependency analysis have
zero findings and no baseline. Fix the reported code or declaration rather than
weakening the gate.

## How it works

`ChangeAnalyzer` asks git what changed: the diff against the merge base with the
base branch, the working tree, and untracked files. The base branch is the
configured one, otherwise `develop`, `main` or `master`, each tried as a remote
branch first.

Whether a change touched public API is a text heuristic, not a compiler. It
errs toward running too much.

`ModuleGraph` reads Gradle and Maven from imported IDE models and CLI
integrations from their manifests or metadata commands. Modules are attributed
to the nearest content root. A changed file outside a known module but below a
build root belongs to every module in the deepest matching build. Gradle
execution coordinates come from the imported model, so included builds keep
their ownership while compatible tasks can run through the composite root.

`TaskPlanner` makes one group per build system and execution root. Gradle and
Maven use one native IDE invocation. CLI adapters put their command sequence
behind one process handler, so one root is one Run tab. Commands stop on
failure unless the native contract disables fail-fast. Independent roots stay
separate. A Gradle module without test sources is compiled, not dropped, so
Kotlin Multiplatform libraries do not become an empty plan.

A `BuildSystem` registers through `com.aspix2k.affected.buildSystem`. Missing
tools, malformed metadata, stale task identities, symlinks and discovery bounds
fail closed to a visible root command or an explicit unresolved Run.

Native adapter projects live under `conformance/cli-fixtures` and run with
`./gradlew :core:test --tests '*CliAdapterConformanceTest' -Paffected.cliConformance=true`.
Parser-only proof is not enough for a release. Exact selection rules belong in
the adapter tests and `docs/SUPPORT.md`, not here.

`ChangeAnalyzer` and `TaskPlanner` have no IDE dependencies. Keep them that
way: return data and let the action format it. The `collector` module produces
Java 8 agents under `agent/` in the plugin zip, outside the IntelliJ classpath.
Only a successful complete run replaces the local dependency map.

## Conventions

- No dependency injection framework. `@Service(Level.APP/PROJECT)` is enough.
- No comments in production code. Names and structure carry the meaning.
- No hardcoded project names, paths, branches or module lists.
- Long work goes off the EDT; actions use `ActionUpdateThread.BGT`.
- Recomputation is event-driven. No timers.

## Releasing

A release is not only a tag. A user who never opens the repository sees only
the last two items:

1. `version` in `build.gradle.kts`.
2. A section for that version in `docs/CHANGELOG.md`. CI fails without it; the same
   text is the GitHub release notes and Marketplace What's New. Pull requests do
   not edit `docs/CHANGELOG.md` except the release pull request that also sets
   `version`. A product change adds one
   `docs/changelog.d/<slug>.<added|fixed|changed|removed|deprecated|security>.md`
   file with a single Marketplace-facing bullet. Infrastructure changes add no
   fragment and never land in a version section. `python3 scripts/changelog_fragments.py render`
   folds pending fragments into Unreleased before `./gradlew patchChangelog`.
   After the cut, leave Unreleased empty of plumbing so the next small release
   cannot pick it up.
3. `README.md` when the change affects what the plugin does or needs.
4. The `<description>` in `plugin.xml` when supported systems change.
5. Getting Started on the Marketplace page — edited in the web form, goes stale
   silently.
6. `config/support-matrix.json` and generated `docs/SUPPORT.md` when a system,
   product, selection unit or minimum IDE version changes.

The pull-request CI job records the verified Git tree and plugin SHA-256 beside
the zip. After merge, the Release workflow finds that successful CI run, requires
the verified tree to match `main`, creates `v<version>`, and promotes the same
zip. It does not rebuild. A missing artifact, a non-green run, or a tree/hash
mismatch stops the release.

```sh
./gradlew patchChangelog
gh workflow run release.yml -f run_id=123456789 -f source_ref=main
```

Add `-f retry_marketplace=true` only to retry Marketplace after the GitHub
release already exists.

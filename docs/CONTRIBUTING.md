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

Pull-request `CI` is the fast gate. `scripts` always runs. `plugin` (detekt,
tests, Kover, SpotBugs), `package` (plugin archive, Plugin Verifier, one product
verifier per IDE) and `buildHealth` run when `scripts/ci_scope.py` says the diff
can affect them. `Exact-impact conformance` separates JVM collector proofs
(`exact`) from native adapter lanes (`native`), so a collector-only change skips
the CLI toolchains. The required checks `verify` and `exact-impact` always
report; unknown paths fail closed. Weekly jobs run `pitest` and the release
currentness check.

Enqueue ready PRs with `gh pr merge --auto --squash` using a user token.
Do not merge by hand and do not enable auto-merge from Actions
`GITHUB_TOKEN`: those merges suppress push workflows, including Release.
Keep GitHub "Automatically delete head branches" on. After a squash lands,
delete leftover heads and worktrees.

`config/support-matrix.json` is the source of truth for products, build systems,
runners and OS evidence. `docs/SUPPORT.md` and the README / Marketplace summaries
are generated from it: `python3 scripts/support_matrix.py --write`.

`config/release-currentness.json` governs direct pins. A `latest` pin must match
the newest stable release. A `compatibility` pin names an exact value, a reason
and repository-owned evidence; use it when a runner contract is proven for one
version only. Pull requests check pins offline; the live check runs weekly, on
pull requests that change `version`, and before a release. Update the direct manifest and regenerate its lock; do not inventory
transitive versions.

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
build root belongs to every module in the deepest matching build. Gradle build
logic (`gradle/**`, `buildSrc/**`, root settings, build scripts and
`gradle.properties`) widens to every project in that build. Gradle
execution coordinates come from the imported model, so included builds keep
their ownership while compatible tasks can run through the composite root.
A Gradle build-logic change (`gradle/**`, `buildSrc/**`, root settings, build
scripts or `gradle.properties`) runs every project of that build. A change in a
separate Gradle root also runs every project of each build that consumes it,
transitively: a `buildSrc` with its own settings belongs to its parent build, and
a root named by a literal `includeBuild("path")` in a consumer's
`settings.gradle(.kts)` belongs to that consumer when it produces Gradle
plugins: a build script mentions `kotlin-dsl`, `java-gradle-plugin` or a
`gradlePlugin {` block, or `src/main/kotlin|groovy` holds `*.gradle(.kts)`
scripts. Plain library builds keep their IDE dependency edges instead. The scan
is textual; plugin applied through a version-catalog alias is not seen when none
of those markers appears. An
`includeBuild` with a non-literal argument, an `apply from` in settings, or an
unreadable settings file or build script makes that build a consumer of every other Gradle root,
so the plan widens instead of narrowing. Inclusion declared anywhere else, such
as a script plugin or init script, is not seen.

`TaskPlanner` makes one group per build system and execution root. One claimed
plan publishes one `Affected` Run session, with a structured child section for
each group. Gradle and Maven keep their native IDE views; CLI adapters keep each
root's command sequence behind one process handler. Direct build-system actions
remain independent. With Stop after first failure disabled, CLI sequences
continue, Gradle adds `--continue`, and Maven adds `--fail-at-end`. Stop mode
uses a bundled Gradle init script to enforce `continueOnFailure=false` after
IDE-provided command-line arguments and adds Maven `--fail-fast`. A project-level
`.mvn/maven.config` `--fail-fast` remains native Maven policy and can override
the full-plan behavior. A Gradle module without test sources is compiled, not
dropped, so Kotlin Multiplatform libraries do not become an empty plan.

Cargo keeps the validated nextest profile, required version and executable
identity, while the Affected failure strategy overrides profile `fail-fast`
in the generated run snapshot. The same setting controls Cargo's native
`--no-fail-fast` flag for ordinary tests, fail-closed fallbacks and doctests.

A `BuildSystem` registers through `com.aspix2k.affected.buildSystem`. Missing
tools, malformed metadata, stale task identities, symlinks and discovery bounds
fail closed to a visible root command or an explicit unresolved Run.
Each CLI execution root keeps its planned filesystem identity and is checked
again before deferred resolution and immediately before every child process.
Missing, re-created, unreadable, linked or out-of-project roots fail visibly.

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

Product pull requests add one
`docs/changelog.d/<slug>.<added|changed|deprecated|removed|fixed|security>.md`
with a single Marketplace-facing bullet; infrastructure changes add none.

A release pull request only sets `version` in `build.gradle.kts` and folds the
pending fragments into `docs/CHANGELOG.md`:

```sh
python3 scripts/changelog_fragments.py render
./gradlew patchChangelog
```

No other pull request edits `docs/CHANGELOG.md`. CI fails when the version has
no section; that section becomes the GitHub release notes and Marketplace
What's New.

Merging the release pull request starts `release.yml`. It finds the pull
request's successful CI run, requires its verified tree to match `main`, tags
`v<version>` and promotes the same zip to GitHub and Marketplace without
rebuilding. Rebase the release pull request on `main` before merging, or the
trees differ and the release stops. A later merge whose CI built no plugin
passes only when its version is already released. To retry by hand, pass the
release pull request's CI run and merge commit:

```sh
gh workflow run release.yml -f run_id=<ci run id> -f source_ref=<merge commit sha>
```

Add `-f retry_marketplace=true` only when the GitHub release already exists.

When they change, also update `README.md`, the `<description>` in `plugin.xml`,
`config/support-matrix.json` with the generated `docs/SUPPORT.md`, and the
Marketplace Getting Started text, which lives only in the web form.

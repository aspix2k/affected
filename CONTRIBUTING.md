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

The same path works as `-Paffected.ide.path=...` or as the `AFFECTED_IDE_PATH`
environment variable.

```sh
./gradlew detekt        # static analysis and formatting, autocorrecting
./gradlew test          # unit tests
./gradlew runIde        # sandbox IDE with the plugin installed
./gradlew buildPlugin   # zip in build/distributions
./gradlew verifyPlugin  # JetBrains plugin verifier
./gradlew pitest        # mutation testing, slow
./gradlew :collector:spotbugsMain :collector:spotbugsMaven
./gradlew buildHealth
scripts/quality.sh analyzers
scripts/quality.sh shell
scripts/quality.sh workflows
python3 -m unittest scripts.tests.test_release_currentness
python3 scripts/release_currentness.py
python3 -m unittest scripts.tests.test_support_matrix
python3 scripts/support_matrix.py --check
python3 -m unittest scripts.tests.test_ci_contracts scripts.tests.test_ci_scope scripts.tests.test_fetch_gradle scripts.tests.test_pitest_gate scripts.tests.test_run_gradle
python3 scripts/ci_contracts.py --check
```

`scripts/run_gradle.sh` seeds `~/.gradle/wrapper/dists` from the official
GitHub `gradle-distributions` release, verifies the wrapper SHA-256, then
starts Gradle once. `services.gradle.org` is only a fallback.
Gradle resolves Maven Central, Plugin Portal and IntelliJ dependencies through
their official direct HTTPS repositories; the IntelliJ Platform plugin cache
redirector is disabled in `gradle.properties`.

Pull-request `CI` is the required fast gate. `scripts` always run. The
plugin graph (`detekt`, tests, Kover verify, plugin zip, Plugin Verifier
and SpotBugs) and `buildHealth` run only when `scripts/ci_scope.py` says
the diff can affect them. The required GitHub check named `verify` always
reports: a scoped skip is success, a failed or missing required job is
not. CodeQL `pull-request` and dependency `review` keep their check names
and skip only the expensive analyze or compare. Exact-impact conformance
still uses workflow `paths`, not a skipped required check. Unknown paths
fail closed and run every expensive gate. `pitest` runs weekly and fails
on surviving mutants. A push to `main` only promotes the already verified
pull-request artifact.

`main` squash-merges after required checks, not by an agent clicking Merge.
Same-repository ready pull requests are enqueued with
`gh pr merge --auto --squash`. GitHub merges when `verify`, CodeQL
`pull-request` and dependency `review` pass. Agents must not merge by hand.
Required checks also listen for `merge_group` so a GitHub merge queue can be
enabled if the repository is ever owned by an organization.

PIT uses the same IntelliJ Platform runtime as the root test task. The complete
2026-08-12 run took 2 minutes 3 seconds: 16 mutants were killed, 167 mutants in
IDE glue had no coverage, and one Kotlin-generated non-null guard was
equivalent. No meaningful mutant survived. Uncovered code remains visible in
the report and is tracked in #94 rather than hidden with exclusions.

The default wrapper, GitHub Actions and native conformance fixtures track the
latest stable releases. Older versions belong only in an explicit compatibility
case such as Gradle 8, Maven 3.9.0 or JDK 17; a regular build pin must be updated
or carry a tested compatibility reason in its issue and changelog entry.

`config/release-currentness.json` governs direct build, analyzer, fixture,
toolchain and GitHub Action pins. The live command reads only bounded official
release endpoints and fails on stale, missing, malformed or unverifiable data.
Compatibility entries require an exact approved value, a reason and
repository-owned test evidence. A temporary security preview must also be
published by its official source, declare its patched lower bound and expire as
soon as the corresponding stable release appears.
Until CodeQL supports that preview, its ephemeral manual build uses the newest
supported stable compiler with `--no-build-cache`; every product and conformance
build keeps the governed patched compiler.
Transitive lockfile entries are resolver output: update the direct manifest pin
and regenerate its lock rather than inventorying transitive versions.
The complete live gate measured 35.98 seconds and 34.3 MB maximum RSS on
macOS arm64 on 2026-08-12.

Cargo conformance installs the checksummed official cargo-nextest archive pinned
in `.github/workflows/conformance.yml`. Selective nextest execution requires a
repository-owned `.config/nextest.toml`, cargo-nextest 0.9.143 or newer on the
stable 0.9.x line and either the built-in/default profile or a declared profile
selected by `NEXTEST_PROFILE`. Only its bounded `fail-fast` value is
copied into the local execution snapshot; filters, retries, scripts, custom
Cargo runners and other ambiguous configuration keep the existing conservative
`cargo test` plan.

`config/support-matrix.json` is the source of truth for supported JetBrains
products, build systems, test runners, selection units and operating-system
evidence. Every registered build-system extension must have an entry backed by
an existing public fixture and CI gate. `SUPPORT.md` and the concise README and
Marketplace summaries are generated from it. After an intentional change, run
`python3 scripts/support_matrix.py --write`; CI rejects stale documentation,
undocumented adapters and unsupported claims.

Every IDE the build or the verifier touches is unpacked into
`~/.gradle/caches/<gradle>/transforms`, three to five gigabytes each, and old
ones are never removed. After changing the pinned IDE version a few times that
directory reaches tens of gigabytes; deleting it is safe and it is rebuilt on
the next build.

`detekt` runs with `autoCorrect`, so formatting fixes itself and only real
findings remain. There is no baseline file: the count is zero and stays zero.

ShellCheck 0.11.0 and actionlint 1.7.12 must also report zero findings. For the
release workflow only, the gate first proves the exact `queue: max` structure
and then suppresses the diagnostic that actionlint does not yet recognize. No
other workflow finding is accepted. CI verifies both analyzer archives before
execution and records their elapsed time and peak memory in the job log.

The zero-finding macOS arm64 baseline measured ShellCheck at 0.11 seconds and
35.4 MB maximum RSS, and actionlint at 0.07 seconds and 31.3 MB maximum RSS.

SpotBugs 4.10.3 analyzes the Java 8 collector and Maven extension bytecode at
maximum effort and default confidence. Low-confidence style advice is excluded
because it overlaps Detekt and can contradict required JVM instrumentation
contracts. Gradle dependency analysis 3.18.0 covers every project and rejects
unused, transitively used or incorrectly scoped dependencies. Both
gates have zero findings, no baseline or suppression file, and every finding
fails CI. Fix the reported code or dependency declaration rather than weakening
the gate.

CodeQL runs the `java-kotlin` security-extended suite against a clean manual
build of every production source set. Pull requests receive only a read token;
the trusted `main` run additionally publishes the same zero-finding analysis to
GitHub code scanning. Gradle dependency submission uses GitHub's two-workflow
model: untrusted code can only generate an artifact, while a trusted workflow
submits it without checking out or executing pull-request code. Dependency
review rejects every newly introduced vulnerability from low severity upward
across runtime, development and unknown scopes, and independently rejects an
incomplete base or pull-request snapshot. Dependabot remains responsible for
update discovery.

The first zero-finding CodeQL pull-request job took 5 minutes 30 seconds. Its
clean one-worker build took 3 minutes 36 seconds and measured 189,664 KB maximum
RSS; CodeQL analysis itself is bounded to 4 GB and two threads. The first Gradle
dependency snapshot took 3 minutes 41 seconds and resolved 531 components.

The zero-finding macOS arm64 `--rerun-tasks` baseline measured both SpotBugs
tasks at 17.78 seconds and 501.8 MB maximum RSS. The four-project dependency
analysis took 103.91 seconds and 112.4 MB maximum RSS. Their CI jobs disable
parallel execution and use one Gradle worker.

## How it works

`ChangeAnalyzer` asks git what changed: the diff against the merge base with the
base branch, the working tree, and untracked files. The base branch is the
configured one, otherwise `develop`, `main` or `master`, each tried as a remote
branch first.

Whether a change touched public API is decided by matching added and removed
lines against a declaration pattern. It is a text heuristic, not a compiler, and
it deliberately errs toward running too much.

`ModuleGraph` reads Gradle and Maven from their imported IDE project models and
the CLI integrations from their standard manifests or metadata commands.
Modules are attributed to the nearest content root. A changed file outside a
known module but below a build root conservatively belongs to every module in
the deepest matching build. Gradle execution coordinates come separately from
the imported model, so included builds keep their ownership while compatible
tasks can run through the composite root.

`TaskPlanner` turns that into task groups, one per build system and execution
root. Gradle and Maven use one native IDE invocation; CLI adapters place their
bounded native command sequence behind one process handler, so one root creates
one Run tab even when the native tool requires multiple commands. Commands stop
on failure unless their native contract explicitly disables fail-fast; Cargo
nextest profiles with `fail-fast=false` still run the doctest step and preserve
the aggregate failure.
Independent roots and different build systems stay separate. A module already
being verified is never also compiled as a consumer.

A `BuildSystem` supplies source matching, module identity, task names and
execution, and registers itself through the
`com.aspix2k.affected.buildSystem` extension point behind an optional dependency
on its IDE integration. CLI graph caches hash every discovered manifest and
lock input. Missing tools, malformed or partial metadata, stale task identities,
symlinks and discovery bounds fail closed to a visible root command.

Minimal native projects for every CLI adapter live under
`conformance/cli-fixtures`. Their gated test is
`./gradlew :core:test --tests '*CliAdapterConformanceTest' -Paffected.cliConformance=true`;
it requires the corresponding native tools and downloads only the pinned test
runner dependencies declared by those fixtures.

Supported Jest and Vitest packages receive runner-native related-file commands
only when a real merge base, an explicit runner version and a bounded static
package graph are available. Custom runner configuration or transforms, dynamic
dependencies, dependency overrides, resources, lockfiles, added, deleted or
generated files, symlinks and scan-limit failures keep the original package test
script. Every exact and full package command remains in the same
`SequentialProcessHandler` for its Node root.

Supported pytest 8–9 projects run through the packaged Python adapter. Pytest
first completes normal collection in the original process; the adapter then
builds a bounded current AST import graph and deselects unrelated test files.
Parametrized nodes stay grouped by file. Third-party plugins, conftest, pytest
configuration, dynamic or external imports, file loading, resources, non-modified
changes, symlinks and scan-limit failures leave the collected package plan intact.
No persistent Python dependency baseline is written.

Supported CMake 4.1+ and CTest 3.29+ projects read the current File API
codemodel and CTest JSON plan, build once and pass proven test names through
`--tests-from-file` in the same Run session. Only a complete successful full
plan with unchanged metadata replaces the baseline. Headers, configuration,
generated registrations, fixtures, resources and unsupported metadata keep the
full CTest plan.

Supported .NET 8–10 SDK projects using VSTest-compatible xUnit, NUnit or MSTest
adapters build before selection. A packaged, locally compiled metadata analyzer
reads the unmodified test and dependency assemblies, while a complete TRX run
provides stable fully qualified identities. Later runs compare compiled DLLs
and pass exact identities through `dotnet test --no-build --filter` in the same
Run session. Parameterized or shared fixtures, custom settings, Microsoft
Testing Platform, test-assembly changes and incomplete metadata keep the full
project plan. Only a complete unchanged full run replaces the counted and
checksummed baseline.

`ChangeAnalyzer` and `TaskPlanner` have no IDE dependencies and are covered by
unit tests. Keep them that way: return data and let the action format it. The
`collector` module produces Java 8 agents, a JUnit listener, a Gradle init script
and a Maven core extension under `agent/` in the plugin distribution, outside
the IntelliJ plugin classpath. A full compatible Gradle or Maven run records
every executed Jupiter or Vintage test class and the production bytecode it
loads. Only a successful run with matching complete worker output atomically
replaces the local dependency map; all other cases keep the full-task fallback.
The counted and checksummed map includes the full production class catalog.

Gradle computes selection in a serializable task-local spec after compile
dependencies and applies the public `TestFilter`. Maven 3.9.x uses a core
extension to pass separate counted project manifests to supported Surefire and
Failsafe 3.x forks. Reusable forks use the bounded Surefire fork slot; isolated
forks use the fork slot plus their single discovered test class. Each worker
records its expected test set, and promotion requires the exact complete union
with unique ownership.
A JUnit Platform discovery filter computes selection after `test-compile`;
Surefire and Failsafe keep distinct task keys, output and maps. Neither path
starts a second visible build. Runtime, test, classpath or resource changes and
any missing, corrupt, added, deleted or unsupported input run the original full
task. Selected and skipped runs never replace the full baseline. A direct
Failsafe `integration-test` can select from a baseline but only `verify`,
`install` or `deploy` may replace one, because Failsafe defers test-failure
reporting to `verify`.

## Conventions

- No dependency injection framework. `@Service(Level.APP/PROJECT)` is enough.
- No comments in production code. Names and structure carry the meaning.
- No hardcoded project names, paths, branches or module lists.
- Long work goes off the EDT; actions use `ActionUpdateThread.BGT`.
- Recomputation is event-driven. No timers.

## Releasing

A release is not only a tag. Everything below describes the same change to a
different audience, and a user who never opens the repository sees only the last
two:

1. `version` in `build.gradle.kts`.
2. A section for that version in `CHANGELOG.md` — CI fails without it, and the
   text becomes both the GitHub release notes and What's New on the marketplace.
3. `README.md` when the change affects what the plugin does or needs.
4. The `<description>` in `plugin.xml` when the supported systems change — it is
   the marketplace page and updates itself on publish.
5. **Getting Started on the marketplace page** — the one thing no automation
   touches. It is edited through the web form and goes stale silently.
6. `config/support-matrix.json` and its generated `SUPPORT.md` when a system,
   product, selection unit or minimum IDE version changes.

Every version needs its own section in `CHANGELOG.md`. CI fails when the version
in `build.gradle.kts` has no entries there, and the release fails when the tagged
version has none — the same section becomes the GitHub release notes and the
plugin's What's New on the marketplace.

The pull-request CI job records the verified Git tree and plugin SHA-256 beside
the plugin zip. After merge, the Release workflow finds that successful CI run,
requires the verified tree to match `main`, creates the annotated `v<version>`
tag, and promotes the exact same zip to a GitHub release and JetBrains
Marketplace. It does not rebuild or rerun tests. A missing artifact, a non-green
CI run, or any tree/hash mismatch stops the release.

```sh
./gradlew patchChangelog
gh workflow run release.yml -f run_id=123456789 -f source_ref=main
```

Add `-f retry_marketplace=true` only to retry Marketplace submission after the
GitHub release already exists.

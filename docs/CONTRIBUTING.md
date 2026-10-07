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
./gradlew pitest :core:pitest :engine:pitest
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

The `engine` module is plain Kotlin/JVM with no IntelliJ Platform dependency
and holds the code that never touches the IDE; `core` depends on it, and its
compile tasks pass `-Xfriend-paths` so `internal` declarations stay visible
across the boundary. Gson, kotlinx-coroutines and JNA are `compileOnly` there,
so the plugin keeps the copies the platform bundles.

The process layer lives in `engine` as well. `CommandSequence` runs a list of
`CliStep`s on JDK processes and reports text, command start and finish and the
final exit code to a `CommandSequenceListener`. `ContainedProcess` owns each
command's process tree through `ProcessSupervisorMain` in a separate JVM and
takes its classpath and a directory with the JNA native library from a
`SupervisorRuntime`. A host supplies both through `ProcessHost`; `core` passes
the IDE's through `ideProcessHost`, and `SequentialProcessHandler` only
forwards the sequence events to the platform `ProcessHandler`.

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

The real repository smoke lane clones the pinned projects of
`conformance/real-repositories.json`, runs each adapter headlessly on a
one-line change and runs the planned command: `RealRepositorySmokeTest` with
`-Paffected.realRepositories=true`, scheduled in `conformance.yml`. A missing
tool fails the lane; add `-Paffected.realRepositories.skipMissingTools=true` to
skip those probes locally. A scenario with an open adapter defect carries
`knownDefect` (`id` and a `failure` regex for the assertion message): the lane
then requires that exact failure and turns red the day the defect is fixed, so
remove the field in the fixing change.

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

The live check reports every stale or unverifiable pin in one run, one
`release-currentness: ERROR:` line each, and exits non-zero. Refresh them with
`python3 scripts/release_currentness.py --write`: it rewrites the pins its
`local` descriptor fully describes (Gradle, Maven, properties, workflow tools,
action SHAs with their version comment, release assets with their digest,
matrix values, fixture manifests) and regenerates their lock files by running
the real tool (`npm`, `composer`, `bundle`, `dotnet`) from `PATH`; `--docker`
lets Composer fall back to the `composer:2` image. A pin whose tool is missing
stays untouched and is listed with the exact command. `latest` pins move to the
newest stable release, `series` pins stay inside their series, `branch` pins take
the branch head, and `compatibility` pins never move. A major version change, a
moved tag and the Gradle wrapper are listed for a decision or a manual step
instead. Review the summary, in particular the flags for runners named in
`config/support-matrix.json`, and the diff before committing; `--json PATH` and
`--report PATH` also save the summary. The weekly workflow puts the same list in
its failure issue. It does not open pull requests, because pull requests created
with `GITHUB_TOKEN` do not start CI.

Every IDE the build or verifier unpacks lives under `~/.gradle/caches`. Old
transforms are never removed; deleting that cache is safe and it rebuilds.

`detekt`, ShellCheck, actionlint, SpotBugs and Gradle dependency analysis have
zero findings and no baseline. Fix the reported code or declaration rather than
weakening the gate.

## Checking the plugin in a running IDE

Unit and conformance tests never load the plugin into an IDE, and the product
verifier only checks binary compatibility. After a change to change collection,
module discovery or task selection, and before a release, check the plugin live:

```sh
python3 scripts/live_ide.py idea-gradle   # IntelliJ IDEA, Gradle project
python3 scripts/live_ide.py idea-maven    # IntelliJ IDEA, Maven project
python3 scripts/live_ide.py pycharm-python # PyCharm, Python project with pytest
python3 scripts/live_ide.py all
```

Each scenario copies its fixture (`conformance/live-ide/gradle`, `conformance/live-ide/maven`,
`conformance/cli-fixtures/python`) into a temporary directory, commits it on `main`, branches
`feature` and leaves one uncommitted edit. It then resets the sandbox config under
`.intellijPlatform/sandbox/affected` (MCP server enabled, project trusted), starts the IDE with
`./gradlew runIde` or `runIdeProduct` and the init script `scripts/live-ide.init.gradle`, and waits
for the MCP server and for the project model (`get_project_modules` lists the modules, and
`affected_verification_plan` answers). It expects the changed file, the planned tasks and a passing
run; for Gradle and Maven it also enables `testDependents` through `affected_configure` and expects the
dependent module's tests too. The IDE is always stopped and the temporary state removed;
`--report-dir <dir>` keeps the report, and after a failure the IDE log and thread dumps.
A first run downloads the IDE (about 1.5 GB) and PyCharm needs the virtual environment's `pytest` from PyPI.

The first-run dialogs are switched off with JVM properties, not clicks: `-Djb.consents.confirmation.enabled=false`
(data sharing consent), `-Djb.privacy.policy.text=<!--999.999-->` (user agreement on a machine that never ran a
JetBrains IDE) and `-Dide.experimental.ui.onboarding=false` (New UI onboarding dialog); each blocks the IDE on a
fresh sandbox. After a failure the IDE is asked for a thread dump, which lands in the collected Gradle output. On a Linux machine without a display the Gradle run is wrapped in `xvfb-run` (`apt install xvfb lsof`).
CI runs it weekly and by hand in `.github/workflows/live-ide.yml`, never on a pull request, and opens an issue when
the scheduled run fails. Android Studio is not covered: its sandbox gets an MCP Server plugin built for another platform
build and the server does not start. Products that ask for a license on the first start cannot run unattended.

To check an already running IDE (any product), find the MCP port (`lsof -nP -iTCP -sTCP:LISTEN`, the
one that answers `/sse`) and run

```sh
python3 scripts/ide_smoke.py --port <port> --project <absolute project path> --task <expected task> --run
```

It fails when the changed files or the planned tasks differ from the expectation, or when the run does not pass.

## How it works

`ChangeAnalyzer` asks git what changed: the diff against the merge base with the
base branch, the working tree, and untracked files. The base branch is the
one configured for the project (Affected menu → Base branch, or the
`affected_configure` MCP tool; an empty value or `auto` clears it), otherwise
the remote default branch (`origin/HEAD`), then `develop`, `main` or `master`,
each tried as a remote branch first. The choice lives in the project's
workspace file (`ProjectBaseBranch`), not in VCS. A branch stored by an older
version in the application settings still applies to projects that have not
chosen one, unless it is the old default `develop`, which now means automatic.
Without any base the check does not pass.

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

An adapter owns the knowledge of its ecosystem. Declare `isTestSource` (the path
is relative to the build root), `consumersNeedSignatureChange` and
`singleOwnerPerRoot` on the adapter instead of branching on an adapter id in
`ChangeAnalyzer`, `Verification` or `ModuleGraph`; the Android instrumentation
task choice in `Verification` is the one remaining exception. Find roots with
`nestedBuildRoots`: it returns several roots up to three levels below the
project base and never descends into a root it found. Key caches, baselines and
command lookups by root, because `executionId` repeats across roots. A new
adapter needs its class, one `plugin.xml` line, a `config/support-matrix.json`
row with a fixture and a test, and a regenerated `docs/SUPPORT.md`.

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

Before bumping `version`, run `python3 scripts/release_currentness.py --write`,
review the result and open it as its own pull request; the release workflow
fails on a stale pin.

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

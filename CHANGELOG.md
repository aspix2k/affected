# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Select changed Pest 5.1.1 test files by native path when every planned suite change is a regular test file; production, boot, dataset and unproved changes keep the package suite.
- Run every test in affected Composer packages through Pest 5.1.1, including datasets and PHPUnit-style tests, while disabling focused and test-impact shortcuts.
- Run affected Cargo workspace packages through repository-configured cargo-nextest 0.9.x profiles, retaining `cargo test --doc` and conservative `cargo test` fallback when exact execution cannot be proven.
- Detect Composer projects that declare Pest and run the full package through `vendor/bin/pest` instead of PHPUnit class selection.
- Run standard-library unittest for Python projects that import `unittest` and do not declare pytest, keeping pytest exact selection elsewhere.
- Run Bun workspaces through `bun test` when `bun.lock` / `bun.lockb` or `packageManager: bun` is unambiguous, keeping mixed npm/Yarn/pnpm lockfiles on their existing managers.
- Let the Affected menu choose whether a sequential plan continues after a failure; the default still runs the full plan and reports every collected failure.
- Run affected Ruby gems through RSpec, Minitest and Test::Unit while preserving whole-gem helper, autorun and global-state semantics in one IDE Run session.
- Make the product, ecosystem, runner, selection and operating-system support matrix executable, evidence-backed and self-auditing.
- Document supported security versions and private vulnerability reporting.
- Adopt the Contributor Covenant and a private channel for conduct reports.
- Expose optional JetBrains MCP Server tools from the same analysis snapshot and exclusive run lease as the toolbar.

### Changed

- Pin `github/codeql-action` to 4.37.7.
- Keep the Composer PHPUnit 13.3 pin and exact-selection matrix on 13.3.1.
- Run weekly PIT on `AffectedMcpInputs` as well as `TestRootResolver`, and reject task or branch names only through the documented charset and `..` rules.
- Run detekt, script tests, CI contracts and analyzer policy on `pre-commit`, and add ShellCheck on `pre-push`, so those cheap CI gates fail locally before GitHub.
- Fail unit tests when a plugin descriptor would block a restartless update: `require-restart`, legacy components, nameless action groups, non-dynamic extension points, or a missing optional/content descriptor.
- Run weekly PIT on `TestRootResolver` in `:core` and fail when either the root or core report has a meaningful survivor. Compiler-generated Kotlin `Intrinsics.checkNotNull*` void-call mutants stay classified as equivalent.
- Disable Dependabot version-update pull requests; the fail-closed release
  currentness gate continues to discover stale governed pins from official
  sources.
- Move the detailed support matrix to its own generated page and keep the README and Marketplace description concise.
- Collapse pull-request CI into one required `verify` aggregator: one Gradle graph for analysis, tests, coverage, packaging and SpotBugs, with `buildHealth` in parallel.
- Enqueue ready same-repository pull requests with squash auto-merge so agents do not merge `main` by hand. Required checks also listen for `merge_group`.
- Run Plugin Verifier, `buildHealth`, CodeQL and dependency review only when the pull-request diff can affect them. Documentation-only changes keep the required check names and the cheap scripts job.

### Fixed

- Pass Pest `--configuration` and `--no-output` before suite paths so Pest 5.1.1 cannot append those flags after them, and resolve the generated XML bootstrap from the project root.
- Read Maven Central metadata through the JetBrains cache-redirector first so a Central 429 cannot fail Scripts; invalid metadata and 404 still fail closed.
- Authenticate the live release-currentness GitHub lookups so a public API 403 cannot fail Scripts and analyzers.
- Retry Gradle after a Maven Central 429 and put the JetBrains cache-redirector first again; compilation and test failures still run once.
- Stop launching the exact-impact matrix from README-only edits, and stop rerunning the full core test suite as skipped CLI contracts.
- Retry Gradle itself after a cache-redirector 502/503/504 and fall through to Maven Central; compilation and test failures still run once.
- Submit main dependency snapshots from `workflow_dispatch` so a GITHUB_TOKEN merge can backfill the review baseline.
- Enforce Jackson BOM 2.22.1 on the MCP module so the optional MCP Server plugin cannot reintroduce Jackson 2.19.
- Count `:core` and `:mcp` in the Kover floor so MCP tests cannot hide behind the root plugin sources.
- Keep cargo-nextest discovery deterministic when the environment forces colored Cargo output.
- Give the Gradle wrapper 120s and four retries instead of a single 10s download from services.gradle.org.
- Seed the Gradle wrapper cache from the official GitHub `gradle-distributions` release, verify the SHA-256, and reuse `~/.gradle/wrapper/dists` so CI does not download the zip from services.gradle.org on every job.
- Resolve Maven artifacts through the JetBrains cache-redirector before repo.maven.apache.org so a Central 403 cannot fail CI.
- Give the ten-build Windows exact-impact Gradle fixture a 300s scenario budget and a 90s hang cutoff per invocation.

## [2.0.1] - 2026-08-12

### Fixed

- Precompute the affected Run plan in one debounced background analysis, reuse its module graph and change snapshot, and keep verification disabled while project sync or another external-system task is running.
- Rebuild the prepared plan after external-system tasks and recover composite Gradle coordinates and Android unit-test tasks from an incomplete imported model, keeping compatible modules in one Run session.
- Keep startup silent while the widget moves immediately from preparation to execution; exact-selection and fallback reasons remain in the Run output.

## [2.0.0] - 2026-08-12

### Added

- Block new releases when a governed direct dependency, toolchain, fixture or GitHub Action pin is stale or unverifiable.
- Run related test files instead of every test in an affected npm, Yarn or pnpm package when a default-config Jest 29–30 or Vitest 2–4 dependency graph can prove the selection.
- Run related pytest files after complete collection when a bounded current Python import graph proves the selection.
- Run exact named CTest tests for changed C and C++ targets when CMake 4.1+ and CTest 3.29+ metadata proves the relationship.
- Run exact VSTest identities for supported xUnit, NUnit and MSTest projects after compiled .NET assemblies prove the dependency.
- Run affected PHPUnit classes for Composer projects on PHPUnit 11.5, 12.5 and 13.2–13.3 when a complete runtime include map proves the selection.

### Changed

- Update the privacy policy and contribution templates for every supported ecosystem.
- Keep the full package test script for unknown Node runners and versions, custom configuration or transforms, dependency overrides, dynamic dependencies, resources, lockfiles, added, deleted or generated paths, symlinks, bounded-scan failures and changes without a merge base.
- Preserve one fail-fast Node Run session while exact workspaces execute their own runner-native related command.
- Keep the full pytest package plan for custom configuration, conftest or third-party plugins, dynamic or external dependencies, resources, non-modification changes, ambiguous ownership and bounded-scan failures.
- Test the native Python fixture with Python 3.14, pytest 9.1.1 and zero-finding Ruff 0.16.2 checks.
- Build with Gradle 9.7 and verify native adapters against current stable GitHub Actions, language runtimes and test frameworks.
- Reject shell and workflow analyzer findings with checksummed ShellCheck 0.11.0 and actionlint 1.7.12 gates.
- Reject Java collector and Gradle dependency findings with zero-baseline SpotBugs and dependency-analysis gates.
- Add zero-finding CodeQL analysis and fork-safe Gradle dependency graph submission.
- Reject newly introduced vulnerable dependencies and incomplete dependency graph comparisons in pull requests.
- Run PIT against the IntelliJ test runtime and cover every icon-count range boundary.
- Keep CMake configuration, generated tests, fixtures, resources, added or deleted targets and incomplete metadata on the full CTest plan.
- Keep .NET projects on their full test plan for unsupported SDKs, adapters, Microsoft Testing Platform, custom test settings, parameterized or shared fixtures, resources, generated code, changed test assemblies and incomplete evidence.
- Build and filter every .NET project inside one fail-fast IDE Run session; skipped, selected, failed and cancelled runs cannot replace an unverified baseline.
- Keep PHPUnit packages on their full test plan for custom configuration, dependencies between tests, process isolation, dynamic I/O, resources, generated code, changed runtime settings and incomplete evidence; only an unchanged successful full run can replace the baseline.

### Fixed

- Keep the toolbar in an analyzing state during project model updates, discard stale analysis results and retry after a cancelled IDE read instead of leaving the widget inactive.
- Show the stable action name instead of presenting changed source-module ownership as the final test plan, while retaining every owner in the affected-modules menu.
- Retry the GitHub run lookup when release promotion starts before the merged pull request is visible through the Actions API.
- Upgrade vulnerable transitive Jackson and jsoup build dependencies to patched releases.
- Stop parent Python packages from inheriting tests owned by nested workspace packages.

## [1.14.1] - 2026-08-11

### Changed

- Rewrite the GitHub and Marketplace pages around the plugin's purpose, supported IDEs, languages and build systems.
- Make the README CI badge report pull-request verification instead of a cancelled legacy `main` run.

### Fixed

- Keep concurrent Maven test forks from losing collector manifests to transient Windows file locks.

## [1.14.0] - 2026-08-11

### Added

- Run single-package Node, Python, Composer and Bundler projects, and single-target CMake projects, without requiring a workspace sibling.
- Verify changed Rust and Go packages even when they contain no explicit test files, and build or statically analyse changed .NET, CMake, TypeScript, Python and Composer modules that have no tests.
- Exercise every existing CLI adapter against a committed native Cargo, Go, npm, .NET, pytest, PHPUnit, RSpec or CMake project in conformance CI.

### Changed

- Keep all Cargo, Go, Node, .NET, Python, Composer, Bundler and CMake commands for one build root in one fail-fast IDE Run session. Compatible native commands are batched where their CLI supports it.
- Cache CLI module graphs by bounded content fingerprints of every discovered graph and lock input instead of root-manifest timestamps.
- Run CLI-adapter contracts on Linux, macOS and Windows in the conformance workflow.

### Fixed

- Widen missing, malformed, partial, stale or oversized CLI project models to a visible root-level verification instead of silently producing an empty or partial plan.
- Pass exact .NET project files to `dotnet`, rebuild CMake before CTest, discover the configured CMake build tree instead of assuming `cmake-build-debug`, and never infer CTest ownership from matching target names.
- Normalize Cargo workspace identities and Node workspace glob separators on Windows.
- Classify consumer impact by the file's owning build system, so non-JVM production and configuration changes remain conservative without treating Gradle JSON resources as public API changes.

## [1.13.0] - 2026-08-11

### Added

- Select exact affected Jupiter and Vintage classes in Maven 3.9.x Surefire and Failsafe runs with integer `forkCount` values from 1 through 256, for both reusable and one-class-per-process forks, without adding another Maven invocation or IDE Run tab.

### Changed

- Promote a multi-fork Maven baseline only when the worker-scoped expected test sets exactly match the complete, uniquely owned union. Missing, crashed, unsupported or duplicate workers, forkless and core-scaled fork counts, and additional test executions keep the original full goal.

## [1.12.0] - 2026-08-11

### Added

- Select affected Jupiter and Vintage integration-test classes in Maven 3.9.x reactors that bind Failsafe 3.x. Surefire and Failsafe keep independent maps while `verify` remains one Maven invocation and one IDE Run tab; direct `integration-test` runs cannot promote a baseline before Failsafe reports failures.

## [1.11.1] - 2026-08-11

### Changed

- Verify the exact Gradle and Maven test-selection contract on Linux, macOS and Windows across compatible JDK 17–26 and build-tool version pairings.

### Fixed

- Render Gradle and Maven decision lines with an ASCII separator so their output remains intact in Windows build logs.

## [1.11.0] - 2026-08-11

### Added

- Print one concise `exact`, `proven-empty`, or full-fallback decision for every supported Gradle test task and Maven reactor module in the original Run output.
- Explain full fallbacks with stable reasons for missing, stale, corrupt, unavailable, or changed evidence, existing test filters, unsupported frameworks, and collector failures.

## [1.10.0] - 2026-08-11

### Added

- Attribute production bytecode dependencies independently to each Jupiter and Vintage test class in Gradle and Maven workers, including parallel classes, reflection, service loading and transitive static calls.
- Select every mapped Maven test-class candidate without depending on discovery or execution order.

### Changed

- Invalidate older dependency maps with schema 4 and rebuild them on the first full run after updating.
- Keep the full test task when production access cannot be attributed safely, and avoid runtime probes on static and private hot paths.

## [1.9.1] - 2026-08-11

### Fixed

- Keep Gradle source-set modules in one composite IDE invocation instead of falling back to a separate Run process for each included build.

## [1.9.0] - 2026-08-11

### Added

- Run one affected Jupiter or Vintage test class when a complete Maven 3.9.x and Surefire 3.x dependency map proves that class unambiguously. Selection happens after normal test compilation in the original Maven invocation and Run tab; reactor modules keep independent maps and unchanged compatible modules skip test execution.

### Changed

- Preserve the full Maven `test` goal for ambiguous shared-worker dependencies, Maven 4, forkless or multi-fork Surefire, user-selected tests, unsupported providers, and every missing, stale, corrupt or changed runtime input. Selected, empty, failed and cancelled runs cannot replace the last complete baseline.

## [1.8.0] - 2026-08-10

### Added

- Run only the Jupiter and Vintage test classes that observed changed production bytecode in compatible Gradle JVM and Android unit-test tasks. A proven-unchanged task skips its test worker; missing, stale, unsupported or ambiguous inputs, resources and class-set changes keep the original full-task fallback in the same IDE Run tab.
- Collect and atomically persist complete local test-class dependency maps and production class catalogs after successful full runs. Selected, skipped, incomplete, failed or cancelled runs keep the previous complete baseline.

## [1.7.2] - 2026-08-10

### Added

- Add the pure dependency-map and fail-closed selection model required for exact JVM test-class impact analysis. It is not connected to task execution yet.

### Fixed

- Use stable Gradle module APIs for composite-build execution coordinates and fail verification on future experimental API usage.
- Include the MIT license in every plugin distribution and verify that the packaged copy matches the repository license.
- Keep deleted source paths in affected-module and API analysis, and treat renames as a deletion plus an addition so both module owners are verified.

## [1.7.1] - 2026-08-09

### Fixed

- Run compatible Gradle tasks from included builds in one composite invocation and one Run tab for verification, toolbar checks, and MCP tasks. Independent roots and different build systems remain separate.

## [1.7.0] - 2026-08-08

### Changed

- Replace the numeric badge with a three-by-three matrix that stays legible at toolbar size.
- Animate the matrix during initialization and verification; restore the affected scope afterward.
- Run independent build roots concurrently without blocking IDE threads.
- Group consumer checks, commit and push guards, and animation in Settings. The checks and guards are off by default; animation is on.

### Fixed

- Bound build-system and manifest discovery so large mixed monorepos stay responsive.
- Detect root `.csproj`, `.fsproj`, and `.vbproj` files without a solution file.
- Route Gradle tasks to the owning linked build, including flat modules, renamed modules, composite builds, and source sets.
- Detect Android modules from imported tasks when no manifest is checked in.
- Capture process output with enforced timeouts and no pipe deadlocks.
- Serialize debounced refreshes, skip API diffs during scope-only recounts, and keep build-system caches project-specific.
- Remove Gradle-specific wording from shared UI and MCP messages.

## [1.6.0] - 2026-08-08

### Changed

- The minimum IDE is 2025.3. The MCP Server plugin does not exist for the 2025.1 platform and lacks the API this plugin uses on 2025.2, which made the verifier report the whole plugin as binary incompatible on both — a red mark on the plugin page for a toolset that could never have run there anyway.

### Fixed

- Java signatures were never recognised as API: the pattern looked for Kotlin keywords, while Java declares a member by its type. Changing a Java signature left every consumer unchecked.
- A parameter on its own line in a multi-line signature was ignored, so wrapped declarations — the common Kotlin style — went unnoticed.
- Test sources at the repository root were not excluded, because the marker was matched with a leading slash.
- A member written on one line together with its body counted as an API change whenever the body was edited. Declarations either side of the change are compared now, so only the signature matters.

### Added

- CMake projects: targets from `add_executable` and `add_library`, the graph from `target_link_libraries`, tests through `ctest`.
- composer monorepos: `phpunit` for changed packages, and `phpstan` or `psalm` for their consumers when the package configures one.
- Ruby monorepos: `rspec` for changed gems. Consumers are not checked — Ruby has nothing to compile and no type checker to stand in for it.
- An exception in the plugin offers to open a prefilled issue on GitHub. Nothing leaves the machine until you submit it, and the report contains only what it shows you.
- A privacy policy, spelling out what is read, stored, executed and reported.

## [1.5.0] - 2026-08-08

### Added

- npm, yarn and pnpm workspaces: tests of changed packages, and a type check of the packages depending on them.
- .NET projects: `dotnet test` for changed projects and `dotnet build` for the projects referencing them, with the graph read from `ProjectReference`.
- Python monorepos: `pytest` for changed packages, and `mypy` for their consumers when the project configures it.

### Changed

- A consumer is only checked when its language has something to check. Plain JavaScript has nothing to compile, so those consumers are skipped instead of being run pointlessly.

## [1.4.0] - 2026-08-08

### Added

- A checkbox in the commit dialog that runs the affected tests and cancels the commit when they fail.
- A check before push that aborts the push on a failed run.

Both are off by default, and each is remembered separately.

- Czech and Indonesian interface, bringing the count to fourteen languages.

## [1.3.0] - 2026-08-08

### Added

- Go modules: `go test` for the packages you changed and `go build` for the packages importing them.

### Changed

- Local edits come from the IDE rather than from running git, so a project under Mercurial, SVN or Perforce is analysed too, and a recount costs no processes. Comparing against the base branch still needs git; without it, every changed file is treated as able to affect consumers.
- The MCP toolset moved into a separate, optionally loaded plugin module. Its classes now live in their own jar and cannot be loaded — or fail to load — in an IDE without the MCP Server plugin.
- The project is split into a core module holding the analysis and the build systems, the plugin itself, and the MCP module.

## [1.2.0] - 2026-08-08

### Added

- Cargo workspaces: `cargo test -p` for changed packages and `cargo check --tests -p` for the packages depending on them.

## [1.1.0] - 2026-08-08

### Added

- Maven projects: tests of changed modules and `test-compile` of the modules consuming a changed API.

### Changed

- The build system is a plug point rather than a hard dependency, so the plugin installs in IDEs without Gradle and can grow to other build systems.

## [1.0.1] - 2026-08-08

### Fixed

- The MCP toolset no longer breaks binary compatibility with IDEs whose MCP Server plugin predates `McpToolset.isEnabled`.

## [1.0.0] - 2026-08-08

First release.

### Added

- Tests for the modules you changed, and a compilation check for the modules consuming an API you changed.
- A toolbar button carrying the number of affected modules, disabled when there are none.
- A menu listing those modules, with navigation to each, and their detekt, lint and coverage tasks where they exist.
- A toggle to run tests only, without the consumer check.
- Composite build support: every module runs against the build that owns it.
- An MCP toolset giving AI agents the same analysis and execution.
- Twelve interface languages.

[Unreleased]: https://github.com/aspix2k/affected/compare/v2.0.1...HEAD
[2.0.1]: https://github.com/aspix2k/affected/compare/v2.0.0...v2.0.1
[2.0.0]: https://github.com/aspix2k/affected/compare/v1.14.1...v2.0.0
[1.14.1]: https://github.com/aspix2k/affected/compare/v1.14.0...v1.14.1
[1.14.0]: https://github.com/aspix2k/affected/compare/v1.13.0...v1.14.0
[1.13.0]: https://github.com/aspix2k/affected/compare/v1.12.0...v1.13.0
[1.12.0]: https://github.com/aspix2k/affected/compare/v1.11.1...v1.12.0
[1.11.1]: https://github.com/aspix2k/affected/compare/v1.11.0...v1.11.1
[1.11.0]: https://github.com/aspix2k/affected/compare/v1.10.0...v1.11.0
[1.10.0]: https://github.com/aspix2k/affected/compare/v1.9.1...v1.10.0
[1.9.1]: https://github.com/aspix2k/affected/compare/v1.9.0...v1.9.1
[1.9.0]: https://github.com/aspix2k/affected/compare/v1.8.0...v1.9.0
[1.8.0]: https://github.com/aspix2k/affected/compare/v1.7.2...v1.8.0
[1.7.2]: https://github.com/aspix2k/affected/compare/v1.7.1...v1.7.2
[1.7.1]: https://github.com/aspix2k/affected/compare/v1.7.0...v1.7.1
[1.7.0]: https://github.com/aspix2k/affected/compare/v1.6.0...v1.7.0
[1.6.0]: https://github.com/aspix2k/affected/compare/v1.5.0...v1.6.0
[1.5.0]: https://github.com/aspix2k/affected/compare/v1.4.0...v1.5.0
[1.4.0]: https://github.com/aspix2k/affected/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/aspix2k/affected/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/aspix2k/affected/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/aspix2k/affected/compare/v1.0.1...v1.1.0
[1.0.1]: https://github.com/aspix2k/affected/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/aspix2k/affected/releases/tag/v1.0.0

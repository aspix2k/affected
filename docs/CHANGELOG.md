# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [3.15.3] - 2026-08-18

### Fixed

- Contain CLI processes at launch so cancellation terminates reparented children inside the Affected-owned boundary before removing temporary output.

## [3.15.2] - 2026-08-18

### Fixed

- Kept Affected compatible with DataSpell installations that do not bundle the Gradle plugin.

## [3.15.1] - 2026-08-17

### Fixed

- Affected now waits for observed CLI and Maven child processes to stop before removing owned runtime files after cancellation.
- Affected now stops observed CLI children before their parents so cancellation can remove temporary output reliably on Linux.

### Changed

- Update the enforced Jackson platform to 2.22.2 for current MCP Server compatibility.

### Security

- Build Affected with a Kotlin preview that contains JetBrains' KAPT cache-deserialization fix for CVE-2026-53914.

## [3.15.0] - 2026-08-17

### Fixed

- Printed static Gradle fallback reasons in the owned Affected run before composite-build tasks start.
- Made `Stop after the first failure` authoritative for Gradle runs even when the IDE adds its own `--continue` argument.

### Added

- Present every build-system root from one affected plan in a single `Affected` Run session while preserving structured Gradle and Maven views, CLI output, failure propagation and owned cancellation.

## [3.14.0] - 2026-08-16

### Added

- Explained conservative Gradle/KMP selection in change-aware Affected test runs with stable fallback reason codes for common, native and unproved source sets.

## [3.13.9] - 2026-08-16

### Fixed

- Made `Stop after the first failure` stop sibling Affected task groups across mixed projects while leaving unrelated IDE runs untouched.

## [3.13.8] - 2026-08-16

### Fixed

- Name every Affected-owned Gradle Run session `Affected` instead of deriving an unbounded tab and test-history name from the project and task paths.

## [3.13.7] - 2026-08-16

### Fixed

- Treat Xcode schemes without runnable tests as build-only so Affected avoids the guaranteed `xcodebuild test` exit 66 and performs a signing-independent project build instead.
- Recalculate affected modules after commits, branch changes, and Git repository refreshes even when no source-file event is emitted.

## [3.13.6] - 2026-08-16

### Fixed

- Prevented Stop Affected Run from starting queued task groups or pending Maven launches, while leaving unrelated IDE runs untouched.

## [3.13.5] - 2026-08-16

### Fixed

- Stop only the exact Affected-owned Gradle verification task across pre-launch, late-binding and running cancellation, and wait for termination before reporting failure.

## [3.13.4] - 2026-08-16

### Fixed

- Revalidate every CLI working directory against its planned filesystem identity before command resolution and process launch, refusing missing, re-created, unreadable, linked or out-of-project roots instead of executing through stale paths.

## [3.13.3] - 2026-08-16

### Fixed

- Make Python unittest exact selection verify that every selected module contributes owned tests, widening zero-test and ambiguous selections to bounded package discovery in the same process and failing visibly when runner or symlink safety cannot be proven.

## [3.13.2] - 2026-08-16

### Fixed

- Restore the documented Go package-level test boundary so build tags and platform-specific test files cannot produce a successful zero-test function filter.

## [3.13.1] - 2026-08-16

### Fixed

- Forward only selected testthat paths to exact Rscript runs, without injecting a synthetic argument into the test context filter.

## [3.13.0] - 2026-08-16

### Added

- Replace the metadata-only R package check with an isolated real `R CMD check` that runs package tests and examples, propagates failures, and removes temporary output without changing testthat selection.

## [3.12.0] - 2026-08-15

### Added

- Select directly changed C# xUnit v3 4 test classes in standalone projects through the native .NET 10 Microsoft Testing Platform runner when SDK, locked NuGet metadata and archives, effective imports, output configuration, and a conventional one-class file with an explicit `global::Xunit.Fact` method are fully proven; projects with dependencies or extensions and every ambiguous case run the full test project.

## [3.11.0] - 2026-08-15

### Added

- Load R package source and run directly changed conventional testthat files in one process while helper, snapshot, configuration, generated and ambiguous changes keep the full project suite.

## [3.10.0] - 2026-08-15

### Added

- A lone first-level Dart package is now the Dart root when the repository base has no marker.

## [3.9.0] - 2026-08-15

### Added

- A lone first-level Flutter package is now the Flutter root when the repository base has no marker.

### Fixed

- Make Run the full plan and Stop after the first failure authoritative for Cargo tests, nextest profiles and doctests.
- Reject symlinked first-level nested build roots instead of executing a build outside the opened project.

## [3.8.1] - 2026-08-15

### Changed

- Apply the configured failure strategy to native Gradle and Maven runs without weakening failed-run reporting or collector promotion.

## [3.8.0] - 2026-08-15

### Added

- A lone first-level `backend/` Kotlin Toolchain project is now the toolchain root when the repository base has no marker.
- Support `xunit.runner.visualstudio` 4.0.0 for exact VSTest selection.

## [3.7.0] - 2026-08-15

### Added

- A lone first-level `pkg/` R package is now the R root when the repository base has no marker.

## [3.6.2] - 2026-08-15

### Fixed

- Plan a Kotlin Multiplatform compile task that exists in the module instead of an ambiguous `compileTestKotlin`.
- Plan only Gradle test and compile tasks that exist in the imported project. Missing task lists stay empty instead of guessing `test` or `compileTestKotlin`.
- Select a lone user Xcode scheme so `xcodebuild test` is not unscoped.

## [3.6.1] - 2026-08-15

### Fixed

- Plan `compileAndroidMain` for a Kotlin Multiplatform Android library instead of a missing `compileDebugKotlin`.

## [3.6.0] - 2026-08-14

### Added

- A lone first-level `backend/` Cargo project is now the Cargo root when the repository base has no marker.

## [3.5.0] - 2026-08-14

### Added

- A lone first-level `backend/` Go module is now the Go root when the repository base has no marker.

## [3.4.1] - 2026-08-14

### Fixed

- Plan exact Kotlin Multiplatform Android test tasks (`testAndroidHostTest`, `testAndroid`) instead of a bare `test` that Gradle treats as ambiguous.

### Changed

- The toolbar count is modules that will run tests. Compile-only affected modules stay in the menu and plan, but no longer inflate the badge.

## [3.4.0] - 2026-08-14

### Added

- A lone first-level `legacy/` Ant project is now the Ant root when the repository base has no marker.

## [3.3.0] - 2026-08-14

### Added

- A lone first-level `migrations/` Atlas project is now the Atlas root when the repository base has no marker.

## [3.2.0] - 2026-08-14

### Added

- A lone first-level `native/` Make project is now the Make root when the repository base has no marker.

## [3.1.0] - 2026-08-14

### Added

- A lone first-level `native/` Ninja project is now the Ninja root when the repository base has no marker.

## [3.0.0] - 2026-08-14

### Added

- Run native CMake and .NET commands on one in-repo mixed fixture so a CMake plan cannot invoke `dotnet` and a .NET plan cannot invoke `cmake` / `ctest`.
- A lone first-level `backend/` Bazel workspace is now the Bazel root when the repository base has no marker.
- A lone first-level `native/` Buck2 project is now the Buck2 root when the repository base has no marker.
- A lone first-level `analytics/` dbt + DuckDB project is now the dbt root when the repository base has no marker.
- A lone first-level `native/` Meson project is now the Meson root when the repository base has no marker.
- A lone first-level `backend/` Pants project is now the Pants root when the repository base has no marker.
- A lone first-level `queries/` sqlc project is now the sqlc root when the repository base has no marker.
- Verify the plugin ZIP against Rider 2025.3.5 and GoLand 2025.3.5.1 as their own Plugin Verifier product types. UI lifecycle stays on #105; DataGrip stays planned.
- Run native `sqlc compile` on the in-repo Linux fixture so local schema and query files compile without a network database; database URIs and cloud stay off.
- Skip startup analysis, VFS refresh, external-system invalidation and external execute-task claims on a proven JetBrains Client frontend so analysis stays on the IDE backend; Gateway install and update stay unclaimed.
- Detect local Atlas roots from `atlas.hcl` and run one `atlas migrate validate` session; database URLs, `dev` databases, cloud directories and interpolated manifests stay off this adapter. Native atlas execution and plain SQL files stay unclaimed.
- Fail dynamic-plugin descriptor tests when a descriptor declares `<nativelib>`; a native library blocks a restartless update.
- Detect local sqlc roots from `sqlc.yaml` / `sqlc.yml` / `sqlc.json` and run one `sqlc compile` session; database URIs, managed/cloud databases, process plugins and interpolated manifests stay off this adapter. Native sqlc execution and plain SQL files stay unclaimed.
- Fail dynamic-plugin descriptor tests when a content module is not `loading="optional"` or a `config-file` dependency is required.
- Keep Ant off JetBrains MPS projects that generate `build.xml` (`.mps`, `*.mpl`, `*.msd`); MPS stays unsupported without a public test CLI.
- Detect local dbt roots from `dbt_project.yml` plus an in-repo DuckDB `profiles.yml` and run one `dbt test` / `dbt compile` session with `--profiles-dir .`; warehouse, MotherDuck and interpolated profiles stay off this adapter.
- Scope Kotlin Toolchain `-p` when every changed file sits in a proven `src@platform` / `test@platform` family; common sources, aliases and unproved versions keep every platform.
- Merge static Buck2 `[cells]` directories into the project content roots; unproved or missing cell paths keep the project root. Aliases and native buck2 execution stay unclaimed.
- Detect Xcode roots from `.xcodeproj` / `.xcworkspace` and run one `xcodebuild test` or `xcodebuild build` session; a single shared scheme is selected, several schemes keep the unscoped command. SwiftPM, Gradle and Maven roots stay off this adapter.
- Detect SwiftPM roots from `Package.swift` and run one `swift test` or `swift build` session; Gradle and Maven roots stay off this adapter. Xcode schemes, target selection and native Swift execution stay unclaimed.
- Detect Buck2 roots from `.buckconfig` and run one `buck2 test` or `buck2 build` session; a lone `BUCK` file and Gradle/Maven roots stay off this adapter. Aliases and native buck2 execution stay unclaimed.
- Detect Pants roots from `pants.toml` and run one `pants test` or `pants check` session; Gradle and Maven roots stay off this adapter. Target selection and native pants execution stay unclaimed.
- Scope Kotlin Toolchain `-m` only when the wrapper pins a proven `0.11.x` CLI; missing or other versions keep the unscoped `kotlin test` / `kotlin build` command.
- Keep Dart workspace commands when `.dart_tool`, `build.yaml` or a generated `*.g.dart` / `*.freezed.dart` file sits outside a member package; generated files and `assets/` inside a member stay scoped.
- Run `dart run build_runner build --delete-conflicting-outputs` before Dart or Flutter `test` / `analyze` when `build.yaml` exists or `pubspec.yaml` declares `build_runner`; unreadable manifests keep generate.
- Detect R package roots from `DESCRIPTION` / `renv.lock` and run one `testthat::test_dir` or `read.dcf` session; Gradle and Maven roots stay off this adapter. testthat file selection, renv libraries and R Markdown stay unclaimed.
- Keep Ant on `ant test` when the graph is dynamic (`antcall`, nested `ant`, unproved target names, `if` / `unless`); static JUnit/TestNG task targets still win when the graph is complete.
- Expand static Ant `${property}` imports from `name`/`value` and `property file` so `file="${defs}"` can contribute `test`; unresolved properties keep `ant test`.
- Detect Flutter package roots from `pubspec.yaml` with `sdk: flutter` and run one `flutter test` or `flutter analyze` session; root Gradle and Maven stay off this adapter. Nested `android/` Gradle files keep the Flutter root. Generated sources and build_runner stay unclaimed.
- Run Ant `generate` / `codegen` before `test` when the test target does not depend on it; proved `depends` skip the extra command, unproved depends keep generate.
- Select an Ant target that contains a `junit` or `testng` task when `test` / `junit` names are absent; a named `test` target still wins.
- Select proven pub workspace packages with one `dart test <pkg>/test` session; `pubspec.yaml` / `pubspec.lock` changes and unproved workspace lists keep the root command. Flutter modules stay unclaimed.
- Merge static Ant `import` files into the target set so a `test` declared in `testdefs.xml` is runnable; property, prefixed `include` and missing imports keep `ant test`.
- Merge in-tree Meson `subprojects` and `meson-info` tests into the project so a test declared only in a subproject is runnable; unreadable introspection keeps `meson test`.
- Merge static Make `include` files into the target set so a `test` declared in `testdefs.mk` is runnable; variable, glob and missing includes keep `make test`.
- Detect standalone Ninja roots from `build.ninja` and run one `ninja test` or `ninja check` session; production-only changes run the default target. The Ninja file is not parsed as a source graph. CMake, Meson, Make, Gradle and Maven roots stay off this adapter.
- Detect conventional Make roots from `Makefile` / `GNUmakefile` and run one `make test` or `make check` session; production-only changes run the default target. Gradle, Maven, CMake and Meson roots stay off this adapter. Includes and Ninja stay unclaimed.
- Detect Meson roots from `meson.build` and run one `meson test` or `meson compile` session; setup runs only when no configured build directory exists. Gradle, Maven and CMake roots stay off this adapter.
- Detect Dart package roots from `pubspec.yaml` and run one `dart test` or `dart analyze` session; Flutter SDK packages, Gradle and Maven roots stay off this adapter. Flutter selection stays unclaimed.
- Select proven Bazel packages with one `bazel test //pkg:all` session; MODULE, WORKSPACE, BUILD and `.bzl` changes keep `//...`. Target-level ownership stays unclaimed.
- Select proven Kotlin Toolchain modules with one `kotlin test -m` / `kotlin build -m` invocation; a root task or several production modules keep the unscoped project command.
- Detect Bazel roots from `MODULE.bazel` / `WORKSPACE` and run one `bazel test //...` or `bazel build //...` session; Gradle and Maven roots stay off this adapter. Target-level ownership stays unclaimed.
- Attribute Kotlin Toolchain `project.yaml` modules to their directories so an `app` change does not own `lib`; globs and unproved lists keep the root, and one `kotlin test` session still runs for the project.
- Detect Kotlin Toolchain roots from `module.yaml` / `project.yaml` plus the `kotlin` wrapper and run one `kotlin test` or `kotlin build` session; Gradle settings and Amper-only wrappers stay off this adapter. Alpha toolchains stay project-level.
- Detect conventional Ant roots from `build.xml` and run one `ant test` or `ant compile` session; Gradle and Maven roots stay off this adapter, and a `junit` target is used when `test` is absent.
- Narrow hierarchical Kotlin Multiplatform source sets so `nativeMain` keeps native target tests, `appleMain` keeps Apple tests, and included-build task paths stay in the same family; `commonMain` and unproved paths keep every target task.
- Narrow Kotlin Multiplatform target tests to the source-set family of the change (`androidMain` keeps `testDebugUnitTest`, `iosMain` keeps iOS tests); `commonMain` and unproved paths keep every target task.
- Treat public Scala and Groovy declarations as API changes so mixed Kotlin/Java/Scala/Groovy consumers are compiled; private members still do not.
- Select Spock specifications from a changed Gradle Groovy test file with `--tests`; JUnit, production and unproved changes keep the module task.
- Select TestNG classes from a changed Gradle test file with `--tests`; JUnit, production and unproved changes keep the module task so the collector can stay honest.
- Select proven sbt `lazy val` subprojects with one `sbt --batch <project>/test` invocation; unparseable `Project(` builds and `build.sbt` / `project/` changes keep the root command.
- Detect sbt roots from `build.sbt` and run one `sbt test` or `sbt compile` session for the affected project; multi-project selection stays on the root command.
- Attribute Scala and Groovy sources in Gradle and Maven modules so a changed `.scala` or `.groovy` file plans the owning module, including Groovy/Scala-only test trees.
- Select named Pest 5.1.1 `it()` / `test()` cases that statically reference a changed PSR-4 class; `describe()`, hooks, dynamic names and file-scope uses keep the selected files.
- Select changed standard-library unittest modules by native path when every planned package change is a regular `test_*.py` / `*_test.py` file; production and unproved changes keep `unittest discover`.
- Select Microsoft Testing Platform test classes from a changed test file with `--filter-class`; production and unproved MTP changes keep the project command.
- Select Pest 5.1.1 tests that statically `use` a changed PSR-4 production class; unmapped or unreferenced production files keep the package suite.
- Narrow cargo-nextest to changed Rust files with a native `file()` filter when the change set stays package-selective; workspace-widening changes keep the full nextest command.
- Select `Test*` functions from a changed Go `*_test.go` file with `go test -run`; production and unproved Go changes keep the package test command.
- Detect Microsoft Testing Platform from the test project as well as `global.json`, and run those projects with `dotnet test --project` instead of the VSTest path form.
- Select named Pest 5.1.1 dataset files together with the test files that statically `->with` them; unused, dynamic or boot datasets keep the package suite.
- Select changed Pest 5.1.1 test files by native path when every planned suite change is a regular test file; production, boot, helper and unproved changes keep the package suite.
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

- Record already implemented TestNG, Spock, unittest and Microsoft Testing Platform runners in the public support matrix.
- Run every available Kotlin Multiplatform target test task (`testDebugUnitTest`, `iosSimulatorArm64Test`, `jvmTest`, …) for a changed module that has tests, instead of a single JVM `test`.
- Compile a changed Gradle module that has no test sources instead of returning an empty plan; Kotlin Multiplatform `androidUnitTest` / `iosTest` count as tests, and production-only modules use `compileKotlin` / `compileDebugKotlin` rather than a missing unit-test task.
- Fail unit tests when a plugin descriptor would block a restartless update: `require-restart`, legacy components, nameless action groups, non-dynamic extension points, or a missing optional/content descriptor.
- Move the detailed support matrix to its own generated page and keep the README and Marketplace description concise.

### Fixed

- Drop a build-system snapshot when it exceeds 4096 modules, so Python, Node, Cargo and the other cached adapters cannot retain an unbounded graph.
- Bound click-to-Run planning to a 250ms budget so a large module set stays under the declared limit.
- Bound manifest scans to a shared time and size budget so a tree that exceeds it fails closed instead of scanning forever.
- Prefer content-root ownership so a mixed CMake and .NET repository does not plan the other system for a file that already has a scoped owner.
- A named task or toolbar check now fails when its build adapter is gone instead of reporting success.
- Fail a published snapshot when it exceeds 4096 modules, so the UI cannot retain an unbounded analysis result.
- Fail descriptor checks when the plugin registers a blocking `<startupActivity>`; startup stays on one `ProjectActivity`.
- Keep README, LICENSE and changelog files out of all-file change collection so a docs edit cannot fan out to every CMake, .NET or Python adapter.
- Select `connectedDebugAndroidTest` for an instrumentation-only Android change when that task exists; mixed or unit-test changes keep `testDebugUnitTest`.
- Keep the requested name when a PATH program is a rustup-style proxy, so `cargo test --doc` is not rewritten to `rustup test --doc`.
- Include the build-system id in a module key so same-named CMake and .NET modules in one root cannot form a false consumer edge.
- Fail a prepared Run group when its build adapter is gone, instead of counting a missing adapter as success.
- Discover a single first-level nested CLI root (`cpp/`, `backend-dotnet/`) when the project base has no marker; several or deeper nested markers stay off.
- Resolve CLI programs through `PATH` and Windows `PATHEXT`, so a proven `name.exe` is chosen and a missing program keeps the original name.
- Treat Windows path separators like `/` when deciding whether a VFS event is a source change, so spaces, non-ASCII names and `.git` / `build` paths stay classified the same on every OS.
- Pass Pest `--configuration` and `--no-output` before suite paths so Pest 5.1.1 cannot append those flags after them, and resolve the generated XML bootstrap from the project root.
- Enforce Jackson BOM 2.22.1 on the MCP module so the optional MCP Server plugin cannot reintroduce Jackson 2.19.
- Keep cargo-nextest discovery deterministic when the environment forces colored Cargo output.

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

[Unreleased]: https://github.com/aspix2k/affected/compare/v3.15.3...HEAD
[3.15.3]: https://github.com/aspix2k/affected/compare/v3.15.2...v3.15.3
[3.15.2]: https://github.com/aspix2k/affected/compare/v3.15.1...v3.15.2
[3.15.1]: https://github.com/aspix2k/affected/compare/v3.15.0...v3.15.1
[3.15.0]: https://github.com/aspix2k/affected/compare/v3.14.0...v3.15.0
[3.14.0]: https://github.com/aspix2k/affected/compare/v3.13.9...v3.14.0
[3.13.9]: https://github.com/aspix2k/affected/compare/v3.13.8...v3.13.9
[3.13.8]: https://github.com/aspix2k/affected/compare/v3.13.7...v3.13.8
[3.13.7]: https://github.com/aspix2k/affected/compare/v3.13.6...v3.13.7
[3.13.6]: https://github.com/aspix2k/affected/compare/v3.13.5...v3.13.6
[3.13.5]: https://github.com/aspix2k/affected/compare/v3.13.4...v3.13.5
[3.13.4]: https://github.com/aspix2k/affected/compare/v3.13.3...v3.13.4
[3.13.3]: https://github.com/aspix2k/affected/compare/v3.13.2...v3.13.3
[3.13.2]: https://github.com/aspix2k/affected/compare/v3.13.1...v3.13.2
[3.13.1]: https://github.com/aspix2k/affected/compare/v3.13.0...v3.13.1
[3.13.0]: https://github.com/aspix2k/affected/compare/v3.12.0...v3.13.0
[3.12.0]: https://github.com/aspix2k/affected/compare/v3.11.0...v3.12.0
[3.11.0]: https://github.com/aspix2k/affected/compare/v3.10.0...v3.11.0
[3.10.0]: https://github.com/aspix2k/affected/compare/v3.9.0...v3.10.0
[3.9.0]: https://github.com/aspix2k/affected/compare/v3.8.1...v3.9.0
[3.8.1]: https://github.com/aspix2k/affected/compare/v3.8.0...v3.8.1
[3.8.0]: https://github.com/aspix2k/affected/compare/v3.7.0...v3.8.0
[3.7.0]: https://github.com/aspix2k/affected/compare/v3.6.2...v3.7.0
[3.6.2]: https://github.com/aspix2k/affected/compare/v3.6.1...v3.6.2
[3.6.1]: https://github.com/aspix2k/affected/compare/v3.6.0...v3.6.1
[3.6.0]: https://github.com/aspix2k/affected/compare/v3.5.0...v3.6.0
[3.5.0]: https://github.com/aspix2k/affected/compare/v3.4.1...v3.5.0
[3.4.1]: https://github.com/aspix2k/affected/compare/v3.4.0...v3.4.1
[3.4.0]: https://github.com/aspix2k/affected/compare/v3.3.0...v3.4.0
[3.3.0]: https://github.com/aspix2k/affected/compare/v3.2.0...v3.3.0
[3.2.0]: https://github.com/aspix2k/affected/compare/v3.1.0...v3.2.0
[3.1.0]: https://github.com/aspix2k/affected/compare/v3.0.0...v3.1.0
[3.0.0]: https://github.com/aspix2k/affected/compare/v2.0.1...v3.0.0
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
[1.0.0]: https://github.com/aspix2k/affected/commits/v1.0.0

# Affected

[![CI](https://github.com/aspix2k/affected/actions/workflows/ci.yml/badge.svg)](https://github.com/aspix2k/affected/actions/workflows/ci.yml)
[![Exact-impact conformance](https://github.com/aspix2k/affected/actions/workflows/conformance.yml/badge.svg)](https://github.com/aspix2k/affected/actions/workflows/conformance.yml)
[![Marketplace](https://img.shields.io/jetbrains/plugin/v/33425?label=marketplace)](https://plugins.jetbrains.com/plugin/33425-affected)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/33425?label=downloads)](https://plugins.jetbrains.com/plugin/33425-affected)
[![Since](https://img.shields.io/badge/IDE-2025.3%2B-blue)](https://plugins.jetbrains.com/plugin/33425-affected/versions)
[![License](https://img.shields.io/github/license/aspix2k/affected)](LICENSE)

**Run only what your change can affect.**

Affected maps changed source files to build modules and runs their tests, build
or configured static analysis. It can also check direct consumers after a
public API change. Unrelated modules are skipped. Compatible modules inside one
build root share one IDE Run tab; independent build roots run concurrently.

After one successful full Affected run, compatible Gradle and Maven test tasks
reuse a local dependency map to narrow execution to Jupiter and Vintage
test-class candidates for changed production bytecode. An unchanged compatible
task skips its test worker. Missing or incompatible maps, changed test/runtime
inputs, added or deleted classes, resources and unsupported test engines keep
the full task. Runtime and bytecode references are attributed independently for
each test class, including parallel execution in one worker. Unattributed or
asynchronous production access keeps the full task instead of risking a skipped
test.

There is no Affected-specific project configuration. Gradle and Maven modules
come from the IDE project model; the other integrations read their standard
manifests.

## Supported projects

| Project type | Changed module | Direct consumer |
| --- | --- | --- |
| Gradle JVM | `test` | `compileTestKotlin` |
| Gradle Android | `testDebugUnitTest` | `compileDebugUnitTestKotlin` |
| Maven | `test`, or `verify` when the reactor binds Failsafe | `test-compile` |
| Cargo workspace or package | `cargo test -p` | `cargo check --tests -p` |
| Go module | `go test` | `go build` |
| npm, Yarn or pnpm workspace or package | workspace `test`, or `tsc --noEmit` when only type checking is available | `tsc --noEmit` when the package has `tsconfig.json` |
| .NET solution or project | `dotnet test`, or `dotnet build` for a non-test project | `dotnet build` |
| Python repository | `pytest`, or `mypy` when only type checking is available | `mypy` when configured |
| Composer repository | PHPUnit, or PHPStan when only static analysis is available | PHPStan when configured |
| Bundler repository | RSpec | — |
| CMake project | full build and CTest when tests are registered, otherwise target build | dependent target build |

The recognised languages are Kotlin, Java, Rust, Go, JavaScript, TypeScript,
C#, F#, Visual Basic, Python, PHP, Ruby, C and C++. JSX, TSX, Vue, Svelte and
Razor files are recognised too. Gradle projects may use Kotlin or Groovy build
scripts; linked and composite builds, renamed or flat modules, and Android
source sets are supported.

## Change scope

Local changes come from the IDE's VCS integration. In a Git repository, Affected
also compares the current branch with the merge base of the configured base
branch, falling back to `develop`, `main`, then `master`.

With Git available, public API detection compares Kotlin and Java declarations.
Production and configuration changes owned by the other supported build systems
are conservatively treated as possible API changes; their recognised test paths
are excluded. Consumer checking is optional and covers direct consumers only.
A root manifest or an unowned source below a build root widens to every module
in that build. Without Git, local source changes still work and conservatively
affect consumers.

## Using the plugin

The toolbar matrix shows the current number of affected modules and animates
while the project is initializing or verification is running. Open its menu to
inspect the modules or navigate to them, then run verification with
`Ctrl+Alt+Shift+T`.

The same menu contains these settings:

- **Check consumers:** off by default.
- **Run before commit:** off by default.
- **Run before push:** off by default.
- **Animate while running:** on by default.

Gradle and Maven run through their IDE integrations. Other build systems launch
their command-line tools in one fail-fast Run session per build-system root, so
the relevant tool must be available on `PATH`. A missing tool, malformed or
stale graph, unresolved planned module, ambiguous CMake build tree or bounded
discovery overflow fails visibly or widens to the build root; it never silently
drops part of the plan.

Cargo, Go, npm, pnpm, Python and Composer batch compatible modules into one
process per task kind; Ruby uses one RSpec process and a CMake test plan uses one
build plus one CTest process. Yarn and .NET keep one process per selected
workspace or project, but all processes remain sequential inside the shared Run
session. General manifest discovery is capped at depth 7 and 16,384 directories;
fingerprints cover at most 4,096 files, 8 MiB per file and 64 MiB total. Node
workspace and CMake build-tree searches have lower 4,096- and 512-directory
caps. Crossing a cap disables selective reuse instead of allowing a partial
graph.

Test-class selection stays inside the original IDE invocation and Run tab.
Gradle selection is task-local. Maven reactor modules use independent maps.
Maven exact selection supports Maven 3.9.x, Surefire 3.x, Failsafe 3.x and
JUnit Platform with integer `forkCount` values from 1 through 256, whether
`reuseForks` is `true` or `false`. Failsafe integration tests use a separate
task key and map; reactors that bind `integration-test` run `verify` in the
same IDE invocation. Both adapters can select multiple mapped test classes
without depending on their execution order. Forkless, core-scaled or
fractional fork counts and additional test executions keep the full module
test goal.
Direct `integration-test` runs may reuse a complete Failsafe map but cannot
replace it because Failsafe reports test failures during `verify`.
Complete maps are replaced only by successful full runs and remain on the local
machine. A collector schema change invalidates older maps and causes one full
run to rebuild them safely.

Each supported Gradle test task or Maven reactor module prints one decision in
the original Run output:

```text
[Affected] :app:testDebugUnitTest - full fallback (baseline missing)
[Affected] :app:testDebugUnitTest - exact (3 test classes)
[Affected] :core:test - proven-empty
[Affected] :legacy:test - full fallback (unsupported framework)
```

The Run tree still shows the original module-level build tasks because exact
class filters are applied inside those tasks after compilation. The decision
line reports what actually happened. Full-fallback reasons never include
absolute paths, bytecode hashes or unbounded class lists.

The optional [MCP Server](https://plugins.jetbrains.com/plugin/26071) integration
exposes the affected modules, changed files, verification plan and run controls
to AI agents.

## Requirements

- A JetBrains IDE based on IntelliJ Platform 2025.3 or newer.
- The IDE integration for Gradle or Maven when using those project types.
- The relevant command-line tools for the other project types.

The repository's conformance matrix runs public Gradle and Maven fixtures,
cross-platform CLI contracts, and committed native projects for Cargo, Go, npm,
.NET, pytest, PHPUnit, RSpec and CMake. It covers Gradle 8.14.3 and 9.6.1,
Maven 3.9.0 and 3.9.16, compatible JDK 17–26 pairings, and real native commands.
Correctness gates exact selections, complete baseline promotion, executable CLI
plans and full fallbacks. CLI discovery is bounded and content-fingerprint caches
avoid reparsing unchanged graphs; overflow disables selective caching instead of
weakening correctness. Measurements have no timing-based correctness threshold.

## Interface languages

English, Czech, German, Spanish, French, Indonesian, Italian, Japanese, Korean,
Polish, Brazilian Portuguese, Russian, Simplified Chinese and Turkish.

## Privacy

Affected analyzes project metadata and changes locally. It has no server,
account or telemetry and makes no network requests of its own. Error reporting
only opens a prefilled GitHub issue for you to review. See
[PRIVACY.md](PRIVACY.md).

[Contributing](CONTRIBUTING.md) · [MIT License](LICENSE)

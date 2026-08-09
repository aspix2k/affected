# Affected

[![CI](https://github.com/aspix2k/affected/actions/workflows/ci.yml/badge.svg)](https://github.com/aspix2k/affected/actions/workflows/ci.yml)
[![Marketplace](https://img.shields.io/jetbrains/plugin/v/33425?label=marketplace)](https://plugins.jetbrains.com/plugin/33425-affected)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/33425?label=downloads)](https://plugins.jetbrains.com/plugin/33425-affected)
[![Since](https://img.shields.io/badge/IDE-2025.3%2B-blue)](https://plugins.jetbrains.com/plugin/33425-affected/versions)
[![License](https://img.shields.io/github/license/aspix2k/affected)](LICENSE)

**Run only what your change can affect.**

Affected maps changed source files to build modules and runs the tests of the
modules they belong to. It can also check direct consumers after a Kotlin or
Java public API change. Unrelated modules are skipped. Compatible Gradle modules
inside one composite build share an IDE invocation and Run tab; independent
build roots run concurrently.

There is no Affected-specific project configuration. Gradle and Maven modules
come from the IDE project model; the other integrations read their standard
manifests.

## Supported projects

| Project type | Changed module | Direct consumer |
| --- | --- | --- |
| Gradle JVM | `test` | `compileTestKotlin` |
| Gradle Android | `testDebugUnitTest` | `compileDebugUnitTestKotlin` |
| Maven | `test` | `test-compile` |
| Cargo workspace | `cargo test -p` | `cargo check --tests -p` |
| Go module | `go test` | `go build` |
| npm, Yarn or pnpm workspace | workspace `test` | `tsc --noEmit` when the package has `tsconfig.json` |
| .NET solution or project | `dotnet test` | `dotnet build` |
| Python multi-project repository | `pytest` | `mypy` when configured |
| Composer multi-package repository | PHPUnit | PHPStan when static analysis is configured |
| Bundler multi-gem repository | RSpec | — |
| CMake project with multiple targets | CTest | dependent target build |

The recognised languages are Kotlin, Java, Rust, Go, JavaScript, TypeScript,
C#, F#, Visual Basic, Python, PHP, Ruby, C and C++. JSX, TSX, Vue, Svelte and
Razor files are recognised too. Gradle projects may use Kotlin or Groovy build
scripts; linked and composite builds, renamed or flat modules, and Android
source sets are supported.

Python and Composer repositories need at least two package manifests, Bundler
repositories need at least two gemspecs, and CMake projects need at least two
`add_executable` or `add_library` targets.

## Change scope

Local changes come from the IDE's VCS integration. In a Git repository, Affected
also compares the current branch with the merge base of the configured base
branch, falling back to `develop`, `main`, then `master`.

With Git available, public API detection compares Kotlin and Java declarations.
Consumer checking is optional and covers direct consumers only. Without Git,
local source changes still work, but they are conservatively treated as possible
API changes.

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
their command-line tools in the Run tool window, so the relevant tool must be
available on `PATH`.

The optional [MCP Server](https://plugins.jetbrains.com/plugin/26071) integration
exposes the affected modules, changed files, verification plan and run controls
to AI agents.

## Requirements

- A JetBrains IDE based on IntelliJ Platform 2025.3 or newer.
- The IDE integration for Gradle or Maven when using those project types.
- The relevant command-line tools for the other project types.

## Interface languages

English, Czech, German, Spanish, French, Indonesian, Italian, Japanese, Korean,
Polish, Brazilian Portuguese, Russian, Simplified Chinese and Turkish.

## Privacy

Affected analyzes project metadata and changes locally. It has no server,
account or telemetry and makes no network requests of its own. Error reporting
only opens a prefilled GitHub issue for you to review. See
[PRIVACY.md](PRIVACY.md).

[Contributing](CONTRIBUTING.md) · [MIT License](LICENSE)

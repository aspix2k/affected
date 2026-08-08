# Affected

[![CI](https://github.com/aspix2k/affected/actions/workflows/ci.yml/badge.svg)](https://github.com/aspix2k/affected/actions/workflows/ci.yml)
[![Marketplace](https://img.shields.io/jetbrains/plugin/v/33425?label=marketplace)](https://plugins.jetbrains.com/plugin/33425-affected)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/33425?label=downloads)](https://plugins.jetbrains.com/plugin/33425-affected)
[![Since](https://img.shields.io/badge/IDE-2025.3%2B-blue)](https://plugins.jetbrains.com/plugin/33425-affected/versions)
[![License](https://img.shields.io/github/license/aspix2k/affected)](LICENSE)

**Run only what your change can affect.**

Affected maps changed files to build modules, tests the modules you touched, and
can verify their direct consumers when a public API changes. Unrelated modules are
skipped; independent build roots run concurrently.

## Why use it

- **Focused feedback.** Catch a broken consumer without waiting for the entire monorepo.
- **Native workflow.** Gradle and Maven use their IDE integrations; other tools use the standard Run window.
- **One plan everywhere.** Use the same analysis from the toolbar, commit dialog, push check, or MCP.
- **No project setup.** Project models and standard manifests define the module graph.
- **Lightweight and local.** The plugin ZIP is under 400 KB. There is no server, account, or telemetry.

## Workflow

The toolbar matrix fills as more modules are affected. It is muted and animated
while the IDE initializes, animates during verification, and returns to the current
affected state when the run ends.

Open the menu to inspect or navigate to affected modules, then run with
`Ctrl+Alt+Shift+T`. Settings contains four switches in three groups:

- **Check consumers:** off by default.
- **Run before commit:** off by default.
- **Run before push:** off by default.
- **Animate while running:** on by default.

## Supported stacks

| Stack | Changed modules | Direct consumers |
| --- | --- | --- |
| Gradle | `test` / `testDebugUnitTest` | test compilation |
| Maven | `test` | `test-compile` |
| Rust / Cargo | `cargo test -p` | `cargo check --tests -p` |
| Go | `go test` | `go build` |
| npm, Yarn, pnpm | workspace test | TypeScript `tsc --noEmit` |
| .NET | `dotnet test` | `dotnet build` |
| Python | `pytest` | `mypy` when configured |
| PHP / Composer | PHPUnit | static analysis when configured |
| Ruby / Bundler | RSpec | — |
| CMake | CTest | dependent target build |

Gradle composite builds, Kotlin and Groovy build scripts, flat or renamed modules,
and Android source sets are supported.

## Requirements

- A JetBrains IDE 2025.3 or newer.
- One of the supported build tools available to the IDE or on `PATH`.

Local edits come from the IDE, so any VCS it supports works. Comparing with a base
branch requires Git; without Git, Affected still handles uncommitted changes.

The optional [MCP Server](https://plugins.jetbrains.com/plugin/26071) integration
lets an AI agent inspect the plan and start or follow verification.

## Interface languages

English, Czech, German, Spanish, French, Indonesian, Italian, Japanese, Korean,
Polish, Brazilian Portuguese, Russian, Simplified Chinese, and Turkish.

## Privacy

Affected reads project metadata and changes locally. It sends no analytics or
source code. Error reporting only opens a prefilled GitHub issue for you to review.
See [PRIVACY.md](PRIVACY.md).

[Contributing](CONTRIBUTING.md) · [MIT License](LICENSE)

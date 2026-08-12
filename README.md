<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="src/main/resources/META-INF/pluginIcon_dark.svg">
    <img src="src/main/resources/META-INF/pluginIcon.svg" width="96" alt="Affected logo">
  </picture>
</p>

<h1 align="center">Affected</h1>

<p align="center"><strong>A JetBrains IDE plugin that finds affected projects and runs only their tests and build checks.</strong></p>

<p align="center">
  <a href="https://github.com/aspix2k/affected/actions/workflows/ci.yml?query=event%3Apull_request"><img src="https://github.com/aspix2k/affected/actions/workflows/ci.yml/badge.svg?event=pull_request" alt="CI"></a>
  <a href="https://plugins.jetbrains.com/plugin/33425-affected"><img src="https://img.shields.io/jetbrains/plugin/v/33425?label=marketplace" alt="JetBrains Marketplace"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/aspix2k/affected" alt="MIT License"></a>
</p>

<p align="center">
  <a href="https://plugins.jetbrains.com/plugin/33425-affected"><strong>Install</strong></a> ·
  <a href="#support-matrix">Support matrix</a> ·
  <a href="https://github.com/aspix2k/affected/releases">Releases</a> ·
  <a href="https://github.com/aspix2k/affected/issues/29">Roadmap</a> ·
  <a href="https://github.com/aspix2k/affected/issues/new/choose">Report a problem</a>
</p>

Built for multi-module projects and monorepos across JVM, Android, Rust, Go,
Node.js, .NET, Python, PHP, Ruby and C/C++.

> A change in one package no longer starts checks for every unrelated package.

## What you get

- **Less waiting.** Run checks for affected parts of the repository.
- **One action.** Cover JVM, Android, Rust, Go, Node.js, .NET, Python, PHP, Ruby
  and C/C++ projects together.
- **One Run session per build-system root.** Commands stay together in the IDE.
- **Smaller test runs.** Gradle and Maven can narrow to affected JUnit classes;
  supported VSTest and CTest projects to named tests; Jest, Vitest and pytest
  projects to related test files; PHPUnit projects to affected test classes.
- **Consumer checks.** Optionally verify direct dependents after a public API
  change.
- **Local by design.** No account, server, telemetry or project-specific config file.

## Support matrix

| Ecosystem | Languages and files | Tests and checks | Selection unit |
|---|---|---|---|
| Gradle JVM and Android | Kotlin, Java | JUnit tasks and consumer test compilation | JUnit class or module |
| Maven | Kotlin, Java | Surefire, Failsafe and consumer test compilation | JUnit class or module |
| Cargo | Rust | `cargo test`, `cargo check --tests` | package |
| Go modules | Go | `go test`, `go build` | package |
| npm, Yarn and pnpm | JavaScript, TypeScript, JSX, TSX, Vue, Svelte | Jest, Vitest, package test, `tsc --noEmit` | related test file or workspace package |
| .NET | C#, F#, Visual Basic, Razor | VSTest via `dotnet test`, `dotnet build` | test or project |
| Python | Python | pytest, mypy | test file or package |
| Composer | PHP | PHPUnit, PHPStan | PHPUnit class or package |
| Bundler | Ruby | RSpec | gem |
| CMake | C, C++ | build and CTest | named CTest test or target |

**Affected** works in IntelliJ IDEA, Android Studio, Rider, GoLand, CLion, PyCharm,
WebStorm, PhpStorm, RubyMine, RustRover and DataSpell based on IntelliJ Platform
2025.3 or newer. Gradle and Maven use the IDE integration; the other ecosystems
require their command-line tools on `PATH`.

## Get started

1. **[Install Affected](https://plugins.jetbrains.com/plugin/33425-affected).**
2. Open a supported project and make a change.
3. Choose **Run affected tests** or press `Ctrl+Alt+Shift+T`.

The first successful compatible run may test a full module once while
**Affected** learns its exact test relationships.

[How it works](CONTRIBUTING.md#how-it-works) · [Privacy](PRIVACY.md) ·
[Contributing](CONTRIBUTING.md) · [Changelog](CHANGELOG.md) · [MIT License](LICENSE)

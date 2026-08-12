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
  <a href="SUPPORT.md">Support matrix</a> ·
  <a href="https://github.com/aspix2k/affected/releases">Releases</a> ·
  <a href="https://github.com/aspix2k/affected/issues/29">Roadmap</a> ·
  <a href="https://github.com/aspix2k/affected/issues/new/choose">Report a problem</a>
</p>

<!-- affected-support-summary:start -->
Built for multi-module projects and monorepos across 10 supported build ecosystems and 12 JetBrains products. See the [support matrix](SUPPORT.md) for languages, test runners, selection units and evidence.
<!-- affected-support-summary:end -->

> A change in one package no longer starts checks for every unrelated package.

## What you get

- **Less waiting.** Run checks for affected parts of the repository.
- **One action.** Cover supported ecosystems together.
- **One Run session per build-system root.** Commands stay together in the IDE.
- **Smaller test runs.** Narrow to the smallest unit whose relationship can be
  proven by the native build system or test runner.
- **Consumer checks.** Optionally verify direct dependents after a public API
  change.
- **Local by design.** No account, server, telemetry or project-specific config file.

## Get started

1. **[Install Affected](https://plugins.jetbrains.com/plugin/33425-affected).**
2. Open a supported project and make a change.
3. Choose **Run affected tests** or press `Ctrl+Alt+Shift+T`.

The first successful compatible run may test a full module once while
**Affected** learns its exact test relationships.

[How it works](CONTRIBUTING.md#how-it-works) · [Privacy](PRIVACY.md) ·
[Contributing](CONTRIBUTING.md) · [Changelog](CHANGELOG.md) · [MIT License](LICENSE)

# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- Run status and stop use public platform API, so they do not break when the internal execution API changes.

## [1.0.0] - 2026-08-07

### Added

- Run unit tests only for modules whose files changed.
- Check that modules directly consuming a changed public API still compile.
- Toolbar button with a live count of affected modules, disabled when nothing changed.
- List of affected modules with navigation to the sources of each one.
- Per-module actions for detekt, lint and coverage, shown only when the module declares those Gradle tasks.
- Toggle to skip the consumer compilation check and run tests only.
- Base branch detection: configured branch, then `develop`, `main`, `master`.
- Support for composite builds, so a module is run against the build that owns it.
- MCP toolset exposing the same analysis and Gradle execution to AI agents.
- User interface in English, Russian, German, French, Spanish, Italian, Portuguese (Brazil), Polish, Turkish, Japanese, Korean and Simplified Chinese.

[Unreleased]: https://github.com/aspix2k/affected/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/aspix2k/affected/releases/tag/v1.0.0

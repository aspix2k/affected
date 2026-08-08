# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/aspix2k/affected/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/aspix2k/affected/compare/v1.0.1...v1.1.0
[1.0.1]: https://github.com/aspix2k/affected/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/aspix2k/affected/releases/tag/v1.0.0

# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/aspix2k/affected/compare/v1.7.2...HEAD
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

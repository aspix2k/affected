# Affected

[![CI](https://github.com/aspix2k/affected/actions/workflows/ci.yml/badge.svg)](https://github.com/aspix2k/affected/actions/workflows/ci.yml)

Runs unit tests only for the modules you actually changed, and checks that
modules depending on your changed public API still compile.

## Why

In a large multi-module project a full test run takes tens of minutes, so it
gets skipped. The usual result: your own module is green, you push, and CI
fails somewhere above — in a module you never touched, on a signature you
changed.

This plugin runs two things and nothing else:

- unit tests of every module whose files changed;
- compilation of modules that directly consume a module whose public API changed.

A change inside a function body, in a private member, in a test or in an XML
resource cannot break consumers, so consumers are not touched for those.

## How it works

The module graph comes from the IDE project model, not from parsing build
scripts. The plugin therefore does not care whether your build is written in
Kotlin or Groovy, which DSL declares dependencies, or whether the repository is
a single project or a monorepo with composite builds.

Changed files come from git: your branch against its base, plus the working
tree, plus untracked files.

## Usage

The toolbar button next to Run shows how many modules are affected. It is
disabled when nothing is affected. Tests run through the normal IDE Gradle
integration, so the test tree and error navigation work as usual, and the
standard Stop button applies.

The button menu lists the affected modules and navigates to the sources of each
one. detekt, lint and coverage actions appear for a module only when that module
declares the corresponding Gradle task.

Turn off *Check compilation of consumers* if you only want tests.

## AI agents

With the [MCP Server](https://plugins.jetbrains.com/plugin/26071) plugin
installed, an agent gets the same capabilities as the toolbar: listing affected
modules and changed files, inspecting the planned Gradle command, running it,
following its status and stopping it.

## Requirements

- IntelliJ IDEA or Android Studio 2025.1+
- a Gradle project imported into the IDE
- git

## Settings

The base branch defaults to `develop`, then falls back to `main` and `master`,
so in most repositories nothing needs configuring.

## Building

The build needs an IDE to compile against. It downloads the Android Studio
version pinned in `gradle.properties` unless you point it at an installed IDE:

```properties
# local.properties
ide.path=/Users/you/Applications/Android Studio.app
```

The same path can be passed as `-Paffected.ide.path=...` or the
`AFFECTED_IDE_PATH` environment variable.

```sh
./gradlew test          # unit tests
./gradlew runIde        # sandbox IDE with the plugin
./gradlew buildPlugin   # distributable zip in build/distributions
./gradlew verifyPlugin  # JetBrains plugin verifier
```

## Releasing

Releases are cut by pushing a tag. The workflow refuses the tag if it does not
match the version in `build.gradle.kts`, then attaches the zip to a GitHub
release with the notes taken from `CHANGELOG.md`, and publishes to the JetBrains
Marketplace when the `JETBRAINS_MARKETPLACE_TOKEN` secret is set.

```sh
./gradlew patchChangelog   # move Unreleased into the new version
git tag v1.0.0 && git push origin v1.0.0
```

## License

MIT

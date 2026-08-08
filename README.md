# Affected

[![CI](https://github.com/aspix2k/affected/actions/workflows/ci.yml/badge.svg)](https://github.com/aspix2k/affected/actions/workflows/ci.yml)
[![Marketplace](https://img.shields.io/jetbrains/plugin/v/33425?label=marketplace)](https://plugins.jetbrains.com/plugin/33425-affected)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/33425?label=downloads)](https://plugins.jetbrains.com/plugin/33425-affected)
[![Rating](https://img.shields.io/jetbrains/plugin/r/rating/33425?label=rating)](https://plugins.jetbrains.com/plugin/33425-affected/reviews)
[![Since](https://img.shields.io/badge/IDE-2025.1%2B-blue)](https://plugins.jetbrains.com/plugin/33425-affected/versions)
[![License](https://img.shields.io/github/license/aspix2k/affected)](LICENSE)

Test what you changed. Compile what depends on it.

A full test run on a large project takes tens of minutes, so nobody runs it
before pushing. Running just your own module is fast but misses the failure that
matters: you changed a signature, your module is green, and the build breaks in
a module you have never opened.

Affected runs the tests of the modules you touched, and checks that the modules
consuming your changed API still compile. Usually seconds, not minutes.

## Using it

The button next to Run carries the number of affected modules, and is disabled
when there are none. The menu lists those modules, navigates to any of them, and
offers their detekt, lint and coverage tasks.

Runs go through the IDE's own integration, so the test tree, the jump from a
failure to its source, and the Stop button work as usual.

The commit dialog gets a checkbox that runs the same verification and cancels the
commit if it fails, and a push can be aborted the same way. Both are off until you
turn them on.

Gradle, Maven, Cargo, Go, npm, .NET and Python all work, with no configuration —
Kotlin or Groovy build scripts, any dependency DSL, composite builds, npm, yarn
or pnpm.

A consumer is only checked where the language gives something to check:
TypeScript is type checked, plain JavaScript is left alone, and Python consumers
are checked when the project configures mypy.

## AI agents

With the [MCP Server](https://plugins.jetbrains.com/plugin/26071) plugin
installed, an agent can list the affected modules, start the run, follow it and
stop it.

## Requirements

A JetBrains IDE 2025.1+, and a project built by Gradle, Maven, Cargo, Go, npm,
yarn, pnpm, .NET or Python.

Local edits are read from the IDE, so any VCS it supports will do. Comparing
against a base branch needs git; without it the plugin works from your
uncommitted changes.

## Privacy

The plugin collects and sends nothing. If it throws an exception, the IDE offers
to open a prefilled issue on GitHub — the report is visible before you submit it,
and it contains only what you can see in it.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

MIT

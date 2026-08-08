# Affected

[![CI](https://github.com/aspix2k/affected/actions/workflows/ci.yml/badge.svg)](https://github.com/aspix2k/affected/actions/workflows/ci.yml)

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

Runs go through the IDE's Gradle integration, so the test tree, the jump from a
failure to its source, and the Stop button work as usual.

## AI agents

With the [MCP Server](https://plugins.jetbrains.com/plugin/26071) plugin
installed, an agent can list the affected modules, start the run, follow it and
stop it.

## Requirements

A JetBrains IDE 2025.2+, a Gradle, Maven, Cargo or Go project, and git.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

MIT

# Affected Tests

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

Turn off *Check compilation of consumers* in the button menu if you only want
tests.

## Requirements

- IntelliJ IDEA or Android Studio 2025.1+
- a Gradle project imported into the IDE
- git

## Settings

The base branch defaults to `develop`, then falls back to `main` and `master`,
so in most repositories nothing needs configuring.

## License

MIT

# Privacy Policy

Last updated: 11 August 2026

## The short version

Affected collects nothing, sends nothing, and has no server. Everything it does
happens on your machine.

## What the plugin reads

To decide what to run, the plugin reads:

- the list of changed files, from the version control your IDE already tracks;
- the module graph, from the IDE project model for Gradle and Maven, and from
  `cargo metadata`, `go list`, `package.json`, `ProjectReference` or
  `pyproject.toml` for the other build systems;
- the changed lines of a source file, to tell whether a public declaration was
  touched;
- compiled production and test outputs, resources and runtime classpaths for
  compatible Gradle and Maven test-class selection.

All of it is processed locally.

## What the plugin stores

Four settings, in your IDE configuration directory:

- the base branch to compare against;
- whether consumers of a changed API are checked;
- whether the verification runs before a commit;
- whether it runs before a push.

Compatible Gradle and Maven runs also keep a derived cache below the IDE system
directory. It contains task and test-class names, local class-output paths,
production bytecode hashes and runtime fingerprints. It contains no source code
or class-file contents. Selected, incomplete, failed and cancelled runs do not
replace the last complete cache. The cache is local to the project and can be
deleted safely; the next run falls back to the full test task.

## What the plugin runs

To gather the graph and to run the verification, the plugin executes the tools
your project already uses: `git`, Gradle, Maven, `cargo`, `go`, `npm`, `yarn`,
`pnpm`, `dotnet`, `pytest` and `mypy`. Their output goes to the Run tool window
of your IDE and nowhere else.

## Network

The plugin makes no network requests of its own.

Two things reach the network only because you asked for them: the build tools
above may download dependencies as they always do, and reporting an error opens
your browser.

## Errors

If the plugin throws an exception, the IDE offers to report it. Choosing that
opens a **prefilled GitHub issue in your browser** containing the stack trace,
the IDE build, the operating system and the plugin version. Nothing is
transmitted until you submit the form yourself, and the report contains only
what the form shows you. Declining sends nothing.

Since the issue is filed on GitHub, GitHub's own privacy terms apply to it once
you submit.

## Statistics from JetBrains Marketplace

Marketplace reports download counts and page visits to us in aggregate, which we
neither control nor can connect to an individual. The plugin plays no part in
that; it is how every plugin on the Marketplace works. See the
[JetBrains Privacy Policy](https://www.jetbrains.com/legal/docs/privacy/privacy/).

The plugin does not use the IDE's Feature Usage Statistics. That mechanism is
marked internal to the platform and is not available to third-party plugins, and
JetBrains does not share its data with plugin vendors in any case.

## Changes

Any change to this policy will appear in this file, in the repository history,
and in the changelog of the release that carries it.

## Contact

Questions and corrections: <https://github.com/aspix2k/affected/issues>

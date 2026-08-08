# Privacy Policy

Last updated: 8 August 2026

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
  touched.

All of it stays in memory for the length of one analysis. Nothing is written
anywhere except the settings below.

## What the plugin stores

Four settings, in your IDE configuration directory:

- the base branch to compare against;
- whether consumers of a changed API are checked;
- whether the verification runs before a commit;
- whether it runs before a push.

That is the entire persisted state. There is no identifier of you, your machine,
or your project.

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

## Statistics from JetBrains

JetBrains Marketplace reports download counts and page visits to us in aggregate,
which we neither control nor can connect to an individual. The plugin plays no
part in that; it is how every plugin on the Marketplace works. See the
[JetBrains Privacy Policy](https://www.jetbrains.com/legal/docs/privacy/privacy/).

The plugin does not use the IDE's Feature Usage Statistics.

## Changes

Any change to this policy will appear in this file, in the repository history,
and in the changelog of the release that carries it.

## Contact

Questions and corrections: <https://github.com/aspix2k/affected/issues>

# Privacy Policy

Last updated: 12 August 2026

## The short version

Affected has no telemetry or server. Project analysis and verification happen
on your machine.

## What the plugin reads

To decide what to run, the plugin reads:

- the list of changed files, from the version control your IDE already tracks;
- the project graph, from IDE models and the standard manifests or metadata
  commands of your build system;
- the changed lines of a source file, to tell whether a public declaration was
  touched;
- compiled outputs, resources, runtime classpaths and test plans when a
  compatible runner can select individual tests.

All of it is processed locally.

## What the plugin stores

Five settings, in your IDE configuration directory:

- the base branch to compare against;
- whether consumers of a changed API are checked;
- whether the verification runs before a commit;
- whether it runs before a push;
- whether the toolbar icon animates during verification.

Compatible exact-selection adapters also keep a derived cache below the IDE
system directory. It contains local paths, test identities, dependency maps,
content hashes and runtime fingerprints, but no copies of your source code or
compiled project outputs. The .NET adapter also builds and caches its own local
analyzer binary. Selected, incomplete, failed and cancelled runs do not replace
the last complete cache. The cache is local to the project and can be deleted
safely; the next run uses the full test plan.

## What the plugin runs

To gather the graph and run verification, the plugin executes the version
control, build, language and test tools your project already uses. Verification
output appears in the Run tool window. The plugin may capture bounded metadata
responses locally, and tools may create their standard metadata files, such as
CMake File API queries, in the project build directory.

## Network

The plugin makes no network requests of its own.

Two things reach the network only because you asked for them: the build tools
above may download dependencies as they always do, and reporting an error opens
your browser.

## Errors

If the plugin throws an exception, the IDE offers to report it. Choosing that
opens a **prefilled GitHub issue in your browser** containing the stack trace,
the IDE build, the operating system and the plugin version. Opening the page
sends that prefilled URL to GitHub, but does not publish an issue until you
submit the form. Declining sends nothing.

GitHub's own privacy terms apply when the prefilled page opens.

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

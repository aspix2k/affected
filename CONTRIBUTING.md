# Contributing

## Building

The build compiles against an IDE that bundles every integration the plugin
supports. Android Studio ships Gradle but not Maven, so the build downloads the
IntelliJ IDEA version pinned in `gradle.properties`.

You can point it at an installed IDE, as long as that IDE bundles both:

```properties
# local.properties
ide.path=/Applications/IntelliJ IDEA.app
```

The same path works as `-Paffected.ide.path=...` or as the `AFFECTED_IDE_PATH`
environment variable.

```sh
./gradlew detekt        # static analysis and formatting, autocorrecting
./gradlew test          # unit tests
./gradlew runIde        # sandbox IDE with the plugin installed
./gradlew buildPlugin   # zip in build/distributions
./gradlew verifyPlugin  # JetBrains plugin verifier
./gradlew pitest        # mutation testing, slow
```

CI runs everything except `pitest` on each push; `pitest` runs weekly.

Every IDE the build or the verifier touches is unpacked into
`~/.gradle/caches/<gradle>/transforms`, three to five gigabytes each, and old
ones are never removed. After changing the pinned IDE version a few times that
directory reaches tens of gigabytes; deleting it is safe and it is rebuilt on
the next build.

`detekt` runs with `autoCorrect`, so formatting fixes itself and only real
findings remain. There is no baseline file: the count is zero and stays zero.

## How it works

`ChangeAnalyzer` asks git what changed: the diff against the merge base with the
base branch, the working tree, and untracked files. The base branch is the
configured one, otherwise `develop`, `main` or `master`, each tried as a remote
branch first.

Whether a change touched public API is decided by matching added and removed
lines against a declaration pattern. It is a text heuristic, not a compiler, and
it deliberately errs toward running too much.

`ModuleGraph` reads the module graph from the IDE project model rather than from
build scripts, which is what makes build script language, dependency DSL and
composite builds irrelevant. Modules are attributed to the build that owns them
by walking up to the nearest `settings.gradle[.kts]`. Gradle execution
coordinates come separately from the imported model, so included builds keep
their ownership while compatible tasks can run through the composite root.

`TaskPlanner` turns that into task groups, one per build system and execution
root, so compatible Gradle modules share a command while independent roots and
different build systems stay separate. A module already being tested is never
also compiled.

A `BuildSystem` supplies module identity, task names and execution, and registers
itself through the `com.aspix2k.affected.buildSystem` extension point behind an
optional dependency on its IDE integration. Adding one is a single class plus a
four-line XML file; nothing else in the plugin changes.

`ChangeAnalyzer` and `TaskPlanner` have no IDE dependencies and are covered by
unit tests. Keep them that way: return data and let the action format it.

## Conventions

- No dependency injection framework. `@Service(Level.APP/PROJECT)` is enough.
- No comments in production code. Names and structure carry the meaning.
- No hardcoded project names, paths, branches or module lists.
- Long work goes off the EDT; actions use `ActionUpdateThread.BGT`.
- Recomputation is event-driven. No timers.

## Releasing

A release is not only a tag. Everything below describes the same change to a
different audience, and a user who never opens the repository sees only the last
two:

1. `version` in `build.gradle.kts`.
2. A section for that version in `CHANGELOG.md` — CI fails without it, and the
   text becomes both the GitHub release notes and What's New on the marketplace.
3. `README.md` when the change affects what the plugin does or needs.
4. The `<description>` in `plugin.xml` when the supported systems change — it is
   the marketplace page and updates itself on publish.
5. **Getting Started on the marketplace page** — the one thing no automation
   touches. It is edited through the web form and goes stale silently.
6. The compatibility matrix in the vault when a system, product or minimum IDE
   version changes.

Every version needs its own section in `CHANGELOG.md`. CI fails when the version
in `build.gradle.kts` has no entries there, and the release fails when the tagged
version has none — the same section becomes the GitHub release notes and the
plugin's What's New on the marketplace.

Merge the release changes into `main`. After the required CI job succeeds, it
creates the annotated `v<version>` tag when that version is not tagged yet and
calls the reusable Release workflow with that tag. The workflow refuses a
mismatched tag, attaches the zip to a GitHub release with the notes from
`CHANGELOG.md`, and uploads to the JetBrains Marketplace when
`JETBRAINS_MARKETPLACE_TOKEN` is set. An existing GitHub release makes the
automatic preparation idempotent.

```sh
./gradlew patchChangelog
gh workflow run release.yml -f tag=v1.0.0 # recovery for an existing tag
```

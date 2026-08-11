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

Pull-request CI runs everything except `pitest`; `pitest` runs weekly. A push
to `main` only promotes the already verified pull-request artifact.

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
unit tests. Keep them that way: return data and let the action format it. The
`collector` module produces Java 8 agents, a JUnit listener, a Gradle init script
and a Maven core extension under `agent/` in the plugin distribution, outside
the IntelliJ plugin classpath. A full compatible Gradle or Maven run records
every executed Jupiter or Vintage test class and the production bytecode it
loads. Only a successful run with matching complete worker output atomically
replaces the local dependency map; all other cases keep the full-task fallback.
The counted and checksummed map includes the full production class catalog.

Gradle computes selection in a serializable task-local spec after compile
dependencies and applies the public `TestFilter`. Maven 3.9.x uses a core
extension to pass separate counted project manifests to one reusable Surefire
3.x fork and one reusable Failsafe 3.x fork. A JUnit Platform discovery filter
computes selection after `test-compile`; Surefire and Failsafe keep distinct
task keys, output and maps. Neither path starts a second visible build. Runtime,
test, classpath or resource changes and any missing, corrupt, added, deleted or
unsupported input run the original full task. Selected and skipped runs never
replace the full baseline. A direct Failsafe `integration-test` can select from
a baseline but only `verify`, `install` or `deploy` may replace one, because
Failsafe defers test-failure reporting to `verify`.

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

The pull-request CI job records the verified Git tree and plugin SHA-256 beside
the plugin zip. After merge, the Release workflow finds that successful CI run,
requires the verified tree to match `main`, creates the annotated `v<version>`
tag, and promotes the exact same zip to a GitHub release and JetBrains
Marketplace. It does not rebuild or rerun tests. A missing artifact, a non-green
CI run, or any tree/hash mismatch stops the release.

```sh
./gradlew patchChangelog
gh workflow run release.yml -f run_id=123456789 -f source_ref=v1.0.0
```

Add `-f retry_marketplace=true` only to retry Marketplace submission after the
GitHub release already exists.

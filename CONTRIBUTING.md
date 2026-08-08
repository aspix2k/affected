# Contributing

## Building

The build compiles against an IDE. It downloads the Android Studio build pinned
in `gradle.properties`, unless you point it at one you already have:

```properties
# local.properties
ide.path=/Users/you/Applications/Android Studio.app
```

The same path works as `-Paffected.ide.path=...` or as the `AFFECTED_IDE_PATH`
environment variable.

```sh
./gradlew test          # unit tests
./gradlew runIde        # sandbox IDE with the plugin installed
./gradlew buildPlugin   # zip in build/distributions
./gradlew verifyPlugin  # JetBrains plugin verifier
./gradlew pitest        # mutation testing, slow
```

CI runs everything except `pitest` on each push; `pitest` runs weekly.

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
by walking up to the nearest `settings.gradle[.kts]`.

`TaskPlanner` turns that into Gradle tasks, grouped per build root:
`testDebugUnitTest` or `test` for changed modules, `compileDebugUnitTestKotlin`
or `compileTestKotlin` for their direct consumers. A module already being tested
is never also compiled.

`ChangeAnalyzer` and `TaskPlanner` have no IDE dependencies and are covered by
unit tests. Keep them that way: return data and let the action format it.

## Conventions

- No dependency injection framework. `@Service(Level.APP/PROJECT)` is enough.
- No comments in production code. Names and structure carry the meaning.
- No hardcoded project names, paths, branches or module lists.
- Long work goes off the EDT; actions use `ActionUpdateThread.BGT`.
- Recomputation is event-driven. No timers.

## Releasing

Push a tag. The workflow refuses it if it does not match the version in
`build.gradle.kts`, attaches the zip to a GitHub release with the notes from
`CHANGELOG.md`, and uploads to the JetBrains Marketplace when
`JETBRAINS_MARKETPLACE_TOKEN` is set.

```sh
./gradlew patchChangelog
git tag v1.0.0 && git push origin v1.0.0
```

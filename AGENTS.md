# AGENTS.md

Guidance for AI agents working in this repository. Human contributors may find
it useful too.

## What this plugin does

It answers one question: after my changes, what is the minimum I must verify?

- unit tests of modules whose files changed;
- compilation of modules that directly consume a module whose **public API** changed.

Anything else is deliberately not run. A change inside a function body, in a
private member, in a test source or in an XML resource cannot break consumers.

## Architecture

Three pieces, kept deliberately separate:

| File | Responsibility | Depends on IDE |
|---|---|---|
| `ChangeAnalyzer` | what changed in git, and whether public API was touched | no |
| `TaskPlanner` | which Gradle tasks to run | no |
| `ModuleGraph` | module graph from the IDE project model | yes |

`ChangeAnalyzer` and `TaskPlanner` are pure and fully unit tested. Keep them
that way: if you need a message for the user, return data and let the action
format it. An earlier version formatted strings inside `TaskPlanner`, which
quietly made pure logic depend on a running IDE and broke four tests.

The module graph is read from the IDE model, never by parsing build scripts.
That is what makes the plugin work with Kotlin and Groovy builds, custom
dependency DSLs, and composite builds.

## Rules

- No dependency injection framework. The platform is the container:
  `@Service(Level.APP/PROJECT)`. External DI adds classloader conflicts and weight.
- No comments in production code. Names and structure carry the meaning.
- No hardcoded project names, paths, branches or module lists. Anything
  environment-specific is either detected or configurable.
- Long work goes off the EDT. Actions use `ActionUpdateThread.BGT`.
- Recomputation is event-driven, never polled: a VFS change schedules a debounced
  recount. Do not add timers.

## Before you push

```bash
./gradlew test          # unit tests
./gradlew koverXmlReport
./gradlew buildPlugin
./gradlew verifyPlugin  # IDE compatibility
./gradlew pitest        # mutation score, slow
```

All of the above run in CI on every push.

## Mutation testing

Surviving mutants are triaged, not chased. Most survivors are
`RemoveConditionalMutator` hits on null checks the Kotlin compiler generates:
they cannot be killed by a test because behaviour does not change. Never weaken
production code or add an assertion-free test to raise the score. Add a test
only when a survivor reveals a real gap in the contract.

## Things that are easy to get wrong

- Module names inside a composite build are flat and hyphenated. Do not rebuild
  a filesystem path from a module name — `app-integration` is one directory, not
  `app/integration`.
- Android modules and JVM modules have different task names: `testDebugUnitTest`
  versus `test`, `compileDebugUnitTestKotlin` versus `compileTestKotlin`.
- The plugin id is permanent. Changing it breaks updates for every existing user.

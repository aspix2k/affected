# AGENTS.md

Read [CONTRIBUTING.md](CONTRIBUTING.md) first — architecture, conventions and
commands live there. This file covers what tends to go wrong.

## Traps

Module names inside a composite build are flat and hyphenated. Never rebuild a
filesystem path from a module name: `ui-shell` is one directory, not
`ui/shell`.

An imported Gradle id may begin with the included-build identity. When tasks run
against that build's linked root, remove the identity and keep the remaining
project path. Source-set suffixes are metadata, but projects legally named
`main` or `test` must remain intact.

Android and JVM modules have different task names. `test` does not accept
`--tests` in an Android module; the task is `testDebugUnitTest`.

Paths must survive Windows. Compare and store paths through
`invariantSeparatorsPath`, never by gluing separators yourself.

The plugin id is permanent. Changing it breaks updates for every existing user.

Kotlin nests block comments. A glob like `packages/*` inside a KDoc opens an
inner comment, and the next `*/` closes only that one — the rest of the file
silently becomes comment. The symptom is an unresolved reference to a function
that is plainly there.

Formatting belongs to the action, not to the planner. An earlier version built
user-facing strings inside `TaskPlanner`, which quietly made pure logic depend on
a running IDE and broke four tests.

## Mutation testing

Surviving mutants are triaged, not chased. Most are `RemoveConditionalMutator`
hits on null checks the Kotlin compiler generates: no test can kill them because
behaviour does not change. Never weaken production code or add an
assertion-free test to raise the score. Add a test only when a survivor reveals a
real gap in the contract.

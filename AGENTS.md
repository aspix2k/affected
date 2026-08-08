# AGENTS.md

Read [CONTRIBUTING.md](CONTRIBUTING.md) first — architecture, conventions and
commands live there. This file covers what tends to go wrong.

## Traps

Module names inside a composite build are flat and hyphenated. Never rebuild a
filesystem path from a module name: `app-integration` is one directory, not
`app/integration`.

Android and JVM modules have different task names. `test` does not accept
`--tests` in an Android module; the task is `testDebugUnitTest`.

Paths must survive Windows. Compare and store paths through
`invariantSeparatorsPath`, never by gluing separators yourself.

The plugin id is permanent. Changing it breaks updates for every existing user.

Formatting belongs to the action, not to the planner. An earlier version built
user-facing strings inside `TaskPlanner`, which quietly made pure logic depend on
a running IDE and broke four tests.

## Mutation testing

Surviving mutants are triaged, not chased. Most are `RemoveConditionalMutator`
hits on null checks the Kotlin compiler generates: no test can kill them because
behaviour does not change. Never weaken production code or add an
assertion-free test to raise the score. Add a test only when a survivor reveals a
real gap in the contract.

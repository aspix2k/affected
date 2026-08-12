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

Gradle execution metadata may be incomplete immediately after project import.
Recover an included build through its linked composite root and build identity,
but keep a separately linked nested build independent. Compatible modules from
one composite root must still produce one Run session.

Android and JVM modules have different task names. `test` does not accept
`--tests` in an Android module; the task is `testDebugUnitTest`. Imported tasks
may be incomplete, so the module's Android manifest is also authoritative.

Paths must survive Windows. Compare and store paths through
`invariantSeparatorsPath`, never by gluing separators yourself.

CLI adapters get one IDE Run tab per build-system root. Never loop over
`CommandRunner`; compose raw-argument `CliCommand`s and let
`SequentialProcessHandler` run them fail-fast inside one session.

A detected CLI project must not become an empty or partial plan because a tool,
parser, cache input or planned module is missing. Widen to the runnable root
module, keep discovery bounded, and cache only a content fingerprint that covers
the complete discovered graph.

Every CLI adapter change must keep its command contract test and the matching
native project under `conformance/cli-fixtures` green. Parser-only or mocked
proof is not sufficient for a release.

Keep README behavior and support claims current in every user-visible change,
but keep it a concise landing page. Put implementation detail in CONTRIBUTING
or a focused document instead of expanding README.

MCP tools must read the current `AffectedState` snapshot and start work through
the same exclusive run lease as the toolbar. Never recompute a torn plan on
demand, and never stop IDE Run processes that Affected did not start.

Keep Gradle, Kotlin, the IntelliJ Platform, libraries and GitHub Actions on the
latest stable versions compatible with the supported product matrix. A version
may stay behind only for a verified compatibility reason recorded in the
relevant changelog or issue.

Do not split the pull-request plugin job back into multiple Gradle invocations,
and do not start exact-impact from README-only edits. The required `verify`
check must keep aggregating scripts, plugin verification and `buildHealth`.
Required checks must keep a `merge_group` trigger or the merge queue hangs.
Do not add `on.paths` to `ci.yml`, `codeql.yml` or `dependency-review.yml`:
a skipped required check blocks merge. Classify with `scripts/ci_scope.py`
and fail closed on unknown paths.

Never merge a pull request immediately. Open it, then stop. `Queue` enables
`gh pr merge --auto --squash` for same-repository ready PRs. If you must
enqueue by hand, use that same command. Do not merge without `--auto`,
and do not squash from the GitHub UI.

Node exact selection is runner-native and static-graph only. Unknown Jest or
Vitest versions, custom config or transforms, dynamic dependencies, resources,
lockfiles, added, deleted or generated paths, symlinks, missing merge bases and
scan-limit failures must keep the package test script. Exact workspace commands
still belong to one `SequentialProcessHandler`; never create a Run session per
test file.

Pytest exact selection runs after complete collection and filters only whole
test files. It has no persistent baseline. Conftest, external plugins, pytest
configuration, dynamic or external imports, file loading, resources, added or
deleted paths, symlinks, ambiguous ownership and scan limits must keep the full
collected package plan. The packaged adapter must stay byte-identical to
`core/src/main/python/affected_pytest.py`, pass Ruff with zero findings and
preserve one Python Run session.

.NET exact selection happens only after `dotnet build`. Keep the deferred
VSTest command in the same `SequentialProcessHandler`, compare uninstrumented
DLLs, filter only stable fully qualified identities, and promote a baseline only
from a complete unchanged full TRX run. Microsoft Testing Platform, custom
settings, parameterized/shared fixtures and any incomplete evidence stay on the
original project-level test command.

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

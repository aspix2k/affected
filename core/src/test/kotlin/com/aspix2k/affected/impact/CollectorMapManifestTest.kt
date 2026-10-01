package com.aspix2k.affected.impact

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNull

class CollectorMapManifestTest : CollectorMapFixture() {

    private val malformedDependencies: Map<String, String> = run {
        val sha = sha256("alpha-1")
        val name = encode("Alpha")
        val source = encode("file:///classes/")
        mapOf(
            "two parts" to "$name|$source",
            "four parts" to "$name|$source|$sha|$sha",
            "an uppercase hash" to "$name|$source|${sha.uppercase()}",
            "a short hash" to "$name|$source|${sha.dropLast(1)}",
            "a blank class" to "${encode(" ")}|$source|$sha",
            "a noncanonical source" to "$name|cnR|$sha",
            "a class that is not base64" to "!!|$source|$sha",
        )
    }

    @Test
    fun `malformed task manifests invalidate task output`() = assertRejected(
        mapOf(
            "task with an extra line" to transform("task.manifest") { it + "extra=1\n" },
            "task without the all line" to edit("task.manifest", "all=true\n", ""),
            "task with another format" to edit("task.manifest", "format=1", "format=2"),
            "task with an unknown all value" to edit("task.manifest", "all=true", "all=yes"),
            "task with an upper case all value" to edit("task.manifest", "all=true", "all=TRUE"),
            "task with an unprefixed key" to edit("task.manifest", "task=", ""),
            "task with a foreign key prefix" to edit("task.manifest", "task=", "job="),
            "task with an unprefixed runtime" to edit("task.manifest", "runtime=", ""),
            "task with a foreign runtime prefix" to edit("task.manifest", "runtime=", "rt="),
            "task with an unprefixed input" to edit("task.manifest", "input=", ""),
            "task with a foreign input prefix" to edit("task.manifest", "input=", "in="),
            "task with an empty runtime" to edit("task.manifest", "runtime=${encode("runtime-1")}", "runtime="),
            "task with a blank runtime" to edit("task.manifest", encode("runtime-1"), encode(" ")),
            "task with a noncanonical runtime" to edit("task.manifest", encode("runtime-1"), "cnR"),
            "task with a runtime that is not text" to edit("task.manifest", encode("runtime-1"), "wyg"),
            "task with a mismatching directory name" to edit(
                "task.manifest",
                encode("root|:app|testDebugUnitTest"),
                encode("root|:other|test"),
            ),
            "task with carriage returns" to transform("task.manifest") { it.replace("\n", "\r\n") },
            "task without a final newline" to transform("task.manifest") { it.dropLast(1) },
            "empty task" to overwrite("task.manifest", ""),
            "task of one newline" to overwrite("task.manifest", "\n"),
            "missing task" to remove("task.manifest"),
            "task that is a directory" to replaceWithDirectory("task.manifest"),
            "task that is a symlink" to replaceWithSymlink("task.manifest"),
        ),
    )

    @Test
    fun `unexpected directory entries invalidate task output`() = assertRejected(
        mapOf(
            "stray file" to overwrite("stray.txt", "x\n"),
            "stray directory" to replaceWithDirectory("stray"),
            "stray worker lookalike" to replaceWithDirectory("worker-abc"),
            "stray upper case worker" to replaceWithDirectory("worker-${sha256("x").uppercase()}"),
            "stray short worker" to replaceWithDirectory("worker-${sha256("x").dropLast(1)}"),
            "stray long worker" to replaceWithDirectory("worker-${sha256("x")}0"),
            "stray prefixed worker" to replaceWithDirectory("xworker-${sha256("x")}"),
            "stray suffixed worker" to replaceWithDirectory("worker-${sha256("x")}.tmp"),
        ),
    )

    @Test
    fun `malformed catalogs invalidate task output`() = assertRejected(
        mapOf(
            "catalog without artifacts" to overwrite("catalog.manifest", "format=1\n"),
            "catalog with another format" to edit("catalog.manifest", "format=1", "format=2"),
            "catalog with a foreign prefix" to edit("catalog.manifest", "artifact=", "dependency="),
            "catalog with an unprefixed line" to edit("catalog.manifest", "artifact=", ""),
            "catalog that is a directory" to replaceWithDirectory("catalog.manifest"),
            "catalog that is a symlink" to replaceWithSymlink("catalog.manifest"),
            "catalog without a final newline" to transform("catalog.manifest") { it.dropLast(1) },
            "catalog with carriage returns" to transform("catalog.manifest") { it.replace("\n", "\r\n") },
            "empty catalog file" to overwrite("catalog.manifest", ""),
        ) + malformedDependencies.entries.associate { (name, value) ->
            "catalog with $name" to overwrite("catalog.manifest", "format=1\nartifact=$value\n")
        },
    )

    @Test
    fun `malformed expected manifests invalidate task output`() = assertRejected(
        mapOf(
            "expectation without a support line" to overwrite("expected.manifest", "format=1\n"),
            "expectation with another format" to edit("expected.manifest", "format=1", "format=2"),
            "expectation with an unknown support value" to edit("expected.manifest", "supported=true", "supported=yes"),
            "expectation with an unprefixed support value" to edit("expected.manifest", "supported=true", "true"),
            "supported expectation without tests" to overwrite("expected.manifest", "format=1\nsupported=true\n"),
            "expectation with duplicate tests" to overwrite(
                "expected.manifest",
                "format=1\nsupported=true\ntest=${encode("AlphaTest")}\ntest=${encode("AlphaTest")}\n",
            ),
            "expectation with a foreign test prefix" to edit("expected.manifest", "test=", "case="),
            "expectation with an unprefixed test" to edit(
                "expected.manifest",
                "test=${encode("AlphaTest")}",
                encode("AlphaTest"),
            ),
            "expectation with a blank test" to edit("expected.manifest", encode("AlphaTest"), encode(" ")),
            "expectation without a final newline" to overwrite(
                "expected.manifest",
                "format=1\nsupported=true\ntest=${encode("BetaTest0")}",
            ),
            "expectation that is a directory" to replaceWithDirectory("expected.manifest"),
            "expectation that is a symlink" to replaceWithSymlink("expected.manifest"),
        ),
    )

    @Test
    fun `malformed started manifests invalidate task output`() = assertRejected(
        mapOf(
            "started with an extra line" to transform(startedPath("worker-1")) { it + "extra=1\n" },
            "started without a worker line" to overwrite(startedPath("worker-1"), "format=1\n"),
            "started with another format" to edit(startedPath("worker-1"), "format=1", "format=2"),
            "started with a foreign prefix" to edit(startedPath("worker-1"), "worker=", "id="),
            "started with an unprefixed worker" to edit(startedPath("worker-1"), "worker=", ""),
            "started with an empty worker" to edit(
                startedPath("worker-1"),
                "worker=${encode("worker-1")}",
                "worker=",
            ),
            "started with a blank worker" to edit(startedPath("worker-1"), encode("worker-1"), encode(" ")),
            "started for another worker" to edit(startedPath("worker-1"), encode("worker-1"), encode("worker-9")),
            "worker directory named for another worker" to together(
                edit(startedPath("worker-1"), encode("worker-1"), encode("worker-9")),
                edit(completedPath("worker-1"), encode("worker-1"), encode("worker-9")),
            ),
            "missing started" to remove(startedPath("worker-1")),
            "started that is a symlink" to replaceWithSymlink(startedPath("worker-1")),
        ),
    )

    @Test
    fun `malformed completion manifests invalidate task output`() = assertRejected(
        mapOf(
            "completion without a support line" to overwrite(
                completedPath("worker-1"),
                "format=1\nworker=${encode("worker-1")}\n",
            ),
            "completion with another format" to edit(completedPath("worker-1"), "format=1", "format=2"),
            "completion for another worker" to edit(completedPath("worker-1"), encode("worker-1"), encode("worker-2")),
            "completion with an unprefixed worker" to edit(completedPath("worker-1"), "worker=", ""),
            "completion with an unknown support value" to edit(
                completedPath("worker-1"),
                "supported=true",
                "supported=yes",
            ),
            "completion with an unprefixed support value" to edit(completedPath("worker-1"), "supported=true", "true"),
            "supported completion without tests" to together(
                overwrite(completedPath("worker-1"), "format=1\nworker=${encode("worker-1")}\nsupported=true\n"),
                remove(mapPath("worker-1", "AlphaTest")),
            ),
            "completion with duplicate tests" to edit(
                completedPath("worker-1"),
                "test=${encode("AlphaTest")}\n",
                "test=${encode("AlphaTest")}\ntest=${encode("AlphaTest")}\n",
            ),
            "completion with a foreign test prefix" to edit(completedPath("worker-1"), "test=", "case="),
            "completion with an unprefixed test" to edit(
                completedPath("worker-1"),
                "test=${encode("AlphaTest")}",
                encode("AlphaTest"),
            ),
            "completion with a blank test" to edit(completedPath("worker-1"), encode("AlphaTest"), encode(" ")),
            "completion listing a test without a map" to edit(
                completedPath("worker-1"),
                encode("AlphaTest"),
                encode("BetaTest"),
            ),
            "completion with a missing map" to remove(mapPath("worker-1", "AlphaTest")),
            "completion with an unlisted map" to overwrite(
                mapPath("worker-1", "OtherTest"),
                "format=1\ntest=${encode("OtherTest")}\n",
            ),
            "completion with a stray file" to overwrite("${workerPath("worker-1")}/stray.txt", "x\n"),
            "completion with a stray directory" to replaceWithDirectory("${workerPath("worker-1")}/stray"),
            "completion that is a directory" to replaceWithDirectory(completedPath("worker-1")),
            "completion that is a symlink" to replaceWithSymlink(completedPath("worker-1")),
        ),
    )

    @Test
    fun `malformed test maps invalidate task output`() = assertRejected(
        mapOf(
            "map without a test line" to overwrite(mapPath("worker-1", "AlphaTest"), "format=1\n"),
            "map with another format" to edit(mapPath("worker-1", "AlphaTest"), "format=1", "format=2"),
            "map for another test" to edit(
                mapPath("worker-1", "AlphaTest"),
                encode("AlphaTest"),
                encode("BetaTest"),
            ),
            "map with an unprefixed test" to edit(mapPath("worker-1", "AlphaTest"), "test=", ""),
            "map with a foreign dependency prefix" to edit(
                mapPath("worker-1", "AlphaTest"),
                "dependency=",
                "artifact=",
            ),
            "map with an unprefixed dependency" to edit(mapPath("worker-1", "AlphaTest"), "dependency=", ""),
            "map with a false unknown marker" to overwrite(
                mapPath("worker-1", "AlphaTest"),
                "format=1\ntest=${encode("AlphaTest")}\nunknown=false\n",
            ),
            "map that is a directory" to replaceWithDirectory(mapPath("worker-1", "AlphaTest")),
            "map that is a symlink" to replaceWithSymlink(mapPath("worker-1", "AlphaTest")),
            "empty map" to overwrite(mapPath("worker-1", "AlphaTest"), ""),
        ) + malformedDependencies.entries.associate { (name, value) ->
            "map with $name" to overwrite(
                mapPath("worker-1", "AlphaTest"),
                "format=1\ntest=${encode("AlphaTest")}\ndependency=$value\n",
            )
        },
    )

    private fun assertRejected(cases: Map<String, (Path) -> Unit>) = withDirectory { root ->
        cases.entries.forEachIndexed { index, (name, corrupt) ->
            val task = task(Files.createDirectory(root.resolve("case-$index")))
            worker(task, "worker-1", "AlphaTest", dependency("Alpha", "alpha-1"))
            corrupt(task)

            assertNull(CollectorMapReader.read(task, "collector-1", "run-1"), name)
        }
    }
}

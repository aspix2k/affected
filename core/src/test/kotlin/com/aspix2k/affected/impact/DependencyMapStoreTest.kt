package com.aspix2k.affected.impact

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DependencyMapStoreTest : CollectorMapFixture() {

    private val alpha = dependency("Alpha", "alpha-1")
    private val beta = dependency("Beta", "beta-1")
    private val taskKey = "root|:app|testDebugUnitTest"
    private val validMap = complete(listOf(alpha, beta), listOf(record(testClass("AlphaTest"), alpha, beta)))
    private val validPayload = listOf(
        "artifact=${dependencyValue(alpha)}",
        "artifact=${dependencyValue(beta)}",
        "record=${encode("AlphaTest")}|${dependencyValue(alpha)};${dependencyValue(beta)}",
    )

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
    fun `complete maps round trip through the atomic store`() = withDirectory { root ->
        val store = DependencyMapStore(root.resolve("store"))

        store.write(validMap)

        assertEquals(validMap, store.read(taskKey))
    }

    @Test
    fun `a map with zero artifacts and unknown records round trips`() = withDirectory { root ->
        val store = DependencyMapStore(root.resolve("store"))
        val noArtifacts = complete(emptyList(), listOf(record(testClass("AlphaTest"))))
        val unknown = complete(
            listOf(alpha),
            listOf(
                record(testClass("AlphaTest"), alpha),
                TestDependencyRecord(testClass("BetaTest"), emptySet(), unknownDependencies = true),
            ),
        )

        store.write(noArtifacts)
        assertEquals(noArtifacts, store.read(taskKey))
        store.write(unknown)
        assertEquals(unknown, store.read(taskKey))
    }

    @Test
    fun `the store writes artifacts records and dependencies in canonical order`() = withDirectory { root ->
        val storeRoot = root.resolve("store")
        val artifacts = listOf(
            dependency("Zeta", "b"),
            ClassDependency(DependencyId("Alpha", "file:///b/"), sha256("aa")),
            ClassDependency(DependencyId("Alpha", "file:///a/"), sha256("ab")),
            dependency("Beta", "z"),
        )
        val map = complete(
            artifacts,
            listOf(
                record(testClass("ZetaTest"), artifacts[0]),
                record(testClass("AlphaTest"), artifacts[0], artifacts[1], artifacts[2], artifacts[3]),
            ),
        )

        DependencyMapStore(storeRoot).write(map)

        val lines = Files.readAllLines(storeRoot.resolve("map-${sha256(taskKey)}.map"))
        val zeta = dependencyValue(artifacts[0])
        val alphaB = dependencyValue(artifacts[1])
        val alphaA = dependencyValue(artifacts[2])
        val betaLast = dependencyValue(artifacts[3])
        assertEquals(
            listOf(
                "artifact=$alphaA",
                "artifact=$alphaB",
                "artifact=$betaLast",
                "artifact=$zeta",
                "record=${encode("AlphaTest")}|$alphaA;$alphaB;$betaLast;$zeta",
                "record=${encode("ZetaTest")}|$zeta",
            ),
            lines.drop(10),
        )
        assertEquals(
            listOf(
                "format=1",
                "schema=$DEPENDENCY_MAP_SCHEMA_VERSION",
                "collector=${encode("collector-1")}",
                "task=${encode(taskKey)}",
                "runtime=${encode("runtime-1")}",
                "input=${encode("input-1")}",
                "run=${encode("run-1")}",
                "artifacts=4",
                "records=2",
            ),
            lines.take(9),
        )
    }

    @Test
    fun `writing replaces the previous map and leaves no temporary file`() = withDirectory { root ->
        val storeRoot = root.resolve("store")
        val store = DependencyMapStore(storeRoot)
        store.write(validMap)
        val replacement = complete(listOf(beta), listOf(record(testClass("BetaTest"), beta)))

        store.write(replacement)

        assertEquals(replacement, store.read(taskKey))
        Files.list(storeRoot).use { stream ->
            assertEquals(listOf("map-${sha256(taskKey)}.map"), stream.map { it.fileName.toString() }.toList())
        }
    }

    @Test
    fun `the store refuses to write an invalid map`() = withDirectory { root ->
        val store = DependencyMapStore(root.resolve("store"))
        val duplicated = listOf(record(testClass("AlphaTest"), alpha), record(testClass("AlphaTest")))

        assertFailsWith<IllegalArgumentException> { store.write(validMap.copy(completedRunId = " ")) }
        assertFailsWith<IllegalArgumentException> { store.write(validMap.copy(records = emptyList())) }
        assertFailsWith<IllegalArgumentException> { store.write(validMap.copy(records = duplicated)) }
    }

    @Test
    fun `the store refuses to replace a target that is not a regular file`() = withDirectory { root ->
        val storeRoot = Files.createDirectory(root.resolve("store"))
        val store = DependencyMapStore(storeRoot)
        val target = storeRoot.resolve("map-${sha256(taskKey)}.map")

        Files.createDirectory(target)
        assertFailsWith<IllegalArgumentException> { store.write(validMap) }
        Files.delete(target)
        val real = Files.writeString(root.resolve("real.map"), "x\n")
        Files.createSymbolicLink(target, real)
        assertFailsWith<IllegalArgumentException> { store.write(validMap) }
        assertEquals("x\n", Files.readString(real))
    }

    @Test
    fun `a symlinked or unusable store root is neither read nor written`() = withDirectory { root ->
        val realRoot = root.resolve("real")
        DependencyMapStore(realRoot).write(validMap)
        val link = Files.createSymbolicLink(root.resolve("link"), realRoot)

        assertNull(DependencyMapStore(link).read(taskKey))
        assertFailsWith<IllegalArgumentException> { DependencyMapStore(link).write(validMap) }

        withPermissions(realRoot, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE)) {
            assertNull(DependencyMapStore(realRoot).read(taskKey))
            assertFailsWith<Exception> { DependencyMapStore(realRoot).write(validMap) }
        }
        withPermissions(realRoot, setOf(PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)) {
            assertNull(DependencyMapStore(realRoot).read(taskKey))
        }
        assertEquals(validMap, DependencyMapStore(realRoot).read(taskKey))
    }

    @Test
    fun `the store ignores a blank key a missing file and a non file`() = withDirectory { root ->
        val storeRoot = root.resolve("store")
        val store = DependencyMapStore(storeRoot)
        store.write(validMap)

        assertNull(store.read(""))
        assertNull(store.read(" "))
        assertNull(store.read("root|:other|test"))
        val target = storeRoot.resolve("map-${sha256(taskKey)}.map")
        val copy = Files.copy(target, root.resolve("copy.map"))
        Files.delete(target)
        Files.createSymbolicLink(target, copy)
        assertNull(store.read(taskKey))
        Files.delete(target)
        Files.createDirectory(target)
        assertNull(store.read(taskKey))
    }

    @Test
    fun `a stored map is read back from a hand written file`() = withDirectory { root ->
        assertEquals(validMap, readStored(root, storeText(validPayload)))
    }

    @Test
    fun `a stored record may list no dependencies or unknown ones`() = withDirectory { root ->
        val map = complete(
            listOf(alpha),
            listOf(
                record(testClass("AlphaTest"), alpha),
                record(testClass("EmptyTest")),
                TestDependencyRecord(testClass("UnknownTest"), emptySet(), unknownDependencies = true),
            ),
        )
        val payload = listOf(
            "artifact=${dependencyValue(alpha)}",
            "record=${encode("AlphaTest")}|${dependencyValue(alpha)}",
            "record=${encode("EmptyTest")}|",
            "record=${encode("UnknownTest")}|*",
        )

        assertEquals(map, readStored(root, storeText(payload)))
    }

    @Test
    fun `a stored map with a malformed header is ignored`() = assertIgnored(headerCases())

    @Test
    fun `a stored map with a malformed payload is ignored`() = assertIgnored(payloadCases())

    private fun headerCases(): Map<String, List<String>> {
        val checksum = sha256(validPayload.joinToString("\n", postfix = "\n"))
        return mapOf(
            "nine lines" to storeText(validPayload).take(9),
            "empty payload" to storeText(emptyList()),
            "another format" to storeText(validPayload, 0 to "format=2"),
            "unprefixed schema" to storeText(validPayload, 1 to "$DEPENDENCY_MAP_SCHEMA_VERSION"),
            "foreign schema prefix" to storeText(validPayload, 1 to "version=$DEPENDENCY_MAP_SCHEMA_VERSION"),
            "textual schema" to storeText(validPayload, 1 to "schema=five"),
            "zero schema" to storeText(validPayload, 1 to "schema=0"),
            "negative schema" to storeText(validPayload, 1 to "schema=-5"),
            "old schema" to storeText(validPayload, 1 to "schema=${DEPENDENCY_MAP_SCHEMA_VERSION - 1}"),
            "unprefixed collector" to storeText(validPayload, 2 to encode("collector-1")),
            "blank collector" to storeText(validPayload, 2 to "collector=${encode(" ")}"),
            "empty collector" to storeText(validPayload, 2 to "collector="),
            "unprefixed task" to storeText(validPayload, 3 to encode(taskKey)),
            "other task" to storeText(validPayload, 3 to "task=${encode("root|:other|test")}"),
            "unprefixed runtime" to storeText(validPayload, 4 to encode("runtime-1")),
            "blank runtime" to storeText(validPayload, 4 to "runtime=${encode(" ")}"),
            "unprefixed input" to storeText(validPayload, 5 to encode("input-1")),
            "blank input" to storeText(validPayload, 5 to "input=${encode(" ")}"),
            "unprefixed run" to storeText(validPayload, 6 to encode("run-1")),
            "blank run" to storeText(validPayload, 6 to "run=${encode(" ")}"),
            "noncanonical run" to storeText(validPayload, 6 to "run=cnR"),
            "unprefixed artifact count" to storeText(validPayload, 7 to "2"),
            "textual artifact count" to storeText(validPayload, 7 to "artifacts=two"),
            "negative artifact count" to storeText(validPayload, 7 to "artifacts=-2"),
            "short artifact count" to storeText(validPayload, 7 to "artifacts=1"),
            "long artifact count" to storeText(validPayload, 7 to "artifacts=3"),
            "unprefixed record count" to storeText(validPayload, 8 to "1"),
            "textual record count" to storeText(validPayload, 8 to "records=one"),
            "negative record count" to storeText(validPayload, 8 to "records=-1"),
            "short record count" to storeText(validPayload, 8 to "records=0"),
            "long record count" to storeText(validPayload, 8 to "records=2"),
            "unprefixed checksum" to storeText(validPayload, 9 to checksum),
            "uppercase checksum" to storeText(validPayload, 9 to "checksum=${checksum.uppercase()}"),
            "short checksum" to storeText(validPayload, 9 to "checksum=${checksum.dropLast(1)}"),
            "empty checksum" to storeText(validPayload, 9 to "checksum="),
            "wrong checksum" to storeText(validPayload, 9 to "checksum=${sha256("x")}"),
        )
    }

    private fun payloadCases(): Map<String, List<String>> {
        val artifacts = validPayload.dropLast(1)
        val record = validPayload.last()
        val stale = dependencyValue(dependency("Alpha", "stale"))
        val test = encode("AlphaTest")
        return mapOf(
            "unknown payload line" to storeText(validPayload + "other=1"),
            "unprefixed payload line" to storeText(artifacts + record.removePrefix("record="), 8 to "records=1"),
            "record without a separator" to storeText(artifacts + "record=$test"),
            "record without a test" to storeText(artifacts + "record=|${dependencyValue(alpha)}"),
            "record with a blank test" to storeText(artifacts + "record=${encode(" ")}|${dependencyValue(alpha)}"),
            "record with duplicate dependencies" to storeText(
                artifacts + "record=$test|${dependencyValue(alpha)};${dependencyValue(alpha)}",
            ),
            "record with an unknown marker among dependencies" to storeText(
                artifacts + "record=$test|*;${dependencyValue(alpha)}",
            ),
            "record with a stale dependency" to storeText(artifacts + "record=$test|$stale"),
            "records with a duplicated test" to storeText(validPayload + record),
            "no records" to storeText(artifacts),
            "duplicate artifacts" to storeText(artifacts + validPayload.first() + record),
        ) + malformedDependencies.entries.associate { (name, value) ->
            "artifact with $name" to storeText(listOf("artifact=$value") + validPayload.drop(1))
        } + malformedDependencies.entries.associate { (name, value) ->
            "record dependency with $name" to storeText(artifacts + "record=$test|$value")
        }
    }

    private fun assertIgnored(cases: Map<String, List<String>>) = withDirectory { root ->
        cases.entries.forEachIndexed { index, (name, lines) ->
            val storeRoot = Files.createDirectory(root.resolve("store-$index"))
            Files.writeString(storeRoot.resolve("map-${sha256(taskKey)}.map"), lines.joinToString("\n", postfix = "\n"))

            assertNull(DependencyMapStore(storeRoot).read(taskKey), name)
        }
    }

    private fun readStored(root: Path, lines: List<String>): CompleteDependencyMap {
        val storeRoot = Files.createDirectory(root.resolve("stored"))
        Files.writeString(storeRoot.resolve("map-${sha256(taskKey)}.map"), lines.joinToString("\n", postfix = "\n"))
        return assertNotNull(DependencyMapStore(storeRoot).read(taskKey))
    }

    private fun storeText(payload: List<String>, vararg overrides: Pair<Int, String>): List<String> {
        val header = mutableListOf(
            "format=1",
            "schema=$DEPENDENCY_MAP_SCHEMA_VERSION",
            "collector=${encode("collector-1")}",
            "task=${encode(taskKey)}",
            "runtime=${encode("runtime-1")}",
            "input=${encode("input-1")}",
            "run=${encode("run-1")}",
            "artifacts=${payload.count { it.startsWith("artifact=") }}",
            "records=${payload.count { it.startsWith("record=") }}",
            "checksum=${sha256(payload.joinToString("\n", postfix = "\n"))}",
        )
        overrides.forEach { (index, line) -> header[index] = line }
        return header + payload
    }
}

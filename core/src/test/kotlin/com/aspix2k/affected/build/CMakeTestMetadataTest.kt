package com.aspix2k.affected.build

import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CMakeTestMetadataTest {

    @Test
    fun `reads owned codemodel and maps exact CTest names to linked targets`() {
        val fixture = metadataFixture()
        val snapshot = snapshot(fixture)
        assertNotNull(snapshot)
        assertEquals(true, hasCMakeCodemodelReply(fixture.build))
        val changed = fixture.root.resolve("src/alpha.c").toString()

        assertEquals(
            CMakeTestSelection.Exact(listOf("affected_alpha")),
            selectCMakeTests(
                fixture.root,
                snapshot,
                snapshot,
                BuildChanges(listOf(changed), setOf(changed), comparedToBase = true),
            ),
        )
    }

    @Test
    fun `interface sources cannot hide a second affected target`() {
        val fixture = metadataFixture(
            targetTransform = { name, json ->
                when (name) {
                    "alpha-lib" -> json.replace(
                        "\"artifacts\"",
                        "\"interfaceSources\":[{\"path\":\"src/shared.c\"}],\"artifacts\"",
                    )
                    "prefix-test" -> json.replace("tests/alpha_extended_test.c", "src/shared.c")
                    else -> json
                }
            },
        )
        val shared = fixture.root.resolve("src/shared.c").also { it.writeText("int shared(void) { return 1; }") }
        val snapshot = snapshot(fixture)
        assertNotNull(snapshot)

        assertEquals(
            CMakeTestSelection.Exact(listOf("affected_alpha", "affected_alpha_extended")),
            selectCMakeTests(
                fixture.root,
                snapshot,
                snapshot,
                BuildChanges(listOf(shared.toString()), setOf(shared.toString()), comparedToBase = true),
            ),
        )
    }

    @Test
    fun `metadata identity changes with the CTest command`() {
        val fixture = metadataFixture()
        val first = snapshot(fixture)
        val changed = fixture.ctest.replace("--alpha", "--changed")
        val second = snapshot(fixture, changed)
        fixture.root.resolve("CMakeLists.txt").writeText("changed CMake input")
        val configChanged = snapshot(fixture)
        fixture.build.resolve("CMakeCache.txt").writeText("CMAKE_GENERATOR:INTERNAL=Unix Makefiles\n")
        val cacheChanged = snapshot(fixture)

        assertNotNull(first)
        assertNotNull(second)
        assertNotNull(configChanged)
        assertNotNull(cacheChanged)
        assertNotEquals(first.fingerprint, second.fingerprint)
        assertNotEquals(first.fingerprint, configChanged.fingerprint)
        assertNotEquals(first.fingerprint, cacheChanged.fingerprint)
    }

    @Test
    fun `rejects multi config generated fixture resource and duplicate artifact metadata`() {
        val multiConfig =
            metadataFixture(indexTransform = { it.replace("\"multiConfig\":false", "\"multiConfig\":true") })
        assertNull(snapshot(multiConfig))

        val generated = metadataFixture(
            ctestTransform = { json -> json.replace("${'$'}ROOT/CMakeLists.txt", "${'$'}BUILD/generated.cmake") },
        )
        assertNull(snapshot(generated))

        val fixture = metadataFixture(
            ctestTransform = { json -> json.replace("\"properties\":[]", FIXTURE_PROPERTY, ignoreCase = false) },
        )
        assertNull(snapshot(fixture))

        val resource = metadataFixture(
            ctestTransform = { json -> json.replace("\"properties\":[]", RESOURCE_PROPERTY, ignoreCase = false) },
        )
        assertNull(snapshot(resource))

        val environment = metadataFixture(
            ctestTransform = { json -> json.replace("\"properties\":[]", ENVIRONMENT_PROPERTY, ignoreCase = false) },
        )
        assertNull(snapshot(environment))

        val duplicate = metadataFixture(
            targetTransform = { name, json ->
                if (name == "prefix-test") json.replace("prefix-test", "alpha-test") else json
            },
        )
        assertNull(snapshot(duplicate))

        val staleTarget = metadataFixture(
            targetTransform = { _, json -> json.replace("\"codemodelVersion\":{\"major\":2,\"minor\":9},", "") },
        )
        assertNull(snapshot(staleTarget))
    }

    @Test
    fun `rejects an unowned or stale client reply`() {
        val fixture = metadataFixture(
            indexTransform = { it.replace("client-affected", "client-other") },
        )
        assertNull(snapshot(fixture))
    }

    @Test
    fun `query and reply directories cannot escape through symlinks`() {
        val root = createTempDirectory("cmake-query-link")
        val build = root.resolve("build").createDirectories()
        val outside = createTempDirectory("cmake-query-outside")
        assumeTrue(runCatching { Files.createSymbolicLink(build.resolve(".cmake"), outside) }.isSuccess)

        assertFalse(requestCMakeCodemodel(build))
        assertFalse(Files.exists(outside.resolve("api")))

        val fixture = metadataFixture()
        val reply = fixture.build.resolve(".cmake/api/v1/reply")
        val externalReply = createTempDirectory("cmake-reply-outside").resolve("reply")
        Files.move(reply, externalReply)
        assumeTrue(runCatching { Files.createSymbolicLink(reply, externalReply) }.isSuccess)
        assertFalse(hasCMakeCodemodelReply(fixture.build))
        assertNull(snapshot(fixture))
    }

    @Test
    fun `test registration cannot enter the build tree through a source symlink`() {
        val fixture = metadataFixture()
        val generated = fixture.build.resolve("generated.cmake").also { it.writeText("generated") }
        val link = fixture.root.resolve("linked.cmake")
        assumeTrue(runCatching { Files.createSymbolicLink(link, generated) }.isSuccess)
        val ctest = fixture.ctest.replace(
            fixture.root.resolve("CMakeLists.txt").portablePath(),
            link.portablePath(),
        )

        assertNull(snapshot(fixture, ctest))
    }

    @Test
    fun `rejects CTest older than tests-from-file support`() {
        val fixture = metadataFixture()

        assertNull(snapshot(fixture, version = "ctest version 3.28.6"))
        assertNotNull(snapshot(fixture, version = "ctest version 3.29.0"))
    }

    private fun snapshot(
        fixture: MetadataFixture,
        ctest: String = fixture.ctest,
        version: String = "ctest version 4.4.2",
    ): CMakeTestSnapshot? = readCMakeTestSnapshot(fixture.root, fixture.build) { command ->
        if (command == listOf("ctest", "--version")) version else ctest
    }

    private fun metadataFixture(
        indexTransform: (String) -> String = { it },
        targetTransform: (String, String) -> String = { _, value -> value },
        ctestTransform: (String) -> String = { it },
    ): MetadataFixture {
        val root = createTempDirectory("cmake-metadata")
        val build = root.resolve("build").createDirectories()
        build.resolve("CMakeCache.txt").writeText("CMAKE_GENERATOR:INTERNAL=Ninja\n")
        val reply = build.resolve(".cmake/api/v1/reply").createDirectories()
        writeFixtureFiles(root, build)

        val targets = linkedMapOf(
            "alpha-lib" to target(
                "alpha-lib",
                "alpha_lib",
                "SHARED_LIBRARY",
                "src/alpha.c",
                "libalpha.so",
                emptyList(),
            ),
            "alpha-test" to target(
                "alpha-test",
                "alpha_test",
                "EXECUTABLE",
                "tests/alpha_test.c",
                "alpha-test",
                listOf("alpha-lib"),
            ),
            "prefix-test" to target(
                "prefix-test",
                "prefix_test",
                "EXECUTABLE",
                "tests/alpha_extended_test.c",
                "prefix-test",
                emptyList(),
            ),
        )
        targets.forEach { (id, json) ->
            reply.resolve("target-$id.json").writeText(targetTransform(id, json))
        }
        val references = targets.keys.joinToString(",") { id ->
            targetReference(id)
        }
        reply.resolve("codemodel.json").writeText(
            """
            {"kind":"codemodel","version":{"major":2,"minor":9},
             "paths":{"source":"${root.portablePath()}","build":"${build.portablePath()}"},
             "configurations":[{"name":"","targets":[$references],"abstractTargets":[]}]}
            """.trimIndent(),
        )
        reply.resolve("cmake-files.json").writeText(
            """
            {"kind":"cmakeFiles","version":{"major":1,"minor":1},
             "paths":{"source":"${root.portablePath()}","build":"${build.portablePath()}"},
             "inputs":[{"path":"CMakeLists.txt"}]}
            """.trimIndent(),
        )
        writeIndex(reply, indexTransform)

        val ctest =
            """
            {"version":{"major":1,"minor":0},
             "backtraceGraph":{"commands":["add_test"],"files":["${'$'}ROOT/CMakeLists.txt"],
               "nodes":[{"file":0,"line":1,"command":0}]},
             "tests":[
               {"name":"affected_alpha",
                "command":["${build.resolve("alpha-test").portablePath()}","--alpha"],
                "properties":[],"backtrace":0},
               {"name":"affected_alpha_extended",
                "command":["${build.resolve("prefix-test").portablePath()}"],
                "properties":[],"backtrace":0}
            ]}
            """.trimIndent()
        return MetadataFixture(
            root,
            build,
            ctestTransform(ctest)
                .replace("${'$'}ROOT", root.portablePath())
                .replace("${'$'}BUILD", build.portablePath()),
        )
    }

    private fun writeFixtureFiles(root: Path, build: Path) {
        listOf(
            "CMakeLists.txt",
            "src/alpha.c",
            "tests/alpha_test.c",
            "tests/alpha_extended_test.c",
        ).forEach { relative ->
            root.resolve(relative).also { Files.createDirectories(it.parent) }.writeText(relative)
        }
        listOf("libalpha.so", "alpha-test", "prefix-test").forEach { build.resolve(it).writeText(it) }
    }

    private fun writeIndex(reply: Path, transform: (String) -> String) {
        val index =
            """
            {"cmake":{"version":{"major":4,"minor":1,"string":"4.1.3"},
                      "generator":{"name":"Ninja","multiConfig":false}},
             "reply":{"client-affected":{
               "codemodel-v2":{"kind":"codemodel","version":{"major":2,"minor":9},
                 "jsonFile":"codemodel.json"},
               "cmakeFiles-v1":{"kind":"cmakeFiles","version":{"major":1,"minor":1},
                 "jsonFile":"cmake-files.json"}}}}
            """.trimIndent()
        reply.resolve("index-0001.json").writeText(transform(index))
    }

    private fun target(
        id: String,
        name: String,
        type: String,
        source: String,
        artifact: String,
        dependencies: List<String>,
    ): String {
        val links = dependencies.joinToString(",") { "{\"id\":\"$it\"}" }
        return """
            {"id":"$id","name":"$name","type":"$type",
             "codemodelVersion":{"major":2,"minor":9},
             "sources":[{"path":"$source"}],"artifacts":[{"path":"$artifact"}],
             "dependencies":[$links],"linkLibraries":[$links]}
        """.trimIndent()
    }

    private fun targetReference(id: String): String {
        val name = if (id == "alpha-lib") "alpha_lib" else id.replace('-', '_')
        return "{\"id\":\"$id\",\"name\":\"$name\",\"jsonFile\":\"target-$id.json\"}"
    }

    private companion object {
        const val FIXTURE_PROPERTY =
            "\"properties\":[{\"name\":\"FIXTURES_REQUIRED\",\"value\":[\"db\"]}]"
        const val RESOURCE_PROPERTY =
            "\"properties\":[{\"name\":\"RESOURCE_LOCK\",\"value\":[\"db\"]}]"
        const val ENVIRONMENT_PROPERTY =
            "\"properties\":[{\"name\":\"ENVIRONMENT\",\"value\":[\"TOOL=/tmp/tool\"]}]"
    }

    private data class MetadataFixture(val root: Path, val build: Path, val ctest: String)
}

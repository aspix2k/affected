package com.aspix2k.affected.build

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal fun readCMakeTestSnapshot(
    root: Path,
    build: Path,
    capture: (List<String>) -> String?,
): CMakeTestSnapshot? = runCatching {
    CMakeMetadataReader(root, build, capture).read()
}.getOrNull()

internal fun requestCMakeCodemodel(build: Path): Boolean = runCatching {
    val root = build.secureDirectory()
    val query = secureChildDirectory(root, ".cmake/api/v1/query/$CMAKE_CLIENT", create = true)
    CMAKE_QUERIES.forEach { name ->
        val target = query.resolve(name)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) Files.createFile(target)
        require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
        require(!Files.isSymbolicLink(target))
    }
    true
}.getOrDefault(false)

internal fun hasCMakeCodemodelReply(build: Path): Boolean = runCatching {
    val root = build.secureDirectory()
    val reply = secureChildDirectory(root, ".cmake/api/v1/reply", create = false)
    val indexes = Files.list(reply).use { stream ->
        stream.limit(MAX_REPLY_FILES + 1L).toList().also { require(it.size <= MAX_REPLY_FILES) }
    }.filter { path ->
        path.fileName.toString().startsWith("index-") && path.fileName.toString().endsWith(".json") &&
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
    }.sortedBy { it.fileName.toString() }
    val index = indexes.lastOrNull() ?: return false
    require(Files.size(index) in 1..MAX_REPLY_FILE_SIZE)
    val parsed = JsonParser.parseString(Files.readString(index, StandardCharsets.UTF_8)).asJsonObject
    replyResponseExists(parsed, reply, CODEMODEL_QUERY, "codemodel") &&
        replyResponseExists(parsed, reply, CMAKE_FILES_QUERY, "cmakeFiles")
}.getOrDefault(false)

private class CMakeMetadataReader(
    requestedRoot: Path,
    requestedBuild: Path,
    private val capture: (List<String>) -> String?,
) {

    private val requestedRoot = requestedRoot.toAbsolutePath().normalize()
    private val requestedBuild = requestedBuild.toAbsolutePath().normalize()
    private var sourceAlias = this.requestedRoot
    private var buildAlias = this.requestedBuild
    private val root = requestedRoot.secureDirectory()
    private val build = requestedBuild.secureDirectory()
    private val reply = secureChildDirectory(build, ".cmake/api/v1/reply", create = false)
    private var bytesRead = 0L
    private var configurationBytes = 0L

    fun read(): CMakeTestSnapshot {
        val model = readModel()
        val ctest = readTests(model.parsedTargets, model.targets.keys)
        val fingerprint = sha256(model.identity + "ctest=${ctest.raw}\n")
        return CMakeTestSnapshot(fingerprint, model.targets, ctest.tests, ctest.allTests)
    }

    private fun readModel(): ModelData {
        val index = readJson(latestIndex())
        val cmake = index.objectValue("cmake")
        val cmakeVersion = cmake.objectValue("version")
        require(cmakeVersion.intValue("major") >= MIN_CMAKE_MAJOR)
        require(!cmake.objectValue("generator").booleanValue("multiConfig", false))

        val codemodelReference = index.objectValue("reply")
            .objectValue(CMAKE_CLIENT)
            .objectValue(CODEMODEL_QUERY)
        require(codemodelReference.stringValue("kind") == "codemodel")
        val referenceVersion = codemodelReference.objectValue("version").schemaVersion()
        require(referenceVersion.major == CODEMODEL_MAJOR)
        require(referenceVersion.minor in MIN_CODEMODEL_MINOR..MAX_CODEMODEL_MINOR)
        val codemodel = readJson(replyFile(codemodelReference.stringValue("jsonFile")))
        require(codemodel.stringValue("kind") == "codemodel")
        val codemodelVersion = codemodel.objectValue("version").schemaVersion()
        require(codemodelVersion == referenceVersion)
        val paths = codemodel.objectValue("paths")
        sourceAlias = Path.of(paths.stringValue("source")).toAbsolutePath().normalize()
        buildAlias = Path.of(paths.stringValue("build")).toAbsolutePath().normalize()
        require(sourceAlias.toRealPath() == root)
        require(buildAlias.toRealPath() == build)
        val configurationIdentity = readConfigurationIdentity(index)
        val configuration = codemodel.arrayValue("configurations").single().asJsonObject
        val targetReferences = JsonArray().apply {
            addAll(configuration.arrayValue("targets"))
            addAll(configuration.arrayValue("abstractTargets", required = false))
        }
        require(targetReferences.size() in 1..MAX_TARGETS)

        val parsedTargets = targetReferences.map { referenceElement ->
            val reference = referenceElement.asJsonObject
            parseTarget(
                reference,
                readJson(replyFile(reference.stringValue("jsonFile"))),
                codemodelVersion,
            )
        }
        require(parsedTargets.map(TargetData::id).toSet().size == parsedTargets.size)
        require(parsedTargets.map(TargetData::name).toSet().size == parsedTargets.size)
        require(parsedTargets.any { it.sources.isNotEmpty() })
        val targetIds = parsedTargets.mapTo(HashSet(), TargetData::id)
        require(parsedTargets.all { target -> target.dependencies.all(targetIds::contains) })
        val targetsById = parsedTargets.associateBy(TargetData::id)
        val targets = parsedTargets.associate { target ->
            target.id to CMakeImpactTarget(
                target.id,
                target.name,
                target.sources,
                dependencyClosure(target.id, targetsById) - target.id,
            )
        }
        val identity = buildString {
            append("cmake=").append(cmakeVersion.stringValue("string")).append('\n')
            append("generator=").append(cmake.objectValue("generator").stringValue("name")).append('\n')
            append("configuration=").append(configuration.stringValue("name", "")).append('\n')
            append(configurationIdentity)
            append("cache=").append(readText(build.resolve("CMakeCache.txt"), MAX_CMAKE_CACHE_SIZE)).append('\n')
            parsedTargets.sortedBy(TargetData::id).forEach { target ->
                append("target=").append(target.raw).append('\n')
            }
        }
        return ModelData(parsedTargets, targets, identity)
    }

    private fun readConfigurationIdentity(index: JsonObject): String {
        val reference = index.objectValue("reply")
            .objectValue(CMAKE_CLIENT)
            .objectValue(CMAKE_FILES_QUERY)
        require(reference.stringValue("kind") == "cmakeFiles")
        val referenceVersion = reference.objectValue("version").schemaVersion()
        require(referenceVersion.major == CMAKE_FILES_MAJOR)
        require(referenceVersion.minor == CMAKE_FILES_MINOR)
        val files = readJson(replyFile(reference.stringValue("jsonFile")))
        require(files.stringValue("kind") == "cmakeFiles")
        require(files.objectValue("version").schemaVersion() == referenceVersion)
        val paths = files.objectValue("paths")
        require(Path.of(paths.stringValue("source")).toRealPath() == root)
        require(Path.of(paths.stringValue("build")).toRealPath() == build)
        val inputs = files.arrayValue("inputs")
        require(inputs.size() in 1..MAX_CONFIG_INPUTS)
        val entries = inputs.mapNotNull { inputElement ->
            configurationInput(inputElement.asJsonObject)
        }
        val unique = LinkedHashMap<String, String>()
        entries.forEach { (name, content) ->
            val previous = unique.putIfAbsent(name, content)
            require(previous == null || previous == content)
        }
        require("source:CMakeLists.txt" in unique)
        return buildString {
            unique.toSortedMap().forEach { (name, content) ->
                append("input=").append(name).append('\n')
                append(content).append('\n')
            }
        }
    }

    private fun configurationInput(input: JsonObject): Pair<String, String>? {
        val generated = input.booleanValue("isGenerated", false)
        val value = input.stringValue("path")
        val path = Path.of(value)
        if (generated) {
            val generatedPath = if (path.isAbsolute) path.toRealPath() else root.resolve(path).toRealPath()
            require(generatedPath.startsWith(build.resolve("CMakeFiles")))
            return null
        }
        val resolved = when {
            !path.isAbsolute -> resolveSource(value)
            canonicalPath(path).startsWith(root) -> resolveSource(value)
            else -> path.toRealPath()
        }
        require(!resolved.startsWith(build))
        require(Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS))
        require(!Files.isSymbolicLink(resolved))
        val name = if (resolved.startsWith(root)) {
            "source:${root.relativize(resolved).portablePath()}"
        } else {
            "external:${resolved.portablePath()}"
        }
        return name to readConfigurationFile(resolved)
    }

    private fun readTests(parsedTargets: List<TargetData>, targetIds: Set<String>): CTestData {
        val versionText = capture(listOf("ctest", "--version")) ?: error("CTest version")
        val version = CTEST_VERSION.matchEntire(versionText.lineSequence().first().trim())
            ?: error("CTest version")
        val versionMajor = version.groupValues[1].toInt()
        val versionMinor = version.groupValues[2].toInt()
        require(versionMajor > MIN_CTEST_MAJOR || versionMajor == MIN_CTEST_MAJOR && versionMinor >= MIN_CTEST_MINOR)
        val artifactOwners = artifactOwners(parsedTargets)
        val ctestText = capture(
            listOf("ctest", "--show-only=json-v1", "--test-dir", build.toString()),
        ) ?: error("CTest metadata")
        require(ctestText.toByteArray(StandardCharsets.UTF_8).size <= MAX_CTEST_OUTPUT)
        val ctest = JsonParser.parseString(ctestText).asJsonObject
        require(ctest.objectValue("version").schemaVersion() == CTEST_SCHEMA)
        val backtraces = ctest.objectValue("backtraceGraph")
        val testsJson = ctest.arrayValue("tests")
        require(testsJson.size() in 1..MAX_TESTS)
        val allTests = LinkedHashSet<String>()
        val tests = LinkedHashMap<String, String>()
        testsJson.forEach { testElement ->
            val test = testElement.asJsonObject
            val name = test.stringValue("name")
            require(validCTestName(name))
            require(allTests.add(name))
            val backtrace = sourceBacktrace(test, backtraces)
            require(backtrace.startsWith(root))
            require(!backtrace.startsWith(build))
            require(Files.isRegularFile(backtrace, LinkOption.NOFOLLOW_LINKS))
            require(!Files.isSymbolicLink(backtrace))
            val properties = test.arrayValue("properties", required = false)
                .map(JsonElement::getAsJsonObject)
            require(properties.none { it.stringValue("name") in RESOURCE_PROPERTIES })
            require(properties.none { it.stringValue("name") in FIXTURE_PROPERTIES })
            require(properties.none { it.stringValue("name") in ENVIRONMENT_PROPERTIES })
            properties.filter { it.stringValue("name") == "WORKING_DIRECTORY" }.forEach { property ->
                require(resolveBuild(property.stringValue("value")) == build)
            }
            val owners = test.arrayValue("command")
                .map(JsonElement::getAsString)
                .flatMap(::commandCandidates)
                .flatMap { artifactOwners[pathKey(it)].orEmpty() }
                .distinct()
            require(owners.size == 1 && owners.single() in targetIds)
            require(tests.put(name, owners.single()) == null)
        }
        require(tests.isNotEmpty())
        return CTestData("version=${version.value}\n$ctest", tests, allTests)
    }

    private fun artifactOwners(targets: List<TargetData>): Map<String, Set<String>> {
        val owners = HashMap<String, MutableSet<String>>()
        targets.forEach { target ->
            target.artifacts.forEach { artifact ->
                owners.getOrPut(pathKey(artifact)) { HashSet() } += target.id
            }
        }
        return owners
    }

    private fun parseTarget(reference: JsonObject, target: JsonObject, codemodelVersion: SchemaVersion): TargetData {
        val id = target.stringValue("id")
        val name = target.stringValue("name")
        require(reference.stringValue("id") == id && reference.stringValue("name") == name)
        require(target.objectValue("codemodelVersion").schemaVersion() == codemodelVersion)
        require(target.stringValue("type") in TARGET_TYPES)
        val sourceEntries = target.arrayValue("sources", required = false) +
            target.arrayValue("interfaceSources", required = false)
        val sources = sourceEntries.mapNotNull { element ->
            val source = element.asJsonObject
            if (source.booleanValue("isGenerated", false)) return@mapNotNull null
            val path = resolveSource(source.stringValue("path"))
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
            root.relativize(path).portablePath()
        }.toSet()
        require(sources.size <= MAX_SOURCES)
        val dependencies = DEPENDENCY_FIELDS.flatMapTo(LinkedHashSet()) { field ->
            target.arrayValue(field, required = false).mapNotNull { dependency ->
                dependency.asJsonObject.optionalString("id")
            }
        }
        require(dependencies.size <= MAX_TARGETS)
        val artifacts = target.arrayValue("artifacts", required = false).map { artifact ->
            resolveBuild(artifact.asJsonObject.stringValue("path"))
        }.toSet()
        return TargetData(id, name, sources, dependencies, artifacts, target.toString())
    }

    private fun dependencyClosure(id: String, targets: Map<String, TargetData>): Set<String> {
        val result = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        queue += id
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!result.add(current)) continue
            targets[current]?.dependencies?.filter(targets::containsKey)?.forEach(queue::addLast)
            require(result.size <= MAX_TARGETS)
        }
        return result
    }

    private fun sourceBacktrace(test: JsonObject, graph: JsonObject): Path {
        val files = graph.arrayValue("files")
        val nodes = graph.arrayValue("nodes")
        var index = test.intValue("backtrace")
        repeat(MAX_BACKTRACE_DEPTH) {
            val node = nodes.get(index).asJsonObject
            if (node.has("file")) return resolveSource(files.get(node.intValue("file")).asString)
            index = node.intValue("parent")
        }
        error("CTest backtrace")
    }

    private fun commandCandidates(value: String): List<Path> = runCatching {
        val path = Path.of(value)
        if (path.isAbsolute) {
            listOf(canonicalPath(path))
        } else {
            listOf(
                build.resolve(path).normalize(),
                root.resolve(path).normalize(),
            )
        }
    }.getOrDefault(emptyList())

    private fun resolveSource(value: String): Path {
        val path = Path.of(value)
        val resolved = if (path.isAbsolute) canonicalPath(path) else root.resolve(path).normalize()
        return secureExistingPath(root, resolved)
    }

    private fun resolveBuild(value: String): Path {
        val path = Path.of(value)
        val resolved = if (path.isAbsolute) canonicalPath(path) else build.resolve(path).normalize()
        require(resolved.startsWith(build))
        return resolved
    }

    private fun canonicalPath(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        return when {
            absolute.startsWith(buildAlias) -> build.resolve(buildAlias.relativize(absolute)).normalize()
            absolute.startsWith(sourceAlias) -> root.resolve(sourceAlias.relativize(absolute)).normalize()
            absolute.startsWith(requestedBuild) -> build.resolve(requestedBuild.relativize(absolute)).normalize()
            absolute.startsWith(requestedRoot) -> root.resolve(requestedRoot.relativize(absolute)).normalize()
            else -> absolute
        }
    }

    private fun latestIndex(): Path {
        val indexes = Files.list(reply).use { stream ->
            stream.limit(MAX_REPLY_FILES + 1L).toList().also { require(it.size <= MAX_REPLY_FILES) }
        }.filter { path ->
            path.fileName.toString().startsWith("index-") && path.fileName.toString().endsWith(".json")
        }.sortedBy { it.fileName.toString() }
        return indexes.lastOrNull() ?: error("CMake File API index")
    }

    private fun replyFile(name: String): Path {
        require(name.isNotBlank() && '/' !in name && '\\' !in name)
        return reply.resolve(name).normalize().also { require(it.parent == reply) }
    }

    private fun readJson(path: Path): JsonObject {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
        val size = Files.size(path)
        require(size in 1..MAX_REPLY_FILE_SIZE)
        bytesRead += size
        require(bytesRead <= MAX_REPLY_TOTAL_SIZE)
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).asJsonObject
    }

    private fun readText(path: Path, limit: Long): String {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        require(!Files.isSymbolicLink(path))
        require(Files.size(path) in 1..limit)
        return Files.readString(path, StandardCharsets.UTF_8)
    }

    private fun readConfigurationFile(path: Path): String {
        val size = Files.size(path)
        require(size in 1..MAX_CONFIG_FILE_SIZE)
        configurationBytes += size
        require(configurationBytes <= MAX_CONFIG_TOTAL_SIZE)
        return Files.readString(path, StandardCharsets.UTF_8)
    }
}

private data class ModelData(
    val parsedTargets: List<TargetData>,
    val targets: Map<String, CMakeImpactTarget>,
    val identity: String,
)

private data class CTestData(
    val raw: String,
    val tests: Map<String, String>,
    val allTests: Set<String>,
)

private data class SchemaVersion(val major: Int, val minor: Int)

private data class TargetData(
    val id: String,
    val name: String,
    val sources: Set<String>,
    val dependencies: Set<String>,
    val artifacts: Set<Path>,
    val raw: String,
)

private fun JsonObject.objectValue(name: String): JsonObject = get(name).asJsonObject

private fun JsonObject.arrayValue(name: String, required: Boolean = true): JsonArray = when {
    has(name) -> getAsJsonArray(name)
    required -> error(name)
    else -> JsonArray()
}

private fun JsonObject.stringValue(name: String, default: String? = null): String = when {
    has(name) -> get(name).asString
    default != null -> default
    else -> error(name)
}

private fun JsonObject.optionalString(name: String): String? = get(name)?.takeUnless(JsonElement::isJsonNull)?.asString

private fun JsonObject.intValue(name: String): Int = get(name).asInt

private fun JsonObject.booleanValue(name: String, default: Boolean): Boolean =
    get(name)?.takeUnless(JsonElement::isJsonNull)?.asBoolean ?: default

private fun JsonObject.schemaVersion(): SchemaVersion = SchemaVersion(intValue("major"), intValue("minor"))

private fun Path.secureDirectory(): Path {
    val absolute = toAbsolutePath().normalize()
    require(Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(absolute))
    return absolute.toRealPath()
}

private fun secureChildDirectory(root: Path, relative: String, create: Boolean): Path {
    val realRoot = root.toRealPath()
    var current = realRoot
    Path.of(relative).forEach { segment ->
        val next = current.resolve(segment.toString())
        if (create && !Files.exists(next, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(next)
        require(Files.isDirectory(next, LinkOption.NOFOLLOW_LINKS))
        require(!Files.isSymbolicLink(next))
        current = next.toRealPath()
        require(current.startsWith(realRoot))
    }
    return current
}

private fun secureExistingPath(root: Path, requested: Path): Path {
    val normalized = requested.toAbsolutePath().normalize()
    require(normalized.startsWith(root))
    var current = root
    root.relativize(normalized).forEach { segment ->
        current = current.resolve(segment.toString())
        require(!Files.isSymbolicLink(current))
    }
    val real = current.toRealPath()
    require(real.startsWith(root))
    return real
}

private fun replyResponseExists(index: JsonObject, reply: Path, query: String, kind: String): Boolean {
    val reference = index.objectValue("reply").objectValue(CMAKE_CLIENT).objectValue(query)
    require(reference.stringValue("kind") == kind)
    val name = reference.stringValue("jsonFile")
    require(name.isNotBlank() && '/' !in name && '\\' !in name)
    val response = reply.resolve(name).normalize()
    require(response.parent == reply)
    return Files.isRegularFile(response, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(response)
}

private fun pathKey(path: Path): String = path.toAbsolutePath().normalize().portablePath().let {
    if (java.io.File.separatorChar == '\\') it.lowercase() else it
}

private val TARGET_TYPES = setOf(
    "EXECUTABLE",
    "STATIC_LIBRARY",
    "SHARED_LIBRARY",
    "MODULE_LIBRARY",
    "OBJECT_LIBRARY",
    "INTERFACE_LIBRARY",
)
private val DEPENDENCY_FIELDS = setOf(
    "dependencies",
    "linkLibraries",
    "interfaceLinkLibraries",
    "compileDependencies",
    "interfaceCompileDependencies",
    "objectDependencies",
    "orderDependencies",
)
private val FIXTURE_PROPERTIES = setOf("FIXTURES_SETUP", "FIXTURES_REQUIRED", "FIXTURES_CLEANUP")
private val ENVIRONMENT_PROPERTIES = setOf("ENVIRONMENT", "ENVIRONMENT_MODIFICATION")
private val RESOURCE_PROPERTIES = setOf(
    "RESOURCE_GROUPS",
    "RESOURCE_LOCK",
    "REQUIRED_FILES",
    "ATTACHED_FILES",
    "ATTACHED_FILES_ON_FAIL",
    "GENERATED_RESOURCE_SPEC_FILE",
    "DEPENDS",
    "DISABLED",
)
private const val CMAKE_CLIENT = "client-affected"
private const val CODEMODEL_QUERY = "codemodel-v2"
private const val CMAKE_FILES_QUERY = "cmakeFiles-v1"
private val CMAKE_QUERIES = listOf(CODEMODEL_QUERY, CMAKE_FILES_QUERY)
private const val CODEMODEL_MAJOR = 2
private const val CMAKE_FILES_MAJOR = 1
private const val CMAKE_FILES_MINOR = 1
private const val MIN_CMAKE_MAJOR = 4
private const val MIN_CTEST_MAJOR = 3
private const val MIN_CTEST_MINOR = 29
private const val MIN_CODEMODEL_MINOR = 9
private const val MAX_CODEMODEL_MINOR = 11
private const val MAX_TARGETS = 4096
private const val MAX_SOURCES = 65_536
private const val MAX_TESTS = 65_536
private const val MAX_BACKTRACE_DEPTH = 128
private const val MAX_REPLY_FILES = 16_384
private const val MAX_REPLY_FILE_SIZE = 8L * 1024 * 1024
private const val MAX_REPLY_TOTAL_SIZE = 64L * 1024 * 1024
private const val MAX_CTEST_OUTPUT = 16 * 1024 * 1024
private const val MAX_CMAKE_CACHE_SIZE = 8L * 1024 * 1024
private const val MAX_CONFIG_INPUTS = 4096
private const val MAX_CONFIG_FILE_SIZE = 8L * 1024 * 1024
private const val MAX_CONFIG_TOTAL_SIZE = 64L * 1024 * 1024
private val CTEST_SCHEMA = SchemaVersion(1, 0)
private val CTEST_VERSION = Regex("ctest version (\\d+)\\.(\\d+)(?:\\.\\d+)?(?:[-+][^\\s]+)?")

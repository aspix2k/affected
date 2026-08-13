package com.aspix2k.affected.build

import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64

internal data class PhpunitProjectState(
    val identity: String,
    val artifacts: Map<String, String>,
)

internal data class PhpunitTestSnapshot(
    val identity: String,
    val artifacts: Map<String, String>,
    val tests: Map<String, String>,
    val classes: Map<String, String>,
    val dependencies: Map<String, Set<String>>,
)

internal sealed interface PhpunitTestSelection {
    data object Full : PhpunitTestSelection
    data class Exact(val classes: List<String>, val tests: List<String>) : PhpunitTestSelection
}

internal fun selectPhpunitTests(
    root: Path,
    current: PhpunitProjectState?,
    baseline: PhpunitTestSnapshot?,
    changes: BuildChanges,
): PhpunitTestSelection = runCatching {
    require(current != null && baseline != null)
    require(validPhpunitSnapshot(baseline))
    require(current.identity == baseline.identity)
    require(current.artifacts.keys == baseline.artifacts.keys)
    val changed = eligiblePhpunitChanges(root, changes)
    require(changed.isNotEmpty() && changed.all(current.artifacts::containsKey))
    require(current.artifacts.all { (path, hash) -> path in changed || baseline.artifacts[path] == hash })
    val selected = baseline.dependencies
        .filterValues { dependencies -> dependencies.any(changed::contains) }
        .keys
        .sorted()
    require(selected.isNotEmpty())
    if (selected.toSet() == baseline.dependencies.keys) {
        PhpunitTestSelection.Full
    } else {
        PhpunitTestSelection.Exact(
            selected,
            baseline.tests.filterValues(selected.toSet()::contains).keys.sorted(),
        )
    }
}.getOrDefault(PhpunitTestSelection.Full)

private fun eligiblePhpunitChanges(root: Path, changes: BuildChanges): Set<String> = runCatching {
    require(changes.comparedToBase && changes.files.isNotEmpty())
    require(changes.files.toSet() == changes.exactSelectionEligible)
    val requestedRoot = root.toAbsolutePath().normalize()
    require(Files.isDirectory(requestedRoot, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(requestedRoot))
    val realRoot = requestedRoot.toRealPath(LinkOption.NOFOLLOW_LINKS)
    changes.files.mapTo(LinkedHashSet()) { raw ->
        val requested = Path.of(raw).toAbsolutePath().normalize()
        require(Files.isRegularFile(requested, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(requested))
        val real = requested.toRealPath(LinkOption.NOFOLLOW_LINKS)
        require(real.startsWith(realRoot) && real.fileName.toString().endsWith(".php", ignoreCase = true))
        realRoot.relativize(real).portablePath().also { require(validPhpunitPath(it)) }
    }
}.getOrElse { emptySet() }

internal class PhpunitTestBaselineStore(private val root: Path) {

    fun read(): PhpunitTestSnapshot? = runCatching {
        val directory = securePhpunitDirectory(root)
        val path = directory.resolve(PHPUNIT_BASELINE_FILE)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        val lines = readPhpunitLines(path)
        require(lines.size >= PHPUNIT_STORE_HEADER_LINES)
        require(lines[0] == PHPUNIT_STORE_FORMAT)
        require(rawPhpunitValue(lines[1], "schema=").toInt() == PHPUNIT_STORE_SCHEMA)
        val identity = rawPhpunitValue(lines[2], "identity=").also { require(it.matches(PHPUNIT_SHA256)) }
        val artifactCount = phpunitCount(lines[3], "artifacts=")
        val testCount = phpunitCount(lines[4], "tests=")
        val classCount = phpunitCount(lines[5], "classes=")
        val dependencyOwnerCount = phpunitCount(lines[6], "dependency-owners=")
        val dependencyCount = phpunitCount(lines[7], "dependencies=")
        val checksum = rawPhpunitValue(lines[8], "checksum=").also { require(it.matches(PHPUNIT_SHA256)) }
        val payloadLines = lines.drop(PHPUNIT_STORE_HEADER_LINES)
        val payload = payloadLines.joinToString("\n", postfix = "\n")
        require(sha256(payload) == checksum)

        val stored = PhpunitStoredMaps().also { maps -> payloadLines.forEach(maps::add) }
        require(stored.artifacts.size == artifactCount && stored.tests.size == testCount)
        require(stored.classes.size == classCount && stored.dependencies.size == dependencyOwnerCount)
        require(stored.dependencyLines == dependencyCount)
        PhpunitTestSnapshot(
            identity,
            stored.artifacts,
            stored.tests,
            stored.classes,
            stored.dependencies.mapValues { it.value.toSet() },
        )
            .also { require(validPhpunitSnapshot(it)) }
    }.getOrNull()

    fun write(snapshot: PhpunitTestSnapshot) {
        require(validPhpunitSnapshot(snapshot))
        val directory = securePhpunitDirectory(root)
        val target = directory.resolve(PHPUNIT_BASELINE_FILE)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target))
        }
        val temporary = Files.createTempFile(directory, PHPUNIT_BASELINE_FILE, ".tmp")
        try {
            val serialized = serializePhpunitSnapshot(snapshot)
            require(serialized.toByteArray(StandardCharsets.UTF_8).size <= MAX_PHPUNIT_STORE_BYTES)
            Files.writeString(temporary, serialized, StandardCharsets.UTF_8)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (failure: AtomicMoveNotSupportedException) {
                throw IllegalStateException("Atomic PHPUnit baseline replacement is unavailable", failure)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

private class PhpunitStoredMaps {
    val artifacts = LinkedHashMap<String, String>()
    val tests = LinkedHashMap<String, String>()
    val classes = LinkedHashMap<String, String>()
    val dependencies = LinkedHashMap<String, MutableSet<String>>()
    var dependencyLines = 0
        private set

    fun add(line: String) {
        when {
            line.startsWith("artifact=") -> addArtifact(line.removePrefix("artifact="))
            line.startsWith("test=") -> addTest(line.removePrefix("test="))
            line.startsWith("class=") -> addClass(line.removePrefix("class="))
            line.startsWith("dependency-owner=") -> addOwner(line.removePrefix("dependency-owner="))
            line.startsWith("dependency=") -> addDependency(line.removePrefix("dependency="))
            else -> error("stored PHPUnit map")
        }
    }

    private fun addArtifact(value: String) {
        val (path, hash) = phpunitPair(value)
        require(hash.matches(PHPUNIT_SHA256) && artifacts.put(path, hash) == null)
    }

    private fun addTest(value: String) {
        val (test, testClass) = phpunitPair(value)
        require(tests.put(test, testClass) == null)
    }

    private fun addClass(value: String) {
        val (testClass, file) = phpunitPair(value)
        require(classes.put(testClass, file) == null)
    }

    private fun addOwner(value: String) {
        require(dependencies.put(decodePhpunit(value), LinkedHashSet()) == null)
    }

    private fun addDependency(value: String) {
        val (testClass, artifact) = phpunitPair(value)
        require(dependencies.getValue(testClass).add(artifact))
        dependencyLines++
    }
}

private fun serializePhpunitSnapshot(snapshot: PhpunitTestSnapshot): String {
    val payload = buildString {
        snapshot.artifacts.toSortedMap().forEach { (path, hash) ->
            append("artifact=").append(encodePhpunit(path)).append('|').append(encodePhpunit(hash)).append('\n')
        }
        snapshot.tests.toSortedMap().forEach { (test, testClass) ->
            append("test=").append(encodePhpunit(test)).append('|').append(encodePhpunit(testClass)).append('\n')
        }
        snapshot.classes.toSortedMap().forEach { (testClass, file) ->
            append("class=").append(encodePhpunit(testClass)).append('|').append(encodePhpunit(file)).append('\n')
        }
        snapshot.dependencies.toSortedMap().forEach { (testClass, artifacts) ->
            append("dependency-owner=").append(encodePhpunit(testClass)).append('\n')
            artifacts.sorted().forEach { artifact ->
                append("dependency=")
                    .append(encodePhpunit(testClass))
                    .append('|')
                    .append(encodePhpunit(artifact))
                    .append('\n')
            }
        }
    }
    return buildString {
        append(PHPUNIT_STORE_FORMAT).append('\n')
        append("schema=").append(PHPUNIT_STORE_SCHEMA).append('\n')
        append("identity=").append(snapshot.identity).append('\n')
        append("artifacts=").append(snapshot.artifacts.size).append('\n')
        append("tests=").append(snapshot.tests.size).append('\n')
        append("classes=").append(snapshot.classes.size).append('\n')
        append("dependency-owners=").append(snapshot.dependencies.size).append('\n')
        append("dependencies=").append(snapshot.dependencies.values.sumOf(Set<String>::size)).append('\n')
        append("checksum=").append(sha256(payload)).append('\n')
        append(payload)
    }
}

private data class PhpunitRunMap(
    val full: Boolean,
    val supported: Boolean,
    val complete: Boolean,
    val tests: Map<String, String>,
    val classes: Map<String, String>,
    val dependencies: Map<String, Set<String>>,
    val inventoryTests: Map<String, String>,
    val inventoryClasses: Map<String, String>,
)

internal fun promotePhpunitBaseline(
    store: PhpunitTestBaselineStore,
    before: PhpunitProjectState?,
    after: PhpunitProjectState?,
    output: Path,
    full: Boolean,
    passed: Boolean,
): Boolean = runCatching {
    require(full && passed && before != null && before == after)
    val run = readPhpunitRunMap(output)
    require(run != null && run.full && run.supported && run.complete)
    require(run.inventoryTests == run.tests && run.inventoryClasses == run.classes)
    val snapshot = PhpunitTestSnapshot(before.identity, before.artifacts, run.tests, run.classes, run.dependencies)
    require(validPhpunitSnapshot(snapshot))
    store.write(snapshot)
    true
}.getOrDefault(false)

internal fun completePhpunitSelection(
    selection: PhpunitTestSelection,
    before: PhpunitProjectState?,
    after: PhpunitProjectState?,
    output: Path,
    baseline: PhpunitTestSnapshot,
): Boolean = when (selection) {
    PhpunitTestSelection.Full -> true
    is PhpunitTestSelection.Exact -> runCatching {
        require(before != null && before == after)
        val run = readPhpunitRunMap(output)
        require(run != null && !run.full && run.supported && run.complete)
        val selectedFiles = selection.classes.mapTo(LinkedHashSet(), baseline.classes::getValue)
        val expectedClasses = baseline.classes.filterValues(selectedFiles::contains)
        val expectedTests = baseline.tests.filterValues(expectedClasses.keys::contains)
        require(run.inventoryTests == expectedTests && run.inventoryClasses == expectedClasses)
        run.classes.keys == selection.classes.toSet() && run.tests.keys == selection.tests.toSet()
    }.getOrDefault(false)
}

private fun readPhpunitRunMap(path: Path): PhpunitRunMap? = runCatching {
    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
    require(Files.size(path) <= MAX_PHPUNIT_STORE_BYTES)
    val root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).asJsonObject
    require(root.get("schema")?.asInt == PHPUNIT_RUN_SCHEMA)
    val full = root.requiredBoolean("full")
    val supported = root.requiredBoolean("supported")
    val complete = root.requiredBoolean("complete")
    val testCount = root.requiredPhpunitCount("test_count", MAX_PHPUNIT_TESTS)
    val classCount = root.requiredPhpunitCount("class_count", MAX_PHPUNIT_TEST_FILES)
    val dependencyOwnerCount = root.requiredPhpunitCount("dependency_owner_count", MAX_PHPUNIT_TEST_FILES)
    val dependencyCount = root.requiredPhpunitCount("dependency_count", MAX_PHPUNIT_ARTIFACTS * MAX_PHPUNIT_TEST_FILES)
    val inventoryTestCount = root.requiredPhpunitCount("inventory_test_count", MAX_PHPUNIT_TESTS)
    val inventoryClassCount = root.requiredPhpunitCount("inventory_class_count", MAX_PHPUNIT_TEST_FILES)
    val tests = LinkedHashMap<String, String>()
    val classes = LinkedHashMap<String, String>()
    val testArray = root.getAsJsonArray("tests")
    require(testArray.size() in 1..MAX_PHPUNIT_TESTS)
    testArray.forEach { entry ->
        val value = entry.asJsonObject
        val id = value.get("id")?.asString.orEmpty()
        val testClass = value.get("class")?.asString.orEmpty()
        val file = value.get("file")?.asString.orEmpty()
        require(validPhpunitTestId(id) && validPhpunitClassName(testClass) && validPhpunitPath(file))
        require(tests.put(id, testClass) == null)
        require(classes.putIfAbsent(testClass, file).let { it == null || it == file })
    }
    val dependencies = LinkedHashMap<String, Set<String>>()
    val dependencyObject = root.getAsJsonObject("dependencies")
    require(dependencyObject.size() <= MAX_PHPUNIT_TEST_FILES)
    dependencyObject.entrySet().forEach { (testClass, rawDependencies) ->
        require(validPhpunitClassName(testClass))
        val values = rawDependencies.asJsonArray
        require(values.size() <= MAX_PHPUNIT_ARTIFACTS)
        val parsed = values.mapTo(LinkedHashSet()) { dependency ->
            dependency.asString.also { require(validPhpunitPath(it)) }
        }
        require(parsed.size == values.size() && dependencies.put(testClass, parsed) == null)
    }
    require(tests.size == testCount && classes.size == classCount)
    require(dependencies.size == dependencyOwnerCount)
    require(dependencies.values.sumOf(Set<String>::size) == dependencyCount)
    require(dependencies.keys == classes.keys && tests.values.toSet() == classes.keys)
    val (inventoryTests, inventoryClasses) = root.phpunitTestInventory("inventory")
    require(inventoryTests.size == inventoryTestCount && inventoryClasses.size == inventoryClassCount)
    require(tests.all { (id, testClass) -> inventoryTests[id] == testClass })
    require(classes.all { (testClass, file) -> inventoryClasses[testClass] == file })
    PhpunitRunMap(full, supported, complete, tests, classes, dependencies, inventoryTests, inventoryClasses)
}.getOrNull()

private fun com.google.gson.JsonObject.phpunitTestInventory(
    name: String,
): Pair<Map<String, String>, Map<String, String>> {
    val tests = LinkedHashMap<String, String>()
    val classes = LinkedHashMap<String, String>()
    val values = getAsJsonArray(name)
    require(values.size() in 1..MAX_PHPUNIT_TESTS)
    values.forEach { entry ->
        val value = entry.asJsonObject
        val id = value.get("id")?.asString.orEmpty()
        val testClass = value.get("class")?.asString.orEmpty()
        val file = value.get("file")?.asString.orEmpty()
        require(validPhpunitTestId(id) && validPhpunitClassName(testClass) && validPhpunitPath(file))
        require(tests.put(id, testClass) == null)
        require(classes.putIfAbsent(testClass, file).let { it == null || it == file })
    }
    require(tests.values.toSet() == classes.keys)
    return tests to classes
}

private fun com.google.gson.JsonObject.requiredBoolean(name: String): Boolean {
    val value = get(name)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
    return value.asBoolean
}

private fun com.google.gson.JsonObject.requiredPhpunitCount(name: String, maximum: Int): Int {
    val value = get(name)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
    return value.asInt.also { require(it in 0..maximum) }
}

private fun validPhpunitSnapshot(snapshot: PhpunitTestSnapshot): Boolean {
    return snapshot.identity.matches(PHPUNIT_SHA256) &&
        validPhpunitSizes(snapshot) &&
        validPhpunitArtifacts(snapshot.artifacts) &&
        validPhpunitTests(snapshot.tests) &&
        validPhpunitClasses(snapshot.classes) &&
        snapshot.tests.values.toSet() == snapshot.classes.keys &&
        snapshot.classes.keys == snapshot.dependencies.keys &&
        validPhpunitDependencies(snapshot.dependencies, snapshot.artifacts.keys)
}

private fun validPhpunitSizes(snapshot: PhpunitTestSnapshot): Boolean =
    snapshot.artifacts.size in 1..MAX_PHPUNIT_ARTIFACTS &&
        snapshot.tests.size in 1..MAX_PHPUNIT_TESTS &&
        snapshot.classes.size in 1..MAX_PHPUNIT_TEST_FILES &&
        snapshot.dependencies.size in 1..MAX_PHPUNIT_TEST_FILES

private fun validPhpunitArtifacts(artifacts: Map<String, String>): Boolean = artifacts.all { (path, hash) ->
    validPhpunitPath(path) && hash.matches(PHPUNIT_SHA256)
}

private fun validPhpunitTests(tests: Map<String, String>): Boolean = tests.all { (test, testClass) ->
    validPhpunitTestId(test) && validPhpunitClassName(testClass)
}

private fun validPhpunitClasses(classes: Map<String, String>): Boolean = classes.all { (testClass, file) ->
    validPhpunitClassName(testClass) && validPhpunitPath(file)
}

private fun validPhpunitDependencies(
    values: Map<String, Set<String>>,
    artifacts: Set<String>,
): Boolean = values.all { (testClass, dependencies) ->
    validPhpunitClassName(testClass) && dependencies.size <= MAX_PHPUNIT_ARTIFACTS &&
        dependencies.all(artifacts::contains)
}

private val validPhpunitClassName: (String) -> Boolean = { value ->
    value.length <= MAX_PHPUNIT_VALUE_LENGTH && value.matches(PHPUNIT_CLASS_NAME)
}

private fun validPhpunitTestId(value: String): Boolean =
    value.isNotBlank() && value.length <= MAX_PHPUNIT_VALUE_LENGTH && '\n' !in value && '\r' !in value

private fun validPhpunitPath(value: String): Boolean =
    value.isNotBlank() && value.length <= MAX_PHPUNIT_VALUE_LENGTH &&
        !value.startsWith('/') && '\\' !in value && value.split('/').none { it.isBlank() || it == "." || it == ".." }

internal fun securePhpunitDirectory(path: Path): Path {
    val absolute = path.toAbsolutePath().normalize()
    Files.createDirectories(absolute)
    require(!Files.isSymbolicLink(absolute))
    return absolute.toRealPath(LinkOption.NOFOLLOW_LINKS).also {
        require(Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(it) && Files.isWritable(it))
    }
}

private fun readPhpunitLines(path: Path): List<String> {
    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
    require(Files.size(path) <= MAX_PHPUNIT_STORE_BYTES)
    return Files.readAllLines(path, StandardCharsets.UTF_8).also { require(it.size <= MAX_PHPUNIT_STORE_LINES) }
}

private fun phpunitPair(value: String): Pair<String, String> {
    val parts = value.split('|')
    require(parts.size == 2)
    return decodePhpunit(parts[0]) to decodePhpunit(parts[1])
}

private fun rawPhpunitValue(line: String, prefix: String): String {
    require(line.startsWith(prefix))
    return line.removePrefix(prefix).also { require(it.isNotBlank()) }
}

private fun phpunitCount(line: String, prefix: String): Int =
    rawPhpunitValue(line, prefix).toInt().also { require(it >= 0) }

private fun encodePhpunit(value: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

private fun decodePhpunit(value: String): String {
    val bytes = Base64.getUrlDecoder().decode(value)
    require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value)
    return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
        .also { require(it.isNotBlank()) }
}

private val PHPUNIT_SHA256 = Regex("[0-9a-f]{64}")
private val PHPUNIT_CLASS_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\\\[A-Za-z_][A-Za-z0-9_]*)*")
private const val PHPUNIT_STORE_FORMAT = "affected-phpunit-map"
private const val PHPUNIT_STORE_SCHEMA = 1
private const val PHPUNIT_RUN_SCHEMA = 2
private const val PHPUNIT_STORE_HEADER_LINES = 9
private const val PHPUNIT_BASELINE_FILE = "baseline.map"
private const val MAX_PHPUNIT_ARTIFACTS = 4096
private const val MAX_PHPUNIT_TESTS = 65_536
private const val MAX_PHPUNIT_TEST_FILES = 4096
private const val MAX_PHPUNIT_VALUE_LENGTH = 4096
private const val MAX_PHPUNIT_STORE_LINES = 200_000
private const val MAX_PHPUNIT_STORE_BYTES = 16L * 1024 * 1024

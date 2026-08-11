package com.aspix2k.affected.build

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64

internal data class CMakeImpactTarget(
    val id: String,
    val name: String,
    val sources: Set<String>,
    val dependencies: Set<String>,
)

internal data class CMakeTestSnapshot(
    val fingerprint: String,
    val targets: Map<String, CMakeImpactTarget>,
    val tests: Map<String, String>,
    val allTests: Set<String>,
)

internal sealed interface CMakeTestSelection {
    data object Full : CMakeTestSelection
    data object Empty : CMakeTestSelection
    data class Exact(val tests: List<String>) : CMakeTestSelection
}

internal fun selectCMakeTests(
    root: Path,
    current: CMakeTestSnapshot?,
    baseline: CMakeTestSnapshot?,
    changes: BuildChanges,
): CMakeTestSelection = runCatching {
    val changed = eligibleChangedSources(root, changes) ?: return CMakeTestSelection.Full
    require(current != null)
    require(baseline != null)
    require(current.fingerprint == baseline.fingerprint)
    require(validSnapshot(baseline))
    val changedTargets = changed.flatMapTo(LinkedHashSet()) { source ->
        baseline.targets.values.filter { source in it.sources }.map(CMakeImpactTarget::id).also {
            require(it.isNotEmpty())
        }
    }
    require(changedTargets.isNotEmpty())

    val affected = baseline.targets.values.filterTo(LinkedHashSet()) { target ->
        target.id in changedTargets || target.dependencies.any(changedTargets::contains)
    }.mapTo(HashSet(), CMakeImpactTarget::id)
    val tests = baseline.tests.filterValues(affected::contains).keys.sorted()
    if (tests.isEmpty()) CMakeTestSelection.Empty else CMakeTestSelection.Exact(tests)
}.getOrDefault(CMakeTestSelection.Full)

private fun eligibleChangedSources(root: Path, changes: BuildChanges): List<String>? = runCatching {
    require(changes.comparedToBase)
    require(changes.files.isNotEmpty())
    require(changes.files.toSet() == changes.exactSelectionEligible)
    val absoluteRoot = root.toAbsolutePath().normalize()
    require(Files.isDirectory(absoluteRoot, LinkOption.NOFOLLOW_LINKS))
    require(!Files.isSymbolicLink(absoluteRoot))
    val projectRoot = absoluteRoot.toRealPath(LinkOption.NOFOLLOW_LINKS)
    changes.files.map { raw ->
        val path = Path.of(raw).toAbsolutePath().normalize()
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        require(!Files.isSymbolicLink(path))
        val real = path.toRealPath(LinkOption.NOFOLLOW_LINKS)
        require(real.startsWith(projectRoot))
        projectRoot.relativize(real).portablePath().also { relative ->
            val extension = relative.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            require(extension in CMAKE_EXACT_SOURCE_EXTENSIONS)
        }
    }
}.getOrNull()

internal class CMakeTestBaselineStore(private val root: Path) {

    fun read(): CMakeTestSnapshot? = runCatching {
        val directory = secureDirectory(root)
        val path = directory.resolve(BASELINE_FILE)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        val lines = readLines(path)
        require(lines.size >= STORE_HEADER_LINES)
        require(lines[0] == STORE_FORMAT)
        require(rawValue(lines[1], "schema=").toInt() == STORE_SCHEMA)
        val fingerprint = rawValue(lines[2], "fingerprint=")
        require(fingerprint.matches(SHA256))
        val targetCount = count(lines[3], "targets=")
        val sourceCount = count(lines[4], "sources=")
        val dependencyCount = count(lines[5], "dependencies=")
        val testCount = count(lines[6], "tests=")
        val allTestCount = count(lines[7], "all-tests=")
        val checksum = rawValue(lines[8], "checksum=")
        require(checksum.matches(SHA256))
        val payloadLines = lines.drop(STORE_HEADER_LINES)
        val payload = payloadLines.joinToString("\n", postfix = "\n")
        require(sha256(payload) == checksum)

        val targets = LinkedHashMap<String, MutableTarget>()
        var sources = 0
        var dependencies = 0
        var tests = 0
        val testMap = LinkedHashMap<String, String>()
        val allTests = LinkedHashSet<String>()
        payloadLines.forEach { line ->
            when {
                line.startsWith("target=") -> {
                    val parts = line.removePrefix("target=").split('|')
                    require(parts.size == 2)
                    val id = decode(parts[0])
                    require(targets.put(id, MutableTarget(id, decode(parts[1]))) == null)
                }
                line.startsWith("source=") -> {
                    val (id, source) = pair(line.removePrefix("source="))
                    targets.getValue(id).sources += source
                    sources++
                }
                line.startsWith("dependency=") -> {
                    val (id, dependency) = pair(line.removePrefix("dependency="))
                    targets.getValue(id).dependencies += dependency
                    dependencies++
                }
                line.startsWith("test=") -> {
                    val (test, id) = pair(line.removePrefix("test="))
                    require(testMap.put(test, id) == null)
                    tests++
                }
                line.startsWith("all-test=") -> require(allTests.add(decode(line.removePrefix("all-test="))))
                else -> error("stored CTest map")
            }
        }
        require(targets.size == targetCount)
        require(sources == sourceCount && dependencies == dependencyCount && tests == testCount)
        require(allTests.size == allTestCount)
        val snapshot = CMakeTestSnapshot(
            fingerprint,
            targets.mapValues { it.value.freeze() },
            testMap,
            allTests,
        )
        require(validSnapshot(snapshot))
        snapshot
    }.getOrNull()

    fun write(snapshot: CMakeTestSnapshot) {
        require(validSnapshot(snapshot))
        val directory = secureDirectory(root)
        val target = directory.resolve(BASELINE_FILE)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
        }
        val temporary = Files.createTempFile(directory, BASELINE_FILE, ".tmp")
        try {
            val serialized = serialize(snapshot)
            require(serialized.toByteArray(StandardCharsets.UTF_8).size <= MAX_STORE_SIZE)
            Files.writeString(temporary, serialized, StandardCharsets.UTF_8)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (failure: AtomicMoveNotSupportedException) {
                throw IllegalStateException("Atomic CTest baseline replacement is unavailable", failure)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun serialize(snapshot: CMakeTestSnapshot): String {
        val payload = buildString {
            snapshot.targets.toSortedMap().forEach { (id, target) ->
                append("target=").append(encode(id)).append('|').append(encode(target.name)).append('\n')
                target.sources.sorted().forEach { source ->
                    append("source=").append(encode(id)).append('|').append(encode(source)).append('\n')
                }
                target.dependencies.sorted().forEach { dependency ->
                    append("dependency=").append(encode(id)).append('|').append(encode(dependency)).append('\n')
                }
            }
            snapshot.tests.toSortedMap().forEach { (test, id) ->
                append("test=").append(encode(test)).append('|').append(encode(id)).append('\n')
            }
            snapshot.allTests.sorted().forEach { test ->
                append("all-test=").append(encode(test)).append('\n')
            }
        }
        return buildString {
            append(STORE_FORMAT).append('\n')
            append("schema=").append(STORE_SCHEMA).append('\n')
            append("fingerprint=").append(snapshot.fingerprint).append('\n')
            append("targets=").append(snapshot.targets.size).append('\n')
            append("sources=").append(snapshot.targets.values.sumOf { it.sources.size }).append('\n')
            append("dependencies=").append(snapshot.targets.values.sumOf { it.dependencies.size }).append('\n')
            append("tests=").append(snapshot.tests.size).append('\n')
            append("all-tests=").append(snapshot.allTests.size).append('\n')
            append("checksum=").append(sha256(payload)).append('\n')
            append(payload)
        }
    }
}

private data class MutableTarget(
    val id: String,
    val name: String,
    val sources: MutableSet<String> = LinkedHashSet(),
    val dependencies: MutableSet<String> = LinkedHashSet(),
) {
    fun freeze(): CMakeImpactTarget = CMakeImpactTarget(id, name, sources, dependencies)
}

private fun validSnapshot(snapshot: CMakeTestSnapshot): Boolean {
    if (!snapshot.fingerprint.matches(SHA256)) return false
    if (snapshot.targets.isEmpty() || snapshot.targets.size > MAX_TARGETS) return false
    if (snapshot.tests.isEmpty()) return false
    if (snapshot.allTests.isEmpty() || snapshot.allTests.size > MAX_TESTS) return false
    if (snapshot.targets.values.map(CMakeImpactTarget::name).toSet().size != snapshot.targets.size) return false
    if (snapshot.targets.any { (id, target) -> !validTarget(id, target, snapshot.targets.keys) }) return false
    if (snapshot.targets.values.none { it.sources.isNotEmpty() }) return false
    if (snapshot.tests.keys != snapshot.allTests) return false
    if (!snapshot.allTests.all(::validCTestName)) return false
    return snapshot.tests.all { (test, target) -> validCTestName(test) && target in snapshot.targets }
}

private fun validTarget(id: String, target: CMakeImpactTarget, targetIds: Set<String>): Boolean {
    if (id.isBlank() || target.id != id || target.name.isBlank()) return false
    if (target.sources.size > MAX_SOURCES || target.sources.any { !validSourcePath(it) }) return false
    if (target.dependencies.size > MAX_TARGETS) return false
    return target.dependencies.all(targetIds::contains)
}

private fun validSourcePath(path: String): Boolean {
    if (path.isBlank() || path.startsWith('/') || path.contains('\\')) return false
    return path.split('/').none { it.isBlank() || it == "." || it == ".." }
}

internal fun validCTestName(name: String): Boolean =
    name.isNotBlank() && !name.contains('\n') && !name.contains('\r')

private fun secureDirectory(path: Path): Path {
    val absolute = path.toAbsolutePath().normalize()
    Files.createDirectories(absolute)
    require(!Files.isSymbolicLink(absolute))
    val real = absolute.toRealPath(LinkOption.NOFOLLOW_LINKS)
    require(Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(real) && Files.isWritable(real))
    return real
}

private fun readLines(path: Path): List<String> {
    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
    require(Files.size(path) in 1..MAX_STORE_SIZE)
    val content = Files.readString(path, StandardCharsets.UTF_8)
    require(!content.contains('\r') && content.endsWith('\n'))
    return content.dropLast(1).split('\n').also { require(it.size <= MAX_STORE_LINES) }
}

private fun pair(value: String): Pair<String, String> {
    val parts = value.split('|')
    require(parts.size == 2)
    return decode(parts[0]) to decode(parts[1])
}

private fun rawValue(line: String, prefix: String): String {
    require(line.startsWith(prefix))
    return line.removePrefix(prefix).also { require(it.isNotBlank()) }
}

private fun count(line: String, prefix: String): Int = rawValue(line, prefix).toInt().also { require(it >= 0) }

private fun decode(value: String): String {
    val bytes = Base64.getUrlDecoder().decode(value)
    require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value)
    return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
        .also { require(it.isNotBlank()) }
}

private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

internal fun Path.portablePath(): String = toString().replace('\\', '/')

private val SHA256 = Regex("[0-9a-f]{64}")
private val CMAKE_EXACT_SOURCE_EXTENSIONS = setOf("c", "cc", "cp", "cpp", "cxx", "c++")
private const val STORE_FORMAT = "affected-ctest-map"
private const val STORE_SCHEMA = 1
private const val STORE_HEADER_LINES = 9
private const val BASELINE_FILE = "baseline.map"
private const val MAX_TARGETS = 4096
private const val MAX_SOURCES = 65_536
private const val MAX_TESTS = 65_536
private const val MAX_STORE_LINES = 200_000
private const val MAX_STORE_SIZE = 16L * 1024 * 1024

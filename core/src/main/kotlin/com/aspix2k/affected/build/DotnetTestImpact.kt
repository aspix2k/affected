package com.aspix2k.affected.build

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64

internal data class DotnetImpactArtifact(
    val sha256: String,
    val dependencies: Set<String>,
)

internal data class DotnetTestSnapshot(
    val identity: String,
    val testAssemblySha256: String,
    val artifacts: Map<String, DotnetImpactArtifact>,
    val classes: Map<String, Set<String>>,
    val tests: Map<String, String>,
)

internal sealed interface DotnetTestSelection {
    data object Full : DotnetTestSelection
    data object Empty : DotnetTestSelection
    data class Exact(val tests: List<String>) : DotnetTestSelection
}

internal fun selectDotnetTests(
    root: Path,
    productionRoots: Set<Path>,
    current: DotnetTestSnapshot?,
    baseline: DotnetTestSnapshot?,
    changes: BuildChanges,
): DotnetTestSelection = runCatching {
    require(eligibleDotnetChanges(root, productionRoots, changes))
    require(current != null && baseline != null)
    require(validDotnetSnapshot(current) && validDotnetSnapshot(baseline))
    require(current.identity == baseline.identity)
    require(current.testAssemblySha256 == baseline.testAssemblySha256)
    require(current.artifacts.keys == baseline.artifacts.keys)
    require(current.classes == baseline.classes)
    require(current.tests == baseline.tests)

    val affected = baseline.artifacts.filter { (name, artifact) ->
        current.artifacts.getValue(name).sha256 != artifact.sha256
    }.keys.toMutableSet()
    var changed: Boolean
    do {
        changed = baseline.artifacts.any { (name, artifact) ->
            name !in affected && artifact.dependencies.any(affected::contains) && affected.add(name)
        }
    } while (changed)

    if (affected.isEmpty()) return DotnetTestSelection.Empty
    val tests = baseline.tests.filter { (_, className) ->
        baseline.classes.getValue(className).any(affected::contains)
    }.keys.sorted()
    if (tests.isEmpty()) {
        DotnetTestSelection.Full
    } else {
        require(tests.size <= MAX_EXACT_TESTS)
        require(dotnetFilter(tests).length <= MAX_FILTER_LENGTH)
        DotnetTestSelection.Exact(tests)
    }
}.getOrDefault(DotnetTestSelection.Full)

internal fun selectUnchangedDotnetConsumer(
    root: Path,
    current: DotnetTestSnapshot?,
    baseline: DotnetTestSnapshot?,
    changes: BuildChanges,
): DotnetTestSelection = runCatching {
    require(eligibleDotnetSourceChanges(root, changes))
    require(current != null && baseline != null)
    require(validDotnetSnapshot(current) && validDotnetSnapshot(baseline))
    require(current == baseline)
    DotnetTestSelection.Empty
}.getOrDefault(DotnetTestSelection.Full)

internal fun dotnetFilter(tests: List<String>): String {
    require(tests.isNotEmpty() && tests.distinct().size == tests.size)
    require(tests.all(::validDotnetTestName))
    return tests.sorted().joinToString("|") { "FullyQualifiedName=$it" }
}

internal fun eligibleDotnetChanges(root: Path, productionRoots: Set<Path>, changes: BuildChanges): Boolean =
    runCatching {
        require(changes.comparedToBase)
        require(changes.files.isNotEmpty())
        require(changes.files.toSet() == changes.exactSelectionEligible)
        val requestedRoot = root.toAbsolutePath().normalize()
        require(Files.isDirectory(requestedRoot, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(requestedRoot))
        val realRoot = requestedRoot.toRealPath()
        val realProductionRoots = productionRoots.mapTo(LinkedHashSet()) { path ->
            val requested = path.toAbsolutePath().normalize()
            require(Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(requested))
            requested.toRealPath().also { require(it.startsWith(realRoot)) }
        }
        require(realProductionRoots.isNotEmpty())
        changes.files.forEach { raw ->
            val path = Path.of(raw).toAbsolutePath().normalize()
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
            val real = path.toRealPath()
            require(realProductionRoots.any(real::startsWith))
            val fileName = real.fileName.toString().lowercase()
            require(fileName.substringAfterLast('.', missingDelimiterValue = "") in DOTNET_EXACT_SOURCE_EXTENSIONS)
            require(GENERATED_SOURCE_SUFFIXES.none(fileName::endsWith))
            require(real.none { segment -> segment.toString().lowercase() in DOTNET_BUILD_DIRECTORIES })
        }
        true
    }.getOrDefault(false)

private fun eligibleDotnetSourceChanges(root: Path, changes: BuildChanges): Boolean = runCatching {
    require(changes.comparedToBase)
    require(changes.files.isNotEmpty() && changes.files.toSet() == changes.exactSelectionEligible)
    val requestedRoot = root.toAbsolutePath().normalize()
    require(Files.isDirectory(requestedRoot, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(requestedRoot))
    val realRoot = requestedRoot.toRealPath()
    changes.files.forEach { raw ->
        val requested = Path.of(raw).toAbsolutePath().normalize()
        require(requested.isSafeDotnetSource(realRoot))
    }
    true
}.getOrDefault(false)

private fun Path.isSafeDotnetSource(root: Path): Boolean {
    require(Files.isRegularFile(this, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(this))
    val real = toRealPath()
    require(real.startsWith(root))
    val fileName = real.fileName.toString().lowercase()
    require(fileName.substringAfterLast('.', missingDelimiterValue = "") in DOTNET_EXACT_SOURCE_EXTENSIONS)
    require(GENERATED_SOURCE_SUFFIXES.none(fileName::endsWith))
    require(real.none { segment -> segment.toString().lowercase() in DOTNET_BUILD_DIRECTORIES })
    return true
}

internal class DotnetTestBaselineStore(private val root: Path) {

    fun read(): DotnetTestSnapshot? = runCatching {
        val directory = secureDotnetDirectory(root)
        val path = directory.resolve(BASELINE_FILE)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        val lines = readDotnetLines(path)
        require(lines.size >= STORE_HEADER_LINES)
        require(lines[0] == STORE_FORMAT)
        require(rawDotnetValue(lines[1], "schema=").toInt() == STORE_SCHEMA)
        val identity = rawDotnetValue(lines[2], "identity=").also { require(it.matches(SHA256)) }
        val testAssembly = rawDotnetValue(lines[3], "test-assembly=").also { require(it.matches(SHA256)) }
        val artifactCount = dotnetCount(lines[4], "artifacts=")
        val dependencyCount = dotnetCount(lines[5], "dependencies=")
        val classCount = dotnetCount(lines[6], "classes=")
        val testCount = dotnetCount(lines[7], "tests=")
        val checksum = rawDotnetValue(lines[8], "checksum=").also { require(it.matches(SHA256)) }
        val payloadLines = lines.drop(STORE_HEADER_LINES)
        require(sha256(payloadLines.joinToString("\n", postfix = "\n")) == checksum)

        val artifacts = LinkedHashMap<String, MutableDotnetArtifact>()
        val classes = LinkedHashMap<String, MutableSet<String>>()
        val tests = LinkedHashMap<String, String>()
        var dependencies = 0
        payloadLines.forEach { line ->
            when {
                line.startsWith("artifact=") -> {
                    val (name, hash) = dotnetPair(line.removePrefix("artifact="))
                    require(hash.matches(SHA256))
                    require(artifacts.put(name, MutableDotnetArtifact(hash)) == null)
                }
                line.startsWith("dependency=") -> {
                    val (name, dependency) = dotnetPair(line.removePrefix("dependency="))
                    require(artifacts.getValue(name).dependencies.add(dependency))
                    dependencies++
                }
                line.startsWith("class=") -> {
                    val name = decodeDotnet(line.removePrefix("class="))
                    require(classes.put(name, LinkedHashSet()) == null)
                }
                line.startsWith("class-dependency=") -> {
                    val (name, dependency) = dotnetPair(line.removePrefix("class-dependency="))
                    require(classes.getValue(name).add(dependency))
                }
                line.startsWith("test=") -> {
                    val (test, className) = dotnetPair(line.removePrefix("test="))
                    require(tests.put(test, className) == null)
                }
                else -> error("stored .NET map")
            }
        }
        require(artifacts.size == artifactCount)
        require(dependencies == dependencyCount)
        require(classes.size == classCount && tests.size == testCount)
        DotnetTestSnapshot(
            identity,
            testAssembly,
            artifacts.mapValues { (_, artifact) -> DotnetImpactArtifact(artifact.sha256, artifact.dependencies) },
            classes,
            tests,
        ).also { require(validDotnetSnapshot(it)) }
    }.getOrNull()

    fun write(snapshot: DotnetTestSnapshot) {
        require(validDotnetSnapshot(snapshot))
        val directory = secureDotnetDirectory(root)
        val target = directory.resolve(BASELINE_FILE)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target))
        }
        val temporary = Files.createTempFile(directory, BASELINE_FILE, ".tmp")
        try {
            val serialized = serialize(snapshot)
            require(serialized.toByteArray(StandardCharsets.UTF_8).size <= MAX_STORE_SIZE)
            Files.writeString(temporary, serialized, StandardCharsets.UTF_8)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (failure: AtomicMoveNotSupportedException) {
                throw IllegalStateException("Atomic .NET baseline replacement is unavailable", failure)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun serialize(snapshot: DotnetTestSnapshot): String {
        val payload = buildString {
            snapshot.artifacts.toSortedMap().forEach { (name, artifact) ->
                append("artifact=").append(encodeDotnet(name)).append('|')
                    .append(encodeDotnet(artifact.sha256)).append('\n')
                artifact.dependencies.sorted().forEach { dependency ->
                    append("dependency=").append(encodeDotnet(name)).append('|')
                        .append(encodeDotnet(dependency)).append('\n')
                }
            }
            snapshot.classes.toSortedMap().forEach { (name, dependencies) ->
                append("class=").append(encodeDotnet(name)).append('\n')
                dependencies.sorted().forEach { dependency ->
                    append("class-dependency=").append(encodeDotnet(name)).append('|')
                        .append(encodeDotnet(dependency)).append('\n')
                }
            }
            snapshot.tests.toSortedMap().forEach { (test, className) ->
                append("test=").append(encodeDotnet(test)).append('|').append(encodeDotnet(className)).append('\n')
            }
        }
        return buildString {
            append(STORE_FORMAT).append('\n')
            append("schema=").append(STORE_SCHEMA).append('\n')
            append("identity=").append(snapshot.identity).append('\n')
            append("test-assembly=").append(snapshot.testAssemblySha256).append('\n')
            append("artifacts=").append(snapshot.artifacts.size).append('\n')
            append("dependencies=").append(snapshot.artifacts.values.sumOf { it.dependencies.size }).append('\n')
            append("classes=").append(snapshot.classes.size).append('\n')
            append("tests=").append(snapshot.tests.size).append('\n')
            append("checksum=").append(sha256(payload)).append('\n')
            append(payload)
        }
    }
}

private data class MutableDotnetArtifact(
    val sha256: String,
    val dependencies: MutableSet<String> = LinkedHashSet(),
)

internal fun validDotnetSnapshot(snapshot: DotnetTestSnapshot): Boolean {
    if (!snapshot.identity.matches(SHA256) || !snapshot.testAssemblySha256.matches(SHA256)) return false
    if (snapshot.artifacts.isEmpty() || snapshot.artifacts.size > MAX_ARTIFACTS) return false
    if (snapshot.classes.isEmpty() || snapshot.classes.size > MAX_CLASSES) return false
    if (snapshot.tests.isEmpty() || snapshot.tests.size > MAX_TESTS) return false
    if (!validDotnetArtifacts(snapshot.artifacts)) return false
    if (!validDotnetClasses(snapshot.classes, snapshot.artifacts.keys)) return false
    return snapshot.tests.all { (test, className) -> validDotnetTestName(test) && className in snapshot.classes }
}

private fun validDotnetArtifacts(artifacts: Map<String, DotnetImpactArtifact>): Boolean =
    artifacts.all { (name, artifact) ->
        validDotnetAssemblyName(name) && artifact.sha256.matches(SHA256) &&
            artifact.dependencies.all(artifacts::containsKey)
    }

private fun validDotnetClasses(classes: Map<String, Set<String>>, artifacts: Set<String>): Boolean =
    classes.all { (name, dependencies) ->
        validDotnetClassName(name) && dependencies.all(artifacts::contains)
    }

private fun validDotnetAssemblyName(name: String): Boolean =
    name.isNotBlank() && name.length <= MAX_NAME_LENGTH && name.none { it == '\r' || it == '\n' || it == '|' }

private fun validDotnetClassName(name: String): Boolean = name.matches(DOTNET_IDENTIFIER)

internal fun validDotnetTestName(name: String): Boolean =
    name.length <= MAX_NAME_LENGTH && name.matches(DOTNET_IDENTIFIER) && name.contains('.')

internal fun secureDotnetDirectory(path: Path): Path {
    val absolute = path.toAbsolutePath().normalize()
    Files.createDirectories(absolute)
    require(!Files.isSymbolicLink(absolute))
    val real = absolute.toRealPath(LinkOption.NOFOLLOW_LINKS)
    require(Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(real) && Files.isWritable(real))
    return real
}

private fun readDotnetLines(path: Path): List<String> {
    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
    require(Files.size(path) in 1..MAX_STORE_SIZE)
    val content = Files.readString(path, StandardCharsets.UTF_8)
    require(!content.contains('\r') && content.endsWith('\n'))
    return content.dropLast(1).split('\n').also { require(it.size <= MAX_STORE_LINES) }
}

private fun dotnetPair(value: String): Pair<String, String> {
    val parts = value.split('|')
    require(parts.size == 2)
    return decodeDotnet(parts[0]) to decodeDotnet(parts[1])
}

private fun rawDotnetValue(line: String, prefix: String): String {
    require(line.startsWith(prefix))
    return line.removePrefix(prefix).also { require(it.isNotBlank()) }
}

private fun dotnetCount(line: String, prefix: String): Int =
    rawDotnetValue(line, prefix).toInt().also { require(it >= 0) }

private fun decodeDotnet(value: String): String {
    val bytes = Base64.getUrlDecoder().decode(value)
    require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value)
    return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
        .also { require(it.isNotBlank()) }
}

private fun encodeDotnet(value: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

private val SHA256 = Regex("[0-9a-f]{64}")
private val DOTNET_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_`+]*(\\.[A-Za-z_][A-Za-z0-9_`+]*)+")
private val DOTNET_EXACT_SOURCE_EXTENSIONS = setOf("cs", "fs", "vb")
private val GENERATED_SOURCE_SUFFIXES = setOf(".g.cs", ".generated.cs", ".designer.cs", ".g.fs", ".g.vb")
private val DOTNET_BUILD_DIRECTORIES = setOf("bin", "obj")
private const val STORE_FORMAT = "affected-dotnet-map"
private const val STORE_SCHEMA = 1
private const val STORE_HEADER_LINES = 9
private const val BASELINE_FILE = "baseline.map"
private const val MAX_ARTIFACTS = 4096
private const val MAX_CLASSES = 65_536
private const val MAX_TESTS = 65_536
private const val MAX_EXACT_TESTS = 256
private const val MAX_FILTER_LENGTH = 16 * 1024
private const val MAX_NAME_LENGTH = 1024
private const val MAX_STORE_LINES = 250_000
private const val MAX_STORE_SIZE = 16L * 1024 * 1024

package com.aspix2k.affected.impact

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

internal object CollectorMapReader {

    fun read(
        taskDirectory: Path,
        collectorVersion: String,
        completedRunId: String,
        cancelled: Boolean = false,
        failed: Boolean = false,
    ): DependencyMapCandidate? = runCatching {
        require(collectorVersion.isNotBlank() && completedRunId.isNotBlank())
        val directory = secureDirectory(taskDirectory)
        val task = parseTask(readFile(directory.resolve(TASK_MANIFEST)))
        require(directory.fileName.toString() == "task-${sha256(task.key)}")

        val entries = list(directory)
        require(entries.all {
            it.fileName.toString() == TASK_MANIFEST ||
                it.fileName.toString() == CATALOG_MANIFEST ||
                it.fileName.toString() == EXPECTED_MANIFEST ||
                isWorkerDirectory(it)
        })
        val artifacts = parseCatalog(readFile(directory.resolve(CATALOG_MANIFEST)))
        val parsed = entries.filter(::isWorkerDirectory).map(::parseWorker)
        val expectedWorkers = parsed.mapTo(LinkedHashSet(), ParsedWorker::id)
        require(expectedWorkers.size == parsed.size)
        val completeWorkers = parsed.mapNotNull(ParsedWorker::complete)
        val rootExpectedPath = directory.resolve(EXPECTED_MANIFEST)
        val rootExpected = if (Files.exists(rootExpectedPath, LinkOption.NOFOLLOW_LINKS)) {
            parseExpected(readFile(rootExpectedPath))
        } else {
            null
        }
        val workerExpected = parsed.mapNotNull(ParsedWorker::expected)
        require(
            rootExpected != null && workerExpected.isEmpty() ||
                rootExpected == null && workerExpected.size == parsed.size,
        )
        val expected = rootExpected ?: ParsedExpected(
            supported = workerExpected.all(ParsedExpected::supported),
            tests = workerExpected.flatMapTo(LinkedHashSet(), ParsedExpected::tests),
        )
        val expectedTests = expected.tests.mapTo(LinkedHashSet(), ::TestClassId)
        val workers = completeWorkers.map { complete ->
            WorkerDependencyMap(complete.workerId, complete.records)
        }
        DependencyMapCandidate(
            identity = DependencyMapIdentity(
                DEPENDENCY_MAP_SCHEMA_VERSION,
                collectorVersion,
                task.key,
                task.runtimeFingerprint,
                task.inputFingerprint,
            ),
            artifacts = artifacts,
            expectedWorkers = expectedWorkers,
            expectedTestClasses = expectedTests,
            collectsAllTests = task.collectsAllTests && expected.supported &&
                completeWorkers.size == parsed.size &&
                completeWorkers.all(ParsedCompleteWorker::supported),
            workers = workers,
            completedRunId = completedRunId,
            cancelled = cancelled,
            failed = failed,
        )
    }.getOrNull()

    private fun parseTask(lines: List<String>): ParsedTask {
        require(lines.size == TASK_LINE_COUNT)
        require(lines[0] == FORMAT)
        require(lines[4] == "all=true" || lines[4] == "all=false")
        return ParsedTask(
            value(lines[1], "task="),
            value(lines[2], "runtime="),
            value(lines[3], "input="),
            lines[4] == "all=true",
        )
    }

    private fun parseExpected(lines: List<String>): ParsedExpected {
        require(lines.size >= EXPECTED_HEADER_LINE_COUNT && lines[0] == FORMAT)
        val supported = when (lines[1]) {
            "supported=true" -> true
            "supported=false" -> false
            else -> error("supported")
        }
        val tests = lines.drop(EXPECTED_HEADER_LINE_COUNT).map { value(it, "test=") }
        require(tests.size == tests.toSet().size)
        if (supported) require(tests.isNotEmpty())
        return ParsedExpected(supported, tests.toSet())
    }

    private fun parseCatalog(lines: List<String>): List<ClassDependency> {
        require(lines.size > CATALOG_HEADER_LINE_COUNT && lines[0] == FORMAT)
        return lines.drop(CATALOG_HEADER_LINE_COUNT).map { line ->
            require(line.startsWith("artifact="))
            parseDependency(line.removePrefix("artifact="))
        }
    }

    private fun parseWorker(directory: Path): ParsedWorker {
        val secure = secureDirectory(directory)
        val started = readFile(secure.resolve(STARTED_MANIFEST))
        require(started.size == STARTED_LINE_COUNT && started[0] == FORMAT)
        val workerId = value(started[1], "worker=")
        require(secure.fileName.toString() == "worker-${sha256(workerId)}")
        val expectedPath = secure.resolve(EXPECTED_MANIFEST)
        val expected = if (Files.exists(expectedPath, LinkOption.NOFOLLOW_LINKS)) {
            parseExpected(readFile(expectedPath))
        } else {
            null
        }
        val completePath = secure.resolve(COMPLETE_MANIFEST)
        if (!Files.exists(completePath, LinkOption.NOFOLLOW_LINKS)) return ParsedWorker(workerId, expected, null)
        return ParsedWorker(workerId, expected, parseCompleteWorker(secure, workerId, expected != null))
    }

    private fun parseCompleteWorker(
        directory: Path,
        workerId: String,
        hasExpectedManifest: Boolean,
    ): ParsedCompleteWorker {
        val manifest = readFile(directory.resolve(COMPLETE_MANIFEST))
        require(manifest.size >= COMPLETE_HEADER_LINE_COUNT)
        require(manifest[0] == FORMAT)
        require(value(manifest[1], "worker=") == workerId)
        val supported = when (manifest[2]) {
            "supported=true" -> true
            "supported=false" -> false
            else -> error("supported")
        }
        val tests = manifest.drop(COMPLETE_HEADER_LINE_COUNT).map { value(it, "test=") }
        require(tests.size == tests.toSet().size)
        if (supported) require(tests.isNotEmpty())

        val entries = list(directory)
        val expectedFiles = tests.mapTo(HashSet()) { "test-${sha256(it)}.map" }
        expectedFiles += STARTED_MANIFEST
        expectedFiles += COMPLETE_MANIFEST
        if (hasExpectedManifest) expectedFiles += EXPECTED_MANIFEST
        require(entries.mapTo(HashSet()) { it.fileName.toString() } == expectedFiles)
        val records = tests.map { test -> parseMap(directory.resolve("test-${sha256(test)}.map"), test) }
        return ParsedCompleteWorker(workerId, supported, tests.toSet(), records)
    }

    private fun parseMap(path: Path, expectedTest: String): TestDependencyRecord {
        val lines = readFile(path)
        require(lines.size >= MAP_HEADER_LINE_COUNT && lines[0] == FORMAT)
        require(value(lines[1], "test=") == expectedTest)
        val dependencies = lines.drop(MAP_HEADER_LINE_COUNT).map { line ->
            require(line.startsWith("dependency="))
            parseDependency(line.removePrefix("dependency="))
        }
        require(dependencies.size == dependencies.toSet().size)
        return TestDependencyRecord(TestClassId(expectedTest), dependencies.toSet())
    }

    private fun isWorkerDirectory(path: Path): Boolean =
        path.fileName.toString().matches(WORKER_DIRECTORY_PATTERN)

    private data class ParsedTask(
        val key: String,
        val runtimeFingerprint: String,
        val inputFingerprint: String,
        val collectsAllTests: Boolean,
    )

    private data class ParsedWorker(
        val id: String,
        val expected: ParsedExpected?,
        val complete: ParsedCompleteWorker?,
    )

    private data class ParsedExpected(val supported: Boolean, val tests: Set<String>)

    private data class ParsedCompleteWorker(
        val workerId: String,
        val supported: Boolean,
        val tests: Set<String>,
        val records: List<TestDependencyRecord>,
    )
}

internal class DependencyMapStore(private val root: Path) {

    fun read(taskKey: String): CompleteDependencyMap? = runCatching {
        require(taskKey.isNotBlank())
        val directory = secureDirectory(root)
        val path = directory.resolve(fileName(taskKey))
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        val lines = readFile(path)
        require(lines.size >= STORE_HEADER_LINE_COUNT)
        require(lines[0] == FORMAT)
        val schema = rawValue(lines[1], "schema=").toInt()
        val identity = DependencyMapIdentity(
            schema,
            value(lines[2], "collector="),
            value(lines[3], "task="),
            value(lines[4], "runtime="),
            value(lines[5], "input="),
        )
        require(identity.taskKey == taskKey)
        val completedRunId = value(lines[6], "run=")
        val artifactCount = count(lines[7], "artifacts=")
        val recordCount = count(lines[8], "records=")
        val expectedChecksum = rawValue(lines[9], "checksum=")
        require(expectedChecksum.matches(SHA256_PATTERN))
        val payloadLines = lines.drop(STORE_HEADER_LINE_COUNT)
        val payload = payloadLines.joinToString(separator = "\n", postfix = "\n")
        require(sha256(payload) == expectedChecksum)
        val artifacts = ArrayList<ClassDependency>()
        val records = ArrayList<TestDependencyRecord>()
        payloadLines.forEach { line ->
            when {
                line.startsWith("artifact=") -> artifacts += parseDependency(line.removePrefix("artifact="))
                line.startsWith("record=") -> records += parseRecord(line.removePrefix("record="))
                else -> error("stored map")
            }
        }
        require(artifacts.size == artifactCount && records.size == recordCount)
        val result = CompleteDependencyMap(identity, artifacts, records, completedRunId)
        require(validated(result) == result)
        result
    }.getOrNull()

    fun write(map: CompleteDependencyMap) {
        require(validated(map) == map)
        val directory = secureDirectory(root)
        val target = directory.resolve(fileName(map.identity.taskKey))
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
        }
        val temporary = Files.createTempFile(directory, target.fileName.toString(), ".tmp")
        try {
            Files.writeString(temporary, serialize(map), StandardCharsets.UTF_8)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (failure: AtomicMoveNotSupportedException) {
                throw IllegalStateException("Atomic dependency map replacement is unavailable", failure)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun serialize(map: CompleteDependencyMap): String {
        val payload = serializePayload(map)
        return buildString {
            append(FORMAT).append('\n')
            append("schema=").append(map.identity.schemaVersion).append('\n')
            append("collector=").append(encode(map.identity.collectorVersion)).append('\n')
            append("task=").append(encode(map.identity.taskKey)).append('\n')
            append("runtime=").append(encode(map.identity.runtimeFingerprint)).append('\n')
            append("input=").append(encode(map.identity.inputFingerprint)).append('\n')
            append("run=").append(encode(map.completedRunId)).append('\n')
            append("artifacts=").append(map.artifacts.size).append('\n')
            append("records=").append(map.records.size).append('\n')
            append("checksum=").append(sha256(payload)).append('\n')
            append(payload)
        }
    }

    private fun serializePayload(map: CompleteDependencyMap): String = buildString {
        map.artifacts.sortedWith(DEPENDENCY_ORDER).forEach { dependency ->
            append("artifact=").append(serialize(dependency)).append('\n')
        }
        map.records.sortedBy { it.testClass.value }.forEach { record ->
            append("record=").append(encode(record.testClass.value)).append('|')
            append(record.dependencies.sortedWith(DEPENDENCY_ORDER).joinToString(";") { serialize(it) })
            append('\n')
        }
    }

    private fun parseRecord(value: String): TestDependencyRecord {
        val separator = value.indexOf('|')
        require(separator > 0)
        val test = TestClassId(decode(value.substring(0, separator)))
        val payload = value.substring(separator + 1)
        val dependencies = if (payload.isEmpty()) emptyList() else payload.split(';').map(::parseDependency)
        require(dependencies.size == dependencies.toSet().size)
        return TestDependencyRecord(test, dependencies.toSet())
    }

    private fun fileName(taskKey: String): String = "map-${sha256(taskKey)}.map"

    private fun validated(map: CompleteDependencyMap): CompleteDependencyMap? = DependencyMapPromotion.promote(
        null,
        DependencyMapCandidate(
            identity = map.identity,
            artifacts = map.artifacts,
            expectedWorkers = setOf(STORE_WORKER),
            expectedTestClasses = map.records.mapTo(LinkedHashSet(), TestDependencyRecord::testClass),
            collectsAllTests = true,
            workers = listOf(WorkerDependencyMap(STORE_WORKER, map.records)),
            completedRunId = map.completedRunId,
            cancelled = false,
            failed = false,
        ),
    )
}

private fun parseDependency(line: String): ClassDependency {
    val parts = line.split('|')
    require(parts.size == DEPENDENCY_PART_COUNT)
    val hash = parts[2]
    require(hash.matches(SHA256_PATTERN))
    return ClassDependency(DependencyId(decode(parts[0]), decode(parts[1])), hash)
}

private fun serialize(dependency: ClassDependency): String =
    "${encode(dependency.id.className)}|${encode(dependency.id.codeSource)}|${dependency.sha256}"

private fun secureDirectory(path: Path): Path {
    val absolute = path.toAbsolutePath().normalize()
    Files.createDirectories(absolute)
    require(!Files.isSymbolicLink(absolute))
    val real = absolute.toRealPath(LinkOption.NOFOLLOW_LINKS)
    require(Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(real) && Files.isWritable(real))
    return real
}

private fun list(directory: Path): List<Path> = Files.list(directory).use { stream ->
    stream.limit(MAX_FILES + 1L).toList().also { require(it.size <= MAX_FILES) }
}

private fun readFile(path: Path): List<String> {
    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
    val size = Files.size(path)
    require(size in 1..MAX_FILE_SIZE)
    val content = Files.readString(path, StandardCharsets.UTF_8)
    require(!content.contains('\r') && content.endsWith('\n'))
    return content.dropLast(1).split('\n').also { require(it.size <= MAX_LINES) }
}

private fun value(line: String, prefix: String): String = decode(rawValue(line, prefix))

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

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private val DEPENDENCY_ORDER = compareBy<ClassDependency>({ it.id.className }, { it.id.codeSource }, { it.sha256 })
private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
private val WORKER_DIRECTORY_PATTERN = Regex("worker-[0-9a-f]{64}")
private const val FORMAT = "format=1"
private const val TASK_MANIFEST = "task.manifest"
private const val CATALOG_MANIFEST = "catalog.manifest"
private const val EXPECTED_MANIFEST = "expected.manifest"
private const val STARTED_MANIFEST = "started.manifest"
private const val COMPLETE_MANIFEST = "complete.manifest"
private const val TASK_LINE_COUNT = 5
private const val STARTED_LINE_COUNT = 2
private const val COMPLETE_HEADER_LINE_COUNT = 3
private const val EXPECTED_HEADER_LINE_COUNT = 2
private const val CATALOG_HEADER_LINE_COUNT = 1
private const val MAP_HEADER_LINE_COUNT = 2
private const val STORE_HEADER_LINE_COUNT = 10
private const val DEPENDENCY_PART_COUNT = 3
private const val STORE_WORKER = "store"
private const val MAX_FILES = 100_000
private const val MAX_LINES = 200_000
private const val MAX_FILE_SIZE = 16L * 1024 * 1024

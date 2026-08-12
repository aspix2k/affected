package com.aspix2k.affected.build

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal data class PhpunitTestMetadata(
    val php: String,
    val phpunit: String,
    val extensions: List<String>,
    val configuration: List<String>,
    val settings: String = "",
    val autoPrependFile: String = "",
    val autoAppendFile: String = "",
    val opcachePreload: String = "",
)

internal fun readPhpunitProjectState(
    root: Path,
    packageRoot: Path,
    productionRoots: Set<Path>,
    adapter: Path,
    runtime: PhpunitTestMetadata,
    environment: Map<String, String>,
): PhpunitProjectState? = runCatching {
    val realRoot = root.securePhpunitRoot()
    val realPackage = packageRoot.securePhpunitChild(realRoot, directory = true)
    val realAdapter = adapter.securePhpunitFile()
    val phpunitExecutable = realRoot.resolve("vendor/bin/phpunit").securePhpunitChild(realRoot)
    require(supportedPhpunitRuntime(runtime))
    require(phpunitConfigurationFiles(realRoot).isEmpty())
    require(Files.isRegularFile(realRoot.resolve("composer.lock"), LinkOption.NOFOLLOW_LINKS))
    val composerRuntime = phpunitComposerRuntimeFiles(realRoot)
    val requestedRoots = productionRoots + setOf(realPackage)
    require(requestedRoots.size <= MAX_PHPUNIT_PACKAGE_ROOTS)
    val sourceRoots = requestedRoots.flatMapTo(LinkedHashSet()) { packagePath ->
        val real = packagePath.securePhpunitChild(realRoot, directory = true)
        phpunitAutoloadRoots(real, realRoot)
    }
    require(sourceRoots.none(::generatedPhpunitRoot))
    val artifacts = phpunitArtifacts(realRoot, sourceRoots)
    require(artifacts.isNotEmpty())
    val tests = phpunitTestFiles(realPackage, realRoot)
    require(tests.isNotEmpty())
    require(supportedPhpunitSources(realRoot, artifacts.keys, tests))
    val projectInputs = phpunitProjectInputs(realRoot, artifacts.keys)
    val manifests = requestedRoots.map { it.securePhpunitChild(realRoot, directory = true).resolve("composer.json") } +
        listOf(realRoot.resolve("composer.json"), realRoot.resolve("composer.lock")) + composerRuntime
    val fingerprint = phpunitFingerprint(
        realRoot,
        manifests + tests + projectInputs,
        listOf(
            PHPUNIT_METADATA_SCHEMA.toString(),
            runtime.php,
            runtime.phpunit,
            runtime.extensions.sorted().joinToString("\n"),
            runtime.configuration.sorted().joinToString("\n"),
            runtime.settings,
            phpunitFileHash(realAdapter),
            phpunitFileHash(phpunitExecutable),
            phpunitEnvironmentFingerprint(environment),
            sourceRoots.map { realRoot.relativize(it).portablePath() }.sorted().joinToString("\n"),
        ),
    )
    PhpunitProjectState(fingerprint, artifacts)
}.getOrNull()

internal fun readPhpunitRuntime(root: Path): PhpunitTestMetadata? = runCatching {
    val php = CommandRunner.capture(root.toString(), listOf("php", "-r", "echo PHP_VERSION;"), PHPUNIT_COMMAND_TIMEOUT)
        ?: return null
    val phpunit = CommandRunner.capture(
        root.toString(),
        listOf("php", "vendor/bin/phpunit", "--version"),
        PHPUNIT_COMMAND_TIMEOUT,
    ) ?: return null
    val environment = CommandRunner.capture(
        root.toString(),
        listOf(
            "php",
            "-r",
            "echo json_encode(['extensions'=>array_map(fn(string \$e)=>[\$e,phpversion(\$e)]," +
                "get_loaded_extensions()),'configuration'=>array_values(array_filter([php_ini_loaded_file()," +
                "php_ini_scanned_files()])), 'settings'=>ini_get_all(null,false)], JSON_THROW_ON_ERROR);",
        ),
        PHPUNIT_COMMAND_TIMEOUT,
        MAX_PHPUNIT_RUNTIME_BYTES,
    ) ?: return null
    parsePhpunitRuntime(php, phpunit, environment)
}.getOrNull()

internal fun parsePhpunitRuntime(
    phpOutput: String,
    phpunitOutput: String,
    environment: String,
): PhpunitTestMetadata? = runCatching {
    val php = phpOutput.trim().takeIf { it.matches(PHP_VERSION) } ?: return null
    val phpunit = PHPUNIT_VERSION.find(phpunitOutput)?.groupValues?.get(1) ?: return null
    val json = JsonParser.parseString(environment).asJsonObject
    val extensions = json.getAsJsonArray("extensions").map { it.toString() }
    val configuration = json.getAsJsonArray("configuration").flatMap { value ->
        value.asString.split(',').map(String::trim).filter(String::isNotBlank)
    }
    val rawSettings = json.getAsJsonObject("settings")
    val settings = rawSettings.entrySet().sortedBy(Map.Entry<String, JsonElement>::key)
        .joinToString("\n") { (key, value) -> "$key=$value" }
    PhpunitTestMetadata(
        php,
        phpunit,
        extensions,
        configuration,
        settings,
        rawSettings.phpunitSetting("auto_prepend_file"),
        rawSettings.phpunitSetting("auto_append_file"),
        rawSettings.phpunitSetting("opcache.preload"),
    )
        .also { require(supportedPhpunitRuntime(it)) }
}.getOrNull()

private fun com.google.gson.JsonObject.phpunitSetting(name: String): String =
    get(name)?.takeIf(JsonElement::isJsonPrimitive)?.asString.orEmpty()

private fun supportedPhpunitRuntime(runtime: PhpunitTestMetadata): Boolean {
    if (
        runtime.autoPrependFile.isNotBlank() ||
        runtime.autoAppendFile.isNotBlank() ||
        runtime.opcachePreload.isNotBlank()
    ) {
        return false
    }
    val php = PHP_VERSION.matchEntire(runtime.php)?.destructured ?: return false
    val phpunit = PHPUNIT_SUPPORTED_VERSION.matchEntire(runtime.phpunit)?.destructured ?: return false
    val phpMajor = php.component1().toInt()
    val phpMinor = php.component2().toInt()
    val phpunitMajor = phpunit.component1().toInt()
    val phpunitMinor = phpunit.component2().toInt()
    if (phpMajor != 8) return false
    return when (phpunitMajor) {
        11 -> phpMinor >= 2 && phpunitMinor == 5
        12 -> phpMinor >= 3 && phpunitMinor == 5
        13 -> phpMinor >= 4 && phpunitMinor in 2..3
        else -> false
    }
}

private fun phpunitComposerRuntimeFiles(root: Path): List<Path> {
    val vendor = root.resolve("vendor")
    val composer = vendor.resolve("composer").securePhpunitChild(root, directory = true)
    val files = ArrayList<Path>()
    files.add(vendor.resolve("autoload.php").securePhpunitChild(root))
    boundedPhpunitWalk(composer, MAX_PHPUNIT_COMPOSER_RUNTIME_FILES).forEach { requested ->
        if (Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)) return@forEach
        files.add(requested.securePhpunitChild(root))
        require(files.size <= MAX_PHPUNIT_COMPOSER_RUNTIME_FILES)
    }
    require(files.any { it.fileName.toString() == "installed.php" })
    return files.distinct()
}

private fun phpunitFileHash(path: Path): String {
    require(Files.size(path) <= MAX_PHPUNIT_FINGERPRINT_FILE_BYTES)
    return sha256(String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1))
}

private fun generatedPhpunitRoot(path: Path): Boolean = path.any { segment ->
    segment.toString().lowercase() in PHPUNIT_GENERATED_DIRECTORIES
}

private fun phpunitConfigurationFiles(root: Path): List<Path> =
    listOf("phpunit.xml", "phpunit.xml.dist", "phpunit.dist.xml").map(root::resolve).filter {
        Files.exists(it, LinkOption.NOFOLLOW_LINKS)
    }

private fun phpunitAutoloadRoots(packageRoot: Path, projectRoot: Path): Set<Path> {
    val manifest = packageRoot.resolve("composer.json").securePhpunitChild(projectRoot)
    val json = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8)).asJsonObject
    require(json.get("autoload-dev") == null)
    val autoload = json.getAsJsonObject("autoload") ?: return emptySet()
    require(autoload.keySet().all { it == "psr-4" })
    val psr4 = autoload.getAsJsonObject("psr-4") ?: return emptySet()
    require(psr4.size() <= MAX_PHPUNIT_AUTOLOAD_ENTRIES)
    return psr4.entrySet().flatMapTo(LinkedHashSet()) { (_, value) ->
        value.phpunitStrings().map { relative ->
            require(relative.isNotBlank())
            packageRoot.resolve(relative).securePhpunitChild(projectRoot, directory = true)
        }
    }
}

private fun JsonElement.phpunitStrings(): List<String> = when {
    isJsonPrimitive && asJsonPrimitive.isString -> listOf(asString)
    isJsonArray -> asJsonArray.map { value ->
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
        value.asString
    }
    else -> error("unsupported Composer autoload")
}

private fun phpunitArtifacts(root: Path, sourceRoots: Set<Path>): Map<String, String> {
    val artifacts = LinkedHashMap<String, String>()
    var total = 0L
    sourceRoots.sorted().forEach { sourceRoot ->
        boundedPhpunitWalk(sourceRoot, MAX_PHPUNIT_METADATA_ARTIFACTS).forEach { requested ->
            if (Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)) return@forEach
            val (relative, hash, size) = phpunitArtifact(root, sourceRoot, requested)
            total += size
            require(total <= MAX_PHPUNIT_SOURCE_BYTES && artifacts.put(relative, hash) == null)
            require(artifacts.size <= MAX_PHPUNIT_METADATA_ARTIFACTS)
        }
    }
    return artifacts
}

private fun phpunitArtifact(root: Path, sourceRoot: Path, requested: Path): Triple<String, String, Int> {
    require(Files.isRegularFile(requested, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(requested))
    require(requested.fileName.toString().endsWith(".php", ignoreCase = true))
    val real = requested.toRealPath(LinkOption.NOFOLLOW_LINKS)
    require(real.startsWith(sourceRoot) && real.startsWith(root))
    require(!generatedPhpunitRoot(root.relativize(real)))
    val bytes = Files.readAllBytes(real)
    val text = String(bytes, StandardCharsets.UTF_8)
    require(!PHPUNIT_UNCERTAIN_SOURCE.containsMatchIn(text))
    val hash = sha256(String(bytes, StandardCharsets.ISO_8859_1))
    return Triple(root.relativize(real).portablePath(), hash, bytes.size)
}

private fun phpunitTestFiles(packageRoot: Path, projectRoot: Path): List<Path> {
    val roots = PHPUNIT_TEST_DIRECTORIES.map(packageRoot::resolve).filter {
        Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it)
    }
    val tests = ArrayList<Path>()
    roots.forEach { testRoot ->
        boundedPhpunitWalk(testRoot, MAX_PHPUNIT_METADATA_TESTS).forEach { requested ->
            if (!requested.fileName.toString().endsWith(".php", ignoreCase = true)) return@forEach
            val test = requested.securePhpunitChild(projectRoot)
            require(!PHPUNIT_UNCERTAIN_SOURCE.containsMatchIn(Files.readString(test, StandardCharsets.UTF_8)))
            tests.add(test)
            require(tests.size <= MAX_PHPUNIT_METADATA_TESTS)
        }
    }
    return tests
}

private fun boundedPhpunitWalk(root: Path, limit: Int): List<Path> = Files.walk(root).use { paths ->
    paths.limit((limit + 1).toLong()).toList().also { files ->
        require(files.size <= limit)
        require(files.none(Files::isSymbolicLink))
    }
}

private fun phpunitProjectInputs(root: Path, artifacts: Set<String>): List<Path> {
    val files = ArrayList<Path>()
    var bytes = 0L
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
            require(!Files.isSymbolicLink(directory))
            return if (directory != root && directory.fileName.toString() in PHPUNIT_OPERATIONAL_DIRECTORIES) {
                FileVisitResult.SKIP_SUBTREE
            } else {
                FileVisitResult.CONTINUE
            }
        }

        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            require(attributes.isRegularFile && !attributes.isSymbolicLink)
            val relative = root.relativize(file.toRealPath(LinkOption.NOFOLLOW_LINKS)).portablePath()
            if (relative in artifacts) return FileVisitResult.CONTINUE
            bytes += attributes.size()
            require(bytes <= MAX_PHPUNIT_PROJECT_INPUT_BYTES)
            files.add(file.securePhpunitChild(root))
            require(files.size <= MAX_PHPUNIT_FINGERPRINT_FILES)
            return FileVisitResult.CONTINUE
        }
    })
    return files
}

private fun phpunitFingerprint(root: Path, files: List<Path>, values: List<String>): String {
    val distinctFiles = files.distinct()
    require(distinctFiles.size <= MAX_PHPUNIT_FINGERPRINT_FILES)
    val digest = MessageDigest.getInstance("SHA-256")
    distinctFiles.sorted().forEach { requested -> phpunitDigestFile(root, requested, digest) }
    values.forEach { value ->
        digest.update(value.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun phpunitDigestFile(root: Path, requested: Path, digest: MessageDigest) {
    val real = requested.securePhpunitChild(root)
    require(Files.size(real) <= MAX_PHPUNIT_FINGERPRINT_FILE_BYTES)
    digest.update(root.relativize(real).portablePath().toByteArray(StandardCharsets.UTF_8))
    digest.update(0)
    Files.newInputStream(real).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var count = input.read(buffer)
        while (count >= 0) {
            digest.update(buffer, 0, count)
            count = input.read(buffer)
        }
    }
    digest.update(0)
}

private fun phpunitEnvironmentFingerprint(environment: Map<String, String>): String {
    require(environment.size <= MAX_PHPUNIT_ENVIRONMENT_ENTRIES)
    var bytes = 0L
    return environment.toSortedMap().entries.joinToString("\n") { (key, value) ->
        bytes += key.toByteArray(StandardCharsets.UTF_8).size + value.toByteArray(StandardCharsets.UTF_8).size
        require(bytes <= MAX_PHPUNIT_ENVIRONMENT_BYTES)
        "$key=${sha256(value)}"
    }.let(::sha256)
}

private fun Path.securePhpunitRoot(): Path {
    val absolute = toAbsolutePath().normalize()
    require(Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(absolute))
    return absolute.toRealPath(LinkOption.NOFOLLOW_LINKS)
}

private fun Path.securePhpunitFile(): Path {
    val absolute = toAbsolutePath().normalize()
    require(Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(absolute))
    require(!Files.isSymbolicLink(absolute))
    return absolute.toRealPath(LinkOption.NOFOLLOW_LINKS)
}

private fun Path.securePhpunitChild(root: Path, directory: Boolean = false): Path {
    val requested = toAbsolutePath().normalize()
    require(!Files.isSymbolicLink(requested))
    if (directory) {
        require(Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(requested))
    } else {
        require(Files.isRegularFile(requested, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(requested))
    }
    return requested.toRealPath(LinkOption.NOFOLLOW_LINKS).also { require(it.startsWith(root)) }
}

private val PHP_VERSION = Regex("(8)\\.(\\d+)\\.\\d+(?:[-+][0-9A-Za-z.-]+)?")
private val PHPUNIT_VERSION = Regex("PHPUnit (\\d+\\.\\d+\\.\\d+)")
private val PHPUNIT_SUPPORTED_VERSION = Regex("(11|12|13)\\.(\\d+)\\.\\d+")
private val PHPUNIT_UNCERTAIN_SOURCE =
    Regex(
        "(?i)\\b(?:include|include_once|require|require_once|eval|file_get_contents|file_put_contents|" +
            "fopen|readfile|glob|scandir|proc_open|shell_exec|exec|system|passthru|curl_exec|fsockopen|" +
            "stream_socket_client|new\\s+SplFileObject)\\b|(?:getenv|\\${'$'}_(?:ENV|SERVER))\\s*(?:\\[|\\()",
    )
private val PHPUNIT_TEST_DIRECTORIES = listOf("tests", "test", "Tests")
private val PHPUNIT_GENERATED_DIRECTORIES = setOf("build", "cache", "generated", "vendor")
private val PHPUNIT_OPERATIONAL_DIRECTORIES = setOf(".git", ".hg", ".svn", ".idea", ".gradle", "vendor")
private const val PHPUNIT_METADATA_SCHEMA = 1
private const val PHPUNIT_COMMAND_TIMEOUT = 30L
private const val MAX_PHPUNIT_RUNTIME_BYTES = 256 * 1024
private const val MAX_PHPUNIT_ENVIRONMENT_ENTRIES = 1024
private const val MAX_PHPUNIT_ENVIRONMENT_BYTES = 1024 * 1024
private const val MAX_PHPUNIT_PACKAGE_ROOTS = 128
private const val MAX_PHPUNIT_AUTOLOAD_ENTRIES = 1024
private const val MAX_PHPUNIT_COMPOSER_RUNTIME_FILES = 4096
private const val MAX_PHPUNIT_METADATA_ARTIFACTS = 4096
private const val MAX_PHPUNIT_METADATA_TESTS = 65_536
private const val MAX_PHPUNIT_SOURCE_BYTES = 256L * 1024 * 1024
private const val MAX_PHPUNIT_FINGERPRINT_FILES = 65_536
private const val MAX_PHPUNIT_FINGERPRINT_FILE_BYTES = 16L * 1024 * 1024
private const val MAX_PHPUNIT_PROJECT_INPUT_BYTES = 64L * 1024 * 1024

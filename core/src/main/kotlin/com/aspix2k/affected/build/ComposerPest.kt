package com.aspix2k.affected.build

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

internal object ComposerPest {

    fun task(root: File, constraint: String): String? {
        if (constraint != PEST_VERSION) return null
        val text = ManifestSearch.readText(File(root, "composer.lock")) ?: return null
        val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
        val packages = lockedPackages(json) ?: return null
        val pest = packages.singleOrNull { it.stringValue("name") == PEST_PACKAGE } ?: return null
        val phpunit = packages.singleOrNull { it.stringValue("name") == PHPUNIT_PACKAGE } ?: return null
        if (!matchesPackage(pest, PEST_PIN) || !matchesPackage(phpunit, PHPUNIT_PIN)) return null
        if (!matchesPestContract(pest)) return null
        return ComposerPackages.PEST
    }

    fun suiteDirectories(root: File, directories: List<File>): Map<String, List<File>>? =
        inspectSuites(root, directories)?.suites

    fun layoutFingerprint(root: File): String? {
        when (composerDeclaresPest(root)) {
            false -> return NO_PEST_FINGERPRINT
            null -> return null
            true -> Unit
        }
        val manifests = ManifestSearch.find(root, "composer.json")
        if (manifests.isEmpty()) return null
        return inspectSuites(root, manifests.mapNotNull(File::getParentFile))?.fingerprint
    }

    private fun inspectSuites(root: File, directories: List<File>): SuiteInspection? = runCatching {
        val rootPath = root.toPath().toAbsolutePath().normalize()
        if (!root.isSafeDirectory()) return null
        val realRoot = rootPath.toRealPath()
        val budget = ScanBudget(realRoot)
        val suites = LinkedHashMap<String, List<File>>()
        for (directory in directories.distinctBy(::pathKey)) {
            val safeDirectory = safeComposerPackage(rootPath, realRoot, directory) ?: return null
            budget.mark('p', safeDirectory)
            val packageSuites = ArrayList<File>()
            for (name in TEST_DIRECTORIES) {
                val suite = directory.resolve(name)
                if (!Files.exists(suite.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
                val hasTests = budget.scanSuite(safeDirectory, suite) ?: return null
                if (hasTests) packageSuites += suite
            }
            suites[pathKey(directory)] = packageSuites
        }
        SuiteInspection(suites, budget.fingerprint())
    }.getOrNull()

    private fun matchesPestContract(pest: JsonObject): Boolean {
        val requires = jsonStringValues(pest.objectValue("require") ?: return false) ?: return false
        val conflicts = jsonStringValues(pest.objectValue("conflict") ?: return false) ?: return false
        val binaries = pest.get("bin")?.takeIf { it.isJsonArray }?.asJsonArray ?: return false
        val binary = binaries.singleOrNull()?.takeIf {
            it.isJsonPrimitive && it.asJsonPrimitive.isString
        }?.asString ?: return false
        return requires[PHPUNIT_PACKAGE] == "^13.3.0" &&
            conflicts[PHPUNIT_PACKAGE] == ">13.3.0" &&
            binaries.size() == 1 &&
            binary == "bin/pest"
    }

    private fun lockedPackages(json: JsonObject): List<JsonObject>? {
        val packages = ArrayList<JsonObject>()
        for (key in listOf("packages", "packages-dev")) {
            val value = json.get(key) ?: return null
            if (!value.isJsonArray) return null
            value.asJsonArray.forEach { element ->
                if (!element.isJsonObject) return null
                packages += element.asJsonObject
            }
        }
        return packages
    }

    private fun matchesPackage(packageJson: JsonObject, pin: PackagePin): Boolean {
        if (packageJson.stringValue("name") != pin.name || packageJson.stringValue("version") != pin.version) {
            return false
        }
        val source = packageJson.objectValue("source") ?: return false
        val dist = packageJson.objectValue("dist") ?: return false
        return source.stringValue("type") == "git" &&
            source.stringValue("url") == pin.sourceUrl &&
            source.stringValue("reference") == pin.reference &&
            dist.stringValue("type") == "zip" &&
            dist.stringValue("url") == pin.distUrl &&
            dist.stringValue("reference") == pin.reference &&
            dist.stringValue("shasum") == ""
    }

    private data class SuiteInspection(
        val suites: Map<String, List<File>>,
        val fingerprint: String,
    )

    private class ScanBudget(private val realRoot: Path) {
        private val markers = ArrayList<String>()
        private val realSuites = HashSet<Path>()
        private var entries = 0

        fun scanSuite(realPackage: Path, suite: File): Boolean? {
            if (!suite.isSafeDirectory()) return null
            val realSuite = suite.toPath().toRealPath()
            if (!realSuite.startsWith(realPackage) || !realSuite.startsWith(realRoot)) return null
            if (!realSuites.add(realSuite)) return false
            mark('d', realSuite)
            val queue = ArrayDeque<Pair<File, Int>>()
            queue += suite to 0
            var hasTests = false
            while (queue.isNotEmpty()) {
                val (directory, depth) = queue.removeFirst()
                val children = directory.listFiles() ?: return null
                for (child in children) {
                    val entry = inspectEntry(child, depth, realSuite) ?: return null
                    mark(if (entry.directory) 'd' else 'f', entry.realPath)
                    if (entry.directory) queue += child to depth + 1
                    hasTests = hasTests || entry.test
                }
            }
            return hasTests
        }

        private fun inspectEntry(child: File, depth: Int, realSuite: Path): ScannedEntry? {
            if (++entries > MAX_SUITE_ENTRIES || Files.isSymbolicLink(child.toPath())) return null
            val directory = Files.isDirectory(child.toPath(), LinkOption.NOFOLLOW_LINKS)
            val file = Files.isRegularFile(child.toPath(), LinkOption.NOFOLLOW_LINKS)
            if (!directory && !file) return null
            if (directory && depth >= MAX_SUITE_DEPTH) return null
            val real = child.toPath().toRealPath()
            if (!real.startsWith(realSuite)) return null
            return ScannedEntry(
                real,
                directory,
                file && isPestTestFile(child),
            )
        }

        fun mark(kind: Char, path: Path) {
            markers += "$kind:${realRoot.relativize(path)}"
        }

        fun fingerprint(): String {
            val digest = MessageDigest.getInstance("SHA-256")
            markers.sorted().forEach { marker ->
                digest.update(marker.replace('\\', '/').toByteArray(StandardCharsets.UTF_8))
                digest.update(0.toByte())
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        private data class ScannedEntry(val realPath: Path, val directory: Boolean, val test: Boolean)
    }

    private data class PackagePin(
        val name: String,
        val version: String,
        val sourceUrl: String,
        val reference: String,
        val distUrl: String,
    )

    private val PEST_PIN = PackagePin(
        PEST_PACKAGE,
        "v$PEST_VERSION",
        "https://github.com/pestphp/pest.git",
        "208f447a10fc416397edf00a5fc6380aa284d393",
        "https://api.github.com/repos/pestphp/pest/zipball/208f447a10fc416397edf00a5fc6380aa284d393",
    )
    private val PHPUNIT_PIN = PackagePin(
        PHPUNIT_PACKAGE,
        "13.3.0",
        "https://github.com/sebastianbergmann/phpunit.git",
        "346fcba6ce7ab89bb1b0675feac6bc29c0f7711b",
        "https://api.github.com/repos/sebastianbergmann/phpunit/zipball/346fcba6ce7ab89bb1b0675feac6bc29c0f7711b",
    )

    private val TEST_DIRECTORIES = listOf("tests", "test", "Tests")
    private const val PEST_PACKAGE = "pestphp/pest"
    private const val PHPUNIT_PACKAGE = "phpunit/phpunit"
    private const val PEST_VERSION = "5.1.1"
    private const val MAX_SUITE_DEPTH = 16
    private const val MAX_SUITE_ENTRIES = 16_384
    private const val NO_PEST_FINGERPRINT = "composer-no-pest"
}

private fun composerDeclaresPest(root: File): Boolean? {
    val text = ManifestSearch.readText(File(root, "composer.json")) ?: return null
    val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
    val dependencies = LinkedHashMap<String, String>()
    for (key in listOf("require", "require-dev")) {
        val value = json.get(key) ?: continue
        if (!value.isJsonObject) return null
        dependencies.putAll(jsonStringValues(value.asJsonObject) ?: return null)
    }
    return "pestphp/pest" in dependencies
}

private fun safeComposerPackage(root: Path, realRoot: Path, directory: File): Path? {
    val requested = directory.toPath().toAbsolutePath().normalize()
    if (!requested.startsWith(root)) return null
    var current = root
    for (component in root.relativize(requested)) {
        current = current.resolve(component)
        if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) return null
    }
    val real = requested.toRealPath()
    return real.takeIf { it.startsWith(realRoot) }
}

private fun jsonStringValues(json: JsonObject): Map<String, String>? {
    val values = LinkedHashMap<String, String>()
    for ((name, value) in json.entrySet()) {
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
        values[name] = value.asString
    }
    return values
}

private fun File.isSafeDirectory(): Boolean =
    !Files.isSymbolicLink(toPath()) && Files.isDirectory(toPath(), LinkOption.NOFOLLOW_LINKS)

private fun isPestTestFile(file: File): Boolean =
    file.extension.equals("phpt", ignoreCase = true) ||
        file.extension.equals("php", ignoreCase = true) && file.name != "Pest.php"

private fun pathKey(file: File): String = file.toPath().toAbsolutePath().normalize().toString()

private fun JsonObject.objectValue(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.stringValue(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

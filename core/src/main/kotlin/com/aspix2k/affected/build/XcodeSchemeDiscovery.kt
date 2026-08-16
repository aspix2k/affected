package com.aspix2k.affected.build

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal fun xcodeHasTests(root: File): Boolean {
    val discovery = xcodeSchemeDiscovery(root)
    return !discovery.complete || discovery.schemes.isEmpty() || discovery.schemes.any(XcodeScheme::testable)
}

internal fun xcodeSchemes(root: File): List<String> =
    xcodeSchemeDiscovery(root).takeIf(XcodeSchemeDiscovery::complete)
        ?.schemes?.map(XcodeScheme::name)?.distinct().orEmpty()

internal fun xcodeSchemeDiscovery(root: File): XcodeSchemeDiscovery =
    XcodeSchemeDiscoveryBuilder(root.toPath().toAbsolutePath().normalize()).discover()

internal data class XcodeScheme(val name: String, val testable: Boolean)

internal data class XcodeSchemeDiscovery(
    val schemes: List<XcodeScheme>,
    val complete: Boolean,
)

private data class XcodeSchemeFile(
    val file: File,
    val scheme: XcodeScheme,
    val content: ByteArray,
)

private data class XcodeDirectoryIdentity(
    val fileKey: Any?,
    val created: FileTime,
    val modified: FileTime,
    val size: Long,
    val realPath: Path,
)

private class XcodeSchemeDiscoveryBuilder(private val root: Path) {
    private val schemeFiles = ArrayList<XcodeSchemeFile>()
    private var bytes = 0L
    private var complete = true
    private var visitedEntries = 0
    private val started = System.nanoTime()
    private val visitedDirectories = LinkedHashMap<Path, XcodeDirectoryIdentity>()

    fun discover(): XcodeSchemeDiscovery {
        val projects = children(root.toFile()) ?: return incomplete()
        projects.filter { XCODE_BUNDLE.containsMatchIn(it.name) }.forEach { project ->
            if (!collectProject(project)) return incomplete()
        }
        return result()
    }

    private fun collectProject(project: File): Boolean {
        if (!secureDirectory(project)) return false
        collect(File(project, "xcshareddata/xcschemes"))
        if (!complete) return false
        val userData = File(project, "xcuserdata")
        if (!Files.exists(userData.toPath(), LinkOption.NOFOLLOW_LINKS)) return true
        if (!secureDirectory(userData)) return false
        val users = children(userData) ?: return false
        users.forEach { user ->
            if (!secureDirectory(user)) return false
            collect(File(user, "xcschemes"))
            if (!complete) return false
        }
        return true
    }

    private fun secureDirectory(directory: File): Boolean = runCatching {
        val target = directory.toPath().toAbsolutePath().normalize()
        require(target.startsWith(root))
        var current = root
        require(recordDirectory(current))
        root.relativize(target).forEach { part ->
            current = current.resolve(part)
            require(recordDirectory(current))
        }
        true
    }.getOrDefault(false)

    private fun collect(directory: File) {
        if (!complete) return
        if (expired()) {
            complete = false
            return
        }
        val path = directory.toPath()
        if (!recordExistingPath(path)) {
            complete = false
            return
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        val requested = path.toAbsolutePath().normalize()
        var parsed: List<XcodeSchemeFile>? = null
        val files = ManifestSearch.completeFiles(
            directory,
            budgetNanos = PerformanceBudgets.SCAN_TIME_NS - (System.nanoTime() - started),
            afterScan = { candidates ->
                parseSchemeFiles(candidates).also { parsed = it } != null
            },
            matches = { file ->
                file.name.endsWith(".xcscheme") &&
                    file.parentFile.toPath().toAbsolutePath().normalize() == requested
            },
        )
        val stable = parsed
        if (files == null || stable == null) {
            complete = false
            return
        }
        schemeFiles += stable
    }

    private fun incomplete(): XcodeSchemeDiscovery {
        complete = false
        return result()
    }

    private fun result(): XcodeSchemeDiscovery {
        val schemes = schemeFiles.map(XcodeSchemeFile::scheme)
        val conflictingNames = schemes.groupBy(XcodeScheme::name).values.any { definitions ->
            definitions.map(XcodeScheme::testable).distinct().size > 1
        }
        val current = complete &&
            !conflictingNames &&
            !expired() &&
            directoriesCurrent() &&
            schemeFilesCurrent()
        return XcodeSchemeDiscovery(
            schemes = schemes.distinct(),
            complete = current,
        )
    }

    private fun children(directory: File): List<File>? = runCatching {
        if (!secureDirectory(directory)) return null
        val result = ArrayList<File>()
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for (entry in entries) {
                if (++visitedEntries > PerformanceBudgets.MAX_DIRECTORIES || expired()) return null
                result += entry.toFile()
            }
        }
        result
    }.getOrNull()

    private fun recordExistingPath(path: Path): Boolean = runCatching {
        val target = path.toAbsolutePath().normalize()
        require(target.startsWith(root))
        var current = root
        require(recordDirectory(current))
        for (part in root.relativize(target)) {
            current = current.resolve(part)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return true
            require(recordDirectory(current))
        }
        true
    }.getOrDefault(false)

    private fun recordDirectory(directory: Path): Boolean {
        val identity = directoryIdentity(directory) ?: return false
        return visitedDirectories.putIfAbsent(directory, identity)?.let { previous -> previous == identity } ?: true
    }

    private fun directoriesCurrent(): Boolean =
        visitedDirectories.all { (directory, identity) ->
            !expired() && directoryIdentity(directory) == identity
        }

    private fun directoryIdentity(directory: Path): XcodeDirectoryIdentity? = runCatching {
        require(directory.isSecureXcodeDirectory())
        val attributes = Files.readAttributes(
            directory,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        require(attributes.isDirectory)
        val real = directory.toRealPath().also { require(it.startsWith(root.toRealPath())) }
        XcodeDirectoryIdentity(
            fileKey = attributes.fileKey(),
            created = attributes.creationTime(),
            modified = attributes.lastModifiedTime(),
            size = attributes.size(),
            realPath = real,
        )
    }.getOrNull()

    private fun parseSchemeFiles(files: List<File>): List<XcodeSchemeFile>? {
        if (files.size >= PerformanceBudgets.MAX_MATCHES - schemeFiles.size) return null
        val parsed = ArrayList<XcodeSchemeFile>()
        files.sortedBy(File::invariantSeparatorsPath).forEach { file ->
            if (expired()) return null
            val text = ManifestSearch.readText(file) ?: return null
            val content = text.toByteArray(StandardCharsets.UTF_8)
            bytes += content.size
            if (bytes > PerformanceBudgets.MAX_TOTAL_BYTES) return null
            val scheme = xcodeScheme(file, text) ?: return null
            parsed += XcodeSchemeFile(file, scheme, content)
        }
        return parsed
    }

    private fun expectedSchemeFingerprint(): String? = runCatching {
        val realRoot = root.toRealPath()
        val digest = MessageDigest.getInstance("SHA-256")
        schemeFiles.sortedBy { it.file.invariantSeparatorsPath }.forEach { schemeFile ->
            val realFile = schemeFile.file.toPath().toRealPath().also { require(it.startsWith(realRoot)) }
            digest.update(
                realRoot.relativize(realFile).toString().replace('\\', '/').toByteArray(StandardCharsets.UTF_8),
            )
            digest.update(0.toByte())
            digest.update(schemeFile.content)
            digest.update(0.toByte())
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }.getOrNull()

    private fun schemeFilesCurrent(): Boolean {
        val expected = expectedSchemeFingerprint() ?: return false
        val current = ManifestSearch.fingerprint(
            root.toFile(),
            schemeFiles.map(XcodeSchemeFile::file),
        ) ?: return false
        return expected == current
    }

    private fun expired(): Boolean =
        Thread.currentThread().isInterrupted || System.nanoTime() - started > PerformanceBudgets.SCAN_TIME_NS
}

private fun xcodeScheme(file: File, text: String): XcodeScheme? = runCatching {
    val factory = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
    val document = factory.newDocumentBuilder().parse(InputSource(StringReader(text)))
    require(document.documentElement.tagName == "Scheme")
    XcodeScheme(
        name = file.name.removeSuffix(".xcscheme"),
        testable = document.getElementsByTagName("TestAction").let { actions ->
            (0 until actions.length).any { index ->
                val action = actions.item(index) as Element
                val testables = action.getElementsByTagName("TestableReference")
                val activeTestable = (0 until testables.length).any { testableIndex ->
                    val reference = testables.item(testableIndex) as Element
                    reference.getAttribute("skipped").equals("YES", ignoreCase = true).not()
                }
                activeTestable || action.getElementsByTagName("TestPlanReference").length > 0
            }
        },
    )
}.getOrNull()

internal fun Path.isSecureXcodeDirectory(): Boolean =
    !Files.isSymbolicLink(this) &&
        Files.isDirectory(this, LinkOption.NOFOLLOW_LINKS) &&
        Files.isReadable(this)

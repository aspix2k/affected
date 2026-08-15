package com.aspix2k.affected.build

import com.google.gson.JsonParser
import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CancellationException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipFile

internal fun nativeMtpAssetsProof(
    root: String,
    project: String,
    expectedArchiveIdentity: String = NATIVE_MTP_ARCHIVE_IDENTITY,
): Boolean = nativeMtpAssetsEvidence(root, project)?.archiveIdentity == expectedArchiveIdentity

internal fun nativeMtpArchiveIdentity(root: String, project: String): String? =
    nativeMtpAssetsEvidence(root, project)?.archiveIdentity

private fun nativeMtpAssetsEvidence(root: String, project: String): NativeMtpAssetsEvidence? =
    nativeMtpAssetOrNull {
        val rootPath = Path.of(root).toAbsolutePath().normalize().toRealPath()
        val projectPath = rootPath.resolve(project).normalize().toRealPath()
        require(projectPath.startsWith(rootPath))
        val projectDirectory = requireNotNull(projectPath.parent)
        val lockPath = projectDirectory.resolve("packages.lock.json")
        val assetsPath = projectDirectory.resolve("obj/project.assets.json")
        require(symlinkFreeDotnetPath(rootPath, lockPath) && symlinkFreeDotnetPath(rootPath, assetsPath))
        val expected = nativeMtpLockedPackages(lockPath) ?: return null
        val assets = nativeMtpAssetsPackages(assetsPath, expected) ?: return null
        val archives = nativeMtpPackageMetadata(assets.packageRoot, assets.packages) ?: return null
        val archiveIdentity = nativeMtpArchiveIdentity(archives) ?: return null
        NativeMtpAssetsEvidence(archiveIdentity)
    }

private fun nativeMtpLockedPackages(lockPath: Path): Map<String, NativeMtpPackage>? = nativeMtpAssetOrNull {
    require(Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS))
    val lock = JsonParser.parseString(ManifestSearch.readText(lockPath.toFile()) ?: return null).asJsonObject
    val dependencies = lock.getAsJsonObject("dependencies")
    require(dependencies.keySet() == setOf("net10.0"))
    dependencies.getAsJsonObject("net10.0").entrySet().associate { (name, value) ->
        val item = value.asJsonObject
        val version = item.get("resolved").asString
        "$name/$version" to NativeMtpPackage(name.lowercase(), version, item.get("contentHash").asString)
    }.also { require(it.isNotEmpty() && it.size <= MAX_MTP_PACKAGES) }
}

private fun nativeMtpAssetsPackages(
    assetsPath: Path,
    expected: Map<String, NativeMtpPackage>,
): NativeMtpAssets? = nativeMtpAssetOrNull {
    require(Files.isRegularFile(assetsPath, LinkOption.NOFOLLOW_LINKS))
    val assets = JsonParser.parseString(ManifestSearch.readText(assetsPath.toFile()) ?: return null).asJsonObject
    require(assets.get("version").let { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asInt == 4 })
    val targets = assets.getAsJsonObject("targets")
    require(targets.keySet() == setOf("net10.0"))
    require(targets.getAsJsonObject("net10.0").keySet() == expected.keys)
    val libraries = assets.getAsJsonObject("libraries")
    require(libraries.keySet() == expected.keys)
    val packages = libraries.entrySet().map { (key, value) ->
        val actual = value.asJsonObject
        val packageValue = expected.getValue(key)
        require(actual.get("type").asString == "package")
        require(actual.get("sha512").asString == packageValue.contentHash)
        require(actual.get("path").asString == "${packageValue.name}/${packageValue.version}")
        val files = actual.getAsJsonArray("files").mapTo(LinkedHashSet()) { entry ->
            normalizedNativeMtpPackagePath(entry.asString)
        }
        require(files.isNotEmpty() && files.size <= MAX_MTP_PACKAGE_FILES)
        packageValue.copy(files = files)
    }
    val restore = assets.getAsJsonObject("project").getAsJsonObject("restore")
    require(restore.getAsJsonObject("sources").keySet() == setOf(NUGET_ORG_SOURCE))
    val framework = assets.getAsJsonObject("project").getAsJsonObject("frameworks")
    require(framework.keySet() == setOf("net10.0"))
    val frameworkDependencies = framework.getAsJsonObject("net10.0").getAsJsonObject("dependencies")
    require(frameworkDependencies.keySet() == setOf("xunit.v3"))
    val xunit = frameworkDependencies.getAsJsonObject("xunit.v3")
    require(xunit.get("target").asString == "Package")
    require(xunit.get("version").asString == "[4.0.0, 4.0.0]")

    val packageFolders = assets.getAsJsonObject("packageFolders")
    require(packageFolders.size() == 1)
    val packageRoot = Path.of(packageFolders.keySet().single()).toAbsolutePath().normalize()
    require(Files.isDirectory(packageRoot, LinkOption.NOFOLLOW_LINKS) && packageRoot.toRealPath() == packageRoot)
    NativeMtpAssets(packageRoot, packages)
}

private fun nativeMtpPackageMetadata(
    packageRoot: Path,
    expected: Collection<NativeMtpPackage>,
): List<NativeMtpArchive>? = nativeMtpAssetOrNull {
    var totalArchiveBytes = 0L
    expected.map { packageValue ->
        val packageDirectory = packageRoot.resolve(packageValue.name).resolve(packageValue.version)
        require(packageDirectory.startsWith(packageRoot) && symlinkFreeDotnetPath(packageRoot, packageDirectory))
        require(Files.isDirectory(packageDirectory, LinkOption.NOFOLLOW_LINKS))
        val metadata = packageDirectory.resolve(".nupkg.metadata")
        require(Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS) && metadata.toRealPath() == metadata)
        val json = JsonParser.parseString(ManifestSearch.readText(metadata.toFile()) ?: return null).asJsonObject
        require(json.get("version").let { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asInt == 2 })
        require(json.get("contentHash").asString == packageValue.contentHash)
        require(json.get("source").asString == NUGET_ORG_SOURCE)
        val archiveName = "${packageValue.name}.${packageValue.version}.nupkg"
        val archive = packageDirectory.resolve(archiveName)
        val hashFile = packageDirectory.resolve("$archiveName.sha512")
        require(Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS) && archive.toRealPath() == archive)
        require(Files.isRegularFile(hashFile, LinkOption.NOFOLLOW_LINKS) && hashFile.toRealPath() == hashFile)
        val archiveBytes = Files.size(archive)
        require(archiveBytes in 1..MAX_MTP_NUPKG_BYTES)
        totalArchiveBytes += archiveBytes
        require(totalArchiveBytes <= MAX_MTP_NUPKG_TOTAL_BYTES)
        val archiveHash = ManifestSearch.readText(hashFile.toFile())?.trim()
        require(!archiveHash.isNullOrBlank() && sha512Base64(archive) == archiveHash)
        require(nativeMtpExtractedFilesMatchArchive(packageDirectory, archive, packageValue))
        NativeMtpArchive("${packageValue.name}/${packageValue.version}", archive)
    }
}

private fun nativeMtpArchiveIdentity(archives: List<NativeMtpArchive>): String? = nativeMtpAssetOrNull {
    require(archives.isNotEmpty() && archives.size <= MAX_MTP_PACKAGES)
    val manifest = archives
        .sortedBy(NativeMtpArchive::key)
        .joinToString(separator = "\n", postfix = "\n") { archive ->
            "${archive.key}=${sha256Hex(archive.path)}"
        }
    sha256Hex(manifest.toByteArray(Charsets.UTF_8))
}

private fun nativeMtpExtractedFilesMatchArchive(
    packageDirectory: Path,
    archive: Path,
    packageValue: NativeMtpPackage,
): Boolean = nativeMtpAssetOrNull {
    val extracted = LinkedHashSet<String>()
    val caseInsensitive = HashSet<String>()
    var totalBytes = 0L
    ZipFile(archive.toFile()).use { zip ->
        val entries = zip.entries()
        var entryCount = 0
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            require(++entryCount <= MAX_MTP_PACKAGE_FILES)
            if (!entry.isDirectory) {
                val archived = normalizedNativeMtpPackagePath(entry.name)
                val relative = nativeMtpExtractedPackagePath(archived, packageValue)
                require(caseInsensitive.add(relative.lowercase()))
                val size = entry.size
                require(size in 0..MAX_MTP_ARCHIVE_ENTRY_BYTES)
                totalBytes += size
                require(totalBytes <= MAX_MTP_ARCHIVE_EXPANDED_BYTES)
                if (!nativeMtpPackagingEntry(relative)) {
                    val target = packageDirectory.resolve(relative).normalize()
                    require(target.startsWith(packageDirectory) && symlinkFreeDotnetPath(packageDirectory, target))
                    require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && target.toRealPath() == target)
                    require(Files.size(target) == size)
                    val archiveDigest = zip.getInputStream(entry).use(::sha256)
                    val extractedDigest = Files.newInputStream(target).use(::sha256)
                    require(archiveDigest.contentEquals(extractedDigest))
                    require(extracted.add(relative))
                }
            }
        }
        require(entryCount > 0)
    }
    extracted += ".nupkg.metadata"
    extracted += "${packageValue.name}.${packageValue.version}.nupkg.sha512"
    require(extracted == packageValue.files)
    val nuspec = extracted.single { relative ->
        '/' !in relative && relative.endsWith(".nuspec", ignoreCase = true)
    }
    val text = ManifestSearch.readText(packageDirectory.resolve(nuspec).toFile()) ?: return false
    val document = secureDotnetDocument(text)
    require(document.singleMtpNuspecValue("id").equals(packageValue.name, ignoreCase = true))
    require(document.singleMtpNuspecValue("version") == packageValue.version)
    true
} ?: false

private fun nativeMtpPackagingEntry(relative: String): Boolean =
    relative == "[Content_Types].xml" || relative.startsWith("_rels/") ||
        relative.startsWith("package/services/metadata/core-properties/")

private fun nativeMtpExtractedPackagePath(relative: String, packageValue: NativeMtpPackage): String =
    if ('/' !in relative && relative.endsWith(".nuspec", ignoreCase = true)) {
        require(relative.substringBeforeLast('.').equals(packageValue.name, ignoreCase = true))
        "${packageValue.name}.nuspec"
    } else {
        relative
    }

private fun org.w3c.dom.Document.singleMtpNuspecValue(name: String): String {
    val elements = getElementsByTagNameNS("*", name)
    require(elements.length == 1)
    return elements.item(0).textContent.trim()
}

private fun normalizedNativeMtpPackagePath(raw: String): String {
    val value = raw.replace('\\', '/')
    require(value.isNotBlank() && !value.startsWith('/'))
    val path = Path.of(value).normalize()
    require(!path.isAbsolute && path.none { segment -> segment.toString() == ".." })
    val normalized = path.joinToString("/") { it.toString() }
    require(normalized.isNotBlank() && normalized == value.trimEnd('/'))
    return normalized
}

private fun sha256(input: java.io.InputStream): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(MTP_DIGEST_BUFFER_BYTES)
    while (true) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("package digest interrupted")
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest()
}

private fun sha256Hex(path: Path): String = Files.newInputStream(path).buffered().use { input ->
    sha256(input).joinToString("") { byte -> "%02x".format(byte) }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }

private fun sha512Base64(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-512")
    Files.newInputStream(path).buffered().use { input ->
        val buffer = ByteArray(MTP_DIGEST_BUFFER_BYTES)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("package digest interrupted")
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return Base64.getEncoder().encodeToString(digest.digest())
}

private inline fun <T> nativeMtpAssetOrNull(block: () -> T): T? = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: ProcessCanceledException) {
    throw error
} catch (error: InterruptedException) {
    Thread.currentThread().interrupt()
    throw error
} catch (_: Exception) {
    null
}

private data class NativeMtpPackage(
    val name: String,
    val version: String,
    val contentHash: String,
    val files: Set<String> = emptySet(),
)

private data class NativeMtpAssets(val packageRoot: Path, val packages: List<NativeMtpPackage>)
private data class NativeMtpArchive(val key: String, val path: Path)
private data class NativeMtpAssetsEvidence(val archiveIdentity: String)

private const val MAX_MTP_PACKAGES = 128
private const val MAX_MTP_NUPKG_BYTES = 64L * 1024 * 1024
private const val MAX_MTP_NUPKG_TOTAL_BYTES = 512L * 1024 * 1024
private const val MAX_MTP_PACKAGE_FILES = 16_384
private const val MAX_MTP_ARCHIVE_ENTRY_BYTES = 128L * 1024 * 1024
private const val MAX_MTP_ARCHIVE_EXPANDED_BYTES = 512L * 1024 * 1024
private const val MTP_DIGEST_BUFFER_BYTES = 64 * 1024
private const val NUGET_ORG_SOURCE = "https://api.nuget.org/v3/index.json"
private const val NATIVE_MTP_ARCHIVE_IDENTITY =
    "b20f9f40b560e9b53a3fe98937474cb628592f0c863f906fa76cdf8c744475ee"

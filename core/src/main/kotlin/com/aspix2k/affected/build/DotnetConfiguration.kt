package com.aspix2k.affected.build

import com.google.gson.JsonParser
import java.io.File
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal fun dotnetManifestFingerprint(root: File): String? = ManifestSearch.fingerprint(
    root,
    listOf("csproj", "fsproj", "vbproj", "props", "targets", "sln", "slnx", "runsettings")
        .flatMap { ManifestSearch.findByExtension(root, it) } +
        listOf("global.json", "NuGet.Config", "Directory.Packages.props")
            .flatMap { ManifestSearch.find(root, it) },
)

internal fun unsupportedDotnetConfiguration(root: File): Boolean {
    val named = DOTNET_UNSUPPORTED_CONFIG_NAMES.flatMap { ManifestSearch.find(root, it) }
    val byExtension = DOTNET_UNSUPPORTED_CONFIG_EXTENSIONS.flatMap { ManifestSearch.findByExtension(root, it) }
    if (named.isNotEmpty() || byExtension.isNotEmpty()) return true
    val manifests = listOf("csproj", "fsproj", "vbproj", "props", "targets")
        .flatMap { ManifestSearch.findByExtension(root, it) }
    return manifests.any { manifest ->
        val text = ManifestSearch.readText(manifest) ?: return true
        !supportedDotnetManifest(manifest, text) ||
            DOTNET_UNSUPPORTED_MSBUILD_SETTINGS.any { setting -> text.contains(setting, ignoreCase = true) }
    }
}

internal fun hasExternalDotnetConfiguration(root: Path): Boolean {
    var parent = root.parent
    while (parent != null) {
        if (DOTNET_PARENT_CONFIG_NAMES.any { name ->
                Files.exists(parent.resolve(name), LinkOption.NOFOLLOW_LINKS)
            }
        ) {
            return true
        }
        parent = parent.parent
    }
    return false
}

internal fun hasDotnetEnvironmentOverrides(environment: Map<String, String> = System.getenv()): Boolean =
    environment.any { (name, value) ->
        value.isNotBlank() && DOTNET_UNSUPPORTED_ENVIRONMENT.any { it.equals(name, ignoreCase = true) }
    }

internal fun dotnetImportFingerprint(
    root: Path,
    assets: Path,
    sdk: String,
    sdkPath: String,
    preprocessedProject: String,
): String? = runCatching {
    require(preprocessedProject.toByteArray(StandardCharsets.UTF_8).size <= DOTNET_PREPROCESS_MAX_BYTES)
    val realRoot = root.toAbsolutePath().normalize().toRealPath()
    val sdkDirectory = Path.of(sdkPath).toAbsolutePath().normalize().secureRealDirectory()
    require(sdkDirectory.fileName.toString() == "Sdks")
    val sdkVersionDirectory = requireNotNull(sdkDirectory.parent).also { require(it.fileName.toString() == sdk) }
    val sdkRoot = requireNotNull(sdkVersionDirectory.parent).also { require(it.fileName.toString() == "sdk") }
    val dotnetDirectory = requireNotNull(sdkRoot.parent)
    val manifestBand = sdk.split('.').also { require(it.size >= 3) }
        .let { "${it[0]}.${it[1]}.100" }
    val manifestRoot = dotnetDirectory.resolve("sdk-manifests").resolve(manifestBand).secureRealDirectoryOrNull()
    val packages = dotnetPackageIndex(assets) ?: return null
    val imports = preprocessedDotnetImports(preprocessedProject) ?: return null
    require(imports.isNotEmpty() && imports.size <= MAX_DOTNET_IMPORTS)
    var totalBytes = 0L
    val fingerprints = imports.map { imported ->
        val requested = imported.toAbsolutePath().normalize()
        require(requested.isSecureImportedFile())
        val real = requested.toRealPath().also { require(it == requested) }
        when {
            real.startsWith(realRoot) -> require(supportedDotnetImportedManifest(real)) { real.toString() }
            real.startsWith(sdkVersionDirectory) -> Unit
            manifestRoot != null && real.startsWith(manifestRoot) -> Unit
            else -> require(packages.contains(real))
        }
        totalBytes += Files.size(real)
        require(totalBytes <= MAX_DOTNET_IMPORT_BYTES)
        "${real.toString().replace('\\', '/')}=${dotnetFileSha256(real)}"
    }
    sha256(fingerprints.sorted().joinToString("\n"))
}.getOrNull()

private fun supportedDotnetManifest(manifest: File, text: String): Boolean =
    supportedDotnetManifest(manifest.toPath(), text, allowImports = false)

private fun supportedDotnetImportedManifest(manifest: Path): Boolean {
    if (manifest.fileName.toString().substringAfterLast('.', "").lowercase() !in DOTNET_IMPORTED_EXTENSIONS) {
        return false
    }
    val text = ManifestSearch.readText(manifest.toFile()) ?: return false
    return supportedDotnetManifest(manifest, text, allowImports = true)
}

private fun supportedDotnetManifest(manifest: Path, text: String, allowImports: Boolean): Boolean = runCatching {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
    val document = factory.newDocumentBuilder().parse(
        org.xml.sax.InputSource(StringReader(text.removePrefix("\uFEFF"))),
    )
    val root = document.documentElement
    require(root.localName == "Project")
    if (manifest.fileName.toString().substringAfterLast('.', "").lowercase() in DOTNET_PROJECT_EXTENSIONS) {
        require(root.getAttribute("Sdk") == "Microsoft.NET.Sdk")
    }
    val unsupportedElements = if (allowImports) {
        DOTNET_UNSUPPORTED_MSBUILD_ELEMENTS - "Import"
    } else {
        DOTNET_UNSUPPORTED_MSBUILD_ELEMENTS
    }
    require(unsupportedElements.none { name ->
        document.getElementsByTagNameNS("*", name).length > 0
    })
    true
}.getOrDefault(false)

private fun preprocessedDotnetImports(raw: String): Set<Path>? = runCatching {
    val lines = raw.lineSequence().map { it.removeSuffix("\r") }.toList()
    val imports = LinkedHashSet<Path>()
    var closingImports = 0
    var index = 0
    while (index + 3 < lines.size) {
        if (lines[index].trim() != "<!--" || !lines[index + 1].isDotnetPreprocessSeparator()) {
            index++
            continue
        }
        val end = (index + 2 until lines.size).firstOrNull { lines[it].isDotnetPreprocessSeparator() }
            ?: return null
        require(end + 1 < lines.size && lines[end + 1].trim() == "-->")
        val block = lines.subList(index + 2, end)
        val marker = block.joinToString("\n").trimStart()
        when {
            DOTNET_PREPROCESS_IMPORT.containsMatchIn(marker) -> {
                val blank = block.indexOfLast(String::isBlank)
                require(blank >= 0)
                val value = block.subList(blank + 1, block.size).joinToString("\n").trim()
                val path = Path.of(value)
                require(path.isAbsolute)
                imports.add(path.toAbsolutePath().normalize())
                require(imports.size <= MAX_DOTNET_IMPORTS)
            }
            marker.startsWith("</Import>") -> closingImports++
        }
        index = end + 2
    }
    require(imports.size == closingImports)
    imports
}.getOrNull()

private fun String.isDotnetPreprocessSeparator(): Boolean {
    val value = trim()
    return value.length == DOTNET_PREPROCESS_SEPARATOR_LENGTH && value.all { it == '=' }
}

private fun dotnetPackageIndex(assets: Path): DotnetPackageIndex? = runCatching {
    require(assets.isSecureImportedFile() && Files.size(assets) in 1L..DOTNET_PREPROCESS_MAX_BYTES.toLong())
    val json = JsonParser.parseString(Files.readString(assets, StandardCharsets.UTF_8)).asJsonObject
    val roots = json.getAsJsonObject("packageFolders").keySet().map { raw ->
        Path.of(raw).toAbsolutePath().normalize().secureRealDirectory()
    }
    require(roots.isNotEmpty())
    val files = LinkedHashSet<String>()
    json.getAsJsonObject("libraries").entrySet().forEach { (_, value) ->
        val library = value.asJsonObject
        if (library.get("type")?.asString != "package") return@forEach
        val packagePath = normalizedDotnetPackagePath(library.get("path")?.asString.orEmpty())
        library.getAsJsonArray("files")?.forEach { file ->
            val relative = normalizedDotnetPackagePath(file.asString)
            files.add("$packagePath/$relative")
            require(files.size <= MAX_DOTNET_PACKAGE_FILES)
        }
    }
    require(files.isNotEmpty())
    DotnetPackageIndex(roots, files)
}.getOrNull()

private fun normalizedDotnetPackagePath(raw: String): String {
    val parts = raw.replace('\\', '/').split('/').filter(String::isNotEmpty)
    require(parts.isNotEmpty() && !raw.startsWith('/') && parts.none { it == "." || it == ".." || ':' in it })
    return parts.joinToString("/")
}

private data class DotnetPackageIndex(val roots: List<Path>, val files: Set<String>) {
    fun contains(path: Path): Boolean = roots.any { root ->
        if (!path.startsWith(root)) return@any false
        val relative = root.relativize(path).joinToString("/") { it.toString() }
        relative in files
    }
}

private fun Path.secureRealDirectory(): Path {
    require(Files.isDirectory(this, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(this))
    return toRealPath().also { require(it == this) }
}

private fun Path.secureRealDirectoryOrNull(): Path? = runCatching { secureRealDirectory() }.getOrNull()

private fun Path.isSecureImportedFile(): Boolean =
    Files.isRegularFile(this, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(this) && Files.isReadable(this)

private val DOTNET_UNSUPPORTED_CONFIG_NAMES = setOf("xunit.runner.json", "nunit.engine.addins")
private val DOTNET_UNSUPPORTED_CONFIG_EXTENSIONS = setOf("runsettings", "testsettings", "testrunconfig")
private val DOTNET_UNSUPPORTED_MSBUILD_SETTINGS = setOf(
    "CustomAfterMicrosoftCommonProps",
    "CustomAfterMicrosoftCommonTargets",
    "CustomBeforeMicrosoftCommonProps",
    "CustomBeforeMicrosoftCommonTargets",
    "EnableMSTestRunner",
    "EnableNUnitRunner",
    "Microsoft.Testing.Platform",
    "MSTest.Sdk",
    "MSBuildProjectExtensionsPath",
    "MSBuildSDKsPath",
    "RunSettingsFilePath",
    "TestingPlatformDotnetTestSupport",
    "VSTestTestAdapterPath",
    "VSTestTestCaseFilter",
    "TestingPlatformCommandLineArguments",
    "UseMicrosoftTestingPlatformRunner",
)
private val DOTNET_UNSUPPORTED_MSBUILD_ELEMENTS = setOf("Import", "Sdk", "Target", "UsingTask")
private val DOTNET_PROJECT_EXTENSIONS = setOf("csproj", "fsproj", "vbproj")
private val DOTNET_IMPORTED_EXTENSIONS = DOTNET_PROJECT_EXTENSIONS + setOf("props", "targets")
private val DOTNET_UNSUPPORTED_ENVIRONMENT = setOf(
    "DOTNET_MSBUILD_SDK_RESOLVER_SDKS_DIR",
    "CustomAfterMicrosoftCommonProps",
    "CustomAfterMicrosoftCommonTargets",
    "CustomBeforeMicrosoftCommonProps",
    "CustomBeforeMicrosoftCommonTargets",
    "EnableMSTestRunner",
    "EnableNUnitRunner",
    "MicrosoftTestingPlatformDotnetTestSupport",
    "MSBuildProjectExtensionsPath",
    "MSBuildSDKsPath",
    "TestingPlatformDotnetTestSupport",
    "UseMicrosoftTestingPlatformRunner",
)
private val DOTNET_PARENT_CONFIG_NAMES = setOf(
    "Directory.Build.props",
    "Directory.Build.targets",
    "Directory.Packages.props",
    "global.json",
    "NuGet.Config",
)
private const val DOTNET_PREPROCESS_MAX_BYTES = 16 * 1024 * 1024
private const val DOTNET_PREPROCESS_SEPARATOR_LENGTH = 140
private const val MAX_DOTNET_IMPORTS = 4096
private const val MAX_DOTNET_PACKAGE_FILES = 131_072
private const val MAX_DOTNET_IMPORT_BYTES = 128L * 1024 * 1024
private val DOTNET_PREPROCESS_IMPORT = Regex("^<Import(?:\\s|>)")

package com.aspix2k.affected.build

import com.google.gson.JsonElement
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
    require(sdk.matches(DOTNET_STABLE_SDK_VERSION))
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
    val document = secureDotnetDocument(text)
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

internal fun supportsNativeXunit4Mtp(root: String, project: String): Boolean = runCatching {
    val rootPath = Path.of(root).toAbsolutePath().normalize()
    require(Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(rootPath))
    val realRoot = rootPath.toRealPath()
    require(nativeMtpGlobalJson(realRoot.resolve("global.json")))
    require(!hasExternalDotnetConfiguration(realRoot))
    require(!hasDotnetEnvironmentOverrides())

    val projectPath = realRoot.resolve(project).normalize()
    require(projectPath.startsWith(realRoot) && projectPath.fileName.toString().substringAfterLast('.', "") == "csproj")
    require(projectPath.isSecureImportedFile() && projectPath.toRealPath() == projectPath)
    require(mtpConfigurationPathIsClean(realRoot, requireNotNull(projectPath.parent)))
    val projectText = ManifestSearch.readText(projectPath.toFile()) ?: return false
    require(supportedDotnetManifest(projectPath, projectText, allowImports = false))
    require(DOTNET_UNSUPPORTED_MSBUILD_SETTINGS.none { projectText.contains(it, ignoreCase = true) })
    val document = secureDotnetDocument(projectText)
    require(document.documentElement.attributes.length == 1)
    require(document.documentElement.getAttribute("Sdk") == "Microsoft.NET.Sdk")
    val elements = document.getElementsByTagName("*")
    repeat(elements.length) { index ->
        val element = elements.item(index)
        require(element.localName in MTP_ALLOWED_PROJECT_ELEMENTS)
        if (element.localName !in setOf("Project", "PackageReference")) {
            require(element.attributes.length == 0)
        }
    }
    require(document.singleElementText("TargetFramework") == "net10.0")
    require(document.singleElementText("OutputType") == "Exe")
    require(document.singleElementText("RestorePackagesWithLockFile") == "true")
    require(document.singleElementText("RestoreLockedMode") == "true")
    val references = document.getElementsByTagNameNS("*", "PackageReference")
    require(references.length == 1)
    val reference = references.item(0)
    require(reference.attributes.length == 2)
    require(reference.attributes.getNamedItem("Include")?.nodeValue == "xunit.v3")
    require(reference.attributes.getNamedItem("Version")?.nodeValue == "[4.0.0]")
    require(reference.childNodes.length == 0)

    val lock = projectPath.parent.resolve("packages.lock.json")
    require(lock.isSecureImportedFile() && lock.toRealPath() == lock)
    val lockText = ManifestSearch.readText(lock.toFile()) ?: return false
    val lockJson = JsonParser.parseString(lockText)
    require(sha256(canonicalDotnetJson(lockJson)) == XUNIT4_MTP_LOCK_IDENTITY)
    true
}.getOrDefault(false)

internal fun nativeMtpGlobalJson(path: Path): Boolean = runCatching {
    require(path.isSecureImportedFile() && path.toRealPath() == path)
    val text = ManifestSearch.readText(path.toFile()) ?: return false
    val root = JsonParser.parseString(text).asJsonObject
    require(root.keySet() == setOf("sdk", "test"))
    val sdk = root.getAsJsonObject("sdk")
    require(sdk.keySet() == setOf("version", "rollForward", "allowPrerelease"))
    require(sdk.get("version").isExactString(NATIVE_MTP_SDK))
    require(sdk.get("rollForward").isExactString("disable"))
    require(sdk.get("allowPrerelease")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean } == true)
    require(!sdk.get("allowPrerelease").asBoolean)
    val test = root.getAsJsonObject("test")
    require(test.keySet() == setOf("runner"))
    require(test.get("runner").isExactString("Microsoft.Testing.Platform"))
    true
}.getOrDefault(false)

private fun JsonElement?.isExactString(value: String): Boolean =
    this?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == value } == true

private fun JsonElement?.isExactString(pattern: Regex): Boolean =
    this?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.matches(pattern) } == true

private fun mtpConfigurationPathIsClean(root: Path, projectDirectory: Path): Boolean = runCatching {
    var current = projectDirectory
    while (true) {
        require(current.startsWith(root))
        Files.newDirectoryStream(current).use { entries ->
            var count = 0
            entries.forEach { entry ->
                require(++count <= MAX_MTP_CONFIGURATION_ENTRIES)
                val name = entry.fileName.toString().lowercase()
                require(name !in MTP_CONFIGURATION_NAMES && !name.endsWith(".runsettings"))
            }
        }
        if (current == root) break
        current = requireNotNull(current.parent)
    }
    val properties = Files.newDirectoryStream(projectDirectory).use { entries ->
        entries.firstOrNull { entry -> entry.fileName.toString().equals("Properties", ignoreCase = true) }
    }
    if (properties != null) {
        require(Files.isDirectory(properties, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(properties))
        Files.newDirectoryStream(properties).use { entries ->
            var count = 0
            entries.forEach { entry ->
                require(++count <= MAX_MTP_CONFIGURATION_ENTRIES)
                require(!entry.fileName.toString().equals("launchSettings.json", ignoreCase = true))
            }
        }
    }
    true
}.getOrDefault(false)

private fun org.w3c.dom.Document.singleElementText(name: String): String {
    val elements = getElementsByTagNameNS("*", name)
    require(elements.length == 1)
    return elements.item(0).textContent.trim()
}

private fun canonicalDotnetJson(element: JsonElement): String = when {
    element.isJsonObject -> element.asJsonObject.entrySet().sortedBy { it.key }
        .joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
            "${com.google.gson.JsonPrimitive(key)}:${canonicalDotnetJson(value)}"
        }
    element.isJsonArray -> element.asJsonArray.joinToString(
        separator = ",",
        prefix = "[",
        postfix = "]",
        transform = ::canonicalDotnetJson,
    )
    else -> element.toString()
}

internal fun secureDotnetDocument(text: String): org.w3c.dom.Document {
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
    return factory.newDocumentBuilder().parse(
        org.xml.sax.InputSource(StringReader(text.removePrefix("\uFEFF"))),
    )
}

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
    "MSBuildUserExtensionsPath",
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
    "MSBuildUserExtensionsPath",
    "RestoreAdditionalProjectSources",
    "RestoreConfigFile",
    "RestoreSources",
    "TestingPlatformCommandLineArguments",
    "TESTINGPLATFORM_EXITCODE_IGNORE",
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
private val MTP_CONFIGURATION_NAMES = setOf(
    "directory.build.props",
    "directory.build.targets",
    "directory.packages.props",
    "nuget.config",
    "testconfig.json",
    "xunit.runner.json",
)
private val MTP_ALLOWED_PROJECT_ELEMENTS = setOf(
    "Project",
    "PropertyGroup",
    "TargetFramework",
    "OutputType",
    "ImplicitUsings",
    "Nullable",
    "RestorePackagesWithLockFile",
    "RestoreLockedMode",
    "ItemGroup",
    "PackageReference",
)
private const val MAX_MTP_CONFIGURATION_ENTRIES = 4_096
private const val DOTNET_PREPROCESS_MAX_BYTES = 16 * 1024 * 1024
private const val DOTNET_PREPROCESS_SEPARATOR_LENGTH = 140
private const val MAX_DOTNET_IMPORTS = 4096
private const val MAX_DOTNET_PACKAGE_FILES = 131_072
private const val MAX_DOTNET_IMPORT_BYTES = 128L * 1024 * 1024
private val DOTNET_PREPROCESS_IMPORT = Regex("^<Import(?:\\s|>)")
private val DOTNET_STABLE_SDK_VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
private val NATIVE_MTP_SDK = Regex("10\\.0\\.400")
private const val XUNIT4_MTP_LOCK_IDENTITY = "903449ba76017a825dfe05c28d4e46c9459b14c3da38d541b93c95cf404a6f09"

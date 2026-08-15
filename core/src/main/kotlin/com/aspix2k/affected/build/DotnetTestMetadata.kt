package com.aspix2k.affected.build

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.PathManager
import org.w3c.dom.Element
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal data class DotnetProjectMetadata(
    val project: String,
    val targetFramework: String,
    val testAssembly: Path,
    val artifactPaths: List<Path>,
    val productionArtifactPaths: Set<Path>,
    val trustedFrameworkArtifactPaths: Set<Path>,
    val identity: String,
    val sdkMajor: Int,
)

internal data class DotnetAnalyzedState(
    val identity: String,
    val testAssemblySha256: String,
    val artifacts: Map<String, DotnetImpactArtifact>,
    val classes: Map<String, Set<String>>,
) {
    fun snapshot(tests: Map<String, String>): DotnetTestSnapshot =
        DotnetTestSnapshot(identity, testAssemblySha256, artifacts, classes, tests)
}

internal data class DotnetTestReport(
    val tests: Map<String, String>,
)

private data class DotnetEvaluation(
    val sdk: String,
    val sdkMajor: Int,
    val targetFramework: String,
    val assemblyName: String,
    val testAssembly: Path,
    val assets: Path,
    val adapter: String,
    val importFingerprint: String,
)

private data class DotnetOutput(
    val artifactPaths: List<Path>,
    val productionArtifactPaths: Set<Path>,
    val trustedFrameworkArtifactPaths: Set<Path>,
    val companionHashes: List<String>,
)

internal fun promoteDotnetBaseline(
    store: DotnetTestBaselineStore,
    before: DotnetAnalyzedState?,
    after: DotnetAnalyzedState?,
    report: Path?,
    full: Boolean,
    passed: Boolean,
): Boolean = runCatching {
    if (!full || !passed) return false
    if (before == null || after == null || report == null) return false
    if (!before.sameDotnetArtifacts(after)) return false
    val tests = readDotnetTestReport(report)?.tests ?: return false
    store.write(after.snapshot(tests))
    true
}.getOrDefault(false)

internal fun DotnetAnalyzedState.sameDotnetArtifacts(other: DotnetAnalyzedState): Boolean =
    identity == other.identity && testAssemblySha256 == other.testAssemblySha256 && artifacts == other.artifacts

internal fun readDotnetProjectMetadata(
    root: Path,
    project: String,
    productionProjects: Set<Path> = emptySet(),
): DotnetProjectMetadata? = runCatching {
    val absoluteRoot = root.toAbsolutePath().normalize()
    require(Files.isDirectory(absoluteRoot, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(absoluteRoot))
    val realRoot = absoluteRoot.toRealPath()
    require(!usesMicrosoftTestingPlatform(realRoot.toString()))
    require(!unsupportedDotnetConfiguration(realRoot.toFile()))
    require(!hasExternalDotnetConfiguration(realRoot))
    require(!hasDotnetEnvironmentOverrides())
    val requestedProject = realRoot.resolve(project).normalize()
    require(requestedProject.startsWith(realRoot) && requestedProject.isSecureRegularFile())
    val projectPath = requestedProject.toRealPath()
    require(projectPath.startsWith(realRoot))
    val evaluation = evaluateDotnetProject(realRoot, projectPath) ?: return null
    val manifestFingerprint = dotnetManifestFingerprint(realRoot.toFile()) ?: return null
    val output = readDotnetOutput(realRoot, evaluation, productionProjects) ?: return null
    val identity = sha256(
        listOf(
            DOTNET_METADATA_SCHEMA.toString(),
            evaluation.sdk,
            evaluation.targetFramework,
            evaluation.adapter,
            evaluation.importFingerprint,
            manifestFingerprint,
            dotnetFileSha256(evaluation.assets),
            output.productionArtifactPaths.sorted().joinToString("\n"),
            output.trustedFrameworkArtifactPaths.sorted().joinToString("\n"),
        ).plus(output.companionHashes).joinToString("\n"),
    )
    DotnetProjectMetadata(
        project,
        evaluation.targetFramework,
        evaluation.testAssembly,
        output.artifactPaths,
        output.productionArtifactPaths,
        output.trustedFrameworkArtifactPaths,
        identity,
        evaluation.sdkMajor,
    )
}.getOrNull()

private fun evaluateDotnetProject(root: Path, project: Path): DotnetEvaluation? = runCatching {
    val sdk = CommandRunner.capture(root.toString(), listOf("dotnet", "--version"), DOTNET_METADATA_TIMEOUT)
        ?.trim()
        ?.takeIf { it.matches(DOTNET_VERSION) }
        ?: return null
    val sdkMajor = sdk.substringBefore('.').toInt()
    require(sdkMajor in MIN_DOTNET_SDK_MAJOR..MAX_DOTNET_SDK_MAJOR)
    val raw = CommandRunner.capture(
        root.toString(),
        listOf(
            "dotnet",
            "msbuild",
            project.toString(),
            "-nologo",
            "-getProperty:TargetFramework,TargetFrameworks,TargetPath,AssemblyName,IsTestProject,ProjectAssetsFile," +
                "MSBuildSDKsPath," +
                DOTNET_EVALUATED_SETTINGS.joinToString(","),
        ),
        DOTNET_METADATA_TIMEOUT,
        DOTNET_METADATA_MAX_BYTES,
    ) ?: return null
    val properties = JsonParser.parseString(raw).asJsonObject.getAsJsonObject("Properties")
    require(supportedDotnetEvaluatedSettings(properties))
    require(properties.string("IsTestProject").equals("true", ignoreCase = true))
    require(properties.string("TargetFrameworks").isBlank())
    val targetFramework = properties.string("TargetFramework").also { require(it.matches(TARGET_FRAMEWORK)) }
    val assemblyName = properties.string("AssemblyName").also { require(it.isNotBlank()) }
    val testAssembly = properties.securePath("TargetPath", root)
    require(testAssembly.fileName.toString() == "$assemblyName.dll")
    val assets = properties.securePath("ProjectAssetsFile", root)
    val adapter = supportedDotnetAdapter(assets) ?: return null
    val preprocessed = CommandRunner.capture(
        root.toString(),
        listOf("dotnet", "msbuild", project.toString(), "-nologo", "-preprocess"),
        DOTNET_METADATA_TIMEOUT,
        DOTNET_METADATA_MAX_BYTES,
    ) ?: return null
    val imports = dotnetImportFingerprint(
        root,
        assets,
        sdk,
        properties.string("MSBuildSDKsPath"),
        preprocessed,
    ) ?: return null
    DotnetEvaluation(sdk, sdkMajor, targetFramework, assemblyName, testAssembly, assets, adapter, imports)
}.getOrNull()

private fun readDotnetOutput(
    root: Path,
    evaluation: DotnetEvaluation,
    productionProjects: Set<Path>,
): DotnetOutput? = runCatching {
    val dependencies = evaluation.testAssembly.parent.resolve("${evaluation.assemblyName}.deps.json")
        .takeIf(Path::isSecureRegularFile) ?: return null
    val runtimeConfiguration = evaluation.testAssembly.parent.resolve("${evaluation.assemblyName}.runtimeconfig.json")
        .takeIf(Path::isSecureRegularFile) ?: return null
    val companionHashes = listOf(dependencies, runtimeConfiguration).map(::dotnetFileSha256)
    val artifactPaths = Files.list(evaluation.testAssembly.parent).use { paths ->
        paths.filter { path ->
            path != evaluation.testAssembly && path.fileName.toString().endsWith(".dll", ignoreCase = true) &&
                path.isSecureRegularFile()
        }.sorted().limit((MAX_DOTNET_ARTIFACTS + 1).toLong()).toList()
    }
    require(artifactPaths.isNotEmpty() && artifactPaths.size <= MAX_DOTNET_ARTIFACTS)
    require(artifactPaths.sumOf(Files::size) <= MAX_DOTNET_ARTIFACT_BYTES)
    require(DotnetTestExtensions.supported(evaluation.adapter, artifactPaths.map { it.fileName.toString() }))
    val productionArtifactPaths = if (productionProjects.isEmpty()) {
        dotnetProductionArtifactPaths(dependencies, evaluation.assemblyName, artifactPaths)
    } else {
        evaluatedDotnetProductionArtifactPaths(root, productionProjects, artifactPaths)
    } ?: return null
    val trustedFrameworkArtifactPaths = DotnetAssets.trustedFrameworkArtifactPaths(
        evaluation.assets,
        evaluation.adapter,
        artifactPaths,
    ) ?: return null
    require(trustedFrameworkArtifactPaths.none(productionArtifactPaths::contains))
    DotnetOutput(artifactPaths, productionArtifactPaths, trustedFrameworkArtifactPaths, companionHashes)
}.getOrNull()

internal fun analyzeDotnetProject(
    metadata: DotnetProjectMetadata,
    classes: Set<String>,
    cache: Path,
): DotnetAnalyzedState? = runCatching {
    require(classes.size <= MAX_DOTNET_CLASSES && classes.all(DOTNET_CLASS_NAME::matches))
    val analyzer = prepareDotnetAnalyzer(cache, metadata.sdkMajor) ?: return null
    val runs = secureDotnetDirectory(cache.resolve("runs"))
    val request = Files.createTempFile(runs, "analyzer-", ".json")
    val response = request.resolveSibling("${request.fileName}.response")
    try {
        val json = JsonObject().apply {
            addProperty("schema", DOTNET_METADATA_SCHEMA)
            addProperty("testAssembly", metadata.testAssembly.toString())
            add("classes", classes.sorted().toJsonArray())
            add("artifacts", metadata.artifactPaths.map(Path::toString).toJsonArray())
            add("productionArtifacts", metadata.productionArtifactPaths.map(Path::toString).sorted().toJsonArray())
            add(
                "trustedFrameworkArtifacts",
                metadata.trustedFrameworkArtifactPaths.map(Path::toString).sorted().toJsonArray(),
            )
        }.toString()
        require(json.toByteArray(StandardCharsets.UTF_8).size <= DOTNET_ANALYZER_REQUEST_MAX_BYTES)
        Files.writeString(request, json, StandardCharsets.UTF_8)
        val output = CommandRunner.capture(
            metadata.testAssembly.parent.toString(),
            listOf("dotnet", analyzer.toString(), request.toString(), response.toString()),
            DOTNET_ANALYZER_TIMEOUT,
            DOTNET_METADATA_MAX_BYTES,
        ) ?: return null
        require(output.isBlank())
        require(response.isSecureRegularFile() && Files.size(response) in 1..DOTNET_METADATA_MAX_BYTES.toLong())
        parseDotnetAnalysis(metadata, Files.readString(response, StandardCharsets.UTF_8), classes)
    } finally {
        runCatching { Files.deleteIfExists(request) }
        runCatching { Files.deleteIfExists(response) }
    }
}.getOrNull()

internal fun dotnetChangedSourcesAreOwned(
    root: Path,
    projects: Set<Path>,
    changes: BuildChanges,
): Boolean = runCatching {
    require(projects.isNotEmpty())
    val realRoot = root.toAbsolutePath().normalize().toRealPath()
    val changed = changes.files.mapTo(LinkedHashSet()) { raw ->
        Path.of(raw).toAbsolutePath().normalize().toRealPath().also { require(it.startsWith(realRoot)) }
    }
    val found = LinkedHashSet<Path>()
    projects.forEach { rawProject ->
        val requestedProject = rawProject.toAbsolutePath().normalize()
        require(requestedProject.isSecureRegularFile())
        val project = requestedProject.toRealPath().also { require(it.startsWith(realRoot)) }
        val output = CommandRunner.capture(
            realRoot.toString(),
            listOf("dotnet", "msbuild", project.toString(), "-nologo", "-getItem:Compile"),
            DOTNET_METADATA_TIMEOUT,
            DOTNET_METADATA_MAX_BYTES,
        ) ?: return false
        val items = JsonParser.parseString(output).asJsonObject
            .getAsJsonObject("Items")
            .getAsJsonArray("Compile") ?: return@forEach
        items.forEach { element ->
            val item = element.asJsonObject
            val source = Path.of(item.string("FullPath")).toAbsolutePath().normalize()
            if (source !in changed) return@forEach
            require(source.isSecureRegularFile() && source.toRealPath() == source)
            require(DOTNET_GENERATED_ITEM_METADATA.none { name ->
                item.string(name).equals("true", ignoreCase = true)
            })
            found.add(source)
        }
    }
    found == changed
}.getOrDefault(false)

internal fun readDotnetTestReport(path: Path): DotnetTestReport? = runCatching {
    require(path.isSecureRegularFile() && Files.size(path) in 1..DOTNET_REPORT_MAX_BYTES)
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
    val document = Files.newInputStream(path).use { factory.newDocumentBuilder().parse(it) }
    require(document.documentElement.localName == "TestRun")
    val summary = document.getElementsByTagNameNS("*", "ResultSummary").singleElement()
    require(summary.getAttribute("outcome") in setOf("Passed", "Completed"))
    val counters = summary.getElementsByTagNameNS("*", "Counters").singleElement()
    val total = counters.nonNegative("total")
    require(total > 0 && counters.nonNegative("passed") == total)
    listOf("failed", "error", "timeout", "aborted", "inconclusive", "notExecuted", "disconnected", "warning")
        .forEach { name -> require(counters.nonNegative(name) == 0) }

    val definitions = document.getElementsByTagNameNS("*", "UnitTest")
    require(definitions.length == total)
    val byId = LinkedHashMap<String, Pair<String, String>>()
    repeat(definitions.length) { index ->
        val definition = definitions.item(index) as Element
        val id = definition.getAttribute("id").also { require(it.matches(UUID_VALUE)) }
        val method = definition.getElementsByTagNameNS("*", "TestMethod").singleElement()
        val className = method.getAttribute("className").also { require(it.matches(DOTNET_CLASS_NAME)) }
        val methodName = method.getAttribute("name").also { require(it.matches(DOTNET_METHOD_NAME)) }
        val test = "$className.$methodName".also { require(validDotnetTestName(it)) }
        require(byId.put(id, test to className) == null)
    }

    val results = document.getElementsByTagNameNS("*", "UnitTestResult")
    require(results.length == total)
    val tests = LinkedHashMap<String, String>()
    val resultIds = LinkedHashSet<String>()
    repeat(results.length) { index ->
        val result = results.item(index) as Element
        require(result.getAttribute("outcome") == "Passed")
        val testId = result.getAttribute("testId").also { require(resultIds.add(it)) }
        val (test, className) = byId.getValue(testId)
        require(tests.putIfAbsent(test, className) == null || tests[test] == className)
    }
    require(resultIds == byId.keys)
    require(tests.isNotEmpty() && tests.size <= MAX_DOTNET_TESTS)
    DotnetTestReport(tests)
}.getOrNull()

internal fun newDotnetReport(directory: Path): Path {
    val run = secureDotnetDirectory(directory.resolve(UUID.randomUUID().toString()))
    return run.resolve("results.trx")
}

private fun parseDotnetAnalysis(
    metadata: DotnetProjectMetadata,
    raw: String,
    expectedClasses: Set<String>,
): DotnetAnalyzedState {
    val json = JsonParser.parseString(raw).asJsonObject
    require(json.get("schema").asInt == DOTNET_METADATA_SCHEMA)
    val testAssembly = json.string("testAssemblySha256").also { require(it.matches(SHA256)) }
    val artifacts = LinkedHashMap<String, DotnetImpactArtifact>()
    json.getAsJsonArray("artifacts").forEach { element ->
        val artifact = element.asJsonObject
        val name = artifact.string("name").also { require(it.isNotBlank()) }
        val path = Path.of(artifact.string("path")).toAbsolutePath().normalize()
        require(path in metadata.artifactPaths)
        val hash = artifact.string("sha256").also { require(it.matches(SHA256)) }
        val dependencies = artifact.getAsJsonArray("dependencies").mapTo(LinkedHashSet()) { it.asString }
        require(artifacts.put(name, DotnetImpactArtifact(hash, dependencies)) == null)
    }
    require(artifacts.size == metadata.artifactPaths.size)
    require(artifacts.values.all { artifact -> artifact.dependencies.all(artifacts::containsKey) })
    val classes = LinkedHashMap<String, Set<String>>()
    val rawClasses = json.getAsJsonObject("classes")
    rawClasses.entrySet().forEach { (name, value) ->
        require(name.matches(DOTNET_CLASS_NAME))
        val dependencies = value.asJsonArray.mapTo(LinkedHashSet()) { it.asString }
        require(dependencies.all(artifacts::containsKey))
        require(classes.put(name, dependencies) == null)
    }
    require(classes.keys == expectedClasses)
    return DotnetAnalyzedState(metadata.identity, testAssembly, artifacts, classes)
}

private fun prepareDotnetAnalyzer(cache: Path, sdkMajor: Int): Path? = synchronized(DOTNET_ANALYZER_LOCK) {
    runCatching {
        val source = configuredDotnetAnalyzer() ?: findDotnetAnalyzer(
            Path.of(PathManager.getJarPathForClass(DotnetBuildSystem::class.java)),
        ) ?: return null
        val project = source.resolve(DOTNET_ANALYZER_PROJECT)
        val program = source.resolve(DOTNET_ANALYZER_PROGRAM)
        require(project.isSecureRegularFile() && program.isSecureRegularFile())
        val fingerprint = sha256(
            "$sdkMajor\n${dotnetFileSha256(project)}\n${dotnetFileSha256(program)}\n",
        )
        val directory = secureDotnetDirectory(cache.resolve("analyzer").resolve(fingerprint))
        val output = secureDotnetDirectory(directory.resolve("out"))
        val executable = output.resolve(DOTNET_ANALYZER_DLL)
        if (executable.isSecureRegularFile()) return executable
        val intermediate = secureDotnetDirectory(directory.resolve("obj"))
        val build = CommandRunner.capture(
            source.toString(),
            listOf(
                "dotnet",
                "build",
                project.toString(),
                "--configuration",
                "Release",
                "--nologo",
                "--disable-build-servers",
                "-p:AffectedTargetFramework=net$sdkMajor.0",
                "-p:BaseIntermediateOutputPath=${intermediate}${File.separator}",
                "-p:RestoreIgnoreFailedSources=true",
                "-p:UseSharedCompilation=false",
                "--output",
                output.toString(),
            ),
            DOTNET_ANALYZER_BUILD_TIMEOUT,
            DOTNET_ANALYZER_BUILD_MAX_BYTES,
        ) ?: return null
        require(build.length <= DOTNET_ANALYZER_BUILD_MAX_BYTES)
        executable.takeIf(Path::isSecureRegularFile)
    }.getOrNull()
}

private fun configuredDotnetAnalyzer(): Path? = System.getProperty(DOTNET_ANALYZER_PROPERTY)
    ?.let(Path::of)
    ?.toAbsolutePath()
    ?.normalize()
    ?.takeIf { it.resolve(DOTNET_ANALYZER_PROJECT).isSecureRegularFile() }

internal fun findDotnetAnalyzer(classPath: Path): Path? {
    var directory = classPath.toAbsolutePath().normalize().let {
        if (Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS)) it else it.parent
    } ?: return null
    repeat(MAX_PLUGIN_PARENT_DEPTH) {
        val source = directory.resolve(DOTNET_ANALYZER_PATH)
        if (source.resolve(DOTNET_ANALYZER_PROJECT).isSecureRegularFile() &&
            source.resolve(DOTNET_ANALYZER_PROGRAM).isSecureRegularFile()
        ) {
            return source
        }
        directory = directory.parent ?: return null
    }
    return null
}

internal fun supportedDotnetAdapter(assets: Path): String? = runCatching {
    require(Files.size(assets) in 1..DOTNET_METADATA_MAX_BYTES.toLong())
    val librariesObject = JsonParser.parseString(Files.readString(assets, StandardCharsets.UTF_8))
        .asJsonObject
        .getAsJsonObject("libraries")
    val libraries = librariesObject.keySet()
        .map { key -> key.substringBeforeLast('/').lowercase() to key.substringAfterLast('/') }
    val testSdk = libraries.singleOrNull { it.first == "microsoft.net.test.sdk" } ?: return null
    require(testSdk.second.substringBefore('.').toInt() in 17..18)
    val adapters = libraries.filter { it.first in SUPPORTED_ADAPTERS }
    require(adapters.size == 1)
    val (name, version) = adapters.single()
    val major = version.substringBefore('.').toInt()
    val supported = SUPPORTED_ADAPTERS.getValue(name)
    require(major in supported.adapterMajors)
    val frameworks = libraries.filter { it.first in SUPPORTED_FRAMEWORKS }
    require(frameworks.size == 1)
    val (framework, frameworkVersion) = frameworks.single()
    require(framework == supported.framework)
    require(frameworkVersion.substringBefore('.').toInt() in SUPPORTED_FRAMEWORKS.getValue(framework))
    val allowedExtensions = setOf(
        name,
        "microsoft.net.test.sdk",
        "microsoft.testplatform.testhost",
        "microsoft.codecoverage",
    )
    val allowedBuildAssets = DOTNET_COMMON_BUILD_ASSET_PACKAGES +
        DOTNET_ADAPTER_BUILD_ASSET_PACKAGES.getValue(name)
    librariesObject.entrySet().forEach { (key, value) ->
        val owner = key.substringBeforeLast('/').lowercase()
        val files = value.asJsonObject.getAsJsonArray("files")?.map { it.asString }.orEmpty()
        require(owner in allowedExtensions || files.none(DotnetTestExtensions::matches))
        require(owner in allowedBuildAssets || files.none(DotnetAssets::isBuildAsset))
    }
    "$name/$version;$framework/$frameworkVersion"
}.getOrNull()

internal object DotnetAssets {
    fun trustedFrameworkArtifactPaths(
        assets: Path,
        adapter: String,
        artifacts: List<Path>,
    ): Set<Path>? = runCatching {
        val framework = adapter.substringAfter(';').substringBefore('/').lowercase()
        val trustedPackages = DOTNET_TRUSTED_FRAMEWORK_PACKAGES.getValue(framework)
        val libraries = JsonParser.parseString(Files.readString(assets, StandardCharsets.UTF_8))
            .asJsonObject
        val packageRoots = libraries.getAsJsonObject("packageFolders").keySet().map { raw ->
            val requested = Path.of(raw).toAbsolutePath().normalize()
            require(Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(requested))
            requested.toRealPath()
        }
        val packageLibraries = libraries.getAsJsonObject("libraries")
        val trustedFiles = packageLibraries.entrySet().asSequence()
            .filter { (key, _) -> key.substringBeforeLast('/').lowercase() in trustedPackages }
            .flatMap { (_, value) ->
                val library = value.asJsonObject
                val packagePath = library.get("path").asString
                library.getAsJsonArray("files")?.asSequence().orEmpty().map { file ->
                    packagePath to file.asString
                }
            }
            .filter { (_, file) -> file.endsWith(".dll", ignoreCase = true) && ".resources." !in file }
            .toList()
        val byName = artifacts.groupBy { it.fileName.toString().lowercase() }
        val trusted = trustedFiles.mapNotNullTo(LinkedHashSet()) { (packagePath, file) ->
            val output = byName[file.replace('\\', '/').substringAfterLast('/').lowercase()]?.singleOrNull()
                ?: return@mapNotNullTo null
            val outputHash = dotnetFileSha256(output)
            val matchesPackage = packageRoots.any { root ->
                val candidate = root.resolve(packagePath).resolve(file).normalize()
                candidate.startsWith(root) && candidate.isSecureRegularFile() && candidate.toRealPath() == candidate &&
                    dotnetFileSha256(candidate) == outputHash
            }
            output.takeIf { matchesPackage }
        }
        require(trusted.isNotEmpty())
        trusted
    }.getOrNull()

    fun isBuildAsset(path: String): Boolean {
        val first = path.replace('\\', '/').substringBefore('/').lowercase()
        return first in setOf("build", "buildtransitive", "buildmultitargeting")
    }
}

internal object DotnetTestExtensions {
    fun supported(adapter: String, files: List<String>): Boolean = runCatching {
        val adapterName = adapter.substringBefore('/').lowercase()
        val allowed = DOTNET_EXTENSION_FILES.getValue(adapterName) + DOTNET_PLATFORM_EXTENSION_FILES
        require(files.filter(::matches).all { it.lowercase() in allowed })
        true
    }.getOrDefault(false)

    fun matches(path: String): Boolean {
        val name = Path.of(path.replace('\\', '/')).fileName.toString().lowercase()
        return DOTNET_EXTENSION_SUFFIXES.any(name::endsWith)
    }
}

private fun dotnetProductionArtifactPaths(
    dependencies: Path,
    testAssemblyName: String,
    artifacts: List<Path>,
): Set<Path>? = runCatching {
    require(Files.size(dependencies) in 1..DOTNET_METADATA_MAX_BYTES.toLong())
    val root = JsonParser.parseString(Files.readString(dependencies, StandardCharsets.UTF_8)).asJsonObject
    val libraries = root.getAsJsonObject("libraries")
    val runtimeTarget = root.getAsJsonObject("runtimeTarget").string("name")
    val targets = root.getAsJsonObject("targets").getAsJsonObject(runtimeTarget)
    val byFileName = artifacts.groupBy { it.fileName.toString().lowercase() }
    val result = LinkedHashSet<Path>()
    targets.entrySet().forEach { (library, value) ->
        val kind = libraries.getAsJsonObject(library).string("type")
        if (kind !in DOTNET_LOCAL_LIBRARY_TYPES || library.substringBeforeLast('/') == testAssemblyName) {
            return@forEach
        }
        val runtime = value.asJsonObject.getAsJsonObject("runtime") ?: return@forEach
        runtime.keySet().filter { it.endsWith(".dll", ignoreCase = true) }.forEach { relative ->
            val name = Path.of(relative).fileName.toString().lowercase()
            result.add(byFileName.getValue(name).single())
        }
    }
    require(result.isNotEmpty())
    result
}.getOrNull()

private fun evaluatedDotnetProductionArtifactPaths(
    root: Path,
    projects: Set<Path>,
    artifacts: List<Path>,
): Set<Path>? = runCatching {
    val byFileName = artifacts.groupBy { it.fileName.toString().lowercase() }
    projects.mapTo(LinkedHashSet()) { rawProject ->
        val requested = rawProject.toAbsolutePath().normalize()
        require(requested.isSecureRegularFile())
        val project = requested.toRealPath().also { require(it.startsWith(root)) }
        val output = CommandRunner.capture(
            root.toString(),
            listOf(
                "dotnet",
                "msbuild",
                project.toString(),
                "-nologo",
                "-getProperty:AssemblyName,TargetFramework",
            ),
            DOTNET_METADATA_TIMEOUT,
            DOTNET_METADATA_MAX_BYTES,
        ) ?: return null
        val name = JsonParser.parseString(output).asJsonObject
            .getAsJsonObject("Properties")
            .string("AssemblyName")
            .also { require(it.isNotBlank()) }
        byFileName.getValue("${name.lowercase()}.dll").single()
    }.also { require(it.size == projects.size) }
}.getOrNull()

private fun JsonObject.string(name: String): String = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

internal fun supportedDotnetEvaluatedSettings(properties: JsonObject): Boolean =
    DOTNET_EVALUATED_SETTINGS.all { setting ->
        properties.string(setting).let { value ->
            value.isBlank() || setting in DOTNET_BOOLEAN_EVALUATED_SETTINGS && value.equals("false", ignoreCase = true)
        }
    }

private fun JsonObject.securePath(name: String, root: Path): Path {
    val raw = Path.of(string(name))
    val requested = (if (raw.isAbsolute) raw else root.resolve(raw)).toAbsolutePath().normalize()
    require(requested.isSecureRegularFile())
    return requested.toRealPath().also { real -> require(real.startsWith(root)) }
}

private fun List<String>.toJsonArray() = com.google.gson.JsonArray().also { array -> forEach(array::add) }

private fun org.w3c.dom.NodeList.singleElement(): Element {
    require(length == 1)
    return item(0) as Element
}

private fun Element.nonNegative(name: String): Int = getAttribute(name).toInt().also { require(it >= 0) }

private fun Path.isSecureRegularFile(): Boolean =
    Files.isRegularFile(this, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(this) && Files.isReadable(this)

internal fun dotnetFileSha256(path: Path): String {
    require(path.isSecureRegularFile())
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private val DOTNET_ANALYZER_LOCK = Any()
private val DOTNET_VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+([.-][A-Za-z0-9.-]+)?")
private val TARGET_FRAMEWORK = Regex("net[0-9]+\\.[0-9]+(-[A-Za-z0-9.-]+)?")
private val SHA256 = Regex("[0-9a-f]{64}")
private val UUID_VALUE = Regex("[0-9a-fA-F-]{36}")
private val DOTNET_CLASS_NAME = Regex("[A-Za-z_][A-Za-z0-9_`+]*(\\.[A-Za-z_][A-Za-z0-9_`+]*)+")
private val DOTNET_METHOD_NAME = Regex("[A-Za-z_][A-Za-z0-9_`]*")
private val SUPPORTED_ADAPTERS = mapOf(
    "xunit.runner.visualstudio" to SupportedDotnetAdapter(2..4, "xunit"),
    "nunit3testadapter" to SupportedDotnetAdapter(5..6, "nunit"),
    "mstest.testadapter" to SupportedDotnetAdapter(3..4, "mstest.testframework"),
)
private val SUPPORTED_FRAMEWORKS = mapOf(
    "xunit" to 2..2,
    "nunit" to 4..4,
    "mstest.testframework" to 4..4,
)
private val DOTNET_EXTENSION_SUFFIXES = setOf(
    "testadapter.dll",
    "testlogger.dll",
    "datacollector.dll",
    "runtimeprovider.dll",
    "testhost.dll",
)
private val DOTNET_PLATFORM_EXTENSION_FILES = setOf(
    "testhost.dll",
    "microsoft.visualstudio.tracedatacollector.dll",
)
private val DOTNET_EXTENSION_FILES = mapOf(
    "xunit.runner.visualstudio" to setOf("xunit.runner.visualstudio.testadapter.dll"),
    "nunit3testadapter" to setOf("nunit3.testadapter.dll"),
    "mstest.testadapter" to setOf("mstest.testadapter.dll"),
)
private val DOTNET_COMMON_BUILD_ASSET_PACKAGES = setOf(
    "microsoft.codecoverage",
    "microsoft.net.test.sdk",
    "microsoft.testplatform.testhost",
    "system.collections.immutable",
    "system.reflection.metadata",
)
private val DOTNET_ADAPTER_BUILD_ASSET_PACKAGES = mapOf(
    "xunit.runner.visualstudio" to setOf(
        "xunit.core",
        "xunit.runner.visualstudio",
    ),
    "nunit3testadapter" to setOf(
        "microsoft.extensions.dependencymodel",
        "microsoft.testing.extensions.telemetry",
        "microsoft.testing.platform",
        "microsoft.testing.platform.msbuild",
        "nunit",
        "nunit3testadapter",
    ),
    "mstest.testadapter" to setOf(
        "microsoft.testing.extensions.telemetry",
        "microsoft.testing.platform",
        "microsoft.testing.platform.msbuild",
        "mstest.analyzers",
        "mstest.testadapter",
        "mstest.testframework",
    ),
)
private val DOTNET_TRUSTED_FRAMEWORK_PACKAGES = mapOf(
    "xunit" to setOf(
        "xunit.abstractions",
        "xunit.assert",
        "xunit.extensibility.core",
        "xunit.extensibility.execution",
    ),
    "nunit" to setOf("nunit"),
    "mstest.testframework" to setOf("mstest.testframework"),
)
private val DOTNET_EVALUATED_SETTINGS = setOf(
    "DirectoryBuildPropsPath",
    "DirectoryBuildTargetsPath",
    "EnableMSTestRunner",
    "EnableNUnitRunner",
    "MicrosoftTestingPlatformDotnetTestSupport",
    "RunSettingsFilePath",
    "TestingPlatformCommandLineArguments",
    "TestingPlatformDotnetTestSupport",
    "UseMicrosoftTestingPlatformRunner",
    "VSTestTestAdapterPath",
    "VSTestTestCaseFilter",
)
private val DOTNET_BOOLEAN_EVALUATED_SETTINGS = setOf(
    "EnableMSTestRunner",
    "EnableNUnitRunner",
    "MicrosoftTestingPlatformDotnetTestSupport",
    "TestingPlatformDotnetTestSupport",
    "UseMicrosoftTestingPlatformRunner",
)
private val DOTNET_GENERATED_ITEM_METADATA = setOf("AutoGen", "DesignTime", "Generated")
private val DOTNET_LOCAL_LIBRARY_TYPES = setOf("project")
private const val DOTNET_METADATA_SCHEMA = 3
private const val MIN_DOTNET_SDK_MAJOR = 8
private const val MAX_DOTNET_SDK_MAJOR = 10
private const val DOTNET_METADATA_TIMEOUT = 30L
private const val DOTNET_ANALYZER_TIMEOUT = 30L
private const val DOTNET_ANALYZER_BUILD_TIMEOUT = 120L
private const val DOTNET_METADATA_MAX_BYTES = 16 * 1024 * 1024
private const val DOTNET_ANALYZER_REQUEST_MAX_BYTES = 1024 * 1024
private const val DOTNET_ANALYZER_BUILD_MAX_BYTES = 8 * 1024 * 1024
private const val DOTNET_REPORT_MAX_BYTES = 16L * 1024 * 1024
private const val MAX_DOTNET_ARTIFACTS = 512
private const val MAX_DOTNET_CLASSES = 65_536
private const val MAX_DOTNET_TESTS = 65_536
private const val MAX_DOTNET_ARTIFACT_BYTES = 512L * 1024 * 1024
private const val MAX_PLUGIN_PARENT_DEPTH = 5
private const val DOTNET_ANALYZER_PROPERTY = "affected.test.dotnetAnalyzer"
private data class SupportedDotnetAdapter(val adapterMajors: IntRange, val framework: String)
private const val DOTNET_ANALYZER_PATH = "agent/dotnet/Affected.DotnetAnalyzer"
private const val DOTNET_ANALYZER_PROJECT = "Affected.DotnetAnalyzer.csproj"
private const val DOTNET_ANALYZER_PROGRAM = "Program.cs"
private const val DOTNET_ANALYZER_DLL = "Affected.DotnetAnalyzer.dll"

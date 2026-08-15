package com.aspix2k.affected.build

import com.google.gson.JsonParser
import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CancellationException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal data class DotnetMtpSelectionPlan(
    val classes: List<String>,
    val files: Set<String>,
    val fingerprint: String,
)

internal fun dotnetMtpTestArguments(
    root: String,
    project: String,
    planned: BuildChanges,
    plannedSelection: DotnetMtpSelectionPlan?,
    currentChanges: () -> BuildChanges,
    runtimeProof: (String, String, Set<String>) -> Boolean = { runRoot, runProject, files ->
        dotnetMtpRuntimeProof(runRoot, runProject, files)
    },
): List<String> {
    val arguments = mutableListOf("dotnet", "test", "--project", project, "--no-build")
    val beforeChanges = currentDotnetChangesOrNull(currentChanges)
    val beforeSelection = beforeChanges
        ?.takeIf { sameDotnetMtpChanges(planned, it) }
        ?.let { dotnetMtpSelectionPlan(root, project, it) }
        ?.takeIf { it == plannedSelection }
    val verified = beforeSelection
        ?.takeIf { runtimeProof(root, project, it.files) }
    val classes = verified
        ?.let { firstProof ->
            val afterChanges = currentDotnetChangesOrNull(currentChanges)
            afterChanges
                ?.takeIf { sameDotnetMtpChanges(planned, it) }
                ?.let { dotnetMtpSelectionPlan(root, project, it) }
                ?.takeIf { it == firstProof }
                ?.takeIf { runtimeProof(root, project, it.files) }
                ?.let { secondProof ->
                    currentDotnetChangesOrNull(currentChanges)
                        ?.takeIf { sameDotnetMtpChanges(planned, it) }
                        ?.let { dotnetMtpSelectionPlan(root, project, it) }
                        ?.takeIf { it == secondProof }
                        ?.classes
                }
        }
    if (classes != null) {
        arguments += listOf("--minimum-expected-tests", classes.size.toString(), "--filter-class")
        arguments += classes
    }
    return arguments
}

internal fun selectMtpFilterClasses(root: String, project: String, changes: BuildChanges): List<String>? =
    dotnetMtpSelectionPlan(root, project, changes)?.classes

internal fun dotnetMtpSelectionPlan(root: String, project: String, changes: BuildChanges): DotnetMtpSelectionPlan? =
    runCatching {
        require(supportsNativeXunit4Mtp(root, project))
        require(changes.comparedToBase)
        require(changes.files.isNotEmpty() && changes.files.size <= MAX_MTP_FILTER_CLASSES)
        require(changes.files.toSet() == changes.exactSelectionEligible)
        val rootPath = Path.of(root).toAbsolutePath().normalize()
        require(Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(rootPath))
        val projectPath = rootPath.resolve(project).normalize()
        require(projectPath.startsWith(rootPath) && symlinkFreeDotnetPath(rootPath, projectPath))
        val projectDirectory = requireNotNull(projectPath.parent)
        require(Files.isDirectory(projectDirectory, LinkOption.NOFOLLOW_LINKS))
        val realRoot = rootPath.toRealPath()
        val realProjectDirectory = projectDirectory.toRealPath().also { require(it.startsWith(realRoot)) }
        val names = LinkedHashSet<String>()
        val selected = LinkedHashSet<Path>()
        for (raw in changes.files) {
            val requested = Path.of(raw).toAbsolutePath().normalize()
            require(requested.startsWith(projectDirectory) && symlinkFreeDotnetPath(rootPath, requested))
            require(Files.isRegularFile(requested, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(requested))
            val real = requested.toRealPath()
            require(real.startsWith(realProjectDirectory))
            require(real.fileName.toString().endsWith(".cs", ignoreCase = true))
            val relative = realProjectDirectory.relativize(real)
            require(relative.none { segment -> segment.toString().lowercase() in DOTNET_MTP_GENERATED_DIRECTORIES })
            require(DOTNET_MTP_GENERATED_SUFFIXES.none { suffix ->
                real.fileName.toString().endsWith(suffix, ignoreCase = true)
            })
            val text = ManifestSearch.readText(real.toFile()) ?: return null
            names += requireNotNull(mtpTestClass(text))
            selected.add(real)
        }
        val classes = names.sorted().also { require(it.isNotEmpty() && it.size == selected.size) }
        require(nativeMtpExactArgumentCharacters(project, classes) <= MAX_MTP_ARGUMENT_CHARACTERS)
        val projectReal = realRoot.resolve(project)
        val proofFiles = listOf(
            realRoot.resolve("global.json"),
            projectReal,
            requireNotNull(projectReal.parent).resolve("packages.lock.json"),
        ) + selected
        val fingerprint = ManifestSearch.fingerprint(realRoot.toFile(), proofFiles.map(Path::toFile)) ?: return null
        DotnetMtpSelectionPlan(classes, selected.mapTo(LinkedHashSet(), Path::toString), fingerprint)
    }.getOrNull()

private fun currentDotnetChangesOrNull(currentChanges: () -> BuildChanges): BuildChanges? = try {
    currentChanges()
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

private fun sameDotnetMtpChanges(planned: BuildChanges, current: BuildChanges): Boolean =
    planned.comparedToBase == current.comparedToBase &&
        planned.files.toSet() == current.files.toSet() &&
        planned.exactSelectionEligible == current.exactSelectionEligible

private fun nativeMtpExactArgumentCharacters(project: String, classes: List<String>): Int =
    (
        listOf(
            "dotnet",
            "test",
            "--project",
            project,
            "--no-build",
            "--minimum-expected-tests",
            classes.size.toString(),
            "--filter-class",
        ) + classes
        ).sumOf { argument -> argument.length + MTP_ARGUMENT_QUOTE_MARGIN }

internal fun symlinkFreeDotnetPath(root: Path, target: Path): Boolean {
    if (!target.startsWith(root)) return false
    var current = root
    for (segment in root.relativize(target)) {
        current = current.resolve(segment)
        if (Files.isSymbolicLink(current)) return false
    }
    return true
}

internal fun dotnetMtpRuntimeProof(
    root: String,
    project: String,
    selectedFiles: Set<String>,
    importProof: (Path, Path, String, String, String) -> Boolean = { runRoot, assets, sdk, sdkPath, projectXml ->
        dotnetImportFingerprint(runRoot, assets, sdk, sdkPath, projectXml) != null
    },
    capture: (String, List<String>, Long, Int) -> String? = { directory, command, timeout, bytes ->
        CommandRunner.capture(directory, command, timeout, bytes)
    },
): Boolean = try {
    require(selectedFiles.isNotEmpty() && selectedFiles.size <= MAX_MTP_FILTER_CLASSES)
    val rootPath = Path.of(root).toAbsolutePath().normalize().toRealPath()
    val version = capture(
        rootPath.toString(),
        listOf("dotnet", "--version"),
        MTP_METADATA_TIMEOUT,
        MTP_METADATA_MAX_BYTES,
    )?.trim()
    require(version == "10.0.400")
    val output = capture(
        rootPath.toString(),
        listOf(
            "dotnet",
            "msbuild",
            project,
            "-nologo",
            "-getProperty:" + MTP_EVALUATED_PROPERTIES.joinToString(","),
            "-getItem:Compile",
        ),
        MTP_METADATA_TIMEOUT,
        MTP_METADATA_MAX_BYTES,
    ) ?: return false
    val projectPath = rootPath.resolve(project).normalize().toRealPath()
    val projectDirectory = requireNotNull(projectPath.parent)
    val metadata = JsonParser.parseString(output).asJsonObject
    require(nativeMtpEvaluatedOutput(projectDirectory, metadata))
    val properties = metadata.getAsJsonObject("Properties")
    val assets = Path.of(properties.get("ProjectAssetsFile").asString).toAbsolutePath().normalize()
    val expectedAssets = projectDirectory.resolve("obj/project.assets.json")
    require(assets == expectedAssets && Files.isRegularFile(assets, LinkOption.NOFOLLOW_LINKS))
    val sdkPath = properties.get("MSBuildSDKsPath").asString
    require(sdkPath.isNotBlank())
    val preprocessed = capture(
        rootPath.toString(),
        listOf("dotnet", "msbuild", project, "-nologo", "-preprocess"),
        MTP_METADATA_TIMEOUT,
        MTP_METADATA_MAX_BYTES,
    ) ?: return false
    require(importProof(rootPath, assets, version, sdkPath, preprocessed))
    require(nativeMtpCompiledSources(projectDirectory, metadata, selectedFiles))
    require(nativeMtpAssetsProof(root, project))
    true
} catch (error: CancellationException) {
    throw error
} catch (error: ProcessCanceledException) {
    throw error
} catch (error: InterruptedException) {
    Thread.currentThread().interrupt()
    throw error
} catch (_: Exception) {
    false
}

private fun nativeMtpCompiledSources(
    projectDirectory: Path,
    metadata: com.google.gson.JsonObject,
    selectedFiles: Set<String>,
): Boolean = nativeMtpProofOrNull {
    val items = metadata.getAsJsonObject("Items").getAsJsonArray("Compile")
    require(items.size() in 1..MAX_MTP_COMPILE_FILES)
    val compiled = LinkedHashSet<String>()
    var totalBytes = 0L
    items.forEach { element ->
        val item = element.asJsonObject
        val source = Path.of(item.get("FullPath").asString).toAbsolutePath().normalize()
        require(source.startsWith(projectDirectory) && symlinkFreeDotnetPath(projectDirectory, source))
        require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) && source.toRealPath() == source)
        require(source.fileName.toString().endsWith(".cs", ignoreCase = true))
        totalBytes += Files.size(source)
        require(totalBytes <= MTP_COMPILE_MAX_BYTES)
        val text = requireNotNull(ManifestSearch.readText(source.toFile()))
        require(!MTP_ESCAPED_IDENTIFIER.containsMatchIn(text))
        require(!MTP_XUNIT_SHADOW.containsMatchIn(text))
        require(!MTP_ASSEMBLY_ATTRIBUTE.containsMatchIn(text))
        if (source.toString() !in selectedFiles) return@forEach
        require(DOTNET_MTP_GENERATED_METADATA.none { name ->
            item.get(name)?.asString.equals("true", ignoreCase = true)
        })
        require(compiled.add(source.toString()))
    }
    compiled == selectedFiles
} ?: false

private fun nativeMtpEvaluatedOutput(projectDirectory: Path, metadata: com.google.gson.JsonObject): Boolean =
    nativeMtpProofOrNull {
        val properties = metadata.getAsJsonObject("Properties")
        val target = Path.of(properties.get("TargetPath").asString).toAbsolutePath().normalize()
        require(target.startsWith(projectDirectory) && symlinkFreeDotnetPath(projectDirectory, target))
        require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && target.toRealPath() == target)
        val targetDirectory = Path.of(properties.get("TargetDir").asString).toAbsolutePath().normalize()
        require(targetDirectory == target.parent)
        val assembly = properties.get("AssemblyName").asString
        require(assembly.matches(MTP_ASSEMBLY_NAME))
        require(target.fileName.toString().equals("$assembly.dll", ignoreCase = true))
        require(properties.get("Configuration").asString == "Debug")
        val outputPath = properties.get("OutputPath").asString.replace('\\', '/')
        require(outputPath == "bin/Debug/net10.0/")
        MTP_EMPTY_EVALUATED_PROPERTIES.forEach { property ->
            require(properties.get(property).asString.isEmpty())
        }
        require(Files.isDirectory(targetDirectory, LinkOption.NOFOLLOW_LINKS))
        require(!Files.isSymbolicLink(targetDirectory) && targetDirectory.toRealPath() == targetDirectory)
        Files.newDirectoryStream(targetDirectory).use { entries ->
            var count = 0
            entries.forEach { entry ->
                require(++count <= MAX_MTP_OUTPUT_ENTRIES)
                val name = entry.fileName.toString().lowercase()
                require(name !in MTP_RUNTIME_CONFIG_NAMES && MTP_RUNTIME_CONFIG_SUFFIXES.none(name::endsWith))
            }
        }
        true
    } ?: false

private inline fun <T> nativeMtpProofOrNull(block: () -> T): T? = try {
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

private fun mtpTestClass(text: String): String? = runCatching {
    val code = csharpCode(text) ?: return null
    require('#' !in code)
    require(MTP_TEST_METHOD.containsMatchIn(code))
    val namespaces = MTP_FILE_NAMESPACE.findAll(code).map { it.groupValues[1] }.toList()
    require(namespaces.size == 1)
    val classes = MTP_TEST_CLASS.findAll(code).map { it.groupValues[1] }.toList()
    require(classes.size == 1 && classes.single().let { it.endsWith("Test") || it.endsWith("Tests") })
    require(MTP_TYPE_DECLARATION.findAll(code).count() == 1)
    "${namespaces.single()}.${classes.single()}"
}.getOrNull()

private fun csharpCode(text: String): String? = CsharpMask(text).value()

private class CsharpMask(private val text: String) {
    private val output = StringBuilder(text.length)
    private var index = 0

    fun value(): String? = runCatching {
        while (index < text.length) {
            when {
                text.startsWith("//", index) -> maskLineComment()
                text.startsWith("/*", index) -> maskBlockComment()
                unsupportedString() -> return null
                text.startsWith("@\"", index) -> maskVerbatimString()
                text[index] == '"' || text[index] == '\'' -> maskQuotedValue(text[index])
                else -> output.append(text[index++])
            }
        }
        output.toString()
    }.getOrNull()

    private fun maskLineComment() {
        while (index < text.length && text[index] != '\n') {
            output.append(' ')
            index++
        }
    }

    private fun maskBlockComment() {
        output.append("  ")
        index += 2
        while (index + 1 < text.length && !text.startsWith("*/", index)) {
            appendMasked(text[index++])
        }
        require(index + 1 < text.length)
        output.append("  ")
        index += 2
    }

    private fun unsupportedString(): Boolean =
        text.startsWith("$\"", index) || text.startsWith("$@\"", index) ||
            text.startsWith("@$\"", index) || text.startsWith("\"\"\"", index)

    private fun maskVerbatimString() {
        output.append("  ")
        index += 2
        while (index < text.length) {
            if (text[index] != '"') {
                appendMasked(text[index++])
            } else if (index + 1 < text.length && text[index + 1] == '"') {
                output.append("  ")
                index += 2
            } else {
                output.append(' ')
                index++
                return
            }
        }
        error("unterminated verbatim string")
    }

    private fun maskQuotedValue(delimiter: Char) {
        output.append(' ')
        index++
        var escaped = false
        while (index < text.length) {
            val character = text[index++]
            appendMasked(character)
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == delimiter -> return
                character == '\n' && delimiter == '\'' -> error("unterminated character")
            }
        }
        error("unterminated quoted value")
    }

    private fun appendMasked(character: Char) {
        output.append(if (character == '\n') '\n' else ' ')
    }
}

private val MTP_FILE_NAMESPACE = Regex(
    "(?m)^\\s*namespace\\s+([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)\\s*;",
)
private val MTP_TEST_METHOD = Regex(
    "(?s)\\[\\s*global::Xunit\\.Fact(?:Attribute)?(?:\\(\\))?\\s*]\\s*" +
        "public\\s+(?:async\\s+)?(?:void|Task|ValueTask|System\\.Threading\\.Tasks\\.Task)\\s+" +
        "[A-Za-z_][A-Za-z0-9_]*\\s*\\(",
)
private val MTP_TEST_CLASS = Regex("\\bpublic\\s+sealed\\s+class\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\{")
private val MTP_TYPE_DECLARATION = Regex("\\b(?:class|record|struct|interface|enum)\\s+[A-Za-z_]")
private val MTP_ESCAPED_IDENTIFIER = Regex("(?i)\\\\u[0-9a-f]{4}|\\\\U[0-9a-f]{8}")
private val MTP_XUNIT_SHADOW = Regex(
    "(?m)\\bnamespace\\s+@?Xunit(?:\\s*[;{]|\\.)|\\b(?:class|record|struct|interface|enum)\\s+@?Xunit\\b",
)
private val MTP_ASSEMBLY_ATTRIBUTE = Regex("\\[\\s*assembly\\s*:", RegexOption.IGNORE_CASE)
private val MTP_ASSEMBLY_NAME = Regex("[A-Za-z0-9_.-]+")
private val MTP_EMPTY_EVALUATED_PROPERTIES = setOf(
    "TestingPlatformCommandLineArguments",
    "RunSettingsFilePath",
    "VSTestTestCaseFilter",
    "RestoreSources",
)
private val MTP_EVALUATED_PROPERTIES = listOf(
    "TargetPath",
    "TargetDir",
    "AssemblyName",
    "Configuration",
    "OutputPath",
    "ProjectAssetsFile",
    "MSBuildSDKsPath",
) + MTP_EMPTY_EVALUATED_PROPERTIES
private val MTP_RUNTIME_CONFIG_SUFFIXES = setOf(".testconfig.json", ".xunit.runner.json")
private val MTP_RUNTIME_CONFIG_NAMES = setOf("testconfig.json", "xunit.runner.json")
private val DOTNET_MTP_GENERATED_DIRECTORIES = setOf("bin", "obj", "generated")
private val DOTNET_MTP_GENERATED_SUFFIXES = setOf(".g.cs", ".generated.cs", ".designer.cs")
private val DOTNET_MTP_GENERATED_METADATA = setOf(
    "AutoGen",
    "DesignTime",
    "DesignTimeSharedInput",
    "Generated",
)
private const val MAX_MTP_FILTER_CLASSES = 32
private const val MAX_MTP_ARGUMENT_CHARACTERS = 16_000
private const val MTP_ARGUMENT_QUOTE_MARGIN = 3
private const val MAX_MTP_COMPILE_FILES = 4_096
private const val MAX_MTP_OUTPUT_ENTRIES = 4_096
private const val MTP_METADATA_TIMEOUT = 30L
private const val MTP_METADATA_MAX_BYTES = 16 * 1024 * 1024
private const val MTP_COMPILE_MAX_BYTES = 64L * 1024 * 1024

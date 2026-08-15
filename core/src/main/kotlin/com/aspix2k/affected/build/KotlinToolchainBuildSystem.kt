package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File

class KotlinToolchainBuildSystem : ChangeAwareSuspendingBuildSystem, WorkspaceChangesBuildSystem {

    override val id: String = "KOTLIN_TOOLCHAIN"

    override val sourceExtensions: Set<String> = setOf("kt", "kts", "java", "yaml")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = manifestOf(project)?.parentFile ?: return emptyList()
        return failClosedModules(
            root,
            KotlinToolchainTasks.TEST,
            KotlinToolchainTasks.BUILD,
            kotlinToolchainModules(root),
        ).modules
    }

    override fun requiresWorkspace(module: BuildModule, changes: BuildChanges): Boolean =
        kotlinToolchainRequiresWorkspace(module.root, changes)

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, kotlinToolchainCommands(File(root), tasks), "Affected Kotlin Toolchain")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(
            project,
            root,
            kotlinToolchainCommands(File(root), tasks),
            "Affected Kotlin Toolchain",
        )

    override suspend fun runAndWaitSuspending(
        project: Project,
        root: String,
        tasks: List<String>,
        changes: BuildChanges,
    ): Boolean = CommandRunner.runBatchAndWait(
        project,
        root,
        kotlinToolchainCommands(File(root), tasks, changes),
        "Affected Kotlin Toolchain",
    )

    private fun manifestOf(project: Project): File? =
        project.basePath?.let(::File)?.let(::kotlinToolchainProjectRoot)?.let(::kotlinToolchainManifest)
}

internal object KotlinToolchainTasks {
    const val TEST = "test"
    const val BUILD = "build"
}

internal fun kotlinToolchainProjectRoot(base: File): File? =
    nestedBuildRoot(base) { kotlinToolchainManifest(it) != null }

internal fun kotlinToolchainManifest(root: File): File? {
    if (GRADLE_SETTINGS.any { File(root, it).isRegularFileNoFollow() }) return null
    val yaml = TOOLCHAIN_YAML.firstOrNull { File(root, it).isRegularFileNoFollow() } ?: return null
    if (kotlinToolchainWrapper(root) == null) return null
    return File(root, yaml)
}

internal fun kotlinToolchainWrapper(root: File): String? {
    val windows = File.separatorChar == '\\'
    val script = File(root, "kotlin")
    val batch = File(root, "kotlin.bat")
    return when {
        windows && batch.isRegularFileNoFollow() -> "kotlin.bat"
        script.isRegularFileNoFollow() -> "./kotlin"
        batch.isRegularFileNoFollow() -> "kotlin.bat"
        else -> null
    }
}

internal fun kotlinToolchainRootModule(root: File): BuildModule {
    val rootPath = root.invariantSeparatorsPath
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = listOf(rootPath),
        testTask = KotlinToolchainTasks.TEST,
        compileTask = KotlinToolchainTasks.BUILD,
        hasTests = kotlinToolchainHasTests(root),
        executionId = ".",
    )
}

internal fun kotlinToolchainHasTests(root: File): Boolean =
    root.listFiles().orEmpty().any { candidate ->
        candidate.isDirectory &&
            (candidate.name == "test" || candidate.name.startsWith("test@")) &&
            candidate.walkTopDown().any(::kotlinToolchainSourceFile)
    }

internal fun kotlinToolchainModules(root: File): List<BuildModule>? {
    val manifest = File(root, "project.yaml")
    if (!manifest.isRegularFileNoFollow()) return listOf(kotlinToolchainRootModule(root))
    val text = runCatching { manifest.readText() }.getOrNull() ?: return null
    val section = MODULES_SECTION.find(text)?.groupValues[1]
    if (section != null && UNPROVED_TOOLCHAIN_MODULE.containsMatchIn(section)) {
        return listOf(kotlinToolchainRootModule(root))
    }
    val declared = section?.let { block ->
        TOOLCHAIN_MODULE_PATH.findAll(block).map { it.groupValues[1] }.toList()
    }.orEmpty()
    val modules = LinkedHashMap<String, BuildModule>()
    if (File(root, "module.yaml").isRegularFileNoFollow()) {
        modules["."] = kotlinToolchainRootModule(root)
    }
    for (directory in declared) {
        val relative = directory.replace('\\', '/').removePrefix("./").ifEmpty { "." }
        if (relative == "." || ".." in relative || relative.startsWith("/")) return null
        val content = File(root, relative)
        if (!File(content, "module.yaml").isRegularFileNoFollow()) return null
        if (relative in modules) return null
        modules[relative] = BuildModule(
            id = relative,
            root = root.invariantSeparatorsPath,
            contentRoots = listOf(content.invariantSeparatorsPath),
            testTask = KotlinToolchainTasks.TEST,
            compileTask = KotlinToolchainTasks.BUILD,
            hasTests = kotlinToolchainHasTests(content),
            executionRoot = root.invariantSeparatorsPath,
            executionId = relative,
        )
    }
    return modules.values.toList().takeIf { it.isNotEmpty() }
}

internal fun kotlinToolchainRequiresWorkspace(root: String, changes: BuildChanges): Boolean {
    val rootPath = File(root).toPath().toAbsolutePath().normalize()
    return changes.files.any { raw ->
        val file = File(raw).toPath().toAbsolutePath().normalize()
        if (!file.startsWith(rootPath)) return@any true
        val relative = rootPath.relativize(file).toString().replace('\\', '/')
        relative == "project.yaml" || relative == "module.yaml" || relative == "kotlin" || relative == "kotlin.bat"
    }
}

internal fun kotlinToolchainCommands(
    root: File,
    tasks: List<String>,
    changes: BuildChanges? = null,
): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    val wrapper = kotlinToolchainWrapper(root) ?: return emptyList()
    val verbs = tasks.map { it.substringAfterLast(':') }.toSet()
    val verb = if (verbs == setOf(KotlinToolchainTasks.BUILD)) {
        KotlinToolchainTasks.BUILD
    } else {
        KotlinToolchainTasks.TEST
    }
    val modules = tasks.map { it.substringBeforeLast(':') }.distinct()
    val scoped = kotlinToolchainVersionProven(root) &&
        modules.none { it == "." } &&
        modules.all { MODULE_NAME.matches(it) }
    val platformArgs = kotlinToolchainPlatforms(root, changes).orEmpty().flatMap { listOf("-p", it) }
    val arguments = when {
        !scoped && platformArgs.isEmpty() -> listOf(wrapper, verb)
        !scoped -> listOf(wrapper, verb) + platformArgs
        verb == KotlinToolchainTasks.BUILD && modules.size == 1 ->
            listOf(wrapper, verb, "-m", modules.single()) + platformArgs
        verb == KotlinToolchainTasks.TEST ->
            listOf(wrapper, verb) + modules.sorted().flatMap { listOf("-m", it) } + platformArgs
        else -> listOf(wrapper, verb) + platformArgs
    }
    return listOf(CliCommand("kotlin $verb", arguments))
}

internal fun kotlinToolchainPlatforms(root: File, changes: BuildChanges?): List<String>? {
    if (changes == null || !changes.comparedToBase || changes.files.isEmpty()) return null
    if (!kotlinToolchainVersionProven(root)) return null
    val rootPath = root.toPath().toAbsolutePath().normalize()
    val platforms = LinkedHashSet<String>()
    for (raw in changes.files) {
        val file = File(raw).toPath().toAbsolutePath().normalize()
        if (!file.startsWith(rootPath)) return null
        val relative = rootPath.relativize(file).toString().replace('\\', '/')
        val name = PLATFORM_QUALIFIER.find(relative)?.groupValues?.get(1) ?: return null
        if (name !in TOOLCHAIN_PLATFORMS) return null
        platforms += name
    }
    return platforms.takeIf { it.isNotEmpty() }?.sorted()
}

internal fun kotlinToolchainVersionProven(root: File): Boolean {
    val wrapper = File(root, "kotlin").takeIf(File::isRegularFileNoFollow)
        ?: File(root, "kotlin.bat").takeIf(File::isRegularFileNoFollow)
        ?: return false
    val text = runCatching { wrapper.readText() }.getOrNull() ?: return false
    val version = TOOLCHAIN_VERSION.find(text)?.groupValues?.get(1) ?: return false
    return PROVEN_TOOLCHAIN_VERSION.matches(version)
}

private fun kotlinToolchainSourceFile(file: File): Boolean =
    file.isFile && file.extension in setOf("kt", "kts", "java")

private val TOOLCHAIN_YAML = listOf("project.yaml", "module.yaml")
private val GRADLE_SETTINGS = listOf("settings.gradle.kts", "settings.gradle")
private val MODULES_SECTION = Regex("""(?m)^modules:\s*\n((?:[ \t]+.*\n?)*)""")
private val UNPROVED_TOOLCHAIN_MODULE = Regex("""[*?\[]|\*\*""")
private val TOOLCHAIN_MODULE_PATH = Regex("""(?m)^[ \t]*-[ \t]+(?:\./)?([A-Za-z0-9][A-Za-z0-9_./-]*)[ \t]*$""")
private val MODULE_NAME = Regex("""[A-Za-z0-9][A-Za-z0-9_./-]*""")
private val TOOLCHAIN_VERSION = Regex("""(?im)(?:^|\n)\s*(?:set\s+)?kotlin_cli_version=([0-9]+\.[0-9]+\.[0-9]+)""")
private val PROVEN_TOOLCHAIN_VERSION = Regex("""0\.11\.[0-9]+""")
private val PLATFORM_QUALIFIER = Regex("""(?:^|/)(?:src|test|resources|testResources)@([A-Za-z][A-Za-z0-9]*)(?:/|$)""")
private val TOOLCHAIN_PLATFORMS = setOf(
    "jvm",
    "android",
    "web",
    "js",
    "wasmJs",
    "wasmWasi",
    "native",
    "linux",
    "linuxX64",
    "linuxArm64",
    "mingw",
    "mingwX64",
    "apple",
    "macos",
    "macosX64",
    "macosArm64",
    "ios",
    "iosArm64",
    "iosSimulatorArm64",
    "iosX64",
    "watchos",
    "watchosArm32",
    "watchosArm64",
    "watchosDeviceArm64",
    "watchosSimulatorArm64",
    "tvos",
    "tvosArm64",
    "tvosSimulatorArm64",
    "tvosX64",
    "androidNative",
    "androidNativeArm32",
    "androidNativeArm64",
    "androidNativeX64",
    "androidNativeX86",
)

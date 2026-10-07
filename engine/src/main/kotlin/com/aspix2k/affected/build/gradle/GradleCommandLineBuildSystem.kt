package com.aspix2k.affected.build.gradle

import com.aspix2k.affected.build.BuildChanges
import com.aspix2k.affected.build.BuildModule
import com.aspix2k.affected.build.EngineBuildSystem
import com.aspix2k.affected.build.SourceRootsBuildSystem
import com.aspix2k.affected.build.Workspace
import com.aspix2k.affected.build.WorkspaceChangesBuildSystem
import com.aspix2k.affected.build.capture
import com.aspix2k.affected.build.isJvmTestSourceSet
import com.aspix2k.affected.build.process.CliCommand
import com.aspix2k.affected.build.rootFallbackModule
import com.aspix2k.affected.build.runBatch
import com.aspix2k.affected.build.runBatchAndWait
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files

internal class GradleCommandLineBuildSystem : EngineBuildSystem, WorkspaceChangesBuildSystem, SourceRootsBuildSystem {

    override val id: String = GRADLE_SYSTEM_ID

    override val sourceExtensions: Set<String> =
        JVM_SOURCE_EXTENSIONS + setOf("gradle", "kts", "properties", "toml", "xml", "json", "pro")

    override val consumersNeedSignatureChange: Boolean = true

    override val singleOwnerPerRoot: Boolean = true

    override fun isTestSource(path: String): Boolean = isJvmTestSourceSet(path)

    override fun isPresent(workspace: Workspace): Boolean = workspace.root?.let(::isGradleRoot) == true

    override fun requiresWorkspace(module: BuildModule, changes: BuildChanges): Boolean =
        gradleRequiresWorkspace(module.root, changes) || gradleUnverifiableScriptChanged(module, changes)

    override fun sourceRoots(module: BuildModule): List<String> = module.contentRoots.drop(1)

    override fun consumerRoots(root: String, candidateRoots: Set<String>): Set<String> =
        gradleConsumerRoots(root, candidateRoots)

    override fun modules(workspace: Workspace): List<BuildModule> {
        val root = workspace.root?.takeIf(::isGradleRoot) ?: return emptyList()
        return compositeModules(workspace, root) ?: listOf(unverifiable(root))
    }

    private fun compositeModules(workspace: Workspace, root: File): List<BuildModule>? {
        val launcher = gradleLauncher(root)
        val pending = ArrayDeque(listOf(GradleBuild(null, root)))
        val seen = HashSet<File>()
        val builds = ArrayList<Pair<List<BuildModule>, List<File>>>()
        while (pending.isNotEmpty()) {
            val build = pending.removeFirst()
            if (!seen.add(build.directory.canonicalFile)) continue
            if (seen.size > MAX_BUILDS) return null
            val model = readModel(workspace, launcher, build.directory) ?: return null
            val modules = gradleCommandLineModules(root, build, model) ?: return null
            val included = gradleIncludedBuilds(model) ?: return null
            builds += modules to included.map { it.directory.canonicalFile }
            pending += included
        }
        return gradleCompositeDependencies(builds)
    }

    override fun run(workspace: Workspace, root: String, tasks: List<String>) =
        workspace.runBatch(root, listOf(command(workspace, root, tasks)), TITLE)

    override suspend fun runAndWait(workspace: Workspace, root: String, tasks: List<String>): Boolean =
        workspace.runBatchAndWait(root, listOf(command(workspace, root, tasks)), TITLE)

    private fun command(workspace: Workspace, root: String, tasks: List<String>): CliCommand {
        val failureArguments = if (workspace.stopAfterFirstFailure) emptyList() else listOf("--continue")
        return CliCommand("gradle", listOf(gradleLauncher(File(root))) + tasks + failureArguments)
    }

    private fun readModel(workspace: Workspace, launcher: String, root: File): JsonObject? = runCatching {
        val directory = Files.createDirectories(workspace.cacheDirectory.resolve("gradle"))
        val script = directory.resolve("affected-model.init.gradle")
        Files.writeString(script, MODEL_INIT_SCRIPT)
        val output = Files.createTempFile(directory, "model-", ".json")
        try {
            workspace.capture(
                root.invariantSeparatorsPath,
                listOf(
                    launcher,
                    "--init-script",
                    script.toString(),
                    "-D$MODEL_OUTPUT_PROPERTY=$output",
                    "--no-configuration-cache",
                    "--quiet",
                    "help",
                ),
                timeoutSeconds = MODEL_TIMEOUT_SECONDS,
            ) ?: return null
            JsonParser.parseString(Files.readString(output)).asJsonObject
        } finally {
            Files.deleteIfExists(output)
        }
    }.getOrNull()

    private fun unverifiable(root: File): BuildModule =
        rootFallbackModule(root, "", null).copy(hasTests = false, systemId = id)

    private companion object {
        const val TITLE = "Affected Gradle"
        const val MODEL_TIMEOUT_SECONDS = 900L
        const val MAX_BUILDS = 64
    }
}

internal fun isGradleRoot(directory: File): Boolean = GRADLE_ROOT_FILES.any { File(directory, it).isFile }

internal fun gradleLauncher(root: File): String {
    val wrapper = File(root, if (File.separatorChar == '\\') "gradlew.bat" else "gradlew")
    return if (wrapper.isFile) wrapper.absolutePath else "gradle"
}

internal class GradleBuild(val name: String?, val directory: File)

internal fun gradleIncludedBuilds(model: JsonObject): List<GradleBuild>? = runCatching {
    model.getAsJsonArray("includedBuilds").map { it.asJsonObject }.map { build ->
        GradleBuild(build.get("name").asString.also { require(it.isNotBlank()) }, File(build.get("directory").asString))
    }
}.getOrNull()

internal fun gradleCompositeDependencies(builds: List<Pair<List<BuildModule>, List<File>>>): List<BuildModule> {
    val byRoot = builds.associate { (modules, _) -> modules.firstOrNull()?.root to modules }
    return builds.flatMap { (modules, included) ->
        val provided = included.flatMapTo(HashSet()) { directory ->
            byRoot[directory.invariantSeparatorsPath].orEmpty().map(BuildModule::key)
        }
        modules.map { it.copy(dependencies = it.dependencies + provided) }
    }
}

internal fun gradleCommandLineModules(root: File, build: GradleBuild, model: JsonObject): List<BuildModule>? =
    runCatching {
        val rootPath = root.invariantSeparatorsPath
        val buildPath = build.directory.canonicalFile.invariantSeparatorsPath
        val prefix = build.name?.let { ":$it" }.orEmpty()
        val projects = model.getAsJsonArray("projects").map { it.asJsonObject }
        val tasks = GradleTaskModel(
            projects.associate { project ->
                val tests = project.strings("tests").toSet()
                val names = project.strings("tasks").filterTo(HashSet()) { it in tests || !isGradleUnitTestTask(it) }
                project.directory() to names
            },
            projects.associate { it.directory() to it.strings("tests").toSet() },
        )
        val built = projects.associate { project ->
            val path = project.get("path").asString
            val id = path.takeUnless { it == ":" }.orEmpty()
            val testSources = project.strings("testSources").map(::normalized)
            val sources = (project.strings("sources").map(::normalized) + testSources).distinct() - project.directory()
            val module = gradleModule(
                id,
                project.directory(),
                buildPath,
                listOf(project.directory()) + sources,
                testSources,
                tasks,
                rootPath to "$prefix$id",
            )
            path to (module to project.strings("dependencies").toSet())
        }
        gradleModulesWithDependencies(built).takeIf { it.isNotEmpty() }
    }.getOrNull()

private fun JsonObject.directory(): String = normalized(get("directory").asString)

private fun normalized(path: String): String = File(path).invariantSeparatorsPath

private fun JsonObject.strings(name: String): List<String> =
    (get(name) as? JsonArray)?.map { it.asString }.orEmpty()

internal const val MODEL_OUTPUT_PROPERTY = "affected.model.output"

private val GRADLE_ROOT_FILES = listOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")

internal val MODEL_INIT_SCRIPT = """
def affectedModelOutput = System.getProperty('$MODEL_OUTPUT_PROPERTY')
if (affectedModelOutput != null) {
    gradle.projectsEvaluated { build ->
        def projects = build.rootProject.allprojects.collect { project ->
            def sources = []
            def testSources = []
            def sourceSets = project.extensions.findByName('sourceSets')
            if (sourceSets != null) {
                sourceSets.each { sourceSet ->
                    def target = sourceSet.name.toLowerCase().contains('test') ? testSources : sources
                    sourceSet.allSource.srcDirs.each { target << it.absolutePath }
                }
            }
            def dependencies = [] as Set
            try {
                project.configurations.each { configuration ->
                    configuration.dependencies.each { dependency ->
                        if (dependency instanceof org.gradle.api.artifacts.ProjectDependency) {
                            dependencies << (dependency.hasProperty('path') ? dependency.path : dependency.dependencyProject.path)
                        }
                    }
                }
            } catch (Throwable ignored) {
                dependencies = build.rootProject.allprojects.collect { it.path }.findAll { it != project.path } as Set
            }
            [
                path: project.path,
                directory: project.projectDir.absolutePath,
                tasks: project.tasks.names as List,
                tests: project.tasks.withType(org.gradle.api.tasks.testing.AbstractTestTask).names as List,
                sources: sources,
                testSources: testSources,
                dependencies: dependencies as List,
            ]
        }
        def includedBuilds = build.includedBuilds.collect { [name: it.name, directory: it.projectDir.absolutePath] }
        new File(affectedModelOutput).setText(
            groovy.json.JsonOutput.toJson([includedBuilds: includedBuilds, projects: projects]),
            'UTF-8',
        )
    }
}
""".trimIndent()

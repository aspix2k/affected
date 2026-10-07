package com.aspix2k.affected.build.maven

import com.aspix2k.affected.build.BuildChanges
import com.aspix2k.affected.build.BuildModule
import com.aspix2k.affected.build.EngineBuildSystem
import com.aspix2k.affected.build.SourceRootsBuildSystem
import com.aspix2k.affected.build.Workspace
import com.aspix2k.affected.build.WorkspaceChangesBuildSystem
import com.aspix2k.affected.build.isJvmTestSourceSet
import com.aspix2k.affected.build.mavenInvocationArguments
import com.aspix2k.affected.build.process.CliCommand
import com.aspix2k.affected.build.rootFallbackModule
import com.aspix2k.affected.build.runBatch
import com.aspix2k.affected.build.runBatchAndWait
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

internal class MavenCommandLineBuildSystem : EngineBuildSystem, WorkspaceChangesBuildSystem, SourceRootsBuildSystem {

    override val id: String = MAVEN_SYSTEM_ID

    override val sourceExtensions: Set<String> = setOf("kt", "java", "scala", "groovy", "xml", "properties")

    override val consumersNeedSignatureChange: Boolean = true

    override val singleOwnerPerRoot: Boolean = true

    override fun isTestSource(path: String): Boolean = isJvmTestSourceSet(path)

    override fun isPresent(workspace: Workspace): Boolean = workspace.root?.let(::isMavenRoot) == true

    override fun requiresWorkspace(module: BuildModule, changes: BuildChanges): Boolean =
        mavenRequiresWorkspace(module.root, changes)

    override fun sourceRoots(module: BuildModule): List<String> = module.contentRoots.flatMap { root ->
        listOf("src/main", "src/test").map { File(root, it) }.filter(File::isDirectory)
            .map { it.invariantSeparatorsPath }
    }

    override fun modules(workspace: Workspace): List<BuildModule> {
        val root = workspace.root?.takeIf(::isMavenRoot) ?: return emptyList()
        return mavenCommandLineModules(root)
            ?: listOf(rootFallbackModule(root, "", null).copy(hasTests = false, systemId = id))
    }

    override fun run(workspace: Workspace, root: String, tasks: List<String>) =
        workspace.runBatch(root, listOf(command(workspace, root, tasks)), TITLE)

    override suspend fun runAndWait(workspace: Workspace, root: String, tasks: List<String>): Boolean =
        workspace.runBatchAndWait(root, listOf(command(workspace, root, tasks)), TITLE)

    private fun command(workspace: Workspace, root: String, tasks: List<String>): CliCommand {
        val goals = tasks.map { it.substringAfterLast(':') }.distinct()
        val projects = tasks.mapNotNull { it.substringBeforeLast(':').takeIf(String::isNotBlank) }.distinct()
        val selection = if (projects.isEmpty()) emptyList() else listOf("--projects", projects.joinToString(","))
        val arguments = mavenInvocationArguments(selection, workspace.stopAfterFirstFailure)
        return CliCommand("maven", listOf(mavenLauncher(File(root))) + arguments + goals)
    }

    private companion object {
        const val TITLE = "Affected Maven"
    }
}

internal fun isMavenRoot(directory: File): Boolean = File(directory, POM).isFile

internal fun mavenLauncher(root: File): String {
    val wrapper = File(root, if (File.separatorChar == '\\') "mvnw.cmd" else "mvnw")
    return if (wrapper.isFile) wrapper.absolutePath else "mvn"
}

private class Pom(
    val file: File,
    val groupId: String,
    val artifactId: String,
    val parent: String?,
    val modules: List<String>,
    val dependencies: Set<String>,
    val failsafe: Boolean,
) {
    val key: String get() = "$groupId:$artifactId"
    val directory: File get() = file.parentFile
}

internal fun mavenCommandLineModules(root: File): List<BuildModule>? = runCatching {
    val poms = LinkedHashMap<String, Pom>()
    val pending = ArrayDeque(listOf(File(root, POM).canonicalFile))
    val seen = HashSet<File>()
    while (pending.isNotEmpty()) {
        val file = pending.removeFirst()
        if (!seen.add(file)) continue
        if (seen.size > MAX_POMS || !file.startsWith(root.canonicalFile)) return null
        val pom = readPom(file) ?: return null
        if (poms.put(pom.key, pom) != null) return null
        pom.modules.forEach { module ->
            val target = File(pom.directory, module)
            pending += (if (target.isDirectory) File(target, POM) else target).canonicalFile
        }
    }
    val rootPath = root.invariantSeparatorsPath
    val goal = if (poms.values.any(Pom::failsafe)) "verify" else "test"
    val modules = poms.mapValues { (_, pom) ->
        val directory = pom.directory.invariantSeparatorsPath
        BuildModule(
            id = pom.artifactId,
            root = rootPath,
            contentRoots = listOf(directory),
            testTask = goal,
            compileTask = MAVEN_COMPILE_GOAL,
            hasTests = File(pom.directory, "src/test").isDirectory,
            executionId = pom.key,
            systemId = MAVEN_SYSTEM_ID,
        )
    }
    poms.values.map { pom ->
        val module = modules.getValue(pom.key)
        val inherited = generateSequence(pom) { it.parent?.let(poms::get) }.take(MAX_POMS).flatMap { it.dependencies }
        module.copy(dependencies = inherited.mapNotNullTo(HashSet()) { modules[it]?.key } - module.key)
    }
}.getOrNull()

private fun readPom(file: File): Pom? {
    if (!file.isFile || file.length() > MAX_POM_BYTES) return null
    val text = file.readText()
    val factory = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        isNamespaceAware = false
    }
    val project = factory.newDocumentBuilder().parse(text.byteInputStream()).documentElement
    if (project.tagName != "project") return null
    val parent = project.child("parent")
    val groupId = project.text("groupId") ?: parent?.text("groupId") ?: return null
    val artifactId = project.text("artifactId") ?: return null
    val scopes = listOf(project) + project.child("profiles")?.children("profile").orEmpty()
    val modules = scopes.flatMap { it.child("modules")?.children("module").orEmpty() }.map { it.textContent.trim() }
    val dependencies = scopes.flatMap { it.child("dependencies")?.children("dependency").orEmpty() }
        .mapNotNullTo(HashSet()) { dependency ->
            val group = dependency.text("groupId") ?: return@mapNotNullTo null
            dependency.text("artifactId")?.let { "${resolve(group, groupId)}:$it" }
        }
    if ((listOf(groupId, artifactId) + modules).any { PROPERTY in it }) return null
    return Pom(
        file,
        groupId,
        artifactId,
        parent?.let { "${it.text("groupId")}:${it.text("artifactId")}" },
        modules,
        dependencies,
        failsafe = FAILSAFE in text,
    )
}

private fun resolve(group: String, projectGroup: String): String =
    if (group in PROJECT_GROUP_PROPERTIES) projectGroup else group

private fun Element.children(name: String): List<Element> {
    val nodes = childNodes
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.tagName == name }
}

private fun Element.child(name: String): Element? = children(name).firstOrNull()

private fun Element.text(name: String): String? = child(name)?.textContent?.trim()?.takeIf(String::isNotEmpty)

private const val POM = "pom.xml"
private const val PROPERTY = "\${"
private const val FAILSAFE = "maven-failsafe-plugin"
private const val MAX_POMS = 2_000
private const val MAX_POM_BYTES = 4L * 1024L * 1024L
private val PROJECT_GROUP_PROPERTIES = setOf("\${project.groupId}", "\${pom.groupId}", "\${groupId}")

package com.aspix2k.affected.build

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal class PhpunitSelectiveRun private constructor(
    val commands: List<CliStep>,
    private val packages: List<PhpunitPackageRun>,
) {

    fun complete(): Boolean = packages.all(PhpunitPackageRun::complete)

    fun close() {
        packages.forEach(PhpunitPackageRun::close)
    }

    companion object {
        fun create(
            project: Project,
            root: Path,
            tasks: List<String>,
            modules: List<BuildModule>,
            changes: BuildChanges,
            adapter: Path,
        ): PhpunitSelectiveRun? = runCatching {
            val realRoot = root.toAbsolutePath().normalize()
            require(Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(realRoot))
            val byExecutionId = modules.associateBy(BuildModule::executionId)
            require(tasks.all { it.substringBeforeLast(':') in byExecutionId })
            val cache = securePhpunitDirectory(
                PathManager.getSystemDir()
                    .resolve(PHPUNIT_CACHE_DIRECTORY)
                    .resolve(project.locationHash)
                    .resolve("phpunit"),
            )
            val packageRuns = ArrayList<PhpunitPackageRun>()
            val commands = ArrayList<CliStep>()
            tasks.forEach { task ->
                val executionId = task.substringBeforeLast(':')
                val verb = task.substringAfterLast(':')
                val module = byExecutionId.getValue(executionId)
                if (verb == ComposerPackages.ANALYSE) {
                    val path = module.contentRoots.single().removePrefix("$realRoot/").ifEmpty { "." }
                    commands += CliCommand("phpstan $executionId", listOf("php", "vendor/bin/phpstan", "analyse", path))
                } else {
                    require(verb == ComposerPackages.TEST)
                    val packageRun = PhpunitPackageRun(
                        realRoot,
                        module,
                        requireNotNull(phpunitDependencyRoots(module, modules)),
                        changes,
                        adapter,
                        cache.resolve(sha256(executionId)),
                    )
                    packageRuns += packageRun
                    commands += DeferredCliCommand(
                        title = "phpunit $executionId",
                        environment = packageRun::environment,
                        arguments = packageRun::resolve,
                    )
                }
            }
            PhpunitSelectiveRun(commands, packageRuns)
        }.getOrNull()
    }
}

private class PhpunitPackageRun(
    private val root: Path,
    private val module: BuildModule,
    private val productionRoots: Set<Path>,
    private val changes: BuildChanges,
    private val adapter: Path,
    cache: Path,
) {

    private val directory = securePhpunitDirectory(cache)
    private val store = PhpunitTestBaselineStore(directory.resolve("maps"))
    private var state: PhpunitRunState = PhpunitRunState.Unresolved

    @Synchronized
    fun resolve(): List<String>? {
        check(state == PhpunitRunState.Unresolved)
        val runtime = readPhpunitRuntime(root)
        val before = runtime?.let(::projectState)
        if (runtime == null || before == null) {
            state = PhpunitRunState.Unsupported
            return legacyPhpunitArguments()
        }
        val baseline = store.read()
        val selection = boundedPhpunitSelection(selectPhpunitTests(root, before, baseline, changes), baseline)
        val files = runCatching {
            val output = newPhpunitFile(directory.resolve("runs"), "run-", ".json", delete = true)
            val context = newPhpunitContext(root, before, output, selection == PhpunitTestSelection.Full, directory)
            output to context
        }.getOrElse {
            state = PhpunitRunState.Unsupported
            return legacyPhpunitArguments()
        }
        val (output, context) = files
        state = PhpunitRunState.Selected(runtime, before, baseline, selection, output, context)
        return phpunitArguments(selection, baseline)
    }

    @Synchronized
    fun environment(): Map<String, String> = when (val resolved = state) {
        is PhpunitRunState.Selected -> mapOf(PHPUNIT_CONTEXT_ENVIRONMENT to resolved.context.toString())
        else -> emptyMap()
    }

    @Synchronized
    fun complete(): Boolean = when (val resolved = state) {
        PhpunitRunState.Unresolved -> false
        PhpunitRunState.Unsupported -> true
        is PhpunitRunState.Selected -> {
            val after = projectState(resolved.runtime)
            when (resolved.selection) {
                PhpunitTestSelection.Full -> {
                    promotePhpunitBaseline(
                        store,
                        resolved.before,
                        after,
                        resolved.output,
                        full = true,
                        passed = true,
                    )
                    true
                }
                is PhpunitTestSelection.Exact -> completePhpunitSelection(
                    resolved.selection,
                    resolved.before,
                    after,
                    resolved.output,
                    requireNotNull(resolved.baseline),
                )
            }
        }
    }

    @Synchronized
    fun close() {
        val selected = state as? PhpunitRunState.Selected ?: return
        listOf(selected.output, selected.context).forEach { runCatching { Files.deleteIfExists(it) } }
    }

    private fun projectState(runtime: PhpunitTestMetadata): PhpunitProjectState? = readPhpunitProjectState(
        root,
        Path.of(module.contentRoots.single()),
        productionRoots,
        adapter,
        runtime,
        System.getenv(),
    )

    private fun phpunitArguments(
        selection: PhpunitTestSelection,
        baseline: PhpunitTestSnapshot?,
    ): List<String> {
        val arguments = mutableListOf(
            "php",
            "-d",
            "auto_prepend_file=${adapter.toAbsolutePath().normalize()}",
            "vendor/bin/phpunit",
            "--extension",
            PHPUNIT_EXTENSION,
            "--do-not-cache-result",
            "--no-coverage",
            "--fail-on-empty-test-suite",
        )
        when (selection) {
            PhpunitTestSelection.Full -> arguments += packagePath()
            is PhpunitTestSelection.Exact -> {
                require(baseline != null && selection.classes.size <= MAX_PHPUNIT_FILTER_CLASSES)
                val filter = phpunitClassFilter(selection.classes)
                require(filter.toByteArray(StandardCharsets.UTF_8).size <= MAX_PHPUNIT_FILTER_BYTES)
                val files = selection.classes.map { baseline.classes.getValue(it) }.distinct().sorted()
                require(files.isNotEmpty())
                arguments += listOf("--filter", filter)
                arguments += files
            }
        }
        return arguments
    }

    private fun legacyPhpunitArguments(): List<String> = listOf("php", "vendor/bin/phpunit", packagePath())

    private fun packagePath(): String = Path.of(module.contentRoots.single()).toAbsolutePath().normalize()
        .let(root::relativize)
        .portablePath()
        .ifEmpty { "." }
}

private sealed interface PhpunitRunState {
    data object Unresolved : PhpunitRunState
    data object Unsupported : PhpunitRunState
    data class Selected(
        val runtime: PhpunitTestMetadata,
        val before: PhpunitProjectState,
        val baseline: PhpunitTestSnapshot?,
        val selection: PhpunitTestSelection,
        val output: Path,
        val context: Path,
    ) : PhpunitRunState
}

private fun phpunitDependencyRoots(module: BuildModule, modules: List<BuildModule>): Set<Path>? {
    val byKey = modules.associateBy(BuildModule::key)
    val pending = ArrayDeque<String>()
    pending += module.key
    val visited = LinkedHashSet<String>()
    val roots = LinkedHashSet<Path>()
    while (pending.isNotEmpty()) {
        val key = pending.removeFirst()
        if (!visited.add(key)) continue
        val dependency = byKey[key] ?: return null
        dependency.contentRoots.mapTo(roots) { Path.of(it).toAbsolutePath().normalize() }
        pending += dependency.dependencies
    }
    return roots
}

private fun newPhpunitContext(
    root: Path,
    state: PhpunitProjectState,
    output: Path,
    full: Boolean,
    directory: Path,
): Path {
    val context = newPhpunitFile(directory.resolve("contexts"), "context-", ".json", delete = false)
    return try {
        val json = JsonObject().apply {
            addProperty("schema", 1)
            addProperty("root", root.toAbsolutePath().normalize().toString())
            addProperty("output", output.toAbsolutePath().normalize().toString())
            addProperty("full", full)
            add("artifacts", JsonArray().also { array -> state.artifacts.keys.sorted().forEach(array::add) })
        }.toString()
        require(json.toByteArray(StandardCharsets.UTF_8).size <= MAX_PHPUNIT_CONTEXT_BYTES)
        Files.writeString(context, json, StandardCharsets.UTF_8)
        context
    } catch (error: Exception) {
        runCatching { Files.deleteIfExists(context) }
        throw error
    }
}

private fun newPhpunitFile(directory: Path, prefix: String, suffix: String, delete: Boolean): Path {
    val secure = securePhpunitDirectory(directory)
    return Files.createTempFile(secure, prefix, suffix).also {
        require(Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it))
        if (delete) Files.delete(it)
    }
}

internal fun phpunitClassFilter(classes: List<String>): String {
    require(classes.isNotEmpty() && classes.distinct().size == classes.size)
    return classes.sorted().joinToString(prefix = "~^(?:", separator = "|", postfix = ")::~D") { className ->
        buildString {
            className.forEach { character ->
                if (character in PHPUNIT_PCRE_SPECIAL) append('\\')
                append(character)
            }
        }
    }
}

internal fun boundedPhpunitSelection(
    selection: PhpunitTestSelection,
    baseline: PhpunitTestSnapshot?,
): PhpunitTestSelection {
    if (selection !is PhpunitTestSelection.Exact) return selection
    val supported = runCatching {
        require(baseline != null && selection.classes.size <= MAX_PHPUNIT_FILTER_CLASSES)
        require(selection.classes.all(baseline.classes::containsKey))
        val filterBytes = phpunitClassFilter(selection.classes).toByteArray(StandardCharsets.UTF_8).size
        require(filterBytes <= MAX_PHPUNIT_FILTER_BYTES)
    }.isSuccess
    return if (supported) selection else PhpunitTestSelection.Full
}

private val PHPUNIT_PCRE_SPECIAL = setOf('\\', '^', '$', '.', '|', '(', ')', '[', ']', '*', '+', '?', '{', '}', '~')
private const val PHPUNIT_CACHE_DIRECTORY = "affected"
private const val PHPUNIT_CONTEXT_ENVIRONMENT = "AFFECTED_PHPUNIT_CONTEXT"
private const val PHPUNIT_EXTENSION = "Affected\\Phpunit\\Extension"
private const val MAX_PHPUNIT_CONTEXT_BYTES = 1024 * 1024
private const val MAX_PHPUNIT_FILTER_CLASSES = 256
private const val MAX_PHPUNIT_FILTER_BYTES = 32 * 1024

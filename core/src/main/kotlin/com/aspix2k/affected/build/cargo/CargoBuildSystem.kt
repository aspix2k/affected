package com.aspix2k.affected.build.cargo

import com.aspix2k.affected.AffectedSettings
import com.aspix2k.affected.build.AllFileChangesBuildSystem
import com.aspix2k.affected.build.BuildChanges
import com.aspix2k.affected.build.BuildModule
import com.aspix2k.affected.build.ChangeAwareSuspendingBuildSystem
import com.aspix2k.affected.build.ManifestSearch
import com.aspix2k.affected.build.WorkspaceChangesBuildSystem
import com.aspix2k.affected.build.combineFingerprints
import com.aspix2k.affected.build.continuesAfterFailure
import com.aspix2k.affected.build.failClosedModules
import com.aspix2k.affected.build.isRegularFileNoFollow
import com.aspix2k.affected.build.nestedBuildRoots
import com.aspix2k.affected.build.pathSegments
import com.aspix2k.affected.build.process.CliCommand
import com.aspix2k.affected.build.process.CommandRunner
import com.aspix2k.affected.build.retainBuildSnapshot
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class CargoBuildSystem : ChangeAwareSuspendingBuildSystem, AllFileChangesBuildSystem, WorkspaceChangesBuildSystem {

    private data class Snapshot(val stamp: String, val modules: List<BuildModule>)

    private val cache = ConcurrentHashMap<String, Snapshot>()

    override val id: String = "CARGO"

    override fun isTestSource(path: String): Boolean =
        pathSegments(path).any { it == "tests" || it == "benches" }

    override val sourceExtensions: Set<String> = setOf("rs", "toml", "lock")

    override fun isPresent(project: Project): Boolean = rootsOf(project).isNotEmpty()

    override fun modules(project: Project): List<BuildModule> {
        val roots = rootsOf(project)
        cache.keys.retainAll(roots.mapTo(HashSet()) { it.invariantSeparatorsPath })
        return roots.flatMap(::modulesOf)
    }

    private fun modulesOf(directory: File): List<BuildModule> {
        val root = directory.invariantSeparatorsPath
        val manifests = ManifestSearch.find(directory, setOf("Cargo.toml"))
        val environment = System.getenv()
        val requestedProfile = cargoNextestProfile(environment)
        val cargoConfigurationPresent = cargoConfigurationExists(directory, environment)
        val unsupportedEnvironment = unsupportedNextestEnvironment(environment)
        val executableStamp = cargoNextestExecutableStamp(environment)
        val inputStamp = combineFingerprints(
            cargoManifestFingerprint(directory, manifests),
            requestedProfile.orEmpty(),
            cargoConfigurationPresent.toString(),
            unsupportedEnvironment.toString(),
            executableStamp,
            cargoBuildScriptLayout(directory, manifests),
        )
        if (inputStamp != null) {
            cache[root]?.takeIf { it.stamp == inputStamp }?.let {
                return it.modules
            }
        }
        val nextest = discoverCargoNextest(
            directory,
            requestedProfile = requestedProfile,
            cargoConfigurationPresent = cargoConfigurationPresent,
            unsupportedEnvironment = unsupportedEnvironment,
            executable = cargoNextestExecutable(environment),
            cargo = cargoExecutable(environment),
        )

        val output = CommandRunner.capture(root, METADATA)
        val effectiveNextest = conservativeCargoNextest(nextest, output?.let(CargoMetadata::hasCustomBuild))
        val discovered = output?.let { metadata ->
            CargoMetadata.parse(metadata, root) { hasDoctests ->
                effectiveNextest.profile?.let { cargoNextestTask(effectiveNextest, hasDoctests) } ?: CargoMetadata.TEST
            }
        }
        val fallbackTask = effectiveNextest.profile?.let { cargoNextestTask(effectiveNextest) } ?: CargoMetadata.TEST
        val discovery = failClosedModules(directory, fallbackTask, CargoMetadata.COMPILE, discovered)
        val discoveredManifests = discovery.modules.mapTo(HashSet()) { module ->
            File(module.contentRoots.single(), "Cargo.toml").absoluteFile.normalize().invariantSeparatorsPath
        }
        val fingerprintedManifests = manifests.mapTo(HashSet()) {
            it.absoluteFile.normalize().invariantSeparatorsPath
        }
        if (inputStamp != null && discovery.complete && fingerprintedManifests.containsAll(discoveredManifests)) {
            cache.retainBuildSnapshot(root, Snapshot(inputStamp, discovery.modules), discovery.modules.size)
        }
        return discovery.modules
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        val stopAfterFirstFailure = AffectedSettings.getInstance().stopAfterFirstFailure
        CommandRunner.runBatch(
            project,
            root,
            cargoCommandsForRun(root, tasks, stopAfterFirstFailure = stopAfterFirstFailure),
            "Affected Cargo",
            continueAfterFailure = continuesAfterFailure(stopAfterFirstFailure),
        )
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean {
        val stopAfterFirstFailure = AffectedSettings.getInstance().stopAfterFirstFailure
        return CommandRunner.runBatchAndWait(
            project,
            root,
            cargoCommandsForRun(root, tasks, stopAfterFirstFailure = stopAfterFirstFailure),
            "Affected Cargo",
            continueAfterFailure = continuesAfterFailure(stopAfterFirstFailure),
        )
    }

    override suspend fun runAndWaitSuspending(
        project: Project,
        root: String,
        tasks: List<String>,
        changes: BuildChanges,
    ): Boolean {
        val stopAfterFirstFailure = AffectedSettings.getInstance().stopAfterFirstFailure
        val commands = withContext(Dispatchers.IO) {
            cargoCommands(root, tasks, changes, stopAfterFirstFailure = stopAfterFirstFailure)
        }
        return CommandRunner.runBatchAndWait(
            project,
            root,
            commands,
            "Affected Cargo",
            continueAfterFailure = continuesAfterFailure(stopAfterFirstFailure),
        )
    }

    override fun requiresWorkspace(module: BuildModule, changes: BuildChanges): Boolean =
        cargoNextestWorkspaceTask(module.testTask) || changes.requireCargoWorkspace(module.root)

    private fun rootsOf(project: Project): List<File> =
        project.basePath?.let(::File)?.let(::cargoProjectRoots).orEmpty()

    private fun discoverCargoNextest(
        root: File,
        requestedProfile: String?,
        cargoConfigurationPresent: Boolean,
        unsupportedEnvironment: Boolean,
        executable: Path?,
        cargo: Path?,
    ): CargoNextestPlan {
        if (unsupportedEnvironment || cargoConfigurationPresent) {
            return CargoNextestPlan(CargoNextestMode.CARGO_TEST, null)
        }
        if (executable == null || cargo == null) {
            return CargoNextestPlan(CargoNextestMode.CARGO_TEST, null)
        }
        val executableIdentity = cargoNextestExecutableIdentity(executable)
            ?: return CargoNextestPlan(CargoNextestMode.CARGO_TEST, null)
        val validationConfig = cargoNextestValidationSnapshot(root, requestedProfile)
            ?: return CargoNextestPlan(CargoNextestMode.CARGO_TEST, null)
        val directory = root.invariantSeparatorsPath
        val discoveryEnvironment = cargoNextestDiscoveryEnvironment(cargo.toString())
        val version = CommandRunner.capture(
            directory,
            listOf(executable.toString(), "--version"),
            timeoutSeconds = 10,
            maxBytes = 4096,
            environment = discoveryEnvironment,
        )
        val configuration = version?.let {
            CommandRunner.capture(
                directory,
                listOf(
                    executable.toString(), "nextest", "show-config", "version",
                    "--manifest-path", File(root, "Cargo.toml").path,
                    "--config-file", validationConfig.path,
                ),
                timeoutSeconds = 20,
                maxBytes = 16 * 1024,
                environment = discoveryEnvironment,
            )
        }
        if (cargoNextestExecutableIdentity(executable) != executableIdentity) {
            return CargoNextestPlan(CargoNextestMode.CARGO_TEST, null)
        }
        return detectCargoNextest(root, version, configuration, requestedProfile, cargoConfigurationPresent)
            .let { plan -> if (plan.profile == null) plan else plan.copy(executableIdentity = executableIdentity) }
    }

    private companion object {
        val METADATA = listOf("cargo", "metadata", "--no-deps", "--format-version", "1")
    }
}

internal fun conservativeCargoNextest(plan: CargoNextestPlan, hasCustomBuild: Boolean?): CargoNextestPlan =
    if (hasCustomBuild != false && plan.profile != null) plan.copy(mode = CargoNextestMode.WORKSPACE) else plan

private fun cargoManifestFingerprint(root: File, manifests: List<File>): String? {
    val config = File(root, ".config/nextest.toml")
    val inputs = manifests + listOf(File(root, "Cargo.lock"), config).filter(File::exists)
    return ManifestSearch.fingerprint(root, inputs)
}

internal fun cargoProjectRoots(base: File): List<File> =
    nestedBuildRoots(base, setOf("cargo.toml")) { cargoManifest(it) != null }

internal fun cargoManifest(root: File): File? =
    File(root, "Cargo.toml").takeIf(File::isRegularFileNoFollow)

internal fun cargoBuildScriptLayout(root: File, manifests: List<File>): String? = runCatching {
    val rootAlias = root.toPath().toAbsolutePath().normalize()
    val markers = ArrayList<String>()
    for (manifest in manifests.sortedBy { it.path }) {
        val script = File(manifest.parentFile, "build.rs").toPath().toAbsolutePath().normalize()
        if (!script.startsWith(rootAlias) || Files.isSymbolicLink(script)) return null
        val state = when {
            !Files.exists(script, LinkOption.NOFOLLOW_LINKS) -> "missing"
            Files.isRegularFile(script, LinkOption.NOFOLLOW_LINKS) -> "file"
            else -> return null
        }
        markers += "${rootAlias.relativize(script)}=$state"
    }
    markers.joinToString(":")
}.getOrNull()

internal fun cargoCommands(tasks: List<String>): List<CliCommand> = cargoCommands(".", tasks)

internal fun cargoCommands(
    root: String,
    tasks: List<String>,
    unsafeCargoExecution: Boolean = false,
    nextestExecutable: String = "cargo-nextest",
    cargoExecutable: String = "cargo",
    stopAfterFirstFailure: Boolean? = null,
): List<CliCommand> =
    tasks.groupBy { canonicalCargoTask(it.substringAfterLast(':')) }
        .flatMap { (task, requestedTasks) ->
            val packages = requestedTasks.map { it.substringBeforeLast(':') }
            val executionTask = requestedTasks.first().substringAfterLast(':')
            val workspace = "." in packages || cargoNextestWorkspaceTask(task)
            val selection = if (workspace) listOf("--workspace") else packages.flatMap { listOf("-p", it) }
            if (task.startsWith("nextest@") || cargoNextestWorkspaceTask(task)) {
                if (unsafeCargoExecution) {
                    listOf(cargoTestCommand(listOf("--workspace"), stopAfterFirstFailure))
                } else {
                    cargoNextestCommands(
                        root,
                        executionTask,
                        nextestExecutable,
                        cargoExecutable,
                        selection,
                        requestedTasks.filter(::cargoNextestHasDoctests).map { it.substringBeforeLast(':') },
                        stopAfterFirstFailure,
                    )
                }
            } else {
                if (task == CargoMetadata.COMPILE) {
                    listOf(CliCommand("cargo check", listOf("cargo", "check", "--tests") + selection))
                } else {
                    listOf(cargoTestCommand(selection, stopAfterFirstFailure))
                }
            }
        }

internal fun cargoCommandsForRun(
    root: String,
    tasks: List<String>,
    environment: Map<String, String> = System.getenv(),
    stopAfterFirstFailure: Boolean? = null,
): List<CliCommand> {
    val executables = verifiedCargoNextestExecutables(root, tasks, environment)
    return cargoCommands(
        root,
        tasks,
        executables == null,
        executables?.nextest?.toString() ?: "cargo-nextest",
        executables?.cargo?.toString() ?: "cargo",
        stopAfterFirstFailure = stopAfterFirstFailure,
    )
}

private data class VerifiedCargoNextestExecutables(
    val nextest: Path,
    val cargo: Path,
)

private fun verifiedCargoNextestExecutables(
    root: String,
    tasks: List<String>,
    environment: Map<String, String>,
): VerifiedCargoNextestExecutables? {
    if (cargoConfigurationExists(File(root), environment) || unsupportedNextestEnvironment(environment)) return null
    val executable = cargoNextestExecutable(environment) ?: return null
    val cargo = cargoExecutable(environment) ?: return null
    val identity = cargoNextestExecutableIdentity(executable)
    val plannedIdentities = tasks.mapNotNull(::cargoNextestExecutableIdentityFromTask).toSet()
    return VerifiedCargoNextestExecutables(executable, cargo)
        .takeUnless { identity == null || plannedIdentities.size != 1 || identity !in plannedIdentities }
}

private fun cargoNextestCommands(
    root: String,
    task: String,
    executable: String,
    cargo: String,
    nextestSelection: List<String>,
    doctestPackages: List<String>,
    stopAfterFirstFailure: Boolean? = null,
): List<CliCommand> {
    val profile = task.split('@').getOrNull(1)
        ?: return listOf(cargoTestCommand(listOf("--workspace"), stopAfterFirstFailure))
    val encodedFailFast = task.split('@').getOrNull(3)?.toBooleanStrictOrNull()
        ?: return listOf(cargoTestCommand(listOf("--workspace"), stopAfterFirstFailure))
    val failFast = stopAfterFirstFailure ?: encodedFailFast
    val snapshot = cargoNextestSnapshot(task, failFast)
        ?: return listOf(cargoTestCommand(listOf("--workspace"), stopAfterFirstFailure))
    val doctestSelection = doctestPackages.distinct().let { packages ->
        if ("." in packages) listOf("--workspace") else packages.flatMap { listOf("-p", it) }
    }
    val doctest = doctestSelection.takeIf { it.isNotEmpty() }?.let { selected ->
        CliCommand(
            "cargo test --doc",
            listOf(
                cargo, "test", "--doc", "--manifest-path", File(root, "Cargo.toml").path,
            ) + if (failFast) selected else listOf("--no-fail-fast") + selected,
        )
    }
    val nextest = CliCommand(
        "cargo nextest",
        listOf(
            executable, "nextest", "run",
            "--manifest-path", File(root, "Cargo.toml").path,
            "--config-file", snapshot.path,
            "--profile", profile,
            "--no-tests=pass",
        ) + nextestSelection,
        environment = mapOf("CARGO" to cargo),
        continueOnFailure = !failFast && doctest != null,
    )
    return listOfNotNull(nextest, doctest)
}

private fun cargoTestCommand(selection: List<String>, stopAfterFirstFailure: Boolean?): CliCommand {
    val strategy = if (stopAfterFirstFailure == false) listOf("--no-fail-fast") else emptyList()
    return CliCommand("cargo test", listOf("cargo", "test") + strategy + selection)
}

private fun canonicalCargoTask(task: String): String =
    if (task.startsWith("nextest")) task.substringBeforeLast('@') else task

private fun cargoNextestHasDoctests(task: String): Boolean =
    task.substringAfterLast(':').substringAfterLast('@').toBooleanStrictOrNull() == true

internal fun cargoCommands(
    root: String,
    tasks: List<String>,
    changes: BuildChanges,
    stopAfterFirstFailure: Boolean? = null,
): List<CliCommand> {
    val executables = verifiedCargoNextestExecutables(root, tasks, System.getenv())
    return cargoCommands(
        root,
        tasks,
        changes,
        executables == null,
        executables?.nextest?.toString() ?: "cargo-nextest",
        executables?.cargo?.toString() ?: "cargo",
        stopAfterFirstFailure,
    )
}

internal fun cargoCommands(
    root: String,
    tasks: List<String>,
    changes: BuildChanges,
    unsafeCargoExecution: Boolean,
    nextestExecutable: String = "cargo-nextest",
    cargoExecutable: String = "cargo",
    stopAfterFirstFailure: Boolean? = null,
): List<CliCommand> {
    val workspace = changes.requireCargoWorkspace(root)
    val effectiveTasks = if (workspace) {
        tasks.map { task ->
            when (val nativeTask = task.substringAfterLast(':')) {
                CargoMetadata.TEST -> ".:${CargoMetadata.TEST}"
                else -> if (nativeTask.startsWith("nextest@") || cargoNextestWorkspaceTask(nativeTask)) {
                    ".:$nativeTask"
                } else {
                    task
                }
            }
        }
    } else {
        tasks
    }
    return cargoCommands(
        root,
        effectiveTasks,
        unsafeCargoExecution,
        nextestExecutable,
        cargoExecutable,
        stopAfterFirstFailure = stopAfterFirstFailure,
    )
}

private fun BuildChanges.requireCargoWorkspace(root: String): Boolean {
    if (!comparedToBase) return true
    val rootPath = File(root).toPath().toAbsolutePath().normalize()
    return files.any { raw ->
        val file = File(raw).toPath().toAbsolutePath().normalize()
        if (!file.startsWith(rootPath)) return@any true
        val relative = rootPath.relativize(file)
        val name = relative.fileName?.toString() ?: return@any true
        name == "build.rs" ||
            !name.endsWith(".rs") ||
            relative.any { segment -> segment.toString() in GENERATED_DIRECTORIES } ||
            raw !in exactSelectionEligible ||
            Files.isSymbolicLink(file) ||
            !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
    }
}

private val GENERATED_DIRECTORIES = setOf("generated", "gen", "out", "target")

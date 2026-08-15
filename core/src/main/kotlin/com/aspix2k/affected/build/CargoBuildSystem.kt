package com.aspix2k.affected.build

import com.aspix2k.affected.AffectedSettings
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

class CargoBuildSystem : ChangeAwareSuspendingBuildSystem, AllFileChangesBuildSystem, WorkspaceChangesBuildSystem {

    private data class Snapshot(val root: String, val stamp: String, val modules: List<BuildModule>)

    private val cache = AtomicReference<Snapshot?>(null)

    override val id: String = "CARGO"

    override val sourceExtensions: Set<String> = setOf("rs", "toml", "lock")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val manifest = manifestOf(project) ?: return emptyList()
        val root = manifest.parentFile.invariantSeparatorsPath
        val manifests = ManifestSearch.find(manifest.parentFile, "Cargo.toml")
        val environment = System.getenv()
        val requestedProfile = cargoNextestProfile(environment)
        val cargoConfigurationPresent = cargoConfigurationExists(manifest.parentFile, environment)
        val unsupportedEnvironment = unsupportedNextestEnvironment(environment)
        val executableStamp = cargoNextestExecutableStamp(environment)
        val inputStamp = combineFingerprints(
            cargoManifestFingerprint(manifest.parentFile, manifests),
            requestedProfile.orEmpty(),
            cargoConfigurationPresent.toString(),
            unsupportedEnvironment.toString(),
            executableStamp,
            cargoBuildScriptLayout(manifest.parentFile, manifests),
        )
        if (inputStamp != null) {
            cache.get()?.takeIf { it.root == root && it.stamp == inputStamp }?.let {
                return it.modules
            }
        }
        val nextest = discoverCargoNextest(
            manifest.parentFile,
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
        val discovery = failClosedModules(manifest.parentFile, fallbackTask, CargoMetadata.COMPILE, discovered)
        val discoveredManifests = discovery.modules.mapTo(HashSet()) { module ->
            File(module.contentRoots.single(), "Cargo.toml").absoluteFile.normalize().invariantSeparatorsPath
        }
        val fingerprintedManifests = manifests.mapTo(HashSet()) {
            it.absoluteFile.normalize().invariantSeparatorsPath
        }
        if (inputStamp != null && discovery.complete && fingerprintedManifests.containsAll(discoveredManifests)) {
            cache.retainBuildSnapshot(Snapshot(root, inputStamp, discovery.modules), discovery.modules.size)
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

    private fun manifestOf(project: Project): File? =
        project.basePath?.let(::File)?.let(::cargoProjectRoot)?.let(::cargoManifest)

    private fun discoverCargoNextest(
        root: File,
        requestedProfile: String?,
        cargoConfigurationPresent: Boolean,
        unsupportedEnvironment: Boolean,
        executable: java.nio.file.Path?,
        cargo: java.nio.file.Path?,
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

internal fun cargoProjectRoot(base: File): File? =
    nestedBuildRoot(base) { cargoManifest(it) != null }

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
    fileFilter: String? = null,
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
                        (fileFilter.takeUnless { workspace }?.let { listOf("-E", it) } ?: emptyList()) + selection,
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
    val nextest: java.nio.file.Path,
    val cargo: java.nio.file.Path,
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

internal fun cargoNextestFileFilter(root: String, changes: BuildChanges): String? = runCatching {
    require(changes.comparedToBase)
    require(changes.files.isNotEmpty() && changes.files.size <= MAX_CARGO_FILE_FILTERS)
    require(changes.files.toSet() == changes.exactSelectionEligible)
    val rootPath = File(root).toPath().toAbsolutePath().normalize()
    require(Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(rootPath))
    val files = changes.files.map { raw ->
        val requested = Path.of(raw).toAbsolutePath().normalize()
        require(Files.isRegularFile(requested, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(requested))
        val real = requested.toRealPath(LinkOption.NOFOLLOW_LINKS)
        require(real.startsWith(rootPath))
        val relative = rootPath.relativize(real).toString().replace('\\', '/')
        require(relative.endsWith(".rs") && CARGO_FILTER_PATH.matches(relative))
        relative
    }.distinct().sorted()
    require(files.isNotEmpty())
    files.joinToString(" + ") { file -> "file($file)" }
}.getOrNull()

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
        fileFilter = if (workspace) null else cargoNextestFileFilter(root, changes),
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
private val CARGO_FILTER_PATH = Regex("""[A-Za-z0-9._+\-]+(?:/[A-Za-z0-9._+\-]+)*\.rs""")
private const val MAX_CARGO_FILE_FILTERS = 256

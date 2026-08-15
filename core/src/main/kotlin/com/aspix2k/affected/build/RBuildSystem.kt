package com.aspix2k.affected.build

import com.aspix2k.affected.ProjectChanges
import com.aspix2k.affected.toBuildChanges
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class RBuildSystem : ChangeAwareSuspendingBuildSystem, NamedSourceBuildSystem, AllFileChangesBuildSystem {

    override val id: String = "RPROJECT"

    override val sourceExtensions: Set<String> = setOf("r", "R")

    override val sourceFileNames: Set<String> = setOf("DESCRIPTION", "renv.lock")

    override val includeGeneratedFiles: Boolean = true

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = manifestOf(project)?.parentFile ?: return emptyList()
        return listOf(rRootModule(root))
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, rExecutionCommands(File(root), tasks), "Affected R")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, rExecutionCommands(File(root), tasks), "Affected R")

    override suspend fun runAndWaitSuspending(
        project: Project,
        root: String,
        tasks: List<String>,
        changes: BuildChanges,
    ): Boolean = CommandRunner.runBatchAndWait(
        project,
        root,
        rDeferredCommands(File(root), tasks, changes) {
            ProjectChanges.collect(project).toBuildChanges()
        },
        "Affected R",
    )

    private fun manifestOf(project: Project): File? =
        project.basePath?.let(::File)?.let(::rProjectRoot)?.let(::rManifest)
}

internal object RTasks {
    const val TEST = "test"
    const val CHECK = "check"
}

internal fun rProjectRoot(base: File): File? =
    nestedBuildRoot(base) { rManifest(it) != null }

internal fun rManifest(root: File): File? {
    if (FOREIGN_ROOTS.any { File(root, it).isRegularFileNoFollow() }) return null
    val description = File(root, "DESCRIPTION").takeIf(File::isRegularFileNoFollow)
    val lockfile = File(root, "renv.lock").takeIf(File::isRegularFileNoFollow)
    if (description != null) {
        val text = runCatching { description.readText() }.getOrNull()
        if (text != null && PACKAGE_FIELD.containsMatchIn(text)) return description
    }
    return lockfile
}

internal fun rRootModule(root: File): BuildModule {
    val rootPath = root.invariantSeparatorsPath
    return BuildModule(
        id = root.name.ifBlank { "project" },
        root = rootPath,
        contentRoots = listOf(rootPath),
        testTask = RTasks.TEST,
        compileTask = RTasks.CHECK,
        hasTests = rHasTests(root),
        executionId = ".",
    )
}

internal fun rHasTests(root: File): Boolean {
    val tests = File(root, "tests/testthat")
    if (tests.isDirectory && tests.listFiles().orEmpty().any { it.isFile && TESTTHAT_SCRIPT.matches(it.name) }) {
        return true
    }
    return rManifest(root)?.name == "renv.lock"
}

internal fun rCommands(tasks: List<String>): List<CliCommand> = rCommands(File("."), tasks)

internal fun rCommands(
    root: File,
    tasks: List<String>,
    changes: BuildChanges? = null,
): List<CliCommand> {
    if (tasks.isEmpty()) return emptyList()
    val verbs = tasks.map { it.substringAfterLast(':') }.toSet()
    val packageRoot = rManifest(root)?.name == "DESCRIPTION"
    val check = verbs == setOf(RTasks.CHECK) && packageRoot
    if (check) return emptyList()
    val fullTest = if (packageRoot) PACKAGE_TEST_ARGUMENTS else PROJECT_TEST_ARGUMENTS
    val arguments = when {
        verbs == setOf(RTasks.TEST) && packageRoot -> selectedTestthatFiles(root, changes)?.let { selected ->
            EXACT_TEST_ARGUMENTS + selected
        } ?: fullTest
        else -> fullTest
    }
    return listOf(CliCommand(arguments.joinToString(" "), arguments))
}

internal fun rDeferredCommands(
    root: File,
    tasks: List<String>,
    planned: BuildChanges,
    currentChanges: () -> BuildChanges,
): List<CliStep> {
    if (tasks.isEmpty()) return emptyList()
    if (isPackageCheck(root, tasks)) return listOf(rPackageCheckStep())
    val plannedCommands = rCommands(root, tasks, planned)
    if (plannedCommands == rCommands(root, tasks)) return plannedCommands
    return listOf(
        DeferredCliCommand(
            title = "Rscript testthat",
            arguments = {
                val current = currentChangesOrNull(currentChanges)
                val effective = current?.takeIf { sameRChanges(planned, it) }
                rCommands(root, tasks, effective).singleOrNull()?.arguments
            },
        ),
    )
}

internal fun rExecutionCommands(root: File, tasks: List<String>): List<CliStep> =
    if (isPackageCheck(root, tasks)) listOf(rPackageCheckStep()) else rCommands(root, tasks)

private fun isPackageCheck(root: File, tasks: List<String>): Boolean =
    tasks.isNotEmpty() &&
        tasks.map { it.substringAfterLast(':') }.toSet() == setOf(RTasks.CHECK) &&
        rManifest(root)?.name == "DESCRIPTION"

private fun rPackageCheckStep(): CliStep = DeferredCliCommand.command {
    val output = Files.createTempDirectory(R_CHECK_DIRECTORY_PREFIX).toAbsolutePath().normalize()
    runCatching { rPackageCheckCommand(output) }.getOrElse { error ->
        output.toFile().deleteRecursively()
        throw error
    }
}

internal fun rPackageCheckCommand(outputDirectory: Path): CliCommand {
    val output = outputDirectory.toAbsolutePath().normalize()
    val arguments = PACKAGE_CHECK_ARGUMENTS + output.toString()
    return CliCommand(
        title = "R CMD check",
        arguments = arguments,
        ownedTemporaryDirectories = listOf(output),
    )
}

private fun currentChangesOrNull(currentChanges: () -> BuildChanges): BuildChanges? = try {
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

private fun sameRChanges(planned: BuildChanges, current: BuildChanges): Boolean =
    planned.comparedToBase == current.comparedToBase &&
        planned.files.toSet() == current.files.toSet() &&
        planned.exactSelectionEligible == current.exactSelectionEligible

private fun selectedTestthatFiles(root: File, changes: BuildChanges?): List<String>? = runCatching {
    require(changes != null && changes.comparedToBase)
    require(changes.files.isNotEmpty() && changes.files.size <= MAX_SELECTED_TEST_FILES)
    require(changes.files.toSet() == changes.exactSelectionEligible)
    val rootPath = root.toPath().toAbsolutePath().normalize()
    require(Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(rootPath))
    val rootReal = rootPath.toRealPath()
    val selected = changes.files.map { raw ->
        val requested = Path.of(raw).toAbsolutePath().normalize()
        require(requested.startsWith(rootPath))
        require(symlinkFreePath(rootPath, requested))
        require(Files.isRegularFile(requested, LinkOption.NOFOLLOW_LINKS))
        require(requested.toRealPath().startsWith(rootReal))
        rootPath.relativize(requested).toString().replace('\\', '/').also { relative ->
            require(TEST_PATH.matches(relative))
        }
    }.distinct().sorted().also { require(it.isNotEmpty()) }
    val testDirectory = rootPath.resolve(TEST_DIRECTORY)
    require(symlinkFreePath(rootPath, testDirectory))
    require(Files.isDirectory(testDirectory, LinkOption.NOFOLLOW_LINKS))
    val scripts = Files.newDirectoryStream(testDirectory).use { entries ->
        val names = ArrayList<String>()
        var count = 0
        for (entry in entries) {
            require(++count <= MAX_TEST_DIRECTORY_ENTRIES)
            val name = entry.fileName.toString()
            if (!TESTTHAT_SCRIPT.matches(name)) continue
            require(Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(entry))
            names += name
        }
        names
    }
    val byContext = scripts.groupBy(::testthatContext)
    for (relative in selected) {
        val name = relative.substringAfterLast('/')
        val context = testthatContext(name)
        require(SAFE_TEST_CONTEXT.matches(context))
        require(byContext[context] == listOf(name))
    }
    require((EXACT_TEST_ARGUMENTS + selected).sumOf { it.length + 1 } <= MAX_EXACT_ARGUMENT_CHARACTERS)
    selected
}.getOrNull()

private fun symlinkFreePath(root: Path, target: Path): Boolean {
    var current = root
    for (segment in root.relativize(target)) {
        current = current.resolve(segment)
        if (Files.isSymbolicLink(current)) return false
    }
    return true
}

private val PACKAGE_FIELD = Regex("""(?m)^Package\s*:""")
private val TEST_PATH = Regex("""tests/testthat/test-[^/]*\.[Rr]""")
private val TESTTHAT_SCRIPT = Regex("""test.*\.[Rr]""")
private val SAFE_TEST_CONTEXT = Regex("""[A-Za-z0-9][A-Za-z0-9_-]{0,127}""")
private val FOREIGN_ROOTS = listOf("settings.gradle.kts", "settings.gradle", "pom.xml")
private const val MAX_SELECTED_TEST_FILES = 256
private const val MAX_TEST_DIRECTORY_ENTRIES = 4_096
private const val MAX_EXACT_ARGUMENT_CHARACTERS = 16_000
private const val TEST_DIRECTORY = "tests/testthat"
private const val TEST_FILE_EXPRESSION =
    "local({version <- utils::packageVersion(\"testthat\"); " +
        "if (version < \"3.0.0\" || version >= \"4.0.0\") " +
        "testthat::test_dir(\"tests/testthat\") else {paths <- commandArgs(trailingOnly = TRUE); " +
        "Sys.setenv(TESTTHAT_PARALLEL = \"false\"); " +
        "contexts <- sub(\"\\\\.[rR]$\", \"\", " +
        "sub(\"^test[-_.]?\", \"\", basename(paths))); testthat::test_local(\".\", " +
        "filter = paste0(\"^(\", paste(contexts, collapse = \"|\"), \")$\"))}})"
private val PACKAGE_TEST_ARGUMENTS = listOf("Rscript", "-e", "testthat::test_local(\".\")")
private val PROJECT_TEST_ARGUMENTS = listOf("Rscript", "-e", "testthat::test_dir(\"tests/testthat\")")
private val EXACT_TEST_ARGUMENTS = listOf("Rscript", "-e", TEST_FILE_EXPRESSION)
private const val PACKAGE_CHECK_EXPRESSION =
    "status <- local({arguments <- commandArgs(trailingOnly = TRUE); " +
        "if (length(arguments) != 1L) stop(\"Expected one R package check directory\"); " +
        "package <- normalizePath(\".\", winslash = \"/\", mustWork = TRUE); " +
        "output <- normalizePath(arguments[[1L]], winslash = \"/\", mustWork = TRUE); " +
        "if (identical(output, package) || startsWith(output, paste0(package, \"/\"))) " +
        "stop(\"R package check directory must be outside the package\"); " +
        "previous <- setwd(output); " +
        "on.exit({setwd(previous); if (dir.exists(output)) " +
        "unlink(output, recursive = TRUE, force = TRUE)}, add = TRUE); " +
        "result <- tryCatch(tools::Rcmd(c(\"check\", \"--no-manual\", \"--no-build-vignettes\", " +
        "shQuote(package))), error = function(error) {message(conditionMessage(error)); 1L}); " +
        "setwd(previous); cleanup <- unlink(output, recursive = TRUE, force = TRUE); " +
        "if (cleanup != 0L || dir.exists(output)) {message(\"Could not remove R package check directory\"); " +
        "if (result == 0L) result <- 1L}; result}); " +
        "quit(status = status)"
private val PACKAGE_CHECK_ARGUMENTS = listOf("Rscript", "--vanilla", "-e", PACKAGE_CHECK_EXPRESSION)
private const val R_CHECK_DIRECTORY_PREFIX = "affected-r-check-"

private fun testthatContext(name: String): String = name
    .replace(Regex("""\.[rR]$"""), "")
    .replaceFirst(Regex("""^test[-_.]?"""), "")

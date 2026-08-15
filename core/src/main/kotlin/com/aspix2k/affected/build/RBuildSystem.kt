package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class RBuildSystem : ChangeAwareSuspendingBuildSystem, NamedSourceBuildSystem, AllFileChangesBuildSystem {

    override val id: String = "RPROJECT"

    override val sourceExtensions: Set<String> = setOf("r", "R")

    override val sourceFileNames: Set<String> = setOf("DESCRIPTION", "renv.lock")

    override fun isPresent(project: Project): Boolean = manifestOf(project) != null

    override fun modules(project: Project): List<BuildModule> {
        val root = manifestOf(project)?.parentFile ?: return emptyList()
        return listOf(rRootModule(root))
    }

    override fun run(project: Project, root: String, tasks: List<String>) {
        CommandRunner.runBatch(project, root, rCommands(File(root), tasks), "Affected R")
    }

    override suspend fun runAndWaitSuspending(project: Project, root: String, tasks: List<String>): Boolean =
        CommandRunner.runBatchAndWait(project, root, rCommands(File(root), tasks), "Affected R")

    override suspend fun runAndWaitSuspending(
        project: Project,
        root: String,
        tasks: List<String>,
        changes: BuildChanges,
    ): Boolean = CommandRunner.runBatchAndWait(
        project,
        root,
        rCommands(File(root), tasks, changes),
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
    val fullTest = if (packageRoot) PACKAGE_TEST_ARGUMENTS else PROJECT_TEST_ARGUMENTS
    val arguments = when {
        check -> listOf("Rscript", "-e", "read.dcf(\"DESCRIPTION\")")
        verbs == setOf(RTasks.TEST) && packageRoot -> selectedTestthatFiles(root, changes)?.let { selected ->
            listOf("Rscript", "-e", TEST_FILE_EXPRESSION, "--args") + selected
        } ?: fullTest
        else -> fullTest
    }
    return listOf(CliCommand(arguments.joinToString(" "), arguments))
}

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
private const val TEST_DIRECTORY = "tests/testthat"
private const val TEST_FILE_EXPRESSION =
    "local({version <- utils::packageVersion(\"testthat\"); " +
        "if (version < \"3.0.0\" || version >= \"4.0.0\") " +
        "testthat::test_dir(\"tests/testthat\") else {paths <- commandArgs(trailingOnly = TRUE); " +
        "contexts <- sub(\"\\\\.[rR]$\", \"\", " +
        "sub(\"^test[-_.]?\", \"\", basename(paths))); testthat::test_local(\".\", " +
        "filter = paste0(\"^(\", paste(contexts, collapse = \"|\"), \")$\"))}})"
private val PACKAGE_TEST_ARGUMENTS = listOf("Rscript", "-e", "testthat::test_local(\".\")")
private val PROJECT_TEST_ARGUMENTS = listOf("Rscript", "-e", "testthat::test_dir(\"tests/testthat\")")

private fun testthatContext(name: String): String = name
    .replace(Regex("""\.[rR]$"""), "")
    .replaceFirst(Regex("""^test[-_.]?"""), "")

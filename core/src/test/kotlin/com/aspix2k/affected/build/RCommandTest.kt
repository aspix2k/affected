package com.aspix2k.affected.build

import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RCommandTest {

    @Test
    fun `the R adapter collects non-R changes before exact selection`() {
        assertTrue(assertIs<AllFileChangesBuildSystem>(RBuildSystem()).includeGeneratedFiles)
    }

    @Test
    fun `an R root runs one project test command`() {
        assertEquals(
            listOf("Rscript", "-e", "testthat::test_dir(\"tests/testthat\")"),
            rCommands(listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `directly changed testthat files run in one command`() {
        val root = rRoot()
        val alpha = testFile(root, "test-alpha.R")
        val beta = testFile(root, "test-beta.r")

        assertEquals(
            listOf(
                "Rscript",
                "-e",
                "local({version <- utils::packageVersion(\"testthat\"); " +
                    "if (version < \"3.0.0\" || version >= \"4.0.0\") " +
                    "testthat::test_dir(\"tests/testthat\") else {paths <- commandArgs(trailingOnly = TRUE); " +
                    "Sys.setenv(TESTTHAT_PARALLEL = \"false\"); " +
                    "contexts <- sub(\"\\\\.[rR]$\", \"\", " +
                    "sub(\"^test[-_.]?\", \"\", basename(paths))); testthat::test_local(\".\", " +
                    "filter = paste0(\"^(\", paste(contexts, collapse = \"|\"), \")$\"))}})",
                "--args",
                "tests/testthat/test-alpha.R",
                "tests/testthat/test-beta.r",
            ),
            rCommands(root, listOf(".:test"), changes(beta, alpha)).single().arguments,
        )
        assertTrue(rCommands(root, listOf(".:test"), changes(beta, alpha)).single().environment.isEmpty())
    }

    @Test
    fun `deferred R selection keeps exact arguments only while the change proof is current`() {
        val root = rRoot()
        val selected = testFile(root, "test-alpha.R")
        val planned = changes(selected)

        val command = rDeferredCommands(root, listOf(".:test"), planned) { planned }
            .single()
            .resolve()

        assertEquals(rCommands(root, listOf(".:test"), planned).single().arguments, command?.arguments)
    }

    @Test
    fun `a full R plan does not refresh changes on the click path`() {
        val root = rRoot()
        val source = File(root, "R/alpha.R").apply {
            parentFile.mkdirs()
            writeText("alpha <- TRUE\n")
        }
        val planned = changes(source)
        var refreshed = false

        val command = rDeferredCommands(root, listOf(".:test"), planned) {
            refreshed = true
            planned
        }.single().resolve()

        assertFalse(refreshed)
        assertEquals(rCommands(root, listOf(".:test")).single(), command)
    }

    @Test
    fun `deferred R selection propagates coroutine cancellation`() {
        val root = rRoot()
        val selected = testFile(root, "test-alpha.R")
        val planned = changes(selected)
        val cancellation = CancellationException("cancelled")

        assertSame(
            cancellation,
            assertFailsWith<CancellationException> {
                rDeferredCommands(root, listOf(".:test"), planned) { throw cancellation }.single().resolve()
            },
        )
    }

    @Test
    fun `deferred R selection propagates IDE process cancellation`() {
        val root = rRoot()
        val selected = testFile(root, "test-alpha.R")
        val planned = changes(selected)
        val processCancellation = ProcessCanceledException()

        assertSame(
            processCancellation,
            assertFailsWith<ProcessCanceledException> {
                rDeferredCommands(root, listOf(".:test"), planned) { throw processCancellation }.single().resolve()
            },
        )
    }

    @Test
    fun `deferred R selection propagates interruption and restores its flag`() {
        val root = rRoot()
        val selected = testFile(root, "test-alpha.R")
        val planned = changes(selected)
        val interruption = InterruptedException("interrupted")

        try {
            assertSame(
                interruption,
                assertFailsWith<InterruptedException> {
                    rDeferredCommands(root, listOf(".:test"), planned) { throw interruption }.single().resolve()
                },
            )
            assertTrue(Thread.interrupted())
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `an ordinary R revalidation failure widens to the full suite`() {
        val root = rRoot()
        val selected = testFile(root, "test-alpha.R")
        val planned = changes(selected)

        val command = rDeferredCommands(root, listOf(".:test"), planned) {
            error("inspection failed")
        }.single().resolve()

        assertEquals(rCommands(root, listOf(".:test")).single().arguments, command?.arguments)
    }

    @Test
    fun `a helper added after planning widens deferred R selection`() {
        val root = rRoot()
        val selected = testFile(root, "test-alpha.R")
        val planned = changes(selected)
        val helper = File(root, "tests/testthat/helper.R").apply { writeText("helper_value <- TRUE\n") }

        val command = rDeferredCommands(root, listOf(".:test"), planned) { changes(selected, helper) }
            .single()
            .resolve()

        assertEquals(rCommands(root, listOf(".:test")).single().arguments, command?.arguments)
    }

    @Test
    fun `a generated input added after planning widens deferred R selection`() {
        val root = rRoot()
        val selected = testFile(root, "test-alpha.R")
        val planned = changes(selected)
        val generated = File(root, "build/generated/input.csv").apply {
            parentFile.mkdirs()
            writeText("input\n")
        }

        val command = rDeferredCommands(root, listOf(".:test"), planned) { changes(selected, generated) }
            .single()
            .resolve()

        assertEquals(rCommands(root, listOf(".:test")).single().arguments, command?.arguments)
    }

    @Test
    fun `a testthat file replaced by a symlink after planning widens deferred R selection`() {
        val root = rRoot()
        val selected = testFile(root, "test-alpha.R")
        val planned = changes(selected)
        val outside = testFile(createTempDirectory("r-outside").toFile(), "external.R")
        selected.delete()
        if (runCatching { Files.createSymbolicLink(selected.toPath(), outside.toPath()) }.isFailure) return

        val command = rDeferredCommands(root, listOf(".:test"), planned) { planned }
            .single()
            .resolve()

        assertEquals(rCommands(root, listOf(".:test")).single().arguments, command?.arguments)
    }

    @Test
    fun `a mixed R change keeps the full project test command`() {
        val root = rRoot()
        val test = testFile(root, "test-alpha.R")
        val source = File(root, "R/alpha.R").apply {
            parentFile.mkdirs()
            writeText("alpha <- TRUE\n")
        }

        assertFullTestCommand(root, changes(test, source))
    }

    @Test
    fun `testthat support files keep the full project test command`() {
        val root = rRoot()
        val helper = File(root, "tests/testthat/helper.R").apply {
            parentFile.mkdirs()
            writeText("helper_value <- TRUE\n")
        }

        assertFullTestCommand(root, changes(helper))
    }

    @Test
    fun `testthat snapshots keep the full project test command`() {
        val root = rRoot()
        val snapshot = File(root, "tests/testthat/_snaps/readme.md").apply {
            parentFile.mkdirs()
            writeText("snapshot\n")
        }

        assertFullTestCommand(root, changes(snapshot))
    }

    @Test
    fun `a colliding testthat context keeps the full project test command`() {
        val root = rRoot()
        val selected = testFile(root, "test-alpha.R")
        testFile(root, "test_alpha.r")

        assertFullTestCommand(root, changes(selected))
    }

    @Test
    fun `a regex-shaped testthat context keeps the full project test command`() {
        val root = rRoot()
        val selected = testFile(root, "test-alpha+beta.R")

        assertFullTestCommand(root, changes(selected))
    }

    @Test
    fun `a missing changed testthat file keeps the full project test command`() {
        val root = rRoot()
        val deleted = File(root, "tests/testthat/test-deleted.R")

        assertFullTestCommand(root, changes(deleted))
    }

    @Test
    fun `an ineligible testthat file keeps the full project test command`() {
        val root = rRoot()
        val test = testFile(root, "test-alpha.R")

        assertFullTestCommand(
            root,
            BuildChanges(listOf(test.path), exactSelectionEligible = emptySet(), comparedToBase = true),
        )
    }

    @Test
    fun `a non-base testthat change keeps the full project test command`() {
        val root = rRoot()
        val test = testFile(root, "test-alpha.R")

        assertFullTestCommand(
            root,
            BuildChanges(listOf(test.path), setOf(test.path), comparedToBase = false),
        )
    }

    @Test
    fun `an empty R change set keeps the full project test command`() {
        val root = rRoot()

        assertFullTestCommand(root, BuildChanges(emptyList(), emptySet(), comparedToBase = true))
    }

    @Test
    fun `an R Markdown change keeps the full project test command`() {
        val root = rRoot()
        val notebook = File(root, "analysis.qmd").apply { writeText("---\ntitle: analysis\n---\n") }

        assertFullTestCommand(root, changes(notebook))
    }

    @Test
    fun `R package metadata resources and generated inputs keep the full project test command`() {
        listOf(
            "NAMESPACE",
            ".Rprofile",
            ".Renviron",
            "tests/testthat.R",
            "tests/testthat/setup.R",
            "tests/testthat/teardown.R",
            "tests/testthat/fixtures/input.csv",
            "inst/extdata/input.csv",
            "data/input.rds",
            "vignettes/guide.Rmd",
        ).forEach { relative ->
            val root = rRoot()
            val changed = File(root, relative).apply {
                parentFile.mkdirs()
                writeText("changed\n")
            }

            assertFullTestCommand(root, changes(changed))
        }
    }

    @Test
    fun `unsafe testthat contexts keep the full project test command`() {
        listOf(
            "test-alpha beta.R",
            "test-alpha+beta.R",
            "test-alpha(beta).R",
            "test-альфа.R",
            "test--option.R",
        ).forEach { name ->
            val root = rRoot()
            val selected = testFile(root, name)

            assertFullTestCommand(root, changes(selected))
        }
    }

    @Test
    fun `an underscore-named changed testthat file keeps the full project test command`() {
        val root = rRoot()
        val selected = testFile(root, "test_alpha.R")

        assertFullTestCommand(root, changes(selected))
    }

    @Test
    fun `a testthat file outside the R root keeps the full project test command`() {
        val root = rRoot()
        val outside = testFile(createTempDirectory("r-outside").toFile(), "test-outside.R")

        assertFullTestCommand(root, changes(outside))
    }

    @Test
    fun `a symlinked testthat file keeps the full project test command`() {
        val root = rRoot()
        val target = testFile(createTempDirectory("r-outside").toFile(), "target.R")
        val link = File(root, "tests/testthat/test-linked.R")
        link.parentFile.mkdirs()
        if (runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isFailure) return

        assertFullTestCommand(root, changes(link))
    }

    @Test
    fun `a testthat file below a symlinked directory keeps the full project test command`() {
        val root = rRoot()
        val external = createTempDirectory("r-outside").toFile()
        val target = testFile(external, "test-linked.R").parentFile
        val link = File(root, "tests/testthat")
        link.parentFile.mkdirs()
        if (runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isFailure) return

        assertFullTestCommand(root, changes(File(link, "test-linked.R")))
    }

    @Test
    fun `an oversized set of changed testthat files keeps the full project test command`() {
        val root = rRoot()
        val files = (0..256).map { testFile(root, "test-$it.R") }.toTypedArray()

        assertFullTestCommand(root, changes(*files))
    }

    @Test
    fun `testthat paths beyond the portable process argument budget keep the full project command`() {
        val root = rRoot()
        val files = (0 until 128).map { index ->
            testFile(root, "test-$index-${"a".repeat(110)}.R")
        }.toTypedArray()

        assertFullTestCommand(root, changes(*files))
    }

    @Test
    fun `a production-only R change runs an isolated package check`() {
        val root = rRoot()
        val output = createTempDirectory("affected-r-check-command-")
        val command = rPackageCheckCommand(output)

        assertEquals(listOf("Rscript", "--vanilla", "-e"), command.arguments.take(3))
        assertContains(command.arguments.single { it.contains("tools::Rcmd") }, "--no-manual")
        assertContains(command.arguments.single { it.contains("tools::Rcmd") }, "--no-build-vignettes")
        assertContains(command.arguments.single { it.contains("tools::Rcmd") }, "shQuote(package)")
        assertContains(
            command.arguments.single { it.contains("tools::Rcmd") },
            "unlink(output, recursive = TRUE, force = TRUE)",
        )
        assertContains(command.arguments.single { it.contains("tools::Rcmd") }, "cleanup != 0L")
        assertEquals(listOf(output), command.ownedTemporaryDirectories)
        assertEquals(output.toString(), command.arguments.last())
        assertFalse(command.arguments.any { it.contains("read.dcf") })
    }

    @Test
    fun `an R package check allocates owned output only when execution resolves`() {
        val root = rRoot()
        val step = rExecutionCommands(root, listOf(".:check")).single()

        assertTrue(step is DeferredCliCommand)
        val command = step.resolve()
        assertTrue(command != null)
        try {
            assertTrue(Files.isDirectory(command.ownedTemporaryDirectories.single()))
            assertEquals(command.ownedTemporaryDirectories.single().toString(), command.arguments.last())
        } finally {
            command.ownedTemporaryDirectories.single().toFile().deleteRecursively()
        }
    }

    @Test
    fun `unknown R tasks keep the project test command`() {
        assertEquals(
            listOf("Rscript", "-e", "testthat::test_dir(\"tests/testthat\")"),
            rCommands(listOf(".:mystery")).single().arguments,
        )
    }

    @Test
    fun `unknown R tasks do not enable changed file selection`() {
        val root = rRoot()
        val test = testFile(root, "test-alpha.R")

        assertEquals(
            listOf("Rscript", "-e", "testthat::test_local(\".\")"),
            rCommands(root, listOf(".:mystery"), changes(test)).single().arguments,
        )
    }

    @Test
    fun `an R package with tests is runnable`() {
        val root = rRoot()
        File(root, "tests/testthat/test-alpha.R").apply {
            parentFile.mkdirs()
            writeText("test_that(\"alpha\", { expect_true(TRUE) })\n")
        }

        val module = rRootModule(root)
        assertTrue(module.hasTests)
        assertEquals("test", module.testTask)
        assertEquals("check", module.compileTask)
        assertEquals(".", module.executionId)
    }

    @Test
    fun `an underscore-named testthat file makes the R package runnable`() {
        val root = rRoot()
        testFile(root, "test_alpha.R")

        assertTrue(rRootModule(root).hasTests)
    }

    @Test
    fun `a nested testthat file does not make the R package runnable`() {
        val root = rRoot()
        File(root, "tests/testthat/nested/test-alpha.R").apply {
            parentFile.mkdirs()
            writeText("test_that(\"alpha\", { expect_true(TRUE) })\n")
        }

        assertFalse(rRootModule(root).hasTests)
    }

    @Test
    fun `an R package without tests is checked`() {
        val root = rRoot()

        assertFalse(rRootModule(root).hasTests)
    }

    @Test
    fun `a lockfile-only R root keeps the test command`() {
        val root = createTempDirectory("r-renv").toFile()
        File(root, "renv.lock").writeText("{}\n")

        val module = rRootModule(root)
        assertTrue(module.hasTests)
        assertEquals(
            listOf("Rscript", "-e", "testthat::test_dir(\"tests/testthat\")"),
            rCommands(root, listOf(".:check")).single().arguments,
        )
    }

    @Test
    fun `an R package check is distinct from testthat routing`() {
        val root = rRoot()
        testFile(root, "test-alpha.R")
        val output = createTempDirectory("affected-r-check-command-")

        assertEquals(
            listOf("Rscript", "-e", "testthat::test_local(\".\")"),
            rCommands(root, listOf(".:test")).single().arguments,
        )
        assertTrue(rPackageCheckCommand(output).arguments.contains("--vanilla"))
    }

    @Test
    fun `a DESCRIPTION without a Package field stays off the R adapter`() {
        val root = rRoot("Title: not a package\n")

        assertNull(rManifest(root))
    }

    @Test
    fun `Gradle settings keep the root off the R adapter`() {
        val root = rRoot()
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"mixed\"")

        assertNull(rManifest(root))
    }

    @Test
    fun `a Maven pom keeps the root off the R adapter`() {
        val root = rRoot()
        File(root, "pom.xml").writeText("<project/>")

        assertNull(rManifest(root))
    }

    @Test
    fun `a single first-level nested R package is the root`() {
        val base = createTempDirectory("r-nested").toFile()
        val nested = File(base, "pkg")
        rRoot().copyRecursively(nested)

        assertEquals(nested.canonicalFile, rProjectRoot(base)?.canonicalFile)
    }

    @Test
    fun `several first-level nested R packages stay off`() {
        val base = createTempDirectory("r-many").toFile()
        rRoot().copyRecursively(File(base, "pkg"))
        rRoot().copyRecursively(File(base, "tools"))

        assertNull(rProjectRoot(base))
    }

    @Test
    fun `a deeper nested R package stays off`() {
        val base = createTempDirectory("r-deep").toFile()
        rRoot().copyRecursively(File(base, "src/pkg"))

        assertNull(rProjectRoot(base))
    }

    private fun rRoot(
        description: String = """
            Package: probe
            Title: Probe
            Version: 0.0.1
            License: MIT
        """.trimIndent(),
    ): File {
        val root = createTempDirectory("r-root").toFile()
        File(root, "DESCRIPTION").writeText(description)
        return root
    }

    private fun testFile(root: File, name: String): File = File(root, "tests/testthat/$name").apply {
        parentFile.mkdirs()
        writeText("test_that(\"$name\", { expect_true(TRUE) })\n")
    }

    private fun changes(vararg files: File): BuildChanges {
        val paths = files.map(File::getPath)
        return BuildChanges(paths, paths.toSet(), comparedToBase = true)
    }

    private fun assertFullTestCommand(root: File, changes: BuildChanges) {
        assertEquals(
            listOf("Rscript", "-e", "testthat::test_local(\".\")"),
            rCommands(root, listOf(".:test"), changes).single().arguments,
        )
    }
}

package com.aspix2k.affected

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.io.File

class ChecksForFileTest : BasePlatformTestCase() {

    fun testAFileAloneIsAChangeOnlyWhenABuildSystemOrADeclarationCountsIt() {
        val base = File(requireNotNull(project.basePath)).apply { mkdirs() }
        File(base, "shared.txt").writeText("shared")
        File(base, "notes.txt").writeText("notes")
        DeclaredDependencies.write(base, listOf(DeclaredDependency("shared.txt", "NODE", "web", ".")))
        try {
            val declared = runBlocking { ProjectChanges.alone(project, "shared.txt") }
            val owner = DeclaredOwner("NODE", File(base, "web").invariantSeparatorsPath, ".")

            assertEquals(listOf(File(base, "shared.txt")), declared.files)
            assertEquals(mapOf(File(base, "shared.txt") to listOf(owner)), declared.declaredOwners)
            assertEquals(emptySet<File>(), declared.exactSelectionEligible)
            assertEquals(1, runBlocking { Verification.covering(project, "shared.txt") }.changedFiles)
            assertEquals(0, runBlocking { Verification.covering(project, "notes.txt") }.changedFiles)
        } finally {
            File(base, DeclaredDependencies.LOCATION).parentFile.deleteRecursively()
            File(base, "shared.txt").delete()
            File(base, "notes.txt").delete()
        }
    }
}

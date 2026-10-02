package com.aspix2k.affected

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class ProjectChangesTest : BasePlatformTestCase() {

    fun testSourceRootsOfTheIdeModelAreRelativeToTheProjectDirectory() {
        val base = File(requireNotNull(project.basePath)).apply { mkdirs() }
        val resources = File(base, "lib/src/main/resources").apply { mkdirs() }
        val root = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(resources))
        PsiTestUtil.addSourceRoot(module, root)
        try {
            assertEquals(setOf("lib/src/main/resources"), ProjectChanges.sourceRoots(project, base))
        } finally {
            PsiTestUtil.removeSourceRoot(module, root)
            PsiTestUtil.removeContentEntry(module, root)
        }
    }
}

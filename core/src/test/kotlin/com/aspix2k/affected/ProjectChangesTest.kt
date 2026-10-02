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

    fun testExcludedRootsOfTheIdeModelAreRelativeToTheProjectDirectory() {
        val base = File(requireNotNull(project.basePath)).apply { mkdirs() }
        val content = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(base))
        val target = File(base, "target").apply { mkdirs() }
        val excluded = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target))
        PsiTestUtil.addContentRoot(module, content)
        PsiTestUtil.addExcludedRoot(module, excluded)
        try {
            assertEquals(setOf("target"), ProjectChanges.excludedRoots(project, base))
        } finally {
            PsiTestUtil.removeExcludedRoot(module, excluded)
            PsiTestUtil.removeContentEntry(module, content)
        }
    }
}

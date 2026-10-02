package com.aspix2k.affected

import com.intellij.util.xmlb.XmlSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectBaseBranchTest {

    private fun configured(project: String, legacy: String) = ProjectBaseBranch.configuredBranch(project, legacy)

    @Test
    fun `an unset project value and the old default mean automatic`() {
        assertNull(configured("", "develop"))
        assertNull(configured("", ""))
    }

    @Test
    fun `a stored legacy branch other than the old default carries over`() {
        assertEquals("release", configured("", "release"))
    }

    @Test
    fun `the project value wins over the legacy value`() {
        assertEquals("main", configured("main", "release"))
        assertEquals("develop", configured("develop", "release"))
    }

    @Test
    fun `choosing automatic overrides a legacy branch`() {
        assertNull(configured("auto", "release"))
    }

    @Test
    fun `an explicit legacy branch survives the application settings round trip`() {
        val stored = XmlSerializer.serialize(AffectedSettings.State(baseBranch = "release"))
        val loaded = XmlSerializer.deserialize(stored, AffectedSettings.State::class.java)

        assertEquals("release", configured("", loaded.baseBranch))
    }

    @Test
    fun `the old default is not persisted so an untouched file reads as automatic`() {
        val stored = XmlSerializer.serialize(AffectedSettings.State())
        val loaded = XmlSerializer.deserialize(stored, AffectedSettings.State::class.java)

        assertNull(configured("", loaded.baseBranch))
    }

    @Test
    fun `the project choice survives its own round trip`() {
        listOf("auto", "release/1.0").forEach { choice ->
            val stored = XmlSerializer.serialize(ProjectBaseBranch.State(baseBranch = choice))

            assertEquals(choice, XmlSerializer.deserialize(stored, ProjectBaseBranch.State::class.java).baseBranch)
        }
    }
}

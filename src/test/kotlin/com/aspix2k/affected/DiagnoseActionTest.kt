package com.aspix2k.affected

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnoseActionTest {

    private val directory = File("src/main/resources/messages")

    private fun english() = Properties().apply {
        File(directory, "AffectedBundle.properties").reader(Charsets.UTF_8).use { load(it) }
    }

    @Test
    fun `every finding has localized text equal to the core English text`() {
        val bundle = english()

        for ((id, text) in AffectedDoctor.texts) {
            assertEquals(text.message, bundle.getProperty("doctor.$id.message"), id)
            assertEquals(text.remedy, bundle.getProperty("doctor.$id.remedy"), id)
        }
    }

    @Test
    fun `the report lists severity, message and remedy for each finding`() {
        val report = doctorReport(
            listOf(
                DoctorFinding("analysis-ready", DoctorSeverity.OK),
                DoctorFinding("changes-uncovered", DoctorSeverity.WARNING, listOf("2", "a.py, b.py")),
            ),
        )

        assertEquals(
            "OK: The analysis is ready.\n→ Nothing to do.\n\n" +
                "Warning: 2 changed code files belong to no detected build system and are not checked, " +
                "for example a.py, b.py.\n→ Add the build file for that language, or check those files by hand.",
            report,
        )
    }

    @Test
    fun `the diagnose action is declared with a text and a description`() {
        val bundle = english()

        assertTrue(bundle.getProperty("action.com.aspix2k.affected.Diagnose.text").isNotBlank())
        assertTrue(bundle.getProperty("action.com.aspix2k.affected.Diagnose.description").isNotBlank())
    }
}

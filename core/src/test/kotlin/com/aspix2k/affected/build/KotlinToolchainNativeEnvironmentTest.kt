package com.aspix2k.affected.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinToolchainNativeEnvironmentTest {

    @Test
    fun `JAVA_HOME bin is first on PATH and TEST_TMPDIR is dropped`() {
        val javaHome = File("/opt/java/home").path
        val env = kotlinToolchainNativeEnvironment(
            mutableMapOf(
                "JAVA_HOME" to javaHome,
                "PATH" to "/usr/bin",
                "TEST_TMPDIR" to "/tmp/bazel-out",
            ),
        )

        assertFalse("TEST_TMPDIR" in env)
        assertEquals(javaHome, env["KOTLIN_CLI_JAVA_HOME"])
        assertEquals(
            File(javaHome, "bin").path + File.pathSeparator + "/usr/bin",
            env["PATH"],
        )
    }

    @Test
    fun `missing JAVA_HOME only drops TEST_TMPDIR`() {
        val env = kotlinToolchainNativeEnvironment(
            mutableMapOf("PATH" to "/usr/bin", "TEST_TMPDIR" to "/tmp/bazel-out"),
        )

        assertFalse("TEST_TMPDIR" in env)
        assertFalse("KOTLIN_CLI_JAVA_HOME" in env)
        assertEquals("/usr/bin", env["PATH"])
    }

    @Test
    fun `JAVA_HOME bin is not duplicated on PATH`() {
        val javaHome = File("/opt/java/home").path
        val javaBin = File(javaHome, "bin").path
        val env = kotlinToolchainNativeEnvironment(
            mutableMapOf(
                "JAVA_HOME" to javaHome,
                "PATH" to "$javaBin${File.pathSeparator}/usr/bin",
            ),
        )

        assertEquals("$javaBin${File.pathSeparator}/usr/bin", env["PATH"])
        assertTrue(env["PATH"]!!.split(File.pathSeparator).count { it == javaBin } == 1)
    }
}

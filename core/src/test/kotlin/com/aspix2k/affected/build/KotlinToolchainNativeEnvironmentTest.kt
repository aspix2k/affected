package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinToolchainNativeEnvironmentTest {

    @Test
    fun `a verified JAVA_HOME is first on PATH and TEST_TMPDIR is dropped`() {
        val javaHome = fakeJdk("env-home")
        val env = kotlinToolchainNativeEnvironment(
            mutableMapOf(
                "JAVA_HOME" to javaHome.path,
                "PATH" to "/usr/bin",
                "TEST_TMPDIR" to "/tmp/bazel-out",
            ),
            runtimeJavaHome = null,
        )

        assertFalse("TEST_TMPDIR" in env)
        assertEquals(javaHome.canonicalPath, env["JAVA_HOME"])
        assertEquals(javaHome.canonicalPath, env["KOTLIN_CLI_JAVA_HOME"])
        assertEquals(
            File(javaHome, "bin").canonicalPath + File.pathSeparator + "/usr/bin",
            env["PATH"],
        )
    }

    @Test
    fun `a stale JAVA_HOME falls back to the runtime home`() {
        val stale = createTempDirectory("stale-jdk").toFile()
        val runtime = fakeJdk("runtime-home")
        val env = kotlinToolchainNativeEnvironment(
            mutableMapOf("JAVA_HOME" to stale.path, "PATH" to "/usr/bin"),
            runtimeJavaHome = runtime.path,
        )

        assertEquals(runtime.canonicalPath, env["JAVA_HOME"])
        assertEquals(runtime.canonicalPath, env["KOTLIN_CLI_JAVA_HOME"])
    }

    @Test
    fun `missing java binaries only drop TEST_TMPDIR`() {
        val env = kotlinToolchainNativeEnvironment(
            mutableMapOf("PATH" to "/usr/bin", "TEST_TMPDIR" to "/tmp/bazel-out"),
            runtimeJavaHome = null,
        )

        assertFalse("TEST_TMPDIR" in env)
        assertFalse("KOTLIN_CLI_JAVA_HOME" in env)
        assertEquals("/usr/bin", env["PATH"])
        assertNull(resolveJavaHome(null, null))
    }

    @Test
    fun `JAVA_HOME bin is not duplicated on PATH`() {
        val javaHome = fakeJdk("dup-home")
        val javaBin = File(javaHome, "bin").canonicalPath
        val env = kotlinToolchainNativeEnvironment(
            mutableMapOf(
                "JAVA_HOME" to javaHome.path,
                "PATH" to "$javaBin${File.pathSeparator}/usr/bin",
            ),
            runtimeJavaHome = null,
        )

        assertEquals("$javaBin${File.pathSeparator}/usr/bin", env["PATH"])
        assertTrue(env["PATH"]!!.split(File.pathSeparator).count { it == javaBin } == 1)
    }

    private fun fakeJdk(name: String): File {
        val home = createTempDirectory(name).toFile()
        val java = File(home, "bin/java")
        java.parentFile.mkdirs()
        java.writeText("#!/bin/sh\n")
        java.setExecutable(true)
        return home
    }
}

package com.aspix2k.affected.build

import java.io.File

internal fun kotlinToolchainNativeEnvironment(base: MutableMap<String, String>): MutableMap<String, String> {
    base.remove("TEST_TMPDIR")
    val javaHome = base["JAVA_HOME"]?.takeIf(String::isNotBlank) ?: return base
    val javaBin = File(javaHome, "bin").path
    val pathKey = if ("PATH" in base || "Path" !in base) "PATH" else "Path"
    val path = base[pathKey].orEmpty()
    if (!path.split(File.pathSeparator).contains(javaBin)) {
        base[pathKey] = if (path.isEmpty()) javaBin else "$javaBin${File.pathSeparator}$path"
    }
    base["KOTLIN_CLI_JAVA_HOME"] = javaHome
    return base
}

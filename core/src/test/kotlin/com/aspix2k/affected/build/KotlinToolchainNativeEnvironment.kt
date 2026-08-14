package com.aspix2k.affected.build

import java.io.File

internal fun kotlinToolchainNativeEnvironment(
    base: MutableMap<String, String>,
    runtimeJavaHome: String? = System.getProperty("java.home"),
): MutableMap<String, String> {
    base.remove("TEST_TMPDIR")
    val home = resolveJavaHome(base["JAVA_HOME"], runtimeJavaHome) ?: return base
    val javaBin = File(home, "bin").path
    val pathKey = if ("PATH" in base || "Path" !in base) "PATH" else "Path"
    val path = base[pathKey].orEmpty()
    if (!path.split(File.pathSeparator).contains(javaBin)) {
        base[pathKey] = if (path.isEmpty()) javaBin else "$javaBin${File.pathSeparator}$path"
    }
    base["JAVA_HOME"] = home
    base["KOTLIN_CLI_JAVA_HOME"] = home
    return base
}

internal fun resolveJavaHome(vararg candidates: String?): String? =
    candidates.asSequence()
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .map(::File)
        .flatMap { home ->
            val parent = home.parent
            if (parent == null) sequenceOf(home) else sequenceOf(home, File(parent))
        }
        .map { it.canonicalFile }
        .firstOrNull(::javaExecutable)
        ?.path

private fun javaExecutable(home: File): Boolean =
    File(home, "bin/java").canExecute() || File(home, "bin/java.exe").canExecute()

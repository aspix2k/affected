package com.aspix2k.affected.build.process

import com.sun.jna.Native
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.WinNT
import java.io.File
import java.io.IOException
import java.nio.file.Path

internal class SupervisorRuntime(
    val classPath: List<Path>,
    val jnaNativePath: List<Path>,
)

internal class ProcessHost(
    val supervisorRuntime: () -> SupervisorRuntime,
    val targetProcess: (List<String>, File, Map<String, String>) -> ProcessBuilder = ::jdkTargetProcess,
) {
    companion object {
        fun standalone(jnaNativePath: List<Path>): ProcessHost =
            ProcessHost({ SupervisorRuntime(codeSourceClassPath(), jnaNativePath) })
    }
}

internal val SUPERVISOR_CLASSES: List<Class<*>> = listOf(
    ProcessSupervisorMain::class.java,
    Native::class.java,
    WinNT::class.java,
    BaseTSD::class.java,
)

private fun jdkTargetProcess(
    arguments: List<String>,
    workingDirectory: File,
    environment: Map<String, String>,
): ProcessBuilder = ProcessBuilder(arguments).directory(workingDirectory).apply {
    environment().putAll(environment)
}

private fun codeSourceClassPath(): List<Path> = SUPERVISOR_CLASSES.map { type ->
    type.protectionDomain?.codeSource?.location?.toURI()?.let(Path::of)?.toAbsolutePath()?.normalize()
        ?: throw IOException("The process supervisor classpath is missing ${type.name}")
}.distinct()

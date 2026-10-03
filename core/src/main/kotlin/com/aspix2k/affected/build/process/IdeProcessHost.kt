package com.aspix2k.affected.build.process

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.PathManager
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal val ideProcessHost = ProcessHost(::ideSupervisorRuntime, ::ideTargetProcess)

internal val ideCommandCapture = CommandCapture(ideProcessHost)

private fun ideTargetProcess(
    arguments: List<String>,
    workingDirectory: File,
    environment: Map<String, String>,
): ProcessBuilder = GeneralCommandLine(arguments)
    .withWorkDirectory(workingDirectory)
    .withCharset(Charsets.UTF_8)
    .withEnvironment(environment)
    .toProcessBuilder()

private fun ideSupervisorRuntime(): SupervisorRuntime = SupervisorRuntime(ideClassPath(), ideJnaNativePath())

private fun ideClassPath(): List<Path> = SUPERVISOR_CLASSES.map { type ->
    PathManager.getJarForClass(type)?.toAbsolutePath()?.normalize()
        ?: throw IOException("The process supervisor classpath is missing ${type.name}")
}.distinct()

private fun ideJnaNativePath(): List<Path> {
    val configured = System.getProperty(JNA_BOOT_LIBRARY_PATH_PROPERTY)?.takeIf(String::isNotBlank)
    val paths = configured?.split(File.pathSeparator)?.map(Path::of) ?: discoverIdeJnaNativePath()
    if (configured == null) {
        System.setProperty(JNA_BOOT_LIBRARY_PATH_PROPERTY, paths.joinToString(File.pathSeparator))
    }
    return paths
}

private fun discoverIdeJnaNativePath(): List<Path> {
    val root = Path.of(PathManager.getHomePath(), "lib", "jna").toAbsolutePath().normalize()
    val candidates = try {
        Files.list(root).use { entries ->
            entries.filter { directory ->
                Files.isDirectory(directory) && Files.isReadable(directory) &&
                    JNA_DISPATCH_LIBRARY_NAMES.any { name ->
                        val library = directory.resolve(name)
                        Files.isRegularFile(library) && Files.isReadable(library)
                    }
            }.limit(2).toList()
        }
    } catch (_: IOException) {
        emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }
    if (candidates.size != 1) throw IOException("The IDE JNA native library path is unavailable")
    return candidates
}

private const val JNA_BOOT_LIBRARY_PATH_PROPERTY = "jna.boot.library.path"
private val JNA_DISPATCH_LIBRARY_NAMES = setOf(
    "jnidispatch.dll",
    "libjnidispatch.jnilib",
    "libjnidispatch.so",
)

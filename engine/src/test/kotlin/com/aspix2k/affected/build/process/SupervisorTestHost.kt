package com.aspix2k.affected.build.process

import com.sun.jna.Platform
import java.nio.file.Files
import java.nio.file.Path

internal object SupervisorTestHost {

    val nativePath: Path by lazy(::extractJnaNatives)

    val host: ProcessHost by lazy { ProcessHost.standalone(listOf(nativePath)) }

    private fun extractJnaNatives(): Path {
        val directory = Files.createTempDirectory("jna-natives-")
        directory.toFile().deleteOnExit()
        val names = listOf(System.mapLibraryName("jnidispatch"), "libjnidispatch.jnilib")
        val resource = names.firstNotNullOfOrNull { name ->
            val path = "/com/sun/jna/${Platform.RESOURCE_PREFIX}/$name"
            Platform::class.java.getResourceAsStream(path)?.let { name to it }
        }
        val (name, stream) = checkNotNull(resource) { "The JNA native library is missing from the test classpath" }
        stream.use { Files.copy(it, directory.resolve(name)) }
        directory.resolve(name).toFile().deleteOnExit()
        return directory
    }
}

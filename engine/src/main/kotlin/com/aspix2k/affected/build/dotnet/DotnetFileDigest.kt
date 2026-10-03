package com.aspix2k.affected.build.dotnet

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

internal fun Path.isSecureRegularFile(): Boolean =
    Files.isRegularFile(this, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(this) && Files.isReadable(this)

internal fun dotnetFileSha256(path: Path): String {
    require(path.isSecureRegularFile())
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException(".NET file digest interrupted")
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

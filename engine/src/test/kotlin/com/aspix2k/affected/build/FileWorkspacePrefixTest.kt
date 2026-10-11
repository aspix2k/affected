package com.aspix2k.affected.build

import com.aspix2k.affected.build.process.CliCommand
import com.aspix2k.affected.build.process.DeferredCliCommand
import com.aspix2k.affected.build.process.testJavaClassPathArgument
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class FileWorkspacePrefixTest {

    @Test
    fun `a prefix wraps deferred commands after earlier commands have completed`() = runBlocking {
        val directory = createTempDirectory("workspace-prefix")
        val ready = directory.resolve("ready")
        val output = StringBuffer()
        val prefix = listOf(
            ProcessHandle.current().info().command().orElseThrow(),
            testJavaClassPathArgument(directory),
            WorkspacePrefixProbe::class.java.name,
        )
        val workspace = FileWorkspace(
            directory.toFile(),
            directory,
            false,
            { BuildChanges(emptyList(), emptySet(), comparedToBase = false) },
            { text, _ -> output.append(text) },
            commandPrefix = prefix,
        )
        val steps = listOf(
            CliCommand("direct", listOf("direct", ready.toString())),
            DeferredCliCommand.command {
                check(Files.exists(ready))
                CliCommand("deferred", listOf("deferred"), environment = mapOf("AFFECTED_PREFIX_TEST" to "present"))
            },
        )

        assertTrue(withTimeout(15_000) { workspace.runBatchAndWait(directory.toString(), steps, "prefix") })
        assertTrue("wrapped deferred present" in output.toString(), output.toString())
    }
}

private object WorkspacePrefixProbe {
    @JvmStatic
    fun main(arguments: Array<String>) {
        if (arguments[0] == "direct") Files.writeString(Path.of(arguments[1]), "ready")
        println("wrapped ${arguments[0]} ${System.getenv("AFFECTED_PREFIX_TEST")}")
    }
}

package com.aspix2k.affected.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CliCommandTest {

    @Test
    fun `CMake tests never infer a CTest regex from target names`() {
        val root = cmakeRoot("cmake-build-debug")
        val commands = cmakeCommands(root.path, listOf("core:test", "core-tests:test"))

        assertEquals(2, commands.size)
        assertEquals(
            listOf("cmake", "--build", "cmake-build-debug"),
            commands[0].arguments,
        )
        assertEquals(
            listOf("ctest", "--test-dir", "cmake-build-debug", "--output-on-failure"),
            commands[1].arguments,
        )
        assertFalse(commands.flatMap { it.arguments }.contains("-R"))
    }

    @Test
    fun `CMake builds compatible targets together`() {
        val root = cmakeRoot("out/build/debug")
        val commands = cmakeCommands(root.path, listOf("core:build", "app:build"))

        assertEquals(
            listOf("cmake", "--build", "out/build/debug", "--target", "core", "app"),
            commands.single().arguments,
        )
    }

    @Test
    fun `CMake refuses a missing or ambiguous build tree`() {
        val missing = createTempDirectory("cmake-missing").toFile()
        val ambiguous = cmakeRoot("build/first", "build/second")

        assertEquals(emptyList(), cmakeCommands(missing.path, listOf("core:test")))
        assertEquals(emptyList(), cmakeCommands(ambiguous.path, listOf("core:test")))
    }

    @Test
    fun `CMake build-tree discovery is bounded`() {
        val root = createTempDirectory("cmake-bounded").toFile()
        repeat(513) { File(root, "directory-$it").mkdirs() }
        File(root, "directory-0/CMakeCache.txt").writeText("CMAKE_GENERATOR=Ninja\n")

        assertEquals(emptyList(), cmakeCommands(root.path, listOf("core:test")))
    }

    @Test
    fun `Python packages share one native command per task kind`() {
        val modules = modules("/repo", "pkg-a", "packages/a", "pkg-b", "packages/b")

        val commands = pythonCommands("/repo", listOf("pkg-a:test", "pkg-b:test"), modules)

        assertEquals(
            listOf("python", "-m", "pytest", "packages/a", "packages/b"),
            commands.single().arguments,
        )
    }

    @Test
    fun `Composer packages share one native command per task kind`() {
        val modules = modules("/repo", "pkg-a", "packages/a", "pkg-b", "packages/b")

        val commands = composerCommands("/repo", listOf("pkg-a:test", "pkg-b:test"), modules)

        assertEquals(
            listOf("php", "vendor/bin/phpunit", "packages/a", "packages/b"),
            commands.single().arguments,
        )
    }

    @Test
    fun `Bundler gems share one RSpec command`() {
        val modules = modules("/repo", "gem-a", "gems/a", "gem-b", "gems/b")

        val commands = rubyCommands("/repo", listOf("gem-a:test", "gem-b:test"), modules)

        assertEquals(
            listOf("bundle", "exec", "rspec", "gems/a", "gems/b"),
            commands.single().arguments,
        )
    }

    @Test
    fun `a missing planned module invalidates the whole command batch`() {
        val modules = modules("/repo", "pkg-a", "packages/a")

        assertEquals(emptyList(), pythonCommands("/repo", listOf("pkg-a:test", "missing:test"), modules))
        assertEquals(emptyList(), composerCommands("/repo", listOf("pkg-a:test", "missing:test"), modules))
        assertEquals(emptyList(), rubyCommands("/repo", listOf("pkg-a:test", "missing:test"), modules))
    }

    @Test
    fun `dotnet receives exact project files without shell quoting`() {
        val root = createTempDirectory("dotnet-commands").toFile()
        val commands = dotnetCommands(
            root.path,
            listOf(
                "src/Library With Spaces/Library.csproj:test",
                "tests/Library.Tests/Library.Tests.fsproj:build",
            ),
        )

        assertEquals(
            listOf("dotnet", "test", "src/Library With Spaces/Library.csproj"),
            commands[0].arguments,
        )
        assertEquals(
            listOf("dotnet", "build", "tests/Library.Tests/Library.Tests.fsproj"),
            commands[1].arguments,
        )
    }

    @Test
    fun `root fallbacks widen Cargo and Go to the whole project`() {
        assertEquals(listOf("cargo", "test", "--workspace"), cargoCommands(listOf(".:test")).single().arguments)
        assertEquals(listOf("go", "test", "./..."), goCommands(listOf(".:test")).single().arguments)
        assertEquals(
            listOf("dotnet", "test"),
            dotnetCommands(createTempDirectory("dotnet-root").toString(), listOf(".:test")).single().arguments,
        )
    }

    @Test
    fun `dotnet uses the Microsoft Testing Platform project option`() {
        val root = createTempDirectory("dotnet-mtp").toFile()
        File(root, "global.json").writeText(
            """{ "test": { "runner": "Microsoft.Testing.Platform" } }""",
        )

        val command = dotnetCommands(root.path, listOf("tests/App.Tests.csproj:test")).single()

        assertEquals(listOf("dotnet", "test", "--project", "tests/App.Tests.csproj"), command.arguments)
    }

    @Test
    fun `root Node packages use the package manager without a workspace selector`() {
        val root = createTempDirectory("node-command").toFile()

        assertEquals(listOf("npm", "test"), nodeCommands(root.path, listOf(".:test")).single().arguments)
        assertEquals(
            listOf("npm", "exec", "--", "tsc", "--noEmit"),
            nodeCommands(root.path, listOf(".:typecheck")).single().arguments,
        )
    }

    @Test
    fun `npm workspaces share one native invocation`() {
        val root = createTempDirectory("node-workspaces-command").toFile()

        val commands = nodeCommands(root.path, listOf("@app/core:test", "@app/ui:test"))

        assertEquals(
            listOf("npm", "test", "--workspace", "@app/core", "--workspace", "@app/ui"),
            commands.single().arguments,
        )
    }

    @Test
    fun `pnpm workspaces share one filtered invocation`() {
        val root = createTempDirectory("pnpm-workspaces-command").toFile()
        File(root, "pnpm-workspace.yaml").writeText("packages: []\n")

        val commands = nodeCommands(root.path, listOf("@app/core:test", "@app/ui:test"))

        assertEquals(
            listOf("pnpm", "--filter", "@app/core", "--filter", "@app/ui", "test"),
            commands.single().arguments,
        )
    }

    @Test
    fun `Yarn workspace commands stay sequential inside the shared handler`() {
        val root = createTempDirectory("yarn-workspaces-command").toFile()
        File(root, "yarn.lock").writeText("")

        val commands = nodeCommands(root.path, listOf("@app/core:test", "@app/ui:test"))

        assertEquals(2, commands.size)
        assertEquals(listOf("yarn", "workspace", "@app/core", "test"), commands[0].arguments)
        assertEquals(listOf("yarn", "workspace", "@app/ui", "test"), commands[1].arguments)
    }

    @Test
    fun `Cargo and Go batch compatible packages`() {
        assertEquals(
            listOf("cargo", "test", "-p", "core", "-p", "ui"),
            cargoCommands(listOf("core:test", "ui:test")).single().arguments,
        )
        assertEquals(
            listOf("go", "test", "example.com/core", "example.com/ui"),
            goCommands(listOf("example.com/core:test", "example.com/ui:test")).single().arguments,
        )
    }

    private fun modules(root: String, vararg entries: String): List<BuildModule> =
        entries.toList().chunked(2).map { (id, relative) ->
            BuildModule(
                id = id,
                root = root,
                contentRoots = listOf("$root/$relative"),
                testTask = "test",
                compileTask = null,
                hasTests = true,
            )
        }

    private fun cmakeRoot(vararg buildDirectories: String) =
        createTempDirectory("cmake-command").toFile().apply {
            buildDirectories.forEach { path ->
                java.io.File(this, "$path/CMakeCache.txt").apply {
                    parentFile.mkdirs()
                    writeText("CMAKE_GENERATOR=Ninja\n")
                }
            }
        }
}

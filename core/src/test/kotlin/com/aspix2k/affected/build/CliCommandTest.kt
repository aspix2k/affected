package com.aspix2k.affected.build

import com.google.gson.JsonParser
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `CMake exact full and empty plans stay in one ordered command batch`() {
        val selected = Path.of("/tmp/selected-tests.txt")
        val report = Path.of("/tmp/ctest.xml")

        val exact = cmakeSelectiveCommands(
            "build",
            CMakeTestSelection.Exact(listOf("affected_alpha")),
            selected = selected,
        )
        assertEquals(listOf("cmake", "--build", "build"), exact[0].arguments)
        assertEquals(
            listOf(
                "ctest", "--test-dir", "build", "--output-on-failure",
                "--tests-from-file", selected.toString(), "--no-tests=error",
            ),
            exact[1].arguments,
        )
        assertFalse(exact.flatMap { it.arguments }.contains("-R"))

        val full = cmakeSelectiveCommands(
            "build",
            CMakeTestSelection.Full,
            report = report,
        )
        assertEquals(listOf("cmake", "--build", "build"), full[0].arguments)
        assertEquals(
            listOf("ctest", "--test-dir", "build", "--output-on-failure", "--output-junit", report.toString()),
            full[1].arguments,
        )

        val empty = cmakeSelectiveCommands("build", CMakeTestSelection.Empty)
        assertEquals(listOf("cmake", "--build", "build"), empty.single().arguments)
    }

    @Test
    fun `command capture aborts output beyond its byte limit`() {
        val directory = createTempDirectory("bounded-capture")
        val bin = File(System.getProperty("java.home"), "bin")
        val java = listOf(File(bin, "java"), File(bin, "java.exe")).first(File::isFile)

        assertNull(CommandRunner.capture(directory.toString(), listOf(java.path, "-version"), maxBytes = 1))
    }

    @Test
    fun `command capture timeout terminates descendant processes`() {
        val directory = createTempDirectory("bounded-capture-tree")
        val bin = File(System.getProperty("java.home"), "bin")
        val java = listOf(File(bin, "java"), File(bin, "java.exe")).first(File::isFile)
        val javac = listOf(File(bin, "javac"), File(bin, "javac.exe")).first(File::isFile)
        val source = directory.resolve("CaptureTree.java")
        val pid = directory.resolve("child.pid")
        source.toFile().writeText(
            """
            import java.nio.file.Files;
            import java.nio.file.Path;

            class CaptureTree {
                public static void main(String[] args) throws Exception {
                    if (args[0].equals("child")) {
                        Thread.sleep(60_000);
                        return;
                    }
                    String java = ProcessHandle.current().info().command().orElseThrow();
                    Process child = new ProcessBuilder(
                        java, "-cp", System.getProperty("java.class.path"), "CaptureTree", "child"
                    ).start();
                    Files.writeString(Path.of(args[1]), Long.toString(child.pid()));
                    child.waitFor();
                }
            }
            """.trimIndent(),
        )
        val compiler = ProcessBuilder(javac.path, source.toString()).directory(directory.toFile()).start()
        assertEquals(0, compiler.waitFor())

        assertNull(
            CommandRunner.capture(
                directory.toString(),
                listOf(java.path, "-cp", directory.toString(), "CaptureTree", "parent", pid.toString()),
                timeoutSeconds = 1,
            ),
        )
        val childPid = pid.toFile().readText().toLong()
        var alive = true
        repeat(20) {
            alive = ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)
            if (alive) Thread.sleep(50)
        }
        assertFalse(alive)
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
    fun `pytest exact context contains only bounded relative paths`() {
        val root = createTempDirectory("pytest-context").toFile()
        val first = File(root, "packages/a").apply { mkdirs() }
        val second = File(root, "packages/b").apply { mkdirs() }
        val changed = File(first, "alpha.py").apply { writeText("value = 1\n") }
        val adapter = Path.of(requireNotNull(System.getProperty("affected.test.pytestAdapter")))
        val modules = modules(root.path, "pkg-a", "packages/a", "pkg-b", "packages/b")

        val command = pythonCommands(
            root.path,
            listOf("pkg-a:test", "pkg-b:test"),
            modules,
            BuildChanges(listOf(changed.path), setOf(changed.path), comparedToBase = true),
            adapter,
        ).single()

        assertEquals(listOf("python", adapter.toString()), command.arguments.take(2))
        assertEquals(listOf("--", "packages/a", "packages/b"), command.arguments.takeLast(3))
        val payload = Base64.getUrlDecoder().decode(command.arguments[2]).toString(StandardCharsets.UTF_8)
        val context = JsonParser.parseString(payload).asJsonObject
        assertEquals(listOf("packages/a", "packages/b"), context["roots"].asJsonArray.map { it.asString })
        assertEquals(listOf("packages/a", "packages/b"), context["packages"].asJsonArray.map { it.asString })
        assertEquals(listOf("packages/a/alpha.py"), context["changes"].asJsonArray.map { it.asString })
        assertFalse(payload.contains(root.path))
        assertTrue(second.isDirectory)
    }

    @Test
    fun `pytest keeps the native full command without an exact comparison`() {
        val root = createTempDirectory("pytest-full").toFile()
        val directory = File(root, "packages/a").apply { mkdirs() }
        val changed = File(directory, "alpha.py").apply { writeText("value = 1\n") }
        val adapter = Path.of(requireNotNull(System.getProperty("affected.test.pytestAdapter")))
        val modules = modules(root.path, "pkg-a", "packages/a")

        val command = pythonCommands(
            root.path,
            listOf("pkg-a:test"),
            modules,
            BuildChanges(listOf(changed.path), setOf(changed.path), comparedToBase = false),
            adapter,
        ).single()

        assertEquals(listOf("python", "-m", "pytest", "packages/a"), command.arguments)
    }

    @Test
    fun `pytest keeps the native full command when any change is ineligible`() {
        val root = createTempDirectory("pytest-ineligible").toFile()
        val directory = File(root, "packages/a").apply { mkdirs() }
        val changed = File(directory, "alpha.py").apply { writeText("value = 1\n") }
        val adapter = Path.of(requireNotNull(System.getProperty("affected.test.pytestAdapter")))
        val modules = modules(root.path, "pkg-a", "packages/a")

        val command = pythonCommands(
            root.path,
            listOf("pkg-a:test"),
            modules,
            BuildChanges(listOf(changed.path), emptySet(), comparedToBase = true),
            adapter,
        ).single()

        assertEquals(listOf("python", "-m", "pytest", "packages/a"), command.arguments)
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
    fun `Composer Pest packages share one native pest command`() {
        val root = createTempDirectory("composer-pest-commands").toFile()
        File(root, "composer.json").writeText(
            """{ "name": "acme/app", "require-dev": { "pestphp/pest": "^3.8" } }""",
        )
        val modules = modules(root.path, "pkg-a", "packages/a", "pkg-b", "packages/b")

        val commands = composerCommands(root.path, listOf("pkg-a:test", "pkg-b:test", "pkg-a:analyse"), modules)

        assertEquals("pest", commands.single { it.title == "pest" }.title)
        assertEquals(
            listOf("php", "vendor/bin/pest", "packages/a", "packages/b"),
            commands.single { it.title == "pest" }.arguments,
        )
        assertEquals(
            listOf("php", "vendor/bin/phpstan", "analyse", "packages/a"),
            commands.single { it.title == "phpstan" }.arguments,
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
    fun `dotnet exact selection uses native fully qualified filters after build`() {
        val report = createTempDirectory("dotnet-report").resolve("results.trx")

        assertEquals(
            listOf("dotnet", "build", "tests/App.Tests.csproj"),
            dotnetBuildCommand("tests/App.Tests.csproj").arguments,
        )
        assertEquals(
            listOf(
                "dotnet",
                "test",
                "tests/App.Tests.csproj",
                "--no-build",
                "--no-restore",
                "--results-directory",
                report.parent.toString(),
                "--logger",
                "trx;LogFileName=results.trx",
                "--filter",
                "FullyQualifiedName=Example.Tests.AlphaTest.Passes",
            ),
            dotnetTestArguments(
                "tests/App.Tests.csproj",
                DotnetTestSelection.Exact(listOf("Example.Tests.AlphaTest.Passes")),
                report,
            ),
        )
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

    @Test
    fun `Cargo nextest batches selected packages with its native package filter`() {
        val ciTask = cargoNextestTask(CargoNextestPlan(CargoNextestMode.PACKAGES, "ci", "0.9.143", false))
        val ciConfig = requireNotNull(cargoNextestSnapshot(ciTask))
        assertEquals(
            listOf(
                listOf(
                    "cargo-nextest", "nextest", "run",
                    "--manifest-path", "/workspace/Cargo.toml",
                    "--config-file", ciConfig.path,
                    "--profile", "ci", "--no-tests=pass",
                    "-p", "core", "-p", "ui",
                ),
                listOf(
                    "cargo", "test", "--doc", "--manifest-path", "/workspace/Cargo.toml",
                    "--no-fail-fast",
                    "-p", "core", "-p", "ui",
                ),
            ),
            cargoCommands("/workspace", listOf("core:$ciTask", "ui:$ciTask")).map(CliCommand::arguments),
        )
        val defaultTask = cargoNextestTask("default")
        val defaultConfig = requireNotNull(cargoNextestSnapshot(defaultTask))
        assertEquals(
            listOf(
                listOf(
                    "cargo-nextest", "nextest", "run",
                    "--manifest-path", "/workspace/Cargo.toml",
                    "--config-file", defaultConfig.path,
                    "--profile", "default", "--no-tests=pass", "--workspace",
                ),
                listOf(
                    "cargo", "test", "--doc", "--manifest-path", "/workspace/Cargo.toml", "--workspace",
                ),
            ),
            cargoCommands("/workspace", listOf(".:$defaultTask")).map(CliCommand::arguments),
        )
    }

    @Test
    fun `Cargo nextest runs doctests only for selected doctested libraries`() {
        val plan = CargoNextestPlan(CargoNextestMode.PACKAGES, "default", "0.9.143", true)
        val commands = cargoCommands(
            "/workspace",
            listOf(
                "library:${cargoNextestTask(plan, hasDoctests = true)}",
                "binary:${cargoNextestTask(plan, hasDoctests = false)}",
                "no-doc-lib:${cargoNextestTask(plan, hasDoctests = false)}",
            ),
        )

        assertEquals(2, commands.size)
        assertEquals(
            listOf("-p", "library", "-p", "binary", "-p", "no-doc-lib"),
            commands.first().arguments.takeLast(6),
        )
        assertEquals(listOf("-p", "library"), commands.last().arguments.takeLast(2))
    }

    @Test
    fun `Cargo widens resource and generated changes to the workspace`() {
        val changed = listOf("schema.json", "alpha/build.rs", "alpha/src/generated/value.rs")
        val task = cargoNextestTask("default")
        val config = requireNotNull(cargoNextestSnapshot(task))

        changed.forEach { path ->
            assertEquals(
                listOf(
                    listOf(
                        "cargo-nextest", "nextest", "run",
                        "--manifest-path", "/workspace/Cargo.toml",
                        "--config-file", config.path,
                        "--profile", "default", "--no-tests=pass", "--workspace",
                    ),
                    listOf(
                        "cargo", "test", "--doc", "--manifest-path", "/workspace/Cargo.toml", "--workspace",
                    ),
                ),
                cargoCommands(
                    "/workspace",
                    listOf("alpha:$task"),
                    BuildChanges(
                        files = listOf("/workspace/$path"),
                        exactSelectionEligible = emptySet(),
                        comparedToBase = true,
                    ),
                    unsafeCargoExecution = false,
                ).map(CliCommand::arguments),
            )
            assertEquals(
                listOf("cargo", "test", "--workspace"),
                cargoCommands(
                    "/workspace",
                    listOf("alpha:test"),
                    BuildChanges(
                        files = listOf("/workspace/$path"),
                        exactSelectionEligible = emptySet(),
                        comparedToBase = true,
                    ),
                    unsafeCargoExecution = false,
                ).single().arguments,
            )
        }
    }

    @Test
    fun `Cargo keeps only a proven regular Rust source package selective`() {
        val root = createTempDirectory("cargo-change").toFile()
        val source = File(root, "alpha/src/lib.rs").apply { parentFile.mkdirs(); writeText("pub fn value() {}") }
        val task = cargoNextestTask("default")
        val expected = listOf("-p", "alpha")

        assertEquals(
            expected,
            cargoCommands(
                root.path,
                listOf("alpha:$task"),
                BuildChanges(listOf(source.path), setOf(source.path), comparedToBase = true),
                unsafeCargoExecution = false,
            ).first().arguments.takeLast(2),
        )
        source.delete()
        assertTrue(
            cargoCommands(
                root.path,
                listOf("alpha:$task"),
                BuildChanges(listOf(source.path), emptySet(), comparedToBase = true),
                unsafeCargoExecution = false,
            ).first().arguments.contains("--workspace"),
        )
    }

    @Test
    fun `Cargo config appearing after analysis retains cargo test`() {
        val root = createTempDirectory("cargo-nextest-command").toFile()
        File(root, "Cargo.toml").writeText("[workspace]\n")
        val task = cargoNextestTask("default")
        File(root, ".cargo").mkdirs()
        File(root, ".cargo/config.toml").writeText("[target.x86_64-unknown-linux-gnu]\nrunner = 'wrapper'")

        assertEquals(
            listOf("cargo", "test", "--workspace"),
            cargoCommands(root.path, listOf("alpha:$task"), unsafeCargoExecution = true).single().arguments,
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

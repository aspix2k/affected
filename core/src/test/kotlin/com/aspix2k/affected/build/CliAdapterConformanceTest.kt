package com.aspix2k.affected.build

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliAdapterConformanceTest {

    @Test
    fun `Cargo commands run the selected workspace packages`() = fixture("cargo") { root ->
        val metadata = execute(root, listOf("cargo", "metadata", "--no-deps", "--format-version", "1"))
        val modules = CargoMetadata.parse(metadata, root.invariantSeparatorsPath)
        val output = execute(root, cargoCommands(modules.map { "${it.executionId}:test" }).single().arguments)

        assertContains(output, "affected_alpha")
        assertContains(output, "affected_beta")
    }

    @Test
    fun `Go commands run the selected module packages`() = fixture("go") { root ->
        val metadata = execute(root, listOf("go", "list", "-json", "./..."))
        val modules = GoPackages.parse(metadata, root.invariantSeparatorsPath)
        val output = execute(root, goCommands(modules.map { "${it.executionId}:test" }).single().arguments)

        assertContains(output, "example.com/affected-fixture/alpha")
        assertContains(output, "example.com/affected-fixture/beta")
    }

    @Test
    fun `npm runs exact Jest and Vitest files and preserves full fallback`() = fixture("node") { root ->
        execute(root, listOf("npm", "ci", "--ignore-scripts", "--no-audit", "--no-fund"))
        val modules = NodeWorkspaces.parse(root).filter(BuildModule::hasTests)
        val alphaSelected = File(root, "packages/alpha/alpha-selected.marker")
        val alphaFull = File(root, "packages/alpha/alpha-full.marker")
        val betaSelected = File(root, "packages/beta/beta-selected.marker")
        val betaFull = File(root, "packages/beta/beta-full.marker")
        execute(root, nodeCommands(root.path, modules.map { "${it.executionId}:test" }).single().arguments)

        deleteMarkers(alphaSelected, alphaFull, betaSelected, betaFull)

        val fullJestMillis = measureTimeMillis {
            execute(root, nodeCommands(root.path, listOf("@affected/alpha:test")).single().arguments)
        }
        deleteMarkers(alphaSelected, alphaFull)

        assertJestRelated(root, alphaSelected, alphaFull, fullJestMillis)
        assertVitestRelated(root, betaSelected, betaFull)
        assertNodeDynamicFallback(root, alphaSelected, alphaFull)
    }

    private fun assertJestRelated(root: File, selected: File, full: File, fullMillis: Long) {
        val exactMillis = measureTimeMillis { executeRelated(root, "@affected/alpha", "alpha.js") }
        assertTrue(selected.isFile)
        assertFalse(full.exists())
        assertTrue(exactMillis < fullMillis, "exact=$exactMillis ms, full=$fullMillis ms")

        assertTrue(selected.delete())
        executeRelated(root, "@affected/alpha", "alpha.test.js")
        assertTrue(selected.delete())
        assertFalse(full.exists())

        executeRelated(root, "@affected/alpha", "unused.js")
        assertFalse(selected.exists())
        assertFalse(full.exists())
    }

    private fun assertVitestRelated(root: File, selected: File, full: File) {
        executeRelated(root, "@affected/beta", "beta.js")
        assertTrue(selected.isFile)
        assertFalse(full.exists())

        assertTrue(selected.delete())
        executeRelated(root, "@affected/beta", "beta.test.js")
        assertTrue(selected.delete())
        assertFalse(full.exists())

        executeRelated(root, "@affected/beta", "unused.js")
        assertFalse(selected.exists())
        assertFalse(full.exists())
    }

    private fun executeRelated(root: File, packageName: String, fileName: String) {
        val file = File(root, "packages/${packageName.substringAfterLast('/')}/$fileName")
        val command = nodeCommands(
            root.path,
            listOf("$packageName:test"),
            BuildChanges(listOf(file.path), setOf(file.path), comparedToBase = true),
        ).single()
        execute(root, command.arguments)
    }

    private fun assertNodeDynamicFallback(root: File, selected: File, full: File) {
        File(root, "packages/alpha/dynamic.js").writeText("export const load = name => import(name)\n")
        val alpha = File(root, "packages/alpha/alpha.js")
        val fallback = nodeCommands(
            root.path,
            listOf("@affected/alpha:test"),
            BuildChanges(listOf(alpha.path), setOf(alpha.path), comparedToBase = true),
        ).single()
        execute(root, fallback.arguments)

        assertEquals(listOf("npm", "test", "--workspace", "@affected/alpha"), fallback.arguments)
        assertTrue(selected.isFile)
        assertTrue(full.isFile)
    }

    private fun deleteMarkers(vararg markers: File) {
        markers.forEach { assertTrue(it.delete()) }
    }

    @Test
    fun `pytest runs exact files and preserves full fallback`() = fixture("python") { root ->
        val modules = PythonProjects.parse(root).filter(BuildModule::hasTests)
        val full = execute(
            root,
            pythonCommands(root.path, modules.map { "${it.executionId}:test" }, modules).single().arguments,
        )
        assertContains(full, "4 passed")

        val alpha = modules.single { it.contentRoots.single().endsWith("/packages/alpha") }
        val alphaSource = File(root, "packages/alpha/alpha.py")
        val adapter = Path.of(requireNotNull(System.getProperty("affected.test.pytestAdapter")))
        val fullAlphaMillis = measureTimeMillis {
            val fullAlpha = execute(
                root,
                pythonCommands(root.path, listOf("${alpha.executionId}:test"), modules).single().arguments,
            )
            assertContains(fullAlpha, "3 passed")
        }
        lateinit var exact: String
        val exactMillis = measureTimeMillis { exact = executeRelatedPytest(root, modules, alpha, alphaSource, adapter) }
        assertContains(exact, "Affected pytest: exact (1 test file)")
        assertContains(exact, "2 passed, 1 deselected")
        assertTrue(exactMillis < fullAlphaMillis, "exact=$exactMillis ms, full=$fullAlphaMillis ms")

        val changedTest = File(root, "packages/alpha/tests/test_alpha.py")
        val testSelection = executeRelatedPytest(root, modules, alpha, changedTest, adapter)
        assertContains(testSelection, "Affected pytest: exact (1 test file)")
        assertContains(testSelection, "2 passed, 1 deselected")

        val dynamic = File(root, "packages/alpha/dynamic.py").apply {
            writeText("import importlib\n\ndef load(name):\n    return importlib.import_module(name)\n")
        }
        val dynamicFallback = executeRelatedPytest(root, modules, alpha, alphaSource, adapter)
        assertContains(dynamicFallback, "Affected pytest: full fallback (dynamic-dependency)")
        assertContains(dynamicFallback, "3 passed")
        assertTrue(dynamic.delete())

        val resource = File(root, "packages/alpha/schema.json").apply { writeText("{}") }
        val resourceFallback = executeRelatedPytest(root, modules, alpha, resource, adapter)
        assertContains(resourceFallback, "Affected pytest: full fallback (invalid-context)")
        assertContains(resourceFallback, "3 passed")
        assertTrue(resource.delete())

        val config = File(root, "pytest.ini").apply { writeText("[pytest]\n") }
        val configFallback = executeRelatedPytest(root, modules, alpha, alphaSource, adapter)
        assertContains(configFallback, "Affected pytest: full fallback (pytest-config)")
        assertContains(configFallback, "3 passed")
        assertTrue(config.delete())

        val conftest = File(root, "packages/alpha/tests/conftest.py").apply {
            writeText("def pytest_collection_modifyitems(items):\n    items.reverse()\n")
        }
        val conftestFallback = executeRelatedPytest(root, modules, alpha, alphaSource, adapter)
        assertContains(conftestFallback, "Affected pytest: full fallback (conftest)")
        assertContains(conftestFallback, "3 passed")
        assertTrue(conftest.delete())

        val runtimeFallback = executeRelatedPytest(
            root,
            modules,
            alpha,
            alphaSource,
            adapter,
            mapOf("PYTEST_ADDOPTS" to "-q"),
        )
        assertContains(runtimeFallback, "Affected pytest: full fallback (runtime-options)")
        assertContains(runtimeFallback, "3 passed")
    }

    private fun executeRelatedPytest(
        root: File,
        modules: List<BuildModule>,
        module: BuildModule,
        changed: File,
        adapter: Path,
        environment: Map<String, String> = emptyMap(),
    ): String {
        val command = pythonCommands(
            root.path,
            listOf("${module.executionId}:test"),
            modules,
            BuildChanges(listOf(changed.path), setOf(changed.path), comparedToBase = true),
            adapter,
        ).single()
        return execute(root, command.arguments, environment)
    }

    @Test
    fun `PHPUnit runs exact classes and preserves full fallback`() = fixture("composer") { root ->
        val modules = installPhpunitFixture(root)
        val fullPackages = execute(
            root,
            composerCommands(root.path, modules.map { "${it.executionId}:test" }, modules).single().arguments,
        )
        assertTrue(fullPackages.contains("OK (3 tests") || fullPackages.contains("Tests: 3"), fullPackages)
        val adapter = Path.of(requireNotNull(System.getProperty("affected.test.phpunitAdapter")))
        val alpha = modules.single { it.executionId == "affected/fixture-alpha" }
        val runtime = assertNotNull(readPhpunitRuntime(root.toPath()))
        val before = phpunitState(root, alpha, adapter, runtime)
        val store = PhpunitTestBaselineStore(createTempDirectory("phpunit-conformance-store"))
        val fullOutput = createTempDirectory("phpunit-conformance-full").resolve("run.json")
        val fullContext = phpunitContext(root.toPath(), before, fullOutput, full = true)
        val full = executePhpunit(root, adapter, fullContext, "packages/alpha")
        assertTrue(full.contains("OK (2 tests") || full.contains("Tests: 2"), full)
        assertTrue(promotePhpunitBaseline(store, before, before, fullOutput, full = true, passed = true))
        assertExactPhpunit(root, alpha, adapter, runtime, before, store)
    }

    private fun assertExactPhpunit(
        root: File,
        alpha: BuildModule,
        adapter: Path,
        runtime: PhpunitTestMetadata,
        before: PhpunitProjectState,
        store: PhpunitTestBaselineStore,
    ) {
        val baseline = assertNotNull(store.read())
        val owners = baseline.dependencies.entries
            .flatMap { (testClass, dependencies) -> dependencies.map { it to testClass } }
            .groupBy({ it.first }, { it.second })
        val expectedClasses = baseline.artifacts.keys.associateWith { artifact ->
            val sourceClass = Path.of(artifact).fileName.toString().removeSuffix(".php")
            "Affected\\Fixture\\Alpha\\Tests\\${sourceClass}Test"
        }
        expectedClasses.forEach { (artifact, expectedClass) ->
            assertTrue(expectedClass in owners[artifact].orEmpty(), baseline.toString())
        }
        val candidates = owners.entries.filter { (artifact, classes) ->
            artifact.startsWith("packages/alpha/src/") && classes.distinct().size == 1
        }
        assertEquals(1, candidates.size, baseline.toString())
        val candidate = candidates.single()
        val expectedClass = expectedClasses.getValue(candidate.key)
        assertEquals(listOf(expectedClass), candidate.value.distinct())
        val source = root.toPath().resolve(candidate.key)
        Files.writeString(source, Files.readString(source).replace("return ", "return /* changed */ "))
        val current = phpunitState(root, alpha, adapter, runtime)
        val selection = assertIs<PhpunitTestSelection.Exact>(
            selectPhpunitTests(
                root.toPath(),
                current,
                baseline,
                BuildChanges(listOf(source.toString()), setOf(source.toString()), comparedToBase = true),
            ),
        )
        assertEquals(listOf(expectedClass), selection.classes)
        val selectedOutput = createTempDirectory("phpunit-conformance-selected").resolve("run.json")
        val selectedContext = phpunitContext(root.toPath(), current, selectedOutput, full = false)
        val selected = executePhpunit(
            root,
            adapter,
            selectedContext,
            "--filter",
            phpunitClassFilter(selection.classes),
            baseline.classes.getValue(selection.classes.single()),
        )
        assertTrue(selected.contains("OK (1 test") || selected.contains("Tests: 1"), selected)
        assertTrue(completePhpunitSelection(selection, current, current, selectedOutput, baseline))
        assertEquals(before.identity, baseline.identity)
        assertPhpunitFallbacks(root, alpha, adapter, runtime, current, baseline)
    }

    private fun assertPhpunitFallbacks(
        root: File,
        alpha: BuildModule,
        adapter: Path,
        runtime: PhpunitTestMetadata,
        current: PhpunitProjectState,
        baseline: PhpunitTestSnapshot,
    ) {
        assertEquals(
            PhpunitTestSelection.Full,
            selectPhpunitTests(
                root.toPath(),
                current,
                baseline,
                BuildChanges(
                    listOf(root.toPath().resolve("packages/alpha/schema.json").toString()),
                    emptySet(),
                    comparedToBase = true,
                ),
            ),
        )
        root.resolve("phpunit.xml").writeText("<phpunit/>")
        assertNull(phpunitStateOrNull(root, alpha, adapter, runtime))
    }

    private fun installPhpunitFixture(root: File): List<BuildModule> {
        val version = System.getProperty("affected.phpunitVersion")
        version?.let {
            val manifest = root.resolve("composer.json")
            val configured = manifest.readText().replace(
                "\"phpunit/phpunit\": \"13.3.1\"",
                "\"phpunit/phpunit\": \"$it\"",
            )
            manifest.writeText(configured)
            root.resolve("locks/phpunit-$it.lock").copyTo(root.resolve("composer.lock"), overwrite = true)
        }
        execute(
            root,
            listOf("composer", "install", "--no-interaction", "--no-progress", "--no-plugins", "--no-scripts"),
        )
        return ComposerPackages.parse(root).filter(BuildModule::hasTests)
    }

    @Test
    fun `Pest 5 runs every test in the selected Composer packages`() = fixture("pest") { root ->
        val lock = root.resolve("composer.lock")
        val locked = lock.readBytes()
        val markers = listOf(
            root.resolve("packages/alpha/alpha.marker"),
            root.resolve("packages/alpha/dataset.marker"),
            root.resolve("packages/alpha/phpunit.marker"),
            root.resolve("packages/beta/beta.marker"),
        )
        execute(root, listOf("composer", "install", "--no-interaction", "--no-progress", "--no-scripts"))
        assertTrue(locked.contentEquals(lock.readBytes()))
        val modules = ComposerPackages.parse(root).filter(BuildModule::hasTests)
        assertEquals(setOf("affected/fixture-pest-alpha", "affected/fixture-pest-beta"), modules.map { it.id }.toSet())
        val command = composerCommands(
            root.path,
            modules.map { "${it.executionId}:${it.testTask}" },
            modules,
        ).single()

        assertEquals(
            listOf(
                "php",
                "vendor/bin/pest",
                "--ci",
                "--no-tia",
                "./packages/alpha/tests",
                "./packages/beta/tests",
            ),
            command.arguments,
        )
        execute(root, command.arguments)

        assertEquals("alpha", markers[0].readText())
        assertEquals(listOf("first", "second"), markers[1].readLines())
        assertEquals("phpunit", markers[2].readText())
        assertEquals("beta", markers[3].readText())
    }

    private fun phpunitState(
        root: File,
        module: BuildModule,
        adapter: Path,
        runtime: PhpunitTestMetadata,
    ): PhpunitProjectState = assertNotNull(phpunitStateOrNull(root, module, adapter, runtime))

    private fun phpunitStateOrNull(
        root: File,
        module: BuildModule,
        adapter: Path,
        runtime: PhpunitTestMetadata,
    ): PhpunitProjectState? = readPhpunitProjectState(
        root.toPath(),
        Path.of(module.contentRoots.single()),
        setOf(Path.of(module.contentRoots.single())),
        adapter,
        runtime,
        System.getenv(),
    )

    private fun executePhpunit(root: File, adapter: Path, context: Path, vararg selection: String): String {
        return execute(
            root,
            listOf(
                "php",
                "-d",
                "auto_prepend_file=${adapter.toAbsolutePath().normalize()}",
                "vendor/bin/phpunit",
                "--extension",
                "Affected\\Phpunit\\Extension",
                "--do-not-cache-result",
                "--no-coverage",
                "--fail-on-empty-test-suite",
            ) + selection,
            mapOf("AFFECTED_PHPUNIT_CONTEXT" to context.toString()),
        )
    }

    private fun phpunitContext(root: Path, state: PhpunitProjectState, output: Path, full: Boolean): Path {
        val context = createTempDirectory("phpunit-conformance-context").resolve("context.json")
        val json = JsonObject().apply {
            addProperty("schema", 1)
            addProperty("root", root.toAbsolutePath().normalize().toString())
            addProperty("output", output.toAbsolutePath().normalize().toString())
            addProperty("full", full)
            add("artifacts", JsonArray().also { array -> state.artifacts.keys.sorted().forEach(array::add) })
        }
        Files.writeString(context, json.toString(), StandardCharsets.UTF_8)
        return context
    }

    @Test
    fun `Bundler runs RSpec Minitest and Test Unit in one session`() = fixture("ruby") { root ->
        val lock = File(root, "Gemfile.lock").readBytes()
        execute(root, listOf("bundle", "config", "set", "--local", "path", "vendor/bundle"))
        execute(root, listOf("bundle", "config", "set", "--local", "frozen", "true"))
        execute(root, listOf("bundle", "install", "--jobs", "2", "--retry", "2"))
        assertTrue(lock.contentEquals(File(root, "Gemfile.lock").readBytes()))
        val modules = RubyGems.parse(root).filter(BuildModule::hasTests)
        val commands = rubyCommands(root.path, modules.map { "${it.executionId}:${it.testTask}" }, modules)
        val result = executeBatch(root, commands)

        assertEquals(listOf("rspec", "minitest", "test-unit"), commands.map(CliCommand::title))
        assertTrue(result.completed, result.output)
        assertTrue(result.passed, result.output)
        assertContains(result.output, "1 example, 0 failures")
        assertContains(result.output, "2 runs, 4 assertions")
        assertContains(result.output, "2 tests, 2 assertions")
        assertContains(result.output, "> rspec")
        assertContains(result.output, "> minitest")
        assertContains(result.output, "> test-unit")
    }

    @Test
    fun `CMake runs exact CTest files and preserves full fallback`() = fixture("cmake") { root ->
        val build = File(root, "build")
        configureCMake(root)
        assertTrue(requestCMakeCodemodel(build.toPath()))
        assertFalse(hasCMakeCodemodelReply(build.toPath()))
        configureCMake(root)
        assertTrue(hasCMakeCodemodelReply(build.toPath()))
        val modules = CMakeTargets.parse(root)
        lateinit var full: String
        val fullMillis = measureTimeMillis {
            full = cmakeCommands(root.path, modules.map { "${it.executionId}:test" })
                .joinToString("\n") { execute(root, it.arguments) }
        }
        assertContains(full, "affected_alpha")
        assertContains(full, "affected_alpha_extended")
        assertContains(full, "affected_beta")
        assertContains(full, "100% tests passed")

        val rootPath = root.canonicalFile.toPath()
        val baseline = assertNotNull(cmakeSnapshot(root, build))
        val alpha = File(root, "alpha.c").canonicalFile
        val exact = assertIs<CMakeTestSelection.Exact>(
            selectCMakeTests(rootPath, baseline, baseline, cmakeChanges(alpha)),
        )
        assertEquals(listOf("affected_alpha"), exact.tests)

        val alphaMarker = File(build, "affected-alpha.marker")
        val alphaExtendedMarker = File(build, "affected-alpha-extended.marker")
        val betaMarker = File(build, "affected-beta.marker")
        deleteMarkers(alphaMarker, alphaExtendedMarker, betaMarker)
        lateinit var exactOutput: String
        val exactMillis = measureTimeMillis {
            exactOutput = executeCMakeSelection(root, build, exact.tests)
        }
        assertContains(exactOutput, "affected_alpha")
        assertFalse(exactOutput.contains("affected_alpha_extended"), exactOutput)
        assertTrue(alphaMarker.isFile)
        assertFalse(alphaExtendedMarker.exists())
        assertFalse(betaMarker.exists())
        assertTrue(exactMillis < fullMillis, "exact=$exactMillis ms, full=$fullMillis ms")

        assertCMakeFallbacks(root, build, modules, baseline, alpha)
    }

    private fun assertCMakeFallbacks(
        root: File,
        build: File,
        modules: List<BuildModule>,
        baseline: CMakeTestSnapshot,
        alpha: File,
    ) {
        val rootPath = root.canonicalFile.toPath()
        val alphaMarker = File(build, "affected-alpha.marker")
        val alphaExtendedMarker = File(build, "affected-alpha-extended.marker")
        val betaMarker = File(build, "affected-beta.marker")

        configureCMake(root, "-DAFFECTED_FIXTURE_ENABLE_CTEST_FIXTURE=ON")
        assertEquals(
            CMakeTestSelection.Full,
            selectCMakeTests(rootPath, cmakeSnapshot(root, build), baseline, cmakeChanges(alpha)),
        )
        deleteExistingMarkers(alphaMarker)
        val fixtureFallback = runFullCMakePlan(root, modules)
        assertContains(fixtureFallback, "affected_fixture_setup")
        assertContains(fixtureFallback, "affected_fixture_cleanup")
        assertTrue(betaMarker.isFile)
        assertFalse(File(build, "affected-fixture.ready").exists())

        configureCMake(
            root,
            "-DAFFECTED_FIXTURE_ENABLE_CTEST_FIXTURE=OFF",
            "-DAFFECTED_FIXTURE_ENABLE_GENERATED_TEST=ON",
        )
        assertEquals(
            CMakeTestSelection.Full,
            selectCMakeTests(rootPath, cmakeSnapshot(root, build), baseline, cmakeChanges(alpha)),
        )
        deleteExistingMarkers(alphaMarker, alphaExtendedMarker, betaMarker)
        val generatedFallback = runFullCMakePlan(root, modules)
        assertContains(generatedFallback, "affected_generated")
        assertTrue(alphaExtendedMarker.isFile)
        assertTrue(File(build, "affected-generated.marker").isFile)

        configureCMake(
            root,
            "-DAFFECTED_FIXTURE_ENABLE_GENERATED_TEST=OFF",
            "-DAFFECTED_FIXTURE_ENABLE_RESOURCE_TEST=ON",
        )
        assertEquals(
            CMakeTestSelection.Full,
            selectCMakeTests(rootPath, cmakeSnapshot(root, build), baseline, cmakeChanges(alpha)),
        )
        deleteExistingMarkers(alphaMarker, alphaExtendedMarker, betaMarker)
        val resourceFallback = runFullCMakePlan(root, modules)
        assertContains(resourceFallback, "affected_alpha_extended")
        assertContains(resourceFallback, "affected_beta")
        assertTrue(alphaMarker.isFile)
        assertTrue(alphaExtendedMarker.isFile)
        assertTrue(betaMarker.isFile)
    }

    private fun configureCMake(root: File, vararg options: String) {
        execute(root, listOf("cmake", "-S", ".", "-B", "build") + options)
    }

    private fun cmakeSnapshot(root: File, build: File): CMakeTestSnapshot? =
        runCatching {
            readCMakeTestSnapshot(
                root.canonicalFile.toPath(),
                build.canonicalFile.toPath(),
            ) { execute(root, it) }
        }.getOrNull()

    private fun cmakeChanges(file: File): BuildChanges =
        BuildChanges(listOf(file.path), setOf(file.path), comparedToBase = true)

    private fun executeCMakeSelection(root: File, build: File, tests: List<String>): String {
        val selected = File(build, "affected-tests.txt").apply {
            writeText(tests.joinToString("\n", postfix = "\n"))
        }
        execute(root, listOf("cmake", "--build", "build"))
        return execute(
            root,
            listOf(
                "ctest",
                "--test-dir",
                "build",
                "--output-on-failure",
                "--tests-from-file",
                selected.path,
                "--no-tests=error",
            ),
        )
    }

    private fun runFullCMakePlan(root: File, modules: List<BuildModule>): String =
        cmakeCommands(root.path, modules.map { "${it.executionId}:test" })
            .joinToString("\n") { execute(root, it.arguments) }

    private fun deleteExistingMarkers(vararg markers: File) {
        markers.filter(File::exists).forEach { assertTrue(it.delete()) }
    }

    private fun fixture(name: String, block: (File) -> Unit) {
        assumeTrue(System.getProperty(CONFORMANCE_PROPERTY) == "true")
        val source = fixtureRoot().resolve(name)
        assertTrue(source.isDirectory, "Missing CLI conformance fixture: $source")
        val target = createTempDirectory("affected-cli-$name").toFile()
        try {
            assertTrue(source.copyRecursively(target, overwrite = true), "Could not copy $source")
            block(target)
        } finally {
            target.deleteRecursively()
        }
    }

    private fun fixtureRoot(): File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { File(it, "conformance/cli-fixtures") }
        .firstOrNull(File::isDirectory)
        ?: File(System.getProperty("user.dir"), "conformance/cli-fixtures")

    private fun execute(
        directory: File,
        arguments: List<String>,
        environment: Map<String, String> = emptyMap(),
    ): String {
        val result = executeResult(directory, arguments, environment)
        assertTrue(result.completed, "Timed out: ${arguments.joinToString(" ")}\n${result.output}")
        assertTrue(result.passed, "Failed: ${arguments.joinToString(" ")}\n${result.output}")
        return result.output
    }

    private fun executeResult(
        directory: File,
        arguments: List<String>,
        environment: Map<String, String> = emptyMap(),
    ): CommandResult {
        val output = File.createTempFile("affected-cli-output", ".log")
        try {
            val builder = ProcessBuilder(arguments)
                .directory(directory)
                .redirectErrorStream(true)
                .redirectOutput(output)
            builder.environment().putAll(environment)
            val process = builder.start()
            val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
            val text = output.readText()
            return CommandResult(completed, completed && process.exitValue() == 0, text)
        } finally {
            output.delete()
        }
    }

    private fun executeBatch(directory: File, commands: List<CliCommand>): CommandResult {
        val output = StringBuilder()
        val handler = SequentialProcessHandler(directory, commands)
        handler.addProcessListener(object : com.intellij.execution.process.ProcessListener {
            override fun onTextAvailable(
                event: com.intellij.execution.process.ProcessEvent,
                outputType: com.intellij.openapi.util.Key<*>,
            ) {
                output.append(event.text)
            }
        })
        handler.startNotify()
        val completed = handler.waitFor(TimeUnit.SECONDS.toMillis(COMMAND_TIMEOUT_SECONDS))
        if (!completed) handler.destroyProcess()
        return CommandResult(completed, completed && handler.exitCode == 0, output.toString())
    }

    private data class CommandResult(val completed: Boolean, val passed: Boolean, val output: String)

    private companion object {
        const val CONFORMANCE_PROPERTY = "affected.cliConformance"
        const val COMMAND_TIMEOUT_SECONDS = 180L
    }
}

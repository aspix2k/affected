package com.aspix2k.affected

import com.aspix2k.affected.build.BuildChanges
import com.aspix2k.affected.build.BuildModule
import com.aspix2k.affected.build.BuildSystem
import com.aspix2k.affected.build.TransitiveTestConsumersBuildSystem
import com.aspix2k.affected.build.WorkspaceChangesBuildSystem
import com.aspix2k.affected.build.cargo.CargoBuildSystem
import com.aspix2k.affected.build.cargo.CargoNextestMode
import com.aspix2k.affected.build.cargo.CargoNextestPlan
import com.aspix2k.affected.build.cargo.cargoCommands
import com.aspix2k.affected.build.cargo.cargoNextestTask
import com.aspix2k.affected.build.gradle.GradleBuildSystem
import com.aspix2k.affected.build.php.ComposerBuildSystem
import com.aspix2k.affected.build.php.ComposerPackages
import com.intellij.openapi.project.Project
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModuleGraphTest {

    @Test
    fun `a root manifest belongs to every module in that build`() {
        val root = createTempDirectory("module-graph-root").toFile()
        val graph = graph(
            module(root, "a", "packages/a"),
            module(root, "b", "packages/b"),
        )

        val owners = graph.nodesFor(File(root, "package.json"))

        assertEquals(setOf("a", "b"), owners.mapTo(HashSet()) { it.id })
    }

    @Test
    fun `a nested build owns its root manifest without widening the outer build`() {
        val root = createTempDirectory("module-graph-nested").toFile()
        val nested = File(root, "web").apply { mkdirs() }
        val graph = ModuleGraph(
            listOf(
                ModuleGraph.Node(module(root, "outer", "app"), system("GRADLE")),
                ModuleGraph.Node(module(nested, "inner", "packages/inner"), system("NODE")),
            ),
        )

        val owners = graph.nodesFor(File(nested, "package.json"))

        assertEquals(listOf("inner"), owners.map { it.id })
    }

    @Test
    fun `shared Gradle content keeps one source-set owner`() {
        val root = createTempDirectory("module-graph-gradle").toFile()
        val content = File(root, "shared").apply { mkdirs() }
        val graph = ModuleGraph(
            listOf(
                ModuleGraph.Node(module(root, "app", "shared"), GradleBuildSystem()),
                ModuleGraph.Node(module(root, "app.main", "shared"), GradleBuildSystem()),
            ),
        )

        assertEquals(listOf("app.main"), graph.nodesFor(File(content, "Sample.kt")).map { it.id })
    }

    @Test
    fun `shared CMake content keeps every declared target owner`() {
        val root = createTempDirectory("module-graph-cmake").toFile()
        val content = File(root, "shared").apply { mkdirs() }
        val graph = ModuleGraph(
            listOf(
                ModuleGraph.Node(module(root, "library", "shared"), system("CMAKE")),
                ModuleGraph.Node(module(root, "application", "shared"), system("CMAKE")),
            ),
        )

        assertEquals(
            setOf("library", "application"),
            graph.nodesFor(File(content, "CMakeLists.txt")).mapTo(HashSet()) { it.id },
        )
    }

    @Test
    fun `affected module snapshot keeps every owner of changed content`() {
        val root = createTempDirectory("affected-module-snapshot").toFile()
        val content = File(root, "shared").apply { mkdirs() }
        val graph = ModuleGraph(
            listOf(
                ModuleGraph.Node(module(root, "library", "shared"), system("CMAKE")),
                ModuleGraph.Node(module(root, "application", "shared"), system("CMAKE")),
            ),
        )

        val modules = affectedModules(graph, listOf(File(content, "shared.h")))

        assertEquals(setOf("library", "application"), modules.mapTo(HashSet(), AffectedModule::id))
    }

    @Test
    fun `Gradle build logic changes widen to every project in that build`() {
        val root = createTempDirectory("module-graph-gradle-build-logic").toFile()
        val system = GradleBuildSystem()
        val graph = ModuleGraph(
            listOf(
                ModuleGraph.Node(module(root, ":", ""), system),
                ModuleGraph.Node(module(root, ":app", "app"), system),
                ModuleGraph.Node(module(root, ":lib", "lib"), system),
            ),
        )
        val buildLogic = listOf(
            "gradle/libs.versions.toml",
            "gradle/wrapper/gradle-wrapper.properties",
            "buildSrc/src/main/kotlin/Conventions.kt",
        ).map { root.createFile(it) }
        val source = root.createFile("lib/src/main/kotlin/Lib.kt")

        buildLogic.forEach { file ->
            val changes = ProjectChanges.Result(listOf(file), emptySet(), setOf(file), comparedToBase = true)
            assertEquals(
                setOf(":", ":app", ":lib"),
                graph.ownersForChanges(changes.toBuildChanges()).getValue(file).mapTo(HashSet()) { it.id },
                file.path,
            )
        }
        val sourceChanges = ProjectChanges.Result(listOf(source), emptySet(), setOf(source), comparedToBase = true)
        assertEquals(
            listOf(":lib"),
            graph.ownersForChanges(sourceChanges.toBuildChanges()).getValue(source).map { it.id },
        )
    }

    @Test
    fun `included build logic widens to every project of the consuming build`() {
        val root = createTempDirectory("module-graph-included-build").toFile()
        val settings = "pluginManagement {\n    includeBuild(\"build-logic\")\n}\nincludeBuild('tools/checks')\n"
        File(root, "settings.gradle.kts").writeText(settings)
        File(root, "build-logic/settings.gradle.kts").apply { parentFile.mkdirs() }.writeText("")
        File(root, "build-logic/convention/build.gradle.kts").apply { parentFile.mkdirs() }
            .writeText("plugins {\n    `kotlin-dsl`\n}\n")
        File(root, "tools/checks/settings.gradle").apply { parentFile.mkdirs() }.writeText("")
        File(root, "tools/checks/checks/src/main/groovy/checks.gradle").apply { parentFile.mkdirs() }.writeText("")
        File(root, "other/settings.gradle.kts").apply { parentFile.mkdirs() }.writeText("")
        val graph = gradleGraph(
            module(root, ":app", "app"),
            module(root, ":lib", "lib"),
            module(File(root, "build-logic"), ":convention", "convention"),
            module(File(root, "tools/checks"), ":checks", "checks"),
            module(File(root, "other"), ":single", "single"),
        )

        mapOf(
            "build-logic/convention/src/Plugin.kt" to ":convention",
            "tools/checks/checks/src/Check.kt" to ":checks",
        ).forEach { (path, owner) ->
            assertEquals(setOf(owner, ":app", ":lib"), graph.owners(root.createFile(path)), path)
        }
    }

    @Test
    fun `an included library build without plugins does not widen the consuming build`() {
        val root = createTempDirectory("module-graph-included-library").toFile()
        File(root, "settings.gradle.kts").writeText("includeBuild(\"library\")\n")
        File(root, "library/settings.gradle.kts").apply { parentFile.mkdirs() }.writeText("")
        File(root, "library/core/build.gradle.kts").apply { parentFile.mkdirs() }
            .writeText("plugins {\n    `java-library`\n}\n")
        val graph = gradleGraph(
            module(root, ":app", "app"),
            module(root, ":lib", "lib"),
            module(File(root, "library"), ":core", "core"),
        )

        val file = root.createFile("library/core/src/main/java/Core.kt")

        assertEquals(setOf(":core"), graph.owners(file))
    }

    @Test
    fun `an included build with gradlePlugin declarations widens the consuming build`() {
        val root = createTempDirectory("module-graph-included-plugin").toFile()
        File(root, "settings.gradle.kts").writeText("includeBuild(\"plugins\")\n")
        File(root, "plugins/settings.gradle.kts").apply { parentFile.mkdirs() }.writeText("")
        File(root, "plugins/build.gradle.kts").writeText("gradlePlugin {\n}\n")
        val graph = gradleGraph(
            module(root, ":app", "app"),
            module(File(root, "plugins"), ":plugins", "src"),
        )

        val file = root.createFile("plugins/src/Plugin.kt")

        assertEquals(setOf(":app", ":plugins"), graph.owners(file))
    }

    @Test
    fun `buildSrc with its own settings widens to the build that owns it`() {
        val root = createTempDirectory("module-graph-buildsrc-settings").toFile()
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"app\"\n")
        File(root, "buildSrc/settings.gradle.kts").apply { parentFile.mkdirs() }.writeText("")
        val graph = gradleGraph(
            module(root, ":app", "app"),
            module(root, ":lib", "lib"),
            module(File(root, "buildSrc"), ":buildSrc", "src"),
        )

        val file = root.createFile("buildSrc/src/main/kotlin/Conventions.kt")

        assertEquals(setOf(":app", ":lib", ":buildSrc"), graph.owners(file))
    }

    @Test
    fun `an unresolvable includeBuild widens every other Gradle build`() {
        val root = createTempDirectory("module-graph-dynamic-include").toFile()
        File(root, "settings.gradle.kts").writeText("includeBuild(rootDir.resolve(name))\n")
        File(root, "logic/settings.gradle.kts").apply { parentFile.mkdirs() }.writeText("")
        File(root, "logic/build.gradle.kts").writeText("plugins { `kotlin-dsl` }\n")
        val graph = gradleGraph(
            module(root, ":app", "app"),
            module(File(root, "logic"), ":logic", "logic"),
        )

        val file = root.createFile("logic/logic/Plugin.kt")

        assertEquals(setOf(":app", ":logic"), graph.owners(file))
    }

    @Test
    fun `builds that do not include the changed build stay narrow`() {
        val root = createTempDirectory("module-graph-unrelated-builds").toFile()
        File(root, "settings.gradle.kts").writeText("includeBuild(\"elsewhere\")\n")
        File(root, "logic/settings.gradle.kts").apply { parentFile.mkdirs() }.writeText("")
        val graph = gradleGraph(
            module(root, ":app", "app"),
            module(File(root, "logic"), ":logic", "logic"),
        )

        val file = root.createFile("logic/logic/Plugin.kt")

        assertEquals(setOf(":logic"), graph.owners(file))
    }

    @Test
    fun `workspace changes widen both the prepared plan and affected module snapshot`() {
        val root = createTempDirectory("module-graph-workspace").toFile()
        val system = workspaceSystem()
        val graph = ModuleGraph(
            listOf(
                ModuleGraph.Node(module(root, "alpha", "alpha"), system),
                ModuleGraph.Node(module(root, "beta", "beta"), system),
            ),
        )
        val resource = File(root, "alpha/schema.json").apply { writeText("{}") }
        val changes = ProjectChanges.Result(listOf(resource), emptySet(), setOf(resource), comparedToBase = true)
        val owners = graph.ownersForChanges(changes.toBuildChanges())

        assertEquals(setOf("alpha", "beta"), owners.getValue(resource).mapTo(HashSet()) { it.id })
        assertEquals(
            setOf("alpha", "beta"),
            affectedModules(graph, changes).mapTo(HashSet(), AffectedModule::id),
        )
        assertEquals(
            listOf("alpha:test", "beta:test"),
            verificationPlan(graph, changes, checkConsumers = false).groups.single().tasks,
        )
    }

    @Test
    fun `changed sources outside every module are unresolved next to a planned module`() {
        val root = createTempDirectory("module-graph-unresolved").toFile()
        val kotlin = object : BuildSystem by system("GRADLE") {
            override val sourceExtensions: Set<String> = setOf("kt")
        }
        val graph = ModuleGraph(listOf(ModuleGraph.Node(module(root, "alpha", "alpha"), kotlin)))
        val owned = File(root, "alpha/Main.kt").apply { parentFile.mkdirs(); writeText("class Main") }
        val outside = File(root.parentFile, "${root.name}-outside/Other.kt").apply {
            parentFile.mkdirs()
            writeText("class Other")
        }
        val workflow = File(root.parentFile, "${root.name}-outside/ci.yml").apply { writeText("on: push") }
        val changes = ProjectChanges.Result(
            listOf(owned, outside, workflow),
            emptySet(),
            setOf(owned, outside),
            comparedToBase = true,
        )
        val prepared = Verification.prepare(graph, changes).select(checkConsumers = false)

        assertEquals(listOf("alpha:test"), prepared.plan.groups.single().tasks)
        assertEquals(1, prepared.unresolvedFiles)
    }

    @Test
    fun `Cargo custom build plan widens UI and execution to the same workspace`() {
        val root = createTempDirectory("module-graph-cargo-build-script").toFile()
        val task = cargoNextestTask(CargoNextestPlan(CargoNextestMode.WORKSPACE, "default", "0.9.143", true))
        val system = CargoBuildSystem()
        val graph = ModuleGraph(
            listOf(
                ModuleGraph.Node(module(root, "alpha", "alpha").copy(testTask = task), system),
                ModuleGraph.Node(module(root, "beta", "beta").copy(testTask = task), system),
            ),
        )
        val source = File(root, "alpha/lib.rs").apply { writeText("pub fn alpha() {}") }
        val changes = ProjectChanges.Result(listOf(source), emptySet(), setOf(source), comparedToBase = true)
        val plan = verificationPlan(graph, changes, checkConsumers = false)

        assertEquals(setOf("alpha", "beta"), affectedModules(graph, changes).mapTo(HashSet(), AffectedModule::id))
        assertEquals(listOf("alpha:$task", "beta:$task"), plan.groups.single().tasks)
        assertEquals(
            listOf("cargo-nextest", "nextest", "run", "--manifest-path", File(root, "Cargo.toml").path),
            cargoCommands(root.path, plan.groups.single().tasks, changes.toBuildChanges(), false)
                .first().arguments.take(5),
        )
        assertEquals(
            "--workspace",
            cargoCommands(root.path, plan.groups.single().tasks, changes.toBuildChanges(), false)
                .first().arguments.last(),
        )
    }

    @Test
    fun `dotnet changes reach transitive test projects`() {
        val root = createTempDirectory("module-graph-dotnet").toFile()
        val alpha = module(root, "Alpha", "Alpha").copy(testTask = "build", compileTask = "build")
        val facade = module(root, "Facade", "Facade").copy(
            testTask = "build",
            compileTask = "build",
            dependencies = setOf(alpha.key),
        )
        val tests = module(root, "Facade.Tests", "Facade.Tests").copy(
            compileTask = "build",
            dependencies = setOf(facade.key),
        )
        val hiddenConsumer = module(root, "Hidden.Tests", "Hidden.Tests").copy(compileTask = "build")
        val dotnet = dotnetSystem()
        val graph = ModuleGraph(
            listOf(alpha, facade, tests, hiddenConsumer).map { ModuleGraph.Node(it, dotnet) },
        )

        val changed = File(root, "Alpha/Value.cs").apply { writeText("namespace Alpha;") }
        val consumers = graph.transitiveTestConsumers(setOf(graph.all().first()))

        assertEquals(setOf("Facade.Tests", "Hidden.Tests"), consumers.mapTo(HashSet(), ModuleGraph.Node::id))
        assertEquals(
            listOf("Alpha:build", "Facade.Tests:test", "Hidden.Tests:test"),
            verificationPlan(
                graph,
                ProjectChanges.Result(listOf(changed), emptySet(), setOf(changed), comparedToBase = true),
                checkConsumers = false,
            ).groups.single().tasks,
        )
    }

    @Test
    fun `Composer changes reach transitive PHPUnit packages`() {
        val root = createTempDirectory("module-graph-composer").toFile()
        val alpha = module(root, "affected/alpha", "packages/alpha")
        val beta = module(root, "affected/beta", "packages/beta").copy(dependencies = setOf(alpha.key))
        val composer = transitiveSystem("COMPOSER")
        val graph = ModuleGraph(listOf(alpha, beta).map { ModuleGraph.Node(it, composer) })
        val changed = File(root, "packages/alpha/src/Alpha.php").apply {
            parentFile.mkdirs()
            writeText("<?php")
        }

        assertEquals(
            listOf("affected/alpha:test", "affected/beta:test"),
            verificationPlan(
                graph,
                ProjectChanges.Result(listOf(changed), emptySet(), setOf(changed), comparedToBase = true),
                checkConsumers = false,
            ).groups.single().tasks,
        )
    }

    @Test
    fun `a root Pest boot file change runs every Pest package`() {
        val root = createTempDirectory("module-graph-pest-bootstrap").toFile()
        val system = ComposerBuildSystem()
        val rootModule = BuildModule(
            id = "affected/root",
            root = root.invariantSeparatorsPath,
            contentRoots = listOf(root.invariantSeparatorsPath),
            testTask = ComposerPackages.PEST,
            compileTask = null,
            hasTests = false,
            executionId = ".",
        )
        val alpha = module(root, "affected/alpha", "packages/alpha").copy(testTask = ComposerPackages.PEST)
        val beta = module(root, "affected/beta", "packages/beta").copy(testTask = ComposerPackages.PEST)
        val graph = ModuleGraph(listOf(rootModule, alpha, beta).map { ModuleGraph.Node(it, system) })
        val bootstrap = File(root, "tests/Helpers/Shared.php").apply {
            parentFile.mkdirs()
            writeText("<?php")
        }
        val changes = ProjectChanges.Result(listOf(bootstrap), emptySet(), setOf(bootstrap), comparedToBase = true)

        assertEquals(
            setOf("affected/root", "affected/alpha", "affected/beta"),
            affectedModules(graph, changes).mapTo(HashSet(), AffectedModule::id),
        )
        assertEquals(
            listOf("affected/alpha:${ComposerPackages.PEST}", "affected/beta:${ComposerPackages.PEST}"),
            verificationPlan(graph, changes, checkConsumers = false).groups.single().tasks,
        )
    }

    @Test
    fun `a cmake consumer does not follow a same-named dotnet module`() {
        val root = createTempDirectory("module-graph-identity").toFile()
        val cmakeLib = module(root, "lib", "cpp").copy(systemId = "CMAKE")
        val dotnetLib = module(root, "lib", "dotnet").copy(systemId = "DOTNET")
        val cmakeApp = module(root, "app", "cpp-app").copy(
            systemId = "CMAKE",
            dependencies = setOf(cmakeLib.key),
        )
        val graph = ModuleGraph(
            listOf(
                ModuleGraph.Node(cmakeLib, system("CMAKE")),
                ModuleGraph.Node(dotnetLib, system("DOTNET")),
                ModuleGraph.Node(cmakeApp, system("CMAKE")),
            ),
        )
        val cmakeNode = graph.all().single { it.system.id == "CMAKE" && it.id == "lib" }
        val dotnetNode = graph.all().single { it.system.id == "DOTNET" && it.id == "lib" }

        assertEquals(listOf("app"), graph.directDependents(setOf(cmakeNode)).map(ModuleGraph.Node::id))
        assertTrue(graph.directDependents(setOf(dotnetNode)).isEmpty())
    }

    private fun File.createFile(relative: String): File =
        File(this, relative).apply {
            parentFile.mkdirs()
            writeText("")
        }

    private fun gradleGraph(vararg modules: BuildModule): ModuleGraph {
        val system = GradleBuildSystem()
        return ModuleGraph(modules.map { ModuleGraph.Node(it, system) })
    }

    private fun ModuleGraph.owners(file: File): Set<String> {
        val changes = ProjectChanges.Result(listOf(file), emptySet(), setOf(file), comparedToBase = true)
        return ownersForChanges(changes.toBuildChanges()).getValue(file).mapTo(HashSet()) { it.id }
    }

    private fun graph(vararg modules: BuildModule): ModuleGraph =
        ModuleGraph(modules.map { ModuleGraph.Node(it, system("NODE")) })

    private fun module(root: File, id: String, contentRoot: String): BuildModule = BuildModule(
        id = id,
        root = root.invariantSeparatorsPath,
        contentRoots = listOf(File(root, contentRoot).apply { mkdirs() }.invariantSeparatorsPath),
        testTask = "test",
        compileTask = null,
        hasTests = true,
    )

    private fun system(systemId: String): BuildSystem = object : BuildSystem {
        override val id: String = systemId
        override val sourceExtensions: Set<String> = emptySet()
        override fun isPresent(project: Project): Boolean = false
        override fun modules(project: Project): List<BuildModule> = emptyList()
        override fun run(project: Project, root: String, tasks: List<String>) = Unit
        override fun runAndWait(project: Project, root: String, tasks: List<String>): Boolean = false
    }

    private fun dotnetSystem(): BuildSystem = transitiveSystem("DOTNET")

    private fun workspaceSystem(): BuildSystem = object : BuildSystem by system("CARGO"), WorkspaceChangesBuildSystem {
        override fun requiresWorkspace(module: BuildModule, changes: BuildChanges): Boolean =
            changes.files.any { !it.endsWith(".rs") }
    }

    private fun transitiveSystem(systemId: String): BuildSystem = object :
        BuildSystem,
        TransitiveTestConsumersBuildSystem {
        override val id: String = systemId
        override val sourceExtensions: Set<String> = emptySet()
        override fun isPresent(project: Project): Boolean = false
        override fun modules(project: Project): List<BuildModule> = emptyList()
        override fun run(project: Project, root: String, tasks: List<String>) = Unit
        override fun runAndWait(project: Project, root: String, tasks: List<String>): Boolean = false
    }
}

import info.solidsoft.gradle.pitest.PitestTask
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import java.io.ByteArrayInputStream
import java.util.Properties
import java.util.jar.JarInputStream
import java.util.zip.ZipFile

buildscript {
    repositories {
        val mavenCentralMirror = "https://cache-redirector.jetbrains.com/repo1.maven.org/maven2"
        if (System.getenv("AFFECTED_PREFER_MAVEN_CENTRAL") == "1") {
            mavenCentral()
            maven(mavenCentralMirror)
        } else {
            maven(mavenCentralMirror)
            mavenCentral()
        }
        maven("https://cache-redirector.jetbrains.com/plugins.gradle.org")
        gradlePluginPortal()
    }
    dependencies {
        classpath(enforcedPlatform("com.fasterxml.jackson:jackson-bom:2.22.1"))
        constraints {
            classpath("org.jsoup:jsoup:1.23.1")
        }
    }
}

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
    id("info.solidsoft.pitest") version "1.19.0"
    id("org.jetbrains.changelog") version "2.5.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("com.autonomousapps.dependency-analysis") version "3.18.0"
}

group = "com.aspix2k"
version = "3.13.8"

repositories {
    val mavenCentralMirror = "https://cache-redirector.jetbrains.com/repo1.maven.org/maven2"
    if (System.getenv("AFFECTED_PREFER_MAVEN_CENTRAL") == "1") {
        mavenCentral()
        maven(mavenCentralMirror)
    } else {
        maven(mavenCentralMirror)
        mavenCentral()
    }
    intellijPlatform { defaultRepositories() }
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

val localIde: String? = providers.gradleProperty("affected.ide.path").orNull
    ?: providers.environmentVariable("AFFECTED_IDE_PATH").orNull
    ?: localProperties.getProperty("ide.path")

dependencies {
    intellijPlatform {
        if (localIde != null) {
            local(localIde)
        } else {
            intellijIdea(providers.gradleProperty("affected.idea.version").get())
        }
        bundledModule("intellij.platform.vcs.dvcs.impl")
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
        pluginComposedModule(api(project(":core")))
        pluginModule(runtimeOnly(project(":mcp")))
    }

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    kover(project(":core"))
    kover(project(":mcp"))
}

kotlin { jvmToolchain(21) }

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
        changeNotes = provider {
            with(changelog) {
                renderItem(
                    (getOrNull(project.version.toString()) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    org.jetbrains.changelog.Changelog.OutputType.HTML,
                )
            }
        }
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = providers.gradleProperty("affected.publish.channel").map { listOf(it) }.orElse(listOf("default"))
    }

    pluginVerification {
        failureLevel = listOf(
            FailureLevel.INVALID_PLUGIN,
            FailureLevel.EXPERIMENTAL_API_USAGES,
        )
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2025.3")
            create(IntelliJPlatformType.IntellijIdea, "2026.2")
            create(IntelliJPlatformType.AndroidStudio, providers.gradleProperty("affected.studio.version"))
            create(IntelliJPlatformType.Rider, "2025.3.5")
            create(IntelliJPlatformType.GoLand, "2025.3.5.1")
        }
    }
}

kover {
    reports {
        total {
            xml { onCheck = true }
            verify {
                rule {
                    minBound(60)
                }
            }
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    autoCorrect = !providers.environmentVariable("CI").isPresent
    config.setFrom(files("$rootDir/config/detekt.yml"))
    source.setFrom(files("src", "core/src", "mcp/src"))
    parallel = true
}

subprojects {
    apply(plugin = "com.autonomousapps.dependency-analysis")
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        apply(plugin = "org.jetbrains.kotlinx.kover")
    }
}

dependencyAnalysis {
    issues {
        all {
            onAny {
                severity("fail")
            }
        }
    }
}

dependencies {
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
}

val pluginLicense = layout.projectDirectory.file("LICENSE")
val pluginDirectory = project.name
val collectorAgentArtifact = configurations.create("collectorAgentArtifact") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
val collectorListenerArtifact = configurations.create("collectorListenerArtifact") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val mavenAgentArtifact = configurations.create("mavenAgentArtifact") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val mavenExtensionArtifact = configurations.create("mavenExtensionArtifact") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies.add(collectorAgentArtifact.name, dependencies.project(path = ":collector"))
dependencies.add(
    collectorListenerArtifact.name,
    dependencies.project(path = ":collector", configuration = "listenerElements"),
)
dependencies.add(
    mavenAgentArtifact.name,
    dependencies.project(path = ":collector", configuration = "mavenAgentElements"),
)
dependencies.add(
    mavenExtensionArtifact.name,
    dependencies.project(path = ":collector", configuration = "mavenExtensionElements"),
)
val collectorAgentArchive = collectorAgentArtifact.elements.map { it.single().asFile }
val collectorListenerArchive = collectorListenerArtifact.elements.map { it.single().asFile }
val mavenAgentArchive = mavenAgentArtifact.elements.map { it.single().asFile }
val mavenExtensionArchive = mavenExtensionArtifact.elements.map { it.single().asFile }
val collectorInitScript = project(":collector").layout.projectDirectory.file("src/main/gradle/affected-collector.init.gradle")
val pytestAdapter = project(":core").layout.projectDirectory.file("src/main/python/affected_pytest.py")
val unittestAdapter = project(":core").layout.projectDirectory.file("src/main/python/affected_unittest.py")
val phpunitAdapter = project(":core").layout.projectDirectory.file("src/main/php/affected_phpunit.php")
val dotnetAnalyzer = project(":core").layout.projectDirectory.dir("src/main/dotnet/Affected.DotnetAnalyzer")
val collectorAgentPath = "$pluginDirectory/agent/affected-collector-agent.jar"
val collectorListenerPath = "$pluginDirectory/agent/affected-collector-listener.jar"
val collectorInitScriptPath = "$pluginDirectory/agent/affected-collector.init.gradle"
val mavenAgentPath = "$pluginDirectory/agent/affected-maven-agent.jar"
val mavenExtensionPath = "$pluginDirectory/agent/affected-maven-extension.jar"
val pytestAdapterPath = "$pluginDirectory/agent/affected-pytest.py"
val unittestAdapterPath = "$pluginDirectory/agent/affected-unittest.py"
val phpunitAdapterPath = "$pluginDirectory/agent/affected-phpunit.php"
val dotnetAnalyzerPath = "$pluginDirectory/agent/dotnet/Affected.DotnetAnalyzer"
val collectorPremain = "com.aspix2k.affected.collector.AffectedCollectorAgent"
val collectorListener = "com.aspix2k.affected.collector.AffectedTestExecutionListener"
val collectorService = "META-INF/services/org.junit.platform.launcher.TestExecutionListener"
val mavenFilter = "com.aspix2k.affected.collector.AffectedMavenFilter"
val mavenFilterService = "META-INF/services/org.junit.platform.launcher.PostDiscoveryFilter"
val mavenComponentService = "META-INF/plexus/components.xml"
val asmLicense = "META-INF/LICENSE-ASM.txt"

tasks.named<BuildPluginTask>("buildPlugin") {
    from(pluginLicense)
    from(collectorAgentArtifact) {
        into("agent")
    }
    from(collectorListenerArtifact) {
        into("agent")
    }
    from(collectorInitScript) {
        into("agent")
    }
    from(pytestAdapter) {
        into("agent")
        rename { "affected-pytest.py" }
    }
    from(unittestAdapter) {
        into("agent")
        rename { "affected-unittest.py" }
    }
    from(phpunitAdapter) {
        into("agent")
        rename { "affected-phpunit.php" }
    }
    from(dotnetAnalyzer) {
        into("agent/dotnet/Affected.DotnetAnalyzer")
    }
    from(mavenAgentArtifact) {
        into("agent")
    }
    from(mavenExtensionArtifact) {
        into("agent")
    }

    doLast {
        val distribution = destinationDirectory.file(archiveFileName).get().asFile
        ZipFile(distribution).use { archive ->
            val path = "$pluginDirectory/LICENSE"
            val packagedLicenses = archive.entries().asSequence().filter { it.name == path }.toList()
            check(packagedLicenses.size == 1) { "Plugin distribution must contain exactly one $path" }
            val packagedLicense = archive.getInputStream(packagedLicenses.single()).use { it.readBytes() }
            check(packagedLicense.contentEquals(pluginLicense.asFile.readBytes())) {
                "Packaged LICENSE must match the root LICENSE"
            }

            val packagedAgents = archive.entries().asSequence().filter { it.name == collectorAgentPath }.toList()
            check(packagedAgents.size == 1) {
                "Plugin distribution must contain exactly one $collectorAgentPath"
            }
            val packagedAgent = archive.getInputStream(packagedAgents.single()).use { it.readBytes() }
            check(packagedAgent.contentEquals(collectorAgentArchive.get().readBytes())) {
                "Packaged collector agent must match the collector module JAR"
            }
            JarInputStream(ByteArrayInputStream(packagedAgent)).use { agent ->
                check(agent.manifest?.mainAttributes?.getValue("Premain-Class") == collectorPremain) {
                    "Packaged collector must declare $collectorPremain as Premain-Class"
                }
                val entries = generateSequence { agent.nextJarEntry }.map { it.name }.toList()
                check(entries.none { it == collectorService } && entries.count { it == asmLicense } == 1) {
                    "Collector agent must contain one ASM license and no JUnit service"
                }
            }

            val packagedListeners = archive.entries().asSequence().filter { it.name == collectorListenerPath }.toList()
            check(packagedListeners.size == 1) {
                "Plugin distribution must contain exactly one $collectorListenerPath"
            }
            val packagedListener = archive.getInputStream(packagedListeners.single()).use { it.readBytes() }
            check(packagedListener.contentEquals(collectorListenerArchive.get().readBytes())) {
                "Packaged collector listener must match the collector module JAR"
            }
            JarInputStream(ByteArrayInputStream(packagedListener)).use { listener ->
                val services = generateSequence { listener.nextJarEntry }
                    .filter { it.name == collectorService }
                    .map { listener.readBytes().toString(Charsets.UTF_8).trim() }
                    .toList()
                check(services == listOf(collectorListener)) {
                    "Packaged collector listener must declare exactly one $collectorListener service"
                }
            }

            val packagedScripts = archive.entries().asSequence().filter { it.name == collectorInitScriptPath }.toList()
            check(packagedScripts.size == 1) {
                "Plugin distribution must contain exactly one $collectorInitScriptPath"
            }
            val packagedScript = archive.getInputStream(packagedScripts.single()).use { it.readBytes() }
            check(packagedScript.contentEquals(collectorInitScript.asFile.readBytes())) {
                "Packaged collector init script must match the collector module source"
            }

            val packagedPytestAdapters = archive.entries().asSequence()
                .filter { it.name == pytestAdapterPath }
                .toList()
            check(packagedPytestAdapters.size == 1) {
                "Plugin distribution must contain exactly one $pytestAdapterPath"
            }
            val packagedPytestAdapter = archive.getInputStream(packagedPytestAdapters.single()).use { it.readBytes() }
            check(packagedPytestAdapter.contentEquals(pytestAdapter.asFile.readBytes())) {
                "Packaged pytest adapter must match the core module source"
            }

            val packagedUnittestAdapters = archive.entries().asSequence()
                .filter { it.name == unittestAdapterPath }
                .toList()
            check(packagedUnittestAdapters.size == 1) {
                "Plugin distribution must contain exactly one $unittestAdapterPath"
            }
            val packagedUnittestAdapter = archive.getInputStream(packagedUnittestAdapters.single()).use { it.readBytes() }
            check(packagedUnittestAdapter.contentEquals(unittestAdapter.asFile.readBytes())) {
                "Packaged unittest adapter must match the core module source"
            }

            val packagedPhpunitAdapters = archive.entries().asSequence()
                .filter { it.name == phpunitAdapterPath }
                .toList()
            check(packagedPhpunitAdapters.size == 1) {
                "Plugin distribution must contain exactly one $phpunitAdapterPath"
            }
            val packagedPhpunitAdapter = archive.getInputStream(packagedPhpunitAdapters.single()).use { it.readBytes() }
            check(packagedPhpunitAdapter.contentEquals(phpunitAdapter.asFile.readBytes())) {
                "Packaged PHPUnit adapter must match the core module source"
            }

            val dotnetAnalyzerFiles = listOf("Affected.DotnetAnalyzer.csproj", "Program.cs")
            dotnetAnalyzerFiles.forEach { name ->
                val packaged = archive.entries().asSequence()
                    .filter { it.name == "$dotnetAnalyzerPath/$name" }
                    .toList()
                check(packaged.size == 1) {
                    "Plugin distribution must contain exactly one $dotnetAnalyzerPath/$name"
                }
                val bytes = archive.getInputStream(packaged.single()).use { it.readBytes() }
                check(bytes.contentEquals(dotnetAnalyzer.file(name).asFile.readBytes())) {
                    "Packaged .NET analyzer must match the core module source"
                }
            }

            val packagedMavenAgents = archive.entries().asSequence().filter { it.name == mavenAgentPath }.toList()
            check(packagedMavenAgents.size == 1) {
                "Plugin distribution must contain exactly one $mavenAgentPath"
            }
            val packagedMavenAgent = archive.getInputStream(packagedMavenAgents.single()).use { it.readBytes() }
            check(packagedMavenAgent.contentEquals(mavenAgentArchive.get().readBytes())) {
                "Packaged Maven agent must match the collector module JAR"
            }
            JarInputStream(ByteArrayInputStream(packagedMavenAgent)).use { agent ->
                check(agent.manifest?.mainAttributes?.getValue("Premain-Class") == collectorPremain) {
                    "Packaged Maven agent must declare $collectorPremain as Premain-Class"
                }
                var listener: String? = null
                var filter: String? = null
                var classes = 0
                var asmLicenses = 0
                while (true) {
                    val entry = agent.nextJarEntry ?: break
                    val bytes = agent.readBytes()
                    when (entry.name) {
                        collectorService -> listener = bytes.toString(Charsets.UTF_8).trim()
                        mavenFilterService -> filter = bytes.toString(Charsets.UTF_8).trim()
                        asmLicense -> asmLicenses++
                    }
                    if (entry.name.endsWith(".class")) {
                        check(bytes.size >= 8 && ((bytes[6].toInt() and 0xff) shl 8 or (bytes[7].toInt() and 0xff)) <= 52) {
                            "Maven agent classes must target Java 8: ${entry.name}"
                        }
                        classes++
                    }
                }
                check(listener == collectorListener && filter == mavenFilter && asmLicenses == 1 && classes > 0) {
                    "Packaged Maven agent must contain the exact JUnit services and one ASM license"
                }
            }

            val packagedMavenExtensions = archive.entries().asSequence().filter { it.name == mavenExtensionPath }.toList()
            check(packagedMavenExtensions.size == 1) {
                "Plugin distribution must contain exactly one $mavenExtensionPath"
            }
            val packagedMavenExtension = archive.getInputStream(packagedMavenExtensions.single()).use { it.readBytes() }
            check(packagedMavenExtension.contentEquals(mavenExtensionArchive.get().readBytes())) {
                "Packaged Maven extension must match the collector module JAR"
            }
            JarInputStream(ByteArrayInputStream(packagedMavenExtension)).use { extension ->
                var components = 0
                var classes = 0
                while (true) {
                    val entry = extension.nextJarEntry ?: break
                    val bytes = extension.readBytes()
                    if (entry.name == mavenComponentService) components++
                    if (entry.name.endsWith(".class")) {
                        check(bytes.size >= 8 && ((bytes[6].toInt() and 0xff) shl 8 or (bytes[7].toInt() and 0xff)) == 52) {
                            "Maven extension classes must target Java 8: ${entry.name}"
                        }
                        classes++
                    }
                }
                check(components == 1 && classes > 0) {
                    "Packaged Maven extension must contain one Plexus component descriptor"
                }
            }
        }
    }
}

pitest {
    targetClasses.set(listOf("com.aspix2k.affected.*"))
    targetTests.set(listOf("com.aspix2k.affected.*"))
    excludedClasses.set(
        listOf(
            "com.aspix2k.affected.ModuleGraph*",
            "com.aspix2k.affected.*Action*",
            "com.aspix2k.affected.*Listener*",
            "com.aspix2k.affected.AffectedState*",
            "com.aspix2k.affected.StartupRefresh*",
            "com.aspix2k.affected.AffectedSettings*",
        )
    )
    mutators.set(listOf("STRONGER"))
    outputFormats.set(listOf("XML", "HTML"))
    threads.set(4)
    timestampedReports.set(false)
}

tasks.named<PitestTask>("pitest") {
    additionalClasspath.from(configurations.named("intellijPlatformTestClasspath"))
}

changelog {
    path = "docs/CHANGELOG.md"
    repositoryUrl = "https://github.com/aspix2k/affected"
}

tasks.register("printVersion") {
    val current = project.version.toString()
    doLast { println(current) }
}

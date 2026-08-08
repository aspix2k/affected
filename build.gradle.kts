import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import java.util.Properties

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
    id("info.solidsoft.pitest") version "1.19.0"
    id("org.jetbrains.changelog") version "2.4.0"
}

group = "com.aspix2k"
version = "1.0.0"

repositories {
    mavenCentral()
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
            androidStudio(providers.gradleProperty("affected.studio.version"))
        }
        bundledPlugin("com.intellij.gradle")
        plugin("com.intellij.mcpServer", providers.gradleProperty("affected.mcp.version").get())
    }

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

kotlin { jvmToolchain(21) }

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
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
        failureLevel = listOf(FailureLevel.INVALID_PLUGIN)
    }
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
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

changelog {
    repositoryUrl = "https://github.com/aspix2k/affected"
}

tasks.register("printVersion") {
    val current = project.version.toString()
    doLast { println(current) }
}

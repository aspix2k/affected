import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
    id("info.solidsoft.pitest") version "1.19.0"
    id("org.jetbrains.changelog") version "2.5.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

group = "com.aspix2k"
version = "1.7.1"

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
            intellijIdea(providers.gradleProperty("affected.idea.version").get())
        }
        bundledModule("intellij.platform.vcs.dvcs.impl")
        pluginComposedModule(implementation(project(":core")))
        pluginModule(implementation(project(":mcp")))
    }

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
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
        failureLevel = listOf(FailureLevel.INVALID_PLUGIN)
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2025.3")
            create(IntelliJPlatformType.IntellijIdea, "2026.2")
            create(IntelliJPlatformType.AndroidStudio, providers.gradleProperty("affected.studio.version"))
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

dependencies {
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
}

val pluginLicense = layout.projectDirectory.file("LICENSE")
val pluginDirectory = project.name

tasks.named<BuildPluginTask>("buildPlugin") {
    from(pluginLicense)

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

changelog {
    repositoryUrl = "https://github.com/aspix2k/affected"
}

tasks.register("printVersion") {
    val current = project.version.toString()
    doLast { println(current) }
}

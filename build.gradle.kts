import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.18.1"
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
    id("info.solidsoft.pitest") version "1.19.0"
}

group = "com.aspix2k"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        local("/Users/aspix/Applications/Android Studio.app")
        bundledPlugin("com.intellij.gradle")
        plugin("com.intellij.mcpServer:261.26222.30")
    }
}

kotlin { jvmToolchain(21) }

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
            untilBuild = provider { null }
        }
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
}

pitest {
    targetClasses.set(listOf("com.aspix2k.affected.*"))
    targetTests.set(listOf("com.aspix2k.affected.*"))
    excludedClasses.set(listOf("com.aspix2k.affected.ModuleGraph*", "com.aspix2k.affected.*Action*", "com.aspix2k.affected.*Listener*", "com.aspix2k.affected.AffectedState*", "com.aspix2k.affected.StartupRefresh*", "com.aspix2k.affected.AffectedSettings*"))
    mutators.set(listOf("STRONGER"))
    outputFormats.set(listOf("XML", "HTML"))
    threads.set(4)
    timestampedReports.set(false)
}

tasks.register("printVersion") {
    val current = project.version.toString()
    doLast { println(current) }
}

intellijPlatform {
    publishing {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
    }
}

intellijPlatform {
    pluginVerification {
        // MCP-классов нет в IDE без плагина MCP Server, и это штатное поведение
        // необязательной зависимости: наш код там просто не загружается.
        failureLevel.set(listOf(FailureLevel.INVALID_PLUGIN))
    }
}

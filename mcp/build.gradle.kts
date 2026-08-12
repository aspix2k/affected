import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform.module")
}

repositories {
    maven("https://cache-redirector.jetbrains.com/repo1.maven.org/maven2")
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("affected.idea.version").get())
        plugin("com.intellij.mcpServer", providers.gradleProperty("affected.mcp.version").get())
        testFramework(TestFrameworkType.Platform)
    }
    implementation(project(":core"))
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

kotlin { jvmToolchain(21) }

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
}

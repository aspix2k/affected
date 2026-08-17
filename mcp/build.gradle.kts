import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform.module")
}

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

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("affected.idea.version").get())
        plugin("com.intellij.mcpServer", providers.gradleProperty("affected.mcp.version").get())
        testFramework(TestFrameworkType.Platform)
    }
    add("intellijPlatformDependencies", enforcedPlatform("com.fasterxml.jackson:jackson-bom:2.22.2"))
    add("intellijPlatformTestDependencies", enforcedPlatform("com.fasterxml.jackson:jackson-bom:2.22.2"))
    implementation(project(":core"))
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

kotlin { jvmToolchain(21) }

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
}

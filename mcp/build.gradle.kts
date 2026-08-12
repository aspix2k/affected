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
    }
    implementation(project(":core"))
}

kotlin { jvmToolchain(21) }

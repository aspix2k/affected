plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform.module")
}

repositories {
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

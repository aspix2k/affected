pluginManagement {
    plugins {
        kotlin("jvm") version providers.gradleProperty("affected.kotlin.version").get()
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "affected"

include(":core")
include(":collector")
include(":mcp")

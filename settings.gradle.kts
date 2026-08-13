pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "affected"

include(":core")
include(":collector")
include(":mcp")

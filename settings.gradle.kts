pluginManagement {
    repositories {
        maven("https://cache-redirector.jetbrains.com/plugins.gradle.org")
        gradlePluginPortal()
        maven("https://cache-redirector.jetbrains.com/repo1.maven.org/maven2")
        mavenCentral()
    }
}

rootProject.name = "affected"

include(":core")
include(":collector")
include(":mcp")

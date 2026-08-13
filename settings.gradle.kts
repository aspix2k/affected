pluginManagement {
    repositories {
        maven("https://cache-redirector.jetbrains.com/plugins.gradle.org")
        gradlePluginPortal()
        val mavenCentralMirror = "https://cache-redirector.jetbrains.com/repo1.maven.org/maven2"
        if (System.getenv("AFFECTED_PREFER_MAVEN_CENTRAL") == "1") {
            mavenCentral()
            maven(mavenCentralMirror)
        } else {
            maven(mavenCentralMirror)
            mavenCentral()
        }
    }
}

rootProject.name = "affected"

include(":core")
include(":collector")
include(":mcp")

import java.nio.file.Files
import java.time.Duration
import java.time.Instant

tasks.register("waitForRelease") {
    doLast {
        val markers = layout.projectDirectory.dir("markers").asFile
        markers.mkdirs()
        Files.writeString(markers.toPath().resolve("started.marker"), "started")
        val deadline = Instant.now().plus(Duration.ofMinutes(2))
        while (!markers.toPath().resolve("release.marker").toFile().isFile) {
            check(Instant.now().isBefore(deadline)) { "release marker was not created" }
            Thread.sleep(50)
        }
        Files.writeString(markers.toPath().resolve("completed.marker"), "completed")
    }
}

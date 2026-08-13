import kotlin.test.Test
import kotlin.test.assertEquals
import java.nio.file.Files
import java.nio.file.Path

class AppTest {
    @Test
    fun reportsName() {
        assertEquals("app", App().name())
        Files.writeString(Path.of("affected-app.marker"), "app")
    }
}

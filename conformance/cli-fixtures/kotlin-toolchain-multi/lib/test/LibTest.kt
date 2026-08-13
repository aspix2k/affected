import kotlin.test.Test
import kotlin.test.assertEquals
import java.nio.file.Files
import java.nio.file.Path

class LibTest {
    @Test
    fun reportsName() {
        assertEquals("lib", Lib().name())
        Files.writeString(Path.of("affected-lib.marker"), "lib")
    }
}

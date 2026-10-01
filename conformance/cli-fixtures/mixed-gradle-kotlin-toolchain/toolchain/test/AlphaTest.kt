import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AlphaTest {
    @Test
    fun reportsName() {
        assertEquals("alpha", Alpha().name())
        File("mixed-kotlin.marker").writeText("executed\n")
    }
}

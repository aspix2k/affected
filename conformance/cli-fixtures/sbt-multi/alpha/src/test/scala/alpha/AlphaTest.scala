package alpha

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Test

class AlphaTest {
  @Test
  def value(): Unit = {
    Files.writeString(Path.of("affected-alpha.marker"), "alpha")
    assertEquals(1, new Alpha().value)
  }
}

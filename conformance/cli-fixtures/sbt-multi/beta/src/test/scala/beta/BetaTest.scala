package beta

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Test

class BetaTest {
  @Test
  def value(): Unit = {
    Files.writeString(Path.of("affected-beta.marker"), "beta")
    assertEquals(2, new Beta().value)
  }
}

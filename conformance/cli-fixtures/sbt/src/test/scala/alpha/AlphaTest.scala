package alpha

import org.junit.Assert.assertEquals
import org.junit.Test

class AlphaTest {
  @Test
  def value(): Unit = {
    assertEquals(1, new Alpha().value)
  }
}

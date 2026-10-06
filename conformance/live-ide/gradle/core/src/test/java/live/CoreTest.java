package live;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CoreTest {
    @Test
    void value() {
        assertEquals(1, Core.value());
    }
}

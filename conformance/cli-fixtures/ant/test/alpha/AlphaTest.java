package alpha;

import java.nio.file.Files;
import java.nio.file.Path;

public final class AlphaTest {
    public static void main(String[] args) throws Exception {
        if (!"alpha".equals(new Alpha().name())) {
            throw new AssertionError("alpha");
        }
        Files.writeString(Path.of("affected-alpha.marker"), "alpha");
    }
}

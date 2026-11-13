package cn.xinkelink.seed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalSeedRunnerTest {
    @TempDir Path directory;
    @Test void rejectsUnknownModeAndNonLocalEnvironment() {
        assertThrows(IllegalArgumentException.class, () -> LocalSeedRunner.parse(new String[]{"--clear"}));
        assertThrows(IllegalArgumentException.class, () -> LocalSeedRunner.checkEnvironment("production"));
        assertThrows(IllegalArgumentException.class, () -> LocalSeedRunner.checkEnvironment(null));
        assertDoesNotThrow(() -> LocalSeedRunner.checkEnvironment("local"));
        assertDoesNotThrow(() -> LocalSeedRunner.checkEnvironment("test"));
    }
    @Test void planWritesRequestedCountsWithoutDatabaseOrCredentials() throws Exception {
        Path report = directory.resolve("plan.json");
        LocalSeedRunner.main(new String[]{"--plan", "--images", directory.resolve("images").toString(), "--report", report.toString()});
        String json = Files.readString(report);
        assertTrue(json.contains("\"users\":4"));
        assertTrue(json.contains("\"posts\":4"));
        assertTrue(json.contains("\"committed\":false"));
        assertFalse(Files.exists(directory.resolve("images")));
    }
}

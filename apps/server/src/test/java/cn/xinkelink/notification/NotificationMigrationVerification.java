package cn.xinkelink.notification;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit migration entry for the owned isolated schema; caller takes an authenticated backup first. */
class NotificationMigrationVerification {
    @Test void migrateOwnedIsolatedSchema(){String url=System.getenv("XINKELINK_DB_URL");
        assertNotNull(url);assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:13306/xinkelink_cache_[a-zA-Z0-9_]+_test(?:\\?.*)?"));
        var flyway=Flyway.configure().dataSource(url,System.getenv("XINKELINK_MIGRATION_USERNAME"),System.getenv("XINKELINK_MIGRATION_PASSWORD"))
                .locations("classpath:db/migration").cleanDisabled(true).load();
        flyway.migrate();flyway.validate();assertEquals("12",flyway.info().current().getVersion().getVersion());
        assertEquals(0,flyway.migrate().migrationsExecuted);
    }
}

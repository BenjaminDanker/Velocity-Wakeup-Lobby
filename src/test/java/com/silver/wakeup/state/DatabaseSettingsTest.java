package com.silver.wakeup.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseSettingsTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsPrivateEnvironmentFile() throws Exception {
        Path file = tempDir.resolve("database.env");
        Files.writeString(file, """
                # WakeUpLobby database
                WAKEUP_DB_JDBC_URL=jdbc:mariadb://localhost:3306/minecraft
                WAKEUP_DB_USER=wakeuplobby
                WAKEUP_DB_PASSWORD='secret value'
                """);

        DatabaseSettings settings = DatabaseSettings.load(file, Map.of());

        assertEquals("jdbc:mariadb://localhost:3306/minecraft", settings.jdbcUrl());
        assertEquals("wakeuplobby", settings.user());
        assertEquals("secret value", settings.password());
    }

    @Test
    void processEnvironmentOverridesFile() throws Exception {
        Path file = tempDir.resolve("database.env");
        Files.writeString(file, """
                WAKEUP_DB_JDBC_URL=jdbc:mariadb://old/minecraft
                WAKEUP_DB_USER=old
                WAKEUP_DB_PASSWORD=old
                """);

        DatabaseSettings settings = DatabaseSettings.load(file, Map.of(
                "WAKEUP_DB_JDBC_URL", "jdbc:mariadb://new/minecraft",
                "WAKEUP_DB_USER", "new-user",
                "WAKEUP_DB_PASSWORD", "new-password"));

        assertEquals("jdbc:mariadb://new/minecraft", settings.jdbcUrl());
        assertEquals("new-user", settings.user());
        assertEquals("new-password", settings.password());
    }
}

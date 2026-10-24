package com.silver.wakeup.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Runs against a scratch database named by WAKEUP_TEST_DB_ENV_FILE (same format as database.env); skipped otherwise. */
class VisitRefreshIT {
    @Test
    void refreshSeesVisitsChangedByAnotherProcess() throws Exception {
        String file = System.getenv("WAKEUP_TEST_DB_ENV_FILE");
        assumeTrue(file != null && Files.exists(Path.of(file)), "no scratch database configured");
        DatabaseSettings settings = DatabaseSettings.load(Path.of(file), java.util.Map.of());
        MariaDbRoutingRepository repository = new MariaDbRoutingRepository(settings);
        repository.migrate();
        RoutingStateService service = new RoutingStateService(repository, LoggerFactory.getLogger("test"), Clock.systemUTC(), Duration.ofHours(1));
        UUID player = UUID.randomUUID();
        service.recordSuccessfulConnection(player, "magic", true).join();
        assertEquals(Set.of("magic"), service.refreshVisitedServers(player));
        try (Connection connection = DriverManager.getConnection(settings.jdbcUrl(), settings.user(), settings.password());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM wakeup_player_visits WHERE player_uuid='" + player + "' AND server_name='magic'");
            statement.executeUpdate("INSERT INTO wakeup_player_visits(player_uuid, server_name) VALUES ('" + player + "','tech')");
        }
        assertEquals(Set.of("tech"), service.refreshVisitedServers(player), "refresh must see the other process's change");
        assertEquals(Set.of("tech"), service.visitedServers(player));
        repository.close();
    }

    @Test
    void completionFlagsComeFromTheMpdsPlayerTable() throws Exception {
        String file = System.getenv("WAKEUP_TEST_DB_ENV_FILE");
        assumeTrue(file != null && Files.exists(Path.of(file)), "no scratch database configured");
        DatabaseSettings settings = DatabaseSettings.load(Path.of(file), java.util.Map.of());
        MariaDbRoutingRepository repository = new MariaDbRoutingRepository(settings);
        repository.migrate();
        RoutingStateService service = new RoutingStateService(repository, LoggerFactory.getLogger("test"), Clock.systemUTC(), Duration.ofHours(1));
        UUID player = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(settings.jdbcUrl(), settings.user(), settings.password());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE player ADD COLUMN IF NOT EXISTS MagicCompleted boolean NOT NULL DEFAULT false");
            statement.execute("ALTER TABLE player ADD COLUMN IF NOT EXISTS TechCompleted boolean NOT NULL DEFAULT false");
            assertEquals(Set.of(), service.completedBranches(player), "unknown player");
            statement.executeUpdate("INSERT INTO player(Name, uuid, TechCompleted) VALUES ('IT" + player.toString().substring(0, 8) + "','" + player + "', TRUE)");
            assertEquals(Set.of("tech"), service.completedBranches(player));
            statement.executeUpdate("UPDATE player SET MagicCompleted = TRUE, TechCompleted = FALSE WHERE uuid='" + player + "'");
            assertEquals(Set.of("magic"), service.completedBranches(player));
            statement.executeUpdate("DELETE FROM player WHERE uuid='" + player + "'");
        }
        repository.close();
    }
}

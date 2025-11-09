package com.silver.wakeup.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.helpers.NOPLogger;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PlayerStateStore")
class PlayerStateStoreTest {

    private Path tempDir;
    private final Logger logger = NOPLogger.NOP_LOGGER;

    @BeforeEach
    void setup(@TempDir Path dir) {
        tempDir = dir;
    }

    @Test
    @DisplayName("loadLastServers returns empty map when file does not exist")
    void loadLastServersEmptyWhenMissing() {
        PlayerStateStore store = new PlayerStateStore(tempDir, logger);
        Map<UUID, String> lastServers = store.loadLastServers();
        
        assertTrue(lastServers.isEmpty());
    }

    @Test
    @DisplayName("loadVisitedServers returns empty map when file does not exist")
    void loadVisitedServersEmptyWhenMissing() {
        PlayerStateStore store = new PlayerStateStore(tempDir, logger);
        Map<UUID, Set<String>> visited = store.loadVisitedServers();
        
        assertTrue(visited.isEmpty());
    }

    @Test
    @DisplayName("saveLastServers persists data")
    void saveLastServersPersists() {
        PlayerStateStore store1 = new PlayerStateStore(tempDir, logger);
        UUID playerId = UUID.randomUUID();
        Map<UUID, String> data = Map.of(playerId, "server1");
        
        store1.saveLastServers(data);
        
        // Create new store instance to verify persistence
        PlayerStateStore store2 = new PlayerStateStore(tempDir, logger);
        Map<UUID, String> loaded = store2.loadLastServers();
        
        assertEquals(1, loaded.size());
        assertEquals("server1", loaded.get(playerId));
    }

    @Test
    @DisplayName("saveVisitedServers persists data")
    void saveVisitedServersPersists() {
        PlayerStateStore store1 = new PlayerStateStore(tempDir, logger);
        UUID playerId = UUID.randomUUID();
        Map<UUID, Set<String>> data = Map.of(playerId, Set.of("server1", "server2"));
        
        store1.saveVisitedServers(data);
        
        // Create new store instance to verify persistence
        PlayerStateStore store2 = new PlayerStateStore(tempDir, logger);
        Map<UUID, Set<String>> loaded = store2.loadVisitedServers();
        
        assertEquals(1, loaded.size());
        assertTrue(loaded.get(playerId).contains("server1"));
        assertTrue(loaded.get(playerId).contains("server2"));
    }

    @Test
    @DisplayName("saveLastServers overwrites previous data")
    void saveLastServersOverwrites() {
        PlayerStateStore store = new PlayerStateStore(tempDir, logger);
        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();
        
        store.saveLastServers(Map.of(player1, "server1"));
        Map<UUID, String> loaded1 = store.loadLastServers();
        assertEquals(1, loaded1.size());
        
        store.saveLastServers(Map.of(player2, "server2"));
        Map<UUID, String> loaded2 = store.loadLastServers();
        assertEquals(1, loaded2.size());
        assertEquals("server2", loaded2.get(player2));
        assertNull(loaded2.get(player1));
    }

    @Test
    @DisplayName("saveLastServers with empty map clears data")
    void saveLastServersEmpty() {
        PlayerStateStore store = new PlayerStateStore(tempDir, logger);
        UUID playerId = UUID.randomUUID();
        
        store.saveLastServers(Map.of(playerId, "server1"));
        assertTrue(store.loadLastServers().containsKey(playerId));
        
        store.saveLastServers(Map.of());
        assertTrue(store.loadLastServers().isEmpty());
    }

    @Test
    @DisplayName("saveVisitedServers with empty map clears data")
    void saveVisitedServersEmpty() {
        PlayerStateStore store = new PlayerStateStore(tempDir, logger);
        UUID playerId = UUID.randomUUID();
        
        store.saveVisitedServers(Map.of(playerId, Set.of("server1")));
        assertTrue(store.loadVisitedServers().containsKey(playerId));
        
        store.saveVisitedServers(Map.of());
        assertTrue(store.loadVisitedServers().isEmpty());
    }

    @Test
    @DisplayName("multiple players' last servers persist correctly")
    void multiplePlayersLastServers() {
        PlayerStateStore store = new PlayerStateStore(tempDir, logger);
        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();
        UUID player3 = UUID.randomUUID();
        
        Map<UUID, String> data = Map.of(
                player1, "server1",
                player2, "server2",
                player3, "server3"
        );
        store.saveLastServers(data);
        
        Map<UUID, String> loaded = store.loadLastServers();
        assertEquals(3, loaded.size());
        assertEquals("server1", loaded.get(player1));
        assertEquals("server2", loaded.get(player2));
        assertEquals("server3", loaded.get(player3));
    }

    @Test
    @DisplayName("multiple players' visited servers persist correctly")
    void multiplePlayersVisited() {
        PlayerStateStore store = new PlayerStateStore(tempDir, logger);
        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();
        
        Map<UUID, Set<String>> data = Map.of(
                player1, Set.of("server1", "server2"),
                player2, Set.of("serverA", "serverB", "serverC")
        );
        store.saveVisitedServers(data);
        
        Map<UUID, Set<String>> loaded = store.loadVisitedServers();
        assertEquals(2, loaded.size());
        assertEquals(Set.of("server1", "server2"), loaded.get(player1));
        assertEquals(Set.of("serverA", "serverB", "serverC"), loaded.get(player2));
    }

    @Test
    @DisplayName("lastServerPath and visitedPath are different")
    void differentPaths() {
        PlayerStateStore store = new PlayerStateStore(tempDir, logger);
        UUID playerId = UUID.randomUUID();
        
        store.saveLastServers(Map.of(playerId, "server1"));
        store.saveVisitedServers(Map.of(playerId, Set.of("visited1")));
        
        // Both should be stored successfully in separate files
        Map<UUID, String> lastServers = store.loadLastServers();
        Map<UUID, Set<String>> visited = store.loadVisitedServers();
        
        assertEquals("server1", lastServers.get(playerId));
        assertTrue(visited.get(playerId).contains("visited1"));
    }

    @Test
    @DisplayName("loadLastServers ignores malformed UUID entries")
    void loadLastServersIgnoresMalformed() {
        PlayerStateStore store = new PlayerStateStore(tempDir, logger);
        // First save valid data
        UUID validId = UUID.randomUUID();
        store.saveLastServers(Map.of(validId, "server1"));
        
        // Then try to load - should only get valid entries
        Map<UUID, String> loaded = store.loadLastServers();
        assertEquals(1, loaded.size());
        assertTrue(loaded.containsKey(validId));
    }

    @Test
    @DisplayName("saveLastServers handles empty set correctly")
    void saveLastServersEmptySet() {
        PlayerStateStore store = new PlayerStateStore(tempDir, logger);
        store.saveLastServers(Map.of());
        
        Map<UUID, String> loaded = store.loadLastServers();
        assertTrue(loaded.isEmpty());
    }

    @Test
    @DisplayName("saveVisitedServers handles empty set correctly")
    void saveVisitedServersEmptySet() {
        PlayerStateStore store = new PlayerStateStore(tempDir, logger);
        store.saveVisitedServers(Map.of());
        
        Map<UUID, Set<String>> loaded = store.loadVisitedServers();
        assertTrue(loaded.isEmpty());
    }

    @Test
    @DisplayName("server names with special characters are preserved")
    void specialCharactersInServerNames() {
        PlayerStateStore store = new PlayerStateStore(tempDir, logger);
        UUID playerId = UUID.randomUUID();
        
        store.saveLastServers(Map.of(playerId, "server-1_test.sub"));
        store.saveVisitedServers(Map.of(playerId, Set.of("srv@1", "srv#2", "srv$3")));
        
        Map<UUID, String> lastServers = store.loadLastServers();
        Map<UUID, Set<String>> visited = store.loadVisitedServers();
        
        assertEquals("server-1_test.sub", lastServers.get(playerId));
        assertTrue(visited.get(playerId).contains("srv@1"));
    }

    @Test
    @DisplayName("purgeHoldingFromLastServers removes holding server")
    void purgeHoldingFromLastServers() {
        PlayerStateStore store = new PlayerStateStore(tempDir, logger);
        UUID playerId = UUID.randomUUID();
        Map<UUID, String> data = new java.util.HashMap<>();
        data.put(playerId, "holding_lobby");
        
        boolean changed = store.purgeHoldingFromLastServers(data, "holding_lobby");
        
        assertTrue(changed);
        assertTrue(data.isEmpty());
    }

    @Test
    @DisplayName("purgeHoldingFromLastServers ignores other servers")
    void purgeHoldingFromLastServersIgnoresOthers() {
        PlayerStateStore store = new PlayerStateStore(tempDir, logger);
        UUID playerId = UUID.randomUUID();
        Map<UUID, String> data = new java.util.HashMap<>();
        data.put(playerId, "regular_server");
        
        boolean changed = store.purgeHoldingFromLastServers(data, "holding_lobby");
        
        assertFalse(changed);
        assertEquals("regular_server", data.get(playerId));
    }

    @Test
    @DisplayName("purgeHoldingFromVisited removes holding servers")
    void purgeHoldingFromVisited() {
        PlayerStateStore store = new PlayerStateStore(tempDir, logger);
        UUID playerId = UUID.randomUUID();
        Map<UUID, Set<String>> data = new java.util.HashMap<>();
        data.put(playerId, new java.util.HashSet<>(Set.of("holding_lobby", "server1", "server2")));
        
        boolean changed = store.purgeHoldingFromVisited(data, "holding_lobby");
        
        assertTrue(changed);
        assertTrue(data.get(playerId).contains("server1"));
        assertTrue(data.get(playerId).contains("server2"));
        assertFalse(data.get(playerId).contains("holding_lobby"));
    }

}

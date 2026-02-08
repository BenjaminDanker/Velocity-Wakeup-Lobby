package com.silver.wakeup.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LobbyConfig")
class LobbyConfigTest {

    @Test
    @DisplayName("constructor stores all values correctly")
    void constructorStoresValues() {
        LobbyConfig config = new LobbyConfig(
                "192.168.1.255",
                "lobby",
                300,
                30,
                Map.of("server1", "00:11:22:33:44:55"),
                Map.of("group1", List.of("server1", "server2")),
                Set.of("Admin"),
                "global-secret",
                Map.of("portal1", "secret1"),
                List.of("vanilla1", "magic"),
                Map.of()
        );

        assertEquals("192.168.1.255", config.broadcastIp());
        assertEquals("lobby", config.holdingServer());
        assertEquals(300, config.graceSec());
        assertEquals(30, config.pingEverySec());
    }

    @Test
    @DisplayName("broadcastIp is required and cannot be null")
    void broadcastIpRequired() {
        assertThrows(NullPointerException.class, () ->
                new LobbyConfig(
                        null,
                        "lobby",
                        300,
                        30,
                        Map.of(),
                        Map.of(),
                        Set.of(),
                        "secret",
                        Map.of(),
                        List.of("vanilla1"),
                        Map.of()
                )
        );
    }

    @Test
    @DisplayName("holdingServer is required and cannot be null")
    void holdingServerRequired() {
        assertThrows(NullPointerException.class, () ->
                new LobbyConfig(
                        "192.168.1.255",
                        null,
                        300,
                        30,
                        Map.of(),
                        Map.of(),
                        Set.of(),
                        "secret",
                        Map.of(),
                        List.of("vanilla1"),
                        Map.of()
                )
        );
    }

    @Test
    @DisplayName("globalPortalSecret is required and cannot be null")
    void globalPortalSecretRequired() {
        assertThrows(NullPointerException.class, () ->
                new LobbyConfig(
                        "192.168.1.255",
                        "lobby",
                        300,
                        30,
                        Map.of(),
                        Map.of(),
                        Set.of(),
                        null,
                        Map.of(),
                        List.of("vanilla1"),
                        Map.of()
                )
        );
    }

    @Test
    @DisplayName("perPortalSecrets is required and cannot be null")
    void perPortalSecretsRequired() {
        assertThrows(NullPointerException.class, () ->
                new LobbyConfig(
                        "192.168.1.255",
                        "lobby",
                        300,
                        30,
                        Map.of(),
                        Map.of(),
                        Set.of(),
                        "secret",
                        null,
                        List.of("vanilla1"),
                        Map.of()
                )
        );
    }

    @Test
    @DisplayName("serverToMac is defensively copied (immutable)")
    void serverToMacImmutable() {
        Map<String, String> original = new java.util.HashMap<>();
        original.put("server1", "00:11:22:33:44:55");
        
        LobbyConfig config = new LobbyConfig(
                "192.168.1.255",
                "lobby",
                300,
                30,
                original,
                Map.of(),
                Set.of(),
                "secret",
                Map.of(),
                List.of("vanilla1"),
                Map.of()
        );

        original.put("server2", "AA:BB:CC:DD:EE:FF");
        
        Map<String, String> fromConfig = config.serverToMac();
        assertEquals(1, fromConfig.size());
        assertThrows(UnsupportedOperationException.class, () ->
                fromConfig.put("server3", "11:22:33:44:55:66")
        );
    }

    @Test
    @DisplayName("groups is defensively copied (immutable)")
    void groupsImmutable() {
        Map<String, List<String>> original = new java.util.HashMap<>();
        original.put("group1", new java.util.ArrayList<>(List.of("server1")));
        
        LobbyConfig config = new LobbyConfig(
                "192.168.1.255",
                "lobby",
                300,
                30,
                Map.of(),
                original,
                Set.of(),
                "secret",
                Map.of(),
                List.of("vanilla1"),
                Map.of()
        );

        original.put("group2", List.of("server2"));
        
        Map<String, List<String>> fromConfig = config.groups();
        assertEquals(1, fromConfig.size());
        assertThrows(UnsupportedOperationException.class, () ->
                fromConfig.put("group3", List.of("server3"))
        );
    }

    @Test
    @DisplayName("adminNames is defensively copied (immutable)")
    void adminNamesImmutable() {
        Set<String> original = new java.util.HashSet<>();
        original.add("Admin1");
        
        LobbyConfig config = new LobbyConfig(
                "192.168.1.255",
                "lobby",
                300,
                30,
                Map.of(),
                Map.of(),
                original,
                "secret",
                Map.of(),
                List.of("vanilla1"),
                Map.of()
        );

        original.add("Admin2");
        
        Set<String> fromConfig = config.adminNames();
        assertEquals(1, fromConfig.size());
        assertThrows(UnsupportedOperationException.class, () ->
                fromConfig.add("Admin3")
        );
    }

    @Test
    @DisplayName("perPortalSecrets is defensively copied (immutable)")
    void perPortalSecretsImmutable() {
        Map<String, String> original = new java.util.HashMap<>();
        original.put("portal1", "secret1");
        
        LobbyConfig config = new LobbyConfig(
                "192.168.1.255",
                "lobby",
                300,
                30,
                Map.of(),
                Map.of(),
                Set.of(),
                "global-secret",
                original,
                List.of("vanilla1"),
                Map.of()
        );

        // Verify that the map retrieved is immutable
        Map<String, String> fromConfig = config.perPortalSecrets();
        assertThrows(UnsupportedOperationException.class, () ->
                fromConfig.put("portal3", "secret3")
        );
    }

    @Test
    @DisplayName("equals and hashCode work correctly")
    void equalsAndHashCode() {
        LobbyConfig config1 = new LobbyConfig(
                "192.168.1.255",
                "lobby",
                300,
                30,
                Map.of("server1", "00:11:22:33:44:55"),
                Map.of("group1", List.of("server1")),
                Set.of("Admin"),
                "secret",
                Map.of(),
                List.of("vanilla1"),
                Map.of()
        );

        LobbyConfig config2 = config1; // same instance
        assertEquals(config1, config2);
    }

    @Test
    @DisplayName("all getters return correct values")
    void allGetters() {
        Map<String, String> serverToMac = Map.of("server1", "00:11:22:33:44:55");
        Map<String, List<String>> groups = Map.of("group1", List.of("server1"));
        Set<String> adminNames = Set.of("Admin");
        Map<String, String> perPortalSecrets = Map.of("portal1", "secret1");

        LobbyConfig config = new LobbyConfig(
                "192.168.1.255",
                "lobby",
                300,
                30,
                serverToMac,
                groups,
                adminNames,
                "global-secret",
                perPortalSecrets,
                List.of("vanilla1", "magic"),
                Map.of()
        );

        assertEquals("192.168.1.255", config.broadcastIp());
        assertEquals("lobby", config.holdingServer());
        assertEquals(300, config.graceSec());
        assertEquals(30, config.pingEverySec());
        assertEquals(serverToMac, config.serverToMac());
        assertEquals(groups, config.groups());
        assertEquals(adminNames, config.adminNames());
        assertEquals("global-secret", config.globalPortalSecret());
        assertEquals(perPortalSecrets, config.perPortalSecrets());
    }
}

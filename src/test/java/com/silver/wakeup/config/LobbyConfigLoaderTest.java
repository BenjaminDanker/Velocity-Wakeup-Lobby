package com.silver.wakeup.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LobbyConfigLoaderTest {
    private static final Logger LOG = LoggerFactory.getLogger(LobbyConfigLoaderTest.class);

    @TempDir
    Path tempDir;

    @Test
    void loadThrowsWhenYamlIsInvalid() throws Exception {
        LobbyConfigLoader loader = new LobbyConfigLoader(LOG, tempDir);
        Path cfg = loader.configPath();
        Files.createDirectories(cfg.getParent());

        Files.writeString(cfg, "return_specials:\n  ocean:\n    - key: id\n       value: broken\n", StandardCharsets.UTF_8);

        assertThrows(RuntimeException.class, loader::load);
    }

    @Test
    void ensureDefaultConfigWritesValidYaml() throws Exception {
        LobbyConfigLoader loader = new LobbyConfigLoader(LOG, tempDir);
        loader.ensureDefaultConfig();

        Path cfg = loader.configPath();
        assertTrue(Files.exists(cfg));

        try (var reader = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
            Object parsed = new Yaml().load(reader);
            assertNotNull(parsed);
        }

        // Also ensure our loader can read its own default without throwing.
        assertDoesNotThrow(loader::load);
    }
}

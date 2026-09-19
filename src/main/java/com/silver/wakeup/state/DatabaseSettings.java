package com.silver.wakeup.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Database credentials loaded from a private environment-style file. */
public record DatabaseSettings(String jdbcUrl, String user, String password) {
    private static final String DEFAULT_FILE = ".config/wakeuplobby/database.env";

    public DatabaseSettings {
        if (jdbcUrl == null || jdbcUrl.isBlank()) throw new IllegalArgumentException("WAKEUP_DB_JDBC_URL is required");
        if (user == null || user.isBlank()) throw new IllegalArgumentException("WAKEUP_DB_USER is required");
        Objects.requireNonNull(password, "password");
    }

    public static DatabaseSettings loadDefault() throws IOException {
        String configured = System.getenv("WAKEUP_DB_ENV_FILE");
        Path path = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), DEFAULT_FILE)
                : Path.of(configured);
        return load(path, System.getenv());
    }

    static DatabaseSettings load(Path path, Map<String, String> processEnvironment) throws IOException {
        Map<String, String> values = new HashMap<>();
        if (Files.exists(path)) {
            for (String raw : Files.readAllLines(path)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int equals = line.indexOf('=');
                if (equals <= 0) continue;
                String key = line.substring(0, equals).trim();
                String value = line.substring(equals + 1).trim();
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            }
        }
        values.putAll(processEnvironment);
        return new DatabaseSettings(
                values.get("WAKEUP_DB_JDBC_URL"),
                values.get("WAKEUP_DB_USER"),
                values.getOrDefault("WAKEUP_DB_PASSWORD", ""));
    }
}

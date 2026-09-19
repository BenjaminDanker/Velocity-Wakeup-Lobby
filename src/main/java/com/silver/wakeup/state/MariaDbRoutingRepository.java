package com.silver.wakeup.state;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Transactional MariaDB persistence for routing profiles and portal transfers. */
public final class MariaDbRoutingRepository implements AutoCloseable {
    private final HikariDataSource dataSource;

    public MariaDbRoutingRepository(DatabaseSettings settings) {
        HikariConfig config = new HikariConfig();
        // Shaded Velocity plugins do not reliably preserve JDBC's service-loader
        // metadata, so identify the bundled driver explicitly.
        config.setDriverClassName("org.mariadb.jdbc.Driver");
        config.setJdbcUrl(settings.jdbcUrl());
        config.setUsername(settings.user());
        config.setPassword(settings.password());
        config.setPoolName("WakeUpLobbyRouting");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5_000);
        config.setValidationTimeout(2_000);
        config.setAutoCommit(true);
        this.dataSource = new HikariDataSource(config);
    }

    public void migrate() throws SQLException, IOException {
        String sql;
        try (InputStream input = MariaDbRoutingRepository.class.getResourceAsStream(
                "/db/migration/V001__create_wakeup_routing.sql")) {
            if (input == null) throw new IOException("Missing routing migration resource");
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                for (String statement : sql.split(";")) {
                    if (!statement.isBlank()) {
                        try (Statement query = connection.createStatement()) {
                            query.execute(statement);
                        }
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT IGNORE INTO wakeup_schema_migrations(version, description) VALUES (1, ?)") ) {
                    insert.setString(1, "create WakeUpLobby routing state");
                    insert.executeUpdate();
                }
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public RoutingSnapshot loadSnapshot(Instant now) throws SQLException {
        Map<UUID, String> preferred = new HashMap<>();
        Map<UUID, String> listed = new HashMap<>();
        Map<UUID, Set<String>> visited = new HashMap<>();
        Map<UUID, PortalTransfer> pending = new HashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement expire = connection.prepareStatement(
                    "UPDATE wakeup_portal_transfers SET status='EXPIRED' "
                            + "WHERE status IN ('PENDING','CLAIMED') AND expires_at <= ?")) {
                expire.setTimestamp(1, Timestamp.from(now));
                expire.executeUpdate();
            }
            // CLAIMED is only an in-process connection attempt. If Velocity stopped
            // before completing it, no live attempt remains, so make it resumable.
            try (PreparedStatement recover = connection.prepareStatement(
                    "UPDATE wakeup_portal_transfers SET status='PENDING', claimed_at=NULL "
                            + "WHERE status='CLAIMED' AND expires_at > ?")) {
                recover.setTimestamp(1, Timestamp.from(now));
                recover.executeUpdate();
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT player_uuid, preferred_server, last_listed_server FROM wakeup_player_state")) {
                while (rows.next()) {
                    UUID playerId = UUID.fromString(rows.getString(1));
                    String preferredServer = rows.getString(2);
                    String listedServer = rows.getString(3);
                    if (preferredServer != null && !preferredServer.isBlank()) preferred.put(playerId, preferredServer);
                    if (listedServer != null && !listedServer.isBlank()) listed.put(playerId, listedServer);
                }
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT player_uuid, server_name FROM wakeup_player_visits")) {
                while (rows.next()) {
                    UUID playerId = UUID.fromString(rows.getString(1));
                    visited.computeIfAbsent(playerId, ignored -> new HashSet<>()).add(rows.getString(2));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT transfer_id, player_uuid, source_server, target_server, arrival_portal, "
                            + "status, issued_at, expires_at FROM wakeup_portal_transfers "
                            + "WHERE status='PENDING' AND expires_at > ? ORDER BY issued_at")) {
                statement.setTimestamp(1, Timestamp.from(now));
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        PortalTransfer transfer = readTransfer(rows);
                        pending.put(transfer.playerId(), transfer);
                    }
                }
            }
        }
        return new RoutingSnapshot(Map.copyOf(preferred), Map.copyOf(listed), deepCopy(visited), Map.copyOf(pending));
    }

    public void importLegacy(Map<UUID, String> preferred,
                             Map<UUID, String> listed,
                             Map<UUID, Set<String>> visited) throws SQLException {
        Set<UUID> players = new HashSet<>();
        players.addAll(preferred.keySet());
        players.addAll(listed.keySet());
        players.addAll(visited.keySet());
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement player = connection.prepareStatement(
                        "INSERT INTO wakeup_player_state(player_uuid, preferred_server, last_successful_server, last_listed_server) "
                                + "VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE "
                                + "preferred_server=COALESCE(preferred_server, VALUES(preferred_server)), "
                                + "last_successful_server=COALESCE(last_successful_server, VALUES(last_successful_server)), "
                                + "last_listed_server=COALESCE(last_listed_server, VALUES(last_listed_server))")) {
                    for (UUID playerId : players) {
                        String last = preferred.get(playerId);
                        player.setString(1, playerId.toString());
                        player.setString(2, last);
                        player.setString(3, last);
                        player.setString(4, listed.get(playerId));
                        player.addBatch();
                    }
                    player.executeBatch();
                }
                try (PreparedStatement visit = connection.prepareStatement(
                        "INSERT INTO wakeup_player_visits(player_uuid, server_name) VALUES (?, ?) "
                                + "ON DUPLICATE KEY UPDATE last_visited_at=last_visited_at")) {
                    for (var entry : visited.entrySet()) {
                        for (String server : entry.getValue()) {
                            if (server == null || server.isBlank()) continue;
                            visit.setString(1, entry.getKey().toString());
                            visit.setString(2, server);
                            visit.addBatch();
                        }
                    }
                    visit.executeBatch();
                }
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public void recordSuccessfulConnection(UUID playerId, String server, String listedServer) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement player = connection.prepareStatement(
                        "INSERT INTO wakeup_player_state(player_uuid, preferred_server, last_successful_server, last_listed_server) "
                                + "VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE preferred_server=VALUES(preferred_server), "
                                + "last_successful_server=VALUES(last_successful_server), "
                                + "last_listed_server=COALESCE(VALUES(last_listed_server), last_listed_server)")) {
                    player.setString(1, playerId.toString());
                    player.setString(2, server);
                    player.setString(3, server);
                    player.setString(4, listedServer);
                    player.executeUpdate();
                }
                try (PreparedStatement visit = connection.prepareStatement(
                        "INSERT INTO wakeup_player_visits(player_uuid, server_name) VALUES (?, ?) "
                                + "ON DUPLICATE KEY UPDATE last_visited_at=CURRENT_TIMESTAMP(3)")) {
                    visit.setString(1, playerId.toString());
                    visit.setString(2, server);
                    visit.executeUpdate();
                }
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public void setPreferredServer(UUID playerId, String server) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO wakeup_player_state(player_uuid, preferred_server) VALUES (?, ?) "
                             + "ON DUPLICATE KEY UPDATE preferred_server=VALUES(preferred_server)")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, server);
            statement.executeUpdate();
        }
    }

    public PortalTransfer createTransfer(UUID playerId, String source, String target,
                                         String arrivalPortal, Instant issuedAt, Instant expiresAt) throws SQLException {
        PortalTransfer transfer = new PortalTransfer(UUID.randomUUID(), playerId, source, target,
                arrivalPortal, PortalTransfer.Status.PENDING, issuedAt, expiresAt);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement cancel = connection.prepareStatement(
                        "UPDATE wakeup_portal_transfers SET status='CANCELLED' "
                                + "WHERE player_uuid=? AND status IN ('PENDING','CLAIMED')")) {
                    cancel.setString(1, playerId.toString());
                    cancel.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO wakeup_portal_transfers(transfer_id, player_uuid, source_server, target_server, "
                                + "arrival_portal, status, issued_at, expires_at) VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?)")) {
                    insert.setString(1, transfer.transferId().toString());
                    insert.setString(2, playerId.toString());
                    insert.setString(3, source);
                    insert.setString(4, target);
                    insert.setString(5, transfer.arrivalPortal());
                    insert.setTimestamp(6, Timestamp.from(issuedAt));
                    insert.setTimestamp(7, Timestamp.from(expiresAt));
                    insert.executeUpdate();
                }
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
        return transfer;
    }

    public Optional<PortalTransfer> claimPending(UUID playerId, String target, Instant now) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int changed;
                try (PreparedStatement claim = connection.prepareStatement(
                        "UPDATE wakeup_portal_transfers SET status='CLAIMED', claimed_at=? "
                                + "WHERE player_uuid=? AND target_server=? AND status='PENDING' AND expires_at>?")) {
                    claim.setTimestamp(1, Timestamp.from(now));
                    claim.setString(2, playerId.toString());
                    claim.setString(3, target);
                    claim.setTimestamp(4, Timestamp.from(now));
                    changed = claim.executeUpdate();
                }
                if (changed != 1) {
                    connection.rollback();
                    return Optional.empty();
                }
                PortalTransfer transfer;
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT transfer_id, player_uuid, source_server, target_server, arrival_portal, "
                                + "status, issued_at, expires_at FROM wakeup_portal_transfers "
                                + "WHERE player_uuid=? AND target_server=? AND status='CLAIMED' ORDER BY claimed_at DESC LIMIT 1")) {
                    select.setString(1, playerId.toString());
                    select.setString(2, target);
                    try (ResultSet rows = select.executeQuery()) {
                        if (!rows.next()) throw new SQLException("Claimed portal transfer disappeared");
                        transfer = readTransfer(rows);
                    }
                }
                connection.commit();
                return Optional.of(transfer);
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public void restorePending(UUID transferId) throws SQLException {
        updateTransferStatus(transferId, "PENDING", "CLAIMED");
    }

    public void complete(UUID transferId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE wakeup_portal_transfers SET status='COMPLETED', completed_at=CURRENT_TIMESTAMP(3) "
                             + "WHERE transfer_id=? AND status='CLAIMED'")) {
            statement.setString(1, transferId.toString());
            statement.executeUpdate();
        }
    }

    public void cancelActive(UUID playerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE wakeup_portal_transfers SET status='CANCELLED' "
                             + "WHERE player_uuid=? AND status IN ('PENDING','CLAIMED')")) {
            statement.setString(1, playerId.toString());
            statement.executeUpdate();
        }
    }

    private void updateTransferStatus(UUID transferId, String next, String expected) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE wakeup_portal_transfers SET status=? WHERE transfer_id=? AND status=?")) {
            statement.setString(1, next);
            statement.setString(2, transferId.toString());
            statement.setString(3, expected);
            statement.executeUpdate();
        }
    }

    private static PortalTransfer readTransfer(ResultSet rows) throws SQLException {
        return new PortalTransfer(
                UUID.fromString(rows.getString("transfer_id")),
                UUID.fromString(rows.getString("player_uuid")),
                rows.getString("source_server"),
                rows.getString("target_server"),
                rows.getString("arrival_portal"),
                PortalTransfer.Status.valueOf(rows.getString("status")),
                rows.getTimestamp("issued_at").toInstant(),
                rows.getTimestamp("expires_at").toInstant());
    }

    private static Map<UUID, Set<String>> deepCopy(Map<UUID, Set<String>> input) {
        Map<UUID, Set<String>> result = new HashMap<>();
        input.forEach((key, value) -> result.put(key, Set.copyOf(value)));
        return Map.copyOf(result);
    }

    @Override
    public void close() {
        dataSource.close();
    }
}

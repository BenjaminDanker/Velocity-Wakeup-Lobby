package com.silver.wakeup.plugin;

import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Network-wide bans: a banned player can't reach any server, the waiting lobby included.
 *
 * <p>The list lives in the network database (table {@code net_bans}; the home page's admin side adds and removes rows) and is
 * kept in memory here, read again every few seconds. A login is refused while a ban applies, and anyone already online when a
 * ban appears is disconnected at the next read. A ban matches the player's UUID, or their name when the UUID isn't known
 * (someone who never joined). A ban with an end time stops applying by itself.</p>
 */
public final class NetworkBans {
    public record Ban(String uuid, String name, String reason, Timestamp until) {
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm z", Locale.ENGLISH).withZone(ZoneId.systemDefault());

    private final DataSource dataSource;
    private final ProxyServer proxy;
    private final Logger logger;
    private volatile Map<String, Ban> byUuid = Map.of();
    private volatile Map<String, Ban> byName = Map.of();
    private volatile boolean tableReady;

    public NetworkBans(DataSource dataSource, ProxyServer proxy, Logger logger) {
        this.dataSource = dataSource;
        this.proxy = proxy;
        this.logger = logger;
    }

    private void ensureTable(Connection c) throws SQLException {
        if (tableReady) {
            return;
        }
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS net_bans (id BIGINT AUTO_INCREMENT PRIMARY KEY, uuid CHAR(36) NULL,"
                    + " name VARCHAR(32) NOT NULL, reason VARCHAR(255) NOT NULL DEFAULT '', until DATETIME NULL,"
                    + " banned_by VARCHAR(64) NOT NULL DEFAULT '', banned_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + " INDEX (uuid), INDEX (name))");
        }
        tableReady = true;
    }

    /** Read the list again and disconnect anyone online who is banned now. Safe to run from a scheduler task. */
    public void refresh() {
        Map<String, Ban> uuids = new HashMap<>();
        Map<String, Ban> names = new HashMap<>();
        try (Connection c = dataSource.getConnection()) {
            ensureTable(c);
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT uuid, name, reason, until FROM net_bans WHERE until IS NULL OR until > NOW()")) {
                while (r.next()) {
                    Ban ban = new Ban(r.getString(1), r.getString(2), r.getString(3), r.getTimestamp(4));
                    if (ban.uuid() != null && !ban.uuid().isBlank()) {
                        uuids.put(ban.uuid().toLowerCase(Locale.ROOT), ban);
                    } else {
                        names.put(ban.name().toLowerCase(Locale.ROOT), ban);
                    }
                }
            }
        } catch (SQLException failure) {
            logger.warn("[NetworkBans] could not read the ban list (keeping the last one): {}", failure.toString());
            return;
        }
        byUuid = uuids;
        byName = names;
        for (Player player : proxy.getAllPlayers()) {
            Ban ban = find(player.getUniqueId(), player.getUsername());
            if (ban != null) {
                logger.info("[NetworkBans] disconnecting {}: banned from the network", player.getUsername());
                player.disconnect(message(ban));
            }
        }
    }

    public Ban find(UUID uuid, String name) {
        Ban ban = uuid == null ? null : byUuid.get(uuid.toString().toLowerCase(Locale.ROOT));
        if (ban == null && name != null) {
            ban = byName.get(name.toLowerCase(Locale.ROOT));
        }
        if (ban != null && ban.until() != null && ban.until().getTime() <= System.currentTimeMillis()) {
            return null;
        }
        return ban;
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        Ban ban = find(player.getUniqueId(), player.getUsername());
        if (ban != null) {
            logger.info("[NetworkBans] refused {}: banned from the network", player.getUsername());
            event.setResult(ResultedEvent.ComponentResult.denied(message(ban)));
        }
    }

    private static Component message(Ban ban) {
        Component text = Component.text("You are banned from this network.", NamedTextColor.RED);
        if (ban.reason() != null && !ban.reason().isBlank()) {
            text = text.append(Component.newline()).append(Component.text("Reason: " + ban.reason(), NamedTextColor.GRAY));
        }
        text = text.append(Component.newline()).append(Component.text(ban.until() == null ? "This ban doesn't end."
                : "It ends " + WHEN.format(ban.until().toInstant()) + ".", NamedTextColor.GRAY));
        return text;
    }
}

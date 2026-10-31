package com.silver.wakeup.plugin;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
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
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Maintenance mode: a server under maintenance takes nobody but admins and the names allowed for it (test bots), so it can be
 * restarted and edited freely. Server "*" means every server, and then the network itself refuses everyone else at login.
 *
 * <p>The switches live in the network database (table {@code net_maintenance}; the home page's admin side sets and clears them)
 * and are read every few seconds. When a server goes under maintenance, the players on it are warned and disconnected
 * {@link #GRACE} later, unless they are allowed in.</p>
 */
public final class Maintenance {
    public record Entry(String server, String reason, Set<String> allowed) {
    }

    static final Duration GRACE = Duration.ofSeconds(30);

    private final DataSource dataSource;
    private final ProxyServer proxy;
    private final Object plugin;
    private final Logger logger;
    private final Predicate<Player> admin;
    private volatile Map<String, Entry> entries = Map.of();
    private final Set<UUID> warned = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastRefusal = new ConcurrentHashMap<>();
    private volatile boolean tableReady;

    public Maintenance(DataSource dataSource, ProxyServer proxy, Object plugin, Logger logger, Predicate<Player> admin) {
        this.dataSource = dataSource;
        this.proxy = proxy;
        this.plugin = plugin;
        this.logger = logger;
        this.admin = admin;
    }

    private void ensureTable(Connection c) throws SQLException {
        if (tableReady) {
            return;
        }
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS net_maintenance (server VARCHAR(64) PRIMARY KEY, reason VARCHAR(255) NOT NULL DEFAULT '',"
                    + " allow_names TEXT NULL, since DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, set_by VARCHAR(64) NOT NULL DEFAULT '')");
        }
        tableReady = true;
    }

    /** Read the switches again; warn and later disconnect players on a server that just went under maintenance. */
    public void refresh() {
        Map<String, Entry> next = new HashMap<>();
        try (Connection c = dataSource.getConnection()) {
            ensureTable(c);
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT server, reason, allow_names FROM net_maintenance")) {
                while (r.next()) {
                    Set<String> allowed = new HashSet<>();
                    String names = r.getString(3);
                    if (names != null) {
                        for (String n : names.split("[,\\s]+")) {
                            if (!n.isBlank()) allowed.add(n.toLowerCase(Locale.ROOT));
                        }
                    }
                    next.put(r.getString(1).toLowerCase(Locale.ROOT), new Entry(r.getString(1), r.getString(2), allowed));
                }
            }
        } catch (SQLException failure) {
            logger.warn("[Maintenance] could not read the maintenance list (keeping the last one): {}", failure.toString());
            return;
        }
        entries = next;
        for (Player player : proxy.getAllPlayers()) {
            String server = player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(null);
            Entry entry = server == null ? next.get("*") : applying(server);
            if (entry == null || allowed(player, entry)) {
                warned.remove(player.getUniqueId());
                continue;
            }
            if (warned.add(player.getUniqueId())) {
                logger.info("[Maintenance] {} is on {} under maintenance; disconnecting in {} s", player.getUsername(), server, GRACE.toSeconds());
                player.sendMessage(Component.text((server == null ? "The network" : server) + " is going under maintenance. You'll be disconnected in "
                        + GRACE.toSeconds() + " seconds.", NamedTextColor.GOLD));
                proxy.getScheduler().buildTask(plugin, () -> disconnectIfStill(player.getUniqueId())).delay(GRACE).schedule();
            }
        }
    }

    private void disconnectIfStill(UUID id) {
        warned.remove(id);
        proxy.getPlayer(id).ifPresent(player -> {
            String server = player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(null);
            Entry entry = server == null ? entries.get("*") : applying(server);
            if (entry != null && !allowed(player, entry)) {
                logger.info("[Maintenance] disconnecting {} from {}", player.getUsername(), server);
                player.disconnect(message(server, entry));
            }
        });
    }

    /** The maintenance entry that applies to a server: its own, or the network-wide one. */
    public Entry applying(String server) {
        Map<String, Entry> now = entries;
        Entry own = now.get(server.toLowerCase(Locale.ROOT));
        return own != null ? own : now.get("*");
    }

    boolean allowed(Player player, Entry entry) {
        return entry.allowed().contains(player.getUsername().toLowerCase(Locale.ROOT)) || admin.test(player);
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        Entry all = entries.get("*");
        if (all != null && !allowed(event.getPlayer(), all)) {
            logger.info("[Maintenance] refused {}: the network is under maintenance", event.getPlayer().getUsername());
            event.setResult(ResultedEvent.ComponentResult.denied(message(null, all)));
        }
    }

    // LAST: the routing handlers may pick or change the target first; maintenance has the final word.
    @Subscribe(order = PostOrder.LAST)
    public void onPreConnect(ServerPreConnectEvent event) {
        var target = event.getResult().getServer().orElse(null);
        if (target == null) {
            return;
        }
        String server = target.getServerInfo().getName();
        Entry entry = applying(server);
        if (entry == null || allowed(event.getPlayer(), entry)) {
            return;
        }
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        long now = System.currentTimeMillis();
        Long last = lastRefusal.put(event.getPlayer().getUniqueId(), now);
        if (last == null || now - last > 30_000) {  // one message per half minute, not one per retry
            event.getPlayer().sendMessage(Component.text(server + " is under maintenance right now. Try again a little later.", NamedTextColor.GOLD));
        }
    }

    private static Component message(String server, Entry entry) {
        Component text = Component.text((server == null || "*".equals(entry.server()) ? "The network" : server) + " is under maintenance.", NamedTextColor.GOLD);
        if (entry.reason() != null && !entry.reason().isBlank()) {
            text = text.append(Component.newline()).append(Component.text(entry.reason(), NamedTextColor.GRAY));
        }
        return text.append(Component.newline()).append(Component.text("Please come back a little later.", NamedTextColor.GRAY));
    }
}

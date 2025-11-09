package com.silver.wakeup.session;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Computes fallback targets and performs the actual connection attempts when
 * sticky waits time out or players invoke manual fallback.
 */
final class FallbackPlanner {
    private final ProxyServer proxy;
    @SuppressWarnings("unused")
    private final Logger logger;
    private final Function<UUID, List<String>> allowedListFn;
    private final Function<UUID, Boolean> isAdminFn;
    private final Function<String, List<String>> groupLookup;

    FallbackPlanner(ProxyServer proxy,
                    Logger logger,
                    Function<UUID, List<String>> allowedListFn,
                    Function<UUID, Boolean> isAdminFn,
                    Function<String, List<String>> groupLookup) {
        this.proxy = proxy;
        this.logger = logger;
        this.allowedListFn = allowedListFn;
        this.isAdminFn = isAdminFn;
        this.groupLookup = groupLookup;
    }

    List<String> buildFallbackList(UUID playerId, String originServer, String holdingServer) {
        List<String> result = new ArrayList<>();
        if (originServer != null && !originServer.equalsIgnoreCase(holdingServer)) {
            result.add(originServer);
        }

        for (String server : allowedListFn.apply(playerId)) {
            if (!server.equalsIgnoreCase(originServer)) {
                result.add(server);
            }
        }
        return result;
    }

    void handleManualFallback(Player player, StickyRouter sessionRouter) {
        UUID who = player.getUniqueId();
        List<String> allowed = buildAllowedList(who);
        if (allowed.isEmpty()) {
            player.sendMessage(Component.text("⚠ No servers are available currently."));
            return;
        }

        Optional<String> chosen = attemptConnectFirstOnline(player, allowed, sessionRouter);
        if (chosen.isPresent()) {
            player.sendMessage(Component.text("➡ Sending you to " + chosen.get()));
        } else {
            player.sendMessage(Component.text("⚠ No servers are available currently."));
        }
    }

    void handleTimeoutFallback(Player player,
                               UUID playerId,
                               String originServer,
                               String holdingServer,
                               StickyRouter sessionRouter) {
        List<String> fallback = buildFallbackList(playerId, originServer, holdingServer);
        Optional<String> chosen = attemptConnectFirstOnline(player, fallback, sessionRouter);
        if (chosen.isPresent()) {
            player.sendMessage(Component.text("➡ Falling back to " + chosen.get()));
        } else {
            player.sendMessage(Component.text("⚠ No servers are available currently."));
        }
    }

    private List<String> buildAllowedList(UUID playerId) {
        if (Boolean.TRUE.equals(isAdminFn.apply(playerId))) {
            List<String> defaults = new ArrayList<>(groupLookup.apply("default_group"));
            Collections.reverse(defaults);
            return defaults;
        }
        return allowedListFn.apply(playerId);
    }

    private Optional<String> attemptConnectFirstOnline(Player player,
                                                       List<String> candidates,
                                                       StickyRouter sessionRouter) {
        for (String server : candidates) {
            var regOpt = proxy.getServer(server);
            if (regOpt.isEmpty()) continue;

            try {
                if (regOpt.get().ping().get(2, TimeUnit.SECONDS) != null) {
                    sessionRouter.markInternalOnce(player.getUniqueId());
                    player.createConnectionRequest(regOpt.get()).connect();
                    return Optional.of(server);
                }
            } catch (Exception ignored) {
                // try next candidate
            }
        }
        return Optional.empty();
    }
}

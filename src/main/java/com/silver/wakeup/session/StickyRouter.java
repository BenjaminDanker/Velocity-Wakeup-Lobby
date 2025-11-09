package com.silver.wakeup.session;

import com.silver.wakeup.plugin.VelocityPlugin;
import com.silver.wakeup.portal.PortalHandoffService;
import com.silver.wakeup.wake.WakeService;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Routes players between configured server groups with sticky session affinity.
 * 
 * <p>Maintains player session state to keep players on their last-known server
 * when possible. Handles fallback routing when servers are unavailable.
 * 
 * <p>Fallback policies:
 * <ul>
 *   <li>STRICT: Only route to explicitly configured servers; fail if unavailable</li>
 *   <li>OFFER: Offer alternative servers from the group if primary is unavailable</li>
 *   <li>AUTO: Automatically route to available servers or lobby on failure</li>
 * </ul>
 */
public class StickyRouter {
    public enum FallbackPolicy { STRICT, OFFER, AUTO }

    private final String holdingServer;
    private final Map<String, String> serverToMac;    // server -> mac
    private final Logger log;
    private final FallbackPolicy policy;

    private final Set<UUID> internalOnce = ConcurrentHashMap.newKeySet();
    private final StickySessionManager sessionManager;
    private final FallbackPlanner fallbackPlanner;

    public StickyRouter(
            ProxyServer proxy,
            VelocityPlugin plugin,
            String holdingServer,
            long graceSeconds,
            long pingEverySeconds,
            FallbackPolicy policy,
            Map<String, List<String>> groups,
            Map<String, String> serverToMac,
            WakeService wakeService,
            Logger log,
            Function<UUID, List<String>> allowedListFn,
            Set<String> adminNames,
            PortalHandoffService portalHandoffService
    ) {
        // Validate stored fields
        this.holdingServer = Objects.requireNonNull(holdingServer, "holdingServer");
        this.serverToMac = Objects.requireNonNull(serverToMac, "serverToMac");
        this.log = Objects.requireNonNull(log, "log");
        this.policy = Objects.requireNonNull(policy, "policy");

        // Validate dependencies passed to child objects
        Objects.requireNonNull(proxy, "proxy");
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(wakeService, "wakeService");
        Objects.requireNonNull(groups, "groups");
        Objects.requireNonNull(allowedListFn, "allowedListFn");
        Objects.requireNonNull(adminNames, "adminNames");
        Objects.requireNonNull(portalHandoffService, "portalHandoffService");

        this.sessionManager = new StickySessionManager(
                proxy,
                plugin,
                wakeService,
                portalHandoffService,
                log,
                graceSeconds,
                pingEverySeconds,
                this
        );

        this.fallbackPlanner = new FallbackPlanner(
                proxy,
                log,
                allowedListFn,
                uuid -> proxy.getPlayer(uuid)
                        .map(p -> adminNames.contains(p.getUsername().toLowerCase(Locale.ROOT)))
                        .orElse(false),
                group -> groups.getOrDefault(group, List.of())
        );
    }

    public boolean isLikelyUp(String name) {
        return sessionManager.isLikelyUp(name);
    }

    /** Wake target, keep player in holding, poll until ready or grace expires. */
    public void beginStickyWait(UUID playerId, String target, String originServer) {
        log.info("[StickyRouter] beginStickyWait: player={} target={} origin={}",
                playerId, target, originServer);

        String mac = serverToMac.get(target);
        sessionManager.beginStickyWait(playerId, target, originServer, mac);
    }

    /** Player-invoked immediate fallback (OFFER mode, or manual escape during HOLD). */
    public void fallbackNow(Player p) {
        if (policy == FallbackPolicy.STRICT) {
            p.sendMessage(Component.text("⚠ Fallback is disabled by policy."));
            return;
        }

        sessionManager.cleanupStickyState(p.getUniqueId());
        fallbackPlanner.handleManualFallback(p, this);
    }

    /** Mark next PreConnect for this player as plugin-internal (skip manual checks). */
    public void markInternalOnce(UUID who) {
        log.info("[StickyRouter] markInternalOnce: player={}", who);
        internalOnce.add(who);
    }

    /** Consume the internal mark; returns true if we should bypass checks. */
    public boolean consumeInternalOnce(UUID who) {
        boolean wasInternal = internalOnce.remove(who);
        log.info("[StickyRouter] consumeInternalOnce: player={} wasInternal={}", who, wasInternal);
        return wasInternal;
    }

    /** Check if there's an active sticky state for this player. */
    public boolean hasStickyState(UUID who) {
        boolean hasState = sessionManager.hasStickyState(who);
        log.info("[StickyRouter] hasStickyState: player={} hasState={}", who, hasState);
        return hasState;
    }

    void handleStickyTimeout(Player player, String originServer) {
        fallbackPlanner.handleTimeoutFallback(player, player.getUniqueId(), originServer, holdingServer, this);
    }

    FallbackPolicy policy() {
        return policy;
    }
}

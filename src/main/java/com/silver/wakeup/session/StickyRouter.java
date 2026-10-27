package com.silver.wakeup.session;

import com.silver.wakeup.plugin.VelocityPlugin;
import com.silver.wakeup.portal.PortalHandoffService;
import com.silver.wakeup.wake.WakeService;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.util.List;
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
 */
public class StickyRouter {
    private final String holdingServer;
    private final Map<String, String> serverToMac;    // server -> mac
    private final Logger log;

    /** Player -> when the mark was set. Marks expire, so a connect that failed can't leave a bypass behind. */
    private java.util.Map<UUID, InternalMark> internalOnce = new ConcurrentHashMap<>();
    private static final long INTERNAL_MARK_TTL_MS = 30_000L;
    private final StickySessionManager sessionManager;

    private record ReturnEligibility(String originServer, String targetServer, long eligibleSinceMs) {}
    private ConcurrentHashMap<UUID, ReturnEligibility> returnEligible = new ConcurrentHashMap<>();

    public StickyRouter(
            ProxyServer proxy,
            VelocityPlugin plugin,
            String holdingServer,
            long graceSeconds,
            long pingEverySeconds,
            Map<String, List<String>> groups,
            Map<String, String> serverToMac,
            WakeService wakeService,
            Logger log,
            Function<UUID, List<String>> allowedListFn,
            PortalHandoffService portalHandoffService
    ) {
        // Validate stored fields
        this.holdingServer = Objects.requireNonNull(holdingServer, "holdingServer");
        this.serverToMac = Objects.requireNonNull(serverToMac, "serverToMac");
        this.log = Objects.requireNonNull(log, "log");

        // Validate dependencies passed to child objects
        Objects.requireNonNull(proxy, "proxy");
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(wakeService, "wakeService");
        Objects.requireNonNull(groups, "groups");
        Objects.requireNonNull(allowedListFn, "allowedListFn");
        Objects.requireNonNull(portalHandoffService, "portalHandoffService");

        this.sessionManager = new StickySessionManager(
                proxy,
                plugin,
                wakeService,
                portalHandoffService,
                log,
                holdingServer,
                graceSeconds,
                pingEverySeconds,
                this
        );
    }

    public boolean isLikelyUp(String name) {
        return sessionManager.isLikelyUp(name);
    }

    /** Wake target, keep player in holding, poll until ready or grace expires. */
    public void beginStickyWait(UUID playerId, String target, String originServer) {
        log.info("[StickyRouter] beginStickyWait: player={} target={} origin={}",
                playerId, target, originServer);

        clearReturnEligibility(playerId);
        String mac = serverToMac.get(target);
        sessionManager.beginStickyWait(playerId, target, originServer, mac);
    }

    /**
     * Marks the player's next connect to {@code target} as plugin-internal (it skips the manual-switch and tier
     * checks). The mark only matches that server, expires after {@value #INTERNAL_MARK_TTL_MS} ms, and is cleared when
     * the player disconnects, so a connect that failed can't leave a bypass for a later, manual one.
     */
    public void markInternalOnce(UUID who, String target) {
        log.debug("[StickyRouter] markInternalOnce: player={} target={}", who, target);
        internalOnce.put(who, new InternalMark(target, System.currentTimeMillis()));
    }

    /** Consumes the mark; true when it was set for {@code target} and is still fresh. */
    public boolean consumeInternalOnce(UUID who, String target) {
        InternalMark mark = internalOnce.remove(who);
        boolean wasInternal = mark != null && mark.target().equalsIgnoreCase(target)
                && System.currentTimeMillis() - mark.markedAtMillis() <= INTERNAL_MARK_TTL_MS;
        log.debug("[StickyRouter] consumeInternalOnce: player={} target={} wasInternal={}", who, target, wasInternal);
        return wasInternal;
    }

    /**
     * After a config reload: keep the players' marks and /return eligibility from the router this one replaces. Both
     * routers then use the same maps, so a sticky wait still running on the old router marks its final connect where
     * the new router's pre-connect check finds it, instead of the connect being treated as manual.
     */
    public void sharePlayerStateFrom(StickyRouter previous) {
        if (previous != null) {
            this.internalOnce = previous.internalOnce;
            this.returnEligible = previous.returnEligible;
        }
    }

    /** Drops any mark (connect failed, or the player left). */
    public void clearInternalMark(UUID who) {
        internalOnce.remove(who);
    }

    private record InternalMark(String target, long markedAtMillis) {
    }

    /** Check if there's an active sticky state for this player. */
    public boolean hasStickyState(UUID who) {
        boolean hasState = sessionManager.hasStickyState(who);
        log.debug("[StickyRouter] hasStickyState: player={} hasState={}", who, hasState);
        return hasState;
    }

    /** Cancel any active sticky-wait sequence for this player. */
    public void cancelStickyWait(UUID who) {
        log.info("[StickyRouter] cancelStickyWait: player={}", who);
        sessionManager.cleanupStickyState(who);
        clearReturnEligibility(who);
    }

    /** Mark a player as eligible to use /return (grace expired while waiting). */
    void markReturnEligible(UUID playerId, String originServer, String targetServer) {
        returnEligible.put(playerId, new ReturnEligibility(originServer, targetServer, System.currentTimeMillis()));
        log.info("[StickyRouter] markReturnEligible: player={} origin={} target={}", playerId, originServer, targetServer);
    }

    public boolean isReturnEligible(UUID playerId) {
        return returnEligible.containsKey(playerId);
    }

    public String returnOriginServer(UUID playerId) {
        ReturnEligibility e = returnEligible.get(playerId);
        return e == null ? null : e.originServer();
    }

    public String returnTargetServer(UUID playerId) {
        ReturnEligibility e = returnEligible.get(playerId);
        return e == null ? null : e.targetServer();
    }

    public void clearReturnEligibility(UUID playerId) {
        returnEligible.remove(playerId);
    }
}

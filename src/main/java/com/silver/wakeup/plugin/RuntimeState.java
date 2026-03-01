package com.silver.wakeup.plugin;

import com.silver.wakeup.config.LobbyConfig;
import com.silver.wakeup.portal.PortalHandoffService;
import com.silver.wakeup.portal.PortalRequestVerifier;
import com.silver.wakeup.portal.PortalTokenVerifier;
import com.silver.wakeup.session.StickyRouter;
import com.silver.wakeup.wake.WakeService;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Holds mutable runtime state derived from the WakeUpLobby configuration and rebuilds
 * the supporting services (WakeService, StickyRouter) whenever the config changes.
 */
public class RuntimeState {
    private final ProxyServer proxy;
    private final VelocityPlugin plugin;
    private final Logger logger;
    private final PortalHandoffService portalHandoffService;
    private final PortalTokenVerifier portalTokenVerifier;
    private final PortalRequestVerifier portalRequestVerifier;

    private LobbyConfig currentConfig;
    private String holdingServer = "waiting_lobby";
    private long graceSec = 90;
    private long pingEverySec = 2;
    private final Map<String, List<String>> groups = new HashMap<>();
    private final Map<String, String> serverToMac = new HashMap<>();
    private WakeService wakeService;
    private StickyRouter stickyRouter;

    public RuntimeState(ProxyServer proxy,
                        VelocityPlugin plugin,
                        Logger logger,
                        PortalHandoffService portalHandoffService,
                        PortalTokenVerifier portalTokenVerifier,
                        PortalRequestVerifier portalRequestVerifier) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.logger = logger;
        this.portalHandoffService = portalHandoffService;
        this.portalTokenVerifier = portalTokenVerifier;
        this.portalRequestVerifier = portalRequestVerifier;
    }

    void applyConfig(LobbyConfig config, Function<UUID, List<String>> allowedTargetsDownward) throws IOException {
        this.currentConfig = config;
        this.holdingServer = config.holdingServer();
        this.graceSec = config.graceSec();
        this.pingEverySec = config.pingEverySec();

        this.serverToMac.clear();
        this.serverToMac.putAll(config.serverToMac());

        this.groups.clear();
        config.groups().forEach((key, value) -> this.groups.put(key, new ArrayList<>(value)));

        try {
            this.wakeService = new WakeService(config.broadcastIp());
        } catch (UnknownHostException ex) {
            throw new IOException("Invalid broadcast IP: " + config.broadcastIp(), ex);
        }

        this.stickyRouter = new StickyRouter(
                proxy,
                plugin,
                holdingServer,
                graceSec,
                pingEverySec,
                groups,
                serverToMac,
                wakeService,
                logger,
                allowedTargetsDownward,
                portalHandoffService
        );

        portalTokenVerifier.updateSecrets(config.globalPortalSecret(), config.perPortalSecrets());
        portalRequestVerifier.updateSecrets(config.backendPortalRequestSecrets());

        logger.info("[WakeUpLobby] Config applied. Holding={}, grace={}s, interval={}s",
            holdingServer, graceSec, pingEverySec);
    }

    StickyRouter stickyRouter() {
        return Objects.requireNonNull(stickyRouter, "StickyRouter not initialised");
    }

    LobbyConfig currentConfig() {
        return currentConfig;
    }

    String holdingServer() {
        return holdingServer;
    }

    List<String> groupMembers(String group) {
        return groups.getOrDefault(group, List.of());
    }

    PortalTokenVerifier portalTokenVerifier() {
        return portalTokenVerifier;
    }

    PortalRequestVerifier portalRequestVerifier() {
        return portalRequestVerifier;
    }
}

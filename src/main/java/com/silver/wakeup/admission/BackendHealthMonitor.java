package com.silver.wakeup.admission;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Maintains a small cached online/offline view of Velocity backend servers. */
public final class BackendHealthMonitor {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Map<String, Boolean> online = new ConcurrentHashMap<>();

    public BackendHealthMonitor(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    public boolean isOnline(String serverName) {
        return Boolean.TRUE.equals(online.get(serverName));
    }

    public void markOffline(String serverName) {
        update(serverName, false);
    }

    /** Ping every registered backend except the Raspberry Pi holding lobby. */
    public void refresh(String holdingServer) {
        for (var registered : proxy.getAllServers()) {
            String name = registered.getServerInfo().getName();
            if (name.equalsIgnoreCase(holdingServer)) {
                continue;
            }

            registered.ping()
                    .orTimeout(1200, TimeUnit.MILLISECONDS)
                    .whenComplete((pong, error) -> update(name, error == null));
        }
    }

    private void update(String serverName, boolean newState) {
        Boolean previous = online.put(serverName, newState);
        if (previous != null && previous != newState) {
            logger.info("[BackendHealth] {} -> {}", serverName, newState ? "ONLINE" : "OFFLINE");
        }
    }
}

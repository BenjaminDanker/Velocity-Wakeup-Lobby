package com.silver.wakeup.config;

import com.silver.wakeup.session.StickyRouter;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Immutable snapshot of WakeUpLobby configuration values loaded from disk.
 */
public class LobbyConfig {
    private final String broadcastIp;
    private final String holdingServer;
    private final long graceSec;
    private final long pingEverySec;
    private final StickyRouter.FallbackPolicy fallbackPolicy;
    private final Map<String, String> serverToMac;
    private final Map<String, List<String>> groups;
    private final Set<String> adminNames;
    private final String globalPortalSecret;
    private final Map<String, String> perPortalSecrets;

    public LobbyConfig(
            String broadcastIp,
            String holdingServer,
            long graceSec,
            long pingEverySec,
            StickyRouter.FallbackPolicy fallbackPolicy,
            Map<String, String> serverToMac,
            Map<String, List<String>> groups,
            Set<String> adminNames,
            String globalPortalSecret,
            Map<String, String> perPortalSecrets
    ) {
        this.broadcastIp = Objects.requireNonNull(broadcastIp, "broadcastIp");
        this.holdingServer = Objects.requireNonNull(holdingServer, "holdingServer");
        this.graceSec = graceSec;
        this.pingEverySec = pingEverySec;
        this.fallbackPolicy = Objects.requireNonNull(fallbackPolicy, "fallbackPolicy");
        this.serverToMac = Collections.unmodifiableMap(copyMap(serverToMac));
        this.groups = Collections.unmodifiableMap(copyGroups(groups));
        this.adminNames = Collections.unmodifiableSet(Set.copyOf(adminNames));
        this.globalPortalSecret = Objects.requireNonNull(globalPortalSecret, "globalPortalSecret");
        this.perPortalSecrets = Collections.unmodifiableMap(Objects.requireNonNull(perPortalSecrets, "perPortalSecrets"));
    }

    public String broadcastIp() {
        return broadcastIp;
    }

    public String holdingServer() {
        return holdingServer;
    }

    public long graceSec() {
        return graceSec;
    }

    public long pingEverySec() {
        return pingEverySec;
    }

    public StickyRouter.FallbackPolicy fallbackPolicy() {
        return fallbackPolicy;
    }

    public Map<String, String> serverToMac() {
        return serverToMac;
    }

    public Map<String, List<String>> groups() {
        return groups;
    }

    public Set<String> adminNames() {
        return adminNames;
    }

    public String globalPortalSecret() {
        return globalPortalSecret;
    }

    public Map<String, String> perPortalSecrets() {
        return perPortalSecrets;
    }

    private static Map<String, List<String>> copyGroups(Map<String, List<String>> groups) {
        Objects.requireNonNull(groups, "groups");
        return groups.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> List.copyOf(e.getValue())
                ));
    }

    private static Map<String, String> copyMap(Map<String, String> input) {
        Objects.requireNonNull(input, "input");
        return input.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue
                ));
    }
}

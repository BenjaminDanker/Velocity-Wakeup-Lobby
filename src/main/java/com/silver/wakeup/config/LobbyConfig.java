package com.silver.wakeup.config;

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
    private final Map<String, String> serverToMac;
    private final Map<String, List<String>> groups;
    private final Set<String> adminNames;
    private final String globalPortalSecret;
    private final Map<String, String> perPortalSecrets;

    private final List<String> returnServerOrder;
    private final Map<String, List<ReturnSpecial>> returnSpecials;

    public LobbyConfig(
            String broadcastIp,
            String holdingServer,
            long graceSec,
            long pingEverySec,
            Map<String, String> serverToMac,
            Map<String, List<String>> groups,
            Set<String> adminNames,
            String globalPortalSecret,
            Map<String, String> perPortalSecrets,
            List<String> returnServerOrder,
            Map<String, List<ReturnSpecial>> returnSpecials
    ) {
        this.broadcastIp = Objects.requireNonNull(broadcastIp, "broadcastIp");
        this.holdingServer = Objects.requireNonNull(holdingServer, "holdingServer");
        this.graceSec = graceSec;
        this.pingEverySec = pingEverySec;
        this.serverToMac = Collections.unmodifiableMap(copyMap(serverToMac));
        this.groups = Collections.unmodifiableMap(copyGroups(groups));
        this.adminNames = Collections.unmodifiableSet(Set.copyOf(adminNames));
        this.globalPortalSecret = Objects.requireNonNull(globalPortalSecret, "globalPortalSecret");
        this.perPortalSecrets = Collections.unmodifiableMap(Objects.requireNonNull(perPortalSecrets, "perPortalSecrets"));

        this.returnServerOrder = List.copyOf(Objects.requireNonNull(returnServerOrder, "returnServerOrder"));
        this.returnSpecials = Collections.unmodifiableMap(copyReturnSpecials(returnSpecials));
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

    public List<String> returnServerOrder() {
        return returnServerOrder;
    }

    public Map<String, List<ReturnSpecial>> returnSpecials() {
        return returnSpecials;
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

        private static Map<String, List<ReturnSpecial>> copyReturnSpecials(Map<String, List<ReturnSpecial>> input) {
        Objects.requireNonNull(input, "returnSpecials");
        return input.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                e -> List.copyOf(e.getValue())
            ));
        }
}

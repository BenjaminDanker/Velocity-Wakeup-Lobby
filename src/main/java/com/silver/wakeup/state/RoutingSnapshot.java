package com.silver.wakeup.state;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record RoutingSnapshot(
        Map<UUID, String> preferredServers,
        Map<UUID, String> lastListedServers,
        Map<UUID, Set<String>> visitedServers,
        Map<UUID, PortalTransfer> pendingTransfers
) {
}

package com.silver.wakeup.authorization;

import com.silver.authorization.CommandCatalog;
import com.silver.authorization.CommandCatalogCodec;
import com.silver.authorization.CommandOrigin;
import com.silver.authorization.DiscoveredCommandPolicies;
import com.silver.authorization.ServerId;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;

/** Authenticated, monotonic in-memory runtime command inventories from Fabric backends. */
public final class CommandCatalogStore {
    private static final Duration FUTURE_SKEW = Duration.ofSeconds(30);
    private static final Duration MAX_AGE = Duration.ofMinutes(5);
    private static final int MAX_NONCES = 10_000;
    private static final com.silver.authorization.CommandPolicyRegistry POLICIES =
            DiscoveredCommandPolicies.create();

    private final Logger log;
    private final Map<ServerId, CatalogState> catalogs = new ConcurrentHashMap<>();
    private final Map<ServerId, LinkedHashMap<UUID, Instant>> nonces = new ConcurrentHashMap<>();
    private final Map<ServerId, Map<String, Integer>> unmappedRootSummaries = new ConcurrentHashMap<>();

    public CommandCatalogStore(Logger log) { this.log = log; }

    public synchronized boolean accept(byte[] frame, ServerId trustedServer, byte[] backendKey, Instant now) {
        try {
            CommandCatalog catalog = CommandCatalogCodec.decodeAndVerify(frame, backendKey);
            if (!catalog.serverId().equals(trustedServer)
                    || catalog.issuedAt().isAfter(now.plus(FUTURE_SKEW))
                    || catalog.issuedAt().isBefore(now.minus(MAX_AGE))) return reject("wrong-server-or-time", trustedServer);

            CatalogState previous = catalogs.get(trustedServer);
            if (previous != null) {
                if (previous.catalog().backendEpoch().equals(catalog.backendEpoch())) {
                    if (catalog.generation() <= previous.catalog().generation()
                            || catalog.issuedAt().isBefore(previous.catalog().issuedAt())) {
                        return reject("stale-generation", trustedServer);
                    }
                } else if (!catalog.issuedAt().isAfter(previous.catalog().issuedAt())) {
                    return reject("stale-backend-epoch", trustedServer);
                }
            }

            LinkedHashMap<UUID, Instant> seen = nonces.computeIfAbsent(trustedServer, ignored -> new LinkedHashMap<>());
            seen.entrySet().removeIf(entry -> entry.getValue().isBefore(now.minus(MAX_AGE)));
            if (seen.containsKey(catalog.nonce()) || seen.size() >= MAX_NONCES) {
                return reject("replayed-or-nonce-capacity", trustedServer);
            }
            seen.put(catalog.nonce(), catalog.issuedAt());
            catalogs.put(trustedServer, new CatalogState(catalog, now));
            log.info("[CommandPolicy] Accepted runtime command catalog server={} paths={} generation={} epoch={}",
                    trustedServer, catalog.entries().size(), catalog.generation(), catalog.backendEpoch());
            Map<String, Integer> unmappedRoots = new TreeMap<>();
            for (var entry : catalog.entries()) {
                if (POLICIES.resolve(CommandOrigin.FABRIC_BACKEND, entry.path()).isEmpty()) {
                    unmappedRoots.merge(entry.path().root(), 1, Integer::sum);
                }
            }
            Map<String, Integer> previousSummary = unmappedRootSummaries.put(trustedServer, Map.copyOf(unmappedRoots));
            if (!unmappedRoots.isEmpty() && !unmappedRoots.equals(previousSummary)) {
                log.warn("[CommandPolicy] Unmapped runtime command families server={} roots={}",
                        trustedServer, unmappedRoots);
            } else if (unmappedRoots.isEmpty() && previousSummary != null && !previousSummary.isEmpty()) {
                log.info("[CommandPolicy] All runtime command families are mapped on {}", trustedServer);
            }
            return true;
        } catch (RuntimeException malformed) {
            log.warn("[CommandPolicy] Rejected malformed/unauthenticated catalog from {}: {}",
                    trustedServer, malformed.toString());
            return false;
        }
    }

    private boolean reject(String reason, ServerId server) {
        log.warn("[CommandPolicy] Rejected command catalog reason={} server={}", reason, server);
        return false;
    }

    public Optional<CommandCatalog> catalog(ServerId server) {
        CatalogState state = catalogs.get(server);
        return Optional.ofNullable(state == null ? null : state.catalog());
    }

    public Map<ServerId, CommandCatalog> catalogs() {
        Map<ServerId, CommandCatalog> result = new java.util.HashMap<>();
        catalogs.forEach((server, state) -> result.put(server, state.catalog()));
        return Map.copyOf(result);
    }

    private record CatalogState(CommandCatalog catalog, Instant acceptedAt) { }
}

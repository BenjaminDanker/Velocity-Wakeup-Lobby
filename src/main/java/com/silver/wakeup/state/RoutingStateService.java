package com.silver.wakeup.state;

import org.slf4j.Logger;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * MariaDB-backed routing authority with local read-through state for Velocity's synchronous events.
 * All mutations are serialized on a dedicated virtual-thread executor.
 */
public final class RoutingStateService implements AutoCloseable {
    private final MariaDbRoutingRepository repository;
    private final Logger log;
    private final Clock clock;
    private final Duration transferLifetime;
    private final ExecutorService writes = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("wakeup-routing-db-", 0).factory());

    private final ConcurrentHashMap<UUID, String> preferredServers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> lastListedServers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<String>> visitedServers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PortalTransfer> pendingTransfers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PortalTransfer> inFlightTransfers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PortalTransfer> handoffsAvailable = new ConcurrentHashMap<>();

    public RoutingStateService(MariaDbRoutingRepository repository, Logger log,
                               Clock clock, Duration transferLifetime) {
        this.repository = repository;
        this.log = log;
        this.clock = clock;
        this.transferLifetime = transferLifetime;
    }

    public static RoutingStateService initialize(Logger log) throws IOException, SQLException {
        MariaDbRoutingRepository repository = new MariaDbRoutingRepository(DatabaseSettings.loadDefault());
        boolean success = false;
        try {
            repository.migrate();
            RoutingStateService service = new RoutingStateService(
                    repository, log, Clock.systemUTC(), Duration.ofHours(24));
            service.installSnapshot(repository.loadSnapshot(Instant.now()));
            success = true;
            return service;
        } finally {
            if (!success) repository.close();
        }
    }

    private void installSnapshot(RoutingSnapshot snapshot) {
        preferredServers.clear();
        preferredServers.putAll(snapshot.preferredServers());
        lastListedServers.clear();
        lastListedServers.putAll(snapshot.lastListedServers());
        visitedServers.clear();
        snapshot.visitedServers().forEach((player, servers) ->
                visitedServers.put(player, ConcurrentHashMap.newKeySet(servers.size())));
        snapshot.visitedServers().forEach((player, servers) -> visitedServers.get(player).addAll(servers));
        pendingTransfers.clear();
        pendingTransfers.putAll(snapshot.pendingTransfers());
        log.info("[RoutingState] Loaded players={}, visitOwners={}, pendingTransfers={}",
                preferredServers.size(), visitedServers.size(), pendingTransfers.size());
    }

    public Optional<String> preferredServer(UUID playerId) {
        return Optional.ofNullable(preferredServers.get(playerId));
    }

    public DataSource dataSource() {
        return repository.dataSource();
    }

    public Optional<String> lastListedServer(UUID playerId) {
        return Optional.ofNullable(lastListedServers.get(playerId));
    }

    public Set<String> visitedServers(UUID playerId) {
        return Set.copyOf(visitedServers.getOrDefault(playerId, Set.of()));
    }

    public Optional<PortalTransfer> pendingTransfer(UUID playerId) {
        PortalTransfer transfer = pendingTransfers.get(playerId);
        if (transfer == null) return Optional.empty();
        if (transfer.expiredAt(clock.instant())) {
            pendingTransfers.remove(playerId, transfer);
            return Optional.empty();
        }
        return Optional.of(transfer);
    }

    public CompletableFuture<Void> recordSuccessfulConnection(UUID playerId, String server,
                                                               boolean isListedServer) {
        preferredServers.put(playerId, server);
        visitedServers.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(server);
        String listed = null;
        if (isListedServer) {
            lastListedServers.put(playerId, server);
            listed = server;
        }
        String finalListed = listed;
        return runDatabase("record successful connection", () ->
                repository.recordSuccessfulConnection(playerId, server, finalListed));
    }

    public CompletableFuture<Void> setPreferredServer(UUID playerId, String server) {
        preferredServers.put(playerId, server);
        return runDatabase("set preferred server", () -> repository.setPreferredServer(playerId, server));
    }

    public CompletableFuture<PortalTransfer> createTransfer(UUID playerId, String sourceServer,
                                                             String targetServer, String arrivalPortal) {
        Instant now = clock.instant();
        return CompletableFuture.supplyAsync(() -> {
            try {
                PortalTransfer transfer = repository.createTransfer(playerId, sourceServer, targetServer,
                        arrivalPortal, now, now.plus(transferLifetime));
                pendingTransfers.put(playerId, transfer);
                inFlightTransfers.remove(playerId);
                handoffsAvailable.remove(playerId);
                log.info("[PortalTransfer] created transfer={} player={} source={} target={} arrival='{}'",
                        transfer.transferId(), playerId, sourceServer, targetServer, arrivalPortal);
                return transfer;
            } catch (SQLException failure) {
                throw new IllegalStateException("Failed to create portal transfer", failure);
            }
        }, writes);
    }

    public CompletableFuture<Optional<PortalTransfer>> claimForTarget(UUID playerId, String targetServer) {
        PortalTransfer pending = pendingTransfer(playerId).orElse(null);
        if (pending == null || !pending.targetServer().equalsIgnoreCase(targetServer)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<PortalTransfer> claimed = repository.claimPending(playerId, targetServer, clock.instant());
                claimed.ifPresent(transfer -> {
                    pendingTransfers.remove(playerId);
                    inFlightTransfers.put(playerId, transfer);
                    handoffsAvailable.put(playerId, transfer);
                    log.info("[PortalTransfer] claimed transfer={} player={} target={}",
                            transfer.transferId(), playerId, targetServer);
                });
                return claimed;
            } catch (SQLException failure) {
                throw new IllegalStateException("Failed to claim portal transfer", failure);
            }
        }, writes);
    }

    /** Consumes the arrival payload once; the in-flight record remains until ServerConnectedEvent. */
    public Optional<PortalTransfer> consumeHandoff(UUID playerId, String requestingServer) {
        PortalTransfer available = handoffsAvailable.get(playerId);
        if (available == null || !available.targetServer().equalsIgnoreCase(requestingServer)) {
            return Optional.empty();
        }
        PortalTransfer transfer = handoffsAvailable.remove(playerId);
        if (transfer != null) {
            log.info("[PortalTransfer] consumed handoff transfer={} player={} target={}",
                    transfer.transferId(), playerId, transfer.targetServer());
        }
        return Optional.ofNullable(transfer);
    }

    public CompletableFuture<Void> connectionFailed(UUID playerId, PortalTransfer claimed) {
        if (!handoffsAvailable.remove(playerId, claimed)) {
            // The destination login already consumed the handoff. Never replay it.
            return CompletableFuture.completedFuture(null);
        }
        inFlightTransfers.remove(playerId, claimed);
        pendingTransfers.put(playerId, claimed.withStatus(PortalTransfer.Status.PENDING));
        return runDatabase("restore portal transfer", () -> repository.restorePending(claimed.transferId()));
    }

    public CompletableFuture<Void> completeConnectedTransfer(UUID playerId, String connectedServer) {
        PortalTransfer transfer = inFlightTransfers.get(playerId);
        if (transfer == null || !transfer.targetServer().equalsIgnoreCase(connectedServer)) {
            return CompletableFuture.completedFuture(null);
        }
        inFlightTransfers.remove(playerId, transfer);
        handoffsAvailable.remove(playerId, transfer);
        return runDatabase("complete portal transfer", () -> repository.complete(transfer.transferId()));
    }

    public CompletableFuture<Void> cancelActiveTransfer(UUID playerId) {
        pendingTransfers.remove(playerId);
        handoffsAvailable.remove(playerId);
        inFlightTransfers.remove(playerId);
        return runDatabase("cancel portal transfer", () -> repository.cancelActive(playerId));
    }

    private CompletableFuture<Void> runDatabase(String operation, SqlOperation action) {
        return CompletableFuture.runAsync(() -> {
            try {
                action.run();
            } catch (SQLException failure) {
                throw new IllegalStateException("Failed to " + operation, failure);
            }
        }, writes).whenComplete((ignored, failure) -> {
            if (failure != null) log.error("[RoutingState] {} failed", operation, failure);
        });
    }

    @Override
    public void close() {
        writes.shutdown();
        try {
            if (!writes.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("[RoutingState] Timed out waiting for queued database writes during shutdown");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        repository.close();
    }

    @FunctionalInterface
    private interface SqlOperation {
        void run() throws SQLException;
    }
}

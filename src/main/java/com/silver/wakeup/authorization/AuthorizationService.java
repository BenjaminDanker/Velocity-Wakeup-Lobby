package com.silver.wakeup.authorization;

import com.silver.authorization.Authorization;
import com.silver.authorization.AuthorizationDecision;
import com.silver.authorization.AuthorizationScope;
import com.silver.authorization.AuthorizationSnapshot;
import com.silver.authorization.AuthorizationSubject;
import com.silver.authorization.BackendRequestSigner;
import com.silver.authorization.PermissionNode;
import com.silver.authorization.ServerId;
import com.silver.authorization.SignedAuthorizationSnapshot;
import com.silver.authorization.SnapshotSigner;
import com.silver.authorization.AuthorizationSyncRequest;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;

/** Velocity's in-memory authority and write-through transaction coordinator. */
public final class AuthorizationService implements Authorization, AutoCloseable {
    private final MariaDbAuthorizationRepository repository;
    private final Logger log;
    private final Clock clock;
    private final AtomicReference<AuthorizationState> state = new AtomicReference<>(AuthorizationState.empty());
    private final ReentrantLock mutations = new ReentrantLock();
    private volatile boolean databaseAvailable;
    private volatile boolean closed;
    private volatile boolean usable;
    private final Instant proxyStartedAt;
    private final Map<ServerId, BackendSession> backendSessions = new HashMap<>();
    private final Map<ServerId, Map<UUID, Instant>> usedNonces = new HashMap<>();
    private final Object syncLock = new Object();
    private final SnapshotSigner snapshotSigner = new SnapshotSigner();
    private final BackendRequestSigner requestSigner = new BackendRequestSigner();

    public AuthorizationService(MariaDbAuthorizationRepository repository, Logger log, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.log = Objects.requireNonNull(log, "log");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.proxyStartedAt = clock.instant();
    }

    public void initialize(Collection<ServerId> configuredServers) throws SQLException {
        repository.bootstrapConfiguredServers(configuredServers, clock.instant());
        AuthorizationState loaded = repository.loadState(clock.instant());
        verifyInitialOwner(loaded);
        state.set(loaded);
        usable = true;
        databaseAvailable = true;
        log.info("[Authorization] Loaded immutable revision={} users={} enabledServers={}",
                loaded.revision(), loaded.subjectCount(), loaded.enabledServers().size());
    }

    private void verifyInitialOwner(AuthorizationState loaded) throws SQLException {
        if (!loaded.hasOwnerManagementAuthority()) {
            throw new SQLException("Authorization state has no active global OWNER with recovery authority");
        }
        log.info("[Authorization] Verified at least one active global OWNER with recovery authority at revision {}",
                loaded.revision());
    }

    public AuthorizationState currentState() {
        return state.get();
    }

    /** Keeps Floodgate and Java names searchable by UUID; this metadata is not an authorization revision. */
    public void rememberUsername(UUID uuid, String username) {
        Objects.requireNonNull(uuid, "uuid");
        if (username == null || username.isBlank() || username.length() > 16) return;
        String normalized = username.strip();
        AuthorizationState current = state.get();
        if (current.username(uuid).filter(normalized::equals).isPresent()) return;
        try {
            repository.rememberUsername(uuid, normalized);
        } catch (SQLException failure) {
            log.warn("[Authorization] Could not persist last-known name for {} (retaining in proxy cache): {}",
                    uuid, failure.getMessage());
        }
        state.updateAndGet(previous -> previous.withUsername(uuid, normalized));
    }

    public boolean databaseAvailable() {
        return databaseAvailable;
    }

    public boolean hasUsableState() { return usable; }

    /** Scheduled health probe; failure never clears or expires a previously valid memory state. */
    public void refreshDatabaseHealth() {
        if (closed) return;
        try {
            repository.ping();
            if (!databaseAvailable) {
                databaseAvailable = true;
                log.info("[Authorization] MariaDB recovered; reloading authorization state from revision={}",
                        state.get().revision());
                if (usable && !reload()) {
                    log.error("[Authorization] MariaDB responded to health probe but authorization reload failed; "
                            + "continuing from last-known-good revision={}", state.get().revision());
                }
            }
        } catch (SQLException failure) {
            if (databaseAvailable) {
                log.error("[Authorization] MariaDB unavailable; serving last-known-good revision={} and continuing snapshot renewal",
                        state.get().revision(), failure);
            }
            databaseAvailable = false;
        }
    }

    /** A database-dependent reload is rejected during an outage and never clears the cache. */
    public boolean reload() {
        mutations.lock();
        try {
            if (!probeForOperation("reload")) return false;
            AuthorizationState replacement = repository.loadState(clock.instant());
            verifyInitialOwner(replacement);
            publish(replacement);
            return true;
        } catch (SQLException failure) {
            markDatabaseFailure("reload", failure);
            return false;
        } finally {
            mutations.unlock();
        }
    }

    public long applyMutation(UUID actor, AuthorizationMutation mutation) throws SQLException {
        Objects.requireNonNull(actor, "actor");
        return applyMutation(actor, null, mutation);
    }

    /** Explicit Velocity-console break-glass path, audited as CONSOLE rather than a synthetic player. */
    public long applyConsoleMutation(String consoleId, AuthorizationMutation mutation) throws SQLException {
        if (consoleId == null || consoleId.isBlank()) throw new IllegalArgumentException("consoleId is required");
        return applyMutation(null, consoleId, mutation);
    }

    private long applyMutation(UUID actor, String consoleId, AuthorizationMutation mutation) throws SQLException {
        mutations.lock();
        try {
            if (closed || !probeForOperation("management write")) {
                throw new SQLException("Authorization management writes are unavailable while MariaDB is unavailable");
            }
            long committedRevision;
            try {
                committedRevision = actor != null
                        ? repository.applyMutation(actor, mutation, clock.instant())
                        : repository.applyConsoleMutation(consoleId, mutation, clock.instant());
            } catch (SQLException failure) {
                // SQL validation failures are not outages; a separate ping distinguishes them.
                try { repository.ping(); } catch (SQLException unavailable) { markDatabaseFailure("management write", unavailable); }
                throw failure;
            }
            try {
                AuthorizationState replacement = repository.loadState(clock.instant());
                if (replacement.revision() < committedRevision) {
                    throw new SQLException("Reloaded authorization revision is older than committed write");
                }
                publish(replacement);
                return committedRevision;
            } catch (SQLException failure) {
                markDatabaseFailure("post-commit state reload", failure);
                log.error("[Authorization] Revision {} committed but not published; retaining last-known-good revision={}",
                        committedRevision, state.get().revision(), failure);
                throw failure;
            }
        } finally {
            mutations.unlock();
        }
    }

    private boolean probeForOperation(String operation) {
        try {
            repository.ping();
            databaseAvailable = true;
            return true;
        } catch (SQLException failure) {
            markDatabaseFailure(operation, failure);
            return false;
        }
    }

    private void markDatabaseFailure(String operation, SQLException failure) {
        if (databaseAvailable) {
            log.error("[Authorization] MariaDB failure during {}; retaining last-known-good revision={} and rejecting writes/reloads",
                    operation, state.get().revision(), failure);
        }
        databaseAvailable = false;
    }

    private void publish(AuthorizationState replacement) {
        AuthorizationState previous = state.get();
        if (replacement.revision() < previous.revision()) {
            throw new IllegalArgumentException("Cannot publish stale authorization revision");
        }
        state.set(replacement);
        usable = true;
        databaseAvailable = true;
        log.info("[Authorization] Published immutable revision={} (previous={})",
                replacement.revision(), previous.revision());
    }

    @Override
    public AuthorizationDecision decide(AuthorizationSubject subject, PermissionNode permission, ServerId server) {
        return state.get().decide(subject, permission, server);
    }

    @Override
    public boolean has(AuthorizationSubject subject, PermissionNode permission, ServerId server) {
        return state.get().has(subject, permission, server);
    }

    public Optional<SignedAuthorizationSnapshot> signedSnapshot(UUID subject, ServerId server,
                                                                 UUID backendEpoch, UUID nonce,
                                                                 Instant issuedAt, Duration lease,
                                                                 byte[] backendKey) {
        if (lease.isNegative() || lease.isZero() || lease.compareTo(Duration.ofMinutes(5)) > 0) {
            return Optional.empty();
        }
        AuthorizationState current = state.get();
        return current.snapshot(subject, server, backendEpoch, nonce, issuedAt, issuedAt.plus(lease))
                .map(snapshot -> snapshotSigner.sign(snapshot, backendKey));
    }

    public boolean verifySyncRequest(AuthorizationSyncRequest request, byte[] backendKey) {
        Instant now = clock.instant();
        if (request.issuedAt().isAfter(now.plus(Duration.ofSeconds(30)))
                || request.issuedAt().isBefore(now.minus(Duration.ofMinutes(2)))
                || request.issuedAt().isBefore(proxyStartedAt.minus(Duration.ofSeconds(30)))
                || !requestSigner.verify(request, backendKey)) return false;
        synchronized (syncLock) {
            Map<UUID, Instant> nonces = usedNonces.computeIfAbsent(request.serverId(), ignored -> new HashMap<>());
            nonces.entrySet().removeIf(entry -> entry.getValue().isBefore(now.minus(Duration.ofMinutes(2))));
            if (nonces.containsKey(request.nonce())) return false;
            if (nonces.size() >= 10_000) return false;
            BackendSession previous = backendSessions.get(request.serverId());
            if (previous != null && !previous.epoch().equals(request.backendEpoch())
                    && !request.issuedAt().isAfter(previous.lastIssuedAt())) return false;
            Instant highWater = previous == null || request.issuedAt().isAfter(previous.lastIssuedAt())
                    ? request.issuedAt() : previous.lastIssuedAt();
            backendSessions.put(request.serverId(), new BackendSession(request.backendEpoch(), highWater));
            nonces.put(request.nonce(), request.issuedAt());
            return true;
        }
    }

    @Override
    public void close() {
        closed = true;
    }

    private record BackendSession(UUID epoch, Instant lastIssuedAt) {}
}

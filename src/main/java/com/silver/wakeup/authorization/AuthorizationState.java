package com.silver.wakeup.authorization;

import com.silver.authorization.Authorization;
import com.silver.authorization.AuthorizationDecision;
import com.silver.authorization.AuthorizationScope;
import com.silver.authorization.AuthorizationSnapshot;
import com.silver.authorization.AuthorizationSubject;
import com.silver.authorization.DirectPermissionRule;
import com.silver.authorization.EffectivePermissionRules;
import com.silver.authorization.PermissionEvaluator;
import com.silver.authorization.PermissionNode;
import com.silver.authorization.PermissionNodes;
import com.silver.authorization.PermissionPattern;
import com.silver.authorization.PermissionRule;
import com.silver.authorization.RoleAssignment;
import com.silver.authorization.RoleId;
import com.silver.authorization.RolePermission;
import com.silver.authorization.ServerId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Fully immutable pre-expanded authorization state; lookup/evaluation does no database work. */
public final class AuthorizationState implements Authorization {
    private final long revision;
    private final Set<ServerId> enabledServers;
    private final Map<UUID, Map<ServerId, List<PermissionRule>>> effectiveRules;
    private final Map<ServerId, List<PermissionRule>> defaultRoleRules;
    private final Map<RoleId, RoleMetadata> roles;
    private final Map<UUID, List<RoleAssignment>> assignments;
    private final Map<RoleId, List<RolePermission>> rolePermissions;
    private final Map<UUID, List<DirectPermissionRule>> directPermissions;
    private final Map<UUID, String> usernames;

    private AuthorizationState(long revision,
                               Collection<ServerId> enabledServers,
                               Map<UUID, Map<ServerId, List<PermissionRule>>> effectiveRules,
                               Map<ServerId, List<PermissionRule>> defaultRoleRules,
                               Map<RoleId, RoleMetadata> roles,
                               Map<UUID, List<RoleAssignment>> assignments,
                               Collection<RolePermission> rolePermissions,
                               Map<UUID, List<DirectPermissionRule>> directPermissions,
                               Map<UUID, String> usernames) {
        this.revision = revision;
        this.enabledServers = Set.copyOf(enabledServers);
        Map<UUID, Map<ServerId, List<PermissionRule>>> users = new HashMap<>();
        effectiveRules.forEach((uuid, perServer) -> {
            Map<ServerId, List<PermissionRule>> copy = new HashMap<>();
            perServer.forEach((server, rules) -> copy.put(server, List.copyOf(rules)));
            users.put(uuid, Map.copyOf(copy));
        });
        this.effectiveRules = Map.copyOf(users);
        Map<ServerId, List<PermissionRule>> defaults = new HashMap<>();
        defaultRoleRules.forEach((server, rules) -> defaults.put(server, List.copyOf(rules)));
        this.defaultRoleRules = Map.copyOf(defaults);
        this.roles = Map.copyOf(roles);
        Map<UUID, List<RoleAssignment>> assigned = new HashMap<>();
        assignments.forEach((uuid, values) -> assigned.put(uuid, List.copyOf(values)));
        this.assignments = Map.copyOf(assigned);
        Map<RoleId, List<RolePermission>> permissions = new HashMap<>();
        rolePermissions.forEach(permission -> permissions.computeIfAbsent(permission.roleId(), ignored -> new ArrayList<>())
                .add(permission));
        permissions.replaceAll((role, values) -> List.copyOf(values));
        this.rolePermissions = Map.copyOf(permissions);
        Map<UUID, List<DirectPermissionRule>> direct = new HashMap<>();
        directPermissions.forEach((uuid, values) -> direct.put(uuid, List.copyOf(values)));
        this.directPermissions = Map.copyOf(direct);
        this.usernames = Map.copyOf(usernames);
    }

    public static AuthorizationState build(long revision,
                                           Collection<ServerId> enabledServers,
                                           Map<RoleId, RoleMetadata> roles,
                                           Map<UUID, List<RoleAssignment>> assignments,
                                           Collection<RolePermission> rolePermissions,
                                           Map<UUID, List<DirectPermissionRule>> directPermissions) {
        return build(revision, enabledServers, roles, assignments, rolePermissions, directPermissions, Map.of());
    }

    public static AuthorizationState build(long revision,
                                           Collection<ServerId> enabledServers,
                                           Map<RoleId, RoleMetadata> roles,
                                           Map<UUID, List<RoleAssignment>> assignments,
                                           Collection<RolePermission> rolePermissions,
                                           Map<UUID, List<DirectPermissionRule>> directPermissions,
                                           Map<UUID, String> usernames) {
        Objects.requireNonNull(enabledServers, "enabledServers");
        Objects.requireNonNull(roles, "roles");
        Objects.requireNonNull(assignments, "assignments");
        Objects.requireNonNull(rolePermissions, "rolePermissions");
        Objects.requireNonNull(directPermissions, "directPermissions");
        Objects.requireNonNull(usernames, "usernames");
        if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");

        Set<UUID> subjects = new HashSet<>(assignments.keySet());
        subjects.addAll(directPermissions.keySet());
        List<RoleAssignment> defaultAssignments = roles.values().stream()
                .filter(RoleMetadata::defaultForPlayers)
                .map(role -> new RoleAssignment(role.id(), AuthorizationScope.global()))
                .toList();
        Map<ServerId, List<PermissionRule>> defaultRules = new HashMap<>();
        for (ServerId server : enabledServers) {
            defaultRules.put(server, EffectivePermissionRules.forServer(
                    defaultAssignments, rolePermissions, List.of(), server));
        }
        Map<UUID, Map<ServerId, List<PermissionRule>>> expanded = new HashMap<>();
        for (UUID subject : subjects) {
            List<RoleAssignment> userAssignments = assignments.getOrDefault(subject, List.of());
            List<DirectPermissionRule> userDirectRules = directPermissions.getOrDefault(subject, List.of());
            Map<ServerId, List<PermissionRule>> perServer = new HashMap<>();
            for (ServerId server : enabledServers) {
                List<PermissionRule> merged = new ArrayList<>(defaultRules.getOrDefault(server, List.of()));
                merged.addAll(EffectivePermissionRules.forServer(
                        userAssignments, rolePermissions, userDirectRules, server));
                perServer.put(server, merged.stream().distinct().toList());
            }
            expanded.put(subject, perServer);
        }
        return new AuthorizationState(revision, enabledServers, expanded, defaultRules, roles, assignments,
                rolePermissions, directPermissions, usernames);
    }

    public static AuthorizationState empty() {
        return new AuthorizationState(0, Set.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of());
    }

    public long revision() { return revision; }
    public int subjectCount() { return effectiveRules.size(); }
    public Set<ServerId> enabledServers() { return enabledServers; }
    public Map<RoleId, RoleMetadata> roles() { return roles; }
    public List<RoleAssignment> assignments(UUID uuid) { return assignments.getOrDefault(uuid, List.of()); }
    public List<RolePermission> rolePermissions(RoleId role) { return rolePermissions.getOrDefault(role, List.of()); }
    public List<DirectPermissionRule> directPermissions(UUID uuid) { return directPermissions.getOrDefault(uuid, List.of()); }
    public Optional<String> username(UUID uuid) { return Optional.ofNullable(usernames.get(uuid)); }
    public Map<UUID, String> usernames() { return usernames; }
    public Set<UUID> knownSubjects() {
        Set<UUID> known = new HashSet<>(assignments.keySet());
        known.addAll(directPermissions.keySet());
        known.addAll(usernames.keySet());
        return Set.copyOf(known);
    }
    public Set<PermissionPattern> knownPermissionPatterns() {
        Set<PermissionPattern> patterns = new TreeSet<>();
        rolePermissions.values().forEach(rules -> rules.forEach(rule -> patterns.add(rule.pattern())));
        directPermissions.values().forEach(rules -> rules.forEach(rule -> patterns.add(rule.pattern())));
        return Set.copyOf(patterns);
    }
    public AuthorizationState withUsername(UUID uuid, String username) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(username, "username");
        Map<UUID, String> names = new HashMap<>(usernames);
        names.put(uuid, username);
        return build(revision, enabledServers, roles, assignments,
                rolePermissions.values().stream().flatMap(Collection::stream).toList(),
                directPermissions, names);
    }

    public List<PermissionRule> rules(UUID uuid, ServerId server) {
        return effectiveRules.getOrDefault(uuid, Map.of()).getOrDefault(server,
                defaultRoleRules.getOrDefault(server, List.of()));
    }

    @Override
    public AuthorizationDecision decide(AuthorizationSubject subject, PermissionNode permission, ServerId server) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(server, "server");
        if (subject.kind() != AuthorizationSubject.Kind.PLAYER || !enabledServers.contains(server)) {
            return AuthorizationDecision.defaultDeny(permission, server, revision);
        }
        return PermissionEvaluator.evaluate(rules(subject.playerUuid(), server), permission, server, revision);
    }

    @Override
    public boolean has(AuthorizationSubject subject, PermissionNode permission, ServerId server) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(server, "server");
        return subject.kind() == AuthorizationSubject.Kind.PLAYER && enabledServers.contains(server)
                && PermissionEvaluator.has(rules(subject.playerUuid(), server), permission, server);
    }

    /** Global-only capability check used to authorize network-wide management operations. */
    public boolean hasGlobal(UUID uuid, PermissionNode permission) {
        List<PermissionRule> global = new ArrayList<>();
        for (ServerId server : enabledServers) {
            rules(uuid, server).stream()
                    .filter(rule -> rule.scope().type() == AuthorizationScope.Type.GLOBAL)
                    .forEach(global::add);
            if (!global.isEmpty()) break;
        }
        // The candidate set is identical for every server for true GLOBAL-scoped rules.
        return !enabledServers.isEmpty() && PermissionEvaluator.evaluate(
                global, permission, enabledServers.iterator().next(), revision).allowed();
    }

    public AuthorizationDecision decideGlobal(UUID uuid, PermissionNode permission) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(permission, "permission");
        if (enabledServers.isEmpty()) {
            return new AuthorizationDecision(permission, AuthorizationScope.global(), revision,
                    AuthorizationDecision.Outcome.DEFAULT_DENY, Optional.empty(), List.of());
        }
        ServerId representative = enabledServers.iterator().next();
        List<PermissionRule> global = rules(uuid, representative).stream()
                .filter(rule -> rule.scope().type() == AuthorizationScope.Type.GLOBAL).toList();
        AuthorizationDecision evaluated = PermissionEvaluator.evaluate(global, permission, representative, revision);
        return new AuthorizationDecision(permission, AuthorizationScope.global(), revision,
                evaluated.outcome(), evaluated.matchedRule(), evaluated.consideredRules());
    }

    public int managementPriority(UUID uuid, ServerId server) {
        int explicit = assignments(uuid).stream()
                .filter(assignment -> assignment.scope().appliesTo(server))
                .map(assignment -> roles.get(assignment.roleId()))
                .filter(Objects::nonNull)
                .mapToInt(RoleMetadata::managementPriority)
                .max().orElse(-1);
        int playerDefault = roles.values().stream().filter(RoleMetadata::defaultForPlayers)
                .mapToInt(RoleMetadata::managementPriority).max().orElse(-1);
        return Math.max(explicit, playerDefault);
    }

    public boolean hasGlobalOwnerAssignment(UUID uuid) {
        return assignments(uuid).stream().anyMatch(assignment -> assignment.roleId().value().equals("OWNER")
                && assignment.scope().type() == AuthorizationScope.Type.GLOBAL);
    }

    public boolean hasOwnerManagementAuthority() {
        return assignments.keySet().stream().anyMatch(this::ownerHasManagementAuthority);
    }

    public boolean ownerHasManagementAuthority(UUID uuid) {
        return hasGlobalOwnerAssignment(uuid)
                && hasGlobal(uuid, PermissionNodes.STAFF_ROLES_MODIFY)
                && hasGlobal(uuid, PermissionNodes.STAFF_PERMISSIONS_MODIFY);
    }

    public Optional<AuthorizationSnapshot> snapshot(UUID uuid, ServerId server, UUID backendEpoch,
                                                     UUID nonce, Instant issuedAt, Instant expiresAt) {
        if (!enabledServers.contains(server)) return Optional.empty();
        return Optional.of(new AuthorizationSnapshot(AuthorizationSnapshot.CURRENT_PROTOCOL_VERSION,
                AuthorizationSubject.player(uuid), server, revision, backendEpoch, nonce,
                issuedAt, expiresAt, rules(uuid, server)));
    }

    public record RoleMetadata(RoleId id, long databaseId, int managementPriority,
                               boolean protectedRole, boolean defaultForPlayers) {
        public RoleMetadata {
            Objects.requireNonNull(id, "id");
            if (databaseId < 1) throw new IllegalArgumentException("databaseId must be positive");
        }
    }
}

package com.silver.wakeup.authorization;

import com.silver.authorization.AuthorizationScope;
import com.silver.authorization.DirectPermissionRule;
import com.silver.authorization.PermissionEffect;
import com.silver.authorization.PermissionNode;
import com.silver.authorization.PermissionPattern;
import com.silver.authorization.PermissionNodes;
import com.silver.authorization.RoleAssignment;
import com.silver.authorization.RoleId;
import com.silver.authorization.RolePermission;
import com.silver.authorization.ServerId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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
import java.util.UUID;
import javax.sql.DataSource;

/** MariaDB persistence for authorization; Velocity owns and shares the underlying Hikari pool. */
public final class MariaDbAuthorizationRepository {
    private final DataSource dataSource;

    public MariaDbAuthorizationRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public void ping() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT revision FROM auth_state WHERE state_key='authorization'");
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) throw new SQLException("Missing auth_state authorization row");
        }
    }

    /** Upserts current Velocity server IDs and disables, but never deletes, historical server rows. */
    public long bootstrapConfiguredServers(Collection<ServerId> configured, Instant now) throws SQLException {
        Set<ServerId> desired = Set.copyOf(configured);
        if (desired.isEmpty()) throw new SQLException("Refusing to bootstrap an empty Velocity server set");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long revision = lockRevision(connection);
                Set<ServerId> current = new HashSet<>();
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT server_id FROM auth_servers WHERE enabled=TRUE");
                     ResultSet rows = query.executeQuery()) {
                    while (rows.next()) current.add(parseServer(rows.getString(1)));
                }
                Set<ServerId> scopeRows = new HashSet<>();
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT server_id FROM auth_scopes WHERE scope_type='SERVER'");
                     ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        String id = rows.getString(1);
                        if (id != null) scopeRows.add(parseServer(id));
                    }
                }
                boolean changed = !current.equals(desired) || !scopeRows.containsAll(desired);
                for (ServerId oldServer : current) {
                    if (desired.contains(oldServer)) continue;
                    try (PreparedStatement disable = connection.prepareStatement(
                            "UPDATE auth_servers SET enabled=FALSE WHERE server_id=? AND enabled=TRUE")) {
                        disable.setString(1, oldServer.value());
                        disable.executeUpdate();
                    }
                }
                for (ServerId server : desired) {
                    try (PreparedStatement upsert = connection.prepareStatement(
                            "INSERT INTO auth_servers(server_id,display_name,enabled) VALUES (?,?,TRUE) "
                                    + "ON DUPLICATE KEY UPDATE display_name=VALUES(display_name), enabled=TRUE")) {
                        upsert.setString(1, server.value());
                        upsert.setString(2, server.value());
                        upsert.executeUpdate();
                    }
                    try (PreparedStatement scope = connection.prepareStatement(
                            "INSERT INTO auth_scopes(scope_key,scope_type,server_id) VALUES (?,'SERVER',?) "
                                    + "ON DUPLICATE KEY UPDATE scope_type='SERVER',server_id=VALUES(server_id)")) {
                        scope.setString(1, AuthorizationScope.server(server).scopeKey());
                        scope.setString(2, server.value());
                        scope.executeUpdate();
                    }
                }
                if (changed) {
                    long next = nextRevision(revision);
                    appendAudit(connection, next, "SERVICE", "wakeup-authorization-bootstrap", null,
                            null, null, "bootstrap_server_scopes", "{}");
                    updateRevision(connection, revision, next);
                    revision = next;
                }
                connection.commit();
                return revision;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public AuthorizationState loadState(Instant now) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            int previousIsolation = connection.getTransactionIsolation();
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                AuthorizationState loaded = readState(connection, now);
                connection.commit();
                return loaded;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            } finally {
                try {
                    connection.setAutoCommit(true);
                } finally {
                    connection.setTransactionIsolation(previousIsolation);
                }
            }
        }
    }

    /** Persists the latest display/lookup name without changing authorization revision or audit history. */
    public void rememberUsername(UUID uuid, String username) throws SQLException {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(username, "username");
        String normalized = username.strip();
        if (normalized.isEmpty() || normalized.length() > 16) {
            throw new IllegalArgumentException("username must be 1-16 characters");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "INSERT INTO auth_users(player_uuid,last_known_username) VALUES (?,?) "
                             + "ON DUPLICATE KEY UPDATE last_known_username=VALUES(last_known_username)")) {
            query.setString(1, uuid.toString());
            query.setString(2, normalized);
            query.executeUpdate();
        }
    }

    /** Lock -> validate -> mutate -> audit -> revision -> commit. Caller publishes only after reloading. */
    public long applyMutation(UUID actor, AuthorizationMutation mutation, Instant now) throws SQLException {
        Objects.requireNonNull(actor, "actor");
        return applyMutation("PLAYER", actor.toString(), actor, mutation, now, false);
    }

    /** Audited console break-glass; still constrained by the final-owner invariant. */
    public long applyConsoleMutation(String consoleId, AuthorizationMutation mutation, Instant now) throws SQLException {
        Objects.requireNonNull(consoleId, "consoleId");
        if (consoleId.isBlank() || consoleId.length() > 128) throw new IllegalArgumentException("invalid consoleId");
        return applyMutation("CONSOLE", consoleId, null, mutation, now, true);
    }

    private long applyMutation(String actorKind, String actorId, UUID actor,
                               AuthorizationMutation mutation, Instant now,
                               boolean consoleBreakGlass) throws SQLException {
        Objects.requireNonNull(mutation, "mutation");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long currentRevision = lockRevision(connection);
                AuthorizationState current = readState(connection, now);
                validateMutation(connection, current, actor, mutation, consoleBreakGlass);
                long nextRevision = nextRevision(currentRevision);
                writeMutation(connection, actor, mutation);
                AuthorizationState afterMutation = readState(connection, now);
                if (!afterMutation.hasOwnerManagementAuthority()) {
                    throw new SQLException("Mutation would remove the final global OWNER management authority");
                }
                UUID target = mutation.targetUser().orElse(null);
                RoleId role = mutation instanceof AuthorizationMutation.RoleAssignment assignment
                        ? assignment.role()
                        : mutation instanceof AuthorizationMutation.RolePermission rolePermission
                                ? rolePermission.targetRole()
                                : mutation instanceof AuthorizationMutation.CreateRole createRole
                                        ? createRole.targetRole()
                                        : mutation instanceof AuthorizationMutation.DeleteRole deleteRole
                                                ? deleteRole.targetRole()
                                                : mutation instanceof AuthorizationMutation.DefaultRole defaultRole
                                                        ? defaultRole.targetRole()
                                                        : mutation instanceof AuthorizationMutation.RolePriority priority
                                                                ? priority.targetRole() : null;
                AuthorizationScope scope = mutationScope(mutation);
                appendAudit(connection, nextRevision, actorKind, actorId, target,
                        role, scope, actionName(mutation), detailsJson(mutation));
                updateRevision(connection, currentRevision, nextRevision);
                connection.commit();
                return nextRevision;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private static AuthorizationState readState(Connection connection, Instant now) throws SQLException {
        long revision;
        Map<RoleId, AuthorizationState.RoleMetadata> roles = new HashMap<>();
        Map<Long, RoleId> roleKeys = new HashMap<>();
        Set<ServerId> servers = new HashSet<>();
        Map<UUID, List<RoleAssignment>> assignments = new HashMap<>();
        Map<UUID, List<DirectPermissionRule>> direct = new HashMap<>();
        Map<UUID, String> usernames = new HashMap<>();
        List<RolePermission> rolePermissions = new ArrayList<>();

        try (PreparedStatement query = connection.prepareStatement(
                "SELECT revision FROM auth_state WHERE state_key='authorization'");
             ResultSet rows = query.executeQuery()) {
            if (!rows.next()) throw new SQLException("Missing auth_state authorization row");
            revision = rows.getLong(1);
            if (revision < 0) throw new SQLException("Authorization revision exceeds supported signed 64-bit range");
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT server_id FROM auth_servers WHERE enabled=TRUE");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) servers.add(parseServer(rows.getString(1)));
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT player_uuid,last_known_username FROM auth_users WHERE last_known_username IS NOT NULL");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) usernames.put(parseUuid(rows.getString("player_uuid")),
                    rows.getString("last_known_username"));
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT role_id,role_key,management_priority,protected,default_for_players FROM auth_roles");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                String rawRole = rows.getString("role_key");
                RoleId id = RoleId.of(rawRole);
                if (!rawRole.equals(id.value())) throw new SQLException("Non-canonical role key in auth_roles");
                long dbId = rows.getLong("role_id");
                roles.put(id, new AuthorizationState.RoleMetadata(id, dbId,
                        rows.getInt("management_priority"), rows.getBoolean("protected"),
                        rows.getBoolean("default_for_players")));
                roleKeys.put(dbId, id);
            }
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT p.role_id,s.scope_key,p.permission_node,p.effect "
                        + "FROM auth_role_permissions p JOIN auth_scopes s ON s.scope_key=p.scope_key");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                RoleId role = roleKeys.get(rows.getLong("role_id"));
                if (role == null) continue;
                rolePermissions.add(new RolePermission(role,
                        parsePattern(rows.getString("permission_node")),
                        parseScope(rows.getString("scope_key")), PermissionEffect.valueOf(rows.getString("effect"))));
            }
        }
        Timestamp at = Timestamp.from(now);
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT u.player_uuid,r.role_key,s.scope_key FROM auth_user_roles u "
                        + "JOIN auth_roles r ON r.role_id=u.role_id JOIN auth_scopes s ON s.scope_key=u.scope_key "
                        + "WHERE u.expires_at IS NULL OR u.expires_at > ?")) {
            query.setTimestamp(1, at);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    UUID user = parseUuid(rows.getString("player_uuid"));
                    String rawRole = rows.getString("role_key");
                    RoleId role = RoleId.of(rawRole);
                    if (!rawRole.equals(role.value())) throw new SQLException("Non-canonical assigned role key");
                    assignments.computeIfAbsent(user, ignored -> new ArrayList<>()).add(
                            new RoleAssignment(role,
                                    parseScope(rows.getString("scope_key"))));
                }
            }
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT u.player_uuid,s.scope_key,u.permission_node,u.effect "
                        + "FROM auth_user_permissions u JOIN auth_scopes s ON s.scope_key=u.scope_key");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                UUID user = parseUuid(rows.getString("player_uuid"));
                direct.computeIfAbsent(user, ignored -> new ArrayList<>()).add(new DirectPermissionRule(
                        parsePattern(rows.getString("permission_node")),
                        parseScope(rows.getString("scope_key")), PermissionEffect.valueOf(rows.getString("effect"))));
            }
        }
        return AuthorizationState.build(revision, servers, roles, assignments, rolePermissions, direct, usernames);
    }

    private static void validateMutation(Connection connection, AuthorizationState state,
                                         UUID actor, AuthorizationMutation mutation,
                                         boolean consoleBreakGlass) throws SQLException {
        AuthorizationScope scope = mutationScope(mutation);
        requireActiveScope(connection, scope);
        boolean globalOperation = scope.type() == AuthorizationScope.Type.GLOBAL;
        PermissionNode managerNode = mutation instanceof AuthorizationMutation.UserPermission
                || mutation instanceof AuthorizationMutation.RolePermission
                ? PermissionNodes.STAFF_PERMISSIONS_MODIFY : PermissionNodes.STAFF_ROLES_MODIFY;
        boolean isOwner = consoleBreakGlass || ownsManagementScope(state, actor, scope);
        boolean mayManage = consoleBreakGlass || (globalOperation ? state.hasGlobal(actor, managerNode)
                : state.has(actor, managerNode, scope.serverId()));
        if (!mayManage) throw new SQLException("Actor lacks " + managerNode + " in requested scope");

        int actorPriority = consoleBreakGlass ? 65536 : priorityForScope(state, actor, scope);
        if (mutation instanceof AuthorizationMutation.RoleAssignment assignment) {
            AuthorizationState.RoleMetadata role = state.roles().get(assignment.role());
            if (role == null) throw new SQLException("Unknown role " + assignment.role());
            if (assignment.add() && actor != null && assignment.target().equals(actor) && !isOwner) {
                throw new SQLException("Self-assignment is not allowed for role grants");
            }
            int targetPriority = priorityForScope(state, assignment.target(), scope);
            boolean safeSelfRemoval = !assignment.add() && assignment.target().equals(actor);
            if (!isOwner && !safeSelfRemoval
                    && actorPriority <= Math.max(targetPriority, role.managementPriority())) {
                throw new SQLException("Manager may not modify an equal- or higher-authority target/role");
            }
            if (!assignment.add() && assignment.role().value().equals("OWNER")
                    && countOwnerAssignments(connection) <= 1) {
                throw new SQLException("Cannot remove the final OWNER assignment");
            }
        } else if (mutation instanceof AuthorizationMutation.RolePermission rolePermission) {
            AuthorizationState.RoleMetadata role = state.roles().get(rolePermission.targetRole());
            if (role == null) throw new SQLException("Unknown role " + rolePermission.targetRole());
            if (!isOwner && actorPriority <= role.managementPriority()) {
                throw new SQLException("Manager may not modify an equal- or higher-authority role");
            }
            if (!isOwner && rolePermission.effect().filter(effect -> effect == PermissionEffect.ALLOW).isPresent()
                    && !hasCapabilityInScope(state, actor, rolePermission.pattern(), scope)) {
                throw new SQLException("A manager cannot grant a capability they do not possess");
            }
        } else if (mutation instanceof AuthorizationMutation.CreateRole createRole) {
            if (state.roles().containsKey(createRole.targetRole())) {
                throw new SQLException("Role already exists: " + createRole.targetRole());
            }
            int ownerPriority = Optional.ofNullable(state.roles().get(RoleId.of("OWNER")))
                    .map(AuthorizationState.RoleMetadata::managementPriority).orElse(Integer.MAX_VALUE);
            if (createRole.managementPriority() >= ownerPriority) {
                throw new SQLException("A non-OWNER role may not equal or exceed OWNER management priority");
            }
            if (!isOwner && actorPriority <= createRole.managementPriority()) {
                throw new SQLException("A manager may create only roles with lower management priority");
            }
        } else if (mutation instanceof AuthorizationMutation.DefaultRole defaultRole) {
            AuthorizationState.RoleMetadata role = state.roles().get(defaultRole.targetRole());
            if (role == null) throw new SQLException("Unknown role " + defaultRole.targetRole());
            if (!isOwner) throw new SQLException("Only OWNER may change default-player roles");
            if (role.defaultForPlayers() == defaultRole.enabled()) {
                throw new SQLException("Role already has the requested default-player setting");
            }
            if (defaultRole.enabled()) {
                if (role.managementPriority() > 0) {
                    throw new SQLException("A role with management authority cannot become a player default");
                }
                boolean grantsStaffManagement = state.rolePermissions(defaultRole.targetRole()).stream()
                        .filter(permission -> permission.effect() == PermissionEffect.ALLOW)
                        .anyMatch(permission -> permission.pattern().matches(PermissionNodes.STAFF_ROLES_MODIFY)
                                || permission.pattern().matches(PermissionNodes.STAFF_PERMISSIONS_MODIFY));
                if (grantsStaffManagement) {
                    throw new SQLException("A role granting staff-management capabilities cannot become a player default");
                }
            } else if (state.roles().values().stream().filter(AuthorizationState.RoleMetadata::defaultForPlayers).count() <= 1) {
                throw new SQLException("At least one player default role must remain configured");
            }
        } else if (mutation instanceof AuthorizationMutation.RolePriority priority) {
            AuthorizationState.RoleMetadata role = state.roles().get(priority.targetRole());
            if (role == null) throw new SQLException("Unknown role " + priority.targetRole());
            if (!isOwner && (actorPriority <= role.managementPriority()
                    || actorPriority <= priority.managementPriority())) {
                throw new SQLException("Manager may only change priority for roles below their own authority");
            }
            if (priority.targetRole().value().equals("OWNER")) {
                int highestOther = state.roles().values().stream()
                        .filter(other -> !other.id().equals(priority.targetRole()))
                        .mapToInt(AuthorizationState.RoleMetadata::managementPriority).max().orElse(-1);
                if (priority.managementPriority() <= highestOther) {
                    throw new SQLException("OWNER must retain the highest management priority");
                }
            } else {
                int ownerPriority = Optional.ofNullable(state.roles().get(RoleId.of("OWNER")))
                        .map(AuthorizationState.RoleMetadata::managementPriority).orElse(Integer.MAX_VALUE);
                if (priority.managementPriority() >= ownerPriority) {
                    throw new SQLException("A non-OWNER role may not equal or exceed OWNER management priority");
                }
            }
        } else if (mutation instanceof AuthorizationMutation.DeleteRole deleteRole) {
            AuthorizationState.RoleMetadata role = state.roles().get(deleteRole.targetRole());
            if (role == null) throw new SQLException("Unknown role " + deleteRole.targetRole());
            if (role.protectedRole() || role.defaultForPlayers()
                    || deleteRole.targetRole().value().equals("OWNER")) {
                throw new SQLException("Protected role cannot be deleted: " + deleteRole.targetRole());
            }
            if (!isOwner && actorPriority <= role.managementPriority()) {
                throw new SQLException("Manager may not delete an equal- or higher-authority role");
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT 1 FROM auth_user_roles ur JOIN auth_roles r ON r.role_id=ur.role_id "
                            + "WHERE r.role_key=? LIMIT 1")) {
                query.setString(1, deleteRole.targetRole().value());
                try (ResultSet rows = query.executeQuery()) {
                    if (rows.next()) throw new SQLException("Remove all role assignments before deleting the role");
                }
            }
        } else if (mutation instanceof AuthorizationMutation.UserPermission permission) {
            int targetPriority = priorityForScope(state, permission.target(), scope);
            boolean safeSelfRestriction = actor != null && permission.target().equals(actor)
                    && permission.effect().filter(effect -> effect == PermissionEffect.ALLOW).isEmpty();
            if (!isOwner && !safeSelfRestriction && actorPriority <= targetPriority) {
                throw new SQLException("Manager may not modify an equal- or higher-authority target");
            }
            if (!isOwner && actor != null && permission.target().equals(actor)
                    && permission.effect().filter(effect -> effect == PermissionEffect.ALLOW).isPresent()) {
                throw new SQLException("Self-grant requires OWNER authority");
            }
            if (!isOwner && permission.effect().filter(effect -> effect == PermissionEffect.ALLOW).isPresent()
                    && !hasCapabilityInScope(state, actor, permission.pattern(), scope)) {
                throw new SQLException("A manager cannot grant a capability they do not possess");
            }
        }
    }

    private static boolean hasCapabilityInScope(AuthorizationState state, UUID actor,
                                                PermissionPattern pattern, AuthorizationScope scope) {
        if (!pattern.isExact()) return false;
        PermissionNode node = PermissionNode.of(pattern.value());
        return scope.type() == AuthorizationScope.Type.GLOBAL
                ? state.hasGlobal(actor, node) : state.has(actor, node, scope.serverId());
    }

    private static AuthorizationScope mutationScope(AuthorizationMutation mutation) {
        if (mutation instanceof AuthorizationMutation.RoleAssignment value) return value.scope();
        if (mutation instanceof AuthorizationMutation.UserPermission value) return value.scope();
        if (mutation instanceof AuthorizationMutation.RolePermission value) return value.scope();
        return AuthorizationScope.global();
    }

    private static boolean ownsManagementScope(AuthorizationState state, UUID actor, AuthorizationScope scope) {
        return state.assignments(actor).stream().anyMatch(assignment ->
                assignment.roleId().value().equals("OWNER")
                        && (scope.type() == AuthorizationScope.Type.GLOBAL
                            ? assignment.scope().type() == AuthorizationScope.Type.GLOBAL
                            : assignment.scope().appliesTo(scope.serverId())));
    }

    private static int priorityForScope(AuthorizationState state, UUID actor, AuthorizationScope scope) {
        int assigned = state.assignments(actor).stream()
                .filter(assignment -> scope.type() == AuthorizationScope.Type.GLOBAL
                        ? assignment.scope().type() == AuthorizationScope.Type.GLOBAL
                        : assignment.scope().appliesTo(scope.serverId()))
                .map(assignment -> state.roles().get(assignment.roleId()))
                .filter(Objects::nonNull)
                .mapToInt(AuthorizationState.RoleMetadata::managementPriority)
                .max().orElse(-1);
        int defaults = state.roles().values().stream().filter(AuthorizationState.RoleMetadata::defaultForPlayers)
                .mapToInt(AuthorizationState.RoleMetadata::managementPriority).max().orElse(-1);
        return Math.max(assigned, defaults);
    }

    private static int countOwnerAssignments(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM auth_user_roles ur JOIN auth_roles r ON r.role_id=ur.role_id "
                        + "WHERE r.role_key='OWNER' AND (ur.expires_at IS NULL OR ur.expires_at>CURRENT_TIMESTAMP(3))");
             ResultSet rows = query.executeQuery()) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static void writeMutation(Connection connection, UUID actor,
                                      AuthorizationMutation mutation) throws SQLException {
        if (mutation instanceof AuthorizationMutation.RoleAssignment assignment) {
            if (assignment.add()) {
                ensureUser(connection, assignment.target());
                if (actor != null) ensureUser(connection, actor);
                try (PreparedStatement query = connection.prepareStatement(
                        "INSERT INTO auth_user_roles(player_uuid,role_id,scope_key,assigned_by_uuid) "
                                + "SELECT ?,role_id,?,? FROM auth_roles WHERE role_key=? "
                                + "ON DUPLICATE KEY UPDATE assigned_by_uuid=VALUES(assigned_by_uuid),assigned_at=CURRENT_TIMESTAMP(3),expires_at=NULL")) {
                    query.setString(1, assignment.target().toString());
                    query.setString(2, assignment.scope().scopeKey());
                    query.setString(3, actor == null ? null : actor.toString());
                    query.setString(4, assignment.role().value());
                    if (query.executeUpdate() == 0) throw new SQLException("Role assignment insert matched no role");
                }
            } else {
                try (PreparedStatement query = connection.prepareStatement(
                        "DELETE ur FROM auth_user_roles ur JOIN auth_roles r ON r.role_id=ur.role_id "
                                + "WHERE ur.player_uuid=? AND ur.scope_key=? AND r.role_key=?")) {
                    query.setString(1, assignment.target().toString());
                    query.setString(2, assignment.scope().scopeKey());
                    query.setString(3, assignment.role().value());
                    if (query.executeUpdate() == 0) throw new SQLException("Role assignment does not exist");
                }
            }
        } else if (mutation instanceof AuthorizationMutation.UserPermission permission) {
            if (permission.effect().isEmpty()) {
                try (PreparedStatement query = connection.prepareStatement(
                        "DELETE FROM auth_user_permissions WHERE player_uuid=? AND scope_key=? AND permission_node=?")) {
                    query.setString(1, permission.target().toString());
                    query.setString(2, permission.scope().scopeKey());
                    query.setString(3, permission.pattern().value());
                    query.executeUpdate();
                }
            } else {
                ensureUser(connection, permission.target());
                try (PreparedStatement query = connection.prepareStatement(
                        "INSERT INTO auth_user_permissions(player_uuid,scope_key,permission_node,effect) VALUES (?,?,?,?) "
                                + "ON DUPLICATE KEY UPDATE effect=VALUES(effect)")) {
                    query.setString(1, permission.target().toString());
                    query.setString(2, permission.scope().scopeKey());
                    query.setString(3, permission.pattern().value());
                    query.setString(4, permission.effect().orElseThrow().name());
                    query.executeUpdate();
                }
            }
        } else if (mutation instanceof AuthorizationMutation.RolePermission permission) {
            if (permission.effect().isEmpty()) {
                try (PreparedStatement query = connection.prepareStatement(
                        "DELETE p FROM auth_role_permissions p JOIN auth_roles r ON r.role_id=p.role_id "
                                + "WHERE r.role_key=? AND p.scope_key=? AND p.permission_node=?")) {
                    query.setString(1, permission.targetRole().value());
                    query.setString(2, permission.scope().scopeKey());
                    query.setString(3, permission.pattern().value());
                    query.executeUpdate();
                }
            } else {
                try (PreparedStatement query = connection.prepareStatement(
                        "INSERT INTO auth_role_permissions(role_id,scope_key,permission_node,effect) "
                                + "SELECT role_id,?,?,? FROM auth_roles WHERE role_key=? "
                                + "ON DUPLICATE KEY UPDATE effect=VALUES(effect)")) {
                    query.setString(1, permission.scope().scopeKey());
                    query.setString(2, permission.pattern().value());
                    query.setString(3, permission.effect().orElseThrow().name());
                    query.setString(4, permission.targetRole().value());
                    if (query.executeUpdate() == 0) throw new SQLException("Role permission insert matched no role");
                }
            }
        } else if (mutation instanceof AuthorizationMutation.CreateRole role) {
            try (PreparedStatement query = connection.prepareStatement(
                    "INSERT INTO auth_roles(role_key,display_name,management_priority,protected) VALUES (?,?,?,FALSE)")) {
                query.setString(1, role.targetRole().value());
                query.setString(2, role.displayName());
                query.setInt(3, role.managementPriority());
                query.executeUpdate();
            }
        } else if (mutation instanceof AuthorizationMutation.DeleteRole role) {
            try (PreparedStatement query = connection.prepareStatement("DELETE FROM auth_roles WHERE role_key=?")) {
                query.setString(1, role.targetRole().value());
                if (query.executeUpdate() != 1) throw new SQLException("Role does not exist");
            }
        } else if (mutation instanceof AuthorizationMutation.DefaultRole role) {
            try (PreparedStatement query = connection.prepareStatement(
                    "UPDATE auth_roles SET default_for_players=? WHERE role_key=?")) {
                query.setBoolean(1, role.enabled());
                query.setString(2, role.targetRole().value());
                if (query.executeUpdate() != 1) throw new SQLException("Role default setting was not updated");
            }
        } else if (mutation instanceof AuthorizationMutation.RolePriority priority) {
            try (PreparedStatement query = connection.prepareStatement(
                    "UPDATE auth_roles SET management_priority=? WHERE role_key=?")) {
                query.setInt(1, priority.managementPriority());
                query.setString(2, priority.targetRole().value());
                if (query.executeUpdate() != 1) throw new SQLException("Role priority was not updated");
            }
        }
    }

    private static void ensureUser(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "INSERT IGNORE INTO auth_users(player_uuid) VALUES (?)")) {
            query.setString(1, uuid.toString());
            query.executeUpdate();
        }
    }

    private static void requireActiveScope(Connection connection, AuthorizationScope scope) throws SQLException {
        if (scope.type() == AuthorizationScope.Type.GLOBAL) return;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM auth_servers s JOIN auth_scopes c ON c.server_id=s.server_id "
                        + "WHERE s.server_id=? AND s.enabled=TRUE AND c.scope_key=?")) {
            query.setString(1, scope.serverId().value());
            query.setString(2, scope.scopeKey());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("Server scope is unknown or disabled: " + scope.serverId());
            }
        }
    }

    private static long lockRevision(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT revision FROM auth_state WHERE state_key='authorization' FOR UPDATE");
             ResultSet rows = query.executeQuery()) {
            if (!rows.next()) throw new SQLException("Missing auth_state authorization row");
            long revision = rows.getLong(1);
            if (revision < 0) throw new SQLException("Authorization revision exceeds supported signed 64-bit range");
            return revision;
        }
    }

    private static long nextRevision(long revision) throws SQLException {
        if (revision == Long.MAX_VALUE) throw new SQLException("Authorization revision is exhausted");
        return revision + 1;
    }

    private static void updateRevision(Connection connection, long before, long after) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "UPDATE auth_state SET revision=?,updated_at=CURRENT_TIMESTAMP(3) "
                        + "WHERE state_key='authorization' AND revision=?")) {
            query.setLong(1, after);
            query.setLong(2, before);
            if (query.executeUpdate() != 1) throw new SQLException("Authorization revision changed unexpectedly");
        }
    }

    private static void appendAudit(Connection connection, long revision, String actorKind,
                                    String actorId, UUID target, RoleId role,
                                    AuthorizationScope scope, String action, String details) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "INSERT INTO auth_audit_log(revision,actor_kind,actor_id,target_player_uuid,role_id,"
                        + "role_key_snapshot,scope_key,scope_key_snapshot,action,details_json) "
                        + "VALUES (?,?,?, ?, (SELECT role_id FROM auth_roles WHERE role_key=?), ?, ?, ?, ?, ?)")) {
            query.setLong(1, revision);
            query.setString(2, actorKind);
            query.setString(3, actorId);
            query.setString(4, target == null ? null : target.toString());
            query.setString(5, role == null ? null : role.value());
            query.setString(6, role == null ? null : role.value());
            query.setString(7, scope == null ? null : scope.scopeKey());
            query.setString(8, scope == null ? null : scope.scopeKey());
            query.setString(9, action);
            query.setString(10, details);
            query.executeUpdate();
        }
    }

    private static String actionName(AuthorizationMutation mutation) {
        if (mutation instanceof AuthorizationMutation.RoleAssignment value) {
            return value.add() ? "user_role_add" : "user_role_remove";
        }
        if (mutation instanceof AuthorizationMutation.UserPermission value) {
            return value.effect().isPresent() ? "user_permission_set" : "user_permission_remove";
        }
        if (mutation instanceof AuthorizationMutation.RolePermission) {
            AuthorizationMutation.RolePermission value = (AuthorizationMutation.RolePermission) mutation;
            return value.effect().isPresent() ? "role_permission_set" : "role_permission_remove";
        }
        if (mutation instanceof AuthorizationMutation.CreateRole) return "role_create";
        if (mutation instanceof AuthorizationMutation.DefaultRole) return "role_default_for_players_set";
        if (mutation instanceof AuthorizationMutation.RolePriority) return "role_priority_set";
        return "role_delete";
    }

    private static String detailsJson(AuthorizationMutation mutation) {
        if (mutation instanceof AuthorizationMutation.UserPermission value) {
            return "{\"permission\":\"" + value.pattern().value() + "\",\"effect\":\""
                    + value.effect().map(Enum::name).orElse("REMOVE") + "\"}";
        }
        if (mutation instanceof AuthorizationMutation.RolePermission value) {
            return "{\"permission\":\"" + value.pattern().value() + "\",\"effect\":\""
                    + value.effect().map(Enum::name).orElse("REMOVE") + "\"}";
        }
        if (mutation instanceof AuthorizationMutation.RoleAssignment value) {
            return "{\"operation\":\"" + (value.add() ? "ADD" : "REMOVE") + "\"}";
        }
        if (mutation instanceof AuthorizationMutation.CreateRole value) {
            return "{\"display_name\":\"" + escapeJson(value.displayName())
                    + "\",\"management_priority\":" + value.managementPriority() + "}";
        }
        if (mutation instanceof AuthorizationMutation.DefaultRole value) {
            return "{\"default_for_players\":" + value.enabled() + "}";
        }
        if (mutation instanceof AuthorizationMutation.RolePriority value) {
            return "{\"management_priority\":" + value.managementPriority() + "}";
        }
        return "{}";
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) escaped.append(String.format("\\u%04x", (int) ch));
                    else escaped.append(ch);
                }
            }
        }
        return escaped.toString();
    }

    private static AuthorizationScope parseScope(String key) throws SQLException {
        if (key.equals("GLOBAL")) return AuthorizationScope.global();
        if (key.startsWith("SERVER:")) {
            ServerId server = ServerId.of(key.substring(7));
            if (!key.equals(AuthorizationScope.server(server).scopeKey())) {
                throw new SQLException("Non-canonical authorization scope key " + key);
            }
            return AuthorizationScope.server(server);
        }
        throw new SQLException("Unknown authorization scope key " + key);
    }

    private static PermissionPattern parsePattern(String stored) throws SQLException {
        PermissionPattern parsed = PermissionPattern.of(stored);
        if (!stored.equals(parsed.value())) throw new SQLException("Non-canonical permission node in auth_* tables");
        return parsed;
    }

    private static UUID parseUuid(String stored) throws SQLException {
        UUID parsed = UUID.fromString(stored);
        if (!stored.equals(parsed.toString())) throw new SQLException("Non-canonical player UUID in auth_* tables");
        return parsed;
    }

    private static ServerId parseServer(String stored) throws SQLException {
        ServerId parsed = ServerId.of(stored);
        if (!stored.equals(parsed.value())) throw new SQLException("Non-canonical server ID in auth_* tables");
        return parsed;
    }

    private static void rollback(Connection connection, Throwable failure) {
        try { connection.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
    }
}

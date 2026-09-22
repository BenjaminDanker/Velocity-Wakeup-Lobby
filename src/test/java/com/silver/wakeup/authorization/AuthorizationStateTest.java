package com.silver.wakeup.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.silver.authorization.AuthorizationScope;
import com.silver.authorization.AuthorizationSubject;
import com.silver.authorization.DirectPermissionRule;
import com.silver.authorization.PermissionEffect;
import com.silver.authorization.PermissionNode;
import com.silver.authorization.PermissionPattern;
import com.silver.authorization.PermissionNodes;
import com.silver.authorization.RoleAssignment;
import com.silver.authorization.RoleId;
import com.silver.authorization.RolePermission;
import com.silver.authorization.ServerId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthorizationStateTest {
    private static final ServerId SKY = ServerId.of("sky-island");
    private static final ServerId MAGIC = ServerId.of("magic");
    private static final RoleId PLAYER = RoleId.of("PLAYER");
    private static final RoleId MODERATOR = RoleId.of("MODERATOR");
    private static final RoleId ADMIN = RoleId.of("ADMIN");
    private static final RoleId OWNER = RoleId.of("OWNER");
    private static final RoleId DEVELOPER = RoleId.of("DEVELOPER");

    @Test
    void immutableStateBuildsFastUserServerRulesWithDefaultPlayerAndScopedOverrides() {
        UUID admin = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID moderator = UUID.randomUUID();
        UUID developer = UUID.randomUUID();
        var roles = Map.of(
                PLAYER, metadata(PLAYER, 1, 0, false, true),
                MODERATOR, metadata(MODERATOR, 2, 300, false, false),
                ADMIN, metadata(ADMIN, 3, 700, false, false),
                OWNER, metadata(OWNER, 4, 1000, true, false),
                DEVELOPER, metadata(DEVELOPER, 5, 250, false, false));
        var rolePermissions = List.of(
                new RolePermission(PLAYER, PermissionPattern.of("aipets.use"), AuthorizationScope.global(), PermissionEffect.ALLOW),
                new RolePermission(PLAYER, PermissionPattern.of("aipets.adopt"), AuthorizationScope.global(), PermissionEffect.ALLOW),
                new RolePermission(PLAYER, PermissionPattern.of("aipets.chat"), AuthorizationScope.global(), PermissionEffect.ALLOW),
                new RolePermission(PLAYER, PermissionPattern.of("aipets.compass"), AuthorizationScope.global(), PermissionEffect.ALLOW),
                new RolePermission(PLAYER, PermissionPattern.of("aipets.recall"), AuthorizationScope.global(), PermissionEffect.ALLOW),
                new RolePermission(ADMIN, PermissionPattern.of("aipets.admin.*"), AuthorizationScope.global(), PermissionEffect.ALLOW),
                new RolePermission(OWNER, PermissionPattern.of("*"), AuthorizationScope.global(), PermissionEffect.ALLOW),
                new RolePermission(MODERATOR, PermissionPattern.of("player.kick"), AuthorizationScope.global(), PermissionEffect.ALLOW),
                new RolePermission(DEVELOPER, PermissionPattern.of("developer.debug"), AuthorizationScope.global(), PermissionEffect.ALLOW));
        var assignments = Map.of(
                admin, List.of(new RoleAssignment(ADMIN, AuthorizationScope.global())),
                owner, List.of(new RoleAssignment(OWNER, AuthorizationScope.global())),
                moderator, List.of(new RoleAssignment(MODERATOR, AuthorizationScope.server(SKY))),
                developer, List.of(new RoleAssignment(DEVELOPER, AuthorizationScope.global())));
        var direct = Map.of(owner, List.of(new DirectPermissionRule(
                PermissionPattern.of("aipets.admin.inspect"), AuthorizationScope.server(SKY), PermissionEffect.DENY)));

        AuthorizationState state = AuthorizationState.build(29, Set.of(SKY, MAGIC), roles,
                assignments, rolePermissions, direct);

        UUID ordinary = UUID.randomUUID();
        assertPublicPetPermissions(state, ordinary, SKY);
        assertPublicPetPermissions(state, moderator, MAGIC);
        assertPublicPetPermissions(state, admin, MAGIC);
        assertPublicPetPermissions(state, developer, SKY);
        assertFalse(state.assignments(moderator).stream().anyMatch(a -> a.roleId().equals(PLAYER)),
                "MODERATOR receives the configured baseline without an explicit PLAYER assignment");
        assertFalse(state.assignments(admin).stream().anyMatch(a -> a.roleId().equals(PLAYER)),
                "ADMIN receives the configured baseline without an explicit PLAYER assignment");
        assertTrue(state.has(admin, PermissionNodes.AIPETS_ADMIN_INSPECT, MAGIC));
        assertTrue(state.has(developer, PermissionNode.of("developer.debug"), SKY));
        assertTrue(state.has(owner, PermissionNodes.AIPETS_ADMIN_RECOVER, MAGIC));
        assertFalse(state.has(owner, PermissionNodes.AIPETS_ADMIN_INSPECT, SKY));
        assertTrue(state.has(moderator, PermissionNode.of("player.kick"), SKY));
        assertFalse(state.has(moderator, PermissionNode.of("player.kick"), MAGIC));
        assertEquals(0, state.managementPriority(ordinary, MAGIC), "the default PLAYER role contributes management rank");
        assertEquals(700, state.managementPriority(admin, MAGIC));
        assertEquals(300, state.managementPriority(moderator, SKY));
        assertEquals(0, state.managementPriority(moderator, MAGIC), "only the default PLAYER priority applies off-scope");
        assertTrue(state.decideGlobal(owner, PermissionNodes.AIPETS_ADMIN_INSPECT).allowed(),
                "global explanation ignores server-only overrides");
        assertEquals(29, state.decide(AuthorizationSubject.player(owner), PermissionNodes.AIPETS_ADMIN_INSPECT, SKY).authorizationRevision());
        assertEquals(OWNER, state.decide(AuthorizationSubject.player(owner), PermissionNodes.AIPETS_ADMIN_INSPECT, SKY)
                .consideredRules().stream().filter(trace -> !trace.winner()).findFirst().orElseThrow()
                .rule().sourceRole().orElseThrow());
        assertFalse(state.has(AuthorizationSubject.console(), PermissionNodes.AIPETS_USE, SKY));
        assertFalse(state.has(AuthorizationSubject.service("pet-service"), PermissionNodes.AIPETS_USE, SKY));
    }

    private static void assertPublicPetPermissions(AuthorizationState state, UUID subject, ServerId server) {
        for (PermissionNode node : List.of(PermissionNodes.AIPETS_USE, PermissionNodes.AIPETS_ADOPT,
                PermissionNodes.AIPETS_CHAT, PermissionNodes.AIPETS_COMPASS, PermissionNodes.AIPETS_RECALL)) {
            assertTrue(state.has(subject, node, server), subject + " should retain " + node);
        }
    }

    private static AuthorizationState.RoleMetadata metadata(RoleId id, long databaseId, int priority,
                                                            boolean protectedRole, boolean defaultForPlayers) {
        return new AuthorizationState.RoleMetadata(id, databaseId, priority, protectedRole, defaultForPlayers);
    }
}

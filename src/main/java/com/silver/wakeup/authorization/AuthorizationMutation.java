package com.silver.wakeup.authorization;

import com.silver.authorization.AuthorizationScope;
import com.silver.authorization.PermissionEffect;
import com.silver.authorization.PermissionPattern;
import com.silver.authorization.RoleId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Validated, auditable writes supported by the authorization service. */
public sealed interface AuthorizationMutation {
    default Optional<UUID> targetUser() { return Optional.empty(); }

    record RoleAssignment(UUID target, RoleId role, AuthorizationScope scope, boolean add)
            implements AuthorizationMutation {
        public RoleAssignment {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(scope, "scope");
        }
        @Override public Optional<UUID> targetUser() { return Optional.of(target); }
    }

    record UserPermission(UUID target, PermissionPattern pattern, AuthorizationScope scope,
                          Optional<PermissionEffect> effect) implements AuthorizationMutation {
        public UserPermission {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(pattern, "pattern");
            Objects.requireNonNull(scope, "scope");
            effect = Objects.requireNonNull(effect, "effect");
        }
        @Override public Optional<UUID> targetUser() { return Optional.of(target); }
    }

    record RolePermission(RoleId targetRole, PermissionPattern pattern, AuthorizationScope scope,
                          Optional<PermissionEffect> effect) implements AuthorizationMutation {
        public RolePermission {
            Objects.requireNonNull(targetRole, "targetRole");
            Objects.requireNonNull(pattern, "pattern");
            Objects.requireNonNull(scope, "scope");
            effect = Objects.requireNonNull(effect, "effect");
        }

    }

    record CreateRole(RoleId targetRole, String displayName, int managementPriority)
            implements AuthorizationMutation {
        public CreateRole {
            Objects.requireNonNull(targetRole, "targetRole");
            Objects.requireNonNull(displayName, "displayName");
            displayName = displayName.trim();
            if (displayName.isEmpty() || displayName.length() > 64) {
                throw new IllegalArgumentException("Role display name must be 1-64 characters");
            }
            if (managementPriority < 0 || managementPriority > 65535) {
                throw new IllegalArgumentException("managementPriority must fit an unsigned SMALLINT");
            }
        }
    }

    record DeleteRole(RoleId targetRole) implements AuthorizationMutation {
        public DeleteRole { Objects.requireNonNull(targetRole, "targetRole"); }
    }

    /** Changes a role's data-driven baseline applicability for all player subjects. */
    record DefaultRole(RoleId targetRole, boolean enabled) implements AuthorizationMutation {
        public DefaultRole { Objects.requireNonNull(targetRole, "targetRole"); }
    }

    record RolePriority(RoleId targetRole, int managementPriority) implements AuthorizationMutation {
        public RolePriority {
            Objects.requireNonNull(targetRole, "targetRole");
            if (managementPriority < 0 || managementPriority > 65535) {
                throw new IllegalArgumentException("managementPriority must fit an unsigned SMALLINT");
            }
        }
    }
}

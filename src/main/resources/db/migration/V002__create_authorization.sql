CREATE TABLE IF NOT EXISTS auth_servers (
    server_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    display_name VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB
;

CREATE TABLE IF NOT EXISTS auth_scopes (
    scope_key VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    scope_type ENUM('GLOBAL','SERVER') NOT NULL,
    server_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_auth_scopes_server_id (server_id),
    CONSTRAINT fk_auth_scopes_server FOREIGN KEY (server_id)
        REFERENCES auth_servers(server_id) ON DELETE CASCADE
) ENGINE=InnoDB
;

CREATE TABLE IF NOT EXISTS auth_users (
    player_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    last_known_username VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    first_seen_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB
;

CREATE TABLE IF NOT EXISTS auth_roles (
    role_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    role_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    display_name VARCHAR(64) NOT NULL,
    management_priority SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    protected BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB
;

CREATE TABLE IF NOT EXISTS auth_user_roles (
    player_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role_id BIGINT UNSIGNED NOT NULL,
    scope_key VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assigned_by_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    assigned_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at TIMESTAMP(3) NULL,
    PRIMARY KEY (player_uuid, role_id, scope_key),
    INDEX ix_auth_user_roles_scope (scope_key, role_id, player_uuid),
    CONSTRAINT fk_auth_user_roles_user FOREIGN KEY (player_uuid)
        REFERENCES auth_users(player_uuid) ON DELETE CASCADE,
    CONSTRAINT fk_auth_user_roles_role FOREIGN KEY (role_id)
        REFERENCES auth_roles(role_id) ON DELETE CASCADE,
    CONSTRAINT fk_auth_user_roles_scope FOREIGN KEY (scope_key)
        REFERENCES auth_scopes(scope_key),
    CONSTRAINT fk_auth_user_roles_assigner FOREIGN KEY (assigned_by_uuid)
        REFERENCES auth_users(player_uuid) ON DELETE SET NULL
) ENGINE=InnoDB
;

CREATE TABLE IF NOT EXISTS auth_role_permissions (
    role_id BIGINT UNSIGNED NOT NULL,
    scope_key VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    permission_node VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    effect ENUM('ALLOW','DENY') NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (role_id, scope_key, permission_node),
    INDEX ix_auth_role_permissions_scope_node (scope_key, permission_node, role_id),
    CONSTRAINT fk_auth_role_permissions_role FOREIGN KEY (role_id)
        REFERENCES auth_roles(role_id) ON DELETE CASCADE,
    CONSTRAINT fk_auth_role_permissions_scope FOREIGN KEY (scope_key)
        REFERENCES auth_scopes(scope_key)
) ENGINE=InnoDB
;

CREATE TABLE IF NOT EXISTS auth_user_permissions (
    player_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_key VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    permission_node VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    effect ENUM('ALLOW','DENY') NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (player_uuid, scope_key, permission_node),
    INDEX ix_auth_user_permissions_scope_node (scope_key, permission_node, player_uuid),
    CONSTRAINT fk_auth_user_permissions_user FOREIGN KEY (player_uuid)
        REFERENCES auth_users(player_uuid) ON DELETE CASCADE,
    CONSTRAINT fk_auth_user_permissions_scope FOREIGN KEY (scope_key)
        REFERENCES auth_scopes(scope_key)
) ENGINE=InnoDB
;

CREATE TABLE IF NOT EXISTS auth_state (
    state_key VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB
;

CREATE TABLE IF NOT EXISTS auth_audit_log (
    audit_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    revision BIGINT UNSIGNED NULL,
    actor_kind ENUM('PLAYER','SERVICE','CONSOLE','COMMAND_BLOCK') NOT NULL,
    actor_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_player_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    role_id BIGINT UNSIGNED NULL,
    role_key_snapshot VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    scope_key VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NULL,
    scope_key_snapshot VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NULL,
    action VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    details_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    occurred_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX ix_auth_audit_revision (revision),
    INDEX ix_auth_audit_target (target_player_uuid, occurred_at),
    INDEX ix_auth_audit_actor (actor_kind, actor_id, occurred_at),
    INDEX ix_auth_audit_scope (scope_key, occurred_at),
    CONSTRAINT fk_auth_audit_role FOREIGN KEY (role_id)
        REFERENCES auth_roles(role_id) ON DELETE SET NULL,
    CONSTRAINT fk_auth_audit_scope FOREIGN KEY (scope_key)
        REFERENCES auth_scopes(scope_key) ON DELETE SET NULL
) ENGINE=InnoDB
;

INSERT IGNORE INTO auth_scopes(scope_key, scope_type, server_id) VALUES ('GLOBAL', 'GLOBAL', NULL)
;

INSERT IGNORE INTO auth_roles(role_key, display_name, management_priority, protected) VALUES
    ('OWNER', 'Owner', 1000, TRUE),
    ('ADMIN', 'Admin', 700, FALSE),
    ('MODERATOR', 'Moderator', 300, FALSE),
    ('PLAYER', 'Player', 0, FALSE)
;

INSERT IGNORE INTO auth_role_permissions(role_id, scope_key, permission_node, effect)
SELECT role_id, 'GLOBAL', '*', 'ALLOW' FROM auth_roles WHERE role_key = 'OWNER'
;

INSERT IGNORE INTO auth_state(state_key, revision) VALUES ('authorization', 0)
;

-- The UUID was checked against Mojang's profile endpoint and its reverse UUID lookup.
-- Keep the legacy velocity-ops.json entry until central-owner verification is complete.
INSERT IGNORE INTO auth_users(player_uuid, last_known_username)
VALUES ('ac4c25b5-7dd2-47c9-a6c0-7c08a3b5905b', 'SilverSphere95')
;
INSERT IGNORE INTO auth_user_roles(player_uuid, role_id, scope_key)
SELECT 'ac4c25b5-7dd2-47c9-a6c0-7c08a3b5905b', role_id, 'GLOBAL'
FROM auth_roles WHERE role_key = 'OWNER'
;

INSERT IGNORE INTO auth_role_permissions(role_id, scope_key, permission_node, effect)
SELECT role_id, 'GLOBAL', 'aipets.use', 'ALLOW' FROM auth_roles WHERE role_key = 'PLAYER'
;
INSERT IGNORE INTO auth_role_permissions(role_id, scope_key, permission_node, effect)
SELECT role_id, 'GLOBAL', 'aipets.adopt', 'ALLOW' FROM auth_roles WHERE role_key = 'PLAYER'
;
INSERT IGNORE INTO auth_role_permissions(role_id, scope_key, permission_node, effect)
SELECT role_id, 'GLOBAL', 'aipets.compass', 'ALLOW' FROM auth_roles WHERE role_key = 'PLAYER'
;
INSERT IGNORE INTO auth_role_permissions(role_id, scope_key, permission_node, effect)
SELECT role_id, 'GLOBAL', 'aipets.chat', 'ALLOW' FROM auth_roles WHERE role_key = 'PLAYER'
;
INSERT IGNORE INTO auth_role_permissions(role_id, scope_key, permission_node, effect)
SELECT role_id, 'GLOBAL', 'aipets.recall', 'ALLOW' FROM auth_roles WHERE role_key = 'PLAYER'
;
INSERT IGNORE INTO auth_role_permissions(role_id, scope_key, permission_node, effect)
SELECT role_id, 'GLOBAL', 'aipets.admin.*', 'ALLOW' FROM auth_roles WHERE role_key = 'ADMIN'
;
INSERT IGNORE INTO auth_role_permissions(role_id, scope_key, permission_node, effect)
SELECT role_id, 'GLOBAL', 'staff.roles.modify', 'ALLOW' FROM auth_roles WHERE role_key = 'ADMIN'
;
INSERT IGNORE INTO auth_role_permissions(role_id, scope_key, permission_node, effect)
SELECT role_id, 'GLOBAL', 'staff.permissions.modify', 'ALLOW' FROM auth_roles WHERE role_key = 'ADMIN'
;

UPDATE auth_state SET revision = revision + 1 WHERE state_key = 'authorization'
;
INSERT INTO auth_audit_log(revision, actor_kind, actor_id, target_player_uuid, role_id,
                           role_key_snapshot, scope_key_snapshot, action, details_json)
SELECT revision, 'SERVICE', 'wakeup-authorization-bootstrap',
       'ac4c25b5-7dd2-47c9-a6c0-7c08a3b5905b', role_id, 'OWNER', 'GLOBAL',
       'bootstrap_initial_owner', '{"identity":"verified_uuid"}'
FROM auth_state JOIN auth_roles ON auth_roles.role_key='OWNER'
WHERE auth_state.state_key='authorization'
;

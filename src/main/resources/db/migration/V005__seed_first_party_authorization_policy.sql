UPDATE auth_roles SET management_priority=1000 WHERE role_key='OWNER'
;
UPDATE auth_roles SET management_priority=700 WHERE role_key='ADMIN'
;
UPDATE auth_roles SET management_priority=300 WHERE role_key='MODERATOR'
;
UPDATE auth_roles SET management_priority=0 WHERE role_key='PLAYER'
;

INSERT IGNORE INTO auth_role_permissions(role_id,scope_key,permission_node,effect)
SELECT role_id,'GLOBAL','portal.use','ALLOW' FROM auth_roles WHERE role_key='PLAYER'
;

INSERT IGNORE INTO auth_role_permissions(role_id,scope_key,permission_node,effect)
SELECT role_id,'GLOBAL','spawnprotect.bypass','ALLOW' FROM auth_roles WHERE role_key='MODERATOR'
;
INSERT IGNORE INTO auth_role_permissions(role_id,scope_key,permission_node,effect)
SELECT role_id,'GLOBAL','mpds.progression.inspect','ALLOW' FROM auth_roles WHERE role_key='MODERATOR'
;
INSERT IGNORE INTO auth_role_permissions(role_id,scope_key,permission_node,effect)
SELECT role_id,'GLOBAL','mpds.skip.inspect','ALLOW' FROM auth_roles WHERE role_key='MODERATOR'
;
INSERT IGNORE INTO auth_role_permissions(role_id,scope_key,permission_node,effect)
SELECT role_id,'GLOBAL','mpds.inventory.metadata.inspect','ALLOW' FROM auth_roles WHERE role_key='MODERATOR'
;
INSERT IGNORE INTO auth_role_permissions(role_id,scope_key,permission_node,effect)
SELECT role_id,'GLOBAL','portal.registry.view','ALLOW' FROM auth_roles WHERE role_key='MODERATOR'
;

INSERT IGNORE INTO auth_role_permissions(role_id,scope_key,permission_node,effect)
SELECT role_id,'GLOBAL','wakeuplobby.manage','ALLOW' FROM auth_roles WHERE role_key='ADMIN'
;
INSERT IGNORE INTO auth_role_permissions(role_id,scope_key,permission_node,effect)
SELECT role_id,'GLOBAL','admission.manage','ALLOW' FROM auth_roles WHERE role_key='ADMIN'
;
INSERT IGNORE INTO auth_role_permissions(role_id,scope_key,permission_node,effect)
SELECT role_id,'GLOBAL','server.switch.force','ALLOW' FROM auth_roles WHERE role_key='ADMIN'
;
INSERT IGNORE INTO auth_role_permissions(role_id,scope_key,permission_node,effect)
SELECT role_id,'GLOBAL','wakeuplobby.server','ALLOW' FROM auth_roles WHERE role_key='ADMIN'
;
INSERT IGNORE INTO auth_role_permissions(role_id,scope_key,permission_node,effect)
SELECT role_id,'GLOBAL','wakeuplobby.selectors','ALLOW' FROM auth_roles WHERE role_key='ADMIN'
;
INSERT IGNORE INTO auth_role_permissions(role_id,scope_key,permission_node,effect)
SELECT role_id,'GLOBAL','portal.registry.view','ALLOW' FROM auth_roles WHERE role_key='ADMIN'
;
INSERT IGNORE INTO auth_role_permissions(role_id,scope_key,permission_node,effect)
SELECT role_id,'GLOBAL','portal.registry.manage','ALLOW' FROM auth_roles WHERE role_key='ADMIN'
;
INSERT IGNORE INTO auth_role_permissions(role_id,scope_key,permission_node,effect)
SELECT role_id,'GLOBAL','mpds.*','ALLOW' FROM auth_roles WHERE role_key='ADMIN'
;
INSERT IGNORE INTO auth_role_permissions(role_id,scope_key,permission_node,effect)
SELECT role_id,'GLOBAL','spawnprotect.bypass','ALLOW' FROM auth_roles WHERE role_key='ADMIN'
;
INSERT IGNORE INTO auth_role_permissions(role_id,scope_key,permission_node,effect)
SELECT role_id,'GLOBAL','dimensions.*','ALLOW' FROM auth_roles WHERE role_key='ADMIN'
;
INSERT IGNORE INTO auth_role_permissions(role_id,scope_key,permission_node,effect)
SELECT role_id,'GLOBAL','atlantis.*','ALLOW' FROM auth_roles WHERE role_key='ADMIN'
;
INSERT IGNORE INTO auth_role_permissions(role_id,scope_key,permission_node,effect)
SELECT role_id,'GLOBAL','skyislands.inspect','ALLOW' FROM auth_roles WHERE role_key='ADMIN'
;
INSERT IGNORE INTO auth_role_permissions(role_id,scope_key,permission_node,effect)
SELECT role_id,'GLOBAL','enderfight.reset','ALLOW' FROM auth_roles WHERE role_key='ADMIN'
;
INSERT IGNORE INTO auth_role_permissions(role_id,scope_key,permission_node,effect)
SELECT role_id,'GLOBAL','villagerinterface.devtest','ALLOW' FROM auth_roles WHERE role_key='ADMIN'
;
INSERT IGNORE INTO auth_role_permissions(role_id,scope_key,permission_node,effect)
SELECT role_id,'GLOBAL','border.bypass','ALLOW' FROM auth_roles WHERE role_key='ADMIN'
;

UPDATE auth_state
SET revision=revision+1,updated_at=CURRENT_TIMESTAMP(3)
WHERE state_key='authorization'
;

INSERT INTO auth_audit_log(revision,actor_kind,actor_id,scope_key_snapshot,action,details_json)
SELECT revision,'SERVICE','wakeup-authorization-first-party-policy','GLOBAL',
       'first_party_policy_seeded',
       '{"roles":["PLAYER","MODERATOR","ADMIN","OWNER"],"policy_version":1}'
FROM auth_state WHERE state_key='authorization'
;

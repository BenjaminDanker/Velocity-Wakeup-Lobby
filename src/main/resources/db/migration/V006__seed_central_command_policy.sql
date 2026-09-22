-- ADMIN receives centralized command-administration capabilities without becoming vanilla OP.
INSERT IGNORE INTO auth_role_permissions(role_id,scope_key,permission_node,effect)
SELECT role_id,'GLOBAL','commands.admin.*','ALLOW' FROM auth_roles WHERE role_key='ADMIN'
;

UPDATE auth_state
SET revision=revision+1,updated_at=CURRENT_TIMESTAMP(3)
WHERE state_key='authorization'
;

INSERT INTO auth_audit_log(revision,actor_kind,actor_id,scope_key_snapshot,action,details_json)
SELECT revision,'SERVICE','wakeup-central-command-policy','GLOBAL',
       'central_command_policy_seeded',
       '{"roles":["ADMIN"],"permission":"commands.admin.*","policy_version":1}'
FROM auth_state WHERE state_key='authorization'
;

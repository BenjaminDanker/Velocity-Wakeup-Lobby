ALTER TABLE auth_roles
    ADD COLUMN IF NOT EXISTS default_for_players BOOLEAN NOT NULL DEFAULT FALSE AFTER protected
;

UPDATE auth_roles
SET default_for_players=TRUE
WHERE role_key='PLAYER'
;

UPDATE auth_state
SET revision=revision+1, updated_at=CURRENT_TIMESTAMP(3)
WHERE state_key='authorization'
;

INSERT INTO auth_audit_log(revision, actor_kind, actor_id, scope_key_snapshot, action, details_json)
SELECT revision, 'SERVICE', 'wakeup-authorization-default-role-migration', 'GLOBAL',
       'default_player_roles_configured', '{"default_roles":["PLAYER"]}'
FROM auth_state
WHERE state_key='authorization'
;

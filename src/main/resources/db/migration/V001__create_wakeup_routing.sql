CREATE TABLE IF NOT EXISTS wakeup_schema_migrations (
    version INT NOT NULL PRIMARY KEY,
    description VARCHAR(255) NOT NULL,
    applied_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB
;
CREATE TABLE IF NOT EXISTS wakeup_player_state (
    player_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    preferred_server VARCHAR(64) NULL,
    last_successful_server VARCHAR(64) NULL,
    last_listed_server VARCHAR(64) NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB
;
CREATE TABLE IF NOT EXISTS wakeup_player_visits (
    player_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    server_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    first_visited_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_visited_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (player_uuid, server_name),
    CONSTRAINT fk_wakeup_visits_player FOREIGN KEY (player_uuid)
        REFERENCES wakeup_player_state(player_uuid) ON DELETE CASCADE
) ENGINE=InnoDB
;
CREATE TABLE IF NOT EXISTS wakeup_portal_transfers (
    transfer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    player_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_server VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_server VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    arrival_portal VARCHAR(128) NOT NULL DEFAULT '',
    status ENUM('PENDING','CLAIMED','COMPLETED','CANCELLED','EXPIRED') NOT NULL,
    issued_at TIMESTAMP(3) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    claimed_at TIMESTAMP(3) NULL,
    completed_at TIMESTAMP(3) NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX ix_wakeup_transfer_player_status (player_uuid, status),
    INDEX ix_wakeup_transfer_expiry (status, expires_at)
) ENGINE=InnoDB
;

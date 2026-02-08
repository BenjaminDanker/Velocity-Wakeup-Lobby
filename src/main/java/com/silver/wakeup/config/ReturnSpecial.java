package com.silver.wakeup.config;

import java.util.Objects;

/**
 * Represents a special item type to remove when a player uses /return.
 *
 * <p>Removal is executed on the destination server via an MPDS command using the
 * provided custom-data key/value pair. The displayName is used for player-facing messages.
 */
public record ReturnSpecial(String key, String value, String displayName) {
    public ReturnSpecial {
        key = Objects.requireNonNull(key, "key");
        value = Objects.requireNonNull(value, "value");
        displayName = Objects.requireNonNull(displayName, "displayName");
    }
}

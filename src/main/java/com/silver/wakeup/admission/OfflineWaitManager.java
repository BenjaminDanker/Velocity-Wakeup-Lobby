package com.silver.wakeup.admission;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds fresh players whose current preferred backend is offline.
 *
 * <p>No destination is stored here. The destination is always re-derived from
 * Velocity's existing routing state, so a changed return/preferred destination
 * is picked up automatically.</p>
 */
public final class OfflineWaitManager {
    public record Entry(UUID playerId, long waitingSinceMs) {
    }

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    public boolean add(UUID playerId) {
        Entry entry = new Entry(playerId, System.currentTimeMillis());
        return entries.putIfAbsent(playerId, entry) == null;
    }

    public void remove(UUID playerId) {
        entries.remove(playerId);
    }

    public boolean contains(UUID playerId) {
        return entries.containsKey(playerId);
    }

    public long waitingSinceMs(UUID playerId) {
        Entry entry = entries.get(playerId);
        return entry == null ? -1L : entry.waitingSinceMs();
    }

    public List<Entry> snapshot() {
        return List.copyOf(entries.values());
    }

    public int size() {
        return entries.size();
    }
}

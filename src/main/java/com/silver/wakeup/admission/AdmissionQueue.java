package com.silver.wakeup.admission;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Priority/FIFO queue for fresh players whose chosen backend is currently online.
 *
 * <p>The queue deliberately stores no destination. Velocity derives the destination
 * from its existing player-routing state when the player's turn is processed.</p>
 */
public final class AdmissionQueue {
    public enum Tier {
        NORMAL(0),
        VIP(100),
        BIG_VIP(200),
        ADMIN(300);

        private final int priority;

        Tier(int priority) {
            this.priority = priority;
        }

        int priority() {
            return priority;
        }
    }

    public record Entry(UUID playerId, Tier tier, long sequence) {
    }

    private static final Comparator<Entry> ORDER =
            Comparator.<Entry>comparingInt(entry -> entry.tier().priority())
                    .reversed()
                    .thenComparingLong(Entry::sequence);

    private final AtomicLong sequence = new AtomicLong();
    private final PriorityBlockingQueue<Entry> queue = new PriorityBlockingQueue<>(16, ORDER);
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    public boolean enqueue(UUID playerId, Tier tier) {
        Entry entry = new Entry(playerId, tier, sequence.getAndIncrement());
        Entry existing = entries.putIfAbsent(playerId, entry);
        if (existing != null) {
            return false;
        }

        queue.add(entry);
        return true;
    }

    public Entry poll() {
        while (true) {
            Entry entry = queue.poll();
            if (entry == null) {
                return null;
            }

            if (entries.remove(entry.playerId(), entry)) {
                return entry;
            }
        }
    }

    public void remove(UUID playerId) {
        entries.remove(playerId);
    }

    public boolean contains(UUID playerId) {
        return entries.containsKey(playerId);
    }

    public int size() {
        return entries.size();
    }

    public int position(UUID playerId) {
        Entry wanted = entries.get(playerId);
        if (wanted == null) {
            return -1;
        }

        int ahead = 0;
        for (Entry other : entries.values()) {
            if (other.playerId().equals(playerId)) {
                continue;
            }
            if (ORDER.compare(other, wanted) < 0) {
                ahead++;
            }
        }
        return ahead + 1;
    }
}

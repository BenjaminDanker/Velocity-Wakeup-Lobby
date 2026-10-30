package com.silver.wakeup.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdmissionQueueRequeueTest {
    private final AdmissionQueue queue = new AdmissionQueue();
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();
    private final UUID vip = UUID.randomUUID();

    @Test
    void aRequeuedEntryKeepsItsPlaceAheadOfLaterPlayers() {
        queue.enqueue(a, AdmissionQueue.Tier.NORMAL);
        queue.enqueue(b, AdmissionQueue.Tier.NORMAL);
        queue.enqueue(c, AdmissionQueue.Tier.NORMAL);

        AdmissionQueue.Entry first = queue.poll();
        assertEquals(a, first.playerId());
        assertFalse(queue.contains(a));
        assertEquals(1, queue.position(b));

        queue.requeue(first);

        assertTrue(queue.contains(a));
        assertEquals(3, queue.size());
        assertEquals(1, queue.position(a));
        assertEquals(2, queue.position(b));
        assertEquals(3, queue.position(c));
        assertEquals(a, queue.poll().playerId());
        assertEquals(b, queue.poll().playerId());
        assertEquals(c, queue.poll().playerId());
        assertNull(queue.poll());
    }

    @Test
    void aRequeuedEntryKeepsItsTierOrder() {
        queue.enqueue(a, AdmissionQueue.Tier.NORMAL);
        queue.enqueue(vip, AdmissionQueue.Tier.VIP);
        queue.enqueue(b, AdmissionQueue.Tier.ADMIN);

        AdmissionQueue.Entry admin = queue.poll();
        AdmissionQueue.Entry vipEntry = queue.poll();
        assertEquals(b, admin.playerId());
        assertEquals(vip, vipEntry.playerId());

        // put back in the opposite order: the order is by tier, not by when they were put back
        queue.requeue(vipEntry);
        queue.requeue(admin);

        assertEquals(1, queue.position(b));
        assertEquals(2, queue.position(vip));
        assertEquals(3, queue.position(a));
        assertEquals(b, queue.poll().playerId());
        assertEquals(vip, queue.poll().playerId());
        assertEquals(a, queue.poll().playerId());
    }

    @Test
    void severalDeferredEntriesComeBackInTheirOriginalOrder() {
        queue.enqueue(a, AdmissionQueue.Tier.NORMAL);
        queue.enqueue(b, AdmissionQueue.Tier.NORMAL);
        queue.enqueue(c, AdmissionQueue.Tier.NORMAL);
        AdmissionQueue.Entry ea = queue.poll();
        AdmissionQueue.Entry eb = queue.poll();
        queue.requeue(eb);
        queue.requeue(ea);
        assertEquals(1, queue.position(a));
        assertEquals(2, queue.position(b));
        assertEquals(3, queue.position(c));
        assertEquals(a, queue.poll().playerId());
        assertEquals(b, queue.poll().playerId());
    }

    @Test
    void requeueIsANoOpWhenThePlayerWasEnqueuedAgainMeanwhile() {
        queue.enqueue(a, AdmissionQueue.Tier.NORMAL);
        queue.enqueue(b, AdmissionQueue.Tier.NORMAL);
        AdmissionQueue.Entry old = queue.poll();
        assertEquals(a, old.playerId());

        assertTrue(queue.enqueue(a, AdmissionQueue.Tier.NORMAL)); // new entry, behind b
        queue.requeue(old);

        assertEquals(2, queue.size());
        assertEquals(1, queue.position(b));
        assertEquals(2, queue.position(a), "the fresh entry wins; the old place is not restored");
        assertEquals(b, queue.poll().playerId());
        assertEquals(a, queue.poll().playerId());
        assertNull(queue.poll(), "no duplicate of the player is left in the queue");
    }

    @Test
    void requeueingTwiceDoesNotDuplicate() {
        queue.enqueue(a, AdmissionQueue.Tier.NORMAL);
        AdmissionQueue.Entry entry = queue.poll();
        queue.requeue(entry);
        queue.requeue(entry);
        assertEquals(1, queue.size());
        assertEquals(a, queue.poll().playerId());
        assertNull(queue.poll());
    }

    @Test
    void aRequeuedEntryCanBeRemovedAndPositionIsGoneAfterwards() {
        queue.enqueue(a, AdmissionQueue.Tier.NORMAL);
        queue.enqueue(b, AdmissionQueue.Tier.NORMAL);
        AdmissionQueue.Entry entry = queue.poll();
        queue.requeue(entry);
        queue.remove(a);
        assertFalse(queue.contains(a));
        assertEquals(-1, queue.position(a));
        assertEquals(1, queue.position(b));
        assertEquals(b, queue.poll().playerId());
        assertNull(queue.poll());
    }
}

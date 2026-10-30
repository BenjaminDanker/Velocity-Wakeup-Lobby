package com.silver.wakeup.ondemand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.silver.wakeup.config.OnDemandSettings;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class OnDemandControllerStartPermitTest {
    private final List<List<String>> commands = new CopyOnWriteArrayList<>();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private OnDemandController controller;

    private OnDemandController controller(long idleMinutes) {
        controller = new OnDemandController(LoggerFactory.getLogger("test"), name -> events.add("wake " + name), command -> {
            commands.add(command);
            events.add("ssh " + command.getLast());
            return 0;
        });
        controller.configure(new OnDemandSettings(true, "silver@primary-ram.local", "/k", idleMinutes, Set.of("magic")));
        return controller;
    }

    @AfterEach
    void close() {
        if (controller != null) {
            controller.close();
        }
    }

    private static void await(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < end) {
            Thread.sleep(10);
        }
    }

    @Test
    void aRefusedStartRunsNoCommandButStillWakesTheHostFirst() throws Exception {
        controller(10);
        controller.setStartPermit(server -> {
            events.add("permit " + server);
            return Optional.of("full");
        });
        controller.want("magic");
        controller.tick(Map.of("magic", new OnDemandPlanner.Observed(false, 0)));
        await(() -> controller.waitingForRoom("magic"), 3_000);
        assertTrue(controller.waitingForRoom("magic"));
        assertEquals(List.of("wake magic", "permit magic"), events, "wake first, then the permit, and nothing else");
        assertEquals(List.of(), commands);
    }

    @Test
    void aGrantedStartRunsTheCommandAfterWakeAndPermit() throws Exception {
        controller(10);
        controller.setStartPermit(server -> {
            events.add("permit " + server);
            return Optional.empty();
        });
        controller.want("magic");
        controller.tick(Map.of("magic", new OnDemandPlanner.Observed(false, 0)));
        await(() -> !commands.isEmpty(), 3_000);
        assertEquals(List.of("wake magic", "permit magic", "ssh start magic"), events);
        assertFalse(controller.waitingForRoom("magic"));
    }

    @Test
    void withoutAPermitSetStartsAreNeverHeldBack() throws Exception {
        controller(10);
        controller.want("magic");
        controller.tick(Map.of("magic", new OnDemandPlanner.Observed(false, 0)));
        await(() -> !commands.isEmpty(), 3_000);
        assertEquals(1, commands.size());
        assertFalse(controller.waitingForRoom("magic"));
    }

    /** The planner retries a start every 10 s while the server is wanted and down, so this waits for that real retry. */
    @Test
    void whenThePermitLaterSaysYesTheStartRunsAndTheWaitingFlagClears() throws Exception {
        controller(10);
        AtomicBoolean room = new AtomicBoolean(false);
        List<String> permitCalls = new CopyOnWriteArrayList<>();
        controller.setStartPermit(server -> {
            permitCalls.add(server);
            return room.get() ? Optional.empty() : Optional.of("full");
        });
        Map<String, OnDemandPlanner.Observed> down = Map.of("magic", new OnDemandPlanner.Observed(false, 0));
        controller.want("magic");
        controller.tick(down);
        await(() -> controller.waitingForRoom("magic"), 3_000);
        assertTrue(controller.waitingForRoom("magic"));
        assertEquals(List.of(), commands);

        // a tick inside the retry window plans nothing
        controller.tick(down);
        Thread.sleep(100);
        assertEquals(1, permitCalls.size());

        room.set(true);
        long end = System.currentTimeMillis() + OnDemandPlanner.START_RETRY_MS + 5_000;
        while (commands.isEmpty() && System.currentTimeMillis() < end) {
            controller.want("magic");
            controller.tick(down);
            Thread.sleep(250);
        }
        await(() -> !commands.isEmpty(), 3_000);
        assertEquals(1, commands.size());
        assertEquals("start magic", commands.getFirst().getLast());
        assertFalse(controller.waitingForRoom("magic"));
        assertEquals("wake magic", events.getFirst());
        assertEquals(2, events.stream().filter("wake magic"::equals).count(), "each attempt wakes the host before asking");
    }

    @Test
    void stopActionsNeverAskForAPermit() throws Exception {
        controller(0); // idle limit 0: a server that is up, empty and unwanted is stopped on the first plan
        List<String> permitCalls = new CopyOnWriteArrayList<>();
        controller.setStartPermit(server -> {
            permitCalls.add(server);
            return Optional.of("full");
        });
        controller.tick(Map.of("magic", new OnDemandPlanner.Observed(true, 0)));
        await(() -> !commands.isEmpty(), 3_000);
        assertEquals(1, commands.size());
        assertEquals("stop magic", commands.getFirst().getLast());
        assertEquals(List.of(), permitCalls);
        assertEquals(List.of("ssh stop magic"), events, "no wake packet for a stop either");
        assertFalse(controller.waitingForRoom("magic"));
    }
}

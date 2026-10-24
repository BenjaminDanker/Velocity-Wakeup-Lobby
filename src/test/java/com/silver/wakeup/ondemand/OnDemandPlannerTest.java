package com.silver.wakeup.ondemand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.silver.wakeup.config.OnDemandSettings;
import com.silver.wakeup.ondemand.OnDemandPlanner.Action;
import com.silver.wakeup.ondemand.OnDemandPlanner.Kind;
import com.silver.wakeup.ondemand.OnDemandPlanner.Observed;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OnDemandPlannerTest {
    private static final long MIN = 60_000;
    private final OnDemandSettings settings = new OnDemandSettings(true, "silver@host", "/key", 10, Set.of("magic", "tech"));
    private final OnDemandPlanner planner = new OnDemandPlanner();

    private static Map<String, Observed> seen(Observed magic, Observed tech) {
        return Map.of("magic", magic, "tech", tech, "waiting_lobby", new Observed(true, 3));
    }

    private static final Observed OFF = new Observed(false, 0);
    private static final Observed EMPTY = new Observed(true, 0);

    @Test
    void anOfflineServerNobodyWantsIsLeftAlone() {
        assertEquals(List.of(), planner.plan(0, seen(OFF, OFF), settings));
    }

    @Test
    void aWantedOfflineServerIsStartedAndRetriedWhileItStaysOffline() {
        planner.want("magic", 0);
        assertEquals(List.of(new Action(Kind.START, "magic")), planner.plan(1_000, seen(OFF, OFF), settings));
        planner.want("magic", 5_000);
        assertEquals(List.of(), planner.plan(5_000, seen(OFF, OFF), settings), "not again straight away");
        planner.want("magic", 12_000);
        assertEquals(List.of(new Action(Kind.START, "magic")), planner.plan(12_000, seen(OFF, OFF), settings), "the machine may still have been booting");
    }

    @Test
    void aWantExpiresIfNobodyRenewsIt() {
        planner.want("magic", 0);
        assertEquals(List.of(), planner.plan(OnDemandPlanner.WANT_TTL_MS + 1, seen(OFF, OFF), settings));
    }

    @Test
    void anEmptyServerIsStoppedOnlyAfterTheIdleTime() {
        assertEquals(List.of(), planner.plan(0, seen(EMPTY, OFF), settings), "first seen empty: the clock starts");
        assertEquals(List.of(), planner.plan(9 * MIN, seen(EMPTY, OFF), settings));
        assertEquals(List.of(new Action(Kind.STOP, "magic")), planner.plan(10 * MIN, seen(EMPTY, OFF), settings));
        assertEquals(List.of(), planner.plan(10 * MIN + 1_000, seen(EMPTY, OFF), settings), "the stop request is not repeated at once");
    }

    @Test
    void aPlayerOnTheServerResetsTheIdleClock() {
        planner.plan(0, seen(EMPTY, OFF), settings);
        planner.plan(8 * MIN, seen(new Observed(true, 1), OFF), settings);
        assertEquals(List.of(), planner.plan(12 * MIN, seen(EMPTY, OFF), settings));
        assertEquals(List.of(new Action(Kind.STOP, "magic")), planner.plan(18 * MIN, seen(EMPTY, OFF), settings));
    }

    @Test
    void someoneWaitingToGoThereKeepsItRunning() {
        planner.plan(0, seen(EMPTY, OFF), settings);
        planner.want("magic", 9 * MIN + 50_000);
        assertEquals(List.of(), planner.plan(10 * MIN, seen(EMPTY, OFF), settings));
    }

    @Test
    void serversThatAreNotManagedAreNeverTouched() {
        planner.want("ocean", 0);
        Map<String, Observed> view = Map.of("ocean", EMPTY, "waiting_lobby", EMPTY);
        assertEquals(List.of(), planner.plan(0, view, settings));
        assertEquals(List.of(), planner.plan(60 * MIN, view, settings));
    }

    @Test
    void aServerThatWentOfflineStartsItsIdleClockAgainWhenItReturns() {
        planner.plan(0, seen(EMPTY, OFF), settings);
        planner.plan(5 * MIN, seen(OFF, OFF), settings);
        assertEquals(List.of(), planner.plan(12 * MIN, seen(EMPTY, OFF), settings));
        assertFalse(planner.wanted("magic", 12 * MIN));
        assertTrue(planner.plan(22 * MIN, seen(EMPTY, OFF), settings).contains(new Action(Kind.STOP, "magic")));
    }

    @Test
    void aKeptServerIsStartedWheneverItIsDownAndNeverStopped() {
        OnDemandSettings kept = new OnDemandSettings(true, "silver@host", "/key", 10, Set.of("magic", "tech"), Set.of("magic"));
        assertEquals(List.of(new Action(Kind.START, "magic")), planner.plan(0, seen(OFF, OFF), kept));
        assertEquals(List.of(), planner.plan(1_000, seen(EMPTY, EMPTY), kept));
        assertEquals(List.of(new Action(Kind.STOP, "tech")), planner.plan(60 * MIN, seen(EMPTY, EMPTY), kept), "only the other one idles out");
    }
}

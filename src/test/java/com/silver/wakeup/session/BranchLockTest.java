package com.silver.wakeup.session;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchLockTest {
    private static final List<String> BRANCHES = List.of("magic", "tech");

    @Test
    void firstBranchEnteredLocksOutTheOther() {
        assertTrue(BranchLock.lockedOut(BRANCHES, Set.of("vanilla", "magic"), "tech"));
        assertTrue(BranchLock.lockedOut(BRANCHES, Set.of("vanilla", "tech"), "magic"));
    }

    @Test
    void theChosenBranchAndOrdinaryServersStayOpen() {
        assertFalse(BranchLock.lockedOut(BRANCHES, Set.of("vanilla", "magic"), "magic"));
        assertFalse(BranchLock.lockedOut(BRANCHES, Set.of("vanilla", "magic"), "vanilla"));
        assertFalse(BranchLock.lockedOut(BRANCHES, Set.of("magic"), "ocean"));
    }

    @Test
    void aPlayerWhoHasChosenNothingMayEnterEitherAndNoGroupMeansNoLock() {
        assertFalse(BranchLock.lockedOut(BRANCHES, Set.of("vanilla"), "tech"));
        assertFalse(BranchLock.lockedOut(BRANCHES, Set.of("vanilla"), "magic"));
        assertFalse(BranchLock.lockedOut(null, Set.of("magic"), "tech"));
        assertFalse(BranchLock.lockedOut(List.of(), Set.of("magic"), "tech"));
    }

    @Test
    void matchingIgnoresCase() {
        assertTrue(BranchLock.lockedOut(BRANCHES, Set.of("MAGIC"), "Tech"));
    }
}

package com.silver.wakeup.session;

import java.util.Collection;
import java.util.List;

/** Exclusive branches: the first branch server a player enters is the only one they may ever enter. */
public final class BranchLock {
    private BranchLock() {
    }

    public static boolean lockedOut(List<String> branches, Collection<String> visited, String requested) {
        if (branches == null || branches.stream().noneMatch(b -> b.equalsIgnoreCase(requested))) return false;
        return branches.stream().anyMatch(b -> !b.equalsIgnoreCase(requested)
                && visited.stream().anyMatch(v -> v.equalsIgnoreCase(b)));
    }
}

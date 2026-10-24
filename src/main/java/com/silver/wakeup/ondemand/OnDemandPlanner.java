package com.silver.wakeup.ondemand;

import com.silver.wakeup.config.OnDemandSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides, from what the proxy sees, which servers to start and which to stop. Pure: no clocks, no network, so it is tested directly.
 *
 * <p>A server is started when something wants it (a player is waiting to go there) and it is not running, retrying every
 * {@code START_RETRY_MS} because the machine itself may still be waking. A running server is stopped once it has had no players, and
 * nothing wanting it, for the configured idle time.</p>
 */
public final class OnDemandPlanner {
    /** How long a want stays alive without being renewed. Waiting players renew it every second. */
    static final long WANT_TTL_MS = 60_000;
    static final long START_RETRY_MS = 10_000;
    static final long STOP_RETRY_MS = 60_000;

    public record Observed(boolean online, int players) {
    }

    public enum Kind { START, STOP }

    public record Action(Kind kind, String server) {
    }

    private final Map<String, Long> wantedAt = new ConcurrentHashMap<>();
    private final Map<String, Long> lastStart = new ConcurrentHashMap<>();
    private final Map<String, Long> lastActive = new ConcurrentHashMap<>();
    private final Map<String, Long> lastStop = new ConcurrentHashMap<>();

    public void want(String server, long now) {
        wantedAt.put(server, now);
    }

    public boolean wanted(String server, long now) {
        Long at = wantedAt.get(server);
        return at != null && now - at <= WANT_TTL_MS;
    }

    public List<Action> plan(long now, Map<String, Observed> observed, OnDemandSettings settings) {
        List<Action> actions = new ArrayList<>();
        long idleMs = settings.idleMinutes() * 60_000L;
        for (Map.Entry<String, Observed> entry : observed.entrySet()) {
            String server = entry.getKey();
            Observed seen = entry.getValue();
            if (!settings.manages(server)) {
                continue;
            }
            boolean wanted = wanted(server, now);
            if (!seen.online()) {
                lastActive.remove(server);
                lastStop.remove(server);
                if ((wanted || settings.keeps(server)) && now - lastStart.getOrDefault(server, Long.MIN_VALUE / 2) >= START_RETRY_MS) {
                    lastStart.put(server, now);
                    actions.add(new Action(Kind.START, server));
                }
                continue;
            }
            if (seen.players() > 0 || wanted || settings.keeps(server)) {
                lastActive.put(server, now);
                continue;
            }
            long since = lastActive.computeIfAbsent(server, ignored -> now);
            if (now - since >= idleMs && now - lastStop.getOrDefault(server, Long.MIN_VALUE / 2) >= STOP_RETRY_MS) {
                lastStop.put(server, now);
                actions.add(new Action(Kind.STOP, server));
            }
        }
        return actions;
    }
}

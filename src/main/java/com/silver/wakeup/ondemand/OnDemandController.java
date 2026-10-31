package com.silver.wakeup.ondemand;

import com.silver.wakeup.config.OnDemandSettings;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;

/**
 * Carries out the planner's decisions: asks the machine that runs the servers (over a key that can only start, stop and query
 * them) to start or stop one. Requests run one at a time off the proxy's threads.
 */
public final class OnDemandController {
    /** Runs a command and returns its exit code; replaced in tests. */
    public interface Runner {
        int run(List<String> command) throws IOException, InterruptedException;
    }

    private final Logger log;
    private final Runner runner;
    private final Consumer<String> wakeHost;
    private final OnDemandPlanner planner = new OnDemandPlanner();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "wakeuplobby-ondemand");
        thread.setDaemon(true);
        return thread;
    });
    private volatile OnDemandSettings settings = OnDemandSettings.DISABLED;

    public OnDemandController(Logger log, Consumer<String> wakeHost) {
        this(log, wakeHost, OnDemandController::runProcess);
    }

    OnDemandController(Logger log, Consumer<String> wakeHost, Runner runner) {
        this.log = log;
        this.wakeHost = wakeHost;
        this.runner = runner;
    }

    public void configure(OnDemandSettings next) {
        this.settings = next;
        log.info("[OnDemand] {}; managing {}; idle stop after {} min", next.enabled() ? "enabled" : "disabled", next.servers(), next.idleMinutes());
    }

    public boolean enabled() {
        return settings.enabled();
    }

    public boolean manages(String server) {
        return settings.manages(server);
    }

    /** Someone is waiting for this server: start it if it is not running, and do not stop it. Safe to call every second. */
    public void want(String server) {
        if (settings.manages(server)) {
            planner.want(server, System.currentTimeMillis());
        }
    }

    /** Called every couple of seconds with what the proxy currently sees. */
    public void tick(Map<String, OnDemandPlanner.Observed> observed) {
        OnDemandSettings current = settings;
        if (!current.enabled()) {
            return;
        }
        for (OnDemandPlanner.Action action : planner.plan(System.currentTimeMillis(), observed, current)) {
            worker.execute(() -> perform(current, action));
        }
    }

    /** mc-ctl's exit code when the server has a job running and must not be stopped yet. */
    private static final int BUSY_EXIT = 75;
    private static final long FAILURE_LOG_EVERY_MS = 5 * 60_000L;
    private final java.util.Map<String, Integer> failures = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Long> lastFailureLog = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> busy = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Asked just before a start: may this server start now? An empty result means yes; otherwise it is why not, and the start is
     * tried again on the planner's next round while someone still wants it. Set by the plugin to the capacity service's reservation.
     */
    private volatile Function<String, java.util.Optional<String>> startPermit = server -> java.util.Optional.empty();
    private final java.util.Set<String> waitingForRoom = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public void setStartPermit(Function<String, java.util.Optional<String>> startPermit) {
        this.startPermit = startPermit;
    }

    /** True while the last start attempt for this server was held back for lack of room on the host. */
    public boolean waitingForRoom(String server) {
        return waitingForRoom.contains(server);
    }

    /** Players on a backend right now, read again just before a stop (the plan used a snapshot). */
    private volatile java.util.function.ToIntFunction<String> playersNow = server -> 0;

    public void setPlayerCount(java.util.function.ToIntFunction<String> playersNow) {
        this.playersNow = playersNow;
    }

    private void perform(OnDemandSettings current, OnDemandPlanner.Action action) {
        String verb = action.kind() == OnDemandPlanner.Kind.START ? "start" : "stop";
        if (action.kind() == OnDemandPlanner.Kind.STOP && playersNow.applyAsInt(action.server()) > 0) {
            log.info("[OnDemand] '{}' got a player since the idle check; not stopping it", action.server());
            return;
        }
        if (action.kind() == OnDemandPlanner.Kind.START) {
            // the machine may be asleep; the packet is harmless when it is not (sent before asking for room: an asleep host can't answer)
            wakeHost.accept(action.server());
            java.util.Optional<String> refused = startPermit.apply(action.server());
            if (refused.isPresent()) {
                if (waitingForRoom.add(action.server())) {
                    log.info("[OnDemand] start of '{}' waits for room on the host: {}", action.server(), refused.get());
                }
                return;
            }
            if (waitingForRoom.remove(action.server())) {
                log.info("[OnDemand] '{}' has room now; starting it", action.server());
            }
        }
        List<String> command = List.of("ssh", "-i", current.sshKey(), "-o", "BatchMode=yes", "-o", "ConnectTimeout=5",
            "-o", "StrictHostKeyChecking=accept-new", current.sshTarget(), verb + " " + action.server());
        try {
            int exit = runner.run(command);
            String key = verb + " " + action.server();
            if (exit != 0 && !(exit == BUSY_EXIT && action.kind() == OnDemandPlanner.Kind.STOP)) {
                // the first failure, then a reminder at most every 5 minutes while the retries keep failing
                int n = failures.merge(key, 1, Integer::sum);
                long nowMs = System.currentTimeMillis();
                Long last = lastFailureLog.get(key);
                if (n == 1 || last == null || nowMs - last >= FAILURE_LOG_EVERY_MS) {
                    lastFailureLog.put(key, nowMs);
                    log.warn("[OnDemand] {} of '{}' failed (ssh exit {}){}; will retry while it is wanted", verb, action.server(), exit,
                            n > 1 ? " (" + n + " attempts so far)" : "");
                }
                return;
            }
            Integer failed = failures.remove(key);
            lastFailureLog.remove(key);
            if (exit == 0) {
                busy.remove(action.server());
                log.info("[OnDemand] asked for {} of '{}'{}", verb, action.server(), failed != null ? " (after " + failed + " failed attempts)" : "");
            } else if (exit == BUSY_EXIT && action.kind() == OnDemandPlanner.Kind.STOP) {
                // said once per busy stretch (the stop is retried every minute while it lasts)
                if (busy.add(action.server())) {
                    log.info("[OnDemand] '{}' is busy with a job (e.g. an Atlantis build); idle stop postponed until it's done", action.server());
                }
                return;
            }
        } catch (IOException | InterruptedException failure) {
            log.warn("[OnDemand] {} of '{}' could not be requested: {}", verb, action.server(), failure.toString());
        }
    }

    private static int runProcess(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(25, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return -1;
        }
        return process.exitValue();
    }

    public void close() {
        worker.shutdownNow();
    }
}

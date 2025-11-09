package com.silver.wakeup.plugin;

import com.google.inject.Inject;
import com.silver.wakeup.config.LobbyConfig;
import com.silver.wakeup.config.LobbyConfigLoader;
import com.silver.wakeup.portal.PortalCommandHandler;
import com.silver.wakeup.portal.PortalCommandHandler.HoldingConnection;
import com.silver.wakeup.portal.PortalHandoffService;
import com.silver.wakeup.portal.PortalTokenVerifier;
import com.silver.wakeup.state.PlayerStateStore;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerLoginPluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main Velocity proxy plugin for lobby management and player state tracking.
 * 
 * <p>Provides sophisticated player routing through a configurable lobby server,
 * maintains player session state across server transitions, and integrates with
 * ServerPortals for secure inter-server handoffs.
 * 
 * <p>Key responsibilities:
 * <ul>
 *   <li>Managing player lobby transitions and grace periods</li>
 *   <li>Tracking player state and visit history</li>
 *   <li>Routing players between configured server groups</li>
 *   <li>Handling portal authentication and secure handoffs</li>
 *   <li>Keeping servers alive via configurable ping intervals</li>
 * </ul>
 */
@Plugin(id = "wakeuplobby", name = "WakeUpLobby", version = "1.3.0")
public class VelocityPlugin {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;

    // config/runtime
    private LobbyConfigLoader configLoader;
    private RuntimeState runtime;
    private PlayerStateStore stateStore;
    private PortalHandoffService portalHandoffService;
    private PortalCommandHandler portalCommandHandler;
    private PortalTokenVerifier portalTokenVerifier;
    private CommandRegistrar commandRegistrar;
    private static final MinecraftChannelIdentifier PORTAL_HANDOFF_CHANNEL =
        MinecraftChannelIdentifier.from("serverportals:portal_handoff");

    // persistence
    private final Map<UUID, String> lastServer = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> visited = new ConcurrentHashMap<>();

    @Inject
    public VelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) {
        try {
            configLoader = new LobbyConfigLoader(logger, dataDir);
            configLoader.ensureDefaultConfig();
            portalTokenVerifier = new PortalTokenVerifier(logger);
            portalHandoffService = new PortalHandoffService(logger);
            runtime = new RuntimeState(proxy, this, logger, portalHandoffService, portalTokenVerifier);
            stateStore = new PlayerStateStore(dataDir, logger);

            loadPlayerState();

            LobbyConfig config = loadConfig();
            purgeHoldingFromStore();
            purgeHoldingFromVisited();

            portalCommandHandler = new PortalCommandHandler(logger, new VelocityPortalCommandDependencies());
            commandRegistrar = new CommandRegistrar(proxy, runtime, portalCommandHandler, this, logger);

            logger.info("[WakeUpLobby] Loaded. Holding={}, grace={}s, interval={}s, fallback={}",
                    runtime.holdingServer(),
                    config.graceSec(),
                    config.pingEverySec(),
                    config.fallbackPolicy());
        } catch (Exception ex) {
            logger.error("Failed to initialize WakeUpLobby", ex);
        }
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        if (commandRegistrar == null) {
            logger.error("[WakeUpLobby] Command registrar not initialised; skipping command registration");
            return;
        }
        commandRegistrar.register();
    }

    @Subscribe
    public void onLoginPluginMessage(ServerLoginPluginMessageEvent event) {
        if (!event.getIdentifier().equals(PORTAL_HANDOFF_CHANNEL)) {
            return;
        }

        var player = event.getConnection().getPlayer();
        if (player == null) {
            logger.warn("[WakeUpLobby] Login plugin message received without player context");
            event.setResult(ServerLoginPluginMessageEvent.ResponseResult.unknown());
            return;
        }

        UUID playerId = player.getUniqueId();
        var portalOpt = portalHandoffService.peekSourcePortal(playerId);
        portalOpt.ifPresentOrElse(
                portal -> logger.info("[WakeUpLobby] Responding with portal handoff '{}' for {}", portal, player.getUsername()),
                () -> logger.debug("[WakeUpLobby] No portal handoff data for {}", player.getUsername())
        );

        byte[] payload = portalHandoffService.createResponsePayload(playerId);
        event.setResult(ServerLoginPluginMessageEvent.ResponseResult.reply(payload));
    }

    /** Initial routing with quick ping: bypass lobby if preferred is up; else send to holding. */
    @Subscribe
    public void onChoose(PlayerChooseInitialServerEvent e) {
        var p = e.getPlayer();
        String preferred = preferredFor(p.getUniqueId()).orElseGet(this::firstDefault);
        logger.info("[WakeUpLobby] onChoose: player={} preferred={}", p.getUsername(), preferred);
        
        if (preferred == null) {
            logger.warn("[WakeUpLobby] onChoose: player {} has no preferred server, returning", p.getUsername());
            return;
        }

        var prefReg = proxy.getServer(preferred);
        var holdReg = proxy.getServer(runtime.holdingServer());

        logger.info("[WakeUpLobby] onChoose: {} preferred='{}' holding='{}'", p.getUsername(), preferred, runtime.holdingServer());

        if (prefReg.isEmpty()) {
            logger.warn("[WakeUpLobby] onChoose: {} preferred '{}' not registered → holding '{}'",
                    p.getUsername(), preferred, runtime.holdingServer());
            holdReg.ifPresent(e::setInitialServer);
            return;
        }

        // Non-blocking: set holding as default, then try to upgrade async
        holdReg.ifPresent(srv -> {
            logger.info("[WakeUpLobby] onChoose: setting initial server to holding '{}' for {}", 
                       runtime.holdingServer(), p.getUsername());
            e.setInitialServer(srv);
        });
        
        prefReg.get().ping()
            .orTimeout(1200, java.util.concurrent.TimeUnit.MILLISECONDS)
            .whenComplete((pong, err) -> {
                if (err == null) {
                    logger.info("[WakeUpLobby] onChoose: {} bypass → preferred '{}' is up", p.getUsername(), preferred);
                    // Player already in holding; they'll switch if this completes
                } else {
                    logger.info("[WakeUpLobby] onChoose: {} preferred '{}' not ready → staying in holding", p.getUsername(), preferred);
                }
            });
    }

    /**
     * Sticky behavior + policy gates:
     * - Internal plugin-initiated moves are always allowed.
     * - Non-admins cannot use /server at all (manual server switches are denied).
     * - Even admins must be allowed; they bypass tier restrictions.
     * - Downward-only tier rule for normal fallback/manual attempts.
     */
    @Subscribe
    public void onPreConnect(ServerPreConnectEvent e) {
        var player = e.getPlayer();
        var destOpt = e.getResult().getServer();
        
        logger.info("[WakeUpLobby] onPreConnect: player={} destServer={}", 
                   player.getUsername(), 
                   destOpt.map(s -> s.getServerInfo().getName()).orElse("none"));
        
        if (destOpt.isEmpty()) {
            logger.warn("[WakeUpLobby] onPreConnect: no destination server for {}", player.getUsername());
            return;
        }

        String requested = destOpt.get().getServerInfo().getName();
        String preferred = preferredFor(player.getUniqueId()).orElse(requested);
        
        logger.info("[WakeUpLobby] onPreConnect: player={} requested={} preferred={}", 
                   player.getUsername(), requested, preferred);

        // Allow plugin-internal switches (auto-move on ready or /fallback)
        if (runtime.stickyRouter().consumeInternalOnce(player.getUniqueId())) {
            logger.info("[WakeUpLobby] onPreConnect: {} internal switch -> '{}' (ALLOWED)", player.getUsername(), requested);
            return;
        }

        boolean isAdmin = hasBypass(player); // wakeuplobby.bypass
        logger.info("[WakeUpLobby] onPreConnect: player={} isAdmin={}", player.getUsername(), isAdmin);

        // Block /server for non-admins (manual server switch = not preferred and not holding)
        if (!isAdmin && !requested.equalsIgnoreCase(preferred) && !requested.equalsIgnoreCase(runtime.holdingServer())) {
            logger.warn("[WakeUpLobby] onPreConnect: {} manual switch DENIED (not admin)", player.getUsername());
            player.sendMessage(Component.text("⚠ Server switching is disabled."));
            e.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }

        // Enforce downward-only tier for non-admins (no jumping up)
        if (!isAdmin
                && !requested.equalsIgnoreCase(runtime.holdingServer())
                && !allowedTargetsDownward(player.getUniqueId()).contains(requested)) {

            logger.warn("[WakeUpLobby] onPreConnect: {} tier restriction violated for '{}'", 
                       player.getUsername(), requested);
            
            // Pick highest allowed ONLINE; else deny
            var allowed = allowedTargetsDownward(player.getUniqueId());
            var chosen = chooseFirstOnline(allowed);
            if (chosen.isPresent()) {
                var srv = proxy.getServer(chosen.get()).orElse(null);
                if (srv != null) {
                    logger.info("[WakeUpLobby] onPreConnect: redirecting {} from {} to {}", 
                               player.getUsername(), requested, chosen.get());
                    e.setResult(ServerPreConnectEvent.ServerResult.allowed(srv));
                    player.sendMessage(Component.text("➡ You don't have access to " + requested + " yet. Sending you to " + chosen.get() + "."));
                    return;
                }
            }
            logger.warn("[WakeUpLobby] onPreConnect: no available servers for {}", player.getUsername());
            player.sendMessage(Component.text("⚠ No servers are available currently."));
            e.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }

        if (requested.equalsIgnoreCase(preferred)) {
            logger.info("[WakeUpLobby] onPreConnect: {} heading to preferred '{}' (ALLOWED)", player.getUsername(), preferred);
            return; // let Velocity proceed
        }

        if (requested.equalsIgnoreCase(runtime.holdingServer())) {
            String origin = currentServerName(player).orElse(null);
            logger.info("[WakeUpLobby] onPreConnect: {} heading to holding '{}', preferred='{}' (origin='{}')",
                    player.getUsername(), runtime.holdingServer(), preferred, origin);
            
            // Check if sticky wait already started (e.g., from /wl portal command)
            if (!runtime.stickyRouter().hasStickyState(player.getUniqueId())) {
                logger.info("[WakeUpLobby] onPreConnect: no existing sticky state, starting sticky toward '{}'", preferred);
                player.sendMessage(Component.text("⏳ Starting " + preferred + "…"));
                runtime.stickyRouter().beginStickyWait(player.getUniqueId(), preferred, origin);
            } else {
                logger.info("[WakeUpLobby] onPreConnect: sticky state already exists for player {}, not recreating", 
                           player.getUsername());
            }
            return;
        }

        // Admin manual or valid downward manual -> allow
        logger.info("[WakeUpLobby] onPreConnect: {} manual request '{}'", player.getUsername(), requested);
    }

    @Subscribe
    public void onCommandExecute(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player player)) {
            return;
        }

        if (runtime == null || runtime.isAdmin(player.getUsername())) {
            return;
        }

        String rawCommand = event.getCommand();
        if (rawCommand == null) {
            return;
        }

        String commandBody = rawCommand.trim();
        if (commandBody.isEmpty()) {
            return;
        }

        if (commandBody.startsWith("/")) {
            commandBody = commandBody.substring(1).trim();
        }

        String lowerCommand = commandBody.toLowerCase(Locale.ROOT);
        String[] parts = lowerCommand.split("\\s+");
        if (parts.length == 0) {
            return;
        }

        boolean allowedBasic = parts[0].equals("w")
                || parts[0].equals("msg")
                || parts[0].equals("teammsg");
        boolean allowedPortal = parts[0].equals("wl") && parts.length >= 2 && parts[1].equals("portal");

        if (allowedBasic || allowedPortal) {
            return;
        }

        player.sendMessage(Component.text("⚠ Commands are restricted."));
        event.setResult(CommandExecuteEvent.CommandResult.denied());
    }


    /** Record visits; never record holding in visited or as last. */
    @Subscribe
    public void onConnected(ServerConnectedEvent e) {
        var p = e.getPlayer();
        var srv = e.getServer().getServerInfo().getName();
        
        logger.info("[WakeUpLobby] onConnected: player={} server={}", p.getUsername(), srv);

        if (!srv.equalsIgnoreCase(runtime.holdingServer())) {
            visited.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>()).add(srv);
            logger.info("[WakeUpLobby] onConnected: added '{}' to visited set for {}", srv, p.getUsername());
            proxy.getScheduler().buildTask(this, this::saveVisited).delay(Duration.ofSeconds(1)).schedule();

            lastServer.put(p.getUniqueId(), srv);
            proxy.getScheduler().buildTask(this, this::saveStore).delay(Duration.ofSeconds(1)).schedule();

            logger.info("[WakeUpLobby] onConnected: {} now last='{}'", p.getUsername(), srv);
        } else {
            logger.info("[WakeUpLobby] onConnected: {} connected to holding '{}', not recording as last/visited",
                    p.getUsername(), srv);
        }
    }

    /* =================== Helpers & config =================== */

    private void loadPlayerState() {
        lastServer.clear();
        lastServer.putAll(stateStore.loadLastServers());
        visited.clear();
        visited.putAll(stateStore.loadVisitedServers());
    }

    private boolean hasBypass(Player p) {
        return runtime.isAdmin(p.getUsername());
    }

    private Optional<String> preferredFor(UUID uuid) {
        String v = lastServer.get(uuid);
        if (v != null && !v.equalsIgnoreCase(runtime.holdingServer())) return Optional.of(v);
        return Optional.ofNullable(firstDefault());
    }

    private String firstDefault() {
        var def = runtime.groupMembers("default_group");
        if (def == null || def.isEmpty()) return null;
        return def.get(0);
    }

    /** Highest index in default_group the player has visited; -1 if none. */
    private int maxVisitedIndex(UUID who) {
        var def = runtime.groupMembers("default_group");
        var seen = visited.getOrDefault(who, Set.of());
        int max = -1;
        for (int i = 0; i < def.size(); i++) if (seen.contains(def.get(i))) max = i;
        return max;
    }

    /** Allowed fallbacks in descending order: [tier, tier-1, ..., 0]. New players => [def[0]] */
    private List<String> allowedTargetsDownward(UUID who) {
        var def = runtime.groupMembers("default_group");
        var seen = visited.getOrDefault(who, Set.of());
        int max = maxVisitedIndex(who);
        
        logger.info("[WakeUpLobby] allowedTargetsDownward: uuid={} visited={} maxIndex={}", who, seen, max);
        
        if (def.isEmpty()) return List.of();
        if (max < 0) return List.of(def.get(0));
        var out = new ArrayList<String>(max + 1);
        for (int i = max; i >= 0; i--) out.add(def.get(i));
        
        logger.info("[WakeUpLobby] allowedTargetsDownward: returning {}", out);
        return out;
    }

    private Optional<String> chooseFirstOnline(List<String> names) {
        for (String name : names) {
            var reg = proxy.getServer(name);
            if (reg.isEmpty()) continue;
            try {
                if (reg.get().ping().getNow(null) != null) return Optional.of(name);
            } catch (Exception ignored) {}
        }
        return Optional.empty();
    }

    void reloadConfig() throws IOException {
        loadConfig();
    }

    private LobbyConfig loadConfig() throws IOException {
        LobbyConfig config = configLoader.load();
        runtime.applyConfig(config, this::allowedTargetsDownward);
        return config;
    }

    private PortalTokenVerifier ensureTokenVerifier() {
        if (portalTokenVerifier == null) {
            portalTokenVerifier = new PortalTokenVerifier(logger);
        }
        return portalTokenVerifier;
    }

    public void applyPortalSecrets(String globalSecret, Map<String, String> perSecrets) {
        ensureTokenVerifier().updateSecrets(globalSecret, perSecrets);
    }

    public PortalTokenVerifier tokenVerifier() {
        return ensureTokenVerifier();
    }

    private void saveStore() {
        stateStore.saveLastServers(lastServer);
    }

    private void saveVisited() {
        stateStore.saveVisitedServers(visited);
    }

    private void purgeHoldingFromStore() {
        if (runtime == null) {
            return;
        }
        boolean changed = stateStore.purgeHoldingFromLastServers(lastServer, runtime.holdingServer());
        if (changed) {
            logger.info("[WakeUpLobby] Purged holding server '{}' from last-server store", runtime.holdingServer());
            saveStore();
        }
    }

    private void purgeHoldingFromVisited() {
        if (runtime == null) {
            return;
        }
        if (stateStore.purgeHoldingFromVisited(visited, runtime.holdingServer())) {
            logger.info("[WakeUpLobby] Purged holding server '{}' from visited store", runtime.holdingServer());
            saveVisited();
        }
    }

    private Optional<String> currentServerName(Player p) {
        return p.getCurrentServer().map(cs -> cs.getServerInfo().getName());
    }

    private final class VelocityPortalCommandDependencies implements PortalCommandHandler.Dependencies {
        @Override
        public boolean verifyPortalToken(String target, String token) {
            return ensureTokenVerifier().verify(target, token);
        }

        @Override
        public void rememberSourcePortal(UUID playerId, String portalName) {
            portalHandoffService.rememberSourcePortal(playerId, portalName);
        }

        @Override
        public void unlockServerFor(UUID playerId, String target) {
            visited.computeIfAbsent(playerId, k -> new HashSet<>()).add(target);
            lastServer.put(playerId, target);
            saveVisited();
            saveStore();
        }

        @Override
        public Optional<String> currentServerName(Player player) {
            return VelocityPlugin.this.currentServerName(player);
        }

        @Override
        public Optional<HoldingConnection> resolveHoldingServer(Player player, String target, Optional<String> originServer) {
            return proxy.getServer(runtime.holdingServer())
                    .map(reg -> () -> player.createConnectionRequest(reg).fireAndForget());
        }

        @Override
        public void beginStickyWait(UUID playerId, String target, Optional<String> originServer) {
            runtime.stickyRouter().beginStickyWait(playerId, target, originServer.orElse(null));
        }

        @Override
        public void markInternalOnce(UUID playerId) {
            runtime.stickyRouter().markInternalOnce(playerId);
        }

        @Override
        public void notifyInvalidToken(Player player) {
            player.sendMessage(Component.text("§cInvalid portal token."));
        }

        @Override
        public String holdingServerName() {
            return runtime.holdingServer();
        }
    }
}

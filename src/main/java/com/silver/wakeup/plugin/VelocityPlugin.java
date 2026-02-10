package com.silver.wakeup.plugin;

import com.google.inject.Inject;
import com.silver.wakeup.config.LobbyConfig;
import com.silver.wakeup.config.LobbyConfigLoader;
import com.silver.wakeup.portal.PortalCommandHandler;
import com.silver.wakeup.portal.PortalCommandHandler.HoldingConnection;
import com.silver.wakeup.portal.PortalHandoffService;
import com.silver.wakeup.portal.PortalRequestPayloadCodec;
import com.silver.wakeup.portal.PortalRequestVerifier;
import com.silver.wakeup.portal.PortalTokenVerifier;
import com.silver.wakeup.state.PlayerStateStore;
import com.silver.wakeup.config.ReturnSpecial;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerLoginPluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
    static final String MPDS_REMOVE_SELF_COMMAND = "mpdsremovecustomidself";
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
    private PortalRequestVerifier portalRequestVerifier;
    private CommandRegistrar commandRegistrar;
    private static final MinecraftChannelIdentifier PORTAL_HANDOFF_CHANNEL =
        MinecraftChannelIdentifier.from("serverportals:portal_handoff");

    private static final MinecraftChannelIdentifier PORTAL_REQUEST_CHANNEL =
        MinecraftChannelIdentifier.from("wakeuplobby:portal_request");

    private static final MinecraftChannelIdentifier MPDS_RETURN_REMOVE_CHANNEL =
        MinecraftChannelIdentifier.from("mpds:return_remove");

    private static final MinecraftChannelIdentifier RETURN_OVERWORLD_CHANNEL =
        MinecraftChannelIdentifier.from("wakeuplobby:return_overworld");

    record ReturnRemoveResponse(boolean success,
                                int removedInventory,
                                int removedDb,
                                int remainingInventory,
                                int remainingDb,
                                String error) {
    }

    private final Map<UUID, CompletableFuture<ReturnRemoveResponse>> pendingReturnRemovals = new ConcurrentHashMap<>();

    // persistence
    private final Map<UUID, String> lastServer = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> visited = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastListedServer = new ConcurrentHashMap<>();

    public String computeReturnDestination(UUID playerId) {
        LobbyConfig cfg = runtime == null ? null : runtime.currentConfig();
        List<String> order = cfg == null ? List.of() : cfg.returnServerOrder();
        if (order.isEmpty()) {
            return firstDefault();
        }

        String lastListed = lastListedServer.get(playerId);
        String candidate = firstMatchingIgnoreCase(order, lastListed).orElse(order.get(0));

        String last = lastServer.get(playerId);
        if (last != null && candidate.equalsIgnoreCase(last)) {
            int idx = indexOfIgnoreCase(order, candidate);
            if (idx > 0) {
                return order.get(idx - 1);
            }
            return order.get(0);
        }

        return candidate;
    }

    public List<String> computeReturnLossDisplayNames(UUID playerId) {
        if (runtime == null) {
            return List.of();
        }

        // Loss list should be based on the server we're giving up on (/return away from),
        // which is the sticky-wait target that grace expired on.
        String serverKey = runtime.stickyRouter().returnTargetServer(playerId);
        if (serverKey == null || serverKey.isBlank()) {
            serverKey = runtime.stickyRouter().returnOriginServer(playerId);
        }
        if (serverKey == null || serverKey.isBlank()) {
            return List.of();
        }

        LobbyConfig cfg = runtime.currentConfig();
        if (cfg == null) {
            return List.of();
        }

        for (var entry : cfg.returnSpecials().entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(serverKey)) {
                return entry.getValue().stream().map(s -> s.displayName()).toList();
            }
        }
        return List.of();
    }

    List<ReturnSpecial> computeReturnSpecials(UUID playerId) {
        if (runtime == null) {
            return List.of();
        }

        String serverKey = runtime.stickyRouter().returnTargetServer(playerId);
        if (serverKey == null || serverKey.isBlank()) {
            serverKey = runtime.stickyRouter().returnOriginServer(playerId);
        }
        if (serverKey == null || serverKey.isBlank()) {
            return List.of();
        }

        LobbyConfig cfg = runtime.currentConfig();
        if (cfg == null) {
            return List.of();
        }

        for (var entry : cfg.returnSpecials().entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(serverKey)) {
                return entry.getValue();
            }
        }
        return List.of();
    }

    public String formatLossList(List<String> displayNames) {
        if (displayNames == null || displayNames.isEmpty()) {
            return "nothing";
        }
        return displayNames.stream().collect(Collectors.joining(", "));
    }

    private static Optional<String> firstMatchingIgnoreCase(List<String> candidates, String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (String c : candidates) {
            if (c != null && c.equalsIgnoreCase(value)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    private static int indexOfIgnoreCase(List<String> list, String value) {
        if (value == null) {
            return -1;
        }
        for (int i = 0; i < list.size(); i++) {
            String s = list.get(i);
            if (s != null && s.equalsIgnoreCase(value)) {
                return i;
            }
        }
        return -1;
    }

    @Inject
    public VelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) {
        try {
            proxy.getChannelRegistrar().register(PORTAL_HANDOFF_CHANNEL);
            proxy.getChannelRegistrar().register(PORTAL_REQUEST_CHANNEL);
            proxy.getChannelRegistrar().register(MPDS_RETURN_REMOVE_CHANNEL);
            proxy.getChannelRegistrar().register(RETURN_OVERWORLD_CHANNEL);

            configLoader = new LobbyConfigLoader(logger, dataDir);
            configLoader.ensureDefaultConfig();
            portalTokenVerifier = new PortalTokenVerifier(logger);
            portalHandoffService = new PortalHandoffService(logger);
            portalRequestVerifier = new PortalRequestVerifier(logger);
            runtime = new RuntimeState(proxy, this, logger, portalHandoffService, portalTokenVerifier, portalRequestVerifier);
            stateStore = new PlayerStateStore(dataDir, logger);

            loadPlayerState();

            LobbyConfig config = loadConfig();
            purgeHoldingFromStore();
            purgeHoldingFromVisited();

            portalCommandHandler = new PortalCommandHandler(logger, new VelocityPortalCommandDependencies());
            commandRegistrar = new CommandRegistrar(proxy, runtime, portalCommandHandler, this, logger);

            logger.info("[WakeUpLobby] Loaded. Holding={}, grace={}s, interval={}s",
                    runtime.holdingServer(),
                    config.graceSec(),
                    config.pingEverySec());
        } catch (Exception ex) {
            logger.error("Failed to initialize WakeUpLobby", ex);
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (event.getIdentifier().equals(PORTAL_REQUEST_CHANNEL)) {
            handlePortalRequestMessage(event);
            return;
        }

        if (!event.getIdentifier().equals(MPDS_RETURN_REMOVE_CHANNEL)) {
            return;
        }

        byte[] data = event.getData();
        try (var in = new DataInputStream(new ByteArrayInputStream(data))) {
            long msb = in.readLong();
            long lsb = in.readLong();
            UUID requestId = new UUID(msb, lsb);

            boolean success = in.readBoolean();
            int removedInv = in.readInt();
            int removedDb = in.readInt();
            int remainingInv = in.readInt();
            int remainingDb = in.readInt();
            String error = readStringBytes(in, 8192);

            CompletableFuture<ReturnRemoveResponse> fut = pendingReturnRemovals.remove(requestId);
            if (fut != null) {
                fut.complete(new ReturnRemoveResponse(success, removedInv, removedDb, remainingInv, remainingDb, error));
            }
        } catch (Exception ex) {
            logger.warn("[WakeUpLobby] Failed parsing mpds:return_remove response: {}", ex.toString());
        }

        // This is a proxy-internal control message; do not forward to the client.
        try {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
        } catch (Throwable ignored) {
            // Older Velocity API variants may not expose ForwardResult; safe to ignore.
        }
    }

    private void handlePortalRequestMessage(PluginMessageEvent event) {
        try {
            if (!(event.getSource() instanceof ServerConnection backendConn)) {
                logger.warn("[WakeUpLobby] Ignoring portal_request from non-backend source: {}", event.getSource());
                return;
            }

            String backendName = backendConn.getServerInfo().getName();
            var requestOpt = PortalRequestPayloadCodec.decode(event.getData());
            if (requestOpt.isEmpty()) {
                logger.warn("[WakeUpLobby] Invalid portal_request payload from backend {}", backendName);
                return;
            }

            var request = requestOpt.get();
            if (runtime == null || portalRequestVerifier == null) {
                logger.warn("[WakeUpLobby] Portal request received before initialization completed (backend={})", backendName);
                return;
            }

            if (!portalRequestVerifier.verify(backendName, request)) {
                return;
            }

            var playerOpt = proxy.getPlayer(request.playerId());
            if (playerOpt.isEmpty()) {
                logger.warn("[WakeUpLobby] Portal request for offline player {} (backend={})", request.playerId(), backendName);
                return;
            }
            Player player = playerOpt.get();

            String target = request.targetServer();
            var targetReg = proxy.getServer(target);
            if (targetReg.isEmpty()) {
                logger.warn("[WakeUpLobby] Portal request rejected: unknown target server '{}' (backend={})", target, backendName);
                return;
            }

            String sourcePortal = request.sourcePortal();
            if (sourcePortal != null && !sourcePortal.isBlank()) {
                portalHandoffService.rememberSourcePortal(player.getUniqueId(), sourcePortal);
            }

            // Same behavior as /wl portal: unlock destination + sticky-wait through holding.
            visited.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>()).add(target);
            lastServer.put(player.getUniqueId(), target);
            saveVisited();
            saveStore();

            String origin = currentServerName(player).orElse(null);
            runtime.stickyRouter().beginStickyWait(player.getUniqueId(), target, origin);
            runtime.stickyRouter().markInternalOnce(player.getUniqueId());

            var holdingReg = proxy.getServer(runtime.holdingServer());
            if (holdingReg.isEmpty()) {
                logger.error("[WakeUpLobby] Holding server '{}' not found; cannot process portal request", runtime.holdingServer());
                return;
            }

            player.createConnectionRequest(holdingReg.get()).fireAndForget();
        } catch (Exception ex) {
            logger.warn("[WakeUpLobby] Failed handling portal_request: {}", ex.toString());
        } finally {
            // proxy-internal; do not forward to client
            try {
                event.setResult(PluginMessageEvent.ForwardResult.handled());
            } catch (Throwable ignored) {
            }
        }
    }

    CompletableFuture<ReturnRemoveResponse> requestMpdsReturnRemoval(Player player, List<ReturnSpecial> specials) {
        if (player == null || specials == null || specials.isEmpty()) {
            return CompletableFuture.completedFuture(new ReturnRemoveResponse(true, 0, 0, 0, 0, ""));
        }

        var current = player.getCurrentServer();
        if (current.isEmpty()) {
            return CompletableFuture.completedFuture(new ReturnRemoveResponse(false, 0, 0, 0, 0, "Not connected to a server"));
        }

        UUID requestId = UUID.randomUUID();
        CompletableFuture<ReturnRemoveResponse> fut = new CompletableFuture<>();
        pendingReturnRemovals.put(requestId, fut);

        byte[] payload;
        try (var bos = new ByteArrayOutputStream(); var out = new DataOutputStream(bos)) {
            out.writeLong(requestId.getMostSignificantBits());
            out.writeLong(requestId.getLeastSignificantBits());
            out.writeInt(Math.min(specials.size(), 32));
            for (int i = 0; i < specials.size() && i < 32; i++) {
                ReturnSpecial spec = specials.get(i);
                writeStringBytes(out, spec.key(), 128);
                writeStringBytes(out, spec.value(), 256);
            }
            out.flush();
            payload = bos.toByteArray();
        } catch (Exception ex) {
            pendingReturnRemovals.remove(requestId);
            return CompletableFuture.completedFuture(new ReturnRemoveResponse(false, 0, 0, 0, 0, "Encode failed: " + ex));
        }

        boolean sent = current.get().sendPluginMessage(MPDS_RETURN_REMOVE_CHANNEL, payload);
        if (!sent) {
            pendingReturnRemovals.remove(requestId);
            return CompletableFuture.completedFuture(new ReturnRemoveResponse(false, 0, 0, 0, 0, "Server did not accept plugin message"));
        }

        return fut.orTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    pendingReturnRemovals.remove(requestId);
                    return new ReturnRemoveResponse(false, 0, 0, 0, 0, "Timeout waiting for MPDS confirmation");
                });
    }

    private static void writeStringBytes(DataOutputStream out, String value, int maxLen) throws IOException {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > maxLen) {
            throw new IllegalArgumentException("String too long: " + bytes.length);
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readStringBytes(DataInputStream in, int maxLen) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > maxLen) {
            throw new IllegalArgumentException("Bad string length: " + len);
        }
        byte[] bytes = in.readNBytes(len);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    void requestReturnOverworldIfNeeded(Player player, String destServer, String originServer) {
        if (player == null || destServer == null || destServer.isBlank() || runtime == null) {
            return;
        }

        if (originServer == null || !originServer.equalsIgnoreCase(destServer)) {
            return;
        }

        var current = player.getCurrentServer();
        if (current.isEmpty() || current.get().getServerInfo() == null) {
            return;
        }

        String currentName = current.get().getServerInfo().getName();
        if (!currentName.equalsIgnoreCase(destServer)) {
            return;
        }

        current.get().sendPluginMessage(RETURN_OVERWORLD_CHANNEL, new byte[0]);
        logger.info("[WakeUpLobby] Requested overworld return for {} on server {} (origin={})",
            player.getUsername(),
            destServer,
            originServer);
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

        // Allow plugin-internal switches (auto-move on ready)
        if (runtime.stickyRouter().consumeInternalOnce(player.getUniqueId())) {
            logger.info("[WakeUpLobby] onPreConnect: {} internal switch -> '{}' (ALLOWED)", player.getUsername(), requested);
            return;
        }

        boolean isAdmin = hasBypass(player); // wakeuplobby.bypass
        logger.info("[WakeUpLobby] onPreConnect: player={} isAdmin={}", player.getUsername(), isAdmin);

        // If an admin manually requests a server while a sticky-wait is in progress, cancel the sticky-wait.
        // Otherwise the background sticky router can keep attempting to connect and effectively override manual /server.
        if (isAdmin
                && !requested.equalsIgnoreCase(runtime.holdingServer())
                && runtime.stickyRouter().hasStickyState(player.getUniqueId())) {
            logger.info("[WakeUpLobby] onPreConnect: {} admin manual request '{}' while sticky-wait active; cancelling sticky-wait",
                    player.getUsername(), requested);
            runtime.stickyRouter().cancelStickyWait(player.getUniqueId());
        }

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
            || parts[0].equals("teammsg")
            || parts[0].equals("return");
        if (allowedBasic) {
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
            runtime.stickyRouter().clearReturnEligibility(p.getUniqueId());

            visited.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>()).add(srv);
            logger.info("[WakeUpLobby] onConnected: added '{}' to visited set for {}", srv, p.getUsername());
            proxy.getScheduler().buildTask(this, this::saveVisited).delay(Duration.ofSeconds(1)).schedule();

            lastServer.put(p.getUniqueId(), srv);
            proxy.getScheduler().buildTask(this, this::saveStore).delay(Duration.ofSeconds(1)).schedule();

            logger.info("[WakeUpLobby] onConnected: {} now last='{}'", p.getUsername(), srv);

            LobbyConfig cfg = runtime.currentConfig();
            if (cfg != null && cfg.returnServerOrder().stream().anyMatch(s -> s.equalsIgnoreCase(srv))) {
                lastListedServer.put(p.getUniqueId(), srv);
                proxy.getScheduler().buildTask(this, this::saveLastListed).delay(Duration.ofSeconds(1)).schedule();
                logger.info("[WakeUpLobby] onConnected: {} lastListed='{}'", p.getUsername(), srv);
            }
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

        lastListedServer.clear();
        lastListedServer.putAll(stateStore.loadLastListedServers());
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

    /** Allowed targets in descending order: [tier, tier-1, ..., 0]. New players => [def[0]]  */
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

    private void saveLastListed() {
        stateStore.saveLastListedServers(lastListedServer);
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

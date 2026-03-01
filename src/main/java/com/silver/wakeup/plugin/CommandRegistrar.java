package com.silver.wakeup.plugin;

import com.silver.wakeup.portal.PortalCommandHandler;
import com.silver.wakeup.config.ReturnSpecial;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Centralises registration and handling for all WakeUpLobby commands.
 */
public class CommandRegistrar {
    private static final String SERVER_PERMISSION = "wakeuplobby.server";
    private static final String WAKEUPLOBBY_USAGE = "§7Usage: /wakeuplobby reload | /wakeuplobby ops <list|add|remove> [player] | /wakeuplobby whitelist <list|add|remove> [player]";

    private final ProxyServer proxy;
    private final RuntimeState runtime;
    private final PortalCommandHandler portalCommandHandler;
    private final VelocityPlugin plugin;
    private final Logger logger;
    private final UUIDResolver uuidResolver;

    private boolean registered;

    public CommandRegistrar(ProxyServer proxy,
                            RuntimeState runtime,
                            PortalCommandHandler portalCommandHandler,
                            VelocityPlugin plugin,
                            Logger logger) {
        this.proxy = proxy;
        this.runtime = runtime;
        this.portalCommandHandler = portalCommandHandler;
        this.plugin = plugin;
        this.logger = logger;
        this.uuidResolver = new UUIDResolver(proxy, logger);
    }

    void register() {
        if (registered) {
            return;
        }
        registerReloadCommand();
        registerPortalCommand();
        registerMessageCommands();
        registerServerOverride();
        registerForceServerCommand();
        registerReturnCommand();
        registered = true;
        logger.info("[WakeUpLobby] Commands registered: /wakeuplobby reload, /wakeuplobby ops, /wl portal, /server (override), /forceserver, /return");
    }

    private void registerReloadCommand() {
        proxy.getCommandManager().register(
            "wakeuplobby",
            new SimpleCommand() {
                @Override
                public void execute(Invocation invocation) {
                    CommandSource source = invocation.source();
                    String[] args = invocation.arguments();

                    if (args.length == 0) {
                        source.sendMessage(Component.text(WAKEUPLOBBY_USAGE));
                        return;
                    }

                    if (args[0].equalsIgnoreCase("reload")) {
                        if (!canManageWakeupLobby(source)) {
                            source.sendMessage(Component.text("§cYou do not have permission to use this command."));
                            return;
                        }
                        try {
                            plugin.reloadConfig();
                            source.sendMessage(Component.text("§aWakeUpLobby config and velocity ops reloaded successfully."));
                        } catch (IOException e) {
                            logger.error("Reload failed", e);
                            source.sendMessage(Component.text("§cFailed to reload config: " + e.getMessage()));
                        }
                        return;
                    }

                    if (args[0].equalsIgnoreCase("ops")) {
                        handleOpsCommand(source, args);
                        return;
                    }

                    if (args[0].equalsIgnoreCase("whitelist")) {
                        handleWhitelistCommand(source, args);
                        return;
                    }

                    source.sendMessage(Component.text(WAKEUPLOBBY_USAGE));
                }

                @Override
                public boolean hasPermission(Invocation invocation) {
                    return canManageWakeupLobby(invocation.source());
                }
            }
        );
    }

    private void handleOpsCommand(CommandSource source, String[] args) {
        if (!canManageWakeupLobby(source)) {
            source.sendMessage(Component.text("§cYou do not have permission to use this command."));
            return;
        }

        if (args.length < 2) {
            source.sendMessage(Component.text("§7Usage: /wakeuplobby ops <list|add|remove> [player]"));
            return;
        }

        if (args[1].equalsIgnoreCase("list")) {
            var ops = plugin.listVelocityOps();
            if (ops.isEmpty()) {
                source.sendMessage(Component.text("§eVelocity ops list is empty."));
            } else {
                source.sendMessage(Component.text("§aVelocity ops: §f" + String.join(", ", ops)));
            }
            return;
        }

        if (args.length < 3) {
            source.sendMessage(Component.text("§7Usage: /wakeuplobby ops " + args[1].toLowerCase() + " <player>"));
            return;
        }

        String target = args[2];
        try {
            if (args[1].equalsIgnoreCase("add")) {
                boolean changed = plugin.addVelocityOp(target);
                source.sendMessage(Component.text(changed
                        ? "§aAdded velocity op: §f" + target
                        : "§ePlayer is already a velocity op: §f" + target));
                return;
            }

            if (args[1].equalsIgnoreCase("remove")) {
                boolean changed = plugin.removeVelocityOp(target);
                source.sendMessage(Component.text(changed
                        ? "§aRemoved velocity op: §f" + target
                        : "§ePlayer is not in velocity ops: §f" + target));
                return;
            }

            source.sendMessage(Component.text("§7Usage: /wakeuplobby ops <list|add|remove> [player]"));
        } catch (IOException e) {
            logger.error("Failed to update velocity ops list", e);
            source.sendMessage(Component.text("§cFailed updating velocity ops list: " + e.getMessage()));
        }
    }

    private void handleWhitelistCommand(CommandSource source, String[] args) {
        if (!canManageWakeupLobby(source)) {
            source.sendMessage(Component.text("§cYou do not have permission to use this command."));
            return;
        }

        if (args.length < 2) {
            source.sendMessage(Component.text("§7Usage: /wakeuplobby whitelist <list|add|remove> [player]"));
            return;
        }

        if (args[1].equalsIgnoreCase("list")) {
            var uuids = plugin.listWhitelist();
            if (uuids.isEmpty()) {
                source.sendMessage(Component.text("§eWhitelist is empty."));
            } else {
                source.sendMessage(Component.text("§aWhitelist (" + uuids.size() + " player(s)):"));
                // Resolve names asynchronously
                for (UUID uuid : uuids) {
                    uuidResolver.resolveName(uuid).thenAccept(nameOpt -> {
                        String display = nameOpt.map(name -> "§f" + name + "§7 (" + uuid + ")")
                                .orElse("§f" + uuid.toString());
                        source.sendMessage(Component.text("  " + display));
                    });
                }
            }
            return;
        }

        if (args.length < 3) {
            source.sendMessage(Component.text("§7Usage: /wakeuplobby whitelist " + args[1].toLowerCase() + " <player>"));
            return;
        }

        String target = args[2];
        
        // Try parsing as UUID first, then as player name
        try {
            java.util.UUID uuid = java.util.UUID.fromString(target);
            // Direct UUID provided
            handleWhitelistUUID(source, args[1], uuid, target);
        } catch (IllegalArgumentException e) {
            // Not a UUID, try resolving as player name
            source.sendMessage(Component.text("§eResolving player '" + target + "'..."));
            uuidResolver.resolveUUID(target).thenAccept(uuidOpt -> {
                if (uuidOpt.isEmpty()) {
                    source.sendMessage(Component.text("§cPlayer '" + target + "' not found. Player may not exist or Mojang API is unavailable."));
                    return;
                }
                handleWhitelistUUID(source, args[1], uuidOpt.get(), target);
            }).exceptionally(ex -> {
                logger.error("Failed to resolve UUID for player '{}'", target, ex);
                source.sendMessage(Component.text("§cFailed to resolve player '" + target + "': " + ex.getMessage()));
                return null;
            });
        }
    }

    private void handleWhitelistUUID(CommandSource source, String action, UUID uuid, String originalInput) {
        try {
            if (action.equalsIgnoreCase("add")) {
                boolean changed = plugin.addWhitelist(uuid);
                source.sendMessage(Component.text(changed
                        ? "§aAdded to whitelist: §f" + originalInput + "§7 (" + uuid + ")"
                        : "§eAlready whitelisted: §f" + originalInput + "§7 (" + uuid + ")"));
                return;
            }

            if (action.equalsIgnoreCase("remove")) {
                boolean changed = plugin.removeWhitelist(uuid);
                source.sendMessage(Component.text(changed
                        ? "§aRemoved from whitelist: §f" + originalInput + "§7 (" + uuid + ")"
                        : "§eNot in whitelist: §f" + originalInput + "§7 (" + uuid + ")"));
                return;
            }

            source.sendMessage(Component.text("§7Usage: /wakeuplobby whitelist <list|add|remove> [player]"));
        } catch (IOException e) {
            logger.error("Failed to update whitelist", e);
            source.sendMessage(Component.text("§cFailed updating whitelist: " + e.getMessage()));
        }
    }

    private boolean canManageWakeupLobby(CommandSource source) {
        if (!(source instanceof Player player)) {
            return true;
        }
        return plugin.isVelocityOp(player.getUsername());
    }

    private void registerPortalCommand() {
        CommandMeta meta = proxy.getCommandManager()
                                .metaBuilder("wl")
                                .plugin(plugin)
                                .hint(LiteralArgumentBuilder.<CommandSource>literal("wl")
                                .requires(source -> false)
                                .build())
                                .build();

        proxy.getCommandManager().register(
            meta,
            new SimpleCommand() {
                    @Override
                    public void execute(Invocation inv) {
                        if (!(inv.source() instanceof Player player)) {
                            inv.source().sendMessage(Component.text("Players only."));
                            return;
                        }

                        String[] args = inv.arguments();
                        if (args.length < 3 || !args[0].equalsIgnoreCase("portal")) {
                            return;
                        }

                        String target = args[1];
                        String token = args[2];
                        String sourcePortal = args.length > 3 ? args[3] : null;

                        logger.info("[WakeUpLobby] /wl portal: player={} target={} token={} sourcePortal={}",
                                player.getUsername(), target, token, sourcePortal);

                        if (portalCommandHandler == null) {
                            logger.warn("[WakeUpLobby] /wl portal invoked before initialization completed");
                            player.sendMessage(Component.text("§cPortal system not ready yet. Try again shortly."));
                            return;
                        }

                        portalCommandHandler.handle(player, target, token, Optional.ofNullable(sourcePortal));
                    }
            }
        );
    }

    private void registerMessageCommands() {
    proxy.getCommandManager().register("w",
        new SanitizedForwardCommand(proxy, logger, this::hasBypass, "minecraft:msg", "w", true));
    proxy.getCommandManager().register("msg",
        new SanitizedForwardCommand(proxy, logger, this::hasBypass, "minecraft:msg", "msg", true));
    proxy.getCommandManager().register("teammsg",
        new SanitizedForwardCommand(proxy, logger, this::hasBypass, "minecraft:teammsg", "teammsg", false));
    }

    private void registerServerOverride() {
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("server")
                        .aliases("connect")
                        .build(),
                new SimpleCommand() {
                    @Override
                    public void execute(Invocation invocation) {
                        if (!(invocation.source() instanceof Player player)) {
                            invocation.source().sendMessage(Component.text("Players only."));
                            return;
                        }

                        if (!hasBypass(player)) {
                            player.sendMessage(Component.text("⚠ Server switching is disabled."));
                            return;
                        }

                        String[] args = invocation.arguments();
                        if (args.length == 0) {
                            player.sendMessage(Component.text("Usage: /server <server>"));
                            return;
                        }

                        String targetServer = args[0];
                        var serverOpt = proxy.getServer(targetServer);
                        if (serverOpt.isEmpty()) {
                            player.sendMessage(Component.text("⚠ Unknown server: " + targetServer));
                            return;
                        }

                        // Manual admin switch should cancel any in-progress sticky auto-move sequence.
                        runtime.stickyRouter().cancelStickyWait(player.getUniqueId());
                        runtime.stickyRouter().markInternalOnce(player.getUniqueId());
                        player.createConnectionRequest(serverOpt.get()).fireAndForget();
                    }

                    @Override
                    public boolean hasPermission(Invocation invocation) {
                        if (!(invocation.source() instanceof Player player)) {
                            return true;
                        }
                        return hasBypass(player);
                    }
                }
        );
    }

    private void registerForceServerCommand() {
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("forceserver").build(),
                new SimpleCommand() {
                    @Override
                    public void execute(Invocation invocation) {
                        CommandSource source = invocation.source();
                        if (!canManageWakeupLobby(source)) {
                            source.sendMessage(Component.text("§cYou do not have permission to use this command."));
                            return;
                        }

                        String[] args = invocation.arguments();
                        if (args.length < 2) {
                            source.sendMessage(Component.text("§7Usage: /forceserver <player> <server>"));
                            return;
                        }

                        String targetPlayerName = args[0];
                        String targetServerName = args[1];

                        Optional<Player> targetPlayerOpt = proxy.getPlayer(targetPlayerName);
                        if (targetPlayerOpt.isEmpty()) {
                            source.sendMessage(Component.text("§cPlayer '" + targetPlayerName + "' is not online."));
                            return;
                        }

                        var serverOpt = proxy.getServer(targetServerName);
                        if (serverOpt.isEmpty()) {
                            source.sendMessage(Component.text("§cUnknown server: " + targetServerName));
                            return;
                        }

                        Player targetPlayer = targetPlayerOpt.get();

                        // Cancel any sticky wait exactly like the /server override
                        runtime.stickyRouter().cancelStickyWait(targetPlayer.getUniqueId());
                        runtime.stickyRouter().markInternalOnce(targetPlayer.getUniqueId());

                        targetPlayer.createConnectionRequest(serverOpt.get()).fireAndForget();
                        source.sendMessage(Component.text("§aMoving " + targetPlayerName + " to " + targetServerName + "..."));
                    }

                    @Override
                    public boolean hasPermission(Invocation invocation) {
                        return canManageWakeupLobby(invocation.source());
                    }

                    @Override
                    public java.util.List<String> suggest(Invocation invocation) {
                        String[] args = invocation.arguments();
                        if (args.length == 0 || args.length == 1) {
                            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
                            return proxy.getAllPlayers().stream()
                                    .map(Player::getUsername)
                                    .filter(name -> name.toLowerCase().startsWith(prefix))
                                    .collect(java.util.stream.Collectors.toList());
                        } else if (args.length == 2) {
                            String prefix = args[1].toLowerCase();
                            return proxy.getAllServers().stream()
                                    .map(server -> server.getServerInfo().getName())
                                    .filter(name -> name.toLowerCase().startsWith(prefix))
                                    .collect(java.util.stream.Collectors.toList());
                        }
                        return java.util.List.of();
                    }
                }
        );
    }

    private void registerReturnCommand() {
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("return").build(),
                new SimpleCommand() {
                    @Override
                    public void execute(Invocation invocation) {
                        if (!(invocation.source() instanceof Player player)) {
                            invocation.source().sendMessage(Component.text("Players only."));
                            return;
                        }

                        String current = player.getCurrentServer()
                                .map(cs -> cs.getServerInfo().getName())
                                .orElse(null);
                        if (current == null || !current.equalsIgnoreCase(runtime.holdingServer())) {
                            player.sendMessage(Component.text("⚠ You can only use /return while waiting in the lobby."));
                            return;
                        }

                        if (!runtime.stickyRouter().isReturnEligible(player.getUniqueId())) {
                            player.sendMessage(Component.text("⚠ /return is only available after the grace period expires."));
                            return;
                        }

                        String dest = plugin.computeReturnDestination(player.getUniqueId());
                        if (dest == null || dest.isBlank()) {
                            player.sendMessage(Component.text("⚠ No return destination is configured."));
                            return;
                        }

                        var serverOpt = proxy.getServer(dest);
                        if (serverOpt.isEmpty()) {
                            player.sendMessage(Component.text("⚠ Return server not found: " + dest));
                            return;
                        }

                        var specialsToRemove = plugin.computeReturnSpecials(player.getUniqueId());
                        player.sendMessage(Component.text("§eReturning you to §a" + dest + "§e…"));
                        String returnOrigin = runtime.stickyRouter().returnOriginServer(player.getUniqueId());

                        // If we have specials to remove, do it BEFORE switching servers and confirm they're gone.
                        if (!specialsToRemove.isEmpty()) {
                            player.sendMessage(Component.text("§eRemoving special items before return…"));
                            plugin.requestMpdsReturnRemoval(player, specialsToRemove).whenComplete((resp, ex) -> {
                                if (ex != null || resp == null || !resp.success()) {
                                    player.sendMessage(Component.text("⚠ Could not confirm special item removal. Not returning."));
                                    return;
                                }
                                if (resp.remainingInventory() > 0 || resp.remainingDb() > 0) {
                                    player.sendMessage(Component.text("⚠ Special items still present after removal. Not returning."));
                                    return;
                                }

                                // Now that removal is confirmed, consume eligibility so it can't be spammed.
                                runtime.stickyRouter().clearReturnEligibility(player.getUniqueId());

                                // Manual return should cancel any in-progress sticky auto-move sequence.
                                runtime.stickyRouter().cancelStickyWait(player.getUniqueId());
                                runtime.stickyRouter().markInternalOnce(player.getUniqueId());
                                player.createConnectionRequest(serverOpt.get()).connect().whenComplete((result, err) -> {
                                    if (err != null || result == null || !result.isSuccessful()) {
                                        player.sendMessage(Component.text("⚠ Failed to connect to " + dest + "."));
                                        return;
                                    }
                                    plugin.requestReturnOverworldIfNeeded(player, dest, returnOrigin);
                                });
                            });
                            return;
                        }

                        // No specials; return immediately.
                        String returnOriginImmediate = runtime.stickyRouter().returnOriginServer(player.getUniqueId());
                        runtime.stickyRouter().clearReturnEligibility(player.getUniqueId());
                        runtime.stickyRouter().cancelStickyWait(player.getUniqueId());
                        runtime.stickyRouter().markInternalOnce(player.getUniqueId());
                        player.createConnectionRequest(serverOpt.get()).connect().whenComplete((result, err) -> {
                            if (err != null || result == null || !result.isSuccessful()) {
                                player.sendMessage(Component.text("⚠ Failed to connect to " + dest + "."));
                                return;
                            }
                            plugin.requestReturnOverworldIfNeeded(player, dest, returnOriginImmediate);
                        });
                    }
                }
        );
    }

    private boolean hasBypass(Player player) {
        return player.hasPermission(SERVER_PERMISSION) || plugin.isVelocityOp(player.getUsername());
    }
}

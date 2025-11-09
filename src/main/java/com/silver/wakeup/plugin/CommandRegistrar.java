package com.silver.wakeup.plugin;

import com.silver.wakeup.portal.PortalCommandHandler;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;

/**
 * Centralises registration and handling for all WakeUpLobby commands.
 */
public class CommandRegistrar {
    private final ProxyServer proxy;
    private final RuntimeState runtime;
    private final PortalCommandHandler portalCommandHandler;
    private final VelocityPlugin plugin;
    private final Logger logger;

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
    }

    void register() {
        if (registered) {
            return;
        }
        registerReloadCommand();
        registerPortalCommand();
        registerMessageCommands();
        registerServerOverride();
        registered = true;
        logger.info("[WakeUpLobby] Commands registered: /wakeuplobby reload, /wl portal, /server (override)");
    }

    private void registerReloadCommand() {
        proxy.getCommandManager().register(
            "wakeuplobby",
            new SimpleCommand() {
                @Override
                public void execute(Invocation invocation) {
                    CommandSource source = invocation.source();

                    if (source instanceof Player player) {
                        String name = player.getUsername().toLowerCase(Locale.ROOT);
                        if (!runtime.isAdmin(name)) {
                            source.sendMessage(Component.text("§cYou do not have permission to use this command."));
                            return;
                        }
                    }

                    String[] args = invocation.arguments();
                    if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                        try {
                            plugin.reloadConfig();
                            source.sendMessage(Component.text("§aWakeUpLobby config reloaded successfully."));
                        } catch (IOException e) {
                            logger.error("Reload failed", e);
                            source.sendMessage(Component.text("§cFailed to reload config: " + e.getMessage()));
                        }
                    } else {
                        source.sendMessage(Component.text("§7Usage: /wakeuplobby reload"));
                    }
                }
            }
        );
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
        new SanitizedForwardCommand(proxy, runtime, logger, "minecraft:msg", "w", true));
    proxy.getCommandManager().register("msg",
        new SanitizedForwardCommand(proxy, runtime, logger, "minecraft:msg", "msg", true));
    proxy.getCommandManager().register("teammsg",
        new SanitizedForwardCommand(proxy, runtime, logger, "minecraft:teammsg", "teammsg", false));
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
                            player.sendMessage(Component.text("⚠ Server switching is disabled. Use /fallback if stuck."));
                            return;
                        }

                        String[] args = invocation.arguments();
                        if (args.length == 0) {
                            player.sendMessage(Component.text("Available servers: " +
                                    String.join(", ", proxy.getAllServers().stream()
                                            .map(s -> s.getServerInfo().getName())
                                            .toList())));
                            return;
                        }

                        String targetServer = args[0];
                        var serverOpt = proxy.getServer(targetServer);
                        if (serverOpt.isEmpty()) {
                            player.sendMessage(Component.text("⚠ Unknown server: " + targetServer));
                            return;
                        }

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

    private boolean hasBypass(Player player) {
        return runtime.isAdmin(player.getUsername());
    }
}

package com.silver.wakeup.plugin;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Proxies a command to a backend namespaced command while enforcing simple selector restrictions.
 */
final class SanitizedForwardCommand implements SimpleCommand {
    private final ProxyServer proxy;
    private final RuntimeState runtime;
    private final Logger logger;
    private final String backendLiteral;
    private final String displayLiteral;
    private final boolean requiresTarget;

    SanitizedForwardCommand(ProxyServer proxy,
                            RuntimeState runtime,
                            Logger logger,
                            String backendLiteral,
                            String displayLiteral,
                            boolean requiresTarget) {
        this.proxy = proxy;
        this.runtime = runtime;
        this.logger = logger;
        this.backendLiteral = backendLiteral;
        this.displayLiteral = displayLiteral;
        this.requiresTarget = requiresTarget;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text("Players only."));
            return;
        }

        String[] args = invocation.arguments();
        if (!hasRequiredArgs(args)) {
            player.sendMessage(Component.text(usageMessage()));
            return;
        }

        boolean isAdmin = runtime != null && runtime.isAdmin(player.getUsername().toLowerCase(Locale.ROOT));
        if (!isAdmin && containsSelector(args)) {
            player.sendMessage(Component.text("⚠ Selectors are not permitted in this command."));
            return;
        }

        forward(player, args);
    }

    private boolean hasRequiredArgs(String[] args) {
        if (requiresTarget) {
            return args.length >= 2;
        }
        return args.length >= 1;
    }

    private String usageMessage() {
        if (requiresTarget) {
            return "§7Usage: /" + displayLiteral + " <player> <message>";
        }
        return "§7Usage: /" + displayLiteral + " <message>";
    }

    private boolean containsSelector(String[] args) {
        return Arrays.stream(args).anyMatch(arg -> arg.indexOf('@') >= 0);
    }

    private void forward(Player player, String[] args) {
        String joined = String.join(" ", args);
        String displayLiteral = playerFacingLiteral();
        String commandLine = displayLiteral + (joined.isEmpty() ? "" : " " + joined);
        try {
            // Spoof the sanitized command so it reaches the backend server like a normal chat input.
            player.spoofChatInput("/" + commandLine);
        } catch (Exception ex) {
            logger.warn("[WakeUpLobby] Failed to proxy command '{}' for {}", commandLine, player.getUsername(), ex);
            player.sendMessage(Component.text("§cUnable to execute backend command."));
        }
    }

    private String playerFacingLiteral() {
        int colon = backendLiteral.indexOf(':');
        return colon >= 0 ? backendLiteral.substring(colon + 1) : backendLiteral;
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!requiresTarget) {
            return Collections.emptyList();
        }

        String[] args = invocation.arguments();
        if (args.length == 0) {
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        }

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}

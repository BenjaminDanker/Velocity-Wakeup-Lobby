package com.silver.wakeup.plugin;

import com.silver.authorization.AuthorizationScope;
import com.silver.authorization.AuthorizationSubject;
import com.silver.authorization.CommandCatalog;
import com.silver.authorization.CommandCatalogEntry;
import com.silver.authorization.CommandClassification;
import com.silver.authorization.CommandPath;
import com.silver.authorization.CommandPolicyDecision;
import com.silver.authorization.CommandOrigin;
import com.silver.authorization.CommandPolicyRegistry;
import com.silver.authorization.DiscoveredCommandPolicies;
import com.silver.authorization.PermissionEffect;
import com.silver.authorization.PermissionNode;
import com.silver.authorization.PermissionNodes;
import com.silver.authorization.PermissionPattern;
import com.silver.authorization.RoleAssignment;
import com.silver.authorization.RoleId;
import com.silver.authorization.RolePermission;
import com.silver.authorization.ServerId;
import com.silver.wakeup.authorization.AuthorizationMutation;
import com.silver.wakeup.authorization.AuthorizationService;
import com.silver.wakeup.authorization.AuthorizationState;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.nio.charset.StandardCharsets;

/** Velocity's audited central staff-management interface. */
final class AuthorizationCommand implements SimpleCommand {
    private static final CommandPolicyRegistry COMMAND_POLICIES = DiscoveredCommandPolicies.create();
    private static final int REPORT_PAGE_SIZE = 12;
    private final ProxyServer proxy;
    private final VelocityPlugin plugin;
    private final AuthorizationService authorization;
    private final UUIDResolver uuids;
    private final Logger log;

    AuthorizationCommand(ProxyServer proxy, VelocityPlugin plugin, AuthorizationService authorization,
                         UUIDResolver uuids, Logger log) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.authorization = authorization;
        this.uuids = uuids;
        this.log = log;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        if (args.length == 0) {
            usage(source);
            return;
        }
        int targetIndex = targetIndex(args);
        if (targetIndex < 0 || args.length <= targetIndex) {
            executeResolved(source, args, null);
            return;
        }
        resolvePlayer(args[targetIndex]).whenComplete((resolution, failure) -> {
            if (failure != null || resolution == null || resolution.uuid().isEmpty()) {
                if (resolution != null && resolution.ambiguous()) {
                    source.sendMessage(Component.text("Ambiguous player name: " + args[targetIndex]
                            + ". Use the player's UUID instead."));
                    return;
                }
                source.sendMessage(Component.text("Unknown player or UUID: " + args[targetIndex]
                        + ". Use an online name, known username, or UUID."));
                return;
            }
            executeResolved(source, args, resolution.uuid().orElseThrow());
        });
    }

    private static int targetIndex(String[] args) {
        if (args.length == 0) return -1;
        if (args[0].equalsIgnoreCase("explain")) return args.length > 1 ? 1 : -1;
        if (args[0].equalsIgnoreCase("commands") && args.length >= 2
                && args[1].equalsIgnoreCase("user")) return args.length > 2 ? 2 : -1;
        if (!args[0].equalsIgnoreCase("user") || args.length < 2) return -1;
        if (args[1].equalsIgnoreCase("info")) return args.length > 2 ? 2 : -1;
        if (args.length >= 4 && args[1].equalsIgnoreCase("permission")
                && Set.of("allow", "deny", "remove").contains(args[2].toLowerCase(Locale.ROOT))) return 3;
        if (args.length >= 4 && args[1].equalsIgnoreCase("role")
                && Set.of("add", "remove").contains(args[2].toLowerCase(Locale.ROOT))) return 3;
        // Temporary compatibility with /auth user <player> info|role|permission ...
        return 1;
    }

    private CompletableFuture<PlayerResolution> resolvePlayer(String input) {
        try {
            return CompletableFuture.completedFuture(new PlayerResolution(Optional.of(UUID.fromString(input)), false));
        } catch (IllegalArgumentException ignored) {
            // Names are lookup aliases only; UUID remains the stored identity.
        }
        Set<UUID> matches = new java.util.HashSet<>();
        proxy.getAllPlayers().stream().filter(player -> player.getUsername().equalsIgnoreCase(input))
                .map(Player::getUniqueId).forEach(matches::add);
        authorization.currentState().usernames().entrySet().stream()
                .filter(entry -> entry.getValue().equalsIgnoreCase(input))
                .map(Map.Entry::getKey).forEach(matches::add);
        if (matches.size() > 1) {
            return CompletableFuture.completedFuture(new PlayerResolution(Optional.empty(), true));
        }
        if (matches.size() == 1) {
            return CompletableFuture.completedFuture(new PlayerResolution(Optional.of(matches.iterator().next()), false));
        }
        return uuids.resolveUUID(input).thenApply(uuid -> new PlayerResolution(uuid, false));
    }

    private record PlayerResolution(Optional<UUID> uuid, boolean ambiguous) { }

    private void executeResolved(CommandSource source, String[] args, UUID target) {
        try {
            if (args[0].equalsIgnoreCase("user")) {
                handleUser(source, args, target);
            } else if (args[0].equalsIgnoreCase("role")) {
                handleRole(source, args);
            } else if (args[0].equalsIgnoreCase("explain")) {
                handleExplain(source, args, target);
            } else if (args[0].equalsIgnoreCase("commands")) {
                handleCommands(source, args, target);
            } else {
                usage(source);
            }
        } catch (IllegalArgumentException invalid) {
            source.sendMessage(Component.text("Invalid authorization command: " + invalid.getMessage()));
        } catch (SQLException failure) {
            source.sendMessage(Component.text("Authorization change rejected: " + failure.getMessage()));
            log.warn("[Authorization] Command mutation rejected: {}", failure.getMessage());
        } catch (RuntimeException failure) {
            source.sendMessage(Component.text("Authorization command failed: " + failure.getMessage()));
            log.error("[Authorization] Command failed", failure);
        }
    }

    private void handleUser(CommandSource source, String[] args, UUID target) throws SQLException {
        if (args.length == 3 && args[1].equalsIgnoreCase("info")) {
            if (!mayManage(source, PermissionNodes.STAFF_ROLES_MODIFY, AuthorizationScope.global())
                    && !mayManage(source, PermissionNodes.STAFF_PERMISSIONS_MODIFY, AuthorizationScope.global())) {
                denied(source);
                return;
            }
            showUserInfo(source, target);
            return;
        }
        if (args.length == 3 && args[2].equalsIgnoreCase("info")) {
            if (!mayManage(source, PermissionNodes.STAFF_ROLES_MODIFY, AuthorizationScope.global())
                    && !mayManage(source, PermissionNodes.STAFF_PERMISSIONS_MODIFY, AuthorizationScope.global())) {
                denied(source);
                return;
            }
            showUserInfo(source, target);
            return;
        }
        boolean newRoleOrder = args.length >= 3 && args[1].equalsIgnoreCase("role");
        if ((newRoleOrder && args.length >= 4) || (args.length >= 4 && args[2].equalsIgnoreCase("role"))) {
            int operationIndex = newRoleOrder ? 2 : 3;
            int roleIndex = newRoleOrder ? operationIndex + 2 : operationIndex + 1;
            if (args.length <= roleIndex) throw new IllegalArgumentException(
                    "usage: /auth user role <add|remove> <player|uuid> <role> [server|global]");
            boolean add = operation(args[operationIndex], "add", "remove");
            RoleId role = RoleId.of(args[roleIndex]);
            AuthorizationScope scope = scope(args, 5);
            if (!mayManage(source, PermissionNodes.STAFF_ROLES_MODIFY, scope)) { denied(source); return; }
            mutate(source, new AuthorizationMutation.RoleAssignment(target, role, scope, add),
                    "Role " + role + (add ? " added to " : " removed from ") + target + " at " + scope.scopeKey());
            return;
        }
        boolean newPermissionOrder = args.length >= 3 && args[1].equalsIgnoreCase("permission");
        if ((newPermissionOrder && args.length >= 4) || (args.length >= 4 && args[2].equalsIgnoreCase("permission"))) {
            int operationIndex = newPermissionOrder ? 2 : 3;
            int permissionIndex = newPermissionOrder ? operationIndex + 2 : operationIndex + 1;
            if (args.length <= permissionIndex) throw new IllegalArgumentException(
                    "usage: /auth user permission <allow|deny|remove> <player|uuid> <permission> [server|global]");
            String op = args[operationIndex].toLowerCase(Locale.ROOT);
            Optional<PermissionEffect> effect = switch (op) {
                case "allow" -> Optional.of(PermissionEffect.ALLOW);
                case "deny" -> Optional.of(PermissionEffect.DENY);
                case "remove" -> Optional.empty();
                default -> throw new IllegalArgumentException("expected allow, deny, or remove");
            };
            PermissionPattern pattern = PermissionPattern.of(args[permissionIndex]);
            AuthorizationScope scope = scope(args, 5);
            if (!mayManage(source, PermissionNodes.STAFF_PERMISSIONS_MODIFY, scope)) { denied(source); return; }
            mutate(source, new AuthorizationMutation.UserPermission(target, pattern, scope, effect),
                    "User permission " + pattern + " " + op + " for " + target + " at " + scope.scopeKey());
            return;
        }
        throw new IllegalArgumentException("usage: /auth user <player|uuid> info | role ... | permission ...");
    }

    private void handleRole(CommandSource source, String[] args) throws SQLException {
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            if (!mayManage(source, PermissionNodes.STAFF_ROLES_MODIFY, AuthorizationScope.global())) { denied(source); return; }
            authorization.currentState().roles().values().stream()
                    .sorted((a, b) -> Integer.compare(b.managementPriority(), a.managementPriority()))
                    .forEach(role -> source.sendMessage(Component.text(role.id() + " priority="
                            + role.managementPriority() + (role.defaultForPlayers() ? " default-for-players" : "")
                            + (role.protectedRole() ? " protected" : ""))));
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("info")) {
            if (!mayManage(source, PermissionNodes.STAFF_ROLES_MODIFY, AuthorizationScope.global())
                    && !mayManage(source, PermissionNodes.STAFF_PERMISSIONS_MODIFY, AuthorizationScope.global())) {
                denied(source); return;
            }
            showRoleInfo(source, RoleId.of(args[2]));
            return;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("create")) {
            if (!mayManage(source, PermissionNodes.STAFF_ROLES_MODIFY, AuthorizationScope.global())) { denied(source); return; }
            RoleId role = RoleId.of(args[2]);
            int priority = args.length > 3 ? Integer.parseInt(args[3]) : 0;
            String displayName = args[2].replace('_', ' ');
            mutate(source, new AuthorizationMutation.CreateRole(role, displayName, priority), "Created role " + role);
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("delete")) {
            if (!mayManage(source, PermissionNodes.STAFF_ROLES_MODIFY, AuthorizationScope.global())) { denied(source); return; }
            RoleId role = RoleId.of(args[2]);
            mutate(source, new AuthorizationMutation.DeleteRole(role), "Deleted role " + role);
            return;
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("priority")) {
            if (!mayManage(source, PermissionNodes.STAFF_ROLES_MODIFY, AuthorizationScope.global())) { denied(source); return; }
            mutate(source, new AuthorizationMutation.RolePriority(RoleId.of(args[2]), Integer.parseInt(args[3])),
                    "Updated management priority for " + RoleId.of(args[2]));
            return;
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("default")) {
            if (!mayManage(source, PermissionNodes.STAFF_ROLES_MODIFY, AuthorizationScope.global())) { denied(source); return; }
            boolean enabled = switch (args[3].toLowerCase(Locale.ROOT)) {
                case "on", "true", "enable" -> true;
                case "off", "false", "disable" -> false;
                default -> throw new IllegalArgumentException("expected on or off");
            };
            mutate(source, new AuthorizationMutation.DefaultRole(RoleId.of(args[2]), enabled),
                    "Set default-for-players=" + enabled + " for " + RoleId.of(args[2]));
            return;
        }
        if (args.length >= 5 && args[1].equalsIgnoreCase("permission")) {
            String op = args[2].toLowerCase(Locale.ROOT);
            Optional<PermissionEffect> effect = switch (op) {
                case "allow" -> Optional.of(PermissionEffect.ALLOW);
                case "deny" -> Optional.of(PermissionEffect.DENY);
                case "remove" -> Optional.empty();
                default -> throw new IllegalArgumentException("expected allow, deny, or remove");
            };
            RoleId role = RoleId.of(args[3]);
            PermissionPattern pattern = PermissionPattern.of(args[4]);
            AuthorizationScope scope = scope(args, 5);
            if (!mayManage(source, PermissionNodes.STAFF_PERMISSIONS_MODIFY, scope)) { denied(source); return; }
            mutate(source, new AuthorizationMutation.RolePermission(role, pattern, scope, effect),
                    "Role permission " + pattern + " " + op + " for " + role + " at " + scope.scopeKey());
            return;
        }
        throw new IllegalArgumentException("usage: /auth role list|info|create|delete|priority|default|permission");
    }

    private void handleExplain(CommandSource source, String[] args, UUID target) {
        if (args.length < 3 || args.length > 4) throw new IllegalArgumentException("usage: /auth explain <player|uuid> <permission> [server]");
        AuthorizationScope scope = scope(args, 3);
        if (!mayManage(source, PermissionNodes.STAFF_PERMISSIONS_MODIFY, scope)) { denied(source); return; }
        PermissionNode permission = PermissionNode.of(args[2]);
        AuthorizationState state = authorization.currentState();
        var decision = scope.type() == AuthorizationScope.Type.GLOBAL
                ? state.decideGlobal(target, permission)
                : state.decide(AuthorizationSubject.player(target), permission, scope.serverId());
        source.sendMessage(Component.text(target + " " + permission + " on "
                + (scope.type() == AuthorizationScope.Type.GLOBAL ? "GLOBAL" : scope.serverId())
                + " => " + decision.outcome() + " (revision " + decision.authorizationRevision() + ")"));
        decision.matchedRule().ifPresent(rule -> source.sendMessage(Component.text("Winner: " + rule.effect() + " "
                + rule.pattern() + " from " + rule.origin()
                + rule.sourceRole().map(role -> " role=" + role).orElse("")
                + " scope=" + rule.scope().scopeKey())));
        decision.consideredRules().stream().filter(trace -> !trace.winner()).limit(12).forEach(trace ->
                source.sendMessage(Component.text("Lost: " + trace.rule().effect() + " " + trace.rule().pattern()
                        + " from " + trace.rule().origin() + trace.rule().sourceRole().map(role -> " role=" + role).orElse("")
                        + " scope=" + trace.rule().scope().scopeKey() + " because " + trace.reason())));
    }

    private void handleCommands(CommandSource source, String[] args, UUID target) {
        if (args.length < 2) throw new IllegalArgumentException("usage: /auth commands unmapped|mapped|check|user ...");
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "mapped" -> listCommandPolicies(source, args, true);
            case "unmapped" -> listCommandPolicies(source, args, false);
            case "check" -> showCommandCheck(source, args);
            case "user" -> showUserCommands(source, args, target);
            default -> throw new IllegalArgumentException("expected unmapped, mapped, check, or user");
        }
    }

    private void listCommandPolicies(CommandSource source, String[] args, boolean mapped) {
        AuthorizationState state = authorization.currentState();
        ParsedCommandList query = parseListQuery(args, 2, state);
        List<CommandReportLine> lines = new ArrayList<>();
        List<ServerId> servers = query.server() == null
                ? state.enabledServers().stream().sorted().toList() : List.of(query.server());
        for (ServerId server : servers) {
            CommandCatalog catalog = plugin.commandCatalogStore().catalog(server).orElse(null);
            if (catalog == null) continue;
            for (CommandCatalogEntry runtimeEntry : catalog.entries()) {
                Optional<com.silver.authorization.CommandPolicyEntry> policy = COMMAND_POLICIES.resolve(
                        CommandOrigin.FABRIC_BACKEND, runtimeEntry.path());
                if (mapped != policy.isPresent()) continue;
                String permission = policy.flatMap(com.silver.authorization.CommandPolicyEntry::permission)
                        .map(PermissionPattern::value).orElse("");
                String sourceName = policy.flatMap(com.silver.authorization.CommandPolicyEntry::source)
                        .or(() -> runtimeEntry.source()).orElse("unknown");
                String modId = policy.flatMap(com.silver.authorization.CommandPolicyEntry::modId)
                        .or(() -> runtimeEntry.modId()).orElse("unknown");
                String description = server + " " + runtimeEntry.path() + " — "
                        + (mapped ? policy.orElseThrow().classification() + " " + permission : "UNMAPPED")
                        + " — " + sourceName + " (" + modId + ")"
                        + (runtimeEntry.executable() ? "" : " [group]");
                if (matches(description, query.search())) lines.add(new CommandReportLine(server, runtimeEntry.path(), description));
            }
        }
        if (query.server() != null && plugin.commandCatalogStore().catalog(query.server()).isEmpty()) {
            source.sendMessage(Component.text("No live command inventory has arrived from " + query.server()
                    + " yet; check the backend authorization link and catalog synchronization log."));
            return;
        }
        if (lines.isEmpty()) {
            source.sendMessage(Component.text("No " + (mapped ? "mapped" : "unmapped")
                    + " runtime command paths matched that search."));
            return;
        }
        lines.sort(Comparator.comparing((CommandReportLine line) -> line.server().value())
                .thenComparing(line -> line.path().canonical()));
        sendPage(source, "/auth commands " + (mapped ? "mapped" : "unmapped")
                + query.suffix(), lines.stream().map(CommandReportLine::text).toList(), query.page());
    }

    private void showCommandCheck(CommandSource source, String[] args) {
        if (args.length < 3) throw new IllegalArgumentException("usage: /auth commands check <command-path> [server]");
        AuthorizationState current = authorization.currentState();
        int end = args.length;
        ServerId server = null;
        if (end > 3) {
            Optional<ServerId> maybeServer = parseEnabledServer(args[end - 1], current);
            if (maybeServer.isPresent()) { server = maybeServer.orElseThrow(); end--; }
        }
        String rawPath = String.join(" ", java.util.Arrays.copyOfRange(args, 2, end));
        CommandPath path = CommandPath.of(rawPath);
        Optional<com.silver.authorization.CommandPolicyEntry> entry = COMMAND_POLICIES.resolve(
                CommandOrigin.FABRIC_BACKEND, path);
        Optional<CommandCatalogEntry> runtimeEntry = server == null ? Optional.empty()
                : plugin.commandCatalogStore().catalog(server).stream().flatMap(catalog -> catalog.entries().stream())
                .filter(candidate -> candidate.path().equals(path)).findFirst();
        String sourceName = entry.flatMap(com.silver.authorization.CommandPolicyEntry::source)
                .or(() -> runtimeEntry.flatMap(CommandCatalogEntry::source)).orElse("unknown");
        String modId = entry.flatMap(com.silver.authorization.CommandPolicyEntry::modId)
                .or(() -> runtimeEntry.flatMap(CommandCatalogEntry::modId)).orElse("unknown");
        CommandClassification classification = entry.map(com.silver.authorization.CommandPolicyEntry::classification)
                .orElse(CommandClassification.UNMAPPED);
        source.sendMessage(Component.text(path + " — " + classification
                + entry.flatMap(com.silver.authorization.CommandPolicyEntry::permission)
                .map(permission -> " — requires " + permission).orElse("")
                + " — " + sourceName + " (" + modId + ") — scope="
                + (server == null ? "GLOBAL" : server.value())));
        for (String role : List.of("PLAYER", "MODERATOR", "ADMIN", "OWNER")) {
            source.sendMessage(Component.text(role + ": "
                    + (rolePolicyAllowed(classification, entry, role, server, current) ? "allowed" : "denied")));
        }
    }

    private boolean rolePolicyAllowed(CommandClassification classification,
                                      Optional<com.silver.authorization.CommandPolicyEntry> entry,
                                      String roleKey, ServerId server, AuthorizationState current) {
        if (classification == CommandClassification.PUBLIC) return true;
        if (classification == CommandClassification.INTERNAL) return false;
        if (classification == CommandClassification.UNMAPPED) return roleKey.equals("OWNER");
        if (roleKey.equals("OWNER")) return true;
        PermissionPattern required = entry.orElseThrow().permission().orElseThrow();
        RoleId role = RoleId.of(roleKey);
        if (!current.roles().containsKey(role)) return false;
        UUID subject = UUID.nameUUIDFromBytes(("command-policy-check:" + roleKey).getBytes(StandardCharsets.UTF_8));
        Map<UUID, List<RoleAssignment>> assignments = Map.of(subject,
                List.of(new RoleAssignment(role, AuthorizationScope.global())));
        List<RolePermission> rolePermissions = current.roles().keySet().stream()
                .flatMap(existing -> current.rolePermissions(existing).stream()).toList();
        AuthorizationState simulated = AuthorizationState.build(current.revision(), current.enabledServers(),
                current.roles(), assignments, rolePermissions, Map.of());
        PermissionNode gate = required.policyGateNode();
        return server == null ? simulated.hasGlobal(subject, gate)
                : simulated.has(AuthorizationSubject.player(subject), gate, server);
    }

    private void showUserCommands(CommandSource source, String[] args, UUID target) {
        if (target == null) throw new IllegalArgumentException(
                "usage: /auth commands user <player|uuid> [server] [detail [page <n>]]");
        AuthorizationState state = authorization.currentState();
        int cursor = 3;
        ServerId selected = null;
        boolean detail = false;
        int page = 1;
        if (cursor < args.length && args[cursor].equalsIgnoreCase("detail")) {
            detail = true;
            cursor++;
        } else if (cursor < args.length && !args[cursor].equalsIgnoreCase("page")) {
            String serverInput = args[cursor];
            selected = parseEnabledServer(serverInput, state)
                    .orElseThrow(() -> new IllegalArgumentException("unknown or disabled server: " + serverInput));
            cursor++;
        }
        if (cursor < args.length) {
            if (args[cursor].equalsIgnoreCase("detail")) {
                detail = true;
                cursor++;
            }
            if (cursor < args.length) {
                if (!args[cursor].equalsIgnoreCase("page") || cursor + 2 != args.length) {
                    throw new IllegalArgumentException(
                            "usage: /auth commands user <player|uuid> [server] [detail [page <n>]]");
                }
                // The old [server] [page n] spelling remains a detail-mode alias.
                detail = true;
                page = Integer.parseInt(args[cursor + 1]);
                cursor += 2;
            }
        }
        if (page < 1) throw new IllegalArgumentException("page must be 1 or greater");
        List<ServerId> servers = selected == null
                ? state.enabledServers().stream().sorted().toList() : List.of(selected);
        boolean owner = state.hasGlobalOwnerAssignment(target);
        AuthorizationSubject subject = AuthorizationSubject.player(target);
        List<String> output = new ArrayList<>();
        int evaluated = 0;
        for (ServerId server : servers) {
            CommandCatalog catalog = plugin.commandCatalogStore().catalog(server).orElse(null);
            if (catalog == null) {
                output.add(server + ": runtime command inventory unavailable");
                continue;
            }
            evaluated += catalog.entries().size();
            Map<String, CommandFamilySummary> families = new java.util.TreeMap<>();
            for (CommandCatalogEntry runtimeEntry : catalog.entries()) {
                CommandPolicyDecision decision = COMMAND_POLICIES.decide(CommandOrigin.FABRIC_BACKEND,
                        runtimeEntry.path(), true, owner,
                        false, false, permission -> state.has(subject, permission.policyGateNode(), server));
                if (detail) {
                    output.add(server + " " + runtimeEntry.path() + " — " + decision.classification()
                            + " — " + (decision.visible() ? "visible" : "hidden")
                            + ", " + (decision.allowed() ? "allowed" : "denied")
                            + decision.requiredPermission().map(permission -> " — " + permission).orElse("")
                            + (decision.classification() == CommandClassification.UNMAPPED ? " — no mapping" : ""));
                    continue;
                }
                String root = runtimeEntry.path().root();
                CommandFamilySummary family = families.computeIfAbsent(root, ignored -> new CommandFamilySummary());
                family.add(runtimeEntry, decision);
            }
            if (!detail) appendFamilySummary(output, state, target, server, families);
        }
        if (!detail) {
            List<String> defaults = state.roles().values().stream()
                    .filter(AuthorizationState.RoleMetadata::defaultForPlayers)
                    .map(role -> role.id().value()).sorted().toList();
            source.sendMessage(Component.text("Authorization summary for " + target
                    + state.username(target).map(name -> " (" + name + ")").orElse("")
                    + " — evaluated " + evaluated + " runtime paths"));
            source.sendMessage(Component.text("Effective default roles: "
                    + (defaults.isEmpty() ? "none" : String.join(", ", defaults))));
            List<RoleAssignment> assignments = state.assignments(target);
            source.sendMessage(Component.text("Explicit role assignments: " + (assignments.isEmpty() ? "none"
                    : assignments.stream().map(a -> a.roleId() + "@" + a.scope().scopeKey())
                    .collect(java.util.stream.Collectors.joining(", ")))));
            List<com.silver.authorization.DirectPermissionRule> direct = state.directPermissions(target);
            source.sendMessage(Component.text("Direct ALLOW/DENY rules: " + (direct.isEmpty() ? "none"
                    : direct.stream().map(rule -> rule.effect() + " " + rule.pattern() + "@" + rule.scope().scopeKey()
                            + " (direct override)").collect(java.util.stream.Collectors.joining(", ")))));
            source.sendMessage(Component.text("Command families are summarized per relevant server below; use `detail` for every runtime path."));
        }
        if (detail) {
            source.sendMessage(Component.text("Detailed command policy for " + target
                    + state.username(target).map(name -> " (" + name + ")").orElse("")));
            sendPage(source, "/auth commands user " + target
                    + (selected == null ? "" : " " + selected.value()) + " detail", output, page);
        } else {
            output.forEach(line -> source.sendMessage(Component.text(line)));
        }
    }

    private static void appendFamilySummary(List<String> output, AuthorizationState state, UUID target,
                                            ServerId server, Map<String, CommandFamilySummary> families) {
        output.add(server + " — effective roles: " + effectiveRoles(state, target, server));
        output.add(server + " — accessible command families:");
        List<String> accessible = new ArrayList<>();
        Map<String, java.util.SortedSet<String>> denied = new java.util.TreeMap<>();
        Map<String, Integer> deniedCounts = new java.util.TreeMap<>();
        for (var entry : families.entrySet()) {
            String root = "/" + entry.getKey();
            CommandFamilySummary family = entry.getValue();
            if (family.allowed > 0) {
                accessible.add(root + (family.allowed < family.total ? " (partial: " + family.allowed + "/" + family.total + ")" : ""));
            }
            if (family.denied > 0) {
                String key = family.denialKey();
                denied.computeIfAbsent(key, ignored -> new java.util.TreeSet<>()).add(root);
                deniedCounts.merge(key, family.denied, Integer::sum);
            }
        }
        output.add("  " + (accessible.isEmpty() ? "none" : String.join(", ", accessible)));
        output.add(server + " — denied administrative/internal/unmapped families:");
        if (denied.isEmpty()) output.add("  none");
        denied.forEach((permission, roots) -> {
            List<String> rootList = roots.stream().limit(18).toList();
            String suffix = roots.size() > rootList.size() ? ", … +" + (roots.size() - rootList.size()) + " roots" : "";
            output.add("  " + permission + " (" + deniedCounts.get(permission) + " paths): "
                    + String.join(", ", rootList) + suffix);
        });
        List<com.silver.authorization.DirectPermissionRule> scoped = state.directPermissions(target).stream()
                .filter(rule -> rule.scope().appliesTo(server)).toList();
        if (!scoped.isEmpty()) output.add(server + " — direct overrides: " + scoped.stream()
                .map(rule -> rule.effect() + " " + rule.pattern() + "@" + rule.scope().scopeKey())
                .collect(java.util.stream.Collectors.joining(", ")));
    }

    private static String effectiveRoles(AuthorizationState state, UUID target, ServerId server) {
        java.util.TreeSet<String> roles = new java.util.TreeSet<>();
        state.roles().values().stream().filter(AuthorizationState.RoleMetadata::defaultForPlayers)
                .map(role -> role.id().value()).forEach(roles::add);
        state.assignments(target).stream().filter(assignment -> assignment.scope().appliesTo(server))
                .map(assignment -> assignment.roleId().value()).forEach(roles::add);
        return roles.isEmpty() ? "none" : String.join(", ", roles);
    }

    private static final class CommandFamilySummary {
        private int total;
        private int allowed;
        private int denied;
        private final Set<String> denials = new java.util.TreeSet<>();

        private void add(CommandCatalogEntry entry, CommandPolicyDecision decision) {
            total++;
            if (decision.allowed()) allowed++;
            else {
                denied++;
                String key = decision.classification() == CommandClassification.UNMAPPED ? "UNMAPPED"
                        : decision.requiredPermission().map(PermissionPattern::value)
                                .orElse(decision.classification().name());
                denials.add(key);
            }
        }

        private String denialKey() {
            return denials.isEmpty() ? "DENIED" : denials.size() == 1 ? denials.iterator().next() : "MIXED POLICY";
        }
    }

    private ParsedCommandList parseListQuery(String[] args, int start, AuthorizationState state) {
        int end = args.length;
        int page = 1;
        if (end >= start + 2 && args[end - 2].equalsIgnoreCase("page")) {
            page = Integer.parseInt(args[end - 1]);
            if (page < 1) throw new IllegalArgumentException("page must be 1 or greater");
            end -= 2;
        }
        ServerId server = null;
        if (start < end) {
            Optional<ServerId> maybeServer = parseEnabledServer(args[start], state);
            if (maybeServer.isPresent()) { server = maybeServer.orElseThrow(); start++; }
        }
        String search = start >= end ? "" : String.join(" ", java.util.Arrays.copyOfRange(args, start, end));
        String suffix = (server == null ? "" : " " + server.value()) + (search.isEmpty() ? "" : " " + search);
        return new ParsedCommandList(server, search.toLowerCase(Locale.ROOT), page, suffix);
    }

    private static Optional<ServerId> parseEnabledServer(String text, AuthorizationState state) {
        try {
            ServerId server = ServerId.of(text);
            return state.enabledServers().contains(server) ? Optional.of(server) : Optional.empty();
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    private static boolean matches(String text, String search) {
        return search.isEmpty() || text.toLowerCase(Locale.ROOT).contains(search);
    }

    private static void sendPage(CommandSource source, String command, List<String> lines, int page) {
        int pages = Math.max(1, (lines.size() + REPORT_PAGE_SIZE - 1) / REPORT_PAGE_SIZE);
        if (page > pages) throw new IllegalArgumentException("page exceeds the result count (" + pages + ")");
        source.sendMessage(Component.text("Page " + page + "/" + pages + " — " + lines.size() + " command paths"));
        int from = (page - 1) * REPORT_PAGE_SIZE;
        for (int i = from; i < Math.min(from + REPORT_PAGE_SIZE, lines.size()); i++) {
            source.sendMessage(Component.text(lines.get(i)));
        }
        if (page < pages) source.sendMessage(Component.text("Next page: " + command + " page " + (page + 1)));
    }

    private record ParsedCommandList(ServerId server, String search, int page, String suffix) { }
    private record CommandReportLine(ServerId server, CommandPath path, String text) { }

    private void showUserInfo(CommandSource source, UUID uuid) {
        AuthorizationState state = authorization.currentState();
        source.sendMessage(Component.text("User " + uuid + state.username(uuid).map(name -> " (" + name + ")").orElse("")
                + " — revision " + state.revision()));
        String defaults = state.roles().values().stream()
                .filter(AuthorizationState.RoleMetadata::defaultForPlayers)
                .map(role -> role.id().value()).sorted().collect(java.util.stream.Collectors.joining(", "));
        source.sendMessage(Component.text("Default roles for player subjects: "
                + (defaults.isEmpty() ? "none" : defaults)));
        List<RoleAssignment> assignments = state.assignments(uuid);
        if (assignments.isEmpty()) source.sendMessage(Component.text("No explicit role assignments."));
        assignments.forEach(role -> source.sendMessage(Component.text("Role: " + role.roleId()
                + " @ " + role.scope().scopeKey())));
        state.directPermissions(uuid).forEach(rule -> source.sendMessage(Component.text("Direct: "
                + rule.effect() + " " + rule.pattern() + " @ " + rule.scope().scopeKey())));
    }

    private void showRoleInfo(CommandSource source, RoleId id) {
        AuthorizationState state = authorization.currentState();
        AuthorizationState.RoleMetadata role = state.roles().get(id);
        if (role == null) { source.sendMessage(Component.text("Unknown role: " + id)); return; }
        source.sendMessage(Component.text("Role " + id + " — priority=" + role.managementPriority()
                + (role.defaultForPlayers() ? ", default-for-players" : "")
                + (role.protectedRole() ? ", protected" : "")));
        List<RolePermission> permissions = state.rolePermissions(id);
        if (permissions.isEmpty()) source.sendMessage(Component.text("No role permissions."));
        permissions.forEach(rule -> source.sendMessage(Component.text(rule.effect() + " " + rule.pattern()
                + " @ " + rule.scope().scopeKey())));
    }

    private void mutate(CommandSource source, AuthorizationMutation mutation, String success) throws SQLException {
        long revision;
        if (source instanceof Player player) {
            revision = authorization.applyMutation(player.getUniqueId(), mutation);
        } else if (source instanceof ConsoleCommandSource) {
            revision = authorization.applyConsoleMutation("velocity-console", mutation);
        } else {
            denied(source);
            return;
        }
        source.sendMessage(Component.text(success + " (authorization revision " + revision + ")"));
    }

    private boolean mayManage(CommandSource source, PermissionNode node, AuthorizationScope scope) {
        if (source instanceof ConsoleCommandSource) return true;
        if (!(source instanceof Player player)) return false;
        AuthorizationState state = authorization.currentState();
        return scope.type() == AuthorizationScope.Type.GLOBAL
                ? state.hasGlobal(player.getUniqueId(), node)
                : state.has(AuthorizationSubject.player(player.getUniqueId()), node, scope.serverId());
    }

    private AuthorizationScope scope(String[] args, int serverIndex) {
        if (args.length == serverIndex) return AuthorizationScope.global();
        if (args.length != serverIndex + 1) throw new IllegalArgumentException("unexpected extra arguments");
        if (args[serverIndex].equalsIgnoreCase("global")) return AuthorizationScope.global();
        ServerId server = ServerId.of(args[serverIndex]);
        if (!authorization.currentState().enabledServers().contains(server)) {
            throw new IllegalArgumentException("unknown or disabled server scope: " + server);
        }
        return AuthorizationScope.server(server);
    }

    private static boolean operation(String value, String first, String second) {
        if (value.equalsIgnoreCase(first)) return true;
        if (value.equalsIgnoreCase(second)) return false;
        throw new IllegalArgumentException("expected " + first + " or " + second);
    }

    private static void denied(CommandSource source) {
        source.sendMessage(Component.text("You do not have the required centralized authorization capability."));
    }

    private static void usage(CommandSource source) {
        source.sendMessage(Component.text("/auth user info <player|uuid>; /auth user role <add|remove> <player|uuid> <role> [server|global]; /auth user permission <allow|deny|remove> <player|uuid> <permission> [server|global]; /auth role list|info|create|delete|priority|default|permission; /auth explain <player|uuid> <permission> [server]; /auth commands unmapped|mapped [server] [search] [page n], check <path> [server], user <player|uuid> [server] [detail [page n]]"));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        if (invocation.source() instanceof ConsoleCommandSource) return true;
        if (!(invocation.source() instanceof Player player)) return false;
        AuthorizationState state = authorization.currentState();
        UUID actor = player.getUniqueId();
        if (state.hasGlobal(actor, PermissionNodes.STAFF_ROLES_MODIFY)
                || state.hasGlobal(actor, PermissionNodes.STAFF_PERMISSIONS_MODIFY)) return true;
        AuthorizationSubject subject = AuthorizationSubject.player(actor);
        return state.enabledServers().stream().anyMatch(server ->
                state.has(subject, PermissionNodes.STAFF_ROLES_MODIFY, server)
                        || state.has(subject, PermissionNodes.STAFF_PERMISSIONS_MODIFY, server));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        AuthorizationState state = authorization.currentState();
        if (args.length == 0) return List.of("user", "role", "explain", "commands");
        List<String> options = new ArrayList<>();
        String a0 = args[0].toLowerCase(Locale.ROOT);
        if (a0.equals("user")) suggestUser(args, state, invocation.source(), options);
        else if (a0.equals("role")) suggestRole(args, state, options);
        else if (a0.equals("explain")) {
            if (args.length == 2) options.addAll(playerSuggestions(state, ignored -> true));
            else if (args.length == 3) options.addAll(permissionSuggestions(state));
            else if (args.length == 4) options.addAll(scopeSuggestions(state, null, null, null));
        } else if (a0.equals("commands")) suggestCommands(args, state, options);

        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().distinct()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }

    private void suggestUser(String[] args, AuthorizationState state, CommandSource source, List<String> out) {
        if (args.length == 2) out.addAll(List.of("info", "role", "permission"));
        else if (args.length == 3 && args[1].equalsIgnoreCase("info")) {
            out.addAll(playerSuggestions(state, ignored -> true));
        } else if (args.length == 3 && args[1].equalsIgnoreCase("role")) {
            out.addAll(List.of("add", "remove"));
        } else if (args.length == 3 && args[1].equalsIgnoreCase("permission")) {
            out.addAll(List.of("allow", "deny", "remove"));
        } else if (args.length == 4 && args[1].equalsIgnoreCase("role")
                && Set.of("add", "remove").contains(args[2].toLowerCase(Locale.ROOT))) {
            boolean remove = args[2].equalsIgnoreCase("remove");
            out.addAll(playerSuggestions(state, uuid -> !remove || removableRoleAssignments(source, state, uuid)));
        } else if (args.length == 4 && args[1].equalsIgnoreCase("permission")
                && Set.of("allow", "deny", "remove").contains(args[2].toLowerCase(Locale.ROOT))) {
            boolean remove = args[2].equalsIgnoreCase("remove");
            out.addAll(playerSuggestions(state, uuid -> !remove || removableDirectPermissions(source, state, uuid)));
        } else if (args.length == 5 && args[1].equalsIgnoreCase("role")) {
            if (args[2].equalsIgnoreCase("remove")) {
                resolveForSuggestions(args[3], state).ifPresent(uuid -> out.addAll(removableRoles(source, state, uuid)));
            } else if (args[2].equalsIgnoreCase("add")) {
                out.addAll(state.roles().keySet().stream().map(RoleId::value).toList());
            }
        } else if (args.length == 5 && args[1].equalsIgnoreCase("permission")) {
            if (args[2].equalsIgnoreCase("remove")) {
                resolveForSuggestions(args[3], state).ifPresent(uuid -> out.addAll(removablePermissionNodes(source, state, uuid)));
            } else out.addAll(permissionSuggestions(state));
        } else if (args.length == 6 && args[1].equalsIgnoreCase("role")) {
            resolveForSuggestions(args[3], state).ifPresent(uuid -> {
                try {
                    RoleId role = RoleId.of(args[4]);
                    out.addAll(scopeSuggestions(state, source, uuid, role));
                } catch (IllegalArgumentException ignored) { }
            });
        } else if (args.length == 6 && args[1].equalsIgnoreCase("permission")) {
            resolveForSuggestions(args[3], state).ifPresent(uuid -> {
                try {
                    PermissionPattern pattern = PermissionPattern.of(args[4]);
                    out.addAll(scopeSuggestions(state, source, uuid, pattern));
                } catch (IllegalArgumentException ignored) { }
            });
        } else if (args.length == 3 && args[1].equalsIgnoreCase("info")) {
            out.addAll(playerSuggestions(state, ignored -> true));
        }
        // Legacy argument ordering remains accepted temporarily.
        else if (args.length == 2) out.addAll(playerSuggestions(state, ignored -> true));
    }

    private void suggestRole(String[] args, AuthorizationState state, List<String> out) {
        if (args.length == 2) out.addAll(List.of("list", "info", "create", "delete", "priority", "default", "permission"));
        else if (args.length == 3 && args[1].equalsIgnoreCase("permission")) out.addAll(List.of("allow", "deny", "remove"));
        else if (args.length == 4 && (args[1].equalsIgnoreCase("info") || args[1].equalsIgnoreCase("delete")
                || args[1].equalsIgnoreCase("priority") || args[1].equalsIgnoreCase("default"))) {
            out.addAll(state.roles().keySet().stream().map(RoleId::value).toList());
        } else if (args.length == 4 && args[1].equalsIgnoreCase("permission")) {
            out.addAll(state.roles().keySet().stream().map(RoleId::value).toList());
        } else if (args.length == 5 && args[1].equalsIgnoreCase("permission")) {
            out.addAll(permissionSuggestions(state));
        } else if (args.length == 6 && args[1].equalsIgnoreCase("permission")) {
            out.addAll(scopeSuggestions(state, null, null, null));
        }
    }

    private void suggestCommands(String[] args, AuthorizationState state, List<String> out) {
        if (args.length == 2) out.addAll(List.of("unmapped", "mapped", "check", "user"));
        else if (args[1].equalsIgnoreCase("user")) {
            if (args.length == 3) out.addAll(playerSuggestions(state, ignored -> true));
            else if (args.length == 4) {
                out.addAll(scopeSuggestions(state, null, null, null));
                out.add("detail");
            } else if (args.length == 5 && args[4].equalsIgnoreCase("detail")) out.add("page");
            else if (args.length == 5) out.add("detail");
        } else if (Set.of("mapped", "unmapped").contains(args[1].toLowerCase(Locale.ROOT))) {
            if (args.length == 3) out.addAll(state.enabledServers().stream().map(ServerId::value).toList());
            else if (args.length >= 4 && args[args.length - 1].equalsIgnoreCase("page")) { /* page number is free-form */ }
            else if (args.length == 4) out.add("page");
        } else if (args[1].equalsIgnoreCase("check") && args.length >= 4) {
            out.addAll(state.enabledServers().stream().map(ServerId::value).toList());
        }
    }

    private List<String> permissionSuggestions(AuthorizationState state) {
        java.util.TreeSet<String> suggestions = new java.util.TreeSet<>();
        PermissionNodes.all().stream().map(PermissionNode::value).forEach(suggestions::add);
        state.knownPermissionPatterns().stream().map(PermissionPattern::value).forEach(suggestions::add);
        COMMAND_POLICIES.entries().stream().flatMap(entry -> entry.permission().stream())
                .map(PermissionPattern::value).forEach(suggestions::add);
        return List.copyOf(suggestions);
    }

    private List<String> playerSuggestions(AuthorizationState state,
                                           java.util.function.Predicate<UUID> include) {
        Map<UUID, String> names = new HashMap<>(state.usernames());
        proxy.getAllPlayers().forEach(player -> names.put(player.getUniqueId(), player.getUsername()));
        Map<String, Set<UUID>> byName = new HashMap<>();
        names.forEach((uuid, name) -> byName.computeIfAbsent(name.toLowerCase(Locale.ROOT), ignored -> new java.util.HashSet<>())
                .add(uuid));
        List<String> result = new ArrayList<>();
        byName.forEach((normalized, identities) -> {
            if (identities.size() == 1) {
                UUID uuid = identities.iterator().next();
                if (include.test(uuid)) result.add(names.get(uuid));
                return;
            }
            List<UUID> included = identities.stream().filter(include).sorted().toList();
            // Even if only one duplicate identity is currently manageable, the bare name is
            // ambiguous to the resolver. Suggest its UUID instead of an unusable name.
            included.forEach(uuid -> result.add(uuid.toString()));
        });
        // Subjects with role/rule history but no stored display name remain addressable by UUID.
        state.knownSubjects().stream().filter(include).filter(uuid -> !names.containsKey(uuid))
                .map(UUID::toString).forEach(result::add);
        proxy.getAllPlayers().stream().map(Player::getUniqueId).filter(include)
                .filter(uuid -> !names.containsKey(uuid)).map(UUID::toString).forEach(result::add);
        return result.stream().distinct().sorted().toList();
    }

    private Optional<UUID> resolveForSuggestions(String name, AuthorizationState state) {
        try { return Optional.of(UUID.fromString(name)); } catch (IllegalArgumentException ignored) { }
        Set<UUID> matches = new java.util.HashSet<>();
        proxy.getAllPlayers().stream().filter(player -> player.getUsername().equalsIgnoreCase(name))
                .map(Player::getUniqueId).forEach(matches::add);
        state.usernames().entrySet().stream().filter(entry -> entry.getValue().equalsIgnoreCase(name))
                .map(Map.Entry::getKey).forEach(matches::add);
        return matches.size() == 1 ? Optional.of(matches.iterator().next()) : Optional.empty();
    }

    private boolean removableDirectPermissions(CommandSource source, AuthorizationState state, UUID target) {
        return state.directPermissions(target).stream().anyMatch(rule -> canRemovePermission(source, state, target, rule.scope()));
    }

    private List<String> removablePermissionNodes(CommandSource source, AuthorizationState state, UUID target) {
        return state.directPermissions(target).stream()
                .filter(rule -> canRemovePermission(source, state, target, rule.scope()))
                .map(rule -> rule.pattern().value()).distinct().sorted().toList();
    }

    private boolean canRemovePermission(CommandSource source, AuthorizationState state, UUID target, AuthorizationScope scope) {
        if (source instanceof ConsoleCommandSource) return true;
        if (!(source instanceof Player actor)) return false;
        UUID actorId = actor.getUniqueId();
        if (!hasManagementCapability(state, actorId, PermissionNodes.STAFF_PERMISSIONS_MODIFY, scope)) return false;
        return ownsScope(state, actorId, scope) || actorId.equals(target)
                || managementPriority(state, actorId, scope) > managementPriority(state, target, scope);
    }

    private boolean removableRoleAssignments(CommandSource source, AuthorizationState state, UUID target) {
        return state.assignments(target).stream().anyMatch(assignment -> canRemoveRole(source, state, target,
                assignment.roleId(), assignment.scope()));
    }

    private List<String> removableRoles(CommandSource source, AuthorizationState state, UUID target) {
        return state.assignments(target).stream()
                .filter(assignment -> canRemoveRole(source, state, target, assignment.roleId(), assignment.scope()))
                .map(assignment -> assignment.roleId().value()).distinct().sorted().toList();
    }

    private boolean canRemoveRole(CommandSource source, AuthorizationState state, UUID target,
                                  RoleId roleId, AuthorizationScope scope) {
        if (source instanceof ConsoleCommandSource) return true;
        if (!(source instanceof Player actor)) return false;
        UUID actorId = actor.getUniqueId();
        if (!hasManagementCapability(state, actorId, PermissionNodes.STAFF_ROLES_MODIFY, scope)) return false;
        if (roleId.value().equals("OWNER") && ownerAssignmentCount(state) <= 1) return false;
        return ownsScope(state, actorId, scope) || actorId.equals(target)
                || managementPriority(state, actorId, scope) > Math.max(managementPriority(state, target, scope),
                state.roles().getOrDefault(roleId, new AuthorizationState.RoleMetadata(roleId, 1, Integer.MAX_VALUE, false, false))
                        .managementPriority());
    }

    private static long ownerAssignmentCount(AuthorizationState state) {
        return state.knownSubjects().stream().flatMap(uuid -> state.assignments(uuid).stream())
                .filter(assignment -> assignment.roleId().value().equals("OWNER")).count();
    }

    private static boolean hasManagementCapability(AuthorizationState state, UUID actor,
                                                   PermissionNode capability, AuthorizationScope scope) {
        return scope.type() == AuthorizationScope.Type.GLOBAL ? state.hasGlobal(actor, capability)
                : state.has(AuthorizationSubject.player(actor), capability, scope.serverId());
    }

    private static boolean ownsScope(AuthorizationState state, UUID actor, AuthorizationScope scope) {
        return state.assignments(actor).stream().anyMatch(assignment -> assignment.roleId().value().equals("OWNER")
                && (scope.type() == AuthorizationScope.Type.GLOBAL
                    ? assignment.scope().type() == AuthorizationScope.Type.GLOBAL
                    : assignment.scope().appliesTo(scope.serverId())));
    }

    private static int managementPriority(AuthorizationState state, UUID subject, AuthorizationScope scope) {
        return state.assignments(subject).stream().filter(assignment -> scope.type() == AuthorizationScope.Type.GLOBAL
                        ? assignment.scope().type() == AuthorizationScope.Type.GLOBAL
                        : assignment.scope().appliesTo(scope.serverId()))
                .map(assignment -> state.roles().get(assignment.roleId())).filter(java.util.Objects::nonNull)
                .mapToInt(AuthorizationState.RoleMetadata::managementPriority).max().orElseGet(() ->
                        state.roles().values().stream().filter(AuthorizationState.RoleMetadata::defaultForPlayers)
                                .mapToInt(AuthorizationState.RoleMetadata::managementPriority).max().orElse(-1));
    }

    private List<String> scopeSuggestions(AuthorizationState state, CommandSource source, UUID target, Object rule) {
        java.util.stream.Stream<String> servers = state.enabledServers().stream().map(ServerId::value);
        if (target != null && source != null && rule != null) {
            Set<String> removableScopes = new java.util.HashSet<>();
            if (rule instanceof PermissionPattern pattern) {
                state.directPermissions(target).stream().filter(direct -> direct.pattern().equals(pattern))
                        .filter(direct -> canRemovePermission(source, state, target, direct.scope()))
                        .map(direct -> direct.scope().scopeKey()).forEach(removableScopes::add);
            } else if (rule instanceof RoleId role) {
                state.assignments(target).stream().filter(assignment -> assignment.roleId().equals(role))
                        .filter(assignment -> canRemoveRole(source, state, target, role, assignment.scope()))
                        .map(assignment -> assignment.scope().scopeKey()).forEach(removableScopes::add);
            }
            servers = servers.filter(server -> removableScopes.contains("SERVER:" + server));
            if (removableScopes.contains("GLOBAL")) return java.util.stream.Stream.concat(servers, java.util.stream.Stream.of("global"))
                    .sorted().toList();
            return servers.sorted().toList();
        }
        return java.util.stream.Stream.concat(servers, java.util.stream.Stream.of("global")).sorted().toList();
    }
}

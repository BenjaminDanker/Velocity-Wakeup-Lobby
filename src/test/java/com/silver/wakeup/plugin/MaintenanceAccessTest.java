package com.silver.wakeup.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

/** Who gets past maintenance: admins (for the target server, or any server at login) and test bots, never plain players. */
class MaintenanceAccessTest {
    private static final String GAME_HOST = "192.168.1.206";
    private static final String OTHER_HOST = "192.168.1.77";

    private DataSource dataSource;
    private Statement statement;
    private ProxyServer proxy;
    private final List<RegisteredServer> servers = new ArrayList<>();
    private BiPredicate<Player, String> admin;
    private Maintenance maintenance;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        statement = mock(Statement.class);
        proxy = mock(ProxyServer.class);
        admin = mock(BiPredicate.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(proxy.getAllPlayers()).thenReturn(List.of());
        when(proxy.getAllServers()).thenAnswer(i -> List.copyOf(servers));
        rows();
        maintenance = new Maintenance(dataSource, proxy, new Object(), mock(Logger.class), admin);
    }

    private static InetSocketAddress at(String literal, int port) throws Exception {
        return new InetSocketAddress(InetAddress.getByName(literal), port);
    }

    private RegisteredServer register(String name, InetSocketAddress address) {
        RegisteredServer server = mock(RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(new ServerInfo(name, address));
        servers.add(server);
        return server;
    }

    private static Player player(String name, InetSocketAddress from) {
        Player player = mock(Player.class);
        when(player.getUsername()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.getRemoteAddress()).thenReturn(from);
        return player;
    }

    /** What the switches table holds: (server, reason) rows. */
    private void rows(String... serverReasonPairs) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        AtomicInteger cursor = new AtomicInteger(-1);
        when(rs.next()).thenAnswer(i -> cursor.incrementAndGet() < serverReasonPairs.length / 2);
        when(rs.getString(1)).thenAnswer(i -> serverReasonPairs[cursor.get() * 2]);
        when(rs.getString(2)).thenAnswer(i -> serverReasonPairs[cursor.get() * 2 + 1]);
        when(statement.executeQuery(anyString())).thenReturn(rs);
    }

    // ------------------------------------------------------------------------------------------ isBot

    @Test
    void aConnectionFromAGameServersMachineIsABot() throws Exception {
        register("magic", at(GAME_HOST, 25567));
        assertTrue(maintenance.isBot(player("Bot1", at(GAME_HOST, 41234))));
    }

    @Test
    void thePortsDoNotMatterOnlyTheMachine() throws Exception {
        register("magic", at(GAME_HOST, 25567));
        register("tech", at(GAME_HOST, 25568));
        assertTrue(maintenance.isBot(player("Bot1", at(GAME_HOST, 1))));
        assertTrue(maintenance.isBot(player("Bot2", at(GAME_HOST, 65535))));
    }

    @Test
    void anyRegisteredServerCounts() throws Exception {
        register("lobby", at("10.0.0.1", 25565));
        register("magic", at("10.0.0.2", 25567));
        register("tech", at(GAME_HOST, 25568));
        assertTrue(maintenance.isBot(player("Bot1", at(GAME_HOST, 4000))));
        assertTrue(maintenance.isBot(player("Bot2", at("10.0.0.1", 4000))));
    }

    @Test
    void aConnectionFromAnotherMachineIsNotABot() throws Exception {
        register("magic", at(GAME_HOST, 25567));
        assertFalse(maintenance.isBot(player("Alice", at(OTHER_HOST, 41234))));
        assertFalse(maintenance.isBot(player("Alice", at("8.8.8.8", 41234))));
    }

    @Test
    void loopbackIsNeverABotEvenWhenAServerIsRegisteredOnLoopback() throws Exception {
        register("lobby", at("127.0.0.1", 25566));
        register("local", at("::1", 25570));
        assertFalse(maintenance.isBot(player("Tunnel", at("127.0.0.1", 41234))));
        assertFalse(maintenance.isBot(player("Tunnel6", at("::1", 41234))));
        assertFalse(maintenance.isBot(player("Tunnel2", at("127.0.0.2", 41234))));
    }

    @Test
    void aLoopbackServerDoesNotMakeALanPlayerABot() throws Exception {
        register("lobby", at("127.0.0.1", 25566));
        assertFalse(maintenance.isBot(player("Alice", at(OTHER_HOST, 41234))));
    }

    @Test
    void noRegisteredServersMeansNoBots() throws Exception {
        assertFalse(maintenance.isBot(player("Alice", at(GAME_HOST, 41234))));
    }

    @Test
    void aMissingOrUnresolvedRemoteAddressIsNotABot() throws Exception {
        register("magic", at(GAME_HOST, 25567));
        assertFalse(maintenance.isBot(player("Nobody", null)));
        assertFalse(maintenance.isBot(player("Unresolved", InetSocketAddress.createUnresolved("some.host.invalid", 25565))));
    }

    @Test
    void aServerRegisteredWithAnUnresolvedAddressIsSkipped() throws Exception {
        register("broken", InetSocketAddress.createUnresolved("gone.invalid", 25567));
        register("magic", at(GAME_HOST, 25568));
        assertFalse(maintenance.isBot(player("Alice", at(OTHER_HOST, 1))));
        assertTrue(maintenance.isBot(player("Bot", at(GAME_HOST, 1))));
    }

    @Test
    void ipv6HostsMatchToo() throws Exception {
        register("magic", at("2001:db8::5", 25567));
        assertTrue(maintenance.isBot(player("Bot", at("2001:db8::5", 3000))));
        assertFalse(maintenance.isBot(player("Alice", at("2001:db8::6", 3000))));
    }

    // ------------------------------------------------------------------------------------------ allowed

    @Test
    void aBotIsAllowedWithoutAskingTheAdminCheck() throws Exception {
        register("magic", at(GAME_HOST, 25567));
        assertTrue(maintenance.allowed(player("Bot", at(GAME_HOST, 1)), "magic"));
        assertTrue(maintenance.allowed(player("Bot", at(GAME_HOST, 1)), null));
        verify(admin, never()).test(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void anAdminIsAllowedForTheServerTheyAreChecked() throws Exception {
        Player alice = player("Alice", at(OTHER_HOST, 1));
        when(admin.test(alice, "magic")).thenReturn(true);
        assertTrue(maintenance.allowed(alice, "magic"));
        assertFalse(maintenance.allowed(alice, "tech"));   // admin on magic only
        verify(admin).test(alice, "tech");
    }

    @Test
    void atLoginTheAdminCheckGetsNullForAnyServer() throws Exception {
        Player alice = player("Alice", at(OTHER_HOST, 1));
        when(admin.test(alice, null)).thenReturn(true);
        assertTrue(maintenance.allowed(alice, null));
        verify(admin).test(alice, null);
    }

    @Test
    void anAdminThroughTheTunnelIsAllowedButAPlainTunnelPlayerIsNot() throws Exception {
        Player boss = player("Boss", at("127.0.0.1", 40000));
        Player alice = player("Alice", at("127.0.0.1", 40001));
        when(admin.test(boss, "magic")).thenReturn(true);
        assertTrue(maintenance.allowed(boss, "magic"));
        assertFalse(maintenance.allowed(alice, "magic"));
    }

    @Test
    void aPlainPlayerIsNotAllowed() throws Exception {
        register("magic", at(GAME_HOST, 25567));
        assertFalse(maintenance.allowed(player("Alice", at(OTHER_HOST, 1)), "magic"));
        assertFalse(maintenance.allowed(player("Alice", at(OTHER_HOST, 1)), null));
    }

    // ------------------------------------------------------------------------------------------ login and routing use it

    @Test
    void networkWideMaintenanceRefusesAPlainPlayerAtLogin() throws Exception {
        rows("*", "update");
        maintenance.refresh();
        LoginEvent event = new LoginEvent(player("Alice", at(OTHER_HOST, 1)));
        maintenance.onLogin(event);
        assertFalse(event.getResult().isAllowed());
    }

    @Test
    void networkWideMaintenanceLetsABotAndAnAdminLogIn() throws Exception {
        register("magic", at(GAME_HOST, 25567));
        rows("*", "update");
        maintenance.refresh();
        Player bot = player("Bot", at(GAME_HOST, 1));
        Player boss = player("Boss", at(OTHER_HOST, 2));
        when(admin.test(boss, null)).thenReturn(true);
        LoginEvent botLogin = new LoginEvent(bot);
        LoginEvent bossLogin = new LoginEvent(boss);
        maintenance.onLogin(botLogin);
        maintenance.onLogin(bossLogin);
        assertTrue(botLogin.getResult().isAllowed());
        assertTrue(bossLogin.getResult().isAllowed());
        verify(admin).test(boss, null);
    }

    @Test
    void aLoopbackPlayerIsRefusedAtLoginEvenWithABotsServerOnLoopback() throws Exception {
        register("lobby", at("127.0.0.1", 25566));
        rows("*", "");
        maintenance.refresh();
        LoginEvent event = new LoginEvent(player("Alice", at("127.0.0.1", 50000)));
        maintenance.onLogin(event);
        assertFalse(event.getResult().isAllowed());
    }

    @Test
    void withoutMaintenanceNobodyIsRefusedAtLogin() throws Exception {
        maintenance.refresh();
        LoginEvent event = new LoginEvent(player("Alice", at(OTHER_HOST, 1)));
        maintenance.onLogin(event);
        assertTrue(event.getResult().isAllowed());
        verify(admin, never()).test(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aSingleServerUnderMaintenanceDoesNotRefuseLogin() throws Exception {
        rows("magic", "update");
        maintenance.refresh();
        LoginEvent event = new LoginEvent(player("Alice", at(OTHER_HOST, 1)));
        maintenance.onLogin(event);
        assertTrue(event.getResult().isAllowed());
    }

    @Test
    void preConnectDeniesAPlainPlayerToAServerUnderMaintenanceButNotABotOrAnAdmin() throws Exception {
        RegisteredServer magic = register("magic", at(GAME_HOST, 25567));
        rows("magic", "update");
        maintenance.refresh();
        Player alice = player("Alice", at(OTHER_HOST, 1));
        Player bot = player("Bot", at(GAME_HOST, 2));
        Player boss = player("Boss", at(OTHER_HOST, 3));
        when(admin.test(boss, "magic")).thenReturn(true);

        ServerPreConnectEvent forAlice = new ServerPreConnectEvent(alice, magic);
        ServerPreConnectEvent forBot = new ServerPreConnectEvent(bot, magic);
        ServerPreConnectEvent forBoss = new ServerPreConnectEvent(boss, magic);
        maintenance.onPreConnect(forAlice);
        maintenance.onPreConnect(forBot);
        maintenance.onPreConnect(forBoss);
        assertFalse(forAlice.getResult().isAllowed());
        assertTrue(forBot.getResult().isAllowed());
        assertTrue(forBoss.getResult().isAllowed());
    }

    @Test
    void preConnectToAnotherServerIsUntouched() throws Exception {
        RegisteredServer tech = register("tech", at("10.0.0.9", 25568));
        rows("magic", "update");
        maintenance.refresh();
        ServerPreConnectEvent event = new ServerPreConnectEvent(player("Alice", at(OTHER_HOST, 1)), tech);
        maintenance.onPreConnect(event);
        assertTrue(event.getResult().isAllowed());
        assertEquals(tech, event.getResult().getServer().orElseThrow());
    }
}

package com.silver.wakeup.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

class NetworkBansTest {
    private static final UUID ALICE = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");
    private static final UUID BOB = UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444");

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private ProxyServer proxy;
    private Logger logger;
    private NetworkBans bans;

    /** One row of the mocked net_bans result: uuid, name, reason, until. */
    private record Row(String uuid, String name, String reason, Timestamp until) {
    }

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        proxy = mock(ProxyServer.class);
        logger = mock(Logger.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(proxy.getAllPlayers()).thenReturn(List.of());
        rows();
        bans = new NetworkBans(dataSource, proxy, logger);
    }

    private void rows(Row... rows) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        AtomicInteger cursor = new AtomicInteger(-1);
        when(rs.next()).thenAnswer(i -> cursor.incrementAndGet() < rows.length);
        when(rs.getString(1)).thenAnswer(i -> rows[cursor.get()].uuid());
        when(rs.getString(2)).thenAnswer(i -> rows[cursor.get()].name());
        when(rs.getString(3)).thenAnswer(i -> rows[cursor.get()].reason());
        when(rs.getTimestamp(4)).thenAnswer(i -> rows[cursor.get()].until());
        when(statement.executeQuery(anyString())).thenReturn(rs);
    }

    private static Timestamp inFuture() {
        return new Timestamp(System.currentTimeMillis() + 3_600_000L);
    }

    private static Timestamp inPast() {
        return new Timestamp(System.currentTimeMillis() - 3_600_000L);
    }

    private static Player player(UUID uuid, String name) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(uuid);
        when(p.getUsername()).thenReturn(name);
        return p;
    }

    private static String plain(Component c) {
        StringBuilder sb = new StringBuilder();
        appendPlain(c, sb);
        return sb.toString();
    }

    private static void appendPlain(Component c, StringBuilder sb) {
        if (c instanceof TextComponent t) {
            sb.append(t.content());
        }
        for (Component child : c.children()) {
            appendPlain(child, sb);
        }
    }

    // ---- refresh(): table creation -------------------------------------------------------------------

    @Test
    void theTableIsCreatedOnTheFirstRefreshOnly() throws SQLException {
        bans.refresh();
        bans.refresh();
        bans.refresh();

        verify(statement, times(1)).execute(startsWith("CREATE TABLE IF NOT EXISTS net_bans"));
        verify(statement, times(3)).executeQuery(anyString());
    }

    @Test
    void theTableIsCreatedBeforeTheListIsRead() throws SQLException {
        bans.refresh();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(statement);
        order.verify(statement).execute(startsWith("CREATE TABLE IF NOT EXISTS net_bans"));
        order.verify(statement).executeQuery(startsWith("SELECT uuid, name, reason, until FROM net_bans"));
    }

    @Test
    void aFailedCreateIsRetriedOnTheNextRefresh() throws SQLException {
        when(statement.execute(anyString())).thenThrow(new SQLException("no create")).thenReturn(false);

        bans.refresh();
        bans.refresh();
        bans.refresh();

        verify(statement, times(2)).execute(startsWith("CREATE TABLE IF NOT EXISTS net_bans"));
    }

    // ---- refresh(): reading and keying ---------------------------------------------------------------

    @Test
    void uuidBansAreKeyedByLowerCaseUuid() throws SQLException {
        rows(new Row(ALICE.toString().toUpperCase(), "Alice", "cheating", null));
        bans.refresh();

        NetworkBans.Ban ban = bans.find(ALICE, "SomethingElse");
        assertNotNull(ban);
        assertEquals("cheating", ban.reason());
        assertEquals("Alice", ban.name());
    }

    @Test
    void nameOnlyBansWithANullUuidAreKeyedByLowerCaseName() throws SQLException {
        rows(new Row(null, "NeverJoined", "griefing", null));
        bans.refresh();

        assertNotNull(bans.find(UUID.randomUUID(), "neverjoined"));
        assertNotNull(bans.find(UUID.randomUUID(), "NEVERJOINED"));
        assertNull(bans.find(UUID.randomUUID(), "someoneelse"));
    }

    @Test
    void nameOnlyBansWithABlankUuidAreKeyedByLowerCaseName() throws SQLException {
        rows(new Row("", "Empty", "r", null), new Row("   ", "Spaces", "r", null));
        bans.refresh();

        assertNotNull(bans.find(UUID.randomUUID(), "empty"));
        assertNotNull(bans.find(UUID.randomUUID(), "SPACES"));
    }

    @Test
    void aUuidBanIsNotAlsoKeyedByItsName() throws SQLException {
        rows(new Row(ALICE.toString(), "Alice", "r", null));
        bans.refresh();

        assertNull(bans.find(UUID.randomUUID(), "Alice"));
        assertNull(bans.find(null, "Alice"));
    }

    @Test
    void anEmptyTableLeavesNoBans() throws SQLException {
        bans.refresh();

        assertNull(bans.find(ALICE, "Alice"));
    }

    @Test
    void aRefreshReplacesTheOldListSoRemovedBansStopApplying() throws SQLException {
        rows(new Row(ALICE.toString(), "Alice", "r", null));
        bans.refresh();
        assertNotNull(bans.find(ALICE, "Alice"));

        rows();
        bans.refresh();
        assertNull(bans.find(ALICE, "Alice"));
    }

    // ---- refresh(): failures -------------------------------------------------------------------------

    @Test
    void aFailedQueryKeepsThePreviousListAndWarns() throws SQLException {
        rows(new Row(ALICE.toString(), "Alice", "r", null));
        bans.refresh();

        when(statement.executeQuery(anyString())).thenThrow(new SQLException("db down"));
        bans.refresh();

        assertNotNull(bans.find(ALICE, "Alice"));
        verify(logger).warn(anyString(), any(Object.class));
    }

    @Test
    void aFailedConnectionKeepsThePreviousListAndWarns() throws SQLException {
        rows(new Row(null, "Bobby", "r", null));
        bans.refresh();

        when(dataSource.getConnection()).thenThrow(new SQLException("no connection"));
        bans.refresh();

        assertNotNull(bans.find(UUID.randomUUID(), "Bobby"));
        verify(logger).warn(anyString(), any(Object.class));
    }

    @Test
    void aFailedRefreshDoesNotDisconnectAnyone() throws SQLException {
        Player p = player(ALICE, "Alice");
        when(proxy.getAllPlayers()).thenReturn(List.of(p));
        rows(new Row(ALICE.toString(), "Alice", "r", null));
        when(dataSource.getConnection()).thenThrow(new SQLException("down"));

        bans.refresh();

        verify(p, never()).disconnect(any(Component.class));
    }

    @Test
    void aFailureReadingARowKeepsThePreviousList() throws SQLException {
        rows(new Row(ALICE.toString(), "Alice", "r", null));
        bans.refresh();

        ResultSet broken = mock(ResultSet.class);
        when(broken.next()).thenReturn(true);
        when(broken.getString(1)).thenThrow(new SQLException("bad row"));
        when(statement.executeQuery(anyString())).thenReturn(broken);
        bans.refresh();

        assertNotNull(bans.find(ALICE, "Alice"));
        verify(logger).warn(anyString(), any(Object.class));
    }

    // ---- refresh(): disconnecting online players -----------------------------------------------------

    @Test
    void onlinePlayersWhoAreBannedAreDisconnectedAndOthersAreNot() throws SQLException {
        Player alice = player(ALICE, "Alice");
        Player bob = player(BOB, "Bob");
        Player nameOnly = player(UUID.randomUUID(), "Ghost");
        when(proxy.getAllPlayers()).thenReturn(List.of(alice, bob, nameOnly));
        rows(new Row(ALICE.toString(), "Alice", "cheating", null), new Row(null, "ghost", "", null));

        bans.refresh();

        ArgumentCaptor<Component> aliceMsg = ArgumentCaptor.forClass(Component.class);
        verify(alice).disconnect(aliceMsg.capture());
        assertTrue(plain(aliceMsg.getValue()).contains("cheating"));
        verify(nameOnly).disconnect(any(Component.class));
        verify(bob, never()).disconnect(any(Component.class));
    }

    @Test
    void nobodyIsDisconnectedWhenThereAreNoBans() {
        Player alice = player(ALICE, "Alice");
        when(proxy.getAllPlayers()).thenReturn(List.of(alice));

        bans.refresh();

        verify(alice, never()).disconnect(any(Component.class));
    }

    @Test
    void aPlayerBannedBetweenRefreshesIsDisconnectedAtTheNextOne() throws SQLException {
        Player alice = player(ALICE, "Alice");
        when(proxy.getAllPlayers()).thenReturn(List.of(alice));
        bans.refresh();
        verify(alice, never()).disconnect(any(Component.class));

        rows(new Row(ALICE.toString(), "Alice", "r", null));
        bans.refresh();
        verify(alice, times(1)).disconnect(any(Component.class));
    }

    // ---- find() --------------------------------------------------------------------------------------

    @Test
    void findMatchesByUuid() throws SQLException {
        rows(new Row(ALICE.toString(), "Alice", "r", null));
        bans.refresh();

        assertNotNull(bans.find(ALICE, null));
        assertNotNull(bans.find(ALICE, "RenamedAlice"));
        assertNull(bans.find(BOB, "Bob"));
    }

    @Test
    void findFallsBackToNameWhenThereIsNoUuidBan() throws SQLException {
        rows(new Row(null, "Bob", "by name", null));
        bans.refresh();

        NetworkBans.Ban ban = bans.find(BOB, "bOb");
        assertNotNull(ban);
        assertEquals("by name", ban.reason());
        assertNotNull(bans.find(null, "BOB"));
    }

    @Test
    void aUuidBanWinsOverANameBan() throws SQLException {
        rows(new Row(null, "Alice", "by name", null), new Row(ALICE.toString(), "Alice", "by uuid", null));
        bans.refresh();

        assertEquals("by uuid", bans.find(ALICE, "Alice").reason());
    }

    @Test
    void findIsCaseInsensitiveForUuidAndName() throws SQLException {
        rows(new Row(ALICE.toString().toUpperCase(), "Alice", "u", null), new Row(null, "MixedCase", "n", null));
        bans.refresh();

        assertNotNull(bans.find(ALICE, null));
        assertNotNull(bans.find(UUID.randomUUID(), "mIXEDcASE"));
    }

    @Test
    void findHandlesNullUuidAndNullName() throws SQLException {
        rows(new Row(ALICE.toString(), "Alice", "r", null), new Row(null, "Bob", "r", null));
        bans.refresh();

        assertNull(bans.find(null, null));
        assertNull(bans.find(UUID.randomUUID(), null));
        assertNull(bans.find(null, "nobody"));
    }

    @Test
    void findIgnoresABanWhoseEndHasPassed() throws SQLException {
        rows(new Row(ALICE.toString(), "Alice", "r", inPast()), new Row(null, "Bob", "r", inPast()));
        bans.refresh();

        assertNull(bans.find(ALICE, "Alice"));
        assertNull(bans.find(UUID.randomUUID(), "Bob"));
    }

    @Test
    void findAppliesABanWhoseEndIsStillAhead() throws SQLException {
        rows(new Row(ALICE.toString(), "Alice", "r", inFuture()), new Row(null, "Bob", "r", inFuture()));
        bans.refresh();

        assertNotNull(bans.find(ALICE, "Alice"));
        assertNotNull(bans.find(UUID.randomUUID(), "Bob"));
    }

    @Test
    void findReturnsTheStoredBan() throws SQLException {
        Timestamp until = inFuture();
        rows(new Row(ALICE.toString(), "Alice", "because", until));
        bans.refresh();

        NetworkBans.Ban ban = bans.find(ALICE, "Alice");
        assertEquals(ALICE.toString(), ban.uuid());
        assertEquals("because", ban.reason());
        assertSame(until, ban.until());
    }

    @Test
    void findBeforeAnyRefreshFindsNothing() {
        assertNull(bans.find(ALICE, "Alice"));
    }

    // ---- onLogin() -----------------------------------------------------------------------------------

    private LoginEvent loginOf(Player p) {
        return new LoginEvent(p);
    }

    @Test
    void aBannedLoginIsDeniedWithTheReasonAndNoEnd() throws SQLException {
        rows(new Row(ALICE.toString(), "Alice", "cheating", null));
        bans.refresh();
        LoginEvent event = loginOf(player(ALICE, "Alice"));

        bans.onLogin(event);

        assertFalse(event.getResult().isAllowed());
        String text = plain(event.getResult().getReasonComponent().orElseThrow());
        assertTrue(text.contains("banned from this network"), text);
        assertTrue(text.contains("Reason: cheating"), text);
        assertTrue(text.contains("doesn't end"), text);
    }

    @Test
    void aBannedLoginWithAnEndTimeSaysWhenItEnds() throws SQLException {
        rows(new Row(ALICE.toString(), "Alice", "spam", inFuture()));
        bans.refresh();
        LoginEvent event = loginOf(player(ALICE, "Alice"));

        bans.onLogin(event);

        assertFalse(event.getResult().isAllowed());
        String text = plain(event.getResult().getReasonComponent().orElseThrow());
        assertTrue(text.contains("banned from this network"), text);
        assertTrue(text.contains("It ends "), text);
        assertFalse(text.contains("doesn't end"), text);
    }

    @Test
    void aBanWithoutAReasonLeavesTheReasonLineOut() throws SQLException {
        rows(new Row(ALICE.toString(), "Alice", "", null), new Row(null, "Bob", null, null));
        bans.refresh();

        for (Player p : List.of(player(ALICE, "Alice"), player(UUID.randomUUID(), "Bob"))) {
            LoginEvent event = loginOf(p);
            bans.onLogin(event);
            assertFalse(event.getResult().isAllowed());
            String text = plain(event.getResult().getReasonComponent().orElseThrow());
            assertTrue(text.contains("banned from this network"), text);
            assertFalse(text.contains("Reason:"), text);
        }
    }

    @Test
    void aNameOnlyBanDeniesTheLogin() throws SQLException {
        rows(new Row(null, "Ghost", "r", null));
        bans.refresh();
        LoginEvent event = loginOf(player(UUID.randomUUID(), "GHOST"));

        bans.onLogin(event);

        assertFalse(event.getResult().isAllowed());
    }

    @Test
    void anUnbannedLoginIsLeftUntouched() throws SQLException {
        rows(new Row(ALICE.toString(), "Alice", "r", null));
        bans.refresh();
        LoginEvent event = loginOf(player(BOB, "Bob"));
        ResultedEvent.ComponentResult before = event.getResult();

        bans.onLogin(event);

        assertTrue(event.getResult().isAllowed());
        assertSame(before, event.getResult());
    }

    @Test
    void aLoginWhoseBanHasExpiredIsLeftUntouched() throws SQLException {
        rows(new Row(ALICE.toString(), "Alice", "r", inPast()));
        bans.refresh();
        LoginEvent event = loginOf(player(ALICE, "Alice"));

        bans.onLogin(event);

        assertTrue(event.getResult().isAllowed());
    }
}

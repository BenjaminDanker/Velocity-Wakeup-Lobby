package com.silver.wakeup.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.proxy.InboundConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

/**
 * Rate limiting and greylisting are keyed by the remote address, except for loopback (every outside player arrives through the
 * tunnel as 127.0.0.1), where the key is the lower-cased player name. The limit is 3 logins per 5 seconds; these tests make a
 * handful of logins back to back, far inside that window.
 */
class SecurityManagerKeyingTest {
    private static final String RATE_LIMITED = "Too many rapid connection attempts. Please try again later.";
    private static final String GREYLISTED = "Too many rapid connection attempts. Please try again in a few minutes.";
    private static final String INVALID = "Invalid username format or characters.";

    private SecurityManager security;

    @BeforeEach
    void setUp() {
        security = new SecurityManager(mock(Logger.class), mock(WhitelistStore.class));
    }

    private static InetSocketAddress at(String literal) throws UnknownHostException {
        return new InetSocketAddress(InetAddress.getByName(literal), 50000);
    }

    /** One pre-login from the address with the name; the reason text when it was denied, or null when it was let through. */
    private String login(InetSocketAddress from, String name) {
        InboundConnection connection = mock(InboundConnection.class);
        when(connection.getRemoteAddress()).thenReturn(from);
        PreLoginEvent event = new PreLoginEvent(connection, name);
        security.onPreLogin(event);
        if (event.getResult().isAllowed()) {
            return null;
        }
        Component reason = event.getResult().getReasonComponent().orElseThrow();
        return ((TextComponent) reason).content();
    }

    private String login(String from, String name) throws UnknownHostException {
        return login(at(from), name);
    }

    // ------------------------------------------------------------------------------------------ loopback: keyed by name

    @Test
    void fourQuickLoginsFromLoopbackWithDifferentNamesAreNotBlocked() throws Exception {
        for (String name : new String[] {"Alice", "Bob", "Carol", "Dave", "Erin", "Frank"}) {
            assertEquals(null, login("127.0.0.1", name), name);
        }
    }

    @Test
    void manyDifferentNamesThroughTheTunnelNeverTripTheLimit() throws Exception {
        for (int i = 0; i < 100; i++) {
            assertEquals(null, login("127.0.0.1", "player" + i), "player" + i);
        }
    }

    @Test
    void theSameNameFromLoopbackIsRateLimitedOnTheFourthLogin() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertEquals(null, login("127.0.0.1", "Alice"), "login " + (i + 1));
        }
        assertEquals(RATE_LIMITED, login("127.0.0.1", "Alice"));
        assertEquals(GREYLISTED, login("127.0.0.1", "Alice"));  // and now greylisted
    }

    @Test
    void rateLimitingOneNameOnLoopbackLeavesTheOtherNamesAlone() throws Exception {
        for (int i = 0; i < 4; i++) {
            login("127.0.0.1", "Spammer");
        }
        assertEquals(GREYLISTED, login("127.0.0.1", "Spammer"));
        assertEquals(null, login("127.0.0.1", "Alice"));
        assertEquals(null, login("127.0.0.1", "Bob"));
        assertEquals(null, login("::1", "Carol"));
    }

    @Test
    void loopbackNamesAreKeyedCaseInsensitively() throws Exception {
        assertEquals(null, login("127.0.0.1", "Alice"));
        assertEquals(null, login("127.0.0.1", "ALICE"));
        assertEquals(null, login("127.0.0.1", "alice"));
        assertEquals(RATE_LIMITED, login("127.0.0.1", "aLiCe"));
    }

    @Test
    void ipv6LoopbackAndOtherLoopbackAddressesAreSharedToo() throws Exception {
        for (String loopback : new String[] {"::1", "127.0.0.2", "127.1.2.3"}) {
            for (String name : new String[] {"Aaa", "Bbb", "Ccc", "Ddd"}) {
                assertEquals(null, login(loopback, name + loopback.length()), loopback + " " + name);
            }
        }
    }

    @Test
    void theSameNameFromDifferentLoopbackAddressesSharesOneKey() throws Exception {
        assertEquals(null, login("127.0.0.1", "Alice"));
        assertEquals(null, login("127.0.0.2", "Alice"));
        assertEquals(null, login("::1", "Alice"));
        assertEquals(RATE_LIMITED, login("127.0.0.1", "Alice"));
    }

    @Test
    void anUnresolvedRemoteAddressIsTreatedLikeLoopback() {
        InetSocketAddress unresolved = InetSocketAddress.createUnresolved("some.host.invalid", 25565);
        for (String name : new String[] {"Alice", "Bob", "Carol", "Dave", "Erin"}) {
            assertEquals(null, login(unresolved, name), name);
        }
    }

    @Test
    void aBadNameFromLoopbackIsDeniedButDoesNotGreylistOtherNames() throws Exception {
        assertEquals(INVALID, login("127.0.0.1", "bad name!"));
        assertEquals(null, login("127.0.0.1", "Alice"));
        assertEquals(null, login("127.0.0.1", "Bob"));
        assertEquals(null, login("127.0.0.1", "Carol"));
        assertEquals(null, login("127.0.0.1", "Dave"));
    }

    @Test
    void aBadNameFromLoopbackIsNotGreylistedItself() throws Exception {
        // twice in a row: still "invalid", never the greylist message (the third and later hit the plain rate limit for that name)
        assertEquals(INVALID, login("127.0.0.1", "x!"));
        assertEquals(INVALID, login("127.0.0.1", "x!"));
        assertEquals(INVALID, login("127.0.0.1", "x!"));
        assertEquals(RATE_LIMITED, login("127.0.0.1", "x!"));
        assertEquals(null, login("127.0.0.1", "Alice"));
    }

    @Test
    void aBadNameFromLoopbackDoesNotBlockTheSameNameInAValidSpelling() throws Exception {
        assertEquals(INVALID, login("127.0.0.1", "Al ice"));
        assertEquals(null, login("127.0.0.1", "Alice"));
    }

    // ------------------------------------------------------------------------------------------ other addresses: keyed by address

    @Test
    void fourQuickLoginsFromOneLanAddressAreBlockedOnTheFourth() throws Exception {
        assertEquals(null, login("192.168.1.50", "Alice"));
        assertEquals(null, login("192.168.1.50", "Bob"));
        assertEquals(null, login("192.168.1.50", "Carol"));
        assertEquals(RATE_LIMITED, login("192.168.1.50", "Dave"));
    }

    @Test
    void theNameDoesNotMatterForALanAddress() throws Exception {
        for (int i = 0; i < 3; i++) {
            login("192.168.1.50", "Alice");
        }
        assertEquals(RATE_LIMITED, login("192.168.1.50", "Alice"));
    }

    @Test
    void aRateLimitedLanAddressIsGreylistedForEveryName() throws Exception {
        for (int i = 0; i < 4; i++) {
            login("192.168.1.50", "name" + i);
        }
        assertEquals(GREYLISTED, login("192.168.1.50", "SomeoneNew"));
        assertEquals(GREYLISTED, login("192.168.1.50", "Alice"));
    }

    @Test
    void oneLanAddressBeingBlockedDoesNotBlockAnotherOrLoopback() throws Exception {
        for (int i = 0; i < 4; i++) {
            login("192.168.1.50", "name" + i);
        }
        assertEquals(GREYLISTED, login("192.168.1.50", "Alice"));
        assertEquals(null, login("192.168.1.51", "Alice"));
        assertEquals(null, login("127.0.0.1", "Alice"));
        assertEquals(null, login("::1", "Bob"));
        assertEquals(null, login("10.0.0.7", "Carol"));
    }

    @Test
    void aBadNameFromALanAddressGreylistsThatAddress() throws Exception {
        assertEquals(INVALID, login("192.168.1.60", "bad name!"));
        assertEquals(GREYLISTED, login("192.168.1.60", "Alice"));
        assertEquals(GREYLISTED, login("192.168.1.60", "Bob"));
        assertEquals(null, login("192.168.1.61", "Alice"));  // another address is fine
        assertEquals(null, login("127.0.0.1", "Alice"));
    }

    @Test
    void ipv6AddressesAreKeyedByAddressToo() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertEquals(null, login("2001:db8::1", "name" + i));
        }
        assertEquals(RATE_LIMITED, login("2001:db8::1", "name3"));
        assertEquals(null, login("2001:db8::2", "name3"));
    }

    @Test
    void aLanAddressAndALoopbackNameDoNotShareState() throws Exception {
        for (int i = 0; i < 4; i++) {
            login("192.168.1.50", "Alice");
        }
        assertEquals(GREYLISTED, login("192.168.1.50", "Alice"));
        assertEquals(null, login("127.0.0.1", "Alice"));
    }

    // ------------------------------------------------------------------------------------------ the name rules still apply

    @Test
    void validAndInvalidNames() throws Exception {
        for (String ok : new String[] {"abc", "Abc_123", "a".repeat(16), "_x_"}) {
            assertEquals(null, login("127.0.0.1", ok), ok);
        }
        for (String bad : new String[] {"ab", "a".repeat(17), "a-b", "a b", ".Floodgate", "café", ""}) {
            assertEquals(INVALID, login("127.0.0.1", bad), bad);
        }
    }

    @Test
    void anInvalidResultIsNotAllowed() throws Exception {
        InboundConnection connection = mock(InboundConnection.class);
        when(connection.getRemoteAddress()).thenReturn(at("127.0.0.1"));
        PreLoginEvent event = new PreLoginEvent(connection, "x");
        security.onPreLogin(event);
        assertFalse(event.getResult().isAllowed());
        assertTrue(new PreLoginEvent(connection, "Alice").getResult().isAllowed());
    }
}

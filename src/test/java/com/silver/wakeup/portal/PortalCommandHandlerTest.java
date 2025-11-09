package com.silver.wakeup.portal;

import com.velocitypowered.api.proxy.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.helpers.NOPLogger;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortalCommandHandlerTest {

    private Player player;
    private UUID playerId;
    private PortalCommandHandler handler;
    private StubDependencies dependencies;

    @BeforeEach
    void setUp() {
        player = mock(Player.class, Mockito.RETURNS_DEEP_STUBS);
        playerId = UUID.randomUUID();
        when(player.getUsername()).thenReturn("TestPlayer");
        when(player.getUniqueId()).thenReturn(playerId);

        dependencies = new StubDependencies();
        handler = new PortalCommandHandler(NOPLogger.NOP_LOGGER, dependencies);
    }

    @Test
    void returnsFalseAndNotifiesWhenTokenInvalid() {
        dependencies.verifyResult = false;

        boolean result = handler.handle(player, "target-server", "bad-token", Optional.of("portal_a"));

        assertFalse(result);
        assertTrue(dependencies.invalidTokenNotified, "Expected invalid token notification");
        assertFalse(dependencies.rememberCalled, "Should not remember portal when token is invalid");
        assertFalse(dependencies.unlockCalled, "Should not unlock server when token is invalid");
    }

    @Test
    void storesPortalAndPreparesStickyWhenValid() {
        boolean result = handler.handle(player, "target-server", "good-token", Optional.of("portal_a"));

        assertTrue(result);
        assertTrue(dependencies.rememberCalled);
        assertEquals(playerId, dependencies.rememberPlayerId);
        assertEquals("portal_a", dependencies.rememberPortalName);
        assertTrue(dependencies.unlockCalled, "Expected destination to be unlocked");
        assertTrue(dependencies.beginStickyCalled, "Expected sticky wait to begin");
        assertTrue(dependencies.markInternalCalled, "Expected internal mark to be set");
        assertTrue(dependencies.connectionResolved, "Expected holding connection to be resolved");
        assertTrue(dependencies.connectionInvoked, "Expected holding connection to be triggered");
    }

    @Test
    void returnsFalseWhenHoldingServerMissing() {
        dependencies.holdingAvailable = false;

        boolean result = handler.handle(player, "target-server", "good-token", Optional.empty());

        assertFalse(result);
        assertTrue(dependencies.connectionResolved, "Should attempt to resolve holding server");
        assertFalse(dependencies.beginStickyCalled, "Should not begin sticky wait when holding is missing");
    }

    private static final class StubDependencies implements PortalCommandHandler.Dependencies {
        boolean verifyResult = true;
        boolean holdingAvailable = true;
        boolean rememberCalled;
        UUID rememberPlayerId;
        String rememberPortalName;
        boolean unlockCalled;
        boolean beginStickyCalled;
        boolean markInternalCalled;
        boolean invalidTokenNotified;
        boolean connectionResolved;
        boolean connectionInvoked;

        @Override
        public boolean verifyPortalToken(String target, String token) {
            return verifyResult;
        }

        @Override
        public void rememberSourcePortal(UUID playerId, String portalName) {
            rememberCalled = true;
            rememberPlayerId = playerId;
            rememberPortalName = portalName;
        }

        @Override
        public void unlockServerFor(UUID playerId, String target) {
            unlockCalled = true;
        }

        @Override
        public Optional<String> currentServerName(Player player) {
            return Optional.of("origin-server");
        }

        @Override
        public Optional<PortalCommandHandler.HoldingConnection> resolveHoldingServer(Player player, String target, Optional<String> originServer) {
            connectionResolved = true;
            if (!holdingAvailable) {
                return Optional.empty();
            }
            return Optional.of(() -> connectionInvoked = true);
        }

        @Override
        public void beginStickyWait(UUID playerId, String target, Optional<String> originServer) {
            beginStickyCalled = true;
        }

        @Override
        public void markInternalOnce(UUID playerId) {
            markInternalCalled = true;
        }

        @Override
        public void notifyInvalidToken(Player player) {
            invalidTokenNotified = true;
        }

        @Override
        public String holdingServerName() {
            return "holding";
        }
    }
}

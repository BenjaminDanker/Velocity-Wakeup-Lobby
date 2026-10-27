package com.silver.wakeup.session;

import com.silver.wakeup.portal.PortalHandoffService;
import com.silver.wakeup.plugin.VelocityPlugin;
import com.silver.wakeup.wake.WakeService;
import com.velocitypowered.api.proxy.ProxyServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.helpers.NOPLogger;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DisplayName("StickyRouter internal marks")
class StickyRouterInternalMarkTest {

    private StickyRouter router;
    private UUID player;

    @BeforeEach
    void setup() {
        Logger logger = NOPLogger.NOP_LOGGER;
        ProxyServer proxyServer = mock(ProxyServer.class);
        VelocityPlugin plugin = mock(VelocityPlugin.class);
        WakeService wakeService = mock(WakeService.class);
        PortalHandoffService portalHandoffService = mock(PortalHandoffService.class);

        Map<String, List<String>> groups = Map.of(
                "survival", List.of("survival_main"),
                "creative", List.of("creative_main")
        );
        Map<String, String> serverToMac = Map.of(
                "survival_main", "00:11:22:33:44:55",
                "creative_main", "AA:BB:CC:DD:EE:FF"
        );
        Function<UUID, List<String>> allowedListFn = uuid -> List.of("survival_main", "creative_main");

        router = new StickyRouter(
                proxyServer, plugin, "lobby", 300, 30,
                groups, serverToMac,
                wakeService, logger, allowedListFn, portalHandoffService
        );
        player = UUID.randomUUID();
    }

    @Test
    @DisplayName("mark for tech then consume for tech is true")
    void consumeMatchingTargetIsTrue() {
        router.markInternalOnce(player, "tech");

        assertTrue(router.consumeInternalOnce(player, "tech"));
    }

    @Test
    @DisplayName("mark for tech then consume for magic is false")
    void consumeDifferentTargetIsFalse() {
        router.markInternalOnce(player, "tech");

        assertFalse(router.consumeInternalOnce(player, "magic"));
    }

    @Test
    @DisplayName("consuming twice returns false the second time")
    void consumeTwiceSecondIsFalse() {
        router.markInternalOnce(player, "tech");

        assertTrue(router.consumeInternalOnce(player, "tech"));
        assertFalse(router.consumeInternalOnce(player, "tech"));
    }

    @Test
    @DisplayName("clearInternalMark then consume is false")
    void clearedMarkIsNotConsumed() {
        router.markInternalOnce(player, "tech");
        router.clearInternalMark(player);

        assertFalse(router.consumeInternalOnce(player, "tech"));
    }

    @Test
    @DisplayName("target match is case-insensitive")
    void targetMatchIgnoresCase() {
        router.markInternalOnce(player, "Tech");

        assertTrue(router.consumeInternalOnce(player, "tech"));
    }

    @Test
    @DisplayName("consume without any mark is false")
    void consumeWithoutMarkIsFalse() {
        assertFalse(router.consumeInternalOnce(player, "tech"));
    }
}

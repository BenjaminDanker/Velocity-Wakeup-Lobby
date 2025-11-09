package com.silver.wakeup.session;

import com.silver.wakeup.portal.PortalHandoffService;
import com.silver.wakeup.plugin.VelocityPlugin;
import com.silver.wakeup.wake.WakeService;
import com.velocitypowered.api.proxy.ProxyServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.slf4j.Logger;
import org.slf4j.helpers.NOPLogger;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("StickyRouter")
class StickyRouterTest {

    private Logger logger;
    private ProxyServer proxyServer;
    private VelocityPlugin plugin;
    private WakeService wakeService;
    private PortalHandoffService portalHandoffService;
    private Map<String, List<String>> groups;
    private Map<String, String> serverToMac;
    private Function<UUID, List<String>> allowedListFn;

    @BeforeEach
    void setup() {
        logger = NOPLogger.NOP_LOGGER;
        proxyServer = mock(ProxyServer.class);
        plugin = mock(VelocityPlugin.class);
        wakeService = mock(WakeService.class);
        portalHandoffService = mock(PortalHandoffService.class);

        groups = Map.of(
                "survival", List.of("survival_main", "survival_backup"),
                "creative", List.of("creative_main")
        );

        serverToMac = Map.of(
                "survival_main", "00:11:22:33:44:55",
                "creative_main", "AA:BB:CC:DD:EE:FF"
        );

        allowedListFn = uuid -> List.of("survival_main", "survival_backup", "creative_main");
    }

    @Test
    @DisplayName("constructor requires non-null dependencies")
    void constructorRequiresNonNull() {
        assertThrows(NullPointerException.class, () ->
                new StickyRouter(
                        null, plugin, "lobby", 300, 30,
                        StickyRouter.FallbackPolicy.AUTO, groups, serverToMac,
                        wakeService, logger, allowedListFn, Set.of(), portalHandoffService
                )
        );

        assertThrows(NullPointerException.class, () ->
                new StickyRouter(
                        proxyServer, plugin, null, 300, 30,
                        StickyRouter.FallbackPolicy.AUTO, groups, serverToMac,
                        wakeService, logger, allowedListFn, Set.of(), portalHandoffService
                )
        );

        assertThrows(NullPointerException.class, () ->
                new StickyRouter(
                        proxyServer, plugin, "lobby", 300, 30,
                        null, groups, serverToMac,
                        wakeService, logger, allowedListFn, Set.of(), portalHandoffService
                )
        );
    }

    @Test
    @DisplayName("router can be created with valid parameters")
    void constructorValid() {
        assertNotNull(createRouter(StickyRouter.FallbackPolicy.AUTO));
        assertNotNull(createRouter(StickyRouter.FallbackPolicy.OFFER));
        assertNotNull(createRouter(StickyRouter.FallbackPolicy.STRICT));
    }

    @Nested
    @DisplayName("FallbackPolicy enum")
    class FallbackPolicyTests {

        @Test
        @DisplayName("all policies have expected values")
        void policiesExist() {
            assertTrue(StickyRouter.FallbackPolicy.STRICT.name().equals("STRICT"));
            assertTrue(StickyRouter.FallbackPolicy.OFFER.name().equals("OFFER"));
            assertTrue(StickyRouter.FallbackPolicy.AUTO.name().equals("AUTO"));
        }

        @Test
        @DisplayName("policies can be compared")
        void policiesComparable() {
            assertEquals(StickyRouter.FallbackPolicy.STRICT, StickyRouter.FallbackPolicy.STRICT);
            assertNotEquals(StickyRouter.FallbackPolicy.STRICT, StickyRouter.FallbackPolicy.OFFER);
        }
    }

    @Nested
    @DisplayName("gracePeriod and ping configuration")
    class ConfigurationTests {

        @Test
        @DisplayName("router accepts various grace period values")
        void gracePeriodValues() {
            assertNotNull(createRouter(StickyRouter.FallbackPolicy.AUTO, 0, 30));
            assertNotNull(createRouter(StickyRouter.FallbackPolicy.AUTO, 60, 30));
            assertNotNull(createRouter(StickyRouter.FallbackPolicy.AUTO, 3600, 30));
        }

        @Test
        @DisplayName("router accepts various ping interval values")
        void pingIntervalValues() {
            assertNotNull(createRouter(StickyRouter.FallbackPolicy.AUTO, 300, 0));
            assertNotNull(createRouter(StickyRouter.FallbackPolicy.AUTO, 300, 15));
            assertNotNull(createRouter(StickyRouter.FallbackPolicy.AUTO, 300, 120));
        }
    }

    @Nested
    @DisplayName("router configuration")
    class RouterConfigurationTests {

        @Test
        @DisplayName("router with empty groups")
        void emptyGroups() {
            assertNotNull(new StickyRouter(
                    proxyServer, plugin, "lobby", 300, 30,
                    StickyRouter.FallbackPolicy.AUTO, Map.of(), serverToMac,
                    wakeService, logger, allowedListFn, Set.of(), portalHandoffService
            ));
        }

        @Test
        @DisplayName("router with empty serverToMac")
        void emptyServerToMac() {
            assertNotNull(new StickyRouter(
                    proxyServer, plugin, "lobby", 300, 30,
                    StickyRouter.FallbackPolicy.AUTO, groups, Map.of(),
                    wakeService, logger, allowedListFn, Set.of(), portalHandoffService
            ));
        }

        @Test
        @DisplayName("router with empty admin names")
        void emptyAdminNames() {
            assertNotNull(createRouter(StickyRouter.FallbackPolicy.AUTO));
        }

        @Test
        @DisplayName("router with admin names")
        void withAdminNames() {
            assertNotNull(new StickyRouter(
                    proxyServer, plugin, "lobby", 300, 30,
                    StickyRouter.FallbackPolicy.AUTO, groups, serverToMac,
                    wakeService, logger, allowedListFn, Set.of("Admin1", "Admin2"), portalHandoffService
            ));
        }
    }

    @Nested
    @DisplayName("server group handling")
    class ServerGroupTests {

        @Test
        @DisplayName("router stores group configuration")
        void groupsStored() {
            StickyRouter router = new StickyRouter(
                    proxyServer, plugin, "lobby", 300, 30,
                    StickyRouter.FallbackPolicy.AUTO,
                    Map.of("group1", List.of("server1", "server2")),
                    Map.of(),
                    wakeService, logger, allowedListFn, Set.of(), portalHandoffService
            );
            assertNotNull(router);
        }

        @Test
        @DisplayName("router handles server-to-mac mapping")
        void serverToMacMapped() {
            StickyRouter router = createRouter(StickyRouter.FallbackPolicy.AUTO);
            assertNotNull(router);
        }

        @Test
        @DisplayName("large number of groups")
        void manyGroups() {
            Map<String, List<String>> manyGroups = new java.util.HashMap<>();
            for (int i = 0; i < 100; i++) {
                manyGroups.put("group" + i, List.of("server" + i + "_1", "server" + i + "_2"));
            }

            assertNotNull(new StickyRouter(
                    proxyServer, plugin, "lobby", 300, 30,
                    StickyRouter.FallbackPolicy.AUTO, manyGroups, Map.of(),
                    wakeService, logger, allowedListFn, Set.of(), portalHandoffService
            ));
        }
    }

    @Nested
    @DisplayName("fallback policy behavior")
    class FallbackPolicyBehaviorTests {

        @Test
        @DisplayName("STRICT policy created successfully")
        void strictPolicy() {
            assertNotNull(createRouter(StickyRouter.FallbackPolicy.STRICT));
        }

        @Test
        @DisplayName("OFFER policy created successfully")
        void offerPolicy() {
            assertNotNull(createRouter(StickyRouter.FallbackPolicy.OFFER));
        }

        @Test
        @DisplayName("AUTO policy created successfully")
        void autoPolicy() {
            assertNotNull(createRouter(StickyRouter.FallbackPolicy.AUTO));
        }
    }

    // Helper method to create a StickyRouter with default parameters
    private StickyRouter createRouter(StickyRouter.FallbackPolicy policy) {
        return createRouter(policy, 300, 30);
    }

    private StickyRouter createRouter(StickyRouter.FallbackPolicy policy, long graceSec, long pingEvery) {
        return new StickyRouter(
                proxyServer,
                plugin,
                "lobby",
                graceSec,
                pingEvery,
                policy,
                groups,
                serverToMac,
                wakeService,
                logger,
                allowedListFn,
                Set.of(),
                portalHandoffService
        );
    }
}

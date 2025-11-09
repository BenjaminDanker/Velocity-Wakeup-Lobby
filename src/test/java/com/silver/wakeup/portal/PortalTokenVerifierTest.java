package com.silver.wakeup.portal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalTokenVerifierTest {

    private PortalTokenVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new PortalTokenVerifier(NOPLogger.NOP_LOGGER);
    }

    @Test
    void rejectsWhenNoSecretsConfigured() {
        verifier.updateSecrets("", Map.of());

        assertFalse(verifier.verify("server", "token"));
    }

    @Test
    void acceptsGlobalSecretWhenNoPerServerSecret() {
        verifier.updateSecrets("global-token", Map.of());

        assertTrue(verifier.verify("server", "global-token"));
        assertFalse(verifier.verify("server", "wrong"));
    }

    @Test
    void perServerSecretOverridesGlobalSecret() {
        verifier.updateSecrets("global-token", Map.of("server", "secret"));

        assertTrue(verifier.verify("server", "secret"));
        assertFalse(verifier.verify("server", "global-token"));
    }

    @Test
    void trimsSecretsDuringUpdateAndVerification() {
        verifier.updateSecrets("  global  ", Map.of("server", "  secret  "));

        assertTrue(verifier.verify("server", "secret"));
        assertTrue(verifier.verify("other", "global"));
    }

    @Test
    void handlesNullTokenGracefully() {
        verifier.updateSecrets("global", Map.of());

        assertFalse(verifier.verify("server", null));
    }
}

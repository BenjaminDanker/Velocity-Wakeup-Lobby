package com.silver.wakeup.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.silver.wakeup.config.OnDemandSettings;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VelocityPluginCapacityHostTest {
    private static OnDemandSettings target(String sshTarget) {
        return new OnDemandSettings(true, sshTarget, "/k", 10, Set.of("magic"));
    }

    @Test
    void theHostIsTheTextAfterTheAt() {
        assertEquals("primary-ram.local", VelocityPlugin.capacityHost(target("silver@primary-ram.local")));
        assertEquals("10.0.0.5", VelocityPlugin.capacityHost(target("root@10.0.0.5")));
    }

    @Test
    void aTargetWithoutAUserIsTheHost() {
        assertEquals("host-only", VelocityPlugin.capacityHost(target("host-only")));
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        assertEquals("box.lan", VelocityPlugin.capacityHost(target("  me@box.lan  ")));
    }

    @Test
    void blankOrMissingFallsBackToThePrimaryHost() {
        assertEquals("primary-ram.local", VelocityPlugin.capacityHost(target("")));
        assertEquals("primary-ram.local", VelocityPlugin.capacityHost(target("   ")));
        assertEquals("primary-ram.local", VelocityPlugin.capacityHost(target(null)));
        assertEquals("primary-ram.local", VelocityPlugin.capacityHost(null));
        assertEquals("primary-ram.local", VelocityPlugin.capacityHost(OnDemandSettings.DISABLED));
    }

    @Test
    void anAtWithNothingAfterItFallsBack() {
        assertEquals("primary-ram.local", VelocityPlugin.capacityHost(target("silver@")));
    }
}

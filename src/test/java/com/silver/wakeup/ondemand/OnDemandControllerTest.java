package com.silver.wakeup.ondemand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.silver.wakeup.config.LobbyConfigLoader;
import com.silver.wakeup.config.OnDemandSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class OnDemandControllerTest {
    @Test
    void aWantedServerIsStartedOverSshWithTheWakePacketFirst() throws Exception {
        List<List<String>> commands = new CopyOnWriteArrayList<>();
        List<String> woken = new CopyOnWriteArrayList<>();
        OnDemandController controller = new OnDemandController(LoggerFactory.getLogger("test"), woken::add, command -> {
            commands.add(command);
            return 0;
        });
        controller.configure(new OnDemandSettings(true, "silver@primary-ram.local", "/home/pi/.ssh/key", 10, Set.of("magic")));
        controller.want("magic");
        controller.tick(Map.of("magic", new OnDemandPlanner.Observed(false, 0)));
        for (int i = 0; i < 50 && commands.isEmpty(); i++) {
            Thread.sleep(20);
        }
        assertEquals(1, commands.size());
        assertEquals("ssh", commands.getFirst().getFirst());
        assertEquals("start magic", commands.getFirst().getLast());
        assertTrue(commands.getFirst().contains("silver@primary-ram.local"));
        assertEquals(List.of("magic"), woken);
        controller.close();
    }

    @Test
    void whenDisabledNothingIsEverRun() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        OnDemandController controller = new OnDemandController(LoggerFactory.getLogger("test"), name -> { }, command -> {
            commands.add(command);
            return 0;
        });
        controller.configure(OnDemandSettings.DISABLED);
        controller.want("magic");
        controller.tick(Map.of("magic", new OnDemandPlanner.Observed(false, 0)));
        Thread.sleep(100);
        assertEquals(List.of(), commands);
        controller.close();
    }

    @Test
    void theConfigSectionIsParsedAndAnIncompleteOneMeansOff() {
        OnDemandSettings on = LobbyConfigLoader.parseOnDemand(Map.of("enabled", true, "ssh_target", "silver@host", "ssh_key", "/k",
            "idle_minutes", 15, "servers", List.of("magic", "tech")));
        assertTrue(on.enabled());
        assertEquals(15, on.idleMinutes());
        assertEquals(Set.of("magic", "tech"), on.servers());
        assertEquals(OnDemandSettings.DISABLED, LobbyConfigLoader.parseOnDemand(Map.of("enabled", true, "servers", List.of("magic"))));
        assertEquals(OnDemandSettings.DISABLED, LobbyConfigLoader.parseOnDemand(null));
    }
}

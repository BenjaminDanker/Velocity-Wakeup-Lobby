package com.silver.wakeup.wake;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WakeService")
class WakeServiceTest {

    @Test
    @DisplayName("constructor accepts valid broadcast IP")
    void constructorWithValidIp() throws Exception {
        WakeService service = new WakeService("192.168.1.255");
        assertNotNull(service);
    }

    @Test
    @DisplayName("constructor accepts localhost")
    void constructorWithLocalhost() throws Exception {
        WakeService service = new WakeService("127.0.0.1");
        assertNotNull(service);
    }

    @Test
    @DisplayName("constructor throws UnknownHostException for invalid hostname")
    void constructorWithInvalidHostname() {
        assertThrows(java.net.UnknownHostException.class, () ->
                new WakeService("this-host-definitely-does-not-exist-12345.local")
        );
    }

    @Test
    @DisplayName("wake sends packet without throwing")
    void wakeSendsPacket() throws Exception {
        WakeService service = new WakeService("127.0.0.1");
        // Should not throw - packet is sent to localhost
        service.wake("00:11:22:33:44:55");
    }

    @Test
    @DisplayName("wake accepts MAC addresses with colons")
    void wakeWithColonMac() throws Exception {
        WakeService service = new WakeService("127.0.0.1");
        service.wake("AA:BB:CC:DD:EE:FF");
    }

    @Test
    @DisplayName("wake accepts MAC addresses with hyphens")
    void wakeWithHyphenMac() throws Exception {
        WakeService service = new WakeService("127.0.0.1");
        service.wake("AA-BB-CC-DD-EE-FF");
    }

    @Test
    @DisplayName("wake rejects invalid MAC - too few octets")
    void wakeRejectsInvalidMacTooFew() throws Exception {
        WakeService service = new WakeService("127.0.0.1");
        assertThrows(IllegalArgumentException.class, () ->
                service.wake("00:11:22:33:44")
        );
    }

    @Test
    @DisplayName("wake rejects invalid MAC - too many octets")
    void wakeRejectsInvalidMacTooMany() throws Exception {
        WakeService service = new WakeService("127.0.0.1");
        assertThrows(IllegalArgumentException.class, () ->
                service.wake("00:11:22:33:44:55:66")
        );
    }

    @Test
    @DisplayName("wake rejects non-hex MAC characters")
    void wakeRejectsNonHexMac() throws Exception {
        WakeService service = new WakeService("127.0.0.1");
        assertThrows(NumberFormatException.class, () ->
                service.wake("00:11:22:33:44:ZZ")
        );
    }

    @Test
    @DisplayName("wake rejects empty MAC")
    void wakeRejectsEmptyMac() throws Exception {
        WakeService service = new WakeService("127.0.0.1");
        assertThrows(IllegalArgumentException.class, () ->
                service.wake("")
        );
    }

    @Test
    @DisplayName("wake handles MAC with mixed separators")
    void wakeMixedSeparators() throws Exception {
        WakeService service = new WakeService("127.0.0.1");
        // This might work depending on implementation - testing current behavior
        try {
            service.wake("00:11-22:33-44:55");
        } catch (IllegalArgumentException e) {
            // Also acceptable if mixed separators are rejected
            assertTrue(true);
        }
    }

    @Test
    @DisplayName("wake accepts leading zeros in MAC octets")
    void wakeWithLeadingZeros() throws Exception {
        WakeService service = new WakeService("127.0.0.1");
        service.wake("00:00:00:00:00:00");
        service.wake("01:02:03:04:05:06");
    }

    @Test
    @DisplayName("wake accepts uppercase hex")
    void wakeWithUppercaseHex() throws Exception {
        WakeService service = new WakeService("127.0.0.1");
        service.wake("FF:EE:DD:CC:BB:AA");
    }

    @Test
    @DisplayName("wake accepts lowercase hex")
    void wakeWithLowercaseHex() throws Exception {
        WakeService service = new WakeService("127.0.0.1");
        service.wake("ff:ee:dd:cc:bb:aa");
    }

    @Test
    @DisplayName("multiple wake calls succeed")
    void multipleWakeCalls() throws Exception {
        WakeService service = new WakeService("127.0.0.1");
        service.wake("00:11:22:33:44:55");
        service.wake("AA:BB:CC:DD:EE:FF");
        service.wake("11:22:33:44:55:66");
    }

    @Test
    @DisplayName("wake works with broadcast address")
    void wakeBroadcast() throws Exception {
        WakeService service = new WakeService("255.255.255.255");
        // Should work without throwing
        service.wake("00:11:22:33:44:55");
    }
}

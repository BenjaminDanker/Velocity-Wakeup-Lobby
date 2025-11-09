package com.silver.wakeup.wake;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Sends Wake-on-LAN (WoL) magic packets to wake servers from sleep.
 * 
 * <p>Constructs and broadcasts standard WoL magic packets containing the target
 * server's MAC address to the configured broadcast address and port 9 (Discard).
 */
public class WakeService {
    private final InetAddress broadcast;

    /**
     * Creates a WakeService for the given broadcast address.
     * 
     * @param broadcastIp the broadcast IP address (e.g., "192.168.1.255")
     * @throws UnknownHostException if the broadcast IP cannot be resolved
     */
    public WakeService(String broadcastIp) throws UnknownHostException {
        this.broadcast = InetAddress.getByName(broadcastIp); // e.g. 192.168.1.255
    }

    /**
     * Sends a Wake-on-LAN magic packet to wake the server with the given MAC address.
     * 
     * @param mac the target server's MAC address in colon or hyphen-separated format
     * @throws IOException if the packet cannot be sent
     * @throws IllegalArgumentException if the MAC address format is invalid
     */
    public void wake(String mac) throws IOException {
        byte[] macBytes = parseMac(mac);
        byte[] packet = new byte[6 + 16 * 6];
        Arrays.fill(packet, 0, 6, (byte) 0xFF);
        for (int i = 6; i < packet.length; i += macBytes.length) {
            System.arraycopy(macBytes, 0, packet, i, macBytes.length);
        }
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            DatagramPacket dp = new DatagramPacket(packet, packet.length, broadcast, 9);
            socket.send(dp);
        }
    }

    private static byte[] parseMac(String mac) {
        String[] hex = mac.split("[:-]");
        if (hex.length != 6) throw new IllegalArgumentException("Bad MAC: " + mac);
        byte[] bytes = new byte[6];
        for (int i = 0; i < 6; i++) bytes[i] = (byte) Integer.parseInt(hex[i], 16);
        return bytes;
    }
}

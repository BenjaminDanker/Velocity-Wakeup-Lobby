package com.silver.wakeup.config;

import java.util.Set;

/**
 * Starting backend servers when a player needs them and stopping them when nobody is on.
 *
 * @param enabled     off by default: the proxy then only sends the Wake-on-LAN packet, as before
 * @param sshTarget   user@host of the machine that runs the servers (it runs a restricted control script for this key)
 * @param sshKey      private key file used for that connection
 * @param idleMinutes how long a server may stay empty before it is stopped
 * @param servers     the servers managed this way (never the holding lobby)
 * @param keepRunning servers (a subset of {@code servers}) that are started whenever they are down and never stopped for being idle
 */
public record OnDemandSettings(boolean enabled, String sshTarget, String sshKey, long idleMinutes, Set<String> servers, Set<String> keepRunning) {
    public static final OnDemandSettings DISABLED = new OnDemandSettings(false, "", "", 10, Set.of(), Set.of());

    public OnDemandSettings {
        servers = Set.copyOf(servers);
        keepRunning = Set.copyOf(keepRunning);
    }

    public OnDemandSettings(boolean enabled, String sshTarget, String sshKey, long idleMinutes, Set<String> servers) {
        this(enabled, sshTarget, sshKey, idleMinutes, servers, Set.of());
    }

    public boolean keeps(String server) {
        return manages(server) && keepRunning.contains(server);
    }

    public boolean manages(String server) {
        return enabled && servers.contains(server);
    }
}

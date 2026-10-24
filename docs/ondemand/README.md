# On-demand servers

The proxy starts a backend server when a player needs it and stops it when it has been empty for a while, so idle servers hold no memory.

How it works
- A player who has to wait for an offline server (fresh login, a portal, coming back from another server) makes the proxy *want* that server.
  Wanting renews every second while they wait. If the server is not running, the proxy sends the Wake-on-LAN packet (the machine may be
  asleep) and runs `ssh <ssh_target> "start <server>"`, retrying every 10 seconds until the server answers pings.
- A running server with no players, and nobody waiting for it, for `idle_minutes` is stopped with `ssh <ssh_target> "stop <server>"`.
- The ssh key is restricted on the server with `restrict,from="<proxy ip>",command="/home/silver/mc-servers/bin/mc-ctl"` in `authorized_keys`, so
  it can only start, stop or query the eight listed servers.
- `plugins/wakeuplobby/start-<server>` (an empty file) asks for one start without a player; the proxy deletes it.

Server side (user systemd units, linger on, none of them enabled at boot): `mc-ctl`, `mc-stop-wait` and `example-server.service` here.
Units use `Restart=on-failure`, so only a crash restarts a server; `mc-stop-wait` stops the server gracefully and waits for it to save.

Config (config.yml):

    on_demand:
      enabled: true
      ssh_target: "silver@primary-ram.local"
      ssh_key: "/home/silver/.ssh/mc_ondemand"
      idle_minutes: 5
      servers: [vanilla, magic, tech, magitech, ocean, cave, sky-island, desert]
      keep_running: [vanilla]   # optional: started whenever down, never idle-stopped

`enabled: false` (or no section) keeps the old behaviour: the proxy only sends the Wake-on-LAN packet.

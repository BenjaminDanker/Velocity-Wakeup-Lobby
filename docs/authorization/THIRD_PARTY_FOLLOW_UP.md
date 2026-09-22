# Legacy bridge and OpenPAC notes

The dedicated authorization transport remains independent. ProxyCommand is still deployed:
its Fabric artifact is present on each of the six primary backends and the Fabric
waiting_lobby; the Velocity artifact remains under /home/silver/velocity/plugins.

The inspected Fabric command is /proxycommand <command>. It requires vanilla permission
level 2, rejects non-player sources, then sends an arbitrary command string over
proxycommand:command_packet. Velocity executes the command in the target player's context.
The bridge has no authenticated server/source binding, signature, nonce, timestamp, version,
size/rate limit, or robust framing. It is not used by silverauth:sync_v1 and does not grant
OP by itself.

WakeUpLobby now provides its own /server command behind centralized wakeuplobby.server,
so the bridge is not needed for that narrow action. No first-party source callsites for
/proxycommand were found, but its arbitrary-command interface may have manual/external users.
It remains deployed and unchanged to avoid silently removing an unverified workflow. If removed
later, use the existing structured authenticated transport for a narrowly typed action such as
SWITCH_SERVER(targetServer); do not reuse this bridge's channel or portal signing material.

All six primary backend configs set OpenPAC's permissionSystem = "prometheus". The
configuration comments describe this as an external permission provider registered through an
addon; no provider jar was identifiable by filename in the active mod directories. OpenPAC
and party/claim ownership remain third-party follow-up; central staff roles do not replace
claim ownership or party membership.

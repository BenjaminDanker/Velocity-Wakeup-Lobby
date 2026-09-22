# Production deployment map

The authorization changes are built, but migrated jars have not been installed on live servers.
The live V005 migration will run automatically when the updated WakeUpLobby jar starts; do not
apply the SQL by hand. The seven backend credential pairs are already provisioned in the
Velocity key file and matching Fabric config files. The existing magic pair was preserved.

## Built artifacts

Use the following remapped production jars (not source jars):

- Velocity authority and /auth: wake-up-lobby-1.0.0.jar
- Required Fabric runtime on every backend receiving migrated mods:
  network-authorization-0.1.0-SNAPSHOT.jar
- Pet Companion: pet-companion-0.1.0-SNAPSHOT.jar
- Portals: server-portals-0+26.2.jar
- MPDS: mpds-26.2-3.0.3.jar
- Spawn Protect: spawn-protect-1.0.0.jar
- Disable Dimensions: disable-dimensions-1.0.0.jar
- Atlantis: Atlantis-1.0.0.jar
- Sky Islands: Sky-Islands-0.1.0.jar
- Ender Fight: enderfight-1.0.0.jar
- Villager Interface: villager-interface-1.0.0.jar
- Border Lock: border-lock-1.0.0.jar

The Network Authorization jar embeds authorization-common. Migrated mod jars compile against the
module and declare it as a Fabric dependency; install the runtime jar alongside them.

## Backend placement

Install Network Authorization first (or in the same stopped-server file update) and replace only
mods already present on that backend:

| Canonical ID | Backend directory | Migrated jars |
| --- | --- | --- |
| vanilla1 | /home/silver/vanilla-mc-server | MPDS, Pet Companion, Server Portals, Spawn Protect, Villager Interface |
| sky-island | /mnt/mc-fast/sky-island-mc-server | Disable Dimensions, MPDS, Pet Companion, Server Portals, Sky Islands, Spawn Protect |
| ocean | /mnt/mc-storage/ocean-mc-server | Atlantis, Disable Dimensions, MPDS, Pet Companion, Server Portals, Spawn Protect |
| desert | /mnt/mc-fast/desert-mc-server | Disable Dimensions, MPDS, Pet Companion, Server Portals, Spawn Protect |
| cave | /mnt/mc-storage/cave-mc-server | Disable Dimensions, MPDS, Pet Companion, Server Portals, Spawn Protect |
| magic | /home/silver/magic-mc-server | Network Authorization, MPDS, Pet Companion, Server Portals, Spawn Protect |
| waiting_lobby | /home/silver/mc_waiting_lobby on the Pi | MPDS, Border Lock |

Ender Fight is built but was not present on an active backend during inventory. Deploy it only where
that gameplay mod is intended. ProxyCommand and OpenPAC are intentionally not changed.

## Rollout

1. Take the normal pre-deployment database and jar backups. Stop Velocity, replace its
   wake-up-lobby-1.0.0.jar, then start Velocity. The startup applies V005, retains the current
   OWNER assignment, and loads the seven configured key entries. Confirm startup reports an
   active global OWNER and the new authorization revision before proceeding.
2. Stop each backend one at a time. Install/update Network Authorization and the listed migrated
   jars as a unit, preserving all current world/config/OP files. Start it and confirm
   network_authorization reports the expected canonical server ID and receives a signed
   snapshot. A missing snapshot denies only the newly centralized checks; it never falls back to
   vanilla OP.
3. Verify /auth role list, then inspect the baseline and relevant capabilities, for example:
   /auth explain <uuid> aipets.use vanilla1 and /auth explain <uuid> portal.use vanilla1.
   Manage users by UUID where possible: /auth user <uuid> role add ADMIN. Default PLAYER is
   data-driven and additive, so staff retains normal gameplay capabilities without a Java role
   hierarchy.
4. Keep ops.json, velocity-ops.json, and ProxyCommand unchanged. OWNER can remain vanilla OP
   for compatibility; ADMIN/MODERATOR do not receive OP automatically.

## /auth command reference

- /auth user <player|uuid> info
- /auth user <player|uuid> role add|remove <role> [server]
- /auth user <player|uuid> permission allow|deny|remove <node> [server]
- /auth role list, /auth role info <role>, /auth role create <role> [priority],
  /auth role delete <role>, /auth role priority <role> <priority>,
  /auth role default <role> on|off
- /auth role permission allow|deny|remove <role> <node> [server]
- /auth explain <player|uuid> <permission> [server]

Omitting [server] means GLOBAL. Console is a separately audited break-glass actor. Player
management requires the relevant staff.roles.modify or staff.permissions.modify capability;
role management priority protects higher-authority roles. The last active global OWNER with
recovery authority cannot be removed or disabled.

## Rollback

Before each replacement, retain a copy of that server's current jar under a task-specific release
rollback directory. If a backend fails to start, stop it and restore its previous migrated-mod
jars as a group; do not leave a newly central-gated mod installed without Network Authorization.
Restart and verify gameplay. If the new Velocity jar must be reverted, restore its previous jar
and restart Velocity. Leave the new auth_* tables and provisioned key files in place: old jars
ignore them, and deleting the tables is not part of rollback. Keep all ops.json and
velocity-ops.json files untouched.

# Velocity-Wakeup-Lobby

[![Latest Release](https://img.shields.io/github/v/release/BenjaminDanker/Velocity-Wakeup-Lobby?style=flat-square)](https://github.com/BenjaminDanker/Velocity-Wakeup-Lobby/releases)

A Velocity proxy plugin for sophisticated lobby management, player state tracking, and server-to-server handoffs with portal integration.

## Features

- **Player State Management** - Track player session state across servers with sticky routing
- **Lobby System** - Route players through a configurable holding/lobby server during transitions
- **Portal Integration** - Support for ServerPortals-style handoff mechanisms with token verification
- **Graceful Transitions** - Configurable grace periods and fallback policies for server unavailability
- **Wake Service** - Keep servers alive with configurable ping intervals
- **Admin Commands** - Portal control commands for administrative operations
- **Flexible Routing** - Group-based routing with configurable fallback policies

## Demo

![Portal handoff in action](assets/Velocity-Wakeup-Lobby-Demo.gif)

## Requirements

- Java 21+
- Velocity Proxy 3.4.0 or later
- MCServerPortals mod (forked version) on each game server
- customportalapi (forked version) on each game server

## Building

```bash
./gradlew build
```

The compiled plugin JAR will be in `build/libs/wake-up-lobby.jar`.

## Installation

1. Place `wake-up-lobby.jar` in your Velocity proxy's `plugins/` directory
2. Start/restart your Velocity proxy - a default configuration file will be generated at `plugins/wakeuplobby/lobby-config.yml`
3. Edit the configuration file to match your setup (see Configuration below)
4. Run `/wakeuplobby reload` to apply changes without restarting
5. Configure per-portal secrets if using portal authentication

### Ecosystem Setup

This plugin is part of a **three-project ecosystem** for multi-server portals:

1. **customportalapi** (forked) - Core portal mechanics library  
   Repository: [github.com/BenjaminDanker/customportalapi](https://github.com/BenjaminDanker/customportalapi)
   
2. **MCServerPortals** (forked) - Fabric mod installed on each game server  
   Repository: [github.com/BenjaminDanker/MCServerPortals](https://github.com/BenjaminDanker/MCServerPortals)
   - customportalapi resides locally in `libs/` directory
   
3. **Velocity-Wakeup-Lobby** (this plugin) - Velocity proxy plugin for secure handoffs  
   Repository: [github.com/BenjaminDanker/Velocity-Wakeup-Lobby](https://github.com/BenjaminDanker/Velocity-Wakeup-Lobby)

**Installation sequence:**
1. Install MCServerPortals + customportalapi on each game server
2. Install Velocity-Wakeup-Lobby on your Velocity proxy
3. Configure portal destinations and authentication secrets in this plugin's config
4. Configure portals on each server using MCServerPortals commands

See the [MCServerPortals README](https://github.com/BenjaminDanker/MCServerPortals) for detailed architecture and system flow.

## Configuration

Create `plugins/wakeuplobby/lobby-config.yml`:

```yaml
# IP address to broadcast for wake pings
broadcast_ip: "192.168.1.100"

# Server to hold players in during transitions
holding_server: "lobby"

# Grace period (seconds) before forcibly disconnecting players from holding server
grace_sec: 300

# Ping interval (seconds) to keep servers alive
ping_every_sec: 30

# Fallback policy when primary server is unavailable: LOBBY or WAIT
fallback_policy: "LOBBY"

# Map server names to MAC addresses for wake-on-LAN
server_to_mac:
  primary_server: "00:11:22:33:44:55"
  backup_server: "AA:BB:CC:DD:EE:FF"

# Group-based server routing
groups:
  survival:
    - primary_server
    - backup_server
  creative:
    - creative_server

# Admin player names (for command access)
admin_names:
  - "AdminName"
  - "ModeratorName"

# Portal authentication
global_portal_secret: "shared-secret-key"
per_portal_secrets:
  "portal-name": "portal-specific-secret"
```

## Usage

### Commands

**Admin Commands:**
- `/wakeuplobby reload` - Reload plugin configuration
- `/wl portal <target> <token> [sourcePortal]` - Handle portal handoff with authentication
- `/server [name]` - Connect to a server

**Allowed for Non-Ops:**
- `/w <player> <message>` - Send a direct message
- `/msg <player> <message>` - Send a direct message
- `/teammsg <message>` - Send a team message

### Portal Handoff

Portal handoff is initiated by other plugins/servers and verified using cryptographic tokens. The plugin validates tokens against configured secrets and handles secure player transfers between servers.

## Architecture

### Key Components

- **VelocityPlugin** - Main plugin entry point and event handlers
- **RuntimeState** - Container for runtime services and configuration
- **PlayerStateStore** - Manages player state across lobby/server transitions
- **StickyRouter** - Routes players based on group membership and fallback policies
- **PortalHandoffService** - Manages secure player handoffs to other servers
- **PortalTokenVerifier** - Verifies portal authentication tokens
- **WakeService** - Handles wake-on-LAN and server pinging

## Testing

Run the test suite:

```bash
./gradlew test
```

Test coverage includes:
- Portal token verification
- Player state transitions
- Payload codec serialization
- Configuration loading
- Portal command handling

## License

[See LICENSE file](LICENSE)

## Contributing

Contributions are welcome. Please ensure all tests pass before submitting PRs.

```bash
./gradlew test
```

## Support

For issues or questions, please open an issue on GitHub.

# LimitedOfflineMode

A Bukkit plugin that lets specific usernames join an **online-mode server without Mojang authentication** — no proxy, no server mode switch required.

---

## Compatibility

| Server | Status |
|--------|--------|
| Paper 1.21.x | ✅ Supported (native API) |
| Spigot 1.21.x | ✅ Supported (reflection injection) |
| Purpur | ✅ Supported |
| Folia | ✅ Supported |
| Mohist / Magma | ✅ Supported |

Requires **Java 21+**.

---

## How It Works

The plugin injects a Netty handler into every new connection pipeline. When a whitelisted username sends their login packet, the plugin intercepts and cancels the encryption handshake, then calls the server's internal post-auth method with an offline profile — skipping Mojang auth entirely while keeping all Bukkit login events intact.

On Paper the injection uses `ChannelInitializeListenerHolder`. On Spigot and other Bukkit-family servers it falls back to reflection-based injection via `ServerConnection`.

---

## Installation

1. Download the plugin JAR
2. Place it in your server's `plugins/` folder
3. Start the server — config files are generated automatically
4. Add usernames to `plugins/LimitedOfflineMode/allowed-users.txt`
5. Restart or run `/lomgroup group reload`

---

## Configuration

### `allowed-users.txt`

Simple list — one username per line. Survives server restarts.

```
# Add usernames (one per line) that may join in offline mode
Steve
Alex
```

### `player-groups.txt`

Group players together and toggle entire groups on/off without editing the file.

```
# Format: groupName|enabled|player1,player2
admins|true|Steve,Alex
testers|false|TestUser
```

---

## Commands

All commands require the `limitedofflinemode.admin` permission (default: op).

| Command | Description |
|---------|-------------|
| `/lomgroup group add <group> <player1,player2,...>` | Add players to a group |
| `/lomgroup group enable <group>` | Enable a group |
| `/lomgroup group disable <group>` | Disable a group |
| `/lomgroup group toggle <group>` | Toggle a group on/off |
| `/lomgroup group list` | List all groups and their state |
| `/lomgroup group reload` | Reload config from disk without restart |

---

## Metrics

Anonymous usage stats via bStats. Can be disabled in `plugins/bStats/config.yml` on your server.

---

## ⚠️ Disclaimer

This plugin is intended for server administrators and developers — for maintenance, testing, and emergency access.
Do not use it to allow cracked or pirated clients, or to bypass Mojang auth for regular players.
You are fully responsible for how you use this plugin and for any EULA implications.

Provided **as is**, without warranty.

---

[GitHub](https://github.com/chank-op)

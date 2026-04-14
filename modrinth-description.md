# LimitedOfflineMode

**Let specific players join your online-mode Paper server without Mojang authentication — no proxy, no config changes, no server restarts required.**

Perfect for server admins, developers, and staff who need emergency or testing access without exposing the entire server to offline mode.

---

## Features

- **Whitelist by username** — add names to a simple text file, done
- **Player groups** — batch players into named groups and toggle them on/off with a command
- **Persists across restarts** — config is file-based, nothing is lost on reboot
- **Live reload** — `/lomgroup group reload` applies changes instantly, no restart needed
- **No proxy needed** — works directly on Paper, no Velocity or BungeeCord required
- **Full login event support** — fires `AsyncPlayerPreLoginEvent` and all standard Bukkit events normally

---

## Quick Start

1. Drop the JAR into your `plugins/` folder
2. Start the server — `allowed-users.txt` is created automatically
3. Add usernames to `plugins/LimitedOfflineMode/allowed-users.txt`
4. Players on the list can now join without Mojang auth

---

## Commands

| Command | Description |
|---------|-------------|
| `/lomgroup group add <group> <players>` | Add players to a group |
| `/lomgroup group enable <group>` | Allow the whole group to join |
| `/lomgroup group disable <group>` | Block the group |
| `/lomgroup group toggle <group>` | Flip a group on/off |
| `/lomgroup group list` | See all groups and their status |
| `/lomgroup group reload` | Reload config files live |

Permission: `limitedofflinemode.admin` (default: op)

---

## Requirements

- Paper 1.21.x
- Java 21+

---

> **Forked from** [moritxius-matsuda/LimitedOfflineMode](https://github.com/moritxius-matsuda/LimitedOfflineMode) (original Velocity/BungeeCord plugin).
> This is a full rewrite for Paper by [chank_op](https://github.com/chank-op).

---

⚠️ **For administrators and developers only.** Do not use to allow cracked clients or bypass Mojang auth for regular players. You are responsible for your own EULA compliance.

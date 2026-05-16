# Newspaper

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Paper](https://img.shields.io/badge/Paper-1.20--1.21.1-blue)](https://papermc.io)

A Paper plugin that enables remote server management via WebSocket with CHAP-IEM encryption from [ZIM (Zigzag Interaction Model)](https://github.com/XSY-HYH/ZIM-Zigzag-Interaction-Model).

## Overview

Newspaper provides a secure WebSocket channel for controlling your Minecraft server remotely. All communication is encrypted using the CHAP-IEM protocol, which provides forward secrecy through automatic key rotation after every operation.

> **Encryption**: The CHAP-IEM (Chain Hash Authentication Protocol - ID Encryption Mode) implementation is sourced from the [ZIM-Zigzag-Interaction-Model](https://github.com/XSY-HYH/ZIM-Zigzag-Interaction-Model) project. See that repository for the full protocol specification.

## Features

- **Secure Communication** — CHAP-IEM encryption with per-operation key rotation and forward secrecy
- **12 Operation Types** — Full remote server management capabilities
- **Version Compatibility** — Supports Paper 1.20.x through 1.21.1 via an abstraction layer
- **Internationalization** — Built-in English (`en`) and Chinese (`zh`) translations
- **IPv6 Support** — Configurable dual-stack networking
- **Zero External Dependencies** — Pure Java WebSocket implementation

## Configuration

```yaml
# plugins/Newspaper/config.yml
port: 8080          # WebSocket server port
username: "admin"   # Login username
password: "newspaper" # Login password
ipv6: false         # Enable IPv6 support
language: "en"      # UI language (en / zh)
```

## Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/newspaper reload` | `newspaper.admin` | Reload configuration and restart WebSocket server |

## WebSocket API

### Connection

Connect to `ws://<server-ip>:<port>/` using any WebSocket client. All data frames must be **binary** (opcode `0x2`).

### Authentication

Newspaper uses the CHAP-IEM protocol for authentication and encryption. The encryption implementation is sourced from [ZIM-Zigzag-Interaction-Model](https://github.com/XSY-HYH/ZIM-Zigzag-Interaction-Model). Refer to that project for the complete protocol specification.

In short:
1. Client derives an AES-256 key from the configured password
2. Client sends an encrypted login request containing the username
3. Server verifies and returns a session ID used as the encryption key for subsequent operations
4. The encryption key rotates with every operation, providing forward secrecy

### Operation Types

All operation requests use the following JSON format:

```json
{
  "type": "<operation_type>",
  "data": { ... }
}
```

#### 1. `console_message` — Console Output

Subscribe to server console log output.

```json
{ "type": "console_message", "data": { "action": "subscribe" } }
{ "type": "console_message", "data": { "action": "poll" } }
{ "type": "console_message", "data": { "action": "unsubscribe" } }
```

#### 2. `chat_message` — Chat Messages

Subscribe to in-game chat messages.

```json
{ "type": "chat_message", "data": { "action": "subscribe" } }
{ "type": "chat_message", "data": { "action": "poll" } }
{ "type": "chat_message", "data": { "action": "unsubscribe" } }
```

#### 3. `command` — Execute Console Command

Execute a command as the console sender.

```json
{ "type": "command", "data": { "command": "say Hello World" } }
```

#### 4. `command2` — Execute Command as Player

Execute a command on behalf of a specific player.

```json
{ "type": "command2", "data": { "player": "Steve", "command": "spawn" } }
```

#### 5. `player_join` — Player Join Events

Subscribe to player join events.

```json
{ "type": "player_join", "data": { "action": "subscribe" } }
{ "type": "player_join", "data": { "action": "poll" } }
{ "type": "player_join", "data": { "action": "unsubscribe" } }
```

#### 6. `player_quit` — Player Quit Events

Subscribe to player quit events.

```json
{ "type": "player_quit", "data": { "action": "subscribe" } }
{ "type": "player_quit", "data": { "action": "poll" } }
{ "type": "player_quit", "data": { "action": "unsubscribe" } }
```

#### 7. `online_players` — Online Player List

Get the current list of online players with details.

```json
{ "type": "online_players", "data": {} }
```

Response includes: player name, display name, UUID, world, gamemode, ping, and IP address.

#### 8. `server_info` — Server Information

Get comprehensive server information.

```json
{ "type": "server_info", "data": {} }
```

Response includes:
- **Server**: version, name, Bukkit version, Minecraft version, API version
- **Java**: version, vendor, home, OS name/arch/version, available processors
- **Memory**: used/total/max/free (bytes and MB)
- **World**: world count, online players, max players

#### 9. `config_modify` — Modify Configuration

Update Newspaper configuration values.

```json
{
  "type": "config_modify",
  "data": {
    "port": 9090,
    "ipv6": true,
    "language": "zh"
  }
}
```

Supported fields: `port`, `username`, `password`, `ipv6`, `language`.

> **Note**: Changes require a `config_reload` to take effect.

#### 10. `config_reload` — Reload Configuration

Reload the configuration and restart the WebSocket server with new settings.

```json
{ "type": "config_reload", "data": {} }
```

#### 11. `shutdown` — Shutdown Server

Shut down the Minecraft server.

```json
{ "type": "shutdown", "data": { "reason": "Scheduled maintenance" } }
```

#### 12. `console_broadcast` — Broadcast as Console

Send a broadcast message to all players (supports `&` color codes).

```json
{ "type": "console_broadcast", "data": { "message": "&aServer will restart in 5 minutes!" } }
```
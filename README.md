# Newspaper

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Paper](https://img.shields.io/badge/Paper-1.20--1.21.1-blue)](https://papermc.io)

A Paper plugin that enables remote server management via WebSocket with configurable encryption.

## Encryption Modes

Newspaper supports multiple encryption protocols so you can choose the one you trust. If you don't want to use the default CHAP-IEM protocol, you can switch to TLS or SSH — standard, well-audited protocols that are widely trusted in the industry.

| Mode | Config Value | Transport | Description |
|------|-------------|-----------|-------------|
| **CHAP-IEM** | `chap-iem` | `ws://` | Default. Application-layer encryption with per-operation key rotation and forward secrecy. Implementation from [ZIM (Zigzag Interaction Model)](https://github.com/XSY-HYH/ZIM-Zigzag-Interaction-Model). |
| **TLS** | `tls` | `wss://` | Standard TLS 1.2/1.3 encryption at the transport layer. Auto-generates a self-signed certificate on first start. Clients connect via `wss://`. |
| **SSH** | `ssh` | `ws://` | SSH-style RSA key exchange with signature verification, then AES-256-GCM for session encryption. Provides public-key authentication similar to OpenSSH. |

To change the encryption mode, set the `encryption` field in `config.yml`:

```yaml
encryption: "chap-iem"  # Options: chap-iem, tls, ssh,
```

> **Note**: Changing the encryption mode requires a config reload (`/newspaper reload`) to take effect. TLS mode generates a `keystore.jks` file in the plugin directory on first start.

## Features

- **Flexible Encryption** — Choose between CHAP-IEM, TLS, or SSH encryption protocols
- **12 Operation Types** — Full remote server management capabilities
- **Version Compatibility** — Supports Paper 1.20.x through 1.21.1 via an abstraction layer
- **Internationalization** — Built-in English (`en`) and Chinese (`zh`) translations
- **IPv6 Support** — Configurable dual-stack networking
- **Zero External Dependencies** — Pure Java WebSocket implementation

## Configuration

```yaml
# plugins/Newspaper/config.yml
port: 8080              # WebSocket server port
username: "admin"       # Login username
password: "newspaper"   # Login password
ipv6: false             # Enable IPv6 support
language: "en"          # UI language (en / zh)
encryption: "chap-iem"  # Encryption mode (chap-iem / tls / ssh)
```

## Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/newspaper reload` | `newspaper.admin` | Reload configuration and restart WebSocket server |

## WebSocket API

### Connection

Connect using the appropriate protocol based on your encryption mode:
- **CHAP-IEM / SSH**: `ws://<server-ip>:<port>/`
- **TLS**: `wss://<server-ip>:<port>/`

All data frames must be **binary** (opcode `0x2`).

### Authentication

All encryption modes require authentication. The exact flow depends on the selected mode:

- **CHAP-IEM**: The CHAP-IEM protocol handles both encryption and authentication. See [ZIM-Zigzag-Interaction-Model](https://github.com/XSY-HYH/ZIM-Zigzag-Interaction-Model) for the full specification.
- **TLS**: Transport is encrypted by TLS. Application-layer authentication uses AES-256-GCM with the configured password as a pre-shared key, plus per-operation key rotation.
- **SSH**: RSA key exchange with signature verification, followed by username/password authentication encrypted with the derived session key.

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

Supported fields: `port`, `username`, `password`, `ipv6`, `language`, `encryption`.

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

## Building

```bash
./gradlew build
```

The output JAR is located at `build/libs/Newspaper-1.1.1.jar`.

## Project Structure

```
src/main/java/com/newspaper/
├── NewspaperPlugin.java          # Plugin entry point
├── abstraction/                  # Version compatibility layer
│   ├── PlatformAbstraction.java  # Unified API interface
│   ├── Paper120.java             # Paper 1.20.x adapter
│   └── Paper121.java             # Paper 1.21.x adapter
├── chap/                         # CHAP-IEM protocol
│   ├── Chapiem.java              # Protocol state machine
│   ├── ChapiemSession.java       # Session state management
│   └── CryptoUtil.java           # AES-256-GCM encryption utilities
├── config/                       # Configuration management
│   ├── ConfigManager.java        # Load/save/reload config
│   └── NewspaperConfig.java      # Config data holder
├── encryption/                   # Encryption provider abstraction
│   ├── EncryptionMode.java       # Enum: chap-iem, tls, ssh
│   ├── EncryptionProvider.java   # Provider interface
│   ├── ChapIemProvider.java      # CHAP-IEM implementation
│   ├── TlsProvider.java          # TLS (wss://) implementation
│   ├── SshProvider.java          # SSH key exchange implementation
│   └── BouncyCastleCertGenerator.java  # Self-signed cert via keytool
├── handler/                      # Operation handlers (12 types)
├── i18n/                         # Internationalization
│   ├── I18nManager.java          # Language loader & validator
│   └── MessageKey.java           # Translation key constants
└── ws/                           # WebSocket server
    ├── NewspaperWebSocketServer.java  # Server lifecycle
    ├── WsSession.java            # Per-client session handler
    ├── WsFrame.java              # RFC 6455 frame parser/builder
    └── MessageDispatcher.java    # Operation routing
```

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.

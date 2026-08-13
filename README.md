# Newspaper

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Paper](https://img.shields.io/badge/Paper-1.21.1--26.2-blue)](https://papermc.io)
[![Fabric](https://img.shields.io/badge/Fabric-1.21.1--26.2-orange)](https://fabricmc.net)

A cross-platform Minecraft plugin/mod for remote server management via WebSocket with mutual TLS authentication.

[中文文档](docs/README.zh.md)

## Features

- **Mutual TLS (mTLS)** — Password-derived certificate generation, zero manual cert exchange
- **Dual Platform** — Paper (1.21.1–26.2) and Fabric (1.21.1–26.2) from a shared codebase
- **Shell Command Execution** — Run system shell commands remotely with configurable timeout
- **File Transfer** — Upload and download files via Base64 over WebSocket
- **Reverse Proxy** — Outbound connection mode for servers behind NAT/firewall
- **PROXY Protocol** — Optional v1/v2 support for client IP detection behind reverse proxies
- **Audit Logging** — Tamper-proof audit log with continuous file locking
- **Password Security** — Auto-generated 128-bit keys with strength validation
- **Internationalization** — Built-in English (`en`) and Chinese (`zh`) translations
- **Config Fault Tolerance** — Invalid config entries are automatically reset to defaults with warnings

## Version Support

| Platform | Module | Minecraft Versions | Java |
|----------|--------|--------------------|------|
| Paper | `paper` | 1.21.1–26.2 | 21+ |
| Fabric | `fabric` | 26.1–26.2 | 25 |
| Fabric (Legacy) | `fabric-legacy` | 1.21.1–1.21.11 | 21 |

> **Note:** Fabric uses two separate modules due to a breaking change in Minecraft 26.1 (Mojang removed class obfuscation, making Yarn intermediary mappings unavailable for 26.1+).

## Alpha Version Notice

When you see a version tagged **Alpha** (e.g. `v1.0.0-alpha.1`), it is likely a release with known issues that has not been pulled. Alpha versions may contain unfinished features, known crash risks, or performance problems, and are primarily intended for early testing and feedback collection.

## Authentication

Newspaper uses pure mTLS. Both server and client derive identical CA/server/client certificates from the shared password using BouncyCastle (secp256r1 + SHA-256). The TLS handshake performs mutual authentication — no application-layer auth messages needed.

If no password is configured, a 128-bit key is auto-generated, validated against security rules (uppercase, lowercase, digits), and printed to the console.

## Connection Modes

| Mode | Config Value | Description |
|------|-------------|-------------|
| **Direct** | `direct` | Server listens for incoming mTLS connections |
| **Reverse Proxy** | `reverse` | Server connects outward to a remote relay, retries every 5 min |

```yaml
connection-mode: "reverse"
reverse-proxy:
  host: "relay.example.com"
  port: 8080
  protocol: "wss"
```

## Configuration

```yaml
port: 8080
password: ""
ipv6: false
language: "en"
connection-mode: "direct"
enforce-password-strength: true
proxy-protocol: false
reverse-proxy:
  host: ""
  port: 8080
  protocol: "wss"
shell:
  timeout: 30
file-transfer:
  enabled: true
  root: ""
  restrict-upload: true
  disable: false
audit-log:
  enabled: true
```

Invalid config values are automatically reset to defaults with a `WARN` log. Supported validation rules:

| Key | Rule |
|-----|------|
| `port` | 1–65535 |
| `connection-mode` | `direct` or `reverse` |
| `reverse-proxy.protocol` | `wss` or `ws` |
| `shell.timeout` | >= 1 |

## Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/newspaper reload` | OP | Reload config and restart WebSocket server |
| `/newspaper modify <key> <value>` | OP | Modify a config field (e.g. `port 9090`) |
| `/newspaper exec <command>` | OP | Execute a system shell command |
| `/newspaper lang <file>` | OP | Load a language file (e.g. `en`, `zh`) |

## WebSocket API

### Connection

Connect via `wss://<host>:<port>/` (mTLS required). All data frames must be **binary** (opcode `0x2`).

### Request Format

```json
{
  "type": "<operation_type>",
  "data": { ... }
}
```

### Operation Types

| Type | Description |
|------|-------------|
| `console_message` | Subscribe/poll/unsubscribe console output |
| `chat_message` | Subscribe/poll/unsubscribe chat messages |
| `command` | Execute a console command |
| `command2` | Execute a command as a specific player |
| `player_join` | Subscribe/poll/unsubscribe player join events |
| `player_quit` | Subscribe/poll/unsubscribe player quit events |
| `online_players` | Get online player list with details |
| `server_info` | Get server, Java, and memory info |
| `config_modify` | Modify configuration fields |
| `config_reload` | Reload configuration and restart WebSocket |
| `shutdown` | Shut down the Minecraft server |
| `console_broadcast` | Broadcast a message to all players |
| `shell_command` | Execute a system shell command |
| `file_upload` | Upload a file (Base64 content) |
| `file_download` | Download a file (returns Base64 content) |

### Examples

**Shell command:**
```json
{ "type": "shell_command", "data": { "command": "ls -la", "timeout": 10 } }
```

**File upload:**
```json
{ "type": "file_upload", "data": { "path": "backup/world.yml", "content": "<base64>" } }
```

**File download:**
```json
{ "type": "file_download", "data": { "path": "config.yml" } }
```

## Security

- **Audit Log** — Records WSS connection IDs, login attempts (success/failure), remote addresses, and operation details. The log file (`audit.log`) is continuously locked during server runtime to prevent tampering. File transfer operations targeting `audit.log` are blocked.
- **File Transfer Security** — Path traversal detection, optional upload directory restriction (server root by default), and global file transfer disable toggle.
- **Password Strength** — Enforced by default. Disabling it triggers a red warning. Auto-generated keys meet complexity requirements (uppercase, lowercase, 3+ alphabetical, 6+ digits).

## License

MIT — see [LICENSE](LICENSE).

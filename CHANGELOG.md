# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/).

**[中文更新日志](docs/CHANGELOG.zh.md)**

## [Unreleased]

### Added
- Config fault tolerance: invalid entries automatically reset to defaults with WARN logs
- `/newspaper lang <file>` command: switch language files at runtime
- Alpha version notice (README.md / docs/README.zh.md)

## [1.0.0-alpha.1] - 2026-08-14

### Added
- Multi-module architecture: `common` (shared logic), `paper` (Paper 1.21.1–26.2), `fabric` (Fabric 26.1–26.2), `fabric-legacy` (Fabric 1.21.1–1.21.11)
- Pure mTLS authentication: password-derived certificates (BouncyCastle secp256r1 + SHA-256), no manual cert exchange
- Fabric dual-module support: 26.1+ uses Mojang official mappings, 1.21.1–1.21.11 uses Yarn mappings
- Shell command execution: remote system commands with timeout and forced termination
- File transfer: Base64-encoded upload/download with path traversal detection
- Reverse proxy mode: outbound connection to remote relay, auto-retry every 5 min
- PROXY protocol support: optional v1 (text) / v2 (binary) parsing for client IP detection behind reverse proxies
- Audit logging: records WSS connection IDs, login attempts, remote addresses, and operation details with continuous file locking
- Password security: auto-generated 128-bit keys, strength validation (uppercase, lowercase, 3+ alphabetical, 6+ digits), Passay library validation
- Config fault tolerance: invalid values automatically reset to defaults
- Internationalization: built-in English and Chinese translations, runtime switching via `/newspaper lang`
- C# client (NewspaperRemote): mTLS-compatible with file upload/download
- Chinese documentation (docs/README.zh.md)
- 15 WebSocket operation types

### Changed
- Restructured into multi-project Gradle layout, common module fully decoupled from Bukkit
- Authentication switched from legacy SSH/TLS to pure mTLS
- Config schema reorganized with new fields: `enforce-password-strength`, `proxy-protocol`, `audit-log`
- `restrict-upload` default changed from `false` to `true`
- Paper module uses 1.21.1-R0.1-SNAPSHOT (to be updated when Paper 26.2 API is released)

### Removed
- Legacy single-module `src/` directory
- Legacy SSH encryption mode
- Legacy Chapiem authentication system

## [0.3.0] - 2026-08-12

### Added
- Reverse proxy connection mode
- Encryption mode support (SSH/TLS)

### Fixed
- Thread scheduling issue for command execution

## [0.2.0] - 2026-08-10

### Added
- Encryption support
- WebSocket communication framework

## [0.1.0] - 2026-08-08

### Added
- Project initialization
- Basic WebSocket server
- Console command execution
- Player event listening
- Logo

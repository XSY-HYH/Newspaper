# Changelog | 更新日志

All notable changes to this project are documented in this file.
本文件记录 Newspaper 项目的所有重要变更。

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/).
格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

---

## [Unreleased] — 未发布

## [2.1.0] - 2026-08-14

### Added | 新增
- Chunked file transfer protocol: replaces single-packet Base64 with multi-packet chunked transfer (start -> meta -> chunks -> end), each chunk default 64KB, SHA-256 verification
- 切片文件传输协议：用多包切片传输替换单包 Base64（start -> meta -> chunks -> end），每片默认 64KB，SHA-256 校验
- Multi-language SDK (`sdk/`): pure API encapsulation for Python (`NewspaperSDK`), C# (`NewspaperSDK` class library), and Node.js (`NewspaperClient` / `NewspaperServer`), all with mTLS + WebSocket + chunked file transfer
- 多语言 SDK（`sdk/`）：Python（`NewspaperSDK`）、C#（`NewspaperSDK` 类库）、Node.js（`NewspaperClient` / `NewspaperServer`）纯 API 封装，均含 mTLS + WebSocket + 切片文件传输
- Shell command encoding parameter: clients can specify output encoding (e.g. `gbk` for Windows `cmd.exe`) to fix garbled text
- Shell 命令编码参数：客户端可指定输出编码（如 Windows `cmd.exe` 的 `gbk`），修复乱码问题
- Config fault tolerance: invalid entries automatically reset to defaults with WARN logs
- 配置容错：无效配置项自动重置为默认值并打印 WARN 警告
- `/newspaper lang <file>` command: switch language files at runtime
- `/newspaper lang <file>` 命令：运行时切换语言文件
- `FileTransferHandler` registered on all platforms (Paper / Fabric / Fabric-legacy) for chunked upload and download
- `FileTransferHandler` 在所有平台（Paper / Fabric / Fabric-legacy）注册，支持切片上传和下载

### Changed | 变更
- File transfer packet size reduced from full-file Base64 to 64KB chunks, preventing oversized WebSocket frames
- 文件传输数据包从整文件 Base64 缩减为 64KB 切片，避免 WebSocket 帧过大被阻断

### Removed | 移除
- `NewspaperRemote/` directory (C# standalone client), replaced by `sdk/csharp/` class library
- `NewspaperRemote/` 目录（C# 独立客户端），由 `sdk/csharp/` 类库替代

## [2.0.0] - 2026-08-14

### Added | 新增
- Multi-module architecture: `common` (shared logic), `paper` (Paper 1.21.1–26.2), `fabric` (Fabric 26.1–26.2), `fabric-legacy` (Fabric 1.21.1–1.21.11)
- 多模块架构：`common`（共享逻辑）、`paper`（Paper 1.21.1–26.2）、`fabric`（Fabric 26.1–26.2）、`fabric-legacy`（Fabric 1.21.1–1.21.11）
- Pure mTLS authentication: password-derived certificates (BouncyCastle secp256r1 + SHA-256), no manual cert exchange
- 纯 mTLS 认证：基于密码派生证书（BouncyCastle secp256r1 + SHA-256），无需手动交换证书
- Fabric dual-module support: 26.1+ uses Mojang official mappings, 1.21.1–1.21.11 uses Yarn mappings
- Fabric 双模块支持：26.1+ 使用 Mojang 官方 mappings，1.21.1–1.21.11 使用 Yarn mappings
- Shell command execution: remote system commands with timeout and forced termination
- Shell 命令执行：远程执行系统命令，支持超时和强制终止
- File transfer: Base64-encoded upload/download with path traversal detection
- 文件传输：通过 Base64 编码上传/下载文件，含路径遍历检测
- Reverse proxy mode: outbound connection to remote relay, auto-retry every 5 min
- 反向代理模式：服务器主动连接远程中继，每 5 分钟自动重试
- PROXY protocol support: optional v1 (text) / v2 (binary) parsing for client IP detection behind reverse proxies
- PROXY 协议支持：可选 v1（文本）/ v2（二进制）解析，用于反向代理后的客户端 IP 检测
- Audit logging: records WSS connection IDs, login attempts, remote addresses, and operation details with continuous file locking
- 审计日志：记录 WSS 连接 ID、登录尝试、远程地址和操作详情，运行期间持续文件锁定
- Password security: auto-generated 128-bit keys, strength validation (uppercase, lowercase, 3+ alphabetical, 6+ digits), Passay library validation
- 密码安全：自动生成 128 位密钥，强度验证（大写、小写、3+ 字母、6+ 数字），Passay 库校验
- Internationalization: built-in English and Chinese translations, runtime switching via `/newspaper lang`
- 国际化：内置英文和中文翻译，支持 `/newspaper lang` 运行时切换
- C# client (NewspaperRemote): mTLS-compatible with file upload/download
- C# 客户端（NewspaperRemote）：mTLS 兼容，含文件上传/下载
- 15 WebSocket operation types
- 15 种 WebSocket 操作类型

### Changed | 变更
- Restructured into multi-project Gradle layout, common module fully decoupled from Bukkit
- 重构为多项目 Gradle 结构，common 模块完全解耦 Bukkit 依赖
- Authentication switched from legacy SSH/TLS to pure mTLS
- 认证方式从旧的 SSH/TLS 切换为纯 mTLS
- Config schema reorganized with new fields: `enforce-password-strength`, `proxy-protocol`, `audit-log`
- 配置结构重组，新增 `enforce-password-strength`、`proxy-protocol`、`audit-log` 等字段
- `restrict-upload` default changed from `false` to `true`
- `restrict-upload` 默认值从 `false` 改为 `true`
- Paper module uses 1.21.1-R0.1-SNAPSHOT (to be updated when Paper 26.2 API is released)
- Paper 模块使用 1.21.1-R0.1-SNAPSHOT（等待 Paper 26.2 API 发布后更新坐标）

### Removed | 移除
- Legacy single-module `src/` directory
- 旧的单模块 `src/` 目录
- Legacy SSH encryption mode
- 旧的 SSH 加密模式
- Legacy Chapiem authentication system
- 旧的 Chapiem 认证系统

## [0.3.0] - 2026-08-12

### Added | 新增
- Reverse proxy connection mode
- 反向代理连接模式
- Encryption mode support (SSH/TLS)
- 加密模式支持（SSH/TLS）

### Fixed | 修复
- Thread scheduling issue for command execution
- 命令执行的线程调度问题

## [0.2.0] - 2026-08-10

### Added | 新增
- Encryption support
- 加密支持
- WebSocket communication framework
- WebSocket 通信框架

## [0.1.0] - 2026-08-08

### Added | 新增
- Project initialization
- 项目初始化
- Basic WebSocket server
- 基本 WebSocket 服务器
- Console command execution
- 控制台命令执行
- Player event listening
- 玩家事件监听
- Logo
- Logo

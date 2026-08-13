# Newspaper

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Paper](https://img.shields.io/badge/Paper-1.21.1--26.2-blue)](https://papermc.io)
[![Fabric](https://img.shields.io/badge/Fabric-1.21.1--26.2-orange)](https://fabricmc.net)

一个跨平台的 Minecraft 插件/模组，通过 WebSocket 实现远程服务器管理，使用双向 TLS 认证。

[English](../README.md)

## 功能特性

- **双向 TLS (mTLS)** — 基于密码派生证书，无需手动交换证书
- **双平台** — Paper (1.21.1–26.2) 和 Fabric (1.21.1–26.2) 共享代码库
- **Shell 命令执行** — 远程执行系统 Shell 命令，支持超时配置
- **文件传输** — 通过 Base64 编码在 WebSocket 上上传和下载文件
- **反向代理** — 主动连接模式，适用于 NAT/防火墙后的服务器
- **PROXY 协议** — 可选的 v1/v2 支持，用于在反向代理后检测客户端真实 IP
- **审计日志** — 持续文件锁定的防篡改审计日志
- **密码安全** — 自动生成 128 位密钥，带强度验证
- **国际化** — 内置英文 (`en`) 和中文 (`zh`) 翻译
- **配置容错** — 无效配置项自动重置为默认值并打印警告

## 版本支持

| 平台 | 模块 | Minecraft 版本 | Java |
|------|------|----------------|------|
| Paper | `paper` | 1.21.1–26.2 | 21+ |
| Fabric | `fabric` | 26.1–26.2 | 25 |
| Fabric (旧版) | `fabric-legacy` | 1.21.1–1.21.11 | 21 |

> **注意：** Fabric 使用两个独立模块，因为 Minecraft 26.1 有一项破坏性变更（Mojang 移除了类名混淆，导致 26.1+ 无法使用 Yarn 中间映射）。

## Alpha 版本说明

当你发现某个版本标记为 **Alpha**（例如 `v1.0.0-alpha.1`），那大概是一个存在已知问题但未被下架的版本。Alpha 版本可能包含未完成的功能、已知的崩溃风险或性能问题，主要用于早期测试和反馈收集。

## 认证机制

Newspaper 使用纯 mTLS。服务端和客户端通过共享密码使用 BouncyCastle（secp256r1 + SHA-256）派生出相同的 CA/服务器/客户端证书。TLS 握手执行双向认证，无需应用层认证消息。

如果未配置密码，将自动生成 128 位密钥，经过安全规则验证（大写字母、小写字母、数字），并打印到控制台。

## 连接模式

| 模式 | 配置值 | 描述 |
|------|--------|------|
| **直连** | `direct` | 服务器监听传入的 mTLS 连接 |
| **反向代理** | `reverse` | 服务器主动连接到远程中继，每 5 分钟重试 |

```yaml
connection-mode: "reverse"
reverse-proxy:
  host: "relay.example.com"
  port: 8080
  protocol: "wss"
```

## 配置

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

无效的配置值会自动重置为默认值并打印 `WARN` 日志。支持的验证规则：

| 配置项 | 规则 |
|--------|------|
| `port` | 1–65535 |
| `connection-mode` | `direct` 或 `reverse` |
| `reverse-proxy.protocol` | `wss` 或 `ws` |
| `shell.timeout` | >= 1 |

## 命令

| 命令 | 权限 | 描述 |
|------|------|------|
| `/newspaper reload` | OP | 重载配置并重启 WebSocket 服务器 |
| `/newspaper modify <key> <value>` | OP | 修改配置项（如 `port 9090`） |
| `/newspaper exec <command>` | OP | 执行系统 Shell 命令 |
| `/newspaper lang <file>` | OP | 加载语言文件（如 `en`、`zh`） |

## WebSocket API

### 连接

通过 `wss://<host>:<port>/` 连接（需要 mTLS）。所有数据帧必须是**二进制帧**（opcode `0x2`）。

### 请求格式

```json
{
  "type": "<操作类型>",
  "data": { ... }
}
```

### 操作类型

| 类型 | 描述 |
|------|------|
| `console_message` | 订阅/轮询/取消订阅控制台输出 |
| `chat_message` | 订阅/轮询/取消订阅聊天消息 |
| `command` | 执行控制台命令 |
| `command2` | 以指定玩家身份执行命令 |
| `player_join` | 订阅/轮询/取消订阅玩家加入事件 |
| `player_quit` | 订阅/轮询/取消订阅玩家退出事件 |
| `online_players` | 获取在线玩家列表及详情 |
| `server_info` | 获取服务器、Java 和内存信息 |
| `config_modify` | 修改配置项 |
| `config_reload` | 重载配置并重启 WebSocket |
| `shutdown` | 关闭 Minecraft 服务器 |
| `console_broadcast` | 向所有玩家广播消息 |
| `shell_command` | 执行系统 Shell 命令 |
| `file_upload` | 上传文件（Base64 内容） |
| `file_download` | 下载文件（返回 Base64 内容） |

### 示例

**Shell 命令：**
```json
{ "type": "shell_command", "data": { "command": "ls -la", "timeout": 10 } }
```

**文件上传：**
```json
{ "type": "file_upload", "data": { "path": "backup/world.yml", "content": "<base64>" } }
```

**文件下载：**
```json
{ "type": "file_download", "data": { "path": "config.yml" } }
```

## 安全

- **审计日志** — 记录 WSS 连接 ID、登录尝试（成功/失败）、远程地址和操作详情。日志文件（`audit.log`）在服务器运行期间持续锁定以防止篡改。针对 `audit.log` 的文件传输操作会被拦截。
- **文件传输安全** — 路径遍历检测、可选的上传目录限制（默认限制到服务器根目录）、全局文件传输禁用开关。
- **密码强度** — 默认强制验证。关闭时会触发红色警告。自动生成的密钥满足复杂度要求（大写字母、小写字母、3+ 个字母、6+ 个数字）。

## 许可证

MIT — 详见 [LICENSE](../LICENSE)。

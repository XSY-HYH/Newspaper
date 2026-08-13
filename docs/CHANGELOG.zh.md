# 更新日志

本文件记录 Newspaper 项目的所有重要变更。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased] — 未发布

### 新增
- 配置容错：无效配置项自动重置为默认值并打印 WARN 警告
- `/newspaper lang <file>` 命令：运行时切换语言文件
- Alpha 版本说明（README.md / docs/README.zh.md）

## [1.0.0-alpha.1] — 2026-08-14

### 新增
- 多模块架构：`common`（共享逻辑）、`paper`（Paper 1.21.1–26.2）、`fabric`（Fabric 26.1–26.2）、`fabric-legacy`（Fabric 1.21.1–1.21.11）
- 纯 mTLS 认证：基于密码派生证书（BouncyCastle secp256r1 + SHA-256），无需手动交换证书
- Fabric 双模块支持：26.1+ 使用 Mojang 官方 mappings，1.21.1–1.21.11 使用 Yarn mappings
- Shell 命令执行：远程执行系统命令，支持超时和强制终止
- 文件传输：通过 Base64 编码上传/下载文件，含路径遍历检测
- 反向代理模式：服务器主动连接远程中继，每 5 分钟自动重试
- PROXY 协议支持：可选 v1（文本）/ v2（二进制）解析，用于反向代理后的客户端 IP 检测
- 审计日志：记录 WSS 连接 ID、登录尝试、远程地址和操作详情，运行期间持续文件锁定
- 密码安全：自动生成 128 位密钥，强度验证（大写、小写、3+ 字母、6+ 数字），Passay 库校验
- 配置容错：无效配置值自动重置为默认值
- 国际化：内置英文和中文翻译，支持 `/newspaper lang` 运行时切换
- C# 客户端（NewspaperRemote）：mTLS 兼容，含文件上传/下载
- 中文文档（docs/README.zh.md）
- 15 种 WebSocket 操作类型

### 变更
- 重构为多项目 Gradle 结构，common 模块完全解耦 Bukkit 依赖
- 认证方式从旧的 SSH/TLS 切换为纯 mTLS
- 配置结构重组，新增 `enforce-password-strength`、`proxy-protocol`、`audit-log` 等字段
- `restrict-upload` 默认值从 `false` 改为 `true`
- Paper 模块使用 1.21.1-R0.1-SNAPSHOT（等待 Paper 26.2 API 发布后更新坐标）

### 移除
- 旧的单模块 `src/` 目录
- 旧的 SSH 加密模式
- 旧的 Chapiem 认证系统

## [0.3.0] — 2026-08-12

### 新增
- 反向代理连接模式
- 加密模式支持（SSH/TLS）

### 修复
- 命令执行的线程调度问题

## [0.2.0] — 2026-08-10

### 新增
- 加密支持
- WebSocket 通信框架

## [0.1.0] — 2026-08-08

### 新增
- 项目初始化
- 基本 WebSocket 服务器
- 控制台命令执行
- 玩家事件监听
- Logo

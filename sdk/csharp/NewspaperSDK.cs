using System;
using System.Collections.Generic;
using System.IO;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;

namespace Newspaper;

/// <summary>
/// Newspaper SDK 连接配置。
/// </summary>
public class NewspaperConfig
{
    /// <summary>服务器地址</summary>
    public string ServerHost { get; set; } = "127.0.0.1";

    /// <summary>服务器端口</summary>
    public int ServerPort { get; set; } = 8080;

    /// <summary>共享密码（用于派生 mTLS 证书）</summary>
    public string Password { get; set; } = "";

    /// <summary>单次请求超时时间</summary>
    public TimeSpan CommandTimeout { get; set; } = TimeSpan.FromSeconds(30);
}

/// <summary>
/// Newspaper 操作异常。
/// </summary>
public class NewspaperException : Exception
{
    public NewspaperException(string message) : base(message) { }
    public NewspaperException(string message, Exception innerException)
        : base(message, innerException) { }
}

/// <summary>
/// Newspaper C# SDK 主 API 类。
/// 通过 mTLS + WebSocket 连接到 Java 服务端，提供 Minecraft 服务器远程操作能力。
/// </summary>
public class NewspaperAPI : IDisposable
{
    // ── JSON 序列化选项 ──

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = null,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        WriteIndented = false,
    };

    private const int DefaultChunkSize = 65536;

    // ── 字段 ──

    private readonly NewspaperConfig _config;
    private ClientWebSocket? _ws;
    private X509Certificate2? _clientCert;
    private X509Certificate2? _caCert;

    private readonly SemaphoreSlim _sendLock = new(1, 1);
    private readonly CancellationTokenSource _cts = new();
    private Task? _receiveTask;

    // 请求-响应匹配
    private TaskCompletionSource<JsonElement>? _responseTcs;

    // ── 事件 ──

    /// <summary>
    /// 推送消息事件。参数: (消息类型, JSON 根元素)。
    /// 当客户端没有等待响应时收到的消息会被视为推送。
    /// </summary>
    public event Action<string, JsonElement>? OnPushMessage;

    /// <summary>连接错误事件。</summary>
    public event Action<Exception>? OnError;

    // ── 属性 ──

    public NewspaperConfig Config => _config;

    /// <summary>是否已连接</summary>
    public bool IsConnected => _ws?.State == WebSocketState.Open;

    // ── 构造 ──

    public NewspaperAPI(NewspaperConfig config)
    {
        _config = config ?? throw new ArgumentNullException(nameof(config));
    }

    public NewspaperAPI(string host, int port, string password)
        : this(new NewspaperConfig
        {
            ServerHost = host,
            ServerPort = port,
            Password = password
        })
    {
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 连接管理
    // ═══════════════════════════════════════════════════════════════════════

    /// <summary>
    /// 连接到 Newspaper 服务端。派生 mTLS 证书并建立 WebSocket 连接。
    /// </summary>
    public async Task ConnectAsync(CancellationToken ct = default)
    {
        if (IsConnected)
            throw new InvalidOperationException("Already connected");

        if (string.IsNullOrEmpty(_config.Password))
            throw new InvalidOperationException("Password is required");

        // 从密码派生 mTLS 证书
        var (clientCert, caCert) = CryptoHelper.DeriveCerts(_config.Password);
        _clientCert = clientCert;
        _caCert = caCert;

        _ws = new ClientWebSocket();

        // 设置客户端证书
        _ws.Options.ClientCertificates.Add(clientCert);

        // 自定义服务器证书验证（验证是否由我们的 CA 签发）
        _ws.Options.RemoteCertificateValidationCallback = ValidateServerCertificate;

        // 忽略hostname验证（证书CN=Newspaper-Server，不含SAN）
        _ws.Options.SetRequestHeader("Host", $"{_config.ServerHost}:{_config.ServerPort}");

        var url = new Uri($"wss://{_config.ServerHost}:{_config.ServerPort}/");

        try
        {
            await _ws.ConnectAsync(url, ct);
        }
        catch (Exception ex)
        {
            throw new NewspaperException(
                $"Failed to connect to {_config.ServerHost}:{_config.ServerPort}: {ex.Message}", ex);
        }

        // 启动后台接收循环
        _receiveTask = Task.Run(ReceiveLoopAsync);
    }

    /// <summary>
    /// 断开连接。
    /// </summary>
    public async Task DisconnectAsync()
    {
        _cts.Cancel();

        if (_ws != null && _ws.State == WebSocketState.Open)
        {
            try
            {
                await _ws.CloseAsync(
                    WebSocketCloseStatus.NormalClosure,
                    "Client disconnecting",
                    CancellationToken.None);
            }
            catch
            {
                // 忽略关闭错误
            }
        }

        // 等待接收循环结束
        if (_receiveTask != null)
        {
            try
            {
                await _receiveTask;
            }
            catch
            {
                // 忽略
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 通用请求
    // ═══════════════════════════════════════════════════════════════════════

    /// <summary>
    /// 发送请求并等待响应。一次只发送一个请求。
    /// </summary>
    /// <param name="type">操作类型</param>
    /// <param name="data">请求数据，可为 null</param>
    /// <param name="ct">取消令牌</param>
    /// <returns>响应 JSON 根元素</returns>
    public async Task<JsonElement> SendRequestAsync(
        string type, object? data = null, CancellationToken ct = default)
    {
        if (!IsConnected)
            throw new InvalidOperationException("Not connected");

        await _sendLock.WaitAsync(ct);
        try
        {
            // 创建响应等待器
            var tcs = new TaskCompletionSource<JsonElement>(
                TaskCreationOptions.RunContinuationsAsynchronously);
            Volatile.Write(ref _responseTcs, tcs);

            // 构造请求 JSON
            var requestObj = new Dictionary<string, object?>
            {
                ["type"] = type,
                ["data"] = data ?? new Dictionary<string, object?>()
            };
            string json = JsonSerializer.Serialize(requestObj, JsonOptions);
            byte[] bytes = Encoding.UTF8.GetBytes(json);

            // 发送二进制帧
            await _ws!.SendAsync(
                bytes,
                WebSocketMessageType.Binary,
                endOfMessage: true,
                ct);

            // 等待响应（带超时）
            using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct, _cts.Token);
            timeoutCts.CancelAfter(_config.CommandTimeout);

            using var registration = timeoutCts.Token.Register(() =>
            {
                tcs.TrySetCanceled();
            });

            JsonElement response;
            try
            {
                response = await tcs.Task;
            }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested)
            {
                throw new TimeoutException(
                    $"Request '{type}' timed out after {_config.CommandTimeout.TotalSeconds:F1}s");
            }

            // 检查错误状态
            if (response.TryGetProperty("status", out var statusProp) &&
                statusProp.ValueEquals("error"))
            {
                string message = response.TryGetProperty("message", out var msgProp)
                    ? msgProp.GetString() ?? "Unknown error"
                    : "Unknown error";
                throw new NewspaperException(message);
            }

            return response;
        }
        finally
        {
            Volatile.Write(ref _responseTcs, null);
            _sendLock.Release();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 基本操作
    // ═══════════════════════════════════════════════════════════════════════

    /// <summary>获取服务器信息（版本、内存、Java信息等）</summary>
    public Task<JsonElement> ServerInfoAsync(CancellationToken ct = default)
    {
        return SendRequestAsync("server_info", null, ct);
    }

    /// <summary>获取在线玩家列表</summary>
    public Task<JsonElement> OnlinePlayersAsync(CancellationToken ct = default)
    {
        return SendRequestAsync("online_players", null, ct);
    }

    /// <summary>执行 Minecraft 控制台命令</summary>
    public Task<JsonElement> CommandAsync(string command, CancellationToken ct = default)
    {
        return SendRequestAsync("command", new { command }, ct);
    }

    /// <summary>
    /// 执行系统 Shell 命令。
    /// </summary>
    /// <param name="command">命令字符串</param>
    /// <param name="encoding">输出编码（如 "UTF-8"、"gbk"），默认 UTF-8</param>
    public Task<JsonElement> ShellCommandAsync(
        string command, string? encoding = null, CancellationToken ct = default)
    {
        var data = new Dictionary<string, object?> { ["command"] = command };
        if (!string.IsNullOrEmpty(encoding))
            data["encoding"] = encoding;

        return SendRequestAsync("shell_command", data, ct);
    }

    /// <summary>关闭 Minecraft 服务器</summary>
    public Task<JsonElement> ShutdownAsync(CancellationToken ct = default)
    {
        return SendRequestAsync("shutdown", null, ct);
    }

    /// <summary>重新加载配置</summary>
    public Task<JsonElement> ConfigReloadAsync(CancellationToken ct = default)
    {
        return SendRequestAsync("config_reload", null, ct);
    }

    /// <summary>
    /// 修改配置项。
    /// </summary>
    /// <param name="changes">键值对，如 {"port": "9090", "language": "zh"}</param>
    public Task<JsonElement> ConfigModifyAsync(
        Dictionary<string, string> changes, CancellationToken ct = default)
    {
        return SendRequestAsync("config_modify", changes, ct);
    }

    /// <summary>广播消息到服务器控制台</summary>
    public Task<JsonElement> ConsoleBroadcastAsync(string message, CancellationToken ct = default)
    {
        return SendRequestAsync("console_broadcast", new { message }, ct);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 轮询操作 (Poll)
    // ═══════════════════════════════════════════════════════════════════════

    /// <summary>
    /// 聊天消息操作（subscribe / unsubscribe / poll）。
    /// 默认 poll 获取缓存的消息。
    /// </summary>
    public Task<JsonElement> ChatMessageAsync(
        string action = "poll", CancellationToken ct = default)
    {
        return SendRequestAsync("chat_message", new { action }, ct);
    }

    /// <summary>
    /// 玩家加入事件操作（subscribe / unsubscribe / poll）。
    /// </summary>
    public Task<JsonElement> PlayerJoinAsync(
        string action = "poll", CancellationToken ct = default)
    {
        return SendRequestAsync("player_join", new { action }, ct);
    }

    /// <summary>
    /// 玩家退出事件操作（subscribe / unsubscribe / poll）。
    /// </summary>
    public Task<JsonElement> PlayerQuitAsync(
        string action = "poll", CancellationToken ct = default)
    {
        return SendRequestAsync("player_quit", new { action }, ct);
    }

    /// <summary>
    /// 控制台消息操作（subscribe / unsubscribe / poll）。
    /// </summary>
    public Task<JsonElement> ConsoleMessageAsync(
        string action = "poll", CancellationToken ct = default)
    {
        return SendRequestAsync("console_message", new { action }, ct);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 文件传输 — 切片协议
    // ═══════════════════════════════════════════════════════════════════════

    /// <summary>
    /// 上传文件到服务器（切片传输协议）。
    /// </summary>
    /// <param name="localPath">本地文件路径</param>
    /// <param name="remotePath">服务器上的绝对路径</param>
    public async Task<JsonElement> UploadFileAsync(
        string localPath, string remotePath, CancellationToken ct = default)
    {
        const int chunkSize = DefaultChunkSize;

        if (!File.Exists(localPath))
            throw new FileNotFoundException($"Local file not found: {localPath}", localPath);

        using var fs = File.OpenRead(localPath);
        long totalSize = fs.Length;
        int totalChunks = (int)Math.Ceiling((double)totalSize / chunkSize);

        // 计算 SHA-256
        fs.Position = 0;
        byte[] hashBytes = await SHA256.HashDataAsync(fs, ct);
        string hash = Convert.ToHexString(hashBytes).ToLowerInvariant();
        fs.Position = 0;

        string transferId = Guid.NewGuid().ToString();

        // 1. 开始传输
        await SendRequestAsync("file_transfer_start", new Dictionary<string, object?>
        {
            ["transfer_id"] = transferId,
            ["direction"] = "upload",
            ["filename"] = remotePath
        }, ct);

        // 2. 元数据
        await SendRequestAsync("file_transfer_meta", new Dictionary<string, object?>
        {
            ["transfer_id"] = transferId,
            ["total_size"] = totalSize,
            ["chunk_size"] = chunkSize,
            ["total_chunks"] = totalChunks,
            ["hash"] = hash
        }, ct);

        // 3. 逐块上传
        var buffer = new byte[chunkSize];
        for (int i = 0; i < totalChunks; i++)
        {
            int len = await fs.ReadAsync(buffer.AsMemory(0, chunkSize), ct);
            string base64 = Convert.ToBase64String(buffer, 0, len);

            await SendRequestAsync("file_transfer_chunk", new Dictionary<string, object?>
            {
                ["transfer_id"] = transferId,
                ["chunk_index"] = i,
                ["data"] = base64
            }, ct);
        }

        // 4. 结束传输
        return await SendRequestAsync("file_transfer_end", new Dictionary<string, object?>
        {
            ["transfer_id"] = transferId,
            ["hash"] = hash
        }, ct);
    }

    /// <summary>
    /// 从服务器下载文件（切片传输协议）。
    /// </summary>
    /// <param name="remotePath">服务器上的绝对路径</param>
    /// <param name="localPath">本地保存路径</param>
    public async Task<JsonElement> DownloadFileAsync(
        string remotePath, string localPath, CancellationToken ct = default)
    {
        string transferId = Guid.NewGuid().ToString();

        // 1. 开始下载（服务端返回文件元信息）
        var startResp = await SendRequestAsync("file_transfer_start", new Dictionary<string, object?>
        {
            ["transfer_id"] = transferId,
            ["direction"] = "download",
            ["filename"] = remotePath
        }, ct);

        long totalSize = startResp.GetProperty("total_size").GetInt64();
        int totalChunks = startResp.GetProperty("total_chunks").GetInt32();
        string expectedHash = startResp.GetProperty("hash").GetString()!;

        // 2. 逐块下载
        using var ms = new MemoryStream();
        for (int i = 0; i < totalChunks; i++)
        {
            var chunkResp = await SendRequestAsync("file_transfer_chunk", new Dictionary<string, object?>
            {
                ["transfer_id"] = transferId,
                ["chunk_index"] = i
            }, ct);

            string base64 = chunkResp.GetProperty("data").GetString()!;
            byte[] chunk = Convert.FromBase64String(base64);
            await ms.WriteAsync(chunk.AsMemory(0, chunk.Length), ct);
        }

        // 3. 结束传输
        await SendRequestAsync("file_transfer_end", new Dictionary<string, object?>
        {
            ["transfer_id"] = transferId,
            ["hash"] = expectedHash
        }, ct);

        // 验证哈希
        byte[] fileBytes = ms.ToArray();
        byte[] actualHashBytes = SHA256.HashData(fileBytes);
        string actualHash = Convert.ToHexString(actualHashBytes).ToLowerInvariant();

        if (actualHash != expectedHash)
        {
            throw new NewspaperException(
                $"Hash mismatch: expected={expectedHash}, actual={actualHash}");
        }

        // 保存到本地文件
        string? dir = Path.GetDirectoryName(localPath);
        if (!string.IsNullOrEmpty(dir))
            Directory.CreateDirectory(dir);

        await File.WriteAllBytesAsync(localPath, fileBytes, ct);

        return startResp;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 旧版单次文件上传/下载（兼容接口）
    // ═══════════════════════════════════════════════════════════════════════

    /// <summary>
    /// 单次上传文件（base64，适用于小文件）。
    /// </summary>
    public async Task<JsonElement> FileUploadAsync(
        string remotePath, string localPath, CancellationToken ct = default)
    {
        byte[] content = await File.ReadAllBytesAsync(localPath, ct);
        string base64 = Convert.ToBase64String(content);

        return await SendRequestAsync("file_upload", new
        {
            path = remotePath,
            content = base64
        }, ct);
    }

    /// <summary>
    /// 单次下载文件（base64，适用于小文件）。
    /// </summary>
    public async Task<JsonElement> FileDownloadAsync(
        string remotePath, string localPath, CancellationToken ct = default)
    {
        var resp = await SendRequestAsync("file_download", new
        {
            path = remotePath
        }, ct);

        string base64 = resp.GetProperty("content").GetString()!;
        byte[] content = Convert.FromBase64String(base64);

        string? dir = Path.GetDirectoryName(localPath);
        if (!string.IsNullOrEmpty(dir))
            Directory.CreateDirectory(dir);

        await File.WriteAllBytesAsync(localPath, content, ct);

        return resp;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 内部实现
    // ═══════════════════════════════════════════════════════════════════════

    /// <summary>
    /// 验证服务器证书是否由共享 CA 签发。
    /// </summary>
    private bool ValidateServerCertificate(
        object sender,
        X509Certificate? certificate,
        X509Chain? chain,
        System.Net.Security.SslPolicyErrors sslPolicyErrors)
    {
        if (certificate == null || _caCert == null)
            return false;

        try
        {
            using var serverCert = new X509Certificate2(certificate);

            // 使用自定义信任存储验证证书链
            using var customChain = new X509Chain();
            customChain.ChainPolicy.TrustMode = X509ChainTrustMode.CustomRootTrust;
            customChain.ChainPolicy.CustomTrustStore.Clear();
            customChain.ChainPolicy.CustomTrustStore.Add(_caCert);
            customChain.ChainPolicy.RevocationMode = X509RevocationMode.NoCheck;
            // 忽略 hostname 不匹配（证书 CN=Newspaper-Server）
            customChain.ChainPolicy.VerificationFlags =
                X509VerificationFlags.IgnoreInvalidName;

            return customChain.Build(serverCert);
        }
        catch
        {
            return false;
        }
    }

    /// <summary>
    /// 后台接收循环。持续接收 WebSocket 消息，
    /// 将响应匹配给等待的请求，将推送消息分发给事件。
    /// </summary>
    private async Task ReceiveLoopAsync()
    {
        var buffer = new byte[64 * 1024];

        try
        {
            while (!_cts.IsCancellationRequested &&
                   _ws?.State == WebSocketState.Open)
            {
                // 处理消息分片
                using var ms = new MemoryStream();
                WebSocketReceiveResult result;

                do
                {
                    result = await _ws.ReceiveAsync(
                        buffer,
                        _cts.Token);

                    if (result.MessageType == WebSocketMessageType.Close)
                        return;

                    ms.Write(buffer, 0, result.Count);
                }
                while (!result.EndOfMessage);

                // 解析 JSON
                string json = Encoding.UTF8.GetString(ms.ToArray());
                JsonElement element;

                try
                {
                    element = JsonDocument.Parse(json).RootElement.Clone();
                }
                catch (JsonException)
                {
                    continue; // 忽略无效 JSON
                }

                // 匹配响应或分发推送
                var tcs = Volatile.Read(ref _responseTcs);
                if (tcs != null && !tcs.Task.IsCompleted)
                {
                    // 有等待的请求 → 这是响应
                    tcs.TrySetResult(element);
                }
                else
                {
                    // 无等待请求 → 推送消息
                    string type = element.TryGetProperty("type", out var typeProp)
                        ? typeProp.GetString() ?? "unknown"
                        : "unknown";

                    OnPushMessage?.Invoke(type, element);
                }
            }
        }
        catch (OperationCanceledException)
        {
            // 正常关闭
        }
        catch (Exception ex)
        {
            if (!_cts.IsCancellationRequested)
            {
                OnError?.Invoke(ex);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // IDisposable
    // ═══════════════════════════════════════════════════════════════════════

    private bool _disposed;

    public void Dispose()
    {
        Dispose(true);
        GC.SuppressFinalize(this);
    }

    protected virtual void Dispose(bool disposing)
    {
        if (_disposed)
            return;

        if (disposing)
        {
            _cts.Cancel();

            // 等待接收循环结束
            if (_receiveTask != null)
            {
                try
                {
                    _receiveTask.Wait(TimeSpan.FromSeconds(3));
                }
                catch
                {
                    // 忽略
                }
            }

            _ws?.Dispose();
            _clientCert?.Dispose();
            _caCert?.Dispose();
            _sendLock?.Dispose();
            _cts?.Dispose();
        }

        _disposed = true;
    }
}

using System;
using System.Collections.Generic;
using System.Net.Security;
using System.Net.WebSockets;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Logging;

namespace MorningCat.Modules
{
    public class ChatMessage
    {
        public string PlayerName { get; set; } = "";
        public string Message { get; set; } = "";
        public string PlayerUuid { get; set; } = "";
        public long Timestamp { get; set; }
    }

    public class PlayerEvent
    {
        public string PlayerName { get; set; } = "";
        public string PlayerUuid { get; set; } = "";
        public string EventType { get; set; } = "";
    }

    public class ShellResult
    {
        public int ExitCode { get; set; }
        public string Output { get; set; } = "";
        public string Error { get; set; } = "";
    }

    public class NewspaperAPI : IDisposable
    {
        private readonly NewspaperConfig _config;

        private ClientWebSocket? _ws;
        private CancellationTokenSource? _cts;
        private bool _isConnected = false;
        private readonly SemaphoreSlim _sendLock = new SemaphoreSlim(1, 1);

        // 请求-响应匹配：_sendLock 保证一次只处理一个请求
        private TaskCompletionSource<string?>? _pendingTcs;
        private string? _currentRequestType;

        // mTLS 证书
        private X509Certificate2? _caCert;
        private X509Certificate2? _clientCert;

        // 轮询状态（Java 端 chat/player 事件为轮询模式）
        private bool _chatSubscribed = false;
        private bool _eventSubscribed = false;
        private CancellationTokenSource? _pollCts;
        private Task? _pollTask;

        public event Action? OnConnected;
        public event Action? OnDisconnected;
        public event Action<Exception>? OnError;
        public event Action<ChatMessage>? OnChatMessage;
        public event Action<PlayerEvent>? OnPlayerEvent;
        public event Action<string>? OnCommandResult;

        public bool IsConnected => _isConnected;

        public NewspaperAPI(NewspaperConfig config)
        {
            _config = config;
        }

        #region 连接与 mTLS

        public async Task<bool> ConnectAsync()
        {
            if (_isConnected) return true;

            try
            {
                // 基于 password 确定性派生 CA 和 Client 证书
                var certs = CryptoHelper.GenerateCertificates(_config.Password);
                _caCert = certs.CaCertificate;
                _clientCert = certs.ClientCertificate;

                _cts = new CancellationTokenSource();

                _ws = new ClientWebSocket();
                _ws.Options.ClientCertificates.Add(_clientCert);
                _ws.Options.RemoteCertificateValidationCallback = ValidateServerCertificate;

                var url = $"wss://{_config.ServerHost}:{_config.ServerPort}";
                Log.Info($"[NewspaperAPI] Connecting to {url} (mTLS)");

                await _ws.ConnectAsync(new Uri(url), _cts.Token);

                _isConnected = true;
                Log.Info("[NewspaperAPI] mTLS handshake success, connected");

                // 启动后台接收循环
                _ = Task.Run(ReceiveLoopAsync);

                OnConnected?.Invoke();
                return true;
            }
            catch (Exception ex)
            {
                Log.Error($"[NewspaperAPI] Connect failed: {ex.Message}");
                OnError?.Invoke(ex);
                return false;
            }
        }

        /// <summary>
        /// 验证服务端证书由本地派生的 CA 签发（mTLS 双向认证的服务端侧验证）。
        /// </summary>
        private bool ValidateServerCertificate(object? sender, X509Certificate? cert, X509Chain? chain, SslPolicyErrors errors)
        {
            if (cert == null || _caCert == null) return false;

            try
            {
                var serverCert = new X509Certificate2(cert);
                chain ??= new X509Chain();

                chain.ChainPolicy.TrustMode = X509ChainTrustMode.CustomRootTrust;
                chain.ChainPolicy.CustomTrustStore.Clear();
                chain.ChainPolicy.CustomTrustStore.Add(_caCert);
                chain.ChainPolicy.RevocationMode = X509RevocationMode.NoCheck;
                chain.ChainPolicy.VerificationFlags = X509VerificationFlags.AllowUnknownCertificateAuthority;

                return chain.Build(serverCert);
            }
            catch (Exception ex)
            {
                Log.Error($"[NewspaperAPI] Cert validation error: {ex.Message}");
                return false;
            }
        }

        private async Task ReceiveLoopAsync()
        {
            var buffer = new byte[64 * 1024];
            var messageBuffer = new List<byte>();

            try
            {
                while (_isConnected && _ws != null && _ws.State == WebSocketState.Open
                       && !_cts!.IsCancellationRequested)
                {
                    WebSocketReceiveResult result;
                    try
                    {
                        result = await _ws.ReceiveAsync(new ArraySegment<byte>(buffer), _cts.Token);
                    }
                    catch (WebSocketException ex)
                    {
                        Log.Warning($"[NewspaperAPI] Receive error: {ex.Message}");
                        break;
                    }
                    catch (OperationCanceledException)
                    {
                        break;
                    }

                    if (result.MessageType == WebSocketMessageType.Close)
                    {
                        Log.Info("[NewspaperAPI] Server closed connection");
                        break;
                    }

                    messageBuffer.AddRange(new ArraySegment<byte>(buffer, 0, result.Count));

                    if (result.EndOfMessage)
                    {
                        var json = Encoding.UTF8.GetString(messageBuffer.ToArray());
                        messageBuffer.Clear();
                        ProcessMessage(json);
                    }
                }
            }
            catch (Exception ex)
            {
                Log.Error($"[NewspaperAPI] Receive loop error: {ex.Message}");
            }
            finally
            {
                _isConnected = false;
                StopPolling();
                OnDisconnected?.Invoke();
            }
        }

        private void ProcessMessage(string json)
        {
            try
            {
                using var doc = JsonDocument.Parse(json);
                var root = doc.RootElement;

                // 推送消息（有 type 字段）
                if (root.TryGetProperty("type", out var typeProp))
                {
                    var type = typeProp.GetString();
                    if (root.TryGetProperty("data", out var dataProp))
                    {
                        if (type == "chat_message")
                        {
                            var msg = new ChatMessage();
                            if (dataProp.TryGetProperty("player", out var p))
                                msg.PlayerName = p.GetString() ?? "";
                            if (dataProp.TryGetProperty("message", out var m))
                                msg.Message = m.GetString() ?? "";
                            if (dataProp.TryGetProperty("uuid", out var u))
                                msg.PlayerUuid = u.GetString() ?? "";
                            if (dataProp.TryGetProperty("timestamp", out var t) && t.ValueKind == JsonValueKind.Number)
                                msg.Timestamp = t.GetInt64();
                            OnChatMessage?.Invoke(msg);
                        }
                        else if (type == "player_join")
                        {
                            var evt = new PlayerEvent { EventType = "join" };
                            if (dataProp.TryGetProperty("player", out var p))
                                evt.PlayerName = p.GetString() ?? "";
                            if (dataProp.TryGetProperty("uuid", out var u))
                                evt.PlayerUuid = u.GetString() ?? "";
                            OnPlayerEvent?.Invoke(evt);
                        }
                        else if (type == "player_quit")
                        {
                            var evt = new PlayerEvent { EventType = "quit" };
                            if (dataProp.TryGetProperty("player", out var p))
                                evt.PlayerName = p.GetString() ?? "";
                            if (dataProp.TryGetProperty("uuid", out var u))
                                evt.PlayerUuid = u.GetString() ?? "";
                            OnPlayerEvent?.Invoke(evt);
                        }
                    }
                    return;
                }

                // 响应（有 status 字段）
                if (root.TryGetProperty("status", out _))
                {
                    _pendingTcs?.TrySetResult(json);

                    // 仅 command 请求的响应触发 OnCommandResult（用于外部转发）
                    if (_currentRequestType == "command")
                    {
                        var output = root.TryGetProperty("output", out var op) ? op.GetString() : "";
                        OnCommandResult?.Invoke(output ?? "");
                    }
                    return;
                }

                Log.Warning($"[NewspaperAPI] Unknown message: {json}");
            }
            catch (Exception ex)
            {
                Log.Error($"[NewspaperAPI] Process message error: {ex.Message}");
            }
        }

        #endregion

        #region 请求发送

        /// <summary>
        /// 发送明文 JSON 请求并等待响应。_sendLock 保证一次只处理一个请求。
        /// 请求格式: {"type":"...","data":{...}}
        /// 响应格式: {"status":"ok",...} 或 {"status":"error","message":"..."}
        /// </summary>
        private async Task<JsonDocument?> SendRequestAsync(string type, object? data = null, int? timeoutOverride = null)
        {
            if (!_isConnected || _ws == null)
            {
                Log.Warning("[NewspaperAPI] Not connected");
                return null;
            }

            await _sendLock.WaitAsync();
            try
            {
                _currentRequestType = type;
                _pendingTcs = new TaskCompletionSource<string?>();

                var request = new Dictionary<string, object?>
                {
                    ["type"] = type,
                    ["data"] = data ?? new { }
                };
                var json = JsonSerializer.Serialize(request);
                var bytes = Encoding.UTF8.GetBytes(json);

                await _ws.SendAsync(new ArraySegment<byte>(bytes),
                    WebSocketMessageType.Binary, true, _cts!.Token);
                Log.Debug($"[NewspaperAPI] Sent: {type}");

                var timeoutMs = (timeoutOverride ?? _config.CommandTimeout) * 1000;
                var completed = await Task.WhenAny(_pendingTcs.Task, Task.Delay(timeoutMs));

                if (completed == _pendingTcs.Task)
                {
                    var responseJson = await _pendingTcs.Task;
                    if (responseJson == null) return null;
                    return JsonDocument.Parse(responseJson);
                }

                Log.Warning($"[NewspaperAPI] Timeout waiting for {type} response");
                return null;
            }
            catch (Exception ex)
            {
                Log.Error($"[NewspaperAPI] Send request failed: {ex.Message}");
                return null;
            }
            finally
            {
                _pendingTcs = null;
                _currentRequestType = null;
                _sendLock.Release();
            }
        }

        #endregion

        #region 轮询

        private void StartPolling()
        {
            if (_pollTask != null && !_pollTask.IsCompleted) return;
            _pollCts?.Cancel();
            _pollCts = new CancellationTokenSource();
            _pollTask = PollLoopAsync(_pollCts.Token);
        }

        private void StopPolling()
        {
            _pollCts?.Cancel();
            _pollCts = null;
            _pollTask = null;
        }

        private async Task PollLoopAsync(CancellationToken ct)
        {
            try
            {
                using var timer = new PeriodicTimer(TimeSpan.FromSeconds(1));
                while (await timer.WaitForNextTickAsync(ct))
                {
                    if (!_isConnected) break;
                    try
                    {
                        if (_chatSubscribed)
                            await PollChatMessagesAsync();
                        if (_eventSubscribed)
                            await PollPlayerEventsAsync();
                    }
                    catch (Exception ex)
                    {
                        Log.Error($"[NewspaperAPI] Poll error: {ex.Message}");
                    }
                }
            }
            catch (OperationCanceledException) { }
        }

        private async Task PollChatMessagesAsync()
        {
            var result = await SendRequestAsync("chat_message", new { action = "poll" });
            if (result == null) return;

            if (result.RootElement.TryGetProperty("messages", out var msgsProp))
            {
                var messages = msgsProp.GetString() ?? "";
                if (string.IsNullOrEmpty(messages)) return;

                foreach (var line in messages.Split('\n'))
                {
                    if (string.IsNullOrWhiteSpace(line)) continue;
                    try
                    {
                        using var msgDoc = JsonDocument.Parse(line);
                        var msg = new ChatMessage();
                        if (msgDoc.RootElement.TryGetProperty("player", out var p))
                            msg.PlayerName = p.GetString() ?? "";
                        if (msgDoc.RootElement.TryGetProperty("message", out var m))
                            msg.Message = m.GetString() ?? "";
                        if (msgDoc.RootElement.TryGetProperty("uuid", out var u))
                            msg.PlayerUuid = u.GetString() ?? "";
                        if (msgDoc.RootElement.TryGetProperty("timestamp", out var t) && t.ValueKind == JsonValueKind.Number)
                            msg.Timestamp = t.GetInt64();
                        OnChatMessage?.Invoke(msg);
                    }
                    catch { }
                }
            }
        }

        private async Task PollPlayerEventsAsync()
        {
            // poll player_join
            var joinResult = await SendRequestAsync("player_join", new { action = "poll" });
            if (joinResult != null && joinResult.RootElement.TryGetProperty("events", out var eventsProp))
            {
                var events = eventsProp.GetString() ?? "";
                if (!string.IsNullOrEmpty(events))
                {
                    foreach (var line in events.Split('\n'))
                    {
                        if (string.IsNullOrWhiteSpace(line)) continue;
                        try
                        {
                            using var evtDoc = JsonDocument.Parse(line);
                            var evt = new PlayerEvent { EventType = "join" };
                            if (evtDoc.RootElement.TryGetProperty("player", out var p))
                                evt.PlayerName = p.GetString() ?? "";
                            if (evtDoc.RootElement.TryGetProperty("uuid", out var u))
                                evt.PlayerUuid = u.GetString() ?? "";
                            OnPlayerEvent?.Invoke(evt);
                        }
                        catch { }
                    }
                }
            }

            // poll player_quit
            var quitResult = await SendRequestAsync("player_quit", new { action = "poll" });
            if (quitResult != null && quitResult.RootElement.TryGetProperty("events", out var quitEventsProp))
            {
                var events = quitEventsProp.GetString() ?? "";
                if (!string.IsNullOrEmpty(events))
                {
                    foreach (var line in events.Split('\n'))
                    {
                        if (string.IsNullOrWhiteSpace(line)) continue;
                        try
                        {
                            using var evtDoc = JsonDocument.Parse(line);
                            var evt = new PlayerEvent { EventType = "quit" };
                            if (evtDoc.RootElement.TryGetProperty("player", out var p))
                                evt.PlayerName = p.GetString() ?? "";
                            if (evtDoc.RootElement.TryGetProperty("uuid", out var u))
                                evt.PlayerUuid = u.GetString() ?? "";
                            OnPlayerEvent?.Invoke(evt);
                        }
                        catch { }
                    }
                }
            }
        }

        #endregion

        #region Public API

        public async Task<bool> SubscribeChatMessagesAsync()
        {
            if (!_isConnected) return false;
            var result = await SendRequestAsync("chat_message", new { action = "subscribe" });
            if (result == null) return false;

            if (result.RootElement.TryGetProperty("status", out var s) && s.GetString() == "ok")
            {
                _chatSubscribed = true;
                StartPolling();
                return true;
            }
            return false;
        }

        public async Task<bool> UnsubscribeChatMessagesAsync()
        {
            if (!_isConnected) return false;
            var result = await SendRequestAsync("chat_message", new { action = "unsubscribe" });
            if (result == null) return false;

            if (result.RootElement.TryGetProperty("status", out var s) && s.GetString() == "ok")
            {
                _chatSubscribed = false;
                if (!_chatSubscribed && !_eventSubscribed)
                    StopPolling();
                return true;
            }
            return false;
        }

        public async Task<bool> SubscribePlayerEventsAsync()
        {
            if (!_isConnected) return false;

            var success1 = await SendRequestAsync("player_join", new { action = "subscribe" });
            var ok1 = success1 != null && success1.RootElement.TryGetProperty("status", out var s1) && s1.GetString() == "ok";

            var success2 = await SendRequestAsync("player_quit", new { action = "subscribe" });
            var ok2 = success2 != null && success2.RootElement.TryGetProperty("status", out var s2) && s2.GetString() == "ok";

            if (ok1 || ok2)
            {
                _eventSubscribed = true;
                StartPolling();
                return ok1 && ok2;
            }
            return false;
        }

        public async Task<bool> UnsubscribePlayerEventsAsync()
        {
            if (!_isConnected) return false;

            await SendRequestAsync("player_join", new { action = "unsubscribe" });
            await SendRequestAsync("player_quit", new { action = "unsubscribe" });

            _eventSubscribed = false;
            if (!_chatSubscribed && !_eventSubscribed)
                StopPolling();
            return true;
        }

        public async Task<ServerInfo?> GetServerInfoAsync()
        {
            var result = await SendRequestAsync("server_info");
            if (result == null) return null;

            try
            {
                var root = result.RootElement;
                var info = new ServerInfo();
                if (root.TryGetProperty("server", out var serverProp))
                {
                    if (serverProp.TryGetProperty("server_name", out var sn))
                        info.ServerName = sn.GetString();
                    if (serverProp.TryGetProperty("minecraft_version", out var mv))
                        info.MinecraftVersion = mv.GetString();
                }
                if (root.TryGetProperty("world", out var worldProp))
                {
                    if (worldProp.TryGetProperty("online_players", out var op) && op.ValueKind == JsonValueKind.Number)
                        info.OnlinePlayers = op.GetInt32();
                    if (worldProp.TryGetProperty("max_players", out var mp) && mp.ValueKind == JsonValueKind.Number)
                        info.MaxPlayers = mp.GetInt32();
                }
                return info;
            }
            catch (Exception ex)
            {
                Log.Error($"[NewspaperAPI] Parse server info failed: {ex.Message}");
                return null;
            }
        }

        public async Task<List<PlayerInfo>> GetOnlinePlayersAsync()
        {
            var result = await SendRequestAsync("online_players");
            if (result == null) return new List<PlayerInfo>();

            var players = new List<PlayerInfo>();
            try
            {
                if (result.RootElement.TryGetProperty("players", out var playersArray)
                    && playersArray.ValueKind == JsonValueKind.Array)
                {
                    foreach (var playerJson in playersArray.EnumerateArray())
                    {
                        var playerInfo = new PlayerInfo();
                        if (playerJson.TryGetProperty("name", out var nameProp))
                            playerInfo.Name = nameProp.GetString() ?? "Unknown";
                        if (playerJson.TryGetProperty("uuid", out var uuidProp))
                            playerInfo.Uuid = uuidProp.GetString();
                        if (playerJson.TryGetProperty("world", out var worldProp))
                            playerInfo.World = worldProp.GetString() ?? "Unknown";
                        players.Add(playerInfo);
                    }
                }
            }
            catch (Exception ex)
            {
                Log.Error($"[NewspaperAPI] Parse player list failed: {ex.Message}");
            }
            return players;
        }

        /// <summary>
        /// 执行 Minecraft 控制台命令。
        /// 请求: {"type":"command","data":{"command":"..."}}
        /// 响应: {"status":"ok","command":"...","success":true,"output":"..."}
        /// </summary>
        public async Task<string?> ExecuteCommandAsync(string command)
        {
            if (!_isConnected)
            {
                Log.Warning("[NewspaperAPI] Cannot execute command: not connected");
                return null;
            }

            var result = await SendRequestAsync("command", new { command });
            if (result == null) return null;

            var root = result.RootElement;
            if (root.TryGetProperty("status", out var s) && s.GetString() == "ok")
            {
                return root.TryGetProperty("output", out var op) ? op.GetString() : "";
            }

            if (root.TryGetProperty("message", out var msg))
            {
                Log.Warning($"[NewspaperAPI] Command error: {msg.GetString()}");
            }
            return null;
        }

        /// <summary>
        /// 执行系统 shell 命令。
        /// 请求: {"type":"shell_command","data":{"command":"...","timeout":30}}
        /// 响应: {"status":"ok","exit_code":0,"output":"...","error":"..."}
        /// </summary>
        public async Task<ShellResult?> ExecuteShellCommandAsync(string command, int timeout = 30)
        {
            if (!_isConnected)
            {
                Log.Warning("[NewspaperAPI] Cannot execute shell: not connected");
                return null;
            }

            var result = await SendRequestAsync("shell_command",
                new { command, timeout },
                timeout + 10);

            if (result == null) return null;

            var root = result.RootElement;
            if (root.TryGetProperty("status", out var s) && s.GetString() == "ok")
            {
                return new ShellResult
                {
                    ExitCode = root.TryGetProperty("exit_code", out var ec) && ec.ValueKind == JsonValueKind.Number
                        ? ec.GetInt32() : -1,
                    Output = root.TryGetProperty("output", out var op) ? op.GetString() ?? "" : "",
                    Error = root.TryGetProperty("error", out var er) ? er.GetString() ?? "" : ""
                };
            }

            if (root.TryGetProperty("message", out var msg))
            {
                Log.Warning($"[NewspaperAPI] Shell error: {msg.GetString()}");
            }
            return null;
        }

        /// <summary>
        /// 上传文件到远程服务器（content 用 Base64 编码放进 JSON）。
        /// 请求: {"type":"file_upload","data":{"path":"...","content":"<base64>"}}
        /// </summary>
        public async Task<bool> UploadFileAsync(string remotePath, byte[] content)
        {
            if (!_isConnected) return false;

            var base64 = Convert.ToBase64String(content);
            var result = await SendRequestAsync("file_upload",
                new { path = remotePath, content = base64 },
                Math.Max(_config.CommandTimeout, 120));

            if (result == null) return false;
            return result.RootElement.TryGetProperty("status", out var s) && s.GetString() == "ok";
        }

        /// <summary>
        /// 从远程服务器下载文件（响应里 content 是 Base64）。
        /// 请求: {"type":"file_download","data":{"path":"..."}}
        /// 响应: {"status":"ok","content":"<base64>"}
        /// </summary>
        public async Task<byte[]?> DownloadFileAsync(string remotePath)
        {
            if (!_isConnected) return null;

            var result = await SendRequestAsync("file_download",
                new { path = remotePath },
                Math.Max(_config.CommandTimeout, 120));

            if (result == null) return null;

            var root = result.RootElement;
            if (root.TryGetProperty("status", out var s) && s.GetString() == "ok")
            {
                if (root.TryGetProperty("content", out var c))
                {
                    var base64 = c.GetString();
                    if (!string.IsNullOrEmpty(base64))
                    {
                        return Convert.FromBase64String(base64);
                    }
                }
            }
            return null;
        }

        #endregion

        public async Task CloseAsync()
        {
            _isConnected = false;
            StopPolling();

            if (_ws != null && _ws.State == WebSocketState.Open)
            {
                try
                {
                    await _ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "Closing", CancellationToken.None);
                }
                catch { }
            }

            _cts?.Cancel();
            _cts?.Dispose();
            _cts = null;
            _ws?.Dispose();
            _ws = null;

            _caCert?.Dispose();
            _caCert = null;
            _clientCert?.Dispose();
            _clientCert = null;
        }

        public void Dispose()
        {
            CloseAsync().ConfigureAwait(false).GetAwaiter().GetResult();
            _sendLock?.Dispose();
        }
    }

    public class ServerInfo
    {
        public string? ServerName { get; set; }
        public string? MinecraftVersion { get; set; }
        public int OnlinePlayers { get; set; }
        public int MaxPlayers { get; set; }
    }

    public class PlayerInfo
    {
        public string Name { get; set; } = "Unknown";
        public string? Uuid { get; set; }
        public string World { get; set; } = "Unknown";
    }
}

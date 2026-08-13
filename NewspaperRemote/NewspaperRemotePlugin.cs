using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Threading.Tasks;
using Logging;
using ModuleManagerLib;
using MorningCat.Commands;
using MorningCat.Config;
using OneBotLib;
using OneBotLib.Models;
using MorningCat.PluginAPI;
namespace MorningCat.Modules
{
    [PluginMetadata(
        DisplayName = "NewspaperRemote",
        Author = "MorningCat",
        Description = "Remote control Minecraft server via Newspaper plugin (mTLS)"
    )]
    public class NewspaperRemotePlugin : ModuleBase
    {
        private OneBotClient _client = null!;
        private CommandRegistry _commandRegistry = null!;
        private PluginConfigManager _configManager = null!;
        private NewspaperAPI _newspaperAPI = null!;
        private NewspaperConfig _config = null!;

        private HashSet<long> _chatSubscribedGroups = new HashSet<long>();
        private HashSet<long> _eventSubscribedGroups = new HashSet<long>();

        public OneBotClient Client
        {
            get => _client;
            set => _client = value;
        }

        public CommandRegistry CommandRegistry
        {
            get => _commandRegistry;
            set => _commandRegistry = value;
        }

        public PluginConfigManager ConfigManager
        {
            get => _configManager;
            set => _configManager = value;
        }

        public override IEnumerable<string> GetLibraryDependencies()
        {
            return new[] { "OneBotLib.dll" };
        }

        public override async Task Init()
        {
            await LoadConfigAsync();
            await InitializeNewspaperAPI();
            RegisterCommands();

            Log.Info("[NewspaperRemote] Plugin loaded");
        }

        private async Task LoadConfigAsync()
        {
            try
            {
                _config = await _configManager.GetConfigAsync<NewspaperConfig>(
                    "NewspaperRemote",
                    "config",
                    new NewspaperConfig()
                ) ?? new NewspaperConfig();
                Log.Info($"[NewspaperRemote] Config loaded: Host={_config.ServerHost}, Port={_config.ServerPort}");

                _chatSubscribedGroups = new HashSet<long>(_config.ForwardChatGroups);
                _eventSubscribedGroups = new HashSet<long>(_config.ForwardEventGroups);
            }
            catch (Exception ex)
            {
                Log.Warning($"[NewspaperRemote] Failed to load config: {ex.Message}");
                _config = new NewspaperConfig();
            }
        }

        private async Task InitializeNewspaperAPI()
        {
            _newspaperAPI = new NewspaperAPI(_config);

            _newspaperAPI.OnConnected += async () =>
            {
                Log.Info("[NewspaperRemote] Connected to Newspaper server (mTLS)");
                await _newspaperAPI.SubscribeChatMessagesAsync();
                await _newspaperAPI.SubscribePlayerEventsAsync();
            };

            _newspaperAPI.OnDisconnected += () =>
                Log.Warning("[NewspaperRemote] Disconnected from Newspaper server");

            _newspaperAPI.OnError += (ex) =>
                Log.Error($"[NewspaperRemote] API error: {ex.Message}");

            _newspaperAPI.OnChatMessage += OnChatMessageReceived;
            _newspaperAPI.OnPlayerEvent += OnPlayerEventReceived;
            _newspaperAPI.OnCommandResult += OnCommandResultReceived;

            await _newspaperAPI.ConnectAsync();
        }

        private async void OnChatMessageReceived(ChatMessage msg)
        {
            try
            {
                if (_chatSubscribedGroups.Count == 0) return;
                var formattedMsg = $"[{_config.ServerDisplayName}] {msg.PlayerName}: {msg.Message}";
                foreach (var groupId in _chatSubscribedGroups)
                {
                    await _client.SendGroupMsgAsync(groupId, formattedMsg);
                }
            }
            catch (Exception ex)
            {
                Log.Error($"[NewspaperRemote] Forward chat failed: {ex.Message}");
            }
        }

        private async void OnPlayerEventReceived(PlayerEvent evt)
        {
            try
            {
                if (_eventSubscribedGroups.Count == 0) return;
                string msg = evt.EventType == "join"
                    ? $"[{_config.ServerDisplayName}] 玩家 {evt.PlayerName} 加入了游戏"
                    : $"[{_config.ServerDisplayName}] 玩家 {evt.PlayerName} 离开了游戏";
                foreach (var groupId in _eventSubscribedGroups)
                {
                    await _client.SendGroupMsgAsync(groupId, msg);
                }
            }
            catch (Exception ex)
            {
                Log.Error($"[NewspaperRemote] Forward player event failed: {ex.Message}");
            }
        }

        private async void OnCommandResultReceived(string result)
        {
            try
            {
                if (_config.CommandResultGroup == 0) return;
                await _client.SendGroupMsgAsync(_config.CommandResultGroup, $"[执行结果]\n{result}");
            }
            catch (Exception ex)
            {
                Log.Error($"[NewspaperRemote] Send command result failed: {ex.Message}");
            }
        }

        private void RegisterCommands()
        {
            _commandRegistry?.RegisterCommand(
                "#服务器",
                "Get Minecraft server info",
                "#服务器 - Get server basic info",
                new List<CommandParameter>(),
                HandleServerInfoCommand,
                "NewspaperRemote",
                CommandPermission.GroupAdmin,
                CommandScope.GroupOnly,
                requireAt: false,
                requireSlash: false
            );

            _commandRegistry?.RegisterCommand(
                "#玩家列表",
                "Get online players list",
                "#玩家列表 - View all online players",
                new List<CommandParameter>(),
                HandlePlayerListCommand,
                "NewspaperRemote",
                CommandPermission.GroupAdmin,
                CommandScope.GroupOnly,
                requireAt: false,
                requireSlash: false
            );

            _commandRegistry?.RegisterCommand(
                "#执行",
                "Execute console command",
                "#执行 <command> - Execute command on server console",
                new List<CommandParameter>
                {
                    new CommandParameter
                    {
                        Name = "command",
                        Description = "Minecraft command",
                        IsRequired = true,
                        Type = ParameterType.String
                    }
                },
                HandleExecuteCommand,
                "NewspaperRemote",
                CommandPermission.GroupAdmin,
                CommandScope.GroupOnly,
                requireAt: false,
                requireSlash: false
            );

            _commandRegistry?.RegisterCommand(
                "#执行命令",
                "Execute system shell command",
                "#执行命令 <command> - Execute system shell command on server",
                new List<CommandParameter>
                {
                    new CommandParameter
                    {
                        Name = "command",
                        Description = "Shell command",
                        IsRequired = true,
                        Type = ParameterType.String
                    }
                },
                HandleShellCommand,
                "NewspaperRemote",
                CommandPermission.GroupAdmin,
                CommandScope.GroupOnly,
                requireAt: false,
                requireSlash: false
            );

            _commandRegistry?.RegisterCommand(
                "#上传文件",
                "Upload file to server",
                "#上传文件 <本地路径> <远程路径> - Upload local file to remote server",
                new List<CommandParameter>
                {
                    new CommandParameter
                    {
                        Name = "local_path",
                        Description = "Local file path",
                        IsRequired = true,
                        Type = ParameterType.String
                    },
                    new CommandParameter
                    {
                        Name = "remote_path",
                        Description = "Remote file path",
                        IsRequired = true,
                        Type = ParameterType.String
                    }
                },
                HandleUploadFileCommand,
                "NewspaperRemote",
                CommandPermission.GroupAdmin,
                CommandScope.GroupOnly,
                requireAt: false,
                requireSlash: false
            );

            _commandRegistry?.RegisterCommand(
                "#下载文件",
                "Download file from server",
                "#下载文件 <远程路径> <本地路径> - Download remote file to local",
                new List<CommandParameter>
                {
                    new CommandParameter
                    {
                        Name = "remote_path",
                        Description = "Remote file path",
                        IsRequired = true,
                        Type = ParameterType.String
                    },
                    new CommandParameter
                    {
                        Name = "local_path",
                        Description = "Local file path",
                        IsRequired = true,
                        Type = ParameterType.String
                    }
                },
                HandleDownloadFileCommand,
                "NewspaperRemote",
                CommandPermission.GroupAdmin,
                CommandScope.GroupOnly,
                requireAt: false,
                requireSlash: false
            );

            _commandRegistry?.RegisterCommand(
                "#订阅聊天",
                "Subscribe to chat messages",
                "#订阅聊天 - Receive Minecraft chat messages in this group",
                new List<CommandParameter>(),
                HandleSubscribeChatCommand,
                "NewspaperRemote",
                CommandPermission.GroupAdmin,
                CommandScope.GroupOnly,
                requireAt: false,
                requireSlash: false
            );

            _commandRegistry?.RegisterCommand(
                "#取消订阅聊天",
                "Unsubscribe from chat messages",
                "#取消订阅聊天 - Stop receiving chat messages",
                new List<CommandParameter>(),
                HandleUnsubscribeChatCommand,
                "NewspaperRemote",
                CommandPermission.GroupAdmin,
                CommandScope.GroupOnly,
                requireAt: false,
                requireSlash: false
            );

            _commandRegistry?.RegisterCommand(
                "#订阅事件",
                "Subscribe to player events",
                "#订阅事件 - Receive player join/quit messages",
                new List<CommandParameter>(),
                HandleSubscribeEventCommand,
                "NewspaperRemote",
                CommandPermission.GroupAdmin,
                CommandScope.GroupOnly,
                requireAt: false,
                requireSlash: false
            );

            _commandRegistry?.RegisterCommand(
                "#取消订阅事件",
                "Unsubscribe from player events",
                "#取消订阅事件 - Stop receiving player join/quit messages",
                new List<CommandParameter>(),
                HandleUnsubscribeEventCommand,
                "NewspaperRemote",
                CommandPermission.GroupAdmin,
                CommandScope.GroupOnly,
                requireAt: false,
                requireSlash: false
            );

            Log.Debug("[NewspaperRemote] Commands registered");
        }

        private async Task HandleServerInfoCommand(CommandContext context)
        {
            var message = context.Message;

            if (!_newspaperAPI.IsConnected)
            {
                await SendMessageAsync(message, "[X] Not connected to Newspaper server");
                return;
            }

            try
            {
                await SendMessageAsync(message, "[...] Fetching server info...");
                var info = await _newspaperAPI.GetServerInfoAsync();
                if (info == null)
                {
                    await SendMessageAsync(message, "[X] Failed to get server info");
                    return;
                }

                var response = $"""
                =====================================
                Minecraft Server Info
                =====================================
                Name: {info.ServerName ?? "Unknown"}
                Version: {info.MinecraftVersion ?? "Unknown"}
                Players: {info.OnlinePlayers}/{info.MaxPlayers}
                =====================================
                """;
                await SendMessageAsync(message, response);
            }
            catch (Exception ex)
            {
                await SendMessageAsync(message, $"[X] Error: {ex.Message}");
            }
        }

        private async Task HandlePlayerListCommand(CommandContext context)
        {
            var message = context.Message;

            if (!_newspaperAPI.IsConnected)
            {
                await SendMessageAsync(message, "[X] Not connected to Newspaper server");
                return;
            }

            try
            {
                await SendMessageAsync(message, "[...] Fetching player list...");
                var players = await _newspaperAPI.GetOnlinePlayersAsync();

                if (players == null || players.Count == 0)
                {
                    await SendMessageAsync(message, "[...] No players online");
                    return;
                }

                var lines = new List<string> { $"=====================================\nOnline Players ({players.Count})\n=====================================" };
                foreach (var p in players)
                {
                    lines.Add($"Name: {p.Name}");
                    lines.Add($"  World: {p.World}");
                    lines.Add("-------------------------------------");
                }
                await SendMessageAsync(message, string.Join("\n", lines));
            }
            catch (Exception ex)
            {
                await SendMessageAsync(message, $"[X] Error: {ex.Message}");
            }
        }

        private async Task HandleExecuteCommand(CommandContext context)
        {
            var message = context.Message;
            var parameters = context.Parameters;

            if (!_newspaperAPI.IsConnected)
            {
                await SendMessageAsync(message, "[X] Not connected to Newspaper server");
                return;
            }

            if (!parameters.TryGetValue("command", out var command) || string.IsNullOrWhiteSpace(command))
            {
                await SendMessageAsync(message, "[X] Please provide a command\nFormat: #执行 <command>");
                return;
            }

            var lowerCommand = command.ToLower();
            foreach (var dangerous in _config.CommandBlacklist)
            {
                if (lowerCommand.StartsWith(dangerous.ToLower()))
                {
                    await SendMessageAsync(message, $"[X] Command '{dangerous}' is not allowed");
                    return;
                }
            }

            try
            {
                await SendMessageAsync(message, $"[...] Executing: {command}");
                var result = await _newspaperAPI.ExecuteCommandAsync(command);

                if (!string.IsNullOrEmpty(result))
                {
                    await SendMessageAsync(message, $"[OK] Result:\n{result}");
                }
                else
                {
                    await SendMessageAsync(message, "[OK] Command sent (no result returned)");
                }
            }
            catch (Exception ex)
            {
                await SendMessageAsync(message, $"[X] Error: {ex.Message}");
            }
        }

        private async Task HandleShellCommand(CommandContext context)
        {
            var message = context.Message;
            var parameters = context.Parameters;

            if (!_newspaperAPI.IsConnected)
            {
                await SendMessageAsync(message, "[X] Not connected to Newspaper server");
                return;
            }

            if (!parameters.TryGetValue("command", out var command) || string.IsNullOrWhiteSpace(command))
            {
                await SendMessageAsync(message, "[X] Please provide a command\nFormat: #执行命令 <command>");
                return;
            }

            try
            {
                await SendMessageAsync(message, $"[...] Executing shell: {command}");
                var result = await _newspaperAPI.ExecuteShellCommandAsync(command, _config.ShellTimeout);

                if (result != null)
                {
                    var sb = new StringBuilder();
                    sb.AppendLine($"[OK] ExitCode: {result.ExitCode}");
                    if (!string.IsNullOrEmpty(result.Output))
                        sb.AppendLine($"Output:\n{result.Output}");
                    if (!string.IsNullOrEmpty(result.Error))
                        sb.AppendLine($"Error:\n{result.Error}");
                    await SendMessageAsync(message, sb.ToString().TrimEnd());
                }
                else
                {
                    await SendMessageAsync(message, "[X] Shell command failed or timed out");
                }
            }
            catch (Exception ex)
            {
                await SendMessageAsync(message, $"[X] Error: {ex.Message}");
            }
        }

        private async Task HandleUploadFileCommand(CommandContext context)
        {
            var message = context.Message;
            var parameters = context.Parameters;

            if (!_newspaperAPI.IsConnected)
            {
                await SendMessageAsync(message, "[X] Not connected to Newspaper server");
                return;
            }

            if (!parameters.TryGetValue("local_path", out var localPath) || string.IsNullOrWhiteSpace(localPath))
            {
                await SendMessageAsync(message, "[X] Please provide local path\nFormat: #上传文件 <本地路径> <远程路径>");
                return;
            }

            if (!parameters.TryGetValue("remote_path", out var remotePath) || string.IsNullOrWhiteSpace(remotePath))
            {
                await SendMessageAsync(message, "[X] Please provide remote path\nFormat: #上传文件 <本地路径> <远程路径>");
                return;
            }

            try
            {
                if (!File.Exists(localPath))
                {
                    await SendMessageAsync(message, $"[X] Local file not found: {localPath}");
                    return;
                }

                var content = await File.ReadAllBytesAsync(localPath);
                await SendMessageAsync(message, $"[...] Uploading {localPath} ({content.Length} bytes)...");

                var success = await _newspaperAPI.UploadFileAsync(remotePath, content);
                await SendMessageAsync(message, success
                    ? $"[OK] Uploaded to {remotePath}"
                    : "[X] Upload failed");
            }
            catch (Exception ex)
            {
                await SendMessageAsync(message, $"[X] Error: {ex.Message}");
            }
        }

        private async Task HandleDownloadFileCommand(CommandContext context)
        {
            var message = context.Message;
            var parameters = context.Parameters;

            if (!_newspaperAPI.IsConnected)
            {
                await SendMessageAsync(message, "[X] Not connected to Newspaper server");
                return;
            }

            if (!parameters.TryGetValue("remote_path", out var remotePath) || string.IsNullOrWhiteSpace(remotePath))
            {
                await SendMessageAsync(message, "[X] Please provide remote path\nFormat: #下载文件 <远程路径> <本地路径>");
                return;
            }

            if (!parameters.TryGetValue("local_path", out var localPath) || string.IsNullOrWhiteSpace(localPath))
            {
                await SendMessageAsync(message, "[X] Please provide local path\nFormat: #下载文件 <远程路径> <本地路径>");
                return;
            }

            try
            {
                await SendMessageAsync(message, $"[...] Downloading {remotePath}...");
                var content = await _newspaperAPI.DownloadFileAsync(remotePath);

                if (content == null)
                {
                    await SendMessageAsync(message, "[X] Download failed");
                    return;
                }

                var dir = Path.GetDirectoryName(localPath);
                if (!string.IsNullOrEmpty(dir) && !Directory.Exists(dir))
                {
                    Directory.CreateDirectory(dir);
                }

                await File.WriteAllBytesAsync(localPath, content);
                await SendMessageAsync(message, $"[OK] Downloaded to {localPath} ({content.Length} bytes)");
            }
            catch (Exception ex)
            {
                await SendMessageAsync(message, $"[X] Error: {ex.Message}");
            }
        }

        private async Task HandleSubscribeChatCommand(CommandContext context)
        {
            var message = context.Message;
            var groupId = message.GroupId ?? 0;

            if (groupId == 0)
            {
                await SendMessageAsync(message, "[X] This command is only available in group chats");
                return;
            }

            if (_chatSubscribedGroups.Contains(groupId))
            {
                await SendMessageAsync(message, "[...] Already subscribed to chat messages");
                return;
            }

            _chatSubscribedGroups.Add(groupId);
            _config.ForwardChatGroups.Add(groupId);
            await SaveConfigAsync();

            await SendMessageAsync(message, $"[OK] Subscribed to chat messages\nServer: {_config.ServerDisplayName}");
        }

        private async Task HandleUnsubscribeChatCommand(CommandContext context)
        {
            var message = context.Message;
            var groupId = message.GroupId ?? 0;

            if (groupId == 0)
            {
                await SendMessageAsync(message, "[X] This command is only available in group chats");
                return;
            }

            if (!_chatSubscribedGroups.Contains(groupId))
            {
                await SendMessageAsync(message, "[...] Not subscribed to chat messages");
                return;
            }

            _chatSubscribedGroups.Remove(groupId);
            _config.ForwardChatGroups.Remove(groupId);
            await SaveConfigAsync();

            await SendMessageAsync(message, "[OK] Unsubscribed from chat messages");
        }

        private async Task HandleSubscribeEventCommand(CommandContext context)
        {
            var message = context.Message;
            var groupId = message.GroupId ?? 0;

            if (groupId == 0)
            {
                await SendMessageAsync(message, "[X] This command is only available in group chats");
                return;
            }

            if (_eventSubscribedGroups.Contains(groupId))
            {
                await SendMessageAsync(message, "[...] Already subscribed to player events");
                return;
            }

            _eventSubscribedGroups.Add(groupId);
            _config.ForwardEventGroups.Add(groupId);
            await SaveConfigAsync();

            await SendMessageAsync(message, $"[OK] Subscribed to player events\nServer: {_config.ServerDisplayName}");
        }

        private async Task HandleUnsubscribeEventCommand(CommandContext context)
        {
            var message = context.Message;
            var groupId = message.GroupId ?? 0;

            if (groupId == 0)
            {
                await SendMessageAsync(message, "[X] This command is only available in group chats");
                return;
            }

            if (!_eventSubscribedGroups.Contains(groupId))
            {
                await SendMessageAsync(message, "[...] Not subscribed to player events");
                return;
            }

            _eventSubscribedGroups.Remove(groupId);
            _config.ForwardEventGroups.Remove(groupId);
            await SaveConfigAsync();

            await SendMessageAsync(message, "[OK] Unsubscribed from player events");
        }

        private async Task SaveConfigAsync()
        {
            try
            {
                await _configManager.SetConfigAsync("NewspaperRemote", "config", _config);
            }
            catch (Exception ex)
            {
                Log.Error($"[NewspaperRemote] Save config failed: {ex.Message}");
            }
        }

        private async Task SendMessageAsync(MessageObject message, string text)
        {
            try
            {
                if (message.MessageType == "private")
                    await _client.SendPrivateMsgAsync(message.UserId ?? 0, text);
                else if (message.MessageType == "group")
                    await _client.SendGroupMsgAsync(message.GroupId ?? 0, text);
            }
            catch (Exception ex)
            {
                Log.Error($"[NewspaperRemote] Send message failed: {ex.Message}");
            }
        }

        public override async Task Exit()
        {
            if (_newspaperAPI != null)
            {
                await _newspaperAPI.UnsubscribeChatMessagesAsync();
                await _newspaperAPI.UnsubscribePlayerEventsAsync();
                await _newspaperAPI.CloseAsync();
                _newspaperAPI.Dispose();
            }
            _commandRegistry?.UnregisterModuleCommands("NewspaperRemote");
            Log.Info("[NewspaperRemote] Plugin unloaded");
        }
    }
}

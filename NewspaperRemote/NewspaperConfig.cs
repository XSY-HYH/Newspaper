namespace MorningCat.Modules
{
    public class NewspaperConfig
    {
        // mTLS 连接配置
        public string ServerHost { get; set; } = "localhost";
        public int ServerPort { get; set; } = 8080;

        // 认证配置（用于 mTLS 证书确定性派生，须与 Java 端一致）
        public string Password { get; set; } = "newspaper";

        // 显示配置
        public string ServerDisplayName { get; set; } = "MinecraftServer";

        // 转发配置 - 绑定哪些群
        public List<long> ForwardChatGroups { get; set; } = new List<long>();
        public List<long> ForwardEventGroups { get; set; } = new List<long>();
        public long CommandResultGroup { get; set; } = 0;

        // 连接配置
        public bool AutoReconnect { get; set; } = true;
        public int ReconnectInterval { get; set; } = 30;
        public int CommandTimeout { get; set; } = 10;
        public int ShellTimeout { get; set; } = 30;

        // 命令白名单/黑名单
        public List<string> CommandWhitelist { get; set; } = new List<string>();
        public List<string> CommandBlacklist { get; set; } = new List<string>
        {
            "stop", "restart", "reload", "kickall", "op", "deop", "ban", "unban"
        };
    }
}

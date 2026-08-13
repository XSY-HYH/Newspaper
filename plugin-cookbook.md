# MorningCat 插件开发实践手册与避坑索引

本文档是插件开发的实践参考，聚焦于**如何正确使用**各个子系统、**内部发生了什么**、以及**踩坑点**。

---

## 目录

- [一、配置系统](#一配置系统)
- [二、数据库系统](#二数据库系统)
- [三、命令系统](#三命令系统)
- [四、插件加载与依赖](#四插件加载与依赖)
- [五、依赖注入](#五依赖注入)
- [六、避坑索引（按症状速查）](#六避坑索引按症状速查)

---

## 一、配置系统

### 1.1 正确用法（BanListPlugin 标准）

```csharp
// 1. 声明配置类，所有属性给默认值
public class BanListPluginConfig
{
    public List<long> AllowedGroups { get; set; } = new List<long>();
}

// 2. 声明 ConfigManager 属性（类型必须精确匹配 PluginConfigManager）
public PluginConfigManager ConfigManager
{
    get => _configManager;
    set => _configManager = value;
}

// 3. 在 Init 中加载，必须传 new 默认实例
private async Task LoadAllowedGroupsAsync()
{
    try
    {
        var config = await _configManager.GetConfigAsync<BanListPluginConfig>(
            "BanListPluginModule",   // 插件名（通常与类名一致）
            "config",                // 配置名
            new BanListPluginConfig() // 必须传！否则不会生成默认配置文件
        );

        if (config?.AllowedGroups != null && config.AllowedGroups.Count > 0)
        {
            _allowedGroups = new HashSet<long>(config.AllowedGroups);
        }
        else
        {
            Log.Warning("[BanListPlugin] 未配置允许查询的群");
        }
    }
    catch (Exception ex)
    {
        Log.Error($"[BanListPlugin] 加载配置失败: {ex.Message}");
        _allowedGroups = new HashSet<long>();
    }
}

// 4. 修改后保存
private async Task SaveConfigAsync()
{
    await _configManager.SetConfigAsync("BanListPluginModule", "config", _config);
}
```

### 1.2 内部流程

调用 `GetConfigAsync<T>(pluginName, configName, defaultValue)` 时：

1. 读取 `Config/{pluginName}-{configName}.yml`
2. 文件不存在 → 用 `defaultValue` 生成默认配置文件（**所以必须传默认实例**）
3. 文件存在 → 用 YamlDotNet 的 `UnderscoredNamingConvention` 反序列化
4. 反序列化失败 → catch 吞掉异常，返回 `defaultValue ?? new T()`

调用 `SetConfigAsync<T>(pluginName, configName, config)` 时：

1. 用 YamlDotNet 序列化为 YAML（同样使用 `UnderscoredNamingConvention`）
2. 在文件头部添加注释（插件名、配置名、时间戳）
3. 写入 `Config/{pluginName}-{configName}.yml`

### 1.3 坑：YAML 命名约定

框架使用 `UnderscoredNamingConvention`，C# 属性名和 YAML 键名的映射关系：

| C# 属性名 | YAML 键名 | 是否自动匹配 |
|-----------|-----------|-------------|
| `AllowedGroups` | `allowed_groups` | 是 |
| `AllowedGroups` | `AllowedGroups` | 否 |
| `AllowedGroups` | `allowedGroups` | 否 |

**解决方案**：

- 方案一（推荐）：让 YAML 使用下划线命名，框架自动处理
- 方案二：在属性上加 `[YamlMember(Alias = "allowedGroups")]` 指定别名

```csharp
using YamlDotNet.Serialization;

public class MyConfig
{
    [YamlMember(Alias = "serverUrl")]
    public string ServerUrl { get; set; } = "ws://localhost:8080";
}
```

### 1.4 坑：默认配置不生成

```csharp
// 错误：defaultValue 是 null（泛型默认值），不会生成配置文件
var config = await _configManager.GetConfigAsync<MyConfig>("Plugin", "config");

// 正确：传默认实例
var config = await _configManager.GetConfigAsync<MyConfig>("Plugin", "config", new MyConfig());
```

### 1.5 坑：反序列化静默失败

框架在 catch 中返回默认值，不会抛出异常。如果配置文件内容格式错误，你只会发现"配置没生效"但看不到报错。

排查方式：检查启动日志中是否有 `加载配置文件失败` 的记录。

### 1.6 坑：YamlDotNet 版本冲突

如果插件项目通过 NuGet 引用了 `YamlDotNet`，而框架也自带了 `YamlDotNet`，版本不一致可能导致运行时 `MissingMethodException` 或 `TypeLoadException`。

解决方案：**插件项目不要通过 NuGet 引用 YamlDotNet**。如果必须使用 `[YamlMember]` 等特性，通过引用框架的 DLL 间接获取（框架的 MorningCat.dll 已经包含了 YamlDotNet 的依赖）。

---

## 二、数据库系统

### 2.1 正确用法（deer_check 标准）

```csharp
// 1. 声明属性
public PluginDatabaseAPI PluginDatabaseAPI
{
    get => _dbAPI;
    set => _dbAPI = value;
}

// 2. 在 Init 中获取数据库实例并建表
private async Task InitDatabases()
{
    _deerDb = _dbAPI.GetDatabase("deer", "DeerCheckinPlugin");

    await _deerDb.ExecuteNonQueryAsync(@"
        CREATE TABLE IF NOT EXISTS checkin (
            user_id TEXT NOT NULL,
            checkin_date TEXT NOT NULL,
            deer_count INTEGER NOT NULL,
            PRIMARY KEY (user_id, checkin_date)
        )");
}

// 3. 使用参数化查询
private async Task<string?> GetMetadata(IPluginDatabase db, string key)
{
    var param = db.CreateParameter("@key", key);
    var result = await db.ExecuteScalarAsync(
        "SELECT value FROM metadata WHERE key = @key", param);
    return result?.ToString();
}

// 4. INSERT OR REPLACE（SQLite 语法）
private async Task SetMetadata(IPluginDatabase db, string key, string value)
{
    var keyParam = db.CreateParameter("@key", key);
    var valueParam = db.CreateParameter("@value", value);
    await db.ExecuteNonQueryAsync(
        "INSERT OR REPLACE INTO metadata (key, value) VALUES (@key, @value)",
        keyParam, valueParam);
}
```

### 2.2 内部流程

调用 `GetDatabase(id, pluginClassName)` 时：

1. 生成 key = `{id}-{pluginClassName}`
2. 如果已缓存则返回缓存实例
3. 否则根据配置创建 `SqliteDatabase` 或 `SqlDatabase`
4. SQLite：数据库文件路径 = `{运行目录}/Database/{id}-{pluginClassName}.db`
5. 日志输出 `[数据库] SQLite数据库已创建: {path}`

### 2.3 API 速查

| 方法 | 同步 | 异步 | 说明 |
|------|------|------|------|
| 增删改 | `ExecuteNonQuery` | `ExecuteNonQueryAsync` | 返回受影响行数 |
| 查询单值 | `ExecuteScalar` | `ExecuteScalarAsync` | 返回 object |
| 查询字典列表 | `Query` | `QueryAsync` | 返回 `List<Dictionary<string, object>>` |
| 查询 DataTable | `QueryTable` | `QueryTableAsync` | 返回 DataTable |
| 创建参数 | `CreateParameter` | - | 返回 `DbParameter` |

### 2.4 坑：DBNull 处理

`Query` 返回的字典中，数据库 NULL 值会被转为 `null`（不是 `DBNull.Value`）。但 `ExecuteScalar` 可能返回 `DBNull.Value`：

```csharp
var result = await _db.ExecuteScalarAsync("SELECT value FROM metadata WHERE key = @key", param);
// result 可能是 DBNull.Value！
return result?.ToString();  // DBNull.Value.ToString() 返回 ""，不是 null
```

安全做法：

```csharp
if (result == null || result == DBNull.Value)
    return null;
return result.ToString();
```

### 2.5 坑：SQLite 与 SQL Server 语法差异

| 功能 | SQLite | SQL Server |
|------|--------|------------|
| 建表不存在才创建 | `CREATE TABLE IF NOT EXISTS` | 需要手动 `IF NOT EXISTS` 判断 |
| 插入或替换 | `INSERT OR REPLACE` | `MERGE` 或 `IF EXISTS` |
| 分页 | `LIMIT n` | `TOP n` 或 `OFFSET-FETCH` |

如果需要同时兼容，使用通用 SQL 或在配置中指定类型。

### 2.6 坑：数据库文件位置

数据库文件在 `{运行目录}/Database/` 下，不在 `Modules/` 下。部署时注意目录权限。

---

## 三、命令系统

### 3.1 注册命令

```csharp
_commandRegistry.RegisterCommand(
    "banlist",                      // commandName: 命令名（不含/）
    "创造服封禁列表",                // description: 简短描述
    "/banlist - 查询创造服封禁名单",  // helpText: 详细帮助
    new List<CommandParameter>(),    // parameters: 参数列表
    HandleBanListCommand,            // handler: 处理函数 Func<CommandContext, Task>
    "BanListPluginModule",           // moduleName: 模块类名（用于卸载时批量注销）
    CommandPermission.Everyone,      // permission: 权限级别
    CommandScope.GroupOnly,          // scope: 作用域
    requireAt: false,                // 是否需要@机器人
    requireSlash: true               // 是否需要/前缀
);
```

### 3.2 各参数详解

#### commandName

- 命令名，不含 `/` 前缀
- 内部会自动 `TrimStart('/').ToLower()`，所以传 `"BanList"` 和 `"banlist"` 和 `"/banlist"` 效果相同
- 存储时统一为小写，匹配时不区分大小写

#### description

- 简短描述，显示在命令列表中
- 如 `"创造服封禁列表"`

#### helpText

- 详细帮助文本，用户执行 `/help banlist` 时显示
- 可以包含多行说明和示例

#### parameters

```csharp
new List<CommandParameter>
{
    new CommandParameter
    {
        Name = "command",          // 参数名（用于 Parameters 字典的 key）
        Description = "要执行的命令", // 参数描述
        IsRequired = true,         // 是否必需
        Type = ParameterType.String, // 参数类型
        DefaultValue = null,       // 默认值（可选参数时使用）
        SubParameters = new List<CommandParameter>() // 嵌套子参数
    }
}
```

**ParameterType 枚举**：

| 类型 | 说明 | 解析方式 |
|------|------|----------|
| `String` | 字符串 | 原样 |
| `Integer` | 整数 | 尝试 int.Parse |
| `Float` | 浮点数 | 尝试 float.Parse |
| `Boolean` | 布尔 | 尝试 bool.Parse |
| `At` | @某人 | 从消息段提取 QQ 号 |
| `Reply` | 回复消息 | 从消息段提取消息 ID |

**注意**：`At` 和 `Reply` 类型的参数不从命令文本中解析，而是从消息段中提取。它们在参数列表中占位但不消耗命令参数的索引。

#### handler

```csharp
private async Task HandleCommand(CommandContext context)
{
    var message = context.Message;       // 原始消息对象
    var parameters = context.Parameters; // Dictionary<string, string>
    var rawCommand = context.RawCommand;  // 原始命令文本
    var client = context.Client;          // OneBotClient 实例
}
```

**注意**：`Parameters` 的值都是 `string` 类型，需要手动转换：

```csharp
if (parameters.TryGetValue("次数", out var countStr) && int.TryParse(countStr, out var count))
{
    // 使用 count
}
```

#### moduleName

- 模块类名，必须与插件类名一致
- 用于 `UnregisterModuleCommands(moduleName)` 批量注销
- **坑**：如果传错了，Exit 时命令不会被注销，导致命令残留

#### permission

| 值 | 说明 | 群聊判定 | 私聊判定 |
|----|------|----------|----------|
| `Everyone` | 所有人 | 始终通过 | 始终通过 |
| `GroupAdmin` | 群管理员+ | 群主/管理员/BotOwner | 仅 BotOwner |
| `Owner` | 群主+ | 群主/BotOwner | 仅 BotOwner |
| `BotOwner` | 仅机器人主人 | 仅 BotOwner | 仅 BotOwner |

**内部逻辑**：私聊时非 BotOwner 权限的命令只有 BotOwner 能用。这意味着 `GroupAdmin` 权限的命令在私聊中只有 BotOwner 能执行，不是任何私聊用户都能用。

#### scope

| 值 | 说明 |
|----|------|
| `All` | 私聊和群聊均可 |
| `PrivateOnly` | 仅私聊 |
| `GroupOnly` | 仅群聊 |

**坑**：如果命令依赖 `message.GroupId`，必须用 `GroupOnly`，否则私聊时 `GroupId` 为 null。

#### requireAt 和 requireSlash

| requireAt | requireSlash | 触发方式 | 适用场景 |
|-----------|--------------|----------|----------|
| false | true | `/命令` | 标准命令（默认） |
| true | false | `@机器人 命令` | 需要@的命令 |
| true | true | `@机器人 /命令` | 严格触发 |
| **false** | **false** | 直接输入命令名 | 无前缀命令 |

**无前缀命令（requireAt=false, requireSlash=false）的坑**：

- 命令会对所有消息进行前缀匹配，容易误触发
- 必须确保命令名足够独特（如 `#服务器`、`#玩家列表`）
- 不要用常见词如 `help`、`status` 等作为无前缀命令名

### 3.3 命令匹配内部流程

当用户发送消息时：

1. `MorningCatBot.HandleMessageAsync` 接收消息
2. 清理文本：移除 CQ 回复标签、移除@段
3. 调用 `CommandRegistry.ExecuteCommandAsync(message, text)`
4. 内部流程：
   - 提取第一个空格前的部分作为命令名
   - 判断是否以 `/` 开头 → `hasSlash`
   - `TrimStart('/')` + `ToLower()` → `commandName`
   - 在字典中查找 `commandName`
   - 检查 `RequireAt`：需要@但没@ → 返回 false
   - 检查 `RequireSlash`：需要/但没有 → 返回 false
   - 检查权限 → 不通过则发送权限不足消息
   - 检查作用域 → 不通过则发送作用域拒绝消息
   - 验证参数 → 不通过则发送参数错误消息
   - 执行 handler

5. 如果 `ExecuteCommandAsync` 返回 false → 触发 `OnUnhandledMessage` 事件

### 3.4 坑：命令名大小写

注册时自动转小写存储，匹配时也自动转小写。所以用户输入 `/BanList` 和 `/banlist` 效果相同。

### 3.5 坑：命令重复注册

如果两个插件注册了同名命令，后注册的会被拒绝（日志显示 `命令 'xxx' 已存在，无法重复注册`），但不会报错。先注册的命令生效。

### 3.6 坑：Exit 不注销命令

```csharp
public override async Task Exit()
{
    // 必须调用！否则命令残留
    _commandRegistry?.UnregisterModuleCommands("MyPluginModule");
    await Task.CompletedTask;
}
```

如果忘记调用 `UnregisterModuleCommands`，插件卸载后命令仍然存在，但 handler 引用的插件实例已被释放，执行时会抛异常。

### 3.7 坑：handler 中的异常

handler 中抛出的异常会被 `ExecuteCommandAsync` 的 try-catch 捕获：

- 内置模块：日志输出 `执行命令 'xxx' 失败: {ex.Message}`
- 插件模块：日志输出 `插件命令 'xxx' (模块: {moduleName}) 执行失败: {ex.Message}` + 堆栈追踪

但**不会向用户发送任何提示**。如果需要用户感知错误，在 handler 中自行 try-catch 并发送消息。

---

## 四、插件加载与依赖

### 4.1 加载流程

```
1. 加载 Modules/Library/*.dll（独立 AssemblyLoadContext，可卸载）
2. 扫描 Modules/*.dll（流加载，不可卸载）
3. 通过反射获取 GetDependencies() 和 GetLibraryDependencies()
4. 通过反射获取 [PluginMetadata] 特性
5. 构建依赖图，拓扑排序
6. 按顺序初始化每个模块：
   a. 依赖注入（按类型匹配属性/字段）
   b. 调用 Init()
7. 输出加载结果
```

### 4.2 坑：NuGet 依赖未声明导致加载失败

**这是最常见的坑**。如果插件项目通过 NuGet 引用了第三方包（如 `SixLabors.ImageSharp`、`Fleck`），编译后这些 DLL 会在插件输出目录中。但部署到服务器时，这些 DLL 不在 `Modules/Library/` 目录下，导致运行时找不到程序集。

**症状**：
```
LoadFailed:MyPlugin.dll|Unable to load one or more of the requested types.
Could not load file or assembly 'SixLabors.ImageSharp, Version=...'.
```

**解决方案**：

1. 将 NuGet 包的 DLL 复制到 `Modules/Library/` 目录
2. 在插件中声明库依赖：

```csharp
public override IEnumerable<string> GetLibraryDependencies()
{
    return new[] { "SixLabors.ImageSharp.dll", "SixLabors.ImageSharp.Drawing.dll" };
}
```

3. 或者使用带 `Private=false` 的引用方式，让编译输出不包含这些 DLL（由框架管理）

### 4.3 坑：插件类名与文件名不一致

模块管理器通过反射扫描 DLL 中的类，类名是模块的唯一标识。如果两个 DLL 中有同名类，只有先加载的会生效。

### 4.4 坑：插件签名验证

框架会对 `Modules/` 目录下的 DLL 进行签名验证。验证失败的 DLL 会被重命名为 `.dll.disabled`。

如果开发时遇到插件被禁用，检查日志中是否有 `签名验证失败` 的记录。

### 4.5 坑：AssemblyLoadContext 隔离

插件在独立的 `AssemblyLoadContext` 中加载，这意味着：

- 同一类型在不同 ALC 中可能被视为不同类型
- 插件间的强类型交互需要通过 `Modules/Library/` 中的共享接口 DLL
- 反射调用不受 ALC 隔离限制

---

## 五、依赖注入

### 5.1 可注入的服务

| 服务类型 | 约定属性名 | 说明 |
|----------|-----------|------|
| `OneBotClient` | `Client` | OneBot 客户端，发消息/调 API |
| `CommandRegistry` | `CommandRegistry` | 命令注册表 |
| `PluginConfigManager` | `ConfigManager` | 插件配置管理器 |
| `MorningCatBot` | `Bot` | 机器人主类 |
| `ModuleManager` | - | 模块管理器 |
| `ConfigManager` | - | 主配置管理器（非插件配置） |
| `PluginCommandAPI` | - | 插件命令 API |
| `PluginDatabaseAPI` | - | 插件数据库 API |

### 5.2 注入规则

- **类型匹配**：属性的类型必须精确匹配上表中的类型
- **必须有 public set**：`{ get; private set; }` 不会被注入
- **属性名无关**：可以叫 `Client` 也可以叫 `MyClient`，只要类型是 `OneBotClient`

### 5.3 坑：Client 重连后引用失效

WebSocket 重连后，框架会自动更新所有插件的 `Client` 属性。但如果在 Init 中缓存了引用：

```csharp
// 错误
private OneBotClient _cachedClient;
public override async Task Init()
{
    _cachedClient = Client;  // 重连后此引用过期！
}

// 正确：始终通过属性访问
private OneBotClient _client;
public OneBotClient Client
{
    get => _client;
    set => _client = value;  // 框架重连时自动调用 set
}
```

### 5.4 坑：注入顺序

依赖注入在 `Init()` 调用之前完成。所以 `Init()` 中可以安全使用注入的属性，但构造函数中不能（还是 null）。

---

## 六、避坑索引（按症状速查）

### 插件加载类

| 症状 | 可能原因 | 解决方案 |
|------|----------|----------|
| 插件 DLL 存在但不被加载 | 签名验证失败，被重命名为 `.disabled` | 检查日志，确认签名 |
| `LoadFailed:xxx.dll\|Unable to load...` | NuGet 依赖的 DLL 不在 Library 目录 | 复制 DLL 到 Library + 声明 `GetLibraryDependencies` |
| `MissingLibraryDependency:Module->Lib` | `GetLibraryDependencies` 声明了但文件不存在 | 确保 DLL 在 `Modules/Library/` |
| `MissingPluginDependency:Module->Dep` | 依赖的插件不存在 | 安装依赖插件或移除依赖声明 |
| `CircularDependencyDetected` | A 依赖 B，B 依赖 A | 消除循环依赖 |
| `InitFailed:Module\|...` | Init 方法中抛出异常 | 检查 Init 逻辑 |
| 插件加载成功但属性全为 null | 依赖注入失败 | 检查属性类型和 set 访问器 |

### 配置类

| 症状 | 可能原因 | 解决方案 |
|------|----------|----------|
| 配置文件没生成 | 没传 `new MyConfig()` 作为 defaultValue | 传默认实例 |
| 配置文件存在但属性值全是默认 | YAML 键名与 C# 属性名不匹配 | 使用下划线命名或 `[YamlMember]` |
| 配置修改后不生效 | 修改了内存对象但没调用 `SetConfigAsync` | 修改后保存 |
| 反序列化异常但无报错 | 框架 catch 吞掉了异常 | 检查日志中 `加载配置文件失败` |
| YamlDotNet 版本冲突 | 插件 NuGet 引用的版本与框架不同 | 不要 NuGet 引用 YamlDotNet |

### 命令类

| 症状 | 可能原因 | 解决方案 |
|------|----------|----------|
| 命令注册成功但不响应 | `requireAt`/`requireSlash` 条件不满足 | 检查触发方式 |
| 无前缀命令不响应 | 旧版代码有强制拒绝逻辑 | 确保使用最新版框架 |
| 命令权限不足但用户是管理员 | `GroupAdmin` 权限在私聊中只有 BotOwner 能用 | 改用 `Everyone` 或在群聊中使用 |
| 命令重复注册失败 | 同名命令已存在 | 更换命令名 |
| 插件卸载后命令仍存在 | Exit 中没调用 `UnregisterModuleCommands` | 添加注销调用 |
| 命令执行报错但用户无感知 | handler 异常被框架捕获但不通知用户 | handler 中自行 try-catch 并发送消息 |
| `GroupId` 为 null 导致报错 | 命令作用域不是 `GroupOnly`，私聊时 GroupId 为 null | 使用 `GroupOnly` 或判空 |

### 数据库类

| 症状 | 可能原因 | 解决方案 |
|------|----------|----------|
| 数据库文件找不到 | 文件在 `{运行目录}/Database/` 不在 `Modules/` | 检查正确路径 |
| `ExecuteScalar` 返回空字符串而非 null | 返回了 `DBNull.Value` | 判断 `result == null \|\| result == DBNull.Value` |
| SQL 语法错误 | SQLite 和 SQL Server 语法差异 | 使用通用 SQL 或按类型分支 |
| 数据库锁 | SQLite 并发写入 | 使用异步方法 + 避免频繁写入 |

### 依赖注入类

| 症状 | 可能原因 | 解决方案 |
|------|----------|----------|
| 属性为 null | 类型不匹配或没有 public set | 精确匹配类型 + public set |
| 重连后发消息失败 | 缓存了旧的 Client 引用 | 始终通过属性访问 |
| 构造函数中属性为 null | 注入在 Init 之前，构造函数之后 | 在 Init 中使用注入属性 |

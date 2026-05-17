package com.newspaper;

import com.newspaper.abstraction.Paper120;
import com.newspaper.abstraction.Paper121;
import com.newspaper.abstraction.PlatformAbstraction;
import com.newspaper.config.ConfigManager;
import com.newspaper.handler.*;
import com.newspaper.i18n.I18nManager;
import com.newspaper.i18n.MessageKey;
import com.newspaper.ws.MessageDispatcher;
import com.newspaper.ws.NewspaperWebSocketServer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.logging.Logger;

public class NewspaperPlugin extends JavaPlugin {

    private static NewspaperPlugin instance;

    private ConfigManager configManager;
    private I18nManager i18nManager;
    private PlatformAbstraction platform;
    private NewspaperWebSocketServer wsServer;
    private MessageDispatcher dispatcher;

    private ConsoleMessageHandler consoleMessageHandler;
    private ChatMessageHandler chatMessageHandler;
    private PlayerJoinHandler playerJoinHandler;
    private PlayerQuitHandler playerQuitHandler;

    @Override
    public void onEnable() {
        instance = this;
        Logger logger = getLogger();

        configManager = new ConfigManager(this, logger);
        configManager.loadConfig();

        i18nManager = new I18nManager(this, logger);
        i18nManager.init(configManager.getConfig().getLanguage());

        platform = detectPlatform();

        dispatcher = new MessageDispatcher(logger);
        registerHandlers();

        wsServer = new NewspaperWebSocketServer(configManager.getConfig(), dispatcher, logger, getDataFolder());
        wsServer.start();

        getCommand("newspaper").setExecutor(this::onCommand);

        logger.info(i18nManager.get(MessageKey.ENABLED));
    }

    @Override
    public void onDisable() {
        if (wsServer != null) {
            wsServer.stop();
        }

        if (dispatcher != null) {
            dispatcher.shutdown();
        }

        Logger logger = getLogger();
        if (i18nManager != null) {
            logger.info(i18nManager.get(MessageKey.DISABLED));
        } else {
            logger.info("Newspaper plugin disabled!");
        }

        instance = null;
    }

    private PlatformAbstraction detectPlatform() {
        String version = Bukkit.getMinecraftVersion();
        if (version.startsWith("1.21")) {
            getLogger().info("Detected Paper 1.21.x - using Paper121 abstraction");
            return new Paper121(this);
        }
        getLogger().info("Detected Paper 1.20.x - using Paper120 abstraction");
        return new Paper120(this);
    }

    private void registerHandlers() {
        consoleMessageHandler = new ConsoleMessageHandler(platform);
        chatMessageHandler = new ChatMessageHandler(platform, this);
        playerJoinHandler = new PlayerJoinHandler(platform, this);
        playerQuitHandler = new PlayerQuitHandler(platform, this);

        dispatcher.registerHandler(OperationType.CONSOLE_MESSAGE, consoleMessageHandler);
        dispatcher.registerHandler(OperationType.CHAT_MESSAGE, chatMessageHandler);
        dispatcher.registerHandler(OperationType.COMMAND, new CommandHandler(platform));
        dispatcher.registerHandler(OperationType.COMMAND2, new Command2Handler(platform));
        dispatcher.registerHandler(OperationType.PLAYER_JOIN, playerJoinHandler);
        dispatcher.registerHandler(OperationType.PLAYER_QUIT, playerQuitHandler);
        dispatcher.registerHandler(OperationType.ONLINE_PLAYERS, new OnlinePlayersHandler(platform));
        dispatcher.registerHandler(OperationType.SERVER_INFO, new ServerInfoHandler(platform));
        dispatcher.registerHandler(OperationType.CONFIG_MODIFY, new ConfigModifyHandler(configManager));
        dispatcher.registerHandler(OperationType.CONFIG_RELOAD, new ConfigReloadHandler(configManager, this::onConfigReload));
        dispatcher.registerHandler(OperationType.SHUTDOWN, new ShutdownHandler(this));
        dispatcher.registerHandler(OperationType.CONSOLE_BROADCAST, new ConsoleBroadcastHandler());
    }

    private void onConfigReload() {
        i18nManager.loadLanguage(configManager.getConfig().getLanguage());

        wsServer.stop();
        wsServer = new NewspaperWebSocketServer(configManager.getConfig(), dispatcher, getLogger(), getDataFolder());
        wsServer.start();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("newspaper.admin")) {
            sender.sendMessage(i18nManager.get(MessageKey.NO_PERMISSION));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(i18nManager.get(MessageKey.INVALID_ARGS));
            return true;
        }

        if ("reload".equalsIgnoreCase(args[0])) {
            configManager.reloadConfig();
            onConfigReload();
            sender.sendMessage(i18nManager.get(MessageKey.CONFIG_RELOADED));
            return true;
        }

        sender.sendMessage(i18nManager.get(MessageKey.INVALID_ARGS));
        return true;
    }

    public static NewspaperPlugin getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public I18nManager getI18nManager() {
        return i18nManager;
    }

    public PlatformAbstraction getPlatform() {
        return platform;
    }

    public NewspaperWebSocketServer getWsServer() {
        return wsServer;
    }
}
package com.newspaper.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ConfigManager {

    private final Plugin plugin;
    private final Logger logger;
    private final File configFile;
    private NewspaperConfig config;
    private FileConfiguration fileConfig;

    public ConfigManager(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
        this.config = new NewspaperConfig();
    }

    public void loadConfig() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }

        fileConfig = YamlConfiguration.loadConfiguration(configFile);

        InputStream defaultConfigStream = plugin.getResource("config.yml");
        if (defaultConfigStream != null) {
            fileConfig.setDefaults(
                    YamlConfiguration.loadConfiguration(new InputStreamReader(defaultConfigStream, StandardCharsets.UTF_8))
            );
        }

        loadFromYaml();

        logger.info("Configuration loaded (port=" + config.getPort()
                + ", encryption=" + config.getEncryption()
                + ", ipv6=" + config.isIpv6()
                + ", language=" + config.getLanguage() + ")");
    }

    public void reloadConfig() {
        fileConfig = YamlConfiguration.loadConfiguration(configFile);

        InputStream defaultConfigStream = plugin.getResource("config.yml");
        if (defaultConfigStream != null) {
            fileConfig.setDefaults(
                    YamlConfiguration.loadConfiguration(new InputStreamReader(defaultConfigStream, StandardCharsets.UTF_8))
            );
        }

        loadFromYaml();

        logger.info("Configuration reloaded");
    }

    private void loadFromYaml() {
        config.setPort(fileConfig.getInt("port", 8080));
        config.setUsername(fileConfig.getString("username", "admin"));
        config.setPassword(fileConfig.getString("password", "newspaper"));
        config.setIpv6(fileConfig.getBoolean("ipv6", false));
        config.setLanguage(fileConfig.getString("language", "en"));
        config.setEncryption(fileConfig.getString("encryption", "chap-iem"));
    }

    public void saveConfig() {
        fileConfig.set("port", config.getPort());
        fileConfig.set("username", config.getUsername());
        fileConfig.set("password", config.getPassword());
        fileConfig.set("ipv6", config.isIpv6());
        fileConfig.set("language", config.getLanguage());
        fileConfig.set("encryption", config.getEncryption());

        try {
            fileConfig.save(configFile);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to save config: " + e.getMessage(), e);
        }
    }

    public NewspaperConfig getConfig() {
        return config;
    }
}
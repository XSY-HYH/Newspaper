package com.newspaper.i18n;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class I18nManager {

    private final Plugin plugin;
    private final Logger logger;
    private final File langFolder;
    private final Map<String, String> messages = new HashMap<>();
    private String currentLanguage = "en";

    private static final String[] SUPPORTED_LANGUAGES = {"en", "zh"};

    public I18nManager(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        this.langFolder = new File(plugin.getDataFolder(), "lang");
    }

    public void init(String language) {
        this.currentLanguage = language;

        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }

        for (String lang : SUPPORTED_LANGUAGES) {
            ensureLanguageFile(lang);
        }

        loadLanguage(language);
    }

    private void ensureLanguageFile(String lang) {
        File langFile = new File(langFolder, lang + ".yml");

        if (!langFile.exists()) {
            extractLanguageFile(lang);
            logger.info("Created language file: " + lang + ".yml");
            return;
        }

        InputStream jarResource = plugin.getResource("lang/" + lang + ".yml");
        if (jarResource == null) {
            return;
        }

        YamlConfiguration jarConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(jarResource, StandardCharsets.UTF_8));
        YamlConfiguration fileConfig = YamlConfiguration.loadConfiguration(langFile);

        Set<String> jarKeys = jarConfig.getKeys(true);
        Set<String> fileKeys = fileConfig.getKeys(true);

        if (!fileKeys.containsAll(jarKeys)) {
            extractLanguageFile(lang);
            logger.info("Language file was incomplete, regenerated: " + lang + ".yml");
        }
    }

    private void extractLanguageFile(String lang) {
        InputStream resource = plugin.getResource("lang/" + lang + ".yml");
        if (resource == null) {
            logger.log(Level.WARNING, "Built-in language resource not found: lang/" + lang + ".yml");
            return;
        }

        File langFile = new File(langFolder, lang + ".yml");
        try {
            plugin.saveResource("lang/" + lang + ".yml", true);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to extract language file: " + lang + ".yml", e);
        }
    }

    public void loadLanguage(String language) {
        messages.clear();
        this.currentLanguage = language;

        File langFile = new File(langFolder, language + ".yml");
        if (!langFile.exists()) {
            logger.log(Level.WARNING, "Language file not found: " + language + ".yml, falling back to en");
            langFile = new File(langFolder, "en.yml");
            this.currentLanguage = "en";
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(langFile);

        for (MessageKey key : MessageKey.values()) {
            String value = config.getString(key.getPath());
            if (value != null) {
                messages.put(key.getPath(), ChatColor.translateAlternateColorCodes('&', value));
            }
        }

        logger.info("Loaded language: " + currentLanguage + " (" + messages.size() + " messages)");
    }

    public String get(MessageKey key) {
        return messages.getOrDefault(key.getPath(), key.getPath());
    }

    public String get(MessageKey key, Map<String, String> placeholders) {
        String message = get(key);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return message;
    }

    public String getCurrentLanguage() {
        return currentLanguage;
    }
}
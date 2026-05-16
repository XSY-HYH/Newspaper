package com.newspaper.handler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.newspaper.config.ConfigManager;

public class ConfigReloadHandler implements OperationHandler {

    private static final Gson GSON = new Gson();
    private final ConfigManager configManager;
    private final Runnable onReload;

    public ConfigReloadHandler(ConfigManager configManager, Runnable onReload) {
        this.configManager = configManager;
        this.onReload = onReload;
    }

    @Override
    public String handle(JsonObject data) {
        try {
            configManager.reloadConfig();
            onReload.run();

            JsonObject response = new JsonObject();
            response.addProperty("status", "ok");
            response.addProperty("message", "Configuration reloaded successfully");
            response.addProperty("port", configManager.getConfig().getPort());
            response.addProperty("ipv6", configManager.getConfig().isIpv6());
            response.addProperty("language", configManager.getConfig().getLanguage());
            return GSON.toJson(response);
        } catch (Exception e) {
            return buildError("Failed to reload config: " + e.getMessage());
        }
    }

    private String buildError(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "error");
        response.addProperty("message", message);
        return GSON.toJson(response);
    }
}
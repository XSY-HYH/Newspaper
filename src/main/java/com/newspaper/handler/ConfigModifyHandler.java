package com.newspaper.handler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.newspaper.config.ConfigManager;

public class ConfigModifyHandler implements OperationHandler {

    private static final Gson GSON = new Gson();
    private final ConfigManager configManager;

    public ConfigModifyHandler(ConfigManager configManager) {
        this.configManager = configManager;
    }

    @Override
    public String handle(JsonObject data) {
        if (data.size() == 0) {
            return buildError("No configuration fields provided");
        }

        try {
            StringBuilder changes = new StringBuilder();

            if (data.has("port")) {
                int port = data.get("port").getAsInt();
                if (port < 1 || port > 65535) {
                    return buildError("Port must be between 1 and 65535");
                }
                configManager.getConfig().setPort(port);
                changes.append("port=").append(port).append("; ");
            }

            if (data.has("username")) {
                String username = data.get("username").getAsString();
                if (username.isEmpty()) {
                    return buildError("Username cannot be empty");
                }
                configManager.getConfig().setUsername(username);
                changes.append("username changed; ");
            }

            if (data.has("password")) {
                String password = data.get("password").getAsString();
                if (password.isEmpty()) {
                    return buildError("Password cannot be empty");
                }
                configManager.getConfig().setPassword(password);
                changes.append("password changed; ");
            }

            if (data.has("ipv6")) {
                boolean ipv6 = data.get("ipv6").getAsBoolean();
                configManager.getConfig().setIpv6(ipv6);
                changes.append("ipv6=").append(ipv6).append("; ");
            }

            if (data.has("language")) {
                String language = data.get("language").getAsString();
                if (!language.matches("^[a-z]{2}$")) {
                    return buildError("Language must be a 2-letter code (e.g., 'en', 'zh')");
                }
                configManager.getConfig().setLanguage(language);
                changes.append("language=").append(language).append("; ");
            }

            configManager.saveConfig();

            JsonObject response = new JsonObject();
            response.addProperty("status", "ok");
            response.addProperty("changes", changes.toString().trim());
            response.addProperty("message", "Configuration updated. Use config_reload to apply changes.");
            return GSON.toJson(response);
        } catch (Exception e) {
            return buildError("Failed to modify config: " + e.getMessage());
        }
    }

    private String buildError(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "error");
        response.addProperty("message", message);
        return GSON.toJson(response);
    }
}
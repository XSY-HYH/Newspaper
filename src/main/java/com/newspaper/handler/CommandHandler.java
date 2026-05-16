package com.newspaper.handler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.newspaper.abstraction.PlatformAbstraction;
import org.bukkit.Bukkit;

public class CommandHandler implements OperationHandler {

    private static final Gson GSON = new Gson();
    private final PlatformAbstraction platform;

    public CommandHandler(PlatformAbstraction platform) {
        this.platform = platform;
    }

    @Override
    public String handle(JsonObject data) {
        if (!data.has("command")) {
            return buildError("Missing 'command' field");
        }

        String command = data.get("command").getAsString();

        try {
            boolean success = platform.dispatchConsoleCommand(command);

            JsonObject response = new JsonObject();
            response.addProperty("status", "ok");
            response.addProperty("command", command);
            response.addProperty("success", success);
            return GSON.toJson(response);
        } catch (Exception e) {
            return buildError("Command execution failed: " + e.getMessage());
        }
    }

    private String buildError(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "error");
        response.addProperty("message", message);
        return GSON.toJson(response);
    }
}
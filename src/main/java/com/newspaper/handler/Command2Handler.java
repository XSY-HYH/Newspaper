package com.newspaper.handler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.newspaper.abstraction.PlatformAbstraction;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class Command2Handler implements OperationHandler {

    private static final Gson GSON = new Gson();
    private final PlatformAbstraction platform;

    public Command2Handler(PlatformAbstraction platform) {
        this.platform = platform;
    }

    @Override
    public String handle(JsonObject data) {
        if (!data.has("player")) {
            return buildError("Missing 'player' field");
        }
        if (!data.has("command")) {
            return buildError("Missing 'command' field");
        }

        String playerName = data.get("player").getAsString();
        String command = data.get("command").getAsString();

        Player player = Bukkit.getPlayer(playerName);
        if (player == null) {
            return buildError("Player not found: " + playerName);
        }

        try {
            boolean success = platform.dispatchPlayerCommand(player, command);

            JsonObject response = new JsonObject();
            response.addProperty("status", "ok");
            response.addProperty("player", playerName);
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
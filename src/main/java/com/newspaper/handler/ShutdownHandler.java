package com.newspaper.handler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public class ShutdownHandler implements OperationHandler {

    private static final Gson GSON = new Gson();
    private final Plugin plugin;

    public ShutdownHandler(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String handle(JsonObject data) {
        String reason = data.has("reason") ? data.get("reason").getAsString() : "Shutdown requested via Newspaper WebSocket";

        JsonObject response = new JsonObject();
        response.addProperty("status", "ok");
        response.addProperty("message", "Server shutdown initiated");

        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.shutdown());

        return GSON.toJson(response);
    }
}
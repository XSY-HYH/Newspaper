package com.newspaper.handler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;

public class ConsoleBroadcastHandler implements OperationHandler {

    private static final Gson GSON = new Gson();
    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    @Override
    public String handle(JsonObject data) {
        if (!data.has("message")) {
            return buildError("Missing 'message' field");
        }

        String message = data.get("message").getAsString();

        try {
            Component component = SERIALIZER.deserialize(message);
            Bukkit.broadcast(component);

            JsonObject response = new JsonObject();
            response.addProperty("status", "ok");
            response.addProperty("message", "Broadcast sent");
            return GSON.toJson(response);
        } catch (Exception e) {
            return buildError("Failed to broadcast: " + e.getMessage());
        }
    }

    private String buildError(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "error");
        response.addProperty("message", message);
        return GSON.toJson(response);
    }
}
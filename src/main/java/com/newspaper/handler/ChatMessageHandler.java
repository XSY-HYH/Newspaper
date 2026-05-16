package com.newspaper.handler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.newspaper.abstraction.PlatformAbstraction;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.Plugin;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ChatMessageHandler implements OperationHandler, Listener {

    private static final Gson GSON = new Gson();
    private final Queue<String> messageQueue = new ConcurrentLinkedQueue<>();
    private final PlatformAbstraction platform;
    private final Plugin plugin;
    private boolean subscribed = false;

    public ChatMessageHandler(PlatformAbstraction platform, Plugin plugin) {
        this.platform = platform;
        this.plugin = plugin;
    }

    @Override
    public String handle(JsonObject data) {
        String action = data.has("action") ? data.get("action").getAsString() : "subscribe";

        if ("subscribe".equals(action)) {
            return subscribe();
        } else if ("unsubscribe".equals(action)) {
            return unsubscribe();
        } else if ("poll".equals(action)) {
            return poll();
        }

        return buildError("Unknown action: " + action);
    }

    private String subscribe() {
        if (subscribed) {
            return buildSuccess("Already subscribed");
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);
        subscribed = true;
        return buildSuccess("Subscribed to chat messages");
    }

    private String unsubscribe() {
        if (!subscribed) {
            return buildSuccess("Not subscribed");
        }

        HandlerList.unregisterAll(this);
        subscribed = false;
        return buildSuccess("Unsubscribed from chat messages");
    }

    private String poll() {
        JsonObject response = new JsonObject();
        response.addProperty("status", "ok");

        StringBuilder messages = new StringBuilder();
        String msg;
        int count = 0;
        while ((msg = messageQueue.poll()) != null && count < 100) {
            if (count > 0) {
                messages.append("\n");
            }
            messages.append(msg);
            count++;
        }

        response.addProperty("messages", messages.toString());
        response.addProperty("count", count);
        return GSON.toJson(response);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        JsonObject msg = new JsonObject();
        msg.addProperty("player", event.getPlayer().getName());
        msg.addProperty("display_name", platform.getPlayerDisplayName(event.getPlayer()));
        msg.addProperty("message", event.getMessage());
        msg.addProperty("timestamp", System.currentTimeMillis());

        messageQueue.offer(GSON.toJson(msg));
        while (messageQueue.size() > 1000) {
            messageQueue.poll();
        }
    }

    @Override
    public void shutdown() {
        unsubscribe();
        messageQueue.clear();
    }

    private String buildSuccess(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "ok");
        response.addProperty("message", message);
        return GSON.toJson(response);
    }

    private String buildError(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "error");
        response.addProperty("message", message);
        return GSON.toJson(response);
    }
}
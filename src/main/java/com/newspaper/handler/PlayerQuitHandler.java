package com.newspaper.handler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.newspaper.abstraction.PlatformAbstraction;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class PlayerQuitHandler implements OperationHandler, Listener {

    private static final Gson GSON = new Gson();
    private final Queue<String> eventQueue = new ConcurrentLinkedQueue<>();
    private final PlatformAbstraction platform;
    private final Plugin plugin;
    private boolean subscribed = false;

    public PlayerQuitHandler(PlatformAbstraction platform, Plugin plugin) {
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
        return buildSuccess("Subscribed to player quit events");
    }

    private String unsubscribe() {
        if (!subscribed) {
            return buildSuccess("Not subscribed");
        }

        HandlerList.unregisterAll(this);
        subscribed = false;
        return buildSuccess("Unsubscribed from player quit events");
    }

    private String poll() {
        JsonObject response = new JsonObject();
        response.addProperty("status", "ok");

        StringBuilder events = new StringBuilder();
        String event;
        int count = 0;
        while ((event = eventQueue.poll()) != null && count < 100) {
            if (count > 0) {
                events.append("\n");
            }
            events.append(event);
            count++;
        }

        response.addProperty("events", events.toString());
        response.addProperty("count", count);
        return GSON.toJson(response);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        JsonObject msg = new JsonObject();
        msg.addProperty("player", event.getPlayer().getName());
        msg.addProperty("display_name", platform.getPlayerDisplayName(event.getPlayer()));
        msg.addProperty("uuid", event.getPlayer().getUniqueId().toString());
        msg.addProperty("timestamp", System.currentTimeMillis());

        eventQueue.offer(GSON.toJson(msg));
        while (eventQueue.size() > 1000) {
            eventQueue.poll();
        }
    }

    @Override
    public void shutdown() {
        unsubscribe();
        eventQueue.clear();
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
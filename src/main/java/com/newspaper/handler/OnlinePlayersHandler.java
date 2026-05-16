package com.newspaper.handler;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.newspaper.abstraction.PlatformAbstraction;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class OnlinePlayersHandler implements OperationHandler {

    private static final Gson GSON = new Gson();
    private final PlatformAbstraction platform;

    public OnlinePlayersHandler(PlatformAbstraction platform) {
        this.platform = platform;
    }

    @Override
    public String handle(JsonObject data) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "ok");

        JsonArray players = new JsonArray();
        for (Player player : Bukkit.getOnlinePlayers()) {
            JsonObject playerObj = new JsonObject();
            playerObj.addProperty("name", player.getName());
            playerObj.addProperty("display_name", platform.getPlayerDisplayName(player));
            playerObj.addProperty("uuid", player.getUniqueId().toString());
            playerObj.addProperty("world", player.getWorld().getName());
            playerObj.addProperty("gamemode", player.getGameMode().name());
            playerObj.addProperty("ping", player.getPing());
            playerObj.addProperty("ip", platform.getPlayerAddress(player));
            players.add(playerObj);
        }

        response.add("players", players);
        response.addProperty("online_count", players.size());
        response.addProperty("max_players", Bukkit.getMaxPlayers());

        return GSON.toJson(response);
    }
}
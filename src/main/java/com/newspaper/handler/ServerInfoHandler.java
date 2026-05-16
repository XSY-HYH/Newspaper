package com.newspaper.handler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.newspaper.abstraction.PlatformAbstraction;
import org.bukkit.Bukkit;

public class ServerInfoHandler implements OperationHandler {

    private static final Gson GSON = new Gson();
    private final PlatformAbstraction platform;

    public ServerInfoHandler(PlatformAbstraction platform) {
        this.platform = platform;
    }

    @Override
    public String handle(JsonObject data) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "ok");

        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("server_version", platform.getServerVersion());
        serverInfo.addProperty("server_name", Bukkit.getName());
        serverInfo.addProperty("bukkit_version", Bukkit.getBukkitVersion());
        serverInfo.addProperty("minecraft_version", Bukkit.getMinecraftVersion());
        serverInfo.addProperty("api_version", Bukkit.getUnsafe().getClass().getPackage().getName());

        JsonObject javaInfo = new JsonObject();
        javaInfo.addProperty("java_version", System.getProperty("java.version"));
        javaInfo.addProperty("java_vendor", System.getProperty("java.vendor"));
        javaInfo.addProperty("java_home", System.getProperty("java.home"));
        javaInfo.addProperty("os_name", System.getProperty("os.name"));
        javaInfo.addProperty("os_arch", System.getProperty("os.arch"));
        javaInfo.addProperty("os_version", System.getProperty("os.version"));
        javaInfo.addProperty("available_processors", runtime.availableProcessors());

        JsonObject memoryInfo = new JsonObject();
        memoryInfo.addProperty("used_memory", usedMemory);
        memoryInfo.addProperty("used_memory_mb", usedMemory / (1024 * 1024));
        memoryInfo.addProperty("total_memory", totalMemory);
        memoryInfo.addProperty("total_memory_mb", totalMemory / (1024 * 1024));
        memoryInfo.addProperty("max_memory", maxMemory);
        memoryInfo.addProperty("max_memory_mb", maxMemory / (1024 * 1024));
        memoryInfo.addProperty("free_memory", freeMemory);
        memoryInfo.addProperty("free_memory_mb", freeMemory / (1024 * 1024));

        JsonObject worldInfo = new JsonObject();
        worldInfo.addProperty("worlds", Bukkit.getWorlds().size());
        worldInfo.addProperty("online_players", Bukkit.getOnlinePlayers().size());
        worldInfo.addProperty("max_players", Bukkit.getMaxPlayers());

        response.add("server", serverInfo);
        response.add("java", javaInfo);
        response.add("memory", memoryInfo);
        response.add("world", worldInfo);

        return GSON.toJson(response);
    }
}
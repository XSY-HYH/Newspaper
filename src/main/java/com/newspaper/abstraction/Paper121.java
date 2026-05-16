package com.newspaper.abstraction;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.net.InetSocketAddress;

public class Paper121 implements PlatformAbstraction {

    @Override
    public String getServerVersion() {
        return Bukkit.getServer().getVersion();
    }

    @Override
    public String getPlayerDisplayName(Player player) {
        return player.getDisplayName();
    }

    @Override
    public String getPlayerAddress(Player player) {
        InetSocketAddress address = player.getAddress();
        return address != null ? address.getHostString() : "unknown";
    }

    @Override
    public boolean dispatchConsoleCommand(String command) {
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    @Override
    public boolean dispatchPlayerCommand(Player player, String command) {
        return Bukkit.dispatchCommand(player, command);
    }
}
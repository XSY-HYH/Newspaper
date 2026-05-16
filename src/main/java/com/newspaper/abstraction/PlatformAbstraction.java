package com.newspaper.abstraction;

import org.bukkit.entity.Player;

public interface PlatformAbstraction {

    String getServerVersion();

    String getPlayerDisplayName(Player player);

    String getPlayerAddress(Player player);

    boolean dispatchConsoleCommand(String command);

    boolean dispatchPlayerCommand(Player player, String command);
}
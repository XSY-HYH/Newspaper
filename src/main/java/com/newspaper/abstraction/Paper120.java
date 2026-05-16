package com.newspaper.abstraction;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Paper120 implements PlatformAbstraction {

    private final Plugin plugin;

    public Paper120(Plugin plugin) {
        this.plugin = plugin;
    }

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
        if (Bukkit.isPrimaryThread()) {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean(false);

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                result.set(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        return result.get();
    }

    @Override
    public boolean dispatchPlayerCommand(Player player, String command) {
        if (Bukkit.isPrimaryThread()) {
            return Bukkit.dispatchCommand(player, command);
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean(false);

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                result.set(Bukkit.dispatchCommand(player, command));
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        return result.get();
    }
}
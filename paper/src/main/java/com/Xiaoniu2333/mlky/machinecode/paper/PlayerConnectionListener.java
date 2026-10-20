package com.Xiaoniu2333.mlky.machinecode.paper;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Captures join/quit only. All expensive work is dispatched to a background executor; the login
 * and join path is never blocked by a hardware lookup.
 */
final class PlayerConnectionListener implements Listener {
    private final MachineCodePlugin plugin;

    PlayerConnectionListener(final MachineCodePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        this.plugin.playerJoined(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        this.plugin.playerQuit(event.getPlayer().getUniqueId());
    }
}

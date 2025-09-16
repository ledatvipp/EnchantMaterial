package org.ledat.enchantMaterial.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.ledat.enchantMaterial.DatabaseManager;

public class PlayerQuitListener implements Listener {
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Force save dữ liệu khi player thoát
        DatabaseManager.onPlayerQuit(event.getPlayer().getUniqueId());
     //   EnchantMaterial.getInstance().getLogger().info(
     //       "💾 Đã save dữ liệu cho " + event.getPlayer().getName() + " khi thoát"
     //   );
    }
}
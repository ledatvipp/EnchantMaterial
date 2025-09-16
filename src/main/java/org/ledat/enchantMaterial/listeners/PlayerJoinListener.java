package org.ledat.enchantMaterial.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.ledat.enchantMaterial.DatabaseManager;
import org.ledat.enchantMaterial.EnchantMaterial;

/**
 * Listener dùng để preload dữ liệu người chơi khi họ tham gia server.  
 * Việc load dữ liệu sớm giúp tránh lag khi block break cần truy vấn database lần đầu.
 */
public class PlayerJoinListener implements Listener {

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        EnchantMaterial.getInstance().getFortuneManager().warmup(e.getPlayer());
        DatabaseManager.getPlayerDataAsync(e.getPlayer().getUniqueId()); // ấm cache DB
    }
}
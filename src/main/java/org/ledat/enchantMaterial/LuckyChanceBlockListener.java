package org.ledat.enchantMaterial;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class LuckyChanceBlockListener implements Listener {

    private final EnchantMaterial plugin;
    // Cache để tránh truy cập config liên tục
    private final Map<String, List<RewardData>> rewardCache = new HashMap<>();
    private final Map<String, Double> permissionChanceCache = new HashMap<>();
    private String cachedPermissionPrefix;
    private double cachedDefaultChance;
    private long lastCacheUpdate = 0;
    private static final long CACHE_DURATION = 30000; // 30 giây

    public LuckyChanceBlockListener(EnchantMaterial plugin) {
        this.plugin = plugin;
        updateCache();
    }

    private void updateCache() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCacheUpdate < CACHE_DURATION) {
            return;
        }

        // Cache permission settings
        cachedPermissionPrefix = plugin.getConfig().getString("perm-chance.permission_prefix", "");
        cachedDefaultChance = plugin.getConfig().getDouble("perm-chance.values.default", 0.1);
        
        // Cache permission chances
        permissionChanceCache.clear();
        ConfigurationSection permSection = plugin.getConfig().getConfigurationSection("perm-chance.values");
        if (permSection != null) {
            for (String key : permSection.getKeys(false)) {
                permissionChanceCache.put(key, permSection.getDouble(key));
            }
        }

        // Cache rewards
        rewardCache.clear();
        ConfigurationSection rewardsSection = plugin.getLuckyBlockConfig().getConfigurationSection("rewards");
        if (rewardsSection != null) {
            for (String blockType : rewardsSection.getKeys(false)) {
                List<String> rewardStrings = rewardsSection.getStringList(blockType);
                List<RewardData> rewards = new ArrayList<>();
                
                for (String rewardString : rewardStrings) {
                    RewardData reward = parseRewardData(rewardString);
                    if (reward != null) {
                        rewards.add(reward);
                    }
                }
                rewardCache.put(blockType, rewards);
            }
        }
        
        lastCacheUpdate = currentTime;
    }

    private RewardData parseRewardData(String rewardString) {
        String[] data = rewardString.split(",");
        if (data.length < 3) return null;

        try {
            String type = data[0].split(":")[1].trim().toLowerCase();
            String value = data[1].split(":")[1].trim();
            double chance = Double.parseDouble(data[2].split(":")[1].trim());
            
            return new RewardData(type, value, chance);
        } catch (Exception e) {
            plugin.getLogger().warning("Lỗi khi parse reward data: " + rewardString);
            return null;
        }
    }

    public void handleLuckyChance(Player player, Block block) {
        Material blockType = block.getType();
        String blockTypeName = blockType.name();

        player.sendMessage("§eBạn đã kích hoạt Lucky Block: §6" + blockTypeName);

        updateCache(); // Cập nhật cache nếu cần
        List<RewardData> rewards = rewardCache.get(blockTypeName);

        if (rewards != null && !rewards.isEmpty()) {
            double playerChance = getPlayerLuckyChance(player);
            ThreadLocalRandom random = ThreadLocalRandom.current();

            for (RewardData reward : rewards) {
                if (random.nextDouble() * 100 < reward.chance * playerChance) {
                    executeReward(player, reward);
                }
            }
        } else {
            player.sendMessage("§cKhông có phần thưởng nào được cấu hình cho Lucky Block: §6" + blockTypeName);
        }
    }

    private void executeReward(Player player, RewardData reward) {
        switch (reward.type) {
            case "money":
                try {
                    double amount = Double.parseDouble(reward.value);
                    giveMoney(player, amount);
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("Invalid money amount: " + reward.value);
                }
                break;
            case "item":
                // Giả sử format là "item_name:amount"
                String[] itemData = reward.value.split(":");
                if (itemData.length >= 2) {
                    try {
                        String itemName = itemData[0];
                        int amount = Integer.parseInt(itemData[1]);
                        giveItem(player, itemName, amount);
                    } catch (NumberFormatException e) {
                        plugin.getLogger().warning("Invalid item data: " + reward.value);
                    }
                }
                break;
            case "command":
                String command = reward.value.replace("{player}", player.getName());
                executeCommand(player, command);
                break;
        }
    }

    public double getPlayerLuckyChance(Player player) {
        updateCache();
        
        // Kiểm tra permission từ cao xuống thấp
        for (Map.Entry<String, Double> entry : permissionChanceCache.entrySet()) {
            if (!"default".equals(entry.getKey()) && 
                player.hasPermission(cachedPermissionPrefix + entry.getKey())) {
                return entry.getValue();
            }
        }
        
        return cachedDefaultChance;
    }

    public void giveMoney(Player player, double amount) {
        plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "eco give " + player.getName() + " " + amount);
        player.sendMessage("§aBạn nhận được §6" + amount + "§a tiền!");
    }

    public void giveItem(Player player, String itemName, int amount) {
        Material material = Material.matchMaterial(itemName);
        if (material != null) {
            ItemStack item = new ItemStack(material, amount);
            player.getInventory().addItem(item);
            player.sendMessage("§aBạn nhận được §6" + amount + "x " + itemName + "§a!");
        } else {
            player.sendMessage("§cKhông thể xác định vật phẩm: " + itemName);
        }
    }

    public void executeCommand(Player player, String command) {
        plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command);
    }

    // Inner class để lưu trữ reward data
    private static class RewardData {
        final String type;
        final String value;
        final double chance;

        RewardData(String type, String value, double chance) {
            this.type = type;
            this.value = value;
            this.chance = chance;
        }
    }
}

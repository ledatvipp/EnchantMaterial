package org.ledat.enchantMaterial.rewards;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.ledat.enchantMaterial.DatabaseManager;
import org.ledat.enchantMaterial.EnchantMaterial;
import org.ledat.enchantMaterial.PlayerData;

import java.io.File;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LevelRewardsManager {
    
    private final EnchantMaterial plugin;
    private YamlConfiguration rewardsConfig;
    private File rewardsFile;
    
    // Pattern để nhận diện hex color
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    
    public LevelRewardsManager(EnchantMaterial plugin) {
        this.plugin = plugin;
        loadConfig();
    }
    
    private void loadConfig() {
        rewardsFile = new File(plugin.getDataFolder(), "level-rewards.yml");
        if (!rewardsFile.exists()) {
            plugin.saveResource("level-rewards.yml", false);
        }
        rewardsConfig = YamlConfiguration.loadConfiguration(rewardsFile);
    }
    
    public void reloadConfig() {
        rewardsConfig = YamlConfiguration.loadConfiguration(rewardsFile);
    }
    
    public YamlConfiguration getConfig() {
        return rewardsConfig;
    }
    
    public boolean hasClaimedReward(Player player, int level) {
        try {
            return DatabaseManager.hasClaimedReward(player.getUniqueId().toString(), level);
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }
    
    public boolean canClaimReward(Player player, int level) {
        // Lấy level từ database thay vì tính toán từ points
        int currentLevel;
        try {
            PlayerData playerData = DatabaseManager.getPlayerData(player.getUniqueId().toString());
            currentLevel = playerData.getLevel();
            
            // DEBUG: Thêm log để kiểm tra
           // player.sendMessage("§e[DEBUG] Current level: " + currentLevel + ", Required level: " + level);
            
        } catch (SQLException e) {
            e.printStackTrace();
          //  player.sendMessage("§c[ERROR] Không thể lấy dữ liệu player từ database!");
            return false;
        }
        
        // Kiểm tra điều kiện
        if (currentLevel < level) {
         //   player.sendMessage("§c[DEBUG] Level không đủ: " + currentLevel + " < " + level);
            return false;
        }
        
        // Kiểm tra đã claim chưa
        boolean hasClaimed = hasClaimedReward(player, level);
        
        return !hasClaimed;
    }
    
    /**
     * Phương thức utility để xử lý cả màu sắc cơ bản (&) và hex color (&#RRGGBB)
     */
    private String translateColors(String text) {
        if (text == null) return null;
        
        // Xử lý hex colors trước (cho Bukkit 1.16+)
        try {
            Matcher matcher = HEX_PATTERN.matcher(text);
            while (matcher.find()) {
                String hexCode = matcher.group(1);
                try {
                    // Thử sử dụng net.md_5.bungee.api.ChatColor cho Bukkit 1.16+
                    Class<?> bungeeColorClass = Class.forName("net.md_5.bungee.api.ChatColor");
                    Object colorObj = bungeeColorClass.getMethod("of", String.class).invoke(null, "#" + hexCode);
                    String replacement = colorObj.toString();
                    text = text.replace("&#" + hexCode, replacement);
                } catch (Exception e) {
                    // Nếu không có BungeeColor, sử dụng cách khác
                    String replacement = convertHexToLegacy(hexCode);
                    text = text.replace("&#" + hexCode, replacement);
                }
            }
        } catch (Exception e) {
            // Nếu có lỗi, bỏ qua hex color processing
        }
        
        // Sau đó xử lý màu sắc cơ bản
        return ChatColor.translateAlternateColorCodes('&', text);
    }
    
    /**
     * Chuyển đổi hex color thành màu legacy gần nhất (fallback cho phiên bản cũ)
     */
    private String convertHexToLegacy(String hexCode) {
        try {
            int rgb = Integer.parseInt(hexCode, 16);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            
            // Tìm màu legacy gần nhất dựa trên RGB
            if (r > 200 && g < 100 && b < 100) return "§c"; // Red
            if (r < 100 && g > 200 && b < 100) return "§a"; // Green
            if (r < 100 && g < 100 && b > 200) return "§9"; // Blue
            if (r > 200 && g > 200 && b < 100) return "§e"; // Yellow
            if (r > 150 && g < 100 && b > 150) return "§d"; // Light Purple
            if (r < 100 && g > 150 && b > 150) return "§b"; // Aqua
            if (r > 150 && g > 100 && b < 100) return "§6"; // Gold
            if (r > 150 && g > 150 && b > 150) return "§f"; // White
            if (r < 100 && g < 100 && b < 100) return "§8"; // Dark Gray
            return "§7"; // Gray (default)
        } catch (Exception e) {
            return "§7"; // Gray fallback
        }
    }
    
    public void claimReward(Player player, int level) {
        if (!canClaimReward(player, level)) {
            return;
        }
        
        // Kiểm tra lại một lần nữa để tránh race condition
        if (hasClaimedReward(player, level)) {
            String message = rewardsConfig.getString("messages.reward-already-claimed", "&c&lBạn đã nhận phần thưởng level %level% rồi!")
                    .replace("%level%", String.valueOf(level));
            player.sendMessage(translateColors(message));
            return;
        }
        
        try {
            // SỬA: Sử dụng synchronous method để đảm bảo database được cập nhật ngay
            DatabaseManager.claimReward(player.getUniqueId().toString(), level);
            
            // Thay thế phần cập nhật cache bằng:
            DatabaseManager.refreshClaimedRewardsCache(player.getUniqueId().toString());
            
            // Execute commands
            List<String> commands = rewardsConfig.getStringList("rewards." + level + ".commands");
            for (String command : commands) {
                String processedCommand = command.replace("%player%", player.getName());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processedCommand);
                });
            }
            
            // Give items
            List<String> items = rewardsConfig.getStringList("rewards." + level + ".items");
            for (String itemString : items) {
                ItemStack item = parseItemString(itemString);
                if (item != null) {
                    if (player.getInventory().firstEmpty() != -1) {
                        player.getInventory().addItem(item);
                    } else {
                        player.getWorld().dropItemNaturally(player.getLocation(), item);
                    }
                }
            }
            
            // Send success message với translateColors
            String message = rewardsConfig.getString("messages.reward-claimed", "&a&lBạn đã nhận phần thưởng level %level%!")
                    .replace("%level%", String.valueOf(level));
            player.sendMessage(translateColors(message));
            
        } catch (SQLException e) {
            e.printStackTrace();
            player.sendMessage(translateColors("&c&lLỗi khi nhận phần thưởng!"));
        }
    }
    
    private ItemStack parseItemString(String itemString) {
        try {
            String[] parts = itemString.split(":");
            if (parts.length < 2) return null;
            
            Material material = Material.valueOf(parts[0]);
            int amount = Integer.parseInt(parts[1]);
            
            ItemStack item = new ItemStack(material, amount);
            
            if (parts.length > 2) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    for (int i = 2; i < parts.length; i += 2) {
                        if (i + 1 < parts.length) {
                            String key = parts[i];
                            String value = parts[i + 1];
                            
                            if (key.equals("name")) {
                                meta.setDisplayName(translateColors(value));
                            } else if (key.equals("lore")) {
                                List<String> lore = Arrays.asList(value.split("\\|"));
                                List<String> coloredLore = new ArrayList<>();
                                for (String line : lore) {
                                    coloredLore.add(translateColors(line));
                                }
                                meta.setLore(coloredLore);
                            }
                        }
                    }
                    item.setItemMeta(meta);
                }
            }
            
            return item;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    public Set<Integer> getAvailableLevels() {
        ConfigurationSection rewardsSection = rewardsConfig.getConfigurationSection("rewards");
        if (rewardsSection == null) return new HashSet<>();
        
        Set<Integer> levels = new HashSet<>();
        for (String key : rewardsSection.getKeys(false)) {
            try {
                levels.add(Integer.parseInt(key));
            } catch (NumberFormatException ignored) {}
        }
        return levels;
    }
    
    public List<String> getCustomLore(int level) {
        return rewardsConfig.getStringList("rewards." + level + ".custom-lore");
    }
}
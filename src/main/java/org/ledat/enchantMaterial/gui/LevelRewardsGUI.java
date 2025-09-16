package org.ledat.enchantMaterial.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.ledat.enchantMaterial.EnchantMaterial;
import org.ledat.enchantMaterial.rewards.LevelRewardsManager;
import org.ledat.enchantMaterial.DatabaseManager;
import org.ledat.enchantMaterial.PlayerData;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.*;

public class LevelRewardsGUI implements Listener {
    
    private final EnchantMaterial plugin;
    private final LevelRewardsManager rewardsManager;
    private final Map<UUID, Integer> playerPages = new HashMap<>();
    
    // Pattern để nhận diện hex color
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    
    public LevelRewardsGUI(EnchantMaterial plugin, LevelRewardsManager rewardsManager) {
        this.plugin = plugin;
        this.rewardsManager = rewardsManager;
    }
    
    /**
     * Phương thức utility để xử lý cả màu sắc cơ bản (&) và hex color (&#RRGGBB)
     * Sử dụng reflection để hỗ trợ cả phiên bản cũ và mới của Bukkit
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
    
    public void openGUI(Player player, int page) {
        playerPages.put(player.getUniqueId(), page);
        
        String title = translateColors(
                rewardsManager.getConfig().getString("level-rewards.gui.title", "&6&lLevel Rewards"));
        Inventory gui = Bukkit.createInventory(null, 54, title + " - Trang " + (page + 1));
        
        // Thứ tự quan trọng: Border trước, Navigation sau
        addBorderItems(gui, player, page); // Truyền thêm tham số để biết có cần navigation không
        addNavigationItems(gui, player, page);
        addRewardItems(gui, player, page);
        
        player.openInventory(gui);
    }
    
    private void addBorderItems(Inventory gui, Player player, int page) {
        ItemStack border = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta borderMeta = border.getItemMeta();
        if (borderMeta != null) {
            borderMeta.setDisplayName(" ");
            border.setItemMeta(borderMeta);
        }
        
        // Tính toán xem có cần navigation không
        Set<Integer> availableLevels = rewardsManager.getAvailableLevels();
        int totalPages = (int) Math.ceil((double) availableLevels.size() / 36);
        boolean needPrevButton = page > 0;
        boolean needNextButton = page < totalPages - 1;
        
        // Top row (0-8)
        for (int i = 0; i <= 8; i++) {
            gui.setItem(i, border);
        }
        
        // Bottom row (45-53) với điều kiện
        for (int i = 45; i <= 53; i++) {
            boolean shouldPlaceBorder = true;
            
            // Không đặt border ở slot 45 nếu cần Previous button
            if (i == 45 && needPrevButton) {
                shouldPlaceBorder = false;
            }
            
            // Không đặt border ở slot 53 nếu cần Next button
            if (i == 53 && needNextButton) {
                shouldPlaceBorder = false;
            }
            
            if (shouldPlaceBorder) {
                gui.setItem(i, border);
            }
        }
    }
    
    private void addNavigationItems(Inventory gui, Player player, int page) {
        Set<Integer> availableLevels = rewardsManager.getAvailableLevels();
        List<Integer> sortedLevels = new ArrayList<>(availableLevels);
        Collections.sort(sortedLevels);
        
        int itemsPerPage = 36;
        int totalPages = (int) Math.ceil((double) sortedLevels.size() / itemsPerPage);
        
        // Previous page button
        if (page > 0) {
            ItemStack prevPage = new ItemStack(Material.RED_STAINED_GLASS_PANE);
            ItemMeta prevMeta = prevPage.getItemMeta();
            if (prevMeta != null) {
                prevMeta.setDisplayName(translateColors(
                        rewardsManager.getConfig().getString("level-rewards.navigation.previous-page.name", "&c&lTrang trước")));
                List<String> prevLore = rewardsManager.getConfig().getStringList("level-rewards.navigation.previous-page.lore");
                List<String> coloredPrevLore = new ArrayList<>();
                for (String line : prevLore) {
                    coloredPrevLore.add(translateColors(line));
                }
                prevMeta.setLore(coloredPrevLore);
                prevPage.setItemMeta(prevMeta);
            }
            gui.setItem(45, prevPage);
        }
        
        // Next page button
        if (page < totalPages - 1) {
            ItemStack nextPage = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
            ItemMeta nextMeta = nextPage.getItemMeta();
            if (nextMeta != null) {
                nextMeta.setDisplayName(translateColors(
                        rewardsManager.getConfig().getString("level-rewards.navigation.next-page.name", "&a&lTrang tiếp theo")));
                List<String> nextLore = rewardsManager.getConfig().getStringList("level-rewards.navigation.next-page.lore");
                List<String> coloredNextLore = new ArrayList<>();
                for (String line : nextLore) {
                    coloredNextLore.add(translateColors(line));
                }
                nextMeta.setLore(coloredNextLore);
                nextPage.setItemMeta(nextMeta);
            }
            gui.setItem(53, nextPage);
        }
    }
    
    private void addRewardItems(Inventory gui, Player player, int page) {
        Set<Integer> availableLevels = rewardsManager.getAvailableLevels();
        List<Integer> sortedLevels = new ArrayList<>(availableLevels);
        Collections.sort(sortedLevels);
        
        int itemsPerPage = 36; // 9x4 grid
        int startIndex = page * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, sortedLevels.size());
        
        // Lấy level từ database
        int currentLevel;
        try {
            PlayerData playerData = DatabaseManager.getPlayerData(player.getUniqueId().toString());
            currentLevel = playerData.getLevel();
        } catch (SQLException e) {
            e.printStackTrace();
            currentLevel = 0;
        }
        
        // Sắp xếp theo grid 9x4 (rows 1-4, cols 0-8)
        int itemCount = 0;
        for (int i = startIndex; i < endIndex; i++) {
            int level = sortedLevels.get(i);
            
            // Tính toán vị trí trong grid 9x4
            int row = itemCount / 9; // Row trong grid (0-3)
            int col = itemCount % 9; // Column trong grid (0-8)
            
            // Chuyển đổi sang slot thực tế (bắt đầu từ row 1)
            int slot = (row + 1) * 9 + col; // +1 để bỏ qua border row
            
            // Đảm bảo slot nằm trong khoảng hợp lệ
            if (slot >= 9 && slot <= 44) {
                ItemStack rewardItem = createRewardItem(player, level, currentLevel);
                gui.setItem(slot, rewardItem);
            }
            
            itemCount++;
        }
    }
    
    private ItemStack createRewardItem(Player player, int level, int currentLevel) {
        boolean claimed = rewardsManager.hasClaimedReward(player, level);
        boolean canClaim = currentLevel >= level;
        
        Material material;
        String nameKey;
        String loreKey;
        
        if (claimed) {
            material = Material.CHEST_MINECART;
            nameKey = "level-rewards.reward-item.claimed.name";
            loreKey = "level-rewards.reward-item.claimed.lore";
        } else if (canClaim) {
            material = Material.CHEST_MINECART;
            nameKey = "level-rewards.reward-item.available.name";
            loreKey = "level-rewards.reward-item.available.lore";
        } else {
            material = Material.MINECART;
            nameKey = "level-rewards.reward-item.locked.name";
            loreKey = "level-rewards.reward-item.locked.lore";
        }
        
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        
        if (meta != null) {
            String name = rewardsManager.getConfig().getString(nameKey, "&7Level " + level)
                    .replace("%level%", String.valueOf(level))
                    .replace("%current_level%", String.valueOf(currentLevel));
            meta.setDisplayName(translateColors(name));
            
            List<String> lore = new ArrayList<>();
            List<String> configLore = rewardsManager.getConfig().getStringList(loreKey);
            for (String line : configLore) {
                lore.add(translateColors(line
                        .replace("%level%", String.valueOf(level))
                        .replace("%current_level%", String.valueOf(currentLevel))));
            }
            
            // Add custom lore for rewards
            List<String> customLore = rewardsManager.getCustomLore(level);
            if (!customLore.isEmpty()) {
                lore.add("");
                lore.add(translateColors("&6&lPHẦN THƯỞNG:"));
                lore.add("");
                for (String line : customLore) {
                    lore.add(translateColors(line));
                }
            }
            
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        
        return item;
    }
    
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        
        String title = event.getView().getTitle();
        if (!title.contains("Level Rewards")) return;
        
        event.setCancelled(true);
        
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() == Material.AIR) return;
        
        int slot = event.getSlot();
        int currentPage = playerPages.getOrDefault(player.getUniqueId(), 0);
        
        // Handle navigation
        if (slot == 45 && clickedItem.getType() == Material.RED_STAINED_GLASS_PANE) {
            // Previous page
            if (currentPage > 0) {
                openGUI(player, currentPage - 1);
            }
            return;
        }
        
        if (slot == 53 && clickedItem.getType() == Material.LIME_STAINED_GLASS_PANE) {
            // Next page
            Set<Integer> availableLevels = rewardsManager.getAvailableLevels();
            int totalPages = (int) Math.ceil((double) availableLevels.size() / 36);
            if (currentPage < totalPages - 1) {
                openGUI(player, currentPage + 1);
            }
            return;
        }
        
        // Handle reward claiming
        if (clickedItem.getType() == Material.CHEST_MINECART || clickedItem.getType() == Material.MINECART) {
            String itemName = clickedItem.getItemMeta().getDisplayName();
            
            try {
                // Loại bỏ tất cả mã màu trước
                String cleanName = ChatColor.stripColor(itemName);
                
                // Tìm pattern "Cấp X" hoặc "Level X" trong tên đã clean
                String[] parts = cleanName.split(" ");
                int level = -1;
                
                for (int i = 0; i < parts.length - 1; i++) {
                    if (parts[i].toLowerCase().contains("cấp") || parts[i].toLowerCase().contains("level")) {
                        // Lấy số sau từ "cấp" hoặc "level"
                        String levelStr = parts[i + 1].replaceAll("[^0-9]", "");
                        if (!levelStr.isEmpty()) {
                            level = Integer.parseInt(levelStr);
                            break;
                        }
                    }
                }
                
                // Nếu không tìm thấy bằng cách trên, thử tìm số đầu tiên trong clean name
                if (level == -1) {
                    String[] words = cleanName.split(" ");
                    for (String word : words) {
                        String numberOnly = word.replaceAll("[^0-9]", "");
                        if (!numberOnly.isEmpty()) {
                            level = Integer.parseInt(numberOnly);
                            break;
                        }
                    }
                }
                
                if (level > 0) {
                    if (rewardsManager.canClaimReward(player, level)) {
                        rewardsManager.claimReward(player, level);
                        // Refresh GUI
                        openGUI(player, currentPage);
                    } else if (rewardsManager.hasClaimedReward(player, level)) {
                        String message = rewardsManager.getConfig().getString("messages.reward-already-claimed", "&c&lBạn đã nhận phần thưởng level %level% rồi!")
                                .replace("%level%", String.valueOf(level));
                        player.sendMessage(translateColors(message));
                    } else {
                        String message = rewardsManager.getConfig().getString("messages.reward-not-available", "&c&lBạn chưa đủ level để nhận phần thưởng này!")
                                .replace("%level%", String.valueOf(level));
                        player.sendMessage(translateColors(message));
                    }
                } else {
                    player.sendMessage("§c[ERROR] Không thể xác định level từ item name: " + cleanName);
                }
            } catch (NumberFormatException e) {
                player.sendMessage("§c[ERROR] Lỗi parse level: " + e.getMessage());
            }
        }
    }
}
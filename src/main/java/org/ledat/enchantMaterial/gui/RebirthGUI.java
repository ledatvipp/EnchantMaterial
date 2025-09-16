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
import org.ledat.enchantMaterial.rebirth.RebirthManager;
import org.ledat.enchantMaterial.rebirth.RebirthData;
import org.ledat.enchantMaterial.DatabaseManager;
import org.ledat.enchantMaterial.PlayerData;
import net.milkbowl.vault.economy.Economy;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class RebirthGUI implements Listener {
    private final EnchantMaterial plugin;
    private final RebirthManager rebirthManager;
    private Economy economy;
    
    public RebirthGUI(EnchantMaterial plugin, RebirthManager rebirthManager) {
        this.plugin = plugin;
        this.rebirthManager = rebirthManager;
        setupEconomy();
    }
    
    private void setupEconomy() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") != null) {
            economy = plugin.getServer().getServicesManager().getRegistration(Economy.class).getProvider();
        }
    }
    
    public void openGUI(Player player) {
        try {
            String title = ChatColor.translateAlternateColorCodes('&', 
                    rebirthManager.getConfig().getString("rebirth.gui.title", "&6&lChuyển Sinh"));
            int size = rebirthManager.getConfig().getInt("rebirth.gui.size", 54);
            Inventory gui = Bukkit.createInventory(null, size, title);
            
            // ✅ Mở GUI ngay lập tức với loading placeholder
            addLoadingItems(gui);
            player.openInventory(gui);
            
            // ✅ Load dữ liệu async và update GUI sau
            CompletableFuture.supplyAsync(() -> {
                try {
                    RebirthData rebirthData = DatabaseManager.getRebirthData(player.getUniqueId());
                    PlayerData playerData = DatabaseManager.getPlayerDataAsync(player.getUniqueId()).get();
                    return new Object[]{rebirthData, playerData};
                } catch (Exception e) {
                    plugin.getLogger().severe("Lỗi load dữ liệu rebirth cho " + player.getName() + ": " + e.getMessage());
                    e.printStackTrace();
                    return null;
                }
            }).thenAcceptAsync(data -> {
                if (data != null && player.isOnline()) {
                    RebirthData rebirthData = (RebirthData) data[0];
                    PlayerData playerData = (PlayerData) data[1];
                    
                    // Clear loading items và fill GUI thật
                    gui.clear();
                    fillGUIFromConfig(gui, player, rebirthData, playerData);
                } else if (player.isOnline()) {
                    player.closeInventory();
                    player.sendMessage("§c✗ Không thể tải dữ liệu chuyển sinh. Vui lòng thử lại!");
                }
            }, runnable -> Bukkit.getScheduler().runTask(plugin, runnable));
            
        } catch (Exception e) {
            plugin.getLogger().severe("Lỗi nghiêm trọng khi mở rebirth GUI cho " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
            player.sendMessage("§c✗ Có lỗi xảy ra khi mở GUI chuyển sinh!");
        }
    }
    
    // ✅ Thêm method loading placeholder
    private void addLoadingItems(Inventory gui) {
        ItemStack loadingItem = new ItemStack(Material.CLOCK);
        ItemMeta meta = loadingItem.getItemMeta();
        meta.setDisplayName("§e§lĐang tải dữ liệu...");
        meta.setLore(Arrays.asList("§7Vui lòng chờ trong giây lát"));
        loadingItem.setItemMeta(meta);
        
        // Fill center slots with loading items
        for (int i = 20; i <= 24; i++) {
            gui.setItem(i, loadingItem);
        }
    }
    
    // Phương thức fillGUIFromConfig với 4 tham số (mới)
    private void fillGUIFromConfig(Inventory gui, Player player, RebirthData rebirthData, PlayerData playerData) {
        try {
            // Fill border items
            if (rebirthManager.getConfig().getBoolean("rebirth.gui.fill-border", true)) {
                fillBorder(gui);
            }
            
            // Add decoration items
            addDecorationItems(gui);
            
            // Add rebirth items
            addRebirthItems(gui, player, rebirthData, playerData);
            
            // Add info item
            addInfoItem(gui, player, rebirthData, playerData);
            
            // Add close button
            addCloseButton(gui);
            
        } catch (Exception e) {
            plugin.getLogger().warning("Lỗi fill GUI: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // Phương thức fillGUIFromConfig với 2 tham số (giữ nguyên để tương thích)
    private void fillGUIFromConfig(Inventory gui, Player player) {
        try {
            RebirthData rebirthData = DatabaseManager.getRebirthData(player.getUniqueId());
            PlayerData playerData = DatabaseManager.getPlayerDataAsync(player.getUniqueId()).get();
            
            // Gọi phương thức với 4 tham số
            fillGUIFromConfig(gui, player, rebirthData, playerData);
            
        } catch (Exception e) {
            plugin.getLogger().warning("Lỗi fill GUI: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void fillBorder(Inventory gui) {
        // SỬA: Thêm "rebirth." vào đầu path
        String borderMaterial = rebirthManager.getConfig().getString("rebirth.gui.border.material", "GRAY_STAINED_GLASS_PANE");
        String borderName = ChatColor.translateAlternateColorCodes('&', 
                rebirthManager.getConfig().getString("rebirth.gui.border.name", " "));
        
        ItemStack border = new ItemStack(Material.valueOf(borderMaterial));
        ItemMeta borderMeta = border.getItemMeta();
        if (borderMeta != null) {
            borderMeta.setDisplayName(borderName);
            border.setItemMeta(borderMeta);
        }
        
        // Fill border based on GUI size
        int size = gui.getSize();
        int rows = size / 9;
        
        // Top and bottom rows
        for (int i = 0; i < 9; i++) {
            gui.setItem(i, border);
            gui.setItem(size - 9 + i, border);
        }
        
        // Left and right columns
        for (int i = 9; i < size - 9; i += 9) {
            gui.setItem(i, border);
            gui.setItem(i + 8, border);
        }
    }
    
    private void addDecorationItems(Inventory gui) {
        // SỬA: Thêm "rebirth." vào đầu path
        if (!rebirthManager.getConfig().contains("rebirth.gui.decoration")) return;
        
        // SỬA: Thêm "rebirth." vào đầu path
        for (String key : rebirthManager.getConfig().getConfigurationSection("rebirth.gui.decoration").getKeys(false)) {
            String path = "rebirth.gui.decoration." + key;
            
            List<Integer> slots = rebirthManager.getConfig().getIntegerList(path + ".slots");
            String material = rebirthManager.getConfig().getString(path + ".material", "STONE");
            String name = ChatColor.translateAlternateColorCodes('&', 
                    rebirthManager.getConfig().getString(path + ".name", ""));
            List<String> lore = rebirthManager.getConfig().getStringList(path + ".lore");
            
            ItemStack item = createItem(material, name, lore, null, null);
            
            for (int slot : slots) {
                if (slot >= 0 && slot < gui.getSize()) {
                    gui.setItem(slot, item);
                }
            }
        }
    }
    
    private void addRebirthItems(Inventory gui, Player player, RebirthData rebirthData, PlayerData playerData) {
        // SỬA: Thêm "rebirth." vào đầu path
        if (!rebirthManager.getConfig().contains("rebirth.gui.rebirth-items")) return;
        
        int currentRebirthLevel = rebirthData.getRebirthLevel();
        
        // SỬA: Thêm "rebirth." vào đầu path
        for (String key : rebirthManager.getConfig().getConfigurationSection("rebirth.gui.rebirth-items").getKeys(false)) {
            String path = "rebirth.gui.rebirth-items." + key;
            
            int level = rebirthManager.getConfig().getInt(path + ".level", 1);
            int slot = rebirthManager.getConfig().getInt(path + ".slot", 0);
            
            if (slot >= 0 && slot < gui.getSize()) {
                ItemStack item = createRebirthItem(player, level, currentRebirthLevel, playerData, path);
                gui.setItem(slot, item);
            }
        }
    }
    
    private ItemStack createRebirthItem(Player player, int level, int currentRebirthLevel, PlayerData playerData, String configPath) {
        String status;
        if (level <= currentRebirthLevel) {
            status = "completed";
        } else if (level == currentRebirthLevel + 1) {
            // ✅ Sử dụng cached data thay vì gọi canRebirth
            boolean canRebirth = canRebirthCached(player, level, playerData, currentRebirthLevel);
            status = canRebirth ? "available" : "locked";
        } else {
            status = "locked";
        }
        
        String itemPath = configPath + "." + status;
        String material = rebirthManager.getConfig().getString(itemPath + ".material", "STONE");
        String name = rebirthManager.getConfig().getString(itemPath + ".name", "&fChuyển Sinh Cấp %level%");
        List<String> lore = new ArrayList<>(rebirthManager.getConfig().getStringList(itemPath + ".lore"));
        
        // Thêm thông tin trạng thái với design đẹp
        addStatusLore(lore, status, level, currentRebirthLevel);
        
        // Thêm thông tin yêu cầu chi tiết cho các item locked/available
        if (level > currentRebirthLevel) {
            addDetailedRequirementLore(lore, level, playerData, player, status);
        }
        
        // Thêm thông tin phần thưởng
        if (level > currentRebirthLevel) {
            addRewardLore(lore, level);
        }
        
        // Thêm thông tin tỉ lệ thành công với design đẹp
        if (level > currentRebirthLevel) {
            addSuccessRateLore(lore, level);
        }
        
        return createItem(material, name, lore, player, level);
    }
    
    private void addStatusLore(List<String> lore, String status, int level, int currentRebirthLevel) {
        lore.add("");
        
        switch (status) {
            case "completed":
                lore.add("§f&lTRẠNG THÁI: §aHoàn Thành");
                lore.add("§7Bạn đã chuyển sinh cấp này thành công!");
                break;
            case "available":
                lore.add("§f§lTRẠNG THÁI: §eSẵn Sàng");
                break;
            case "locked":
                if (level == currentRebirthLevel + 1) {
                    lore.add("§f§lTRẠNG THÁI: §cChưa Đủ Yêu Cầu");
                    lore.add("§7Hoàn thành yêu cầu để mở khóa!");
                } else {
                    lore.add("§f§lTRẠNG THÁI: §cBị Khóa");
                    lore.add("§7Hoàn thành cấp trước để mở khóa!");
                }
                break;
        }
    }
    
    private void addDetailedRequirementLore(List<String> lore, int level, PlayerData playerData, Player player, String status) {
        String levelPath = "rebirth.rebirth.levels." + level;
        
        lore.add("");
        lore.add("§6§lYÊU CẦU CHUYỂN SINH:");
        lore.add("");
        
        // ✅ Cache config values
        int requiredLevel = rebirthManager.getConfig().getInt(levelPath + ".required-level", 0);
        double requiredMoney = rebirthManager.getConfig().getDouble(levelPath + ".required-money", 0);
        List<Map<?, ?>> requiredItems = rebirthManager.getConfig().getMapList(levelPath + ".required-items");
        
        // Level requirement
        boolean levelMet = playerData.getLevel() >= requiredLevel;
        String levelIcon = levelMet ? "§a§l✓" : "§c§l✗";
        String levelColor = levelMet ? "§a" : "§c";
        
        lore.add("  " + levelIcon + " §f§lCấp độ: " + levelColor + requiredLevel);
        lore.add("    §8▸ §7Hiện tại: " + (levelMet ? "§a" : "§c") + playerData.getLevel() + "§7/" + requiredLevel);
        
        if (!levelMet) {
            int needed = requiredLevel - playerData.getLevel();
            lore.add("    §8▸ §7Cần thêm: §c" + needed + " cấp");
        }
        
        // Money requirement
        if (requiredMoney > 0 && economy != null) {
            double currentMoney = economy.getBalance(player);
            boolean moneyMet = currentMoney >= requiredMoney;
            String moneyIcon = moneyMet ? "§a§l✓" : "§c§l✗";
            String moneyColor = moneyMet ? "§a" : "§c";
            
            lore.add("");
            lore.add("  " + moneyIcon + " §fTiền: " + moneyColor + formatNumber(requiredMoney));
            lore.add("    §8▸ §7Hiện tại: " + (moneyMet ? "§a" : "§c") + formatNumber(currentMoney));
            
            if (!moneyMet) {
                double needed = requiredMoney - currentMoney;
                lore.add("    §8▸ §7Cần thêm: §c" + formatNumber(needed));
            }
        }
        
        // ✅ Item requirements với batch checking
        if (!requiredItems.isEmpty()) {
            lore.add("");
            
            // Cache inventory contents để tránh multiple calls
            ItemStack[] inventoryContents = player.getInventory().getContents();
            
            for (Map<?, ?> itemMap : requiredItems) {
                String itemMaterial = (String) itemMap.get("material");
                int amount = (Integer) itemMap.get("amount");
                String customName = (String) itemMap.get("custom-name");
                
                // ✅ Sử dụng cached inventory
                boolean hasItem = hasRequiredItemCached(inventoryContents, itemMap);
                
                String itemIcon = hasItem ? "§a§l✓" : "§c§l✗";
                String itemColor = hasItem ? "§a" : "§c";
                
                String displayName;
                if (customName != null && !customName.isEmpty()) {
                    displayName = customName.replace("&", "§");
                } else {
                    displayName = formatMaterialName(itemMaterial);
                }
                
                lore.add("  " + itemIcon + " " + itemColor + amount + "x " + displayName);
                
                if (!hasItem) {
                    int currentAmount = countMatchingItemsCached(inventoryContents, itemMap);
                    int needed = amount - currentAmount;
                    lore.add("    §8▸ §7Hiện có: §c" + currentAmount + "§7, cần thêm: §c" + needed);
                    
                    if (customName != null && !customName.isEmpty()) {
                        lore.add("    §8▸ §e⚠ §7Cần item có tên: " + customName.replace("&", "§"));
                    }
                }
            }
        }
    }
    
    private void addRewardLore(List<String> lore, int level) {
        String levelPath = "rebirth.rebirth.levels." + level;
        
        lore.add("");
        lore.add("§e§lPHẦN THƯỞNG:");
        lore.add("");
        
        // Commands rewards
        List<String> commands = rebirthManager.getConfig().getStringList(levelPath + ".rewards.commands");
        if (!commands.isEmpty()) {
            lore.add("  §8○ §7Mở khóa khu vực mới");
            lore.add("  §8○ §7Tăng 10% EXP");
            lore.add("  §8○ §7Nhận title đặc biệt");
        }
        
        // Item rewards
        List<Map<?, ?>> items = rebirthManager.getConfig().getMapList(levelPath + ".rewards.items");
        if (!items.isEmpty()) {
            for (Map<?, ?> itemMap : items) {
                String material = (String) itemMap.get("material");
                int amount = (Integer) itemMap.get("amount");
                String materialName = formatMaterialName(material);
                lore.add("  §8○ §f" + amount + "x " + materialName);
            }
        }
    }
    
    private void addSuccessRateLore(List<String> lore, int level) {
        double successRate = rebirthManager.getConfig().getDouble("rebirth.rebirth.levels." + level + ".success-rate", 100.0);
        
        lore.add("");
        
        String rateColor;
        String rateIcon;
        if (successRate >= 90) {
            rateColor = "§a";
            rateIcon = "§a✓";
        } else if (successRate >= 70) {
            rateColor = "§e";
            rateIcon = "§e⚠";
        } else {
            rateColor = "§c";
            rateIcon = "§c⚠";
        }
        
        lore.add("§f§lTỈ LỆ THÀNH CÔNG: " + rateColor + "§l" + successRate + "%" + rateIcon);
        
        // Thêm progress bar cho tỉ lệ thành công
        String progressBar = createProgressBar(successRate, 100, 20, '█', '▒', rateColor, "§8");
        lore.add(progressBar);
        
        if (successRate < 100) {
            lore.add("");
            lore.add("§c§lCẢNH BÁO:");
            lore.add("  §7Nếu thất bại, bạn sẽ mất:");
            lore.add("  §8▸ §c25% tiền");
            lore.add("  §8▸ §cNgẫu nhiên 1 đồ yêu cầu");
            lore.add("  §8▸ §c10 cấp độ");
        }
        
        lore.add(" ");
    }
    
    // Helper methods
    private String formatNumber(double number) {
        if (number >= 1000000) {
            return String.format("%.1fM", number / 1000000);
        } else if (number >= 1000) {
            return String.format("%.1fK", number / 1000);
        } else {
            return String.format("%.0f", number);
        }
    }
    
    private String formatMaterialName(String material) {
        String formatted = material.toLowerCase()
                .replace("_", " ")
                .replace("minecraft:", "");
        
        // Viết hoa chữ cái đầu của mỗi từ
        String[] words = formatted.split(" ");
        StringBuilder result = new StringBuilder();
        
        for (String word : words) {
            if (word.length() > 0) {
                result.append(word.substring(0, 1).toUpperCase())
                      .append(word.substring(1))
                      .append(" ");
            }
        }
        
        return result.toString().trim();
    }
    
    private int getItemAmount(Player player, String material) {
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == Material.valueOf(material)) {
                total += item.getAmount();
            }
        }
        return total;
    }
    
    private String createProgressBar(double current, double max, int length, char filledChar, char emptyChar, String filledColor, String emptyColor) {
        double percentage = current / max;
        int filledLength = (int) (length * percentage);
        
        StringBuilder bar = new StringBuilder();
        bar.append(filledColor);
        for (int i = 0; i < filledLength; i++) {
            bar.append(filledChar);
        }
        bar.append(emptyColor);
        for (int i = filledLength; i < length; i++) {
            bar.append(emptyChar);
        }
        
        return bar.toString();
    }
    
    private void addInfoItem(Inventory gui, Player player, RebirthData rebirthData, PlayerData playerData) {
        // SỬA: Thêm "rebirth." vào đầu path
        if (!rebirthManager.getConfig().contains("rebirth.gui.info-item")) return;
        
        String path = "rebirth.gui.info-item";
        int slot = rebirthManager.getConfig().getInt(path + ".slot", -1);
        
        if (slot >= 0 && slot < gui.getSize()) {
            String material = rebirthManager.getConfig().getString(path + ".material", "BOOK");
            String name = rebirthManager.getConfig().getString(path + ".name", "&e&lThông Tin Chuyển Sinh");
            List<String> lore = new ArrayList<>(rebirthManager.getConfig().getStringList(path + ".lore"));
            
            // Add dynamic info
            lore.add("");
            lore.add("§7Cấp chuyển sinh hiện tại: §f" + rebirthData.getRebirthLevel());
            lore.add("§7Level EnchantMaterial: §f" + playerData.getLevel());
            lore.add("§7Điểm: §f" + String.format("%.0f", playerData.getPoints()));
            
            if (economy != null) {
                lore.add("§7Tiền: §f" + String.format("%.0f", economy.getBalance(player)));
            }
            
            ItemStack item = createItem(material, name, lore, player, null);
            gui.setItem(slot, item);
        }
    }
    
    private void addCloseButton(Inventory gui) {
        // SỬA: Thêm "rebirth." vào đầu path
        if (!rebirthManager.getConfig().contains("rebirth.gui.close-button")) return;
        
        String path = "rebirth.gui.close-button";
        int slot = rebirthManager.getConfig().getInt(path + ".slot", -1);
        
        if (slot >= 0 && slot < gui.getSize()) {
            String material = rebirthManager.getConfig().getString(path + ".material", "BARRIER");
            String name = rebirthManager.getConfig().getString(path + ".name", "&c&lĐóng");
            List<String> lore = rebirthManager.getConfig().getStringList(path + ".lore");
            
            ItemStack item = createItem(material, name, lore, null, null);
            gui.setItem(slot, item);
        }
    }
    
    private ItemStack createItem(String material, String name, List<String> lore, Player player, Integer level) {
        ItemStack item = new ItemStack(Material.valueOf(material.toUpperCase()));
        ItemMeta meta = item.getItemMeta();
        
        if (meta != null) {
            // Set display name with placeholders
            String displayName = ChatColor.translateAlternateColorCodes('&', name);
            if (player != null) {
                displayName = replacePlaceholders(displayName, player, level);
            }
            meta.setDisplayName(displayName);
            
            // Set lore with placeholders
            List<String> finalLore = new ArrayList<>();
            for (String line : lore) {
                String coloredLine = ChatColor.translateAlternateColorCodes('&', line);
                if (player != null) {
                    coloredLine = replacePlaceholders(coloredLine, player, level);
                }
                finalLore.add(coloredLine);
            }
            meta.setLore(finalLore);
            
            item.setItemMeta(meta);
        }
        
        return item;
    }
    
    private String replacePlaceholders(String text, Player player, Integer level) {
        try {
            PlayerData playerData = DatabaseManager.getPlayerData(player.getUniqueId().toString());
            RebirthData rebirthData = DatabaseManager.getRebirthData(player.getUniqueId());
            
            text = text.replace("%player%", player.getName())
                      .replace("%current_level%", String.valueOf(playerData.getLevel()))
                      .replace("%points%", String.format("%.0f", playerData.getPoints()))
                      .replace("%rebirth_level%", String.valueOf(rebirthData.getRebirthLevel()));
            
            if (level != null) {
                text = text.replace("%level%", String.valueOf(level));
            }
            
            if (economy != null) {
                text = text.replace("%money%", String.format("%.0f", economy.getBalance(player)));
            }
            
        } catch (Exception e) {
            plugin.getLogger().warning("Lỗi replace placeholders: " + e.getMessage());
        }
        
        return text;
    }
    
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        
        Player player = (Player) event.getWhoClicked();
        String title = ChatColor.stripColor(event.getView().getTitle());
        
        // Check if it's rebirth GUI
        String guiTitle = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', 
                rebirthManager.getConfig().getString("rebirth.gui.title", "Chuyển Sinh")));
        
        if (!title.contains(guiTitle)) return;
        
        event.setCancelled(true);
        
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || !clickedItem.hasItemMeta()) return;
        
        String itemName = ChatColor.stripColor(clickedItem.getItemMeta().getDisplayName());
        
        // Handle close button
        // SỬA: Thêm "rebirth." vào đầu path
        String closeButtonName = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', 
                rebirthManager.getConfig().getString("rebirth.gui.close-button.name", "Đóng")));
        if (itemName.contains(closeButtonName)) {
            player.closeInventory();
            return;
        }
        
        // Handle rebirth items
        handleRebirthClick(player, clickedItem, event.getSlot());
    }
    
    private void handleRebirthClick(Player player, ItemStack clickedItem, int slot) {
        // Find which rebirth item was clicked based on slot
        if (!rebirthManager.getConfig().contains("rebirth.gui.rebirth-items")) return;
        
        for (String key : rebirthManager.getConfig().getConfigurationSection("rebirth.gui.rebirth-items").getKeys(false)) {
            String path = "rebirth.gui.rebirth-items." + key;
            int itemSlot = rebirthManager.getConfig().getInt(path + ".slot", -1);
            
            if (itemSlot == slot) {
                int level = rebirthManager.getConfig().getInt(path + ".level", 1);
                
                try {
                    RebirthData rebirthData = DatabaseManager.getRebirthData(player.getUniqueId());
                    
                    // Check if this is the next available rebirth level
                    if (level == rebirthData.getRebirthLevel() + 1) {
                        // SỬA: Kiểm tra đầy đủ các yêu cầu trước khi cho phép chuyển sinh
                        if (rebirthManager.canRebirthWithDebug(player, level)) {
                            // Show confirmation GUI or attempt rebirth directly
                            if (rebirthManager.getConfig().getBoolean("rebirth.gui.confirm-rebirth", true)) {
                                openConfirmationGUI(player, level);
                            } else {
                                attemptRebirth(player, level);
                            }
                        } else {
                            // Gửi thông báo chi tiết về yêu cầu chưa đáp ứng
                            sendRequirementMessage(player, level);
                        }
                    } else {
                        // Send message about requirements
                        String message = rebirthManager.getConfig().getString("rebirth.messages.cannot-rebirth", 
                                "§c✗ Bạn không thể chuyển sinh cấp này!");
                        player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Lỗi xử lý click rebirth: " + e.getMessage());
                }
                break;
            }
        }
    }
    
    private void openConfirmationGUI(Player player, int level) {
        String title = ChatColor.translateAlternateColorCodes('&', 
                rebirthManager.getConfig().getString("rebirth.gui.confirmation-gui.title", "&c&lXác Nhận Chuyển Sinh"));

        
        Inventory confirmGUI = Bukkit.createInventory(null, 27, title + " - Cấp " + level);
        
        // Confirm button
        ItemStack confirm = new ItemStack(Material.GREEN_WOOL);
        ItemMeta confirmMeta = confirm.getItemMeta();
        if (confirmMeta != null) {
            confirmMeta.setDisplayName("§axáᴄ ɴʜậɴ");
            confirmMeta.setLore(Arrays.asList(
                    "§fClick để xác nhận chuyển sinh!",
                    "",
                    "§cCảnh báo: Hành động này không thể hoàn tác!"
            ));
            confirm.setItemMeta(confirmMeta);
        }
        
        // Cancel button
        ItemStack cancel = new ItemStack(Material.RED_WOOL);
        ItemMeta cancelMeta = cancel.getItemMeta();
        if (cancelMeta != null) {
            cancelMeta.setDisplayName("§cʜủʏ ʙỏ");
            cancelMeta.setLore(Arrays.asList(
                    "§fClick để hủy bỏ chuyển sinh!"
            ));
            cancel.setItemMeta(cancelMeta);
        }
        
        // Fill GUI
        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta glassMeta = glass.getItemMeta();
        if (glassMeta != null) {
            glassMeta.setDisplayName(" ");
            glass.setItemMeta(glassMeta);
        }
        
        for (int i = 0; i < 27; i++) {
            confirmGUI.setItem(i, glass);
        }
        
        confirmGUI.setItem(11, confirm);
        confirmGUI.setItem(15, cancel);
        
        // Store level in player metadata for confirmation
        player.setMetadata("rebirth_confirm_level", new org.bukkit.metadata.FixedMetadataValue(plugin, level));
        
        player.openInventory(confirmGUI);
    }
    
    private void attemptRebirth(Player player, int level) {
        boolean success = rebirthManager.attemptRebirth(player, level);
        
        if (success) {
            // Refresh GUI after successful rebirth
            Bukkit.getScheduler().runTaskLater(plugin, () -> openGUI(player), 1L);
        }
        // Failure message is handled in RebirthManager
    }
    
    @EventHandler
    public void onConfirmationClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        
        Player player = (Player) event.getWhoClicked();
        String title = ChatColor.stripColor(event.getView().getTitle());
        
        if (!title.contains("Xác Nhận Chuyển Sinh")) return;
        
        event.setCancelled(true);
        
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || !clickedItem.hasItemMeta()) return;
        
        String itemName = ChatColor.stripColor(clickedItem.getItemMeta().getDisplayName());
        
        if (itemName.contains("xáᴄ ɴʜậɴ")) {
            if (player.hasMetadata("rebirth_confirm_level")) {
                int level = player.getMetadata("rebirth_confirm_level").get(0).asInt();
                player.removeMetadata("rebirth_confirm_level", plugin);
                player.closeInventory();
                attemptRebirth(player, level);
            }
        } else if (itemName.contains("ʜủʏ ʙỏ")) {
            player.removeMetadata("rebirth_confirm_level", plugin);
            player.closeInventory();
            openGUI(player); // Return to main rebirth GUI
        }
    }

    private void sendRequirementMessage(Player player, int level) {
        try {
            // SỬA: Đường dẫn config đúng
            String path = "rebirth.rebirth.levels." + level;
            PlayerData playerData = DatabaseManager.getPlayerData(player.getUniqueId().toString());
            RebirthData rebirthData = DatabaseManager.getRebirthData(player.getUniqueId());
        
            // Kiểm tra level chuyển sinh
            if (rebirthData.getRebirthLevel() + 1 != level) {
                player.sendMessage("§c✗ Bạn cần hoàn thành chuyển sinh cấp " + (level - 1) + " trước!");
                return;
            }
            
            // Kiểm tra EnchantMaterial Level
            int requiredLevel = rebirthManager.getConfig().getInt(path + ".required-level", 0);
            if (playerData.getLevel() < requiredLevel) {
                player.sendMessage("§c✗ Bạn cần level EnchantMaterial " + requiredLevel + " để chuyển sinh! (Hiện tại: " + playerData.getLevel() + ")");
                return;
            }
        
            // Kiểm tra tiền
            double requiredMoney = rebirthManager.getConfig().getDouble(path + ".required-money", 0);
            if (economy != null && economy.getBalance(player) < requiredMoney) {
                player.sendMessage("§c✗ Bạn cần " + String.format("%.0f", requiredMoney) + " tiền để chuyển sinh! (Hiện tại: " + String.format("%.0f", economy.getBalance(player)) + ")");
                return;
            }
        
            // Kiểm tra items
            List<Map<?, ?>> requiredItems = rebirthManager.getConfig().getMapList(path + ".required-items");
            for (Map<?, ?> itemMap : requiredItems) {
                String material = (String) itemMap.get("material");
                int amount = (Integer) itemMap.get("amount");
                
                if (!player.getInventory().containsAtLeast(new ItemStack(org.bukkit.Material.valueOf(material)), amount)) {
                    player.sendMessage("§c✗ Bạn cần " + amount + "x " + formatMaterialName(material) + " để chuyển sinh!");
                    return;
                }
            }
        
            // Nếu đến đây thì có lỗi logic khác
            player.sendMessage("§c✗ Bạn không thể chuyển sinh cấp này!");
        
        } catch (Exception e) {
            plugin.getLogger().warning("Lỗi kiểm tra yêu cầu chuyển sinh: " + e.getMessage());
            player.sendMessage("§c✗ Có lỗi xảy ra khi kiểm tra điều kiện chuyển sinh!");
        }
    }

    // Thêm phương thức này vào RebirthGUI
    private int countMatchingItems(Player player, Map<?, ?> itemMap) {
        String material = (String) itemMap.get("material");
        String customName = (String) itemMap.get("custom-name");
        List<String> customLore = (List<String>) itemMap.get("custom-lore");
    
        int count = 0;
    
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || !item.getType().name().equals(material)) {
                continue;
            }
        
            // Kiểm tra custom name và lore
            if (itemMatches(item, customName, customLore)) {
                count += item.getAmount();
            }
        }
    
        return count;
    }

    private boolean itemMatches(ItemStack item, String customName, List<String> customLore) {
        if (customName == null && customLore == null) {
            return true;
        }
    
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return customName == null && customLore == null;
        }
    
        // Kiểm tra custom name
        if (customName != null) {
            String itemName = meta.getDisplayName();
            if (itemName == null || !itemName.equals(customName.replace("&", "§"))) {
                return false;
            }
        }
    
        // Kiểm tra custom lore (nếu cần)
        if (customLore != null && !customLore.isEmpty()) {
            List<String> itemLore = meta.getLore();
            if (itemLore == null || itemLore.size() != customLore.size()) {
                return false;
            }
        
            for (int i = 0; i < customLore.size(); i++) {
                String expectedLore = customLore.get(i).replace("&", "§");
                if (!itemLore.get(i).equals(expectedLore)) {
                    return false;
                }
            }
        }
    
        return true;
    }
    
    // ✅ Thêm method hasRequiredItemCached
    private boolean hasRequiredItemCached(ItemStack[] inventory, Map<?, ?> itemMap) {
        String material = (String) itemMap.get("material");
        int amount = (Integer) itemMap.get("amount");
        String customName = (String) itemMap.get("custom-name");
        List<String> customLore = (List<String>) itemMap.get("custom-lore");
        
        int foundAmount = 0;
        
        for (ItemStack item : inventory) {
            if (item == null || !item.getType().name().equals(material)) {
                continue;
            }
            
            if (itemMatches(item, customName, customLore)) {
                foundAmount += item.getAmount();
                if (foundAmount >= amount) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    // ✅ Thêm method countMatchingItemsCached
    private int countMatchingItemsCached(ItemStack[] inventory, Map<?, ?> itemMap) {
        String material = (String) itemMap.get("material");
        String customName = (String) itemMap.get("custom-name");
        List<String> customLore = (List<String>) itemMap.get("custom-lore");
        
        int count = 0;
        
        for (ItemStack item : inventory) {
            if (item == null || !item.getType().name().equals(material)) {
                continue;
            }
            
            if (itemMatches(item, customName, customLore)) {
                count += item.getAmount();
            }
        }
        
        return count;
    }

    // ✅ Method kiểm tra canRebirth với cached data
    private boolean canRebirthCached(Player player, int targetLevel, PlayerData playerData, int currentRebirthLevel) {
        try {
            // Kiểm tra level chuyển sinh
            if (currentRebirthLevel + 1 != targetLevel) {
                return false;
            }
        
            String path = "rebirth.rebirth.levels." + targetLevel;
        
            // Kiểm tra EnchantMaterial Level
            int requiredLevel = rebirthManager.getConfig().getInt(path + ".required-level", 0);
            if (playerData.getLevel() < requiredLevel) {
                return false;
            }
        
            // Kiểm tra tiền
            double requiredMoney = rebirthManager.getConfig().getDouble(path + ".required-money", 0);
            if (economy != null && economy.getBalance(player) < requiredMoney) {
                return false;
            }
        
            // Kiểm tra items (cache inventory check)
            List<Map<?, ?>> requiredItems = rebirthManager.getConfig().getMapList(path + ".required-items");
            for (Map<?, ?> itemMap : requiredItems) {
                if (!rebirthManager.hasRequiredItem(player, itemMap)) {
                    return false;
                }
            }
        
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Lỗi kiểm tra canRebirth cached: " + e.getMessage());
            return false;
        }
    }
}
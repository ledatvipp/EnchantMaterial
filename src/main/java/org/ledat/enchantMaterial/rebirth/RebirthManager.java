package org.ledat.enchantMaterial.rebirth;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.ledat.enchantMaterial.EnchantMaterial;
import org.ledat.enchantMaterial.DatabaseManager;
import org.ledat.enchantMaterial.PlayerData;
import net.milkbowl.vault.economy.Economy;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class RebirthManager {
    private final EnchantMaterial plugin;
    private final File rebirthFile;
    private YamlConfiguration rebirthConfig;
    private Economy economy;

    public RebirthManager(EnchantMaterial plugin) {
        this.plugin = plugin;
        this.rebirthFile = new File(plugin.getDataFolder(), "rebirth.yml");
        loadConfig();
        setupEconomy();
    }

    private void loadConfig() {
        if (!rebirthFile.exists()) {
            plugin.saveResource("rebirth.yml", false);
        }
        rebirthConfig = YamlConfiguration.loadConfiguration(rebirthFile);
    }

    private void setupEconomy() {
        try {
            // 1) Chưa cài Vault -> bỏ qua kinh tế
            if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
                economy = null;
                plugin.getLogger().warning("[Rebirth] Vault chưa được cài. Sẽ bỏ qua yêu cầu tiền (required-money).");
                return;
            }

            // 2) Có Vault nhưng chưa có Economy provider (EssentialsX/CMI/…)
            var rsp = plugin.getServer().getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
            if (rsp == null) {
                economy = null;
                plugin.getLogger().warning("[Rebirth] Không tìm thấy Economy provider cho Vault (EssentialsX Economy/CMI Economy?).");
                return;
            }

            // 3) OK
            economy = rsp.getProvider();
            plugin.getLogger().info("[Rebirth] Đã hook Economy: " + economy.getName());
        } catch (Throwable t) {
            economy = null;
            plugin.getLogger().warning("[Rebirth] Lỗi hook Vault/Economy: " + t.getMessage());
        }
    }


    public boolean canRebirth(Player player, int targetLevel) {
        try {
            RebirthData rebirthData = DatabaseManager.getRebirthData(player.getUniqueId());

            // Kiểm tra level chuyển sinh hiện tại
            if (rebirthData.getRebirthLevel() + 1 != targetLevel) {
                return false;
            }

            String path = "rebirth.rebirth.levels." + targetLevel;

            // ✅ Sử dụng async data với timeout
            CompletableFuture<PlayerData> playerDataFuture = DatabaseManager.getPlayerDataAsync(player.getUniqueId());
            PlayerData playerData;

            try {
                playerData = playerDataFuture.get(3, TimeUnit.SECONDS); // 3 giây timeout
            } catch (TimeoutException e) {
                plugin.getLogger().warning("Timeout khi load PlayerData cho " + player.getName());
                return false;
            }

            // Kiểm tra EnchantMaterial Level
            int requiredLevel = rebirthConfig.getInt(path + ".required-level", 0);
            if (playerData.getLevel() < requiredLevel) {
                player.sendMessage("§cLevel không đủ! Hiện tại: " + playerData.getLevel() + ", Yêu cầu: " + requiredLevel);
                return false;
            }

            // Kiểm tra tiền
            double requiredMoney = rebirthConfig.getDouble(path + ".required-money", 0);
            if (economy != null && economy.getBalance(player) < requiredMoney) {
                player.sendMessage("§cTiền không đủ! Hiện tại: " + economy.getBalance(player) + ", Yêu cầu: " + requiredMoney);
                return false;
            }

            // ✅ SửA: Kiểm tra items với Map thay vì Material, int, String
            if (rebirthConfig.contains(path + ".required-items")) {
                List<Map<?, ?>> requiredItems = rebirthConfig.getMapList(path + ".required-items");
                for (Map<?, ?> itemMap : requiredItems) {
                    if (!hasRequiredItem(player, itemMap)) {
                        String material = (String) itemMap.get("material");
                        int amount = (Integer) itemMap.get("amount");
                        player.sendMessage("§cThiếu vật phẩm: " + amount + "x " + material);
                        return false;
                    }
                }
            }

            return true;

        } catch (Exception e) {
            plugin.getLogger().severe("Lỗi nghiêm trọng trong canRebirth cho " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // Phương thức kiểm tra item có custom name
    public boolean hasRequiredItem(Player player, Map<?, ?> itemMap) {
        String material = (String) itemMap.get("material");
        int amount = (Integer) itemMap.get("amount");
        String customName = (String) itemMap.get("custom-name"); // Tên tùy chỉnh (tùy chọn)
        List<String> customLore = (List<String>) itemMap.get("custom-lore"); // Lore tùy chỉnh (tùy chọn)

        int foundAmount = 0;

        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || !item.getType().name().equals(material)) {
                continue;
            }

            // Nếu không có yêu cầu custom name/lore, chỉ cần kiểm tra material
            if (customName == null && customLore == null) {
                foundAmount += item.getAmount();
                continue;
            }

            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                continue;
            }

            // Kiểm tra custom name - BỎ QUA MÃ MÀU
            if (customName != null) {
                String itemName = meta.getDisplayName();
                if (itemName == null) {
                    continue;
                }

                // Chuyển đổi mã màu và loại bỏ tất cả mã màu để so sánh
                String expectedName = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', customName));
                String actualName = ChatColor.stripColor(itemName);

                if (!actualName.equals(expectedName)) {
                    continue;
                }
            }

            // Kiểm tra custom lore - BỎ QUA MÃ MÀU
            if (customLore != null && !customLore.isEmpty()) {
                List<String> itemLore = meta.getLore();
                if (itemLore == null || itemLore.size() != customLore.size()) {
                    continue;
                }

                boolean loreMatches = true;
                for (int i = 0; i < customLore.size(); i++) {
                    // Chuyển đổi mã màu và loại bỏ tất cả mã màu để so sánh
                    String expectedLore = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', customLore.get(i)));
                    String actualLore = ChatColor.stripColor(itemLore.get(i));

                    if (!actualLore.equals(expectedLore)) {
                        loreMatches = false;
                        break;
                    }
                }

                if (!loreMatches) {
                    continue;
                }
            }

            // Item phù hợp với yêu cầu
            foundAmount += item.getAmount();
        }

        return foundAmount >= amount;
    }

    public boolean attemptRebirth(Player player, int targetLevel) {
        // Kiểm tra chi tiết từng yêu cầu
        if (!canRebirthWithDebug(player, targetLevel)) {
            return false;
        }

        try {
            // SỬA: Đường dẫn config đúng
            String path = "rebirth.rebirth.levels." + targetLevel;

            // Tính tỉ lệ thành công
            double successRate = rebirthConfig.getDouble(path + ".success-rate", 100.0);
            double random = ThreadLocalRandom.current().nextDouble(0, 100);

            boolean success = random <= successRate;

            if (success) {
                // Trừ điều kiện
                consumeRequirements(player, targetLevel);

                // Cập nhật level chuyển sinh
                RebirthData rebirthData = DatabaseManager.getRebirthData(player.getUniqueId());
                rebirthData.setRebirthLevel(targetLevel);
                rebirthData.setLastRebirthTime(System.currentTimeMillis());
                DatabaseManager.saveRebirthData(rebirthData);

                // Thực hiện rewards
                executeRewards(player, targetLevel);

                // Gửi thông báo thành công
                String successMsg = rebirthConfig.getString("messages.rebirth-success", "§a✓ Chuyển sinh thành công!");
                player.sendMessage(successMsg.replace("%level%", String.valueOf(targetLevel)));

                return true;
            } else {
                // Chuyển sinh thất bại - áp dụng hình phạt
                applyFailurePenalties(player, targetLevel);

                // Chuyển sinh thất bại - có thể trừ một phần điều kiện
                boolean consumeOnFail = rebirthConfig.getBoolean(path + ".consume-on-fail", false);
                if (consumeOnFail) {
                    consumeRequirements(player, targetLevel);
                }

                String failMsg = rebirthConfig.getString("messages.rebirth-fail", "§c✗ Chuyển sinh thất bại!");
                player.sendMessage(failMsg.replace("%level%", String.valueOf(targetLevel)));

                return false;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Lỗi thực hiện chuyển sinh: " + e.getMessage());
            return false;
        }
    }

    private void consumeRequirements(Player player, int targetLevel) {
        // SỬA: Đường dẫn config đúng
        String path = "rebirth.rebirth.levels." + targetLevel;

        // Trừ tiền
        double requiredMoney = rebirthConfig.getDouble(path + ".required-money", 0);
        if (economy != null && requiredMoney > 0) {
            economy.withdrawPlayer(player, requiredMoney);
        }

        // Trừ items (bao gồm custom name)
        List<Map<?, ?>> requiredItems = rebirthConfig.getMapList(path + ".required-items");
        for (Map<?, ?> itemMap : requiredItems) {
            removeRequiredItem(player, itemMap);
        }
    }

    // Phương thức xóa item có custom name
    private void removeRequiredItem(Player player, Map<?, ?> itemMap) {
        String material = (String) itemMap.get("material");
        int amount = (Integer) itemMap.get("amount");
        String customName = (String) itemMap.get("custom-name");
        List<String> customLore = (List<String>) itemMap.get("custom-lore");

        int remainingToRemove = amount;
        ItemStack[] contents = player.getInventory().getContents();

        for (int i = 0; i < contents.length && remainingToRemove > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || !item.getType().name().equals(material)) {
                continue;
            }

            // Kiểm tra custom name và lore nếu có
            if (!itemMatches(item, customName, customLore)) {
                continue;
            }

            int removeAmount = Math.min(item.getAmount(), remainingToRemove);

            if (item.getAmount() <= removeAmount) {
                player.getInventory().setItem(i, null);
            } else {
                item.setAmount(item.getAmount() - removeAmount);
            }

            remainingToRemove -= removeAmount;
        }
    }

    // Kiểm tra item có khớp với yêu cầu custom name/lore
    private boolean itemMatches(ItemStack item, String customName, List<String> customLore) {
        if (customName == null && customLore == null) {
            return true; // Không có yêu cầu đặc biệt
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return customName == null && customLore == null;
        }

        // Kiểm tra custom name - BỎ QUA MÃ MÀU
        if (customName != null) {
            String itemName = meta.getDisplayName();
            if (itemName == null) {
                return false;
            }

            // Chuyển đổi mã màu và loại bỏ tất cả mã màu để so sánh
            String expectedName = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', customName));
            String actualName = ChatColor.stripColor(itemName);

            if (!actualName.equals(expectedName)) {
                return false;
            }
        }

        // Kiểm tra custom lore - BỎ QUA MÃ MÀU
        if (customLore != null && !customLore.isEmpty()) {
            List<String> itemLore = meta.getLore();
            if (itemLore == null || itemLore.size() != customLore.size()) {
                return false;
            }

            for (int i = 0; i < customLore.size(); i++) {
                // Chuyển đổi mã màu và loại bỏ tất cả mã màu để so sánh
                String expectedLore = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', customLore.get(i)));
                String actualLore = ChatColor.stripColor(itemLore.get(i));

                if (!actualLore.equals(expectedLore)) {
                    return false;
                }
            }
        }

        return true;
    }

    private void executeRewards(Player player, int targetLevel) {
        // SỬA: Đường dẫn config đúng
        String path = "rebirth.rebirth.levels." + targetLevel + ".rewards";

        // Thực hiện commands
        List<String> commands = rebirthConfig.getStringList(path + ".commands");
        for (String command : commands) {
            String finalCommand = command.replace("%player%", player.getName())
                                        .replace("%level%", String.valueOf(targetLevel));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
        }

        // Tặng items
        List<Map<?, ?>> rewardItems = rebirthConfig.getMapList(path + ".items");
        for (Map<?, ?> itemMap : rewardItems) {
            String material = (String) itemMap.get("material");
            int amount = (Integer) itemMap.get("amount");

            ItemStack item = new ItemStack(org.bukkit.Material.valueOf(material), amount);
            player.getInventory().addItem(item);
        }
    }

    private void applyFailurePenalties(Player player, int targetLevel) {
        try {
            // SỬA: Đường dẫn config đúng
            String path = "rebirth.rebirth.levels." + targetLevel;

            // Mất 25% tiền
            if (economy != null) {
                double currentBalance = economy.getBalance(player);
                double moneyLoss = currentBalance * 0.25; // 25%
                if (moneyLoss > 0) {
                    economy.withdrawPlayer(player, moneyLoss);
                    player.sendMessage("§c✗ Bạn đã mất " + String.format("%.0f", moneyLoss) + " tiền do chuyển sinh thất bại!");
                }
            }

            // Mất 10 cấp độ EnchantMaterial
            PlayerData playerData = DatabaseManager.getPlayerData(player.getUniqueId().toString());
            int currentLevel = playerData.getLevel();
            int newLevel = Math.max(0, currentLevel - 10); // Không cho phép level âm
            playerData.setLevel(newLevel);
            DatabaseManager.savePlayerData(playerData);

            if (currentLevel > newLevel) {
                player.sendMessage("§c✗ Bạn đã mất " + (currentLevel - newLevel) + " cấp độ EnchantMaterial do chuyển sinh thất bại!");
            }

            // Mất ngẫu nhiên 1 vật phẩm yêu cầu
            List<Map<?, ?>> requiredItems = rebirthConfig.getMapList(path + ".required-items");
            if (!requiredItems.isEmpty()) {
                // Chọn ngẫu nhiên 1 item từ danh sách yêu cầu
                Map<?, ?> randomItemMap = requiredItems.get(ThreadLocalRandom.current().nextInt(requiredItems.size()));
                String material = (String) randomItemMap.get("material");
                int amount = (Integer) randomItemMap.get("amount");

                ItemStack itemToRemove = new ItemStack(org.bukkit.Material.valueOf(material), amount);

                // Kiểm tra xem player có item này không
                if (player.getInventory().containsAtLeast(itemToRemove, amount)) {
                    player.getInventory().removeItem(itemToRemove);
                    player.sendMessage("§c✗ Bạn đã mất " + amount + "x " + material + " do chuyển sinh thất bại!");
                } else {
                    // Nếu không có đủ item, lấy tất cả những gì có
                    ItemStack[] contents = player.getInventory().getContents();
                    for (int i = 0; i < contents.length; i++) {
                        ItemStack item = contents[i];
                        if (item != null && item.getType() == org.bukkit.Material.valueOf(material)) {
                            int removedAmount = Math.min(item.getAmount(), amount);
                            if (item.getAmount() <= removedAmount) {
                                player.getInventory().setItem(i, null);
                            } else {
                                item.setAmount(item.getAmount() - removedAmount);
                            }
                            player.sendMessage("§c✗ Bạn đã mất " + removedAmount + "x " + material + " do chuyển sinh thất bại!");
                            break;
                        }
                    }
                }
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Lỗi áp dụng hình phạt chuyển sinh thất bại: " + e.getMessage());
        }
    }

    public YamlConfiguration getConfig() {
        return rebirthConfig;
    }

    public void reloadConfig() {
        rebirthConfig = YamlConfiguration.loadConfiguration(rebirthFile);
    }

    public boolean canRebirthWithDebug(Player player, int targetLevel) {
        try {
            String path = "rebirth.rebirth.levels." + targetLevel;

            RebirthData rebirthData = DatabaseManager.getRebirthData(player.getUniqueId());
            PlayerData playerData = DatabaseManager.getPlayerData(player.getUniqueId().toString());

            // Kiểm tra level chuyển sinh hiện tại
            if (rebirthData.getRebirthLevel() + 1 != targetLevel) {
                return false;
            }

            // Kiểm tra EnchantMaterial Level
            int requiredLevel = rebirthConfig.getInt(path + ".required-level", 0);
            int currentLevel = playerData.getLevel();
            if (currentLevel < requiredLevel) {
                return false;
            }

            // Kiểm tra tiền
            double requiredMoney = rebirthConfig.getDouble(path + ".required-money", 0);
            double currentMoney = economy != null ? economy.getBalance(player) : 0;
            if (economy != null && currentMoney < requiredMoney) {
                return false;
            }

            // Kiểm tra items
            List<Map<?, ?>> requiredItems = rebirthConfig.getMapList(path + ".required-items");

            for (Map<?, ?> itemMap : requiredItems) {
                if (!hasRequiredItem(player, itemMap)) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Lỗi kiểm tra điều kiện chuyển sinh: " + e.getMessage());
            return false;
        }
    }
}
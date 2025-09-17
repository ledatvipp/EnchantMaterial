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
                List<RewardData> rewards = new ArrayList<>();

                List<Map<?, ?>> rewardMaps = rewardsSection.getMapList(blockType);
                if (!rewardMaps.isEmpty()) {
                    for (int index = 0; index < rewardMaps.size(); index++) {
                        RewardData reward = parseRewardData(blockType, index, rewardMaps.get(index));
                        if (reward != null) {
                            rewards.add(reward);
                        }
                    }
                } else {
                    List<String> rewardStrings = rewardsSection.getStringList(blockType);
                    for (String rewardString : rewardStrings) {
                        RewardData reward = parseLegacyRewardData(blockType, rewardString);
                        if (reward != null) {
                            rewards.add(reward);
                        }
                    }
                }

                if (rewards.isEmpty()) {
                    plugin.getLogger().warning("Không tìm thấy reward hợp lệ cho block " + blockType + ". Hãy kiểm tra lại cấu hình!");
                }

                rewardCache.put(blockType, rewards);
            }
        } else {
            plugin.getLogger().warning("Không thể tìm thấy phần rewards trong luckyblock.yml");
        }

        lastCacheUpdate = currentTime;
    }

    private RewardData parseRewardData(String blockType, int index, Map<?, ?> rewardMap) {
        if (rewardMap == null || rewardMap.isEmpty()) {
            plugin.getLogger().warning("Reward trống tại " + blockType + "[#" + index + "]");
            return null;
        }

        Object typeObj = rewardMap.get("type");
        if (!(typeObj instanceof String)) {
            plugin.getLogger().warning("Thiếu hoặc sai định dạng 'type' cho reward tại " + blockType + "[#" + index + "]");
            return null;
        }

        String type = ((String) typeObj).trim().toLowerCase(Locale.ROOT);
        Double chance = parseDoubleField(rewardMap.get("chance"));
        if (chance == null) {
            plugin.getLogger().warning("Thiếu hoặc sai định dạng 'chance' cho reward tại " + blockType + "[#" + index + "]");
            return null;
        }

        Double amount = parseDoubleField(rewardMap.get("amount"));
        String item = rewardMap.containsKey("item") && rewardMap.get("item") != null
            ? String.valueOf(rewardMap.get("item")).trim()
            : null;
        String command = rewardMap.containsKey("command") && rewardMap.get("command") != null
            ? String.valueOf(rewardMap.get("command")).trim()
            : null;

        switch (type) {
            case "money":
                if (amount == null) {
                    plugin.getLogger().warning("Reward money tại " + blockType + "[#" + index + "] thiếu 'amount'");
                    return null;
                }
                break;
            case "item":
                if (item == null || item.isEmpty()) {
                    plugin.getLogger().warning("Reward item tại " + blockType + "[#" + index + "] thiếu 'item'");
                    return null;
                }
                if (amount == null) {
                    amount = 1d;
                }
                break;
            case "command":
                if (command == null || command.isEmpty()) {
                    plugin.getLogger().warning("Reward command tại " + blockType + "[#" + index + "] thiếu 'command'");
                    return null;
                }
                break;
            default:
                plugin.getLogger().warning("Loại reward không hợp lệ '" + type + "' tại " + blockType + "[#" + index + "]");
                return null;
        }

        return new RewardData(type, chance, amount, item, command, null);
    }

    private RewardData parseLegacyRewardData(String blockType, String rewardString) {
        String[] data = rewardString.split(",");
        if (data.length < 3) {
            plugin.getLogger().warning("Reward legacy không hợp lệ tại " + blockType + ": " + rewardString);
            return null;
        }

        try {
            String type = data[0].split(":", 2)[1].trim().toLowerCase(Locale.ROOT);
            String value = data[1].split(":", 2)[1].trim();
            double chance = Double.parseDouble(data[2].split(":", 2)[1].trim());

            Double amount = null;
            String item = null;
            String command = null;

            switch (type) {
                case "money":
                    amount = Double.parseDouble(value);
                    break;
                case "item":
                    String[] itemData = value.split(":", 2);
                    item = itemData[0].trim();
                    if (itemData.length > 1) {
                        amount = Double.parseDouble(itemData[1].trim());
                    } else {
                        amount = 1d;
                    }
                    break;
                case "command":
                    command = value;
                    break;
                default:
                    plugin.getLogger().warning("Loại reward legacy không hợp lệ '" + type + "' tại " + blockType + "");
                    return null;
            }

            return new RewardData(type, chance, amount, item, command, value);
        } catch (Exception e) {
            plugin.getLogger().warning("Lỗi khi parse reward legacy tại " + blockType + ": " + rewardString);
            return null;
        }
    }

    private Double parseDoubleField(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
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
            plugin.getLogger().warning("Lucky Block '" + blockTypeName + "' không có reward hợp lệ. Vui lòng kiểm tra cấu hình!");
        }
    }

    private void executeReward(Player player, RewardData reward) {
        switch (reward.type) {
            case "money":
                if (reward.amount != null) {
                    giveMoney(player, reward.amount);
                } else if (reward.legacyValue != null) {
                    try {
                        giveMoney(player, Double.parseDouble(reward.legacyValue));
                    } catch (NumberFormatException ex) {
                        plugin.getLogger().warning("Invalid legacy money amount: " + reward.legacyValue);
                    }
                } else {
                    plugin.getLogger().warning("Reward money thiếu amount");
                }
                break;
            case "item":
                String itemName = reward.item;
                if ((itemName == null || itemName.isEmpty()) && reward.legacyValue != null) {
                    String[] legacyItemData = reward.legacyValue.split(":", 2);
                    itemName = legacyItemData[0];
                    if (legacyItemData.length > 1 && reward.amount == null) {
                        try {
                            giveItem(player, itemName, Integer.parseInt(legacyItemData[1]));
                            return;
                        } catch (NumberFormatException e) {
                            plugin.getLogger().warning("Invalid legacy item amount: " + reward.legacyValue);
                            return;
                        }
                    }
                }

                if (itemName == null || itemName.isEmpty()) {
                    plugin.getLogger().warning("Reward item thiếu tên vật phẩm");
                    return;
                }

                int amount = 1;
                if (reward.amount != null) {
                    amount = (int) Math.max(1, Math.round(reward.amount));
                } else if (reward.legacyValue != null) {
                    String[] legacyItemData = reward.legacyValue.split(":", 2);
                    if (legacyItemData.length > 1) {
                        try {
                            amount = Integer.parseInt(legacyItemData[1]);
                        } catch (NumberFormatException e) {
                            plugin.getLogger().warning("Invalid legacy item amount: " + reward.legacyValue);
                        }
                    }
                }

                giveItem(player, itemName, amount);
                break;
            case "command":
                String command = reward.command;
                if ((command == null || command.isEmpty()) && reward.legacyValue != null) {
                    command = reward.legacyValue;
                }

                if (command == null || command.isEmpty()) {
                    plugin.getLogger().warning("Reward command thiếu chuỗi lệnh");
                    return;
                }

                command = command.replace("{player}", player.getName());
                executeCommand(player, command);
                break;
            default:
                plugin.getLogger().warning("Không thể xử lý reward type: " + reward.type);
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
        final double chance;
        final Double amount;
        final String item;
        final String command;
        final String legacyValue;

        RewardData(String type, double chance, Double amount, String item, String command, String legacyValue) {
            this.type = type;
            this.chance = chance;
            this.amount = amount;
            this.item = item;
            this.command = command;
            this.legacyValue = legacyValue;
        }
    }
}

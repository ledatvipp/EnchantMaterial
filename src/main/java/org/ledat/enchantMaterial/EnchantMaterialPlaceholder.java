package org.ledat.enchantMaterial;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.ledat.enchantMaterial.booster.BoosterManager;
import org.ledat.enchantMaterial.booster.BoosterType;
import org.ledat.enchantMaterial.booster.Booster;
import org.ledat.enchantMaterial.rebirth.RebirthData;
import org.ledat.enchantMaterial.rebirth.RebirthManager;

import java.util.*;
import java.util.stream.Collectors;
import java.text.SimpleDateFormat;

public class EnchantMaterialPlaceholder extends PlaceholderExpansion {

    private final EnchantMaterial plugin;

    public EnchantMaterialPlaceholder(EnchantMaterial plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "enchantmaterial";
    }

    @Override
    public @NotNull String getAuthor() {
        return "zonecluck";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0";
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        PlayerData data;
        try {
            data = DatabaseManager.getPlayerDataAsync(player.getUniqueId()).get();
        } catch (Exception e) {
            return "N/A";
        }

        double points = data.getPoints();
        int level = data.getLevel();
        BoosterManager manager = EnchantMaterial.getInstance().getBoosterManager();
        UUID uuid = player.getUniqueId();
        List<Double> levelRequests = plugin.getConfig().getDoubleList("level-request");

        double nextLevelPoints = (level < levelRequests.size()) ? levelRequests.get(level) : -1;
        double progressPercent = (nextLevelPoints > 0) ? Math.min(1.0, points / nextLevelPoints) : 1.0;

        switch (params.toLowerCase()) {
            case "points":
                return String.format("%.2f", points);
            case "level":
                return String.valueOf(level);
            case "level_next":
                return (level < levelRequests.size()) ? String.valueOf(level + 1) : "MAX";
            case "progress":
                return (nextLevelPoints > 0)
                        ? String.format("%.0f/%.0f", points, nextLevelPoints)
                        : "MAX";
            case "progress_bar":
                return getProgressBar(progressPercent);
            case "progress_percent":
                return String.format("%.0f%%", progressPercent * 100);
            // ✅ Placeholder mới: percent (50%)
            case "percent":
                return String.format("%.0f%%", progressPercent * 100);
            // ✅ Placeholder mới: percent_amount (100/600)
            case "percent_amount":
                return (nextLevelPoints > 0)
                        ? String.format("%.0f/%.0f", points, nextLevelPoints)
                        : "MAX";
            case "fortune_multiplier": {
                if (player.getPlayer() != null) {
                    double fortune = plugin.getFortuneManager().getMultiplier(player.getPlayer());
                    return String.format("%.2f", fortune);
                } else return "N/A";
            }
            case "drop_multiplier": {
                if (player.getPlayer() != null) {
                    double drop = plugin.getFortuneManager().getMultiplier(player.getPlayer());
                    return String.format("%.2f", drop);
                } else return "N/A";
            }

            // Booster placeholders
            case "booster_points":
            case "booster_drop":
            case "booster_exp": {
                BoosterType type = BoosterType.valueOf(params.replace("booster_", "").toUpperCase());
                return manager.getBoosters(uuid).stream()
                        .filter(b -> b.getType() == type)
                        .findFirst()
                        .map(b -> String.format("x%.1f", b.getMultiplier()))
                        .orElse("x1.0");
            }

            case "booster_time_points":
            case "booster_time_drop":
            case "booster_time_exp": {
                BoosterType type = BoosterType.valueOf(params.replace("booster_time_", "").toUpperCase());
                return manager.getBoosters(uuid).stream()
                        .filter(b -> b.getType() == type)
                        .findFirst()
                        .map(Booster::formatTimeLeft)
                        .orElse("0s");
            }

            case "booster_summary": {
                List<Booster> boosters = manager.getBoosters(uuid);
                if (boosters.isEmpty()) return "Chưa booster";

                StringBuilder summary = new StringBuilder();
                for (Booster b : boosters) {
                    String line = String.format("§f%s §ex%.1f§7 (%s)",
                            b.getType().name().toLowerCase(),
                            b.getMultiplier(),
                            b.formatTimeLeft());
                    summary.append(line).append(" | ");
                }
                if (summary.length() > 3) summary.setLength(summary.length() - 3);
                return summary.toString();
            }

            case "booster_active_count":
                return String.valueOf(manager.getBoosters(uuid).size());

            // Rebirth placeholders
            case "rebirth_level": {
                try {
                    RebirthData rebirthData = DatabaseManager.getRebirthData(uuid);
                    return String.valueOf(rebirthData.getRebirthLevel());
                } catch (Exception e) {
                    return "0";
                }
            }
            
            case "rebirth_next_level": {
                try {
                    RebirthData rebirthData = DatabaseManager.getRebirthData(uuid);
                    RebirthManager rebirthManager = plugin.getRebirthManager();
                    int maxLevel = rebirthManager.getConfig().getInt("rebirth.rebirth.max-level", 10);
                    int currentLevel = rebirthData.getRebirthLevel();
                    return currentLevel >= maxLevel ? "MAX" : String.valueOf(currentLevel + 1);
                } catch (Exception e) {
                    return "1";
                }
            }
            
            case "rebirth_max_level": {
                try {
                    RebirthManager rebirthManager = plugin.getRebirthManager();
                    return String.valueOf(rebirthManager.getConfig().getInt("rebirth.rebirth.max-level", 10));
                } catch (Exception e) {
                    return "10";
                }
            }
            
            case "rebirth_last_time": {
                try {
                    RebirthData rebirthData = DatabaseManager.getRebirthData(uuid);
                    long lastTime = rebirthData.getLastRebirthTime();
                    if (lastTime == 0) return "Chưa từng chuyển sinh";
                    SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
                    return sdf.format(new Date(lastTime));
                } catch (Exception e) {
                    return "N/A";
                }
            }
            
            case "rebirth_can_rebirth": {
                try {
                    if (player.getPlayer() == null) return "false";
                    RebirthData rebirthData = DatabaseManager.getRebirthData(uuid);
                    RebirthManager rebirthManager = plugin.getRebirthManager();
                    int nextLevel = rebirthData.getRebirthLevel() + 1;
                    return String.valueOf(rebirthManager.canRebirth(player.getPlayer(), nextLevel));
                } catch (Exception e) {
                    return "false";
                }
            }
            
            case "rebirth_required_level": {
                try {
                    RebirthData rebirthData = DatabaseManager.getRebirthData(uuid);
                    RebirthManager rebirthManager = plugin.getRebirthManager();
                    int nextLevel = rebirthData.getRebirthLevel() + 1;
                    String path = "rebirth.rebirth.levels." + nextLevel + ".required-level";
                    return String.valueOf(rebirthManager.getConfig().getInt(path, 0));
                } catch (Exception e) {
                    return "0";
                }
            }
            
            case "rebirth_required_money": {
                try {
                    RebirthData rebirthData = DatabaseManager.getRebirthData(uuid);
                    RebirthManager rebirthManager = plugin.getRebirthManager();
                    int nextLevel = rebirthData.getRebirthLevel() + 1;
                    String path = "rebirth.rebirth.levels." + nextLevel + ".required-money";
                    double money = rebirthManager.getConfig().getDouble(path, 0);
                    return formatValue(money);
                } catch (Exception e) {
                    return "0";
                }
            }
            
            case "rebirth_success_rate": {
                try {
                    RebirthData rebirthData = DatabaseManager.getRebirthData(uuid);
                    RebirthManager rebirthManager = plugin.getRebirthManager();
                    int nextLevel = rebirthData.getRebirthLevel() + 1;
                    String path = "rebirth.rebirth.levels." + nextLevel + ".success-rate";
                    double rate = rebirthManager.getConfig().getDouble(path, 100.0);
                    return String.format("%.1f%%", rate);
                } catch (Exception e) {
                    return "100.0%";
                }
            }
            
            case "rebirth_progress": {
                try {
                    RebirthData rebirthData = DatabaseManager.getRebirthData(uuid);
                    RebirthManager rebirthManager = plugin.getRebirthManager();
                    int maxLevel = rebirthManager.getConfig().getInt("rebirth.rebirth.max-level", 10);
                    int currentLevel = rebirthData.getRebirthLevel();
                    return String.format("%d/%d", currentLevel, maxLevel);
                } catch (Exception e) {
                    return "0/10";
                }
            }
            
            case "rebirth_progress_percent": {
                try {
                    RebirthData rebirthData = DatabaseManager.getRebirthData(uuid);
                    RebirthManager rebirthManager = plugin.getRebirthManager();
                    int maxLevel = rebirthManager.getConfig().getInt("rebirth.rebirth.max-level", 10);
                    int currentLevel = rebirthData.getRebirthLevel();
                    double percent = maxLevel > 0 ? (double) currentLevel / maxLevel * 100 : 0;
                    return String.format("%.1f%%", percent);
                } catch (Exception e) {
                    return "0.0%";
                }
            }
            
            case "rebirth_progress_bar": {
                try {
                    RebirthData rebirthData = DatabaseManager.getRebirthData(uuid);
                    RebirthManager rebirthManager = plugin.getRebirthManager();
                    int maxLevel = rebirthManager.getConfig().getInt("rebirth.rebirth.max-level", 10);
                    int currentLevel = rebirthData.getRebirthLevel();
                    double percent = maxLevel > 0 ? (double) currentLevel / maxLevel : 0;
                    return getProgressBar(percent);
                } catch (Exception e) {
                    return getProgressBar(0);
                }
            }

            // Permission booster placeholders
            case "perm_booster_points": {
                if (player.getPlayer() != null) {
                    double permMultiplier = manager.getPermissionMultiplier(player.getPlayer(), BoosterType.POINTS);
                    return String.format("x%.1f", permMultiplier);
                } else return "x1.0";
            }
            
            case "perm_booster_exp": {
                if (player.getPlayer() != null) {
                    double permMultiplier = manager.getPermissionMultiplier(player.getPlayer(), BoosterType.EXP);
                    return String.format("x%.1f", permMultiplier);
                } else return "x1.0";
            }
            
            case "perm_booster_drop": {
                if (player.getPlayer() != null) {
                    double permMultiplier = manager.getPermissionMultiplier(player.getPlayer(), BoosterType.DROP);
                    return String.format("x%.1f", permMultiplier);
                } else return "x1.0";
            }
            
            case "total_points_multiplier": {
                if (player.getPlayer() != null) {
                    double totalMultiplier = manager.getPointsMultiplier(player.getPlayer());
                    return String.format("x%.1f", totalMultiplier);
                } else return "x1.0";
            }
            
            case "total_exp_multiplier": {
                if (player.getPlayer() != null) {
                    double totalMultiplier = manager.getExpMultiplier(player.getPlayer());
                    return String.format("x%.1f", totalMultiplier);
                } else return "x1.0";
            }
            
            case "total_drop_multiplier": {
                if (player.getPlayer() != null) {
                    double totalMultiplier = manager.getDropMultiplier(player.getPlayer());
                    return String.format("x%.1f", totalMultiplier);
                } else return "x1.0";
            }

            default:
                if (params.startsWith("top_")) {
                    return handleTop(params);
                } else if (params.startsWith("rebirth_top_")) {
                    return handleRebirthTop(params);
                }
                return null;
        }
    }

    private String handleRebirthTop(String params) {
        String[] parts = params.split("_");
        if (parts.length < 4) return null; // rebirth_top_1_level

        int index;
        try {
            index = Integer.parseInt(parts[2]) - 1;
        } catch (NumberFormatException e) {
            return null;
        }

        // Lấy top rebirth từ database
        try {
            List<RebirthData> topRebirth = getTopRebirthPlayers();
            
            if (index >= topRebirth.size()) return "";

            RebirthData rebirthData = topRebirth.get(index);
            String sub = parts[3];

            switch (sub.toLowerCase()) {
                case "name":
                    return Bukkit.getOfflinePlayer(rebirthData.getUuid()).getName();
                case "level":
                    return String.valueOf(rebirthData.getRebirthLevel());
                case "last_time":
                    if (rebirthData.getLastRebirthTime() == 0) return "Chưa từng";
                    SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy");
                    return sdf.format(new Date(rebirthData.getLastRebirthTime()));
                default:
                    return null;
            }
        } catch (Exception e) {
            return "N/A";
        }
    }
    
    private List<RebirthData> getTopRebirthPlayers() {
        // Tạo một method helper để lấy top rebirth players
        // Bạn có thể implement logic này trong DatabaseManager
        try {
            // Tạm thời return empty list, bạn cần implement method này trong DatabaseManager
            return new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String handleTop(String params) {
        String[] parts = params.split("_");
        if (parts.length < 3) return null;

        int index;
        try {
            index = Integer.parseInt(parts[1]) - 1;
        } catch (NumberFormatException e) {
            return null;
        }

        try {
            Map<UUID, PlayerData> allData = DatabaseManager.getAllPlayerData();
            List<Map.Entry<UUID, PlayerData>> sorted = allData.entrySet().stream()
                    .sorted(Comparator.comparingDouble(e -> -e.getValue().getPoints()))
                    .collect(Collectors.toList());

            if (index >= sorted.size()) return "";

            Map.Entry<UUID, PlayerData> entry = sorted.get(index);
            String sub = parts[2];

            switch (sub.toLowerCase()) {
                case "name":
                    return Bukkit.getOfflinePlayer(entry.getKey()).getName();
                case "points":
                    return String.format("%.2f", entry.getValue().getPoints());
                case "points_format":
                    return formatValue(entry.getValue().getPoints());
                default:
                    return null;
            }
        } catch (Exception e) {
            return "N/A";
        }
    }

    // ✅ Cải tiến getProgressBar với khả năng config linh hoạt hơn
    private String getProgressBar(double percent) {
        if (!plugin.getConfig().getBoolean("progress-bar.enabled", true)) {
            return "";
        }

        int length = plugin.getConfig().getInt("progress-bar.length", 10);
        String filledChar = plugin.getConfig().getString("progress-bar.filled", "◼");
        String emptyChar = plugin.getConfig().getString("progress-bar.empty", "◼");
        String filledColor = plugin.getConfig().getString("progress-bar.filled-color", "&a");
        String emptyColor = plugin.getConfig().getString("progress-bar.empty-color", "&7");

        filledColor = ChatColor.translateAlternateColorCodes('&', filledColor);
        emptyColor = ChatColor.translateAlternateColorCodes('&', emptyColor);

        int filled = (int) Math.round(length * percent);
        int empty = length - filled;

        StringBuilder progressBar = new StringBuilder();
        
        // Thêm các ký tự đã fill
        if (filled > 0) {
            progressBar.append(filledColor);
            for (int i = 0; i < filled; i++) {
                progressBar.append(filledChar);
            }
        }
        
        // Thêm các ký tự empty
        if (empty > 0) {
            progressBar.append(emptyColor);
            for (int i = 0; i < empty; i++) {
                progressBar.append(emptyChar);
            }
        }

        return progressBar.toString();
    }

    private String formatValue(double value) {
        if (value >= 1_000_000) {
            return String.format("%.1fM", value / 1_000_000);
        } else if (value >= 1_000) {
            return String.format("%.1fk", value / 1_000);
        } else {
            return String.format("%.1f", value);
        }
    }
}

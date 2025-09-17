package org.ledat.enchantMaterial;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.ledat.enchantMaterial.booster.Booster;
import org.ledat.enchantMaterial.booster.BoosterActivationResult;
import org.ledat.enchantMaterial.booster.BoosterManager;
import org.ledat.enchantMaterial.booster.BoosterRequest;
import org.ledat.enchantMaterial.booster.BoosterSource;
import org.ledat.enchantMaterial.booster.BoosterStackingStrategy;
import org.ledat.enchantMaterial.booster.BoosterType;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class CommandManager implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sendUsageMessage(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "level":
                return handleLevelCommand(sender);
            case "reload":
                return handleReloadCommand(sender, args);
            case "add":
                return handleAddCommand(sender, args);
            case "booster":
                return handleBoosterCommand(sender, args);
            case "rewards":
                return handleRewardsCommand(sender);
            case "give":
                return handleGiveCommand(sender, args);
            case "chuyensinh":
            case "rebirth":
                return handleRebirthCommand(sender, args);
            case "permbooster":
                return handlePermissionBooster(sender, args);
            case "admin":
                return handleAdminCommand(sender, args);
            default:
                sendUsageMessage(sender);
                return true;
        }
    }

    private boolean handleLevelCommand(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cChỉ người chơi mới có thể sử dụng lệnh này.");
            return true;
        }
        
        Player player = (Player) sender;
        displayLevelInfo(player);
        return true;
    }

    private boolean handleReloadCommand(CommandSender sender) {
        if (!sender.hasPermission("enchantmaterial.admin")) {
            sender.sendMessage(ConfigManager.getMessage("no_permission"));
            return true;
        }

        try {
            sender.sendMessage("§e⏳ Đang reload plugin...");
            
            // 1. Reload config.yml chính
            EnchantMaterial.getInstance().reloadConfig();
            sender.sendMessage("§a✅ Đã reload config.yml");
            
            // 2. Reload booster.yml
            EnchantMaterial.getInstance().loadBoosterConfig();
            sender.sendMessage("§a✅ Đã reload booster.yml");
            
            // 3. Reload level-rewards.yml
            if (EnchantMaterial.getInstance().getLevelRewardsManager() != null) {
                EnchantMaterial.getInstance().getLevelRewardsManager().reloadConfig();
                sender.sendMessage("§a✅ Đã reload level-rewards.yml");
            }
            
            // 4. Reload rebirth.yml
            EnchantMaterial.getInstance().loadRebirthConfig();
            sender.sendMessage("§a✅ Đã reload rebirth.yml");
            
            // 5. Reload luckyblock.yml
            EnchantMaterial.getInstance().loadLuckyBlockConfig();
            sender.sendMessage("§a✅ Đã reload luckyblock.yml");
            
            // 6. Reload level-system.yml
            EnchantMaterial.getInstance().loadLevelSystemConfig();
            sender.sendMessage("§a✅ Đã reload level-system.yml");
            
            // 7. Clear caches nếu cần
            DatabaseManager.clearAllCaches();
            sender.sendMessage("§a✅ Đã xóa cache");
            
            sender.sendMessage("§a🎉 Plugin đã được reload hoàn toàn thành công!");
            
        } catch (Exception e) {
            sender.sendMessage("§c❌ Lỗi khi reload plugin: " + e.getMessage());
            EnchantMaterial.getInstance().getLogger().severe("Reload error: " + e.getMessage());
            e.printStackTrace();
        }
        return true;
    }

    private boolean handleAddCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("enchantmaterial.admin")) {
            sender.sendMessage(ConfigManager.getMessage("no_permission"));
            return true;
        }
        
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cChỉ người chơi mới có thể sử dụng lệnh này.");
            return true;
        }
        
        if (args.length < 3) {
            sender.sendMessage("§cSử dụng: /enchantmaterial add <enchant> <level>");
            return true;
        }

        Player player = (Player) sender;
        ItemStack tool = player.getInventory().getItemInMainHand();
        
        if (tool == null || tool.getType() == Material.AIR) {
            player.sendMessage("§cBạn cần cầm một dụng cụ trong tay!");
            return true;
        }

        String enchantKey = args[1];
        int level;
        
        try {
            level = Integer.parseInt(args[2]);
            if (level <= 0) {
                sender.sendMessage("§cLevel phải là số dương!");
                return true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§cLevel phải là một số hợp lệ!");
            return true;
        }

        String displayType = ConfigManager.getEnchantDisplayType(enchantKey);
        if (displayType == null) {
            player.sendMessage("§c✗ Enchant '" + enchantKey + "' không tồn tại!");
            return true;
        }

        int maxLevel = EnchantMaterial.getInstance().getConfig().getInt("enchantments." + enchantKey + ".max_level", 1);
        if (level > maxLevel) {
            sender.sendMessage("§eLevel vượt quá giới hạn (" + maxLevel + "), đã điều chỉnh xuống " + maxLevel);
            level = maxLevel;
        }

        // Apply enchantment
        String loreFormat = EnchantMaterial.getInstance().getConfig().getString("messages.add_lore_format", "&fBổ trợ: %display_type%");
        loreFormat = loreFormat.replace("%display_type%", displayType);

        ItemMeta meta = tool.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore();
            if (lore == null) {
                lore = new ArrayList<>();
            }

            String enchantPrefix = ChatColor.translateAlternateColorCodes('&', loreFormat) + " ";
            boolean found = false;

            for (int i = 0; i < lore.size(); i++) {
                String line = lore.get(i);
                if (line.startsWith(enchantPrefix)) {
                    lore.set(i, enchantPrefix + level);
                    found = true;
                    break;
                }
            }

            if (!found) {
                lore.add(0, enchantPrefix + level);
            }

            meta.setLore(lore);
            tool.setItemMeta(meta);

            sender.sendMessage("§aĐã thêm enchant '" + enchantKey + "' level " + level + " vào dụng cụ!");
        }

        return true;
    }

    private boolean handleBoosterCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendBoosterUsage(sender);
            return true;
        }

        String boosterAction = args[1].toLowerCase();
        BoosterManager boosterManager = EnchantMaterial.getInstance().getBoosterManager();

        switch (boosterAction) {
            case "start":
                return handleBoosterStart(sender, args, boosterManager);
            case "stop":
                return handleBoosterStop(sender, args, boosterManager);
            case "list":
                return handleBoosterList(sender, args, boosterManager);
            default:
                sendBoosterUsage(sender);
                return true;
        }
    }

    private void sendBoosterUsage(CommandSender sender) {
        sender.sendMessage("§6=== Booster Commands ===");
        
        if (sender.hasPermission("enchantmaterial.admin")) {
            sender.sendMessage("§e/em booster start <player> <type> <multiplier> <seconds> [mode]");
            sender.sendMessage("§e/em booster list <player> §7- Xem booster của người chơi");
            sender.sendMessage("§e/em booster stop <type> [player] §7- Dừng booster");
        } else {
            sender.sendMessage("§e/em booster stop <type> §7- Dừng booster của bạn");
        }

        sender.sendMessage("§7Types: §fpoints, drop, exp");
        sender.sendMessage("§7Modes: §fsmart, extend, replace, strict");
    }

    private boolean handleBoosterStart(CommandSender sender, String[] args, BoosterManager boosterManager) {
        if (!sender.hasPermission("enchantmaterial.admin")) {
            sender.sendMessage("§cBạn không có quyền sử dụng lệnh này!");
            return true;
        }

        if (args.length < 6) {
            sender.sendMessage("§cSử dụng: /em booster start <player> <type> <multiplier> <seconds> [mode]");
            return true;
        }

        String playerName = args[2];
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            sender.sendMessage("§cNgười chơi '" + playerName + "' không trực tuyến.");
            return true;
        }

        BoosterType type;
        try {
            type = BoosterType.valueOf(args[3].toUpperCase());
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cLoại booster không hợp lệ! Sử dụng: points, drop, exp");
            return true;
        }

        double multiplier;
        int duration;
        try {
            multiplier = Double.parseDouble(args[4]);
            duration = Integer.parseInt(args[5]);

            if (multiplier <= 0) {
                sender.sendMessage("§cHệ số nhân phải lớn hơn 0!");
                return true;
            }

            if (duration <= 0) {
                sender.sendMessage("§cThời gian phải lớn hơn 0!");
                return true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§cHệ số hoặc thời gian không hợp lệ.");
            return true;
        }

        BoosterStackingStrategy stackingStrategy = args.length >= 7
                ? BoosterStackingStrategy.fromString(args[6])
                : BoosterStackingStrategy.SMART;

        BoosterRequest.Builder builder = BoosterRequest.builder(type)
                .multiplier(multiplier)
                .durationSeconds(duration)
                .stackingStrategy(stackingStrategy)
                .source(BoosterSource.ADMIN_COMMAND)
                .note("Kích hoạt bởi " + sender.getName())
                .saveToStorage(true);

        if (sender.hasPermission("enchantmaterial.booster.bypasslimit") || sender.hasPermission("enchantmaterial.admin")) {
            builder.bypassLimit(true);
        }

        BoosterActivationResult result = boosterManager.processBoosterRequest(target, builder.build());

        if (!result.isSuccess()) {
            String reason = result.getMessage() != null ? result.getMessage() : result.getFailureReason().getDefaultMessage();
            sender.sendMessage("§c" + reason);
            return true;
        }

        BoosterActivationResult.Status status = result.getStatus();
        Booster activeBooster = result.getBooster();
        Booster previous = result.getPreviousBooster();

        switch (status) {
            case CREATED -> {
                sender.sendMessage("§a✓ Đã kích hoạt booster " + type.name().toLowerCase() + " x" + multiplier +
                        " cho " + target.getName() + " trong " + duration + " giây (§7" + stackingStrategy.getDisplayName() + "§a)");
                target.sendMessage("§e✨ Bạn nhận được booster §f" + type.name().toLowerCase() + " x" + multiplier +
                        " §etrong §f" + duration + "§e giây!");
            }
            case EXTENDED -> {
                long addedSeconds = result.getAdditionalDurationSeconds();
                sender.sendMessage("§a✓ Đã cộng thêm §f" + addedSeconds + "s §acho booster " + type.name().toLowerCase() + " của " + target.getName());
                target.sendMessage("§e⏳ Booster §f" + type.name().toLowerCase() + " §ecủa bạn được kéo dài thêm §f" + addedSeconds + "§e giây!");
            }
            case REPLACED -> {
                double oldMultiplier = previous != null ? previous.getMultiplier() : multiplier;
                sender.sendMessage("§a✓ Đã nâng cấp booster " + type.name().toLowerCase() + " của " + target.getName() +
                        " từ x" + String.format(Locale.US, "%.1f", oldMultiplier) + " lên x" + String.format(Locale.US, "%.1f", activeBooster.getMultiplier()) +
                        " trong " + activeBooster.getTimeLeftSeconds() + " giây");
                target.sendMessage("§e⚡ Booster §f" + type.name().toLowerCase() + " §eđược nâng cấp lên §fx" +
                        String.format(Locale.US, "%.1f", activeBooster.getMultiplier()) + "§e!");
            }
            case QUEUED -> {
                sender.sendMessage("§eBooster đã được xếp hàng và sẽ kích hoạt sau khi booster hiện tại kết thúc.");
                target.sendMessage("§e✨ Booster mới của bạn sẽ tự động kích hoạt sau khi booster hiện tại kết thúc!");
            }
        }

        return true;
    }

    private boolean handleBoosterStop(CommandSender sender, String[] args, BoosterManager boosterManager) {
        if (args.length < 3) {
            sender.sendMessage("§cSử dụng: /em booster stop <type> [player]");
            return true;
        }

        BoosterType type;
        try {
            type = BoosterType.valueOf(args[2].toUpperCase());
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cLoại booster không hợp lệ! Sử dụng: points, drop, exp");
            return true;
        }

        // Admin stopping other player's booster
        if (args.length == 4) {
            if (!sender.hasPermission("enchantmaterial.admin")) {
                sender.sendMessage("§cBạn không có quyền dừng booster của người khác.");
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[3]);
            if (target == null) {
                sender.sendMessage("§c✗ Người chơi '" + args[3] + "' không trực tuyến.");
                return true;
            }

            boolean removed = boosterManager.removeBooster(target.getUniqueId(), type);
            if (removed) {
                sender.sendMessage("§aĐã dừng booster " + type.name().toLowerCase() + " của " + target.getName());
                target.sendMessage("§cBooster " + type.name().toLowerCase() + " của bạn đã bị dừng bởi admin.");
            } else {
                sender.sendMessage("§eNgười chơi không có booster loại này.");
            }
            return true;
        }

        // Player stopping their own booster
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cConsole cần chỉ rõ tên người chơi: /em booster stop <type> <player>");
            return true;
        }

        Player player = (Player) sender;
        boolean removed = boosterManager.removeBooster(player.getUniqueId(), type);
        if (removed) {
            player.sendMessage("§aBạn đã dừng booster " + type.name().toLowerCase());
        } else {
            player.sendMessage("§cBạn không có booster loại này.");
        }
        return true;
    }

    private boolean handleBoosterList(CommandSender sender, String[] args, BoosterManager boosterManager) {
        if (args.length < 3) {
            sender.sendMessage("§cSử dụng: /em booster list <player>");
            return true;
        }

        if (!sender.hasPermission("enchantmaterial.admin")) {
            sender.sendMessage("§c✗ Bạn không có quyền xem booster của người khác.");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage("§c✗ Người chơi '" + args[2] + "' không trực tuyến.");
            return true;
        }

        List<Booster> boosters = boosterManager.getBoosters(target.getUniqueId());
        if (boosters.isEmpty()) {
            sender.sendMessage("§7📋 " + target.getName() + " không có booster nào.");
        } else {
            sender.sendMessage("§a📋 Booster của §f" + target.getName() + ":");
            for (Booster b : boosters) {
                StringBuilder line = new StringBuilder("§8  ▪ §f")
                        .append(b.getType().getIcon()).append(" ")
                        .append(b.getType().name().toLowerCase()).append(" x")
                        .append(String.format(Locale.US, "%.1f", b.getMultiplier()))
                        .append(" §7(").append(b.formatTimeLeft()).append(")");

                boosterManager.getBoosterMetadata(target.getUniqueId(), b.getType()).ifPresent(metadata -> {
                    line.append(" §7[").append(metadata.getStackingStrategy().getDisplayName());
                    if (metadata.getSource() != BoosterSource.UNKNOWN) {
                        line.append(" · ").append(metadata.getSource().getDisplayName());
                    }
                    line.append(']');
                });

                sender.sendMessage(line.toString());
            }
        }
        return true;
    }

    private boolean handleRewardsCommand(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cChỉ người chơi mới có thể sử dụng lệnh này.");
            return true;
        }

        Player player = (Player) sender;
        try {
            EnchantMaterial.getInstance().getLevelRewardsGUI().openGUI(player, 0);
        } catch (Exception e) {
            player.sendMessage("§cLỗi khi mở giao diện: " + e.getMessage());
        }
        return true;
    }

    private void displayLevelInfo(Player player) {
        UUID uuid = player.getUniqueId();
        boolean hasCache = DatabaseManager.getCached(uuid) != null;

        if (!hasCache) {
            player.sendMessage("§e⏳ Đang tải dữ liệu cấp độ, vui lòng chờ...");
        }

        DatabaseManager.getPlayerDataCachedOrAsync(uuid, data -> {
            if (data == null || !player.isOnline()) {
                return;
            }

            Bukkit.getScheduler().runTask(EnchantMaterial.getInstance(), () -> sendLevelInfoMessage(player, data));
        });
    }

    private void sendLevelInfoMessage(Player player, PlayerData data) {
        double currentPoints = data.getPoints();
        int currentLevel = data.getLevel();

        List<Double> levelRequests = EnchantMaterial.getInstance().getConfig().getDoubleList("level-request");

        double pointsForNextLevel = currentLevel < levelRequests.size() ? levelRequests.get(currentLevel) : 0;
        double pointsNeeded = pointsForNextLevel - currentPoints;

        List<String> messageTemplate = EnchantMaterial.getInstance().getConfig().getStringList("level-info-message.message");

        if (EnchantMaterial.getInstance().getConfig().getBoolean("level-info-message.enabled") && messageTemplate != null) {
            for (String line : messageTemplate) {
                String formatted = line
                        .replace("{current_level}", String.valueOf(currentLevel))
                        .replace("{current_points}", String.format("%.2f", currentPoints))
                        .replace("{points_needed}", String.format("%.2f", pointsNeeded))
                        .replace("{points_for_next_level}", String.format("%.2f", pointsForNextLevel))
                        .replace("{next_level}", String.valueOf(currentLevel + 1))
                        .replace("{max_level_reached}", currentLevel >= levelRequests.size() ? "true" : "false");

                player.sendMessage(ChatColor.translateAlternateColorCodes('&', formatted));
            }
            return;
        }

        player.sendMessage("§6=== Thông tin cấp độ ===");
        player.sendMessage("§a📊 Cấp độ hiện tại: §f" + currentLevel);
        player.sendMessage("§a💎 Điểm hiện tại: §f" + String.format("%.2f", currentPoints));

        if (currentLevel < levelRequests.size()) {
            player.sendMessage("§e⭐ Điểm cần thiết để lên cấp tiếp theo: §f" + String.format("%.2f", pointsNeeded));
            player.sendMessage("§e🎯 Điểm cần để lên cấp " + (currentLevel + 1) + ": §f" + String.format("%.2f", pointsForNextLevel));
        } else {
            player.sendMessage("§c🏆 Bạn đã đạt cấp cao nhất!");
        }
    }

    private boolean handleGiveCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("enchantmaterial.admin")) {
            sender.sendMessage("§c✗ Bạn không có quyền sử dụng lệnh này!");
            return true;
        }

        if (args.length < 4) {
            sender.sendMessage("§c✗ Sử dụng: /enchantmaterial give <player> <level|points|score> <amount>");
            sender.sendMessage("§7Ví dụ: /enchantmaterial give Steve level 5");
            sender.sendMessage("§7Ví dụ: /enchantmaterial give Steve points 100.5");
            sender.sendMessage("§7Ví dụ: /enchantmaterial give Steve score 50");
            return true;
        }

        String targetPlayerName = args[1];
        String type = args[2].toLowerCase();
        String amountStr = args[3];

        // Tìm người chơi
        Player targetPlayer = Bukkit.getPlayer(targetPlayerName);
        if (targetPlayer == null) {
            sender.sendMessage("§c✗ Không tìm thấy người chơi: " + targetPlayerName);
            return true;
        }

        try {
            switch (type) {
                case "level": {
                    int level = Integer.parseInt(amountStr);
                    if (level < 1) {
                        sender.sendMessage("§cLevel phải lớn hơn 0!");
                        return true;
                    }

                    UUID uuid = targetPlayer.getUniqueId();
                    boolean cached = DatabaseManager.getCached(uuid) != null;
                    if (!cached) {
                        sender.sendMessage("§e⏳ Đang tải dữ liệu level của " + targetPlayer.getName() + "...");
                    }

                    DatabaseManager.getPlayerDataCachedOrAsync(uuid, data -> {
                        if (data == null) {
                            return;
                        }

                        data.setLevel(data.getLevel() + level);
                        DatabaseManager.savePlayerDataAsync(data);

                        Bukkit.getScheduler().runTask(EnchantMaterial.getInstance(), () -> {
                            sender.sendMessage("§aĐã cộng thêm " + level + " level cho " + targetPlayer.getName() + " (Tổng: " + data.getLevel() + ")");
                            targetPlayer.sendMessage("§aBạn đã được cộng thêm " + level + " level bởi " + sender.getName() + " (Tổng: " + data.getLevel() + ")");
                        });
                    });
                    break;
                }
                case "points":
                case "score": {
                    double points = Double.parseDouble(amountStr);
                    if (points < 0) {
                        sender.sendMessage("§cPoints phải lớn hơn hoặc bằng 0!");
                        return true;
                    }

                    UUID targetUuid = targetPlayer.getUniqueId();
                    boolean hasCache = DatabaseManager.getCached(targetUuid) != null;
                    if (!hasCache) {
                        sender.sendMessage("§e⏳ Đang tải dữ liệu điểm của " + targetPlayer.getName() + "...");
                    }

                    DatabaseManager.getPlayerDataCachedOrAsync(targetUuid, data -> {
                        if (data == null) {
                            return;
                        }

                        double newTotalPoints = data.getPoints() + points;
                        data.setPoints(newTotalPoints);

                        handleLevelUpdate(targetPlayer, data);
                        DatabaseManager.savePlayerDataAsync(data);

                        Bukkit.getScheduler().runTask(EnchantMaterial.getInstance(), () -> {
                            sender.sendMessage("§aĐã cộng thêm " + points + " points cho " + targetPlayer.getName() + " (Tổng: " + String.format("%.2f", newTotalPoints) + ")");
                            targetPlayer.sendMessage("§aBạn đã được cộng thêm " + points + " points bởi " + sender.getName() + " (Tổng: " + String.format("%.2f", newTotalPoints) + ")");
                        });
                    });
                    break;
                }
                default:
                    sender.sendMessage("§cLoại không hợp lệ! Sử dụng: level, points, hoặc score");
                    return true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§cSố lượng không hợp lệ: " + amountStr);
            return true;
        } catch (Exception e) {
            sender.sendMessage("§cLỗi khi thực hiện lệnh: " + e.getMessage());
            e.printStackTrace();
            return true;
        }

        return true;
    }

    private void handleLevelUpdate(Player player, PlayerData data) {
        List<Double> levelRequests = EnchantMaterial.getInstance().getConfig().getDoubleList("level-request");
        int currentLevel = data.getLevel();
        int calculatedLevel = currentLevel;

        for (int i = 0; i < levelRequests.size(); i++) {
            if (data.getPoints() >= levelRequests.get(i)) {
                calculatedLevel = i + 1;
            } else {
                break;
            }
        }

        if (calculatedLevel <= currentLevel) {
            return;
        }

        data.setLevel(calculatedLevel);
        final int finalNewLevel = calculatedLevel;

        Bukkit.getScheduler().runTask(EnchantMaterial.getInstance(), () -> {
            String title = ChatColor.translateAlternateColorCodes('&',
                    EnchantMaterial.getInstance().getConfig().getString("level-up-title.title")
                            .replace("%next_level%", String.valueOf(finalNewLevel)));
            String subtitle = ChatColor.translateAlternateColorCodes('&',
                    EnchantMaterial.getInstance().getConfig().getString("level-up-title.subtitle")
                            .replace("%next_level%", String.valueOf(finalNewLevel)));

            player.sendTitle(title, subtitle, 10, 70, 20);
            player.sendMessage("§a✨ Chúc mừng! Bạn đã lên cấp " + finalNewLevel + "!");
        });
    }
        
    private boolean handleRebirthCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§c✗ Chỉ người chơi mới có thể sử dụng lệnh này!");
            return true;
        }

        Player player = (Player) sender;

        if (args.length >= 2 && args[1].equalsIgnoreCase("edit")) {
            if (!sender.hasPermission("enchantmaterial.admin")) {
                sender.sendMessage(ConfigManager.getMessage("no_permission"));
                return true;
            }

            EnchantMaterial.getInstance().getRebirthConfigEditorGUI().openLevelSelector(player);
            return true;
        }

        EnchantMaterial.getInstance().getRebirthGUI().openGUI(player);
        return true;
    }

    private boolean handlePermissionBooster(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cLệnh này chỉ dành cho người chơi!");
            return true;
        }
        
        Player player = (Player) sender;
        BoosterManager boosterManager = EnchantMaterial.getInstance().getBoosterManager();
        
        if (args.length == 1) {
            // ✅ CẢI TIẾN: Hiển thị thông tin chi tiết hơn
            String info = boosterManager.getDetailedBoosterInfo(player);
            player.sendMessage(info);
            return true;
        }
        
        // Admin có thể xem thông tin của player khác
        if (args.length == 2 && sender.hasPermission("enchantmaterial.permissionbooster.admin")) {
            String targetName = args[1];
            Player target = Bukkit.getPlayer(targetName);
            if (target == null) {
                sender.sendMessage("§cKhông tìm thấy player: " + targetName);
                return true;
            }
            
            String info = boosterManager.getDetailedBoosterInfo(target);
            sender.sendMessage("§6Thông tin booster của " + target.getName() + ":");
            sender.sendMessage(info);
            return true;
        }
        
        sender.sendMessage("§cSử dụng: /em permissionbooster [player]");
        return true;
    }

    private boolean handleReloadCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("enchantmaterial.admin")) {
            sender.sendMessage(ConfigManager.getMessage("no_permission"));
            return true;
        }

        try {
            // Nếu không có tham số hoặc tham số là "all", reload tất cả
            if (args.length == 1 || args[1].equalsIgnoreCase("all")) {
                sender.sendMessage("§e⏳ Đang reload toàn bộ plugin...");
                EnchantMaterial.getInstance().reloadAllConfigs();
                DatabaseManager.clearAllCaches();
                sender.sendMessage("§a🎉 Đã reload toàn bộ plugin thành công!");
                return true;
            }
        
            // Reload từng file riêng lẻ
            String configType = args[1].toLowerCase();
            switch (configType) {
                case "config":
                    EnchantMaterial.getInstance().reloadConfig();
                    sender.sendMessage("§a✅ Đã reload config.yml");
                    break;
                case "booster":
                    EnchantMaterial.getInstance().loadBoosterConfig();
                    sender.sendMessage("§a✅ Đã reload booster.yml");
                    break;
                case "rewards":
                    EnchantMaterial.getInstance().loadLevelRewardsConfig();
                    sender.sendMessage("§a✅ Đã reload level-rewards.yml");
                    break;
                case "rebirth":
                    EnchantMaterial.getInstance().loadRebirthConfig();
                    sender.sendMessage("§a✅ Đã reload rebirth.yml");
                    break;
                case "luckyblock":
                    EnchantMaterial.getInstance().loadLuckyBlockConfig();
                    sender.sendMessage("§a✅ Đã reload luckyblock.yml");
                    break;
                case "levelsystem":
                    EnchantMaterial.getInstance().loadLevelSystemConfig();
                    sender.sendMessage("§a✅ Đã reload level-system.yml");
                    break;
                default:
                    sender.sendMessage("§c❌ Config type không hợp lệ! Sử dụng: all, config, booster, rewards, rebirth, luckyblock, levelsystem");
                    break;
            }
        
        } catch (Exception e) {
            sender.sendMessage("§c❌ Lỗi khi reload: " + e.getMessage());
            EnchantMaterial.getInstance().getLogger().severe("Reload error: " + e.getMessage());
            e.printStackTrace();
        }
        return true;
    }

    private boolean handleAdminCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("enchantmaterial.admin")) {
            sender.sendMessage("§c✗ Bạn không có quyền sử dụng lệnh này!");
            return true;
        }
    
        if (args.length < 2) {
            sendAdminUsage(sender);
            return true;
        }
    
        String adminAction = args[1].toLowerCase();
    
        if (adminAction.equals("allow")) {
            return handleAllowCommand(sender, args);
        }
    
        sendAdminUsage(sender);
        return true;
    }

    private boolean handleAllowCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§c✗ Chỉ người chơi mới có thể sử dụng lệnh này!");
            return true;
        }
    
        Player player = (Player) sender;
    
        if (args.length < 3) {
            sendAllowUsage(sender);
            return true;
        }
    
        String allowAction = args[2].toLowerCase();
    
        switch (allowAction) {
            case "pos1":
                // SỬA: Lấy block mà người chơi đang nhìn
                Block targetBlock = player.getTargetBlock(null, 10);
                if (targetBlock != null && targetBlock.getType() != Material.AIR) {
                    EnchantMaterial.getInstance().getRegionManager().setPos1(player, targetBlock.getLocation());
                } else {
                    // Fallback: sử dụng vị trí block dưới chân người chơi
                    Location blockLoc = player.getLocation().getBlock().getLocation();
                    EnchantMaterial.getInstance().getRegionManager().setPos1(player, blockLoc);
                }
                return true;
            
            case "pos2":
                // SỬA: Lấy block mà người chơi đang nhìn
                Block targetBlock2 = player.getTargetBlock(null, 10);
                if (targetBlock2 != null && targetBlock2.getType() != Material.AIR) {
                    EnchantMaterial.getInstance().getRegionManager().setPos2(player, targetBlock2.getLocation());
                } else {
                    // Fallback: sử dụng vị trí block dưới chân người chơi
                    Location blockLoc = player.getLocation().getBlock().getLocation();
                    EnchantMaterial.getInstance().getRegionManager().setPos2(player, blockLoc);
                }
                return true;
            
            case "create":
                if (args.length < 4) {
                    player.sendMessage("§c✗ Sử dụng: /em admin allow create <tên>");
                    return true;
                }
                String regionName = args[3];
                EnchantMaterial.getInstance().getRegionManager().createRegion(player, regionName);
                return true;
            
            case "delete":
                if (args.length < 4) {
                    player.sendMessage("§c✗ Sử dụng: /em admin allow delete <tên>");
                    return true;
                }
                String deleteRegionName = args[3];
                if (EnchantMaterial.getInstance().getRegionManager().deleteRegion(deleteRegionName)) {
                    player.sendMessage("§a✅ Đã xóa region '" + deleteRegionName + "' thành công!");
                } else {
                    player.sendMessage("§c✗ Region '" + deleteRegionName + "' không tồn tại!");
                }
                return true;
            
            case "list":
                var regions = EnchantMaterial.getInstance().getRegionManager().getAllRegions();
                if (regions.isEmpty()) {
                    player.sendMessage("§e⚠ Không có region nào được tạo!");
                } else {
                    player.sendMessage("§6=== Danh sách Regions ===");
                    for (String name : regions.keySet()) {
                        player.sendMessage("§e- " + name);
                    }
                }
                return true;
            
            case "test":
                 if (args.length < 4) {
                    player.sendMessage("§c✗ Sử dụng: /em admin allow test <tên>");
                    return true;
                }
                String testRegionName = args[3];
                EnchantMaterial.getInstance().getRegionManager().testRegion(player, testRegionName);
                return true;
            
            default:
                sendAllowUsage(sender);
                return true;
        }
    }

    private void sendAdminUsage(CommandSender sender) {
        sender.sendMessage("§6=== Admin Commands ===");
        sender.sendMessage("§e/em admin allow §7- Quản lý khu vực cho phép đào");
    }

    private void sendAllowUsage(CommandSender sender) {
        sender.sendMessage("§6=== Allow Region Commands ===");
        sender.sendMessage("§e/em admin allow pos1 §7- Đặt vị trí 1");
        sender.sendMessage("§e/em admin allow pos2 §7- Đặt vị trí 2");
        sender.sendMessage("§e/em admin allow create <tên> §7- Tạo region");
        sender.sendMessage("§e/em admin allow delete <tên> §7- Xóa region");
        sender.sendMessage("§e/em admin allow list §7- Xem danh sách regions");
        sender.sendMessage("§e/em admin allow debug §7- Debug vị trí hiện tại");
    }

    // Cập nhật sendUsageMessage method
    private void sendUsageMessage(CommandSender sender) {
        sender.sendMessage("§6=== EnchantMaterial Commands ===");
        sender.sendMessage("§e/enchantmaterial level §7- Xem thông tin cấp độ");
        sender.sendMessage("§e/enchantmaterial permbooster [player] §7- Xem permission booster");
        sender.sendMessage("§e/enchantmaterial rebirth §7- Mở GUI chuyển sinh");

        if (sender.hasPermission("enchantmaterial.admin")) {
            sender.sendMessage("§e/enchantmaterial add <enchant> <level> §7- Thêm enchant vào tool");
            sender.sendMessage("§e/enchantmaterial reload §7- Tải lại config");
            sender.sendMessage("§e/enchantmaterial booster §7- Quản lý booster");
            sender.sendMessage("§e/enchantmaterial give <player> <type> <amount> §7- Cấp level/points cho người chơi");
            sender.sendMessage("§e/enchantmaterial admin §7- Lệnh admin");
            sender.sendMessage("§e/enchantmaterial rebirth edit §7- Chỉnh sửa cấu hình chuyển sinh");
        }

        sender.sendMessage("§e/enchantmaterial rewards §7- Mở GUI phần thưởng level");
    }
}
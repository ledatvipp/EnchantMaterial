package org.ledat.enchantMaterial;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.ledat.enchantMaterial.booster.BoosterManager;
import org.bukkit.scheduler.BukkitRunnable;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class BlockBreakListener implements Listener {

    private final Map<Player, Long> lastMessageTime = new HashMap<>();
    private final LuckyChanceBlockListener luckyChanceBlockListener;
    private final FortuneManager fortuneManager;

    // Cache nhẹ theo config
    private final Set<String> materialWhiteListCache = new HashSet<>();
    private final Set<String> disabledWorldsCache = new HashSet<>();
    private final Set<String> luckyBlockTypesCache = new HashSet<>();
    private final Map<String, BlockData> blockDataCache = new HashMap<>();
    private long lastConfigCacheUpdate = 0;
    private static final long CONFIG_CACHE_DURATION = 60_000; // 1 phút

    // Ánh xạ ngược: Material -> các display strings yêu cầu (từ enchantments.*)
    private final Map<Material, Set<String>> requiredDisplaysByMaterial = new EnumMap<>(Material.class);
    private long lastEnchantCacheUpdate = 0;

    // Bossbar (giữ nguyên logic cũ)
    private final Map<Player, BossBar> playerBossBars = new ConcurrentHashMap<>();
    private final Map<Player, BukkitRunnable> bossBarTasks = new ConcurrentHashMap<>();

    // Throttle + buffer cho ActionBar/điểm
    private final Map<UUID, Long> lastActionBarTime = new ConcurrentHashMap<>();
    private final Map<UUID, Double> pointsBuffer = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastBufferTime = new ConcurrentHashMap<>();

    public BlockBreakListener(FortuneManager fortuneManager, LuckyChanceBlockListener luckyChanceBlockListener) {
        this.fortuneManager = fortuneManager;
        this.luckyChanceBlockListener = luckyChanceBlockListener;
        updateConfigCache();
        updateEnchantCache();
    }

    private void updateConfigCache() {
        long now = System.currentTimeMillis();
        if (now - lastConfigCacheUpdate < CONFIG_CACHE_DURATION) return;

        EnchantMaterial plugin = EnchantMaterial.getInstance();

        // material-whitelist
        materialWhiteListCache.clear();
        materialWhiteListCache.addAll(plugin.getConfig().getStringList("material-whitelist"));

        // disable-world
        disabledWorldsCache.clear();
        disabledWorldsCache.addAll(plugin.getConfig().getStringList("disable-world"));

        // lucky-block types
        luckyBlockTypesCache.clear();
        luckyBlockTypesCache.addAll(plugin.getConfig().getStringList("lucky-blocks.block-replace"));

        // block-whitelist (exp/score/chance)
        blockDataCache.clear();
        for (Map<?, ?> m : plugin.getConfig().getMapList("block-whitelist")) {
            String name = String.valueOf(m.get("type"));
            if (name == null) continue;
            BlockData data = new BlockData(
                    String.valueOf(m.get("exp")),
                    String.valueOf(m.get("score")),
                    Double.parseDouble(String.valueOf(m.get("chance")))
            );
            blockDataCache.put(name, data);
        }

        lastConfigCacheUpdate = now;
    }

    private void updateEnchantCache() {
        long now = System.currentTimeMillis();
        if (now - lastEnchantCacheUpdate < CONFIG_CACHE_DURATION) return;

        requiredDisplaysByMaterial.clear();
        EnchantMaterial plugin = EnchantMaterial.getInstance();

        if (plugin.getConfig().contains("enchantments")) {
            for (String encKey : plugin.getConfig().getConfigurationSection("enchantments").getKeys(false)) {
                List<String> mats = plugin.getConfig().getStringList("enchantments." + encKey + ".material");
                String display = plugin.getConfig().getString("enchantments." + encKey + ".display_type");
                if (display == null || display.isEmpty()) continue;
                for (String matName : mats) {
                    try {
                        Material m = Material.valueOf(matName);
                        requiredDisplaysByMaterial.computeIfAbsent(m, k -> new HashSet<>()).add(display);
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        }
        lastEnchantCacheUpdate = now;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {

        Player player = event.getPlayer();
        if (player.hasPermission("enchantmaterial.admin")) return; // bỏ qua admin

        // Region check
        if (!EnchantMaterial.getInstance().getRegionManager().isInAllowedRegion(event.getBlock().getLocation())) return;

        // Tải cache nếu quá hạn
        long now = System.currentTimeMillis();
        if (now - lastConfigCacheUpdate > CONFIG_CACHE_DURATION) {
            updateConfigCache();
            updateEnchantCache();
        }

        Block block = event.getBlock();
        Material blockType = block.getType();
        String blockTypeName = blockType.name();
        ItemStack tool = player.getInventory().getItemInMainHand();

        // World disable
        if (disabledWorldsCache.contains(player.getWorld().getName())) return;

        // Whitelist vật liệu
        if (!materialWhiteListCache.contains(blockTypeName)) {
            event.setCancelled(true);
            return;
        }

        // Tay không (nếu không cho phép)
        if (tool.getType() == Material.AIR) {
            boolean allowEmpty = EnchantMaterial.getInstance().getConfig()
                    .getBoolean("settings.empty_hand_break", false);
            if (!allowEmpty) {
                if (canSendMessage(player)) player.sendMessage(ChatColor.RED + "Bạn không thể đập block bằng tay không!");
                event.setCancelled(true);
                return;
            }
        }

        // Kiểm tra lore/enchant cần có cho material này (nếu được cấu hình)
        Set<String> requires = requiredDisplaysByMaterial.get(blockType);
        if (requires != null && !requires.isEmpty()) {
            if (!hasAnyDisplay(tool, requires)) {
                if (canSendMessage(player)) player.sendMessage(ChatColor.RED + "Khối này yêu cầu bổ trợ phù hợp.");
                event.setCancelled(true);
                return;
            }
        } else {
            // fallback nhẹ: vẫn giữ logic "Bổ trợ:" nếu server bạn đang sử dụng
            if (!hasEnchantMaterial(tool)) {
                if (canSendMessage(player)) player.sendMessage(ChatColor.RED + "Dụng cụ của bạn không có bổ trợ phù hợp.");
                event.setCancelled(true);
                return;
            }
        }

        // Lucky Block
        if (EnchantMaterial.getInstance().getConfig().getBoolean("lucky-blocks.enabled")
                && luckyBlockTypesCache.contains(blockTypeName)) {
            double chance = luckyChanceBlockListener.getPlayerLuckyChance(player);
            if (ThreadLocalRandom.current().nextDouble() < chance) {
                event.setCancelled(true);
                block.setType(Material.LIME_GLAZED_TERRACOTTA);
                luckyChanceBlockListener.handleLuckyChance(player, block);
                return;
            }
        }

        // EXP – CHUYỂN THẲNG, KHÔNG SPAWN ORB
        BlockData blockData = blockDataCache.get(blockTypeName);
        if (blockData != null && blockData.expStr != null) {
            int exp = calculateExp(blockData.expStr);
            // Chặn MỌI exp-orb từ block này
            event.setExpToDrop(0);

            if (exp > 0) {
                double expMul = EnchantMaterial.getInstance().getBoosterManager().getExpMultiplier(player);
                int finalExp = Math.max(0, (int) Math.round(exp * expMul));
                // Cộng XP trực tiếp cho người chơi (vào thanh XP, không rơi orb)
                player.giveExp(finalExp);
            }
        }

        // Drop – hủy drop tự nhiên và tự xử lý
        boolean cancelNatural = EnchantMaterial.getInstance().getConfig()
                .getBoolean("performance.drops.cancel_natural_drops", true);
        if (cancelNatural) {
            event.setDropItems(false);
        }
        handleDrops(player, tool, block);

        // Điểm – non-blocking, gộp + throttle UI
        addPoints(player, blockType, blockData);
    }

    // --- Helpers ---

    private int calculateExp(String expStr) {
        try {
            if (expStr.contains("-")) {
                String[] parts = expStr.split("-");
                int min = Integer.parseInt(parts[0]);
                int max = Integer.parseInt(parts[1]);
                return ThreadLocalRandom.current().nextInt(min, max + 1);
            } else {
                return Integer.parseInt(expStr);
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** GỘP DROP: addItem 1 lần, drop dư 1 lần */
    private void handleDrops(Player player, ItemStack tool, Block block) {
        BoosterManager boosterManager = EnchantMaterial.getInstance().getBoosterManager();
        double fortuneMul = fortuneManager.getMultiplier(player);
        double boosterMul = boosterManager.getDropMultiplier(player);
        int bonusLvl = DropBonusUtils.getBonusLevel(tool);
        double extraChance = EnchantMaterial.getInstance().getConfig()
                .getDouble("performance.drops.bonus_chance", 0.8);

        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        for (ItemStack base : block.getDrops(tool)) {
            if (base == null || base.getType() == Material.AIR) continue;

            int baseAmount = base.getAmount();
            // Nhân tổng hợp cho số lượng item
            double expected = baseAmount * (fortuneMul * boosterMul);
            int total = (int) Math.floor(expected);
            double frac = expected - total;
            if (rnd.nextDouble() < frac) total += 1;

            // Bonus theo level: mỗi level có cơ hội thêm baseAmount
            for (int i = 0; i < Math.max(0, bonusLvl - 1); i++) {
                if (rnd.nextDouble() < extraChance) total += baseAmount;
            }

            if (total <= 0) continue;

            ItemStack stack = base.clone();
            stack.setAmount(total);

            // Thêm vào túi 1 lần
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);

            // Rơi phần dư (nếu có)
            if (!leftover.isEmpty()) {
                Location dropLoc = block.getLocation().add(0.5, 0.5, 0.5);
                World w = block.getWorld();
                for (ItemStack rem : leftover.values()) {
                    if (rem != null && rem.getType() != Material.AIR) {
                        w.dropItemNaturally(dropLoc, rem);
                    }
                }
            }
        }
    }

    private boolean canSendMessage(Player player) {
        long now = System.currentTimeMillis();
        long last = lastMessageTime.getOrDefault(player, 0L);
        if (now - last > 5000) { // 5s
            lastMessageTime.put(player, now);
            return true;
        }
        return false;
    }

    /** Fallback “Bổ trợ:” nếu không cấu hình enchant-display cho material */
    private boolean hasEnchantMaterial(ItemStack tool) {
        if (tool == null || !tool.hasItemMeta()) return false;
        ItemMeta meta = tool.getItemMeta();
        if (meta != null && meta.hasLore()) {
            for (String line : meta.getLore()) {
                if (line != null && ChatColor.stripColor(line).contains("Bổ trợ:")) return true;
            }
        }
        return false;
    }

    /** Kiểm tra lore có chứa bất kỳ display string nào yêu cầu cho material này */
    private boolean hasAnyDisplay(ItemStack item, Set<String> displays) {
        if (item == null || !item.hasItemMeta() || displays == null || displays.isEmpty()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) return false;
        List<String> lore = meta.getLore();
        if (lore == null || lore.isEmpty()) return false;

        for (String line : lore) {
            if (line == null) continue;
            for (String d : displays) {
                if (d != null && !d.isEmpty() && line.contains(d)) return true;
            }
        }
        return false;
    }

    /** Cộng điểm non-blocking + buffer/throttle UI */
    private void addPoints(Player player, Material blockType, BlockData blockData) {
        if (blockData == null || blockData.scoreStr == null) return;

        String[] range = blockData.scoreStr.split("-");
        if (range.length != 2) return;

        try {
            double min = Double.parseDouble(range[0]);
            double max = Double.parseDouble(range[1]);

            if (ThreadLocalRandom.current().nextDouble() < blockData.chance) {
                double base = ThreadLocalRandom.current().nextDouble(min, max);

                EnchantMaterial plugin = EnchantMaterial.getInstance();
                BoosterManager bm = plugin.getBoosterManager();

                // multiplier = fortune * boosterPoints
                double mul = fortuneManager.getMultiplier(player) * bm.getPointsMultiplier(player);

                // PvP giảm điểm nếu đang bảo vệ
                double pvpMul = 1.0D;
                if (plugin.isPvpReductionEnabled(player) && plugin.isPvpProtected(player)) {
                    pvpMul = plugin.getPvpMultiplier(); // vd 0.5
                }

                double total = base * mul * pvpMul;
                double rounded = new BigDecimal(total).setScale(2, RoundingMode.HALF_UP).doubleValue();

                // NON-BLOCKING: chỉ cộng delta vào cache + pending
                DatabaseManager.addPointsAsync(player.getUniqueId(), rounded);

                // UI: buffer + throttle ActionBar
                bufferAndNotify(player, rounded, blockType.name());

                // Kiểm tra lên cấp (không block): dùng cache nếu có
                PlayerData pd = DatabaseManager.getCached(player.getUniqueId());
                if (pd != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> checkLevelUp(player, pd.getPoints()));
                }
            }
        } catch (NumberFormatException ignored) {
        }
    }

    /** Gộp điểm trong ~0.3s rồi gửi 1 actionbar; throttle theo ticks */
    private void bufferAndNotify(Player player, double delta, String blockName) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        pointsBuffer.merge(id, delta, Double::sum);

        double bufferSeconds = EnchantMaterial.getInstance().getConfig()
                .getDouble("performance.notification_buffer_seconds", 0.3D);
        long bufferMillis = (long) (bufferSeconds * 1000);

        // Chưa tới thời gian gộp
        long last = lastBufferTime.getOrDefault(id, 0L);
        if (now - last < bufferMillis) return;

        // Xả buffer
        lastBufferTime.put(id, now);
        double totalAdded = pointsBuffer.getOrDefault(id, 0.0);
        pointsBuffer.put(id, 0.0);

        // Throttle theo ticks (tính theo ms)
        int intervalTicks = EnchantMaterial.getInstance().getConfig()
                .getInt("performance.actionbar_interval_ticks", 8);
        long tickMs = intervalTicks * 50L;

        long lastSend = lastActionBarTime.getOrDefault(id, 0L);
        if (now - lastSend < tickMs) return;
        lastActionBarTime.put(id, now);

        // Chỉ xử lý khi mode actionbar (các mode khác giữ nguyên sendNotification cũ)
        String mode = EnchantMaterial.getInstance().getConfig().getString("notification.mode", "actionbar").toLowerCase(Locale.ROOT);
        if (!"actionbar".equals(mode)) {
            // fallback: dùng sendNotification cũ cho mode chat/title/bossbar
            sendNotification(player, totalAdded, blockName);
            return;
        }

        String fmt = EnchantMaterial.getInstance().getConfig()
                .getString("notification.actionbar", "§a+%score% điểm §7(%block%)")
                .replace("%score%", String.format(Locale.US, "%.2f", totalAdded))
                .replace("%block%", blockName);

        // KHÔNG dùng reflection — dùng Spigot API
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(fmt));
    }

    private void sendNotification(Player player, double score, String blockName) {
        EnchantMaterial plugin = EnchantMaterial.getInstance();
        String mode = plugin.getConfig().getString("notification.mode", "actionbar").toLowerCase(Locale.ROOT);
        String scoreStr = String.format(Locale.US, "%.2f", score);

        // Xác định trạng thái PvP-protect (đang ở khu an toàn và có bật giảm)
        boolean pvpActive = plugin.isPvpReductionEnabled(player) && plugin.isPvpProtected(player);
        double pvpMul = plugin.getPvpMultiplier(); // ví dụ 0.5
        String pvpMulStr = String.format(Locale.US, "%.2f", pvpMul);

        switch (mode) {
            case "chat":
                player.sendMessage("§aBạn nhận được §f" + scoreStr + " điểm §7(" + blockName + ")");
                break;

            case "title": {
                String title = plugin.getConfig().getString("notification.title.text", "Bạn đã nhận được %score% điểm!")
                        .replace("%score%", scoreStr);
                String subtitle = plugin.getConfig().getString("notification.title.subtitle", "Từ khối %block%")
                        .replace("%block%", blockName);
                player.sendTitle(title, subtitle, 10, 70, 20);
                break;
            }

            case "bossbar": {
                String bossbar = plugin.getConfig().getString("notification.bossbar", "Điểm nhận: %score% từ %block%")
                        .replace("%score%", scoreStr).replace("%block%", blockName);
                showBossBar(player, bossbar);
                break;
            }

            case "actionbar":
            default: {
                // Nếu đang PvP-protect -> dùng template khác
                String key = pvpActive ? "notification.actionbar_pvp" : "notification.actionbar";
                String def = pvpActive
                        ? "§a+%score% điểm §7(%block%) §6(-%pvp_mul%x khu an toàn)"
                        : "§a+%score% điểm §7(%block%)";
                String msg = plugin.getConfig().getString(key, def)
                        .replace("%score%", scoreStr)
                        .replace("%block%", blockName)
                        .replace("%pvp_mul%", pvpMulStr);

                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(msg));
                break;
            }
        }
    }

    private void showBossBar(Player player, String message) {
        BossBar bossBar = playerBossBars.get(player);
        if (bossBar == null) {
            bossBar = Bukkit.createBossBar(message, BarColor.GREEN, BarStyle.SOLID);
            bossBar.addPlayer(player);
            bossBar.setProgress(1.0);
            playerBossBars.put(player, bossBar);
        } else {
            bossBar.setTitle(message);
            bossBar.setProgress(1.0);
        }

        final BossBar finalBossBar = bossBar;

        BukkitRunnable previousTask = bossBarTasks.get(player);
        if (previousTask != null) previousTask.cancel();

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (finalBossBar != null && player.isOnline()) {
                    finalBossBar.removePlayer(player);
                }
                playerBossBars.remove(player);
                bossBarTasks.remove(player);
            }
        };
        task.runTaskLater(EnchantMaterial.getInstance(), 60L);
        bossBarTasks.put(player, task);
    }

    // === Level/Points helpers giữ nguyên, chỉ bỏ block sync nếu có thể ===

    private void setPoints(Player player, double points) {
        try {
            // Ưu tiên cache nếu có
            PlayerData pd = DatabaseManager.getCached(player.getUniqueId());
            if (pd == null) {
                pd = DatabaseManager.getPlayerDataAsync(player.getUniqueId()).get();
            }
            pd.setPoints(points);
            DatabaseManager.savePlayerDataAsync(pd);
        } catch (Exception e) {
            e.printStackTrace();
            player.sendMessage("Có lỗi khi lưu điểm của bạn. Vui lòng thử lại sau.");
        }
    }

    private int getCurrentLevel(Player player) {
        try {
            PlayerData pd = DatabaseManager.getCached(player.getUniqueId());
            if (pd != null) return pd.getLevel();
            return DatabaseManager.getPlayerDataAsync(player.getUniqueId()).get().getLevel();
        } catch (Exception e) {
            return 0;
        }
    }

    private void setPlayerLevel(Player player, int newLevel) {
        try {
            PlayerData pd = DatabaseManager.getCached(player.getUniqueId());
            if (pd == null) pd = DatabaseManager.getPlayerDataAsync(player.getUniqueId()).get();
            pd.setLevel(newLevel);
            DatabaseManager.savePlayerDataAsync(pd);
        } catch (Exception ignored) {}
    }

    private void checkLevelUp(Player player, double points) {
        List<Double> req = EnchantMaterial.getInstance().getConfig().getDoubleList("level-request");
        int cur = getCurrentLevel(player);

        for (int i = cur; i < req.size(); i++) {
            if (points >= req.get(i)) {
                levelUp(player, i + 1);
            } else break;
        }
    }

    private void levelUp(Player player, int newLevel) {
        int cur = getCurrentLevel(player);
        if (newLevel == cur) return;

        setPlayerLevel(player, newLevel);

        String title = ChatColor.translateAlternateColorCodes('&',
                EnchantMaterial.getInstance().getConfig().getString("level-up-title.title")
                        .replace("%next_level%", String.valueOf(newLevel)));
        String subtitle = ChatColor.translateAlternateColorCodes('&',
                EnchantMaterial.getInstance().getConfig().getString("level-up-title.subtitle")
                        .replace("%next_level%", String.valueOf(newLevel)));

        player.sendTitle(title, subtitle, 10, 70, 20);
        setPoints(player, 0);
    }

    // Inner class để cache block data
    private static class BlockData {
        final String expStr;
        final String scoreStr;
        final double chance;
        BlockData(String expStr, String scoreStr, double chance) {
            this.expStr = expStr;
            this.scoreStr = scoreStr;
            this.chance = chance;
        }
    }
}

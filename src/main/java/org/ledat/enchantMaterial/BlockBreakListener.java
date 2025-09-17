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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class BlockBreakListener implements Listener {

    private final EnchantMaterial plugin;
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

    private volatile boolean allowEmptyHandBreak = false;
    private volatile boolean cancelNaturalDrops = true;
    private volatile boolean luckyBlocksEnabled = false;
    private volatile double extraDropChance = 0.8D;
    private volatile double notificationBufferSeconds = 0.3D;
    private volatile int actionBarIntervalTicks = 8;
    private volatile String notificationMode = "actionbar";
    private volatile String actionBarTemplate = "§a+%score% điểm §7(%block%)";
    private volatile String actionBarPvpTemplate = "§a+%score% điểm §7(%block%) §6(-%pvp_mul%x khu an toàn)";

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
        this.plugin = EnchantMaterial.getInstance();
        this.fortuneManager = fortuneManager;
        this.luckyChanceBlockListener = luckyChanceBlockListener;
        updateConfigCache();
        updateEnchantCache();
    }

    private void updateConfigCache() {
        long now = System.currentTimeMillis();
        if (now - lastConfigCacheUpdate < CONFIG_CACHE_DURATION) return;

        // material-whitelist
        materialWhiteListCache.clear();
        materialWhiteListCache.addAll(plugin.getConfig().getStringList("material-whitelist"));

        // disable-world
        disabledWorldsCache.clear();
        disabledWorldsCache.addAll(plugin.getConfig().getStringList("disable-world"));

        // lucky-block types
        luckyBlockTypesCache.clear();
        luckyBlockTypesCache.addAll(plugin.getConfig().getStringList("lucky-blocks.block-replace"));

        allowEmptyHandBreak = plugin.getConfig().getBoolean("settings.empty_hand_break", false);
        cancelNaturalDrops = plugin.getConfig().getBoolean("performance.drops.cancel_natural_drops", true);
        luckyBlocksEnabled = plugin.getConfig().getBoolean("lucky-blocks.enabled");
        extraDropChance = Math.max(0D, Math.min(1D,
                plugin.getConfig().getDouble("performance.drops.bonus_chance", 0.8D)));
        notificationBufferSeconds = plugin.getConfig().getDouble("performance.notification_buffer_seconds", 0.3D);
        actionBarIntervalTicks = plugin.getConfig().getInt("performance.actionbar_interval_ticks", 8);
        notificationMode = plugin.getConfig()
                .getString("notification.mode", "actionbar")
                .toLowerCase(Locale.ROOT);
        actionBarTemplate = plugin.getConfig().getString(
                "notification.actionbar",
                "§a+%score% điểm §7(%block%)");
        actionBarPvpTemplate = plugin.getConfig().getString(
                "notification.actionbar_pvp",
                "§a+%score% điểm §7(%block%) §6(-%pvp_mul%x khu an toàn)");

        // block-whitelist (exp/score/chance)
        blockDataCache.clear();
        for (Map<?, ?> m : plugin.getConfig().getMapList("block-whitelist")) {
            Object typeObj = m.get("type");
            if (typeObj == null) continue;
            String name = String.valueOf(typeObj);
            if (name.isEmpty()) continue;
            BlockData data = BlockData.fromConfig(m.get("exp"), m.get("score"), m.get("chance"));
            blockDataCache.put(name, data);
        }

        lastConfigCacheUpdate = now;
    }

    private void updateEnchantCache() {
        long now = System.currentTimeMillis();
        if (now - lastEnchantCacheUpdate < CONFIG_CACHE_DURATION) return;

        requiredDisplaysByMaterial.clear();

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
        if (!plugin.getRegionManager().isInAllowedRegion(event.getBlock().getLocation())) return;

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
        if (tool.getType() == Material.AIR && !allowEmptyHandBreak) {
            if (canSendMessage(player)) player.sendMessage(ChatColor.RED + "Bạn không thể đập block bằng tay không!");
            event.setCancelled(true);
            return;
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

        ThreadLocalRandom random = ThreadLocalRandom.current();

        // Lucky Block
        if (luckyBlocksEnabled && luckyBlockTypesCache.contains(blockTypeName)) {
            double chance = luckyChanceBlockListener.getPlayerLuckyChance(player);
            if (random.nextDouble() < chance) {
                event.setCancelled(true);
                block.setType(Material.LIME_GLAZED_TERRACOTTA);
                luckyChanceBlockListener.handleLuckyChance(player, block);
                return;
            }
        }

        // EXP – CHUYỂN THẲNG, KHÔNG SPAWN ORB
        BlockData blockData = blockDataCache.get(blockTypeName);
        BoosterManager boosterManager = plugin.getBoosterManager();
        double fortuneMultiplier = fortuneManager.getMultiplier(player);
        double dropMultiplier = boosterManager.getDropMultiplier(player);
        double pointsMultiplier = boosterManager.getPointsMultiplier(player);
        double expMultiplier = boosterManager.getExpMultiplier(player);

        if (blockData != null && blockData.hasExp()) {
            int exp = blockData.rollExp(random);
            // Chặn MỌI exp-orb từ block này
            event.setExpToDrop(0);

            if (exp > 0) {
                int finalExp = Math.max(0, (int) Math.round(exp * expMultiplier));
                if (finalExp > 0) {
                    // Cộng XP trực tiếp cho người chơi (vào thanh XP, không rơi orb)
                    player.giveExp(finalExp);
                }
            }
        }

        // Drop – hủy drop tự nhiên và tự xử lý
        if (cancelNaturalDrops) {
            event.setDropItems(false);
        }
        handleDrops(player, tool, block, fortuneMultiplier, dropMultiplier, random);

        // Điểm – non-blocking, gộp + throttle UI
        addPoints(player, blockType, blockData, fortuneMultiplier, pointsMultiplier, random);
    }

    // --- Helpers ---
    /** GỘP DROP: addItem 1 lần, drop dư 1 lần */
    private void handleDrops(Player player, ItemStack tool, Block block,
                             double fortuneMul, double boosterMul, ThreadLocalRandom rnd) {
        int bonusLvl = DropBonusUtils.getBonusLevel(tool);
        double extraChance = this.extraDropChance;

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
    private void addPoints(Player player, Material blockType, BlockData blockData,
                           double fortuneMul, double boosterPointsMul, ThreadLocalRandom rnd) {
        if (blockData == null || !blockData.hasScore()) return;

        if (!blockData.shouldReward(rnd)) return;

        double base = blockData.rollScore(rnd);

        double mul = fortuneMul * boosterPointsMul;

        // PvP giảm điểm nếu đang bảo vệ
        double pvpMul = 1.0D;
        if (plugin.isPvpReductionEnabled(player) && plugin.isPvpProtected(player)) {
            pvpMul = plugin.getPvpMultiplier(); // vd 0.5
        }

        double total = base * mul * pvpMul;
        double rounded = Math.round(total * 100.0D) / 100.0D;
        if (rounded <= 0) {
            return;
        }

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

    /** Gộp điểm trong ~0.3s rồi gửi 1 actionbar; throttle theo ticks */
    private void bufferAndNotify(Player player, double delta, String blockName) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        pointsBuffer.merge(id, delta, Double::sum);

        long bufferMillis = (long) (Math.max(0D, notificationBufferSeconds) * 1000.0D);

        // Chưa tới thời gian gộp
        long last = lastBufferTime.getOrDefault(id, 0L);
        if (now - last < bufferMillis) return;

        // Xả buffer
        lastBufferTime.put(id, now);
        double totalAdded = pointsBuffer.getOrDefault(id, 0.0);
        pointsBuffer.put(id, 0.0);
        if (totalAdded <= 0) {
            return;
        }

        // Throttle theo ticks (tính theo ms)
        long tickMs = Math.max(0, actionBarIntervalTicks) * 50L;

        long lastSend = lastActionBarTime.getOrDefault(id, 0L);
        if (now - lastSend < tickMs) return;
        lastActionBarTime.put(id, now);

        // Chỉ xử lý khi mode actionbar (các mode khác giữ nguyên sendNotification cũ)
        if (!"actionbar".equals(notificationMode)) {
            // fallback: dùng sendNotification cũ cho mode chat/title/bossbar
            sendNotification(player, totalAdded, blockName);
            return;
        }

        String template = actionBarTemplate != null && !actionBarTemplate.isEmpty()
                ? actionBarTemplate
                : "§a+%score% điểm §7(%block%)";
        String fmt = template
                .replace("%score%", String.format(Locale.US, "%.2f", totalAdded))
                .replace("%block%", blockName);

        // KHÔNG dùng reflection — dùng Spigot API
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(fmt));
    }

    private void sendNotification(Player player, double score, String blockName) {
        String mode = notificationMode != null ? notificationMode : "actionbar";
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
                String template = pvpActive ? actionBarPvpTemplate : actionBarTemplate;
                if (template == null || template.isEmpty()) {
                    template = pvpActive
                            ? "§a+%score% điểm §7(%block%) §6(-%pvp_mul%x khu an toàn)"
                            : "§a+%score% điểm §7(%block%)";
                }
                String msg = template
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
        task.runTaskLater(plugin, 60L);
        bossBarTasks.put(player, task);
    }

    // === Level/Points helpers giữ nguyên, chỉ bỏ block sync nếu có thể ===

    private void setPoints(Player player, double points) {
        UUID uuid = player.getUniqueId();
        PlayerData cached = DatabaseManager.getCached(uuid);
        if (cached != null) {
            cached.setPoints(points);
            DatabaseManager.savePlayerDataAsync(cached);
            return;
        }

        DatabaseManager.getPlayerDataCachedOrAsync(uuid, data -> {
            if (data == null) {
                return;
            }
            data.setPoints(points);
            DatabaseManager.savePlayerDataAsync(data);
        });
        plugin.getLogger().finer("Points cache miss trong BlockBreak cho " + player.getName());
    }

    private int getCurrentLevel(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerData pd = DatabaseManager.getCached(uuid);
        if (pd != null) {
            return pd.getLevel();
        }
        DatabaseManager.getPlayerDataCachedOrAsync(uuid, null);
        plugin.getLogger().finer("Level cache miss trong BlockBreak cho " + player.getName());
        return 1;
    }

    private void setPlayerLevel(Player player, int newLevel) {
        UUID uuid = player.getUniqueId();
        PlayerData pd = DatabaseManager.getCached(uuid);
        if (pd != null) {
            pd.setLevel(newLevel);
            DatabaseManager.savePlayerDataAsync(pd);
            return;
        }

        DatabaseManager.getPlayerDataCachedOrAsync(uuid, data -> {
            if (data == null) {
                return;
            }
            data.setLevel(newLevel);
            DatabaseManager.savePlayerDataAsync(data);
        });
        plugin.getLogger().finer("Level cache miss (set) trong BlockBreak cho " + player.getName());
    }

    private void checkLevelUp(Player player, double points) {
        List<Double> req = plugin.getConfig().getDoubleList("level-request");
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
                plugin.getConfig().getString("level-up-title.title")
                        .replace("%next_level%", String.valueOf(newLevel)));
        String subtitle = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("level-up-title.subtitle")
                        .replace("%next_level%", String.valueOf(newLevel)));

        player.sendTitle(title, subtitle, 10, 70, 20);
        setPoints(player, 0);
    }

    // Inner class để cache block data
    private static class BlockData {
        private final boolean hasExp;
        private final int expMin;
        private final int expMax;
        private final boolean hasScore;
        private final double scoreMin;
        private final double scoreMax;
        private final double chance;

        private BlockData(boolean hasExp, int expMin, int expMax,
                          boolean hasScore, double scoreMin, double scoreMax,
                          double chance) {
            this.hasExp = hasExp;
            this.expMin = expMin;
            this.expMax = expMax;
            this.hasScore = hasScore;
            this.scoreMin = scoreMin;
            this.scoreMax = scoreMax;
            this.chance = chance;
        }

        static BlockData fromConfig(Object expObj, Object scoreObj, Object chanceObj) {
            RangeInt expRange = parseIntRange(expObj);
            RangeDouble scoreRange = parseDoubleRange(scoreObj);
            double chance = parseChance(chanceObj);

            boolean hasExp = expRange != null;
            boolean hasScore = scoreRange != null;

            int expMin = hasExp ? expRange.min : 0;
            int expMax = hasExp ? expRange.max : 0;
            double scoreMin = hasScore ? scoreRange.min : 0D;
            double scoreMax = hasScore ? scoreRange.max : 0D;

            return new BlockData(hasExp, expMin, expMax, hasScore, scoreMin, scoreMax, chance);
        }

        boolean hasExp() {
            return hasExp;
        }

        boolean hasScore() {
            return hasScore;
        }

        int rollExp(ThreadLocalRandom rnd) {
            if (!hasExp) return 0;
            if (expMax <= expMin) return expMin;
            return rnd.nextInt(expMin, expMax + 1);
        }

        double rollScore(ThreadLocalRandom rnd) {
            if (!hasScore) return 0D;
            if (scoreMax <= scoreMin) return scoreMin;
            return rnd.nextDouble(scoreMin, scoreMax);
        }

        boolean shouldReward(ThreadLocalRandom rnd) {
            if (!hasScore) return false;
            if (chance <= 0D) return false;
            if (chance >= 1D) return true;
            return rnd.nextDouble() < chance;
        }

        private static RangeInt parseIntRange(Object value) {
            if (value == null) return null;
            if (value instanceof Number) {
                int v = ((Number) value).intValue();
                return new RangeInt(v, v);
            }
            String str = value.toString();
            if (str == null) return null;
            str = str.trim();
            if (str.isEmpty() || "null".equalsIgnoreCase(str)) {
                return null;
            }
            try {
                if (str.contains("-")) {
                    String[] parts = str.split("-");
                    if (parts.length >= 2) {
                        int min = Integer.parseInt(parts[0].trim());
                        int max = Integer.parseInt(parts[1].trim());
                        if (max < min) {
                            int tmp = min;
                            min = max;
                            max = tmp;
                        }
                        return new RangeInt(min, max);
                    }
                }
                int single = Integer.parseInt(str);
                return new RangeInt(single, single);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        private static RangeDouble parseDoubleRange(Object value) {
            if (value == null) return null;
            if (value instanceof Number) {
                double v = ((Number) value).doubleValue();
                return new RangeDouble(v, v);
            }
            String str = value.toString();
            if (str == null) return null;
            str = str.trim();
            if (str.isEmpty() || "null".equalsIgnoreCase(str)) {
                return null;
            }
            try {
                if (str.contains("-")) {
                    String[] parts = str.split("-");
                    if (parts.length >= 2) {
                        double min = Double.parseDouble(parts[0].trim());
                        double max = Double.parseDouble(parts[1].trim());
                        if (max < min) {
                            double tmp = min;
                            min = max;
                            max = tmp;
                        }
                        return new RangeDouble(min, max);
                    }
                }
                double single = Double.parseDouble(str);
                return new RangeDouble(single, single);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        private static double parseChance(Object value) {
            double defaultChance = 1.0D;
            if (value == null) return defaultChance;
            if (value instanceof Number) {
                return clampChance(((Number) value).doubleValue());
            }
            try {
                return clampChance(Double.parseDouble(value.toString().trim()));
            } catch (Exception ignored) {
                return defaultChance;
            }
        }

        private static double clampChance(double value) {
            if (Double.isNaN(value)) return 0D;
            if (value < 0D) return 0D;
            if (value > 1D) return 1D;
            return value;
        }

        private static final class RangeInt {
            final int min;
            final int max;
            RangeInt(int min, int max) {
                this.min = min;
                this.max = max;
            }
        }

        private static final class RangeDouble {
            final double min;
            final double max;
            RangeDouble(double min, double max) {
                this.min = min;
                this.max = max;
            }
        }
    }
}

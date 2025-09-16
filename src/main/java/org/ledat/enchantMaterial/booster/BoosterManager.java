package org.ledat.enchantMaterial.booster;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.ledat.enchantMaterial.EnchantMaterial;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class BoosterManager {

    // Thread-safe collections
    private final Map<UUID, List<Booster>> activeBoosters = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> playerBars = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastBossBarUpdate = new ConcurrentHashMap<>();
    
    private final EnchantMaterial plugin;
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private BukkitTask updateTask;
    private BukkitTask saveTask;
    
    // Cache để tránh tính toán lại multiplier
    private final Map<UUID, Double> pointsMultiplierCache = new ConcurrentHashMap<>();
    private final Map<UUID, Double> dropMultiplierCache = new ConcurrentHashMap<>();
    private final Map<UUID, Double> expMultiplierCache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cacheTimestamp = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION = 5000; // 5 giây
    
    public BoosterManager(EnchantMaterial plugin) {
        this.plugin = plugin;
        startTasks();
        loadBoostersFromStorage();
    }
    
    private void startTasks() {
        // Task update mỗi 5 giây thay vì mỗi giây
        updateTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!isShuttingDown.get()) {
                    tick();
                }
            }
        }.runTaskTimer(plugin, 0L, 200L); // 10 giây
        
        // Task auto-save mỗi 30 giây
        saveTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!isShuttingDown.get()) {
                    saveBoostersAsync();
                }
            }
        }.runTaskTimerAsynchronously(plugin, 600L, 600L); // 30 giây
    }
    
    private void loadBoostersFromStorage() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Sử dụng instance có sẵn thay vì tạo mới
                BoosterStorage storage = EnchantMaterial.getInstance().getBoosterStorage();
                Map<UUID, List<Booster>> loaded = storage.loadBoosters();
                
                Bukkit.getScheduler().runTask(plugin, () -> {
                    activeBoosters.putAll(loaded);
                    invalidateAllCaches();
                    plugin.getLogger().info("Đã load " + loaded.size() + " booster từ database");
                });
                
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Lỗi load boosters từ storage", e);
            }
        });
    }
    
    // Sửa tất cả các chỗ tạo BoosterStorage mới
    public void saveBooster(UUID playerUUID, Booster booster) {
        // Sử dụng instance có sẵn
        BoosterStorage storage = EnchantMaterial.getInstance().getBoosterStorage();
        storage.saveBooster(playerUUID, booster);
    }
    
    public CompletableFuture<List<Booster>> getPlayerBoostersAsync(UUID playerUUID) {
        // Sử dụng instance có sẵn
        BoosterStorage storage = EnchantMaterial.getInstance().getBoosterStorage();
        return storage.getPlayerBoostersAsync(playerUUID);
    }
    
    /**
     * Thêm booster với validation và error handling
     */
    public boolean addBooster(Player player, Booster booster) {
        if (player == null || booster == null) {
            plugin.getLogger().warning("Null player hoặc booster trong addBooster");
            return false;
        }
        
        if (isShuttingDown.get()) {
            plugin.getLogger().warning("Không thể thêm booster khi plugin đang tắt");
            return false;
        }
        
        UUID uuid = player.getUniqueId();
        
        synchronized (activeBoosters) {
            activeBoosters.putIfAbsent(uuid, new ArrayList<>());
            List<Booster> list = activeBoosters.get(uuid);
            
            // Kiểm tra trùng loại
            for (Booster b : list) {
                if (b.getType() == booster.getType()) {
                    plugin.getLogger().info("Player " + player.getName() + " đã có booster loại " + booster.getType());
                    return false;
                }
            }
            
            // Kiểm tra giới hạn
            int maxBoosters = plugin.getBoosterConfig().getInt("settings.max-per-player", 3);
            if (list.size() >= maxBoosters) {
                plugin.getLogger().info("Player " + player.getName() + " đã đạt giới hạn booster (" + maxBoosters + ")");
                return false;
            }
            
            list.add(booster);
            invalidatePlayerCache(uuid);
            
            plugin.getLogger().info("Đã thêm booster " + booster.getType() + " x" + booster.getMultiplier() + " cho " + player.getName());
            
            // Update BossBar ngay lập tức
            updateBossbar(player);
            
            return true;
        }
    }
    
    /**
     * Lấy multiplier với caching
     */
    public double getPointsMultiplier(Player player) {
        if (player == null) return 1.0;
        return getCachedMultiplier(player.getUniqueId(), BoosterType.POINTS, pointsMultiplierCache);
    }
    
    public double getDropMultiplier(Player player) {
        if (player == null) return 1.0;
        return getCachedMultiplier(player.getUniqueId(), BoosterType.DROP, dropMultiplierCache);
    }
    
    public double getExpMultiplier(Player player) {
        if (player == null) return 1.0;
        return getCachedMultiplier(player.getUniqueId(), BoosterType.EXP, expMultiplierCache);
    }
    
    /**
     * Lấy permission-based multiplier cho từng loại booster
     */
    public double getPermissionMultiplier(Player player, BoosterType type) {
        if (player == null) return 1.0;
        
        // Lấy prefix từ config
        String permissionPrefix = plugin.getConfig().getString(
            "permission-booster.permission-prefix", 
            "enchantmaterial.booster"
        ) + "." + type.name().toLowerCase() + ".";
        
        double maxMultiplier = 1.0;
        
        // Lấy giới hạn kiểm tra từ config
        String configPath = "permission-booster.max-check-levels." + type.name().toLowerCase();
        int maxCheck = plugin.getConfig().getInt(configPath, getDefaultMaxCheck(type));
        
        for (int i = 1; i <= maxCheck; i++) {
            String permission = permissionPrefix + i;
            if (player.hasPermission(permission)) {
                maxMultiplier = Math.max(maxMultiplier, i);
            }
        }
        
        return maxMultiplier;
    }
    
    /**
     * Lấy giá trị mặc định nếu config không có
     */
    private int getDefaultMaxCheck(BoosterType type) {
        switch (type) {
            case POINTS:
            case EXP:
                return 10;
            case DROP:
                return 5;
            default:
                return 10;
        }
    }
    
    /**
     * ✅ CẢ TIẾN: Tính toán multiplier tổng hợp với logic rõ ràng hơn
     */
    private double getCachedMultiplier(UUID uuid, BoosterType type, Map<UUID, Double> cache) {
        Long timestamp = cacheTimestamp.get(uuid);
        if (timestamp != null && System.currentTimeMillis() - timestamp < CACHE_DURATION) {
            Double cached = cache.get(uuid);
            if (cached != null) {
                return cached;
            }
        }
        
        Player player = Bukkit.getPlayer(uuid);
        
        // 1. Personal Booster Multiplier (nhân với nhau)
        double personalMultiplier = activeBoosters.getOrDefault(uuid, Collections.emptyList()).stream()
                .filter(b -> b.getType() == type && !b.isExpired())
                .mapToDouble(Booster::getMultiplier)
                .reduce(1.0, (a, b) -> a * b);
        
        // 2. Global Booster Multiplier (nếu có)
        double globalMultiplier = getGlobalBoosterMultiplier(type);
        
        // 3. Permission Booster Multiplier
        double permissionMultiplier = getPermissionMultiplier(player, type);
        
        // 4. Tính toán tổng hợp theo công thức: (Personal × Global) + (Permission - 1)
        // Ví dụ: Personal 2x, Global 1.5x, Permission 3x = (2 × 1.5) + (3 - 1) = 3 + 2 = 5x
        double combinedBoosterMultiplier = personalMultiplier * globalMultiplier;
        double totalMultiplier = combinedBoosterMultiplier + (permissionMultiplier - 1.0);
        
        // Đảm bảo không vượt quá giới hạn
        double maxAllowed = type.getMaxMultiplier() * 2; // Cho phép vượt giới hạn cơ bản khi kết hợp
        totalMultiplier = Math.min(totalMultiplier, maxAllowed);
        
        // Cache kết quả
        cache.put(uuid, totalMultiplier);
        cacheTimestamp.put(uuid, System.currentTimeMillis());
        
        return totalMultiplier;
    }
    
    /**
     * ✅ THÊM: Lấy global booster multiplier
     */
    private double getGlobalBoosterMultiplier(BoosterType type) {
        // Kiểm tra global boosters từ config hoặc database
        String configPath = "global-boosters." + type.name().toLowerCase();
        if (plugin.getConfig().contains(configPath + ".active") && 
            plugin.getConfig().getBoolean(configPath + ".active", false)) {
            
            long endTime = plugin.getConfig().getLong(configPath + ".end-time", 0);
            if (System.currentTimeMillis() < endTime) {
                return plugin.getConfig().getDouble(configPath + ".multiplier", 1.0);
            }
        }
        return 1.0;
    }
    
    /**
     * ✅ CẢI TIẾN: Thông tin booster chi tiết hơn
     */
    public String getDetailedBoosterInfo(Player player) {
        if (player == null) return "§cKhông có thông tin";
        
        StringBuilder info = new StringBuilder();
        info.append("§6§l=== THÔNG TIN BOOSTER ===\n");
        
        UUID uuid = player.getUniqueId();
        
        for (BoosterType type : BoosterType.values()) {
            info.append("\n§e").append(type.getIcon()).append(" ").append(type.getVietnameseName()).append(":\n");
            
            // Personal Boosters
            List<Booster> personalBoosters = activeBoosters.getOrDefault(uuid, Collections.emptyList())
                    .stream().filter(b -> b.getType() == type && !b.isExpired()).toList();
            
            if (!personalBoosters.isEmpty()) {
                info.append("  §a✓ Personal: ");
                for (Booster b : personalBoosters) {
                    info.append("§fx").append(String.format("%.1f", b.getMultiplier()))
                        .append(" §7(").append(b.formatTimeLeft()).append(") ");
                }
                info.append("\n");
            } else {
                info.append("  §7○ Personal: §fx1.0\n");
            }
            
            // Global Booster
            double globalMultiplier = getGlobalBoosterMultiplier(type);
            if (globalMultiplier > 1.0) {
                info.append("  §b✓ Global: §fx").append(String.format("%.1f", globalMultiplier)).append("\n");
            } else {
                info.append("  §7○ Global: §fx1.0\n");
            }
            
            // Permission Booster
            double permissionMultiplier = getPermissionMultiplier(player, type);
            if (permissionMultiplier > 1.0) {
                info.append("  §d✓ Permission: §fx").append(String.format("%.1f", permissionMultiplier)).append("\n");
            } else {
                info.append("  §7○ Permission: §fx1.0\n");
            }
            
            // Tổng cộng
            double total = getCachedMultiplier(uuid, type, 
                type == BoosterType.POINTS ? pointsMultiplierCache :
                type == BoosterType.DROP ? dropMultiplierCache : expMultiplierCache);
            
            info.append("  §6§l➤ Tổng cộng: §f§lx").append(String.format("%.1f", total)).append("\n");
        }
        
        return info.toString();
    }
    
    /**
     * Thêm method để lấy thông tin permission booster của player
     */
    public String getPermissionBoosterInfo(Player player) {
        if (player == null) return "§cKhông có thông tin";
        
        StringBuilder info = new StringBuilder();
        info.append("§6=== Permission Booster ===").append("\n");
        
        for (BoosterType type : BoosterType.values()) {
            double multiplier = getPermissionMultiplier(player, type);
            String status = multiplier > 1.0 ? "§a✓" : "§c✗";
            info.append(status).append(" ")
                .append(type.getIcon()).append(" ")
                .append(type.getVietnameseName()).append(": ")
                .append("§fx").append(String.format("%.1f", multiplier))
                .append("\n");
        }
        
        return info.toString();
    }
    
    /**
     * Method để admin kiểm tra permission booster của player khác
     */
    public String getPermissionBoosterInfo(String playerName) {
        Player player = Bukkit.getPlayer(playerName);
        if (player == null) {
            return "§cKhông tìm thấy player: " + playerName;
        }
        return getPermissionBoosterInfo(player);
    }
    
    /**
     * Tick được tối ưu hóa
     */
    public void tick() {
        try {
            long currentTime = System.currentTimeMillis();
            Iterator<Map.Entry<UUID, List<Booster>>> iterator = activeBoosters.entrySet().iterator();
            
            while (iterator.hasNext()) {
                Map.Entry<UUID, List<Booster>> entry = iterator.next();
                UUID uuid = entry.getKey();
                List<Booster> boosters = entry.getValue();
                
                // Remove expired boosters
                boolean hadExpired = boosters.removeIf(Booster::isExpired);
                
                if (boosters.isEmpty()) {
                    // Cleanup player data
                    cleanupPlayer(uuid);
                    iterator.remove();
                    continue;
                }
                
                // Invalidate cache nếu có booster hết hạn
                if (hadExpired) {
                    invalidatePlayerCache(uuid);
                }
                
                // Update BossBar (chỉ khi cần thiết)
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    Long lastUpdate = lastBossBarUpdate.get(uuid);
                    if (lastUpdate == null || currentTime - lastUpdate > 1000) { // 1 giây
                        updateBossbar(player);
                        lastBossBarUpdate.put(uuid, currentTime);
                    }
                }
            }
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Lỗi trong BoosterManager.tick()", e);
        }
    }
    
    /**
     * Update BossBar được tối ưu hóa
     */
    public void updateBossbar(Player player) {
        if (player == null || !player.isOnline()) return;
        
        UUID uuid = player.getUniqueId();
        List<Booster> list = activeBoosters.getOrDefault(uuid, new ArrayList<>());
        
        // Remove expired boosters
        list.removeIf(Booster::isExpired);
        
        // Không còn booster → ẩn bossbar
        if (list.isEmpty()) {
            cleanupPlayer(uuid);
            return;
        }
        
        try {
            // Tạo title
            String title = createBossBarTitle(list);
            BarColor color = determineBossBarColor(list);
            BarStyle style = determineBossBarStyle(list.size());
            
            // Get hoặc tạo BossBar
            BossBar bar = playerBars.computeIfAbsent(uuid, id -> {
                BossBar newBar = Bukkit.createBossBar("", color, style);
                newBar.addPlayer(player);
                return newBar;
            });
            
            // Update properties
            bar.setColor(color);
            bar.setStyle(style);
            bar.setTitle(ChatColor.translateAlternateColorCodes('&', title));
            
            // Progress dựa trên thời gian còn lại của booster sắp hết hạn nhất
            double progress = calculateBossBarProgress(list);
            bar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Lỗi update BossBar cho " + player.getName(), e);
        }
    }
    
    private String createBossBarTitle(List<Booster> boosters) {
        if (boosters.size() == 1) {
            Booster b = boosters.get(0);
            return plugin.getConfig().getString("bossbar.single.title", "&aBooster: &f%type% x%multiplier% &7(%time%)")
                    .replace("%type%", b.getType().name())
                    .replace("%multiplier%", String.format("%.1f", b.getMultiplier()))
                    .replace("%time%", b.formatTimeLeft());
        } else {
            Booster b1 = boosters.get(0);
            Booster b2 = boosters.get(1);
            return plugin.getConfig().getString("bossbar.multiple.title", "&b%type1% x%multiplier1% &7(%time1%) &8| &a%type2% x%multiplier2% &7(%time2%)")
                    .replace("%type1%", b1.getType().name())
                    .replace("%multiplier1%", String.format("%.1f", b1.getMultiplier()))
                    .replace("%time1%", b1.formatTimeLeft())
                    .replace("%type2%", b2.getType().name())
                    .replace("%multiplier2%", String.format("%.1f", b2.getMultiplier()))
                    .replace("%time2%", b2.formatTimeLeft());
        }
    }
    
    private BarColor determineBossBarColor(List<Booster> boosters) {
        // Đỏ nếu có booster nào còn < 30 giây
        boolean hasUrgent = boosters.stream().anyMatch(b -> b.getTimeLeftSeconds() <= 30);
        if (hasUrgent) return BarColor.RED;
        
        // Vàng nếu có booster nào còn < 5 phút
        boolean hasWarning = boosters.stream().anyMatch(b -> b.getTimeLeftSeconds() <= 300);
        if (hasWarning) return BarColor.YELLOW;
        
        return boosters.size() == 1 ? BarColor.GREEN : BarColor.BLUE;
    }
    
    private BarStyle determineBossBarStyle(int boosterCount) {
        return boosterCount == 1 ? BarStyle.SEGMENTED_10 : BarStyle.SEGMENTED_20;
    }
    
    private double calculateBossBarProgress(List<Booster> boosters) {
        if (boosters.isEmpty()) return 0.0;
        
        // Tìm booster có thời gian còn lại ít nhất
        Booster shortest = boosters.stream()
                .min(Comparator.comparing(Booster::getRemainingMillis))
                .orElse(boosters.get(0));
        
        long remaining = shortest.getRemainingMillis();
        long total = plugin.getBoosterConfig().getLong("settings.default-duration", 3600) * 1000; // 1 giờ default
        
        return Math.min(1.0, (double) remaining / total);
    }
    
    private void cleanupPlayer(UUID uuid) {
        BossBar bar = playerBars.remove(uuid);
        if (bar != null) {
            bar.removeAll();
        }
        lastBossBarUpdate.remove(uuid);
        invalidatePlayerCache(uuid);
    }
    
    private void invalidatePlayerCache(UUID uuid) {
        pointsMultiplierCache.remove(uuid);
        dropMultiplierCache.remove(uuid);
        expMultiplierCache.remove(uuid);
        cacheTimestamp.remove(uuid);
    }
    
    private void invalidateAllCaches() {
        pointsMultiplierCache.clear();
        dropMultiplierCache.clear();
        expMultiplierCache.clear();
        cacheTimestamp.clear();
    }
    
    /**
     * Save boosters async
     */
    private void saveBoostersAsync() {
        if (isShuttingDown.get()) return;
        
        Map<UUID, List<Booster>> toSave = getAllBoosters();
        if (toSave.isEmpty()) return;
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                BoosterStorage storage = new BoosterStorage(plugin);
                storage.saveBoosters(toSave);
                storage.close();
                
              //  plugin.getLogger().info("✅ Đã save " + toSave.size() + " booster vào database");
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "❌ Lỗi save boosters", e);
            }
        });
    }
    
    public Map<UUID, List<Booster>> getAllBoosters() {
        Map<UUID, List<Booster>> filtered = new HashMap<>();
        for (Map.Entry<UUID, List<Booster>> entry : activeBoosters.entrySet()) {
            List<Booster> valid = entry.getValue().stream()
                    .filter(b -> !b.isExpired())
                    .toList();
            if (!valid.isEmpty()) {
                filtered.put(entry.getKey(), new ArrayList<>(valid));
            }
        }
        return filtered;
    }
    
    public List<Booster> getBoosters(UUID uuid) {
        if (uuid == null) return new ArrayList<>();
        
        List<Booster> list = activeBoosters.getOrDefault(uuid, new ArrayList<>());
        list.removeIf(Booster::isExpired);
        return new ArrayList<>(list);
    }
    
    public boolean removeBooster(UUID uuid, BoosterType type) {
        if (uuid == null || type == null) return false;
        
        synchronized (activeBoosters) {
            List<Booster> list = activeBoosters.get(uuid);
            if (list == null || list.isEmpty()) return false;
            
            boolean removed = list.removeIf(b -> b.getType() == type);
            
            if (removed) {
                invalidatePlayerCache(uuid);
                
                if (list.isEmpty()) {
                    cleanupPlayer(uuid);
                    activeBoosters.remove(uuid);
                } else {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null && player.isOnline()) {
                        updateBossbar(player);
                    }
                }
                
             //   plugin.getLogger().info("✅ Đã xóa booster " + type + " của player " + uuid);
            }
            
            return removed;
        }
    }
    
    /**
     * Cleanup khi player logout
     */
    public void onPlayerQuit(UUID uuid) {
        lastBossBarUpdate.remove(uuid);
        // Không xóa activeBoosters vì booster vẫn chạy khi offline
    }
    
    /**
     * Shutdown manager
     */
    public void shutdown() {
        isShuttingDown.set(true);
        
        // Cancel tasks
        if (updateTask != null) {
            updateTask.cancel();
        }
        if (saveTask != null) {
            saveTask.cancel();
        }
        
        // Save boosters synchronously
        try {
            BoosterStorage storage = new BoosterStorage(plugin);
            storage.saveBoosters(getAllBoosters());
            storage.close();
            plugin.getLogger().info("✅ Đã save boosters trước khi tắt plugin");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "❌ Lỗi save boosters khi shutdown", e);
        }
        
        // Cleanup BossBars
        for (BossBar bar : playerBars.values()) {
            bar.removeAll();
        }
        playerBars.clear();
        
        // Clear caches
        invalidateAllCaches();
        activeBoosters.clear();
        
        plugin.getLogger().info("✅ BoosterManager đã shutdown hoàn tất");
    }
}

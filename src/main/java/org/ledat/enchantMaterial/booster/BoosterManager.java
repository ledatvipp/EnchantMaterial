package org.ledat.enchantMaterial.booster;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.*;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.FileConfiguration;
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
    private final Map<UUID, Map<BoosterType, BoosterRequest>> boosterMetadata = new ConcurrentHashMap<>();
    
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

    public BoosterActivationResult processBoosterRequest(Player player, BoosterRequest request) {
        if (request == null) {
            BoosterRequest fallback = BoosterRequest.builder(BoosterType.POINTS)
                    .multiplier(1.0)
                    .durationSeconds(1)
                    .saveToStorage(false)
                    .build();
            return BoosterActivationResult.failure(fallback, BoosterFailureReason.INVALID_REQUEST, "Yêu cầu booster không hợp lệ.");
        }

        if (player == null) {
            return BoosterActivationResult.failure(request, BoosterFailureReason.PLAYER_OFFLINE, "Không tìm thấy người chơi.");
        }

        if (isShuttingDown.get()) {
            return BoosterActivationResult.failure(request, BoosterFailureReason.PLUGIN_SHUTTING_DOWN,
                    "Plugin đang tắt, không thể xử lý booster mới.");
        }

        BoosterType type = request.getType();
        if (type == null) {
            return BoosterActivationResult.failure(request, BoosterFailureReason.INVALID_REQUEST, "Loại booster không hợp lệ.");
        }

        double targetMultiplier = request.hasCustomBooster()
                ? request.getCustomBooster().getMultiplier()
                : request.getMultiplier();

        if (!request.hasCustomBooster() && !request.isBypassValidation() && !type.isValidMultiplier(targetMultiplier)) {
            return BoosterActivationResult.failure(request, BoosterFailureReason.INVALID_MULTIPLIER,
                    "Multiplier vượt quá giới hạn cho booster " + type.name().toLowerCase());
        }

        if (targetMultiplier <= 0) {
            return BoosterActivationResult.failure(request, BoosterFailureReason.INVALID_MULTIPLIER,
                    "Multiplier phải lớn hơn 0.");
        }

        if (!request.hasCustomBooster() && request.getDurationSeconds() <= 0) {
            return BoosterActivationResult.failure(request, BoosterFailureReason.INVALID_DURATION,
                    "Thời gian booster phải lớn hơn 0.");
        }

        UUID uuid = player.getUniqueId();
        Booster boosterToApply = null;
        Booster previous = null;
        BoosterActivationResult result;

        synchronized (activeBoosters) {
            activeBoosters.putIfAbsent(uuid, new ArrayList<>());
            List<Booster> boosters = activeBoosters.get(uuid);
            boosters.removeIf(Booster::isExpired);

            BoosterStackingStrategy strategy = resolveStackingStrategy(request);
            Optional<Booster> existingOpt = boosters.stream()
                    .filter(b -> b.getType() == type)
                    .findFirst();

            if (existingOpt.isPresent()) {
                previous = existingOpt.get();

                switch (strategy) {
                    case REJECT_DUPLICATES:
                        return BoosterActivationResult.failure(request, BoosterFailureReason.DUPLICATE_TYPE,
                                "Người chơi đã có booster loại này.");
                    case EXTEND_DURATION: {
                        long additionalSeconds = request.getDurationSeconds();
                        if (additionalSeconds <= 0) {
                            return BoosterActivationResult.failure(request, BoosterFailureReason.INVALID_DURATION,
                                    "Thời gian cộng thêm phải lớn hơn 0.");
                        }
                        boosterToApply = previous.extendDuration(additionalSeconds);
                        boosters.remove(previous);
                        boosters.add(boosterToApply);
                        sortByExpiry(boosters);
                        result = BoosterActivationResult.extended(request, boosterToApply, previous, additionalSeconds,
                                "Đã cộng thêm " + additionalSeconds + " giây cho booster " + type.name().toLowerCase());
                        break;
                    }
                    case REPLACE_IF_STRONGER: {
                        if (!request.isBypassValidation() && targetMultiplier <= previous.getMultiplier()) {
                            return BoosterActivationResult.failure(request, BoosterFailureReason.WEAKER_THAN_CURRENT,
                                    "Booster hiện tại mạnh hơn (x" + previous.getMultiplier() + ")");
                        }
                        boosterToApply = request.hasCustomBooster()
                                ? request.getCustomBooster()
                                : new Booster(type, targetMultiplier, request.getDurationSeconds());
                        boosters.remove(previous);
                        boosters.add(boosterToApply);
                        sortByExpiry(boosters);
                        result = BoosterActivationResult.replaced(request, boosterToApply, previous,
                                "Đã thay thế booster cũ x" + previous.getMultiplier() + " bằng booster mới x" + boosterToApply.getMultiplier());
                        break;
                    }
                    case SMART:
                    default: {
                        if (targetMultiplier > previous.getMultiplier()) {
                            boosterToApply = request.hasCustomBooster()
                                    ? request.getCustomBooster()
                                    : new Booster(type, targetMultiplier, request.getDurationSeconds());
                            boosters.remove(previous);
                            boosters.add(boosterToApply);
                            sortByExpiry(boosters);
                            result = BoosterActivationResult.replaced(request, boosterToApply, previous,
                                    "Đã nâng cấp booster lên x" + boosterToApply.getMultiplier());
                        } else if (Math.abs(targetMultiplier - previous.getMultiplier()) < 0.0001D) {
                            long additionalSeconds = request.getDurationSeconds();
                            if (additionalSeconds <= 0) {
                                return BoosterActivationResult.failure(request, BoosterFailureReason.INVALID_DURATION,
                                        "Thời gian cộng thêm phải lớn hơn 0.");
                            }
                            boosterToApply = previous.extendDuration(additionalSeconds);
                            boosters.remove(previous);
                            boosters.add(boosterToApply);
                            sortByExpiry(boosters);
                            result = BoosterActivationResult.extended(request, boosterToApply, previous, additionalSeconds,
                                    "Đã cộng thêm " + additionalSeconds + " giây cho booster " + type.name().toLowerCase());
                        } else {
                            return BoosterActivationResult.failure(request, BoosterFailureReason.WEAKER_THAN_CURRENT,
                                    "Booster mới yếu hơn booster hiện tại x" + previous.getMultiplier());
                        }
                        break;
                    }
                }
            } else {
                int maxBoosters = request.isBypassLimit() ? Integer.MAX_VALUE : plugin.getBoosterConfig().getInt("settings.max-per-player", 3);
                if (boosters.size() >= maxBoosters) {
                    return BoosterActivationResult.failure(request, BoosterFailureReason.LIMIT_REACHED,
                            "Người chơi đã đạt giới hạn " + maxBoosters + " booster đang hoạt động.");
                }

                boosterToApply = request.hasCustomBooster()
                        ? request.getCustomBooster()
                        : new Booster(type, targetMultiplier, request.getDurationSeconds());
                boosters.add(boosterToApply);
                sortByExpiry(boosters);
                result = BoosterActivationResult.created(request, boosterToApply,
                        "Đã kích hoạt booster " + type.name().toLowerCase() + " x" + boosterToApply.getMultiplier());
            }
        }

        if (!result.isSuccess()) {
            return result;
        }

        BoosterRequest snapshot = request.withCustomBooster(boosterToApply);
        storeMetadata(uuid, snapshot);
        invalidatePlayerCache(uuid);

        if (request.isSaveToStorage()) {
            persistPlayerStateAsync(uuid);
        }

        Player online = Bukkit.getPlayer(uuid);
        if (online != null && online.isOnline()) {
            updateBossbar(online);
            lastBossBarUpdate.put(uuid, System.currentTimeMillis());
        }

        if (result.getMessage() != null && !result.getMessage().isEmpty()) {
            plugin.getLogger().info("[Booster] " + player.getName() + ": " + result.getMessage());
        }

        return result;
    }

    private BoosterStackingStrategy resolveStackingStrategy(BoosterRequest request) {
        if (request.getStackingStrategy() != null) {
            return request.getStackingStrategy();
        }
        String configured = plugin.getBoosterConfig().getString("settings.default-stack-strategy", "SMART");
        return BoosterStackingStrategy.fromString(configured);
    }

    private void sortByExpiry(List<Booster> boosters) {
        boosters.sort(Comparator.comparingLong(Booster::getEndTime));
    }

    private void storeMetadata(UUID uuid, BoosterRequest request) {
        boosterMetadata.computeIfAbsent(uuid, id -> new ConcurrentHashMap<>())
                .put(request.getType(), request);
    }

    public Optional<BoosterRequest> getBoosterMetadata(UUID uuid, BoosterType type) {
        Map<BoosterType, BoosterRequest> map = boosterMetadata.get(uuid);
        if (map == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(map.get(type));
    }

    private void removeMetadata(UUID uuid, BoosterType type) {
        Map<BoosterType, BoosterRequest> map = boosterMetadata.get(uuid);
        if (map == null) {
            return;
        }
        map.remove(type);
        if (map.isEmpty()) {
            boosterMetadata.remove(uuid);
        }
    }

    private void persistPlayerStateAsync(UUID uuid) {
        if (uuid == null || isShuttingDown.get()) {
            return;
        }

        List<Booster> snapshot;
        synchronized (activeBoosters) {
            List<Booster> list = activeBoosters.getOrDefault(uuid, Collections.emptyList());
            snapshot = new ArrayList<>();
            for (Booster booster : list) {
                if (!booster.isExpired()) {
                    snapshot.add(new Booster(booster.getType(), booster.getMultiplier(), booster.getStartTime(), booster.getEndTime()));
                }
            }
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Map<UUID, List<Booster>> data = new HashMap<>();
                data.put(uuid, snapshot);
                plugin.getBoosterStorage().saveBoosters(data);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "❌ Lỗi lưu boosters cho " + uuid, e);
            }
        });
    }

    private String describeBooster(Booster booster) {
        if (booster == null) {
            return "unknown";
        }
        return booster.getType().getIcon() + " " + booster.getType().name().toLowerCase() + " x"
                + String.format(Locale.US, "%.1f", booster.getMultiplier())
                + " (" + booster.formatTimeLeft(Booster.TimeFormat.COMPACT) + ")";
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
                    activeBoosters.clear();
                    boosterMetadata.clear();

                    loaded.forEach((uuid, boosters) -> {
                        List<Booster> sorted = new ArrayList<>(boosters);
                        sortByExpiry(sorted);
                        activeBoosters.put(uuid, sorted);

                        Map<BoosterType, BoosterRequest> metadataMap = boosterMetadata.computeIfAbsent(uuid, id -> new ConcurrentHashMap<>());
                        for (Booster booster : sorted) {
                            metadataMap.put(booster.getType(), BoosterRequest.fromExistingBooster(booster, BoosterSource.PERSISTED));
                        }
                    });

                    invalidateAllCaches();

                    Bukkit.getOnlinePlayers().forEach(player -> {
                        List<Booster> boosters = activeBoosters.get(player.getUniqueId());
                        if (boosters != null && !boosters.isEmpty()) {
                            updateBossbar(player);
                        }
                    });

                    plugin.getLogger().info("Đã load " + loaded.size() + " booster từ database");
                });
                
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Lỗi load boosters từ storage", e);
            }
        });
    }
    
    // Sửa tất cả các chỗ tạo BoosterStorage mới
    @Deprecated(forRemoval = false)
    public void saveBooster(UUID playerUUID, Booster booster) {
        if (playerUUID == null) {
            return;
        }
        persistPlayerStateAsync(playerUUID);
    }
    
    public CompletableFuture<List<Booster>> getPlayerBoostersAsync(UUID playerUUID) {
        // Sử dụng instance có sẵn
        BoosterStorage storage = EnchantMaterial.getInstance().getBoosterStorage();
        return storage.getPlayerBoostersAsync(playerUUID);
    }
    
    /**
     * Thêm booster theo cơ chế cũ (giữ lại vì tương thích).
     */
    @Deprecated(forRemoval = false)
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
        boolean inserted = false;

        synchronized (activeBoosters) {
            activeBoosters.putIfAbsent(uuid, new ArrayList<>());
            List<Booster> list = activeBoosters.get(uuid);
            list.removeIf(Booster::isExpired);

            Optional<Booster> sameType = list.stream()
                    .filter(b -> b.getType() == booster.getType())
                    .findFirst();

            if (sameType.isPresent()) {
                Booster existing = sameType.get();
                if (!existing.equals(booster)) {
                    plugin.getLogger().info("Player " + player.getName() + " đã có booster loại " + booster.getType());
                    return false;
                }
            } else {
                int maxBoosters = plugin.getBoosterConfig().getInt("settings.max-per-player", 3);
                if (list.size() >= maxBoosters) {
                    plugin.getLogger().info("Player " + player.getName() + " đã đạt giới hạn booster (" + maxBoosters + ")");
                    return false;
                }

                list.add(booster);
                sortByExpiry(list);
                inserted = true;
            }
        }

        if (inserted || getBoosterMetadata(uuid, booster.getType()).isEmpty()) {
            storeMetadata(uuid, BoosterRequest.fromExistingBooster(booster, BoosterSource.LEGACY));
        }
        invalidatePlayerCache(uuid);

        if (player.isOnline()) {
            updateBossbar(player);
        }

        if (inserted) {
            persistPlayerStateAsync(uuid);
            plugin.getLogger().info("Đã thêm booster " + booster.getType() + " x" + booster.getMultiplier() + " cho " + player.getName());
        }

        return true;
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
        List<Booster> boosters = getBoosters(uuid);

        for (BoosterType type : BoosterType.values()) {
            info.append("\n§e").append(type.getIcon()).append(" ").append(type.getVietnameseName()).append(":\n");

            List<Booster> personalBoosters = boosters.stream()
                    .filter(b -> b.getType() == type)
                    .toList();

            if (!personalBoosters.isEmpty()) {
                for (Booster booster : personalBoosters) {
                    info.append("  §8▪ §f").append(booster.getType().getIcon()).append(" x")
                            .append(String.format(Locale.US, "%.1f", booster.getMultiplier()))
                            .append(" §7Còn: §f").append(booster.formatTimeLeft())
                            .append(" §7| Tổng: §f").append(booster.getTotalDurationSeconds()).append("s\n");
                }
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

            getBoosterMetadata(uuid, type).ifPresent(metadata -> {
                if (metadata.getSource() != BoosterSource.UNKNOWN) {
                    info.append("  §7Nguồn: §f").append(metadata.getSource().getDisplayName()).append("\n");
                }
                if (metadata.getNote() != null && !metadata.getNote().isBlank()) {
                    info.append("  §7Ghi chú: §f").append(metadata.getNote()).append("\n");
                }
                info.append("  §7Chiến lược: §f").append(metadata.getStackingStrategy().getDisplayName()).append("\n");
            });
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
                
                List<BoosterType> expiredTypes = new ArrayList<>();
                for (Booster booster : boosters) {
                    if (booster.isExpired()) {
                        expiredTypes.add(booster.getType());
                    }
                }

                boolean hadExpired = !expiredTypes.isEmpty();
                if (hadExpired) {
                    boosters.removeIf(Booster::isExpired);
                }

                if (boosters.isEmpty()) {
                    cleanupPlayer(uuid);
                    iterator.remove();
                    continue;
                }

                sortByExpiry(boosters);

                if (hadExpired) {
                    expiredTypes.forEach(type -> removeMetadata(uuid, type));
                    invalidatePlayerCache(uuid);
                    persistPlayerStateAsync(uuid);
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

        if (!plugin.getBoosterConfig().getBoolean("bossbar.show-bossbar", true)) {
            cleanupPlayer(player.getUniqueId());
            return;
        }

        UUID uuid = player.getUniqueId();
        List<Booster> boosters;

        synchronized (activeBoosters) {
            List<Booster> list = activeBoosters.get(uuid);
            if (list == null) {
                boosters = Collections.emptyList();
            } else {
                list.removeIf(Booster::isExpired);
                if (list.isEmpty()) {
                    activeBoosters.remove(uuid);
                    boosters = Collections.emptyList();
                } else {
                    sortByExpiry(list);
                    boosters = new ArrayList<>(list);
                }
            }
        }

        if (boosters.isEmpty()) {
            cleanupPlayer(uuid);
            return;
        }

        try {
            String title = createBossBarTitle(boosters);
            BarColor color = determineBossBarColor(boosters);
            BarStyle style = determineBossBarStyle(boosters.size());

            BossBar bar = playerBars.computeIfAbsent(uuid, id -> {
                BossBar newBar = Bukkit.createBossBar("", color, style);
                newBar.addPlayer(player);
                return newBar;
            });

            bar.setColor(color);
            bar.setStyle(style);
            bar.setTitle(ChatColor.translateAlternateColorCodes('&', title));

            double progress = calculateBossBarProgress(boosters);
            bar.setProgress(Math.max(0.0, Math.min(1.0, progress)));

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Lỗi update BossBar cho " + player.getName(), e);
        }
    }
    
    private String createBossBarTitle(List<Booster> boosters) {
        BoosterConfigurationWrapper wrapper = new BoosterConfigurationWrapper(plugin.getBoosterConfig());

        if (boosters.size() == 1) {
            Booster booster = boosters.get(0);
            return wrapper.singleTitle()
                    .replace("%type%", booster.getType().getFormattedName())
                    .replace("%multiplier%", String.format(Locale.US, "%.1f", booster.getMultiplier()))
                    .replace("%time%", booster.formatTimeLeft());
        }

        Booster first = boosters.get(0);
        Booster second = boosters.size() > 1 ? boosters.get(1) : first;

        String base;
        if (boosters.size() == 2) {
            base = wrapper.doubleTitle();
        } else {
            base = wrapper.multiTitle();
        }

        base = base
                .replace("%type1%", first.getType().getFormattedName())
                .replace("%multiplier1%", String.format(Locale.US, "%.1f", first.getMultiplier()))
                .replace("%time1%", first.formatTimeLeft())
                .replace("%type2%", second.getType().getFormattedName())
                .replace("%multiplier2%", String.format(Locale.US, "%.1f", second.getMultiplier()))
                .replace("%time2%", second.formatTimeLeft());

        if (boosters.size() > 2) {
            int remaining = boosters.size() - 2;
            base = base.replace("%more%", wrapper.multiMoreFormat().replace("%count%", String.valueOf(remaining)));
        } else {
            base = base.replace("%more%", "");
        }

        return base;
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
        
        Booster shortest = boosters.stream()
                .min(Comparator.comparingLong(Booster::getRemainingMillis))
                .orElse(boosters.get(0));

        return shortest.getRemainingRatio();
    }
    
    private void cleanupPlayer(UUID uuid) {
        BossBar bar = playerBars.remove(uuid);
        if (bar != null) {
            bar.removeAll();
        }
        lastBossBarUpdate.remove(uuid);
        invalidatePlayerCache(uuid);
        boosterMetadata.remove(uuid);
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
                plugin.getBoosterStorage().saveBoosters(toSave);
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
        if (uuid == null) {
            return new ArrayList<>();
        }

        synchronized (activeBoosters) {
            List<Booster> list = activeBoosters.get(uuid);
            if (list == null) {
                return new ArrayList<>();
            }
            list.removeIf(Booster::isExpired);
            if (list.isEmpty()) {
                activeBoosters.remove(uuid);
                return new ArrayList<>();
            }
            sortByExpiry(list);
            return new ArrayList<>(list);
        }
    }
    
    public boolean removeBooster(UUID uuid, BoosterType type) {
        if (uuid == null || type == null) return false;
        
        synchronized (activeBoosters) {
            List<Booster> list = activeBoosters.get(uuid);
            if (list == null || list.isEmpty()) return false;
            
            boolean removed = list.removeIf(b -> b.getType() == type);

            if (removed) {
                removeMetadata(uuid, type);
                invalidatePlayerCache(uuid);
                persistPlayerStateAsync(uuid);

                if (list.isEmpty()) {
                    cleanupPlayer(uuid);
                    activeBoosters.remove(uuid);
                } else {
                    sortByExpiry(list);
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
            plugin.getBoosterStorage().saveBoosters(getAllBoosters());
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

    private static class BoosterConfigurationWrapper {
        private final FileConfiguration config;

        private BoosterConfigurationWrapper(FileConfiguration config) {
            this.config = config;
        }

        private String singleTitle() {
            return config.getString("bossbar.single.title", "&aBooster: &f%type% x%multiplier% &7(%time%)");
        }

        private String doubleTitle() {
            return config.getString("bossbar.double.title", "&b%type1% x%multiplier1% &7(%time1%) &8| &a%type2% x%multiplier2% &7(%time2%)");
        }

        private String multiTitle() {
            return config.getString("bossbar.multi.title", "&b%type1% x%multiplier1% &7(%time1%) &8| &a%type2% x%multiplier2% &7(%time2%)%more%");
        }

        private String multiMoreFormat() {
            return config.getString("bossbar.multi.more-format", " &7(+%count% booster khác)");
        }
    }
}

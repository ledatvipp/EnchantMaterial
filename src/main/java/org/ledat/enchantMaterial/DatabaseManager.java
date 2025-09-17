package org.ledat.enchantMaterial;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.ledat.enchantMaterial.rebirth.RebirthData;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class DatabaseManager {

    // ===== PHẦN 1: CONNECTION POOLING =====
    // Thay vì 1 connection, giờ có pool nhiều connections
    private static HikariDataSource dataSource;

    // ===== PHẦN 2: CACHING SYSTEM =====
    // Cache để giảm database calls
    private static final Map<UUID, PlayerData> playerDataCache = new ConcurrentHashMap<>();
    private static final Map<String, Set<Integer>> claimedRewardsCache = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION = 300000; // 5 phút
    private static final Map<UUID, Long> cacheTimestamps = new ConcurrentHashMap<>();
    private static final Map<UUID, RebirthData> rebirthDataCache = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> rebirthCacheTimestamps = new ConcurrentHashMap<>();

    // ===== PHẦN 3: BATCH PROCESSING =====
    // Gom nhiều operations thành 1 lần để giảm database load
    private static final Map<UUID, PlayerData> pendingUpdates = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService batchExecutor = Executors.newScheduledThreadPool(2);

    // ===== PHẦN 4: KHỞI TẠO DATABASE =====
    public static void initializeDatabase() {
        try {
            setupConnectionPool();
            createTables();
            startBatchProcessor();
            startCacheCleanup();
        } catch (Exception e) {
            EnchantMaterial.getInstance().getLogger().severe("Lỗi khởi tạo database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void setupConnectionPool() {
        // Tạo HikariCP connection pool - giúp quản lý nhiều connections hiệu quả
        HikariConfig config = new HikariConfig();

        // Tạo đường dẫn database
        File dataFolder = new File(EnchantMaterial.getInstance().getDataFolder(), "data");
        if (!dataFolder.exists()) dataFolder.mkdirs();

        String dbPath = dataFolder.getAbsolutePath().replace("\\", "/") + "/data";
        config.setJdbcUrl("jdbc:h2:file:" + dbPath + ";TRACE_LEVEL_FILE=0;DB_CLOSE_DELAY=-1");
        config.setDriverClassName("org.h2.Driver");
        config.setUsername("sa");
        config.setPassword("");

        // Cấu hình pool - QUAN TRỌNG để tránh lag
        config.setMaximumPoolSize(10);        // Tối đa 10 connections
        config.setMinimumIdle(2);             // Luôn có sẵn 2 connections
        config.setConnectionTimeout(30000);   // Timeout 30 giây
        config.setIdleTimeout(600000);        // Idle 10 phút thì đóng
        config.setMaxLifetime(1800000);       // Connection sống tối đa 30 phút
        config.setLeakDetectionThreshold(60000); // Phát hiện memory leak

        // Tối ưu hóa performance
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        dataSource = new HikariDataSource(config);
    }

    // ===== PHẦN 5: BATCH PROCESSOR =====
    // Gom nhiều updates thành 1 lần để giảm database load

    // Tối ưu batch processor
    private static void startBatchProcessor() {
        batchExecutor.scheduleAtFixedRate(() -> {
            if (!pendingUpdates.isEmpty()) {
                processBatchUpdates();
            }
        }, 5, 5, TimeUnit.SECONDS); // Chạy mỗi 5 giây thay vì 3 giây
    }

    // Thêm lock để đồng bộ hóa
    private static final Object BATCH_LOCK = new Object();

    private static void processBatchUpdates() {
        synchronized (BATCH_LOCK) {
            if (pendingUpdates.isEmpty()) return;

            // KHÔNG clear ngay, mà copy trước
            Map<UUID, PlayerData> toUpdate = new HashMap<>(pendingUpdates);

            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);

                String sql = "MERGE INTO player_data KEY(uuid) VALUES (?, ?, ?, CURRENT_TIMESTAMP)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {

                    for (PlayerData data : toUpdate.values()) {
                        statement.setString(1, data.getUuid().toString());
                        statement.setInt(2, data.getLevel());
                        statement.setDouble(3, data.getPoints());
                        statement.addBatch();
                    }

                    statement.executeBatch();
                    connection.commit();

                    // CHỈ clear sau khi save thành công
                    for (UUID uuid : toUpdate.keySet()) {
                        pendingUpdates.remove(uuid);
                    }

                    // Update cache
                    for (PlayerData data : toUpdate.values()) {
                        playerDataCache.put(data.getUuid(), data);
                        cacheTimestamps.put(data.getUuid(), System.currentTimeMillis());
                    }

                } catch (SQLException e) {
                    connection.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                EnchantMaterial.getInstance().getLogger().warning("Lỗi batch update: " + e.getMessage());
                // Không cần putAll vì chưa remove khỏi pendingUpdates
            }
        }
    }

    // ===== PHẦN 6: CACHE CLEANUP - SỬA LẠI =====
    private static void startCacheCleanup() {
        batchExecutor.scheduleAtFixedRate(() -> {
            synchronized (BATCH_LOCK) {
                long currentTime = System.currentTimeMillis();

                // Save pending updates trước
                if (!pendingUpdates.isEmpty()) {
                    processBatchUpdates();
                }

                // Cleanup cache an toàn hơn
                List<UUID> toRemove = new ArrayList<>();
                for (Map.Entry<UUID, Long> entry : cacheTimestamps.entrySet()) {
                    UUID uuid = entry.getKey();
                    if (currentTime - entry.getValue() > CACHE_DURATION &&
                            !pendingUpdates.containsKey(uuid)) {
                        toRemove.add(uuid);
                    }
                }

                for (UUID uuid : toRemove) {
                    playerDataCache.remove(uuid);
                    claimedRewardsCache.remove(uuid.toString());
                    cacheTimestamps.remove(uuid);
                    rebirthDataCache.remove(uuid);
                    rebirthCacheTimestamps.remove(uuid);
                }
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    // ===== THÊM PHƯƠNG THỨC SAVE NGAY LẬP TỨC CHO DỮ LIỆU QUAN TRỌNG =====
    public static void savePlayerDataImmediate(PlayerData playerData) {
        // Save ngay vào database
        savePlayerDataSync(playerData);

        // Xóa khỏi pending updates vì đã save rồi
        pendingUpdates.remove(playerData.getUuid());
    }

    // ===== SỬA PHƯƠNG THỨC getPlayerDataAsync =====
    public static CompletableFuture<PlayerData> getPlayerDataAsync(UUID uuid) {
        // Kiểm tra pending updates trước
        PlayerData pending = pendingUpdates.get(uuid);
        if (pending != null) {
            // Có dữ liệu pending, trả về ngay
            return CompletableFuture.completedFuture(pending);
        }

        // Kiểm tra cache
        PlayerData cached = playerDataCache.get(uuid);
        Long cacheTime = cacheTimestamps.get(uuid);

        if (cached != null && cacheTime != null &&
                System.currentTimeMillis() - cacheTime < CACHE_DURATION) {
            return CompletableFuture.completedFuture(cached);
        }

        // Load từ database
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                String query = "SELECT * FROM player_data WHERE uuid = ?";
                try (PreparedStatement statement = connection.prepareStatement(query)) {
                    statement.setString(1, uuid.toString());
                    ResultSet resultSet = statement.executeQuery();

                    PlayerData data;
                    if (resultSet.next()) {
                        int level = resultSet.getInt("level");
                        double points = resultSet.getDouble("points");
                        data = new PlayerData(uuid, level, points);
                    } else {
                        data = new PlayerData(uuid, 1, 0.0);
                    }

                    // Cache kết quả
                    playerDataCache.put(uuid, data);
                    cacheTimestamps.put(uuid, System.currentTimeMillis());

                    return data;
                }
            } catch (SQLException e) {
                EnchantMaterial.getInstance().getLogger().warning("Lỗi load player data: " + e.getMessage());
                return new PlayerData(uuid, 1, 0.0);
            }
        });
    }

    // ===== THÊM PHƯƠNG THỨC ĐỒNG BỘ DỮ LIỆU KHI PLAYER THOÁT =====
    public static void onPlayerQuit(UUID uuid) {
        // Force save dữ liệu của player khi họ thoát
        PlayerData pending = pendingUpdates.get(uuid);
        if (pending != null) {
            savePlayerDataSync(pending);
            pendingUpdates.remove(uuid);
        }
    }

    // ===== SỬA SHUTDOWN METHOD =====
    public static void shutdown() {
        try {
            // Save tất cả pending updates trước khi shutdown
            if (!pendingUpdates.isEmpty()) {
                for (PlayerData data : new ArrayList<>(pendingUpdates.values())) {
                    savePlayerDataSync(data);
                }
                pendingUpdates.clear();
            }

            // Shutdown executors
            batchExecutor.shutdown();
            if (!batchExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                batchExecutor.shutdownNow();
            }

            // Close connection pool
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
            }
        } catch (Exception e) {
            EnchantMaterial.getInstance().getLogger().warning("Lỗi khi đóng database: " + e.getMessage());
        }
    }

    /**
     * Đảm bảo tất cả các bảng cần thiết đều tồn tại
     */
    public static void ensureTablesExist() {
        try {
            createTables();
        } catch (SQLException e) {
            EnchantMaterial.getInstance().getLogger().severe("Lỗi đảm bảo bảng tồn tại: " + e.getMessage());
            e.printStackTrace();
        }
    }
    public static Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("DataSource is not initialized or closed");
        }
        return dataSource.getConnection();
    }

    // Thêm phương thức này vào DatabaseManager
    public static CompletableFuture<Void> forceSaveAllPendingDataAsync() {
        if (pendingUpdates.isEmpty() || batchExecutor.isShutdown()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            if (!pendingUpdates.isEmpty()) {
                processBatchUpdates();
            }
        }, batchExecutor);
    }

    public static void forceSaveAllPendingData() {
        try {
            forceSaveAllPendingDataAsync().join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String message = cause.getMessage() != null ? cause.getMessage() : cause.toString();
            EnchantMaterial.getInstance().getLogger().warning(
                    "Lỗi khi force save pending data: " + message
            );
        }
    }

    // Sửa phương thức savePlayerDataAsync để có tùy chọn save ngay lập tức
    public static void savePlayerDataSync(PlayerData playerData) {
        try (Connection connection = dataSource.getConnection()) {
            String sql = "MERGE INTO player_data KEY(uuid) VALUES (?, ?, ?, CURRENT_TIMESTAMP)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerData.getUuid().toString());
                statement.setInt(2, playerData.getLevel());
                statement.setDouble(3, playerData.getPoints());
                statement.executeUpdate();

                // Update cache
                playerDataCache.put(playerData.getUuid(), playerData);
                cacheTimestamps.put(playerData.getUuid(), System.currentTimeMillis());
            }
        } catch (SQLException e) {
            EnchantMaterial.getInstance().getLogger().warning("Lỗi save player data sync: " + e.getMessage());
        }
    }

    public static void savePlayerDataAsync(PlayerData playerData) {
        synchronized (BATCH_LOCK) {
            pendingUpdates.put(playerData.getUuid(), playerData);
            // Update cache ngay lập tức
            playerDataCache.put(playerData.getUuid(), playerData);
            cacheTimestamps.put(playerData.getUuid(), System.currentTimeMillis());
        }
    }

    // ====== PHẦN BỔ SUNG: API HIỆU NĂNG CHO GAMEPLAY ======

    /**
     * (MỚI) Lấy nhanh dữ liệu người chơi từ bộ nhớ (không chạm DB).
     * Ưu tiên pendingUpdates (mới nhất), sau đó tới playerDataCache. Có thể trả về null nếu chưa từng load.
     */
    public static PlayerData getCached(UUID uuid) {
        PlayerData p = pendingUpdates.get(uuid);
        if (p != null) return p;
        return playerDataCache.get(uuid);
    }

    public static PlayerData getPlayerDataCachedOrAsync(UUID uuid, Consumer<PlayerData> callback) {
        PlayerData cached = getCached(uuid);
        if (cached != null) {
            if (callback != null) {
                try {
                    callback.accept(cached);
                } catch (Exception e) {
                    EnchantMaterial.getInstance().getLogger().warning(
                            "Lỗi callback khi trả dữ liệu cache cho " + uuid + ": " + e.getMessage());
                }
            }
            return cached;
        }

        PlayerData defaultData = new PlayerData(uuid, 1, 0.0);
        EnchantMaterial.getInstance().getLogger().finer(
                "PlayerData cache miss cho " + uuid + ", trả về mặc định và tải async");

        getPlayerDataAsync(uuid).thenAccept(data -> {
            if (callback != null) {
                Bukkit.getScheduler().runTask(EnchantMaterial.getInstance(), () -> {
                    try {
                        callback.accept(data);
                    } catch (Exception e) {
                        EnchantMaterial.getInstance().getLogger().warning(
                                "Lỗi callback khi trả dữ liệu async cho " + uuid + ": " + e.getMessage());
                    }
                });
            }
        }).exceptionally(ex -> {
            EnchantMaterial.getInstance().getLogger().warning(
                    "Lỗi tải async player data cho " + uuid + ": " + ex.getMessage());
            if (callback != null) {
                Bukkit.getScheduler().runTask(EnchantMaterial.getInstance(), () -> {
                    try {
                        callback.accept(defaultData);
                    } catch (Exception e) {
                        EnchantMaterial.getInstance().getLogger().warning(
                                "Lỗi callback fallback cho " + uuid + ": " + e.getMessage());
                    }
                });
            }
            return null;
        });

        return defaultData;
    }

    /**
     * (MỚI) Cộng dồn điểm cho người chơi theo kiểu non-blocking.
     * - Không gọi DB trên main thread.
     * - Cập nhật cả pendingUpdates và cache để UI hiển thị tức thời.
     * - Nếu người chơi chưa có trong cache, tạo dữ liệu mặc định level=1, points=0.
     */
    public static void addPointsAsync(UUID uuid, double delta) {
        if (delta == 0.0) return;
        long now = System.currentTimeMillis();
        synchronized (BATCH_LOCK) {
            PlayerData cur = pendingUpdates.get(uuid);
            if (cur == null) cur = playerDataCache.get(uuid);
            if (cur == null) cur = new PlayerData(uuid, 1, 0.0);

            cur.setPoints(cur.getPoints() + delta);

            // Ghi vào pending và cache để flush theo batch
            pendingUpdates.put(uuid, cur);
            playerDataCache.put(uuid, cur);
            cacheTimestamps.put(uuid, now);
        }
    }

    public static RebirthData getCachedRebirthData(UUID uuid) {
        return rebirthDataCache.get(uuid);
    }

    public static CompletableFuture<RebirthData> getRebirthDataAsync(UUID uuid) {
        RebirthData cached = rebirthDataCache.get(uuid);
        Long ts = rebirthCacheTimestamps.get(uuid);
        if (cached != null && ts != null && System.currentTimeMillis() - ts < CACHE_DURATION) {
            return CompletableFuture.completedFuture(cached);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return getRebirthData(uuid);
            } catch (SQLException e) {
                EnchantMaterial.getInstance().getLogger().warning(
                        "Lỗi load rebirth data: " + e.getMessage());
                return new RebirthData(uuid, 0, 0);
            }
        });
    }

    public static RebirthData getRebirthDataCachedOrAsync(UUID uuid, Consumer<RebirthData> callback) {
        RebirthData cached = rebirthDataCache.get(uuid);
        Long ts = rebirthCacheTimestamps.get(uuid);
        if (cached != null && ts != null && System.currentTimeMillis() - ts < CACHE_DURATION) {
            if (callback != null) {
                try {
                    callback.accept(cached);
                } catch (Exception e) {
                    EnchantMaterial.getInstance().getLogger().warning(
                            "Lỗi callback khi trả rebirth cache cho " + uuid + ": " + e.getMessage());
                }
            }
            return cached;
        }

        RebirthData defaultData = new RebirthData(uuid, 0, 0);
        EnchantMaterial.getInstance().getLogger().finer(
                "Rebirth cache miss cho " + uuid + ", trả về mặc định và tải async");

        getRebirthDataAsync(uuid).thenAccept(data -> {
            rebirthDataCache.put(uuid, data);
            rebirthCacheTimestamps.put(uuid, System.currentTimeMillis());
            if (callback != null) {
                Bukkit.getScheduler().runTask(EnchantMaterial.getInstance(), () -> {
                    try {
                        callback.accept(data);
                    } catch (Exception e) {
                        EnchantMaterial.getInstance().getLogger().warning(
                                "Lỗi callback khi trả rebirth async cho " + uuid + ": " + e.getMessage());
                    }
                });
            }
        }).exceptionally(ex -> {
            EnchantMaterial.getInstance().getLogger().warning(
                    "Lỗi tải async rebirth data cho " + uuid + ": " + ex.getMessage());
            if (callback != null) {
                Bukkit.getScheduler().runTask(EnchantMaterial.getInstance(), () -> {
                    try {
                        callback.accept(defaultData);
                    } catch (Exception e) {
                        EnchantMaterial.getInstance().getLogger().warning(
                                "Lỗi callback fallback rebirth cho " + uuid + ": " + e.getMessage());
                    }
                });
            }
            return null;
        });

        return defaultData;
    }

    /**
     * Kiểm tra xem player đã claim reward level nào đó chưa (async)
     */
    public static CompletableFuture<Boolean> hasClaimedRewardAsync(UUID uuid, int level) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Set<Integer> claimedLevels = getClaimedLevels(uuid.toString());
                return claimedLevels.contains(level);
            } catch (SQLException e) {
                EnchantMaterial.getInstance().getLogger().warning("Lỗi kiểm tra claimed reward: " + e.getMessage());
                return false;
            }
        });
    }

    /**
     * Claim reward cho player (async)
     */
    public static CompletableFuture<Void> claimRewardAsync(UUID uuid, int level) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                String sql = "MERGE INTO level_rewards (uuid, level, claimed_at) KEY(uuid, level) VALUES (?, ?, CURRENT_TIMESTAMP)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, uuid.toString());
                    statement.setInt(2, level);
                    statement.executeUpdate();

                    // Update cache
                    Set<Integer> claimedLevels = claimedRewardsCache.computeIfAbsent(uuid.toString(), k -> new HashSet<>());
                    claimedLevels.add(level);
                }
            } catch (SQLException e) {
                EnchantMaterial.getInstance().getLogger().warning("Lỗi claim reward: " + e.getMessage());
            }
        });
    }

    private static void createTables() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            // Bảng player_data
            String createPlayerTable = "CREATE TABLE IF NOT EXISTS player_data (" +
                    "uuid VARCHAR(36) PRIMARY KEY," +
                    "level INT DEFAULT 1," +
                    "points DOUBLE DEFAULT 0.0," +
                    "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";

            // Bảng level_rewards
            String createRewardsTable = "CREATE TABLE IF NOT EXISTS level_rewards (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "uuid VARCHAR(36) NOT NULL," +
                    "level INT NOT NULL," +
                    "claimed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "UNIQUE(uuid, level)" +
                    ")";

            // Bảng boosters
            String createBoostersTable = "CREATE TABLE IF NOT EXISTS boosters (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "uuid VARCHAR(36) NOT NULL, " +
                    "type VARCHAR(10) NOT NULL, " +
                    "multiplier DOUBLE NOT NULL, " +
                    "end_time BIGINT NOT NULL, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";

            // Tạo từng bảng
            try (PreparedStatement stmt1 = connection.prepareStatement(createPlayerTable)) {
                stmt1.executeUpdate();
            }
            try (PreparedStatement stmt2 = connection.prepareStatement(createRewardsTable)) {
                stmt2.executeUpdate();
            }
            try (PreparedStatement stmt3 = connection.prepareStatement(createBoostersTable)) {
                stmt3.executeUpdate();
                // Indexes
                try (PreparedStatement idxUuidStmt = connection.prepareStatement("CREATE INDEX IF NOT EXISTS idx_uuid ON boosters(uuid)")) {
                    idxUuidStmt.executeUpdate();
                }
                try (PreparedStatement idxEndTimeStmt = connection.prepareStatement("CREATE INDEX IF NOT EXISTS idx_end_time ON boosters(end_time)")) {
                    idxEndTimeStmt.executeUpdate();
                }
            }

            // Bảng rebirth_data
            String createRebirthTable = "CREATE TABLE IF NOT EXISTS rebirth_data (" +
                    "uuid VARCHAR(36) PRIMARY KEY," +
                    "rebirth_level INT DEFAULT 0," +
                    "last_rebirth_time BIGINT DEFAULT 0," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";

            try (PreparedStatement stmt4 = connection.prepareStatement(createRebirthTable)) {
                stmt4.executeUpdate();
            }
        }
    }

    // Thêm methods cho rebirth
    public static RebirthData getRebirthData(UUID uuid) throws SQLException {
        RebirthData cached = rebirthDataCache.get(uuid);
        Long ts = rebirthCacheTimestamps.get(uuid);
        if (cached != null && ts != null && System.currentTimeMillis() - ts < CACHE_DURATION) {
            return cached;
        }

        try (Connection connection = dataSource.getConnection()) {
            String query = "SELECT * FROM rebirth_data WHERE uuid = ?";
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setString(1, uuid.toString());
                ResultSet resultSet = statement.executeQuery();

                RebirthData data;
                if (resultSet.next()) {
                    int rebirthLevel = resultSet.getInt("rebirth_level");
                    long lastRebirthTime = resultSet.getLong("last_rebirth_time");
                    data = new RebirthData(uuid, rebirthLevel, lastRebirthTime);
                } else {
                    data = new RebirthData(uuid, 0, 0);
                }

                rebirthDataCache.put(uuid, data);
                rebirthCacheTimestamps.put(uuid, System.currentTimeMillis());
                return data;
            }
        }
    }

    public static void saveRebirthData(RebirthData rebirthData) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            String query = "MERGE INTO rebirth_data (uuid, rebirth_level, last_rebirth_time) " +
                    "KEY(uuid) VALUES (?, ?, ?)";

            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setString(1, rebirthData.getUuid().toString());
                statement.setInt(2, rebirthData.getRebirthLevel());
                statement.setLong(3, rebirthData.getLastRebirthTime());
                statement.executeUpdate();

                rebirthDataCache.put(rebirthData.getUuid(), rebirthData);
                rebirthCacheTimestamps.put(rebirthData.getUuid(), System.currentTimeMillis());
            }
        }
    }

    // Thêm method này để force refresh cache cho một player cụ thể
    public static void refreshClaimedRewardsCache(String uuid) {
        claimedRewardsCache.remove(uuid);
        try {
            getClaimedLevels(uuid); // Này sẽ reload từ database và cache lại
        } catch (SQLException e) {
            EnchantMaterial.getInstance().getLogger().warning("Lỗi refresh cache cho " + uuid + ": " + e.getMessage());
        }
    }

    // ===== PHẦN 8: LEGACY METHODS (để tương thích) =====

    @Deprecated
    public static PlayerData getPlayerData(String uuid) throws SQLException {
        // Chuyển sang async version
        try {
            return getPlayerDataAsync(UUID.fromString(uuid)).get();
        } catch (Exception e) {
            throw new SQLException("Error getting player data", e);
        }
    }

    @Deprecated
    public static void savePlayerData(PlayerData playerData) {
        savePlayerDataAsync(playerData);
    }

    @Deprecated
    public static boolean hasClaimedReward(String uuid, int level) throws SQLException {
        try {
            return hasClaimedRewardAsync(UUID.fromString(uuid), level).get();
        } catch (Exception e) {
            throw new SQLException("Error checking claimed reward", e);
        }
    }

    @Deprecated
    public static void claimReward(String uuid, int level) throws SQLException {
        try {
            claimRewardAsync(UUID.fromString(uuid), level).get();
        } catch (Exception e) {
            throw new SQLException("Error claiming reward", e);
        }
    }

    public static Set<Integer> getClaimedLevels(String uuid) throws SQLException {
        Set<Integer> cached = claimedRewardsCache.get(uuid);
        if (cached != null) {
            return new HashSet<>(cached);
        }

        try (Connection connection = dataSource.getConnection()) {
            String query = "SELECT level FROM level_rewards WHERE uuid = ?";
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setString(1, uuid);
                ResultSet resultSet = statement.executeQuery();

                Set<Integer> claimedLevels = new HashSet<>();
                while (resultSet.next()) {
                    claimedLevels.add(resultSet.getInt("level"));
                }

                claimedRewardsCache.put(uuid, claimedLevels);
                return claimedLevels;
            }
        }
    }

    public static Map<UUID, PlayerData> getAllPlayerData() throws SQLException {
        Map<UUID, PlayerData> playerDataMap = new HashMap<>();

        try (Connection connection = dataSource.getConnection()) {
            String query = "SELECT * FROM player_data";
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                ResultSet resultSet = statement.executeQuery();

                while (resultSet.next()) {
                    UUID uuid = UUID.fromString(resultSet.getString("uuid"));
                    int level = resultSet.getInt("level");
                    double points = resultSet.getDouble("points");
                    PlayerData data = new PlayerData(uuid, level, points);

                    playerDataMap.put(uuid, data);
                    // Cache luôn
                    playerDataCache.put(uuid, data);
                    cacheTimestamps.put(uuid, System.currentTimeMillis());
                }
            }
        }

        return playerDataMap;
    }

    /**
     * Clear all caches - useful for reload command
     */
    public static void clearAllCaches() {
        try {
            // Clear player data cache
            playerDataCache.clear();

            // Clear claimed rewards cache
            claimedRewardsCache.clear();

            rebirthDataCache.clear();
            rebirthCacheTimestamps.clear();

            // Force save any pending data before clearing
            forceSaveAllPendingData();

            EnchantMaterial.getInstance().getLogger().info("All caches have been cleared successfully!");
        } catch (Exception e) {
            EnchantMaterial.getInstance().getLogger().warning("Error clearing caches: " + e.getMessage());
        }
    }
}

package org.ledat.enchantMaterial.booster;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.ledat.enchantMaterial.EnchantMaterial;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class BoosterStorage {

    private final EnchantMaterial plugin;
    private static HikariDataSource dataSource;
    private static boolean initialized = false;
    
    public BoosterStorage(EnchantMaterial plugin) {
        this.plugin = plugin;
        // Đảm bảo bảng boosters tồn tại
        try {
            if (!isTableExists()) {
                plugin.getLogger().warning("⚠️ Bảng boosters không tồn tại, đang tạo lại...");
                org.ledat.enchantMaterial.DatabaseManager.ensureTablesExist();
            }
          //  plugin.getLogger().info("✅ BoosterStorage initialized - using DatabaseManager connection");
        } catch (Exception e) {
            plugin.getLogger().severe("❌ Lỗi khởi tạo BoosterStorage: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void initializeConnectionPool() {
        // XÓA TOÀN BỘ - không cần tạo connection pool riêng
    }
    
    
    private Connection getConnection() throws SQLException {
        // Sử dụng DatabaseManager thay vì dataSource riêng
        return org.ledat.enchantMaterial.DatabaseManager.getConnection();
    }
    
    public CompletableFuture<Integer> deleteExpiredBoostersAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM boosters WHERE end_time < ?")) {
                
                stmt.setLong(1, System.currentTimeMillis());
                int deleted = stmt.executeUpdate();
                
                if (deleted > 0) {
                //    plugin.getLogger().info("Đã xóa " + deleted + " boosters hết hạn");
                }
                
                return deleted;
                
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Lỗi xóa expired boosters", e);
                return 0;
            }
        });
    }
    
    public void close() {
        // Không cần đóng gì cả
    }
    
    public static void shutdown() {
        initialized = false;
    }
    
    /**
     * Save boosters với batch processing và upsert
     */
    public void saveBoosters(Map<UUID, List<Booster>> boosters) {
        if (boosters.isEmpty()) return;
        
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            
            try {
                // Xóa boosters cũ của các players có trong map
                Set<UUID> playerUUIDs = boosters.keySet();
                if (!playerUUIDs.isEmpty()) {
                    String deleteSql = "DELETE FROM boosters WHERE uuid = ?";
                    try (PreparedStatement deleteStmt = conn.prepareStatement(deleteSql)) {
                        for (UUID uuid : playerUUIDs) {
                            deleteStmt.setString(1, uuid.toString());
                            deleteStmt.addBatch();
                        }
                        deleteStmt.executeBatch();
                    }
                }
                
                // Insert boosters mới
                String insertSql = "INSERT INTO boosters (uuid, type, multiplier, end_time) VALUES (?, ?, ?, ?)";
                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    int batchCount = 0;
                    
                    for (Map.Entry<UUID, List<Booster>> entry : boosters.entrySet()) {
                        String uuidStr = entry.getKey().toString();
                        
                        for (Booster booster : entry.getValue()) {
                            if (!booster.isExpired()) {
                                insertStmt.setString(1, uuidStr);
                                insertStmt.setString(2, booster.getType().name());
                                insertStmt.setDouble(3, booster.getMultiplier());
                                insertStmt.setLong(4, booster.getEndTime());
                                insertStmt.addBatch();
                                
                                batchCount++;
                                
                                // Execute batch mỗi 100 records
                                if (batchCount % 100 == 0) {
                                    insertStmt.executeBatch();
                                }
                            }
                        }
                    }
                    
                    // Execute remaining batch
                    if (batchCount % 100 != 0) {
                        insertStmt.executeBatch();
                    }
                }
                
                conn.commit();
            //    plugin.getLogger().info("✅ Đã save " + boosters.size() + " player boosters");
                
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
            
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "❌ Lỗi save boosters", e);
        }
    }
    
    /**
     * Load boosters với filtering và validation
     */
    public Map<UUID, List<Booster>> loadBoosters() {
        Map<UUID, List<Booster>> result = new HashMap<>();
        long currentTime = System.currentTimeMillis();
        
        String sql = "SELECT uuid, type, multiplier, end_time FROM boosters WHERE end_time > ? ORDER BY uuid, end_time";
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setLong(1, currentTime);
            
            try (ResultSet rs = stmt.executeQuery()) {
                int loadedCount = 0;
                
                while (rs.next()) {
                    try {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        BoosterType type = BoosterType.valueOf(rs.getString("type"));
                        double multiplier = rs.getDouble("multiplier");
                        long endTime = rs.getLong("end_time");
                        
                        // Validate data
                        if (multiplier <= 0 || endTime <= currentTime) {
                            continue;
                        }
                        
                        long durationSeconds = (endTime - currentTime) / 1000;
                        if (durationSeconds <= 0) continue;
                        
                        Booster booster = new Booster(type, multiplier, durationSeconds);
                        result.computeIfAbsent(uuid, k -> new ArrayList<>()).add(booster);
                        loadedCount++;
                        
                    } catch (Exception e) {
                        plugin.getLogger().warning("❌ Lỗi parse booster record: " + e.getMessage());
                    }
                }
                
             //   plugin.getLogger().info("✅ Đã load " + loadedCount + " boosters cho " + result.size() + " players");
            }
            
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "❌ Lỗi load boosters", e);
        }
        
        return result;
    }
    
    /**
     * Get boosters for specific player
     */
    public CompletableFuture<List<Booster>> getPlayerBoostersAsync(UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            List<Booster> boosters = new ArrayList<>();
            long currentTime = System.currentTimeMillis();
            
            String sql = "SELECT type, multiplier, end_time FROM boosters WHERE uuid = ? AND end_time > ?";
            
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                
                stmt.setString(1, playerUUID.toString());
                stmt.setLong(2, currentTime);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        try {
                            BoosterType type = BoosterType.valueOf(rs.getString("type"));
                            double multiplier = rs.getDouble("multiplier");
                            long endTime = rs.getLong("end_time");
                            
                            long durationSeconds = (endTime - currentTime) / 1000;
                            if (durationSeconds > 0) {
                                boosters.add(new Booster(type, multiplier, durationSeconds));
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning("❌ Lỗi parse booster: " + e.getMessage());
                        }
                    }
                }
                
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "❌ Lỗi get player boosters", e);
            }
            
            return boosters;
        });
    }
    
    /**
     * Database statistics
     */
    public void printStatistics() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = getConnection()) {
                // Total boosters
                try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) as total FROM boosters");
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        int total = rs.getInt("total");
                   //     plugin.getLogger().info("📊 Total boosters in database: " + total);
                    }
                }
                
                // Active boosters
                try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) as active FROM boosters WHERE end_time > ?");
                     ResultSet rs = stmt.executeQuery()) {
                    stmt.setLong(1, System.currentTimeMillis());
                    if (rs.next()) {
                        int active = rs.getInt("active");
                        // Dòng 258 - Comment out log active boosters
                        // plugin.getLogger().info("📊 Active boosters: " + active);
                    }
                }
                
                // Boosters by type
                try (PreparedStatement stmt = conn.prepareStatement("SELECT type, COUNT(*) as count FROM boosters WHERE end_time > ? GROUP BY type");
                     ResultSet rs = stmt.executeQuery()) {
                    stmt.setLong(1, System.currentTimeMillis());
                    while (rs.next()) {
                        String type = rs.getString("type");
                        int count = rs.getInt("count");
                    //    plugin.getLogger().info("📊 " + type + " boosters: " + count);
                    }
                }
                
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "❌ Lỗi get statistics", e);
            }
        });
    }
    
    /**
     * Kiểm tra xem bảng boosters có tồn tại không
     */
    public boolean isTableExists() {
        try (Connection conn = getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "BOOSTERS", new String[]{"TABLE"})) {
                return rs.next();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("❌ Lỗi kiểm tra bảng boosters: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Save a single booster for a player
     */
    public void saveBooster(UUID playerUUID, Booster booster) {
        if (booster == null || booster.isExpired()) {
            return;
        }
        
        String sql = "INSERT INTO boosters (uuid, type, multiplier, end_time) VALUES (?, ?, ?, ?)";
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, playerUUID.toString());
            stmt.setString(2, booster.getType().name());
            stmt.setDouble(3, booster.getMultiplier());
            stmt.setLong(4, booster.getEndTime());
            
            int result = stmt.executeUpdate();
            
            if (result > 0) {
             //   plugin.getLogger().info("✅ Đã save booster " + booster.getType() + " cho player " + playerUUID);
            }
            
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "❌ Lỗi save booster cho player " + playerUUID, e);
        }
    }
}

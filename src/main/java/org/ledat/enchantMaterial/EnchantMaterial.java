package org.ledat.enchantMaterial;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.ledat.enchantMaterial.booster.Booster;
import org.ledat.enchantMaterial.booster.BoosterManager;
import org.ledat.enchantMaterial.booster.BoosterStorage;
import org.ledat.enchantMaterial.gui.LevelRewardsGUI;
import org.ledat.enchantMaterial.gui.RebirthConfigEditorGUI;
import org.ledat.enchantMaterial.gui.RebirthGUI;
import org.ledat.enchantMaterial.listeners.PlayerQuitListener;
import org.ledat.enchantMaterial.rebirth.RebirthManager;
import org.ledat.enchantMaterial.rewards.LevelRewardsManager;
import org.ledat.enchantMaterial.region.RegionManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.io.File;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class EnchantMaterial extends JavaPlugin {

    private static EnchantMaterial instance;
    private File luckyBlockFile;
    private YamlConfiguration luckyBlockConfig;
    private YamlConfiguration levelSystemConfig;
    private FortuneManager fortuneManager;
    private LuckyChanceBlockListener luckyChanceBlockListener;
    private BoosterManager boosterManager;
    private BoosterStorage boosterStorage;
    private File boosterFile;
    private FileConfiguration boosterConfig;
    private LevelRewardsManager levelRewardsManager;
    private LevelRewardsGUI levelRewardsGUI;
    private RegionManager regionManager;

    private RebirthManager rebirthManager;
    private RebirthGUI rebirthGUI;
    private RebirthConfigEditorGUI rebirthConfigEditorGUI;

    // ===== PvP API Hook (không cần compileOnly) =====
    private static final String BVP_META = "ledat.baovepvp.protected";
    private Object pvpService;                  // provider thực tế từ BaoVePvP
    private Method pvpIsProtectedMethod;        // cache method isProtected(Player)

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        reloadConfig();
        loadBoosterConfig();
        hookBaoVePvP();

        // Load luckyblock.yml TRƯỚC KHI khởi tạo listeners
        luckyBlockFile = new File(getDataFolder(), "luckyblock.yml");
        if (!luckyBlockFile.exists()) {
            saveResource("luckyblock.yml", false);
        }
        luckyBlockConfig = YamlConfiguration.loadConfiguration(luckyBlockFile);
    
        // Khởi tạo FortuneManager và LuckyChanceBlockListener
        fortuneManager = new FortuneManager(this);
        luckyChanceBlockListener = new LuckyChanceBlockListener(this);
        getServer().getPluginManager().registerEvents(new BlockBreakListener(fortuneManager, luckyChanceBlockListener), this);

        getCommand("enchantmaterial").setExecutor(new CommandManager());
        getCommand("enchantmaterial").setTabCompleter(new EnchantMaterialTabCompleter());
        getLogger().info("EnchantMaterial has been enabled!");

        // level-system.yml
        File levelSystemFile = new File(getDataFolder(), "level-system.yml");
        if (!levelSystemFile.exists()) {
            saveResource("level-system.yml", false);
        }
        levelSystemConfig = YamlConfiguration.loadConfiguration(levelSystemFile);

        // PlaceholderAPI
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new EnchantMaterialPlaceholder(this).register();
            getLogger().info("PlaceholderAPI expansion registered successfully!");
        }

        try {
            // QUAN TRỌNG: Khởi tạo database TRƯỚC KHI tạo BoosterStorage
            DatabaseManager.initializeDatabase();
            getLogger().info("Database đã được khởi tạo thành công với connection pooling!");
            
            // CHỈ SAU KHI database đã được khởi tạo, mới tạo BoosterStorage
            boosterStorage = new BoosterStorage(this);
            getLogger().info("BoosterStorage đã được khởi tạo thành công!");
            
            // Khởi tạo BoosterManager sau khi BoosterStorage đã sẵn sàng
            boosterManager = new BoosterManager(this);
            
            // Load boosters từ database
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (boosterStorage.isTableExists()) {
                    Map<UUID, List<Booster>> loaded = boosterStorage.loadBoosters();
                    for (Map.Entry<UUID, List<Booster>> entry : loaded.entrySet()) {
                        Player player = Bukkit.getPlayer(entry.getKey());
                        if (player != null && player.isOnline()) {
                            for (Booster b : entry.getValue()) {
                                boosterManager.addBooster(player, b);
                            }
                        }
                    }
                    getLogger().info("Boosters đã được load thành công!");
                } else {
                    getLogger().warning("Bảng boosters không tồn tại, không thể load boosters!");
                }
            }, 20L); // Đợi 1 giây
        } catch (Exception e) {
            getLogger().severe("Lỗi khởi động plugin: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        // THÊM PHẦN NÀY: Initialize level rewards system
        levelRewardsManager = new LevelRewardsManager(this);
        levelRewardsGUI = new LevelRewardsGUI(this, levelRewardsManager);
        getServer().getPluginManager().registerEvents(levelRewardsGUI, this);
        getServer().getPluginManager().registerEvents(new PlayerQuitListener(), this);
        getLogger().info("Level Rewards system has been initialized!");
    
        // THÊM PHẦN NÀY: Initialize Rebirth system
        rebirthManager = new RebirthManager(this);
        rebirthGUI = new RebirthGUI(this, rebirthManager);
        rebirthConfigEditorGUI = new RebirthConfigEditorGUI(this, rebirthManager);
        getServer().getPluginManager().registerEvents(rebirthGUI, this);
        getServer().getPluginManager().registerEvents(rebirthConfigEditorGUI, this);
        getLogger().info("Rebirth system has been initialized!");

        regionManager = new RegionManager(this);
        getLogger().info("Region system has been initialized!");

        // Load dữ liệu người chơi khi server start
        Bukkit.getScheduler().runTask(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                try {
                    PlayerData playerData = DatabaseManager.getPlayerData(player.getUniqueId().toString());
                    setPlayerLevel(player, playerData.getLevel());
                    setPlayerPoints(player, playerData.getPoints());
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        });

        // Gộp auto-save player + booster vào 1 task với tần suất cao hơn
        int autoSaveInterval = getConfig().getInt("database.auto-save-interval", 60);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            // ✅ CHỈ FORCE SAVE PENDING DATA
            DatabaseManager.forceSaveAllPendingData();
            
            // ❌ XÓA PHẦN NÀY - KHÔNG CẦN SAVE LẠI
            // for (Player player : Bukkit.getOnlinePlayers()) {
            //     savePlayerDataAsync(player);
            // }
        
            // Lưu booster data
            boosterStorage.saveBoosters(boosterManager.getAllBoosters());
        
          //  getLogger().info("Auto-save completed - " + Bukkit.getOnlinePlayers().size() + " players");
        
        }, 0L, autoSaveInterval * 20L);

        // Tự động xoá booster đã hết hạn khỏi H2 mỗi 5 phút
        // Đợi bảng được tạo trước khi khởi tạo task cleanup
        Bukkit.getScheduler().runTaskLater(this, () -> {
            // Đợi cho đến khi bảng tồn tại
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                boosterStorage.deleteExpiredBoostersAsync().thenAccept(deletedCount -> {
                    if (deletedCount > 0) {
                        getLogger().info("Đã dọn dẹp " + deletedCount + " boosters hết hạn");
                    }
                }).exceptionally(throwable -> {
                    getLogger().warning("Lỗi khi dọn dẹp expired boosters: " + throwable.getMessage());
                    return null;
                });
            }, 0L, 20L * 300); // Chạy ngay lập tức, sau đó mỗi 5 phút
        }, 20L * 120); // Đợi 2 phút sau khi plugin khởi động
    }

    @Override
    public void onDisable() {
        getLogger().info("EnchantMaterial has been disabled!");
    
        // Force save tất cả pending data trước
        DatabaseManager.forceSaveAllPendingData();
        
        // Lưu toàn bộ dữ liệu người chơi từ PENDING UPDATES
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                UUID uuid = player.getUniqueId();
                
                // Lấy dữ liệu từ pending updates hoặc cache
                DatabaseManager.getPlayerDataAsync(uuid).thenAccept(playerData -> {
                    // Force save ngay lập tức
                    DatabaseManager.savePlayerDataImmediate(playerData);
                  //  getLogger().info("Đã save dữ liệu cho player: " + player.getName() + 
                  //                 " (Level: " + playerData.getLevel() + ", Points: " + playerData.getPoints() + ")");
                }).exceptionally(throwable -> {
                 //   getLogger().warning("Lỗi save dữ liệu cho " + player.getName() + ": " + throwable.getMessage());
                    return null;
                });
                
            } catch (Exception e) {
                getLogger().warning("Lỗi xử lý dữ liệu cho " + player.getName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        // Đợi một chút để đảm bảo tất cả async operations hoàn thành
        try {
            Thread.sleep(1000); // 1 giây
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    
        if (boosterManager != null) {
            boosterManager.shutdown();
        }
        
        if (rebirthManager != null) {
            getLogger().info("Rebirth system has been disabled!");
        }
        
        BoosterStorage.shutdown();
        DatabaseManager.shutdown();
    }

    // Thêm các phương thức reload mới
    public void loadLuckyBlockConfig() {
        luckyBlockFile = new File(getDataFolder(), "luckyblock.yml");
        if (!luckyBlockFile.exists()) {
            saveResource("luckyblock.yml", false);
        }
        luckyBlockConfig = YamlConfiguration.loadConfiguration(luckyBlockFile);
        getLogger().info("LuckyBlock config has been reloaded!");
    }
    
    public void loadLevelSystemConfig() {
        File levelSystemFile = new File(getDataFolder(), "level-system.yml");
        if (!levelSystemFile.exists()) {
            saveResource("level-system.yml", false);
        }
        levelSystemConfig = YamlConfiguration.loadConfiguration(levelSystemFile);
        getLogger().info("Level system config has been reloaded!");
    }
    
    public void loadLevelRewardsConfig() {
        if (levelRewardsManager != null) {
            levelRewardsManager.reloadConfig();
            getLogger().info("Level rewards config has been reloaded!");
        }
    }
    
    // Cải thiện phương thức loadRebirthConfig hiện có
    public void loadRebirthConfig() {
        if (rebirthManager != null) {
            rebirthManager.reloadConfig();
            getLogger().info("Rebirth config has been reloaded!");
        }
    }
    
    // Cải thiện phương thức loadBoosterConfig hiện có
    public void loadBoosterConfig() {
        boosterFile = new File(getDataFolder(), "booster.yml");
        if (!boosterFile.exists()) {
            saveResource("booster.yml", false);
        }
        boosterConfig = YamlConfiguration.loadConfiguration(boosterFile);
        getLogger().info("Booster config has been reloaded!");
    }
    
    // Thêm phương thức reload toàn bộ
    public void reloadAllConfigs() {
        try {
            // Reload tất cả config files
            reloadConfig(); // config.yml
            loadBoosterConfig(); // booster.yml
            loadLuckyBlockConfig(); // luckyblock.yml
            loadRebirthConfig(); // rebirth.yml
        
            // Reload level rewards
            if (levelRewardsManager != null) {
                levelRewardsManager.reloadConfig();
            }
        
            // Reload regions
            if (regionManager != null) {
                regionManager.reloadConfig();
            }
        
            getLogger().info("All configs have been reloaded!");
        } catch (Exception e) {
            getLogger().severe("Error reloading configs: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static EnchantMaterial getInstance() {
        return instance;
    }

    public void savePlayerDataAsync(final Player player) {
        // ✅ KHÔNG CẦN LÀM GÀ - DatabaseManager tự động save pending updates
        // Auto-save chỉ cần force save pending data
        DatabaseManager.forceSaveAllPendingData();
        
        getLogger().info("Auto-save completed for all players");
    }

    public void setPlayerLevel(Player player, int level) {
        try {
            // Lấy dữ liệu hiện tại từ cache/pending
            DatabaseManager.getPlayerDataAsync(player.getUniqueId()).thenAccept(playerData -> {
                playerData.setLevel(level);
                DatabaseManager.savePlayerDataAsync(playerData);
              //  getLogger().info("Set level " + level + " cho " + player.getName());
            });
        } catch (Exception e) {
            getLogger().warning("Lỗi set level: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void setPlayerPoints(Player player, double points) {
        try {
            // Lấy dữ liệu hiện tại từ cache/pending
            DatabaseManager.getPlayerDataAsync(player.getUniqueId()).thenAccept(playerData -> {
                playerData.setPoints(points);
                DatabaseManager.savePlayerDataAsync(playerData);
                getLogger().info("💰 Set points " + points + " cho " + player.getName());
            });
        } catch (Exception e) {
            getLogger().warning("❌ Lỗi set points: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Thêm phương thức để lấy dữ liệu từ cache/pending
    public double getCurrentPoints(Player player) {
        try {
            return DatabaseManager.getPlayerDataAsync(player.getUniqueId()).get().getPoints();
        } catch (Exception e) {
            getLogger().warning("❌ Lỗi lấy points: " + e.getMessage());
            return 0.0;
        }
    }

    public int getCurrentLevel(Player player) {
        try {
            return DatabaseManager.getPlayerDataAsync(player.getUniqueId()).get().getLevel();
        } catch (Exception e) {
            getLogger().warning("❌ Lỗi lấy level: " + e.getMessage());
            return 1;
        }
    }

    private void hookBaoVePvP() {
        try {
            Class<?> apiClazz = Class.forName("org.ledat.baovePvP.PvpProtectionService");
            // dùng raw type để không cần generics
            RegisteredServiceProvider<?> reg =
                    getServer().getServicesManager().getRegistration((Class) apiClazz);
            if (reg != null) {
                pvpService = reg.getProvider();
                pvpIsProtectedMethod = apiClazz.getMethod("isProtected", Player.class);
                getLogger().info("[EnchantMaterial] Hook BaoVePvP API thành công.");
            } else {
                getLogger().info("[EnchantMaterial] Không thấy BaoVePvP API, sẽ dùng metadata fallback.");
            }
        } catch (Throwable t) {
            getLogger().info("[EnchantMaterial] BaoVePvP API chưa có trên classpath, dùng metadata fallback.");
            pvpService = null;
            pvpIsProtectedMethod = null;
        }
    }

    /** true nếu BẬT giảm điểm khi có PvP prot & người chơi KHÔNG có quyền bypass */
    public boolean isPvpReductionEnabled(Player p) {
        if (!getConfig().getBoolean("pvp-protection.enabled", true)) return false;
        String perm = getConfig().getString("pvp-protection.bypass-permission", "");
        return perm == null || perm.isEmpty() || !p.hasPermission(perm);
    }

    /** multiplier khi bảo vệ PvP (ví dụ 0.5) */
    public double getPvpMultiplier() {
        return getConfig().getDouble("pvp-protection.multiplier", 0.5D);
    }

    /** Kiểm tra người chơi có đang được bảo vệ PvP không (API > metadata) */
    public boolean isPvpProtected(Player p) {
        // Ưu tiên API nếu có
        if (pvpService != null && pvpIsProtectedMethod != null) {
            try {
                Object r = pvpIsProtectedMethod.invoke(pvpService, p);
                if (r instanceof Boolean) return (Boolean) r;
            } catch (Throwable ignored) {}
        }
        // Fallback metadata
        return p.hasMetadata(BVP_META);
    }

    public YamlConfiguration getLuckyBlockConfig() {
        return luckyBlockConfig;
    }

    public FortuneManager getFortuneManager() {
        return fortuneManager;
    }

    public BoosterManager getBoosterManager() {
        return boosterManager;
    }

    public FileConfiguration getBoosterConfig() {
        return boosterConfig;
    }

    public LevelRewardsManager getLevelRewardsManager() {
        return levelRewardsManager;
    }
    
    public LevelRewardsGUI getLevelRewardsGUI() {
        return levelRewardsGUI;
    }

    public BoosterStorage getBoosterStorage() {
        return boosterStorage;
    }

    public RegionManager getRegionManager() {
        return regionManager;
    }
    
    public RebirthManager getRebirthManager() { return rebirthManager; }
    public RebirthGUI getRebirthGUI() { return rebirthGUI; }
    public RebirthConfigEditorGUI getRebirthConfigEditorGUI() { return rebirthConfigEditorGUI; }
}

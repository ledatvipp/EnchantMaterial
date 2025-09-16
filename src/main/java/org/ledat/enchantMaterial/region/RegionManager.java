package org.ledat.enchantMaterial.region;

import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.ledat.enchantMaterial.EnchantMaterial;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RegionManager {
    private final EnchantMaterial plugin;
    private final File regionsFile;
    private FileConfiguration regionsConfig;
    
    // Cache cho hiệu suất - tránh lag
    private final Map<String, Region> regionCache = new ConcurrentHashMap<>();
    private final Map<UUID, Location> playerPos1 = new HashMap<>();
    private final Map<UUID, Location> playerPos2 = new HashMap<>();
    
    public RegionManager(EnchantMaterial plugin) {
        this.plugin = plugin;
        this.regionsFile = new File(plugin.getDataFolder(), "regions.yml");
        loadConfig();
        loadRegions();
    }
    
    private void loadConfig() {
        if (!regionsFile.exists()) {
            try {
                regionsFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Không thể tạo file regions.yml: " + e.getMessage());
            }
        }
        regionsConfig = YamlConfiguration.loadConfiguration(regionsFile);
    }
    
    private void loadRegions() {
        regionCache.clear();
        if (regionsConfig.getConfigurationSection("regions") != null) {
            for (String regionName : regionsConfig.getConfigurationSection("regions").getKeys(false)) {
                String path = "regions." + regionName;
                
                String worldName = regionsConfig.getString(path + ".world");
                double minX = regionsConfig.getDouble(path + ".min.x");
                double minY = regionsConfig.getDouble(path + ".min.y");
                double minZ = regionsConfig.getDouble(path + ".min.z");
                double maxX = regionsConfig.getDouble(path + ".max.x");
                double maxY = regionsConfig.getDouble(path + ".max.y");
                double maxZ = regionsConfig.getDouble(path + ".max.z");
                
                Region region = new Region(regionName, worldName, minX, minY, minZ, maxX, maxY, maxZ);
                regionCache.put(regionName.toLowerCase(), region);
            }
        }
        plugin.getLogger().info("Đã load " + regionCache.size() + " regions từ file config!");
    }
    
    public void setPos1(Player player, Location location) {
        // SỬA: Sử dụng tọa độ block thay vì tọa độ người chơi
        Location blockLocation = new Location(location.getWorld(), 
            Math.floor(location.getX()), 
            Math.floor(location.getY()), 
            Math.floor(location.getZ()));
        playerPos1.put(player.getUniqueId(), blockLocation);
        player.sendMessage("§a✅ Đã đặt vị trí 1: " + formatLocation(blockLocation));
    }
    
    public void setPos2(Player player, Location location) {
        // SỬA: Sử dụng tọa độ block thay vì tọa độ người chơi
        Location blockLocation = new Location(location.getWorld(), 
            Math.floor(location.getX()), 
            Math.floor(location.getY()), 
            Math.floor(location.getZ()));
        playerPos2.put(player.getUniqueId(), blockLocation);
        player.sendMessage("§a✅ Đã đặt vị trí 2: " + formatLocation(blockLocation));
    }
    
    // Thêm method debug để kiểm tra
    public void debugLocation(Player player, Location location) {
        player.sendMessage("§6=== Debug Location ===");
        player.sendMessage("§eLocation: " + formatLocation(location));
        player.sendMessage("§eIn allowed region: " + isInAllowedRegion(location));
        
        for (Region region : regionCache.values()) {
            boolean contains = region.contains(location);
            player.sendMessage("§7- " + region.getName() + ": " + (contains ? "§aYES" : "§cNO"));
            if (contains) {
                player.sendMessage("§7  " + region.getInfo());
            }
        }
    }
    
    // Cập nhật createRegion để hiển thị thông tin chi tiết
    public boolean createRegion(Player player, String regionName) {
        UUID uuid = player.getUniqueId();
        
        if (!playerPos1.containsKey(uuid) || !playerPos2.containsKey(uuid)) {
            player.sendMessage("§c✗ Bạn cần đặt cả 2 vị trí trước khi tạo region!");
            return false;
        }
        
        if (regionCache.containsKey(regionName.toLowerCase())) {
            player.sendMessage("§c✗ Region '" + regionName + "' đã tồn tại!");
            return false;
        }
        
        Location pos1 = playerPos1.get(uuid);
        Location pos2 = playerPos2.get(uuid);
        
        if (!pos1.getWorld().equals(pos2.getWorld())) {
            player.sendMessage("§c✗ Hai vị trí phải cùng một thế giới!");
            return false;
        }
        
        // SỬA: Tính toán min/max chính xác
        double minX = Math.min(pos1.getX(), pos2.getX());
        double minY = Math.min(pos1.getY(), pos2.getY());
        double minZ = Math.min(pos1.getZ(), pos2.getZ());
        double maxX = Math.max(pos1.getX(), pos2.getX());
        double maxY = Math.max(pos1.getY(), pos2.getY());
        double maxZ = Math.max(pos1.getZ(), pos2.getZ());
        
        // DEBUG: Hiển thị thông tin tọa độ
        player.sendMessage("§7Debug - Pos1: " + formatLocation(pos1));
        player.sendMessage("§7Debug - Pos2: " + formatLocation(pos2));
        player.sendMessage("§7Debug - Min: (" + minX + ", " + minY + ", " + minZ + ")");
        player.sendMessage("§7Debug - Max: (" + maxX + ", " + maxY + ", " + maxZ + ")");
        
        // Tạo region
        Region region = new Region(regionName, pos1.getWorld().getName(), minX, minY, minZ, maxX, maxY, maxZ);
        regionCache.put(regionName.toLowerCase(), region);
        
        // Lưu vào config với precision cao
        String path = "regions." + regionName;
        regionsConfig.set(path + ".world", pos1.getWorld().getName());
        regionsConfig.set(path + ".min.x", minX);
        regionsConfig.set(path + ".min.y", minY);
        regionsConfig.set(path + ".min.z", minZ);
        regionsConfig.set(path + ".max.x", maxX);
        regionsConfig.set(path + ".max.y", maxY);
        regionsConfig.set(path + ".max.z", maxZ);
        
        saveConfig();
        
        // Xóa vị trí đã chọn
        playerPos1.remove(uuid);
        playerPos2.remove(uuid);
        
        player.sendMessage("§a✅ Đã tạo region '" + regionName + "' thành công!");
        
        // Hiển thị kích thước
        int sizeX = (int) Math.abs(maxX - minX) + 1;
        int sizeY = (int) Math.abs(maxY - minY) + 1;
        int sizeZ = (int) Math.abs(maxZ - minZ) + 1;
        player.sendMessage("§7Kích thước: " + sizeX + "x" + sizeY + "x" + sizeZ + " blocks");
        
        return true;
    }
    
    // SỬA: Method kiểm tra region
    public boolean isInAllowedRegion(Location location) {
        if (regionCache.isEmpty()) {
            return true; // Nếu không có region nào, cho phép tất cả
        }
        
        // Kiểm tra từng region
        for (Region region : regionCache.values()) {
            if (region.getWorldName().equalsIgnoreCase(location.getWorld().getName())) {
                // Sử dụng cả 2 method để đảm bảo
                if (region.contains(location.getX(), location.getY(), location.getZ()) ||
                    region.containsWithTolerance(location.getX(), location.getY(), location.getZ())) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    // Thêm method test region
    public void testRegion(Player player, String regionName) {
        Region region = regionCache.get(regionName.toLowerCase());
        if (region == null) {
            player.sendMessage("§c✗ Region không tồn tại!");
            return;
        }
        
        Location loc = player.getLocation();
        boolean contains = region.contains(loc.getX(), loc.getY(), loc.getZ());
        boolean containsTolerance = region.containsWithTolerance(loc.getX(), loc.getY(), loc.getZ());
        
        player.sendMessage("§6=== Test Region '" + regionName + "' ===");
        player.sendMessage("§7Vị trí hiện tại: " + formatLocation(loc));
        player.sendMessage("§7Region bounds: (" + region.getMinX() + "," + region.getMinY() + "," + region.getMinZ() + ") to (" + region.getMaxX() + "," + region.getMaxY() + "," + region.getMaxZ() + ")");
        player.sendMessage("§7Contains (exact): " + (contains ? "§aYES" : "§cNO"));
        player.sendMessage("§7Contains (tolerance): " + (containsTolerance ? "§aYES" : "§cNO"));
    }
    
    public boolean deleteRegion(String regionName) {
        if (!regionCache.containsKey(regionName.toLowerCase())) {
            return false;
        }
        
        regionCache.remove(regionName.toLowerCase());
        regionsConfig.set("regions." + regionName, null);
        saveConfig();
        
        return true;
    }
    
    private void saveConfig() {
        try {
            regionsConfig.save(regionsFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Không thể lưu file regions.yml: " + e.getMessage());
        }
    }
    
    private String formatLocation(Location loc) {
        return String.format("(%d, %d, %d) trong %s", 
            (int) loc.getX(), (int) loc.getY(), (int) loc.getZ(), loc.getWorld().getName());
    }
    
    public void reloadConfig() {
        loadConfig();
        loadRegions();
    }
    
    public Map<String, Region> getAllRegions() {
        return new HashMap<>(regionCache);
    }
}
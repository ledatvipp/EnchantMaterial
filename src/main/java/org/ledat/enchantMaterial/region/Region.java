package org.ledat.enchantMaterial.region;

import org.bukkit.Location;

public class Region {
    private final String name;
    private final String worldName;
    private final double minX, minY, minZ;
    private final double maxX, maxY, maxZ;
    
    public Region(String name, String worldName, double minX, double minY, double minZ, 
                  double maxX, double maxY, double maxZ) {
        this.name = name;
        this.worldName = worldName;
        // Đảm bảo min luôn nhỏ hơn max
        this.minX = Math.min(minX, maxX);
        this.minY = Math.min(minY, maxY);
        this.minZ = Math.min(minZ, maxZ);
        this.maxX = Math.max(minX, maxX);
        this.maxY = Math.max(minY, maxY);
        this.maxZ = Math.max(minZ, maxZ);
    }
    
    public boolean contains(double x, double y, double z) {
        // SỬA: Sử dụng <= và >= để bao gồm boundary
        // VÀ đảm bảo logic đúng cho mọi trường hợp
        return x >= minX && x <= maxX &&
               y >= minY && y <= maxY &&
               z >= minZ && z <= maxZ;
    }
    
    // Thêm method contains với tolerance cho floating point
    public boolean containsWithTolerance(double x, double y, double z) {
        double tolerance = 0.1; // Tolerance cho floating point
        return x >= (minX - tolerance) && x <= (maxX + tolerance) &&
               y >= (minY - tolerance) && y <= (maxY + tolerance) &&
               z >= (minZ - tolerance) && z <= (maxZ + tolerance);
    }
    
    // Overload method để nhận Location trực tiếp
    public boolean contains(Location location) {
        if (!location.getWorld().getName().equals(worldName)) {
            return false;
        }
        return contains(location.getX(), location.getY(), location.getZ());
    }
    
    // Method để debug - hiển thị thông tin region
    public String getInfo() {
        return String.format("Region '%s' in world '%s': (%.1f,%.1f,%.1f) to (%.1f,%.1f,%.1f)",
            name, worldName, minX, minY, minZ, maxX, maxY, maxZ);
    }
    
    // Getters
    public String getName() { return name; }
    public String getWorldName() { return worldName; }
    public double getMinX() { return minX; }
    public double getMinY() { return minY; }
    public double getMinZ() { return minZ; }
    public double getMaxX() { return maxX; }
    public double getMaxY() { return maxY; }
    public double getMaxZ() { return maxZ; }
}
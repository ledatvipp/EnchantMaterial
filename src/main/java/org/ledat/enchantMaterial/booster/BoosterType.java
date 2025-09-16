package org.ledat.enchantMaterial.booster;

import org.ledat.enchantMaterial.EnchantMaterial;
import java.util.Arrays;
import java.util.List;

public enum BoosterType {
    POINTS("Points", "Điểm", "⭐"),
    EXP("Experience", "Kinh nghiệm", "✨"),
    DROP("Drop Rate", "Tỷ lệ rơi", "💎");
    
    private final String displayName;
    private final String vietnameseName;
    private final String icon;
    
    BoosterType(String displayName, String vietnameseName, String icon) {
        this.displayName = displayName;
        this.vietnameseName = vietnameseName;
        this.icon = icon;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public String getVietnameseName() {
        return vietnameseName;
    }
    
    public String getIcon() {
        return icon;
    }
    
    public String getFormattedName() {
        return icon + " " + displayName;
    }
    
    /**
     * Parse từ string với nhiều format
     */
    public static BoosterType fromString(String input) {
        if (input == null || input.trim().isEmpty()) {
            return null;
        }
        
        String normalized = input.trim().toUpperCase();
        
        // Thử exact match trước
        try {
            return BoosterType.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            // Ignore và thử các cách khác
        }
        
        // Thử match với display name
        for (BoosterType type : values()) {
            if (type.displayName.equalsIgnoreCase(input) ||
                type.vietnameseName.equalsIgnoreCase(input) ||
                type.name().equalsIgnoreCase(input)) {
                return type;
            }
        }
        
        // Thử partial match
        for (BoosterType type : values()) {
            if (type.name().toLowerCase().contains(normalized.toLowerCase()) ||
                type.displayName.toLowerCase().contains(input.toLowerCase())) {
                return type;
            }
        }
        
        return null;
    }
    
    /**
     * Lấy tất cả types dưới dạng string list
     */
    public static List<String> getTypeNames() {
        return Arrays.stream(values())
                .map(Enum::name)
                .toList();
    }
    
    /**
     * Lấy max multiplier từ config
     */
    /**
     * Lấy max multiplier từ config
     */
    public double getMaxMultiplier() {
        EnchantMaterial plugin = EnchantMaterial.getInstance();
        if (plugin == null) {
            return getDefaultMaxMultiplier();
        }
        
        // Lấy từ permission-booster config
        String configPath = "permission-booster.max-check-levels." + this.name().toLowerCase();
        double configMax = plugin.getConfig().getDouble(configPath, getDefaultMaxMultiplier());
        
        // Trả về giá trị lớn hơn giữa config và default
        return Math.max(configMax, getDefaultMaxMultiplier());
    }
    
    /**
     * Lấy default multiplier từ config
     */
    public double getDefaultMultiplier() {
        EnchantMaterial plugin = EnchantMaterial.getInstance();
        if (plugin == null) {
            return getDefaultDefaultMultiplier();
        }
        
        String configPath = "booster.default-multipliers." + this.name().toLowerCase();
        return plugin.getConfig().getDouble(configPath, getDefaultDefaultMultiplier());
    }
    
    /**
     * Fallback max multiplier values (hard-coded backup)
     */
    private double getDefaultMaxMultiplier() {
        switch (this) {
            case POINTS:
            case EXP:
                return 100.0;
            case DROP:
                return 50.0;
            default:
                return 100.0;
        }
    }
    
    /**
     * Fallback default multiplier values (hard-coded backup)
     */
    private double getDefaultDefaultMultiplier() {
        switch (this) {
            case POINTS:
            case EXP:
                return 2.0;
            case DROP:
                return 1.5;
            default:
                return 1.0;
        }
    }
    
    /**
     * Validate multiplier cho từng loại booster (sử dụng config)
     */
    public boolean isValidMultiplier(double multiplier) {
        return multiplier >= 1.0 && multiplier <= getMaxMultiplier();
    }
}
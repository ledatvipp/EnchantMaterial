package org.ledat.enchantMaterial.booster;

import java.util.Locale;

/**
 * Xác định cách xử lý khi người chơi đã có booster cùng loại.
 */
public enum BoosterStackingStrategy {
    /**
     * Từ chối booster mới nếu đã có booster cùng loại.
     */
    REJECT_DUPLICATES("Giữ nguyên", "Không cho phép booster trùng loại"),

    /**
     * Thay thế booster hiện tại nếu booster mới mạnh hơn.
     */
    REPLACE_IF_STRONGER("Thay thế", "Chỉ thay thế khi booster mới mạnh hơn"),

    /**
     * Cộng dồn thời gian cho booster hiện tại.
     */
    EXTEND_DURATION("Cộng dồn", "Cộng thêm thời gian vào booster hiện có"),

    /**
     * Chiến lược thông minh: mạnh hơn thì thay, bằng nhau thì cộng thời gian, yếu hơn thì từ chối.
     */
    SMART("Thông minh", "Tự động quyết định tùy theo booster hiện tại");

    private final String displayName;
    private final String description;

    BoosterStackingStrategy(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public static BoosterStackingStrategy fromString(String input) {
        if (input == null || input.isBlank()) {
            return SMART;
        }

        String normalized = input.trim().toUpperCase(Locale.ROOT);
        switch (normalized) {
            case "STRICT":
            case "REJECT":
            case "NONE":
                return REJECT_DUPLICATES;
            case "REPLACE":
            case "OVERRIDE":
            case "OVERWRITE":
                return REPLACE_IF_STRONGER;
            case "EXTEND":
            case "STACK":
            case "ADD":
                return EXTEND_DURATION;
            case "SMART":
            default:
                return SMART;
        }
    }
}

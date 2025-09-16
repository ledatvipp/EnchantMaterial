package org.ledat.enchantMaterial.booster;

/**
 * Danh sách các lý do khiến booster không thể kích hoạt hoặc xử lý thành công.
 */
public enum BoosterFailureReason {
    PLUGIN_SHUTTING_DOWN("Plugin đang tắt, không thể xử lý booster mới."),
    PLAYER_OFFLINE("Người chơi không trực tuyến."),
    INVALID_REQUEST("Dữ liệu booster không hợp lệ."),
    INVALID_MULTIPLIER("Hệ số nhân không hợp lệ."),
    INVALID_DURATION("Thời lượng booster không hợp lệ."),
    LIMIT_REACHED("Người chơi đã đạt giới hạn booster."),
    DUPLICATE_TYPE("Người chơi đã có booster loại này."),
    WEAKER_THAN_CURRENT("Booster mới yếu hơn booster hiện tại."),
    INTERNAL_ERROR("Đã xảy ra lỗi nội bộ.");

    private final String defaultMessage;

    BoosterFailureReason(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}

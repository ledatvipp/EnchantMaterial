package org.ledat.enchantMaterial.booster;

/**
 * Đại diện cho nguồn gốc của một booster để phục vụ mục đích hiển thị và logging.
 */
public enum BoosterSource {
    ADMIN_COMMAND("Lệnh quản trị"),
    REWARD_SYSTEM("Phần thưởng"),
    GLOBAL_EVENT("Sự kiện toàn server"),
    PLAYER_PURCHASE("Cửa hàng"),
    PERSISTED("Khôi phục"),
    LEGACY("Kế thừa"),
    API("Từ API"),
    UNKNOWN("Không rõ");

    private final String displayName;

    BoosterSource(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}

EnchantMaterial là plugin Minecraft cho phép người chơi kiếm điểm bằng cách đào 
các loại block đặc biệt với công cụ có enchantment phù hợp. Plugin bao gồm hệ thống 
level, booster, rebirth và nhiều tính năng khác.

⚙️ Yêu Cầu Hệ Thống
- Minecraft: 1.20+
- Dependencies: Vault (bắt buộc)
- Soft Dependencies: PlaceholderAPI (tùy chọn)

📋 Lệnh Cơ Bản
/em level                    - Xem thông tin level của bạn
/em rewards                  - Mở GUI phần thưởng level
/em rebirth                  - Mở GUI chuyển sinh
/em chuyensinh              - Mở GUI chuyển sinh (alias)
/em permbooster             - Xem thông tin booster từ permission

🔧 Lệnh Admin
/em reload                   - Reload plugin config
/em add <player> <points>    - Thêm điểm cho người chơi
/em give <player> <booster> <type> <multiplier> <time> - Tặng booster
/em booster <action>         - Quản lý booster
/em admin allow <action>     - Quản lý region cho phép đào

🏆 Lệnh Booster
/em booster list             - Xem danh sách booster
/em booster start <type> <multiplier> <time> [player] - Bật booster
/em booster stop <type> [player] - Tắt booster
/em booster info [player]    - Xem thông tin booster

🗺️ Lệnh Region (Admin)
/em admin allow pos1         - Đặt vị trí 1 cho region
/em admin allow pos2         - Đặt vị trí 2 cho region
/em admin allow create <name> - Tạo region mới
/em admin allow delete <name> - Xóa region
/em admin allow list         - Xem danh sách region
/em admin allow test         - Test vị trí hiện tại
/em admin allow debug        - Bật/tắt debug mode

🏅 Permissions (Quyền)
enchantmaterial.admin        # Quyền admin (bypass mọi hạn chế)
enchantmaterial.rewards      # Quyền sử dụng GUI phần thưởng

📊 PlaceholderAPI

🎯 Player Stats
%enchantmaterial_points%           # Điểm hiện tại
%enchantmaterial_level%            # Level hiện tại
%enchantmaterial_level_next%       # Level tiếp theo
%enchantmaterial_progress%         # Tiến độ (100/600)
%enchantmaterial_progress_bar%     # Thanh tiến độ
%enchantmaterial_progress_percent% # Phần trăm tiến độ
%enchantmaterial_percent%          # Phần trăm (50%)
%enchantmaterial_percent_amount%   # Số lượng/tổng (100/600)

⚡ Multipliers
%enchantmaterial_fortune_multiplier% # Hệ số fortune
%enchantmaterial_drop_multiplier%    # Hệ số drop

🚀 Boosters
%enchantmaterial_booster_points%     # Booster điểm (x2.0)
%enchantmaterial_booster_drop%       # Booster drop (x1.5)
%enchantmaterial_booster_exp%        # Booster exp (x3.0)
%enchantmaterial_booster_time_points% # Thời gian còn lại booster điểm
%enchantmaterial_booster_time_drop%   # Thời gian còn lại booster drop
%enchantmaterial_booster_time_exp%    # Thời gian còn lại booster exp
%enchantmaterial_booster_summary%     # Tóm tắt tất cả booster
%enchantmaterial_booster_active_count% # Số lượng booster đang hoạt động

🔄 Rebirth
%enchantmaterial_rebirth_level%        # Level chuyển sinh hiện tại
%enchantmaterial_rebirth_next_level%   # Level chuyển sinh tiếp theo
%enchantmaterial_rebirth_max_level%    # Level chuyển sinh tối đa
%enchantmaterial_rebirth_last_time%    # Lần chuyển sinh cuối
%enchantmaterial_rebirth_can_rebirth%  # Có thể chuyển sinh (true/false)
%enchantmaterial_rebirth_required_level% # Level yêu cầu để chuyển sinh

🎮 Cách Chơi
### 1. 🔨 Đào Block
- Sử dụng công cụ có enchantment phù hợp
- Đào các block trong whitelist (xem config.yml)
- Phải ở trong region được phép (nếu có)
- Nhận điểm, exp và items
### 2. 📈 Hệ Thống Level
- Tích lũy điểm để lên level
- Mỗi level có phần thưởng riêng
- Sử dụng /em rewards để nhận thưởng
### 3. 🔄 Chuyển Sinh (Rebirth)
- Đạt level yêu cầu để chuyển sinh
- Reset level về 1 nhưng giữ lại ưu đãi
- Tăng hệ số kiếm điểm
### 4. ⚡ Booster System
- Points Booster: Tăng điểm kiếm được
- Drop Booster: Tăng items rơi
- EXP Booster: Tăng kinh nghiệm


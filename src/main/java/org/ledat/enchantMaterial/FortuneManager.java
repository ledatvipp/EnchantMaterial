package org.ledat.enchantMaterial;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;

/**
 * FortuneManager tối ưu:
 * - Đọc cấu hình 1 lần vào bộ nhớ (reload khi cần)
 * - Cache multiplier theo người chơi với TTL
 * - Tương thích key cũ/mới trong config (prefix & default)
 */
public class FortuneManager {

    private final EnchantMaterial plugin;

    /** Cache kết quả multiplier theo người chơi (TTL) */
    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    /** Bản đồ tier -> multiplier (đọc từ config fortune.values) */
    private volatile Map<String, Double> tierMultipliers = new HashMap<>();

    /** Prefix permission (ví dụ: enchantmaterial.fortune.) */
    private volatile String permPrefix = "enchantmaterial.fortune.";

    /** Multiplier mặc định nếu không có tier nào khớp */
    private volatile double defaultMultiplier = 1.0;

    /** TTL cache (giây) */
    private volatile int ttlSeconds = 15;

    private static final class CacheEntry {
        final double value;
        final long timeMillis;
        CacheEntry(double value, long timeMillis) {
            this.value = value;
            this.timeMillis = timeMillis;
        }
    }

    public FortuneManager(EnchantMaterial plugin) {
        this.plugin = plugin;
        reload(); // đọc config ngay khi khởi tạo
    }

    /**
     * Đọc lại cấu hình vào bộ nhớ (gọi khi /em reload).
     * - Đọc fortune.values (tiers) -> tierMultipliers
     * - Đọc prefix & default
     * - Đọc TTL từ performance.cache-ttl.fortune-seconds
     */
    public void reload() {
        // TTL
        this.ttlSeconds = plugin.getConfig().getInt("performance.cache-ttl.fortune-seconds", 15);

        // Prefix: hỗ trợ cả "permission_prefix" và "permission-prefix"
        String p1 = plugin.getConfig().getString("fortune.permission_prefix", null);
        String p2 = plugin.getConfig().getString("fortune.permission-prefix", null);
        this.permPrefix = p1 != null ? p1 : (p2 != null ? p2 : "enchantmaterial.fortune.");

        // Đọc fortune.values
        Map<String, Double> map = new HashMap<>();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("fortune.values");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                double v = sec.getDouble(key, 1.0);
                map.put(key.toLowerCase(Locale.ROOT), v);
            }
        }
        this.tierMultipliers = map;

        // default: ưu tiên fortune.default_multiplier; fallback fortune.values.default; cuối cùng 1.0
        double def = plugin.getConfig().getDouble("fortune.default_multiplier",
                map.getOrDefault("default", 1.0));
        this.defaultMultiplier = def;

        // Sau khi reload nên xoá cache để đảm bảo giá trị mới được áp dụng
        invalidateAll();
    }

    /**
     * Trả về hệ số multiplier dựa trên permission (có cache TTL).
     */
    public double getMultiplier(Player player) {
        long now = System.currentTimeMillis();

        // 1) Cache hit
        CacheEntry ce = cache.get(player.getUniqueId());
        if (ce != null && (now - ce.timeMillis) <= ttlSeconds * 1000L) {
            return ce.value;
        }

        // 2) Tính lại multiplier
        double maxMul = this.defaultMultiplier;

        // Quét các tier cấu hình — lấy giá trị lớn nhất mà player có permission
        // Ví dụ: permPrefix="enchantmaterial.fortune.", tier "vip", "mvp", "elite", ...
        // -> kiểm tra hasPermission("enchantmaterial.fortune.vip"), ...
        for (Map.Entry<String, Double> e : tierMultipliers.entrySet()) {
            String tier = e.getKey();
            if ("default".equalsIgnoreCase(tier)) continue; // đã xử lý ở defaultMultiplier
            String perm = this.permPrefix + tier;
            if (player.hasPermission(perm)) {
                double v = e.getValue();
                if (v > maxMul) maxMul = v;
            }
        }

        // 3) Ghi cache
        CacheEntry ne = new CacheEntry(maxMul, now);
        cache.put(player.getUniqueId(), ne);
        return maxMul;
    }

    /** Hệ số drop hiện tại = multiplier fortune (giữ tương thích API cũ) */
    public double getDropMultiplier(Player player) {
        return getMultiplier(player);
    }

    /** Boost điểm theo multiplier fortune (giữ tương thích API cũ) */
    public double boostPoints(Player player, double basePoints) {
        return basePoints * getMultiplier(player);
    }

    /** Invalidate cache cho 1 người chơi (gọi khi permission thay đổi) */
    public void invalidate(UUID uuid) {
        cache.remove(uuid);
    }

    /** Xoá toàn bộ cache (gọi khi reload) */
    public void invalidateAll() {
        cache.clear();
    }

    /** Tuỳ chọn: warmup cache khi người chơi join (giảm lag tick đầu) */
    public void warmup(Player player) {
        getMultiplier(player);
    }
}

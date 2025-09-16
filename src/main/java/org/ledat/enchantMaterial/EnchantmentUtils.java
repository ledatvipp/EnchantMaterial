package org.ledat.enchantMaterial;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;

import java.util.List;
import java.util.ArrayList;

public class EnchantmentUtils {

    // Các hàm khác như hasEnchantment, addEnchantment đã định nghĩa ở trên...

    /**
     * Lấy cấp độ của một enchantment trên dụng cụ.
     *
     * @param item        Item cần kiểm tra.
     * @param displayType Loại enchantment (hiển thị).
     * @return Cấp độ của enchantment hoặc 0 nếu không có.
     */
    public static int getEnchantmentLevel(ItemStack item, String displayType) {
        if (item == null || !item.hasItemMeta()) return 0;

        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) return 0;

        List<String> lore = meta.getLore();
        if (lore == null) return 0;

        for (String line : lore) {
            if (line.contains(displayType)) {
                String[] parts = line.split(" ");
                if (parts.length > 1) {
                    try {
                        return Integer.parseInt(parts[1].trim());
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                }
            }
        }
        return 0;
    }

    /**
     * Kiểm tra nếu một item có enchantment cụ thể.
     *
     * @param item        Item cần kiểm tra.
     * @param displayType Loại enchantment (hiển thị).
     * @return True nếu item có enchantment, ngược lại là false.
     */
    public static boolean hasEnchantment(ItemStack item, String displayType) {
        if (item == null || !item.hasItemMeta()) return false;

        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) return false;

        List<String> lore = meta.getLore();
        if (lore == null) return false;

        for (String line : lore) {
            if (line.contains(displayType)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Kiểm tra xem item có chứa bất kỳ chuỗi hiển thị nào trong danh sách yêu cầu hay không.
     * Phương thức này duyệt lore của item và trả về true nếu tìm thấy ít nhất một chuỗi khớp.
     *
     * @param item     Item cần kiểm tra
     * @param displays Tập hợp các chuỗi hiển thị cần có
     * @return true nếu item có chứa bất kỳ chuỗi hiển thị nào, ngược lại false
     */
    public static boolean hasAnyDisplay(ItemStack item, java.util.Set<String> displays) {
        if (item == null || displays == null || displays.isEmpty()) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) return false;
        java.util.List<String> lore = meta.getLore();
        if (lore == null) return false;
        outer:
        for (String line : lore) {
            if (line == null) continue;
            for (String d : displays) {
                if (line.contains(d)) {
                    return true;
                }
            }
        }
        return false;
    }
}
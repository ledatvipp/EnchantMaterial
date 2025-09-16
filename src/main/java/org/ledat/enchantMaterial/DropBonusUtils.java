package org.ledat.enchantMaterial;

import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DropBonusUtils {

    /**
     * Lấy cấp độ bổ trợ từ dòng lore.
     */
    public static int getBonusLevel(ItemStack tool) {
        if (tool == null || !tool.hasItemMeta()) return 1;

        List<String> loreList = tool.getItemMeta().getLore();
        if (loreList == null) return 1;

        for (String lore : loreList) {
            String raw = ChatColor.stripColor(lore);
            if (raw.contains("Bổ trợ:")) {
                Matcher matcher = Pattern.compile("(\\d+)$").matcher(raw);
                if (matcher.find()) {
                    try {
                        return Integer.parseInt(matcher.group(1));
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return 1;
    }
}

package org.ledat.enchantMaterial;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

public class ConfigManager {

    public static String getMessage(String key) {
        return EnchantMaterial.getInstance().getConfig().getString("messages." + key, "Message not found.");
    }

    // Test check không bổ trợ
    public static boolean isEnchantApplicable(List<String> lore, Material blockType) {
        ConfigurationSection section = EnchantMaterial.getInstance().getConfig().getConfigurationSection("enchantments");
        if (section == null) return false;

        for (String enchantKey : section.getKeys(false)) {
            List<String> materials = section.getStringList(enchantKey + ".material");
            if (materials.contains(blockType.name())) {
                String displayType = section.getString(enchantKey + ".display_type");
                return lore.stream().anyMatch(line -> line.contains(displayType));
            }
        }
        return false;
    }

    public static String getEnchantDisplayType(String enchantKey) {
        return EnchantMaterial.getInstance().getConfig().getString("enchantments." + enchantKey + ".display_type");
    }
}
package org.ledat.enchantMaterial;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class EnchantMaterialTabCompleter implements TabCompleter {

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("enchantmaterial")) return null;

        List<String> completions = new ArrayList<>();

        // First argument - main subcommands với kiểm tra quyền
        if (args.length == 1) {
            // Lệnh cơ bản cho tất cả người chơi
            completions.add("level");
            completions.add("rewards");
            completions.add("rebirth");
            completions.add("chuyensinh");
            
            // Lệnh booster cho người chơi
            if (sender.hasPermission("enchantmaterial.booster.use")) {
                completions.add("booster");
            }
            
            // Lệnh permission booster
            if (sender.hasPermission("enchantmaterial.permissionbooster.use")) {
                completions.add("permissionbooster");
            }
            
            // Lệnh admin
            if (sender.hasPermission("enchantmaterial.admin")) {
                completions.add("add");
                completions.add("reload");
                completions.add("give");
                completions.add("debug");
            }
            
            return filterPartial(args[0], completions);
        }

        // Handle 'reload' subcommand - admin only
        if (args[0].equalsIgnoreCase("reload") && sender.hasPermission("enchantmaterial.admin")) {
            if (args.length == 2) {
                return filterPartial(args[1], Arrays.asList(
                    "all", "config", "booster", "rewards", "rebirth", "luckyblock", "levelsystem"
                ));
            }
        }

        // Handle 'booster' subcommand với quyền chi tiết
        if (args[0].equalsIgnoreCase("booster")) {
            if (args.length == 2) {
                // Lệnh cơ bản cho người chơi
                if (sender.hasPermission("enchantmaterial.booster.use")) {
                    completions.add("stop");
                }
                
                // Lệnh admin
                if (sender.hasPermission("enchantmaterial.booster.admin")) {
                    completions.add("start");
                    completions.add("list");
                    completions.add("clear");
                }
                
                return filterPartial(args[1], completions);
            }

            // Handle 'booster start' - chỉ admin
            if (args[1].equalsIgnoreCase("start") && sender.hasPermission("enchantmaterial.booster.admin")) {
                if (args.length == 3) {
                    // Player names hoặc "global" cho global booster
                    completions.addAll(getOnlinePlayerNames());
                    completions.add("global");
                    return filterPartial(args[2], completions);
                } else if (args.length == 4) {
                    // Booster types
                    return filterPartial(args[3], Arrays.asList("points", "drop", "exp"));
                } else if (args.length == 5) {
                    // Multiplier suggestions
                    return filterPartial(args[4], Arrays.asList("1.5", "2.0", "2.5", "3.0", "5.0"));
                } else if (args.length == 6) {
                    // Duration suggestions
                    return filterPartial(args[5], Arrays.asList("5m", "10m", "30m", "1h", "2h"));
                }
            }

            // Handle 'booster stop'
            if (args[1].equalsIgnoreCase("stop")) {
                if (args.length == 3) {
                    // Booster types
                    return filterPartial(args[2], Arrays.asList("points", "drop", "exp", "all"));
                } else if (args.length == 4 && sender.hasPermission("enchantmaterial.booster.admin")) {
                    // Player names cho admin
                    completions.addAll(getOnlinePlayerNames());
                    completions.add("global");
                    return filterPartial(args[3], completions);
                }
            }

            // Handle 'booster list' - admin only
            if (args[1].equalsIgnoreCase("list") && sender.hasPermission("enchantmaterial.booster.admin")) {
                if (args.length == 3) {
                    completions.addAll(getOnlinePlayerNames());
                    completions.add("global");
                    return filterPartial(args[2], completions);
                }
            }
        }

        if (args[0].equalsIgnoreCase("rebirth") || args[0].equalsIgnoreCase("chuyensinh")) {
            if (args.length == 2 && sender.hasPermission("enchantmaterial.admin")) {
                return filterPartial(args[1], List.of("edit"));
            }
        }

        // Handle 'permissionbooster' subcommand
        if (args[0].equalsIgnoreCase("permissionbooster")) {
            if (args.length == 2 && sender.hasPermission("enchantmaterial.permissionbooster.admin")) {
                // Admin có thể xem thông tin của player khác
                return filterPartial(args[1], getOnlinePlayerNames());
            }
        }

        // Handle 'give' subcommand - admin only
        if (args[0].equalsIgnoreCase("give") && sender.hasPermission("enchantmaterial.admin")) {
            if (args.length == 2) {
                return filterPartial(args[1], getOnlinePlayerNames());
            } else if (args.length == 3) {
                return filterPartial(args[2], Arrays.asList("level", "points", "score"));
            } else if (args.length == 4) {
                return Arrays.asList("1", "5", "10", "50", "100");
            }
        }

        // Handle 'add' subcommand - admin only
        if (args[0].equalsIgnoreCase("add") && sender.hasPermission("enchantmaterial.admin")) {
            if (args.length == 2) {
                ConfigurationSection section = EnchantMaterial.getInstance().getConfig().getConfigurationSection("enchantments");
                if (section != null) {
                    return filterPartial(args[1], new ArrayList<>(section.getKeys(false)));
                }
            } else if (args.length == 3) {
                String enchantKey = args[1];
                int maxLevel = EnchantMaterial.getInstance().getConfig().getInt("enchantments." + enchantKey + ".max_level", 10);
                
                List<String> levels = new ArrayList<>();
                for (int i = 1; i <= Math.min(maxLevel, 10); i++) {
                    levels.add(String.valueOf(i));
                }
                return filterPartial(args[2], levels);
            }
        }

        // Handle 'debug' subcommand - admin only
        if (args[0].equalsIgnoreCase("debug") && sender.hasPermission("enchantmaterial.admin")) {
            if (args.length == 2) {
                return filterPartial(args[1], Arrays.asList("items", "boosters", "rebirth", "database"));
            }
        }

        return Collections.emptyList();
    }

    /**
     * Filter completions based on partial input
     */
    private List<String> filterPartial(String arg, List<String> options) {
        if (arg == null || arg.isEmpty()) {
            return options;
        }

        return options.stream()
                .filter(opt -> opt.toLowerCase().startsWith(arg.toLowerCase()))
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Get list of online player names
     */
    private List<String> getOnlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .sorted()
                .collect(Collectors.toList());
    }
}

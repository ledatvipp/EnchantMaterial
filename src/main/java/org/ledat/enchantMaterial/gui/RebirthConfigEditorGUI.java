package org.ledat.enchantMaterial.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.ledat.enchantMaterial.EnchantMaterial;
import org.ledat.enchantMaterial.rebirth.RebirthManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class RebirthConfigEditorGUI implements Listener {
    private static final String LEVEL_SELECTOR_TITLE = ChatColor.translateAlternateColorCodes('&', "&6&lRebirth Config - Levels");
    private static final String LEVEL_EDITOR_TITLE_FORMAT = ChatColor.translateAlternateColorCodes('&', "&e&lChỉnh Rebirth Cấp %level%");
    private static final String REQUIRED_ITEMS_TITLE_FORMAT = ChatColor.translateAlternateColorCodes('&', "&a&lVật Phẩm Cấp %level%");

    private static final int SLOT_REQUIRED_LEVEL = 10;
    private static final int SLOT_REQUIRED_MONEY = 12;
    private static final int SLOT_SUCCESS_RATE = 14;
    private static final int SLOT_REQUIRED_ITEMS = 16;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_SAVE = 49;

    private static final int SLOT_ITEMS_BACK = 45;
    private static final int SLOT_ITEMS_ADD = 52;

    private final EnchantMaterial plugin;
    private final RebirthManager rebirthManager;
    private final File rebirthFile;
    private YamlConfiguration config;

    private final Map<UUID, Map<Integer, Integer>> levelSlotMap = new HashMap<>();
    private final Map<UUID, Map<Integer, Integer>> requiredItemSlotMap = new HashMap<>();
    private final Map<UUID, Integer> selectedLevel = new HashMap<>();
    private final Map<UUID, EditRequest> pendingEdits = new HashMap<>();

    public RebirthConfigEditorGUI(EnchantMaterial plugin, RebirthManager rebirthManager) {
        this.plugin = plugin;
        this.rebirthManager = rebirthManager;
        this.rebirthFile = new File(plugin.getDataFolder(), "rebirth.yml");
        reloadInternalConfig();
    }

    public void openLevelSelector(Player player) {
        reloadInternalConfig();

        Inventory inventory = Bukkit.createInventory(null, 54, LEVEL_SELECTOR_TITLE);
        fillBackground(inventory);

        ItemStack info = createItem(Material.BOOK, ChatColor.GOLD + "Chỉnh sửa chuyển sinh", Arrays.asList(
                ChatColor.YELLOW + "Chọn cấp để chỉnh sửa yêu cầu",
                ChatColor.YELLOW + "Shift-click vật phẩm để xóa",
                ChatColor.GRAY + "Lưu ý: mọi chỉnh sửa sẽ được lưu ngay"
        ));
        inventory.setItem(4, info);

        Map<Integer, Integer> slotMapping = new HashMap<>();
        List<Integer> levelSlots = getContentSlots();

        ConfigurationSection levelsSection = config.getConfigurationSection("rebirth.rebirth.levels");
        if (levelsSection != null) {
            List<Integer> levels = levelsSection.getKeys(false).stream()
                    .map(key -> {
                        try {
                            return Integer.parseInt(key);
                        } catch (NumberFormatException ignored) {
                            return null;
                        }
                    })
                    .filter(level -> level != null)
                    .sorted()
                    .collect(Collectors.toList());

            for (int i = 0; i < levels.size() && i < levelSlots.size(); i++) {
                int level = levels.get(i);
                int slot = levelSlots.get(i);
                inventory.setItem(slot, createLevelItem(level));
                slotMapping.put(slot, level);
            }
        }

        inventory.setItem(49, createItem(Material.BARRIER, ChatColor.RED + "Đóng", List.of(ChatColor.GRAY + "Đóng giao diện")));

        levelSlotMap.put(player.getUniqueId(), slotMapping);
        player.openInventory(inventory);
    }

    private ItemStack createLevelItem(int level) {
        String basePath = "rebirth.rebirth.levels." + level;
        int requiredLevel = config.getInt(basePath + ".required-level", 0);
        double requiredMoney = config.getDouble(basePath + ".required-money", 0);
        double successRate = config.getDouble(basePath + ".success-rate", 100);
        int itemCount = config.getMapList(basePath + ".required-items").size();

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Level yêu cầu: " + ChatColor.WHITE + requiredLevel);
        lore.add(ChatColor.GRAY + "Tiền yêu cầu: " + ChatColor.WHITE + formatMoney(requiredMoney));
        lore.add(ChatColor.GRAY + "Tỉ lệ thành công: " + ChatColor.WHITE + successRate + "%");
        lore.add(ChatColor.GRAY + "Vật phẩm yêu cầu: " + ChatColor.WHITE + itemCount);
        lore.add(" ");
        lore.add(ChatColor.YELLOW + "Click để chỉnh sửa cấp này");

        return createItem(Material.ENCHANTED_BOOK, ChatColor.GOLD + "Cấp chuyển sinh " + level, lore);
    }

    private void fillBackground(Inventory inventory) {
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
    }

    private List<Integer> getContentSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int row = 1; row <= 4; row++) {
            for (int col = 1; col <= 7; col++) {
                slots.add(row * 9 + col);
            }
        }
        return slots;
    }

    private ItemStack createItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (name != null) {
                meta.setDisplayName(name);
            }
            if (lore != null) {
                meta.setLore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private String formatMoney(double value) {
        if (Math.abs(value - Math.round(value)) < 0.0001D) {
            return String.format(Locale.US, "%,.0f", value);
        }
        return String.format(Locale.US, "%,.2f", value);
    }

    private String getLevelEditorTitle(int level) {
        return LEVEL_EDITOR_TITLE_FORMAT.replace("%level%", String.valueOf(level));
    }

    private String getRequiredItemsTitle(int level) {
        return REQUIRED_ITEMS_TITLE_FORMAT.replace("%level%", String.valueOf(level));
    }

    public void openLevelEditor(Player player, int level) {
        reloadInternalConfig();
        selectedLevel.put(player.getUniqueId(), level);

        Inventory inventory = Bukkit.createInventory(null, 54, getLevelEditorTitle(level));
        fillBackground(inventory);

        String basePath = "rebirth.rebirth.levels." + level;

        int requiredLevel = config.getInt(basePath + ".required-level", 0);
        double requiredMoney = config.getDouble(basePath + ".required-money", 0);
        double successRate = config.getDouble(basePath + ".success-rate", 100);
        int itemCount = config.getMapList(basePath + ".required-items").size();

        inventory.setItem(SLOT_REQUIRED_LEVEL, createItem(
                Material.EXPERIENCE_BOTTLE,
                ChatColor.YELLOW + "Required Level",
                Arrays.asList(
                        ChatColor.GRAY + "Hiện tại: " + ChatColor.WHITE + requiredLevel,
                        " ",
                        ChatColor.GOLD + "Click" + ChatColor.GRAY + " để nhập level mới"
                )
        ));

        inventory.setItem(SLOT_REQUIRED_MONEY, createItem(
                Material.GOLD_INGOT,
                ChatColor.YELLOW + "Required Money",
                Arrays.asList(
                        ChatColor.GRAY + "Hiện tại: " + ChatColor.WHITE + formatMoney(requiredMoney),
                        " ",
                        ChatColor.GOLD + "Click" + ChatColor.GRAY + " để nhập số tiền mới"
                )
        ));

        inventory.setItem(SLOT_SUCCESS_RATE, createItem(
                Material.NETHER_STAR,
                ChatColor.YELLOW + "Success Rate",
                Arrays.asList(
                        ChatColor.GRAY + "Hiện tại: " + ChatColor.WHITE + successRate + "%",
                        " ",
                        ChatColor.GOLD + "Click" + ChatColor.GRAY + " để nhập tỉ lệ mới (0-100)"
                )
        ));

        inventory.setItem(SLOT_REQUIRED_ITEMS, createItem(
                Material.CHEST,
                ChatColor.YELLOW + "Required Items",
                Arrays.asList(
                        ChatColor.GRAY + "Số vật phẩm: " + ChatColor.WHITE + itemCount,
                        " ",
                        ChatColor.GOLD + "Click" + ChatColor.GRAY + " để chỉnh sửa danh sách"
                )
        ));

        inventory.setItem(SLOT_BACK, createItem(Material.ARROW, ChatColor.YELLOW + "Quay lại", List.of(ChatColor.GRAY + "Trở lại danh sách cấp")));
        inventory.setItem(SLOT_SAVE, createItem(Material.EMERALD_BLOCK, ChatColor.GREEN + "Lưu & Reload", List.of(
                ChatColor.GRAY + "Lưu thay đổi vào rebirth.yml",
                ChatColor.GRAY + "và reload cấu hình"
        )));

        player.openInventory(inventory);
    }

    private List<Map<String, Object>> getRequiredItems(int level) {
        String path = "rebirth.rebirth.levels." + level + ".required-items";
        List<Map<?, ?>> rawList = config.getMapList(path);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<?, ?> raw : rawList) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            result.add(copy);
        }
        return result;
    }

    private void setRequiredItems(int level, List<Map<String, Object>> items) {
        String path = "rebirth.rebirth.levels." + level + ".required-items";
        config.set(path, items);
    }

    public void openRequiredItemsEditor(Player player, int level) {
        reloadInternalConfig();
        selectedLevel.put(player.getUniqueId(), level);

        Inventory inventory = Bukkit.createInventory(null, 54, getRequiredItemsTitle(level));
        fillBackground(inventory);

        Map<Integer, Integer> slotMap = new HashMap<>();
        List<Integer> slots = getContentSlots();
        List<Map<String, Object>> items = getRequiredItems(level);

        for (int i = 0; i < items.size() && i < slots.size(); i++) {
            Map<String, Object> data = items.get(i);
            int slot = slots.get(i);
            inventory.setItem(slot, createRequiredItemDisplay(data));
            slotMap.put(slot, i);
        }

        inventory.setItem(SLOT_ITEMS_BACK, createItem(Material.ARROW, ChatColor.YELLOW + "Quay lại", List.of(ChatColor.GRAY + "Trở lại chỉnh sửa cấp")));
        inventory.setItem(SLOT_ITEMS_ADD, createItem(Material.LIME_STAINED_GLASS_PANE, ChatColor.GREEN + "Thêm vật phẩm", Arrays.asList(
                ChatColor.GRAY + "Cầm vật phẩm trên tay",
                ChatColor.GRAY + "Sau đó click để thêm vào danh sách"
        )));

        requiredItemSlotMap.put(player.getUniqueId(), slotMap);
        player.openInventory(inventory);
    }

    private ItemStack createRequiredItemDisplay(Map<String, Object> data) {
        String materialName = String.valueOf(data.getOrDefault("material", "STONE"));
        Material material;
        try {
            material = Material.valueOf(materialName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            material = Material.STONE;
        }

        int amount = 1;
        Object rawAmount = data.get("amount");
        if (rawAmount instanceof Number) {
            amount = ((Number) rawAmount).intValue();
        }
        if (amount <= 0) {
            amount = 1;
        }

        ItemStack stack = new ItemStack(material);
        stack.setAmount(Math.min(amount, stack.getMaxStackSize()));

        String customName = data.containsKey("custom-name") ? String.valueOf(data.get("custom-name")) : null;
        List<String> customLore = new ArrayList<>();
        if (data.containsKey("custom-lore")) {
            Object loreObject = data.get("custom-lore");
            if (loreObject instanceof List<?>) {
                for (Object line : (List<?>) loreObject) {
                    customLore.add(String.valueOf(line));
                }
            }
        }

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Material: " + ChatColor.WHITE + materialName);
        lore.add(ChatColor.GRAY + "Số lượng: " + ChatColor.WHITE + amount);
        if (customName != null && !customName.isEmpty()) {
            lore.add(ChatColor.GRAY + "Tên: " + ChatColor.translateAlternateColorCodes('&', customName));
        }
        if (!customLore.isEmpty()) {
            lore.add(ChatColor.GRAY + "Lore:");
            for (String line : customLore) {
                lore.add(ChatColor.WHITE + " - " + ChatColor.translateAlternateColorCodes('&', line));
            }
        }
        lore.add(" ");
        lore.add(ChatColor.RED + "Shift-Click" + ChatColor.GRAY + " để xóa vật phẩm này");

        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (customName != null && !customName.isEmpty()) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', customName));
            } else {
                meta.setDisplayName(ChatColor.GOLD + materialName);
            }
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }

        return stack;
    }

    private void reloadInternalConfig() {
        if (!rebirthFile.exists()) {
            plugin.saveResource("rebirth.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(rebirthFile);
    }

    private void saveAndReload(Player player, String successMessage) {
        try {
            config.save(rebirthFile);
            rebirthManager.reloadConfig();
            reloadInternalConfig();
            if (successMessage != null && !successMessage.isEmpty()) {
                player.sendMessage(successMessage);
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Không thể lưu rebirth.yml: " + e.getMessage());
            player.sendMessage(ChatColor.RED + "Không thể lưu rebirth.yml: " + e.getMessage());
        }
    }

    private void requestEdit(Player player, int level, EditField field, String prompt) {
        pendingEdits.put(player.getUniqueId(), new EditRequest(level, field));
        player.closeInventory();
        player.sendMessage(ChatColor.YELLOW + prompt);
        player.sendMessage(ChatColor.GRAY + "Gõ 'cancel' để hủy chỉnh sửa.");
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Inventory topInventory = event.getView().getTopInventory();
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(topInventory)) {
            return;
        }

        String title = event.getView().getTitle();
        UUID uuid = player.getUniqueId();

        if (title.equals(LEVEL_SELECTOR_TITLE)) {
            event.setCancelled(true);
            if (event.getSlot() == 49) {
                player.closeInventory();
                return;
            }
            Map<Integer, Integer> mapping = levelSlotMap.get(uuid);
            if (mapping != null && mapping.containsKey(event.getSlot())) {
                int level = mapping.get(event.getSlot());
                Bukkit.getScheduler().runTask(plugin, () -> openLevelEditor(player, level));
            }
            return;
        }

        Integer level = selectedLevel.get(uuid);
        if (level == null) {
            return;
        }

        if (title.equals(getLevelEditorTitle(level))) {
            event.setCancelled(true);
            switch (event.getSlot()) {
                case SLOT_REQUIRED_LEVEL -> requestEdit(player, level, EditField.REQUIRED_LEVEL,
                        "Nhập required-level mới (số nguyên >= 0):");
                case SLOT_REQUIRED_MONEY -> requestEdit(player, level, EditField.REQUIRED_MONEY,
                        "Nhập required-money mới (số >= 0):");
                case SLOT_SUCCESS_RATE -> requestEdit(player, level, EditField.SUCCESS_RATE,
                        "Nhập tỉ lệ thành công mới (0-100):");
                case SLOT_REQUIRED_ITEMS -> Bukkit.getScheduler().runTask(plugin, () -> openRequiredItemsEditor(player, level));
                case SLOT_BACK -> Bukkit.getScheduler().runTask(plugin, () -> openLevelSelector(player));
                case SLOT_SAVE -> {
                    saveAndReload(player, ChatColor.GREEN + "Đã lưu và reload rebirth.yml!");
                    Bukkit.getScheduler().runTaskLater(plugin, () -> openLevelEditor(player, level), 1L);
                }
                default -> {
                }
            }
            return;
        }

        if (title.equals(getRequiredItemsTitle(level))) {
            event.setCancelled(true);

            if (event.getSlot() == SLOT_ITEMS_BACK) {
                Bukkit.getScheduler().runTask(plugin, () -> openLevelEditor(player, level));
                return;
            }

            if (event.getSlot() == SLOT_ITEMS_ADD) {
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand == null || hand.getType() == Material.AIR) {
                    player.sendMessage(ChatColor.RED + "Hãy cầm vật phẩm trên tay để thêm!");
                    return;
                }

                Map<String, Object> itemData = createDataFromItem(hand);
                List<Map<String, Object>> items = getRequiredItems(level);
                items.add(itemData);
                setRequiredItems(level, items);
                saveAndReload(player, ChatColor.GREEN + "Đã thêm vật phẩm yêu cầu cho cấp " + level + ".");
                Bukkit.getScheduler().runTaskLater(plugin, () -> openRequiredItemsEditor(player, level), 1L);
                return;
            }

            Map<Integer, Integer> slotMap = requiredItemSlotMap.get(uuid);
            if (slotMap != null && slotMap.containsKey(event.getSlot())) {
                if (!event.isShiftClick()) {
                    player.sendMessage(ChatColor.YELLOW + "Shift-Click để xóa vật phẩm khỏi danh sách.");
                    return;
                }
                int index = slotMap.get(event.getSlot());
                List<Map<String, Object>> items = getRequiredItems(level);
                if (index >= 0 && index < items.size()) {
                    items.remove(index);
                    setRequiredItems(level, items);
                    saveAndReload(player, ChatColor.GREEN + "Đã xóa vật phẩm yêu cầu khỏi cấp " + level + ".");
                    Bukkit.getScheduler().runTaskLater(plugin, () -> openRequiredItemsEditor(player, level), 1L);
                }
            }
        }
    }

    private Map<String, Object> createDataFromItem(ItemStack stack) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("material", stack.getType().name());
        data.put("amount", stack.getAmount());

        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName()) {
                data.put("custom-name", toConfigString(meta.getDisplayName()));
            }
            if (meta.hasLore()) {
                List<String> lore = meta.getLore();
                if (lore != null && !lore.isEmpty()) {
                    data.put("custom-lore", lore.stream()
                            .map(this::toConfigString)
                            .collect(Collectors.toList()));
                }
            }
        }

        return data;
    }

    private String toConfigString(String text) {
        return text == null ? null : text.replace(ChatColor.COLOR_CHAR, '&');
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        levelSlotMap.remove(uuid);
        requiredItemSlotMap.remove(uuid);
        selectedLevel.remove(uuid);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        EditRequest request = pendingEdits.remove(uuid);
        if (request == null) {
            return;
        }

        event.setCancelled(true);
        String message = event.getMessage().trim();
        Player player = event.getPlayer();

        Bukkit.getScheduler().runTask(plugin, () -> handleChatInput(player, request, message));
    }

    private void handleChatInput(Player player, EditRequest request, String input) {
        if (input.equalsIgnoreCase("cancel")) {
            player.sendMessage(ChatColor.RED + "Đã hủy chỉnh sửa.");
            Bukkit.getScheduler().runTaskLater(plugin, () -> openLevelEditor(player, request.level()), 1L);
            return;
        }

        String basePath = "rebirth.rebirth.levels." + request.level();

        try {
            switch (request.field()) {
                case REQUIRED_LEVEL -> {
                    int value = Integer.parseInt(input);
                    if (value < 0) {
                        player.sendMessage(ChatColor.RED + "Giá trị phải lớn hơn hoặc bằng 0!");
                        pendingEdits.put(player.getUniqueId(), request);
                        return;
                    }
                    config.set(basePath + ".required-level", value);
                    saveAndReload(player, ChatColor.GREEN + "Đã cập nhật required-level cho cấp " + request.level() + ".");
                }
                case REQUIRED_MONEY -> {
                    double value = Double.parseDouble(input);
                    if (value < 0) {
                        player.sendMessage(ChatColor.RED + "Giá trị phải lớn hơn hoặc bằng 0!");
                        pendingEdits.put(player.getUniqueId(), request);
                        return;
                    }
                    config.set(basePath + ".required-money", value);
                    saveAndReload(player, ChatColor.GREEN + "Đã cập nhật required-money cho cấp " + request.level() + ".");
                }
                case SUCCESS_RATE -> {
                    double value = Double.parseDouble(input);
                    if (value < 0 || value > 100) {
                        player.sendMessage(ChatColor.RED + "Tỉ lệ phải trong khoảng 0-100!");
                        pendingEdits.put(player.getUniqueId(), request);
                        return;
                    }
                    config.set(basePath + ".success-rate", value);
                    saveAndReload(player, ChatColor.GREEN + "Đã cập nhật success-rate cho cấp " + request.level() + ".");
                }
            }
        } catch (NumberFormatException ex) {
            player.sendMessage(ChatColor.RED + "Giá trị không hợp lệ, vui lòng thử lại!");
            pendingEdits.put(player.getUniqueId(), request);
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> openLevelEditor(player, request.level()), 1L);
    }

    private enum EditField {
        REQUIRED_LEVEL,
        REQUIRED_MONEY,
        SUCCESS_RATE
    }

    private record EditRequest(int level, EditField field) { }
}

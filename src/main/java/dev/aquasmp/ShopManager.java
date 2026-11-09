package dev.aquasmp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class ShopManager implements Listener, CommandExecutor {
    private enum MenuType { ROOT, CATEGORY, CONFIRM, SELL }

    private static final class MenuHolder implements InventoryHolder {
        private final MenuType type;
        private final ShopCatalog.Product product;
        private final int page;
        private int amount;
        private ShopCatalog.NatureSubcategory natureSubcategory;
        private int parentPage;
        private Inventory inventory;

        private MenuHolder(MenuType type, ShopCatalog.Product product, int amount, int page) {
            this.type = type;
            this.product = product;
            this.amount = amount;
            this.page = page;
        }

        @Override
        public Inventory getInventory() { return inventory; }
    }

    private final AquaSMPPlugin plugin;
    private final EconomyService economy;
    private final NamespacedKey productKey;
    private final NamespacedKey abilityKey;
    private final Map<ShopCatalog.Section, List<ShopCatalog.Product>> products = new EnumMap<>(ShopCatalog.Section.class);
    private final Set<String> abilityBreakEvents = new HashSet<>();

    public ShopManager(AquaSMPPlugin plugin, EconomyService economy) {
        this.plugin = plugin;
        this.economy = economy;
        productKey = new NamespacedKey(plugin, "shop_product");
        abilityKey = new NamespacedKey(plugin, "aqua_ability");
        for (ShopCatalog.Section section : ShopCatalog.Section.values()) {
            products.put(section, ShopCatalog.products(section));
        }
    }

    public void openShop(Player player) {
        MenuHolder holder = create(MenuType.ROOT, null, 1, 0, 27, "Aqua SMP Shop");
        fill(holder.inventory);
        int[] slots = {10, 12, 14, 16, 22};
        ShopCatalog.Section[] sections = ShopCatalog.Section.values();
        for (int index = 0; index < sections.length; index++) {
            ShopCatalog.Section section = sections[index];
            ItemStack icon = item(section.icon(), section.label(), NamedTextColor.AQUA);
            setLore(icon, List.of(line(section == ShopCatalog.Section.SHARDS
                ? "Balance: " + (long) economy.balance(player, true) + " shards"
                : products.get(section).size() + " items", NamedTextColor.GRAY), line("Click to browse", NamedTextColor.WHITE)));
            holder.inventory.setItem(slots[index], icon);
        }
        holder.inventory.setItem(4, item(Material.PRISMARINE_SHARD, "Aqua SMP", NamedTextColor.AQUA));
        holder.inventory.setItem(26, item(Material.BARRIER, "Close shop", NamedTextColor.RED));
        player.openInventory(holder.inventory);
    }

    public void openSell(Player player) {
        MenuHolder holder = create(MenuType.SELL, null, 1, 0, 54, "Aqua SMP Sell");
        fill(holder.inventory);
        holder.inventory.setItem(4, item(Material.DISPENSER, "Drop items here", NamedTextColor.AQUA));
        refreshSellInfo(holder.inventory);
        holder.inventory.setItem(53, item(Material.BARRIER, "Close", NamedTextColor.RED));
        player.openInventory(holder.inventory);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used in game.");
            return true;
        }
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "shop" -> openShop(player);
            case "sell" -> openSell(player);
            case "aquasmp" -> handleAdminCommand(sender, args);
            default -> { return false; }
        }
        return true;
    }

    private void handleAdminCommand(CommandSender sender, String[] args) {
        if (args.length == 3 && args[0].equalsIgnoreCase("shards")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(prefix("That player is not online.", NamedTextColor.RED));
                return;
            }
            try {
                long amount = Long.parseLong(args[2]);
                if (amount < 1) throw new NumberFormatException();
                economy.grantShards(target, amount);
                sender.sendMessage(prefix("Granted " + amount + " shards to " + target.getName() + ".", NamedTextColor.GREEN));
                target.sendMessage(prefix("You received " + amount + " shards.", NamedTextColor.AQUA));
            } catch (NumberFormatException exception) {
                sender.sendMessage(prefix("Usage: /aquasmp shards <player> <positive amount>", NamedTextColor.RED));
            }
            return;
        }
        sender.sendMessage(prefix("Usage: /aquasmp shards <player> <amount>", NamedTextColor.GRAY));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (holder.type == MenuType.SELL) {
            handleSellClick(event, holder, player, slot);
            return;
        }
        if (slot < 0 || slot >= holder.inventory.getSize()) return;
        switch (holder.type) {
            case ROOT -> {
                if (slot == 26) {
                    player.closeInventory();
                    return;
                }
                ShopCatalog.Section[] sections = ShopCatalog.Section.values();
                int[] sectionSlots = {10, 12, 14, 16, 22};
                for (int index = 0; index < sectionSlots.length; index++) {
                    if (slot == sectionSlots[index]) openCategory(player, sections[index], 0);
                }
            }
            case CATEGORY -> handleCategoryClick(player, holder, slot);
            case CONFIRM -> handleConfirmClick(player, holder, slot);
            default -> { }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof MenuHolder holder)) return;
        if (holder.type != MenuType.SELL) {
            event.setCancelled(true);
            return;
        }
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < 0 || rawSlot >= 54 || rawSlot == 4 || rawSlot >= 45) {
                event.setCancelled(true);
                return;
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> refreshSellInfo(holder.inventory));
    }

    private void handleSellClick(InventoryClickEvent event, MenuHolder holder, Player player, int slot) {
        if (slot >= 54) {
            if (event.isShiftClick() && event.getCurrentItem() != null && !event.getCurrentItem().getType().isAir()) {
                ItemStack moving = event.getCurrentItem().clone();
                if (depositStack(holder.inventory, moving)) {
                    event.setCurrentItem(null);
                    refreshSellInfo(holder.inventory);
                }
            }
            return;
        }
        if (slot == 4) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir() && depositStack(holder.inventory, cursor.clone())) {
                ItemStack selected = cursor.clone();
                event.setCursor(null);
                collectMatchingItems(player, holder.inventory, selected);
                refreshSellInfo(holder.inventory);
            }
        } else if (slot == 49) {
            sellDeposited(holder.inventory, player);
        } else if (slot == 53) {
            player.closeInventory();
        } else if (slot >= 45) {
            return;
        }
    }

    private void handleCategoryClick(Player player, MenuHolder holder, int slot) {
        if (slot == 45) {
            if (holder.natureSubcategory != null) openCategory(player, ShopCatalog.Section.NATURE, holder.parentPage);
            else openShop(player);
            return;
        }
        if (slot == 51) {
            player.closeInventory();
            return;
        }

        if (holder.natureSubcategory != null) {
            if (slot < 25) {
                List<ShopCatalog.Product> natureItems = ShopCatalog.products(holder.natureSubcategory);
                if (slot < natureItems.size()) {
                    openConfirm(player, natureItems.get(slot), 1, 0, holder.natureSubcategory, holder.parentPage);
                }
            }
            return;
        }

        ShopCatalog.Section section = holder.product.section();
        if (section == ShopCatalog.Section.NATURE) {
            switch (slot) {
                case 46 -> openNatureSubcategory(player, ShopCatalog.NatureSubcategory.OVERWORLD, holder.page);
                case 47 -> openNatureSubcategory(player, ShopCatalog.NatureSubcategory.NETHER, holder.page);
                case 48 -> openNatureSubcategory(player, ShopCatalog.NatureSubcategory.END, holder.page);
                case 50 -> { if (holder.page > 0) openCategory(player, section, holder.page - 1); }
                case 53 -> {
                    if ((holder.page + 1) * 36 < products.get(section).size()) openCategory(player, section, holder.page + 1);
                }
                default -> {
                    if (slot < 36) {
                        int index = holder.page * 36 + slot;
                        List<ShopCatalog.Product> sectionItems = products.get(section);
                        if (index < sectionItems.size()) {
                            openConfirm(player, sectionItems.get(index), 1, holder.page, null, 0);
                        }
                    }
                }
            }
            return;
        }

        List<ShopCatalog.Product> sectionItems = products.get(section);
        if (slot == 47 && holder.page > 0) {
            openCategory(player, section, holder.page - 1);
        } else if (slot == 53 && (holder.page + 1) * 45 < sectionItems.size()) {
            openCategory(player, section, holder.page + 1);
        } else if (slot < 45) {
            int index = holder.page * 45 + slot;
            if (index < sectionItems.size()) {
                openConfirm(player, sectionItems.get(index), 1, holder.page, null, 0);
            }
        }
    }

    private void handleConfirmClick(Player player, MenuHolder holder, int slot) {
        switch (slot) {
            case 10 -> holder.amount = Math.max(1, holder.amount - 10);
            case 11 -> holder.amount = Math.max(1, holder.amount - 1);
            case 15 -> holder.amount = Math.min(2304, holder.amount + 1);
            case 16 -> holder.amount = Math.min(2304, holder.amount + 10);
            case 17 -> holder.amount = Math.min(2304, holder.amount + 64);
            case 28 -> purchase(player, holder.product, holder.amount, PurchaseMode.INVENTORY);
            case 30 -> purchase(player, holder.product, holder.amount, PurchaseMode.DROP);
            case 32 -> purchase(player, holder.product, holder.amount, PurchaseMode.FILL);
            case 49 -> {
                if (holder.natureSubcategory != null) openNatureSubcategory(player, holder.natureSubcategory, holder.parentPage);
                else openCategory(player, holder.product.section(), holder.page);
            }
            case 53 -> player.closeInventory();
            default -> { }
        }
        if (player.getOpenInventory().getTopInventory().getHolder(false) == holder) refreshConfirm(holder, player);
    }

    private void openCategory(Player player, ShopCatalog.Section section, int page) {
        MenuHolder holder = create(MenuType.CATEGORY, new ShopCatalog.Product("section", section, section.icon(), 0), 1,
            page, 54, "Aqua SMP: " + section.label());
        fill(holder.inventory);
        List<ShopCatalog.Product> sectionItems = products.get(section);
        int pageSize = section == ShopCatalog.Section.NATURE ? 36 : 45;
        int start = page * pageSize;
        for (int index = 0; index < pageSize && start + index < sectionItems.size(); index++) {
            ShopCatalog.Product product = sectionItems.get(start + index);
            ItemStack icon = createProductItem(product);
            setLore(icon, List.of(
                line(priceText(product), section.usesShards() ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.GOLD),
                line("Click to select quantity", NamedTextColor.GRAY)));
            holder.inventory.setItem(index, icon);
        }
        holder.inventory.setItem(45, item(Material.ARROW, "Back", NamedTextColor.WHITE));
        holder.inventory.setItem(49, item(Material.PRISMARINE_SHARD, "Page " + (page + 1), NamedTextColor.AQUA));
        holder.inventory.setItem(51, item(Material.BARRIER, "Close", NamedTextColor.RED));
        if (section == ShopCatalog.Section.NATURE) {
            holder.inventory.setItem(46, item(Material.MOSS_BLOCK, "Overworld blocks", NamedTextColor.GREEN));
            holder.inventory.setItem(47, item(Material.NETHERRACK, "Nether blocks", NamedTextColor.RED));
            holder.inventory.setItem(48, item(Material.END_STONE, "End blocks", NamedTextColor.LIGHT_PURPLE));
            if (page > 0) holder.inventory.setItem(50, item(Material.ARROW, "Previous page", NamedTextColor.WHITE));
        } else if (page > 0) {
            holder.inventory.setItem(47, item(Material.ARROW, "Previous page", NamedTextColor.WHITE));
        }
        if (start + pageSize < sectionItems.size()) holder.inventory.setItem(53, item(Material.ARROW, "Next page", NamedTextColor.WHITE));
        player.openInventory(holder.inventory);
    }

    private void openNatureSubcategory(Player player, ShopCatalog.NatureSubcategory subcategory, int parentPage) {
        MenuHolder holder = create(MenuType.CATEGORY,
            new ShopCatalog.Product("nature_subcategory", ShopCatalog.Section.NATURE, subcategory.icon(), 0),
            1, 0, 54, "Aqua SMP: " + subcategory.label());
        holder.natureSubcategory = subcategory;
        holder.parentPage = parentPage;
        fill(holder.inventory);
        List<ShopCatalog.Product> sectionItems = ShopCatalog.products(subcategory);
        for (int index = 0; index < sectionItems.size(); index++) {
            ShopCatalog.Product product = sectionItems.get(index);
            ItemStack icon = createProductItem(product);
            setLore(icon, List.of(line(priceText(product), NamedTextColor.GOLD),
                line("Click to select quantity", NamedTextColor.GRAY)));
            holder.inventory.setItem(index, icon);
        }
        holder.inventory.setItem(45, item(Material.ARROW, "Back to Nature", NamedTextColor.WHITE));
        holder.inventory.setItem(49, item(subcategory.icon(), subcategory.label() + " blocks", NamedTextColor.AQUA));
        holder.inventory.setItem(51, item(Material.BARRIER, "Close", NamedTextColor.RED));
        player.openInventory(holder.inventory);
    }

    private void openConfirm(Player player, ShopCatalog.Product product, int amount, int sourcePage,
        ShopCatalog.NatureSubcategory natureSubcategory, int parentPage) {
        MenuHolder holder = create(MenuType.CONFIRM, product, amount, sourcePage, 54, "Aqua SMP: Confirm Purchase");
        holder.natureSubcategory = natureSubcategory;
        holder.parentPage = parentPage;
        fill(holder.inventory);
        holder.inventory.setItem(13, createProductItem(product));
        holder.inventory.setItem(10, item(Material.REDSTONE, "-10", NamedTextColor.RED));
        holder.inventory.setItem(11, item(Material.REDSTONE, "-1", NamedTextColor.RED));
        holder.inventory.setItem(15, item(Material.LIME_DYE, "+1", NamedTextColor.GREEN));
        holder.inventory.setItem(16, item(Material.LIME_DYE, "+10", NamedTextColor.GREEN));
        holder.inventory.setItem(17, item(Material.LIME_DYE, "+64", NamedTextColor.GREEN));
        holder.inventory.setItem(28, item(Material.EMERALD, "Buy", NamedTextColor.GREEN));
        holder.inventory.setItem(30, item(Material.DROPPER, "Buy and drop", NamedTextColor.AQUA));
        holder.inventory.setItem(32, item(Material.CHEST, "Fill inventory", NamedTextColor.GOLD));
        holder.inventory.setItem(49, item(Material.ARROW, "Back to shop", NamedTextColor.WHITE));
        holder.inventory.setItem(53, item(Material.BARRIER, "Close", NamedTextColor.RED));
        refreshConfirm(holder, player);
        player.openInventory(holder.inventory);
    }

    private void refreshConfirm(MenuHolder holder, Player player) {
        double unitPrice = price(holder.product);
        ItemStack preview = createProductItem(holder.product);
        setLore(preview, List.of(line("Quantity: " + holder.amount, NamedTextColor.WHITE),
            line("Total: " + format(unitPrice * holder.amount) + currency(holder.product), NamedTextColor.GOLD)));
        holder.inventory.setItem(13, preview);
        ItemStack fill = item(Material.CHEST, "Fill inventory", NamedTextColor.GOLD);
        setLore(fill, List.of(line("Fills available slots", NamedTextColor.GRAY)));
        holder.inventory.setItem(32, fill);
    }

    private MenuHolder create(MenuType type, ShopCatalog.Product product, int amount, int page, int size, String title) {
        MenuHolder holder = new MenuHolder(type, product, amount, page);
        holder.inventory = Bukkit.createInventory(holder, size, Component.text(title, NamedTextColor.AQUA));
        return holder;
    }

    private void fill(Inventory inventory) {
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, " ", NamedTextColor.BLACK);
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
    }

    private void purchase(Player player, ShopCatalog.Product product, int requested, PurchaseMode mode) {
        int amount = mode == PurchaseMode.FILL ? maxFit(player, createProductItem(product)) : requested;
        if (amount < 1) {
            player.sendMessage(prefix("Your inventory has no room for this item.", NamedTextColor.RED));
            return;
        }
        double total = price(product) * amount;
        if (!economy.withdraw(player, total, product.section().usesShards())) {
            player.sendMessage(prefix(product.section().usesShards()
                ? "You do not have enough shards."
                : economy.hasVault() ? "You do not have enough money." : "The shop requires Vault and an economy provider.", NamedTextColor.RED));
            return;
        }
        List<ItemStack> stacks = makeStacks(createProductItem(product), amount);
        if (mode == PurchaseMode.DROP) {
            for (ItemStack stack : stacks) player.getWorld().dropItemNaturally(player.getLocation(), stack);
        } else {
            for (ItemStack stack : stacks) {
                Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
                leftovers.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            }
        }
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
        player.sendMessage(prefix("Purchased " + amount + " " + plainName(product.id()) + " for " + format(total)
            + currency(product) + ".", NamedTextColor.GREEN));
    }

    private enum PurchaseMode { INVENTORY, DROP, FILL }

    private int maxFit(Player player, ItemStack template) {
        int space = 0;
        int maxStack = template.getMaxStackSize();
        for (ItemStack content : player.getInventory().getStorageContents()) {
            if (content == null || content.getType().isAir()) space += maxStack;
            else if (content.isSimilar(template) && content.getAmount() < maxStack) space += maxStack - content.getAmount();
        }
        return Math.min(2304, space);
    }

    private List<ItemStack> makeStacks(ItemStack template, int amount) {
        List<ItemStack> result = new ArrayList<>();
        int maxStack = template.getMaxStackSize();
        while (amount > 0) {
            ItemStack stack = template.clone();
            stack.setAmount(Math.min(maxStack, amount));
            result.add(stack);
            amount -= stack.getAmount();
        }
        return result;
    }

    private double price(ShopCatalog.Product product) {
        if (product.section().usesShards()) return switch (product.id()) {
            case "AQUA_3X3_PICKAXE" -> 48;
            case "AQUA_TREE_FELLER" -> 32;
            case "AQUA_SELL_AXE" -> 64;
            default -> 40;
        };
        if (isDiamondArmor(product.id())) {
            return 15_000 * plugin.getConfig().getDouble("shop.price-multiplier", 1.0);
        }
        double base = switch (product.section()) {
            case GEAR -> 120 + product.index() * 22.0;
            case NATURE -> 8 + product.index() * 2.0;
            case DECOR -> 14 + product.index() * 3.0;
            case REDSTONE -> 12 + product.index() * 2.0;
            case SHARDS -> 0;
        };
        return Math.max(1, base * plugin.getConfig().getDouble("shop.price-multiplier", 1.0));
    }

    private String priceText(ShopCatalog.Product product) {
        return format(price(product)) + currency(product);
    }

    private String currency(ShopCatalog.Product product) {
        return product.section().usesShards() ? " shards" : " coins";
    }

    private String format(double value) {
        return value == Math.rint(value) ? String.format(Locale.US, "%.0f", value) : String.format(Locale.US, "%.2f", value);
    }

    private ItemStack createProductItem(ShopCatalog.Product product) {
        ItemStack stack = new ItemStack(product.material());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(productName(product));
        meta.getPersistentDataContainer().set(productKey, PersistentDataType.STRING, product.id());
        if (product.section() == ShopCatalog.Section.SHARDS) {
            meta.getPersistentDataContainer().set(abilityKey, PersistentDataType.STRING, product.id());
            addCustomEnchants(stack, product.id());
            meta = stack.getItemMeta();
            meta.displayName(productName(product));
            meta.getPersistentDataContainer().set(productKey, PersistentDataType.STRING, product.id());
            meta.getPersistentDataContainer().set(abilityKey, PersistentDataType.STRING, product.id());
        } else if (product.section() == ShopCatalog.Section.GEAR) {
            addMaxedDiamondEnchants(stack);
            meta = stack.getItemMeta();
            meta.displayName(productName(product));
            meta.getPersistentDataContainer().set(productKey, PersistentDataType.STRING, product.id());
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private Component productName(ShopCatalog.Product product) {
        String prefix = "";
        if (isDiamondArmor(product.id())) {
            prefix = "Common ";
        } else if (product.section() == ShopCatalog.Section.GEAR
            && !product.id().equals("GOLDEN_APPLE") && !product.id().equals("GOLDEN_CARROT")) {
            prefix = "Starter ";
        }
        return Component.text(prefix + plainName(product.id()), NamedTextColor.GREEN, TextDecoration.BOLD);
    }

    private boolean isDiamondArmor(String id) {
        return id.equals("DIAMOND_HELMET") || id.equals("DIAMOND_CHESTPLATE")
            || id.equals("DIAMOND_LEGGINGS") || id.equals("DIAMOND_BOOTS");
    }

    private void addMaxedDiamondEnchants(ItemStack stack) {
        String material = stack.getType().name();
        if (material.equals("DIAMOND_HELMET")) {
            enchant(stack, "protection", 4); enchant(stack, "respiration", 3); enchant(stack, "aqua_affinity", 1);
            enchant(stack, "thorns", 3);
        } else if (material.equals("DIAMOND_CHESTPLATE") || material.equals("DIAMOND_LEGGINGS")) {
            enchant(stack, "protection", 4); enchant(stack, "thorns", 3);
            if (material.equals("DIAMOND_LEGGINGS")) enchant(stack, "swift_sneak", 3);
        } else if (material.equals("DIAMOND_BOOTS")) {
            enchant(stack, "protection", 4); enchant(stack, "feather_falling", 4); enchant(stack, "depth_strider", 3);
            enchant(stack, "thorns", 3); enchant(stack, "soul_speed", 3);
        } else if (material.equals("DIAMOND_SWORD")) {
            enchant(stack, "sharpness", 5); enchant(stack, "looting", 3); enchant(stack, "sweeping_edge", 3);
        } else if (material.startsWith("DIAMOND_")) {
            enchant(stack, "efficiency", 5); enchant(stack, "fortune", 3);
        }
        if (material.startsWith("DIAMOND_")) {
            enchant(stack, "unbreaking", 3); enchant(stack, "mending", 1);
        }
    }

    private void addCustomEnchants(ItemStack stack, String id) {
        switch (id) {
            case "AQUA_3X3_PICKAXE" -> { enchant(stack, "efficiency", 5); enchant(stack, "unbreaking", 3); enchant(stack, "mending", 1); }
            case "AQUA_TREE_FELLER" -> { enchant(stack, "efficiency", 5); enchant(stack, "unbreaking", 3); enchant(stack, "mending", 1); }
            case "AQUA_SELL_AXE" -> { enchant(stack, "efficiency", 5); enchant(stack, "unbreaking", 3); enchant(stack, "mending", 1); }
            case "AQUA_TIDEBREAKER" -> { enchant(stack, "sharpness", 5); enchant(stack, "looting", 3); enchant(stack, "unbreaking", 3); enchant(stack, "mending", 1); }
            default -> { }
        }
    }

    private void enchant(ItemStack stack, String key, int level) {
        Enchantment enchantment = Enchantment.getByKey(NamespacedKey.minecraft(key));
        if (enchantment != null) stack.addUnsafeEnchantment(enchantment, level);
    }

    private ItemStack item(Material material, String name, NamedTextColor color) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(name, color, TextDecoration.BOLD));
        stack.setItemMeta(meta);
        return stack;
    }

    private void setLore(ItemStack stack, List<Component> lore) {
        ItemMeta meta = stack.getItemMeta();
        meta.lore(lore);
        stack.setItemMeta(meta);
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color);
    }

    private Component prefix(String text, NamedTextColor color) {
        return Component.text("Aqua SMP ", NamedTextColor.AQUA, TextDecoration.BOLD)
            .append(Component.text("» ", NamedTextColor.DARK_AQUA))
            .append(Component.text(text, color));
    }

    private String plainName(String id) {
        String[] words = id.replace("AQUA_", "").toLowerCase(Locale.ROOT).split("_");
        StringBuilder name = new StringBuilder();
        for (String word : words) {
            if (name.length() > 0) name.append(' ');
            name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return name.toString();
    }

    private boolean depositStack(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.clone();
        int[] slots = new int[44];
        int index = 0;
        for (int slot = 0; slot < 45; slot++) if (slot != 4) slots[index++] = slot;
        int capacity = 0;
        for (int slot : slots) {
            ItemStack current = inventory.getItem(slot);
            if (current == null || current.getType().isAir()) capacity += remaining.getMaxStackSize();
            else if (current.isSimilar(remaining)) capacity += current.getMaxStackSize() - current.getAmount();
        }
        if (capacity < remaining.getAmount()) return false;
        for (int slot : slots) {
            ItemStack current = inventory.getItem(slot);
            if (current != null && current.isSimilar(remaining) && current.getAmount() < current.getMaxStackSize()) {
                int moved = Math.min(remaining.getAmount(), current.getMaxStackSize() - current.getAmount());
                current.setAmount(current.getAmount() + moved);
                remaining.setAmount(remaining.getAmount() - moved);
                if (remaining.getAmount() == 0) return true;
            }
        }
        for (int slot : slots) {
            ItemStack current = inventory.getItem(slot);
            if (current == null || current.getType().isAir()) {
                ItemStack placed = remaining.clone();
                placed.setAmount(Math.min(remaining.getAmount(), placed.getMaxStackSize()));
                inventory.setItem(slot, placed);
                remaining.setAmount(remaining.getAmount() - placed.getAmount());
                if (remaining.getAmount() == 0) return true;
            }
        }
        return false;
    }

    private void sellDeposited(Inventory inventory, Player player) {
        double total = 0;
        for (int slot = 0; slot < 45; slot++) {
            if (slot == 4) continue;
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;
            total += sellUnitPrice(stack.getType()) * stack.getAmount();
        }
        total *= plugin.getConfig().getDouble("shop.sell-multiplier", 0.5);
        if (total <= 0) {
            player.sendMessage(prefix("There are no sellable items in the menu.", NamedTextColor.RED));
            return;
        }
        if (!economy.deposit(player, total)) {
            player.sendMessage(prefix("Selling requires Vault and an economy provider.", NamedTextColor.RED));
            return;
        }
        for (int slot = 0; slot < 45; slot++) if (slot != 4) inventory.setItem(slot, null);
        refreshSellInfo(inventory);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
        Component saleMessage = prefix("Sold deposited items for " + format(total) + " coins.", NamedTextColor.GREEN);
        player.sendMessage(saleMessage);
        player.sendActionBar(saleMessage);
    }

    private double sellUnitPrice(Material material) {
        String name = material.name();
        if (name.contains("NETHERITE")) return 45;
        if (name.contains("DIAMOND")) return 18;
        if (name.contains("EMERALD")) return 12;
        if (name.contains("GOLD")) return 5;
        if (name.contains("IRON")) return 3;
        if (material.isEdible()) return 1.5;
        return 1;
    }

    private void refreshSellInfo(Inventory inventory) {
        double total = 0;
        for (int slot = 0; slot < 45; slot++) {
            if (slot == 4) continue;
            ItemStack stack = inventory.getItem(slot);
            if (stack != null && !stack.getType().isAir()) total += sellUnitPrice(stack.getType()) * stack.getAmount();
        }
        total *= plugin.getConfig().getDouble("shop.sell-multiplier", 0.5);
        ItemStack button = item(Material.EMERALD, "Sell deposited items", NamedTextColor.GREEN);
        setLore(button, List.of(line("Estimated total: " + format(total) + " coins", NamedTextColor.GOLD)));
        inventory.setItem(49, button);
    }

    private void collectMatchingItems(Player player, Inventory menu, ItemStack selected) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack content = contents[slot];
            if (content == null || !content.isSimilar(selected)) continue;
            if (depositStack(menu, content.clone())) player.getInventory().setItem(slot, null);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (abilityBreakEvents.remove(blockKey(event.getBlock()))) return;
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        String ability = tool.getItemMeta().getPersistentDataContainer().get(abilityKey, PersistentDataType.STRING);
        if (ability == null) return;
        if (ability.equals("AQUA_3X3_PICKAXE") && isPickaxeMineable(event.getBlock().getType())) {
            breakThreeByThree(event.getBlock(), player, tool);
        } else if (ability.equals("AQUA_TREE_FELLER") && isLog(event.getBlock().getType())) {
            fellTree(event.getBlock(), player, tool);
        }
    }

    private void breakThreeByThree(Block center, Player player, ItemStack tool) {
        BlockFace face = player.getTargetBlockFace(5);
        if (face == null) face = BlockFace.UP;
        int[][] offsets = face.getModX() != 0
            ? new int[][]{{0, -1, -1}, {0, -1, 0}, {0, -1, 1}, {0, 0, -1}, {0, 0, 1}, {0, 1, -1}, {0, 1, 0}, {0, 1, 1}}
            : face.getModZ() != 0
                ? new int[][]{{-1, -1, 0}, {-1, 0, 0}, {-1, 1, 0}, {0, -1, 0}, {0, 1, 0}, {1, -1, 0}, {1, 0, 0}, {1, 1, 0}}
                : new int[][]{{-1, 0, -1}, {-1, 0, 0}, {-1, 0, 1}, {0, 0, -1}, {0, 0, 1}, {1, 0, -1}, {1, 0, 0}, {1, 0, 1}};
        for (int[] offset : offsets) {
            Block block = center.getRelative(offset[0], offset[1], offset[2]);
            if (isPickaxeMineable(block.getType())) breakAdditionalBlock(block, player, tool);
        }
    }

    private void fellTree(Block root, Player player, ItemStack tool) {
        ArrayDeque<Block> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(root);
        int broken = 0;
        while (!queue.isEmpty() && broken < 128) {
            Block block = queue.removeFirst();
            String key = block.getX() + ":" + block.getY() + ":" + block.getZ();
            if (!visited.add(key) || !isLog(block.getType())) continue;
            for (BlockFace face : BlockFace.values()) {
                if (face.getModX() == 0 && face.getModY() == 0 && face.getModZ() == 0) continue;
                Block adjacent = block.getRelative(face);
                if (isLog(adjacent.getType())) queue.add(adjacent);
            }
            if (block != root) breakAdditionalBlock(block, player, tool);
            broken++;
        }
    }

    private void breakAdditionalBlock(Block block, Player player, ItemStack tool) {
        String key = blockKey(block);
        abilityBreakEvents.add(key);
        BlockBreakEvent breakEvent = new BlockBreakEvent(block, player);
        Bukkit.getPluginManager().callEvent(breakEvent);
        abilityBreakEvents.remove(key);
        if (breakEvent.isCancelled()) return;
        if (breakEvent.isDropItems()) block.breakNaturally(tool);
        else block.setType(Material.AIR);
    }

    private String blockKey(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private boolean isLog(Material material) {
        String name = material.name();
        return name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_STEM") || name.equals("BAMBOO_BLOCK");
    }

    private boolean isPickaxeMineable(Material material) {
        return material.isBlock() && material.getHardness() >= 0 && material != Material.BEDROCK
            && material != Material.BARRIER && !material.isAir();
    }

    @EventHandler
    public void onContainerUse(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || event.getAction().isLeftClick()) return;
        ItemStack tool = event.getItem();
        if (tool == null || !tool.hasItemMeta()) return;
        String ability = tool.getItemMeta().getPersistentDataContainer().get(abilityKey, PersistentDataType.STRING);
        if (!"AQUA_SELL_AXE".equals(ability)) return;
        if (!(event.getClickedBlock().getState() instanceof Container container)) return;
        event.setCancelled(true);
        sellContainer(container.getInventory(), event.getPlayer());
    }

    private void sellContainer(Inventory inventory, Player player) {
        double total = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && !stack.getType().isAir()) total += sellUnitPrice(stack.getType()) * stack.getAmount();
        }
        total *= plugin.getConfig().getDouble("shop.sell-multiplier", 0.5);
        if (total <= 0 || !economy.deposit(player, total)) {
            player.sendMessage(prefix("No items sold. Check that an economy is available.", NamedTextColor.RED));
            return;
        }
        inventory.clear();
        player.sendMessage(prefix("Container sold for " + format(total) + " coins.", NamedTextColor.GREEN));
    }
}
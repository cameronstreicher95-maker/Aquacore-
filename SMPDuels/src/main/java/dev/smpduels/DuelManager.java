package dev.smpduels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class DuelManager implements Listener, CommandExecutor {
    private enum MenuType { SETUP, REQUEST, KIT_LIST, KIT_EDITOR }

    private static final class DuelMenu implements InventoryHolder {
        private final MenuType type;
        private final UUID otherPlayer;
        private final int page;
        private String kitName;
        private Inventory inventory;

        private DuelMenu(MenuType type, UUID otherPlayer, String kitName, int page) {
            this.type = type;
            this.otherPlayer = otherPlayer;
            this.kitName = kitName;
            this.page = page;
        }

        @Override
        public Inventory getInventory() { return inventory; }
    }

    private static final class DuelRequest {
        private final UUID sender;
        private final UUID target;
        private final String kitName;
        private BukkitTask expiry;

        private DuelRequest(UUID sender, UUID target, String kitName) {
            this.sender = sender;
            this.target = target;
            this.kitName = kitName;
        }
    }

    private static final class PendingTextEdit {
        private final String kitName;
        private final String field;

        private PendingTextEdit(String kitName, String field) {
            this.kitName = kitName;
            this.field = field;
        }
    }

    private static final class PlayerSnapshot {
        private final Location returnLocation;
        private final ItemStack[] storage;
        private final ItemStack[] armor;
        private final ItemStack offhand;
        private final GameMode gameMode;
        private final boolean allowFlight;
        private final boolean flying;
        private final double health;
        private final int food;
        private final float saturation;
        private final float exhaustion;
        private final int level;
        private final float experience;
        private final int totalExperience;
        private final int fireTicks;
        private final List<PotionEffect> effects;

        private PlayerSnapshot(Player player) {
            returnLocation = player.getLocation().clone();
            storage = copyItems(player.getInventory().getStorageContents());
            armor = copyItems(player.getInventory().getArmorContents());
            offhand = copyItem(player.getInventory().getItemInOffHand());
            gameMode = player.getGameMode();
            allowFlight = player.getAllowFlight();
            flying = player.isFlying();
            health = player.getHealth();
            food = player.getFoodLevel();
            saturation = player.getSaturation();
            exhaustion = player.getExhaustion();
            level = player.getLevel();
            experience = player.getExp();
            totalExperience = player.getTotalExperience();
            fireTicks = player.getFireTicks();
            effects = player.getActivePotionEffects().stream()
                .map(effect -> new PotionEffect(effect.getType(), effect.getDuration(), effect.getAmplifier(),
                    effect.isAmbient(), effect.hasParticles(), effect.hasIcon())).toList();
        }
    }

    private static final class DuelMatch {
        private final DuelRequest request;
        private final String worldName;
        private final Map<UUID, PlayerSnapshot> snapshots = new HashMap<>();
        private World world;
        private BukkitTask worldLoadTask;
        private boolean finishing;
        private boolean ending;

        private DuelMatch(DuelRequest request, String worldName) {
            this.request = request;
            this.worldName = worldName;
        }
    }

    private final SMPDuelsPlugin plugin;
    private final Map<UUID, DuelRequest> requestsByTarget = new HashMap<>();
    private final Map<UUID, DuelRequest> requestsBySender = new HashMap<>();
    private final Map<UUID, PendingTextEdit> pendingTextEdits = new HashMap<>();
    private DuelMatch activeMatch;

    public DuelManager(SMPDuelsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "duel" -> {
                if (!(sender instanceof Player player)) return playerOnly(sender);
                if (args.length != 1) {
                    player.sendMessage(message("Usage: /duel <player>", NamedTextColor.RED));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null) {
                    player.sendMessage(message("That player is not online.", NamedTextColor.RED));
                    return true;
                }
                openSetup(player, target, null, 0);
            }
            case "duelaccept" -> {
                if (!(sender instanceof Player player)) return playerOnly(sender);
                acceptRequest(player);
            }
            case "dueldeny" -> {
                if (!(sender instanceof Player player)) return playerOnly(sender);
                denyRequest(player);
            }
            case "ckit" -> {
                if (!(sender instanceof Player player)) return playerOnly(sender);
                saveKit(player, args);
            }
            case "kit" -> {
                if (!(sender instanceof Player player)) return playerOnly(sender);
                if (!player.isOp()) {
                    player.sendMessage(message("Only operators can edit kits.", NamedTextColor.RED));
                    return true;
                }
                if (args.length != 1 || !args[0].equalsIgnoreCase("edit")) {
                    player.sendMessage(message("Usage: /kit edit", NamedTextColor.RED));
                    return true;
                }
                openKitList(player, 0);
            }
            default -> { return false; }
        }
        return true;
    }

    private boolean playerOnly(CommandSender sender) {
        sender.sendMessage("This command can only be used in game.");
        return true;
    }

    private void saveKit(Player player, String[] args) {
        if (!player.isOp()) {
            player.sendMessage(message("You do not have permission to save kits.", NamedTextColor.RED));
            return;
        }
        if (args.length != 2 || !args[0].equalsIgnoreCase("save") || !args[1].matches("[A-Za-z0-9_-]{1,24}")) {
            player.sendMessage(message("Usage: /ckit save <kitname>", NamedTextColor.RED));
            return;
        }
        String kitName = args[1].toLowerCase(Locale.ROOT);
        PlayerInventory inventory = player.getInventory();
        String path = "kits." + kitName;
        plugin.getConfig().set(path + ".storage", serializeItems(inventory.getStorageContents()));
        plugin.getConfig().set(path + ".armor", serializeItems(inventory.getArmorContents()));
        plugin.getConfig().set(path + ".offhand", serializeItem(inventory.getItemInOffHand()));
        if (!plugin.getConfig().contains(path + ".display.name")) plugin.getConfig().set(path + ".display.name", kitName);
        plugin.saveConfig();
        player.sendMessage(message("Saved duel kit " + kitName + ".", NamedTextColor.GREEN));
    }

    private List<Map<String, Object>> serializeItems(ItemStack[] items) {
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (ItemStack item : items) serialized.add(item == null || item.getType().isAir() ? Map.of() : item.serialize());
        return serialized;
    }

    private Map<String, Object> serializeItem(ItemStack item) {
        return item == null || item.getType().isAir() ? Map.of() : item.serialize();
    }

    private ItemStack[] deserializeItems(String path, int size) {
        ItemStack[] items = new ItemStack[size];
        List<?> serialized = plugin.getConfig().getList(path, List.of());
        for (int slot = 0; slot < Math.min(size, serialized.size()); slot++) {
            Object value = serialized.get(slot);
            if (value instanceof ItemStack item) {
                items[slot] = item.clone();
            } else if (value instanceof Map<?, ?> map && !map.isEmpty()) {
                Map<String, Object> itemData = new HashMap<>();
                map.forEach((key, itemValue) -> itemData.put(String.valueOf(key), itemValue));
                try {
                    items[slot] = ItemStack.deserialize(itemData);
                } catch (IllegalArgumentException exception) {
                    plugin.getLogger().warning("Skipping invalid item in duel kit at " + path + ".");
                }
            }
        }
        return items;
    }

    private ItemStack deserializeItem(String path) {
        Object value = plugin.getConfig().get(path);
        if (value instanceof ItemStack item) return item.clone();
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            Map<String, Object> itemData = new HashMap<>();
            map.forEach((key, itemValue) -> itemData.put(String.valueOf(key), itemValue));
            try {
                return ItemStack.deserialize(itemData);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private List<String> kitNames() {
        ConfigurationSection kits = plugin.getConfig().getConfigurationSection("kits");
        if (kits == null) return List.of();
        return kits.getKeys(false).stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private void openKitList(Player player, int page) {
        DuelMenu menu = new DuelMenu(MenuType.KIT_LIST, null, null, page);
        menu.inventory = Bukkit.createInventory(menu, 54, Component.text("Edit duel kits", NamedTextColor.GOLD));
        fillMenu(menu.inventory);
        List<String> kits = kitNames();
        int start = page * 45;
        for (int slot = 0; slot < 45 && start + slot < kits.size(); slot++) {
            String kitName = kits.get(start + slot);
            menu.inventory.setItem(slot, getKitDisplayItem(kitName));
        }
        if (page > 0) menu.inventory.setItem(45, menuItem(Material.ARROW, "Previous page", NamedTextColor.WHITE));
        menu.inventory.setItem(49, menuItem(Material.BARRIER, "Close", NamedTextColor.RED));
        if (start + 45 < kits.size()) menu.inventory.setItem(53, menuItem(Material.ARROW, "Next page", NamedTextColor.WHITE));
        player.openInventory(menu.inventory);
    }

    private void openKitEditor(Player player, String kitName) {
        if (!player.isOp() || plugin.getConfig().getConfigurationSection("kits." + kitName) == null) {
            player.sendMessage(message("That kit is unavailable for editing.", NamedTextColor.RED));
            return;
        }
        DuelMenu menu = new DuelMenu(MenuType.KIT_EDITOR, null, kitName, 0);
        menu.inventory = Bukkit.createInventory(menu, 54, Component.text("Edit kit: " + kitName, NamedTextColor.GOLD));
        fillMenu(menu.inventory);
        ItemStack[] storage = deserializeItems("kits." + kitName + ".storage", 36);
        for (int slot = 0; slot < storage.length; slot++) menu.inventory.setItem(slot, storage[slot]);
        ItemStack[] armor = deserializeItems("kits." + kitName + ".armor", 4);
        for (int slot = 0; slot < armor.length; slot++) menu.inventory.setItem(36 + slot, armor[slot]);
        menu.inventory.setItem(40, deserializeItem("kits." + kitName + ".offhand"));
        menu.inventory.setItem(41, getKitDisplayItem(kitName));
        String basePath = "kits." + kitName + ".display";
        menu.inventory.setItem(42, editorButton(Material.NAME_TAG, "Edit display name", plugin.getConfig().getString(basePath + ".name", kitName)));
        menu.inventory.setItem(43, editorButton(Material.WRITABLE_BOOK, "Edit display lore", String.join(" | ", plugin.getConfig().getStringList(basePath + ".lore"))));
        menu.inventory.setItem(49, menuItem(Material.LIME_CONCRETE, "Save kit", NamedTextColor.GREEN));
        menu.inventory.setItem(53, menuItem(Material.RED_CONCRETE, "Cancel", NamedTextColor.RED));
        player.openInventory(menu.inventory);
    }

    private ItemStack editorButton(Material material, String title, String value) {
        ItemStack button = menuItem(material, title, NamedTextColor.GOLD);
        button.editMeta(meta -> meta.lore(List.of(
            Component.text(value.isBlank() ? "Not set" : value, NamedTextColor.GRAY),
            Component.text("Click, then type in chat. Use & color codes.", NamedTextColor.DARK_GRAY))));
        return button;
    }

    private ItemStack getKitDisplayItem(String kitName) {
        String path = "kits." + kitName + ".display";
        ItemStack icon = deserializeItem(path + ".item");
        if (icon == null || icon.getType().isAir()) {
            ItemStack[] storage = deserializeItems("kits." + kitName + ".storage", 36);
            icon = java.util.Arrays.stream(storage).filter(item -> item != null && !item.getType().isAir())
                .findFirst().map(ItemStack::clone).orElseGet(() -> new ItemStack(Material.IRON_SWORD));
        }
        ItemMeta meta = icon.getItemMeta();
        String displayName = plugin.getConfig().getString(path + ".name", kitName);
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(displayName));
        List<String> lore = plugin.getConfig().getStringList(path + ".lore");
        if (!lore.isEmpty()) meta.lore(lore.stream().map(LegacyComponentSerializer.legacyAmpersand()::deserialize).toList());
        icon.setItemMeta(meta);
        return icon;
    }

    private void saveEditedKit(Player player, DuelMenu menu) {
        String path = "kits." + menu.kitName;
        ItemStack[] contents = new ItemStack[36];
        for (int slot = 0; slot < contents.length; slot++) contents[slot] = menu.inventory.getItem(slot);
        ItemStack[] armor = new ItemStack[4];
        for (int slot = 0; slot < armor.length; slot++) armor[slot] = menu.inventory.getItem(36 + slot);
        plugin.getConfig().set(path + ".storage", serializeItems(contents));
        plugin.getConfig().set(path + ".armor", serializeItems(armor));
        plugin.getConfig().set(path + ".offhand", serializeItem(menu.inventory.getItem(40)));
        plugin.getConfig().set(path + ".display.item", serializeItem(menu.inventory.getItem(41)));
        plugin.saveConfig();
        player.sendMessage(message("Saved kit " + menu.kitName + ".", NamedTextColor.GREEN));
        openKitList(player, 0);
    }

    @EventHandler
    public void onEditorChat(AsyncChatEvent event) {
        PendingTextEdit edit = pendingTextEdits.remove(event.getPlayer().getUniqueId());
        if (edit == null) return;
        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = event.getPlayer();
            if (!player.isOnline()) return;
            if (input.equalsIgnoreCase("cancel")) {
                openKitEditor(player, edit.kitName);
                return;
            }
            String path = "kits." + edit.kitName + ".display." + edit.field;
            if (edit.field.equals("lore")) {
                List<String> lore = java.util.Arrays.stream(input.split("\\|", -1)).map(String::trim).toList();
                plugin.getConfig().set(path, lore);
            } else {
                plugin.getConfig().set(path, input);
            }
            plugin.saveConfig();
            openKitEditor(player, edit.kitName);
        });
    }

    private void openSetup(Player sender, Player target, String selectedKit, int page) {
        if (sender.equals(target)) {
            sender.sendMessage(message("You cannot duel yourself.", NamedTextColor.RED));
            return;
        }
        if (activeMatch != null || isInActiveMatch(sender.getUniqueId()) || isInActiveMatch(target.getUniqueId())) {
            sender.sendMessage(message("A duel is already in progress. Try again after its arena is deleted.", NamedTextColor.RED));
            return;
        }
        DuelMenu menu = new DuelMenu(MenuType.SETUP, target.getUniqueId(), selectedKit, page);
        menu.inventory = Bukkit.createInventory(menu, 54, Component.text("Choose a duel loadout", NamedTextColor.GOLD));
        fillMenu(menu.inventory);
        List<String> kits = kitNames();
        int pageSize = 36;
        int start = page * pageSize;
        for (int index = 0; index < pageSize && start + index < kits.size(); index++) {
            String kitName = kits.get(start + index);
            ItemStack icon = getKitDisplayItem(kitName);
            icon.editMeta(meta -> meta.lore(List.of(Component.text(
                kitName.equalsIgnoreCase(selectedKit) ? "Selected" : "Select this kit", NamedTextColor.GRAY))));
            menu.inventory.setItem(index, icon);
        }
        if (page > 0) menu.inventory.setItem(36, menuItem(Material.ARROW, "Previous kits", NamedTextColor.WHITE));
        ItemStack ownInventory = menuItem(Material.CHEST, "Use my inventory", selectedKit == null ? NamedTextColor.GREEN : NamedTextColor.WHITE);
        ownInventory.editMeta(meta -> meta.lore(List.of(Component.text("Your items are restored after the duel", NamedTextColor.GRAY))));
        menu.inventory.setItem(45, ownInventory);
        if (start + pageSize < kits.size()) menu.inventory.setItem(47, menuItem(Material.ARROW, "Next kits", NamedTextColor.WHITE));
        menu.inventory.setItem(49, menuItem(Material.LIME_CONCRETE, "Send duel request", NamedTextColor.GREEN));
        menu.inventory.setItem(53, menuItem(Material.RED_CONCRETE, "Cancel", NamedTextColor.RED));
        sender.openInventory(menu.inventory);
    }

    private void handleSetupClick(Player sender, DuelMenu menu, int slot) {
        List<String> kits = kitNames();
        if (slot >= 0 && slot < 36) {
            int index = menu.page * 36 + slot;
            if (index < kits.size()) {
                Player target = Bukkit.getPlayer(menu.otherPlayer);
                if (target == null) {
                    sender.closeInventory();
                    sender.sendMessage(message("That player is no longer online.", NamedTextColor.RED));
                } else {
                    openSetup(sender, target, kits.get(index), menu.page);
                }
            }
        } else if (slot == 36 && menu.page > 0) {
            Player target = Bukkit.getPlayer(menu.otherPlayer);
            if (target != null) openSetup(sender, target, menu.kitName, menu.page - 1);
        } else if (slot == 45) {
            Player target = Bukkit.getPlayer(menu.otherPlayer);
            if (target != null) openSetup(sender, target, null, menu.page);
        } else if (slot == 47 && (menu.page + 1) * 36 < kits.size()) {
            Player target = Bukkit.getPlayer(menu.otherPlayer);
            if (target != null) openSetup(sender, target, menu.kitName, menu.page + 1);
        } else if (slot == 49) {
            Player target = Bukkit.getPlayer(menu.otherPlayer);
            if (target == null) {
                sender.sendMessage(message("That player is no longer online.", NamedTextColor.RED));
                sender.closeInventory();
            } else {
                sendRequest(sender, target, menu.kitName);
                sender.closeInventory();
            }
        } else if (slot == 53) {
            sender.closeInventory();
        }
    }

    private void sendRequest(Player sender, Player target, String kitName) {
        if (sender.equals(target) || activeMatch != null) {
            sender.sendMessage(message("A duel cannot be started right now.", NamedTextColor.RED));
            return;
        }
        if (isInActiveMatch(sender.getUniqueId()) || isInActiveMatch(target.getUniqueId())) {
            sender.sendMessage(message("One of those players is already dueling.", NamedTextColor.RED));
            return;
        }
        if (requestsBySender.containsKey(sender.getUniqueId())) {
            sender.sendMessage(message("You already have a pending duel request.", NamedTextColor.RED));
            return;
        }
        DuelRequest previous = requestsByTarget.remove(target.getUniqueId());
        if (previous != null) {
            previous.expiry.cancel();
            requestsBySender.remove(previous.sender);
        }
        DuelRequest request = new DuelRequest(sender.getUniqueId(), target.getUniqueId(), kitName);
        requestsByTarget.put(target.getUniqueId(), request);
        requestsBySender.put(sender.getUniqueId(), request);
        int expiry = Math.max(5, plugin.getConfig().getInt("arena.request-expiry-seconds", 60));
        request.expiry = Bukkit.getScheduler().runTaskLater(plugin, () -> expireRequest(request), expiry * 20L);
        sender.sendMessage(message("Duel request sent to " + target.getName() + ".", NamedTextColor.GREEN));
        target.sendMessage(message(sender.getName() + " challenged you. Use /duelaccept or /dueldeny.", NamedTextColor.GOLD));
        openRequestMenu(target, request);
    }

    private void openRequestMenu(Player target, DuelRequest request) {
        DuelMenu menu = new DuelMenu(MenuType.REQUEST, request.sender, request.kitName, 0);
        menu.inventory = Bukkit.createInventory(menu, 27, Component.text("Duel challenge", NamedTextColor.GOLD));
        fillMenu(menu.inventory);
        Player sender = Bukkit.getPlayer(request.sender);
        String kitLabel = request.kitName == null ? "Own inventory" : "Kit: " + request.kitName;
        menu.inventory.setItem(11, menuItem(Material.LIME_CONCRETE, "Accept duel", NamedTextColor.GREEN));
        ItemStack kitIcon = request.kitName == null ? menuItem(Material.CHEST, kitLabel, NamedTextColor.GOLD)
            : getKitDisplayItem(request.kitName);
        kitIcon.editMeta(meta -> meta.displayName(Component.text((sender == null ? "Player" : sender.getName()) + " | " + kitLabel,
            NamedTextColor.GOLD, TextDecoration.BOLD)));
        menu.inventory.setItem(13, kitIcon);
        menu.inventory.setItem(15, menuItem(Material.RED_CONCRETE, "Deny duel", NamedTextColor.RED));
        target.openInventory(menu.inventory);
    }

    private void handleRequestClick(Player target, DuelMenu menu, int slot) {
        DuelRequest request = requestsByTarget.get(target.getUniqueId());
        if (request == null || !request.sender.equals(menu.otherPlayer)) {
            target.closeInventory();
            return;
        }
        if (slot == 11) acceptRequest(target);
        else if (slot == 15) denyRequest(target);
    }

    private void acceptRequest(Player target) {
        DuelRequest request = requestsByTarget.remove(target.getUniqueId());
        if (request == null) {
            target.sendMessage(message("You have no pending duel request.", NamedTextColor.RED));
            return;
        }
        requestsBySender.remove(request.sender);
        request.expiry.cancel();
        Player sender = Bukkit.getPlayer(request.sender);
        if (sender == null) {
            target.sendMessage(message("The challenger is no longer online.", NamedTextColor.RED));
            return;
        }
        if (activeMatch != null || isInActiveMatch(sender.getUniqueId()) || isInActiveMatch(target.getUniqueId())) {
            target.sendMessage(message("A duel is already in progress.", NamedTextColor.RED));
            sender.sendMessage(message("A duel is already in progress.", NamedTextColor.RED));
            return;
        }
        if (request.kitName != null && plugin.getConfig().getConfigurationSection("kits." + request.kitName) == null) {
            target.sendMessage(message("That kit no longer exists.", NamedTextColor.RED));
            sender.sendMessage(message("The selected kit no longer exists.", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(message(target.getName() + " accepted your challenge.", NamedTextColor.GREEN));
        target.sendMessage(message("Duel accepted. Creating a temporary arena...", NamedTextColor.GREEN));
        startMatch(request);
    }

    private void denyRequest(Player target) {
        DuelRequest request = requestsByTarget.remove(target.getUniqueId());
        if (request == null) {
            target.sendMessage(message("You have no pending duel request.", NamedTextColor.RED));
            return;
        }
        requestsBySender.remove(request.sender);
        request.expiry.cancel();
        target.sendMessage(message("Duel request denied.", NamedTextColor.RED));
        Player sender = Bukkit.getPlayer(request.sender);
        if (sender != null) sender.sendMessage(message(target.getName() + " denied your duel request.", NamedTextColor.RED));
    }

    private void expireRequest(DuelRequest request) {
        if (!requestsByTarget.remove(request.target, request)) return;
        requestsBySender.remove(request.sender, request);
        Player sender = Bukkit.getPlayer(request.sender);
        Player target = Bukkit.getPlayer(request.target);
        if (sender != null) sender.sendMessage(message("Duel request expired.", NamedTextColor.GRAY));
        if (target != null) target.sendMessage(message("Duel request expired.", NamedTextColor.GRAY));
    }

    private void startMatch(DuelRequest request) {
        String prefix = plugin.getConfig().getString("arena.world-prefix", "smpduel_");
        String worldName = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        DuelMatch match = new DuelMatch(request, worldName);
        activeMatch = match;
        boolean accepted = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv create " + worldName + " normal");
        if (!accepted) {
            failBeforeStart(match, "Multiverse could not create the duel arena.");
            return;
        }
        int maxChecks = Math.max(20, plugin.getConfig().getInt("arena.world-load-timeout-seconds", 45) * 2);
        int[] checks = {0};
        match.worldLoadTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                match.worldLoadTask.cancel();
                prepareMatch(match, world);
            } else if (++checks[0] >= maxChecks) {
                match.worldLoadTask.cancel();
                failBeforeStart(match, "The Multiverse arena did not load in time.");
            }
        }, 10L, 10L);
    }

    private void prepareMatch(DuelMatch match, World world) {
        Player sender = Bukkit.getPlayer(match.request.sender);
        Player target = Bukkit.getPlayer(match.request.target);
        if (sender == null || target == null || activeMatch != match) {
            failBeforeStart(match, "A duel participant disconnected before the arena was ready.");
            return;
        }
        match.world = world;
        world.setPVP(true);
        world.setGameRule(GameRule.LOCATOR_BAR, true);
        double borderSize = Math.max(32, plugin.getConfig().getDouble("arena.border-size", 80));
        world.getWorldBorder().setCenter(0, 0);
        world.getWorldBorder().setSize(borderSize);
        Location firstSpawn = findSpawn(world, -18);
        Location secondSpawn = findSpawn(world, 18);
        match.snapshots.put(sender.getUniqueId(), new PlayerSnapshot(sender));
        match.snapshots.put(target.getUniqueId(), new PlayerSnapshot(target));
        prepareFighter(sender, firstSpawn);
        prepareFighter(target, secondSpawn);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (activeMatch != match || match.ending) return;
            Player currentSender = Bukkit.getPlayer(match.request.sender);
            Player currentTarget = Bukkit.getPlayer(match.request.target);
            if (currentSender == null || currentTarget == null) {
                failBeforeStart(match, "A duel participant disconnected while the arena was loading.");
                return;
            }
            if (currentSender.getWorld() != match.world || currentTarget.getWorld() != match.world) {
                failBeforeStart(match, "A duel participant did not finish loading into the arena.");
                return;
            }
            int senderKitItems = 0;
            int targetKitItems = 0;
            if (match.request.kitName != null
                && ((senderKitItems = applyKit(currentSender, match.request.kitName)) < 1
                    || (targetKitItems = applyKit(currentTarget, match.request.kitName)) < 1)) {
                currentSender.sendMessage(message("The saved kit has no valid items. The duel was cancelled and your inventory restored.", NamedTextColor.RED));
                currentTarget.sendMessage(message("The saved kit has no valid items. The duel was cancelled and your inventory restored.", NamedTextColor.RED));
                endMatch(match, "Duel cancelled because the kit could not be loaded.");
                return;
            }
            String borderLabel = (int) borderSize + "x" + (int) borderSize;
            if (match.request.kitName != null) {
                currentSender.sendActionBar(message("Kit loaded: " + senderKitItems + " items", NamedTextColor.GREEN));
                currentTarget.sendActionBar(message("Kit loaded: " + targetKitItems + " items", NamedTextColor.GREEN));
            }
            currentSender.sendMessage(message("Duel started. The arena border is " + borderLabel + " and will be deleted when the round ends.", NamedTextColor.GREEN));
            currentTarget.sendMessage(message("Duel started. The arena border is " + borderLabel + " and will be deleted when the round ends.", NamedTextColor.GREEN));
        }, 5L);
    }

    private Location findSpawn(World world, int centerX) {
        for (int radius = 0; radius <= 5; radius++) {
            for (int offset = -radius; offset <= radius; offset++) {
                int x = centerX + offset;
                int z = radius == 0 ? 0 : (offset % 2 == 0 ? radius : -radius);
                int y = world.getHighestBlockYAt(x, z);
                Block floor = world.getBlockAt(x, y, z);
                if (floor.getType().isSolid() && !isHazard(floor.getType())
                    && floor.getRelative(0, 1, 0).isPassable() && floor.getRelative(0, 2, 0).isPassable()) {
                    return new Location(world, x + 0.5, y + 1, z + 0.5);
                }
            }
        }
        int y = Math.max(world.getMinHeight() + 10, Math.min(world.getMaxHeight() - 4, world.getHighestBlockYAt(centerX, 0) + 2));
        for (int x = centerX - 2; x <= centerX + 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.getBlockAt(x, y, z).setType(Material.OBSIDIAN, false);
                world.getBlockAt(x, y + 1, z).setType(Material.AIR, false);
                world.getBlockAt(x, y + 2, z).setType(Material.AIR, false);
            }
        }
        return new Location(world, centerX + 0.5, y + 1, 0.5);
    }

    private boolean isHazard(Material material) {
        return material == Material.MAGMA_BLOCK || material == Material.CAMPFIRE
            || material == Material.SOUL_CAMPFIRE || material == Material.FIRE
            || material == Material.SOUL_FIRE || material == Material.CACTUS;
    }

    private void prepareFighter(Player player, Location spawn) {
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setFallDistance(0);
        player.setFireTicks(0);
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.setSaturation(5);
        player.setExhaustion(0);
        for (PotionEffect effect : player.getActivePotionEffects()) player.removePotionEffect(effect.getType());
        player.teleport(spawn, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    private int applyKit(Player player, String kitName) {
        String path = "kits." + kitName;
        PlayerInventory inventory = player.getInventory();
        ItemStack[] storage = deserializeItems(path + ".storage", inventory.getStorageContents().length);
        ItemStack[] armor = deserializeItems(path + ".armor", inventory.getArmorContents().length);
        ItemStack offhand = deserializeItem(path + ".offhand");
        int itemCount = countItems(storage) + countItems(armor) + (hasItem(offhand) ? 1 : 0);
        if (itemCount == 0) {
            plugin.getLogger().warning("Duel kit " + kitName + " contains no deserializable items.");
            return 0;
        }
        inventory.clear();
        for (int slot = 0; slot < storage.length; slot++) {
            if (hasItem(storage[slot])) inventory.setItem(slot, storage[slot].clone());
        }
        inventory.setArmorContents(armor);
        inventory.setItemInOffHand(hasItem(offhand) ? offhand.clone() : new ItemStack(Material.AIR));
        player.updateInventory();
        return itemCount;
    }

    private int countItems(ItemStack[] items) {
        int count = 0;
        for (ItemStack item : items) if (hasItem(item)) count += item.getAmount();
        return count;
    }

    private boolean hasItem(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getAmount() > 0;
    }

    private void failBeforeStart(DuelMatch match, String reason) {
        Player sender = Bukkit.getPlayer(match.request.sender);
        Player target = Bukkit.getPlayer(match.request.target);
        if (sender != null) sender.sendMessage(message(reason, NamedTextColor.RED));
        if (target != null) target.sendMessage(message(reason, NamedTextColor.RED));
        if (match.world != null) deleteArena(match);
        else {
            String worldName = match.worldName;
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv delete " + worldName);
            Bukkit.getScheduler().runTaskLater(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv confirm"), 2L);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (activeMatch == match && Bukkit.getWorld(worldName) == null) activeMatch = null;
            }, 40L);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof DuelMenu menu)) return;
        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        int slot = event.getRawSlot();
        if (menu.type == MenuType.KIT_EDITOR) {
            if (!player.isOp()) {
                event.setCancelled(true);
                player.closeInventory();
                return;
            }
            if (slot >= menu.inventory.getSize()) {
                event.setCancelled(false);
                return;
            }
            if (slot >= 0 && slot < 42) {
                ItemStack current = menu.inventory.getItem(slot);
                ItemStack cursor = event.getCursor();
                boolean cursorEmpty = cursor == null || cursor.getType().isAir();
                if (current != null && !current.getType().isAir()) {
                    event.setCancelled(true);
                    if (cursorEmpty && event.isRightClick()) menu.inventory.setItem(slot, null);
                } else {
                    event.setCancelled(false);
                }
                return;
            }
            event.setCancelled(true);
            handleKitEditorClick(player, menu, slot);
            return;
        }
        event.setCancelled(true);
        switch (menu.type) {
            case SETUP -> handleSetupClick(player, menu, slot);
            case REQUEST -> handleRequestClick(player, menu, slot);
            case KIT_LIST -> handleKitListClick(player, menu, slot);
            default -> { }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof DuelMenu menu)) return;
        if (menu.type != MenuType.KIT_EDITOR) {
            event.setCancelled(true);
            return;
        }
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= 42 && rawSlot < menu.inventory.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void handleKitListClick(Player player, DuelMenu menu, int slot) {
        List<String> kits = kitNames();
        if (slot >= 0 && slot < 45) {
            int index = menu.page * 45 + slot;
            if (index < kits.size()) openKitEditor(player, kits.get(index));
        } else if (slot == 45 && menu.page > 0) {
            openKitList(player, menu.page - 1);
        } else if (slot == 49) {
            player.closeInventory();
        } else if (slot == 53 && (menu.page + 1) * 45 < kits.size()) {
            openKitList(player, menu.page + 1);
        }
    }

    private void handleKitEditorClick(Player player, DuelMenu menu, int slot) {
        if (slot == 42) {
            requestTextEdit(player, menu.kitName, "name");
        } else if (slot == 43) {
            requestTextEdit(player, menu.kitName, "lore");
        } else if (slot == 49) {
            saveEditedKit(player, menu);
        } else if (slot == 53) {
            openKitList(player, 0);
        }
    }

    private void requestTextEdit(Player player, String kitName, String field) {
        pendingTextEdits.put(player.getUniqueId(), new PendingTextEdit(kitName, field));
        player.closeInventory();
        String prompt = field.equals("name")
            ? "Type the kit display name in chat. Use & color codes, or type cancel."
            : "Type lore lines separated by |. Use & color codes, or type cancel.";
        player.sendMessage(message(prompt, NamedTextColor.GOLD));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        DuelMatch match = activeMatch;
        if (match == null || match.ending || match.finishing || !match.snapshots.containsKey(event.getEntity().getUniqueId())) return;
        match.finishing = true;
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        Player loser = event.getEntity();
        Player winner = Bukkit.getPlayer(loser.getUniqueId().equals(match.request.sender) ? match.request.target : match.request.sender);
        if (winner != null) winner.sendMessage(message("You won the duel!", NamedTextColor.GREEN));
        loser.sendMessage(message("You lost the duel. Your pre-duel items will be restored.", NamedTextColor.RED));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        DuelMatch match = activeMatch;
        if (match == null || !match.snapshots.containsKey(event.getPlayer().getUniqueId())) return;
        PlayerSnapshot snapshot = match.snapshots.get(event.getPlayer().getUniqueId());
        event.setRespawnLocation(snapshot.returnLocation);
        Bukkit.getScheduler().runTaskLater(plugin, () -> endMatch(match, "Duel finished."), 1L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        DuelRequest sent = requestsBySender.remove(player.getUniqueId());
        if (sent != null) removeRequest(sent, player.getUniqueId(), true);
        DuelRequest received = requestsByTarget.remove(player.getUniqueId());
        if (received != null) removeRequest(received, player.getUniqueId(), true);
        DuelMatch match = activeMatch;
        if (match != null && match.snapshots.containsKey(player.getUniqueId()) && !match.ending) {
            Player winner = Bukkit.getPlayer(player.getUniqueId().equals(match.request.sender) ? match.request.target : match.request.sender);
            if (winner != null) winner.sendMessage(message(player.getName() + " left the duel. You win.", NamedTextColor.GREEN));
            endMatch(match, player.getName() + " left the duel.");
        }
    }

    private void removeRequest(DuelRequest request, UUID disconnectedPlayer, boolean notifyOther) {
        requestsByTarget.remove(request.target, request);
        requestsBySender.remove(request.sender, request);
        if (request.expiry != null) request.expiry.cancel();
        if (!notifyOther) return;
        UUID otherId = request.sender.equals(disconnectedPlayer) ? request.target : request.sender;
        Player other = Bukkit.getPlayer(otherId);
        if (other != null) other.sendMessage(message("Duel request cancelled because the other player disconnected.", NamedTextColor.GRAY));
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!isInActiveMatch(event.getPlayer().getUniqueId())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(message("Commands are disabled during a duel.", NamedTextColor.RED));
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        DuelMatch match = activeMatch;
        if (match == null || match.ending || match.finishing || !match.snapshots.containsKey(event.getPlayer().getUniqueId())) return;
        if (event.getTo() == null || event.getTo().getWorld() != match.world) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(message("You cannot leave the duel arena before the round ends.", NamedTextColor.RED));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFinishingDuelDamage(EntityDamageEvent event) {
        DuelMatch match = activeMatch;
        if (match == null || !match.finishing || !(event.getEntity() instanceof Player player)) return;
        if (match.snapshots.containsKey(player.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEnderChestUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
            || event.getClickedBlock().getType() != Material.ENDER_CHEST) return;
        if (isInActiveMatch(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(message("Ender chests are disabled during a duel.", NamedTextColor.RED));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEnderChestOpen(InventoryOpenEvent event) {
        if (event.getInventory().getType() == InventoryType.ENDER_CHEST
            && event.getPlayer() instanceof Player player && isInActiveMatch(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private void endMatch(DuelMatch match, String reason) {
        if (match.ending) return;
        match.ending = true;
        for (Map.Entry<UUID, PlayerSnapshot> entry : match.snapshots.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) continue;
            restorePlayer(player, entry.getValue());
            player.sendMessage(message(reason + " Your original inventory has been restored.", NamedTextColor.GOLD));
        }
        deleteArena(match);
    }

    private void restorePlayer(Player player, PlayerSnapshot snapshot) {
        player.setGameMode(snapshot.gameMode);
        PlayerInventory inventory = player.getInventory();
        inventory.setStorageContents(copyItems(snapshot.storage));
        inventory.setArmorContents(copyItems(snapshot.armor));
        inventory.setItemInOffHand(copyItem(snapshot.offhand));
        player.teleport(snapshot.returnLocation, PlayerTeleportEvent.TeleportCause.PLUGIN);
        player.setAllowFlight(snapshot.allowFlight);
        if (snapshot.allowFlight) player.setFlying(snapshot.flying);
        player.setFallDistance(0);
        player.setFireTicks(snapshot.fireTicks);
        player.setFoodLevel(snapshot.food);
        player.setSaturation(snapshot.saturation);
        player.setExhaustion(snapshot.exhaustion);
        player.setLevel(snapshot.level);
        player.setExp(snapshot.experience);
        player.setTotalExperience(snapshot.totalExperience);
        double maxHealth = player.getMaxHealth();
        player.setHealth(Math.max(1, Math.min(maxHealth, snapshot.health)));
        for (PotionEffect effect : player.getActivePotionEffects()) player.removePotionEffect(effect.getType());
        for (PotionEffect effect : snapshot.effects) player.addPotionEffect(effect);
        player.updateInventory();
    }

    private void deleteArena(DuelMatch match) {
        if (match.worldLoadTask != null) match.worldLoadTask.cancel();
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv delete " + match.worldName);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv confirm");
            pollWorldDeletion(match, 0);
        }, 2L);
    }

    private void pollWorldDeletion(DuelMatch match, int checks) {
        if (Bukkit.getWorld(match.worldName) == null) {
            if (activeMatch == match) activeMatch = null;
            return;
        }
        int timeout = Math.max(10, plugin.getConfig().getInt("arena.cleanup-timeout-seconds", 30) * 2);
        if (checks >= timeout) {
            plugin.getLogger().severe("Multiverse has not deleted duel world " + match.worldName
                + ". New duels are locked to avoid overlapping arenas.");
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> pollWorldDeletion(match, checks + 1), 10L);
    }

    private boolean isInActiveMatch(UUID playerId) {
        return activeMatch != null && !activeMatch.ending && activeMatch.snapshots.containsKey(playerId);
    }

    private void fillMenu(Inventory inventory) {
        ItemStack filler = menuItem(Material.BLACK_STAINED_GLASS_PANE, " ", NamedTextColor.BLACK);
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
    }

    private ItemStack menuItem(Material material, String name, NamedTextColor color) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color, TextDecoration.BOLD));
        item.setItemMeta(meta);
        return item;
    }

    private Component message(String text, NamedTextColor color) {
        return Component.text("DUELS ", NamedTextColor.GOLD, TextDecoration.BOLD)
            .append(Component.text("» ", NamedTextColor.DARK_RED))
            .append(Component.text(text, color));
    }

    private boolean isInActiveMatch(Player player) {
        return isInActiveMatch(player.getUniqueId());
    }

    private static ItemStack[] copyItems(ItemStack[] items) {
        ItemStack[] copied = new ItemStack[items.length];
        for (int index = 0; index < items.length; index++) copied[index] = copyItem(items[index]);
        return copied;
    }

    private static ItemStack copyItem(ItemStack item) {
        return item == null ? null : item.clone();
    }

    public void shutdown() {
        for (DuelRequest request : new HashSet<>(requestsByTarget.values())) removeRequest(request, request.sender, false);
        DuelMatch match = activeMatch;
        if (match != null) {
            if (match.worldLoadTask != null) match.worldLoadTask.cancel();
            for (Map.Entry<UUID, PlayerSnapshot> entry : match.snapshots.entrySet()) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null) restorePlayer(player, entry.getValue());
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv delete " + match.worldName);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv confirm");
        }
    }
}
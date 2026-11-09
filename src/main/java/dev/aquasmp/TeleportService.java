package dev.aquasmp;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class TeleportService implements Listener, CommandExecutor {
    private enum RequestMenu { ACCEPT, DENY }

    private static final class RequestHolder implements InventoryHolder {
        private final UUID requester;
        private Inventory inventory;

        private RequestHolder(UUID requester) { this.requester = requester; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class RtpHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class Request {
        private final UUID sender;
        private BukkitTask expiry;
        private Request(UUID sender) { this.sender = sender; }
    }

    private static final class Teleport {
        private final Location origin;
        private final Runnable destination;
        private final String label;
        private int ticksRemaining;
        private BukkitTask task;

        private Teleport(Location origin, Runnable destination, String label, int ticksRemaining) {
            this.origin = origin;
            this.destination = destination;
            this.label = label;
            this.ticksRemaining = ticksRemaining;
        }
    }

    private final AquaSMPPlugin plugin;
    private final NamespacedKey guiKey;
    private final Map<UUID, Request> requests = new HashMap<>();
    private final Map<UUID, Teleport> teleports = new HashMap<>();
    private final Map<UUID, Location> rtpReservations = new HashMap<>();
    private final Random random = new Random();
    private final Map<String, World.Environment> rtpWorlds = new HashMap<>();

    public TeleportService(AquaSMPPlugin plugin) {
        this.plugin = plugin;
        guiKey = new NamespacedKey(plugin, "tp_gui_enabled");
        rtpWorlds.put("overworld", World.Environment.NORMAL);
        rtpWorlds.put("nether", World.Environment.NETHER);
        rtpWorlds.put("end", World.Environment.THE_END);
    }

    public void setupRtpWorlds() {
        for (Map.Entry<String, World.Environment> entry : rtpWorlds.entrySet()) {
            String worldName = plugin.getConfig().getString("teleport.rtp.worlds." + entry.getKey(), "aqua_" + entry.getKey());
            if (Bukkit.getWorld(worldName) != null) continue;
            if (Bukkit.getPluginManager().isPluginEnabled("Multiverse-Core")) {
                String environment = switch (entry.getValue()) {
                    case NORMAL -> "normal";
                    case NETHER -> "nether";
                    case THE_END -> "end";
                    default -> "normal";
                };
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv create " + worldName + " " + environment);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (Bukkit.getWorld(worldName) == null) createWorld(worldName, entry.getValue());
                }, 40L);
            } else {
                createWorld(worldName, entry.getValue());
            }
        }
    }

    private void createWorld(String worldName, World.Environment environment) {
        try {
            Bukkit.createWorld(new WorldCreator(worldName).environment(environment));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Could not create RTP world " + worldName + ": " + exception.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase()) {
            case "tpa" -> {
                if (!(sender instanceof Player player)) return playerOnly(sender);
                if (args.length != 1) {
                    player.sendMessage(message("Usage: /tpa <player>", NamedTextColor.RED));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null) {
                    player.sendMessage(message("That player is not online.", NamedTextColor.RED));
                    return true;
                }
                sendRequest(player, target);
            }
            case "tpaccept" -> {
                if (!(sender instanceof Player player)) return playerOnly(sender);
                resolveRequest(player, true);
            }
            case "tpadeny" -> {
                if (!(sender instanceof Player player)) return playerOnly(sender);
                resolveRequest(player, false);
            }
            case "tpagui" -> {
                if (!(sender instanceof Player player)) return playerOnly(sender);
                boolean enabled = !player.getPersistentDataContainer().getOrDefault(guiKey, PersistentDataType.BYTE, (byte) 0).equals((byte) 1);
                player.getPersistentDataContainer().set(guiKey, PersistentDataType.BYTE, (byte) (enabled ? 1 : 0));
                player.sendMessage(message("Teleport request menus " + (enabled ? "enabled" : "disabled") + ".", NamedTextColor.AQUA));
            }
            case "spawn" -> {
                if (!(sender instanceof Player player)) return playerOnly(sender);
                Location spawn = getConfiguredSpawn();
                if (spawn == null) player.sendMessage(message("Spawn is not set. Ask an admin to run /cmdsetspawn.", NamedTextColor.RED));
                else startTeleport(player, () -> player.teleport(spawn), "spawn");
            }
            case "cmdsetspawn" -> {
                if (!(sender instanceof Player player)) return playerOnly(sender);
                Location location = player.getLocation();
                plugin.getConfig().set("spawn.world", location.getWorld().getName());
                plugin.getConfig().set("spawn.x", location.getX());
                plugin.getConfig().set("spawn.y", location.getY());
                plugin.getConfig().set("spawn.z", location.getZ());
                plugin.getConfig().set("spawn.yaw", location.getYaw());
                plugin.getConfig().set("spawn.pitch", location.getPitch());
                plugin.saveConfig();
                player.sendMessage(message("Spawn location saved.", NamedTextColor.GREEN));
            }
            case "rtp" -> {
                if (!(sender instanceof Player player)) return playerOnly(sender);
                openRtpMenu(player);
            }
            default -> { return false; }
        }
        return true;
    }

    private boolean playerOnly(CommandSender sender) {
        sender.sendMessage("This command can only be used in game.");
        return true;
    }

    private void sendRequest(Player sender, Player target) {
        if (sender.getUniqueId().equals(target.getUniqueId())) {
            sender.sendMessage(message("You cannot request to teleport to yourself.", NamedTextColor.RED));
            return;
        }
        Request old = requests.remove(target.getUniqueId());
        if (old != null) old.expiry.cancel();
        Request request = new Request(sender.getUniqueId());
        requests.put(target.getUniqueId(), request);
        int expirySeconds = plugin.getConfig().getInt("teleport.request-expiry-seconds", 60);
        request.expiry = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (requests.remove(target.getUniqueId(), request)) {
                sender.sendMessage(message("Your teleport request to " + target.getName() + " expired.", NamedTextColor.RED));
                target.sendMessage(message("Teleport request expired.", NamedTextColor.GRAY));
            }
        }, expirySeconds * 20L);
        sender.sendMessage(message("Teleport request sent to " + target.getName() + ".", NamedTextColor.GREEN));
        target.sendMessage(message(sender.getName() + " wants to teleport to you. Use /tpaccept or /tpadeny.", NamedTextColor.AQUA));
        if (target.getPersistentDataContainer().getOrDefault(guiKey, PersistentDataType.BYTE, (byte) 0) == 1) openRequestMenu(target, sender);
    }

    private void resolveRequest(Player target, boolean accept) {
        Request request = requests.remove(target.getUniqueId());
        if (request == null) {
            target.sendMessage(message("You do not have a pending teleport request.", NamedTextColor.RED));
            return;
        }
        request.expiry.cancel();
        Player sender = Bukkit.getPlayer(request.sender);
        if (!accept) {
            target.sendMessage(message("Teleport request denied.", NamedTextColor.RED));
            if (sender != null) sender.sendMessage(message(target.getName() + " denied your teleport request.", NamedTextColor.RED));
            return;
        }
        if (sender == null) {
            target.sendMessage(message("That player is no longer online.", NamedTextColor.RED));
            return;
        }
        target.sendMessage(message("Accepted " + sender.getName() + "'s teleport request.", NamedTextColor.GREEN));
        sender.sendMessage(message(target.getName() + " accepted your request.", NamedTextColor.GREEN));
        startTeleport(sender, () -> {
            if (target.isOnline()) sender.teleport(target.getLocation());
            else sender.sendMessage(message("Teleport cancelled because the target left.", NamedTextColor.RED));
        }, "teleport to " + target.getName());
    }

    private void openRequestMenu(Player target, Player sender) {
        RequestHolder holder = new RequestHolder(sender.getUniqueId());
        holder.inventory = Bukkit.createInventory(holder, 27, Component.text("Aqua SMP: Teleport Request", NamedTextColor.AQUA));
        ItemStack filler = namedItem(Material.CYAN_STAINED_GLASS_PANE, " ", NamedTextColor.AQUA);
        for (int slot = 0; slot < 27; slot++) holder.inventory.setItem(slot, filler);
        holder.inventory.setItem(11, namedItem(Material.LIME_CONCRETE, "Accept", NamedTextColor.GREEN));
        holder.inventory.setItem(15, namedItem(Material.RED_CONCRETE, "Deny", NamedTextColor.RED));
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(sender);
        meta.displayName(Component.text(sender.getName(), NamedTextColor.AQUA, TextDecoration.BOLD));
        head.setItemMeta(meta);
        holder.inventory.setItem(13, head);
        target.openInventory(holder.inventory);
    }

    @EventHandler
    public void onRequestMenuClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof RequestHolder holder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player target)) return;
            if (event.getRawSlot() == 11) resolveRequest(target, true);
            else if (event.getRawSlot() == 15) resolveRequest(target, false);
            target.closeInventory();
        } else if (event.getView().getTopInventory().getHolder(false) instanceof RtpHolder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            String key = switch (event.getRawSlot()) {
                case 11 -> "overworld";
                case 13 -> "nether";
                case 15 -> "end";
                default -> null;
            };
            if (key != null) startRtp(player, key);
            player.closeInventory();
        }
    }

    private void openRtpMenu(Player player) {
        RtpHolder holder = new RtpHolder();
        holder.inventory = Bukkit.createInventory(holder, 27, Component.text("Aqua SMP: Random Teleport", NamedTextColor.AQUA));
        ItemStack filler = namedItem(Material.BLUE_STAINED_GLASS_PANE, " ", NamedTextColor.AQUA);
        for (int slot = 0; slot < 27; slot++) holder.inventory.setItem(slot, filler);
        holder.inventory.setItem(11, namedItem(Material.GRASS_BLOCK, "Overworld", NamedTextColor.GREEN));
        holder.inventory.setItem(13, namedItem(Material.NETHERRACK, "Nether", NamedTextColor.RED));
        holder.inventory.setItem(15, namedItem(Material.END_STONE, "The End", NamedTextColor.LIGHT_PURPLE));
        player.openInventory(holder.inventory);
    }

    private void startRtp(Player player, String environment) {
        String worldName = plugin.getConfig().getString("teleport.rtp.worlds." + environment, "aqua_" + environment);
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            player.sendMessage(message("RTP world " + worldName + " is not available yet.", NamedTextColor.RED));
            return;
        }
        findRtpLocation(player, world, plugin.getConfig().getInt("teleport.rtp.attempts", 80));
    }

    private void findRtpLocation(Player player, World world, int attemptsLeft) {
        if (!player.isOnline()) return;
        if (attemptsLeft <= 0) {
            player.sendMessage(message("No safe, unoccupied RTP location was found. Try again.", NamedTextColor.RED));
            return;
        }
        int radius = Math.max(100, plugin.getConfig().getInt("teleport.rtp.radius", 8000));
        int x = random.nextInt(radius * 2 + 1) - radius;
        int z = random.nextInt(radius * 2 + 1) - radius;
        int minimumDistance = plugin.getConfig().getInt("teleport.rtp.minimum-distance", 500);
        world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (!world.getWorldBorder().isInside(new Location(world, x, world.getMinHeight(), z))) {
                findRtpLocation(player, world, attemptsLeft - 1);
                return;
            }
            Block floor = world.getHighestBlockAt(x, z);
            Material floorType = floor.getType();
            Block feet = floor.getRelative(0, 1, 0);
            Block head = floor.getRelative(0, 2, 0);
            if (!floorType.isSolid() || floorType == Material.MAGMA_BLOCK || floorType == Material.CAMPFIRE
                || floorType == Material.SOUL_CAMPFIRE || !feet.isPassable() || !head.isPassable()) {
                findRtpLocation(player, world, attemptsLeft - 1);
                return;
            }
            Location destination = new Location(world, x + 0.5, floor.getY() + 1, z + 0.5, player.getLocation().getYaw(), 0);
            double minSquared = (double) minimumDistance * minimumDistance;
            boolean occupied = world.getPlayers().stream().anyMatch(other -> other.getLocation().distanceSquared(destination) < minSquared);
            occupied |= rtpReservations.values().stream().anyMatch(reserved -> reserved.getWorld().equals(world)
                && reserved.distanceSquared(destination) < minSquared);
            if (occupied) {
                findRtpLocation(player, world, attemptsLeft - 1);
                return;
            }
            rtpReservations.put(player.getUniqueId(), destination);
            startTeleport(player, () -> {
                player.teleport(destination);
                rtpReservations.remove(player.getUniqueId());
            }, "random location");
        }));
    }

    private Location getConfiguredSpawn() {
        String worldName = plugin.getConfig().getString("spawn.world");
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world, plugin.getConfig().getDouble("spawn.x"), plugin.getConfig().getDouble("spawn.y"),
            plugin.getConfig().getDouble("spawn.z"), (float) plugin.getConfig().getDouble("spawn.yaw"),
            (float) plugin.getConfig().getDouble("spawn.pitch"));
    }

    private void startTeleport(Player player, Runnable destination, String label) {
        cancelTeleport(player, false);
        int seconds = Math.max(1, plugin.getConfig().getInt("teleport.countdown-seconds", 10));
        Teleport teleport = new Teleport(player.getLocation().clone(), destination, label, seconds * 20);
        teleports.put(player.getUniqueId(), teleport);
        player.sendMessage(message("Teleporting to " + label + " in " + seconds + " seconds. Do not move.", NamedTextColor.AQUA));
        teleport.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tickTeleport(player, teleport), 1L, 1L);
    }

    private void tickTeleport(Player player, Teleport teleport) {
        if (!player.isOnline() || !teleports.containsKey(player.getUniqueId())) {
            finishTeleport(player, teleport);
            return;
        }
        if (!samePosition(teleport.origin, player.getLocation())) {
            cancelTeleport(player, true);
            return;
        }
        teleport.ticksRemaining--;
        if (teleport.ticksRemaining % 20 == 0) {
            int seconds = teleport.ticksRemaining / 20;
            if (seconds > 0) {
                player.sendActionBar(Component.text("Teleporting in " + seconds + "...", NamedTextColor.AQUA));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.4f);
            }
        }
        if (teleport.ticksRemaining <= 0) {
            finishTeleport(player, teleport);
            if (player.isOnline()) {
                teleport.destination.run();
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.1f);
                player.sendMessage(message("Teleport complete.", NamedTextColor.GREEN));
            }
        }
    }

    private boolean samePosition(Location first, Location second) {
        return first.getWorld().equals(second.getWorld())
            && Double.compare(first.getX(), second.getX()) == 0
            && Double.compare(first.getY(), second.getY()) == 0
            && Double.compare(first.getZ(), second.getZ()) == 0;
    }

    private void finishTeleport(Player player, Teleport teleport) {
        if (teleport.task != null) teleport.task.cancel();
        teleports.remove(player.getUniqueId(), teleport);
    }

    private void cancelTeleport(Player player, boolean movement) {
        Teleport teleport = teleports.remove(player.getUniqueId());
        if (teleport == null) return;
        if (teleport.task != null) teleport.task.cancel();
        rtpReservations.remove(player.getUniqueId());
        if (movement) {
            player.playSound(player.getLocation(), Sound.BLOCK_CHAIN_BREAK, 0.8f, 1.0f);
            player.sendMessage(message("Canceled due Movement.", NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Teleport teleport = teleports.get(event.getPlayer().getUniqueId());
        if (teleport == null || event.getTo() == null) return;
        if (!samePosition(teleport.origin, event.getTo())) cancelTeleport(event.getPlayer(), true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancelTeleport(event.getPlayer(), false);
        rtpReservations.remove(event.getPlayer().getUniqueId());
        Request request = requests.remove(event.getPlayer().getUniqueId());
        if (request != null) request.expiry.cancel();
    }

    private ItemStack namedItem(Material material, String name, NamedTextColor color) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(name, color, TextDecoration.BOLD));
        stack.setItemMeta(meta);
        return stack;
    }

    private Component message(String text, NamedTextColor color) {
        return Component.text("Aqua SMP ", NamedTextColor.AQUA, TextDecoration.BOLD)
            .append(Component.text("» ", NamedTextColor.DARK_AQUA)).append(Component.text(text, color));
    }
}
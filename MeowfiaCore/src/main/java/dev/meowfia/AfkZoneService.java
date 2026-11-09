package dev.meowfia;

import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class AfkZoneService implements Listener, CommandExecutor {
    private final MeowfiaCorePlugin plugin;
    private final EconomyService economy;
    private final NamespacedKey wandKey;
    private final NamespacedKey nextRewardKey;
    private BukkitTask rewardTask;

    public AfkZoneService(MeowfiaCorePlugin plugin, EconomyService economy) {
        this.plugin = plugin;
        this.economy = economy;
        wandKey = new NamespacedKey(plugin, "meowfia_afk_zone_wand");
        nextRewardKey = new NamespacedKey(plugin, "meowfia_afk_next_shard_reward");
    }

    public void start() {
        rewardTask = Bukkit.getScheduler().runTaskTimer(plugin, this::rewardPlayers, 20L, 20L);
    }

    public void stop() {
        if (rewardTask != null) rewardTask.cancel();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used in game.");
            return true;
        }
        if (!player.isOp()) {
            player.sendMessage(message("Only operators can set the AFK zone.", NamedTextColor.RED));
            return true;
        }
        if (args.length != 0) {
            player.sendMessage(message("Usage: /afkz", NamedTextColor.RED));
            return true;
        }
        giveWand(player);
        player.sendMessage(message("Left-click the highest corner, then right-click the opposite lowest corner.", NamedTextColor.GOLD));
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onWandUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack held = event.getItem();
        if (!player.isOp() || held == null || !held.hasItemMeta()
            || !held.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE)) return;
        Action action = event.getAction();
        if ((action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) || event.getClickedBlock() == null) return;
        event.setCancelled(true);
        Location point = event.getClickedBlock().getLocation();
        if (action == Action.LEFT_CLICK_BLOCK) {
            savePoint("high", point);
            player.sendMessage(message("Highest corner set. Right-click the opposite lowest corner.", NamedTextColor.GREEN));
            return;
        }
        String highWorld = plugin.getConfig().getString("afk.high.world", "");
        if (highWorld.isBlank()) {
            player.sendMessage(message("Set the highest corner first with a left-click.", NamedTextColor.RED));
            return;
        }
        if (!highWorld.equals(point.getWorld().getName()) || point.getBlockY() > plugin.getConfig().getInt("afk.high.y")) {
            player.sendMessage(message("The lowest corner must be in the same world and no higher than the first corner.", NamedTextColor.RED));
            return;
        }
        savePoint("low", point);
        player.sendMessage(message("AFK zone saved. Shard amount and cooldown are in config.yml.", NamedTextColor.GREEN));
    }

    private void savePoint(String key, Location location) {
        String path = "afk." + key;
        plugin.getConfig().set(path + ".world", location.getWorld().getName());
        plugin.getConfig().set(path + ".x", location.getBlockX());
        plugin.getConfig().set(path + ".y", location.getBlockY());
        plugin.getConfig().set(path + ".z", location.getBlockZ());
        plugin.saveConfig();
    }

    private void rewardPlayers() {
        if (!plugin.getConfig().getBoolean("afk.enabled", true)) return;
        long reward = plugin.getConfig().getLong("afk.reward-shards", 1);
        if (reward < 1) return;
        long cooldown = Math.max(1, plugin.getConfig().getLong("afk.cooldown-seconds", 60)) * 1000L;
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!insideZone(player.getLocation())) continue;
            long nextReward = player.getPersistentDataContainer().getOrDefault(nextRewardKey, PersistentDataType.LONG, 0L);
            if (now < nextReward) continue;
            economy.grantShards(player, reward);
            player.getPersistentDataContainer().set(nextRewardKey, PersistentDataType.LONG, now + cooldown);
            player.sendMessage(message("AFK reward: +" + reward + " shards.", NamedTextColor.GREEN));
        }
    }

    private boolean insideZone(Location location) {
        String highWorldName = plugin.getConfig().getString("afk.high.world", "");
        String lowWorldName = plugin.getConfig().getString("afk.low.world", "");
        if (highWorldName.isBlank() || !highWorldName.equals(lowWorldName)
            || !location.getWorld().getName().equals(highWorldName)) return false;
        World world = Bukkit.getWorld(highWorldName);
        if (world == null) return false;
        int highX = plugin.getConfig().getInt("afk.high.x");
        int highY = plugin.getConfig().getInt("afk.high.y");
        int highZ = plugin.getConfig().getInt("afk.high.z");
        int lowX = plugin.getConfig().getInt("afk.low.x");
        int lowY = plugin.getConfig().getInt("afk.low.y");
        int lowZ = plugin.getConfig().getInt("afk.low.z");
        return between(location.getBlockX(), lowX, highX)
            && between(location.getBlockY(), lowY, highY)
            && between(location.getBlockZ(), lowZ, highZ);
    }

    private boolean between(int value, int first, int second) {
        return value >= Math.min(first, second) && value <= Math.max(first, second);
    }

    private void giveWand(Player player) {
        ItemStack wand = new ItemStack(Material.MAGMA_CREAM);
        ItemMeta meta = wand.getItemMeta();
        meta.displayName(Component.text("AFK Zone Wand", NamedTextColor.GOLD, TextDecoration.BOLD));
        meta.lore(List.of(Component.text("Left-click: highest corner", NamedTextColor.GRAY),
            Component.text("Right-click: lowest opposite corner", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        wand.setItemMeta(meta);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(wand);
        leftovers.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
    }

    private Component message(String text, NamedTextColor color) {
        return Component.text("Meowfia SMP ", NamedTextColor.RED, TextDecoration.BOLD)
            .append(Component.text("» ", NamedTextColor.DARK_RED))
            .append(Component.text(text, color));
    }
}
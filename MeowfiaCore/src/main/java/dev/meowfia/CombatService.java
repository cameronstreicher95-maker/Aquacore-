package dev.meowfia;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class CombatService implements Listener {
    private final MeowfiaCorePlugin plugin;
    private final Map<UUID, Long> taggedUntil = new HashMap<>();
    private final Map<UUID, Set<UUID>> opponents = new HashMap<>();
    private BukkitTask cleanupTask;

    public CombatService(MeowfiaCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::expireTags, 20L, 20L);
    }

    public void stop() {
        if (cleanupTask != null) cleanupTask.cancel();
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (event.isCancelled() || event.getFinalDamage() <= 0 || !(event.getEntity() instanceof Player victim)) return;
        Player attacker = attackingPlayer(event.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        long expiry = System.currentTimeMillis() + plugin.getConfig().getLong("combat.tag-seconds", 30) * 1000L;
        boolean victimWasTagged = taggedUntil.containsKey(victim.getUniqueId());
        boolean attackerWasTagged = taggedUntil.containsKey(attacker.getUniqueId());
        taggedUntil.put(victim.getUniqueId(), expiry);
        taggedUntil.put(attacker.getUniqueId(), expiry);
        opponents.computeIfAbsent(victim.getUniqueId(), ignored -> new HashSet<>()).add(attacker.getUniqueId());
        opponents.computeIfAbsent(attacker.getUniqueId(), ignored -> new HashSet<>()).add(victim.getUniqueId());
        if (!victimWasTagged) victim.sendMessage(message("You are in combat. Do not log out or use commands.", NamedTextColor.RED));
        if (!attackerWasTagged) attacker.sendMessage(message("You are in combat. Do not log out or use commands.", NamedTextColor.RED));
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!isTagged(event.getPlayer().getUniqueId())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(message("Commands are blocked while you are in combat.", NamedTextColor.RED));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (!isTagged(player.getUniqueId())) return;
        clearFight(player.getUniqueId());
        player.sendMessage(message("Combat logging is not allowed. You have been killed.", NamedTextColor.RED));
        if (player.getHealth() > 0) player.setHealth(0);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        UUID playerId = event.getEntity().getUniqueId();
        if (!taggedUntil.containsKey(playerId)) return;
        clearFight(playerId);
    }

    private Player attackingPlayer(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        return null;
    }

    private boolean isTagged(UUID playerId) {
        Long expiry = taggedUntil.get(playerId);
        if (expiry == null) return false;
        if (expiry <= System.currentTimeMillis()) {
            clearTag(playerId);
            return false;
        }
        return true;
    }

    private void expireTags() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> entry : new HashMap<>(taggedUntil).entrySet()) {
            if (entry.getValue() > now) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) player.sendMessage(message("You are no longer in combat.", NamedTextColor.GREEN));
            clearFight(entry.getKey());
        }
    }

    private void clearFight(UUID playerId) {
        Set<UUID> linkedPlayers = new HashSet<>(opponents.getOrDefault(playerId, Set.of()));
        clearTag(playerId);
        for (UUID opponentId : linkedPlayers) clearTag(opponentId);
    }

    private void clearTag(UUID playerId) {
        taggedUntil.remove(playerId);
        Set<UUID> linkedPlayers = opponents.remove(playerId);
        if (linkedPlayers == null) return;
        for (UUID linkedId : linkedPlayers) {
            Set<UUID> theirOpponents = opponents.get(linkedId);
            if (theirOpponents != null) {
                theirOpponents.remove(playerId);
                if (theirOpponents.isEmpty()) opponents.remove(linkedId);
            }
        }
    }

    private Component message(String text, NamedTextColor color) {
        return Component.text("Meowfia SMP ", NamedTextColor.RED, TextDecoration.BOLD)
            .append(Component.text("» ", NamedTextColor.DARK_RED)).append(Component.text(text, color));
    }
}
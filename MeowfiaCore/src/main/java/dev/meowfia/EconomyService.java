package dev.meowfia;

import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

public final class EconomyService {
    private final MeowfiaCorePlugin plugin;
    private final NamespacedKey shardKey;
    private Object vaultProvider;
    private Method getBalance;
    private Method withdrawPlayer;
    private Method depositPlayer;

    public EconomyService(MeowfiaCorePlugin plugin) {
        this.plugin = plugin;
        shardKey = new NamespacedKey(plugin, "shards");
        hookVault();
    }

    public boolean hasVault() {
        if (vaultProvider == null) hookVault();
        return vaultProvider != null;
    }

    public double balance(Player player, boolean shards) {
        if (shards) {
            return player.getPersistentDataContainer().getOrDefault(shardKey, PersistentDataType.LONG, 0L);
        }
        if (vaultProvider == null) hookVault();
        if (vaultProvider == null) {
            return 0;
        }
        try {
            return ((Number) getBalance.invoke(vaultProvider, player)).doubleValue();
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("Could not read Vault balance: " + exception.getMessage());
            return 0;
        }
    }

    public boolean withdraw(Player player, double amount, boolean shards) {
        if (amount < 0 || balance(player, shards) < amount) {
            return false;
        }
        if (shards) {
            long current = (long) balance(player, true);
            player.getPersistentDataContainer().set(shardKey, PersistentDataType.LONG, current - (long) amount);
            return true;
        }
        return invokeEconomy(withdrawPlayer, player, amount);
    }

    public boolean deposit(Player player, double amount) {
        if (vaultProvider == null) hookVault();
        return amount >= 0 && invokeEconomy(depositPlayer, player, amount);
    }

    public void grantShards(Player player, long amount) {
        long balance = player.getPersistentDataContainer().getOrDefault(shardKey, PersistentDataType.LONG, 0L);
        player.getPersistentDataContainer().set(shardKey, PersistentDataType.LONG, Math.max(0, balance + amount));
    }

    public double balance(OfflinePlayer player) {
        if (vaultProvider == null) hookVault();
        if (vaultProvider == null) {
            return 0;
        }
        try {
            return ((Number) getBalance.invoke(vaultProvider, player)).doubleValue();
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("Could not read Vault balance: " + exception.getMessage());
            return 0;
        }
    }

    private boolean invokeEconomy(Method method, Player player, double amount) {
        if (vaultProvider == null || method == null) {
            return false;
        }
        try {
            Object response = method.invoke(vaultProvider, player, amount);
            return response != null && (boolean) response.getClass().getMethod("transactionSuccess").invoke(response);
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("Vault transaction failed: " + exception.getMessage());
            return false;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void hookVault() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return;
        }
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            vaultProvider = Bukkit.getServicesManager().load((Class) economyClass);
            if (vaultProvider == null) {
                return;
            }
            getBalance = economyClass.getMethod("getBalance", OfflinePlayer.class);
            withdrawPlayer = economyClass.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            depositPlayer = economyClass.getMethod("depositPlayer", OfflinePlayer.class, double.class);
        } catch (ReflectiveOperationException exception) {
            vaultProvider = null;
            plugin.getLogger().warning("Vault is present, but no compatible economy provider was found.");
        }
    }
}
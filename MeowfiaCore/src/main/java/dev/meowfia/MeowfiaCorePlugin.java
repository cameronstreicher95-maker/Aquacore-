package dev.meowfia;

import org.bukkit.plugin.java.JavaPlugin;

public final class MeowfiaCorePlugin extends JavaPlugin {
    private CombatService combatService;
    private ScoreboardService scoreboardService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        EconomyService economy = new EconomyService(this);
        ShopManager shop = new ShopManager(this, economy);
        TeleportService teleports = new TeleportService(this);
        combatService = new CombatService(this);
        scoreboardService = new ScoreboardService(this, economy, true);

        getServer().getPluginManager().registerEvents(shop, this);
        getServer().getPluginManager().registerEvents(teleports, this);
        getServer().getPluginManager().registerEvents(combatService, this);
        scoreboardService.start();
        registerExecutor(shop, "shop", "sell", "meowfiacore");
        registerExecutor(teleports, "tpa", "tpaccept", "tpadeny", "tpagui", "spawn", "cmdsetspawn", "rtp");
        getServer().getScheduler().runTaskLater(this, teleports::setupRtpWorlds, 1L);
        combatService.start();
        getLogger().info("Meowfia SMP is enabled. Vault economy: " + economy.hasVault());
    }

    private void registerExecutor(org.bukkit.command.CommandExecutor executor, String... commands) {
        for (String command : commands) {
            if (getCommand(command) == null) {
                getLogger().warning("Command /" + command + " is missing from plugin.yml.");
            } else {
                getCommand(command).setExecutor(executor);
            }
        }
    }

    @Override
    public void onDisable() {
        if (combatService != null) combatService.stop();
        if (scoreboardService != null) scoreboardService.stop();
    }
}
package dev.aquasmp;

import org.bukkit.plugin.java.JavaPlugin;

public final class AquaSMPPlugin extends JavaPlugin {
    private CombatService combatService;
    private ScoreboardService scoreboardService;
    private AfkZoneService afkZoneService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        EconomyService economy = new EconomyService(this);
        ShopManager shop = new ShopManager(this, economy);
        TeleportService teleports = new TeleportService(this);
        combatService = new CombatService(this);
        scoreboardService = new ScoreboardService(this, economy, false);
        afkZoneService = new AfkZoneService(this, economy);

        getServer().getPluginManager().registerEvents(shop, this);
        getServer().getPluginManager().registerEvents(teleports, this);
        getServer().getPluginManager().registerEvents(combatService, this);
        getServer().getPluginManager().registerEvents(afkZoneService, this);
        scoreboardService.start();
        afkZoneService.start();
        registerExecutor(shop, "shop", "sell", "aquasmp");
        registerExecutor(teleports, "tpa", "tpaccept", "tpadeny", "tpagui", "spawn", "cmdsetspawn", "rtp");
        registerExecutor(afkZoneService, "afkz");
        getServer().getScheduler().runTaskLater(this, teleports::setupRtpWorlds, 1L);
        combatService.start();
        getLogger().info("Aqua SMP is enabled. Vault economy: " + economy.hasVault());
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
        if (afkZoneService != null) afkZoneService.stop();
    }
}
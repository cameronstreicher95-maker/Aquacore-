package dev.smpduels;

import org.bukkit.plugin.java.JavaPlugin;

public final class SMPDuelsPlugin extends JavaPlugin {
    private DuelManager duelManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        duelManager = new DuelManager(this);
        getServer().getPluginManager().registerEvents(duelManager, this);
        register("duel", "duelaccept", "dueldeny", "ckit", "kit");
        getLogger().info("SMPDuels enabled. Multiverse-Core is active.");
    }

    @Override
    public void onDisable() {
        if (duelManager != null) duelManager.shutdown();
    }

    private void register(String... commandNames) {
        for (String commandName : commandNames) {
            if (getCommand(commandName) == null) {
                getLogger().warning("Command /" + commandName + " is missing from plugin.yml.");
            } else {
                getCommand(commandName).setExecutor(duelManager);
            }
        }
    }
}
package dev.aquasmp;

import java.io.File;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public final class ScoreboardService implements Listener {
    private static final ChatColor[] ENTRIES = {
        ChatColor.BLACK, ChatColor.DARK_BLUE, ChatColor.DARK_GREEN, ChatColor.DARK_AQUA,
        ChatColor.DARK_RED, ChatColor.DARK_PURPLE, ChatColor.GOLD, ChatColor.GRAY,
        ChatColor.DARK_GRAY, ChatColor.BLUE, ChatColor.GREEN, ChatColor.AQUA,
        ChatColor.RED, ChatColor.LIGHT_PURPLE, ChatColor.YELLOW, ChatColor.WHITE
    };

    private static final class BoardState {
        private final Scoreboard previous;
        private final Scoreboard scoreboard;
        private final Objective objective;
        private final List<Team> teams;

        private BoardState(Scoreboard previous, Scoreboard scoreboard, Objective objective, List<Team> teams) {
            this.previous = previous;
            this.scoreboard = scoreboard;
            this.objective = objective;
            this.teams = teams;
        }
    }

    private final JavaPlugin plugin;
    private final EconomyService economy;
    private final Map<UUID, BoardState> boards = new HashMap<>();
    private final boolean meowfiaTheme;
    private YamlConfiguration scoreboardConfig;
    private BukkitTask updateTask;

    public ScoreboardService(JavaPlugin plugin, EconomyService economy, boolean meowfiaTheme) {
        this.plugin = plugin;
        this.economy = economy;
        this.meowfiaTheme = meowfiaTheme;
    }

    public void start() {
        loadScoreboardConfig();
        if (!scoreboardConfig.getBoolean("enabled", true)) return;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateOnlinePlayers, 1L,
            Math.max(20, scoreboardConfig.getLong("update-ticks", 40)));
    }

    private void loadScoreboardConfig() {
        File directory = new File(plugin.getDataFolder(), "scoreboard");
        if (!directory.exists() && !directory.mkdirs()) {
            plugin.getLogger().warning("Could not create scoreboard config directory.");
        }
        File file = new File(directory, "scoreboard.yml");
        if (!file.exists()) plugin.saveResource("scoreboard/scoreboard.yml", false);
        scoreboardConfig = YamlConfiguration.loadConfiguration(file);
    }

    public void stop() {
        if (updateTask != null) updateTask.cancel();
        for (Player player : Bukkit.getOnlinePlayers()) restore(player);
        boards.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> update(event.getPlayer()), 1L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        restore(event.getPlayer());
    }

    private void updateOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) update(player);
    }

    private void update(Player player) {
        BoardState state = boards.computeIfAbsent(player.getUniqueId(), ignored -> createBoard(player));
        if (player.getScoreboard() != state.scoreboard) player.setScoreboard(state.scoreboard);
        List<Component> lines = lines(player);
        for (int index = 0; index < state.teams.size(); index++) state.teams.get(index).prefix(lines.get(index));
    }

    private BoardState createBoard(Player player) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        Scoreboard previous = player.getScoreboard();
        Scoreboard scoreboard = manager.getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective("smpstats", Criteria.DUMMY, title());
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        List<Team> teams = new ArrayList<>();
        List<Component> initialLines = lines(player);
        for (int index = 0; index < initialLines.size(); index++) {
            String teamName = "line" + index;
            String entry = ENTRIES[index].toString();
            Team team = scoreboard.registerNewTeam(teamName);
            team.addEntry(entry);
            team.prefix(initialLines.get(index));
            objective.getScore(entry).setScore(initialLines.size() - index);
            teams.add(team);
        }
        return new BoardState(previous, scoreboard, objective, teams);
    }

    private List<Component> lines(Player player) {
        String money = economy.hasVault() ? NumberFormat.getNumberInstance(Locale.US).format(economy.balance(player, false)) : "not hooked";
        Map<String, String> values = new HashMap<>();
        values.put("%player%", player.getName());
        values.put("%display_name%", PlainTextComponentSerializer.plainText().serialize(player.displayName()));
        values.put("%money%", money);
        values.put("%kills%", Integer.toString(player.getStatistic(Statistic.PLAYER_KILLS)));
        values.put("%deaths%", Integer.toString(player.getStatistic(Statistic.DEATHS)));
        values.put("%shards%", NumberFormat.getNumberInstance(Locale.US).format((long) economy.balance(player, true)));
        values.put("%online%", Integer.toString(Bukkit.getOnlinePlayers().size()));
        values.put("%max_players%", Integer.toString(Bukkit.getMaxPlayers()));
        values.put("%world%", player.getWorld().getName());
        return scoreboardConfig.getStringList("lines").stream().limit(16)
            .<Component>map(line -> {
                String rendered = line;
                for (Map.Entry<String, String> value : values.entrySet()) rendered = rendered.replace(value.getKey(), value.getValue());
                return LegacyComponentSerializer.legacyAmpersand().deserialize(rendered);
            }).toList();
    }

    private Component title() {
        String defaultTitle = meowfiaTheme ? "&c&lMEOWFIA &6&lSMP" : "&b&lAQUA &3&lSMP";
        return LegacyComponentSerializer.legacyAmpersand().deserialize(scoreboardConfig.getString("title", defaultTitle));
    }

    private void restore(Player player) {
        BoardState state = boards.remove(player.getUniqueId());
        if (state != null && player.getScoreboard() == state.scoreboard) player.setScoreboard(state.previous);
    }
}
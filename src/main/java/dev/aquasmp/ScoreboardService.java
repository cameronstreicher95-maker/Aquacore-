package dev.aquasmp;

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
    private BukkitTask updateTask;

    public ScoreboardService(JavaPlugin plugin, EconomyService economy, boolean meowfiaTheme) {
        this.plugin = plugin;
        this.economy = economy;
        this.meowfiaTheme = meowfiaTheme;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("scoreboard.enabled", true)) return;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateOnlinePlayers, 1L,
            Math.max(20, plugin.getConfig().getLong("scoreboard.update-ticks", 40)));
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
        NamedTextColor primary = meowfiaTheme ? NamedTextColor.RED : NamedTextColor.AQUA;
        NamedTextColor secondary = meowfiaTheme ? NamedTextColor.GOLD : NamedTextColor.DARK_AQUA;
        NamedTextColor moneyColor = meowfiaTheme ? NamedTextColor.GOLD : NamedTextColor.GREEN;
        String money = economy.hasVault() ? NumberFormat.getNumberInstance(Locale.US).format(economy.balance(player, false)) : "not hooked";
        return List.of(
            Component.text("   " + (meowfiaTheme ? "LAVA LEDGER" : "TIDE LEDGER"), primary, TextDecoration.BOLD),
            Component.text("----------------------", secondary),
            Component.text("MONEY   ", secondary).append(Component.text(money, moneyColor, TextDecoration.BOLD)),
            Component.text("KILLS   ", secondary).append(Component.text(player.getStatistic(Statistic.PLAYER_KILLS), NamedTextColor.GREEN)),
            Component.text("DEATHS  ", secondary).append(Component.text(player.getStatistic(Statistic.DEATHS), NamedTextColor.RED)),
            Component.text("SHARDS  ", secondary).append(Component.text((long) economy.balance(player, true), meowfiaTheme ? NamedTextColor.GOLD : NamedTextColor.LIGHT_PURPLE)),
            Component.text("ONLINE  ", secondary).append(Component.text(Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers(), primary)),
            Component.text("----------------------", secondary)
        );
    }

    private Component title() {
        if (meowfiaTheme) {
            return Component.text("MEOWFIA", NamedTextColor.RED, TextDecoration.BOLD)
                .append(Component.text(" SMP", NamedTextColor.GOLD, TextDecoration.BOLD));
        }
        return Component.text("AQUA", NamedTextColor.AQUA, TextDecoration.BOLD)
            .append(Component.text(" SMP", NamedTextColor.DARK_AQUA, TextDecoration.BOLD));
    }

    private void restore(Player player) {
        BoardState state = boards.remove(player.getUniqueId());
        if (state != null && player.getScoreboard() == state.scoreboard) player.setScoreboard(state.previous);
    }
}
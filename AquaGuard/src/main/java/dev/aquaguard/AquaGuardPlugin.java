package dev.aquaguard;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

public final class AquaGuardPlugin extends JavaPlugin implements Listener {
    private static final class MovementState {
        private Location lastGrounded;
        private int hoverSamples;
    }

    private static final class PlacementWindow {
        private long startedAt;
        private int placements;

        private void record(long now) {
            if (now - startedAt >= 1000) {
                startedAt = now;
                placements = 0;
            }
            placements++;
        }
    }

    private final Map<UUID, MovementState> movementStates = new HashMap<>();
    private final Map<UUID, PlacementWindow> placementWindows = new HashMap<>();
    private final Map<String, Long> lastAlerts = new HashMap<>();
    private HttpClient httpClient;
    private ExecutorService discordExecutor;
    private URI webhookUri;
    private BukkitTask flightTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        configureWebhook();
        discordExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "AquaGuard-Discord");
            thread.setDaemon(true);
            return thread;
        });
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .executor(discordExecutor)
            .build();

        getServer().getPluginManager().registerEvents(this, this);
        long interval = Math.max(1, getConfig().getLong("checks.flight.sample-interval-ticks", 5));
        flightTask = Bukkit.getScheduler().runTaskTimer(this, this::inspectFlight, interval, interval);
        getLogger().info("AquaGuard enabled. No bans are issued by this plugin.");
    }

    @Override
    public void onDisable() {
        if (flightTask != null) flightTask.cancel();
        if (discordExecutor != null) discordExecutor.shutdownNow();
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlightToggle(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (!event.isFlying() || isExempt(player) || !getConfig().getBoolean("checks.flight.enabled", true)) return;
        event.setCancelled(true);
        player.setFlying(false);
        alert(player, "FLIGHT", "Unauthorized flight toggle was blocked.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player) || !getConfig().getBoolean("checks.fast-place.enabled", true)) return;
        long now = System.currentTimeMillis();
        PlacementWindow window = placementWindows.computeIfAbsent(player.getUniqueId(), ignored -> {
            PlacementWindow created = new PlacementWindow();
            created.startedAt = now;
            return created;
        });
        window.record(now);
        int maximum = Math.max(1, getConfig().getInt("checks.fast-place.max-placements-per-second", 40));
        if (window.placements > maximum) {
            event.setCancelled(true);
            alert(player, "FAST_PLACE", "Block placement rate exceeded " + maximum + " per second.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!getConfig().getBoolean("checks.flight.enabled", true) || event.getTo() == null) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() != to.getWorld() || from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;
        if (player.isOnGround()) {
            movementStates.computeIfAbsent(player.getUniqueId(), ignored -> new MovementState()).lastGrounded = to.clone();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        movementStates.remove(playerId);
        placementWindows.remove(playerId);
    }

    private void inspectFlight() {
        int requiredSamples = Math.max(2, getConfig().getInt("checks.flight.hover-samples", 8));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isExempt(player)) {
                MovementState state = movementStates.get(player.getUniqueId());
                if (state != null) state.hoverSamples = 0;
                continue;
            }

            MovementState state = movementStates.computeIfAbsent(player.getUniqueId(), ignored -> new MovementState());
            if (player.isOnGround()) {
                state.lastGrounded = player.getLocation().clone();
                state.hoverSamples = 0;
                continue;
            }
            if (isInLegitimateAirMovement(player)) {
                state.hoverSamples = 0;
                continue;
            }

            double verticalVelocity = player.getVelocity().getY();
            if (Math.abs(verticalVelocity) <= 0.025 && player.getFallDistance() < 0.5f) state.hoverSamples++;
            else state.hoverSamples = 0;

            if (state.hoverSamples < requiredSamples) continue;
            alert(player, "FLIGHT", "Suspicious mid-air hover was detected and corrected.");
            if (state.lastGrounded != null && state.lastGrounded.getWorld() == player.getWorld()) {
                player.teleport(state.lastGrounded);
                player.setVelocity(new Vector(0, 0, 0));
                player.setFallDistance(0);
            }
            state.hoverSamples = 0;
        }
    }

    private boolean isExempt(Player player) {
        GameMode mode = player.getGameMode();
        return player.hasPermission("aquaguard.bypass")
            || mode == GameMode.CREATIVE
            || mode == GameMode.SPECTATOR
            || player.getAllowFlight();
    }

    private boolean isInLegitimateAirMovement(Player player) {
        if (player.isGliding() || player.isSwimming() || player.isInsideVehicle()
            || player.hasPotionEffect(PotionEffectType.LEVITATION)
            || player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) return true;
        Block feet = player.getLocation().getBlock();
        Block below = feet.getRelative(0, -1, 0);
        return feet.isLiquid() || below.isLiquid() || isClimbable(feet.getType()) || isClimbable(below.getType());
    }

    private boolean isClimbable(Material material) {
        return switch (material) {
            case LADDER, VINE, SCAFFOLDING, WEEPING_VINES, WEEPING_VINES_PLANT,
                TWISTING_VINES, TWISTING_VINES_PLANT, CAVE_VINES, CAVE_VINES_PLANT -> true;
            default -> false;
        };
    }

    private void alert(Player player, String check, String details) {
        long now = System.currentTimeMillis();
        String key = player.getUniqueId() + ":" + check;
        long cooldown = Math.max(0, getConfig().getLong("discord.alert-cooldown-seconds", 15)) * 1000L;
        Long lastSent = lastAlerts.get(key);
        if (lastSent != null && now - lastSent < cooldown) return;
        lastAlerts.put(key, now);
        sendDiscordAlert(player, check, details);
    }

    private void configureWebhook() {
        String configuredUrl = getConfig().getString("discord.webhook-url", "").trim();
        if (configuredUrl.isEmpty()) return;
        try {
            URI candidate = URI.create(configuredUrl);
            String host = candidate.getHost();
            if (!"https".equalsIgnoreCase(candidate.getScheme()) || host == null
                || !(host.equalsIgnoreCase("discord.com") || host.equalsIgnoreCase("discordapp.com"))
                || !candidate.getPath().startsWith("/api/webhooks/")) {
                getLogger().warning("Discord webhook URL is not a supported HTTPS Discord webhook; alerts are disabled.");
                return;
            }
            webhookUri = candidate;
        } catch (IllegalArgumentException exception) {
            getLogger().warning("Discord webhook URL is invalid; alerts are disabled.");
        }
    }

    private void sendDiscordAlert(Player player, String check, String details) {
        if (webhookUri == null || httpClient == null || discordExecutor.isShutdown()) return;
        String clientBrand = player.getClientBrandName();
        if (clientBrand == null || clientBrand.isBlank()) clientBrand = "unknown";
        String payload = "{\"allowed_mentions\":{\"parse\":[]},\"embeds\":[{"
            + "\"title\":\"AquaGuard prevented suspicious activity\","
            + "\"color\":16753920,\"fields\":["
            + field("Player", player.getName()) + ","
            + field("Detected check", check) + ","
            + field("Client brand (reported, unverified)", clientBrand) + ","
            + field("Action", details) + "],"
            + "\"footer\":{\"text\":\"No ban was issued. Client brand is self-reported and may be spoofed.\"}}]}";
        HttpRequest request = HttpRequest.newBuilder(webhookUri)
            .timeout(Duration.ofSeconds(8))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
            .thenAccept(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    getLogger().warning("Discord webhook returned HTTP " + response.statusCode() + ".");
                }
            })
            .exceptionally(error -> {
                getLogger().warning("Could not send Discord alert: " + error.getClass().getSimpleName());
                return null;
            });
    }

    private String field(String name, String value) {
        return "{\"name\":\"" + escape(name) + "\",\"value\":\"" + escape(limit(value, 900)) + "\",\"inline\":false}";
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\r", " ").replace("\n", " ");
    }

    private String limit(String value, int maximumLength) {
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }
}
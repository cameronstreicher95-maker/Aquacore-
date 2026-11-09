# Meowfia SMP

A custom Paper survival-server plugin with a red-and-gold lava-themed Meowfia SMP shop, shard gear, teleport menus, safe random destinations, and combat logging.

## Build

- Java 25 and Gradle are required.
- The build targets the published Paper `26.3-pre-2.build.0-alpha` API because that is the available 26.3 Maven artifact.
- Run `gradle build`; the plugin jar is written to `build/libs/MeowfiaCore-1.0.1.jar`.

## Dependencies

- Vault plus an economy provider is required for coin purchases and selling. S-Economy works when it registers a Vault economy provider.
- Multiverse-Core is optional. When installed, Meowfia SMP asks Multiverse to create the configured RTP worlds; Bukkit world creation is used as a fallback.
- Shards use Meowfia SMP's persistent player balances and do not require another economy plugin.

## Commands

- `/shop` opens Gear, Nature, Decorations, the four-item Shard Shop, and Redstone. Gear is unchanged: diamond armor and tools, golden apples, and golden carrots; each diamond armor piece costs 15,000 coins and uses the bold green `Common` label. Nature and Decorations each have 75 products; Nature also has Overworld, Nether, and End build menus with 25 blocks each. Redstone stocks circuitry, rails, switches, sensors, and related components. Shop menus include back/close controls and use black stained-glass filler panes.
- Select a product to adjust quantity with `-10`, `-1`, `+1`, `+10`, or `+64`, then buy into inventory, drop the selection, or fill available inventory slots.
- `/sell` opens the sell inventory. Place items in the deposit area, or put one item on the dispenser to collect matching stacks from your inventory. The emerald shows the estimated total before selling.
- `/tpa <player>`, `/tpaccept`, and `/tpadeny` manage teleport requests. `/tpagui` toggles the accept/deny player-head menu.
- `/spawn` teleports to the configured spawn. Operators can run `/cmdsetspawn` to save their current location.
- `/rtp` opens the Overworld, Nether, and End destination menu. Worlds default to `meowfia_overworld`, `meowfia_nether`, and `meowfia_end`.
- `/meowfiacore shards <player> <amount>` grants shards and requires `meowfiacore.admin`.

All command messages use the Meowfia SMP prefix. Teleports use a configurable countdown and cancel on movement. Player combat tags last 30 seconds by default; commands are blocked while tagged, and logging out while tagged kills the player so normal death drops apply.

## Configuration

`config.yml` controls the teleport countdown and request expiry, RTP world names/radius/separation, combat duration, and shop/sell price multipliers. Spawn coordinates are saved by `/cmdsetspawn`.

## Separate Anti-Cheat Plugin

The standalone [AquaGuard plugin](AquaGuard/README.md) provides conservative server-side flight and rapid-placement checks, Discord bot alerts, and a Paper Anti-Xray configuration template. Build it from the repository root with `gradle -p AquaGuard build`.

## Release Versioning

Each published update uses a new version and GitHub tag, such as `v1.0.1`; existing releases and their assets are kept unchanged. Individual plugin jars use their own version numbers.
# Aqua SMP

A custom Paper survival-server plugin with a blue Aqua SMP shop, shard gear, teleport menus, safe random destinations, and combat logging.

## Build

- Java 25 and Gradle are required.
- The build targets the published Paper `26.3-pre-2.build.0-alpha` API because that is the available 26.3 Maven artifact.
- Run `gradle build`; the plugin jar is written to `build/libs/AquaSMP-1.0.0.jar`.

## Dependencies

- Vault plus an economy provider is required for coin purchases and selling. S-Economy works when it registers a Vault economy provider.
- Multiverse-Core is optional. When installed, Aqua SMP asks Multiverse to create the configured RTP worlds; Bukkit world creation is used as a fallback.
- Shards use Aqua SMP's persistent player balances and do not require another economy plugin.

## Commands

- `/shop` opens Gear, Nature, Decorations, and the four-item Shard Shop. Gear is unchanged: diamond armor and tools, golden apples, and golden carrots; each diamond armor piece costs 15,000 coins and uses the bold green `Common` label. Nature has 47 plant products plus Overworld, Nether, and End build menus with 25 blocks each. Decorations has 72 products. Shop menus include back/close controls and use black stained-glass filler panes.
- Select a product to adjust quantity with `-10`, `-1`, `+1`, `+10`, or `+64`, then buy into inventory, drop the selection, or fill available inventory slots.
- `/sell` opens the sell inventory. Place items in the deposit area, or put one item on the dispenser to collect matching stacks from your inventory. The emerald shows the estimated total before selling.
- `/tpa <player>`, `/tpaccept`, and `/tpadeny` manage teleport requests. `/tpagui` toggles the accept/deny player-head menu.
- `/spawn` teleports to the configured spawn. Operators can run `/cmdsetspawn` to save their current location.
- `/rtp` opens the Overworld, Nether, and End destination menu. Worlds default to `aqua_overworld`, `aqua_nether`, and `aqua_end`.
- `/aquasmp shards <player> <amount>` grants shards and requires `aquasmp.admin`.

All command messages use the Aqua SMP prefix. Teleports use a configurable countdown and cancel on movement. Player combat tags last 30 seconds by default; commands are blocked while tagged, and logging out while tagged kills the player so normal death drops apply.

## Configuration

`config.yml` controls the teleport countdown and request expiry, RTP world names/radius/separation, combat duration, and shop/sell price multipliers. Spawn coordinates are saved by `/cmdsetspawn`.
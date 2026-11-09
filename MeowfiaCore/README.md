# Meowfia SMP

A custom Paper survival-server plugin with a red-and-gold lava-themed Meowfia SMP shop, shard gear, teleport menus, safe random destinations, and combat logging.

## Build

- Java 25 and Gradle are required.
- The build targets the published Paper `26.3-pre-2.build.0-alpha` API because that is the available 26.3 Maven artifact.
- Run `gradle build`; the plugin jar is written to `build/libs/MeowfiaCore-1.0.3.jar`.

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
- `/meowfiacore give <player> <item-id> [amount]` grants any catalog item, including special shard tools, and requires `meowfiacore.admin`. `/meowfiacore shards <player> <amount>` grants shard currency.
- The native red-and-gold sidebar displays money (when Vault is available), kills, deaths, shards, and online players. It requires no PlaceholderAPI.
- Edit `plugins/MeowfiaCore/scoreboard/scoreboard.yml` to change the title, update interval, and lines. Built-in placeholders include `%player%`, `%display_name%`, `%money%`, `%kills%`, `%deaths%`, `%shards%`, `%online%`, `%max_players%`, and `%world%`; use `&` color codes.
- Operators use `/afkz`, then left-click the highest corner and right-click the opposite lowest corner with the wand. `afk.reward-shards` and `afk.cooldown-seconds` in `plugins/MeowfiaCore/config.yml` control the built-in shard reward.
- Shop product settings are generated at `shop.products.<ITEM_ID>` in `config.yml`; per-item enablement, name, material, price, currency, lore, enchantments, ability, unbreakable state, and quantity limit can be edited there.

All command messages use the Meowfia SMP prefix. Teleports use a configurable countdown and cancel on movement. Player combat tags last 30 seconds by default; commands are blocked while tagged, and logging out while tagged kills the player so normal death drops apply.

## Configuration

`config.yml` controls the teleport countdown and request expiry, RTP world names/radius/separation, combat duration, and shop/sell price multipliers. Spawn coordinates are saved by `/cmdsetspawn`.

## Separate Anti-Cheat Plugin

The standalone [AquaGuard plugin](AquaGuard/README.md) provides conservative server-side flight and rapid-placement checks, Discord bot alerts, and a Paper Anti-Xray configuration template. Build it from the repository root with `gradle -p AquaGuard build`.

## Release Versioning

Each published update uses a new version and GitHub tag, such as `v1.0.1`; existing releases and their assets are kept unchanged. Individual plugin jars use their own version numbers.
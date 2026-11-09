# SMPDuels

A separate Paper plugin for consensual duels in temporary Multiverse worlds. It supports saved kits, own-inventory duels, request/accept menus, inventory restoration, and one arena at a time.

## Build and install

- Java 25 and Gradle are required.
- Run `gradle -p SMPDuels build` from the repository root.
- Install `SMPDuels/build/libs/SMPDuels-1.0.0.jar` and Multiverse-Core in the Paper server's `plugins/` directory, then restart. SMPDuels requires Multiverse-Core.

## Commands

- `/duel <player>` opens the kit/loadout menu. Select a saved kit or **Use my inventory**, then press the green button to send the request. The red button closes without sending.
- The challenged player accepts using the green request-menu button or `/duelaccept`, and denies using the red button or `/dueldeny`.
- Operators with `smpduels.admin` save a kit from their current inventory using `/ckit save <kitname>`.

Each accepted duel gets a random, uniquely named Multiverse world. Its border is 80 by 80 blocks and the locator-bar gamerule is enabled. Only one duel can be active; the next arena is not created until Multiverse has unloaded and deleted the prior world.

For both kit and own-inventory duels, SMPDuels snapshots storage, armor, offhand, experience, health, food, effects, and flight settings before the match. On death or disconnect, participants are returned to their saved location and their pre-duel state is restored. Death drops are suppressed. Ender chests and commands are blocked during a duel, and kit items are never saved to a player's persistent inventory.

`config.yml` controls the arena world-name prefix, border size, request expiry, and creation/cleanup timeouts. Multiverse cleanup failure keeps the duel lock active rather than creating overlapping arenas.
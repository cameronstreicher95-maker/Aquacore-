# AquaGuard

AquaGuard is a separate Paper plugin for server-side cheat prevention. It does not ban players. It cancels unauthorized flight toggles, corrects sustained hovering and upward flight to the last grounded location, cancels excessive block placement, and forces non-operators found in Creative back to Survival. Discord bot alerts include the username, check type, and client brand reported by the connection.

## Build and install

- Java 25 and Gradle are required.
- Run `gradle -p AquaGuard build` from the repository root.
- Install `AquaGuard/build/libs/AquaGuard-1.0.2.jar` in the Paper server's `plugins/` directory, then restart.
- Discord alerts are optional. Create a Discord bot with permission to send messages in the target channel, then set `discord.bot-token` and `discord.channel-id` in `plugins/AquaGuard/config.yml` after the first startup. Keep that file private because the bot token is a secret. Do not paste the token into chat or commit it to the repository.

## Anti-xray

Minecraft clients do not reliably disclose xray use, so a plugin cannot identify an xray client with certainty. AquaGuard includes a Paper Anti-Xray template at `AquaGuard/paper-world-defaults.yml`; merge its `anticheat.anti-xray` section into the server's `config/paper-world-defaults.yml` and restart the server. Paper then obfuscates hidden ores before chunks are sent to clients.

## Client identification

Paper exposes the client brand string sent during the connection handshake. AquaGuard reports it as **reported, unverified**: modified clients can spoof it, and it does not prove which cheats are installed. Alerts are rate-limited per player and check. Bot tokens are never sent to player chat or logged.

The checks are deliberately conservative and are not a guarantee against every cheat or exploit. Keep Paper current, use its built-in packet validation, and test the movement thresholds on the server before enabling them for all players.

Operators are exempt from movement checks. Other players with legitimate plugin-granted flight should receive the `aquaguard.bypass` permission; the `allowFlight` flag alone no longer disables flight checks. AquaGuard cannot stop a client from turning a local cheat setting on; it blocks detected server-side effects.
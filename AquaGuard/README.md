# AquaGuard

AquaGuard is a separate Paper plugin for server-side cheat prevention. It does not ban players. It cancels unauthorized flight toggles, corrects sustained hovering to the last grounded location, and cancels excessive block placement. Discord alerts include the username, check type, and client brand reported by the connection.

## Build and install

- Java 25 and Gradle are required.
- Run `gradle -p AquaGuard build` from the repository root.
- Install `AquaGuard/build/libs/AquaGuard-1.0.0.jar` in the Paper server's `plugins/` directory, then restart.
- The webhook is optional. Configure it in `plugins/AquaGuard/config.yml` after the first startup. Keep that file private because the webhook URL is a secret.

## Anti-xray

Minecraft clients do not reliably disclose xray use, so a plugin cannot identify an xray client with certainty. AquaGuard includes a Paper Anti-Xray template at `AquaGuard/paper-world-defaults.yml`; merge its `anticheat.anti-xray` section into the server's `config/paper-world-defaults.yml` and restart the server. Paper then obfuscates hidden ores before chunks are sent to clients.

## Client identification

Paper exposes the client brand string sent during the connection handshake. AquaGuard reports it as **reported, unverified**: modified clients can spoof it, and it does not prove which cheats are installed. Alerts are rate-limited per player and check. No webhook URL is sent to player chat or logged.

The checks are deliberately conservative and are not a guarantee against every cheat or exploit. Keep Paper current, use its built-in packet validation, and test the movement thresholds on the server before enabling them for all players.
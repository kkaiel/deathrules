# DeathRules 1.0.0

Paper 26.3 / Java 25 plugin source project. The GitHub Actions workflow builds the JAR automatically.

## Rules implemented

- Normal and PvP deaths use the same inventory rule.
- Preserve hotbar slots 1-9, all armor slots, and offhand.
- Drop the remaining main inventory (slots 10-36) at the death location.
- After every death, apply Slowness I, Weakness I, and Mining Fatigue I for 120 seconds after respawn.
- Player melee and player-shot projectile damage apply a 15-second combat tag by default.
- Logging out during the combat tag triggers the normal death event pipeline, applying the same inventory rule and respawn effects.
- Experience handling is not overridden; Paper's normal death settings apply.

## Build the JAR with GitHub Actions

1. Sign in to GitHub and create a new repository (private is fine).
2. Extract this ZIP and upload **the contents of the `DeathRules` folder** to the repository root. Keep the hidden `.github` folder; it contains the build workflow.
3. Open the repository's **Actions** tab. If prompted, enable workflows.
4. Select **Build DeathRules** on the left, then click **Run workflow**.
5. Wait for the run to finish successfully. Open that run and find **Artifacts** at the bottom.
6. Download the `DeathRules` artifact and extract it. It contains `DeathRules.jar`.
7. Stop the Minecraft server, back up the server/plugins folder, put `DeathRules.jar` in `plugins/`, then start the server.

A push to the `main` branch also starts a build automatically.

## Configuration

After the first server start, edit `plugins/DeathRules/config.yml` and restart:

- `combat-tag-seconds`: combat tag duration (default 15 seconds).
- `respawn-effects-seconds`: effect duration (default 120 seconds).
- `respawn-effects-amplifier`: 0 means level I.

## Compatibility

The Maven dependency uses PaperMC's documented Maven version range `[26.3.build,)`, and compilation targets Java 25. The JAR has not yet been built or tested on a live server; treat the first installation as a test and keep a backup.

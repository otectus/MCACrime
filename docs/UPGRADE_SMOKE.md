# Legacy World Upgrade Checklist

A manual checklist for testing the Forge 1.20.1 to NeoForge 1.21.1 world upgrade and verifying gameplay regression does not occur. Run this against the production JAR (not a dev build).

## Setup

- [ ] Back up the Phase 0 Forge 1.20.1 world and configs (do not proceed if this is your only copy).
- [ ] Copy the Forge fixture to a new NeoForge 1.21.1 server instance (see Fixture Contents below).
- [ ] Install the production MCA: Crime port JAR from `build/libs/mcacrime-*.jar`.
- [ ] Install MCA Reborn 1.21.1 and only declared dependencies (no optional mods yet).

## Build Artifacts

- [ ] Run `C:\Projects\.mcmod-tools\gradlew-quiet.ps1 -Project "<project-dir>" -Task build` from the NeoForge port project.
- [ ] Confirm the build succeeds and the JAR exists at `build/libs/mcacrime-*.jar`.
- [ ] Jar size is reasonable (check `jar tf build/libs/mcacrime-*.jar | wc -l` for class count).

## Initial Server Startup

- [ ] Start the dedicated server and capture the full first-upgrade log.
- [ ] Confirm log lines `[MCA: Crime] Imported 1.20.1 Forge crime data for player <UUID>.` appear for each legacy player (one per player, on first login).
- [ ] Confirm no errors appear in the network codec or attachment loading.
- [ ] Stop the server cleanly.

## Player Data Verification (After First Startup)

- [ ] Join each fixture player once, causing legacy player attachment import.
- [ ] Compare in-game state (karma/heat/band/wanted status) against the Forge fixture manifest for each player.
- [ ] Compare `/crime query <target>` output against the manifest expectations.
- [ ] Stop cleanly.

## File Inspection

- [ ] Inspect the world data file: `world/data/mcacrime.dat` must exist and be readable.
- [ ] Confirm each joined player's `.dat` file contains `neoforge:attachments.mcacrime:player_crime` (use `nbt explorer` or `nbtedit`).
- [ ] Confirm the `ForgeCaps` key still exists in each player file (read-only, not modified).

## Idempotence Verification

- [ ] Restart the server (second startup).
- [ ] Re-check player state against manifest: karma, heat, band, wanted status must be identical to the first startup state.
- [ ] Stop and restart once more (third startup).
- [ ] Re-check player state: must still match manifest.

## Gameplay Exercise

For each scenario, verify state persists across server restarts:

- [ ] Detection: trigger a witnessed crime, false positive, config-protected action. Verify witness memory.
- [ ] Karma/Heat: thresholds, daily caps, online-only decay, band transitions.
- [ ] Guard enforcement: challenge accept/refuse/timeout, pursuit, legal target check, logout/restart behavior.
- [ ] Arrest/escort: phase transitions, prisoner death, dimension transfer.
- [ ] Jail: holding cell creation, containment, sentence clock, bail/release, death/relog/restart.
- [ ] Captivity: capture/release/rescue, cap, ownership.
- [ ] Mugging/ransom: channel interruption, demand/counter/pay/refuse/expiry.
- [ ] Economy/loot: fines, bail, profession drops, duplication resistance.
- [ ] Case ledger: pagination, open/resolved status, dossier display.
- [ ] Relationships: spouse/family/village consequences (if MCA data is present), hearts.
- [ ] Config/datapacks: custom config values persist, reload works, invalid JSON diagnostics appear.
- [ ] Multiplayer: late join, tracking start/stop, logout cache clear, reconnect to same server.

## Optional Reputation Companion

If the optional MCA: Reputation mod is installed:

- [ ] Join a player who had reputation data in the Forge fixture.
- [ ] Verify reputation state (public/community standing) matches the Forge fixture manifest.
- [ ] Exercise a crime that updates reputation; confirm updates persist across restart.

## Client Acceptance (NeoForge 1.21.1 Client Only)

- [ ] Run `C:\Projects\.mcmod-tools\gradlew-quiet.ps1 -Project "<project-dir>" -Task runClient`.
- [ ] Confirm no mixin, rendering, narration, or resource errors appear in the launch log.
- [ ] Join the upgraded world. Verify:
  - [ ] Keybindings: defaults, remapping, conflicts, unbound keys, pause menu, held-repeat drain.
  - [ ] GUI: dossier, config, action, challenge screens. Test mouse, keyboard, narration, all GUI scales.
  - [ ] HUD: all anchors/offsets/toggles, hide GUI, spectator, action success/cancel, jail/captive clocks.
  - [ ] Name colors/player card: join/leave, dimension, stale cache, inventory screen.
  - [ ] Restraint visuals: normal/slim skins, armor, common poses.
  - [ ] Escort rope: visibility, lighting, multiple entities, stale IDs, graphics modes.
  - [ ] MCA screen button: normal villager, guard, family member, config off, server denial.
  - [ ] Disconnect: all client caches empty before next server connection.

## Fixture Contents

The Forge 1.20.1 fixture must contain:

- Non-zero karma and heat in at least two communities.
- A witnessed crime, a reported crime, and an unwitnessed crime.
- Open and resolved case-ledger entries.
- One active arrest/escort and one completed jail sentence.
- A registered holding cell with replaced blocks.
- An unlawful captive and ransom state.
- Weapon/restraint inventory items.
- Observation positions in at least two dimensions.
- Optional MCA: Reputation data (if a compatible Forge source build is available).
- Non-default common and client config values.

Place the fixture at `src/test/resources/fixtures/forge-1.20.1/`.

## Rollback

If any test fails:

- Stop the server.
- Restore from the backup (step 1).
- Log the failure with the full server and client logs.

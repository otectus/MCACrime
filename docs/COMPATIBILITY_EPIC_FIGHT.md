# Compatibility: Epic Fight, EpicFight-MCA Patch, MCA Skin x Epic Fight

## The report

With Epic Fight installed, a player in battle mode gets no MCA interaction screen and no MCA:
Crime Crime menu on an MCA villager. With the third-party EpicFight-MCA Patch (`efmca`) installed
on top, hitting an MCA villager — with a fist, an Epic Fight battle-mode attack, or a player-owned
projectile — never registers as damage at all.

## Root cause, per mod, per layer

### Epic Fight (`epicfight`) — the menu never opens

In battle mode, while the held weapon has a guard skill that can execute, Epic Fight's
`ControlEngine` cancels the client-side use-key event before it reaches vanilla handling. Its
default setting is `key_conflict_resolve_scope = INTERACTION`, which prefers the guard skill over
the interaction. A cancelled use-key event means `Minecraft.startUseItem` never runs, so neither
`INTERACT_AT` nor `INTERACT` is ever sent to the server. No packet means no
`PlayerInteractEvent.EntityInteract` server-side, so `action/CrimeActionInteractHandler` never runs
— and neither does MCA's own screen, because the same client event fed both. Nothing server-side
is rejecting anything; the interaction never leaves the client.

### EpicFight-MCA Patch (`efmca`) — no damage ever registers

The CurseForge file `efmca-1.1` is titled "EFMCA Fixed"; the jar's own metadata names the mod
"EFMCA" at version `1.0.0`, which is what `/crime debug compat` prints as `version=1.0.0` — the
"efmca-1.1"/"EFMCA Fixed" names belong to the download page, not the mod.

Its damage block has three layers (the jar ships five common mixins plus one client-only
invoker (six in total), plus guard and attribute changes unrelated to this report):

1. A mixin on `VillagerEntityMCA.hurt` that returns `false` unconditionally whenever the damage
   source's entity is a `Player` — melee, Epic Fight battle-mode attacks, and player-owned
   projectiles alike.
2. A mixin that makes Epic Fight report the attack result as "missed" for players hitting
   villagers.
3. A `LivingAttackEvent` cancel for player attacks, gated on efmca's own `friendlyFire` option
   (`net.forixaim.mcea.Config.FRIENDLY_FIRE`), which defaults to `false`.

Setting `friendlyFire = true` only removes layer 3. Layer 1 is unconditional and stays in place, so
villagers are still immune. Because no damage event ever fires, MCA: Crime's assault and kill
crimes, self-defence against guards, and kill bounties cannot occur while `efmca` is installed.
MCA: Crime does not attempt to work around this — the only way to would be forcing damage onto
MCA's entity from a source with no player attached, which is not a fix, it is a second bug.
Everything that does not depend on hitting a villager (menus, Mug, restraints, fines, bounties by
capture) is unaffected.

`efmca` cannot be verified on a dedicated server at all: its mod constructor
(`net.forixaim.mcea.MinecraftComesEpiclyAlive`) touches `net.minecraft.client.gui.screens.Screen`
unconditionally, and Forge aborts mod loading on a headless server with "Attempted to load class
net/minecraft/client/gui/screens/Screen for invalid dist DEDICATED_SERVER". `efmca` therefore only
loads in single-player or on a LAN-hosted integrated server, and MCA: Crime's startup warning and
`BLOCKED` verdict for it can only be exercised there or in a unit test — never on a dedicated
server, headless or otherwise.

### MCA Skin x Epic Fight Compatibility (`mcaefcompat`)

Client-side rendering only (skin/pose compatibility). It is not involved in either issue above and
is recorded in diagnostics for completeness, never acted on.

## What MCA: Crime does about each

- **`efmca` damage block.** Detected, not worked around. `compat/EpicFightCompat` logs a startup
  warning naming the mixin and explaining that `friendlyFire` does not lift it, and reports the
  same verdict in `/crime debug compat`.
- **Epic Fight's swallowed use key.** Forwarded. The client-only `client/EpicFightInteractShim`
  listens for the cancelled `InputEvent.InteractionKeyMappingTriggered` event and, only when the
  target is an MCA villager and a crime interaction actually applies — a qualifying drawn weapon
  under the synced weapon policy (honouring `weaponTrigger.enabled` and
  `weaponTrigger.requireSneak`), or a restraint item in hand — replays the same
  `interactAt`/`interact`/swing sequence vanilla would have sent. The server sees an ordinary
  interaction and re-validates all of it through `CrimeActionInteractHandler`, restraint handlers,
  reach, line of sight, custody, child protection and cooldowns unchanged. There is never a
  duplicate packet or a doubled menu, because the shim only ever acts on an interaction Epic Fight
  had already cancelled.
- **`mcaefcompat`.** Nothing to do; it does not touch interaction or damage.

## What players and pack authors should do

- **For the empty-handed MCA conversation screen** (which the shim deliberately does not
  forward — opening it is MCA's own decision, not this mod's to synthesise): leave Epic Fight's
  battle mode, or set `key_conflict_resolve_scope` to `NONE` or `ITEM_USE` so the use key stops
  being contested.
- **For the `efmca` damage block:** there is no client- or config-side workaround. Setting
  `friendlyFire = true` changes nothing about the outcome — the unconditional `hurt` mixin still
  blocks the damage. Remove `efmca`, or report the limitation to its author, to restore violent
  crime detection against MCA villagers.

## `/crime debug compat`

Prints, in order:

- Presence and installed version of `epicfight`, `efmca` (with its `friendlyFire` value, or
  `unreadable` when the class/field cannot be read — absent mod, renamed field, or config not yet
  loaded, appended to the same line), and `mcaefcompat` (`-` when a mod is absent).
- The damage verdict: `Player damage to MCA villagers: OK`, or `BLOCKED by efmca (unconditional
  mixin on VillagerEntityMCA.hurt; its friendlyFire option does not lift it)` in red.
- When Epic Fight is installed, a line confirming the shim forwards armed/restraint interactions
  and that an empty-handed conversation still needs Epic Fight's own vanilla mode or
  `key_conflict_resolve_scope`.
- The MCA binding summary (`compat/mca/McaBinding`).
- `Active currency: <id>`.
- The held item's weapon classification (same output as `/crime debug weapon`) — only when the
  command is run by a player; from the console it prints "held item: run as a player to classify
  it" instead.

No line names a player or a world, so the output is safe to paste directly into a bug report.

## Verification matrix

Versions these notes were written against: MCA `7.6.28-beta.10`, Architectury `9.2.14`, Epic Fight
`20.14.17-mc1.20.1-forge`, efmca `1.0.0` (CurseForge file `efmca-1.1`), MCAEF (`mcaefcompat`)
`1.0.4`, Numismatic Overhaul Reforged Again `2.0.1`.

Automated, committed JUnit tests that run under `check` (no client, no mod jars on the classpath):

- [x] `EpicFightCompatTest` — presence, version and `friendlyFire` reads never throw with no mod
      list at all; the diagnostic reports the healthy (`OK`) verdict with all three mods absent.
- [x] `EpicFightInteractShimTest` — `shouldForward` vetoes on a missing Epic Fight, an uncancelled
      event, a non-use-item key, an off-hand press, a non-MCA-villager target, and a trigger that
      does not apply; forwards only when every condition holds and the cooldown has elapsed;
      `crimeTriggerApplies` forwards a restraint regardless of the weapon-trigger switches and
      forwards an armed hand only while the trigger is on and its sneak rule is satisfied.

Exercised this session on a headless Forge dedicated server at the Forge version in
`gradle.properties` (MCA, Architectury, Epic
Fight, `mcaefcompat` and Numismatic Overhaul all present as listed above — `efmca` cannot load on
a dedicated server at all, see above):

- [x] Clean server start and `/crime debug compat` output checked against three mod sets: baseline
      (MCA only), `+epicfight`, and `+mcaefcompat`.
- [x] `integrations.currencyId = "mcacrime:item"` with `integrations.currencyItem =
      "minecraft:gold_nugget"` resolves and pays in gold nuggets.
- [x] `integrations.currencyItem = "foo:bar"` (unparseable/unregistered) warns once and falls back
      to emeralds; `/crime validate` lists it.
- [x] `integrations.currencyItem = "numismaticoverhaul:bronze_coin"`, both with and without
      Numismatic Overhaul installed, resolves as an ordinary item currency in both cases (the item
      form does not require the mod to be present).
- [x] `integrations.currencyId = "mcacrime:numismatic"`, both with and without Numismatic Overhaul
      installed: with the mod present it registers and is selected; without it, one warning and a
      fallback to `mcacrime:emerald`, no crash either way.

Manual, requires a running single-player or LAN client (not exercisable on a dedicated server):

- [ ] With `epicfight` installed and battle mode active, right-clicking an MCA villager while
      holding a qualifying weapon opens the same Crime menu a vanilla right-click would, with no
      duplicate menu and no duplicate chat message.
- [ ] Under the same conditions but sneaking with `weaponTrigger.requireSneak = true`, the
      interaction is forwarded only while sneaking.
- [ ] Holding a restraint item and right-clicking a vulnerable MCA villager in battle mode starts
      the capture channel, regardless of `weaponTrigger.enabled`.
- [ ] An empty-handed right-click in battle mode does **not** open any MCA: Crime menu; leaving
      battle mode (or setting `key_conflict_resolve_scope = NONE`) restores MCA's own conversation
      screen.
- [ ] With `efmca` installed, a melee hit, an Epic Fight battle-mode attack, and a player-owned
      projectile against an MCA villager all deal no damage and generate no crime case.
- [ ] Setting `efmca`'s `friendlyFire = true` and repeating the previous check still deals no
      damage — confirming the mixin, not the config, is the block.
- [ ] `/crime debug compat` reports `installed=true` for whichever of `epicfight`/`efmca`/
      `mcaefcompat` are present, the correct `friendlyFire` value or `unreadable`, and the matching
      `BLOCKED`/`OK` damage verdict.
- [ ] With `mcaefcompat` installed alongside the others, villager skins render correctly and no
      behaviour above changes.

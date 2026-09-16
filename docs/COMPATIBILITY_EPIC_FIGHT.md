# Compatibility: Epic Fight and its MCA bridges (mcea, efmca, mcaefcompat)

## The report

With Epic Fight installed, a player in battle mode gets no MCA interaction screen and no MCA:
Crime menu on an MCA villager. With "MC-Epicly-A" (`mcea`, the NeoForge 1.21.1 MCA/Epic Fight
patch) installed on top, hitting an MCA villager — with a fist, an Epic Fight battle-mode attack,
or a player-owned projectile — never registers as damage at all.

## The four ids, and what each one triggers

`compat/EpicFightCompat` checks four mod ids and nothing else — Epic Fight itself plus the three MCA
bridges — and no `epicfight`, `mcea`, `efmca` or `mcaefcompat` type is named anywhere in this mod.

| Id | Detected as | What its presence triggers |
|---|---|---|
| `epicfight` | Epic Fight itself | An `INFO` startup line, a `/crime debug compat` line about the shim, and the client-only `client/EpicFightInteractShim` forwarding armed and restraint interactions. |
| `mcea` | "MC-Epicly-A" | A `WARN` startup notice and the red verdict `BLOCKED by mcea`. `playerDamageToVillagersBlocked()` is true, so violent crime against MCA villagers is reported as unavailable. |
| `efmca` | "EpicFight-MCA Patch" | A `WARN` startup notice naming the `friendlyFire` value it managed to read (or `unreadable`), and the verdict `BLOCKED by efmca`. Also sets `playerDamageToVillagersBlocked()`. |
| `mcaefcompat` | "MCA Skin x Epic Fight Compatibility" | A presence-and-version line in the diagnostic, marked "client rendering only". Nothing else: it is deliberately excluded from the damage verdict. |

`mcea` and `efmca` are two packagings of the same damage block, so `playerDamageToVillagersBlocked()`
is true when **either** is installed, and `mcea` takes precedence in the reported verdict when both
are. `efmca`'s `friendlyFire` field is read reflectively (`net.forixaim.mcea.Config.FRIENDLY_FIRE`,
unwrapped by calling `get()` on whatever config-value object it holds) purely so a bug report shows
what the operator already tried; the value never changes the verdict, because the `hurt` mixin stays
in place either way, and an unreadable field reports `unreadable` rather than failing anything.

`efmca` and `mcaefcompat` are the ids the 1.20.1 Forge line shipped under, so a NeoForge 1.21.1
server is unlikely to see them. They are still checked by id, because which packaging an operator
installed is an accident and the symptom they report is identical, and because the diagnostic must
answer the question rather than assume it away.

## Root cause, per mod, per layer

### Epic Fight (`epicfight`) — the menu never opens

In battle mode, while the held weapon has a guard skill that can execute, Epic Fight's
`ControlEngine` cancels the client-side use-key event before it reaches vanilla handling
(`net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered` on this port). A
cancelled use-key event means `Minecraft.startUseItem` never runs, so neither `interactAt` nor
`interact` is ever sent to the server. No packet means no `PlayerInteractEvent.EntityInteract`
server-side, so `action/CrimeActionInteractHandler` never runs — and neither does MCA's own
screen, because the same client event fed both. Nothing server-side is rejecting anything; the
interaction never leaves the client.

### MC-Epicly-A (`mcea`) — no damage ever registers

`mcea` (package `net.forixaim.mcea`) is the NeoForge 1.21.1 patch that keeps Epic Fight and MCA
compatible. It carries the same unconditional damage block as the Forge-side `efmca` patch, with
one difference: it has **no friendly-fire option at all** to even try turning off. Its block has
two layers:

1. A mixin on `VillagerEntityMCA.hurt` that returns `false` unconditionally whenever the damage
   source's entity is a `Player` — melee, Epic Fight battle-mode attacks, and player-owned
   projectiles alike.
2. A `LivingIncomingDamageEvent` cancel for every `PLAYER_ATTACK` on a `VillagerLike`,
   unconditionally — there is no config flag, unlike `efmca`'s `friendlyFire`, that lifts it.

Because no damage event ever fires, MCA: Crime's assault and kill crimes, self-defence against
guards, and kill bounties cannot occur while `mcea` is installed. MCA: Crime does not attempt to
work around this — the only way to would be forcing damage onto MCA's entity from a source with no
player attached, which is not a fix, it is a second bug. Everything that does not depend on hitting
a villager (menus, Mug, restraints, fines, bounties by capture) is unaffected.

Unlike the Forge-side `efmca`, `mcea` **does load on a dedicated server**: its client-side code is
behind a distribution check, so `mcea`'s block and MCA: Crime's diagnostic for it can both be
exercised headless, not only in single-player or on a LAN-hosted integrated server.

## What MCA: Crime does about each

- **`efmca`'s damage block** (should a copy reach this port). Detected, not worked around: the same
  startup warning, naming the `friendlyFire` value it read and stating that the value does not lift
  the mixin.
- **`mcaefcompat`.** Reported only. It draws skins; it is never a cause, and nothing in this mod
  behaves differently because it is installed.
- **`mcea`'s damage block.** Detected, not worked around. `compat/EpicFightCompat` logs a startup
  warning naming both mixins and stating there is no option that lifts either, and reports the
  same verdict in `/crime debug compat`.
- **Epic Fight's swallowed use key.** Forwarded. The client-only `client/EpicFightInteractShim`
  listens for the cancelled `InputEvent.InteractionKeyMappingTriggered` event and, only when the
  target is an MCA villager and a crime interaction actually applies — a qualifying drawn weapon
  under the synced weapon policy (honouring `weaponTrigger.enabled` and
  `weaponTrigger.requireSneak` via `WeaponPolicySnapshot`'s `triggerEnabled` and
  `triggerRequireSneak` fields), or a restraint item in hand — replays the same
  `interactAt`/`interact`/swing sequence vanilla would have sent. The server sees an ordinary
  interaction and re-validates all of it through `CrimeActionInteractHandler`, restraint handlers,
  reach, line of sight, custody, child protection and cooldowns unchanged. There is never a
  duplicate packet or a doubled menu, because the shim only ever acts on an interaction Epic Fight
  had already cancelled, and only on the main hand.

## What players and pack authors should do

- **For the empty-handed MCA conversation screen** (which the shim deliberately does not
  forward — opening it is MCA's own decision, not this mod's to synthesise): leave Epic Fight's
  battle mode, or set its `key_conflict_resolve_scope` option (config key
  `ingame.key_conflict_resolve_scope`, default `INTERACTION`; the internal field/enum name is
  `canceledVanillaActions`) to `NONE`.
- **For the `mcea` damage block:** there is no client- or config-side workaround. `mcea` has no
  friendly-fire switch at all, so there is nothing to try turning off. Remove `mcea`, or report the
  limitation to its author, to restore violent crime detection against MCA villagers.

## `/crime debug compat`

Prints, in order:

- Presence and installed version of `epicfight`; of `mcea` (with the note that its damage block is
  unconditional — there is no friendly-fire field to read); of `efmca` with its `friendlyFire` value
  or `unreadable`; and of `mcaefcompat`, marked "client rendering only".
- The damage verdict: `Player damage to MCA villagers: OK`, or `BLOCKED by mcea (unconditional
  mixin on VillagerEntityMCA.hurt, plus an unconditional incoming-damage cancel; it has no option
  that lifts either)` in red, or — when only `efmca` is present — `BLOCKED by efmca (unconditional
  mixin on VillagerEntityMCA.hurt; its friendlyFire option does not lift it)`.
- When Epic Fight is installed, a line confirming the shim forwards armed/restraint interactions
  and that an empty-handed conversation still needs Epic Fight's own vanilla mode or
  `key_conflict_resolve_scope = NONE`.
- The MCA binding summary (`compat/mca/McaBinding`).
- `Active currency: <id>` (`economy/Currencies`).
- The held item's weapon classification (same output as `/crime debug weapon`) — only when the
  command is run by a player; from the console it prints "held item: run as a player to classify
  it" instead.

No line names a player or a world, so the output is safe to paste directly into a bug report.

## Verification matrix — recorded in an earlier session, before the 0.7.2 parity pass

Everything in this section was recorded in an **earlier session, against the tree as it then stood**,
before the 0.7.2 parity work. **None of it has been re-run since.** Treat it as the last known state
of this compatibility seam, not as evidence about the current tree; see
[docs/0.7.2/VERIFICATION.md](0.7.2/VERIFICATION.md) for what is evidenced now.

Versions that session was run against, none of which can be verified from this repository (no jar,
lockfile or manifest here records them): the NeoForge version in `gradle.properties`, MCA
`7.7.36-beta.3`, Architectury `13.0.8`, MC-Epicly-A (`mcea`) `mcea-21.17.0.2-mc1.21.1-neoforge`,
Epic Fight `21.17.3.1-mc1.21.1-neoforge`, Numismatic Overhaul Reforged Again `2.0.1` for NeoForge
(with owo-lib `0.12.15.5-beta.1` — Numismatic hard-depends on it without declaring it, so a server
without owo-lib fails at Mixin bootstrap before any mod loads).

Automated JUnit tests from the working tree, run in that earlier session under the ModDevGradle
NeoForge test runner: 1365 unit tests, 0 failures. The suite has grown since; that count describes
the pre-parity tree. `EpicFightCompatTest` and `client/EpicFightInteractShimTest` ran for real
in that pass, and `economy/ItemCurrencyInventoryTest` ran against live registries rather than
skipping:

- `EpicFightCompatTest` — presence, version and diagnostic reads never throw with neither mod
  present; the diagnostic reports the healthy (`OK`) verdict, and confirms the reported
  `friendlyFire` line reads "no such option" rather than a boolean, matching `mcea` having none.
- `EpicFightInteractShimTest` — `shouldForward` vetoes on a missing Epic Fight, an uncancelled
  event, a non-use-item key, an off-hand press, a non-MCA-villager target, and a trigger that does
  not apply; forwards only when every condition holds and the cooldown has elapsed;
  `crimeTriggerApplies` forwards a restraint regardless of the weapon-trigger switches and forwards
  an armed hand only while the trigger is on and its sneak rule is satisfied.

Automated, server-side checks that passed **in that earlier session** on a headless dedicated server
running the versions above (MCA, Architectury, Epic Fight, `mcea` and Numismatic Overhaul + owo-lib
all present). They have not been repeated on the post-parity tree:

- [x] Clean server start and full `/crime debug compat` output checked against three mod sets:
      baseline (MCA only), `+epicfight`, and `+mcea`.
- [x] With `mcea` installed: the startup `WARN` from `compat/EpicFightCompat` is present in the
      log, and `/crime debug compat` reports the verdict `BLOCKED by mcea (unconditional mixin on
      VillagerEntityMCA.hurt, plus an unconditional incoming-damage cancel; it has no option that
      lifts either)`. With `-Dmixin.debug.verbose=true`, the log shows `Mixing MixinVillagerMCA
      from mcea.mixins.json into net.conczin.mca.entity.VillagerEntityMCA`.
- [x] `integrations.currencyId = "mcacrime:item"` with `integrations.currencyItem =
      "minecraft:gold_nugget"` resolves with `Active currency: mcacrime:item/minecraft/gold_nugget`.
- [x] `integrations.currencyItem = "foo:bar"` (unparseable/unregistered) warns once and falls back
      to `mcacrime:item/minecraft/emerald`; `/crime validate` lists it.
- [x] `integrations.currencyItem = "numismaticoverhaul:bronze_coin"` resolves as an ordinary item
      currency.
- [x] `integrations.currencyId = "mcacrime:numismatic"` with Numismatic Overhaul (+ owo-lib)
      installed: the purse registers and is selected as the active currency.
- [x] `integrations.currencyId = "mcacrime:numismatic"` with Numismatic Overhaul removed: one
      warning, fallback to `mcacrime:emerald`, `/crime validate` lists it, no crash.

Manual, requires a running single-player or LAN client. Not exercised in that session, and not
since:

- [ ] With `epicfight` installed and battle mode active, right-clicking an MCA villager while
      holding a qualifying weapon opens the same Crime menu a vanilla right-click would, with no
      duplicate menu and no duplicate chat message.
- [ ] Under the same conditions but sneaking with `weaponTrigger.requireSneak = true`, the
      interaction is forwarded only while sneaking.
- [ ] Holding a restraint item and right-clicking a vulnerable MCA villager in battle mode starts
      the capture channel, regardless of `weaponTrigger.enabled`.
- [ ] An empty-handed right-click in battle mode does **not** open any MCA: Crime menu.
- [ ] With `mcea` installed, a melee hit, an Epic Fight battle-mode attack, and a player-owned
      projectile against an MCA villager all deal no damage and generate no crime case.

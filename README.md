# MCA: Crime

> Villages that notice, remember, and answer back.

An add-on for [Minecraft Comes Alive: Reborn](https://modrinth.com/mod/minecraft-comes-alive-reborn)
that gives a village law. Hurt someone where a guard can see it and you are pursued, fined, and
jailed. Do it where nobody is looking and the world still records what happened — the law simply
never hears about it.

- **Minecraft** 1.21.1 · **NeoForge** · **Java** 21
- **Requires** MCA Reborn, the version pinned in `gradle.properties`
- **Optional integrations** MCA: Reputation, MCA: Quests, and Locks Reforged, using their NeoForge 1.21.1 builds
- **Licence** GPL-3.0-only

---

## What it does

The latest development pass confirms death before awarding kill bounties or recovering stolen
property. Recovered goods go to their owner's escrow, with delivery attempted immediately for
online owners and on login for everyone else. See the
[death and recovery phase notes](docs/MCA_CRIME_PHASE2_DEATH_RECOVERY.md) for validation and limits.

MCA already models how one villager feels about you — that is what hearts are. This mod models
what the **law** does about you, on two separate axes that never read each other.

- **Karma** is long-term moral standing, and it derives your **band**: Lawful above +100, Outlaw
  below −100, Neutral in between. It moves slowly and normalises toward zero over online days.
- **Heat** is short-term law-enforcement pressure. At 50 you are **Wanted** and guards come for
  you. It bleeds off per online minute, so lying low genuinely works — and lying logged out does
  not, because every clock in this mod counts online time only.
- **Crimes are data.** Fourteen ship as JSON — theft, harming a villager, assaulting a guard,
  jailbreak, kidnapping, killing a villager, murder during a robbery, among others — each with its
  own karma and Heat cost. A datapack can retune any of them or add its own.
- **Witnesses** see or hear crimes within offense-specific ranges. Walls block sight; sound alone
  identifies no suspect. Civilians carry their information to guards, and only sufficiently confident
  reports create public consequences. Local family conversations can spread uncertain accounts.
- **Intimidation and memory (0.6.0).** Victims can comply, panic, stall, resist or defy depending on
  weapon aim, personality, health, support and history. Persistent fear and anger affect later
  interactions and soften through time, apologies and matching-case reconciliation.
  To apologize, put weapons away, wait one minute after the incident, then right-click the
  villager with an empty main hand and nothing weapon-like in the off hand — without sneaking.
  `memory.emptyHandApologyMode` (default `CONTEXTUAL_DIRECT`) governs that gesture; set it to
  `MENU_ONLY` and the apology is offered only through the Crime menu, its keybind and the MCA
  screen button. An apology helps repair trust but does not immediately erase fear or legal
  charges.
- **Enforcement.** Guards act on reported cases in their own jurisdiction. Wanted Heat,
  including Heat set by commands, independently causes nearby guards
  and archers to approach and confront you. Refusal keeps pursuit lawful until it expires or is
  resolved. These statuses do not reveal private crimes to a guard.
  Escaped prisoners and active captors also provide a basis for intervention. Victims react from
  personal memory; neither system runs a per-tick world scan.
- **Combat evidence.** Damage charges use final damage and confirmed death. Armor-reduced hits,
  totem saves and canceled deaths cannot become a predicted murder charge. Self-defense requires
  a recent unprovoked attack and permits nonlethal retaliation; provoking a villager or guard does
  not make their retaliation a license to attack them. Lethal force needs its own permission.
- **Jail** is served in **online ticks**. Logging out pauses your sentence, dying does not clear
  it, changing dimension does not stop it, and a server restart resumes it. Three containment
  modes decide whether the walls are breakable and whether leaving counts as a breakout.
- **Fines and surrender.** Guards quote and settle only their locally known cases. A changed offer
  requires another confirmation before payment; insufficient funds leaves surrender available.
  Local Heat reductions are capped by the selected cases' recorded contribution. Outside an encounter, `/crime payfine`
  remains a voluntary settlement of the whole record and Heat, including unreported crimes.
  Mandatory-custody cases and cases already assigned to a sentence cannot be paid away. The configured
  Heat threshold and Outlaw payment policy still apply to each settlement's scope.
- **Kidnapping** is the structural twin of jail, deliberately kept legally distinct. Restrain a
  villager or a player with rope, cuffs, or locked cuffs after a channel that a hit, a step, or a
  lost line of sight will break — and only against a target who is genuinely vulnerable. Guards
  are never capturable this way.
- **Cuff lockpicking with Locks Reforged.** When installed, escaping ordinary or locked cuffs
  requires winning its native minigame. Attempts need no item by default; enable
  `[kidnapping].cuffEscapeRequiresLockpick` to require a lockpick in the inventory. Lawful cuff
  escape counts as jailbreak and preserves the sentence. Rope keeps its existing escape rules.
- **Ransom.** Somebody has to pay for your captive, and who it is follows a strict priority:
  spouse, parent, adult child, sibling, close relative, and failing all of those, the village
  itself at a lower price. Family payers must be reachable online players.
- **Mugging.** Rob a villager for a modest amount of the active currency (emeralds by default;
  see `integrations.currencyId`) and take a moderate theft charge. Kill that same villager shortly
  afterwards and the death is reclassified as murder during a robbery — the heaviest crime in the mod.
- **Masks and the Mask Station.** Sixteen mask styles in four families — Cloth (`bandana`,
  `highwaymans_domino`, `wrapped_scarf`, `half_veil`), Leather (`leather_mask`, `raven_mask`,
  `jackal_mask`, `stitched_mask`), Clay (`hockey_mask`, `clay_mask`, `comedy_mask`, `tragedy_mask`)
  and Metal (`iron_skull_mask`, `brigand_visor`, `owl_mask`, `blank_iron_mask`) — defer the Heat of
  most crimes while worn. Within a family every style is identical in everything a player can
  measure: the same concealment, the same wear budget (Cloth 48, Clay 64, Leather 192, Metal 256),
  the same cost. None grants armour and none is enchantable. Masks are crafted at a **Mask Station**
  (`mcacrime:mask_station`, a wooden bench crafted from planks, clay, leather and string) from a
  material, a binding and an optional dye; the same station **restyles** a mask into another style of
  the same family, carrying its wear, name, lore, enchantments and dye across and refusing anything
  it cannot move honestly. Dye is the vanilla `minecraft:dyed_color` component, so one dye produces
  the same colour it would on a leather cap, and it tints the worn layer as well as the icon.
- **Thief is a real profession.** `mcacrime:thief` is a registered villager profession whose
  workstation is the Mask Station: a recruited thief claims a station, works it, and can be found by
  it. Nothing else may take a Mask Station as its job site, and a thief that loses its station keeps
  the job for a grace period before it lapses rather than being stripped mid-shift.
- **Sand Bottle.** `mcacrime:sand_bottle` is a thrown disruption tool, not a weapon: it deals no
  damage and synthesises no damage event, so nothing raises a false or duplicated charge. Blinding
  somebody who is not fair game is still a crime — one hostile exposure records exactly one incident,
  attributed `non_damaging`. A direct hit blinds for up to 80 ticks, a
  splash within 2 blocks for less, and a blinded guard, thief or mob loses live line of sight past
  arm's reach — pursuit walks to the last place it genuinely saw you. It never erases a memory, a
  case or a warrant, and a 60-tick recovery window per target stops two throwers stun-locking one
  victim. Tuned under `[sandBottle]`; `client.sandParticles` controls the dust.
- **Death loot.** Villagers drop equipped gear and one purchase worth of each available trade; guards
  and archers leave their equipment. Item data is preserved and carried gear is not duplicated.
  Fences leave one item per available selling offer. Configure these defaults under `[loot]` in [CONFIG.md](CONFIG.md).
- **The ledger.** Every crime is a case with an identity, a victim, a community, its witnesses,
  and a disposition: unresolved, fined, served, pardoned, escaped, or expired. Escaping is not
  forgiveness — an escaped case stays actionable, and can still be settled later.
- **Consequences with people, not just numbers.** The victim loses hearts toward you, so does
  their family, so do the witnesses. Rescuing a captive earns them back. Paying a fine returns a
  fraction of it as restitution.

## What it deliberately does not do

No hearts replacement. No trials. Villagers still never decide to commit a crime on their own — a
villager with the Thief occupation robs players, never another villager. A player can recruit an
eligible relative as an accomplice, though: that villager is then individually wanted, arrestable,
and bailable by family for what they did. No positive karma for trading, gifting, or clicking
through dialogue; those are farmable and belong to systems that already own them. Eight narrowly
scoped mixins, all of them on vanilla classes and none on MCA: seven common —
`MobDeathEquipmentMixin` (`Mob.setItemSlot`, before MCA clears a dying villager's equipment),
`MaskStationAcquisitionMixin`, `NativeJobAssignmentMixin`, `ThiefPoiValidationMixin`,
`ThiefBrainMixin` and `MerchantOffersAccessor` (the Thief profession and its worksite), and
`SandSensingMixin` (`Sensing.hasLineOfSight`, for sand blindness) — plus client-only
`RestraintPoseMixin`, which poses restrained arms. No per-tick village scans, no AI text
generation, no telemetry, no outbound network calls. Turning a subsystem off changes behaviour only,
and deletes nothing; time-based retention does — stale criminal-villager records, expired bounty
contracts, and claims past their retention window are dropped on a timer.

## Installing

In 0.6.0, bounty payments retain unpaid inventory overflow and uncertain currency outcomes. Use
`/crime collectbounty` to collect queued rewards. Operators can inspect/export/reconcile retained
receipts with `/crime recovery`; see [recovery operations](docs/RECOVERY_OPERATIONS.md).

Drop the jar in `mods/` alongside MCA Reborn. That is the whole installation; the mod works
standalone.

**Architectury is not required by this mod.** MCA's version in `gradle.properties` does not use it; this mod names no
Architectury type, so a user is not blocked.

**The MCA dependency range is pinned to the one tested NeoForge 1.21.1 build of MCA Reborn** (see `gradle.properties`); widen it only after the gameplay matrix has been run on another build. No class in this mod names an MCA type. Every MCA class
and member is resolved by name at runtime, against whichever package root the installed MCA
actually uses. Anything MCA has removed degrades to "absent" per member rather than throwing, so a
future MCA that drops one method loses one feature instead of crashing a server.

---

## Credits

**Restraint item artwork:** TheWiggleDuck designed and provided custom textures for the three
restraint items (open cuffs, locked cuffs, and rope) used in this release.

## With the rest of the suite

Each add-on works alone, and any combination works.

| Installed | What you get |
|---|---|
| **Crime** alone | Karma and Heat, the fourteen shipped crimes, witnesses, guards, jail, kidnapping, ransom, mugging, fines, the ledger, and a built-in per-village standing store |
| **+ MCA: Reputation** | Crime becomes the single producer for villager assault and killing; every case becomes a public incident the village can gossip about, and paying a fine or serving a sentence reads publicly as making good |

Nothing here depends on MCA: Reputation at compile time, and removing it leaves this mod working
on its own store. The handover is a real handshake rather than a guess — one mod produces each
deed, never both and never neither. `/crime debug integrations` reports which.

## Seeing where you stand

- A **reputation card** opens from a small button at the bottom-left of the player model in your
  inventory: karma, standing, Heat, whether you are Wanted, your remaining sentence, whether you
  are a legal target, and who is holding you.
- **Nameplates** are tinted by band. Neutral is left completely untouched, and the colour is
  applied so that a nickname or formatting mod keeps its own styling.
- **Ambient messages** tell you when your band changes, when a villager witnessed something, and
  *why* a guard is currently pursuing you — there are four distinct reasons and the game names the
  one that applies.
- **Chat colouring** by band exists but is **off by default**, and is server-authoritative when
  enabled.

## Commands

Everything is under `/crime`. Checking your own standing and acting on your own situation needs no
permission; reading someone else's needs level 2. Setting heat also needs level 2 so command blocks
can use it; other administrative changes need level 3.

For ordinary play, open MCA's villager interaction screen and choose **Crime…**. If a supported MCA
layout cannot be bridged, Shift+interact with an empty hand opens the same server-issued menu. The
gameplay commands below are accessibility fallbacks and use identical validation, timing, locks,
cooldowns, and finite accounts.

```
/crime karma                                 your karma and band
/crime status                                karma, band, Heat, Wanted, remaining sentence
/crime payfine                               pay off cases in the active currency, oldest first
/crime surrender                             surrender near a guard, jail, or lawful player
/crime mug                                   threaten the exact villager in your crosshair
/crime ransom                                demand a ransom for the captive you hold
/crime payransom                             pay a ransom for a held relative
/crime escape                                begin timed work against your restraint
/crime releasecaptive                        release any captive record you own

/crime query <player>                        another player's full status           (level 2)
/crime ledger <player>                       another player's every crime record    (level 2)
/crime debug villager|custody|actions        villager, custody, or action state     (level 2)
/crime debug integrations                    API version, bridge state, outbox      (level 2)
/crime debug outbox [dead]                   queued or dead-lettered cross-mod work (level 2)

/crime validate                              run config validation                  (level 3)
/crime reload                                reload datapack crime definitions       (level 3)
/crime set heat <player> <value>             also available to command blocks       (level 2)
/crime set karma <player> <value>                                                   (level 3)
/crime clearheat <player>                                                           (level 3)
/crime jail <player> <ticks>                 ticks are online ticks                 (level 3)
/crime release <player>                      clears jail and kidnapping alike       (level 3)
/crime assignjail <pos> [radius]                                                    (level 3)
```

`/crime debug integrations` deliberately emits no player UUIDs, so its output can be pasted
straight into a bug report. `/crime release` is the universal backstop: it frees a player from a
sentence, from a kidnapper, or from their own captive, whichever applies.

## Configuration

`config/mcacrime-common.toml` (server-authoritative) and `config/mcacrime-client.toml`
(presentation only). Every option, its default, its range, and what switching it off actually does
is in **[CONFIG.md](CONFIG.md)** — including which options are declared but not yet wired.

### The Mask Station and Sand Bottles

```toml
[maskStation]
    enableMaskStationCrafting = true
    enableMaskRestyling = true

[sandBottle]
    enableSandBottles = true
    sandCooldownTicks = 80
    sandDirectDurationTicks = 80
    sandSplashDurationTicks = 40
    sandRadius = 2.0
    sandRecoveryTicks = 60
    sandAffectsPlayers = false
```

Turning station crafting off closes any open station menu at the next tick and hands the inputs
back; the block, its recipes and its worksite claim are untouched, so a thief keeps its job.
Turning restyling off hides every restyle style from the catalogue and leaves ordinary crafting
alone. For sand, the master switch stops new throws immediately — a bottle already in flight
applies nothing and discards itself, ammunition already spent is not refunded, and an effect
already running finishes normally. `sandAffectsPlayers` can only ever subtract: with it on, the
server's own PvP setting and team friendly-fire rules still apply, and catching yourself in your
own splash is never PvP.

Two more keys worth naming here. `memory.emptyHandApologyMode` (`CONTEXTUAL_DIRECT` by default,
or `MENU_ONLY`) decides whether a non-sneaking empty-hand right-click on a villager you wronged
apologizes directly or only the Crime menu does. `client.sandParticles` (`NORMAL`, `REDUCED` or
`OFF`) is presentation only — a blinded NPC is exactly as blind at every setting.

### Currency

`integrations.currencyId` selects what fines, bail, ransom, theft and bounties are paid in:
`mcacrime:emerald` (the default), `mcacrime:item` (the item named by `integrations.currencyItem`,
one item per unit), and `mcacrime:numismatic` (the Numismatic Overhaul purse, offered only when
that mod is installed; its balance is in bronze — 100 bronze is one silver, 10000 is one gold).
Economy mods register further ids at common setup with `McaCrimeApi.registerCurrency`. An id
nothing has registered falls back to `mcacrime:emerald` with a single warning rather than taking
fines, bail and ransom offline; `/crime validate` also reports an unregistered `currencyId`.

```toml
[integrations]
    currencyId = "mcacrime:item"
    currencyItem = "minecraft:emerald"
```

```toml
[integrations]
    currencyId = "mcacrime:item"
    currencyItem = "numismaticoverhaul:bronze_coin"
```

`currencyItem` is only read when `currencyId` is `mcacrime:item`, as a registry id. An item that is
absent, unparseable, or not registered falls back to emeralds with one warning, not one per
transaction. `mcacrime:numismatic` has no item form of its own: it spends and pays into the purse
attachment, not the inventory, so an item currency and the Numismatic purse are two different
balances even when both eventually mean coins. Numismatic Overhaul (Reforged Again) hard-depends on
owo-lib without declaring it, so install owo-lib alongside it or the server fails at Mixin
bootstrap before any mod loads.

Money already owed follows the currency it was earned in, not the currency configured when it is
paid. A queued payout (a bounty, a recovered-property lot) records the provider id it was
created with — for `mcacrime:item` that is `mcacrime:item/<namespace>/<path>`, naming the exact
item — so switching `currencyItem` afterwards does not change what a pending payment is owed in.

## Compatibility

Epic Fight's battle mode can swallow the right-click that would open MCA: Crime's menu (armed, or
holding a restraint) — MCA's own empty-handed conversation screen is deliberately not forwarded;
use Epic Fight's vanilla mode or set key_conflict_resolve_scope = NONE for that. Three MCA
bridges that ship alongside it are detected by id: "MC-Epicly-A" (`mcea`) and "EpicFight-MCA Patch"
(`efmca`) both make MCA villagers immune to all player damage, and "MCA Skin x Epic Fight
Compatibility" (`mcaefcompat`) is client rendering only and is reported rather than acted on.
MCA: Crime forwards the swallowed interaction and can only report the damage block. See
[docs/COMPATIBILITY_EPIC_FIGHT.md](docs/COMPATIBILITY_EPIC_FIGHT.md).

## For pack authors

Crime definitions are datapack-driven, and so are the incidents this mod publishes to MCA:
Reputation:

```
data/<namespace>/mcacrime/crimes/*.json
data/<namespace>/mcareputation/incidents/*.json
```

`/crime reload` swaps them in and reports the counts; `/crime validate` lists every problem with
its file and field. Schemas and worked examples are in **[DATAPACK.md](DATAPACK.md)**.

## For mod authors

A read-only, server-authoritative Java API plus events that include two cancellable Pre events.
Mutation is never exposed — it stays behind the single state chokepoint on purpose. See
**[API.md](API.md)**.

## Upgrading an existing world

Older saves are migrated on load through schema 12 without a server or a config
being consulted. The migration is **not reversible** — take a copy of your world first. The
policy, what changes about village identity, and what an old jar does with a new save are in
**[MIGRATION.md](docs/MIGRATION.md)**.

## Documentation

| File | What is in it |
|---|---|
| [CONFIG.md](CONFIG.md) | every config option, default, range, and disabled behaviour |
| [DATAPACK.md](DATAPACK.md) | crime and incident schemas with examples |
| [API.md](API.md) | the public Java API, events, and failure contracts |
| [MIGRATION.md](docs/MIGRATION.md) | schema migration, removal, and rollback |
| [CHANGELOG.md](CHANGELOG.md) | release notes |
| [NeoForge parity](docs/NEOFORGE_PARITY_2026-09-08.md) | 0.6.0 feature parity, platform adaptations, and validation |
| [0.7.2 stage notes](docs/0.7.2/) | the Thief occupation, Mask Station, mask catalogue and Sand Bottle as shipped on this port |
| [CURSEFORGE.md](CURSEFORGE.md) | the store listing copy |
| [mca-crime-spec-document.md](docs/mca-crime-spec-document.md) | the original design specification |
| [MCA_CRIME_SUITE_INTEGRATION_IMPLEMENTATION_PLAN.md](docs/MCA_CRIME_SUITE_INTEGRATION_IMPLEMENTATION_PLAN.md) | the suite integration design |
| [PHASE_2](docs/PHASE_2_VERIFICATION.md) · [PHASE_3](docs/PHASE_3_VERIFICATION.md) · [PHASE_4](docs/PHASE_4_VERIFICATION.md) · [PHASE_5](docs/PHASE_5_VERIFICATION.md) | the in-world checklists that must pass before a release is tagged |

## Building

Needs the Java toolchain configured in `build.gradle`. The build resolves it through the Gradle wrapper
and the foojay resolver if needed, so it works on any machine without needing `JAVA_HOME` pinned.

```bash
./gradlew build
```

`build/libs/mcacrime-<version>.jar` is the release artifact. The build also runs
`checkJarContents`, which fails if a companion mod's classes ever end up shaded into it.

To include every optional integration in a release, build the NeoForge sibling projects
`MCAReputation_1.21.1`, `MCAQuests_1.21.1` and `Locks_Reforged_1.21.1` first, then require all three
adapters when building Crime. For example, build Reputation first:

```bash
cd ../MCAReputation_1.21.1 && ./gradlew build
```

```bash
./gradlew build -PrequireReputation=true -PrequireQuests=true -PrequireLocks=true
```

Without the corresponding `require` flag, an unavailable sibling excludes that optional adapter.
Release flags make a missing dependency fail the build. If Locks is installed but the cuff adapter
is unavailable, cuff escape reports the problem and never substitutes a timed escape.

The test suite includes a probe that replays the entire MCA binding manifest against the real MCA jars
listed in `mca_probe_versions` in `gradle.properties`, each in its own class loader, and fails if anything
the mod needs has been renamed or removed. Because no class names an MCA type, the compiler can no
longer catch that; this is what replaces it. Add a version to `mca_probe_versions` whenever MCA moves again.

MCA Reborn loads in the NeoForge development runtime. Unit tests boot its mod loader, and
`runGameTestServer` exercises real world and attachment behavior. Client presentation still needs
in-game verification; see [the NeoForge parity report](docs/NEOFORGE_PARITY_2026-09-08.md).

## Licence

GPL-3.0-only, because this mod links against MCA Reborn's internals. See [LICENSE.md](LICENSE.md).

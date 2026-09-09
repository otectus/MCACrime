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
- **Crimes are data.** Seven ship as JSON — theft, harming a villager, assaulting a guard,
  jailbreak, kidnapping, killing a villager, and murder during a robbery — each with its own karma
  and Heat cost. A datapack can retune all seven or add its own.
- **Witnesses** see or hear crimes within offense-specific ranges. Walls block sight; sound alone
  identifies no suspect. Civilians carry their information to guards, and only sufficiently confident
  reports create public consequences. Local family conversations can spread uncertain accounts.
- **Intimidation and memory (0.6.0).** Victims can comply, panic, stall, resist or defy depending on
  weapon aim, personality, health, support and history. Persistent fear and anger affect later
  interactions and soften through time, apologies and matching-case reconciliation.
  To apologize, put weapons away, wait one minute after the incident, then sneak and
  right-click the villager with an empty main hand. Choose **Apologize** in the Crime menu;
  its MCA screen button and optional keybind also work unarmed. An apology helps repair
  trust but does not immediately erase fear or legal charges.
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
- **Mugging.** Rob a villager for a modest amount of emeralds and take a moderate theft charge.
  Kill that same villager shortly afterwards and the death is reclassified as murder during a
  robbery — the heaviest crime in the mod.
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

No hearts replacement. No trials. No positive karma for trading, gifting, or clicking through
dialogue; those are farmable and belong to systems that already own them. No crime between NPCs —
a villager with the Thief occupation robs players, never another villager. No mixins anywhere except
one client-side mixin, for restraint pose rendering, no per-tick village scans, no AI text
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
| **Crime** alone | Karma and Heat, the seven crimes, witnesses, guards, jail, kidnapping, ransom, mugging, fines, the ledger, and a built-in per-village standing store |
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
/crime payfine                               pay off cases in emeralds, oldest first
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

Older saves are migrated on load through schema 10 without a server or a config
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

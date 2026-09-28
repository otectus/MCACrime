# MCA: Crime — configuration

## Witness, intimidation and memory options (0.6.0)

All values below live in `mcacrime-common.toml` and are decided by the server.

| Group / key | Default | Range / behavior |
|---|---|---|
| `crimeAwareness.enableWitnessSystem` | `true` | Use per-crime visual perception; off uses the legacy visual witness scan. |
| `crimeAwareness.visualWitnessRadiusMultiplier` | `1.0` | `0–2`, applied to datapack visual radii. |
| `crimeAwareness.auditoryWitnessRadiusMultiplier` | `1.0` | `0–2`; zero disables hearing. Sound alone never identifies a suspect. |
| `crimeAwareness.enableWitnessGossip` | `true` | One nearby relative per direct observation, after a delay; rumors cannot relay again. |
| `intimidation.enableDynamicCompliance` | `true` | Reevaluate active victims; off uses the previous compliance decision. |
| `intimidation.enablePanic` / `enableStalling` / `enablePleading` | `true` | Enable these outcomes/presentation. Resistance and freezing retain `reactions.armedVillagersCanResist` and `reactions.freezeComplyingVictims`. |
| `intimidation.threatReevaluationTicks` | `10` | `5–100`; transitions also have at least twice this interval or 20 ticks of dwell time. |
| `intimidation.meleeThreatRange` | `6.0` | `1–12` blocks; distance reduces threat. |
| `intimidation.rangedThreatRange` | `24.0` | `4–64` blocks; bows must be drawn and crossbows charged. Existing action reach still applies. |
| `victimMemory.enableVictimMemory` | `true` | Enables new memories and their behavioral effects; off preserves stored data. |
| `victimMemory.enableFamilyMemory` | `true` | Reduced memories for informed family. |
| `victimMemory.memoryDecayMultiplier` | `1.0` | `0–10`; zero pauses emotional decay. World game time advances while the server runs, including while the offender is offline. |
| `victimMemory.enableRestitution` | `true` | Matching theft/robbery case payment reduces memory anger. |
| `victimMemory.enableApologies` | `true` | Requires a memory, no drawn weapon or coercive session, and at least 1,200 ticks since the incident. |
| `victimMemory.maximumMemoriesPerVillager` | `24` | `1–64`; enforced when recording, merging repeats and preferentially retaining severe memories. |
| `victimMemory.apologyCooldownTicks` | `24000` | `1200–168000`; one emotional benefit per current incident, with the cooldown retained after repeats. |

Existing `detection.observations.enableObservations`, report radius/statute, confidence threshold,
reaction limits and navigation intervals continue to apply. Arrest requires at least probable
identification (0.5), even if the configurable threshold is lower. Core witness sight always checks
line of sight; no option allows walls to provide a confident suspect. Existing global propagation
remains opt-in and is only applied after a report reaches authority.

The legacy `hearingWitnessRadius` is superseded by offense-specific sound radii and the auditory
multiplier in this phase. Mugging action reach is unchanged; a larger intimidation range does not
permit remote purse transfers.

Two files, written on first run:

- `config/mcacrime-common.toml` — **server-authoritative**. On a dedicated server the server's copy
  is the one that matters; a client's copy of these values is never consulted for anything.
- `config/mcacrime-client.toml` — **presentation only**. Changing what you see changes nothing about
  what the server does.

Three rules hold throughout:

1. **Everything is config.** Every number, chance, threshold, duration, and toggle in this mod is an
   option here rather than a constant in the source. The whole key set was declared up front so the
   generated TOML shows the entire design surface from day one — which means a few blocks are
   present but **not yet wired**. Those are marked below; do not tune them expecting an effect.
2. **Disabling a subsystem never deletes anything.** Turn detection off and no new crime is recorded;
   every existing case, sentence, and standing stays in the save untouched.
3. **Config may tighten a bound, never loosen one.** Stored collections and clocks are clamped in the
   source too, so hand-editing the TOML cannot produce an unbounded witness list or an unservable
   sentence.

`/crime validate` (permission level 3) runs the validator on demand and lists every problem it finds.
The same checks run at load and log warnings.

---

## `[bands]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `karmaBlueThreshold` | `100` | `-1000000 … 1000000` | Karma at or above this is the Lawful band. Must be greater than the Red threshold. |
| `karmaRedThreshold` | `-100` | `-1000000 … 1000000` | Karma at or below this is the Outlaw band. Must be less than the Blue threshold. |
| `wantedHeatThreshold` | `50` | `0 … 1000000` | Heat at or above this makes a player Wanted. Nearby available guards and archers pursue and confront them even without a crime report, including Heat set by commands. |

Bands are derived from karma alone and never read Heat. If the two thresholds are inverted, band
derivation refuses to produce nonsense and falls back to ±100 — but `/crime validate` reports it, and
that is the single most important thing the validator checks.

## `[karma]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `karmaMin` | `-1000000` | `-1000000000 … 0` | Lower clamp on karma. |
| `karmaMax` | `1000000` | `0 … 1000000000` | Upper clamp on karma. |
| `karmaDecayPerDay` | `1` | `0 … 1000000` | Karma normalised toward zero by this much per **online** MC day (24000 ticks). `0` freezes karma where it is. |
| `unwitnessedKarmaFactor` | `1.0` | `0.0 … 1.0` | Fraction of the karma penalty applied when nobody saw the crime. `1.0` means a private crime costs the same karma as a public one; lower values make conscience cheaper than reputation. |

### `[karma.rewardWeights]` — declared, not yet wired

| Option | Default | Range |
|---|---|---|
| `tradeKarma` | `1` | `-1000 … 1000` |
| `giftKarma` | `1` | `-1000 … 1000` |
| `questCompleteKarma` | `5` | `-1000 … 1000` |
| `defendVillageKarma` | `5` | `-1000 … 1000` |
| `protectVillagerKarma` | `10` | `-1000 … 1000` |
| `failQuestKarma` | `-2` | `-1000 … 1000` |

**No source writes positive karma yet.** The per-villager, per-village, and per-player daily counters
that would cap it are persisted and reset on the MC-day epoch, but nothing increments them. These
weights exist so the shape is fixed before the sources arrive; changing them today does nothing.

## `[heat]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `heatMax` | `1000000` | `0 … 1000000000` | Upper clamp on Heat. |
| `heatDecayPerMinute` | `1` | `0 … 1000000` | Heat bled off per **online** minute (1200 ticks). `0` means Heat never fades on its own and must be paid or served off. |
| `requireWitnessForHeat` | `true` | — | Only witnessed crimes generate Heat. **This is the load-bearing option in the mod.** With it off, the law reacts to crimes nobody saw, and hiding a murder stops being possible. |

Both decay clocks count online time only. Logging out pauses them and a restart resumes them, so no
consequence in this mod can be waited out while offline.

## `[detection]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `enableCrimeDetection` | `true` | — | Master switch. Off means no new crime is ever recorded; existing cases, Heat, and sentences are untouched. |
| `witnessRadius` | `12` | `1 … 64` | Block radius around the **victim** in which a villager or guard with line of sight becomes a witness. Scanned only when a crime happens, never on a tick. |
| `communitySearchRadius` | `64` | `0 … 256` | How far beyond a village's border a victim with **no home village** is still charged to that village (0.7.5). A Guard Villagers guard bridged by MCA: Mob Compatibility has a hidden MCA stand-in with no residency; before this, a crime against it named no community and MCA: Reputation refused it as invalid. Uses the victim's own position, never the offender's. `0` restores the old home-village-only rule. |
| `harmCooldownTicks` | `20` | `0 … 6000` | Minimum ticks between counted harm crimes against the same victim by the same player, so a melee flurry is one crime rather than many. `0` counts every hit. |
| `maxStoredWitnesses` | `8` | `1 … 64` | How many witness identities one record keeps. The nearest are kept and the true crowd size is recorded separately, so a riot outside a busy village does not write an unbounded list into the save. |

Witnesses are resolved against the victim's position and level, not the offender's — an arrow through
a portal is witnessed where it lands. Identities are captured once, at detection, and never
re-derived.

### `[detection.observations]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `enableObservations` | `true` | — | Record who saw a crime, in what role, and how sure they are. Off falls back to the count-only witness scan: no reports, no reaction triggers, and guards that know only what Heat says. |
| `hearingWitnessRadius` | `16` | `0 … 64` | Radius in which a struggle can be heard without being seen. Deliberately larger than the sight radius, and halved when something solid is in the way. `0` disables hearing witnesses. |
| `reportRadius` | `24` | `1 … 128` | How far a frightened witness will search for a guard to report to. Below `witnessRadius` most reports never get filed, and the validator says so. |
| `observationStatuteTicks` | `168000` | `1200 … 10000000` | How long an undelivered observation stays reportable. Past it the observation is marked **expired**, not deleted — "they saw it and never told anyone in time" is a different fact from "nobody saw it". |
| `reportConfidenceThreshold` | `0.6` | `0.0 … 1.0` | Report confidence at or above which an arrest is justified. Below it a guard investigates instead, which is what a heard-but-unseen crime should produce. A responder who saw it themselves always clears this. |

An observation existing never adds Heat on its own. The crime's own commit already charged what the
crime type is worth; charging again per witness would make a crowded street cost several times what
the same act costs in an alley, for reasons the player cannot see. What a filed report changes is
**jurisdiction** — which village's guards have a basis to act, and whose standing drops.

## `[reactions]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `enableVillagerReactions` | `true` | — | The reaction state machine. Villagers with an active reaction are driven by a bounded server-side controller; a calm villager is left entirely to MCA's own AI. |
| `reactionTickIntervalTicks` | `5` | `1 … 100` | Ticks between state evaluations for one active reaction. |
| `reactionNavigationIntervalTicks` | `10` | `1 … 200` | Ticks between path reissues. Slower than state evaluation on purpose: reissuing every tick fights MCA's own sensors and produces visible jitter. |
| `maxActiveReactions` | `64` | `1 … 512` | Hard ceiling on simultaneously reacting villagers. Past it new reactions are refused rather than queued, so a village-wide panic stays bounded rather than scaling with the crowd. |
| `reactionThreatenedTicks` | `60` | `0 … 12000` | How long a villager faces the actor deciding what to do. |
| `reactionFleeTicks` | `200` | `0 … 12000` | How long a flight lasts before it becomes hiding. |
| `reactionSeekHelpTicks` | `400` | `0 … 24000` | How long a witness spends trying to reach a guard before giving up and running instead. The observation stays pending either way. |
| `reactionHideTicks` | `600` | `0 … 24000` | How long a villager stays hidden before recovering. |
| `reactionRecoveryTicks` | `1200` | `0 … 72000` | How long they keep refusing or altering interaction with the offender afterwards. Memory outlives this; only the behaviour stops. |
| `safeDestinationSamples` | `8` | `1 … 32` | How many candidate destinations a fleeing villager scores. Bounded sampling — there is never an unbounded POI search on the server thread. |
| `civilianCrimeReactionSpeedMultiplier` | `0.65` | `0.10 … 1.00` | Movement speed of an unarmed villager while this mod is steering them, as a fraction of normal. Applied as a transient attribute modifier and removed on every exit path, so it can never persist. |
| `complianceResistThreshold` | `0.6` | `0.0 … 1.0` | Bravery at or above which an unthreatened villager fights rather than runs. |
| `complianceHelpThreshold` | `0.5` | `0.0 … 1.0` | Score at or above which a villager fetches a responder rather than running, when there is one to fetch. |

There is no scan of villagers anywhere in this system. Reactions are created by an event and only
existing controllers are ticked, so a world with three hundred villagers and one mugging in progress
does one villager's worth of work.

## `[victimMemory]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `emptyHandApologyMode` | `CONTEXTUAL_DIRECT` | `CONTEXTUAL_DIRECT`, `MENU_ONLY` | How the empty-hand apology gesture behaves. `CONTEXTUAL_DIRECT`: right-clicking a villager you wronged while not sneaking, with an empty main hand and no weapon in the off hand, apologizes directly. `MENU_ONLY`: only the Crime menu, its keybind and the screen button offer it. Neither mode changes settling time, cooldowns or how much is repaired. |

## `[dialogue]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `enableDialogue` | `true` | — | Data-driven villager lines. The server picks the line and the client renders the key, so lines localise and no text crosses the wire. |
| `dialogueCooldownTicks` | `40` | `0 … 12000` | Minimum ticks between spoken lines from one villager to one player. Per **pair**, not global, so a village reacting to a murder produces several villagers each saying something once. |
| `dialogueMessageFormat` | `<%1$s> %2$s` | — | How a spoken line is laid out in chat. `%1$s` is the villager's name, `%2$s` the line. A template missing either placeholder falls back to `%1$s: %2$s`. |
| `dialogueNameColor` | `#FFC34D` | — | Hex colour (`#RRGGBB`) for the villager's name in spoken lines. An unparseable value leaves the name uncoloured. |
| `dialogueNameBold` | `true` | — | Whether the villager's name in spoken lines is bold. |

Lines live in `data/<namespace>/mcacrime/dialogue/*.json` and reload with `/reload`. A pack replaces
a pool by declaring the same `event`. Dialogue never determines an outcome: nothing downstream
branches on what was said, so rewriting every line cannot change what happens.

The `dialogueMessageFormat`/`dialogueNameColor`/`dialogueNameBold` defaults mirror MCA Conversations'
`chatModeMessageFormat` and its bold gold name styling, so a villager reads the same whichever mod
is speaking.

## `[antifarm]` — declared, not yet wired

| Option | Default | Range |
|---|---|---|
| `perVillagerDailyKarmaCap` | `20` | `0 … 1000000` |
| `perVillageDailyKarmaCap` | `50` | `0 … 1000000` |
| `perPlayerDailyKarmaCap` | `100` | `0 … 1000000` |
| `diminishingReturnsFactor` | `0.5` | `0.0 … 1.0` |

These cap *positive* karma, and no positive karma source exists yet. See `[karma.rewardWeights]`.

## `[enforcement]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `enableVillagerFlee` | `true` | — | Ordinary villagers flee Outlaw players using vanilla navigation. |
| `villagerFleeRadius` | `10.0` | `1.0 … 64.0` | How close an Outlaw must be for villagers to run. |
| `guardAggroRadius` | `16.0` | `1.0 … 128.0` | How far a guard is made to pursue a legal target. |
| `guardScanIntervalTicks` | `10` | `1 … 200` | Server ticks between guard-pursuit scans, and the re-apply cadence. The scan is bounded by the number of online legal targets, so it is never a per-tick world sweep — raise this on a large server before touching anything else. |
| `redIsLegalTarget` | `false` | — | Whether simply being an Outlaw makes force against you lawful, with no Wanted status needed. |
| `allowKillingRed` | `false` | — | Whether **lethal** force against a player who is only an Outlaw is lawful. Separate from `redIsLegalTarget` on purpose: a server can allow subduing an outlaw without allowing executing one. Wanted status, an escape, or actively holding a captive each justify lethal force regardless of this setting. |
| `raidGrace` | `true` | — | Forgive the first nonlethal indirect explosion against a protected non-player, non-responder in a combat encounter during an active raid. Direct attacks, arrows, repeated hits and killing remain chargeable. Encounter grace resets only after 200 ticks without harm between those actors. |
| `pvpCountsAsCrime` | `false` | — | Whether harming another player is recorded as a crime. On, it produces `mcacrime:assault_player` and `mcacrime:murder_player` — their own crime types, not the villager ones. Force against a legal target is still lawful, so attacking a Wanted player is never itself an offence. |
| `globalCrimePropagation` | `false` | — | Whether a filed report sours every village that already knows you, rather than only the jurisdiction that received it. Off keeps standing local, which is what makes per-village reputation mean anything. It also decides whether a guard may act on another village's reports. |
| `enableGuardChallenge` | `true` | — | A guard with a basis challenges before it attacks: it states the charge and opens a window to surrender, pay, ask what the charges are, or refuse. Off returns guards to attacking a Wanted player on sight. |
| `guardChallengeWindowTicks` | `300` | `300 … 12000` | At least **15 seconds** to answer from the first displayed menu frame. Delivery acknowledgment has a bounded five-second allowance. Reopening/requoting does not restart the timer. No answer is a refusal. Older shorter settings are raised to 300. |
| `guardChallengeRadius` | `6.0` | `1.0 … 32.0` | How close a guard must be to issue a challenge. Smaller than `guardAggroRadius`, so guards do not shout charges across a field. |
| `resistingArrestTicks` | `2400` | `20 … 1728000` | How long refusing a challenge keeps you a lawful target, in online ticks. This is what makes refusal a decision rather than a message: for the duration, guards may use force whether or not your Heat would otherwise justify it. |

`/crime set heat <player> 100` makes the player Wanted with the default threshold (50), without
requiring a witnessed crime or filed report. The next guard scan (10 ticks / half a second by
default) sends an available nearby guard or archer toward the player; confrontation starts
within 6 blocks and line of sight. The acquisition radius defaults to 16 blocks. Sleeping,
captive, unloaded or already-assigned responders cannot take the encounter, and an existing
arrest or recovery period retains control. The mod does not spawn or teleport a guard for a
Heat command. Custom `wantedHeatThreshold` settings still determine when pursuit begins.

A stop based only on Wanted status explains that the player must surrender, without inventing
ledger charges or a fine. Refusal permits force even if Heat subsequently falls below the
threshold, until resistance expires or is resolved. Otherwise clearing/decaying Heat below
the threshold ends a stop that has no remaining basis. Heat does not reveal private or remote
crime records to guards; their case lists and fines retain the configured jurisdiction rules.

### `[enforcement.guards]`

Keeps villages topped up to a share of guards by converting eligible adult villagers. **Off by
default.** Takes effect on the next scan; no restart is required.

| Option | Default | Range | What it does |
|---|---|---|---|
| `manageGuardPopulation` | `false` | — | Master switch. Read the note below before turning it on: MCA already does this, at a higher fraction. |
| `guardPopulationRatio` | `0.10` | `0.0 … 1.0` | Fraction of a village's population that should be guards. `0.10` = 10%. |
| `guardPopulationMinimum` | `1` | `0 … 64` | Fewest guards a village with any population at all should have. An empty village is still left alone. |
| `guardPopulationMaxPerPass` | `1` | `1 … 16` | How many villagers may be converted in one pass, so a village grows its guard force gradually rather than a third of the population changing clothes at once. |
| `guardPopulationScanIntervalTicks` | `1200` | `200 … 72000` | Game ticks between passes. One village in one dimension is examined per pass, and only dimensions that have players in them are considered at all. |
| `guardPopulationCooldownTicks` | `6000` | `1200 … 1728000` | Game ticks before the same village is examined again. Should be comfortably longer than the scan interval, and `/crime validate` says so if it is not. |
| `guardThiefResponseRadius` | `24.0` | `4.0 … 64.0` | How far a guard will notice a mugging in progress. Line of sight is required as well, so this is the range at which a guard who can already see the threat reacts to it. |
| `guardThiefPursuitTimeoutTicks` | `600` | `40 … 24000` | How long a guard chases a thief before giving up. The chase also ends when the thief gets further away than `guardAggroRadius`. |
| `guardsUseForceOnArmedThieves` | `true` | — | An armed thief is fought rather than merely chased. Off makes every arrest a non-lethal capture. |
| `returnStolenGoodsOnArrest` | `true` | — | A guard hands back what the thief took, to any victim standing nearby. |
| `stolenGoodsReturnRadius` | `16.0` | `1.0 … 64.0` | How close a victim must be to the arrest to be handed their property back. Owners further away keep their claim: the ledger entry is untouched. |
| `npcEscortOrphanTicks` | `1200` | `100 … 24000` | How long a restrained thief waits for a replacement escort after its guard dies or wanders off, before being jailed where it stands. |

**How the target rounds.** `ceil(population × ratio)`, floored at `guardPopulationMinimum` and capped
at the population itself. So 10 villagers ask for 1 guard, 20 ask for 2, 50 ask for 5, and 11 ask for
2 — it always rounds up. A village with nobody in it asks for none: the minimum is a floor on a real
village, not a way to conjure a guard out of an empty one.

**What counts as a guard.** Everything the mod recognises as law, plus everything MCA does: this mod's
`guard` profession match, anything added through `responderEntities`, MCA's archers, and any guard
spawned by a command or placed by hand. Babies are never eligible, and neither is a villager MCA
considers to hold an important profession. Residents in unloaded chunks are credited their share of
the target rather than counted as missing, which is what stops a half-loaded village reading as
guardless and being over-converted the moment the rest of it loads.

**Only ever adds.** Nothing here converts a guard back into a villager, so a population that wobbles
by one cannot make guards appear and disappear — the count only rises, and then stops. Repeated
re-evaluation is bounded by the per-village cooldown rather than by a margin on the count.

> **MCA already does this.** MCA Reborn's own `VillageGuardsManager` targets
> `ceil(population × guardSpawnFraction)`, and `guardSpawnFraction` defaults to **0.175** — higher
> than the `0.10` here. With both systems running, MCA reaches its target first and this pass finds
> nothing to do, which looks like the option is broken when it is simply satisfied. Enable this only
> if you have turned MCA's fraction down, or want a floor MCA's fraction does not give you. Whichever
> target is higher wins; the two do not add up. `/crime debug guards` prints
> `population / loaded / guards / target / needed` per village, which is the quickest way to see which
> of the two is doing the work.

## `[kidnapping]`

Kidnapping is a **legal** state: who holds whom, unlawfully, and what the law does about it. What is
physically on the captive's body moved to `[restraints]` in 0.7.5, and the physical keys that used to
live here are retired — see *Retired in 0.7.5* below.

| Option | Default | Range | What it does |
|---|---|---|---|
| `enableKidnappingNpc` | `true` | — | Whether villagers can be taken captive. |
| `enableKidnappingPlayer` | `true` | — | Whether players can. |
| `npcCaptiveVirtualizeWhenUnloaded` | `true` | — | An NPC captive in an unloaded chunk is virtually contained rather than force-loading the chunk. Turning this off makes every captive a permanently loaded chunk. |
| `maxUnlawfulCaptivesPerCaptor` | `1` | `1 … 16` | Maximum simultaneous unlawful captives owned by one captor. |
| `captorDisconnectGraceTicks` | `1200` | `0 … 72000` | Player captive release grace after their captor disconnects. |
| `enableRescue` | `true` | — | Let a third party free somebody else's captive. With it off, only the captor or the captive can ever end a captivity. |
| `rescueChannelTicks` | `40` | `0 … 6000` | Channel duration to cut or unlock another player's captive free. |

Escaping lawful cuffs preserves the assessed sentence, pauses sentence credit, ends the escort and
files a jailbreak. Recapture or surrender resumes that sentence; returning to the jail region alone
does not undo an escape. Rescue, captor release, administration and captivity failsafes still work.

Since 0.7.5 escape is physical work against the worn restraint, tuned under `[restraints.escape]` and
`[lockpicking]`, and it needs neither Cuffed nor Locks Reforged installed. Locks Reforged is still an
optional companion for its own locks and for fence stock; it no longer supplies the cuff minigame.

## `[criminalJobs]`

Autonomous criminal professions: thieves who target and rob players, and fences who trade contraband
with dynamic pricing.

| Option | Default | Range | What it does |
|---|---|---|---|
| `villageFenceChance` | `0.010` | `0.0 … 1.0` | Chance a village villager becomes a fence. Fences belong to settlements, not the wilderness. |
| `wildThiefChance` | `0.0025` | `0.0 … 1.0` | Chance for a villager with no home village. Independent criminals should be rare. |
| `minVillagePopulationForFence` | `5` | `1 … 200` | A village smaller than this never produces a fence. |
| `presentFenceAsMcaProfession` | `true` | — | Show a fence as the `mcacrime:fence` villager profession. The previous profession is remembered and restored if this is turned off again. |
| `presentThiefAsMcaProfession` | `false` | — | Show a thief as the `mcacrime:thief` villager profession. Off by default: a thief wearing a label has no cover. |
| `staleRecordGraceDays` | `14` | `1 … 365` | Days a criminal record is kept after the villager was last seen loaded. |

### `[criminalJobs.thief]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `mugProtectionHearts` | `50` | `-1 … 1000` | A villager will not mug a player with at least this many MCA relationship hearts toward that villager. `-1` disables relationship protection; `0` protects neutral and positive relationships. |
| `thiefJailTicks` | `12000` | `200 … 240000` | Sentence length in online ticks when a thief is arrested. 12000 = 10 minutes. A thief comes out of jail still a thief. |
| `mugDurationTicks` | `80` | `20 … 600` | How long the victim's bar takes to fill. Four seconds by default. |
| `scanIntervalTicks` | `30` | `10 … 200` | Ticks between a scouting thief's target scans. |
| `targetSearchRadius` | `20.0` | `4.0 … 64.0` | How far a thief will consider a victim. |
| `guardAvoidRadius` | `16.0` | `0.0 … 64.0` | Guards within this distance contribute to the thief's risk score. |
| `guardHardAbortRadius` | `8.0` | `0.0 … 64.0` | A guard this close is a problem whatever else is true: the risk score jumps and the thief breaks off. |
| `guardRiskAbortThreshold` | `0.6` | `0.0 … 1.0` | Risk score at or above which a target is refused and an approach broken off. |
| `minCurrencySteal` | `1` | `0 … 1000000` | Smallest amount of the active currency one mugging takes, when there is that much to take. |
| `maxCurrencySteal` | `8` | `0 … 1000000` | Largest amount one mugging takes. Must not be below the minimum. |
| `stealAllIfBelowMinimum` | `true` | — | When the victim holds less than `minCurrencySteal`, take all of it. Off skips currency entirely and falls through to the item. |
| `protectHotbar` | `true` | — | Thieves never reach into the hotbar. What you are holding is yours. |
| `protectArmor` | `true` | — | Thieves never take worn armor. |
| `protectOffhand` | `true` | — | Thieves never take the offhand item. |
| `itemTheftMode` | `SINGLE_ITEM` | `SINGLE_ITEM`, `WHOLE_STACK`, `RANDOM_COUNT` | How much of the chosen slot goes. `SINGLE_ITEM` is the default because it is the only one whose punishment does not depend on how the victim stacked. |
| `stolenGoodsPersistenceDays` | `7` | `0 … 365` | Days a stolen item stays attributable to its owner before it is laundered out of the world data. `0` keeps stolen goods forever; goods are never expired out from under a thief who is still mugging or fleeing. |

Set the threshold in `config/mcacrime-common.toml` on the server (or in your singleplayer
instance), under `[criminalJobs.thief]`. Pack authors can ship that same common config. The
`enableThieves` master switch is under `[criminalJobs]`; there is no `enableMuggingThieves` option.

The threshold is inclusive and uses each villager's relationship with the individual player,
not Karma or village reputation. With the default, 49 hearts remains eligible and 50 hearts
is protected. A friend of one thief can still be targeted by another thief who has fewer hearts
with that player. Relationship gains and config reloads also stop an approach or an ongoing
mugging before property is taken, including attempts started with `/crime mugtest`. Checks do
not depend on the thief being shown as an MCA profession. Other victim protections continue
to apply when the threshold is disabled. If MCA's heart lookup is unavailable, it returns 0,
following the compatibility layer's existing fallback.

**Mugging frequency (0.7.0).** Every cooldown above belongs to the thief; the keys below belong
to the *victim*, and are therefore shared by every thief in the world. Without them, one player
could be robbed by several different thieves in quick succession, each of them well inside its
own limit.

| Option | Default | Range | What it does |
|---|---|---|---|
| `enableNpcMugging` | `true` | — | Villagers may mug players. Off leaves thieves and fences in place as occupations; they simply never rob anybody. |
| `muggingFrequencyMultiplier` | `1.0` | `0.1 … 10` | Scales every victim-scoped cooldown below. Above 1 means muggings happen more often (the windows get shorter); below 1 means less often. |
| `playerMugProtectionTicks` | `36000` | `0 … 1728000` | How long after any mugging, successful or not, no thief may rob this player again. 30 in-game minutes by default. |
| `respawnMugProtectionTicks` | `6000` | `0 … 1728000` | Grace period after respawning. |
| `loginMugProtectionTicks` | `1200` | `0 … 1728000` | Grace period after logging in. |
| `releaseMugProtectionTicks` | `12000` | `0 … 1728000` | Grace period after release from a cell or from custody. |
| `thiefVictimRepeatCooldownTicks` | `72000` | `0 … 1728000` | How long before the same thief may rob the same player again. Longer than the shared protection above on purpose. |
| `maxMuggingsPerPlayerPerDay` | `2` | `0 … 64` | Most muggings one player may suffer in an in-game day. Only completed muggings count. `0` disables the cap. |
| `maxActiveThievesPerJurisdiction` | `2` | `0 … 64` | Most thieves one village may have at once. `criminalAssignmentCooldownDays` throttles how often a village produces a thief; this is the live count nothing previously kept. |

A grant never shortens an existing protection window — the longest one already in effect always
wins, whichever of the grants above produced it.

**Changed defaults (0.7.0).** Three defaults were retuned to reduce how often a player is
mugged: `villageThiefChance` `0.025` → `0.01`; `assignmentScanIntervalTicks` `1200` → `2400`
(both under `[criminalJobs]`); `mugCooldownTicks` `12000` → `24000` (this table). All three are
unchanged keys with new default values — nothing was renamed or removed. See
[the verification doc](docs/FAMILY_CONTRABAND_MUGGING_VERIFICATION.md) for why the old values let
one player be mugged repeatedly in a short span.

### `[criminalJobs.fence]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `maxKarmaDiscount` | `0.25` | `0.0 … 1.0` | Largest discount criminal standing earns, at the Red band threshold. `0.25` = 25% off. |
| `maxHeatMarkup` | `0.35` | `0.0 … 1.0` | Largest surcharge Heat adds, at the Wanted threshold. Stacks with the discount above. |
| `wantedMarkup` | `0.20` | `0.0 … 1.0` | Flat surcharge added on top while the player is Wanted. |
| `minimumPriceMultiplier` | `0.55` | `0.05 … 1.0` | Floor on the combined sell-side multiplier. Must be below `maximumPriceMultiplier`. |
| `maximumPriceMultiplier` | `2.50` | `1.0 … 10.0` | Ceiling on the combined sell-side multiplier. |
| `buyPriceRatio` | `0.5` | `0.05 … 0.95` | What a fence pays for goods, as a fraction of the base price. **`FencePolicy`'s compact constructor clamps this to at most `minimumPriceMultiplier` on load** (not `ConfigValidator`, which only warns that the clamp will apply): the buy price is computed independently from the base price rather than from the marked-up sell price, so it can never rise with Heat, and the ratio can never let a player buy and resell the same item at a profit. |
| `defaultBasePrice` | `8` | `1 … 100000` | Price used for contraband that a tag names but no `fence_prices` file gives a value. |
| `offerCount` | `6` | `1 … 12` | How many trades one fence offers at a time. |
| `offerMaxUses` | `8` | `1 … 4096` | How many times one of a fence's trades may be repeated before that stock runs out. Uses are tracked per fence in `FenceStockRecord` and persist across closing the screen, relogging, and a restart; they reset when the fence restocks. |
| `restockIntervalDays` | `1` | `0 … 30` | In-game days a fence keeps the same stock. `0` re-rolls it every time it is opened. |

The fence occupation's own master switch, `enableFences` (`true`), is not a key of this table: it is
declared one level up, under `[criminalJobs]`, alongside `enableThieves`. Off, `FenceTradeActionHandler`
refuses fence trades and the criminal-job sweep never assigns a fence.

## `[npccrime]` — `enableNpcCrime` declared, not yet wired

| Option | Default | Range | What it does |
|---|---|---|---|
| `enableNpcCrime` | `false` | — | Declared, not wired; see below. |
| `maxActiveNpcCrimesPerVillage` | `2` | `0 … 1000` | Declared, not wired; see below. |
| `minTimeBetweenNpcCrimes` | `6000` | `0 … 1000000` | Declared, not wired; see below. |
| `npcMugHudUpdateIntervalTicks` | `3` | `1 … 20` | Ticks between progress packets for a thief's mugging bar during an NPC-initiated mugging (`NpcMuggingService`). The client interpolates between updates, so this is packet volume rather than smoothness. |
| `npcMugWeaponCheckIntervalTicks` | `1` | `1 … 10` | Ticks between checks of whether a mugging victim has drawn a weapon. `1` checks every tick, so drawing a weapon stops the mug immediately rather than on the next scheduled check. |

Villagers still do not decide to commit a crime of their own accord — `enableNpcCrime` and its two
throttles (`maxActiveNpcCrimesPerVillage`, `minTimeBetweenNpcCrimes`) remain a declared seam that
changes nothing, and turning `enableNpcCrime` on produces a validator warning saying so.
`npcMugHudUpdateIntervalTicks` and `npcMugWeaponCheckIntervalTicks` are the exception: they are read
by `NpcMuggingService` for the mugging sessions a Thief villager already starts, so they are wired
regardless of `enableNpcCrime`. That is separate again from `[npccrime.accomplices]` below, which
**is** shipped: it is player-initiated crime with a villager accomplice, not NPC-initiated crime, so
it is not gated on `enableNpcCrime` at all.

### `[npccrime.accomplices]`

Family who help commit a crime, and family who can buy an arrested relative out of jail again. A
relative only ever acts because a player asked them to; the villager is individually wanted,
arrestable and bailable for what they did, exactly as a thief is. On by default.

| Option | Default | Range | What it does |
|---|---|---|---|
| `enableAccomplices` | `true` | — | Master switch for the whole feature. |
| `accompliceScope` | `["SPOUSE","CHILD","SIBLING"]` | `SPOUSE, PARENT, CHILD, SIBLING, EXTENDED, IN_LAW` | Which relatives may be recruited. |
| `accompliceHeartsRequired` | `65` | `0 … 100` | MCA relationship hearts needed before a relative will help at all. |
| `accompliceRecruitCooldownTicks` | `6000` | `0 … 240000` | Ticks a relative waits after helping before they will be asked again. |
| `lookoutDurationTicks` | `2400` | `200 … 24000` | How long a lookout watches the street for you. |
| `lookoutWitnessRadiusMultiplier` | `0.6` | `0.1 … 1.0` | Witness radius multiplier while a lookout is posted. Lower is safer. |
| `lookoutWarnRadius` | `24.0` | `4 … 64` | How far a lookout looks for an approaching guard, in blocks. |
| `lookoutWarnCooldownTicks` | `100` | `20 … 1200` | Minimum ticks between two warnings from the same lookout. |
| `distractionDurationTicks` | `600` | `100 … 6000` | How long a distraction holds ordinary villagers' attention. |
| `distractionRadius` | `10.0` | `2 … 32` | Blocks around the distraction inside which a civilian sees nothing else. Guards are never distracted. |
| `escapeHelpDurationTicks` | `400` | `100 … 6000` | How long a relative's interference keeps guards off you. |
| `escapeHelpEscapeBonus` | `0.5` | `0.0 … 5.0` | Applied as a divisor on the work required to get out of a restraint, not as extra progress per tick: `0.5` means the requirement is divided by `1.5`, so it comes off a third faster while the window is open. |
| `implicateOnPrincipalArrest` | `true` | — | If true, arresting the player also makes any active accomplice wanted. |
| `accompliceJailTicks` | `6000` | `0 … 240000` | Sentence served by an arrested accomplice, separate from `thiefJailTicks`. |
| `notifyFamilyOnArrest` | `true` | — | Tell online relatives of an accomplice when they are arrested and when they are released. |
| `enableFamilyBail` | `true` | — | Let a relative buy an arrested accomplice out of the rest of their sentence. |
| `bailBase` | `64` | `0 … 1000000` | Flat part of the bail price, in emeralds. |
| `bailPerThousandTicks` | `8.0` | `0.0 … 1000` | Emeralds added per thousand ticks of sentence still to serve. |
| `bailMin` | `16` | `0 … 1000000` | Floor on the quoted price. |
| `bailMax` | `4096` | `0 … 1000000` | Ceiling on the quoted price. |
| `bailRepeatMultiplier` | `1.5` | `1.0 … 10` | Price multiplier compounded once per prior arrest of that relative. |

**The three actions.** Each is reached from the Crime menu's `Conspire` category and requires the
target to be family within `accompliceScope`, at or above `accompliceHeartsRequired`, and not
already an active accomplice on cooldown (`AccompliceGate`):

- **Ask for a lookout** — while posted, the offender's witness radius is multiplied by
  `lookoutWitnessRadiusMultiplier` for `lookoutDurationTicks`, so fewer villagers are ever
  considered as witnesses at all; the lookout also warns the player once a responder comes within
  `lookoutWarnRadius`, at most once per `lookoutWarnCooldownTicks`.
- **Ask for a distraction** — the relative walks a short distance off and holds the attention of
  every ordinary villager within `distractionRadius` for `distractionDurationTicks`; those
  villagers cannot become witnesses for the duration. Guards are never distracted.
- **Ask for escape help** — one-shot: every responder currently pursuing the player immediately
  loses its target, and for `escapeHelpDurationTicks` afterward guards do not re-acquire them and
  a worn restraint comes off faster (see `escapeHelpEscapeBonus` above).

**Exposure.** An accomplice is not automatically safe. Whenever an effect fires, any nearby
villager who is neither loyal to the player nor another accomplice is checked with the same
perception rules a crime's own witnesses are — if one of them sees the act, the relative is charged
with `mcacrime:aiding_a_criminal` and becomes wanted. Separately, if `implicateOnPrincipalArrest` is
on and the principal player is jailed while the agreement is still active, every relative currently
helping them is implicated at once. A relative already wanted is never exposed a second time.

**Arrest and bail.** A wanted accomplice is arrested through the same NPC custody path a thief is
— nothing new was built for it — and serves `accompliceJailTicks` rather than `thiefJailTicks`. With
`enableFamilyBail` on, a family member can pay to release a lawfully held relative: the price is
`clamp(round(bailBase + bailPerThousandTicks × remainingTicks/1000) × bailRepeatMultiplier^priorArrests, bailMin, bailMax)`,
priced on the sentence still to serve rather than the original term, and compounding once per prior
arrest of that same relative. The quote is sent to the client as a `BailQuoteS2CPacket`; paying uses
the same currency sink as a player's own self-bail. If the player's balance is below the quoted
cost, or the relative has already been released between the quote and the payment, nothing is
charged. Without payment, custody simply runs to the end of the sentence like any other arrest.

## `[jail]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `enableFines` | `true` | — | Whether `/crime payfine` works at all. |
| `enableBail` | `false` | — | Let a jailed player buy out the rest of a sentence. Off is the default and keeps a sentence something you serve rather than something you price. Bail settles the cases as `FINED` — a player released with every charge still open would walk out of the cell into the arms of the guard who put them there. |
| `bailCostPerMinute` | `4` | `0 … 100000` | Emeralds per remaining real minute. Priced on what is left, not the original sentence, and rounded up so a sliver of a minute is never free. |
| `bailMinServedFraction` | `0.25` | `0.0 … 1.0` | How much of the sentence must already be served before bail is offered. `0.0` lets a sentence be bought out the instant it starts. |
| `maxCaptivityRealMinutes` | `360` | `1 … 100000` | Hard ceiling, in real online minutes, on how long any player may be held — jailed or kidnapped. This is the softlock backstop: it force-releases regardless of anchor, dimension, or chunk state. |
| `jailContainmentMode` | `CONTAINMENT` | `CONTAINMENT`, `PHYSICAL`, `REINFORCED` | How jail resists escape. **Snapshotted at the moment of jailing**, so changing this mid-sentence cannot surprise a prisoner. One rule sits above the mode: once a generated cell's padlock has been picked, detached or unlocked, walking out of that cell is an escape in every mode, because the door was the containment and it has been defeated. |
| `maxJailCommandTicks` | `72000` | `1 … 100000000` | Upper clamp on a `/crime jail` sentence, in online ticks. 72000 is one online hour. |
| `jailRadiusDefault` | `8` | `1 … 64` | Default jail-region radius for `/crime assignjail` and the fallback. |
| `buildHoldingCell` | `true` | — | **This is the temporary-jail switch.** When no jail anchor is assigned and no fallback is configured, build a small holding cell near the arrest — reinforced stone and reinforced bars around a cell door that faces the arrest, held shut by a padlock the mod hangs itself — and take it down again on release, restoring every block it replaced. The padlock can be picked, from outside or through the door; a prisoner who then walks out has escaped and the cell comes down without a release (see `jailContainmentMode`). It gates the only code path in the mod that places a block, so turning it off means the mod builds nothing, anywhere, ever. Player-built and `/crime assignjail` jails keep working exactly as before. See below for what happens to an arrest with nowhere to go. |
| `holdingCellSearchRadius` | `24` | `4 … 96` | How far from the arrest to look for ground clear enough to build a cell on. A site is rejected outright if it contains any block entity, any fluid, or anything that is not air, replaceable foliage, or plain terrain. |
| `holdingCellLifetimeTicks` | `1728000` | `1200 … 100000000` | Hard ceiling on how long a built cell may stand (24000 = one Minecraft day). The leak guard: a player arrested and never seen again would otherwise leave a cage in a village for the life of the save. |
| `sentenceBaseTicks` | `1200` | `0 … 100000000` | Fixed part of a sentence, in online ticks (1200 = one online minute). |
| `sentenceTicksPerHeat` | `30` | `0 … 1000000` | Added sentence length per point of Heat at the time of arrest. Surrender reduces Heat first, so giving yourself up genuinely shortens the term. |
| `sentenceTicksPerCharge` | `200` | `0 … 1000000` | Added sentence length per outstanding charge being answered for. |
| `arrestEscortTimeoutTicks` | `600` | `0 … 24000` | How long a guard is given to walk an arrested player to the cell before the arrest completes by teleport instead. `0` skips the escort entirely. A pathfinding failure must never be a way to dodge a sentence. |
| `escortTetherBlocks` | `16.0` | `4.0 … 64.0` | **Hard** radius: how far an arrested player may get from their escort before the arrest is abandoned and they are marked resisting instead. Running from a surrender is a decision, so it gets a consequence rather than a teleport back. |
| `escortLeashBlocks` | `5.0` | `1.0 … 32.0` | **Soft** radius: past this, the prisoner is pulled back toward the guard, gently at first and harder as they approach the tether. Must stay below `escortTetherBlocks`, or the escort is abandoned before the lead ever engages — `/crime validate` flags this. |
| `escortSpeedPenalty` | `0.35` | `0.0 … 0.9` | How much of a restrained player's movement speed is taken away. `0.35` = they move at 65% of normal. Applied as an attribute modifier rather than a potion effect, so it is invisible, emits no particles, and cannot be drunk away with milk. |
| `escortWalkSpeed` | `0.9` | `0.1 … 2.0` | Relative walking pace. MCA navigation uses half the raw multiplier, with a cap on movement attribute times navigation speed. Guards wait for their prisoner before the lead gets taut. |
| `escortNavigationIntervalTicks` | `20` | `1 … 200` | How often the escort reissues its walk order. MCA villagers run their own brain, so an order reissued every tick fights it and the guard visibly stutters; the order is also refreshed whenever the previous path finishes. |
| `escortStuckScans` | `6` | `1 … 100` | Consecutive scans without meaningful guard or prisoner movement before intake completes by teleport. Detours count as progress even when they lead away from the jail temporarily. The overall escort deadline still applies. |
| `restrainedPlayerRestrictions` | `true` | — | While restrained, suppress attacking, interacting, breaking blocks, jumping, mounting and sprinting. Off keeps the escort and the visuals but lets a cuffed player act normally. |
| `arrestRecoveryTicks` | `200` | `20 … 24000` | How long, in online ticks, guards stand down after an arrest could not be completed. This is a state the arrest genuinely reached, not a cooldown on the screen: without it a guard that just failed re-opens the same confrontation on the next scan and fails again. |
| `jailAssignedMaxDistance` | `256.0` | `0.0 … 10000.0` | How far an operator-assigned jail may be from an arrest and still be used. `0` means unlimited, which is the historical behaviour and means a single `/crime assignjail` anywhere in a dimension captures every arrest in it and permanently suppresses cell-building. Beyond this distance the arrest builds or falls back locally instead. `/crime jail` is never distance-limited. |
| `jailFallbackEnabled` | `false` | — | Jail at the fallback position when no anchor is assigned. Off means jailing is **refused** with a clear message instead — which is the safer default, because a fallback pointing at a hole in the ground is worse than a refusal. |
| `jailFallbackPos` | `[0, 64, 0]` | — | Fallback position, used only when the fallback is enabled. |
| `jailFallbackDim` | `minecraft:overworld` | — | Dimension for the fallback position. |

**Containment modes.** `CONTAINMENT` protects the region's blocks and teleports strays back.
`PHYSICAL` lets the walls be broken and treats leaving as a genuine breakout: the escaped flag is
set, a `jailbreak` crime is recorded with its Heat, the prisoner is *not* teleported back, and the
sentence keeps running while they run. `REINFORCED` currently behaves as `CONTAINMENT`.

Sentences are counted in online ticks: logging out pauses, dying does not clear, changing dimension
does not stop it, and a restart resumes.

**Turning temporary jails off.** With `buildHoldingCell = false`, an arrest looks for an assigned
anchor within `jailAssignedMaxDistance`, then for a cell that already exists, then for the configured
fallback. If none of those resolves, the arrest is **refused cleanly**: the player is told there is
nowhere to hold them, the guards stand down for `arrestRecoveryTicks`, any restraint is removed, and
no custody is ever taken. A player can never be left cuffed, tethered, or mid-escort because there was
nowhere for the escort to go — that is the one outcome this subsystem is built to make unreachable.
Surrendering in that state still reduces Heat and still stands the guards down; it simply does not
end in a cell. Running `buildHoldingCell = false` together with `jailFallbackEnabled = false` and no
assigned jail means nobody is ever jailed at all, and `/crime validate` says so in as many words.

**The arrest itself.** Surrendering now puts the player in a single authoritative state that persists
across logout, death and restart. They are restrained and roped to the arresting guard, who walks them
to the jail; the sentence starts when they are actually inside the jail region, not when they
surrendered. If the guard dies or unloads, another responder nearby takes over; if there is nobody
left, or the path fails, or the escort runs out of time, the arrest finishes by teleport rather than
being cancelled. Breaking the tether deliberately is the one case that ends the arrest, and it is
treated as resisting. Because the state is one value rather than several, a guard cannot open a second
confrontation screen against somebody already being arrested — which is what the repeating menu was.

## `[fines]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `fineBase` | `8` | `0 … 1000000` | Flat emerald cost before the per-Heat term. |
| `finePerHeat` | `1` | `0 … 1000000` | Extra emeralds per point of Heat. |
| `jailableHeatThreshold` | `80` | `1 … 1000000` | At or above this Heat a fine is refused outright — the offender must serve or surrender. Must be at least `wantedHeatThreshold`; the validator checks this. |
| `blueFineMultiplier` | `0.5` | `0.0 … 10.0` | Fine multiplier for Lawful offenders. |
| `redCanPayFine` | `false` | — | Whether Outlaws may pay a fine at all. Off means they must `/crime surrender` first. |
| `maxCasesPerFinePayment` | `3` | `1 … 20` | How many separate cases one payment may settle when it is not clearing everything at once. Kept small so a single payment cannot quietly clear a long history. |

Payment is allocated **oldest case first**, so the ledger afterwards records which offences were
answered for. Charges are atomic: a failed charge takes nothing.

## `[bounty]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `baseBounty` | `8` | `0 … 1000000` | What a freshly Wanted outlaw is worth before their record is counted. |
| `minBounty` | `1` | `0 … 1000000` | Floor on the payout. A bounty worth nothing is a bounty nobody hunts. |
| `maxBounty` | `128` | `0 … 1000000` | Ceiling on the payout, so a career criminal is not a jackpot. |
| `severityRewardScale` | `2.0` | `0.0 … 100.0` | Multiplier on the Heat of the target's unresolved cases. |
| `fineRewardShare` | `0.25` | `0.0 … 1.0` | Fraction of the target's outstanding fines folded into the price. |
| `repeatOffenderBonus` | `4` | `0 … 100000` | Added per warrant already closed against the target. |
| `payForKills` | `true` | — | Whether killing a bounty-eligible outlaw pays. |
| `payForAliveCapture` | `true` | — | Whether taking a bounty-eligible outlaw alive pays. This is also the switch that makes restraining one a citizen's arrest rather than a kidnapping. |
| `killMultiplier` | `1.0` | `0.0 … 10.0` | Multiplier applied to a kill payout. |
| `aliveCaptureMultiplier` | `1.25` | `0.0 … 10.0` | Alive is worth more than dead by default, which is the reason the restraint and jail mechanics are worth a hunter's trouble. |
| `karmaReward` | `2` | `0 … 50` | Karma granted to the claimant. Bounty hunting is lawful work. |
| `redBandEligible` | `false` | — | Whether a Red-band player with no Wanted status can carry a bounty. Off by default: a reputation is not a warrant. |
| `claimRetentionDays` | `30` | `1 … 3650` | In-game days a paid claim is remembered before it is forgotten. |
| `deliveryRadius` | `4.0` | `1.0 … 16.0` | How close a hunter holding an outlaw must bring them to a guard for the arrest to count as a delivery. |

## `[surrender]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `surrenderNearRadius` | `8.0` | `1.0 … 64.0` | How close a guard, a jail, or a Lawful player must be to surrender to. |
| `surrenderHeatReduction` | `30` | `0 … 1000000` | Heat removed on surrender. Heat is also forced below the jailable threshold regardless of this value, so surrendering always makes a fine possible. |
| `surrenderSentenceReductionPct` | `25` | `0 … 100` | Percent of a remaining sentence waived. |

Surrender also clears the escaped flag, which is often the real reason to do it.

## `[entities]`

| Option | Default | What it does |
|---|---|---|
| `protectedEntities` | `[]` | Extra entity ids treated as protected victims, beyond MCA villagers. |
| `responderEntities` | `[]` | Extra entity ids treated as law responders, beyond MCA guards. |

Both accept plain entity ids (`minecraft:iron_golem`), `#tags`, and `*` wildcards. The validator
parses each entry and checks it against the entity registry on a best-effort basis, so a typo is
reported rather than silently ignored.

Both lists are **additive**: MCA's own villagers and guards are matched separately, so emptying these
cannot switch off the mod's core subject. `protectedEntities` is consulted by the crime gate, and
`responderEntities` by the observation service, the report flow, and guard enforcement — so an entity
added here genuinely witnesses crimes and receives reports.

## `[weaponTrigger]`

| Option | Default | What it does |
|---|---|---|
| `enabled` | `true` | Right-clicking an MCA villager while holding a weapon opens the Crime menu. |
| `requireSneak` | `false` | Also require sneaking before the menu opens. |
| `allowOffHand` | `true` | Let an off-hand weapon open the menu too. |
| `requireWeaponForCrimeMenu` | `true` | Require a drawn weapon for coercive menu actions. Peaceful options, including apologies, remain reachable unarmed. Individual action requirements still apply when this is off. |

The interacting hand's item is the one classified, and the main hand is dispatched first, so a
main-hand weapon opens the menu once rather than twice.

Restraints keep their claim on the interaction: capture runs first and a restraint is never
classified as a weapon, so cuffing somebody still cuffs them.

**Gifting caveat.** While this is on, right-click gifting a *weapon* to an MCA villager is
pre-empted by the Crime menu. Blacklist that item under `[weapons]`, or turn the trigger off.

## `[weapons]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `whitelist` | `[]` | — | Items always treated as weapons. |
| `blacklist` | `[]` | — | Items never treated as weapons. Always wins. |
| `autoDetect` | `true` | — | Classify unlisted items automatically. Off leaves only the two lists and the `mcacrime:weapons` tag. |
| `autoDetectMinAttackDamage` | `3.0` | `0.0 … 100.0` | Last-resort melee threshold, in bonus attack damage. A wooden sword grants 3. |
| `gunKeywords` | `gun, rifle, pistol, revolver, shotgun, musket, blunderbuss, smg, carbine, sniper, launcher, minigun` | — | Substrings in an item's registry path that mark it as a firearm. |
| `weaponMods` | `tacz, cgm, pointblank, scguns, mwc` | — | Namespaces whose non-stackable, non-block items are assumed to be firearms. Editable guesses, not a verified list. |
| `mugRequiresWeapon` | `true` | — | Mugging requires a weapon in one hand. Unarmed, the row stays visible and says so. |

Both lists take plain item ids (`minecraft:iron_sword`) or `#tags` (`#c:tools/melee_weapon`). Wildcards
are **not** accepted here, because nothing expands one. The validator parses every entry, checks
plain ids against the item registry, and warns when the same entry appears on both lists.

Classification is first-match-wins, in this order:

1. a restraint — never a weapon;
2. `blacklist`;
3. `whitelist`;
4. the `mcacrime:weapons_blacklist` item tag;
5. the `mcacrime:weapons` item tag (ships covering `#minecraft:swords`, `#minecraft:axes`, `#c:tools/melee_weapon`,
   `#c:tools/ranged_weapon`, `#c:tools/bow`, `#c:tools/crossbow`, and `#c:tools/spear`, so a datapack can add to it);
6. then, only if `autoDetect` is on: swords, axes and tridents; bows, crossbows and anything with a
   drawing use animation; a gun keyword in the item's path; a `weaponMods` namespace on a
   non-stackable, non-block item; digging tools other than axes, which are excluded; and finally the
   `autoDetectMinAttackDamage` threshold.

`/crime debug weapon` prints the held item's id, its class, the layer that decided it, and the
threshold in force.

**Config sync caveat.** This block is COMMON config, which NeoForge does not sync to clients. The client
runs the same rule to decide whether to swallow the right-click locally, so a client whose lists
differ from the server's will mispredict the swing. The server's answer still decides what happens.

## `[ransom]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `ransomBaseAmount` | `16` | `0 … 1000000` | Base emerald ransom before the per-tier multiplier. |
| `ransomSpouseMultiplier` | `2.0` | `0.0 … 100.0` | A spouse pays most. |
| `ransomParentMultiplier` | `1.5` | `0.0 … 100.0` | |
| `ransomChildMultiplier` | `1.5` | `0.0 … 100.0` | |
| `ransomSiblingMultiplier` | `1.2` | `0.0 … 100.0` | |
| `ransomRelativeMultiplier` | `1.0` | `0.0 … 100.0` | |
| `ransomVillageMultiplier` | `0.75` | `0.0 … 100.0` | The village-authority fallback, deliberately worth less than a family. |
| `enableVillageRansomFallback` | `true` | — | When no family payer can be reached, downgrade to a village settlement. Off means the demand is refused instead. |
| `enableCloseFriendTier` | `false` | — | MCA has no NPC-to-NPC friendship edge, so this tier is off and degrades to the village fallback. |
| `ransomDemandTtlTicks` | `12000` | `0 … 10000000` | How long an open demand stands before expiring. |
| `ransomCooldownPerVictimTicks` | `24000` | `0 … 10000000` | Anti-farm cooldown on one victim. |
| `ransomCooldownPerFamilyTicks` | `24000` | `0 … 10000000` | Anti-farm cooldown on one family. |
| `ransomCooldownPerVillageTicks` | `12000` | `0 … 10000000` | Anti-farm cooldown on one village. |
| `villageTreasuryInitialBalance` | `64` | `0 … 1000000` | Finite initial authority account; village ransom cannot overdraw it. |

The payer is resolved by strict priority — spouse, parent, adult child, sibling, close relative, then
the village — and family payers must be **reachable online players**. A demand can never be paid once
the captive has died, escaped, been rescued, or been jailed; it is re-validated about once a second
and flipped to the matching failure with a notification.

The validator rejects a ransom table that cannot produce a price: a zero TTL, or a non-zero base with
every tier multiplier at zero.

## `[loot]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `dropEquipment` | `true` | — | Drop an MCA villager's equipped gear, retaining names, damage and enchantments. Applies to guards and archers too. |
| `dropTradeStock` | `true` | — | Drop one purchase worth of output per unlocked, non-exhausted trade. Applies to fence goods too. |
| `maxTradeDropStacks` | `128` | `1 … 4096` | Maximum trade-output stacks per death, split at each item's actual stack limit. Excess is discarded; equipment is separate. |

These additions apply to deaths from any cause and honor `doMobLoot`. Equipped items with
Curse of Vanishing do not receive an extra drop. MCA continues to handle its carried inventory;
equipment backed by the same inventory item is not duplicated. These options control Crime's
additions, not MCA's existing inventory drops.

Each available trade drops its output count once: an iron-axe trade drops one axe, and a trade
for three bread drops three bread. Remaining uses never multiply the drop. Exhausted and still-locked
offers do not drop anything. Children have no trade stock. Fence buying orders are not physical stock,
and death does not restock or reroll a fence. New loot defaults also apply to existing configs;
the old `mugging.enableProfessionDeathDrops` key remains a separate, disabled legacy fallback.

## `[mugging]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `enableMugging` | `true` | — | Whether target-bound mug actions are available. |
| `muggingBaseLoot` | `4` | `0 … 1000000` | Maximum emeralds requested; actual payout is bounded by purse and daily caps. |
| `enableProfessionDeathDrops` | `false` | — | Legacy small profession drops for player kills; used only when `loot.dropTradeStock` is disabled. Actual equipment and trade stock use `[loot]`. |
| `muggingChannelTicks` | `60` | `1 … 6000` | Continuous threat time before resolution. |
| `muggingAttemptCooldownTicks` | `24000` | `0 … 10000000` | Same actor/victim attempt window, stamped at threat start. |
| `muggingVictimRecoveryTicks` | `12000` | `0 … 10000000` | Global recovery after a successful loss. |
| `muggingFearMemoryTicks` | `168000` | `0 … 100000000` | Duration of offender-specific fear memory. |
| `muggingPanicTicks` | `600` | `0 … 100000` | Short active-flee window for the direct victim; long-term fear remains memory/dialogue only. |
| `muggingActorSuccessCapPerDay` | `4` | `0 … 10000` | Profitable mug limit per player/day. |
| `muggingActorValueCapPerDay` | `12` | `0 … 1000000` | Stolen emerald limit per player/day. |
| `muggingVillageValueCapPerDay` | `24` | `0 … 1000000` | Stolen emerald limit per jurisdiction/day. |
| `muggingPurseCapacity` | `5` | `0 … 1000` | Finite purse capacity. |
| `muggingPurseInitialMax` | `3` | `0 … 1000` | Deterministic initial purse upper bound. |
| `muggingPurseDailyIncome` | `1` | `0 … 1000` | At most one lazy refill increment after dawn. |
| `allowHostileActionsAgainstChildren` | `false` | — | Whether hostile person actions may target children. |
| `allowGameplayCommandFallback` | `true` | — | Keeps gameplay commands as adapters to the same action engine. |

The victim's persisted purse is debited before the player is credited. Repetition cannot exceed the
finite source or actor/village windows; even an interrupted threat leaves its cooldown and memory.

## `[contraband]`

Items an operator has made illegal to carry, and how guards find them. **Off with an empty list by
default** — what counts as contraband is a pack's decision and not this mod's, and a list that
shipped with guesses in it would confiscate somebody's inventory on first launch.

| Option | Default | Range | What it does |
|---|---|---|---|
| `enableContraband` | `false` | — | Master switch. |
| `illegalItems` | `[]` | ids or `#tags` | The list itself. See worked examples below. |
| `contrabandHeat` | `-1` | `-1 … 100000` | Heat one contraband charge adds. `-1` uses the crime type's own value. |
| `contrabandKarma` | `-1` | `-1 … 100000` | Karma one contraband charge applies. `-1` uses the crime type's own value; a positive number here is still applied as the crime type would apply it. |
| `perItemStackHeat` | `false` | — | Charge once per distinct illegal item id found rather than once for the whole search. |
| `discoveryMode` | `BOTH` | `GUARD_PATROL`, `ARREST_ONLY`, `BOTH` | When a search may happen. |
| `searchRadius` | `4.0` | `1 … 16` | How close a guard must be to search a player. |
| `searchIntervalTicks` | `40` | `10 … 1200` | Server ticks between patrol search passes. Never per tick per player. |
| `searchLosTicksRequired` | `40` | `0 … 1200` | Ticks of unbroken line of sight a guard must accumulate before it searches, added in `searchIntervalTicks`-sized steps each pass. `0` means a glance is enough; at the defaults, one pass with line of sight is already enough. |
| `searchChance` | `0.15` | `0.0 … 1.0` | Chance a qualifying guard actually searches on a pass. |
| `searchRequiresSuspicion` | `true` | — | Only search players the guards already have a reason to stop. Off searches anybody. |
| `includeEquipped` | `true` | — | Worn armour is searched. |
| `includeOffhand` | `true` | — | The offhand slot is searched. |
| `searchNestedContainers` | `true` | — | Shulker boxes and bundles are opened, one level deep, hard-capped in code at that depth. |
| `rechargeTicks` | `24000` | `0 … 1728000` | How long before the same unchanged haul may be charged again. An in-game day by default, so carrying the same illegal item past ten guards is one crime. |
| `confiscateOnDiscovery` | `false` | — | A guard takes what it finds. **Destructive**: see below. |

**Entries and worked examples.** Each entry in `illegalItems` is either `namespace:path` for a
single item or `#namespace:path` for an item tag. A vanilla example: `minecraft:tnt`. A modded
example: `create:schematic` — if that mod is not installed, the id is not registered, so it simply
never matches anything a player can carry, and `/crime validate` reports it as an unregistered item
rather than failing the load. The tag example this mod ships is `#mcacrime:illicit_goods`
(`data/mcacrime/tags/item/illicit_goods.json` — 1.21's item tags live under the singular `item`
folder), which lists `minecraft:tnt`, `minecraft:fire_charge`, `minecraft:gunpowder`,
`minecraft:wither_skeleton_skull`, `minecraft:spyglass`, `minecraft:tripwire_hook`,
`minecraft:ender_pearl`, `minecraft:golden_apple`, `minecraft:name_tag`, and this mod's own
restraint items — a starting point for a pack, not a default: `illegalItems` itself ships empty and
this tag is not added to it automatically.

**Tags are validated late, on purpose.** Item tags do not exist while the config loads, so a
`#tag` entry cannot be checked at config-load time at all. `ContrabandPolicy` provisionally accepts
it, then checks once tags actually bind — on NeoForge's `TagsUpdatedEvent`, by asking
`BuiltInRegistries.ITEM.getTag(...)` — and an entry naming a tag nobody registered is dropped from
the active list at that point, with one log line naming it, and the rest of the list keeps working.
Nothing about a malformed or unknown entry is ever fatal.

**Scanning.** `includeEquipped` and `includeOffhand` add worn armour and the offhand slot to the
main inventory scan. With `searchNestedContainers` on, a shulker box or bundle in the scanned slots
is opened and its contents checked too — but only **one level deep**; that depth is hard-capped in
code (`ContrabandInventoryScanner.MAX_NESTED_DEPTH = 1`), so a shulker inside a shulker is not
opened further. In 1.21 both container shapes are read from data components rather than NBT: a
shulker box's `DataComponents.CONTAINER` and a bundle's `DataComponents.BUNDLE_CONTENTS`.

**Discovery.** Mere possession never charges anybody by itself — only a guard search does, and a
search itself is deliberately hard to trigger. On a `GUARD_PATROL`/`BOTH` pass, all of the following
must hold: the feature is enabled; the mode allows patrol searches; a guard is within
`searchRadius`; that guard has accumulated `searchLosTicksRequired` ticks of unbroken line of
sight, added in `searchIntervalTicks`-sized steps each pass (so at the defaults of 40/40, a
single pass with line of sight already satisfies it); `searchRequiresSuspicion` is off or the
player is already wanted/suspicious; and a
`searchChance` roll succeeds. `ARREST_ONLY` mode skips patrol searches entirely and searches only
from the arrest hook — and there, a search always runs, with no line-of-sight requirement and no
chance roll, because somebody being arrested is already in a guard's hands. One guard searches at
most one candidate per pass.

**Charging and dedupe.** A find is charged as `mcacrime:possess_contraband`, once per haul unless
`perItemStackHeat` is on (then once per distinct item id). The same unchanged haul — an
order-independent fingerprint over the listed items' ids and counts, stored per player on the
`mcacrime:player_crime` data attachment — is not charged again within `rechargeTicks` of the last
charge; a haul that changes (a different item, a different count) is charged immediately regardless
of the recharge window.

**Confiscation.** `confiscateOnDiscovery` is off by default. When on, every listed **top-level**
stack (main inventory, and armour/offhand if those toggles are on) is removed outright; nested
items inside a shulker or bundle still produce the charge but are not taken, because emptying one
would mean rewriting its data components. There is no recovery ledger for a confiscated item — it
is gone. Feedback never announces what a guard did not find: the `searched` message is sent only
when a search ran and found nothing, and the `found`/`confiscated` messages name the item.

## `[relationship]`

MCA hearts moved when a crime or a good deed lands. All of these are counts of hearts.

| Option | Default | Range | What it does |
|---|---|---|---|
| `directVictimHeartLoss` | `2` | `0 … 1000` | The victim's opinion of the offender. |
| `familyHeartLoss` | `1` | `0 … 1000` | Their family's. |
| `witnessTrustLoss` | `1` | `0 … 1000` | Every villager who watched. |
| `villageRepDrop` | `2` | `0 … 1000` | The village standing penalty. |
| `rescueHeartGain` | `3` | `0 … 1000` | A rescued captive's opinion of their rescuer. |
| `familyHeartGain` | `2` | `0 … 1000` | Their family's. |
| `villageRepRise` | `2` | `0 … 1000` | The village standing reward. |
| `restitutionHeartGain` | `2` | `0 … 1000` | Hearts returned to a victim when a fine is paid. |
| `restitutionFractionOfFine` | `0.5` | `0.0 … 1.0` | Fraction of a paid fine treated as restitution to the victim. |

Every one of these goes through the MCA adapter and fails safe: a differing MCA version means the
hearts change does not happen, not that anything crashes.

### `[relationship.familyLoyalty]`

Family who look the other way. A relative who declines to report is removed from the crime's
witness set itself (`WitnessLoyaltyFilter`), so the crime is un-witnessed for Heat, community
standing and family heart loss alike — they still remember it and can talk about it. On by default.

| Option | Default | Range | What it does |
|---|---|---|---|
| `enableFamilyLoyalty` | `true` | — | Master switch. |
| `familyLoyaltyScope` | `["SPOUSE","PARENT","CHILD","SIBLING"]` | `SPOUSE, PARENT, CHILD, SIBLING, EXTENDED, IN_LAW` | Which relatives may decline to report. |
| `familyLoyaltyGenerations` | `1` | `1 … 3` | How many generations out `EXTENDED` reaches. |
| `loyaltyHeartsWeight` | `1.0` | `0.0 … 10` | Loyalty score added per MCA relationship heart with the offender. |
| `loyaltyThreshold` | `50` | `0 … 1000` | Score at or above which a relative stays quiet. |
| `loyaltyTierBonusSpouse` | `25` | `-500 … 500` | Score bonus for a spouse. |
| `loyaltyTierBonusImmediate` | `10` | `-500 … 500` | Applied to a parent, child or sibling. |
| `loyaltyTierBonusExtended` | `0` | `-500 … 500` | Applied to an extended relative or an in-law. |
| `loyalPersonalities` | `[]` | MCA personality names | Personalities that add `personalityLoyaltyBonus`. |
| `lawfulPersonalities` | `[]` | MCA personality names | Personalities that subtract `personalityLoyaltyPenalty`. A personality named in both lists cancels out and does nothing; `/crime validate` says so. |
| `personalityLoyaltyBonus` | `20` | `0 … 500` | |
| `personalityLoyaltyPenalty` | `20` | `0 … 500` | |
| `loyaltyMaxCrimeHeat` | `60` | `0 … 100000` | Family never cover for an offender whose Heat is above this. |
| `notifyOnLoyalWitness` | `true` | — | Tell the offender that a relative saw it and said nothing. |

**The decision (`FamilyLoyalty.evaluate`).** Deterministic, no RNG: two identical crimes minutes
apart produce the same answer. The score is `loyaltyHeartsWeight × hearts + tierBonus +
personalityModifier`, and a relative stays quiet when that score is at or above `loyaltyThreshold`
**and** none of six hard exclusions apply — an exclusion always wins, however high the score:

1. the witness is the victim;
2. the victim is the witness's own relative;
3. the witness is a guard or archer (a responder);
4. the witness is not an adult;
5. their family tier is not in `familyLoyaltyScope`;
6. the offender's Heat at the time exceeds `loyaltyMaxCrimeHeat`.

MCA's own `Mood` is deliberately not an input: mood is transient, so a mood-weighted decision would
flip between two identical crimes minutes apart, which would read as a bug rather than as a family
choosing sides.

**What loyal witnesses become.** A loyal relative is removed from the crime's witness set before
Heat, community standing and family heart loss are computed from it, so a crime seen only by loyal
family produces none of those. It is not forgotten, though: a parallel observation is recorded for
each loyal relative with report state `WITHHELD` — distinct from intimidation's `SUPPRESSED` — which
is never filed and never relayed by gossip, but remains available so dialogue can reference it.

## `[messages]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `ambientMessagesEnabled` | `true` | — | On-screen messages for band changes, being witnessed, and guard pursuit. |
| `ambientMessageThrottleTicks` | `100` | `0 … 100000` | Minimum ticks between repeats of the same kind of message to one player. Band transitions are never throttled — they are the one thing you must not miss. |
| `chatNameColorEnabled` | `false` | — | Colour player names in chat by band. This is now the only switch: the client-side `chatFormatToggle` that used to shadow it was removed in 0.4.0, because a client cannot opt out of text the server has already formatted, and a setting that reads as a choice but is not is worse than no setting at all. |
| `chatNameColorMode` | `FULL` | `FULL`, `PREFIX_ONLY` | Signed chat only exposes the message body to the server, so `FULL` adds a coloured prefix marker rather than recolouring the sender's name. `PREFIX_ONLY` adds a plainer marker. |

## `[matching]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `professionMatchingMode` | `NORMALIZED` | `STRICT`, `NORMALIZED`, `LOOSE` | How a villager's profession is compared against a name such as `guard`. `STRICT` compares the whole id, so only that exact namespace matches. `NORMALIZED` ignores the namespace, so `mca:guard` and `somemod:guard` both count — the historical behaviour, and the default. `LOOSE` matches a substring, catching `guard_captain` and `village_guard` at the cost of catching anything else containing the word. |

The mode is consulted wherever a profession is matched: the guard check, law-responder resolution,
and profession death drops.

## `[debug]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `strictJsonValidation` | `false` | — | Treat any malformed or unknown crime JSON as a hard error rather than falling back to the built-in definitions. Useful while authoring a datapack; risky on a live server. |
| `debugLogging` | `false` | — | Verbose DEBUG for MCA access failures, witness selection, custody transitions, and delivery outcomes. Never one line per tick. |

## `[mask]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `maskEnabled` | `true` | — | Master switch for masks. Off leaves them as ordinary wearables with no legal effect. |
| `maskSuppressesHeat` | `true` | — | Crimes committed while masked apply 0 Heat. Karma still applies immediately: what a player did is not in question, only whether anybody can say who did it. |
| `maskDefersHeat` | `true` | — | Suppressed Heat is parked against the player and lands all at once if a witness sees the mask come off, or at jail intake. Off voids it instead. |
| `maskHidesIdentityFromWitnesses` | `true` | — | Masked crimes write no identity-attributed villager observation or victim memory, and no responder files one on the spot. Only that attribution is hidden: the victim and any bystanders still react in real time. |
| `maskedPursuitTicks` | `1200` | `0 … 432000` | How long a responder who witnessed a masked crime keeps hunting the figure they saw, in online ticks. `0` disables masked pursuit entirely. |
| `maskedOffenderLethalForce` | `false` | — | Whether masked pursuit on its own authorises lethal force, or only subdual. |
| `maskSuppressesRedBandTargeting` | `false` | — | While masked, the Red-band legal-target term does not apply. Being Wanted is never suppressed this way. |
| `guardsChallengeMaskWearers` | `false` | — | Guards challenge anyone they see wearing a mask, with no crime behind it. A challenge only: the guard walks over and asks, and it never authorises force on its own. |
| `maskedHeatExpiryTicks` | `0` | `0 … 1000000000` | Deferred Heat older than this many online ticks is dropped. `0` means it never lapses. |
| `maskRemovalWitnessRadius` | `12.0` | `0.0 … 64.0` | Block radius of the witness scan run when a mask comes off. `0` means an unmask is never seen, which leaves deferred Heat parked indefinitely. |
| `maskDurabilityEnabled` | `true` | — | Off makes masks take no damage from wear. |

## `[maskStation]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `enableMaskStationCrafting` | `true` | — | Master switch for station crafting. Off closes any open station menu at the next tick and hands the inputs back; the block, its recipes and its worksite claim are untouched. |
| `enableMaskRestyling` | `true` | — | Whether a mask can be remade as another style of the same family. Off hides every restyle recipe from the catalogue; ordinary crafting is unaffected. |

## `[sandBottle]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `enableSandBottles` | `true` | — | Master switch. Off stops new throws immediately; a bottle already in flight stops applying anything and discards itself, ammunition already spent is not refunded, and an effect already running finishes normally. |
| `sandCooldownTicks` | `80` | `0 … 1200` | Ticks between throws, shared across every Sand Bottle stack and both hands. |
| `sandDirectDurationTicks` | `80` | `1 … 200` | How long a directly struck target is blinded, in ticks. |
| `sandSplashDurationTicks` | `40` | `0 … 200` | The most a splash victim at the centre of the burst is blinded for, falling off linearly to nothing at the rim. Should not exceed `sandDirectDurationTicks`. |
| `sandRadius` | `2.0` | `0.0 … 4.0` | Splash radius in blocks. `0` leaves only the directly struck target. |
| `sandRecoveryTicks` | `60` | `1 … 1200` | After the effect ends or is cured, how long sand cannot take hold on that target again — from any thrower, which is what stops two players alternating bottles. |
| `sandAffectsPlayers` | `false` | — | Whether sand can blind other players. The server's own PvP setting and team friendly-fire rules are still obeyed when this is on; catching yourself in your own splash is never PvP and always possible. |

## `[integrations]`

Every setting here is a no-op when the companion mod is absent.

| Option | Default | Range | What it does |
|---|---|---|---|
| `enableReputation` | `true` | — | Record community standing through MCA: Reputation when it is installed, instead of the built-in per-village store. **With this off, MCA: Reputation keeps detecting villager assault and killing itself** — there is never a state where both record the same deed, or neither does. |
| `mirrorReputationFallback` | `true` | — | After Reputation commits a standing change, copy the resulting score into the built-in store. Costs nothing, and means uninstalling Reputation later does not reset every player to a stranger. |
| `suppressLocalVillagePenalty` | `true` | — | Skip the built-in village penalty for crimes Reputation is recording canonically. **Turning this off applies both, which double-counts every witnessed crime.** |
| `locksReforgedFenceTrades` | `true` | — | Let fences stock Locks Reforged locks, picks and keys when that mod is installed. A no-op without it: nothing here names a Locks class, and the goods are looked up by registry id. Since 0.7.5 this is the whole of the Locks Reforged integration — lockpicking is native and no longer routes through that mod. |
| `replayPendingOperations` | `true` | — | Retry cross-mod writes that were queued but not delivered — after a crash, or while a companion was uninstalled. Off strands pending work indefinitely. |
| `pumpIntervalTicks` | `100` | `20 … 12000` | How often the delivery queue is checked. |
| `pumpBudgetPerTick` | `8` | `1 … 128` | How many queued writes may be delivered in one pass. |
| `maxDeliveryAttempts` | `6` | `1 … 20` | Attempts before a write is set aside as a dead letter for `/crime debug outbox dead`. |
| `retryBaseDelayTicks` | `200` | `20 … 24000` | First retry delay; doubles on each failure. Must not exceed the maximum. |
| `retryMaxDelayTicks` | `24000` | `20 … 1728000` | Ceiling on the retry delay. |
| `dedupeRetentionTicks` | `168000` | `1200 … 1728000` | How long a completed transaction is remembered so a replay of it changes nothing. Long-lived case-to-incident links live on the record itself and never expire; this only covers the replay window for one-off mutations. |
| `currencyId` | `mcacrime:emerald` | string | Which registered currency fines, bail, ransom, theft and bounties are paid in. Built in: `mcacrime:emerald`, `mcacrime:item` (the item named by `currencyItem`, one item to one unit) and `mcacrime:numismatic` (the Numismatic Overhaul purse, in bronze, offered only when that mod is installed). Economy mods register their own ids through `McaCrimeApi.registerCurrency`; an unregistered id falls back to emeralds with one warning rather than taking the economy offline. |
| `currencyItem` | `minecraft:emerald` | string | The item `mcacrime:item` pays in, as a registry id. Only read when `currencyId` is `mcacrime:item`; ignored otherwise. An unknown or absent item falls back to emeralds with one warning. |
| `mcaQuestsBounties` | `true` | — | Publish open bounties as MCA: Quests contracts when that mod is installed. A bounty is paid once whichever route claims it. |

A companion that is not installed yet is a *delay*, and the write is retried. A payload the companion
actively rejects is *not* retried, because a thousand retries would bury the one log line an operator
needs.

### `[integrations.reputation]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `fineResolutionStatus` | `atoned` | `atoned`, `apologized` | How a paid fine reads to the village: made good, or merely said sorry. |
| `servedResolutionStatus` | `atoned` | `atoned`, `apologized` | The same for a served sentence. |
| `supersedeWindowTicks` | `1200` | `0 … 24000` | How far back a killing may absorb the assault that preceded it, so one encounter reads to the village as one incident. `0` records the two separately, which charges the player for both. Ignored when the installed MCA: Reputation does not advertise supersession. |

Only `atoned` and `apologized` are accepted for the two statuses; anything else is a validation
error. Unresolved, escaped, and expired cases deliberately map to **no** resolution status at all —
breaking out of jail is not atonement, and a case ageing out is not the village forgiving a murder.

`supersedeWindowTicks` exists because this mod claims villager assault and killing from MCA:
Reputation's own detector, which folds an assault into the killing that finished it. Without the
fold a player who beat a villager and then killed them would pay for both — a heavier penalty than
the mod the deed was taken from would have applied. The fold only ever applies within one village,
against the same victim, and while the earlier case is still unsettled: a fine already paid for the
assault is an atonement the village accepted, and absorbing that record would quietly delete it.

## `[townstead]`

Every setting here is a no-op when Townstead is absent, and MCA: Crime behaves exactly as it does
without it. A setting that is **on while the Townstead surface behind it is missing** is reported as
`DEGRADED` — on, but not running — by `/crime validate` and `/crime debug townstead`; it is never
silently ignored. `/crime debug townstead [entity <target>|village]` prints the bridge state, the
capability list and the mixin layer.

| Option | Default | Range | What it does |
|---|---|---|---|
| `enabled` | `true` | — | Master switch for the whole integration. Off means MCA: Crime never asks Townstead anything, even when it is installed. |
| `respectIncapacity` | `true` | — | Treat a villager Townstead has collapsed, or whose life stage cannot move, as unable to witness, report, flee or be escorted. Needs `read_needs` + `stage_capabilities`. |
| `protectWorkerAssignments` | `true` | — | Prefer villagers who are not on a Townstead work shift when drafting guards or responders. This can leave a village under its guard target while everyone is at work — the intended trade. A village whose roles cannot be read stops being recruited from at all, rather than being guessed at. Needs `read_schedule` + `read_profession`. |
| `excludeWorksitesFromTemporaryCells` | `true` | — | Refuse to build a temporary holding cell inside a registered Townstead building. Needs `read_building`. |
| `equipmentProvenance` | `true` | — | Ask where a villager's held item came from before treating it as their own, so a display tool is not dropped as extra loot or counted as an armed villager. Answered by a hook into Townstead's own work-tool ticker; without that hook this reports degraded and the fallback is the equipment-only rule, which never deletes inventory. Needs `equipment_provenance`. |
| `publicReactions` | `true` | — | Let Townstead play its own villager reactions when a crime, an arrest or a release becomes public knowledge. Needs `dispatch_reaction`. |
| `needResponseModifiers` | `false` | — | Let hunger, thirst and fatigue nudge how strongly a villager reacts to a crime. Off by default: it changes detection and threat numbers a server owner has already tuned. Needs `read_needs`. |
| `propertyLaw` | `false` | — | Treat marked containers and buildings as property, so taking from one can be a crime with an owner. Ownership is never inferred: a policy exists only because an operator, a datapack or the bounded automatic protection below wrote one, and a container nothing claims answers `UNKNOWN`, which never charges anyone. A taking is attributed only when the container lost exactly what one actor gained in the same tick, in one of four vanilla storage menus (chest, shulker box, hopper, dispenser); anything else is reported as unattributed or unsupported. Also reserves those containers from settlement auto-sourcing. Needs `storage_policy` — provided by MCA: Crime's own `StoragePolicyMixin` since 0.7.4 — and `read_building`. |
| `autoProtectGeneratedProperty` | `false` | — | Derive property policies without an operator marking each container. Bounded on purpose: it covers only assigned civic facilities whose role is `evidence_storage` or `jail_cell`, plus the recognised building at those anchors. It never walks the world for chests and never overwrites a policy somebody wrote by hand. Only meaningful with `propertyLaw` on — `/crime validate` reports it when it is on while `propertyLaw` is off, because nothing evaluates a policy until then. Needs `building_enumeration`. |
| `serviceRestrictions` | `false` | — | Let a settlement refuse services to an outlaw — trade, luxuries, the fence's back room. Four rules, in order: an essential service (food, shelter, care) is never refused; a villager who personally remembers being harmed may refuse, and that refusal fades as the memory decays rather than being cached; an open case this settlement knows about refuses a non-essential service, but only when the subject is wanted, or the service is a luxury and their band is Outlaw — and a fence is exempt from the public rule entirely; standing alone never refuses anything. Surrender, restitution and settling a case are never routed through the decision, so there is always a route back. Needs `read_profession` + `read_schedule`. |
| `communityService` | `false` | — | Offer civic work instead of paying a fine, for one eligible case at a time: help take a wanted outlaw alive, return what was taken (needs `propertyLaw`, which is what writes the receipt), or apologise to the residents who remember. The fine is never discounted up front — completion settles the named case through the ordinary fine path, and a failed or cancelled contract leaves the original sentence untouched. `/crime validate` reports this being on while `jail.enableFines` is off, since there would be nothing for the work to replace. Needs `activity_coordination` + `read_schedule` (a claim on an NPC offender, and their schedule so nobody is held to civic work through the night). A villager on a contract is stopped from *starting* a work shift and is never interrupted mid-task. |
| `economyProfiles` | `false` | — | Let a village's Townstead character shape fines, bounties, fence prices and villager purse refills. Three fixed profiles (frontier, town, prosperous), every multiplier clamped to `0.5 … 2`, and the refill multiplier may only ever select an amount at or below the configured one, so no village can mint currency. The profile is re-read rather than persisted, behind the ordinary `snapshotCacheTicks` cache. A settlement whose character cannot be read is priced as an ordinary town, which `/crime validate` reports as degraded rather than off. Needs `read_spirit`. |
| `automaticShiftAssignment` | `false` | — | Assign guard shifts through Townstead's own scheduler. Needs `activity_coordination` + `read_schedule`. |
| `snapshotCacheTicks` | `20` | `1 … 200` | How long a Townstead villager snapshot is reused before it is read again. Snapshots are never persisted; this only bounds how stale one may be inside a tick loop. |
| `activityLeaseTicks` | `40` | `10 … 400` | How long MCA: Crime holds a villager for one enforcement action before the claim lapses. The default is four guard scans. |
| `facilitySearchRadius` | `96` | `16 … 512` | How far an automatic arrest may look for an assigned civic facility — a jail cell, a care room — before falling back to the ordinary jail ladder. |

Two `[townstead]` checks are about one switch contradicting another. `autoProtectGeneratedProperty` on while `propertyLaw` is off is reported, because automatic protection only writes policies and nothing evaluates one until property law is on — an operator would otherwise reasonably believe their stores were protected. `communityService` on while `jail.enableFines` is off is reported, because civic work is only ever offered where a fine could have been paid, so no contract would be issued.

Two more checks are about ordering rather than range:
`activityLeaseTicks` shorter than `snapshotCacheTicks` is reported, because an enforcement claim
could then expire while the snapshot it was made from is still being reused; `facilitySearchRadius`
smaller than `jail.holdingCellSearchRadius` is reported, because an arrest would then dig a
temporary cell closer than an assigned facility it had refused to consider. With Townstead installed
but `enabled` false, any `[townstead]` switch still left on is reported too — none of them does
anything until `enabled` is true. With Townstead **absent**, no capability is ever reported as
missing; the two ordering checks above are pure number checks and still apply.

## The physical restraint system (0.7.5)

Everything from here to `[sentencing.capitalPunishment]` is new in 0.7.5, when MCA: Crime absorbed
the Cuffed feature set as native code. Two rules run through all of it:

- **Physical is not legal.** These groups decide what is on somebody's body, what they are tied to
  and which device holds them. They never decide who is in custody, under what sentence, for which
  case — that stays in `[jail]`, `[kidnapping]`, `[ransom]` and `[enforcement]`. Removing a restraint
  is not a release; opening a cell door is not a pardon.
- **The server owns every outcome.** No durability delta, lockpick result or search transfer is ever
  taken from a client packet.

## `[restraints]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `preset` | `CUFFED_PARITY` | `CUFFED_PARITY`, `BALANCED_VILLAGE` | Which tuning this server wants for the physical restraint system. See *The two presets* below. A preset is never imposed silently. |
| `appliedPreset` | `CUFFED_PARITY` | string | The preset this config was last written from. Managed automatically, so a preset is applied once rather than overwriting an operator's own edits at every load. |
| `maxConcurrentSessions` | `64` | `1 … 4096` | How many restraint, lockpicking and frisking sessions run at once. At the cap a new session is refused, never an existing one evicted — evicting would make a busy server a way to interrupt other players. |
| `sessionTimeoutTicks` | `200` | `20 … 24000` | How long a session may go without input before the server drops it. Sessions are never saved. |

### The two presets

`CUFFED_PARITY` is the default and is the shipped behaviour: immediate application, independent
head/arms/legs slots, durability carried by the worn instance rather than the source item, keys,
native lockpicking, chains, fixed anchors, furniture and every item functioning, with low-health
gating **off**.

`BALANCED_VILLAGE` is the opt-in alternative: it adds an application duration, vulnerability
requirements before a subject can be restrained, stricter permissions for capturing players, and
non-destructive lock picking.

**One deliberate exception.** `CUFFED_PARITY` holds `lockpicking.destructiveOutcome` = `true`, while
the shipped default for that key is `false`. The shipped defaults are therefore this preset apart
from that one key: destroying a player's safe is not an outcome anybody should arrive at by
accident, so it stays off until an operator asks for parity. Selecting `CUFFED_PARITY`
explicitly — that is, switching back to it from `BALANCED_VILLAGE` — turns destructive picking on,
which is what asking for parity means.

A preset is never applied silently. Nothing is rewritten while `preset` matches `appliedPreset`;
changing `preset` rewrites exactly the keys that preset owns, once, and names every change in the
log with its old and new value. Editing one of those keys afterwards is respected — the preset is
not re-applied until `preset` changes again.

A config file whose `appliedPreset` marker is **blank** — a fresh file, or one an operator has
hand-blanked — is left alone when the requested preset is the shipped default: the shipped defaults
already *are* `CUFFED_PARITY` apart from `destructiveOutcome`, so auto-applying the default preset
over a blank marker would move that key behind the operator's back. An explicitly chosen
non-default preset still applies over a blank marker, because selecting it is the act.

## `[restraints.definitions]`

Durability is **struggle work the worn restraint withstands, in accepted struggle inputs**, not item
damage: it lives on the worn instance, so two prisoners never share one counter.

| Option | Default | Range | What it does |
|---|---|---|---|
| `durabilityHandcuffs` | `40` | `1 … 4096` | Work a pair of handcuffs withstands. |
| `durabilityShackles` | `15` | `1 … 4096` | Work shackles withstand. |
| `durabilityDuckTapeArms` | `5` | `1 … 4096` | Work arm tape withstands. |
| `durabilityDuckTapeLegs` | `5` | `1 … 4096` | Work leg tape withstands; configured independently of the arm value. |
| `durabilityDuckTapeHead` | `5` | `1 … 4096` | Work head tape withstands. |
| `durabilityBundleHood` | `5` | `1 … 4096` | Work a bundle hood withstands. |
| `headTapeMufflesTextChat` | `false` | — | Whether a head restraint also silences typed chat. Off by default: a gag is about voice, and taking away a player's ability to say "let me out" is a moderation problem, not a mechanic. |

## `[restraints.application]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `allowSelfApplication` | `true` | — | Whether a subject may restrain themselves. A self-applied restraint is never a kidnapping and files no case. |
| `channelTicks` | `0` | `0 … 6000` | How long applying a restraint to somebody else takes. `0` is instant, the shipped parity behaviour; `BALANCED_VILLAGE` sets `60`. |
| `maxRangeBlocks` | `4.0` | `0.5 … 64.0` | How far away a subject may be when a restraint is applied. |
| `requireLineOfSight` | `true` | — | Whether application needs line of sight to the subject. |
| `lowHealthFraction` | `0.35` | `0.0 … 1.0` | What counts as "low health" for the `low_health` gate, as a fraction of maximum health. Only consulted when that gate is listed below. |
| `vulnerabilityGates` | *(empty)* | list of `low_health`, `sleeping`, `unconscious`, `already_restrained`, `surrendered`, `detained` | Conditions a subject must meet before a restraint can be applied. Empty is parity: nothing is gated. |

## `[restraints.escape]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `minWorkIntervalTicks` | `4` | `1 … 200` | Minimum ticks between two accepted struggle inputs — the floor a hold-to-struggle accessibility binding hits, so an auto-clicker gains nothing over a human. |
| `maxInputsPerSecond` | `5` | `1 … 100` | Per-player ceiling on struggle packets considered in one second, counting refused ones, so a spamming client costs a bounded amount of work. |
| `returnsWornItem` | `true` | — | Whether removing a restraint gives its item back. System-issued gear returns nothing whatever this says. |
| `dropItemWhenBroken` | `false` | — | Whether a restraint struggled to pieces still drops its item. Off, so struggling free is not the cheapest way to keep a spare pair. |

## `[transport]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `maxChainLength` | `5.0` | `1.0 … 64.0` | How far a tethered subject may get before the tether pulls them back. |
| `overextensionLength` | `12.0` | `1.0 … 128.0` | How far past the anchor a subject may be dragged before suspension damage starts. Must be greater than `maxChainLength`; `/crime validate` refuses any other ordering. |
| `guardTransportHarmless` | `true` | — | Whether a lawful escort's tether refuses to injure the prisoner it is walking. |
| `suspensionDamagePerTick` | `2.0` | `0.0 … 20.0` | Damage per tick past `overextensionLength`, through `mcacrime:hang`. Attributed to whoever holds the other end, never to the victim. |
| `anchorOnlyWhenRestrained` | `false` | — | Whether a chain may only be attached to an already restrained subject. |
| `allowFenceAnchors` | `true` | — | Whether a chain may be tied to a fence. |
| `allowTripwireHookAnchors` | `true` | — | Whether a chain may be tied to a tripwire hook. |
| `maxTethersPerHolder` | `8` | `1 … 64` | How many subjects one holder may lead at once. |
| `forcedMountingEnabled` | `true` | — | Whether a holder may put the subject they are leading into a seat, boat, mount or bunk. Off leaves every mount voluntary. |

## `[detention]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `pilloryBreakoutTransitions` | `100` | `0 … 10000` | How many crouch transitions break a pillory open from the inside. `0` disables breaking out; stale-occupancy cleanup runs either way. |
| `guillotineEnabled` | `true` | — | Whether the guillotine may carry out an authorised execution at all. Off leaves the block placeable and usable as a restraint device that never takes a life. |
| `guillotineDropsHead` | `true` | — | Whether an executed player's head drops at the device. A non-player victim never fabricates a player head. |
| `guillotineActivationDelayTicks` | `5` | `1 … 200` | Ticks between the blade being released and the blow landing. Persisted, so an unload inside the window neither cancels nor repeats it. |
| `bunkSetsRespawn` | `true` | — | Whether sleeping in a bunk sets the sleeper's respawn point. The previous point is restored on release only while this system still owns the override. |

## `[locks]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `maxKeysPerRing` | `16` | `1 … 64` | How many keys one key ring holds. Lowering this never erases keys from a fuller ring; it refuses further additions until the ring is back within the limit. |
| `foreignLockPolicy` | `REFUSE` | `REFUSE`, `IGNORE` | What to do when another lock mod already owns the target. `REFUSE` declines to be the second lock on one block — two independent access checks on one chest is how a player gets locked out of their own container. |
| `lockAutomationPolicy` | `BLOCK_ALL` | `BLOCK_ALL`, `ALLOW_INSERT`, `ALLOW_ALL` | What automation may do to a locked container. Checked on every operation, so a handler fetched while the safe was open stops working the moment it is locked. |
| `protectLockedBlocksFromBreaking` | `true` | — | Whether a locked block resists being broken by hand. |
| `protectLockedBlocksFromExplosions` | `true` | — | Whether a locked block survives explosions. |
| `protectLockedBlocksFromPistons` | `true` | — | Whether a locked block refuses to be pushed or pulled. |
| `allowPadlockReinforcement` | `true` | — | Whether a padlock can be reinforced with an item from `mcacrime:can_reinforce_padlock`. Reinforcement only makes a lock harder to pick; it is never impossible. |

## `[lockpicking]`

Native, server-owned, and needs neither Cuffed nor Locks Reforged installed.

| Option | Default | Range | What it does |
|---|---|---|---|
| `enabled` | `true` | — | Master switch. Off leaves keys as the only way through a lock. |
| `drainPerTickDivisor` | `200` | `20 … 4000` | The divisor in the per-tick meter drain. Larger is slower and therefore easier. |
| `minAttemptIntervalTicks` | `2` | `1 … 100` | Minimum ticks between two accepted alignment attempts; an automated client cannot beat this floor. |
| `windowBelowDegrees` | `10.0` | `0.5 … 180.0` | How far below the phase target an alignment still counts. |
| `windowAboveDegrees` | `5.0` | `0.5 … 180.0` | How far above it counts. The window is deliberately asymmetric, and both halves are configurable as an accessibility setting. |
| `maxRangeBlocks` | `5.0` | `1.0 … 32.0` | How far a picker may drift from what they are picking before the session ends. |
| `destructiveOutcome` | `false` | — | What a successful pick does to a door or a safe. `false` unlocks it, which is the default; `true` reproduces the source outcome and destroys the block, a safe moving its contents out exactly once first. Padlocks and restraints are unaffected either way: picking those removes the lock and leaves the block or the prisoner intact. This is the one key the shipped defaults hold differently from the `CUFFED_PARITY` preset (`restraints.preset`) — it is off until an operator asks for parity. |

## `[frisking]`

A search opens from the crime menu on a restrained subject. What it takes goes to the searcher's
own inventory, or into the property escrow when a lawful search seals it as evidence; a take the
inventory cannot hold whole is refused and stays where it was.

| Option | Default | Range | What it does |
|---|---|---|---|
| `maxRangeBlocks` | `5.0` | `1.0 … 16.0` | How far the searcher may get from the subject before the search ends. Re-checked every tick, not only when the menu opens. |
| `sessionTimeoutTicks` | `1200` | `20 … 24000` | How long a frisk session may stay open with no accepted transfer. |
| `transferIntervalTicks` | `5` | `0 … 200` | Minimum ticks between two accepted transfers. The delay is server-owned: a disabled button on a client is not enforcement. |
| `requiresArmRestraint` | `true` | — | Whether the subject's arms must be restrained before they can be searched. |
| `lawfulSeizureToEscrow` | `true` | — | Whether a lawful search's seizures are recorded in the property escrow, so they come back when custody ends. |

## `[prison]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `safeSlots` | `36` | `9 … 54` | How many slots a safe holds, rounded down to a multiple of nine. Lowering it never destroys stored items: the surplus is kept and simply not shown until the size goes back up. |
| `reinforcedBreakingPolicy` | `PICKAXE_QUALIFIED` | `PICKAXE_QUALIFIED`, `HARD_CONTAINMENT` | How reinforced blocks resist being taken apart. `PICKAXE_QUALIFIED`: ordinary blocks that need an iron pickaxe and a long time. `HARD_CONTAINMENT`: a subject in custody cannot break one at all; anybody else still can. Nothing here is unbreakable. |
| `reinforcedResistsExplosions` | `true` | — | Whether an explosion may remove a reinforced block. |
| `reinforcedResistsPistons` | `true` | — | Whether a piston may push or pull a reinforced block. |
| `reinforcedAuthorisedRemovalOnly` | `false` | — | Whether only an operator or a facility-authorised actor may break a reinforced block. Off: this is a survival building set, not a protection plugin. |

## `[enchantments]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `allowed` | `imbue, famine, shroud, exhaust, silence` | list of those five names | Which of the five restraint enchantments may be applied at all. A name removed here stops being offered, applicable and effective — but an item already carrying it still loads and can still be removed, because disabling a mechanic must never make saved data undeserialisable. |
| `maxLevelImbue` | `1` | `1 … 5` | Maximum level of Imbue. Every level is clamped to this before any arithmetic reads it. |
| `maxLevelFamine` | `1` | `1 … 5` | Maximum level of Famine. |
| `maxLevelShroud` | `1` | `1 … 5` | Maximum level of Shroud. |
| `maxLevelExhaust` | `1` | `1 … 5` | Maximum level of Exhaust. |
| `maxLevelSilence` | `1` | `1 … 5` | Maximum level of Silence. |
| `effectDurationTicks` | `100` | `20 … 1200` | How long one applied Famine, Shroud or Exhaust instance lasts. Re-offered each second while the enchanted restraint is worn, so a foreign instance of the same effect is never stomped or shortened. |
| `effectAmplifier` | `1` | `0 … 4` | The amplifier those instances carry. |
| `manaDrainPerTick` | `0.005` | `0.0 … 1.0` | Share of a Silenced subject's maximum spell pool drained per tick, per level. With no supported spell mod installed nothing drains and the startup log says so. |
| `imbueTransferPerLevel` | `0.2666` | `0.0 … 1.0` | Share of the captor's damage each level of Imbue moves onto the prisoners they hold. |
| `imbueMaxTransferFraction` | `0.8` | `0.0 … 1.0` | Ceiling on that share whatever the level, so no configuration turns a prison into immortality. |
| `imbueMaxRecipients` | `8` | `1 … 64` | How many distinct prisoners may share one transfer. The budget does not grow with the number of recipients: more prisoners means a smaller share each, never more damage. |

Enchantments are registry **data** entries on this line, so the five ship as
`data/mcacrime/enchantment/*.json` rather than as classes, and their availability is expressed by the
vanilla tags `#minecraft:in_enchanting_table` and `#minecraft:on_random_loot` (both list all five,
`replace: false`). None of them joins `#minecraft:treasure` or `#minecraft:double_trade_price`, and
none joins `#minecraft:tradeable` — vanilla folds `#minecraft:non_treasure` into the trade,
mob-spawn and traded-equipment tags, so joining that would put prison equipment on a librarian's
trade table.

**One divergence from the Forge 1.20.1 line follows from that.** A tag cannot read `allowed`, so an
enchanting table or a loot roll here may still *offer* one of the five that `allowed` has switched
off. The effect stays gated exactly as on the baseline: every service asks
`enchantment/EnchantmentApplicability` first, so a disallowed enchantment does nothing — the same
answer the baseline gives for an enchantment already sitting on an item when an operator removes it
from the list.

## `[compatibility]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `cuffedCoexistence` | `WARN` | `WARN`, `REFUSE` | What to do when the Cuffed mod — the mod this release absorbs — is installed alongside. `WARN` logs one startup line and proceeds. `REFUSE` additionally stops MCA: Crime applying new restraints; removal, recovery and loading existing ones stay enabled, because a disabled mechanic must still let an operator get equipment off somebody. No Cuffed data is read, cleared or disabled either way. |
| `optionalAdaptersEnabled` | `true` | — | Master switch for every optional-mod adapter. Off leaves the mods installed and binds nothing. |
| `reportAdapterVersions` | `true` | — | Whether the startup log names each installed optional mod, its version and what MCA: Crime does about it. |

## `[sentencing.capitalPunishment]`

The **only** capital offence is killing a guard. Nothing escalates automatically: no other offence
qualifies, no timer, circuit, packet or scheduled task ever carries a sentence out, and execution is
always a deliberate act at a guillotine by a player or an on-duty guard. With no usable guillotine
the condemned simply stays in custody. `enabled = false` switches the whole feature off.

| Option | Default | Range | What it does |
|---|---|---|---|
| `enabled` | `true` | — | Master switch for the whole feature. Off leaves the guillotine a usable device that never receives a lawful execution order. |
| `guardKillingIsCapital` | `true` | — | The only offence gate there is. Off means no offence qualifies and every sentence is custodial, whatever the Heat, charge count or band. |
| `npcOffendersEligible` | `false` | — | Whether a villager offender may be capitally sentenced at all. Off by default: an NPC arrest has no player in the loop. |
| `executionDelayTicks` | `1200` | `0 … 72000` | The ceremony window between arming the device and the blade — the rescue and pardon window. Clemency, rescue, escape, guard death, device destruction or a chunk unload inside it returns the condemned to custody, never to freedom. |
| `condemnedEscortTimeoutTicks` | `2400` | `20 … 216000` | How long a guard may spend walking a condemned captive to a device before giving up and returning them to a cell. The captive stays condemned and stays held; only the walk ends. |
| `guardMayExecute` | `true` | — | Whether an on-duty enforcement guard may carry out a sentence. Off leaves execution to players only. |
| `executionSiteSearchRadius` | `48` | `8 … 128` | How far a guard looks for an assigned execution site. A site is an explicit facility assignment, never a guillotine somebody happened to place. |
| `requiresExecutionDevice` | `true` | — | Whether a capital sentence needs a real device. May only be `false` while `enabled` is `false`; `/crime validate` refuses any other combination, because a capital sentence with no device requirement is an automatic death. |
| `refuseRansom` | `true` | — | A capital captive cannot be ransomed; the demand is refused with its own reason rather than quoting a price nobody may accept. |
| `refuseBail` | `true` | — | A capital sentence has no bail price. A fine never clears a capital case either way. |
| `dropPossessionsOnExecution` | `true` | — | Whether the possessions held in escrow for the condemned spill at the device. Off banks them instead. |

`executionDelayTicks` must be at or below `condemnedEscortTimeoutTicks`, and both must be finite and
non-negative. `/crime validate` reports any violation.

## Retired in 0.7.5

These keys no longer exist. Their values are **not** migrated: `ModConfigSpec` drops them from an
existing `.toml` on rewrite, and `ConfigValidator` reads the raw files at startup, names every
survivor it finds with its replacement, and does **not** fail the load. Set the replacement yourself
if you had tuned the old key.

| Retired key | Section | Replacement |
|---|---|---|
| `captureChannelTicks` | `[kidnapping]` | `restraints.application.channelTicks` |
| `captureMaxMoveBlocks` | `[kidnapping]` | no equivalent — an application is decided once, not channelled at a distance |
| `captureMaxRangeBlocks` | `[kidnapping]` | `restraints.application.maxRangeBlocks` |
| `captureRequireLineOfSight` | `[kidnapping]` | `restraints.application.requireLineOfSight` |
| `captureLowHealthFraction` | `[kidnapping]` | `restraints.application.lowHealthFraction` |
| `villagerCaptureRelaxedVulnerability` | `[kidnapping]` | `restraints.application.vulnerabilityGates` — an empty gate list gates nothing, the same way |
| `captureChannelMultiplierRope` | `[kidnapping]` | `restraints.application.channelTicks` — one duration for every restraint |
| `captureChannelMultiplierCuffs` | `[kidnapping]` | `restraints.application.channelTicks` |
| `captureChannelMultiplierLockedCuffs` | `[kidnapping]` | `restraints.application.channelTicks` |
| `restraintEscapeChanceRope` | `[kidnapping]` | `restraints.definitions.durabilityDuckTapeArms` — escape is work against durability, not a roll |
| `restraintEscapeChanceCuffs` | `[kidnapping]` | `restraints.definitions.durabilityShackles` |
| `restraintEscapeChanceLockedCuffs` | `[kidnapping]` | `restraints.definitions.durabilityHandcuffs` |
| `captiveTetherBlocks` | `[kidnapping]` | no equivalent in 0.7.5 — the hold is a tether record, not a radius |
| `captiveCanEscapeByDistance` | `[kidnapping]` | `[restraints.escape]` — a captive struggles out, rather than walking out |
| `escapeWorkTicksRope` | `[kidnapping]` | `restraints.definitions.durabilityDuckTapeArms` |
| `escapeWorkTicksCuffs` | `[kidnapping]` | `restraints.definitions.durabilityShackles` |
| `escapeWorkTicksLockedCuffs` | `[kidnapping]` | `restraints.definitions.durabilityHandcuffs` |
| `cuffEscapeRequiresLockpick` | `[kidnapping]` | `lockpicking.enabled` — picking is native now; otherwise a key or a cutting tool |
| `escapeAttemptCooldownTicks` | `[kidnapping]` | `restraints.escape.minWorkIntervalTicks` |
| `renderCuffs` | `[client]` | `client.renderWornRestraints` (the worn models) and `client.hudRestraintPanel` (the panel) |
| `renderEscortRope` | `[client]` | `client.renderTether` |

### Renamed before release

Thirteen 0.7.5 keys were renamed during development so that no key repeats the name of its own
section. They are new in this unreleased version, so **no existing config file is affected**; the
list is here only for anyone who followed the release along.

| Earlier spelling | Final key |
|---|---|
| `restraints.application.applicationChannelTicks` | `restraints.application.channelTicks` |
| `restraints.application.applicationMaxRangeBlocks` | `restraints.application.maxRangeBlocks` |
| `restraints.application.applicationRequireLineOfSight` | `restraints.application.requireLineOfSight` |
| `restraints.escape.escapeMinWorkIntervalTicks` | `restraints.escape.minWorkIntervalTicks` |
| `restraints.escape.escapeMaxInputsPerSecond` | `restraints.escape.maxInputsPerSecond` |
| `restraints.escape.escapeReturnsWornItem` | `restraints.escape.returnsWornItem` |
| `lockpicking.enableLockpicking` | `lockpicking.enabled` |
| `lockpicking.lockpickDrainPerTickDivisor` | `lockpicking.drainPerTickDivisor` |
| `lockpicking.lockpickMinAttemptIntervalTicks` | `lockpicking.minAttemptIntervalTicks` |
| `lockpicking.lockpickWindowBelowDegrees` | `lockpicking.windowBelowDegrees` |
| `lockpicking.lockpickWindowAboveDegrees` | `lockpicking.windowAboveDegrees` |
| `lockpicking.lockpickMaxRangeBlocks` | `lockpicking.maxRangeBlocks` |
| `lockpicking.lockpickDestructiveOutcome` | `lockpicking.destructiveOutcome` |

## Client — `[client]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `nameColorEnabled` | `true` | — | Tint player nameplates by band. |
| `nameColorMode` | `FULL` | `FULL`, `PREFIX_ONLY` | `FULL` recolours the name, but only where it is unstyled, so a nickname or formatting mod keeps its own styling. `PREFIX_ONLY` adds a marker and leaves the name alone. Neutral is never touched either way. |
| `showPlayerCardButton` | `true` | — | Show the reputation card button in the inventory screen. |
| `playerCardOpenByDefault` | `false` | — | Open the card automatically whenever the inventory opens. |
| `showButtonOnMcaScreen` | `true` | — | Add the Crime button to MCA's own villager interaction screen. Off leaves the weapon trigger and the keybind as the ways in. |
| `captiveScreenToggle` | `true` | — | Show the captive panel while you are being held. |
| `confirmHostileActions` | `true` | — | Ask before a hostile action. Presentation only — the server validates every action whether or not you were asked. |
| `hudEnabled` | `true` | — | Master switch for the on-screen HUD. Off returns everything to chat. |
| `hudChannelBar` | `true` | — | The action channel bar, and the reason an action broke off. Nine distinct interruption reasons exist; this is where they are shown. |
| `hudStatusIndicator` | `true` | — | Heat and Wanted status. Draws nothing at all when you have neither. |
| `hudCustodyIndicator` | `true` | — | Remaining jail sentence or captivity time, counted down continuously and shown as `1m 42s remaining`. The client runs its own clock between the server's periodic resyncs, so the number moves every tick without a packet every tick; the server remains the only thing that decides when a sentence actually ends. |
| `renderRestraintPose` | `true` | — | Pose a restrained player's arms behind their back. Drawn by a client-only mixin a dedicated server never loads. Presentation only: turning it off changes nothing the server knows or allows. |
| `hudRestraintPanel` | `true` | — | Show the restraint panel: what is physically worn, how far a struggle has got, and which escape actions are available. |
| `renderWornRestraints` | `true` | — | Draw the worn restraint models — cuffs, shackles, tape and hood — on a restrained body. Presentation only: a hidden restraint restricts exactly as much as a drawn one. |
| `renderTether` | `true` | — | Draw the tether between whoever is holding somebody — an escorting guard, a captor, a chain anchor — and the subject. Cosmetic: it is drawn from mod state rather than a real leash, because a vanilla lead cannot be attached to a player. |
| `hudAnchor` | `BOTTOM_LEFT` | `TOP_LEFT`, `TOP_CENTER`, `TOP_RIGHT`, `CENTER_LEFT`, `CENTER_RIGHT`, `BOTTOM_LEFT`, `BOTTOM_CENTER`, `BOTTOM_RIGHT` | Anchor for one combined Heat/Sentence panel. BOTTOM_LEFT fits below chat and left of the hotbar; other bottom anchors clear the health rows (and the channel bar at BOTTOM_CENTER). |
| `hudLayoutVersion` | `0` → `1` | `0 … 1` | Managed migration marker. On first load, the former TOP_LEFT default at offsets 4/4 becomes BOTTOM_LEFT. Custom anchor/offset combinations survive. |
| `hudOffsetX` | `4` | `-4096 … 4096` | Horizontal nudge inward from the anchored edge — right from a left anchor, left from a right one. Clamped so an element never leaves the screen. |
| `hudOffsetY` | `4` | `-4096 … 4096` | Vertical nudge inward from the anchored edge — down from a top anchor, up from a bottom one. Clamped the same way. |
| `crimeButtonAnchor` | `BOTTOM` | `BOTTOM`, `TOP_RIGHT` | Where the Crime button sits on MCA's interaction screen. `BOTTOM` measures MCA's own widgets and drops in underneath them; `TOP_RIGHT` is the corner placement used before 0.5.1, kept for MCA builds whose panel reaches the bottom of the screen. |
| `showNpcMuggingHud` | `true` | — | Show the bar and the hint line while a thief is mugging you. Presentation only — with it off the mugging still runs, and drawing a weapon still stops it. |
| `maskHidesNameTag` | `true` | — | Hide the nameplate of a player wearing a mask. Presentation only: the server still knows who they are. |
| `sandParticles` | `NORMAL` | `NORMAL`, `REDUCED`, `OFF` | How much dust a sand burst throws up. Presentation only: a blinded NPC is exactly as blind either way. |

The default panel stays below vanilla chat, including its queued-message strip, and beside the hotbar.
Sentence shares the same panel as Heat, with no reserved empty Heat row when Heat is hidden. On small
GUI widths or with long translated labels, the panel scales to fit that pocket. BOTTOM_LEFT clamps
offsets within the pocket; select another anchor for unrestricted custom positioning. The panel hides
while the chat input is open. MCA: Quests can keep its top-left quest log.

Responders retain law/combat AI when threatened, including with empty hands or low health; civilian
fear/compliance options no longer make guards flee. The existing civilian reaction speed multiplier
also applies to direct flee/help requests, in addition to the normalized navigation pace.

## Playing with it turned down

A few coherent configurations rather than a list of switches:

- **Consequence without policing.** Set `enableCrimeDetection = true` but `guardScanIntervalTicks`
  high and `enableVillagerFlee = false`. Crimes are recorded, karma and Heat move, the ledger fills,
  hearts are lost — and nobody chases you. The reckoning comes through relationships instead.
- **Harsh law.** `redIsLegalTarget = true`, `requireWitnessForHeat = false`,
  `heatDecayPerMinute = 0`. Being an outlaw is enough to be hunted, private crimes still raise Heat,
  and Heat only goes away by paying or serving.
- **No kidnapping.** `enableKidnappingNpc = false` and `enableKidnappingPlayer = false`. The
  restraint items still exist and the custody machinery still runs for jail, which is a separate
  system.
- **Numbers only, no theatre.** `ambientMessagesEnabled = false` server-side and
  `nameColorEnabled = false` client-side. The whole system runs silently; `/crime status` is the
  only way to see it.
- **Suite standing without suite double-counting.** With MCA: Reputation installed, leave
  `enableReputation`, `mirrorReputationFallback`, and `suppressLocalVillagePenalty` all at their
  defaults. Changing the third is the one that quietly doubles every penalty.

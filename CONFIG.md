# MCA: Crime — configuration

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
| `wantedHeatThreshold` | `50` | `0 … 1000000` | Heat at or above this makes a player Wanted, and therefore a legal target. |

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

There is no scan of villagers anywhere in this system. Reactions are created by an event and only
existing controllers are ticked, so a world with three hundred villagers and one mugging in progress
does one villager's worth of work.

## `[dialogue]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `enableDialogue` | `true` | — | Data-driven villager lines. The server picks the line and the client renders the key, so lines localise and no text crosses the wire. |
| `dialogueCooldownTicks` | `40` | `0 … 12000` | Minimum ticks between spoken lines from one villager to one player. Per **pair**, not global, so a village reacting to a murder produces several villagers each saying something once. |

Lines live in `data/<namespace>/mcacrime/dialogue/*.json` and reload with `/reload`. A pack replaces
a pool by declaring the same `event`. Dialogue never determines an outcome: nothing downstream
branches on what was said, so rewriting every line cannot change what happens.

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
| `raidGrace` | `true` | — | Suppress crime detection during an active village raid, so a stray arrow in a pillager fight is not a murder charge. |
| `pvpCountsAsCrime` | `false` | — | Whether harming another player is recorded as a crime. On, it produces `mcacrime:assault_player` and `mcacrime:murder_player` — their own crime types, not the villager ones. Force against a legal target is still lawful, so attacking a Wanted player is never itself an offence. |
| `globalCrimePropagation` | `false` | — | Whether a filed report sours every village that already knows you, rather than only the jurisdiction that received it. Off keeps standing local, which is what makes per-village reputation mean anything. It also decides whether a guard may act on another village's reports. |
| `enableGuardChallenge` | `true` | — | A guard with a basis challenges before it attacks: it states the charge and opens a window to surrender, pay, ask what the charges are, or refuse. Off returns guards to attacking a Wanted player on sight. |
| `guardChallengeWindowTicks` | `200` | `20 … 12000` | How long a challenged player has to answer. **No answer is a refusal**, not a pardon. |
| `guardChallengeRadius` | `6.0` | `1.0 … 32.0` | How close a guard must be to issue a challenge. Smaller than `guardAggroRadius`, so guards do not shout charges across a field. |
| `resistingArrestTicks` | `2400` | `20 … 1728000` | How long refusing a challenge keeps you a lawful target, in online ticks. This is what makes refusal a decision rather than a message: for the duration, guards may use force whether or not your Heat would otherwise justify it. |

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

| Option | Default | Range | What it does |
|---|---|---|---|
| `enableKidnappingNpc` | `true` | — | Whether villagers can be taken captive. |
| `enableKidnappingPlayer` | `true` | — | Whether players can. |
| `captureChannelTicks` | `60` | `0 … 6000` | Base channel duration to restrain a target. `0` makes capture instant, which is exactly the grief button this gate exists to prevent. |
| `captureMaxMoveBlocks` | `1.5` | `0.0 … 64.0` | The channel breaks if the captor moves this far from where it started. |
| `captureMaxRangeBlocks` | `4.0` | `0.5 … 64.0` | The channel breaks if the target gets this far from the captor. |
| `captureRequireLineOfSight` | `true` | — | The channel needs, and breaks on losing, line of sight. |
| `captureLowHealthFraction` | `0.35` | `0.0 … 1.0` | A player target counts as vulnerable at or below this fraction of max health. |
| `villagerCaptureRelaxedVulnerability` | `true` | — | Ordinary villagers may be captured without meeting a vulnerability condition. **Guards and combat NPCs never skip the gate, whatever this is set to.** |
| `captureChannelMultiplierRope` | `0.6` | `0.1 … 10.0` | Per-restraint channel multipliers: rope is quick to tie… |
| `captureChannelMultiplierCuffs` | `1.0` | `0.1 … 10.0` | …cuffs are the baseline… |
| `captureChannelMultiplierLockedCuffs` | `1.5` | `0.1 … 10.0` | …and locked cuffs take longest. |
| `restraintEscapeChanceRope` | `0.25` | `0.0 … 1.0` | Per-attempt chance a captive slips rope. |
| `restraintEscapeChanceCuffs` | `0.08` | `0.0 … 1.0` | Per-attempt chance for cuffs. |
| `restraintEscapeChanceLockedCuffs` | `0.0` | `0.0 … 1.0` | `0` means escape needs a key or a rescue, not a roll. |
| `captiveTetherBlocks` | `6.0` | `1.0 … 128.0` | How far a captive may stray from the hold point. |
| `captiveCanEscapeByDistance` | `true` | — | A kidnapping captive who strays past the tether escapes — and escaping kidnapping is never a crime. Set false and they are pulled back instead. |
| `npcCaptiveVirtualizeWhenUnloaded` | `true` | — | An NPC captive in an unloaded chunk is virtually contained rather than force-loading the chunk. Turning this off makes every captive a permanently loaded chunk. |

## `[npccrime]` — declared, not yet wired

| Option | Default | Range |
|---|---|---|
| `enableNpcCrime` | `false` | — |
| `maxActiveNpcCrimesPerVillage` | `2` | `0 … 1000` |
| `minTimeBetweenNpcCrimes` | `6000` | `0 … 1000000` |

Villagers do not commit crimes against each other in this version. The master switch is off and the
throttles have no effect.

## `[jail]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `enableFines` | `true` | — | Whether `/crime payfine` works at all. |
| `enableBail` | `false` | — | Let a jailed player buy out the rest of a sentence. Off is the default and keeps a sentence something you serve rather than something you price. Bail settles the cases as `FINED` — a player released with every charge still open would walk out of the cell into the arms of the guard who put them there. |
| `bailCostPerMinute` | `4` | `0 … 100000` | Emeralds per remaining real minute. Priced on what is left, not the original sentence, and rounded up so a sliver of a minute is never free. |
| `bailMinServedFraction` | `0.25` | `0.0 … 1.0` | How much of the sentence must already be served before bail is offered. `0.0` lets a sentence be bought out the instant it starts. |
| `maxCaptivityRealMinutes` | `360` | `1 … 100000` | Hard ceiling, in real online minutes, on how long any player may be held — jailed or kidnapped. This is the softlock backstop: it force-releases regardless of anchor, dimension, or chunk state. |
| `jailContainmentMode` | `CONTAINMENT` | `CONTAINMENT`, `PHYSICAL`, `REINFORCED` | How jail resists escape. **Snapshotted at the moment of jailing**, so changing this mid-sentence cannot surprise a prisoner. |
| `maxJailCommandTicks` | `72000` | `1 … 100000000` | Upper clamp on a `/crime jail` sentence, in online ticks. 72000 is one online hour. |
| `jailRadiusDefault` | `8` | `1 … 64` | Default jail-region radius for `/crime assignjail` and the fallback. |
| `buildHoldingCell` | `true` | — | **This is the temporary-jail switch.** When no jail anchor is assigned and no fallback is configured, build a small iron-bar holding cell near the arrest and take it down again on release, restoring every block it replaced. It gates the only code path in the mod that places a block, so turning it off means the mod builds nothing, anywhere, ever. Player-built and `/crime assignjail` jails keep working exactly as before. See below for what happens to an arrest with nowhere to go. |
| `holdingCellSearchRadius` | `24` | `4 … 96` | How far from the arrest to look for ground clear enough to build a cell on. A site is rejected outright if it contains any block entity, any fluid, or anything that is not air, replaceable foliage, or plain terrain. |
| `holdingCellLifetimeTicks` | `1728000` | `1200 … 100000000` | Hard ceiling on how long a built cell may stand (24000 = one Minecraft day). The leak guard: a player arrested and never seen again would otherwise leave a cage in a village for the life of the save. |
| `sentenceBaseTicks` | `1200` | `0 … 100000000` | Fixed part of a sentence, in online ticks (1200 = one online minute). |
| `sentenceTicksPerHeat` | `30` | `0 … 1000000` | Added sentence length per point of Heat at the time of arrest. Surrender reduces Heat first, so giving yourself up genuinely shortens the term. |
| `sentenceTicksPerCharge` | `200` | `0 … 1000000` | Added sentence length per outstanding charge being answered for. |
| `arrestEscortTimeoutTicks` | `600` | `0 … 24000` | How long a guard is given to walk an arrested player to the cell before the arrest completes by teleport instead. `0` skips the escort entirely. A pathfinding failure must never be a way to dodge a sentence. |
| `escortTetherBlocks` | `16.0` | `4.0 … 64.0` | **Hard** radius: how far an arrested player may get from their escort before the arrest is abandoned and they are marked resisting instead. Running from a surrender is a decision, so it gets a consequence rather than a teleport back. |
| `escortLeashBlocks` | `5.0` | `1.0 … 32.0` | **Soft** radius: past this, the prisoner is pulled back toward the guard, gently at first and harder as they approach the tether. Must stay below `escortTetherBlocks`, or the escort is abandoned before the lead ever engages — `/crime validate` flags this. |
| `escortSpeedPenalty` | `0.35` | `0.0 … 0.9` | How much of a restrained player's movement speed is taken away. `0.35` = they move at 65% of normal. Applied as an attribute modifier rather than a potion effect, so it is invisible, emits no particles, and cannot be drunk away with milk. |
| `escortWalkSpeed` | `0.9` | `0.1 … 2.0` | How fast the guard walks while escorting. Below `1.0` reads as a deliberate march rather than a chase, and keeps the guard inside the leash radius. |
| `escortNavigationIntervalTicks` | `20` | `1 … 200` | How often the escort reissues its walk order. MCA villagers run their own brain, so an order reissued every tick fights it and the guard visibly stutters; the order is also refreshed whenever the previous path finishes. |
| `escortStuckScans` | `6` | `1 … 100` | How many consecutive escort scans may pass without the prisoner getting closer to the jail before the arrest completes by teleport instead. The door, terrain and pathfinding failsafe: a guard that cannot find its way may finish an arrest less gracefully, never cancel it. |
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

Both lists take plain item ids (`minecraft:iron_sword`) or `#tags` (`#forge:tools/spears`). Wildcards
are **not** accepted here, because nothing expands one. The validator parses every entry, checks
plain ids against the item registry, and warns when the same entry appears on both lists.

Classification is first-match-wins, in this order:

1. a restraint — never a weapon;
2. `blacklist`;
3. `whitelist`;
4. the `mcacrime:weapons_blacklist` item tag;
5. the `mcacrime:weapons` item tag (ships covering swords, axes, and the Forge bow/crossbow/trident
   tags, so a datapack can add to it rather than to anybody's config file);
6. then, only if `autoDetect` is on: swords, axes and tridents; bows, crossbows and anything with a
   drawing use animation; a gun keyword in the item's path; a `weaponMods` namespace on a
   non-stackable, non-block item; digging tools other than axes, which are excluded; and finally the
   `autoDetectMinAttackDamage` threshold.

`/crime debug weapon` prints the held item's id, its class, the layer that decided it, and the
threshold in force.

**Config sync caveat.** This block is COMMON config, which Forge does not sync to clients. The client
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

## `[mugging]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `enableMugging` | `true` | — | Whether target-bound mug actions are available. |
| `muggingBaseLoot` | `4` | `0 … 1000000` | Maximum emeralds requested; actual payout is bounded by purse and daily caps. |
| `enableProfessionDeathDrops` | `false` | — | Whether a villager killed while resisting a mugging drops profession loot. Off by design: with it on, murdering your victim starts paying better than robbing them. |
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

## `[messages]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `ambientMessagesEnabled` | `true` | — | On-screen messages for band changes, being witnessed, and guard pursuit. |
| `ambientMessageThrottleTicks` | `100` | `0 … 100000` | Minimum ticks between repeats of the same kind of message to one player. Band transitions are never throttled — they are the one thing you must not miss. |
| `chatNameColorEnabled` | `false` | — | Colour player names in chat by band. This is now the only switch: the client-side `chatFormatToggle` that used to shadow it was removed in 0.4.0, because a client cannot opt out of text the server has already formatted, and a setting that reads as a choice but is not is worse than no setting at all. |
| `chatNameColorMode` | `FULL` | `FULL`, `PREFIX_ONLY` | On 1.20.1 signed chat only exposes the message body to the server, so `FULL` adds a coloured prefix marker rather than recolouring the sender's name. `PREFIX_ONLY` adds a plainer marker. |

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

## `[integrations]`

Every setting here is a no-op when the companion mod is absent.

| Option | Default | Range | What it does |
|---|---|---|---|
| `enableReputation` | `true` | — | Record community standing through MCA: Reputation when it is installed, instead of the built-in per-village store. **With this off, MCA: Reputation keeps detecting villager assault and killing itself** — there is never a state where both record the same deed, or neither does. |
| `mirrorReputationFallback` | `true` | — | After Reputation commits a standing change, copy the resulting score into the built-in store. Costs nothing, and means uninstalling Reputation later does not reset every player to a stranger. |
| `suppressLocalVillagePenalty` | `true` | — | Skip the built-in village penalty for crimes Reputation is recording canonically. **Turning this off applies both, which double-counts every witnessed crime.** |
| `replayPendingOperations` | `true` | — | Retry cross-mod writes that were queued but not delivered — after a crash, or while a companion was uninstalled. Off strands pending work indefinitely. |
| `pumpIntervalTicks` | `100` | `20 … 12000` | How often the delivery queue is checked. |
| `pumpBudgetPerTick` | `8` | `1 … 128` | How many queued writes may be delivered in one pass. |
| `maxDeliveryAttempts` | `6` | `1 … 20` | Attempts before a write is set aside as a dead letter for `/crime debug outbox dead`. |
| `retryBaseDelayTicks` | `200` | `20 … 24000` | First retry delay; doubles on each failure. Must not exceed the maximum. |
| `retryMaxDelayTicks` | `24000` | `20 … 1728000` | Ceiling on the retry delay. |
| `dedupeRetentionTicks` | `168000` | `1200 … 1728000` | How long a completed transaction is remembered so a replay of it changes nothing. Long-lived case-to-incident links live on the record itself and never expire; this only covers the replay window for one-off mutations. |

A companion that is not installed yet is a *delay*, and the write is retried. A payload the companion
actively rejects is *not* retried, because a thousand retries would bury the one log line an operator
needs.

### `[integrations.reputation]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `fineResolutionStatus` | `atoned` | `atoned`, `apologized` | How a paid fine reads to the village: made good, or merely said sorry. |
| `servedResolutionStatus` | `atoned` | `atoned`, `apologized` | The same for a served sentence. |

Only those two values are accepted; anything else is a validation error. Unresolved, escaped, and
expired cases deliberately map to **no** resolution status at all — breaking out of jail is not
atonement, and a case ageing out is not the village forgiving a murder.

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
| `renderRestraintPose` | `true` | — | Pose a restrained player's arms behind their back. The only thing in this mod that needs a mixin, and it is client-side only — a dedicated server never loads it. Presentation only: turning it off changes nothing the server knows or allows. |
| `renderCuffs` | `true` | — | Draw cuffs on a restrained player's wrists. Parented to the arms, so they sit correctly with or without the pose above. |
| `renderEscortRope` | `true` | — | Draw the lead between an escorting guard and their prisoner. Cosmetic: it is drawn from mod state rather than a real leash, because a vanilla lead cannot be attached to a player. |
| `hudAnchor` | `BOTTOM_LEFT` | `TOP_LEFT`, `TOP_CENTER`, `TOP_RIGHT`, `CENTER_LEFT`, `CENTER_RIGHT`, `BOTTOM_LEFT`, `BOTTOM_CENTER`, `BOTTOM_RIGHT` | Which edge the status and custody boxes sit against. Bottom anchors are lifted clear of the hotbar and the health and armor rows, and `BOTTOM_CENTER` clear of the channel bar, so picking one never hides a box behind vanilla's HUD. The channel bar itself always sits above the hotbar. |
| `hudOffsetX` | `4` | `-4096 … 4096` | Horizontal nudge inward from the anchored edge — right from a left anchor, left from a right one. Clamped so an element never leaves the screen. |
| `hudOffsetY` | `4` | `-4096 … 4096` | Vertical nudge inward from the anchored edge — down from a top anchor, up from a bottom one. Clamped the same way. |

The default corner is the bottom-left because the top-left is where MCA: Quests draws its quest log.
Vanilla chat also lives in the bottom-left, so the boxes and recent chat lines share that space until
chat fades; a player who would rather not have that can pick another corner or raise `hudOffsetY`.

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

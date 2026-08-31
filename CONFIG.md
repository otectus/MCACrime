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
| `allowKillingRed` | `false` | — | Whether killing an Outlaw is permitted without it being a crime. |
| `raidGrace` | `true` | — | Suppress crime detection during an active village raid, so a stray arrow in a pillager fight is not a murder charge. |
| `pvpCountsAsCrime` | `false` | — | Reserved seam. Player-versus-player harm is not currently detected as a crime. |
| `globalCrimePropagation` | `false` | — | Whether a crime in one village sours every village. Off keeps standing local. |

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
| `enableBail` | `false` | — | **Declared, not implemented.** There is no bail in this version. |
| `maxCaptivityRealMinutes` | `360` | `1 … 100000` | Hard ceiling, in real online minutes, on how long any player may be held — jailed or kidnapped. This is the softlock backstop: it force-releases regardless of anchor, dimension, or chunk state. |
| `jailContainmentMode` | `CONTAINMENT` | `CONTAINMENT`, `PHYSICAL`, `REINFORCED` | How jail resists escape. **Snapshotted at the moment of jailing**, so changing this mid-sentence cannot surprise a prisoner. |
| `maxJailCommandTicks` | `72000` | `1 … 100000000` | Upper clamp on a `/crime jail` sentence, in online ticks. 72000 is one online hour. |
| `jailRadiusDefault` | `8` | `1 … 64` | Default jail-region radius for `/crime assignjail` and the fallback. |
| `jailFallbackEnabled` | `false` | — | Jail at the fallback position when no anchor is assigned. Off means jailing is **refused** with a clear message instead — which is the safer default, because a fallback pointing at a hole in the ground is worse than a refusal. |
| `jailFallbackPos` | `[0, 64, 0]` | — | Fallback position, used only when the fallback is enabled. |
| `jailFallbackDim` | `minecraft:overworld` | — | Dimension for the fallback position. |

**Containment modes.** `CONTAINMENT` protects the region's blocks and teleports strays back.
`PHYSICAL` lets the walls be broken and treats leaving as a genuine breakout: the escaped flag is
set, a `jailbreak` crime is recorded with its Heat, the prisoner is *not* teleported back, and the
sentence keeps running while they run. `REINFORCED` currently behaves as `CONTAINMENT`.

Sentences are counted in online ticks: logging out pauses, dying does not clear, changing dimension
does not stop it, and a restart resumes.

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
| `chatNameColorEnabled` | `false` | — | Colour player names in chat by band. **This is the authoritative switch**; the client's `chatFormatToggle` is only a display hint. |
| `chatNameColorMode` | `FULL` | `FULL`, `PREFIX_ONLY` | On 1.20.1 signed chat only exposes the message body to the server, so `FULL` adds a coloured prefix marker rather than recolouring the sender's name. `PREFIX_ONLY` adds a plainer marker. |

## `[matching]` — declared, not yet wired

| Option | Default | Range |
|---|---|---|
| `professionMatchingMode` | `NORMALIZED` | `STRICT`, `NORMALIZED`, `LOOSE` |

Nothing consumes the profession matching mode in this version.

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
| `chatFormatToggle` | `false` | — | **Display hint only.** The server's `chatNameColorEnabled` is what actually decides. |
| `captiveScreenToggle` | `true` | — | **Reserved.** There is no dedicated captive screen in this version; the captive line on the inventory card is what you get. |

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

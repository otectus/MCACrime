# MCA: Crime — Finalized Specification

**Target:** Minecraft 1.20.1 · Forge 47.x (hard constraint)
**Type:** Standalone add-on to MCA Reborn 7.6.x (hard dependency). All other mods optional.
**Status:** Implementation-ready design spec. Code not yet written.
**Document intent:** A precise enough handoff that a coding agent can implement Phase 1 without further design decisions, while never trusting an unverified API name.

---

## 0. Reading Rules (non-negotiables)

These apply to the entire document and to any implementation derived from it.

1. **Server-authoritative, always.** Every state change — karma, heat, fines, jail, captivity, bounties — happens on the server. The client receives sync only for display (HUD, name color, captive screen). No client message is ever trusted to mutate state.
2. **Idempotent transactions.** Fines, jail commits, bounty claims, ransom payments, and reward grants must be atomic and replay-safe so packet-spam cannot duplicate effects.
3. **Never cast foreign entities.** MCA villagers, Recruits NPCs, Guard Villagers, etc. are accessed only via `instanceof`/tag/`ModList` guards behind a compatibility adapter. A wrong cast crashed legacy MCA; that mistake is forbidden here.
4. **Fail safe, never crash.** If MCA data, a Quests API, or an optional mod is missing or shaped differently than expected, the affected feature disables itself and logs a clear message. The server keeps running.
5. **Everything is config.** Every number, chance, threshold, duration, and toggle in this document is a default, not a constant. Treat hard-coded values as bugs.
6. **Verify before trusting names.** Every class, method, field, mod ID, entity ID, and event referenced from another project is a **verification target** (marked ⚠ or listed in §16) until confirmed against the actual source during coding. Keep all such access behind adapters so a renamed symbol is a one-file fix.

---

## 1. Glossary & Core Concepts

The whole design depends on keeping four ideas separate. Conflating any two of them produces the exploits and grief vectors this spec exists to prevent.

| Term | What it is | What it drives | Time behavior |
|---|---|---|---|
| **Karma** | Long-term moral/legal reputation of a player | Blue/Grey/Red band, villager willingness, quest eligibility | Slow to change, slow decay toward 0 |
| **Heat** (Wanted) | Short-term criminal attention from recent acts | Active guard pursuit, Wanted status, fines/jail clearing | Fast to rise, fast decay (online time) |
| **Legal Target** | A formal flag meaning "force against this entity is lawful right now" | Whether guards/Blue players incur penalty for attacking | Event-driven; set/cleared by conditions |
| **Custody / Captivity** | Whether an entity is held, by whom, and under what legality | Jail vs kidnapping rules, escape legality, ransom validity | Persistent until resolved |

### 1.1 Bands (Karma-only)

- **Blue** — trusted/lawful. Blue name. Default threshold: Karma ≥ **+100**.
- **Grey** — neutral. Normal name. Default: **−99 … +99**.
- **Red** — outlaw. Red name. Default: Karma ≤ **−100**.

Bands are derived from **Karma only**. They never read Heat. A Red player is "known to be a criminal"; it does not by itself mean "kill on sight" (see §6.3).

### 1.2 Wanted (Heat-only)

A player/NPC is **Wanted** when Heat ≥ a configurable threshold (default **50**). Wanted means "law enforcement is actively pursuing you right now." Wanted is derived from **Heat only**, so a long-term Red outlaw who has lain low is *not* automatically Wanted, and a Grey player who just committed a witnessed murder *is* Wanted despite good standing.

### 1.3 Legal Target (formal state)

An entity is a **Legal Target** when any of these hold (all config-tunable):

- It is **Wanted** (Heat ≥ threshold), or
- It is **actively committing a witnessed crime**, or
- It is an **escaped jail prisoner**, or
- It is **currently holding a captive** (active kidnapper), or
- It is **attacking guards** or a village authority, or
- It is **Red** *and* the server has `redIsLegalTarget=true`, or
- It is **resisting arrest** after a surrender demand.

The rule then collapses to one line: **guards and Blue players may use force against a Legal Target without criminal penalty.** Force against anyone who is *not* a Legal Target is a crime, including for Blue players and guards. Blue is trusted authority, never immunity.

### 1.4 Captivity types (must stay distinct in the data model)

- **Jail (legal custody):** sanctioned confinement after a crime. **Escaping jail is itself a crime** (adds Heat, may extend sentence).
- **Kidnapping (illegal custody):** confinement by a criminal. **Escaping kidnapping is never a crime** and never produces Heat, a bounty, or karma loss for the victim.

A single boolean (`lawful`) on the captivity record drives this distinction everywhere. Treating the two as the same object is the bug this section exists to prevent.

---

## 2. Data Model

All persistent data lives in server-side storage and survives logout, death, dimension change, chunk/villager unload, and restart. Two storage mechanisms, mirroring the pattern MCA: Quests uses for shared world state:

- **Per-player capability** (attached to the player, copied on respawn via `PlayerEvent.Clone`): the player's own Karma, Heat, band cache, jail state, and active-captor/captive references.
- **World `SavedData`** (e.g. `<world>/data/mcacrime.dat`): authoritative registries that aren't owned by one player — the crime ledger, bounty board, jail roster, custody table, and per-village reputation deltas.

Capabilities hold the fast-path per-player values; `SavedData` is the source of truth for anything cross-player or world-scoped. On login the player capability is reconciled against `SavedData`.

### 2.1 Player record (capability)

```
PlayerCrimeState {
  UUID        player
  long        karma                 // signed; band derived from this
  long        heat                  // >= 0; Wanted derived from this
  Band        cachedBand            // BLUE | GREY | RED (recompute on karma change)
  JailState   jail                  // null if not jailed
  UUID        heldCaptiveRef        // entity/player this player is currently holding, or null
  UUID        heldByRef             // captor currently holding THIS player, or null
  Map<...>    dailyKarmaCounters    // anti-farm tallies (see §3.3)
  long        lastDecayTickOnline   // for karma/heat decay accounting
}
```

### 2.2 Crime Ledger (SavedData)

The ledger is the backbone that makes bounties, repeat-offender scaling, NPC memory, civil-registry entries, and admin debugging possible. Every **serious** crime writes one entry. Petty/no-witness events may be summarized rather than individually recorded (config `ledgerVerbosity`).

```
CrimeRecord {
  UUID        id
  UUID        offender
  UUID        victim                // player or NPC; null for victimless (e.g. trespass)
  CrimeType   type                  // see §5.1 registry
  UUID        villageId             // nearest/owning village, or null
  boolean     witnessed
  long        timeCommitted         // game time
  long        heatGenerated
  long        karmaDelta
  long        fineAmount            // assessed, in currency units
  long        jailTicks             // sentence assigned, online ticks
  Resolution  resolution            // UNRESOLVED | SERVED | FINED | PARDONED | ESCAPED | EXPIRED
}
```

`Resolution` lets the system say "your Karma recovered to Grey, but this village still has an unresolved record against you" — serious crimes leave consequences (bounty, village reputation, relationship damage) until explicitly resolved, even after Karma rebounds (§3.4).

### 2.3 Custody record (SavedData)

```
CustodyRecord {
  UUID        captive               // player or NPC
  boolean     lawful                // true = jail, false = kidnapping
  CustodyOwner owner                // KIDNAPPER(uuid) | GUARD(uuid) | JAIL(villageId/pos) | AUTHORITY(villageId) | NONE
  RestraintType restraint           // NONE | ROPE | CUFFS | LOCKED_CUFFS
  long        startTickOnline       // for captivity-cap accounting (player captives)
  long        remainingJailTicks    // for lawful custody only
  BlockPos    holdPos               // jail/holding location if applicable
  boolean     virtual               // true if chunk unloaded and captive is virtually contained
}
```

### 2.4 Bounty board (SavedData)

```
Bounty {
  UUID        id
  UUID        target
  long        reward                // currency
  boolean     preferCapture         // capture pays full; kill pays reduced/none per config
  UUID        issuer                // village authority or player
  long        expiry                // game time, optional
}
```

### 2.5 Per-village reputation (SavedData)

Lightweight in 1.0: a `Map<villageId, Map<playerUUID, int delta>>`. Global Karma remains the authoritative Blue/Grey/Red value; the per-village delta is an optional local modifier affecting guard aggression, fines, and villager willingness in that village only. A crime in one village does **not** make every village hostile unless `globalCrimePropagation=true`.

---

## 3. Karma & Heat System

### 3.1 Two-axis model (the core decision)

- **Karma** is reputation. It moves on trade, gifts, quests completed/failed, village defense, crimes, arrests, jail served, ransom paid/extorted, and murder. It changes slowly and decays gently toward 0 (default ±1 per online MC day) so neglected reputations normalize but active behavior dominates.
- **Heat** is current law-enforcement pressure. It rises from recent witnessed crimes and decays per online minute, or is cleared by jail/fine/surrender. Heat — not Karma — is what makes a guard chase you *now*.

This separation prevents both failure modes: a single mistake can't permanently brand you (Heat decays), and a career criminal can't launder himself trusted by paying one fine (Karma is sticky and the ledger persists).

### 3.2 Karma sources (default weights — all config)

| Action | Karma | Notes |
|---|---|---|
| Trade with MCA villager | small + | Capped daily (§3.3) |
| Gift / positive MCA interaction | small + | Capped daily |
| Complete MCA: Quest | medium + | Via `QuestCompleted`; harder to farm |
| Defend village / kill raid enemy near village | medium + | Only inside/near valid village vs real raid enemies |
| Protect villager from hostile player/NPC | large + | Strong signal |
| Harm MCA villager | medium − | Scaled by witnessed (§3.5) |
| Kill MCA villager | large − | Plus ledger entry, Heat, bounty eligibility |
| Theft / vandalism / trespass | small − | Context-gated |
| Fail/abandon crime-relevant quest | small − | Via `QuestFailed`/`QuestAbandoned` |

### 3.3 Anti-farming (mandatory)

Positive Karma is trivially farmable without limits. Required protections:

- **Per-villager daily cap:** trading/gifting a single villager grants Karma only up to a daily ceiling.
- **Per-village daily cap:** aggregate positive Karma from one village per day is capped.
- **Per-player global daily cap:** total daily positive Karma from passive sources (trade/gift) is capped; quests and defense have separate, higher allowances.
- **Diminishing returns:** repeated identical actions yield progressively less within the window.
- **Defense validity:** raid-defense Karma requires the player to be inside/near a valid village and to damage actual raid enemies — not mob-farm kills.

Quests intentionally grant more meaningful Karma than trades because they're structured and hard to spam.

### 3.4 Asymmetric recovery + criminal record

Good deeds must not instantly erase serious crime. Karma can recover normally, but serious crimes recorded in the ledger (§2.2) retain consequences — bounty, village reputation, relationship damage, unresolved status — until specifically resolved by jail served, fine + restitution, or pardon. "Trading potatoes for ten minutes" can lift your band back to Grey but cannot clear an unresolved murder record.

### 3.5 Witness logic (Heat vs Karma split)

The reviewer's refinement, adopted as default:

- **Unwitnessed crime:** reduces **Karma** (slightly or fully, per `unwitnessedKarmaFactor`, default 1.0) but generates **little or no Heat**. Stealth crime still has moral weight — invisible murder is not morally free — but doesn't summon guards.
- **Witnessed crime:** reduces Karma **and** generates Heat/Wanted, triggering guard response, reports, and bounty eligibility.

A crime is "witnessed" when an MCA villager/guard (or configured responder NPC) has line-of-sight to the act within a configurable radius — mirroring vanilla gossip's line-of-sight rule.

---

## 4. Player Bands, Legal Targets & Law Enforcement

### 4.1 Band effects

Band (from Karma) drives: chat/nameplate name color (client-toggleable, non-destructive — see §10.3), guard disposition baseline, villager interaction gating (Red villagers may refuse trade/dialogue or flee), and quest eligibility via the Quests condition bridge (§8).

### 4.2 Red is not automatically kill-on-sight

Default consequence ladder for Red, all configurable:

- **Red** players are *legally suspicious* and valid for **capture**.
- **Wanted** players (Heat-based) are *actively pursued*.
- **Red + Wanted** are *dangerous criminals* and may be attacked by guards or Blue players.
- Killing Red targets is permitted **only if** `redIsLegalTarget`/`allowKillingRed` is set; **capture is always more rewarding than killing** (§7, §9) so "saw a red name, killed instantly" is never the single optimal play.

### 4.3 Blue law enforcement — strict rules

Blue status is trusted authority, not a license to murder. A Blue player avoids Karma penalty **only** when acting against a **Legal Target** (§1.3): Red-if-configured, Wanted, active attackers, escaped prisoners, active kidnappers, or someone committing a witnessed crime. If a Blue player attacks a Grey or Blue innocent, they **lose Karma and may become Wanted** like anyone else. This is stated explicitly so implementation never grants blanket immunity.

### 4.4 Guard roles (behavior, not new entities)

Guards differentiate by location/profession behavior, not separate entity types in 1.0:

- **Patrol guards** — pursue nearby Wanted/Legal Targets.
- **Jail guards** — protect prisoners, resist breakouts near a jail POI.
- **Bounty guards** — accept criminal turn-ins.
- **Village authority** — handles fines, pardons, ransom reports.

Prefer MCA's existing guard/outlaw aggression machinery over inventing new AI; add external targeting goals only where MCA exposes no hook.

---

## 5. Crime Detection

### 5.1 Crime type registry (data-driven)

Crimes are JSON-defined (`data/<ns>/mcacrime/crimes/*.json`) with: `id`, base `karmaDelta`, base `heatDelta`, `witnessedMultiplier`, `victimTag` (which entities it applies to), and `requiresContext` flags. Built-in types: `harm_villager`, `kill_villager`, `theft`, `kidnap`, `trespass`, `vandalism`, `assault_guard`, `jailbreak`. Server owners add types without code.

### 5.2 False-positive protection (critical)

False detection ruins the mod fast. Before any crime registers, the detector verifies all of:

- **True attacker** resolved via `DamageSource.getEntity()` (arrow → shooter), and it is a real `ServerPlayer` — **`FakePlayer` is filtered out** to exclude automation/machines.
- **Victim type** matches a protected tag/list (§9).
- **Context:** environmental/indirect deaths (mob knocks villager into lava, dispenser damage) don't count by default.
- **Raid state:** crime detection suppressed during an active village raid (`raidGrace=true`) — accidental cleave on a villager mid-raid isn't a crime.
- **PvP setting:** `pvpCountsAsCrime=false` by default.
- **Owner/team/faction:** for owned NPCs (Recruits etc.), respect owner/team/faction/PvP config (§9.3) before ruling a hit criminal.
- **Red/Wanted/hostile check:** attacking a Legal Target or a target that was hostile/attacking first is lawful, not a crime.

### 5.3 Detection hooks (Forge 1.20.1, verified-available)

`LivingHurtEvent` / `LivingAttackEvent` (harm), `LivingDeathEvent` (kill), `AttackEntityEvent` (player-initiated), `PlayerInteractEvent.EntityInteract` (right-click interactions incl. restraints), `BlockEvent.BreakEvent` (vandalism/jailbreak). All filtered through the §5.2 gate before touching karma/heat.

---

## 6. Punishment & Release Valves

A spectrum so not every crime ends in jail or a fight to the death.

### 6.1 Fines

Low-Heat offenders can **pay a fine** to clear Wanted status (emerald sink; fits Minecraft). Severe crimes still require jail or bounty resolution. Blue players may receive reduced fines; Red players may be barred from paying until they **surrender** (§6.4). A portion of every fine conceptually funds **restitution** (§11.3).

### 6.2 Bail (design-included, lower priority)

Distinct from fines: bail **temporarily releases** a jailed player before the sentence completes, optionally leaving a debt/bounty/"court date" hook for future expansion. Not required for first playable; specified so the data model leaves room.

### 6.3 Surrender

A Red/Wanted player may `/crime surrender` near a guard, jail, or Blue player to reduce sentence severity or clear some Heat. Guards may **demand surrender before attacking** (config). NPC criminals may surrender when outnumbered or low-health. This makes arrest possible without every encounter being lethal.

### 6.4 Forgiveness paths

Three routes back to good standing: **serve jail**, **pay fine + restitution**, or **complete an amends quest** (§11.2). Each resolves the corresponding ledger records.

---

## 7. Jail System

### 7.1 Online-ticks, never timestamps (mandatory)

Sentences are stored as **`remainingJailTicks` (online ticks)** and decremented only in `ServerTickEvent`/`PlayerTickEvent` while the player is online and loaded. Storing a remaining duration rather than an end timestamp gives exactly the required behavior:

- Logging out **pauses** the sentence (can't wait it out offline).
- Death **does not clear** it (copied on `PlayerEvent.Clone`).
- Restart **does not break** it (it's a duration, not a wall-clock end).
- Dimension change keeps ticking (player-tick based).
- Admin `/crime release` always works.

### 7.2 Real-time captivity failsafe

Independent of the MC-tick sentence, a **maximum captivity cap measured in real online minutes** (default **360**) protects players from long-duration abuse. Sentences are Minecraft-flavored; the cap is a hard real-world online-time ceiling on how long any player can be held.

### 7.3 Block-protection / containment

Minecraft blocks are breakable, so the spec must decide what stops a prisoner mining out. Configurable modes:

- **Containment (default for anti-grief servers):** prisoners are prevented from breaking jail-region blocks; escape requires interacting with bars/doors/locks or a minigame.
- **Physical (default for hardcore/roleplay):** walls are breakable and breakouts are legitimate — escaping then flags **jailbreak** (Heat + Legal Target).
- **Reinforced:** jail blocks have raised break resistance.

Region is defined by jail structure bounds or an assigned POI/area.

### 7.4 Jail structure — three assignment modes (existing-world safe)

A village must never break for lacking a generated jail:

1. **Generated structure** for newly generated villages.
2. **POI/block-based assignment** for existing villages (designate an area/blocks as the jail).
3. **Command-based assignment** by server owners.

If no jail exists, guards **escort to the nearest jail or a temporary holding point**, or **refuse arrest** until one is assigned — never a crash, never a softlock.

### 7.5 No forced chunk-loading

Prisoner/captive state persists in `SavedData`. If the jail chunk is **loaded**, the prisoner exists physically; if **unloaded**, they are **virtually contained** (`virtual=true`) and restored safely on reload. Avoid force-loading chunks by default (performance).

---

## 8. Captivity & Kidnapping

> **Defaults (corrected per your preference):** **NPC kidnapping defaults ON. Player kidnapping defaults ON.** Both independently disableable. Server owners can select preset profiles — *safe, roleplay, hardcore, singleplayer* (§12.2).

### 8.1 Jail vs kidnapping (never conflate)

Driven by `CustodyRecord.lawful`. Jail = lawful; **escaping jail is a crime**. Kidnapping = unlawful; **escaping kidnapping is never a crime** and never produces Heat/bounty/karma loss for the victim. A rescued/escaped kidnapping victim is never treated as a jailbreaker.

### 8.2 Capture conditions (no instant grief-button)

Kidnapping is **not** a simple right-click that instantly captures anyone. Capturing a **player** requires the target to meet ≥1 vulnerability condition — **low health, sleeping, stunned, surrendered, already restrained, outnumbered, recently defeated, or failed a resistance check** — **and** a short **channel/cast** that **breaks if the kidnapper is hit, moves too far, or loses line of sight**. Capturing an **NPC** can be easier but is still **not instant against guards or combat-capable villagers**.

### 8.3 Restraints — rope vs cuffs (distinct roles)

| Restraint | Strength | Escape | Compatibility |
|---|---|---|---|
| **Rope** | Basic | Easier to break free | Broad: items tagged rope — Plant Fibers, Supplementaries, Farmer's Delight (if applicable), any `forge:rope`-style tag |
| **Cuffs** | Stronger, legal/criminal | Hard to escape | Used by guards/Blue for arrests |
| **Locked cuffs** | Strongest | Needs key, lockpick, minigame, or rescue | **Locks Reforged** compatible |

Rope leads/tethers/binds; cuffs are the law-enforcement and serious-crime restraint. Giving each distinct strength is why both exist.

### 8.4 Custody ownership + fallbacks

`CustodyRecord.owner` tracks who holds a captive: **kidnapper, guard, jail, village authority, or none** (escaped/released). Stealing a captive from a guard is itself a crime. Fallbacks when a captor logs off / dies / changes dimension:

- **NPC captive:** held in **virtual captivity** until reloaded, or an **escape/rescue chance** begins.
- **Player captive:** the real-time captivity cap (§7.2) and a guaranteed recovery path apply — **must never softlock.** The captive entity is **never deleted**; it is moved/leashed, and admin release always works.

### 8.5 Ransom (anti-exploit)

Ransom requires the victim to be **alive and actually captive**; if the victim dies, escapes, is rescued, or is jailed, the demand **fails or changes**. Anti-farming:

- **Cooldowns per victim, per family, and per village** prevent infinite extraction from the same spouse.
- **Payer chosen by relationship priority:** spouse → parent → adult child → sibling → close relative → close friend → village authority. If no valid payer exists, ransom is disabled or downgraded to a lower-value village ransom.

### 8.6 Mugging/robbery vs murder

Mugging must not become a murder-loot farm. Default: if mugging **succeeds**, the NPC pays; if they **resist and die**, they drop **only normal loot** (no bonus) and the killer takes **heavier Karma loss, Heat, witness response, and bounty eligibility**. Profession-based death drops remain available via config, but the default favors **robbery over murder**.

---

## 9. NPC Crime

> **Default posture: conservative.** Petty by default; serious crime is rare, disabled, or archetype-gated. Villages must not collapse into chaos.

### 9.1 Default NPC crime table (small crimes)

Default-enabled NPC crimes favor: **petty theft, attempted mugging, starting fights, trespassing, vandalism, food theft.** **Kidnapping, murder, arson** are **rare, disabled by default, or reserved for criminal archetypes** (§9.3).

### 9.2 Village throttles (mandatory)

- `maxActiveNpcCrimesPerVillage` — hard ceiling on concurrent NPC crimes.
- `minTimeBetweenNpcCrimes` — cooldown between NPC crimes in a village.
- NPC crime never cascades into village-destroying loops.

### 9.3 Use MCA Outlaws & Cultists (archetype-driven)

Rather than forcing all criminality onto Nitwits/Jobless, lean on MCA's existing fiction (⚠ verify these professions/archetypes exist and their IDs in the 1.20.1 branch — MCA's changelog references Outlaw/Cultist professions and a `nightOwlChance` schedule):

- **Outlaws:** higher crime chance, lower resistance to becoming Red, may already read as suspicious.
- **Cultists:** darker crimes, may kidnap villagers, generate quest hooks.
- **Guards** may treat Outlaws differently per config.

Nitwits/Jobless retain a low petty-crime chance; serious behavior belongs to the criminal archetypes.

---

## 10. Relationship, Village & Client Consequences

### 10.1 Relationship consequences (the MCA connection)

Crime must touch MCA relationships, not just abstract Karma:

- **Mug/harm a villager →** that villager loses hearts toward the player; **spouse/family lose some hearts**; village reputation drops; nearby witnesses lose trust.
- **Rescue a villager →** the rescued villager and **their family gain hearts**; village reputation rises.

This is what makes MCA: Crime feel native to MCA rather than a generic bounty mod. Heart read/modify happens through `McaCompat` (§13) — ⚠ method names are verification targets.

### 10.2 Village-level reputation (lightweight in 1.0)

Global Karma stays the main band value; **optional per-village modifiers** (§2.5) give local reputation, guard aggression, fines, and villager willingness. A player can be a hero in one town and hated in another. Expandable later.

### 10.3 Client UX (clarity is essential)

Players must understand *why* guards hate them or the mod reads as buggy/unfair. Required surfaces:

- Small **Karma/Heat HUD** (repositionable, client-toggle).
- **Jail countdown** and a **Wanted** indicator.
- Messages on: **band change**, **crime witnessed**, and **why a guard is attacking**.
- **Captive screen:** remaining max captivity, escape option, captor identity, and reason.

Name coloring uses vanilla `Style`/`TextColor`, applied **non-destructively** (never strips other mods' styling; offers a prefix-only mode for nickname mods).

---

## 11. Redemption & Economy

### 11.1 Fines/bail/surrender — see §6.

### 11.2 Amends quests (redemption path)

Via the Quests integration, criminals repair reputation through community-service quests rather than grinding trades: deliver supplies, rebuild village blocks, defend the village, rescue a captive, pay restitution, apologize to a victim, clear monsters near town. More interesting than trade-grinding and reuses existing quest machinery.

### 11.3 Victim restitution

When a fine is paid, a portion conceptually goes to the victim/family/village: grant **relationship recovery** (hearts) to the victim or their family after restitution, even with no simulated economy. Makes "pay fine" feel like repairing harm, not bribing the system.

### 11.4 Bounties (capture-first)

Bounties prefer capture: if killing paid the same as capturing, everyone kills. **Capture pays full and grants positive Karma; killing pays less or only counts when the target is violent/armed/resisting/configured deadly.** Reinforces cuffs/rope value and roleplay.

### 11.5 Optional economy compatibility (design-for, not 1.0)

Emeralds are the default currency for ransom, fines, bail, bounty, bribes, restitution. Future optional adapters: **Lightman's Currency, FTB Money, Numismatics**. Spec leaves the currency layer abstract so adapters slot in later.

### 11.6 Trials — future content only

A court/trial/testimony system is explicitly **out of scope for 1.0** to avoid bloat. The data model (ledger, resolution states, "court date" bail hook) leaves room; judges/courts/testimony are later expansion.

---

## 12. Config

### 12.1 Structure

Forge `common` + `client` TOML, registry-driven to mirror MCA's own config style (which already carries mod-compat hooks like profession-conversion maps, interaction-item blacklists, dimension blacklists, and modded-villager whitelists — ⚠ confirm exact keys).

- **common:** band thresholds; Wanted threshold; per-crime karma/heat weights; heat decay rate; karma decay rate; daily anti-farm caps; `enableKidnapping.npc` (**true**), `enableKidnapping.player` (**true**), capture-condition requirements, channel duration; restraint strengths; `enableNpcCrime` (**false** for serious; petty allowed low), `maxActiveNpcCrimesPerVillage`, `minTimeBetweenNpcCrimes`; `enableFines`/`enableBail`; `maxCaptivityRealMinutes` (**360**); jail containment mode; `protectedEntities`/`responderEntities` lists; `requireWitnessForHeat` (**true**), `unwitnessedKarmaFactor` (**1.0**); `pvpCountsAsCrime` (**false**); `raidGrace` (**true**); `redIsLegalTarget`/`allowKillingRed`; `globalCrimePropagation` (**false**); ransom cooldowns; `professionMatchingMode` (STRICT/NORMALIZED/LOOSE).
- **client:** `nameColorEnabled`, name-color mode (full/prefix-only), `showKarmaHud`, HUD anchor/offset, chat-format toggle, captive-screen toggle.
- **datapack:** crime definitions (§5.1) and punishment ladders under `data/<ns>/mcacrime/...` with a reload listener.

### 12.2 Crime profiles (documented presets)

Even if not GUI presets, the config documents recommended combinations:

- **Singleplayer / RPG:** NPC kidnap on, player kidnap on, NPC crime on, bounties on.
- **Public Server Safe:** NPC kidnap on, **player kidnap off**, short jail, high escape chance.
- **Hardcore Law:** severe penalties, guards attack Red, long sentences, physical breakouts.
- **Lightweight:** Karma + guard hostility only; no kidnapping or jail.

### 12.3 Config validation (major feature, not polish)

`/crime validate` and load-time validation must catch: invalid entity IDs, invalid item IDs, invalid tag references, negative jail durations, **Blue threshold below Grey/Red**, missing jail structures, unknown profession IDs, malformed crime JSON, impossible ransom tables, and unresolvable punishment ladders. Malformed datapack entries are skipped-with-log unless `strictJsonValidation`.

---

## 13. MCA: Reborn Integration

**All MCA access flows through one adapter — `mcacrime.compat.McaCompat` — and every symbol below is a ⚠ verification target** confirmed against the `1.20.1` branch before/during coding. This is the single most important architectural rule for surviving MCA version drift; pin to the **7.6.x** line.

Adapter surface (candidate names — verify, then keep behind the adapter):

- `boolean isMcaVillager(Entity)` — `instanceof net.mca.entity.VillagerEntityMCA` (class seen in a 1.20.1 crash stack ⚠) **or** entity-type/villager-tag check. **Never cast.**
- `UUID villagerUuid(Entity)`, `Component displayName(Entity)`, `ResourceLocation profession(Entity)` (normalize vanilla vs `mca:`).
- `int getHearts(ServerPlayer, Entity)` / `void modHearts(ServerPlayer, Entity, int)` — wraps MCA's hearts/memories/relationship calls. **Method names unverified — this is the one capability the adapter cannot live without; confirm first.**
- `boolean isGuard(Entity)` (`mca:guard` ⚠), `boolean isNitwit(Entity)` (`minecraft:none`), `boolean isOutlawOrCultist(Entity)` (⚠).
- `Optional<UUID> villageId(Entity)`, `Optional<BlockPos> homeOrVillagePos(Entity)` — for per-village reputation and trespass.
- `void setHostileToPlayer(Entity villager, ServerPlayer)` — drive aggression through MCA's own AI where exposed; otherwise attach an external targeting goal via `EntityJoinLevelEvent` (least-invasive).
- `void writeCivilRegistry(UUID villageId, Component entry)` — **if** MCA exposes a civil-registry hook (MCA changelog references a civil registry ⚠); otherwise keep crime logs in `mcacrime.dat` and expose `/crime log village`.

**Least-invasive hooks:** detection via Forge events filtered by `isMcaVillager` (no MCA code touched); menu injection via a client-side Mixin into MCA's interaction screen (the pattern MCA: Quests uses — ⚠ confirm screen class), gated behind client config, with a command fallback if the target shifts; guard response via MCA's existing guard/outlaw machinery first.

**Persistence/networking:** MCA syncs villager state via its own data manager (⚠); MCA: Crime keeps its **own** `SimpleChannel` and does **not** piggyback on MCA's tracked data. Karma/Heat sync to client is cosmetic only.

---

## 14. MCA: Quests Integration

Cleanest integration because MCA: Quests exposes a real API (⚠ confirm coordinates/symbols against its source; soft-depend via `ModList.get().isLoaded("mcaquests")`).

- **Consume events** (Forge bus, names per Quests' API package ⚠): `QuestCompleted` → award Karma; `QuestFailed`/`QuestAbandoned` → optional Karma penalty for crime-relevant quests; `QuestAccepted`/`QuestReady` available if needed.
- **Register condition types** so datapack quests gate on crime state: `mcacrime:karma {min,max}`, `mcacrime:player_band {blue|grey|red}`, `mcacrime:is_jailed`, `mcacrime:has_bounty`, `mcacrime:is_legal_target`. These slot into the Quests `all_of`/`any_of`/`not` tree.
- **Register reward types:** `mcacrime:karma {amount}`, `mcacrime:clear_heat`.
- **Crime-themed & amends quests** become pure datapack content: a `mca:guard` issues capture-bounty quests gated on band; an amends/redemption quest gated on a prior failure mirrors the Quests redemption pattern.
- **Required surface between the two mods:** (1) Crime reads MCA hearts via its **own** `McaCompat`, not via Quests; (2) Crime listens to the quest events; (3) Crime registers condition/reward types via the Quests API. **No hard dependency either direction** — each mod runs with the other absent.

---

## 15. Compatibility (optional mods)

**No hard dependency except MCA Reborn.** Two configurable entity sets plus tags drive everything:

- **Protected entities** (`mcacrime:protected` tag + config list): default MCA villagers; optionally vanilla villagers, iron golems, **owned** Recruits NPCs, Villager-Workers NPCs.
- **Responder entities** (`mcacrime:responders` tag + config list): default MCA guards; optionally Guard Villagers, Recruits, iron golems.

### 15.1 Guard Villagers

Detect via `ModList.isLoaded("guardvillagers")`. The mod's guards already attack players who harm villagers (⚠ confirm entity ID `guardvillagers:guard` and the targeting path); on a qualifying crime, set the guard's target through that same path behind an optional sub-module. Respect its own whitelist config to avoid friendly-fire. **Never import its classes at top level.**

### 15.2 Recruits (ownership/faction matters)

Recognize Recruits NPCs and their ownership/faction (⚠ confirm `recruits` mod ID, `AbstractRecruitEntity`, and the `OWNER_ID`/`GROUP`/`STATE` accessors). Rules are **not** simply "Recruits are protected":

- Harming an **owned** recruit = crime against that owner/their village.
- Attacking a recruit owned by a **Red** player may be lawful.
- **Opposing teams/factions** → PvP/faction rules apply where available.

Owned/faction NPCs respect **owner, team, faction, and PvP config**.

### 15.3 Non-MCA interaction paths (not MCA-menu-only)

MCA villagers get menu options via MCA's UI, but Recruits/Guards/Villager-Workers/vanilla villagers may have no such menu. Support **item- and keybind-based** interactions too: right-click **cuffs** to attempt arrest/kidnap, right-click **rope** to bind/tether, `/crime report` near a victim/guard, and a keybind/radial menu on compatible NPCs.

### 15.4 General compat rules

All integrations live in `@Mod`-optional sub-packages, every call `instanceof`/`ModList`/tag-guarded, every path fail-safe — a missing mod means that entity simply isn't protected/responding, never a crash.

---

## 16. Events / API (exposed to other mods)

Posted on the Forge bus so Quests, RPG/faction/economy mods, and future add-ons integrate cleanly:

`KarmaChangedEvent` · `CrimeCommittedEvent` · `CrimeWitnessedEvent` · `WantedStatusChangedEvent` · `EntityKidnappedEvent` · `EntityReleasedFromCaptivityEvent` · `PlayerJailedEvent` · `PlayerReleasedFromJailEvent` · `BountyCreatedEvent` · `BountyClaimedEvent`

---

## 17. Commands

Brigadier, permission-tiered (mirroring the Quests command style):

- **Player:** `/crime karma`, `/crime status`, `/crime payfine`, `/crime surrender`, `/crime report`, `/crime log village`.
- **Op L2 (read/debug):** `/crime query <player>`, `/crime debug villager`, `/crime bounty list`, `/crime ledger <player>`.
- **Op L3–4 (mutate):** `/crime set karma <player> <n>`, `/crime set heat <player> <n>`, `/crime jail <player> <ticks>`, `/crime release <player>`, `/crime clearheat <player>`, `/crime pardon <recordId>`, `/crime assignjail <villageId> <pos>`, `/crime reload`, `/crime validate`.

---

## 18. Technical Architecture (summary)

- **Persistence:** per-player capability (copied on `PlayerEvent.Clone`) for Karma/Heat/jail/custody refs; world `SavedData` (`mcacrime.dat`) for ledger, bounties, jail roster, custody table, per-village reputation. Survives logout/death/dimension/unload/restart by construction.
- **Networking:** Forge `SimpleChannel`; S2C sync of karma/heat/band/jail-countdown/captive-screen for display only; **no C2S trust** for state changes; every packet validates server-side, player presence, distance, and existence.
- **Data-driven definitions:** crime types and punishment ladders as JSON with Codecs; skipped-with-log on parse error unless strict.
- **Dedicated-server safety:** no client-only classes loaded server-side; all UI in the client dist; jail/captivity timers tick only in server/player tick.
- **Testing strategy:** unit tests for karma/heat math + band transitions, online-tick jail accounting across logout, false-positive filters (FakePlayer/PvP/raid/owned-NPC), datapack parse/skip, ransom payer-priority and cooldowns; manual matrix for logout/death/dimension/restart **during jail and during captivity**, kidnapping softlock checks, capture-condition/channel-break behavior, large-village performance, and load combinations (**MCA-only / +Quests / +GuardVillagers / +Recruits / +LocksReforged**).

---

## 19. Implementation Roadmap (phased, hard stop boundaries)

Build legal/persistence/safeguard systems **before** the flashy features that depend on them. Each phase ends at a **STOP** with explicit deliverables and a verification gate; do not begin the next phase until the gate passes.

### Phase 1 — Foundation
**Deliver:** `McaCompat` skeleton (villager detect/UUID/profession/hearts — verified §16); per-player Karma/Heat capability + `mcacrime.dat` SavedData; band derivation; name coloring (non-destructive); `/crime karma`/`status`; full config skeleton + `/crime validate`; `KarmaChangedEvent`/`WantedStatusChangedEvent`.
**Gate:** karma/heat persist across logout/death/dimension/restart in singleplayer **and** dedicated; band/name color correct; validate catches a deliberately broken config.
**STOP.**

### Phase 2 — Crime detection
**Deliver:** detection for harm/kill of protected NPCs via Forge events; witness/line-of-sight logic; full false-positive gate (FakePlayer/raid/PvP/context/owner/Legal-Target); crime ledger writes; `CrimeCommittedEvent`/`CrimeWitnessedEvent`.
**Gate:** the §5.2 false-positive matrix passes (lava/dispenser/FakePlayer/raid-cleave/self-defense all correctly *not* crimes); witnessed vs unwitnessed produce the §3.5 Karma/Heat split.
**STOP.**

### Phase 3 — Enforcement & jail
**Deliver:** guard hostility via MCA machinery; villager refusal/flee for Red; fines; surrender; **online-ticks jail** with containment modes, three structure-assignment modes, virtual containment, real-time captivity cap; Legal Target state; `PlayerJailedEvent`/`PlayerReleasedFromJailEvent`.
**Gate:** jail survives the full §7.1 matrix; no softlock with no jail structure present; containment vs physical-breakout both behave; offline time does not decrement sentence.
**STOP.**

### Phase 4 — Captivity & kidnapping
**Deliver:** cuffs/rope/locked-cuffs restraints; capture conditions + channel/break; custody ownership + all fallbacks; jail-vs-kidnapping legality split; ransom with payer-priority + cooldowns; mugging robbery-over-murder; `EntityKidnappedEvent`/`EntityReleasedFromCaptivityEvent`.
**Gate:** player captive can **never** softlock (cap + recovery + admin release verified); escaping kidnapping yields no Heat/bounty; ransom fails correctly when victim dies/escapes/rescued/jailed.
**STOP.**

### Phase 5 — Bounties & Blue enforcement
**Deliver:** bounty board (capture-first rewards); Blue-player law-enforcement rules tied strictly to Legal Target; bounty turn-in to guards; `BountyCreatedEvent`/`BountyClaimedEvent`.
**Gate:** Blue attacking an innocent loses Karma/becomes Wanted; capture out-rewards killing; bounty claims are idempotent.
**STOP.**

### Phase 6 — NPC crime
**Deliver:** conservative NPC crime table (petty default); Outlaw/Cultist archetype rules; per-village throttles.
**Gate:** village throttles hold under stress; no cascade/loop; serious NPC crime only from configured archetypes.
**STOP.**

### Phase 7 — Deepened compatibility & integration
**Deliver:** Quests bridge (consume events; register conditions/rewards; amends quests); Guard Villagers + Recruits adapters (ownership/faction); Locks Reforged / Plant Fibers / Supplementaries / Farmer's Delight restraint-tag compat; non-MCA item/keybind interaction paths; civil-registry integration if a hook exists.
**Gate:** all load combinations run without crash with each mod present or absent; restraint tags resolve; quest conditions/rewards function in a test datapack.
**STOP — 1.0 candidate.**

---

## 20. Risks & Mitigations (consolidated)

| Risk | Mitigation |
|---|---|
| False positives (automation/farms/environment) | `FakePlayer` filter; real-`ServerPlayer` attacker; ignore indirect/environmental deaths by default |
| PvP/raid mis-flagging | `pvpCountsAsCrime=false`, `raidGrace=true` defaults; hostile/Legal-Target check |
| Accidental Karma loss | `requireWitnessForHeat`; line-of-sight witness; unwitnessed affects Karma only |
| Casting foreign entities (legacy crash) | `instanceof`/tag/`ModList` only; never cast; adapter isolation |
| Kidnapping softlock | UUID tracking; never delete captive; cap + recovery path; admin release; pause-not-lose on unload/logoff |
| Jail/kidnapping confusion | `lawful` flag drives separate rules; escaping kidnapping never criminal |
| Jail abuse | online-ticks (no offline wait-out); death-copy (no suicide escape); containment (no walk-out) |
| Karma/fine duplication | server-authoritative; idempotent transactions; clamp deltas |
| Positive-karma farming | per-villager/village/player daily caps; diminishing returns; defense validity |
| Instant redemption of serious crime | ledger consequences persist past Karma recovery |
| Name/chat conflicts | client toggle; non-destructive styling; prefix-only mode |
| Large-village performance | event-driven detection (no per-tick scans); throttled radius-limited witness checks; lightweight per-village data; disableable expensive behaviors |
| MCA version drift | pin 7.6.x; all internals behind `McaCompat`; disable-with-log if symbols missing |
| NPC-crime chaos | conservative defaults; per-village throttles; no cascades |
| Existing worlds lack jails | three jail-assignment modes; escort/holding fallback |
| Chunk-unload prisoners | `SavedData` virtual containment; no forced chunk-loading |

---

## 21. Verification Checklist (confirm against actual sources before/while coding)

Treat every item as blocking for the phase that depends on it. Keep all confirmed symbols behind adapters.

**MCA Reborn (`1.20.1` branch):**
- [ ] `net.mca.entity.VillagerEntityMCA` superclass + full set of villager entity-type IDs (for `isMcaVillager`).
- [ ] **Hearts/memories/relationship class + read/modify method signatures** — the one capability the adapter cannot live without.
- [ ] Guard/Outlaw/Cultist profession IDs and the `nightOwlChance` schedule.
- [ ] Village/reputation persistence classes and per-village/per-player storage shape.
- [ ] Interaction-screen class (for the Mixin menu injection) + a command fallback.
- [ ] Civil-registry hook existence (else use own log + `/crime log village`).
- [ ] The `/mca` Brigadier command class/subcommands (avoid `/crime` collisions).
- [ ] Exact config key names to mirror MCA's style.

**MCA: Quests:**
- [ ] API jar/Maven coordinates and `McaQuestsApi` symbol.
- [ ] Exact event class names/package (`QuestCompleted`/`QuestFailed`/`QuestAbandoned`/`QuestAccepted`/`QuestReady`).
- [ ] Condition/reward registration entry points.

**Guard Villagers:**
- [ ] Mod ID `guardvillagers` and entity ID `guardvillagers:guard`.
- [ ] The targeting path used when a player harms a villager; its whitelist config.

**Recruits:**
- [ ] Mod ID `recruits`, `AbstractRecruitEntity`, `OWNER_ID`/`GROUP`/`STATE`/`FOLLOW_STATE` accessors, faction/team data manager.

**Restraint-tag mods:**
- [ ] Locks Reforged integration points for locked cuffs.
- [ ] Rope-tag sources: Plant Fibers, Supplementaries, Farmer's Delight (confirm applicable items/tags).

**Forge 1.20.1:**
- [ ] Confirm event signatures in use (`LivingHurtEvent`, `LivingAttackEvent`, `LivingDeathEvent`, `AttackEntityEvent`, `PlayerInteractEvent.EntityInteract`, `BlockEvent.BreakEvent`, `PlayerEvent.Clone`, `AttachCapabilitiesEvent<Entity>`) and `net.minecraftforge.common.util.FakePlayer`.

---

## 22. Licensing (check before release — not a blocker for design)

MCA Reborn is GPLv3, and MCA: Crime links MCA internals, so it is effectively GPLv3 too. Confirm license compatibility for any bundled/optional-integration code and publish under a compatible license. Keep this as a release-gate checklist item, not an architectural constraint.

---

### Bottom line
With Heat, Legal Target state, the crime ledger, capture conditions, custody ownership, anti-farming caps, jail block-protection, the jail-vs-kidnapping split, ransom cooldowns, and relationship/village consequences all specified above, MCA: Crime is no longer "mug/kidnap villagers" — it is a full village law-and-crime framework with clean separation between **reputation (Karma)**, **current wanted status (Heat)**, **lawful authority (Legal Target)**, and **captivity type (lawful vs unlawful)**. Build the legal/persistence/safeguard spine first (Phases 1–3); the flashy features (Phases 4–6) depend on it being solid.
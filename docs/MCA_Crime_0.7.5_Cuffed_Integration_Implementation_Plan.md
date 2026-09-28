# MCA: Crime 0.7.5 — full native Cuffed integration: implementation plan

Planning-only document. **No repository file was modified and no build, test or tool was run in this
job.** Every command in section 9 is *proposed*. Evidence citations are `path:line`.

Lines:
- Baseline (Forge 1.20.1): `/home/otectus/Projects/MCACrime`, branch `feature/reputation-0.6.0` @
  `d5b738df7241b216f327597f640f8955c1c795f2` (verified by `git log -1`), `mod_version=0.7.4`.
- Port (NeoForge 1.21.1): `/home/otectus/Projects/1.21.1 Ports/MCACrime_1.21.1`, head `eed3b4e`,
  `mod_version=0.7.4`.
- Requirements source: `/home/otectus/Projects/MCACrime/docs/MCA_Crime_Cuffed_Full_Integration_Plan.md`
  (1170 lines, read in full). Referred to below as **the specification**.
- Cuffed reference (read-only): `<scratchpad>/cuffed` @ `48a33650`. Referred to as **upstream**.

Release numbers fixed by the user: `mod_version=0.7.5`, world schema **15**, network protocol **"15"**,
on both lines.

---

## 1. Summary and approach

### 1.1 What changes

MCA: Crime absorbs the whole functioning Cuffed feature set as native code. One physical-restraint
engine replaces MCA: Crime's single-slot restraint enum, its capture channel, its cuff-escape path,
its kidnapping teleport tether, its NPC leash and its wrist-only rendering. MCA: Crime keeps and
extends everything legal: cases, sentences, witnesses, bounties, ransom, property, facilities,
occupations, masks and its companion integrations.

Three items are added to the registry per Appendix A, minus the two canonical remappings, plus the
blocks, block entities, entities, restraint definitions, enchantments, effects, recipe serializers,
sounds, particle, stats and tags that make them work. Native lockpicking becomes a first-class
server-owned mini-game that needs neither Cuffed nor Locks Reforged.

One feature in this release is **not** from Cuffed: the user scoped in capital sentencing for killing
a guard, carried out only as a deliberate act at a guillotine by a player or an on-duty guard, behind
its own config group. Section 3.19 specifies it and section 10.7 records the decision.

### 1.2 What is removed

The legacy overlapping mechanics (full list in section 5): `captivity/RestraintType` as an authority,
`CaptureChannel(s)`/`CaptureService`/`CaptureTicker`/`CaptureInteractHandler` in their current roles,
`CuffEscapeService`, `CuffLockProgress`, `CustodyConfine`, `enforcement/EscortRestraint`,
`enforcement/RestraintPolicy`/`RestraintHandlers` in their "any restraint blocks everything" form,
`RestraintVisual*`, `client/render/RestraintWristLayer`, `compat/locksreforged/CuffLockPickingMenu`
and the `LocksReforgedBridge.openCuffs` path, plus their config keys, packets and tests.

### 1.3 What is preserved

- The two protected item textures, byte for byte:
  `src/main/resources/assets/mcacrime/textures/item/restraint_cuffs.png` =
  `df1a7d30af0e4795cf706c94a4b37036dd748c4f9c750b793550daeb6f6997f4` and
  `restraint_locked_cuffs.png` = `b0a86e25652e6d1acc7b2d1cf58a4be73b9e3bc4218760917337e324fd7d084f`
  (both re-verified today by `sha256sum` against the specification's table, section 3.1).
- Every registry id that already exists. No item, block, POI, menu, profession or effect id is
  repurposed or removed.
- Compatibility invariants: mod id `mcacrime`, SavedData name `mcacrime`
  (`state/world/CrimeWorldData.java:79`), the whole migration ladder and the `__future` quarantine
  (`state/world/CrimeWorldData.java:82-85`, `frozen()` at `:324`), the public `McaCrimeApi` surface
  (deprecated projections allowed, contradictions not).
- All existing law, mask, economy, family, bounty, property, occupation and facility behaviour and
  their tests.

### 1.4 Physical restraint versus legal custody (specification section 6)

Two independent state machines with one bridge service:

- **Physical**: what is on the body, what it is tied to, what device holds it. Owned by
  `restraint/`, `tether/`, `detention/`. Knows nothing about cases or sentences.
- **Legal**: who is in custody, under what authority, for which case, for how long. Owned by the
  existing `captivity/`, `enforcement/`, `jail/`, `ransom/`, `bounty/`.
- **Bridge**: `restraint/CustodyTransitionService` is the only class that translates a committed
  physical event into a legal transition and vice versa. It is the only place that may call
  `CustodyService.capture*`/`release` from the physical side.

Consequences enforced by tests: removing one restraint is not a release; opening a cell door is not
a pardon; escaping a kidnapper files no jailbreak and costs the victim no Heat; a self-applied hood
is not a kidnapping; two successive captures of the same subject do not accept each other's packets.

### 1.5 State-ownership table

| State | Owner | Persistence | Client copy |
|---|---|---|---|
| `PhysicalRestraintState` (head/arms/legs per subject) | `state/world/CrimeWorldData` table `physicalRestraints` | world `mcacrime.dat`, schema 15 | bounded S2C snapshot, render-only |
| `AppliedRestraint` instance (item snapshot, durability, applier, provenance, revision) | inside `PhysicalRestraintState` | same | durability + definition id only |
| `TetherRecord` (chain/anchor/escort) | `CrimeWorldData` table `tethers` + `tether/TetherIndex` (transient reverse index) | world | holder/anchor refs for rendering |
| `DetentionRecord` (pillory/guillotine/bunk occupancy) | `CrimeWorldData` table `detentions` | world | occupancy + pose |
| `LockRecord` (lock identity, binding revision, reinforcement, target) | `CrimeWorldData` table `locks` | world | locked/reinforced booleans only |
| Block-side lock handle | `LockableBlockEntity` stores **only** the `lockId` | chunk | block state `OPEN`/`LOCKED` |
| Padlock entity | `PadlockEntity` stores **only** the `lockId` | entity NBT | synched booleans |
| Key binding (`lockId` + `bindingRevision` + name) | item NBT (baseline) / `DataComponentType` (port) | stack | n/a |
| Struggle work, lockpick phase, frisk session | `restraint/SessionRegistry` (transient, server-only, capped) | none | progress only |
| Legal custody (`CustodyRecord`, now with `custodyId` + `generation`) | `CrimeWorldData` table `custody` (existing) | world | existing `CaptiveStatusS2CPacket` |
| Nickname / privacy policy | `PlayerCrimeData` (players) and `CrimeWorldData` `identities` (villagers) | player NBT / world | display name only |

Rule: **exactly one durable owner per fact.** No Forge capability and no entity persistent-data blob
carries an authoritative copy of physical restraint state. Rationale in section 3.2.

### 1.6 Packet contract

Built on `network/CrimeNetwork` (`PROTOCOL_VERSION` at `network/CrimeNetwork.java:41`, bumped to
`"15"`), `network/ServerPacketGuard`, `network/RequestBudget`, `network/PacketBounds`.

- Every C2S action packet carries **intent only**: `{sessionId(long), targetHandle, slot(enum),
  inputKind(enum), inputSeq(int), boundedScalar}`. No durability delta, no actor UUID, no outcome,
  no block position that the server has not already bound to the session.
- Identity comes from the connection (`ServerPacketGuard.accept` resolves the sender). Every handler
  additionally validates: session freshness, dimension, distance, line of sight where relevant, the
  held item, target generation/revision, menu ownership and the allowed action.
- S2C snapshots carry render/interaction data only: slot, definition id, durability fraction,
  tether/device references, animation flags. Never lock secrets, never another player's inventory,
  never private case evidence. Only the authorised frisker receives searchable contents.
- Full state on tracking start, reconnect and respawn; deltas on change; explicit removal messages;
  revision-ordered.

### 1.7 Milestone order

M0 evidence and provenance → M1 state model and migration → M2 restraints and legacy removal →
M3 lockpicking and locks → M4 transport and detention → M5 frisking and prison content →
M6 enchantments, integrations, dormant completion and capital sentencing → M7 release candidate.

Sequencing rule from the specification, section 20: finish state ownership before interaction
handlers; finish transactional inventory and locks before exposing valuable confiscation and safe
storage; finish the physical/legal boundary before wiring bounties, ransom and death outcomes;
finish per-slot policy before combat/magic compatibility.

---

## 2. Verified assumptions

Marked **[me]** where I verified it in this job, **[scout]** where I rely on a recon report that
carries the `file:line`.

### 2.1 Baseline

1. **[me]** Head is `d5b738df7241b216f327597f640f8955c1c795f2`, identical to the commit the
   specification pinned (`git log -1`).
2. **[me]** `mod_version=0.7.4` (`gradle.properties:39`); world schema 14
   (`state/world/CrimeDataMigrations.java:93-94`); network protocol `"14"`
   (`network/CrimeNetwork.java:41`).
3. **[me]** Both protected textures still hash exactly to the specification's values (`sha256sum`).
4. **[me]** Registration order in the `McaCrime` constructor is blocks → items+tab → POI →
   professions → entities → effects → recipes → menus, with comments explaining each ordering
   constraint (`McaCrime.java:57-72`). Config specs are registered first (`:46-47`); capability
   registration is a mod-bus listener (`:56`).
5. **[me]** `activity/` is the existing bounded control-claim mechanism:
   `CrimeActivityRegistry.claim/renew/release/permits` with a `DEFAULT_LEASE_TICKS = 40`
   (`activity/CrimeActivityRegistry.java:52-307`); `CrimeActivityView.Kind` already has
   `HOLD, CHALLENGE, ESCORT, ARREST, CUSTODY, PURSUIT, REACTION, THIEF_ACTION, MUGGING, …`
   (`activity/CrimeActivityView.java:39-59`); `CrimeActivityOperation` is
   `WORK_START, REST_TRAVEL, REACTION_LOCK, DISPLAY_TOOL, SOCIAL_WANDER`
   (`activity/CrimeActivityOperation.java:16-38`). The specification never names `activity/`; it is
   nevertheless the required route for "bounded, reversible control claims" (section 14.2).
6. **[me]** Today's physical restraint keys live under the `kidnapping` config group, not under any
   `restraints`/`transport`/`locks` group: `captureChannelMultiplier*`, `restraintEscapeChance*`
   (locked cuffs default **0.0**), `captiveTetherBlocks`, `escapeWorkTicks*`,
   `cuffEscapeRequiresLockpick` (`McaCrimeConfig.java:904-956`).
7. **[me]** `MODMAP.md` has `<!-- MODMAP:AUTO:END … -->` at line 186; everything below is
   hand-maintained and must be preserved.
8. **[scout]** `captivity/` is 20 files / 2,535 lines; `CustodyRecord` has **no id field, no item
   snapshot, no durability, no per-slot state, no applier, no generation**; `CustodyView.custodyId`
   and `linkedCaseId` are hard-coded `Optional.empty()` (`api/McaCrimeApi.java:255-256`).
   → the stable custody identity the specification asks for does not exist and must be created.
9. **[scout]** `CustodyConfine` is player-only; NPC captives are held by a vanilla leash plus the
   `virtual` flag and `CustodyService.tickNpcCaptives` (`captivity/CustodyService.java:734`).
10. **[scout]** There is **no native lockpicking**: `CuffEscapeService.usesMinigame` requires
    `LocksReforgedBridge.installed()` (`captivity/CuffEscapeService.java:20`), and without Locks
    Reforged `restraintEscapeChanceLockedCuffs = 0.0` means locked cuffs have no self-escape at all.
    Removing the Locks cuff path removes the *only* implementation, not a preference.
11. **[scout]** No custom `SoundEvent` and no `ParticleType` is registered anywhere;
    `audio/CrimeSounds.java:12-28` documents vanilla-only as a deliberate choice. Appendix A's six
    sounds and `blood_drip` particle are therefore entirely new registry surface.
12. **[scout]** There are **no datagen providers** (`grep GatherDataEvent` empty; MODMAP line 166).
    All models, blockstates, recipes, tags, loot tables and lang keys are hand-written.
13. **[scout]** Contraband discovery lives in `enforcement/Contraband*` and `item/contraband/*`, not
    in `property/`; `property/` is gated by `townstead.propertyLaw`, **off by default**. Frisking
    must therefore not be built on `property/` alone.
14. **[scout]** `mcacrime.mixins.json` is `required:true`, `defaultRequire:1`, vanilla targets only,
    7 common + 1 client mixin; both configs are listed in the jar manifest `MixinConfigs`
    (`build.gradle:328`); `checkJarContents` (`build.gradle:357-383`) fails on shaded companion /
    Architectury / MCA classes.
15. **[scout]** `ServerPacketGuard.accept` gives sender resolution, budget and a server-thread hop
    only; distance, dimension, line of sight, menu ownership and generation are each packet's own
    responsibility today, with no shared helper. A shared helper is part of this work.
16. **[scout]** `CrimeWorldData.frozen()` is applied per mutator at ~40 call sites; nothing
    mechanically enforces it, so every new physical mutator must add its own guard. A test is
    proposed for this (section 9).

### 2.2 Upstream (Cuffed)

17. **[scout]** Appendix A's counts are exact: 37 items, 15 blocks, 5 block entity types, 4 entity
    types, 10 restraint definitions, 6 enchantments, 2 effects, 7 recipe serializers, 6 sounds, 1
    particle, 19 statistics, 43 recipe files (`init/*`, `R:data/cuffed/recipes/`).
18. **[scout]** The `999` durability in item registration is a placeholder overridden by config:
    handcuffs **40**, fuzzy **30**, shackles **15** (`items/HandcuffsItem.java:24-27`,
    `FuzzyHandcuffsItem.java:70-73`, `ShacklesItem.java:26`).
19. **[scout]** Leg shackles are **self-consistent** upstream: `AllowMovement()` is true
    (`ShacklesLegsRestraint.java:112-114`) *and* the blocked-key override blocks only jump and
    sprint (`:135-142`). Only `en_us.json`'s description disagrees.
20. **[scout]** The safe is hard-coded to **27** slots (`SafeBlockEntity.java:173-175`); the
    `SAFE_SLOTS = 36` config property is never read. The specification's "36 source-derived default"
    is a design choice, not an observation.
21. **[scout]** Upstream **does** gag outgoing text chat for a duck-tape head restraint
    (`ModClientEvents.java:119-156`), client-side only. The specification's matrix row "no automatic
    block on text chat" is prescribing new behaviour.
22. **[scout]** All three `DUCK_TAPE_ON_LEGS_*` config keys are dead; the leg class reads the arm
    keys (`DuckTapeLegsRestraint.java:239,243,247`).
23. **[scout]** Restraints are attached to `Player` only (`event/ModServerEvents.java:82-86`);
    chains/anchors apply to any `LivingEntity` through `mixin/LivingEntityMixin.java:32` gated by
    the `cuffed:chainable_entities` tag.
24. **[scout]** Upstream ships `assets/cuffed/sounds.json` (six events) but has no advancements, no POI, no `tray` loot table, and
    one menu type (`frisking_menu`).
25. **[scout]** Licence discrepancy confirmed: `LICENSE` is GPL-3.0 verbatim, `README.md:9` carries
    a GPL-3.0 badge, and `gradle.properties` declares `mod_license=All Rights Reserved`, which
    expands into `META-INF/mods.toml:3` of every published jar. **No credits file of any kind
    exists.** The seven poster textures and the fuzzy-cuff worn texture carry no attribution; the
    fuzzy definition is labelled "Supporter only restraints" (`init/ModRestraints.java:40`) and its
    lang description says it is given exclusively to Discord supporters.

### 2.3 Port

26. **[scout]** The port has class-for-class parity with the baseline across `captivity/`,
    `enforcement/`, `client/`, `item/`, `mixin/`. `CuffLockProgress` is platform-neutral pure logic.
27. **[scout]** Platform differences that matter here: capabilities → **data attachments**
    (`state/CrimeAttachments.java`); `SimpleChannel` → **`CustomPacketPayload` + `StreamCodec`**,
    with `CrimeClientPayloadRouter` mandatory because common code may not name a client class;
    `SavedData` save/load take a `HolderLookup.Provider`; item NBT → **`DataComponentType`** (the
    port registers none today); enchantments are **data-driven JSON**, not Java registry objects;
    **no block entities exist yet**; **no dispenser behaviour is registered**; **no custom sounds,
    particles or stats**; mixins are `remap = false` with no refmap and are declared by `[[mixins]]`
    in `neoforge.mods.toml`; `check_mod.py` and `modmap.py` are forbidden; GameTests exist under
    `gametest/` and are excluded from the jar.
28. **[scout]** Both lines are at schema 14 and protocol `"14"`; the port keeps `mod_version` in
    step with the baseline and lands one commit per baseline release.

### 2.4 Assumptions I could not verify and that must be checked before coding

- The exact 1.21.1 API names for data-driven enchantment definition and for extra entity spawn data
  (`IEntityWithComplexSpawn`). Proposed check: `find_api.py --project "<port dir>" --class
  Enchantment --symbol enchantment` and `--class IEntityWithComplexSpawn`.
- `MovementInputUpdateEvent` availability on both lines (used below to avoid a client input mixin).
  Proposed check: `find_api.py --class MovementInputUpdateEvent --symbol getInput` on each project.
- Whether `/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh` is the sanctioned Linux substitute
  for the port's Windows `gradlew-quiet.ps1` command (port CLAUDE.md prescribes the Windows form).
  **Being confirmed empirically now**: a verifier is running the Linux script against the port as
  this plan is amended, so the first verifier run settles it; no planning step depends on the answer,
  and the remaining bullets here stay unverified.
- Upstream tray nutrition arithmetic and the fork/spoon crumble stage→removal transition were only
  partly traced by the scout. Both must be read before M5 (`items/TrayItem.java`,
  `blocks/entity/TrayBlockEntity.java`, `entity/CrumblingBlockEntity.java:96-120`).

---

## 3. Decisions taken (technical)

Each decision states the evidence and the rejected alternative.

### 3.1 Item and definition ID scheme

**Decision.** Registry ids follow Appendix A A.1 exactly, with the three mandated exceptions:
`mcacrime:restraint_cuffs` = Shackles family, `mcacrime:restraint_locked_cuffs` = Handcuffs family,
`mcacrime:restraint_rope` = hidden legacy conversion carrier. `duck_tape` is the path, "Duct Tape"
the display name. No `mcacrime:handcuffs` or `mcacrime:shackles` item is registered.

Restraint **definition** ids are a separate namespace and do use the Cuffed words:
`mcacrime:handcuffs_arms`, `handcuffs_legs`, `shackles_arms`, `shackles_legs`, `fuzzy_handcuffs`,
`duck_tape_arms`, `duck_tape_legs`, `duck_tape_head`, `bundle`, `pillory` — ten, matching Appendix A.
A definition names its item, never the reverse.

*Evidence*: specification section 3.2; the two protected textures are already attached to those two
item ids (`assets/mcacrime/models/item/restraint_{cuffs,locked_cuffs}.json`).
*Rejected*: registering new `handcuffs`/`shackles` items and deprecating the old ones — loses every
existing stack, breaks `data/mcacrime/mcacrime/fence_prices/vanilla.json:11-13` and the item tags,
and is forbidden by the specification.

### 3.2 Where restraint state lives

**Decision.** `state/world/CrimeWorldData`, in a new `physicalRestraints` table keyed by subject
UUID, for **both players and villagers**. No Forge capability, no entity persistent data, no second
copy. A transient `restraint/RestraintIndex` provides reverse lookups (by holder, by anchor
position, by device) and is rebuilt at load.

*Evidence*: (a) `CustodyRecord` is already world-keyed by UUID for both players and NPCs
(`captivity/CustodyRecord.java:26-65`), so villagers already have durable per-subject rows there;
(b) MCA villagers are not ours and may be despawned/reloaded by MCA — world data survives entity
unload, which matters for `npcCaptiveVirtualizeWhenUnloaded`; (c) `frozen()` future-schema
protection and the `reserved` forward-compat channel already exist there
(`CrimeWorldData.java:284,324`); (d) the specification section 6.1 requires exactly this.
*Rejected*: upstream's `RestrainableCapability` on `Player`
(`cap/RestrainableCapability.java`, attached at `event/ModServerEvents.java:82-86`) — players only,
a second persistence root, and its `orElseGet(RestrainableCapability::new)` accessor
(`api/CuffedAPI.java:326-328`) silently writes to a throwaway object.
*Rejected*: `PlayerCrimeData` — player-only and copied on death, which would duplicate restraints.

Definitions are **immutable singletons with explicit factories**; `AppliedRestraint` is a separate
mutable-per-instance record. Deserialisation constructs a new instance and never hands out the
registry object (upstream's `RestraintAPI.getNewRestraintByKey` defect). A two-subject
save/load isolation test proves it.

### 3.3 How NPC restraint replaces the leash path

**Decision.** `tether/TetherService` owns every physical hold. `CustodyConfine`'s player teleport
and the NPC vanilla leash are both deleted. Guard escort and kidnapper holds become `TetherRecord`s
with a `kind` of `ESCORT`, `CHAIN`, `ANCHOR` or `LEGACY_HOLD`. NPC movement suppression is done
through an `activity/` claim of `Kind.CUSTODY` or `Kind.ESCORT`, never `setNoAi(true)`.

`CrimeActivityOperation` gains two values: `VOLUNTARY_MOVEMENT` (a leg restraint or a device denies
it) and `ITEM_ACTION` (an arm restraint denies it). Existing consumers that switch over the enum
must be revisited — it is consumed through `permits(...)` so additions are additive.

*Evidence*: `activity/CrimeActivityRegistry.java:95-307` already offers exactly the lease/generation
semantics required; `NpcReleaseEffects` already orders `clearLeash → releaseControl →
clearDistraction → notifyFamily` (`captivity/NpcReleaseEffects.java:46-57`) and has no "remove the
entity" step.
*Rejected*: keeping the leash for NPCs and adding chains only for players — two engines, which the
specification forbids, and the leash cannot express anchors or tension damage.

### 3.4 The restriction matrix is the authority

**Decision.** `restraint/RestrictionResolver` composes every active definition plus any detention
into one immutable `RestrictionPolicy` record of **action-typed** booleans:
`mineBlocks, useItem, attack, interactEntity, interactBlock, dropItem, mutateInventory,
swapOffhand, changeHotbar, voluntaryMovement, jump, sprint, steerVehicle, dismount, obscureVision,
voiceGag`. Composition is "most restrictive wins", with an explicit allow-list of escape/care
actions that no policy may cancel: struggle input, configured self-escape, status/help screens,
chat, and externally provided care.

The matrix implemented is the specification's section 7.1 table, i.e. **leg shackles allow walking**
and block jump/sprint (which is also what upstream's code does, section 2.2 point 19), and the head
slot does **not** gag text chat by default (a deliberate departure from upstream, section 2.2 point
21; exposed as `restraints.definitions.headTapeMufflesTextChat`, default false).

*Rejected*: upstream's `RestrainedEffectInstance` packed-amplifier encoding
(`effect/RestrainedEffectInstance.java:227-243`) — it loses its packing across save/load, it is
re-applied per tick in a branch that checks the wrong attribute (`:209`), and a `MobEffect` is the
wrong owner for an authorisation decision.
*Rejected*: upstream's blocked-key-code lists — they are client-side, name foreign mods' keybinds by
string, and are unenforceable on the server.

Enforcement is server-first: Forge events where they exist (`AttackEntityEvent`, `RightClickItem`,
`RightClickBlock`, `LeftClickBlock`, `EntityInteract`, `EntityInteractSpecific`,
`BlockEvent.BreakEvent`, `EntityMountEvent`, `ItemTossEvent` — the set already handled by
`enforcement/RestraintHandlers.java:86-152`, extended), attribute modifiers for movement/attack
speed, and **three** narrowly scoped vanilla-only mixins (section 4, M2 step 9). Client-side input
suppression exists only to make the block feel immediate and is never the enforcement.

### 3.5 Escape, struggle and lockpick model

**Decision.** The server owns all work.

- **Struggle.** C2S `RestraintStruggleC2SPacket{sessionId, slot, inputKind(ATTACK|USE|LEFT|RIGHT),
  inputSeq}`. The server validates: the subject is the sender, that slot holds a breakable
  definition, the input alternates against the last accepted input, at least
  `restraints.escape.minWorkIntervalTicks` (default 4) have passed, and a per-player budget is not
  exhausted. It then rolls the Unbreaking-scaled chance (base 0.5, upstream's
  `((invert01(d/3)*0.7)+0.3)*0.5` with `d = level/3`) and decrements durability by one, on the
  server only. Cooldown is stored and **decremented** (upstream never decrements `breakCooldown`,
  `restraints/custom/HandcuffsArmsRestraint.java:264,288`).
- **Hold-to-struggle** accessibility resends the same bounded packet at the same server-limited
  rate; ergonomics change, effective speed does not.
- **Lockpick.** `lockpick/LockpickSession` is server-created and bound to actor, target identity,
  target revision, dimension and the exact pick stack. Meter parity parameters are kept: start 30,
  win 40, fail 0, per-target progress/speed from the specification's section 8 table, acceptance
  window `target-10°` to `target+5°`. The frame-driven drain is reinterpreted as a **tick** drain of
  `(phase+1) * speedIncrease / 200 * 20 / 20` per tick with the constant exposed as
  `lockpicking.drainPerTickDivisor` (default 200, documented as a tick reinterpretation and
  explicitly not claimed to be frame-identical). The server chooses each phase target and accepts at
  most one bounded-angle attempt per phase after `lockpicking.minAttemptIntervalTicks`.
  Client packets are `LockpickAttemptC2SPacket{sessionId, phase, angleMilliDegrees}` and
  `LockpickCancelC2SPacket{sessionId}`. There is no success packet.
- Sessions cancel on death, logout, dimension change, range loss, target removal/replacement,
  relevant damage, item change, custody replacement and menu close. A stale success cannot affect a
  replacement target because the session pins `(lockId, bindingRevision)`, not coordinates.

*Evidence*: upstream's exploit surface is confirmed at `packet/LockpickRestraintPacket.java:241-246`
and `api/CuffedAPI.java:130,162,194` (client names both victim and actor, nothing validated) and at
`restraints/base/AbstractRestraint.java:301-307` (raw client durability delta).
*Rejected*: porting the client-authoritative screen contract with added checks — the specification
and the evidence both require the outcome to be server-computed.

### 3.6 Transport arbitration

**Decision.** `tether/TransportArbiter` resolves, once per subject per tick, exactly one authority
in the specification's order: occupied detention device → seat/mount → close escort → chain tension
→ ordinary movement filtered by limb restrictions. Lower-priority relationships are marked
`suspended` in their record, not deleted, so a device release restores the chain rather than
dropping it. Close escort uses collision-aware `moveTo`-free correction (bounded velocity nudge plus
NPC navigation), never a per-tick teleport, and validity is checked **before** any correction
(upstream reverses the order at `cap/RestrainableCapability.java:113-126`, making its own 3.5-block
break condition unreachable).

`activity/` provides the claim: the arbiter refuses to act on a subject whose claim generation has
changed, which is how guard reassignment and death hand over cleanly.

### 3.7 Lock identity and key lifecycle

**Decision.** Server-created `UUID` lock identities held in `CrimeWorldData.locks`, each with a
`bindingRevision`. Blocks and padlock entities store only the id. A key stack stores
`{lockId, bindingRevision, name}`; a copy inherits both, a reset/rekey increments the revision and
invalidates every old copy without touching coordinates. Key rings hold a bounded list of those
triples (`locks.maxKeysPerRing`, default 16); reducing the configured capacity rejects additions but
never erases keys. Ring recipes either reject an ambiguous multi-ring input or process every input
losslessly — no recipe may consume several rings and return one key (upstream issue #29).
`mcacrime:keys` (the existing tag, `data/mcacrime/tags/items/keys.json`) stays a *cuff-family* tag
and is explicitly **not** a master-key tag for block locks.

Restraint keys are a different concept from block keys: `handcuffs_key` opens the handcuff family
(arms, legs, fuzzy), `shackles_key` opens the shackle family. Identity comparison is on the `Item`
singleton.

*Evidence*: upstream keys are already UUID-bound (`items/KeyItem.java:108-110`), so issue #14 does
not apply; `KeyRingItem.getBoundIdIndex` compares UUIDs with `==` (`:183`) and must not be copied.

### 3.8 Frisking transaction model and the contraband seam

**Decision.** Do **not** port `FriskingContainer`. The frisking screen is a **read-only projection**
plus an explicit transfer packet:

- `menu/FriskingMenu` is a registered `MenuType` whose slots are all `mayPlace=false`,
  `mayPickup=false`; `quickMoveStack` returns empty; every vanilla click type is rejected
  server-side with no mutation.
- Confiscation is `FriskTransferC2SPacket{sessionId, sourceSlot, expectedRevision, count}`. The
  server runs one `frisk/FriskTransaction` with a unique transfer id, validating the six conditions
  of specification section 11.2, then debits the source and credits the destination once and writes
  a receipt.
- `stillValid` re-checks liveness, dimension, reach, restraint condition and custody generation
  every tick.

Legal seam: a **lawful** search routes discovered items through the existing
`enforcement/ContrabandSearchService.search` discovery gate and stores seizures in
`state/world/PropertyEscrow` with a `PropertyReceipt`; a **criminal** extraction records theft
through `property/PropertyTheftService` and `CrimeWorldData.reserveTheft/finishTheftReservation`
(`CrimeWorldData.java:1499-1502`) and files witnessed theft. Ordinary `possessions_box` storage
stays available and is not an escrow bypass.

*Evidence*: upstream's container returns `stillValid() == true`, exposes a `clearContent()` that
wipes the subject's inventory, and `removeItem` ignores `count`
(`inventory/FriskingContainer.java:69-102,130-142`).
*Note*: because `property/` is Townstead-gated and off by default (scout, section 13), frisking
depends on `enforcement/Contraband*` + `PropertyEscrow`, which are **not** gated; property-law
attribution is an enhancement applied only when that subsystem is on.

### 3.9 Prison devices and facility/cell integration

**Decision.** Pillory, guillotine, bunk, safe, cell door and tray become real block entities under
`block/entity/`. Occupancy lives in `CrimeWorldData.detentions` keyed by device `GlobalPos`, with a
back-reference on the subject — **not** on the player entity as upstream does
(`mixin/PlayerMixin.java:76-79`), because villagers must be detainable and chunk unload must not
look like destruction. Closing a pillory is an atomic claim: two simultaneous closers, one success.
Lifecycle cleanup (stale occupant release) is **outside** the breakout toggle (upstream nests it
inside `ALLOW_BREAKING_OUT_OF_PILLORY`, `mixin/PlayerMixin.java:168-178`).

Reinforced materials become a configurable palette in `jail/CellBlueprint`; existing temporary cells
finish under their saved blueprint and journal (`pendingCellRestorations`) and are never rewritten
on upgrade. Player-built permanent prisons are untouched by cleanup.

Guillotine execution is a **single attributed server action** through the normal damage pipeline —
never `kill()` plus `Float.MAX_VALUE` damage (upstream's dangling-else at
`blocks/entity/GuillotineBlockEntity.java:308-313`). Drops, custody closure, property recovery and
bounty evaluation happen only after confirmed death, and the pending-chop state is persisted so an
unload inside the five-tick window cannot silently cancel or double-run it. A guillotine may take a
life only while `detention/ExecutionAuthorization` holds a pending-execution record naming that
subject, that device and that actor (section 3.19, M4.10); with no such record the device releases or
refuses, never kills.

### 3.10 Enchantments and effects

**Decision (baseline).** Six enchantments registered through a new
`enchantment/CrimeEnchantments` `DeferredRegister`: `imbue`, `famine`, `shroud`, `exhaust`,
`silence`, `buoyant`; max level configurable per `enchantments.maxLevel*`, default 1. Effects are
applied by our services, not by the enchantment classes. Imbue is rewritten: recipients
de-duplicated per subject, the budget computed once outside the distribution loop, a reentrancy
guard flag on the damage path, level clamped, non-finite input rejected, and the transferred damage
attributed to a new `mcacrime:imbue` damage type naming the captor.

Two mob effects: `mcacrime:restrained` is **display-only** (icon + tooltip; no attribute logic, no
packed amplifier) and `mcacrime:wounded` carries the max-health and movement modifiers with a
configurable floor (`wounds.minimumMaxHealth`, default 2.0 hearts) — and **no damage on apply or
remove** (upstream deals 1 magic damage in both `addAttributeModifiers` and
`removeAttributeModifiers`, `effect/WoundedEffect.java:50,67`, which can kill the patient).

### 3.11 Sounds and particles policy

**Decision.** Register the six `SoundEvent`s and the `blood_drip` `ParticleType`, in new
`audio/CrimeSoundEvents` and `entity/particle/CrimeParticles` `DeferredRegister`s, and amend
`audio/CrimeSounds`' javadoc: vanilla sounds remain the rule for *law and social* moments; device
and restraint moments get named events. Ship an MCA: Crime-authored `assets/mcacrime/sounds.json` (upstream ships its own declaring all six
events, but none of its `.ogg` files is carried over, so ours is written fresh).

Because the six `.ogg` files are authored original audio under the same registry ids (section 10.1)
and may land after the code that names them, `CrimeSounds`
resolves each moment through an indirection that falls back to a named vanilla `SoundEvent` when our
sound file is absent, and `sounds.json` only declares entries whose files ship. The registry ids are
stable either way, so a later art drop needs no code change.

### 3.12 Config group naming and key migration

**Decision.** Create the specification's section 16.1 groups as new COMMON top-level sections:
`restraints` (+`application`, `definitions`, `escape` subsections), `transport`, `detention`,
`locks`, `lockpicking`, `frisking`, `prison`, `enchantments`, `wounds`, `identity`, `compatibility`;
and extend the existing CLIENT section with the presentation keys. `ConfigValidator` gains one rule
block per group.

The legal/kidnapping keys stay where they are: `enableKidnappingNpc`, `enableKidnappingPlayer`,
`maxUnlawfulCaptivesPerCaptor`, `captorDisconnectGraceTicks`, `enableRescue`, `rescueChannelTicks`,
`accompliceJailTicks` remain under `kidnapping`. The **physical** keys under `kidnapping` are
retired: `captureChannelTicks`, `captureMaxMoveBlocks`, `captureMaxRangeBlocks`,
`captureRequireLineOfSight`, `captureChannelMultiplier{Rope,Cuffs,LockedCuffs}`,
`restraintEscapeChance{Rope,Cuffs,LockedCuffs}`, `captiveTetherBlocks`, `captiveCanEscapeByDistance`,
`escapeWorkTicks{Rope,Cuffs,LockedCuffs}`, `cuffEscapeRequiresLockpick`,
`escapeAttemptCooldownTicks` (`McaCrimeConfig.java:906-956`).

`ForgeConfigSpec` silently drops unknown keys from an existing `.toml` on rewrite, so retired keys
disappear without error. Values are **not** auto-migrated: retired keys are dropped and
documented one-for-one in `CONFIG.md` and `docs/MIGRATION.md` (section 10.5). `ConfigValidator` gains a startup pass that reads the raw
config file, reports any retired key it still finds with its replacement, and does not fail. This is
the settled policy, not a default awaiting confirmation (section 10.5).

`restraints.application.channelTicks` (default **0** = immediate, the Cuffed parity behaviour) is
the replacement for the old capture channel; a server that wants the old feel sets it to 60.

### 3.13 Datapack content

**Decision.** Data-driven: `mcacrime:restraint_profiles/*.json` (per-definition durability,
restriction overrides, pick difficulty, key family, supported rigs) loaded by a reload listener that
validates against the code-defined definition registry and never introduces a new definition id;
tags `lockable_blocks`, `reinforced_blocks`, `chainable_entities`, `can_reinforce_padlock`,
`restrainable_entities`, `lockpicks`, `cuff_keys`; the existing `fence_prices` datapack gains the new
goods; damage types `mcacrime:hang` and `mcacrime:imbue` as datapack files. Damage-type tags are
authored deliberately — `hang` is **not** added to `is_explosion` or `is_drowning` (upstream does,
`R:data/minecraft/tags/damage_type/`); it gets `bypasses_armor` only, with the reason recorded in
`DATAPACK.md`.

Hard-coded (not data-driven): restraint slot semantics, lock access rules, session validation. Those
are authorisation decisions and stay in code.

### 3.14 Commands and API

**Decision.** Extend the existing `/crime` root (`command/CrimeCommand.java:114`) rather than adding
roots: `restraint apply|remove|inspect`, `anchor set|remove`, `nickname set|reset`,
`lock inspect|reset`, `debug restraints`, and `recovery restraints <target>` inside the existing
`RecoveryCommand.tree()`. Permission level 3 for every mutating subcommand, matching
`CrimeCommand.java:153`; `inspect` and `debug` at level 2. Command sources other than players are
supported (no blanket `getPlayerOrException`). `/crime release` keeps its meaning — *legal* release —
and now additionally clears physical claims owned by that custody, nothing else; the narrower
`/crime restraint remove` does physical-only removal and never pardons.

API additions in `api/model/`: `RestraintView`, `RestraintSlotView`, `TransportView`,
`DetentionView`; events `RestraintAppliedEvent`, `RestraintRemovedEvent`, `PhysicalEscapeEvent`,
`SubjectSeizedEvent`, `DetentionOutcomeEvent`, all post-commit. `CustodyView.custodyId` finally
returns a real value. `McaCrimeApi.getApiVersion()` is bumped and `API.md` records the new surface
and any deprecated projection.

### 3.15 Client rendering architecture

**Decision.**
- Nine worn models under `client/render/restraint/model/` (handcuffs, shackles, fuzzy, legcuffs,
  leg shackles, tape head/arms/legs, bundle hood), registered through
  `EntityRenderersEvent.RegisterLayerDefinitions`.
- One render layer, `client/render/RestraintSlotLayer`, replacing `RestraintWristLayer`, added to
  player and MCA villager renderers through `EntityRenderersEvent.AddLayers` by **registry key
  string**, naming no MCA class (the port already does this at `client/render/CrimeRenderLayers.java:29`).
- Pose: **keep** `mixin/client/RestraintPoseMixin` (injects into `LivingEntityRenderer.render`
  after `setupAnim`) and extend it to multi-slot poses. Upstream's `HumanoidModelMixin`
  (`setupAnim` HEAD, cancellable, plus unrestored `head.z`/`body.z` writes on a shared model) is
  **not** ported: that is the cause of the missing-skin-second-layer reports #38/#48, and our
  injection point structurally avoids it.
- Chains are drawn from a `RenderLivingEvent.Post` handler, not a mixin on `EntityRenderer.render`.
- HUD: slot icons, restraint name, struggle/durability bar, chain/escort status and available escape
  actions, laid out by the existing `client/hud/CrimeHudLayout` and drawn from the existing
  `CrimeSprites` sheet (extended by `tools/gui/generate_gui_sheet.py`, which has a `--check` mode the
  build must keep tolerating without Python).
- Screens: `LockpickingScreen` (interpolated client rendering over server ticks), `FriskingScreen`,
  `PossessionsBoxTooltip`/`TrayTooltip` tooltip components, `WardenGuideScreen` (M6). No Lazr's Lib:
  the rotated blit and the upright progress bar are reimplemented in
  `client/screen/widget/CrimeBlit`.
- First-person hood overlay renders only for the local hooded player; observers see the worn model.
  A client visual toggle can never lift a server restriction.
- `IClientItemExtensions` is not used for worn geometry; restraints are not armour items.

### 3.16 Locks Reforged coexistence

**Decision.** Native picking is the only default. `compat/locksreforged/CuffLockPickingMenu.java` is
deleted and `LocksReforgedBridge.openCuffs` with it; the fence-goods provider
(`compat/locksreforged/LocksReforgedCompat`) and `locksReforgedFenceTrades` stay. On a block target
already owned by Locks Reforged, MCA: Crime **refuses** a second padlock rather than installing a
competing access check (`locks.foreignLockPolicy = REFUSE|IGNORE`, default `REFUSE`).
`OptionalClassloadTest`'s expected file set for `compat/locksreforged/` must shrink to one class.

### 3.17 Cuffed installed alongside

**Decision.** `compat/CuffedCoexistence` detects mod id `cuffed` with `ModList.isLoaded` only and
names no upstream type. Policy `compatibility.cuffedCoexistence`: `WARN` (default) logs one startup
warning and proceeds; `REFUSE` additionally disables MCA: Crime restraint *application* (removal,
recovery and deserialisation stay enabled, per specification section 16). No foreign data is read,
cleared or mixin-disabled. The donor-world import tool (specification section 17.4) is explicitly
**out of scope for 0.7.5** and recorded as such in the release notes.

### 3.18 Save migration v14 → v15

**Decision.** Two parts.

1. A **pure** `CrimeDataMigrations.v14to15(CompoundTag)`, following the `v13to14` precedent
   (`state/world/CrimeDataMigrations.java:517-521`): create the empty `physicalRestraints`,
   `tethers`, `detentions`, `locks` and `identities` lists, stamp `custodyId` + `generation` on every
   existing custody row that lacks one, cancel any `escapeActive` flag, and stamp the schema. It
   invents no gear.
2. An **impure reconciliation** at first load after migration, inside `ServerMutationGate` and
   `frozen()`, in `restraint/RestraintMigrationReconciler`:
   - `ROPE` → `duck_tape_arms` instance flagged `LEGACY_CONVERSION`;
   - `CUFFS` → `shackles_arms` using the `restraint_cuffs` item;
   - `LOCKED_CUFFS` → `handcuffs_arms` using the `restraint_locked_cuffs` item;
   - arrest phase `RESTRAINED` with no explicit gear → one `handcuffs_arms` instance marked
     `SYSTEM_ISSUED`;
   - every migrated instance gets a conservative `itemSnapshot` (correct item, full durability, no
     enchantments claimed — the old record never stored any) and `returnPolicy = NONE`, so release
     cannot mint free cuffs;
   - the legacy `cuffCombination` byte array is archived under `reserved` and then retired; it does
     not unlock the new state;
   - a legacy kidnapping `holdPos` becomes a `TetherRecord` of kind `LEGACY_HOLD` — no fence knot is
     fabricated, no chain item is dropped;
   - legacy capture/escape sessions are cancelled with a message to the participant and no item
     charge.
   The reconciler is idempotent and stamps a `reconciledSchema` marker so a repeated load is a no-op
   (the failure mode the specification calls out: "a migration that creates a free extra cuff on
   every login fails").

`restraint_rope` stays registered, is removed from the creative tab, and is normalised to
`duck_tape` at controlled boundaries (application, fence stock, recipe). A lossless
`rope_to_duck_tape` shapeless recipe ships. No broad NBT text replacement is performed anywhere.

---

### 3.19 Capital sentencing for killing a guard  *(scoped in by the user; working interpretation)*

**Status.** Not from the Cuffed specification. The user scoped this in alongside decision 7 and asked
for a fully functional feature. What follows is the **working interpretation** the rest of the plan is
built on, recorded here so the user can correct any line of it before M6.6 starts.

**Working interpretation.**
1. One new sentence kind, `CAPITAL`, joins the existing (implicitly custodial) sentence model. The
   **only** offence that may produce it is killing a guard.
2. "Not automatic" means three separate guarantees: no other offence ever escalates to it; no timer,
   redstone circuit, packet or scheduled task ever carries it out; and the whole feature sits behind
   one config group that one key switches off.
3. Execution is a deliberate act at a guillotine (M4.6) performed by a **player** or by an **on-duty
   enforcement guard**, and by nothing else.
4. With no usable guillotine the condemned simply stays in custody under the sentence. No substitute
   death, no despawn, no automatic commutation, no expiry into freedom.

**The offence seam.** Today killing a guard is indistinguishable from killing any villager:
`detect/CrimeClassifier.java:29-33` returns `CrimeIds.KILL_VILLAGER` for every non-player victim, even
though the *harm* branch at `:26` already asks `McaCompat.isGuard(victim)`. M6.6 therefore adds
`CrimeIds.KILL_GUARD` (`mcacrime:kill_guard`) beside `KILL_VILLAGER`
(`crime/type/CrimeIds.java:10-11`), a `CrimeType` row beside `crime/type/BuiltinCrimeTypes.java:20-21`
with its own karma/Heat weights and the `"guard"` victim class, an incident mapping beside
`CrimeIncidentMapping.GUARD_ASSAULTED` (`compat/CrimeIncidentMapping.java:58-63`), and one branch in
`classifyKill`. Everything downstream — witnesses, warrants, bounty, authority policy, companion
incident mapping — already keys off the crime id, so that is the single point of change. The mugging
branch at `detect/DamageIncidentService.java:171` keeps precedence: a mugging that kills a guard stays
`mcacrime:mugging_murder` and is **not** capital, because the gate is the crime id, not the victim.

**Sentence assignment.** New `ledger/SentenceKind` (`CUSTODIAL | CAPITAL`) is stored on the sentence
binding beside the assessed case ids (`ledger/SentenceAssignmentService.java:26-37` and
`CrimeWorldData.bindSentence`) and mirrored on the custody record next to its existing `sentenceId`
(`captivity/CustodyRecord.java:129-133`). `enforcement/ArrestService.java:156-215` prices the holding
term exactly as it does today, then asks a new `ledger/CapitalSentenceService` whether the assessed
case list contains an unresolved `mcacrime:kill_guard`; if it does and the config allows, the binding
is marked `CAPITAL` and the custodial tick figure is retained unchanged as the holding term. Marking
is idempotent: the existing "replayed arrival cannot expand it" rule in
`SentenceAssignmentService.assign` extends to the kind, and a kind is never upgraded after binding.
NPC arrests (`enforcement/NpcArrestService.java:68-79`) take the same path only when
`npcOffendersEligible` is on.

**Physical/legal bridge (M4.8) transitions.** Three rows join the section 14.1 table:
*condemned-in-custody* (a capital binding exists; confinement rules otherwise unchanged), *pending
execution* (a device claim is held for this subject and `executionDelayTicks` is running), and
*executed* (confirmed death). Only *pending execution* authorises the device to act, and it is cleared
by pardon, commutation, rescue, escape, guard death, device destruction or chunk unload — each of
which returns the subject to *condemned-in-custody*, never to freedom.

**Refusals.** While a capital binding is live and `refuseRansom`/`refuseBail` are on,
`ransom/RansomService.demandFor` and `action/handler/BailActionHandler.cost` refuse with a distinct
reason and lang key rather than quoting an unpayable number, and `economy/SettlementQuote` excludes
the capital cases. A fine never clears a capital case.

**Clemency.** Pardon and commutation are the only legal exits and both are explicit privileged
transactions, which `ledger/CaseTransitions.java:13-14` already requires of any pardon. Commutation
rewrites the binding to `CUSTODIAL` and keeps the holding term; it never resurrects anybody. Both are
reachable by command and through the API (M6.8).

**Death outcome.** Confirmed death runs through M4.6's single attributed damage action and its
persisted completion marker, and then, once only: the capital sentence closes under a new
`jail/ReleaseReason.EXECUTED` (paired with the existing `CustodyReleaseReason.CAPTIVE_DIED`,
`captivity/CustodyReleaseReason.java:26`), the warrant resolves, bounty is evaluated, possessions drop
or bank per M5.1, and the existing relationship/grief paths run. A **player** dies an ordinary vanilla
death and respawns with no lingering sentence. A **villager** dies permanently; MCA owns the corpse,
family and memorial behaviour and MCA: Crime does not intervene — `compat/mca/McaBinding` exposes no
death or family-notification member today (the nearest is `McaCompat.getSpouseUuid`,
`compat/McaCompat.java:611`), so this plan claims nothing beyond firing its own event and letting MCA
react to a normal death. Any cancelled death (totem, PlayerRevive, invulnerability) leaves the
sentence live and resolves nothing.

**Config group** (COMMON, `sentencing.capitalPunishment`):

| Key | Default | Range / values | Meaning |
|---|---|---|---|
| `enabled` | `true` | boolean | master switch for the whole feature |
| `guardKillingIsCapital` | `true` | boolean | the only offence gate; off means no offence qualifies |
| `npcOffendersEligible` | `false` | boolean | whether a villager offender may be capitally sentenced |
| `executionDelayTicks` | `1200` | 0–72000 | ceremony window between arming the device and the blade; this is the rescue/pardon window |
| `condemnedEscortTimeoutTicks` | `2400` | 20–216000 | how long a guard may spend walking a condemned captive to a device before giving up and returning them to a cell |
| `guardMayExecute` | `true` | boolean | off leaves execution to players only |
| `executionSiteSearchRadius` | `48` | 8–128 | how far a guard looks for an assigned execution site |
| `requiresExecutionDevice` | `true` | boolean | may only be `false` when `enabled` is `false`; `ConfigValidator` refuses any other combination |
| `refuseRansom` | `true` | boolean | a capital captive cannot be ransomed |
| `refuseBail` | `true` | boolean | a capital sentence has no bail price |
| `dropPossessionsOnExecution` | `true` | boolean | possessions-box contents drop at the device rather than being retained |

`detention.guillotineEnabled` and `detention.guillotineDropsHead` stay as section 10.7 fixed them
(both `true`). The guillotine remains a usable device with `capitalPunishment.enabled` off — it simply
never receives a lawful execution order.

**Rejected alternatives.** A capital *threshold* on Heat or charge count (exactly the automatic
escalation the user ruled out); a death timer in the jail tick (no deliberate act, no rescue window);
reusing `KILL_VILLAGER` with a victim-class test at sentencing time (the ledger, the dossier and every
companion mod would still read "killed a villager", and a datapack could not reweigh the offence).

---

## 4. Ordered implementation steps (baseline, Forge 1.20.1)

Paths are repo-relative to `/home/otectus/Projects/MCACrime`. `J:` = `src/main/java/dev/otectus/mcacrime/`,
`T:` = `src/test/java/dev/otectus/mcacrime/`, `R:` = `src/main/resources/`.
Every step lists: files, purpose, dependencies, tests, and the proving check.

### M0 — Evidence, content manifest and provenance  *(size: small)*

**M0.1 Baseline check run.** Run the existing gates before any edit and record pre-existing failures
separately: `gradlew-quiet.sh <repo> check` and `build`, plus `check_mod.py MCACrime`.
*Deps*: none. *Proves*: a clean starting point; any later red is ours.

**M0.2 Parity ledger.** New `docs/0.7.5/PARITY_LEDGER.md`: one row per Appendix A id and per section 4
feature id (R01…C01) and per section 5 dormant entry, mapping to planned package, resources and
acceptance test. This is the checked-in version of section 6 of this plan.
*Deps*: none. *Proves*: no active upstream feature lacks a destination.

**M0.3 Provenance manifest.** New `docs/0.7.5/PROVENANCE.md`: for every copied or adapted artefact —
source repo, commit `48a33650`, original path, resulting path, author, licence evidence,
modifications. Records the confirmed discrepancy (repo GPL-3.0 vs `mod_license=All Rights Reserved`
in the published jar) and the absence of any credits file, and flags the seven poster textures, the
fuzzy worn texture and the six `.ogg` files as **unattributable, therefore authored original**
(section 10.1) — nothing waits on author contact — while every artefact the upstream GPL-3.0
`LICENSE` and README do cover is recorded as adapted, with its attribution. Adds `CREDITS.md` at the
repo root carrying those attributions.
*Deps*: none. *Proves*: the distribution position is explicit and already decided; no later step can
be blocked on provenance.

**M0.4 Protected-texture gate.** New `T:resource/ProtectedTextureHashTest.java` asserting both
SHA-256 values byte-for-byte by reading the files from `src/main/resources`.
*Deps*: none. *Test*: itself. *Proves*: `gradlew-quiet.sh <repo> check`.

**M0.5 Art bucket decision.** Record in `docs/0.7.5/PROVENANCE.md` three buckets: (a) protected MCA
art, unchanged; (b) Cuffed-origin art the upstream licence covers, adapted with attribution (M0.3);
(c) art MCA: Crime authors itself — everything unattributable (the seven posters, the fuzzy worn
texture, the six `.ogg` files) plus the art it needs regardless — the worn restraint entity textures that replace `R:assets/mcacrime/textures/entity/cuffs.png`,
the new HUD sprites in the `CrimeSprites` sheet, and any placeholder standing in for (b).
*Proves*: `tools/gui/generate_gui_sheet.py --check` still passes on the regenerated sheet.

### M1 — State model and migration foundation  *(size: large)*

**M1.1 Value types.** New package `J:restraint/`:
`RestraintSlot` (HEAD/ARMS/LEGS), `RestraintFamily` (HANDCUFFS/SHACKLES/FUZZY/TAPE/HOOD/LEGACY_ROPE),
`RestraintDefinition` (immutable record: id, slot, item, key item, restriction profile, escape
profile, pick profile, render profile, rig predicate, allowed enchantments),
`RestraintDefinitions` (the ten-entry code registry with explicit factories, no mutable singletons
handed out), `AppliedRestraint` (instanceId, definitionId, itemSnapshot, remainingDurability,
definitionRevision, applier, `ApplicationContext` VOLUNTARY/UNLAWFUL/LAWFUL/ADMINISTRATIVE/DEVICE,
`Provenance` PLAYER_OWNED/SYSTEM_ISSUED/LEGACY_CONVERSION, custodyId, appliedTick, revision),
`PhysicalRestraintState` (subject, dimension, generation, revision, three optional slots, tetherId,
escortId, detentionId), `RestrictionPolicy`, `RestrictionResolver`.
*Deps*: none. *Tests*: `T:restraint/RestraintDefinitionsTest`, `RestrictionResolverTest` (the whole
section 7.1 matrix, one assertion per cell), `AppliedRestraintNbtTest`.

**M1.2 Transport and device value types.** New `J:tether/TetherRecord`, `TetherKind`,
`J:detention/DetentionRecord`, `DetentionKind`, `J:locks/LockRecord`, `LockTarget` (a GlobalPos or
entity UUID union), `J:identity/SubjectIdentity` (nickname + privacy policy).
*Deps*: M1.1. *Tests*: `T:tether/TetherRecordNbtTest`, `T:locks/LockRecordNbtTest`.

**M1.3 Custody identity.** Modify `J:captivity/CustodyRecord.java` to add `custodyId` (UUID) and
`generation` (long), written and read in `save()`/`load()` (`:249-315`); modify
`J:captivity/CustodyService.java` to allocate both on every capture and to bump the generation on
transfer; modify `J:api/McaCrimeApi.java:255-256` to project the real `custodyId`.
*Deps*: M1.1. *Tests*: rewrite `T:CustodyRecordNbtTest`; new `T:api/CustodyIdentityTest` (two
successive captures of one subject produce different ids and the older generation's packets are
refused).

**M1.4 World tables.** Modify `J:state/world/CrimeWorldData.java`: five new root lists
(`physicalRestraints`, `tethers`, `detentions`, `locks`, `identities`) with accessors, each mutator
guarded by `frozen()`; extend the `reserved` re-emit path; bounded caps mirroring `MAX_STOLEN_GOODS`.
*Deps*: M1.1, M1.2. *Tests*: `T:state/PhysicalStateWorldDataTest`, extend
`T:QuarantineAndFutureSchemaTest` with a physical-table row; new `T:state/FrozenGuardCoverageTest`
(reflectively asserts every public mutator on the new tables calls `frozen()`).

**M1.5 Migration.** Modify `J:state/world/CrimeDataMigrations.java`: `SCHEMA_CUFFED_PHYSICAL = 15`,
`CURRENT_SCHEMA = 15`, pure `v14to15`. New `J:restraint/RestraintMigrationReconciler.java`
implementing section 3.18 part 2, idempotent, with a `reconciledSchema` marker.
*Deps*: M1.3, M1.4. *Tests*: new `T:CrimeDataMigrationsV14toV15Test`; new
`T:restraint/RestraintReconcilerTest` with fixtures for each of the specification's section 21.5
cases (each old restraint, lawful player arrest, unlawful player custody, lawful NPC custody,
unloaded NPC, recovery/care state, active sentence, ransom, property receipts, a generated cell
journal, an active Locks Reforged escape attempt); a repeated-load test asserting no extra item.
*Proves*: `gradlew-quiet.sh <repo> check`.

**M1.6 Protocol and sync lifecycle.** Modify `J:network/CrimeNetwork.java`: `PROTOCOL_VERSION = "15"`.
New `J:network/PhysicalStateS2CPacket` (full snapshot), `PhysicalStateDeltaS2CPacket`,
`PhysicalStateRemoveS2CPacket`; new `J:network/ActionValidation.java`, the shared helper
`ServerPacketGuard` lacks today (distance, dimension, line of sight, menu ownership, generation,
held item). New `J:restraint/RestraintSyncService` replacing `enforcement/RestraintSync`'s role:
full state on tracking start/reconnect/respawn, deltas on change.
*Deps*: M1.4. *Tests*: rewrite `T:RestraintSyncPacketTest` for the multi-slot shape; new
`T:network/ActionValidationTest`; extend `T:PacketBoundsTest`.

**M1.7 Session registry.** New `J:restraint/SessionRegistry` + `Session` base (id, actor, target,
target revision, dimension, source item, expiry), capped by `restraints.maxConcurrentSessions`.
*Deps*: M1.6. *Tests*: `T:restraint/SessionRegistryTest` (cap, expiry, cancel-on-event matrix).

**Gate M1.** Nothing in M1 is reachable from gameplay. Exit: a schema-14 fixture upgrades with cases,
sentences and property intact; two subjects stay independent through save/load.

### M2 — Restraints and replacement of the old paths  *(size: large)*

**M2.1 Items.** Modify `J:item/CrimeItems.java`: re-point `RESTRAINT_CUFFS` and
`RESTRAINT_LOCKED_CUFFS` at the new `RestraintItem(family)`; hide `RESTRAINT_ROPE` from the creative
tab; register `fuzzy_handcuffs`, `duck_tape`, `handcuffs_key`, `shackles_key`, `key`, `key_ring`,
`key_mold`, `baked_key_mold`, `lockpick`, `padlock`, `possessions_box`, `prisoner_tag`, `weighted_anchor`,
`fork`, `spoon`, `knife`, `bandage`, and the three creative items. Modify `J:item/RestraintItem.java`
to carry a `RestraintFamily` and no behaviour. `fuzzy_handcuffs` gets **no crafting recipe**
(section 10.3): creative tab plus a Fence stock entry in the `fence_prices` datapack (M5.12), with a
tooltip that says so honestly rather than repeating upstream's "Supporter only" text. New `J:item/restraint/RestraintKeyItem`,
`J:item/lock/{KeyItem,KeyRingItem,KeyMoldItem,BakedKeyMoldItem,PadlockItem,LockpickItem}`,
`J:item/tool/{ForkItem,SpoonItem,KnifeItem,BandageItem,DuckTapeItem,PrisonerTagItem}`,
`J:item/creative/{CreativeRestraintCutter,CreativeKeyItem,BindBreakerItem}`.
Creative-item authorisation is a **server** check (creative mode or permission ≥ 2), not an item
possession check — upstream's `CreativeRestraintCutter.interactLivingEntity` has none.
*Deps*: M1.1. *Tests*: `T:item/CrimeItemsRegistrationTest` (ids, stack sizes, durability sources),
`T:item/CreativeAuthorizationTest`.

**M2.2 Durability source of truth.** New `J:restraint/RestraintDurability` reading
`restraints.definitions.durability*` (handcuffs 40, shackles 15, fuzzy 30, tape arms 5, tape legs 5 —
independent keys, fixing upstream's leg-reads-arm defect). Items expose it; nothing hard-codes 999.
*Tests*: `T:restraint/RestraintDurabilityTest` (independent arm/leg tape settings).

**M2.3 Application service.** New `J:restraint/RestraintService` (apply/remove/replace one slot,
owning item movement, snapshots and lifecycle) and `J:restraint/ApplicationTransaction` implementing
the six-step commit of specification section 7.2 — resolve, check, classify, reserve one exact item,
commit once, publish afterwards. A rejected action consumes nothing; a duplicated
main-hand/offhand/event delivery cannot equip twice (a per-tick actor+target dedupe key).
*Deps*: M1.1, M1.4, M2.1. *Tests*: `T:restraint/ApplicationTransactionTest` (two actors one slot →
one success one refusal one item; replayed delivery → no second equip).

**M2.4 Body-area selection.** New `J:restraint/BodyRegionResolver`: normalised rig-aware regions
(fraction of the target's bounding-box height, so crouching, swimming, MCA body scales and Townstead
life stages work), replacing upstream's hard-coded world heights
(`cap/RestrainableCapability.java:172-256`). Standing players reproduce the practical
head/arms/legs split. The requested slot from the menu is a **request**; the server rechecks.
*Tests*: `T:restraint/BodyRegionResolverTest` (standing, crouching, half-height and double-height
rigs; unsupported rig returns an explicit unavailable reason, never a default slot).

**M2.5 Interaction routing.** Rewrite `J:captivity/CaptureInteractHandler.java` into
`J:restraint/RestraintInteractHandler` with a documented priority order: frisking box → restraint
item → matching key → lockpick → empty-hand keyless removal (crouching) → empty-hand escort start
(not crouching) → fall through to MCA. The empty-hand ambiguity the specification flags is resolved
in that order and written into the guide. Modify `J:action/CrimeActionInteractHandler.java:52` and
`J:client/EpicFightInteractShim.java:89` to defer to the new handler.
*Deps*: M2.3. *Tests*: `T:restraint/InteractionPriorityTest` (a release request never starts an
escort).

**M2.6 Self-application and dispensers.** Self-use gated by
`restraints.application.allowSelfApplication` (default true), using an explicit slot selector in the
self panel rather than upstream's broken pitch test (`mixin/PlayerMixin.java:206,209` mixes degrees
and radians). New `J:restraint/DispenserRestraintBehavior` registered in
`FMLCommonSetupEvent#enqueueWork` for the two cuff items, fuzzy cuffs, `duck_tape` and
`minecraft:bundle`: searches the full block in front plus one, picks the nearest eligible subject
(player or MCA villager through `McaCompat`), applies with `ApplicationContext.DEVICE` and an
`applier` of the device — never a forged "the victim restrained herself" identity
(upstream passes `(player, player)` at `items/base/AbstractRestraintItem.java:227-245`). One item
consumed on success only.
*Deps*: M2.3. *Tests*: `T:restraint/DispenserBehaviorTest` (device actor recorded; no kidnapping
case attributed to the victim; failed dispense consumes nothing).

**M2.7 Removal, keys and struggle.** New `J:restraint/EscapeService` (server-owned struggle work per
section 3.5), `J:restraint/RemovalService` (key match, keyless tape/hood removal, cutting tool,
creative cutter, break-on-zero-durability). One break = one event, one item resolution per the
configured drop rule, restrictions recomputed once. Returned items preserve damage, custom name,
enchantments and supported NBT; `SYSTEM_ISSUED` provenance returns nothing.
New `J:network/RestraintStruggleC2SPacket`.
*Deps*: M1.7, M2.3. *Tests*: `T:restraint/EscapeServiceTest` (alternation required; forged interval
rejected; Unbreaking scaling; release at zero durability exactly once — upstream issues #20/#26),
`T:restraint/ItemReturnPolicyTest` (no free cuff minting).

**M2.8 Restriction enforcement.** Rewrite `J:enforcement/RestraintHandlers.java` to consult
`RestrictionPolicy` per action type instead of blanket blocking, keeping its existing Forge
subscriptions (`:86-152`) and adding `ItemTossEvent`. New `J:restraint/RestraintAttributes` applying
owned, fixed-UUID transient modifiers for movement and attack speed, removed exactly once.
Delete `J:enforcement/RestraintPolicy.java` (its arrest-phase fold moves into
`RestrictionResolver`). Client input suppression uses Forge's `MovementInputUpdateEvent` (confirm
with `find_api.py`), **not** a mixin.
*Deps*: M1.1, M2.3. *Tests*: rewrite `T:RestraintPolicyTest` as
`T:restraint/RestrictionCompositionTest` (head+arms+legs compose; removing one leaves the other two;
identical effects do not multiply); `T:restraint/EscapeNotCancelledTest` (no policy cancels the
struggle path).

**M2.9 Three new vanilla-only mixins.** Add to `R:mcacrime.mixins.json` (common array):
- `J:mixin/RestraintJumpMixin` — `LivingEntity#jumpFromGround`, HEAD, cancellable; cancels when the
  server policy denies `jump`. (`LivingJumpEvent` fires too late to cancel.)
- `J:mixin/RestraintContainerClickMixin` — `AbstractContainerMenu#clicked`, HEAD, cancellable;
  refuses inventory mutation when the policy denies it. This is the server-side answer to a forged
  inventory click.
- `J:mixin/RestraintPlayerActionMixin` — `ServerGamePacketListenerImpl#handlePlayerAction`, HEAD,
  cancellable, **only** for `SWAP_ITEM_WITH_OFFHAND`.
All three are vanilla-only, narrowly scoped, no MixinExtras, no access transformer.
*Deps*: M2.8. *Tests*: extend `T:MixinConfigTest` with the three new entries and the side split.
*Proves*: `gradlew-quiet.sh <repo> build` (mixin config is `required:true`, so a bad target fails at
runtime, not compile — the client/server smoke run in M7 is the real proof).

**M2.10 Rendering replacement.** New `J:client/render/restraint/` (nine models + `RestraintSlotLayer`
+ `RestraintAnimations` with `ARMS_TIED_FRONT`/`ARMS_TIED_BEHIND`/leg flags); modify
`J:mixin/client/RestraintPoseMixin.java` for multi-slot poses; modify
`J:client/render/CrimeRenderLayers.java` to add the new layer; delete
`J:client/render/RestraintWristLayer.java` and `J:client/render/RestrainedPose.java`. New
`J:client/ClientPhysicalState` replacing `J:client/ClientRestraintData` (slots, tether, device,
durability); keep `J:client/ClientRestraintRig`. New `J:client/hud/RestraintHudSection`. New
first-person hood overlay in `J:client/HoodOverlayHandler` (local player only).
*Deps*: M1.6. *Tests*: `T:client/RestraintHudLayoutTest`, `T:client/HoodOverlayOwnershipTest`,
extend `T:ClientConfigIsolationTest` for the new client roots.
*Proves*: `check_mod.py MCACrime` for model/texture coverage.

**M2.11 Retire the legacy engine.** Delete `J:captivity/CaptureChannel.java`,
`CaptureChannels.java`, `CaptureService.java`, `CaptureTicker.java`, `CaptureVulnerability.java`
(its vulnerability gate moves to `restraints.application.vulnerabilityGates`, off in the parity
preset), `CuffEscapeService.java`, `CuffLockProgress.java`, `CustodyConfine.java`,
`enforcement/EscortRestraint.java`, `enforcement/RestraintPolicy.java`,
`enforcement/RestraintSync.java`, `enforcement/RestraintVisualResolver.java`,
`RestraintVisualState.java`, `RestraintVisualType.java`. Reduce `J:captivity/RestraintType.java` to a
deprecated API projection used only by `api/event/EntityKidnappedEvent` and `api/model/CustodyView`,
never authoritative. Modify `J:captivity/CustodyService.java` to call the physical services
transactionally and `J:captivity/CustodyRecord.java` to reference `PhysicalRestraintState` instead of
holding a `RestraintType`.
*Deps*: M2.3–M2.10 complete. *Tests deleted or rewritten*: see section 5.
*Proves*: `gradlew-quiet.sh <repo> check`.

**M2.12 Resources for M2.** `R:assets/mcacrime/models/item/*.json` for every new item;
`R:assets/mcacrime/textures/item/*` (bucket b/c per M0.5);
`R:assets/mcacrime/textures/entity/restraint/*` for the worn models;
lang keys `item.mcacrime.*`, `mcacrime.restraint.<definition>.name`,
`mcacrime.restraint.slot.*`, `mcacrime.hud.*`, `mcacrime.command.restraint.*`;
recipes `duck_tape.json`, `handcuffs_key.json`, `shackles_key.json`, `prisoner_tag.json`,
`rope_to_duck_tape.json`, and replacements for `restraint_cuffs.json` / `restraint_locked_cuffs.json`
using upstream's `shackles.json` / `handcuffs.json` shapes; `bundle.json` (vanilla bundle recipe,
documented in `DATAPACK.md` as a deliberate, collidable addition); tags `restrainable_entities.json`,
`cuff_keys.json`.
*Tests*: `T:LangCoverageTest` (existing, will fail until keys land), new
`T:resource/RestraintResourceCoverageTest` (every definition has a model, texture, lang key and
recipe or a documented acquisition).

**Gate M2.** Exit: every restraint type works in multiplayer and on supported NPCs; the two protected
images are unchanged; no duplicate engine applies restrictions.

### M3 — Native lockpicking and secure locks  *(size: large)*

**M3.1 Lock service.** New `J:locks/LockService` (create, resolve, bind, rekey, lock/unlock, remove,
admin recovery), `J:locks/LockAccess` (pure access table), `J:locks/LockTargetNormalizer` (both door
halves, both chest halves, pillory halves, safe native lock vs external padlock — one canonical owner
per target group, conflicting duplicates refused).
*Deps*: M1.2, M1.4. *Tests*: `T:locks/LockTargetNormalizerTest`, `T:locks/LockAccessTest`
(wrong key, rekeyed copy, another player's key, generic tag not a master key).

**M3.2 Keys, rings, molds.** New `J:item/lock/*` behaviour and `J:recipe/lock/` recipe classes:
`KeyRingCreateRecipe`, `KeyRingAddRecipe`, `KeyRingDisassembleRecipe`, `KeyMoldCopyRecipe`,
`BakedKeyMoldCopyRecipe`, `KeyMoldBakeRecipe` (custom serializer — vanilla smelting loses NBT),
`KeyResetRecipe`. Registered in `J:recipe/CrimeRecipes.java` alongside `mask_making`.
Multi-ring inputs are rejected, never silently merged.
*Deps*: M3.1. *Tests*: `T:recipe/KeyRingRecipeTest` (exact input/output counts; multi-ring rejected;
capacity reduction does not erase), `T:recipe/KeyMoldTest`.

**M3.3 Lockpick sessions.** New `J:lockpick/LockpickService`, `LockpickSession`, `LockpickProfile`
(the seven difficulty profiles from specification section 8), `J:network/LockpickBeginS2CPacket`,
`LockpickPhaseS2CPacket`, `LockpickAttemptC2SPacket`, `LockpickCancelC2SPacket`,
`LockpickResultS2CPacket`. Outcomes: restraint → remove that instance and process the return;
padlock → remove the padlock, preserve the block; door/safe → `lockpicking.destructiveOutcome`
(default **false** = unlock; `true` reproduces the Cuffed parity outcome and, for a safe, moves the
contents exactly once **before** removal).
*Deps*: M1.7, M3.1. *Tests*: `T:lockpick/LockpickSessionTest` (forged success/target/durability
rejected; stale session against a replaced lock at the same coordinates rejected; cancel matrix),
`T:lockpick/LockpickProfileTest` (the seven profiles' numbers), `T:lockpick/PickDurabilityTest`
(used ≠ broken; the `successful_lockpicks` / `lockpicks_broken` stats are distinct).

**M3.4 Padlock entity and lockable blocks.** New `J:entity/PadlockEntity` (stores only `lockId`;
reinforcement stores an **item id, correctly namespaced** — upstream's drop path builds an invalid
`ResourceLocation` from a full registry name, `entity/PadlockEntity.java:178` vs `:86`), new
`J:block/CellDoorBlock`, `J:block/entity/LockableBlockEntity` (guarded `contains("LockId")` read —
upstream throws without it, `blocks/entity/LockableBlockEntity.java:50`).
*Deps*: M3.1. *Tests*: `T:locks/PadlockLifecycleTest` (break/replace the supporting block detaches
once; orphan recovery route exists), `T:block/CellDoorLockTest`.

**M3.5 Safe and automation protection.** New `J:block/SafeBlock`, `J:block/entity/SafeBlockEntity`
with `prison.safeSlots` default **36** (a deliberate design choice; upstream's live value is 27 and
its 36 is dead config — recorded in `CONFIG.md`). The block entity owns both menu access and the
Forge `ITEM_HANDLER` capability; the handler re-evaluates lock state on **every** operation,
insertion and extraction, and the capability is invalidated on lock change so a cached handler cannot
survive a lock. Open menus are closed or revalidated when the lock closes, rekeys or loses its block.
Slot-count changes preserve overflow rather than truncating.
*Deps*: M3.4. *Tests*: `T:block/SafeAutomationTest` (hopper, hopper minecart, cached `IItemHandler`;
lock-while-open; slot shrink with overflow), `T:block/SafeContentsLifecycleTest` (pick, destroy,
unload — contents settle exactly once).

**M3.6 External padlock protection on vanilla containers.** New `J:locks/LockProtectionHandlers`:
interaction, break, explosion, piston, redstone and `IItemHandler` transfer coverage per
`locks.automationPolicy`; paired containers rechecked. Modded containers require a verified adapter;
unsupported automation routes are **reported**, not silently claimed.
*Deps*: M3.5. *Tests*: `T:locks/LockProtectionCoverageTest`.

**M3.7 Locks Reforged decoupling.** Delete `J:compat/locksreforged/CuffLockPickingMenu.java`; modify
`J:compat/LocksReforgedBridge.java` to drop `openCuffs` and keep the fence provider and `status()`;
implement `locks.foreignLockPolicy`. Update `T:OptionalClassloadTest`'s expected file set.
*Deps*: M3.3. *Tests*: `T:compat/LocksCoexistenceTest`; `T:LocksReforgedTierTableTest` (unchanged,
must still pass).

**M3.8 Resources for M3.** Blockstates + models for `cell_door` (including the sixteen bars
connection models) and `safe`; item models; the recipe files `key.json`, `key_reset.json`,
`key_ring_*.json`, `key_mold_*.json`, `baked_key_mold_copy.json`, `padlock.json`, `lockpick.json`,
`cell_door.json`, `safe.json`; loot tables for `cell_door` and `safe`; tags `lockable_blocks.json`,
`can_reinforce_padlock.json`, `lockpicks.json`; the six sound events + `sounds.json` entries for
`block.safe.open` / `block.safe.close`; lang keys.
*Tests*: `check_mod.py MCACrime`, `T:resource/LockResourceCoverageTest`.

**Gate M3.** Exit: unrelated keys and stale packets cannot open a lock; safe contents survive every
break/pick/unload path; core features run without Locks Reforged.

### M4 — Transport, anchors and detention  *(size: large)*

**M4.1 Tether engine.** New `J:tether/TetherService`, `TetherIndex` (by subject, holder UUID, anchor
position), `TetherPhysics` (bounded pull, `transport.maxChainLength` 5.0,
`transport.overextensionLength` 12.0, validated ordered and finite), `TetherDamage` (explicit
`mcacrime:hang` damage type, responsible actor/device attributed; guards use a custody transport
policy that does not deliberately harm prisoners, `transport.guardTransportHarmless` default true).
Chain item ownership is recorded once; `detach` is idempotent and returns **at most one** chain
(upstream mints a chain on every `setAnchoredTo(null)` call from five call sites,
`mixin/LivingEntityMixin.java:64-79`).
*Deps*: M1.2, M1.4. *Tests*: `T:tether/TetherOwnershipTest` (repeated detach yields one chain),
`T:tether/TetherPhysicsTest` (finite velocity, cycles rejected, extreme coordinates bounded),
`T:tether/TetherIndexTest` (no world scan: assert lookups are index-driven).

**M4.2 Anchors.** New `J:entity/ChainKnotEntity` (fence and tripwire hook, persistent, multi-subject,
`survives()` checks the actual block), `J:entity/WeightedAnchorEntity` (placement, movement, pickup,
Buoyant; pickup **detaches everything anchored to it first** — upstream discards with dangling
references, `entity/WeightedAnchorEntity.java:176-183`; synched data declared on the correct class,
unlike upstream's `Player.class` declaration at `:47`). Fixed-anchor transfer is index-driven; the
full-world entity scan at `event/ModServerEvents.java:165-171` is not ported.
*Deps*: M4.1. *Tests*: `T:tether/AnchorTransferTest`, `T:entity/WeightedAnchorPickupTest`
(no duplication, no dangling reference, enchantments preserved).

**M4.3 Escort and arbitration.** New `J:tether/EscortTransport` (collision-aware, validity checked
before correction), `J:tether/TransportArbiter` (the five-level priority ladder of specification
section 9.4, suspending lower levels explicitly). Modify `J:enforcement/EscortService.java`,
`J:enforcement/JailEscortNavigation.java`, `J:enforcement/NpcCustodyService.java` to keep their legal
destination/reassignment logic and call the new transport API. Modify `J:engine/CrimeDecayHandler` to
drop the old `EscortRestraint`/`CustodyConfine` ticks.
*Deps*: M4.1, M2.11. *Tests*: `T:tether/TransportArbiterTest` (exactly one authority per subject;
suspended relationships restore), `T:enforcement/EscortHandoverTest` (guard death/logout/dimension
change).

**M4.4 Seats, mounts and dismounting.** New `J:tether/MountTransfer`: validates passenger capacity,
mount capability, dimension, identity and a safe attachment position — not "every clicked entity is a
vehicle" (upstream: `event/ModServerEvents.java:320-334`). Leg restraint blocks voluntary steering
and the ordinary dismount route but not gravity, vehicle movement, external impulses or emergency
release. Vehicle destruction/removal/dimension change ends the claim and finds a safe exit. Works for
players and NPCs alike.
*Deps*: M4.3. *Tests*: `T:tether/MountTransferTest`.

**M4.5 Pillory.** New `J:block/PilloryBlock`, `J:block/entity/PilloryBlockEntity`,
`J:detention/DetentionService`. Two-block integrity, orientation, atomic occupancy claim, pose,
padlocking, escort admission, crouch-transition breakout (`detention.pilloryBreakoutTransitions`
default 100, server-bounded, with an accessible alternative), release. Generalised to villagers.
Lifecycle cleanup runs regardless of the breakout toggle. The pillory's detention is **independent of
the head slot**, so a hood or gag survives it — an explicit improvement over upstream's head-slot
`pillory` restraint (`restraints/custom/PilloryRestraint.java:31`); `mcacrime:pillory` survives as an
import/extension definition id backed by a composite detention profile.
*Deps*: M4.3. *Tests*: `T:detention/PilloryOccupancyTest` (simultaneous close → one success; destroy
either half → one safe release; chunk unload ≠ destruction; breakout toggle off still cleans up).

**M4.6 Guillotine.** New `J:block/GuillotineBlock`, `J:block/entity/GuillotineBlockEntity`: pillory
attachment, blade state, five-tick activation delay **persisted**, reset action, sounds, bloody
visual state, optional head drop (`detention.guillotineDropsHead`, default true; a non-player target
without a head item never fabricates a player head). Execution is one attributed server action
through the normal damage pipeline; cancelled deaths, invulnerability, totems and PlayerRevive are
respected; drops, custody closure, property recovery and bounty evaluation run only after confirmed
death, once, with a persisted completion marker.
*Deps*: M4.5. *Tests*: `T:detention/GuillotineExecutionTest` (no double kill; cancelled death yields
no head/bounty/recovery; repeated activation and restart do not duplicate).

**M4.7 Bunk.** New `J:block/BunkBlock`, `J:block/entity/BunkBlockEntity`: reinforced bed behaviour,
sleeping, occupied state, two-part integrity, escorted placement, safe release. For forced custody
use the prior respawn point is snapshotted and restored **only if this system still owns the
override** (a player who set a newer spawn keeps it). Connected to `facility/CustodyCarePolicy` and
Townstead schedules through the existing adapter; no global home or profession reassignment.
*Deps*: M4.5. *Tests*: `T:detention/BunkRespawnTest`.

**M4.8 Custody bridge.** New `J:restraint/CustodyTransitionService` implementing the specification's
section 14.1 table in full, with one transition per episode: first involuntary restraint starts
unlawful custody and one kidnapping incident; additional slots update physical state only; escape
from unlawful custody costs the victim nothing; defeating lawful confinement files exactly one
jailbreak; caregiver removal is an authorised equipment change.
*Deps*: M2.11, M4.3. *Tests*: `T:restraint/CustodyTransitionTest` (one row per table line),
`T:restraint/JailbreakDeduplicationTest`.

**M4.9 Resources for M4.** Blockstates/models for `pillory`, `guillotine`, `bunk`; entity models and
textures for `chain_knot`, `padlock`, `weighted_anchor`; recipes `pillory.json`, `guillotine.json`,
`bunk.json`, `weighted_anchor.json`; loot tables; `chainable_entities.json` tag; sounds
`block.pillory.use`, `block.guillotine.use`; the `mcacrime:hang` damage type and its **deliberately
chosen** tags; lang keys.
*Tests*: `check_mod.py MCACrime`.

**M4.10 Execution authorisation.** New `J:detention/ExecutionAuthorization`: a guillotine may take a
life only while it holds a *pending execution* record naming this subject, this device and this actor,
created by an explicit player or guard order and never by a timer, a comparator, a packet or a
scheduled task. Modify `J:block/entity/GuillotineBlockEntity` (M4.6) to consult it before the chop and
to refuse with a lang-keyed message — an unlawful, unsentenced or merely restrained subject placed in
a guillotine can still be released, never executed. Extend the M4.8 bridge with the three transitions
of section 3.19 and their clearing rules (pardon, commutation, rescue, escape, guard death, device
destruction, chunk unload). Resources: `block.guillotine.arm` (original audio, section 10.1) and the
`mcacrime.execution.*` lang keys.
*Deps*: M4.6, M4.8. *Tests*: `T:detention/CapitalExecutionAuthorizationTest` (no timer, redstone or
packet path; unauthorised subject refused; pardon inside the window cancels), plus the three new rows
in `T:restraint/CustodyTransitionTest`.

**Gate M4.** Exit: handover, death, reconnect, chunk unload and device destruction never strand or
duplicate a subject or an item.

### M5 — Frisking, inventory and prison equipment  *(size: large)*

**M5.1 Possessions box.** New `J:item/lock/PossessionsBoxItem` + `J:inventory/PossessionsStore`:
bounded slot count (`frisking.boxSlots`), per-stack NBT size cap, total serialized payload cap,
nesting depth guard (a box inside a box is refused), modded stack limits honoured (no hard-coded 64).
Preview tooltip, stack retrieval, spilling, destruction drops.
*Deps*: M2.1. *Tests*: `T:frisk/PossessionsBoundsTest` (full box, NBT-heavy stacks, nested
containers, unbounded payload refused).

**M5.2 Frisking session and menu.** New `J:frisk/FriskingService`, `FriskSession`,
`FriskTransaction`; new `J:menu/FriskingMenu` registered in `J:menu/CrimeMenus.java`; new
`J:network/FriskTransferC2SPacket`, `FriskSnapshotS2CPacket`; new
`J:client/screen/FriskingScreen`. Read-only projection plus explicit transfer per section 3.8.
Inventory providers are an interface (`J:frisk/InventoryProvider`) with vanilla,
MCA-equipment-through-`McaCompat`, and optional Curios / Cosmetic Armor adapters (M6). Villager
**trade offers are never confiscated** — they are not inventory stacks.
*Deps*: M5.1, M1.7. *Tests*: `T:frisk/FriskTransactionTest` (the six validations; concurrent
searchers; searcher drops the box; target dies/disconnects/released mid-search; cursor-item close;
crash-between-persistence receipt recovery), `T:menu/FriskingClickRejectionTest` (every vanilla click
type either has defined safe behaviour or fails with no mutation).

**M5.3 Legal seam.** Modify `J:enforcement/ContrabandSearchService.java` to accept a frisk-sourced
discovery; new `J:frisk/SeizureLedger` writing to `PropertyEscrow`/`PropertyReceipt`; criminal
extraction routes through `property/PropertyTheftService` and `CrimeWorldData.reserveTheft`.
Return on custody end, bail payment or property recovery uses the existing recovery ledger.
*Deps*: M5.2. *Tests*: `T:frisk/SeizureClassificationTest` (guard search vs voluntary transfer vs
criminal seizure are three different events), `T:frisk/EvidenceReturnTest`.

**M5.4 Reinforced construction.** New blocks: `reinforced_stone`, `reinforced_smooth_stone`,
`chiseled_reinforced_stone`, `reinforced_lamp`, `reinforced_stone_slab`, `reinforced_stone_stairs`,
`reinforced_bars`, `reinforced_bars_gap`, in `J:block/CrimeBlocks.java` + `J:block/prison/`.
`prison.reinforcedBreakingPolicy` = `PICKAXE_QUALIFIED` (default, honest description) |
`HARD_CONTAINMENT` (no prisoner excavation); explosion, piston and authorised removal configured
separately. Documentation says what the code does — never "unbreakable".
*Deps*: M2.1. *Tests*: `T:block/ReinforcedPolicyTest`.

**M5.5 Excavation.** New `J:entity/CrumblingBlockEntity` and `J:block/prison/ExcavationService`:
fork/spoon progress with configurable chance (`prison.excavationChance`, default 0.25 from upstream's
hard-coded roll) and stage count, persistent progress, cancelled by the containment policy.
*Deps*: M5.4. *Tests*: `T:block/ExcavationProgressTest`.

**M5.6 Cell integration.** Modify `J:jail/CellBlueprint.java`, `CellBuilder.java`,
`HoldingCellService.java` for a configurable reinforced palette
(`prison.generatedCellPalette`), preserving safe exit geometry, occupancy limits, facility exclusion,
the restoration journal and exact block-state/block-entity backups. Occupied cells finish under their
saved blueprint.
*Deps*: M5.4. *Tests*: extend `T:CellBlueprintTest`, `T:HoldingCellJournalTest` (an in-flight cell
keeps its old palette).

**M5.7 Trays and utensils.** New `J:block/TrayBlock`, `J:block/entity/TrayBlockEntity`,
`J:item/tool/TrayItem`: one food plus one of each utensil, contents preserved through placement,
pickup, save/load and destruction, shown in world and tooltip, utensil nutrition bonus
(`prison.utensilNutritionBonus` — the exact upstream arithmetic must be read first, section 2.4).
Connected to `facility/CustodyCareService` meals.
*Deps*: M5.4. *Tests*: `T:block/TrayContentsTest` (modded food effects and remainders survive).

**M5.8 Posters.** New `J:block/PosterBlock`, `J:item/PosterBlockItem`, `PosterVariant` enum (seven
authored variants plus `NONE`), placement/orientation, the `poster_change` recipe. Art is
**authored original** under the same registry ids (section 10.1, M0.3): seven MCA: Crime
poster designs, no upstream image copied.
*Deps*: M5.4. *Tests*: `T:block/PosterVariantTest`.

**M5.9 Knife, wounds, bandages.** New `J:wound/WoundService`, `J:effect/WoundedEffect`,
`J:item/tool/{KnifeItem,BandageItem}`: configurable clamped severity
(`wounds.knifeIncrement` default 20, `wounds.severityCap`, `wounds.minimumMaxHealth` **nonzero**),
blood-drip particles, treatment applied once on the server, all owned modifiers cleared, severity
resynced, one bandage consumed, configurable regeneration afterwards. **No damage is dealt when
adding or removing wound modifiers.** Side checks on both `use` and `interactLivingEntity`
(upstream has none, `items/BandageItem.java:267-289`). NPC treatment connects to
`captivity/CustodyCareService`.
*Deps*: M2.1. *Tests*: `T:wound/WoundLifecycleTest` (health stays finite through stacking, reconnect,
death, healing and foreign health modifiers; treatment never kills).

**M5.10 Prisoner tags.** New `J:identity/IdentityService` + `J:item/tool/PrisonerTagItem`: display
nickname applied/removed, persistence options on death and logout, statistics, genuine identity
retained internally and used by every legal lookup.
*Deps*: M1.2. *Tests*: `T:identity/NicknameTest` (legal identity unaffected).

**M5.11 Statistics.** New `J:stat/CrimeStats` registering all 19 custom statistics in common setup,
awarded **once per committed server event** — including for tape, bundle and pillory, which upstream
never awards (`init/ModStatistics.java:88-123`).
*Deps*: M2.7, M3.3, M4.5. *Tests*: `T:stat/StatAwardOnceTest`.

**M5.12 Resources for M5.** Blockstates/models/textures for the eight reinforced blocks, tray,
poster (8 variants); item models; the remaining recipes from Appendix A.4 including the three
stonecutting files and the mirrored stairs recipe; loot tables for every new block **including
`tray`** (upstream is missing it); `reinforced_blocks.json` tag; `mineable/pickaxe`,
`needs_iron_tool`, `walls` vanilla tag additions; `blood_drip` particle definition; the
`mob_effect` textures; lang keys.
*Tests*: `check_mod.py MCACrime`, `T:LangCoverageTest`.

**Gate M5.** Exit: every active source item and block has a real use, resource coverage and lifecycle
validation; search transfers preserve exact counts and NBT.

### M6 — Enchantments, integrations and dormant completion  *(size: medium)*

**M6.1 Enchantments.** New `J:enchantment/CrimeEnchantments` (six), `J:enchantment/ImbueHandler`
(rewritten per section 3.10, with the `mcacrime:imbue` damage type), famine/shroud/exhaust applied
from the restraint tick with bounded durations and correct cleanup, `silence` mana drain through
optional adapters at a **configured** rate (`enchantments.manaDrainPerTick`, default 0.005 — upstream
hard-codes it with a TODO), `buoyant` on weighted anchors. Vanilla Unbreaking and Curse of Binding
supported on the appropriate items.
*Deps*: M2.7, M4.2. *Tests*: `T:enchantment/ImbueDistributionTest` (zero/one/many recipients, dual
slots, mutually bound subjects, cancelled damage, death, no recursion, finite values),
`T:enchantment/EnchantmentApplicabilityTest`.

**M6.2 Optional adapters.** New `J:compat/` adapters, each `ModList.isLoaded` + `Class.forName` into
an isolated package, each with a version probe and an explicit "unsupported" report rather than a
silent claim: Better Combat, Epic Fight (through the existing `EpicFightCompat` and
`client/EpicFightInteractShim`), ParCool, Elenai Dodge 2, Combat Roll, Iron's Spells, Ars Nouveau,
Mana and Artifice, Simple Voice Chat (head-tape microphone suppression through its server plugin
API; normal voice returns immediately on removal), PlayerRevive, Curios, Cosmetic Armor Reworked.
Session-scoped restrictions only — **no adapter may permanently change a user setting**.
TacZ and Knights of Britannia: implement a real version-probed adapter or ship no claim; the
upstream TacZ adapter is empty and its setup wrongly sets the voice-chat flag
(`CuffedMod.java:176-179`).
*Deps*: M2.8. *Tests*: `T:compat/AdapterAbsenceTest` (absent mods load nothing),
extend `T:OptionalClassloadTest` with every new adapter package.

**M6.3 Dormant completions.** Guide: new `J:item/WardenGuideItem` + `J:client/screen/WardenGuideScreen`
covering the integrated mechanics with **rewritten** text, not revived stale upstream text. Toilet:
new `J:block/ToiletBlock` + its own block entity, storage, saving and drops (no sewage simulation).
Privacy policy: `J:identity/PrivacyPolicy` with `ALWAYS | ASK | ONLY_WHEN_RESTRAINED | NEVER`,
checked at **every** entry point (restrain, anchor, detain, nickname), `ASK` backed by a one-use
server consent session bound to actor/target/action/expiry; consent can never override a
server-disabled action. Bind breaker: a real rekey that invalidates old keys. Fuzzy cuff tooltip
corrected to arms-only. Leg-shackle description corrected to match the code.
*Deps*: M3.1, M5.10. *Tests*: `T:identity/PrivacyPolicyTest` (every entry point checks it),
`T:locks/BindBreakerTest`.

**M6.4 API and diagnostics.** New `J:api/model/{RestraintView,RestraintSlotView,TransportView,
DetentionView}`, five new `J:api/event/` classes, `J:api/RestraintRegistrationApi` for third-party
definitions, rigs, key families and inventory providers — explicit interfaces with unique ids,
independent state instances and bounded serialisation, never registry sniffing.
*Deps*: all of M2–M5. *Tests*: extend `T:api/PublicProjectionTest`, new
`T:api/ThirdPartyDefinitionTest`.

**M6.5 Commands.** Modify `J:command/CrimeCommand.java` and `J:command/RecoveryCommand.java` per
section 3.14.
*Tests*: extend `T:CrimeCommandTest`, `T:RecoveryCommandTest`.

**M6.6 Capital sentence model.** New `J:ledger/SentenceKind` and `J:ledger/CapitalSentenceService`;
new `KILL_GUARD` id in `J:crime/type/CrimeIds`, its `BuiltinCrimeTypes` row, its
`J:compat/CrimeIncidentMapping` entry and the `classifyKill` branch in `J:detect/CrimeClassifier`; the
sentence-kind field on the binding (`J:ledger/SentenceAssignmentService`, `J:state/world/CrimeWorldData`)
and on `J:captivity/CustodyRecord`; the marking call in `J:enforcement/ArrestService` and
`J:enforcement/NpcArrestService`; refusals in `J:ransom/RansomService`,
`J:action/handler/BailActionHandler` and `J:economy/SettlementQuote`; commutation and pardon as
privileged transactions through `J:ledger/CrimeCaseService`; new `J:jail/ReleaseReason.EXECUTED`; the
whole `sentencing.capitalPunishment` group of section 3.19 in `J:McaCrimeConfig.java`. Schema note:
the kind is an additive field defaulting to `CUSTODIAL` on load, so it rides the existing v14→v15
migration (M1.5) and adds one fixture assertion rather than a new case.
*Deps*: M1.5, M4.10. *Tests*: `T:ledger/CapitalSentenceEligibilityTest`,
`T:ledger/CapitalSentenceAssignmentTest`, `T:ledger/CapitalSentenceRefusalTest`; extend
`T:ArrestSentenceAssignmentTest` and `T:CombatIncidentTest` (a guard kill files `mcacrime:kill_guard`;
a mugging that kills a guard still files `mcacrime:mugging_murder` and is not capital).

**M6.7 Guard-carried execution.** New `J:enforcement/CondemnedEscortService` and
`J:enforcement/ExecutionSiteRegistry`; new `EXECUTION_SITE` role in `J:facility/FacilityRole`
(capacity 1, an explicit operator assignment exactly like `JAIL_CELL`,
`facility/FacilityRole.java:20-40`), plus a device-position index so a guillotine inside an assigned
guardhouse is found without a world scan. A guard holding lawful custody of a condemned captive takes
an `J:activity/CrimeActivityRegistry` claim of the same generation-checked shape as the cell escort,
reserves the device through the `J:facility/CellReservation` machinery, walks the captive there reusing
`J:enforcement/EscortService` and `J:enforcement/JailEscortNavigation` over the M4.3 transport arbiter,
places them, waits `executionDelayTicks`, then issues the order M4.10 authorises. Villager captives
take the same path through `J:enforcement/NpcCustodyService` (`EscortService` is player-typed today,
`enforcement/EscortService.java:140-160`, so the shared step logic is lifted rather than duplicated).
Failure is always identical: claim released, reservation released, captive returned to a cell, sentence
left live — on guard death, logout, dimension change, `condemnedEscortTimeoutTicks`, device destruction
or no device found at all. The whole sequence is server-side, so it works in single player with one
guard and one captive.
*Deps*: M6.6, M4.3. *Tests*: `T:enforcement/CondemnedEscortTest` (no device → continued custody;
rescue inside the window; guard death mid-escort; two guards, one claim), extend the facility-role and
activity-claim tests.

**M6.8 Capital API, commands and diagnostics.** New `J:api/model/CapitalSentenceView`; new
`J:api/event/{CapitalSentenceAssignedEvent,ExecutionCarriedOutEvent,SentenceCommutedEvent}`, all
post-commit; `J:api/McaCrimeApi.sentence(...)` reports the kind and any pending execution and
`JailSentenceView` gains the kind additively (`api/model/JailSentenceView.java:21-30`). Commands under
the existing `/crime` root (`command/CrimeCommand.java:114`): `capital inspect <target>` at level 2;
`capital commute <target>`, `capital pardon <target>` and `capital execute <target>` at level 3 — the
last is the operator's explicit act, never a shortcut past the device unless the device is absent and
the operator is overriding; and `debug capital` listing live capital sentences, pending executions,
claims and reservations. `/crime status` and the dossier say "condemned" plainly.
*Deps*: M6.6, M6.7. *Tests*: extend `T:CrimeCommandTest` and `T:api/PublicProjectionTest`; new
`T:api/CapitalSentenceViewTest`.

**Gate M6.** Exit: optional mods present or absent without crashes; every advertised integration has
meaningful behaviour and a verification note.

### M7 — Release candidate and obsolete-code removal  *(size: medium)*

**M7.1 Config completion.** `J:McaCrimeConfig.java` final group/key sweep;
`J:config/ConfigValidator.java` rule block per group (positive durability, finite damage and forces,
ordered distances — `maxChainLength < overextensionLength`, safe slot bounds, packet and storage
caps, session caps, retired-key report); mid-session change policy (snapshot the applicable profile
or cancel/revalidate the session).
*Tests*: extend `T:ConfigValidatorTest`; new `T:config/RetiredKeyReportTest`,
`T:config/ConfigGroupCoverageTest` (every key documented in `CONFIG.md`).

**M7.2 Presets.** `restraints.preset = CUFFED_PARITY` (default) | `BALANCED_VILLAGE`. The parity
preset is immediate application, independent slots, source durability, keys, native picking, chains,
fixed anchors, furniture and all functioning items, with low-health gating **off**. The balanced
preset enables application duration, vulnerability requirements, stricter player-capture permissions
and non-destructive picking, and is never imposed silently.
*Tests*: `T:config/PresetTest`.

**M7.3 Documentation.** `CHANGELOG.md` (Keep a Changelog, compatibility paragraph, Added/Changed/
Fixed with bolded lead-ins), `docs/MIGRATION.md` (0.7.5 section: protocol 15, schema 15, the physical
tables, the rope carrier, the retired config keys, the removed API projections),
`CONFIG.md` (every new key with default and range), `API.md` (new views, events, registration
interfaces, deprecations), `DATAPACK.md` (restraint profiles, the new tags, the bundle recipe
collision note, the damage-type tag reasoning), `CURSEFORGE.md`, `README.md`,
`docs/0.7.5/{BASELINE.md,CUFFED_INTEGRATION.md,VERIFICATION.md,PARITY_LEDGER.md,PROVENANCE.md}`.
*Deps*: everything.

**M7.4 MODMAP.** Regenerate with `python3 /home/otectus/Projects/.mcmod-tools/modmap.py` and add a
0.7.5 note **below** `MODMAP.md:186` (`AUTO:END`), preserving the hand-maintained content.

**M7.5 Version bump and packaging.** `gradle.properties`: `mod_version=0.7.5` (the only place a
version may appear). Build the jar and confirm `checkJarContents` passes.

**M7.6 Acceptance matrix.** Run the whole of specification section 21 against a fresh world and a
migrated schema-14 world, plus a dedicated-server launch without client classes and without any
optional mod. Audit classloading, duplicate listeners, legacy tick paths, registry and resource
coverage, the two protected item images inside the built jar, docs and licences. The capital-sentence
walkthrough is part of the matrix: a single-player guard-carried execution at an assigned guillotine,
and the same sentence with no guillotine present, which must end in continued custody.

**M7.7 Capital-sentence documentation and validation.** `CONFIG.md` gains the whole
`sentencing.capitalPunishment` group with defaults and ranges; `API.md` gains `CapitalSentenceView`,
`CapitalSentenceAssignedEvent`, `ExecutionCarriedOutEvent`, `SentenceCommutedEvent` and the extended
`JailSentenceView`; `CHANGELOG.md` and `docs/MIGRATION.md` state plainly that the only capital offence
is killing a guard, that nothing escalates automatically, that execution is always a deliberate act at
a device, and that one key disables the group; `docs/0.7.5/VERIFICATION.md` records the M7.6
walkthroughs. `J:config/ConfigValidator.java` gains the section 3.19 rule block
(`executionDelayTicks` ≤ `condemnedEscortTimeoutTicks`; both finite and non-negative;
`requiresExecutionDevice = false` refused unless `enabled = false`).
*Deps*: M6.6–M6.8. *Tests*: extend `T:ConfigValidatorTest` and `T:config/ConfigGroupCoverageTest`.

---

## 5. Removal list

### 5.1 Classes deleted

| Deleted | Replacement | Migration story |
|---|---|---|
| `captivity/CaptureChannel.java` | `restraints.application.channelTicks` inside `restraint/ApplicationTransaction` | never persisted; in-flight channels cancelled at load with a message, no item charged |
| `captivity/CaptureChannels.java` | `restraint/SessionRegistry` | transient static map, nothing to migrate |
| `captivity/CaptureService.java` | `restraint/RestraintService` + `ApplicationTransaction` | behaviour moves; the availability probe becomes `RestraintService.evaluate` |
| `captivity/CaptureTicker.java` | server-tick hook inside `restraint/RestraintService` | the pure `commit(...)` sequence is preserved as `ApplicationTransaction.commit` |
| `captivity/CaptureVulnerability.java` | `restraints.application.vulnerabilityGates` | gate list preserved as config; off in the parity preset |
| `captivity/CuffEscapeService.java` | `restraint/EscapeService` + `lockpick/LockpickService` | active escape attempts cancelled by the reconciler |
| `captivity/CuffLockProgress.java` | `lockpick/LockpickSession` | `cuffCombination` archived under `reserved`, then retired |
| `captivity/CustodyConfine.java` | `tether/TetherService` (`LEGACY_HOLD` kind) | old `holdPos` becomes a legacy hold record; no block or chain fabricated |
| `enforcement/EscortRestraint.java` | `tether/EscortTransport` | no persistent state |
| `enforcement/RestraintPolicy.java` | `restraint/RestrictionResolver` | the arrest-phase `RESTRAINED → CUFFS` default becomes a system-issued `handcuffs_arms` instance |
| `enforcement/RestraintSync.java` | `restraint/RestraintSyncService` | packet shape replaced; protocol bump refuses old peers |
| `enforcement/RestraintVisualResolver.java`, `RestraintVisualState.java`, `RestraintVisualType.java` | `restraint/PhysicalRestraintState` + `client/ClientPhysicalState` | client-only projection, nothing persisted |
| `client/render/RestraintWristLayer.java`, `client/render/RestrainedPose.java` | `client/render/restraint/RestraintSlotLayer` + nine models | `textures/entity/cuffs.png` is superseded; keep the file only if something still references it, otherwise delete with the layer |
| `client/ClientRestraintData.java` | `client/ClientPhysicalState` | client cache |
| `compat/locksreforged/CuffLockPickingMenu.java` | `lockpick/LockpickService` | an open Locks cuff menu is closed at load; its escape attempt is cancelled |
| `captivity/RestraintType.java` **(retained, demoted)** | `RestraintDefinition` | kept only as a deprecated projection for `EntityKidnappedEvent` and `CustodyView`; never written to world data again |

### 5.2 Config keys retired

`kidnapping.captureChannelTicks`, `captureMaxMoveBlocks`, `captureMaxRangeBlocks`,
`captureRequireLineOfSight`, `captureLowHealthFraction`, `villagerCaptureRelaxedVulnerability`,
`captureChannelMultiplierRope|Cuffs|LockedCuffs`, `restraintEscapeChanceRope|Cuffs|LockedCuffs`,
`captiveTetherBlocks`, `captiveCanEscapeByDistance`, `escapeWorkTicksRope|Cuffs|LockedCuffs`,
`cuffEscapeRequiresLockpick`, `escapeAttemptCooldownTicks`
(all `McaCrimeConfig.java:906-956`); client `renderCuffs`, `renderEscortRope`.
Replacements are named one-for-one in `CONFIG.md` and `docs/MIGRATION.md`; `ConfigValidator` reports
any survivor found in an existing `.toml`. Values are **not** auto-migrated; the drop-and-document
policy is settled (section 10.5), with the one-for-one mapping in `CONFIG.md` and `docs/MIGRATION.md`.
`locksReforgedFenceTrades` and every `jail`, `ransom` and legal `kidnapping` key stay.

### 5.3 Packets removed or replaced

`RestraintSyncS2CPacket` and `RestraintBulkSyncS2CPacket` are replaced by
`PhysicalStateS2CPacket` / `PhysicalStateDeltaS2CPacket` / `PhysicalStateRemoveS2CPacket`;
`RestraintRigSyncS2CPacket` is retained. `CaptiveStatusS2CPacket` keeps its legal payload and loses
its restraint field. The protocol bump to `"15"` makes an old client's handshake fail cleanly instead
of decoding a multi-slot snapshot as the old enum packet.

### 5.4 Commands changed

`/crime escape` becomes a physical-escape request routed through `EscapeService` (no longer a timed
roll); `/crime releasecaptive` keeps its legal meaning and now also clears the physical claims owned
by that custody. `/crime release` is unchanged in meaning. New subtrees per section 3.14.

### 5.5 Resources removed or replaced

`assets/mcacrime/textures/entity/cuffs.png` (superseded by the worn restraint textures);
`data/mcacrime/recipes/restraint_rope.json` (rope is no longer craftable; the conversion recipe
replaces it); `restraint_cuffs.json` and `restraint_locked_cuffs.json` recipe **contents** replaced by
upstream's shackle/handcuff shapes while keeping the filenames. The `mcacrime:restraints` and
`illicit_goods` tags keep all three ids. Fence prices for rope are retired and re-pointed at
`duck_tape`.

### 5.6 Tests deleted or rewritten

| Test | Disposition |
|---|---|
| `RestraintVisualTypeTest` | deleted with the enum |
| `RestraintPolicyTest` | rewritten as `restraint/RestrictionCompositionTest` |
| `CuffEscapeTest` | rewritten as `restraint/EscapeServiceTest` |
| `CaptureChannelTest`, `CaptureVulnerabilityTest`, `CaptureCommitTest` | rewritten as `restraint/ApplicationTransactionTest` |
| `CustodyRecordNbtTest` | extended for `custodyId`, `generation` and the physical reference |
| `RestraintSyncPacketTest` | rewritten for the multi-slot snapshot |
| `CustodyRecordNpcLawfulTest`, `CrimeWorldDataCustodyTest`, `CaptivityTickTest`, `ArrestSentenceAssignmentTest`, `HoldingCellJournalTest`, `MandatoryCustodyTest`, `ThiefCustodyRecoveryTest`, `AccompliceCustodyInvariantTest` | constructor updates only; assertions preserved |
| `FenceGoodsRegistryTest`, `FenceOfferBuilderTest`, `FencePriceLoaderTest`, `WeaponRulesTest`, `ArmedResolverTest` | id lists extended with the new goods |
| `OptionalClassloadTest` | expected file sets updated for `compat/locksreforged/` (shrinks) and every new adapter package |
| `MixinConfigTest` | three new common mixins |
| `LangCoverageTest`, `MaskResourceCoverageTest`, `ClientConfigIsolationTest`, `CrimeCommandTest`, `PacketBoundsTest`, `ConfigSweepTest`, `NoMcaStaticLinkTest`, `NoTownsteadStaticLinkTest`, `TownsteadMixinTargetTest`, `McaBindingProbeTest` | must keep passing unchanged except for additive expectations |

No failing test is deleted to make the build green; each is replaced by the test of the behaviour
that took its place.

---

## 6. Traceability

### 6.1 Specification section 4 feature inventory

| ID | Steps | Principal files | Tests |
|---|---|---|---|
| R01 arm handcuffs | M1.1, M2.1–M2.3, M2.7, M2.8, M2.10 | `restraint/RestraintDefinitions`, `item/CrimeItems` | `RestraintDefinitionsTest`, `RestrictionCompositionTest` |
| R02 arm shackles | as R01 | same | same |
| R03 leg handcuffs | as R01 + M2.9 (`RestraintJumpMixin`) | `restraint/RestraintAttributes` | `RestrictionCompositionTest` |
| R04 leg shackles | as R03 | same | `RestrictionCompositionTest` (walking allowed) |
| R05 fuzzy handcuffs | M2.1, M2.2, M5.12 | `item/CrimeItems` (no recipe; Fence + creative), art bucket (c) | `CrimeItemsRegistrationTest`, `RestraintResourceCoverageTest` |
| R06 tape head/arms/legs | M2.1–M2.3, M2.7 | `item/tool/DuckTapeItem` | `RestraintDurabilityTest` (independent leg keys) |
| R07 bundle hood | M2.3, M2.10 | `restraint/RestraintDefinitions` (`bundle`), `client/HoodOverlayHandler` | `HoodOverlayOwnershipTest` (filled bundle refused) |
| R08 simultaneous restraints | M1.1, M2.8 | `RestrictionResolver` | `RestrictionCompositionTest` |
| R09 body-area application | M2.4, M2.5 | `restraint/BodyRegionResolver` | `BodyRegionResolverTest` |
| R10 self application/removal | M2.6 | `restraint/RestraintService`, self panel | `ApplicationTransactionTest` |
| R11 dispenser application | M2.6 | `restraint/DispenserRestraintBehavior` | `DispenserBehaviorTest` |
| R12 restraint keys | M2.1, M2.7 | `item/restraint/RestraintKeyItem` | `EscapeServiceTest` |
| R13 struggling and durability | M2.2, M2.7, M2.10 | `restraint/EscapeService`, HUD section | `EscapeServiceTest`, `ItemReturnPolicyTest` |
| R14 native lockpicking | M3.3 | `lockpick/*` | `LockpickSessionTest`, `LockpickProfileTest` |
| T01 direct escort | M4.3 | `tether/EscortTransport` | `TransportArbiterTest`, `EscortHandoverTest` |
| T02 forced seating/mounting | M4.4 | `tether/MountTransfer` | `MountTransferTest` |
| T03 chains | M4.1 | `tether/TetherService` | `TetherOwnershipTest` |
| T04 fixed anchors | M4.2 | `entity/ChainKnotEntity` | `AnchorTransferTest` |
| T05 weighted anchors | M4.2 | `entity/WeightedAnchorEntity` | `WeightedAnchorPickupTest` |
| T06 tension and suspension damage | M4.1 | `tether/TetherDamage`, `mcacrime:hang` | `TetherPhysicsTest` |
| I01 frisking | M5.2, M5.3 | `frisk/*`, `menu/FriskingMenu` | `FriskTransactionTest`, `FriskingClickRejectionTest` |
| I02 possessions boxes | M5.1 | `inventory/PossessionsStore` | `PossessionsBoundsTest` |
| L01 padlocks | M3.4 | `entity/PadlockEntity`, `locks/LockService` | `PadlockLifecycleTest` |
| L02 bound keys | M3.1, M3.2 | `item/lock/KeyItem` | `LockAccessTest` |
| L03 key rings | M3.2 | `item/lock/KeyRingItem` | `KeyRingRecipeTest` |
| L04 key copying | M3.2 | `recipe/lock/*` | `KeyMoldTest` |
| L05 cell doors | M3.4 | `block/CellDoorBlock` | `CellDoorLockTest` |
| L06 safes | M3.5 | `block/entity/SafeBlockEntity` | `SafeAutomationTest`, `SafeContentsLifecycleTest` |
| P01 reinforced construction | M5.4 | `block/prison/*` | `ReinforcedPolicyTest` |
| P02 pillories | M4.5 | `block/PilloryBlock`, `detention/DetentionService` | `PilloryOccupancyTest` |
| P03 guillotines | M4.6 | `block/entity/GuillotineBlockEntity` | `GuillotineExecutionTest` |
| P04 bunks | M4.7 | `block/BunkBlock` | `BunkRespawnTest` |
| P05 posters | M5.8 | `block/PosterBlock` | `PosterVariantTest` |
| P06 meal trays | M5.7 | `block/entity/TrayBlockEntity` | `TrayContentsTest` |
| P07 forks and spoons | M5.5 | `block/prison/ExcavationService` | `ExcavationProgressTest` |
| P08 knife and wounds | M5.9 | `wound/WoundService` | `WoundLifecycleTest` |
| P09 bandages | M5.9 | `item/tool/BandageItem` | `WoundLifecycleTest` |
| P10 prisoner tags | M5.10 | `identity/IdentityService` | `NicknameTest` |
| E01 Imbue | M6.1 | `enchantment/ImbueHandler` | `ImbueDistributionTest` |
| E02 Famine/Shroud/Exhaust | M6.1 | `enchantment/CrimeEnchantments` | `EnchantmentApplicabilityTest` |
| E03 Silence | M6.1, M6.2 | mana adapters | `AdapterAbsenceTest` |
| E04 Buoyant | M4.2, M6.1 | `entity/WeightedAnchorEntity` | `WeightedAnchorPickupTest` |
| E05 vanilla enchantments | M2.7, M6.1 | `restraint/EscapeService` (Unbreaking), death path (Binding) | `EscapeServiceTest` |
| A01 creative utility items | M2.1 | `item/creative/*` | `CreativeAuthorizationTest` |
| A02 commands and extension API | M6.4, M6.5 | `api/*`, `command/*` | `ThirdPartyDefinitionTest`, `CrimeCommandTest` |
| A03 presentation and resources | M2.10, M2.12, M3.8, M4.9, M5.12 | assets/data | `check_mod.py`, `LangCoverageTest`, coverage tests |
| A04 statistics | M5.11 | `stat/CrimeStats` | `StatAwardOnceTest` |
| C01 existing working compat | M6.2 | `compat/*` | `AdapterAbsenceTest`, `OptionalClassloadTest` |
| X01 capital sentence for killing a guard | M6.6, M6.8 | `ledger/SentenceKind`, `ledger/CapitalSentenceService`, `crime/type/CrimeIds` (`kill_guard`) | `CapitalSentenceEligibilityTest`, `CapitalSentenceAssignmentTest`, `CapitalSentenceRefusalTest` |
| X02 execution at a guillotine | M4.6, M4.10 | `detention/ExecutionAuthorization`, `block/entity/GuillotineBlockEntity` | `CapitalExecutionAuthorizationTest`, `CapitalDeathOutcomeTest` |
| X03 guard-carried execution | M6.7 | `enforcement/CondemnedEscortService`, `facility/FacilityRole.EXECUTION_SITE` | `CondemnedEscortTest` |

X01–X03 are **not** Cuffed specification features: the user scoped them in (section 10.7) and they are
specified in section 3.19. They are listed here so the parity ledger of M0.2 carries them too, marked
as user-scoped rather than upstream parity.

### 6.2 Specification section 5 dormant content — dispositions

| Entry | Disposition | Step |
|---|---|---|
| Warden's Guide / booklet | **Complete and register** an MCA: Crime guide with rewritten text | M6.3 |
| Toilet | **Complete** as a furnishing with its own registration, storage, saving and drops; no sewage simulation | M6.3 |
| Privacy restrictions | **Complete** as an optional server-enforced policy checked at every entry point | M6.3 |
| TacZ | **Implement a real version-probed adapter or ship no claim**; never set the voice-chat flag from it | M6.2 |
| Knights of Britannia | **Implement against an explicitly supported build or ship no claim**; never create scoreboard objectives speculatively | M6.2 |
| Creative bind breaker | **Implement** a distinct reset that rotates the binding revision and invalidates old keys | M6.3 |
| Fuzzy cuff scope | **Arms-only**, tooltip corrected; a leg variant is a documented future enhancement | M2.1, M6.3 |
| Leg shackles | **Walking allowed**, sprint/jump blocked, one policy on both sides; lang corrected | M2.8, M6.3 |
| Tape leg settings | **Independent leg durability and break settings** | M2.2 |
| Lockpicking a door/safe | Both outcomes: configurable non-destructive unlock (default) and the named Cuffed parity destruction; a broken safe moves its contents exactly once first | M3.3 |
| Reinforced blocks | **Honest qualified resistance**, with hard containment as a separate explicit policy | M5.4 |

### 6.3 Confirmed upstream defects — planned fix locations

| Defect (upstream evidence) | Fixed in |
|---|---|
| Client-decided lockpick outcome and client-named actor (`api/CuffedAPI.java:130,162,194`; `packet/Lockpick*`) | M3.3 `lockpick/LockpickService` — server-computed outcome, identity from the connection |
| Unvalidated durability delta (`restraints/base/AbstractRestraint.java:301-307`) | M2.7 `restraint/EscapeService` — server owns durability; no delta is accepted |
| `FriskingContainer.stillValid`/`clearContent`/`removeItem` (`inventory/FriskingContainer.java:69-102,130-142`) | M5.2 — read-only projection + explicit transaction; no vanilla container |
| Imbue duplicate recipients, in-loop accumulator, recursion (`event/ModServerEvents.java:399-446`) | M6.1 `enchantment/ImbueHandler` |
| `WoundedEffect` damage on add and remove (`effect/WoundedEffect.java:50,67`) | M5.9 `wound/WoundService` |
| Guillotine dangling-else double kill (`blocks/entity/GuillotineBlockEntity.java:308-313`) | M4.6 |
| `HumanoidModelMixin` shared-part mutation and cancelled `setupAnim` (`mixin/HumanoidModelMixin.java:39-86`) | M2.10 — that mixin is not ported; the existing `LivingEntityRenderer` injection point is used |
| Chain duplication on unanchor (`mixin/LivingEntityMixin.java:64-79`) | M4.1 — idempotent `detach`, ownership recorded once |
| Invalid `ResourceLocation` on reinforced-padlock drop (`entity/PadlockEntity.java:178` vs `:86`) | M3.4 |
| Escort teleport before validity checks (`cap/RestrainableCapability.java:113-126`) | M4.3 — validity first, bounded correction, no teleport |
| `breakCooldown` never decremented (`restraints/custom/HandcuffsArmsRestraint.java:264,288`) | M2.7 |
| Self-application pitch test mixes degrees and radians (`mixin/PlayerMixin.java:206,209`) | M2.6 — explicit slot selector |
| Forged dispenser captor (`items/base/AbstractRestraintItem.java:227-245`) | M2.6 |
| `KeyRingItem.getBoundIdIndex` uses `==` on UUIDs (`items/KeyRingItem.java:183`) | M3.2 |
| `ResourceLocation` compared with `==` (`mixin/HumanoidModelMixin.java:62`) | M2.10 |
| `LocalPlayerMixin.canStartSprinting` queries the leg slot three times (`:186,195,204`) | M2.8 |
| `ChainKnotEntity.isOnFence()` inverted (`entity/ChainKnotEntity.java:55-57`) | M4.2 |
| `WeightedAnchorEntity` synched data declared on `Player.class` (`:47`) | M4.2 |
| `ModDamageTypes.GetModSource` ignores its type argument (`init/ModDamageTypes.java:23-25`) | M4.1, M6.1 — two distinct damage types |
| `onCommand` cancels every command for a restrained non-op (`event/ModServerEvents.java:448-459`) | M2.8 — not ported; commands are never blanket-cancelled |
| Restraint durability not serialised (`restraints/base/AbstractRestraint.java:257-275`) | M1.1 — durability is a persisted field of `AppliedRestraint` |
| `LockableBlockEntity.load` unguarded `getUUID` (`:50`) | M3.4 |
| `GuillotineBlockEntity.chopDelay` not persisted (`:327` vs `:339-344`) | M4.6 |
| Statistics never awarded for tape/bundle/pillory (`init/ModStatistics.java:88-123`) | M5.11 |
| Full-world entity scan per block right-click (`event/ModServerEvents.java:165-171`) | M4.1 — reverse indices |
| Missing `tray` loot table, no advancements (upstream's `sounds.json` is present and complete; its six sounds are replaced by vanilla `SoundEvent`s under the resolved provenance policy, not missing) | M3.8, M5.12 |
| `SAFE_SLOTS` dead, safe hard-coded to 27 (`blocks/entity/SafeBlockEntity.java:173-175`) | M3.5 — one authoritative profile value, 36 |

---

## 7. Port parity part — NeoForge 1.21.1

Checkout: `/home/otectus/Projects/1.21.1 Ports/MCACrime_1.21.1`. `S:` =
`src/main/java/dev/otectus/mcacrime/`. The port's own `CLAUDE.md` governs; where it differs from the
baseline's rules, it wins. The port is **not deferred**: it lands in the same job, one commit,
titled in the established form ("Bring MCA Crime 0.7.5 to the NeoForge 1.21.1 port"), after the
baseline release commit.

Port steps are keyed to baseline steps as `P<M>.<n> ← M<M>.<n>`.

### P0 ← M0  *(size: small)*
P0.1 Run the port's own gates first: `test` and `build`. Record pre-existing failures.
P0.2 `docs/0.7.5/PARITY.md` on the port line, in the established form (included behaviour, native
platform handling, schema and protocol numbers, test counts).
P0.3 Copy `ProtectedTextureHashTest` (the same two files exist on this line). Note: the port resolves
project files through the `mcacrime.projectRoot` system property, so the test must use it.
P0.4 **Do not** run `check_mod.py` or `modmap.py`; there is no `MODMAP.md` here.

### P1 ← M1  *(size: medium)*
P1.1 Mirror `restraint/`, `tether/`, `detention/`, `locks/`, `identity/` value types class-for-class,
same names and packages.
P1.2 `state/world/CrimeWorldData`: the new tables must thread `HolderLookup.Provider` through
`save`/`load`/`readInto` (`:2057,2227,2258`), because `AppliedRestraint.itemSnapshot` serialises
through `ItemStack.CODEC` rather than `ItemStack.save(CompoundTag)`.
P1.3 `CrimeDataMigrations`: `SCHEMA_CUFFED_PHYSICAL = 15`, same pure `v14to15`, same
`CrimeDataMigrationsV14toV15Test`. **Bump the GameTest that asserts the live world's schema number.**
P1.4 Network: `PROTOCOL_VERSION = "15"`; the three physical-state payloads become
`record … implements CustomPacketPayload` with a `Type<>` id and `StreamCodec.composite(...)`
(pattern at `S:network/RestraintSyncS2CPacket.java:37-51`; a record component may not be named
`type`). Every new S2C handler routes through `S:network/CrimeClientPayloadRouter` — common code may
not name a client class, and `DedicatedServerIsolationTest` enforces it.
P1.5 `S:item/CrimeDataComponents.java` **(port-only, no baseline counterpart)**: registered
`DataComponentType`s with `Codec` + `StreamCodec` for the lock binding (`lockId`,
`bindingRevision`, name), key-ring contents, poster variant, tray contents, possessions-box contents
and the restraint durability snapshot. This replaces the baseline's item-NBT tags. The port registers
no `DataComponentType` today, so this is a new `DeferredRegister` and a new line in the `McaCrime`
constructor.

### P2 ← M2  *(size: large)*
P2.1 Items: `DeferredRegister.createItems`, `DeferredItem<Item>`; the creative tab entry order mirrors
the baseline. `RestraintItem` keeps its family field (`S:item/RestraintItem.java:12-23`).
P2.2 Dispenser behaviours are the **first** on this line: `DispenserBlock.registerBehavior` inside
`FMLCommonSetupEvent#enqueueWork` (the port already has such a block at `S:McaCrime.java:130-166`).
P2.3 Event renames the baseline code must be translated through:
`TickEvent.ServerTickEvent` → `ServerTickEvent.Pre/.Post`; `LivingDamageEvent` →
`LivingIncomingDamageEvent` / `LivingDamageEvent.Pre` / `.Post`; `LivingAttackEvent` →
`LivingIncomingDamageEvent`.
P2.4 The three new mixins are mirrored with `remap = false` on every annotation and declared in
`mcacrime.mixins.json`, which is itself declared by `[[mixins]]` in `neoforge.mods.toml` — **not** by
a jar-manifest attribute. `checkJarContents` asserts exactly two configs and no refmap.
P2.5 Rendering: model layers through `EntityRenderersEvent.RegisterLayerDefinitions` and `AddLayers`
(`S:client/render/CrimeRenderLayers.java:45,50`), HUD through `RegisterGuiLayersEvent` +
`VanillaGuiLayers` with a `(GuiGraphics, DeltaTracker)` callback, screens through
`RegisterMenuScreensEvent` (`MenuScreens.register` throws here), client item extensions through
`RegisterClientExtensionsEvent`.
P2.6 Menus: `IMenuTypeExtension.create((id, inv, buf) -> …)` with a `RegistryFriendlyByteBuf`;
`IForgeMenuType` does not exist. Menu opening is `serverPlayer.openMenu(new SimpleMenuProvider(…), …)`.

### P3 ← M3  *(size: large)*
P3.1 **Block entities are the first on this line**: a new
`DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, …)`, `BlockEntityType.Builder.of(…).build(null)`,
`loadAdditional/saveAdditional(CompoundTag, HolderLookup.Provider)`,
`getUpdateTag(HolderLookup.Provider)`/`getUpdatePacket`, and renderer registration through
`EntityRenderersEvent.RegisterRenderers#registerBlockEntityRenderer`.
P3.2 Safe automation protection uses NeoForge **block capabilities**
(`Capabilities.ItemHandler.BLOCK` registered in `RegisterCapabilitiesEvent`) and must call
`level.invalidateCapabilities(pos)` on every lock-state change — the structural equivalent of the
baseline's `LazyOptional` invalidation, and the port's only correct way to stop a cached handler.
P3.3 Lock bindings live in `CrimeDataComponents` (P1.5), not item NBT.

### P4 ← M4  *(size: medium-large)*
P4.1 Entities: `EntityType.Builder.of(...).sized(...).clientTrackingRange(...).updateInterval(...)`.
`ChainKnotEntity` and `PadlockEntity` need extra spawn data; use vanilla's add-entity packet plus
`IEntityWithComplexSpawn` (**confirm the interface name with `find_api.py` first** — the port
implements it nowhere today). `WeightedAnchorEntity` is a `LivingEntity`, so it needs an
`EntityAttributeCreationEvent` handler on the mod bus — the port has none yet.
P4.2 Damage types are datapack JSON on both lines; no code difference.
P4.3 ← M4.10. Execution authorisation is plain common code and ports unchanged: the pending-execution
record lives in world data, not in an entity attachment, so the port needs no `DataComponentType` for
it and no new 1.21.1 API.

### P5 ← M5  *(size: large)*
P5.1 Tray, poster and possessions-box contents are `DataComponentType`s, not NBT tags.
P5.2 Custom statistics: the port registers none today; add the same `Registry.register(
BuiltInRegistries.CUSTOM_STAT, …)` pass in common setup.
P5.3 Sounds and particles: the port has no custom sound or particle registry either; add the same
two `DeferredRegister`s and the same `sounds.json` (subject to the same provenance gate).

### P6 ← M6  *(size: medium)*
P6.1 **Enchantments are data-driven here.** The six Java enchantment classes of the baseline are
**not** ported. Instead: six JSON files under `src/main/resources/data/mcacrime/enchantment/`, a new
`S:enchantment/CrimeEnchantments.java` holding six `ResourceKey<Enchantment>` constants, and
holder-based lookups (`registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key)`), with
"does this stack have it" answered by an `EnchantmentHelper` effect-component query — the pattern
already used at `S:loot/DeathLoot.java:38`. Confirm the exact API with
`find_api.py --project "<port dir>" --class Enchantment` before writing.
P6.2 Optional adapters mirror the baseline one-for-one; `OptionalClassloadTest` is updated here too.
P6.3 ← M6.6–M6.8. Capital sentencing ports one-for-one: the crime id, the sentence kind, the refusals,
the condemned escort and the commands are all common code with no 1.21.1 API surface of their own. Two
differences: the new lang keys and the `EXECUTION_SITE` facility role must be added to the port's own
resources, and the behavioural proof here is a GameTest (7.2) rather than JUnit alone.

### P7 ← M7  *(size: medium)*
P7.1 `gradle.properties`: `mod_version=0.7.5` only.
P7.2 `CHANGELOG.md` gains a **Notes** section naming each baseline class the port deliberately omits
and the structural reason (list below), following the established precedent at `CHANGELOG.md:989-999`.
P7.3 `docs/0.7.5/PARITY.md` completed with the test counts and the schema/protocol numbers.
P7.4 `docs/0.7.5/PARITY.md` and the port `CHANGELOG.md` state that capital sentencing is present on
both lines with identical defaults, and that it is user-scoped rather than Cuffed parity.

### 7.1 Baseline classes the port deliberately omits, with reasons

1. `state/CrimeCapabilities`, `state/PlayerCrimeDataProvider`, `state/CrimeCapabilityEvents` — already
   absent; `AttachmentType.serializable(...)` plus `.copyOnDeath()` covers creation, serialization,
   lifetime and the death copy structurally (`S:state/CrimeAttachments.java:27-34`). Nothing in this
   release changes that, and no restraint attachment is added: physical state lives in
   `CrimeWorldData` on both lines.
2. The baseline's item-NBT helper class for lock bindings, key rings, poster variant, tray and box
   contents — replaced by registered `DataComponentType`s (P1.5). 1.21.1 has no item NBT.
3. The baseline's six `enchantment/*Enchantment.java` classes — replaced by data-driven enchantment
   JSON plus `ResourceKey` constants (P6.1). `Enchantment` is a registry *data* entry in 1.21.
4. Any `LazyOptional` capability wrapper for the safe's item handler — replaced by NeoForge block
   capabilities plus `invalidateCapabilities` (P3.2).
5. `IForgeMenuType`-based menu registration — replaced by `IMenuTypeExtension` (P2.6).
6. A jar-manifest `MixinConfigs` entry — NeoForge does not honour it; `[[mixins]]` in
   `neoforge.mods.toml` is the only declaration (P2.4).
7. A Forge-style `NetworkHooks.openScreen` call — replaced by `openMenu(MenuProvider, …)`.

**Caution.** This line has already walked back two "structurally unnecessary" claims
(`ServerPacketGuard` and `PacketBounds`, `CHANGELOG.md:991-999`). Every omission above must be
covered by an explicit test or GameTest rather than an argument.

### 7.2 GameTests expected on the port (baseline has none)

Extend `S:gametest/CuffParityGameTests.java`, and add:
`RestraintSlotGameTests` (apply each definition in each slot on a real server; assert the payload the
fake `ServerGamePacketListenerImpl` captures), `LockpickSessionGameTests` (a forged attempt is
rejected; a stale session against a replaced lock fails), `FriskTransferGameTests` (exact counts and
components preserved; concurrent searchers), `TetherLifecycleGameTests` (chunk unload, holder death,
detach yields one chain), `DetentionDeviceGameTests` (pillory claim race, guillotine cancelled death),
`SafeAutomationGameTests` (hopper and cached handler after a lock change), `CapitalSentenceGameTests`
(on a real server a guard escorts a condemned captive to an assigned guillotine and the sentence is
carried out exactly once; with the device removed the captive stays in custody and nothing dies), and a
bump to the existing schema-number GameTest.

### 7.3 Port build and check commands (from its `CLAUDE.md`)

- `C:\Projects\.mcmod-tools\gradlew-quiet.ps1 -Project "<project-dir>" -Task compileJava | test | build`
  — the Linux equivalent on this machine is
  `/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh "/home/otectus/Projects/1.21.1 Ports/MCACrime_1.21.1" <task>`
  (**unresolved**: confirm this substitution is sanctioned before the first run).
- GameTests run through the `gameTestServer` run configuration
  (`neoforge.enabledGameTestNamespaces=mcacrime`); confirm the Gradle task name in `build.gradle`
  before first use.
- Optional-companion switches for a release build: `-PrequireLocks=true`, plus
  `-PmcaReputationClasses` / `-PmcaQuestsClasses` / `-PtownsteadJar` as applicable.
- **Forbidden here**: `check_mod.py`, `modmap.py`.

---

## 8. Risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| 1 | **Provenance.** Upstream declares GPL-3.0 in `LICENSE`/README but ships `All Rights Reserved` in its jar metadata, has no credits file, and its posters and fuzzy art are unattributed third-party-looking work | High (the discrepancy is confirmed) | Original-art authoring load lands on this release; a late discovery of a further unattributable asset adds more art at RC time | **Policy resolved (section 10.1)**: every unattributable Cuffed-origin asset is authored as original MCA: Crime work under the same registry id, so nothing waits on author contact and no code path depends on the outcome; covered material is adapted with attribution in `docs/0.7.5/PROVENANCE.md` and `CREDITS.md`; M0.3 manifests every artefact and M0.5 fixes the buckets before M2, so the authoring list is known early |
| 2 | **Save corruption / item duplication in migration.** Schema 15 touches custody, adds five tables and reconciles physical state from records that never stored an item | Medium | Catastrophic and user-visible | Pure `v14to15` separate from an idempotent reconciler with a `reconciledSchema` marker; fixtures for all eleven section 21.5 cases; a repeated-load test asserting zero extra items; `frozen()` on every new mutator plus `FrozenGuardCoverageTest` |
| 3 | **Exploit surface.** Every upstream feature being imported was client-authoritative | High if copied naively | Server-side item duplication, arbitrary block destruction, freeing any player | No packet carries an outcome, an actor identity, a durability delta or an unbound position; `ActionValidation` is a shared, tested helper; explicit negative tests per packet (section 9.3) |
| 4 | **Chain/tether tick cost.** Upstream scans all world entities on every block right-click and all players on every damage event | Medium | Server tick regression on populated worlds | Reverse indices by subject/holder/anchor; only loaded tethers tick; bounded reconciliation passes; a measured fixed-scenario tick/packet cost recorded in `docs/0.7.5/VERIFICATION.md` — no invented percentage |
| 5 | **Mixin fragility.** Three new `required:true` vanilla mixins; a bad target stops the game, not the build | Medium | Hard launch failure | Targets are stable vanilla methods; `find_api.py` confirms each signature before writing; `MixinConfigTest` covers registration and side split; the M7 client and dedicated-server launches are the real proof; the port additionally needs `remap = false` everywhere |
| 6 | **MCA binding gaps** for villager body scale, equipment access and movement control | Medium | Restraints render at wrong coordinates or cannot be applied to villagers | `BodyRegionResolver` is rig-relative, not world-height based; an unsupported rig returns an explicit unavailable reason instead of a default; every MCA access goes through `compat/mca/McaBinding` and degrades to a stub; `McaBindingProbeTest` replays the binding against every jar in `mca_probe_versions` |
| 7 | **Townstead interplay.** Facilities, custody care, storage policy and work claims all touch the same villagers | Medium | Double ownership of a villager, or work claims never restored | Physical holds go through `activity/` claims with generations; `CustodyCarePolicy` keeps its precedence (critical needs may suspend confinement without pardoning); no Townstead type is named outside `compat/townstead`; `NoTownsteadStaticLinkTest` and `TownsteadMixinTargetTest` keep passing |
| 8 | **Epic Fight shim.** Battle mode already cancels the use key; a multi-slot restraint menu adds more paths | Medium | Restraint menu unreachable in battle mode | `client/EpicFightInteractShim` is extended in the same shape and tested by `EpicFightInteractShimTest`; no global combat-mode preference is changed, and none may survive release or disconnect |
| 9 | **Jar shading / classloading.** Many new optional adapters | Low | Build failure or a dedicated-server crash | `checkJarContents` stays wired into `build`; `OptionalClassloadTest` expectations updated per adapter; a dedicated-server launch with no optional mod is an M7 exit criterion |
| 10 | **Test-suite churn.** ~25 existing tests are rewritten or retargeted across 220 | High (certain) | Real regressions hidden by mass edits | Section 5.6 lists each test and its disposition; rewrites land in the same step as the behaviour they cover; no failing test is deleted to go green |
| 11 | **Scope.** This is the largest single release in the project's history, on two lines | High | Partial delivery advertised as "everything from Cuffed" | Milestones are independently releasable gates; a partial stage is never published under that claim (specification section 4 preamble); the parity ledger is the release gate |
| 12 | **Config break for existing servers.** Retired `kidnapping.*` keys vanish silently on rewrite | Medium | Servers silently revert to new defaults | `ConfigValidator` reports every retired key found in the raw file with its replacement; `docs/MIGRATION.md` carries the one-for-one mapping; the drop-and-report policy is settled (section 10.5) and `T:config/RetiredKeyReportTest` proves the report |
| 13 | **Port drift.** Data components, data-driven enchantments and first-ever block entities make the port more than a translation this time | Medium | Port lands late or diverges behaviourally | Port steps are keyed one-to-one to baseline steps; GameTests carry the behavioural proof; `docs/0.7.5/PARITY.md` records every intentional divergence |
| 14 | **Capital sentencing.** A permanent, irreversible outcome added to a legal system that has only ever jailed, with an NPC able to take part in carrying it out | Medium | A player or villager killed by a bug or a griefed execution, or a captive stuck forever under a sentence nothing can carry out | The offence gate is one new crime id plus one config flag (section 3.19); no timer, packet or redstone path ever kills — only a player or an on-duty guard acting at a real guillotine through `ExecutionAuthorization` (M4.10); `executionDelayTicks` leaves a rescue and pardon window; with no device the fallback is explicitly continued custody, never a substitute death; `CapitalExecutionAuthorizationTest`, `CondemnedEscortTest` and the M7.6 walkthroughs are exit criteria; one key disables the whole group |

---

## 9. Proposed checks

**No check in this section has been run.** This planning job ran no build, test or tool.

### 9.1 Per-milestone commands — baseline

Let `B = /home/otectus/Projects/MCACrime`, `G = /home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh`.

| Milestone | Command | Required output |
|---|---|---|
| M0 | `$G $B check` then `$G $B build` | recorded baseline result; any failure attributed to the pre-existing tree, not to this work |
| M0 | `python3 /home/otectus/Projects/.mcmod-tools/check_mod.py MCACrime` | recorded baseline list of missing models/lang/textures |
| M0 | `sha256sum src/main/resources/assets/mcacrime/textures/item/restraint_{cuffs,locked_cuffs}.png` | exactly the two hashes in section 1.3 |
| M1–M6 iteration | `$G $B compileJava` | `BUILD SUCCESSFUL` |
| end of each milestone | `$G $B check` | all JUnit green, including the new tests named in section 4 and the untouched gates `NoMcaStaticLinkTest`, `NoTownsteadStaticLinkTest`, `MixinConfigTest`, `TownsteadMixinTargetTest`, `ClientConfigIsolationTest`, `ConfigSweepTest`, `McaBindingProbeTest`, `LangCoverageTest`, `OptionalClassloadTest` |
| M2, M3, M4, M5 | `python3 .../check_mod.py MCACrime` | no missing model, lang key or texture for anything registered in that milestone |
| any uncertain vanilla/Forge symbol | `python3 .../find_api.py --class <Class> --symbol <member>` | the signature, before it is written into code or a mixin |
| M7 | `$G $B build` | `BUILD SUCCESSFUL` including `checkJarContents`; no companion, Architectury or MCA class in the jar |
| M7 | `python3 .../modmap.py` | `MODMAP.md` regenerated, content below `AUTO:END` (line 186) preserved verbatim |
| M7 | `python3 tools/gui/generate_gui_sheet.py --check` | the committed GUI sheet matches |
| M7 | client launch and a dedicated-server launch with no optional mod installed | no crash; no client class resolved on the server |
| M7 | unzip the built jar and hash the two protected textures | identical to section 1.3 |

### 9.2 Per-milestone commands — port

Let `P = "/home/otectus/Projects/1.21.1 Ports/MCACrime_1.21.1"`.

| Milestone | Command | Required output |
|---|---|---|
| P0 | `$G $P test` then `$G $P build` | recorded baseline result |
| P1–P6 iteration | `$G $P compileJava` | `BUILD SUCCESSFUL` |
| end of each port milestone | `$G $P test` | all JUnit green, including `DedicatedServerIsolationTest`, `NoMcaStaticLinkTest`, `MixinConfigTest`, `McaBindingProbeTest` |
| P1, P3, P4, P5 | the `gameTestServer` run (task name confirmed from `build.gradle` first) | every GameTest in section 7.2 passes |
| any uncertain 1.21.1 symbol | `python3 .../find_api.py --project "$P" --class <Class> --symbol <member>` | the signature |
| P7 | `$G $P build` | `BUILD SUCCESSFUL`; `checkJarContents` asserts exactly two mixin configs, no refmap, and no MCA/Reputation/Architectury/Forge/test/gametest class |
| every milestone | **not** `check_mod.py`, **not** `modmap.py` | forbidden on this line |

### 9.3 Acceptance tests (specification section 21) as concrete test names

**21.1 Core state and behaviour** — baseline JUnit unless marked GameTest.

| Test class :: method | Assertion |
|---|---|
| `RestraintDefinitionsTest::everyDefinitionInEverySupportedSlot` | correct item, restriction profile, durability source, sound id and statistic for all ten definitions |
| `RestrictionCompositionTest::headArmsLegsCompose` | three slots compose; removing any one leaves the other two's restrictions intact |
| `PhysicalStateIsolationTest::twoSubjectsSameDefinition` | damage, owner, enchantments and save/load never bleed between two subjects |
| `ApplicationTransactionTest::twoActorsOneEmptySlot` | one success, one refusal, exactly one item consumed |
| `ApplicationTransactionTest::replayedApplicationAndRemoval` | no extra item, incident, sound or custody change |
| `EscapeServiceTest::reboundKeysAndAccessibleStruggle` | identical allowed work rate; release at zero durability exactly once |
| `RestrictionCompositionTest::legShacklesWithGuiToggling` | walking allowed; sprint and jump never bypass |
| `ContainerClickRejectionTest::forgedInventoryClickWhileArmRestrained` | the transfer is rejected on the server with no mutation |
| `PhysicalStateSyncTest::reconnectWhileRestrained` | full snapshot on tracking start; correct restrictions and visuals without manual resync |
| `BodyRegionResolverTest::alternateRigsAndScales` | correct placement, or an explicit unsupported-slot reason |
| GameTest `RestraintSlotGameTests` (port) | the same, on a live server, via the captured clientbound payload |

**21.2 Locks and inventory**

| Test class :: method | Assertion |
|---|---|
| `LockAccessTest::wrongKeyOldCopyForeignSession` | no unlock in any of the three cases |
| `KeyRingRecipeTest::copyThroughMoldsAndRingsThenDisassemble` | exact input/output quantities; no lost or duplicated binding |
| `KeyRingRecipeTest::multipleRingsInOneRecipe` | rejected, or processed losslessly — never "several rings in, one key out" |
| `LockpickSessionTest::lockReplacedAtSameCoordinatesMidPick` | the old session cannot affect the replacement |
| `LockpickSessionTest::forgedSuccessTargetAndDurability` | rejected; only connection identity and server progress matter |
| `LockTargetNormalizerTest::doorHalvesAndDoubleChest` | one consistent lock group |
| `SafeAutomationTest::lockWhileContainerOpen` | the menu closes or loses access before another transfer |
| `SafeAutomationTest::hopperMinecartAndCachedHandler` | locked extraction and insertion follow the declared policy |
| `FriskTransactionTest::searcherChangesOrDropsTheBox` | the session closes; every target item stays accounted for |
| `FriskTransactionTest::targetDiesDisconnectsOrIsReleased` | no further extraction; no duplicate death loot |
| `FriskTransactionTest::concurrentSearchersAndCursorClose` | every stack exists in exactly one resolved location |
| `PossessionsBoundsTest::fullBoxLargeNbtNestedContainers` | bounds enforced; no silent loss; no unbounded payload |
| `FriskTransactionTest::crashBetweenSourceAndDestination` | receipt recovery or quarantine; never a blind replay |
| `SafeContentsLifecycleTest::pickDestroyUnloadWithContents` | contents and lock state settle exactly once |

**21.3 Transport, custody and devices**

| Test class :: method | Assertion |
|---|---|
| `AnchorTransferTest::holderFenceHookWeightedAnchor` | one tether and one chain-ownership record throughout |
| `TetherLifecycleTest::anchorAndSubjectUnloadSeparately` | no premature drop, no permanent orphan |
| `EscortHandoverTest::captorLogoutGuardDeathDimensionChange` | a documented stop, grace, reassignment or release — never a stranded subject |
| `TetherPhysicsTest::cyclesAndExtremeCoordinates` | rejected or bounded; no non-finite velocity |
| `EscortTransportTest::aroundWallsStairsAndDoors` | no per-tick teleport through solids; no competing movement loop |
| `PilloryOccupancyTest::releaseOneRestraintWhilePilloried` | the device still detains; legal state unchanged |
| `PilloryOccupancyTest::destroyEitherHalfWhileOccupied` | one safe release, no stale occupancy |
| `MountTransferTest::enterAndLeaveVehiclesWithLegRestraint` | correct control and dismount behaviour, with a safe fallback |
| `CustodyTransitionTest::escapeKidnappingVersusLawfulCustody` | no victim penalty for a kidnapping escape; exactly one jailbreak for a lawful escape |
| `CustodyTransitionTest::sentenceEndsDuringPhysicalRestraint` | the settlement completes once; only that custody's physical claims clear |
| `CustodyTransitionTest::ransomRescueAndReleaseRace` | one custody generation settles; no repeated money or reward |
| `GuillotineExecutionTest::cancelledDeathTotemAndRevive` | no premature head, bounty, property recovery or murder completion |
| `GuillotineExecutionTest::repeatedActivationAndRestart` | no duplicated death processing or drops |
| `BunkRespawnTest::releaseAfterPlayerChoseAnotherSpawn` | the player's newer spawn is preserved |

**21.4 Rendering, effects and optional integrations**

`ProtectedTextureHashTest` (files and built jar) · `RestraintHudLayoutTest` (no overlap with the jail
timer, custody reason or action HUD; GUI scaling and small windows) · `HoodOverlayOwnershipTest`
(local player only) · `WoundLifecycleTest` (all health, velocity and mana values finite) ·
`ImbueDistributionTest` (zero/one/many recipients, dual-slot enchantments, mutually bound subjects,
cancelled damage, death) · `AdapterAbsenceTest` and `OptionalClassloadTest` (each adapter alone,
absent, and in combination) · `EpicFightInteractShimTest` · `TrayContentsTest` (vanilla and modded
food effects and remainders survive; contents survive placement and pickup) · a manual visual
regression pass for skin second layers, slim and wide arms, armour, capes, invisibility, spectator,
pose reset and adjacent players · a dedicated-server launch with no client classes and no optional
mod · a check that no client configuration or combat-mode preference remains changed after release or
disconnect.

**21.5 Migration fixtures** — `CrimeDataMigrationsV14toV15Test` and `RestraintReconcilerTest` carry
one fixture per case: each old restraint value, lawful player arrest, unlawful player custody,
lawful NPC custody, unloaded NPC, recovery/care state, active sentence, ransom, property receipts, a
generated cell journal, an active Locks Reforged escape attempt. Each asserts a round trip of
identities, clocks, balances and ownership, plus `repeatedLoadCreatesNoExtraItem`,
`futureSchemaStaysQuarantined` and `legacySentenceLoadsAsCustodial` (the section 3.19 kind field).

**21.6 Capital sentencing (user-scoped, section 3.19)** — baseline JUnit; the port adds
`CapitalSentenceGameTests` (section 7.2).

| Test class :: method | Assertion |
|---|---|
| `CapitalSentenceEligibilityTest::onlyGuardKillingQualifies` | every other crime id, at any Heat, any charge count and any band, yields a custodial sentence; an unresolved `mcacrime:kill_guard` case is the sole capital path |
| `CapitalSentenceEligibilityTest::muggingMurderOfAGuardIsNotCapital` | the `mugging_murder` branch keeps precedence and produces no capital binding |
| `CapitalSentenceEligibilityTest::disabledConfigNeverEscalates` | with `capitalPunishment.enabled = false`, or `guardKillingIsCapital = false`, a guard killing is an ordinary custodial sentence and no capital marker is written |
| `CapitalSentenceAssignmentTest::assignmentIsIdempotent` | a replayed arrival neither re-prices, duplicates nor upgrades the sentence kind |
| `CapitalSentenceAssignmentTest::npcOffenderRequiresOptIn` | a villager offender is capital only with `npcOffendersEligible = true` |
| `CapitalSentenceRefusalTest::ransomAndBailRefused` | `RansomService.demandFor` and `BailActionHandler.cost` refuse with a distinct reason while a capital binding is live; no fine clears a capital case |
| `CapitalExecutionAuthorizationTest::onlyPlayerOrGuardAtDeviceMayExecute` | no timer, redstone signal, packet or scheduled task can carry out the sentence; the delay window alone never kills |
| `CapitalExecutionAuthorizationTest::pardonOrCommutationDuringWindowCancels` | clemency inside `executionDelayTicks` clears the pending execution and leaves nobody dead |
| `CondemnedEscortTest::noGuillotineKeepsCaptiveInCustody` | with no reachable assigned device the captive stays held under sentence: no substitute death, no despawn, no release |
| `CondemnedEscortTest::rescueInsideTheWindow` | a successful rescue or escape ends the pending execution, releases claim and reservation, and files exactly one jailbreak |
| `CondemnedEscortTest::guardDeathMidEscort` | claim and reservation released, captive returned to a cell, sentence still live |
| `CapitalDeathOutcomeTest::casesWarrantsAndBountiesCloseOnce` | on confirmed death the sentence closes under `ReleaseReason.EXECUTED`, the warrant resolves, bounty pays once and possessions drop or bank once |
| `CapitalDeathOutcomeTest::cancelledDeathLeavesSentenceLive` | totem, PlayerRevive or a cancelled damage event resolves nothing and leaves the sentence live |

---

## 10. Resolved decisions

Every item that was open here has been answered by the user. They are recorded as decisions, not
options, and the rest of the plan is written to them.

1. **Provenance and distribution.** *Resolved: author original art.* Every unattributed
   Cuffed-origin asset — the seven poster textures, the fuzzy worn texture, the six `.ogg` sounds and
   anything else the M0.3 manifest cannot attribute — is authored as original MCA: Crime work under
   the **same registry ids**. No step waits on author contact. Cuffed-origin material that upstream's
   GPL-3.0 `LICENSE` and README do cover is adapted **with attribution** recorded in
   `docs/0.7.5/PROVENANCE.md` and `CREDITS.md`. "Blocked pending author contact" is no longer a state
   any step may sit in (see M0.3, M0.5, section 3.11, M5.8, risk 1).
2. **Release number.** *Resolved: 0.7.5, schema 15, protocol `"15"`* — already this plan's target,
   now fixed rather than provisional.
3. **Fuzzy handcuffs acquisition.** *Resolved: Fence stock plus creative tab, no crafting recipe*,
   documented honestly as a non-craftable illicit item (M2.1, M5.12).
4. **Locks Reforged coexistence.** *Resolved: `locks.foreignLockPolicy = REFUSE` by default* — MCA:
   Crime refuses to place a second lock on a target Locks Reforged already owns (section 3.16, M3.7).
5. **Retired config keys.** *Resolved: drop them; do not auto-migrate values.* `CONFIG.md` and
   `docs/MIGRATION.md` carry the one-for-one old→new mapping, and `ConfigValidator` emits a startup
   report naming every retired key still present in a server's file, without failing startup
   (section 3.12, M7.1, risk 12).
6. **Head tape and text chat.** *Resolved: no text-chat effect by default.*
   `restraints.definitions.headTapeMufflesTextChat` remains available and defaults to `false`.
7. **Guillotine and capital punishment.** *Resolved: the guillotine is craftable*,
   `detention.guillotineEnabled = true`, `detention.guillotineDropsHead = true`, and **no automatic
   capital sentencing anywhere in the legal system**. The user additionally scoped in a fully
   functional capital-sentence feature whose **only** qualifying offence is killing a guard —
   specified in section 3.19 and implemented in M4.10, M6.6–M6.8 and M7.7, with port steps P4.3, P6.3
   and P7.4, traceability rows X01–X03, acceptance tests in 9.3 §21.6 and risk 14.
8. **Donor-world import from an existing Cuffed world.** *Resolved: out of scope for 0.7.5*, stated
   plainly in the release notes and `docs/MIGRATION.md` (section 3.17 already says so).

**New, and open only as a correction opportunity.** Section 3.19 is a *working interpretation* of the
scoped-in capital feature, not a user answer. Nothing blocks planning, but two product choices there
deserve an explicit confirmation before M6.6: whether villager offenders are capitally eligible at all
(`sentencing.capitalPunishment.npcOffendersEligible`, planned default `false`), and whether a condemned
player's death should stay an ordinary vanilla death with normal respawn (planned) rather than
anything harsher.

---

## 11. Sequencing constraints and relative size

### 11.1 Hard constraints

1. M1 (state ownership and migration) completes before any interaction handler in M2 is written.
2. M2.11 (deleting the legacy engine) happens only after M2.3–M2.10 are all in place; until then both
   engines exist but the new one is unreachable from gameplay.
3. M3 (transactional locks) completes before M5 exposes valuable confiscation and safe storage.
4. M4.8 (the physical/legal bridge) completes before bounties, ransom and death outcomes are rewired.
5. M2.8 (per-action restriction policy) completes before M6.2 touches combat and magic compatibility.
6. The protocol bump (M1.6) and the schema bump (M1.5) land together, in one release, once.
7. Port milestone `P<n>` starts only after baseline `M<n>` is green, and the port commit lands after
   the baseline release commit.
8. The specification's own advice — "avoid combining a Minecraft-version port into these milestones" —
   is respected by keeping the port a **separate, keyed part** with its own commit, not by deferring
   it to a later release.
9. M4.6 (the guillotine device) and M4.8 (the physical/legal bridge) both complete before M6.6 can
   mark a sentence `CAPITAL`: a capital sentence with no lawful way to carry it out is a stuck
   captive.
10. M6.6 completes before M6.7 — the guard-carried execution AI reads the sentence kind and never
    writes it — and M4.10 completes before either, because it is the only thing that authorises a
    device to act.

### 11.2 Relative size

| Milestone | Baseline | Port | Note |
|---|---|---|---|
| M0 / P0 | small | small | documents and one hash test |
| M1 / P1 | **large** | medium | the port adds `CrimeDataComponents` but reuses every value type |
| M2 / P2 | **large** | **large** | ~37 items, nine models, three mixins, the legacy deletion |
| M3 / P3 | **large** | **large** | the port's first block entities and its block-capability protection |
| M4 / P4 | **large** | medium-large | the port needs `IEntityWithComplexSpawn` and an attribute event |
| M5 / P5 | **large** | **large** | the largest resource volume on both lines |
| M6 / P6 | **large** | **large** | the port's enchantments are JSON, not classes; both lines now also carry the capital-sentence model (M6.6), the condemned-escort AI (M6.7) and its GameTest |
| M7 / P7 | medium | medium | docs, presets, acceptance matrix, packaging |

Largest single risks to the schedule, in order: the resource volume of M5/P5, the migration
correctness work in M1/P1, the original-art authoring load in M0/M5 now that provenance is resolved to
original work (section 10.1), and the user-scoped capital-sentence feature in M4.10/M6.6–M6.8, which
grew M6 from medium to large on both lines.

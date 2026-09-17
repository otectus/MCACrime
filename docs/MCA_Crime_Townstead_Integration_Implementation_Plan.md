# MCA: Crime × Townstead

## Source review and implementation plan

**Prepared:** 16 September 2026  
**Primary target:** Minecraft 1.20.1, Forge, Java 17  
**Implementation home:** `otectus/MCACrime`, with a small, explicitly identified set of Townstead cooperation hooks  
**Purpose:** Give a coding agent a concrete path to reliable coexistence and meaningful shared gameplay.

---

## Revalidation notes — 16 September 2026

Recorded during implementation, not by rewriting the body below: the plan's reasoning stands, but
several of its statements about the two checkouts were already false when the work started. Where a
note and the body disagree, the note is the current truth.

- **World data schema.** The document says 10. It was **12** at the start of this work (the 0.7.2
  occupation schema), and this work adds **13** — `facilities`, `cellReservations` and the custody
  `recovery*` keys.
- **Mixin count.** The document says MCA: Crime has two mixins. It has **eight** vanilla ones (seven
  common plus one client), and this work adds **four** Townstead ones in a second, plugin-gated
  config.
- **Townstead's needs snapshot.** `collapsed` and `gated` are already public. `gated` is the
  **fatigue recovery gate**, not "needs disabled", and reading it as the latter would have inverted
  the incapacity check.
- **`ReactionLockTracker`.** It restores `WALK_TARGET` only when the brain has none, not
  unconditionally as the document assumes. The yield hook is written to that behaviour.
- **`WorldCriminalJobService`.** It already carried `previousProfessionKind`; no field had to be
  added. What did change is *when* the previous profession is restored — see
  `job/ProfessionPresentationRevision`.
- **`CellBuilder.siteIsClear`.** Already conservative; the settlement refusal is an additional
  refusal on top of it rather than a rewrite of it.
- **`IntegrationTargets`.** Already generic enough to carry a non-Reputation target; adding
  `TOWNSTEAD_REACTION` needed no restructuring, only target-specific routing in the pump.
- **Test file count.** The document says 143. It was well past that before this work began (about
  205 when checked during implementation) and the tree now holds 232 test files.
- **MCA: Quests had already solved this once.** It ships a reflective Townstead binding, and that
  approach — manifest, owner/name/arity/staticness matching, erased handles — was reused here rather
  than designed again.
- **The NeoForge 1.21.1 port is a hard requirement** and is absent from this document. Parity work on
  that branch belongs to the same job as the Forge 1.20.1 baseline change.
- **`-PrequireTownsteadIntegration` was dropped.** A flag that could make the integration mandatory
  contradicts the rule that the whole seam degrades silently; `townsteadProbeTest` with an explicitly
  supplied jar covers what it was for.
- **Datapack path.** `data/mcacrime/townstead/`, not the path named in the body.
- **Townstead jars had to be built from source.** No Maven serves them, and each probe jar has to be
  paired with the MCA build it was compiled against.

---

## 1. Recommended direction

Make Townstead's villagers, workplaces, needs, and schedules meaningful to Crime, and make Crime's incidents, custody, and recovery meaningful to Townstead's daily life.

The foundation is **coordinated villager behavior**. A guard must finish a lawful approach without a rest routine stopping her. A restrained cook must stop cooking safely, retain her ingredients and profession progress, receive appropriate care in custody, and resume her current schedule after release. A collapsed witness must not identify an offender or deliver a report. A working villager's displayed tool must not become an extra physical item on death.

Build the integration in this order:

1. Verify matching loader and MCA artifacts; introduce an optional, diagnosable Townstead bridge.
2. Coordinate AI control, awareness, needs, professions, equipment, and screen transitions.
3. Integrate existing buildings with safe jail placement, guard coverage, and recovery.
4. Add public incident responses, victim support, and practical service opportunities.
5. Add explicitly configured property law and more ambitious economic or civic features.

Compatibility should activate automatically for supported installations. Features that introduce new offenses, alter property permissions, recruit workers, or restrict ordinary services need separate server settings and clear player feedback.

### 1.1 Completion standard

“Fully compatible” means the supported combinations pass production-jar, dedicated-server, client, multiplayer, save/reload, and removal tests. A successful launch or a working snapshot query alone is insufficient.

The implementation must preserve:

- The exact villager entity and UUID, family relationships, root/species data, age, needs, profession experience, inventory, and existing schedule configuration.
- Crime's distinction between an incident, an observation, a report, a legal decision, and a sentence.
- Finite item and currency accounting, exact case membership, and recovery records.
- Existing Crime behavior when Townstead is absent.
- Townstead's normal needs and life simulation while a villager is under temporary Crime control.

### 1.2 Scope of this review

Both default branches were retrieved and pinned. The complete Java source trees were inventoried; the review then followed the implementation surfaces relevant to interoperability: build configuration, APIs, villager tick and brain changes, rest and consumption, professions, buildings and storage, incident/report flows, enforcement and custody, loot, UI, and persistence.

The inventories contain **1,196 Townstead main Java files** and **419 Crime main Java files**. This is a focused source review, not a claim that every unrelated subsystem received a line-by-line audit. No Minecraft client, dedicated server, or Gradle test suite was executed during preparation. Identified interactions are therefore **source-confirmed mechanisms or source-derived risks**, with runtime reproduction specified below.

All proposed classes, APIs, settings, events, commands, and data schemas in this document are new designs unless explicitly marked as existing. Links in the source register are pinned to the reviewed commits.

---

## 2. Reviewed baseline and artifact requirements

| Repository | Reviewed commit | Source version | Relevant build facts |
|---|---|---|---|
| [Townstead repository][T00] | `4d6206cdf8b9d0f558694d7b35b223f4f6ace61e` | `0.7.7` | Stonecutter source shared across NeoForge 1.21.1, Forge 1.20.1 modern, and Forge 1.20.1 legacy. |
| [MCA: Crime repository][C00] | `fdb602428c101c2364a0e7bb0cc5cba57aeedce1` | `0.6.4` | Forge 1.20.1; Java 17; MCA access resolved reflectively across package roots. |

Townstead's current source view is the NeoForge 1.21.1 variant. Its Forge builds transform MCA package references and contain version-specific branches. Do not compile the active source view directly into a Crime adapter or copy NeoForge packet signatures into Forge code. Read the Stonecutter directives and verify the generated Forge artifacts. [Townstead settings and Forge build][T01] [Crime build and dependency configuration][C01]

### 2.1 Initial certification matrix

| Combination | Treatment |
|---|---|
| Crime 0.6.4 baseline + MCA 7.6.20, Townstead absent | Preserve existing baseline behavior. |
| New Crime + MCA 7.7.0-beta.2 + Townstead Forge legacy | Primary legacy integration target; verify actual published artifact identity. |
| New Crime + MCA 7.7.1 line + Townstead Forge modern | Primary modern integration target; verify the specific MCA artifact against both projects. |
| MCA 7.6.20 + Townstead legacy | Test as an additional combination before claiming support; metadata ranges alone do not prove behavior. |
| Crime Forge jar + Townstead NeoForge 1.21.1 jar | Different platform artifacts; outside this implementation target. |
| Townstead absent, unsupported, or missing a specific cooperation hook | Report the actual available capabilities and use the defined fallback for each feature. |

Townstead's Forge build distinguishes:

- `townstead-mca-legacy`, compiled against `forge.net.mca`, with a local MCA `7.7.0-beta.2` universal jar path.
- `townstead-mca-modern`, compiled against `forge.net.conczin.mca`, with a local MCA `7.7.1-alpha.1` universal jar path.
- A `Townstead-MCA-Namespace` manifest attribute recording the intended namespace.
- A distribution `jarJar` artifact and a separate slim jar. Verify the distribution jar includes its required MixinExtras dependency.

Crime's probe fleet includes MCA `7.6.20`, `7.7.0-beta.2`, and `7.7.1-alpha.2`. Its runtime development default remains `7.6.20`. The difference between Townstead's alpha.1 compile input and Crime's alpha.2 probe is a **test obligation**, not evidence of either compatibility or incompatibility. [T01] [C01]

Townstead requires Patchouli in its Forge metadata. Include the dependency sets required by the actual MCA and Townstead artifacts in every launch fixture. Use a Forge version compatible with both; Crime's existing development build uses 47.4.10.

### 2.2 Baseline documentation corrections

Treat executable code as authoritative where documentation has drifted:

- Crime's README describes one client mixin, but the current mixin configuration also includes `MobDeathEquipmentMixin`.
- Crime's README describes migration through schema 9; `CrimeDataMigrations.CURRENT_SCHEMA` is **10**.
- Townstead's broad MCA dependency range does not replace its separate namespace-specific Forge builds.

Update these statements as part of the release work. Do not carry the stale assumptions into migration or compatibility tests. [C02] [C03] [T01]

---

## 3. Source findings that should drive the implementation

### 3.1 Existing functionality worth reusing

| Area | Existing implementation | Consequence for the integration |
|---|---|---|
| Townstead observations | `TownsteadAPI.entity(Entity)`, `buildingAt(ServerLevel, BlockPos)`, `calendar(MinecraftServer)`, and immutable snapshot records. | Use this facade for supported reads; the entity entry point avoids naming an MCA class in Crime. |
| Crime observations | `McaCrimeApi` exposes player, custody, sentence, case, and victim-memory views. | Extend this API with narrowly scoped behavior and public-knowledge views instead of exposing internal maps. |
| Crime behavior coordination | `LawHold`, `ResponderAssignments`, `ReactionControlPolicy`, action sessions, reaction controllers, and custody state already divide responsibilities inside Crime. | Extend their coordination outward; avoid an unrelated second arrest or restraint state machine. |
| Townstead work | Work adapters, producer station sessions, consumable claims, navigation budgets, and cleanup routines already exist. | Interrupt work through those owners, preserving material and station state. |
| Crime incident accounting | `IncidentService`, `CrimeWorldData`, reports, legal decisions, sentence identities, economic receipts, and property recovery. | New Townstead-related actions must use the same incident and transaction paths. |
| Community identity | Crime's `CrimeCommunityKey` and Townstead's saved village key both include dimension plus village ID. | Reuse MCA village identity with dimension; add building identity only where needed. |
| Shared social consequences | Crime already hands incident delivery to its Reputation bridge and uses a persisted integration outbox. | Avoid sending the same incident through a second Townstead-to-Reputation route. |

Sources: [Townstead API][T02], [Crime API][C04], [Crime control][C05], [Townstead producer work][T05], [Crime incident commit][C06], [community keys][C07], [Townstead village data][T09], [Crime integration hooks and pump][C08].

### 3.2 Compatibility gaps and risks

| ID | Source finding | Implication | Priority |
|---|---|---|---|
| F01 | There is no Townstead adapter in Crime's main source and no Crime integration in Townstead's main source. | Coexistence currently relies on incidental agreement. | P0 |
| F02 | Crime's `NpcAwareness.isAwake` checks alive and vanilla sleeping state only. Townstead collapse independently clears movement and sets its own flags. | A collapsed villager can still qualify as a Crime witness, responder, ally, or actor unless an additional capability check is introduced. | P0 |
| F03 | Townstead's `GuardRestEnforcerTicker` stops a resting, awake guard without HOME or ATTACK_TARGET. Crime's peaceful approach uses WALK_TARGET and `LawHold` before assigning an attack target. | A specific path exists for rest enforcement to cancel a lawful approach. Reproduce before and after the fix. | P0 |
| F04 | Townstead injects WORK tasks and CORE food, bed, and care tasks into the MCA brain. Its independent tickers also write movement, tools, and reactions. | Stopping only the navigator or skipping one ticker will not consistently suspend work or needs-related travel. | P0 |
| F05 | `ReactionLockTracker` freezes movement and can later restore a saved WALK_TARGET. | A social reaction can interrupt Crime control or restore a stale destination after capture or release. | P0 |
| F06 | `WorkToolTicker` copies a real inventory tool into the main hand for display. Crime's inventory ownership check tests object identity. | Crime cannot identify that display copy by its current ownership check. Duplicate or inappropriate equipment recovery is a concrete integration risk; equal item data alone is also insufficient proof of identity. | P0 |
| F07 | Crime guard recruitment selects adult, non-important-profession residents. Criminal presentation can replace a profession and restore it later. Townstead tracks progression, station claims, and schedules separately. | Automatic or temporary role changes can disrupt productive residents, manual assignments, or newer player edits. | P0 |
| F08 | Townstead has custom life stages, SENIOR, per-stage mobility/needs/talkability, custom personality IDs, rigs, and optional abilities. The public villager snapshot omits several of those effective capabilities. | Biological years, a raw stage name, or the current vanilla pose are insufficient for all Crime eligibility decisions. | P0/P1 |
| F09 | Townstead changes damage and can cancel death through immortality or prevention. Crime has final-damage and confirmed-death processing. | Preserve Crime's finality checks and test them against Townstead; do not introduce a second early death handler. | P0 |
| F10 | Crime recognizes MCA's InteractScreen for its injected button. Townstead redirects Talk into its own `RpgDialogueScreen` and changes transition handling. | Crime needs an explicit entry point and lifecycle handling for Townstead's dialogue screen. Existing button placement is also a layout test target. | P1 |
| F11 | Townstead's `buildingAt` returns the first matching building in one nearest village, with bounds and IDs but no owner, access rights, completion flag, or revision. | Useful contextual read; insufficient as a complete property-law or jail-validation API. | P1 |
| F12 | Crime's jail registry is a list of dimensional anchors; temporary cells are placed from block-space checks. | Facility identity, jurisdiction, capacity, Townstead worksite exclusion, and supply access need explicit integration. | P1 |
| F13 | Townstead storage sourcing uses block/tag protection and indexed container contents. It does not establish individual land or item ownership. Crime's reviewed detectors do not provide general chest-withdrawal theft detection. | Property law is a new subsystem, not a one-line building-type check. | P2 |
| F14 | Townstead village “spirit” is computed from completed buildings and their contributions. | It represents settlement character, not a dynamic crime, morale, or treasury balance. Keep these concepts separate. | P2 |
| F15 | Crime already has finite villager purses. Townstead's inspected API exposes profession XP and work state, not a compatible wallet or treasury service. | Economy links require an explicit policy and transfer adapter; do not assume Townstead has a purse to debit. | P2 |

Evidence: [awareness][C09], [Townstead fatigue][T03], [guard rest][T04], [guard pursuit][C10], [brain task injection and dispatch][T06], [reaction lock][T07], [work tools][T08], [Crime loot][C11], [guard recruitment][C12], [criminal professions][C13], [life stages and personality][T10], [damage/death handling][T11], [screen integrations][T12] [C14], [jails][C15], [storage][T13], [spirits][T14], [purses][C16].

These are source findings and predicted integration failures, not assertions that a runtime crash or duplication exploit was reproduced in this review.

---

## 4. Architecture and responsibility

### 4.1 Authority map

| State or decision | Authoritative owner |
|---|---|
| Entity identity, basic inventory, core family/residency identity | Minecraft/MCA, accessed through the established compatibility layers. |
| Townstead needs, life cycle, custom personality definition, profession progression, configured schedules, work sessions | Townstead. |
| Crime case truth, observations, reports, warrants, lawful force, custody, sentences, criminal jobs | Crime. |
| Crime money transfers, stolen-property receipts, restitution | Crime's existing economy and recovery services. |
| Public community standing when its handshake succeeds | MCA: Reputation, through the existing Crime bridge. |
| Fallback community standing without that integration | Crime's existing fallback store. |
| Temporary navigation, hand use, and reaction control | One coordinated decision derived from the actual owning action, custody, or work session. |
| New facility assignments and Crime property permissions | Crime, linked to Townstead/MCA building references. |
| New Townstead emotional or work responses | Townstead, consuming an appropriately filtered Crime consequence. |

### 4.2 Bridge design

Add a Crime-owned package:

~~~text
dev.otectus.mcacrime.compat.townstead
  TownsteadBridge
  TownsteadBinding
  TownsteadCapabilities
  TownsteadReadAdapter
  TownsteadEntityContext
  TownsteadBuildingContext
  TownsteadCompatDiagnostics
~~~

The core-facing types belong to Crime and contain vanilla types, IDs, immutable values, and explicit availability states. Do not return Townstead classes from Crime core signatures.

Use cached reflection around the existing `TownsteadAPI.entity(Entity)` entry point and snapshot accessors. Bind once after normal mod initialization; keep the no-Townstead path cheap. Avoid repeating reflective field scans during a villager tick.

Use separate capabilities rather than one “Townstead loaded” boolean:

~~~text
ENTITY_SNAPSHOT
FEATURE_AND_LIFE_CAPABILITIES
BUILDING_LOOKUP
BUILDING_ENUMERATION_AND_REVISION
ACTIVITY_COORDINATION
WORK_SUSPENSION
EQUIPMENT_PROVENANCE
STORAGE_POLICY
CONSUMPTION_IN_CUSTODY
PUBLIC_REACTION_DELIVERY
CLIENT_DIALOGUE_ENTRY
RIG_ATTACHMENTS
~~~

The existing API can support the first and a limited building lookup. The other capabilities require additional hooks or carefully isolated, version-tested access. A snapshot's zero value must never be interpreted as “starving” when the need is disabled or unavailable.

Define query results as `AVAILABLE(value)`, `UNAVAILABLE`, or `FAILED(reason)`. Keep unknown state distinct from a genuine false/zero value. Catch linkage and invocation failures at the optional boundary, report them once per capability, and disable only the failing optional feature. Never let a Townstead lookup trip Crime's session-wide crime-detection disable switch.

Extend packaging checks to reject shaded Townstead classes. A normal Crime checkout must build and include the reflection adapter without requiring a sibling Townstead checkout. Add an optional `-PrequireTownsteadIntegration=true` verification gate for release fixtures; do not silently omit the adapter based on local folder presence.

### 4.3 Small Townstead cooperation surface

Full behavior and inventory compatibility needs cooperation beyond read-only snapshots. Prefer a small Townstead patch with a versioned facade, using vanilla `Entity`/`LivingEntity` arguments and Townstead-owned DTOs.

Proposed responsibilities:

| Hook | Purpose |
|---|---|
| Effective entity capabilities | Return whether needs systems are enabled, whether the current stage is mobile/talkable, normalized adult/senior status, collapse/recovery state, and current/resting schedule information. |
| External activity policy | Let Townstead query which operations a live Crime activity permits. |
| Safe work interruption | Release claims and reconcile staged materials using the owning task's semantics. |
| Display equipment provenance | Identify equipment representing an inventory slot or temporary work display. |
| Facility/building queries | Enumerate candidates and return exact geometry, completion, stable identifiers, and revision information. |
| Storage-access policy | Let Townstead exclude evidence, recovery, and reserved facility storage from ordinary sourcing. |
| Custody consumption | Consume real accessible food/drink through Townstead's consumption rules within an allowed region. |
| Public consequence sink | Apply a filtered, idempotent reaction or memory effect. |
| Dialogue action registration | Add Crime actions to Townstead's screen without private-field discovery. |

Choose one transport for these hooks. A practical choice is a Townstead service-provider registration API containing no Crime types; Crime registers its adapter after both mods are ready. A direct Townstead-to-Crime reflection bridge is an acceptable alternative for a small upstream patch, but implement only one active path.

**If upstream changes cannot be shipped:** keep read-only features in Crime, and isolate any necessary compatibility mixins in a dedicated, gated package targeting Townstead's own classes. Gate them by tested signatures and capability probes. Verify start, continuation, tick, stop, and cleanup semantics for every intercepted task. Unsupported hooks must be reported as unavailable. Do not advertise full AI/material compatibility for an installation where required hooks are missing.

Do not route this integration through a new mandatory shared library. It can be extracted later if multiple add-ons actually need the same stable contract.

### 4.4 Data flow

~~~mermaid
flowchart TD
    T["Townstead state and work"] --> Q["Optional context adapter"]
    Q --> P["Crime behavior and legal policy"]
    P --> A["Coordinated activity control"]
    A --> T
    P --> L["Crime cases and recovery"]
    L --> V["Filtered consequence delivery"]
    V --> T
~~~

The context adapter supplies facts. Crime decides legal outcomes. Townstead decides how its own work, needs, and presentations respond.

---

## 5. P0: Coordinate AI without losing work or villager state

### 5.1 Derive control from existing owners

Extend `LawHold` and Crime's existing action/custody/controller coordination with a common read-only `CrimeActivityView`. Do not create a second persistent captivity flag inside Townstead.

Suggested fields:

~~~text
entity UUID, dimension, activity kind, owner token, generation
authority: lawful / unlawful / ordinary / emergency
allowed movement region or destination, if any
allowed operations: work, navigation, hand use, consumption, sleep, reaction
expiry for transient ownership
~~~

Transient claims need an owner token and generation, so ending an older action cannot release a newer escort. Scope caches by server instance, dimension, and UUID. On server restart, reconstruct temporary claims from authoritative Crime custody and active recoverable state; never deserialize a saved navigator or Brain.

A renewed guard approach can use a short lease, initially **40 ticks**, refreshed by the owning pursuit/challenge/escort service. Tune this against the actual scan interval. Persisted custody has its own lifetime and should not disappear merely because a transient lease expires.

### 5.2 Operation policy

Movement, consumption, sleep, equipment, and animation need separate permissions. One total priority number cannot express every case.

| Current situation | Work and supply trips | Movement | Needs | Reactions |
|---|---|---|---|---|
| Ordinary Townstead life | Normal. | Townstead/MCA. | Normal. | Normal. |
| Guard approaching or challenging | Suspend routine work/rest travel for the active assignment. | Crime owns approach or hold. | Metabolism continues; critically unfit guards can hand over. | Brief non-blocking legal dialogue; ambient freezes suppressed. |
| Guard escorting | Suspend routine travel and tool swapping. | Escort owns route. | Continue needs; replace guard if incapacitated. | Cosmetic overlays that respect escort pose. |
| Threatened/compliant villager | Safely suspend work and acquisition. | The chosen Crime reaction owns hold/flee/resist. | Continue metabolism; resolve emergency incapacity explicitly. | Crime response takes precedence over ambient gestures. |
| Lawful prisoner in a facility | Suspend production and external supply trips. | Confined movement, subject to Crime containment mode. | Permit valid food, drink, and rest inside the assigned region. | Custody-compatible presentation. |
| Unlawfully held villager | Suspend production and unrelated wandering. | Existing captive/escape rules. | Actual needs continue; supplied care uses normal consumption rules. | Captivity and distress responses. |
| Collapsed or otherwise immobile stage | No work or voluntary pursuit. | Townstead's incapacity applies; confinement still remains. | Townstead recovery rules. | No physically impossible active gestures or reports. |
| Immediate environmental danger or attack | Cancel/transition the owning action as appropriate. | Resolve escape, defense, relocation, or escort handover through existing services. | Preserve life-state processing. | Cancel an obstructive animation immediately. |

A guard's emergency response may interrupt an ordinary REST schedule. It must not turn a collapsed villager into a functioning guard, permanently overwrite a weekly plan, or suspend fatigue accumulation forever.

### 5.3 Apply the policy at all relevant entry points

Crime touchpoints:

- `GuardEnforcement`, `GuardChallengeService`, `ResponderAssignments`, and `LawHold`.
- `EscortService`, `JailEscortNavigation`, `NpcCriminalPursuit`, and `NpcCustodyService`.
- `CrimeReactionService`, `ReactionControlPolicy`, `CrimeNavigation`, and the thief behavior services.
- `ActionSessionManager`, capture channels, and `CustodyService`.
- `McaCompat.stopModNavigation`, `holdPosition`, and target cleanup, so release respects current ownership.

Townstead touchpoints:

- `GuardRestEnforcerTicker`: yield to a valid legal approach/challenge/escort without requiring ATTACK_TARGET.
- `RestCoordinator`, `FatigueVillagerTicker`, and `SeekBedWhenFatiguedTask`: distinguish ordinary rest travel, actual incapacity, and authorized rest inside custody.
- `ProducerWorkTask` and all independent work task families: check permission on start and continuation, before mutation, and on interruption.
- `RefuelTask`, care/hydration tasks, `EmptyContainerDropoff`, tool acquisition, and `WorkToolTicker`.
- `ReactionLockTracker`, active ability travel, and disposition-driven reactions.
- Both profiled and unprofiled paths in `VillagerServerTickDispatcher`.

Do not skip the entire Townstead dispatcher for captives. That would also skip hunger, thirst, life stages, recovery, and other state that should remain active. Gate the specific conflicting operations. [T06]

### 5.4 Safe interruption of production

Before enabling work suspension, define `interruptForExternalActivity(reason, token)` for producer and non-producer tasks.

The interruption must reconcile:

1. Inputs still in the original container.
2. Inputs already transferred into villager inventory.
3. Inputs reserved or staged for a recipe.
4. A recipe already committed to an actual workstation.
5. A completed output waiting to be collected or stored.
6. Station, berth, crop, tool, and consumable claims.

The current producer base has `stagedInputs`, `pendingOutput`, station callbacks, and transient cleanup. Treat these as transaction state: blindly invoking cleanup can discard the information needed to recover materials. Audit each concrete task's `onStop`, `rollbackGather`, and output handling before making it interruptible. [T05]

A completed workstation process may finish normally without the worker standing beside it. Release the worker's movement ownership, and leave a durable or re-discoverable station result. Do not rerun the recipe or award production XP twice after release.

### 5.5 Navigation release

On release or interruption:

- Clear only the route, target, and modifiers owned by the finishing activity.
- Invalidate any saved ambient-reaction destination belonging to an older generation.
- Ask the current Townstead schedule/work logic to choose its next task.
- Preserve HOME, JOB_SITE, POI tickets, and configured shifts unless the owning system intentionally changed them.
- If a player changed the profession or weekly plan during the incident, respect the newer state.
- Never restore an entire stale brain, cached schedule, or remembered WALK_TARGET over new state.

Existing reaction locking stores a WALK_TARGET and conditionally restores it. Add an explicit discard/cancel-without-restore path when a higher-priority activity preempts it. [T07]

### 5.6 Required cleanup paths

Exercise normal completion, canceled action, damage, death cancellation, confirmed death, loss of target, dimension change, entity unload/reload, player logout/login, server stop/restart, mod configuration changes, and brain refresh.

An unloaded entity must not retain a strong reference in a bridge cache. Reloaded lawful custody resumes confinement and care; an expired transient challenge does not resume as a permanent freeze.

---

## 6. P0: Awareness, life stages, and needs

### 6.1 Replace a single awake predicate with capabilities

Keep `NpcAwareness.isAwake` as a compatibility wrapper if callers rely on it, but introduce explicit decisions:

- `canObserveAct`
- `canIdentifyActor`
- `canSpeakOrReport`
- `canNavigate`
- `canRespondAsGuard`
- `canPerformCriminalAction`
- `canUseHands`

A collapsed villager may be alive and not vanilla-sleeping. It must not identify a new suspect, accept an incoming report, contribute active armed support, chase someone, or start a mugging. Recovery does not grant retroactive knowledge of an incident it did not perceive. Historical memories remain stored.

An unknown optional capability should fall back to the existing MCA behavior for baseline gameplay; newly introduced Townstead-specific actions requiring that capability remain unavailable. Do not interpret bridge failure as permanent incapacity or as permission to capture someone.

Route these decisions through witnesses, observation/report creation and delivery, allied support counts, threat evaluation, thief target/actor selection, guard eligibility, capture vulnerability, and reaction navigation. A patch only to the guard list would leave several inconsistent paths. [C09] [C17]

### 6.2 Normalize needs correctly

The reviewed source uses different scales:

| Value | Range | Direction |
|---|---|---|
| Hunger | 0–100 | Higher is better fed. |
| Thirst | 0–20 | Higher is better hydrated. |
| Fatigue | 0–20 | Higher is more tired; collapse threshold is 20. |

Use Townstead constants or adapter metadata for these scales; do not apply vanilla hunger assumptions to all three. Resolve feature-enabled status and per-life-stage needs before using a value. Thirst additionally depends on Townstead's active thirst integration. [T15]

For optional response flavor, start with bounded changes:

- Low energy can modestly reduce willingness to resist or take a long reporting route.
- Severe needs can favor a nearby safe guard or refuge instead of a distant home.
- A tired guard can finish a short conversation and request relief.
- Unmet needs must not manufacture criminal guilt, identify a hidden actor, or create a severe penalty multiplier.

Initially cap any continuous threat-factor adjustment at **±0.10** and explain it in debug output. Keep the existing fear, anger, personality, weapon, and support inputs authoritative. Tune with gameplay fixtures rather than asserting these initial values are balanced.

### 6.3 Life stage and personality mapping

Townstead's raw stage ID is data-driven. Resolve its effective `presentsAs` category and capability flags. ADULT and SENIOR are generally adult roles; mobility, talkability, current health, and explicit job eligibility still apply.

Do not determine adult status from apparent years or a hardcoded string equality to `adult`. Some stages can be nonmobile, have no needs, or not be talkable. The current public snapshot omits those booleans, so add a capability query or leave the corresponding new feature unavailable. [T10]

For custom personalities:

1. Use an exact configured Townstead personality ID mapping if supplied.
2. Otherwise resolve its declared MCA base personality.
3. Otherwise use Crime's existing deterministic neutral/personality fallback.

Townstead's `PersonalityDef` supplies an ID, base, name, and description; it does not supply numeric Crime bravery or honesty values. Those mappings would be new data. Do not infer guilt or criminal recruitment probability from species, heritage, skin, or a personality's display text.

### 6.4 Clocks

Townstead's calendar should label dates and support schedule presentation. Preserve Crime's existing clocks for sentence service, expiry, online player progression, NPC loaded-time behavior, and transaction cooldowns.

Changing the calendar profile, sleeping through a night, or using a time-setting command must not automatically serve a sentence, refill a purse repeatedly, pay a bounty, or make an NPC forget a crime. Audit each affected subsystem's actual clock instead of assuming every Crime timer uses the same clock.

---

## 7. P0: Professions, equipment, death, and physical capabilities

### 7.1 Protect Townstead workers from incidental recruitment

The current guard population service uses total/loaded population estimates and MCA profession importance. It does not ask whether a resident is operating a Townstead workstation or has an explicit Townstead assignment. [C12]

Add `TownsteadRolePolicy` to candidate selection:

- Prefer eligible unemployed adults.
- Exclude active producers, residents with retained workstation assignments, manually assigned professions, lawful or unlawful captives, current incident participants, collapsed residents, and nonmobile/nonworking stages.
- Preserve named or otherwise deliberately protected villagers through an explicit server tag/configuration option.
- Count resting or unloaded known guards as members of the guard population; track operational availability separately.
- When reliable role information is unavailable, stop automatic conversions for that affected Townstead village and explain the shortfall. Do not randomly remove its workforce to satisfy the target.

Keep the existing population target configurable. Introduce an explicit distinction between **roster size** and **guards currently available**. A night with no awake guard is a scheduling problem, not automatic justification to convert more residents.

### 7.2 Criminal jobs and profession presentation

Crime already stores a criminal role separately from a visible profession. Use that separation for Townstead. The current presentation path can set a new profession and later restore `previousProfessionId`; it needs to recognize intervening manual changes. [C13]

Recommended behavior:

1. Existing criminal-role assignments remain intact.
2. When Townstead is active, use Crime's role/UI overlay by default for new assignments.
3. Apply a visible MCA thief/fence profession only when explicitly enabled and the role transition is compatible with the resident's assignment.
4. Record the expected profession revision and the value written by the presentation owner.
5. Restore only when the current profession still matches that owned transition.
6. Preserve Townstead profession XP, learned skills, weekly schedule, job-site identity, and station state.

A player changing a villager from fence to fisherman must not have that choice silently reverted when Crime refreshes presentation. Conversely, merely changing the displayed profession must not erase a criminal record or reset thief cooldowns.

Route Crime-requested permanent role changes through a Townstead-owned assignment service when one exists. Avoid invoking the current private UI handler directly: it releases POIs, resets vanilla level/XP and offers, and refreshes the brain. Those effects need an intentional contract for automated recruitment. [T16]

### 7.3 Real equipment versus displayed work tools

Introduce a Townstead-owned `EquipmentProvenanceView`:

~~~text
equipment slot
kind: PHYSICAL_EQUIPMENT / INVENTORY_MIRROR / TEMPORARY_DISPLAY / UNKNOWN
source inventory slot and revision, when applicable
owning work-session token
safe interruption/restore status
~~~

Crime needs this in loot, coercion, disarm/confiscation, restitution, and recovery paths that touch equipment.

Rules:

- An inventory-mirrored hoe is one physical tool, even when also displayed in the hand.
- Genuine separate tools with identical item IDs, enchantments, and damage remain separate items.
- A display-only copy is never confiscated or returned as extra property.
- If a work session stops, Townstead restores its owned presentation without overwriting equipment installed by a newer action.
- An original hand item stashed by Townstead must survive interruption, unload/reload, and death.
- Unknown provenance requires a conservative, documented equipment-only fallback, not a guessed removal from inventory.

Do not “fix” this by globally deduplicating equal ItemStacks. Crime's current identity check and Townstead's `tool.copy()` explain why provenance is needed, but item equality is not ownership. [T08] [C11]

For physical support calculations, distinguish carrying a usable weapon from simply being at work. A butcher holding a cleaver can be physically capable of defending herself; that fact must not automatically label her work as threatening or unlawful. Keep threat intent, aim, and action context separate from item classification.

### 7.4 Death and recovery

Continue using Crime's final-damage and confirmed-death pipeline. Townstead's Forge handlers can modify/cancel damage and cancel death through immortality or prevention. [T11] [C18]

Add integration cases for:

- A lethal-looking hit followed by Townstead death prevention.
- Immortal villagers surviving with health restored.
- A gene modifying a hit to zero.
- An actual death with Townstead life-stage loot, real carried equipment, displayed work tools, and Crime trade-stock drops.
- A player with Townstead keep-inventory behavior.
- A later event listener canceling death after another listener has run.

Canceled death must not resolve a kill bounty, release another captive as though the captor died, deliver stolen property as death recovery, or produce a murder case.

Townstead currently spawns life-stage loot from its death callback. Audit that path's finality separately; do not assume all Townstead drops arrive through the same `LivingDropsEvent` that Crime sees. If cancellation ordering permits premature drops, fix the originating Townstead path with deferred/finalized handling. Do not hide that problem by spawning replacement loot from Crime.

Preserve actual item NBT, enchantments, damage, and stack counts. Keep the existing `doMobLoot` and canceled-drop policies coherent between the two mods.

### 7.5 Rigs, movement abilities, and restraints

Townstead can alter size, rig, mobility, flight, phasing, and related behavior. Crime's current cuff layer assumes a HumanoidModel arm structure. [T10] [C19]

Separate visual and mechanical compatibility:

- Render cuffs only when the actual model supplies supported attachment points.
- Add an optional Townstead rig attachment adapter for alternate wrists or equivalent limbs.
- A model without supported wrist attachments receives a clear restraint indicator and existing tether presentation instead of broken floating cuffs.
- Check actual bounding boxes, eye positions, reach, and collision for capture distance and jail placement.
- A small or flying villager is still the same persistent entity; do not replace it with a standard villager for custody.

Ability handling must respect the configured restraint and jail rules. Keep physical-breakout modes meaningful. If a containment mode prohibits teleport/phasing escapes, enforce that through a narrow ability authorization hook while actual custody is active; preserve the ability definition and restore ordinary use after release. Allow permitted escape attempts to flow through Crime's existing escape/jailbreak outcomes.

Do not turn immortality, species traits, or the entire gene ticker off merely to make imprisonment convenient. A bridge failure must not permanently strip a player's or villager's abilities.

---

## 8. P1: Villages, buildings, jails, and care

### 8.1 Community and building identity

Keep `CrimeCommunityKey(dimension, villageId)`. Add a Crime-owned building reference:

~~~text
TownsteadBuildingRef {
  dimension
  villageId
  buildingId
  optional generation/fingerprint
  observed revision
}
~~~

Store a source building type string separately from any normalized Crime classification. Townstead's current snapshots expose building types as strings; its resources and reconcilers use names such as `dock_l1` and `kitchen_l1`. Do not assume every returned type string is already a namespaced registry identifier.

For existing crimes against villagers, preserve the established community policy and historical case identity. For new property incidents, use the property's explicitly associated community and the actual event location. Extend `IncidentService` with typed internal incident context: its current record creation derives community from a victim/offender anchor and cannot be corrected merely by adding a string to provenance. [C06] [C07]

Record separately:

- Event dimension and position.
- Victim's home community, when known.
- Property/facility jurisdiction, when applicable.
- The community selected by the legal policy.

Test traveling residents and border cases. Never create a fabricated village 0, and never infer a cross-dimensional jurisdiction from a matching integer ID.

### 8.2 Building lookup beyond the current API

The existing `buildingAt` is useful for a tooltip or initial context. Legal and facility decisions require a candidate query with:

- All relevant overlapping buildings.
- Exact supported geometry/floor information.
- Completion/validity status.
- Building revision and optional generation.
- Loaded-data status.
- Source of the recognition: enclosed, synthetic/open-air, or external floor system.

Choose matches deterministically by explicit assignment first, then supported exact containment, specificity, and a stable final ID order. An unresolved overlap returns ambiguity; it does not arbitrarily assign ownership.

Townstead already reconciles building tiers, docks, recognition, and spirit. Attach invalidation to those paths when a supported event/API is added. Use bounded revalidation as a fallback, without repeatedly running Townstead's scanners from Crime. [T17]

Village merge, split, deletion, reassignment, or building-ID reuse must not silently attach old property rights or a sentence to a different place. Prefer an authoritative identity-change notification. Otherwise require fingerprint/revision revalidation and retain unresolved references for operator inspection.

### 8.3 Recognized civic facilities

Add Crime facility roles independent of Townstead's existing type names:

| Proposed role | Function | Default relationship to Townstead |
|---|---|---|
| Guard post | Patrol anchor, report destination, shift meeting point. | Explicitly assign an existing valid building or area. |
| Jail cell | Safe custody region with capacity and release point. | Explicit facility assignment; optional new building definition later. |
| Guardhouse | Contains multiple assigned cells and on-duty guard facilities. | Aggregates assigned cells; does not imply every room is a cell. |
| Evidence storage | Holds explicitly deposited evidence or impounded items. | Excluded from ordinary Townstead sourcing. |
| Care room | Safe temporary recovery and feeding location. | Only a validated, configured region qualifies. |
| Public notice location | Displays public cases, bounties, and approved civic work. | Optional placement in a village building. |

The reviewed Townstead resource tree does not supply a universal jail API or a ready-made jail/guardhouse pair. These roles are proposed additions. Do not assume a kitchen, inn, pen, or wool shed is automatically suitable for confinement.

### 8.4 Facility registration and destination selection

Add `CrimeFacilityService` and `FacilityAssignment` records, retaining Crime's existing manually assigned anchors.

Registration validates:

1. Dimension, community, building reference, and current revision.
2. Loaded and valid interior geometry.
3. Safe entry and release positions.
4. Collision/headroom for the actual prisoner.
5. Hazard checks appropriate to the prisoner and containment mode.
6. Capacity, reservations, and separated cell regions.
7. Accessible food/drink/rest arrangement for applicable Townstead needs.
8. Exclusion from active production, crop/enclosure areas, emergency-bed claims, and unrelated resident homes.

Automatic arrests should select:

1. A still-valid explicitly assigned local cell.
2. A validated unoccupied cell in the same jurisdiction.
3. The existing bounded local manual-anchor fallback.
4. A temporary holding cell on a permitted site.
5. Crime's explicit safe recovery/fallback behavior when no valid destination exists.

Preserve the special semantics of an operator explicitly selecting a distant jail; do not change all automatic arrests into unlimited-distance teleports. Crime already distinguishes bounded automatic selection from an unlimited administrative lookup. [C15]

Reserve a cell slot before beginning the escort. Consume or release that reservation through a token so simultaneous arrests cannot double-book it. Revalidate the destination on arrival.

### 8.5 Temporary holding cells

Extend `CellBuilder.siteIsClear` with a Townstead-aware exclusion policy:

- No intersection with an active work area, recognized building interior, dock approach, enclosure, planted field, bed claim, or reserved facility.
- Check the whole cell footprint and reasonable access space, including open-air/synthetic building geometry.
- Reject ambiguous/unavailable building context inside a known Townstead work area.
- Retain existing loaded-chunk, block-entity, fluid, world-border, and rollback protections.
- Continue restoring only blocks still matching what Crime placed.

When Townstead data is unavailable, a server can configure a conservative no-auto-build policy for its villages, with manual jails continuing to work. Provide a useful explanation when no site qualifies. Do not hide placement failure behind an endless escort.

### 8.6 Needs in custody

Lawful custody must be compatible with the needs system it constrains.

**Default behavior:** needs continue; a prisoner can consume appropriate accessible supplies and rest inside the approved region. Townstead performs nutrition, hydration, purity, container-return, and diet handling.

Create `CustodyCarePolicy`:

- Restrict food/drink acquisition to the cell's inventory, assigned supply container, or an authorized direct delivery.
- Allow in-place consumption even when ordinary work travel is suspended.
- Limit bed selection to the assigned region; preserve the villager's original home and release temporary bed claims afterward.
- Use actual stock. A virtual emerald purse is not a physical meal inventory.
- Exclude evidence and restitution containers from sourcing, even if they contain food.
- Permit family or player care through a validated interaction without turning it into repeated karma farming.

If a lawful prisoner becomes critically unfit:

1. Attempt care using available valid supplies.
2. Request a capable guard and a validated care destination.
3. If no safe care destination exists, use an explicit custody-recovery transition that suspends unsafe confinement while retaining the sentence/case association and operator-visible recovery record.

This must not call a generic “served,” “pardon,” or administrative release path that accidentally clears liability. Add a specific recovery reason and tests if the current custody model cannot express it. Denying a new facility assignment for lack of basic care is preferable to trapping residents until they die.

For unlawful captives, needs and actual supplies also remain meaningful. Do not grant a kidnapper free supplies or rewards for repeatedly feeding a self-created captive. Rescue and neglect consequences require attributable evidence and bounded incident rules.

### 8.7 Facility destruction and unavailable regions

If a jail changes, unloads, floods, loses a bed, or becomes invalid:

- Stop new assignments.
- Preserve current sentence and case identities.
- Revalidate when data becomes available; lack of a loaded chunk is not proof of destruction.
- Move an endangered prisoner only through the custody service and a validated destination.
- Release the facility reservation exactly once.
- Keep unresolved recovery work inspectable.

Do not force-load a network of villages to maintain a jail index. Do not mark a sentence served because the building was deleted.

---

## 9. P1: Guard shifts and reports that fit Townstead life

### 9.1 Patrol coverage

Introduce `GuardDutyService` as policy around existing responders, not a replacement combat AI.

Track:

- Assigned guard roster.
- Scheduled shift and weekly plan.
- Current effective activity.
- Awake/collapsed/unfit status.
- Existing legal assignment.
- Guard-post assignment and local coverage.

Prefer awake, capable, unassigned guards already working a suitable shift. Off-duty guards can respond to a nearby immediate threat according to configuration; remote minor reports can wait or find another guard.

Provide optional day/night templates and an administrative **suggest coverage** action. Applying a new schedule must be explicit. Preserve custom plans and account for Townstead chronotypes; do not decide all night duty from vanilla time alone.

Coverage diagnostics should say “4 guards, 1 on duty, 1 escorting, 2 resting,” rather than simply “4 guards.” The UI must distinguish unavailable roster members from missing employees.

### 9.2 Reporting behavior

Existing Crime report delivery requires a live reporter/responder, proximity, line of sight, a pending observation, and sufficient legal evidence downstream. Keep those checks. [C20]

Improve destination selection:

1. Nearby capable guard with no conflicting assignment.
2. Known local guard post where a capable guard is present.
3. A safe waiting place with bounded retry.
4. Resume ordinary life while retaining an unexpired pending observation when immediate delivery is impossible.

A guard post is a navigation destination, not a magical reporting receiver. A hearing-only witness can raise an alarm without naming a suspect. A report delivered after waking uses the witness's stored perception; it does not reveal everything that happened during sleep.

Coordinate reporting with fatigue and work interruption. A witness fleeing an immediate threat should not detour into a kitchen because the next schedule hour began.

### 9.3 Emergency handover

If an escort guard collapses, dies, becomes unavailable, or is drawn into immediate defense:

- Use existing responder assignment ownership to nominate one replacement.
- Keep the prisoner and sentence bound to the same case set.
- Pause or safely complete transfer according to existing custody recovery rules.
- Do not classify an automatic medical/handover interruption as prisoner resistance.
- Distinguish actual prisoner escape from inability to follow a broken route.

An optional second escort for a dangerous case must have a distinct support role; only one actor controls the prisoner's route and custody transition.

### 9.4 Bounded patrol opportunities

After stability:

- Let guards visit assigned workplaces, docks, and public gathering points during duty.
- Weight routes by publicly reported recent incidents and explicit guard-post coverage.
- Apply a capped, decaying weight; avoid turning the entire village into permanent pursuit mode.
- Keep patrol routing separate from evidence. Visiting a theft hotspot does not identify an offender.
- Never increase patrol frequency from private incidents that nobody reported.

---

## 10. P2: Property law and working supplies

This is desirable, but it is a new gameplay and transaction subsystem. Ship it only after the P0/P1 foundation and keep it separately configurable.

### 10.1 Ownership and access

Townstead knows where work and supplies are. Its current storage protection is based on block IDs/tags, including `townstead:protected_food_storage`; that is a sourcing exclusion, not a player ownership ACL. [T13]

Introduce an explicit Crime `PropertyPolicy`:

~~~text
property ID, revision, community, region or container references
owner kind: PLAYER / HOUSEHOLD / COMMUNITY / UNASSIGNED
owner identity, when applicable
authorized players and role-based permissions
access by purpose: PERSONAL_USE / WORK / DONATION / DELIVERY / CARE / EVIDENCE
public allowance, when enabled
offense mapping and notification policy
~~~

Default rules:

- A recognized Townstead building alone does not create ownership.
- Existing player containers remain unclaimed unless an owner or authorized operator assigns them.
- An unknown or disputed owner does not authorize a theft charge.
- Townstead workers can use the inputs explicitly authorized for their task.
- Deposits, agreed deliveries, restitution, and permitted care are legitimate operations.
- Opening or inspecting a container is not completed theft.
- An opt-in policy can define communal supplies, private supplies, or limited public rations.

Automatic protection of generated village property can be offered later as a separate mode. It must distinguish generated property from player construction and be previewable before activation.

### 10.2 Verified withdrawal detection

Do not detect player theft by comparing a chest's contents at open and close. A Townstead worker, hopper, producer, or second player could change it during the same interval.

For the initial implementation, support a narrow, tested set of vanilla container menu transfers:

1. Resolve the actual server menu, source container/slot, and authenticated player.
2. Check property revision and access for that exact operation.
3. Observe the committed transfer, including cursor, shift-click, hotbar swap, throw, drag, and double-click routes.
4. Compute the attributable quantity transferred to that actor or deliberately thrown by that actor.
5. Assign one transfer ID and one incident grouping key.
6. Commit a Crime incident only after the unauthorized transfer actually occurs.
7. Create provenance/restitution records for the actual quantity, with bounded metadata.

There is no general extraction event in the reviewed mods that proves this automatically. Use precise version-tested hooks for supported menus, and require explicit adapters for custom menus and storage networks. An unsupported source should be reported as unsupported rather than charging the nearest player.

Cover double chests, sided item handlers, container replacement, canceled clicks, disconnects with a cursor stack, and simultaneous automation.

Transaction integrity matters across a server crash. Keep the initial scope local and synchronous on the server thread, with persistent transfer receipts and reconciliation appropriate to the inventory operation. Do not claim database-like atomicity between independent Minecraft inventories and SavedData. If an outcome is uncertain, retain an unresolved receipt instead of repeating a debit or granting restitution.

### 10.3 Attribution and offense context

Extend the internal incident request to include:

~~~text
incidentId
actor identity and actor kind
actual location and optional property community
victim identity, if a specific owner is known
property reference and revision
transferred item fingerprint/count
server-verified action source
~~~

Use the same witness and report pipeline as existing theft. The fact that the server knows who moved an item does not mean every villager knows. A nearby owner may discover a loss later, but discovery without identifying evidence should produce an unknown-suspect report.

Retain history when property is later transferred to another owner. Do not rewrite past crimes or refund a different person just because a container changed hands.

### 10.4 Townstead worker operations

Add a scoped `WorkTransferContext` owned by Townstead, containing worker, task, source, purpose, and operation ID. Only server-side task code may create it.

Use it to:

- Permit legitimate collection of ingredients, seeds, tools, water, and fuel.
- Permit output deposits and container returns.
- Distinguish accepted donations from taking community stock.
- Exclude reserved/evidence goods.
- Produce optional auditable loss or damage information without inventing a player offender.

A role label is not a blanket exemption for arbitrary NPC theft. Validate that the worker, source, and operation match an active task and property policy.

### 10.5 Further property offenses

Add individually toggled, data-defined offenses in increasing complexity:

| Offense | Minimum proof | Important exemption |
|---|---|---|
| Theft of supplies | Verified unauthorized committed withdrawal. | Authorized work, donation handling, public allowance, restitution. |
| Crop destruction | Confirmed player-caused destruction of registered protected crops. | Authorized harvest/replant and farming tasks. |
| Workstation sabotage | Confirmed unauthorized destructive change with a meaningful consequence. | Owner renovation, maintenance, canceled actions. |
| Livestock harm | Attributable harm to explicitly registered livestock/property. | Authorized Townstead butchery/slaughter operations and lawful self-defense. |
| Tampering with custody/evidence | Verified unauthorized action against an assigned facility or sealed record. | Authorized rescue/recovery, owner/operator maintenance, legitimate release. |
| Trespass | Explicitly registered restricted region, visible warning, and grace period. | Unclaimed buildings, public routes, necessary access to the player's own property. |

Avoid escalating every broken block into several overlapping charges. Group a continuous action into a bounded incident and choose the most appropriate offense or explicitly compatible components.

Explosion, fire, indirect damage, redstone, and environmental attribution require dedicated policies. Do not assign them to the closest player.

### 10.6 Making restitution useful

Returning stolen work materials should help the village recover:

- Return actual tracked goods to the rightful owner or designated escrow.
- If the source container no longer exists, retain recovery goods in Crime's recovery system.
- Provide a repair/replacement route when goods were legitimately consumed or transformed.
- Resolve only the corresponding property loss and case component.
- Never erase assault or kidnapping merely because stolen bread was returned.
- Preserve item data and prevent repeated turn-ins against the same lot.

An optional accepted work order can authorize replacing missing seeds or tools. The authorization belongs to the specific order and should not grant general access to the entire village warehouse.

---

## 11. P1/P2: Social responses and reciprocal gameplay

### 11.1 Separate truth, knowledge, and presentation

Add observer-specific/public consequence views before exposing Crime information to Townstead.

| Consumer | Allowed information |
|---|---|
| Direct victim | Their actual memories and valid observations. |
| Eyewitness | What that witness perceived and retained. |
| Guard | A legal decision based on the guard's jurisdiction and accepted evidence, plus existing independent legal bases. |
| Ordinary resident | Publicly disseminated local information within the selected gossip policy. |
| Player viewing their own ledger | Existing authorized personal case information. |
| Operator with permission | Diagnostic/full information through explicit administrative routes. |

A server API returning full case data is not automatically suitable for a public UI. Do not populate Townstead dialogue, client packets, village alerts, or economic restrictions directly from every `CrimeCommittedEvent`.

Use accepted reports and case resolution transitions for public effects. Use exact victim/observer memory for personal effects. Hearing-only alarms can change a local scene without publishing the offender UUID.

### 11.2 Townstead reactions

Add a small event vocabulary:

~~~text
crime_threatened
crime_witnessed
alarm_heard
report_delivered
custody_started
custody_ended
rescued
property_returned
restitution_completed
~~~

Map it into Townstead's data-driven reaction system through a dedicated bridge. Each payload includes an effect ID, audience, subject IDs where known, cause/case identity, and expiry. A reaction may be in-place/nonblocking or require an activity claim.

Examples:

- A threatened cook stops her work display and raises her hands using an installed supported emote.
- A witness looks toward an alarm, then heads toward a guard.
- A released prisoner pauses at the gate, receives her belongings, and returns to her current schedule.
- A rescued resident seeks a safe person or location before resuming work.
- A victim accepts restitution with guarded relief instead of instantly acting as a close friend.

The reviewed resources include wave, applause, and dance reactions; additional arrest/distress animations are proposed assets and must have working fallbacks. Do not assume an emote provider or a particular animation ID is installed.

### 11.3 Modest effects on daily life

Use short, bounded disruptions tied to actual incident participants:

- A mugging victim pauses work briefly and can request an escort home.
- A reporting witness has an authorized interruption of her shift.
- A rescued resident gets a recovery interval appropriate to her needs.
- Nearby residents who heard an alarm briefly avoid the immediate scene.
- A worker resumes production once the specific danger ends.

Avoid permanently reducing production across a village after every petty theft. Keep minimum service availability, decay, per-incident deduplication, and a maximum cumulative disruption budget.

Townstead already applies need-related mood pressure. Crime memory should not be counted again every tick as a new Townstead mood event. Apply a bounded transition effect or let dialogue read the existing Crime memory directly.

### 11.4 Village spirit and security

Use Townstead spirit as setting and optional planning context. Its existing categories include martial, commercial, pastoral, nautical, scholar, and others, derived from completed buildings. [T14]

Possible connections:

| Townstead character | Sensible Crime connection |
|---|---|
| Martial | Suggest guard coverage and civic guardhouse facilities. |
| Commercial | Offer restitution, goods-recovery, and merchant escort work. |
| Pastoral | Prioritize recovery of protected farm supplies and livestock-related help. |
| Nautical | Use docks as patrol and escort destinations when accessible. |
| Scholar | Provide public case summaries, notice access, and carefully scoped clerical work. |
| Tourism | Make public safety information visible to visitors. |

Keep a proposed dynamic `VillageSecurityView` separate from spirit totals. It can summarize public recent incidents, available guards, unresolved recovery requests, and validated facility coverage. It must not mutate building-derived spirit points or become a hidden collective reputation penalty.

### 11.5 Essential services and individual relationships

If optional service restrictions are enabled:

- Base a personal refusal on that villager's justified fear/anger or a valid public local case.
- Let basic food, water, medical/care access, restitution, surrender, and case-resolution actions remain reachable through a safe channel.
- Keep the owning player's property accessible unless an explicit legal impoundment exists.
- Explain the specific reason and route to repair the relationship.
- End temporary restrictions when their condition ends; do not retain a stale cached ban after pardon or expiry.

A low karma band or a genetic trait should not automatically disable every Townstead service.

### 11.6 Existing suite integration

Preserve the Crime → Reputation authority handshake and case dedupe keys. Crime already defers public incident delivery until relevant reports exist when observations are enabled. [C08]

Townstead should consume that result or Crime's filtered view, not publish the same assault independently. Do not call `communityStanding` blindly as though it necessarily represents the external Reputation mod: the reviewed `McaCrimeApi.communityStanding` reads Crime's fallback store. Add a clearly defined effective-standing projection if needed.

MCA: Quests and MCA: Conversations are optional extension targets. Their current source was not reviewed for this document. Use Crime's existing integration seams and verify the installed companion APIs before implementing new work orders or dialogue topics.

---

## 12. Creative extensions with concrete boundaries

These features should build on the compatibility foundation rather than block it.

| Feature | Player experience | Required implementation | Suggested release |
|---|---|---|---|
| **The night watch** | Help organize a village's day/night guard coverage around real sleep patterns. | Duty view, schedule suggestions, explicit application, roster protection. | P1 |
| **A kitchen for the guardhouse** | Townstead cooks supply real meals to an assigned jail care container. | Authorized delivery task, consumption adapter, actual stock accounting. | P2 |
| **Home safely** | Escort a frightened witness or rescued resident home, to family, or to a guard post. | Existing reaction/custody coordination, safe destination, one completion token. | P2 |
| **Make good** | Replace stolen seeds, return a tool, or repair a specific damaged workstation. | Property loss identity, work order, delivery validation, partial resolution. | P2 |
| **A place to report** | Visit a guard post or public notice location to see known local problems. | Observer/public views, staffed report destination, permission checks. | P1/P2 |
| **Return to work** | A released resident regains access to her actual job and gradually recovers trust. | Safe profession/schedule restoration, optional recovery interval. | P1 |
| **Community service** | Settle an eligible minor case through a concrete useful task. | Case-bound contract, authorized materials/region, completion and anti-farming rules. | P3 |
| **Witness protection** | A threatened witness can wait in a staffed safe place and later report. | Voluntary refuge state, restricted knowledge, escort ownership, bounded duration. | P3 |
| **A troubled workplace** | Repeated public incidents at a dock or market prompt guard-route suggestions. | Public security projection with caps and decay. | P2 |
| **Specialist fences** | A fence's legal front and limited illicit stock fit the settlement's trade character. | Finite stock, optional profession overlay, explicit illicit-goods provider. | P3 |
| **Supply recovery board** | A village requests return of specific missing property or rescue of an actual captive. | Existing Crime case/lot/captive IDs; optional Quests adapter after API verification. | P2/P3 |
| **Care after rescue** | Feed, hydrate, or accompany a rescued resident through a short recovery. | Needs adapter and rescue-linked, once-only recognition. | P2 |

### 12.1 Community service design

Keep an initial community-service system small:

1. A guard offers it only for configured minor, public, unresolved cases.
2. The player accepts a server-issued contract naming exact case IDs and obligations.
3. The contract grants narrowly scoped work permissions.
4. Work is measured by actual accepted outputs or verified repairs.
5. Completion resolves only the agreed fine/restitution component.
6. The original incident and victim memory remain.

Reject severe or mandatory-custody cases by default. Prevent self-created damage/reward loops, duplicate output submissions, task regeneration after reconnect, and repeated credits for the same transferred items.

Use existing action and settlement infrastructure for offers and confirmation. A Quest representation is an optional presentation layer; Crime retains the case and completion authority.

### 12.2 Economy policy

Keep Crime's finite `VillagerPurse` as its existing money source. A Townstead profession tier may select a capped purse profile if explicitly enabled, but refreshing an API snapshot, switching a job, or changing the calendar must not mint currency.

Start with fixed, documented profiles or small bounded multipliers. Use the purse's existing refill policy and transaction receipts. Avoid automatically converting production counts into emerald income.

A real village treasury, salaries, bribes, confiscation budgets, or production-backed banking are separate projects. If later implemented, they need their own account owner, deposit/withdrawal rules, insufficient-funds behavior, receipts, and migration plan.

### 12.3 Features to defer until evidence and transactions are strong

Defer NPC theft from arbitrary villagers, organized criminal factions, dynamic taxation, full trials, political imprisonment, a replacement village economy, and procedural prison construction across entire settlements.

They could fit a later vision, but each adds substantial attribution, AI, persistence, and fairness requirements. The first integrated release should establish reliable villagers, meaningful facilities, useful recovery, and clear public consequences.

---

## 13. UI, interaction, and server authority

### 13.1 Crime entry points

Keep the existing MCA interaction-screen button and fallback input. Add an explicit integration with Townstead's `RpgDialogueScreen`:

- A compact **Crime / Law** action, using the existing server-issued Crime menu.
- A contextual **Return property**, **Make restitution**, or **Ask for help** action when valid.
- Custody and interruption information that does not obscure needs icons.
- A safe return to the initiating screen if the target is still valid.

Use a screen adapter or extension registration mechanism. Crime currently recognizes a class-name suffix for MCA's InteractScreen and searches fields for a villager. Do not extend that heuristic to every Townstead screen. [C14] [T12]

Track target UUID, dimension, initiating screen, and interaction token. On screen transitions, transfer or close the actual interaction explicitly. Close cleanly when the target dies, unloads, moves out of range, or becomes unavailable.

An emergency Crime menu must not leave Townstead's dialogue state or animation lock active behind it. A Townstead transition must not cancel an ongoing server-side legal encounter merely because the UI object changed.

Townstead's dialogue also owns camera/HUD state, and its `removed()` method can queue reopening while text or a late response is pending. Add an explicit external-interruption close reason: cancel queued reopening and late-response routing, restore camera/HUD state exactly once, and close the corresponding server dialogue token. Returning to dialogue requires a valid interaction session. Do not assume that replacing the current screen performs this cleanup. See `RpgDialogueScreen` in [T12].

### 13.2 Player-facing explanations

Prefer useful reasons:

- “Resting. Another guard is handling the report.”
- “Collapsed and unable to respond.”
- “Escorting a prisoner.”
- “This cell has no available place to rest.”
- “These supplies are reserved for the kitchen.”
- “Return the missing tool to settle this property claim.”
- “No safe local holding cell is available.”

Do not show bridge class names, capabilities, internal enum values, or protocol errors in ordinary gameplay. Put those in the administrative diagnostic report.

### 13.3 Information scope and packets

Use `ServerPacketGuard`, existing request budgets, and action/session validation.

For new packets:

- Resolve entities and buildings on the server.
- Validate dimension, range, permissions, current revision, action eligibility, and loaded state.
- Limit collection sizes and text lengths.
- Send only what the requesting viewer may know.
- Reject stale or replayed action tokens.
- Keep simulation and legal mutation on the server thread.

Do not send full villager inventories, private victim memories, unreported cases, hidden thief roles, or all village property owners merely to draw a status icon. The current Crime snapshot/record APIs are useful internal tools; each new public UI needs its own authorized projection.

### 13.4 Administrative actions

Proposed additions under Crime's existing command root:

~~~text
/crime debug townstead
/crime debug townstead entity <target>
/crime debug townstead village
/crime facility list
/crime facility assign <role> ...
/crime facility validate
/crime facility remove <assignment>
/crime property inspect
/crime property assign ...
/crime property access ...
/crime duty inspect
/crime duty suggest
~~~

Reuse Crime's established distinction between diagnostics and mutation permissions. Self-service actions use normal action authorization; facility/property changes require an appropriate owner or operator policy. Implement an explicit policy rather than treating mere presence inside a village as administrative authority.

The reviewed Townstead Forge profession handler locates a villager across loaded dimensions and performs a substantial assignment change. Before adding legal/facility controls to that UI, audit its current request validation and shared server assignment path. Do not reuse it as an implicit permission check. [T16]

### 13.5 Client compatibility

Verify GUI scales, short screens, translations, keyboard focus, tooltips, and opening Crime from both MCA and Townstead dialogue. Test alternate rigs, player animations, and common MCA-rendering paths using the actual distribution jars.

Client presentation failure should leave the server-issued fallback interaction usable. Dedicated servers must never load Townstead client classes from a common bridge initializer.

---

## 14. Configuration and datapack design

### 14.1 Configuration principles

Keep an automatic integration switch and separate subsystem switches. A setting that introduces new legal obligations should never be hidden inside a compatibility toggle.

Suggested initial server settings:

| Proposed setting | Default | Meaning |
|---|---|---|
| `townstead.enabled` | true | Attempt supported automatic integration. |
| `townstead.coordinateActivities` | true | Coordinate behavior when the required hook is verified. |
| `townstead.respectIncapacity` | true | Respect collapse and effective stage capabilities. |
| `townstead.protectWorkerAssignments` | true | Protect active/manual Townstead jobs from automatic role changes. |
| `townstead.equipmentProvenance` | true | Use verified display-equipment ownership information. |
| `townstead.useAssignedFacilities` | true | Use explicitly registered compatible facilities. |
| `townstead.excludeWorksitesFromTemporaryCells` | true | Protect recognized work areas from generated cells. |
| `townstead.custodyCare` | true | Provide compatible needs handling using actual permitted supplies. |
| `townstead.guardScheduleAwareness` | true | Prefer capable guards in suitable duty periods. |
| `townstead.emergencyOffDutyResponse` | true | Permit nearby capable off-duty response to configured urgent situations. |
| `townstead.automaticShiftAssignment` | false | Preserve player plans; schedule application is explicit by default. |
| `townstead.publicReactions` | true | Enable filtered, bounded incident reactions. |
| `townstead.needResponseModifiers` | false | Optional balancing beyond basic incapacity correctness. |
| `townstead.propertyLaw` | false | New explicit property-law system. |
| `townstead.autoProtectGeneratedProperty` | false | Separate automatic ownership/protection experiment. |
| `townstead.serviceRestrictions` | false | Optional relationship/public-law service restrictions. |
| `townstead.communityService` | false | Optional case-bound work settlements. |
| `townstead.economyProfiles` | false | Optional capped purse-profile changes. |
| `townstead.securityPresentation` | true | Display public coverage and facility information where available. |

Feature switches apply only when their required capabilities are available. Diagnostics should distinguish configured-on, available, active, and degraded.

Turning integration off must safely relinquish transient claims and stop new optional effects. It must not erase custody, case, property, recovery, or facility records. If a mid-session switch cannot safely complete a handover, defer it until the current operation finishes or a restart and explain that behavior in CONFIG.md.

### 14.2 Datapack locations

Proposed Crime-owned data:

~~~text
data/<namespace>/mcacrime/townstead/building_roles/*.json
data/<namespace>/mcacrime/townstead/personality_profiles/*.json
data/<namespace>/mcacrime/townstead/civic_work/*.json
data/<namespace>/mcacrime/townstead/reaction_bindings/*.json
data/<namespace>/mcacrime/townstead/property_rules/*.json
~~~

Keep Townstead's native building, profession, reaction, and schedule definitions in its own formats. A Crime mapping references them; it should not redefine the entire Townstead registry.

Example proposed building-role mapping:

~~~json
{
  "schema": 1,
  "requires_mods": ["townstead"],
  "match": {
    "townstead_building_types": ["dock_l1", "dock_l2", "dock_l3"]
  },
  "roles": ["patrol_destination", "public_notice_candidate"],
  "requires_complete": true,
  "creates_property_claim": false,
  "creates_jail_assignment": false
}
~~~

This maps actual type strings used in Townstead's source into contextual roles. It does not invent land ownership or a jail cell.

Example proposed personality-profile override:

~~~json
{
  "schema": 1,
  "townstead_personality_id": "example:steadfast",
  "bravery_delta": 0.05,
  "report_persistence_multiplier": 1.1,
  "maximum_combined_delta": 0.1
}
~~~

`example:steadfast` is illustrative, not a claimed built-in Townstead personality. Unknown exact IDs should be reported and inactive; preserve the MCA-base fallback.

Example proposed community-service template:

~~~json
{
  "schema": 1,
  "id": "example:replace_farm_supplies",
  "eligible_crimes": ["mcacrime:theft"],
  "requires_public_case": true,
  "requires_minor_non_custodial_case": true,
  "objective_source": "linked_property_loss",
  "completion": "accepted_replacement_delivery",
  "maximum_reward_claims": 1,
  "resolves": ["property_restitution"],
  "grants_general_storage_access": false
}
~~~

The template derives actual items and quantity from the authoritative loss record. It must not award repayment for a hardcoded arbitrary item stack.

### 14.3 Validation and reload

Validate:

- Schema version.
- Namespaced references where the referenced system actually uses them.
- Literal Townstead building type matching.
- Required mods/capabilities.
- Numeric ranges and finite floating-point values.
- Bounded list/string sizes.
- Duplicate IDs and deterministic priority rules.
- Eligible case categories and allowed resolution components.
- Unsupported menu/storage adapters.

Parse into a complete replacement registry and swap only after validation. Invalid new data must not half-apply or modify active sentences. Keep existing receipts tied to the rules/revision that created them where changing interpretation would alter money, items, or legal obligations.

Define compatibility-specific resource conditions so Townstead-dependent assets are inactive when absent. Crime-owned generic data may remain registered if it is harmless and unused.

---

## 15. Persistence, idempotency, and performance

### 15.1 Persist only durable facts

Extend Crime's existing SavedData with versioned records for:

- Facility assignments, cell reservations, and retained safe-recovery state.
- Explicit property policies.
- Property transfer/loss associations.
- Case-bound civic work and its completion state.
- Idempotency receipts for durable cross-mod consequences.
- Optional bounded public security summaries or the minimal facts needed to rebuild them.

Do not persist transient Townstead snapshots, full entity NBT, entire inventories, Brain objects, navigation paths, every position in a village, or a second copy of all Crime cases.

At the reviewed Crime revision, the schema is **10**. The next coordinated durable-format change can introduce **11**, but the implementing agent must recheck the latest branch first and use the next available version. Keep migration pure, deterministic, and independent of live Townstead classes. [C03]

### 15.2 Migration and removal

For an existing world:

1. Load all existing Crime data with its current semantics.
2. Add empty optional collections; infer no historical property ownership or new charges.
3. Treat existing manually assigned jail anchors as valid legacy/manual assignments.
4. Revalidate new Townstead references lazily and within budgets.
5. Retain ambiguous associations for operator review.
6. Preserve newer/future data protections and `ServerMutationGate`.

When Townstead is removed:

- Stored IDs and primitive records still deserialize.
- Crime retains its legal and recovery authority.
- Snapshot-dependent flavor and role suggestions stop.
- Valid Crime custody uses its persisted safe destination/region or explicit recovery route.
- Stale Townstead property references stop producing new offenses until resolved.
- Pending durable deliveries remain inspectable; expired transient reactions are discarded.

Reinstalling Townstead must not replay old applause, reissue completed rewards, duplicate ration deliveries, or restore obsolete professions.

Document downgrade behavior honestly. A migration that adds records an old Crime version cannot safely read is not automatically reversible; ship backup guidance and a tested upgrade path.

### 15.3 Cross-mod delivery

Use an effect identity such as:

~~~text
source incident or case ID
resolution revision
effect kind
recipient UUID or facility ID
~~~

Use unique transaction IDs for material transfers and civic-work completion. Each receiver acknowledges already-applied effects.

Crime's existing `CrimeIntegrationPump` currently dispatches to Reputation and has Reputation-specific dead-letter fallback behavior. **It is not already a generic Townstead delivery bus.** Before using it:

1. Introduce target-specific handlers and validation.
2. Preserve existing Reputation target behavior byte-for-byte where feasible.
3. Restrict deferred village-penalty fallback to the appropriate Reputation operation.
4. Give Townstead effects their own retry/expiry/error semantics.
5. Keep idempotency at the receiving owner as well as the sending queue.

Do not enqueue all new effects as a generic CREATE and accidentally trigger Reputation fallback penalties when a Townstead animation fails. [C08]

Transient presentation can use bounded best-effort delivery with short expiry. Durable effects involving property, money, case resolution, or material consumption need receipts and reconciliation. A visual acknowledgment is never proof of successful inventory transfer.

### 15.4 Threading and bounded work

All entity, inventory, brain, legal, and Townstead state access runs on the server thread. Async tasks may parse immutable data, but must not access live villagers or containers.

Initial operating budgets, to be measured:

| Work | Starting budget |
|---|---|
| Activity ownership query | O(1), no world scan or reflection binding. |
| Basic entity snapshot | Cache for up to 20 ticks; invalidate on relevant transitions. |
| Critical action eligibility | Revalidate current capability at commit; do not trust a stale snapshot. |
| Facility/building validation | At most 2 queued facilities per tick; spatially bound each query. |
| Public security recomputation | At most 1 affected village per tick from a dirty queue. |
| Patrol route planning | Stagger by guard; avoid per-tick full path search. |
| Transient reaction delivery | Small per-tick budget and expiry. |
| Durable outbox | Reuse/configure bounded pumping with separate target handlers. |

Use Townstead's existing storage/navigation caches through supported APIs where useful, but do not iterate raw village inventories merely to paint a UI. Its storage snapshots can be temporarily stale and contain live inventory handles; they are not permission proofs or transaction commits. [T13]

If an expensive optional query exhausts its budget, return pending/unavailable context and retry later. Avoid synchronous fallback scans that defeat the budget.

### 15.5 Diagnostics

`/crime debug townstead` should report:

~~~text
Townstead version and detected artifact namespace
Crime version and integration contract version
detected MCA package root
capabilities: available / unavailable / degraded, with concise reason
active Crime-owned villager activities
work interruptions and material-reconciliation failures
guard roster / available / assigned / resting / collapsed counts
valid / stale / ambiguous facility references
supported property menu adapters
pending durable deliveries and expired transient effects
snapshot hits, refreshes, and budget deferrals
~~~

Provide a focused per-entity diagnostic for owner token, current schedule, capability decisions, and last interruption reason. Keep UUIDs/private case content out of the default shareable summary; explicit privileged entity diagnostics can expose needed identity.

Clear all transient state on server stop and verify a second integrated world in the same process does not inherit the first world's activity locks or caches. Townstead already has memory lifecycle cleanup infrastructure; register the new owned caches consistently. [T18]

---

## 16. Implementation work packages

The following order is deliberate. Complete and validate each package before activating the next. All new class names below are suggestions, not claims that those files already exist.

### WP0 — Reproducible baseline and fixtures

**Changes**

- Record current source commits and artifact hashes.
- Obtain actual matching legacy and modern Townstead distribution jars and their dependencies.
- Add optional integration-test/run configurations without shading dependencies.
- Correct stale README/mixin/schema statements.
- Create small saved-world fixtures for a worker, guard without HOME at REST, supplied worksite, alternate life stage, and existing Crime custody.

**Acceptance**

- Baseline Crime alone launches.
- Matching Townstead combinations launch on dedicated server and client.
- Namespace mismatch is distinguishable from an integration bug.
- The pre-fix guard/rest and collapse-awareness scenarios are recorded as observed failures or non-reproducing source risks.

### WP1 — Optional context bridge

**Changes**

- Add binding/capability/result types and DTO conversion.
- Query the existing public entity/building/calendar API.
- Add capability diagnostics and version fixtures.
- Extend forbidden-shading/static-link checks.

**Acceptance**

- Townstead absent behaves normally.
- The adapter ships from a standalone Crime checkout.
- API/signature failures disable only affected optional capabilities.
- Dedicated server does not load client code.

### WP2 — Behavior and needs compatibility

**Changes**

- Add effective entity capabilities and activity policy.
- Wire every relevant Crime actor/witness/responder path.
- Add Townstead work, rest, consumption, reaction-lock, and tool-control hooks.
- Add material-safe task interruption and owner-aware cleanup.

**Acceptance**

- Peaceful guard approach survives REST scheduling.
- Collapsed NPCs cannot act as awake witnesses/guards/thieves.
- Captured workers stop safely and resume without loss/duplication.
- Needs and life processing remain active during custody.
- New profession/shift edits survive release.

### WP3 — Equipment, roles, and death correctness

**Changes**

- Add display-equipment provenance and owned restoration.
- Protect worker assignments during recruitment/presentation.
- Validate custom stages and rig fallback.
- Exercise final-damage and death cancellation with Townstead abilities.

**Acceptance**

- One work tool remains one physical item.
- Separate identical tools remain separate.
- Canceled death has no death-only Crime consequences.
- Auto-recruitment preserves active Townstead workers.
- No stale role restoration overwrites a player change.

### WP4 — Facilities, care, and duty

**Changes**

- Add building references, facility assignments, cell reservations, and invalidation.
- Protect temporary-cell placement.
- Add confined care and safe recovery transitions.
- Add guard duty view, handover, and staffed report destinations.

**Acceptance**

- Arrests use a valid local facility, or finish with an explicit safe fallback.
- Multiple prisoners cannot share one reserved capacity slot accidentally.
- Worksites remain intact.
- Prisoners can meet enabled needs from real authorized supplies.
- Unavailable guards are handed over without false resistance charges.

### WP5 — UI and public consequences

**Changes**

- Add Townstead dialogue entry and lifecycle integration.
- Add observer/public projections.
- Add bounded reaction delivery and security presentation.
- Add restitution/recovery interaction links.

**Acceptance**

- Both dialogue systems open/close cleanly.
- Private cases stay private.
- Reactions do not steal navigation.
- Resolved/expired effects disappear correctly.

**Recommended first release:** WP0–WP5, once all required gates pass. This provides substantial integration before adding new property offenses.

### WP6 — Explicit property law

**Changes**

- Add property policies and permissions.
- Implement supported menu transfer attribution.
- Add work-transfer context and reserved-storage policy.
- Add property loss/recovery references and a small initial offense set.

**Acceptance**

- Worker/hopper/second-player operations cannot charge the viewer.
- Unknown ownership yields no theft charge.
- Deposits and authorized deliveries stay legitimate.
- Receipt replay cannot duplicate goods, fines, or restitution.

### WP7 — Civic work and optional economy

**Changes**

- Add case-bound service contracts and useful recovery tasks.
- Add optional guardhouse meal delivery.
- Add capped economic profiles if desired.
- Verify current Quests/Conversations APIs before implementing companion representations.

**Acceptance**

- A specific minor obligation can be completed once through useful work.
- Severe unrelated cases remain intact.
- No self-created reward loops.
- All options can be disabled without data loss.

### WP8 — Release verification and operational documentation

**Changes**

- Run the final distribution-jar matrix and multiplayer soak.
- Document tested versions, required Townstead hooks, partial-support conditions, config defaults, migration, removal, and troubleshooting.
- Update API.md, CONFIG.md, changelog, and in-world verification checklists.

**Acceptance**

- Publish a precise support table and known limitations.
- List test evidence separately from any remaining manual checks.
- Do not label an unverified fallback as fully integrated.

---

## 17. Concrete file map

Paths in this section are relative to each repository's main Java package unless a full relative path is shown.

### 17.1 MCA: Crime

Base: `src/main/java/dev/otectus/mcacrime/`.

| Existing path | Intended work |
|---|---|
| `compat/McaCompat.java`, `compat/mca/McaBinding.java`, `compat/mca/McaHandles.java` | Preserve MCA package independence; route relevant capability and owned-control decisions. |
| `ai/NpcAwareness.java`, `ai/ThreatContexts.java`, `ai/ArmedResolver.java` | Effective incapacity/stage checks and optional contextual threat inputs. |
| `ai/CrimeNavigation.java`, `ai/CrimeReactionService.java`, `ai/ReactionControlPolicy.java` | Coordinated navigation and reaction ownership. |
| `ai/thief/ThiefBehaviorService.java`, `ai/thief/MugTargetSelector.java`, `ai/thief/GuardRiskEvaluator.java` | Apply effective capabilities and real responder availability. |
| `enforcement/LawHold.java`, `enforcement/ResponderAssignments.java` | Extend shared control identity and handover. |
| `enforcement/GuardEnforcement.java`, `enforcement/GuardChallengeService.java` | Duty-aware approach/challenge without violating evidence rules. |
| `enforcement/EscortService.java`, `enforcement/JailEscortNavigation.java`, `enforcement/NpcCustodyService.java` | Facility reservation, safe handover, and care-aware recovery. |
| `enforcement/GuardPopulationService.java` | Worker-preserving recruitment and roster/availability separation. |
| `captivity/CustodyService.java`, `captivity/CaptureVulnerability.java` | Capabilities, confinement care, and cleanup. |
| `job/WorldCriminalJobService.java` | Preserve profession progress and newer assignments. |
| `loot/VillagerDeathLoot.java`, `mixin/MobDeathEquipmentMixin.java` | Display equipment ownership and confirmed-death integration. |
| `detect/DamageIncidentService.java`, `detect/WitnessChecker.java` | Preserve finality; integrate perception capability checks. |
| `memory/ObservationService.java`, `memory/ReportService.java` | Effective reporting eligibility and filtered public information. |
| `incident/IncidentService.java`, `detect/CrimeCommunityResolver.java` | Typed location/property context for new property incidents. |
| `justice/JusticeService.java` | Reuse authoritative local decisions; avoid private-case leakage. |
| `jail/JailRegistry.java`, `jail/CellBuilder.java`, `jail/HoldingCellService.java`, `jail/SafeCustodyDestination.java` | Assigned facilities, exclusions, safe arrival/release. |
| `api/McaCrimeApi.java`, `api/model/` | New activity/public projections with explicit semantics. |
| `integration/CrimeIntegrationHooks.java`, `integration/CrimeIntegrationPump.java` | Target-specific Townstead delivery without breaking Reputation. |
| `state/world/CrimeWorldData.java`, `state/world/CrimeDataMigrations.java` | Durable facility/property/work records and migration. |
| `compat/mca/client/McaInteractionScreenBridge.java`, `client/render/RestraintWristLayer.java` | Explicit screen/rig adapters and safe fallback. |
| `network/ServerPacketGuard.java`, `network/RequestBudget.java` | Bound and authorize new interaction requests. |
| `McaCrimeConfig.java`, `command/CrimeCommand.java` | Integration settings, diagnostics, administrative actions. |

Suggested new package groupings:

~~~text
compat/townstead/       optional binding and immutable context
activity/               shared activity projection and operation policy
facility/               references, assignments, reservations, care
property/               permissions, verified transfers, loss associations
civic/                  public security and case-bound service contracts
compat/townstead/client/ dialogue and rig integration
~~~

Keep generic facility/property services independent of Townstead types. Townstead should be a context/provider implementation, not embedded throughout Crime's legal engine.

### 17.2 Townstead cooperation patch

Base: `src/main/java/com/aetherianartificer/townstead/`.

| Existing path | Intended work |
|---|---|
| `api/TownsteadAPI.java` and snapshot types | Add effective capability/building contracts without exposing mutable internals. |
| `tick/VillagerServerTickDispatcher.java` | Apply granular policy in both dispatcher paths. |
| `mixin/VillagerHungerMixin.java` | Verify injected tasks participate in the coordination mechanism. |
| `tick/GuardRestEnforcerTicker.java`, `fatigue/RestCoordinator.java`, `tick/FatigueVillagerTicker.java` | Recognize legal work and incapacity without permanent schedule changes. |
| `ai/work/producer/ProducerWorkTask.java` and concrete work tasks | Material-safe interruption and resumption. |
| `hunger/RefuelTask.java`, `hunger/VillagerConsumptionManager.java`, `storage/EmptyContainerDropoff.java` | Confined sourcing/consumption and reserved-storage exclusions. |
| `tick/WorkToolTicker.java` and specialized hand/tool owners | Provenance and safe ownership transfer. |
| `reaction/ReactionLockTracker.java`, `reaction/ReactionDispatcher.java` | Generation-aware interruption and nonblocking legal reactions. |
| `profession/ProfessionSlotRules.java`, `TownsteadNetwork.java` | Validated shared role-assignment service and legal state restrictions. |
| `village/TownsteadVillageSavedData.java`, `compat/mca/BuildingReportReconciler.java` | Revision/identity queries and change notifications. |
| `storage/StorageSearchContext.java`, `storage/VillageStorageIndex.java`, `hunger/NearbyItemSources.java` | Purpose-aware access hooks, exact transfer integration where supported. |
| `root/LifeStage.java`, `root/personality/PersonalityResolver.java` | Read-only effective capability/base-personality mapping. |
| `root/ability/`, `root/rig/`, relevant render adapters | Custody-aware permitted traversal and valid visual attachment points. |
| `mixin/InteractScreenMixin.java`, `client/gui/dialogue/RpgDialogueScreen.java` | Explicit action extension and clean transitions. |
| `memory/TownsteadMemoryLifecycle.java` | Release owned caches on unload/stop. |

Audit equivalent NeoForge handlers when changing shared Townstead source. Forge-specific fixes must not break the existing NeoForge build.

---

## 18. Verification plan

### 18.1 Test layers

Use three distinct layers:

1. **Unit tests:** pure activity policy, capability fallback, need normalization, identity/revision checks, candidate ranking, permission evaluation, dedupe, reservations, and migration.
2. **Integration tests with real artifacts:** optional binding, namespace variants, work interruption, material ownership, brain refresh, death ordering, menus, and saved-state reconstruction.
3. **In-world multiplayer tests:** movement, care, UI, visibility, contention, path failure, timing, and behavior with actual MCA/Townstead brains and renderers.

Retain Crime's existing relevant tests, including `McaBindingProbeTest`, `NoMcaStaticLinkTest`, `GuardPopulationTest`, `GuardResponseWindowTest`, `ReportCaseAuthorityTest`, `DeathLootTest`, `DeathConsequencesTest`, `SafeCustodyDestinationTest`, and `ThiefCustodyRecoveryTest`. Extend Townstead's `RestCoordinatorTest` where the rest policy changes.

The reviewed tree contains 143 Crime and 40 Townstead files named `*Test.java`. Those counts describe available files, not a passing test result. The Crime release commit reports its own validation; reproduce the relevant gates for the new changes.

### 18.2 Behavioral regression matrix

| ID | Scenario | Required outcome |
|---|---|---|
| A01 | Townstead absent. | Crime builds, loads, and retains its baseline gameplay. |
| A02 | Matching legacy and modern MCA/Townstead artifacts. | Required bindings and coordination hooks are detected; both dedicated server and client launch. |
| A03 | Wrong Townstead namespace, missing API member, or unsupported hook. | Clear diagnostic; no false “fully integrated” status; supported independent features continue where the installation can load. |
| A04 | Start and stop two different worlds in one process. | No activity, entity, property, or snapshot state crosses worlds. |
| B01 | Resting guard without HOME approaches a player peacefully. | Approach reaches the challenge radius; Townstead does not erase its route. |
| B02 | Guard gives a challenge while the schedule changes. | One encounter remains active and the response window is honored. |
| B03 | Guard collapses mid-escort. | One valid handover or safe recovery; no false prisoner-resistance incident. |
| B04 | Two guards select one suspect while another escort is active. | Assignment ownership remains coherent; no competing movement controllers. |
| B05 | Worker is threatened at each producer phase. | Safe interruption with no lost/duplicated inputs, outputs, station claims, or XP. |
| B06 | Work task tries to start while a villager is already captive. | Start is refused without side effects. |
| B07 | Reaction animation ends during capture or escort. | No stale WALK_TARGET restoration. |
| B08 | A player changes a villager's profession/shift during the incident. | Release respects the newer authorized change. |
| B09 | Brain refresh occurs during control. | Ownership is reasserted safely; no duplicate tasks or permanent freeze. |
| B10 | Captive/guard chunk unloads and reloads. | Authoritative custody resumes; stale transient actions do not. |
| C01 | Collapsed villager near visible theft. | No new actor identification, report, guard response, or armed support from that villager. |
| C02 | Sleeping witness wakes after the incident. | No retroactive knowledge is invented. |
| C03 | Hunger, thirst, or fatigue is disabled individually. | Disabled values do not affect Crime decisions. |
| C04 | Custom stage is nonmobile, not talkable, or SENIOR. | Each operation uses effective capability, not one raw age-string rule. |
| C05 | Custom personality ID has a valid MCA base. | Base fallback applies unless an exact Crime profile overrides it. |
| C06 | Calendar profile/time changes repeatedly. | No sentence, purse, bounty, or cooldown exploit. |
| D01 | Farmer dies with a displayed copy of one inventory hoe. | Exactly the physically owned quantity is handled. |
| D02 | Villager owns two genuinely separate identical tools. | Both remain accounted for; equality-based dedupe does not delete one. |
| D03 | Work display replaces a valuable hand item before capture/unload/death. | Original hand item is restored or recovered exactly once. |
| D04 | Townstead cancels a lethal-looking death. | No murder, kill bounty, death-only release, or stolen-goods death recovery. |
| D05 | Real death with life-stage loot, work display, trade stock, and equipment. | Each category follows its actual ownership/drop rule and respects cancellation policies. |
| D06 | Alternate rig, scale, flying or phasing stage. | Valid geometry and permitted escape behavior; safe visual fallback. |
| E01 | Valid local facility competes with a distant manual anchor. | Automatic selection obeys local policy; explicit administration remains intentional. |
| E02 | Two simultaneous arrests reserve the final cell slot. | One reservation succeeds; the other uses a valid alternative/fallback. |
| E03 | Facility is edited, deleted, flooded, or unloaded mid-escort. | Arrival revalidation prevents unsafe teleport or case loss. |
| E04 | Temporary cell candidate overlaps farm, dock, pen, or kitchen. | Candidate is rejected without changing blocks. |
| E05 | Prisoner needs food, drink, and sleep. | Actual authorized supplies/rest work inside the region; ordinary work remains suspended. |
| E06 | Evidence chest contains food beside a hungry worker/prisoner. | Ordinary sourcing cannot consume evidence. |
| E07 | Critical lawful prisoner with no supplies or safe care room. | Explicit retained custody recovery, never silent starvation or false sentence completion. |
| E08 | Player changes a cell block before dismantling. | Crime preserves the player's later change. |
| F01 | Player opens a chest while a cook or hopper removes items. | No theft charge against the viewer. |
| F02 | Two players transfer items concurrently. | Each committed transfer is attributed only to its actor. |
| F03 | Shift-click, hotbar swap, cursor move, throw, drag, double-click. | Supported actions have correct quantities and one receipt. |
| F04 | Deposit, donation, permitted work, direct care, accepted delivery. | Legitimate transfers receive no theft charge. |
| F05 | Unknown owner, disputed overlap, unsupported custom menu. | No speculative theft charge; diagnostic identifies the gap. |
| F06 | Crash/reconnect during transfer or repayment. | Reconciliation retains uncertainty and prevents duplicate debit/reward. |
| F07 | Property changes owner after theft. | Historical victim and recovery beneficiary remain correct. |
| F08 | Crop/slaughter task performs its authorized work. | No player sabotage or livestock-harm incident. |
| G01 | Private unwitnessed crime. | Townstead public dialogue, patrol priorities, security summaries, and service policies do not reveal it. |
| G02 | Hearing-only alarm. | Alarm may be presented; suspect identity is absent. |
| G03 | Same report or resolution delivered repeatedly. | One durable social/material effect. |
| G04 | Reputation installed, unavailable, then restored. | Existing authority and dedupe behavior remain correct; Townstead adds no duplicate penalty. |
| G05 | Minor property restitution alongside a serious assault. | Only the agreed property obligation resolves. |
| G06 | Player creates a loss/captive and repeatedly “helps.” | No repeatable karma, reputation, XP, or currency loop. |
| H01 | Crime opened from MCA and Townstead dialogue at multiple GUI scales. | Correct target, usable controls, clean close/return, no stale freeze. |
| H02 | Malformed, stale, remote-target, or replayed packet. | Server rejects it without state mutation. |
| H03 | Townstead removed while Crime has a prisoner and pending recovery. | Crime data loads and uses a safe fallback; no foreign-class deserialization. |
| H04 | Integration toggled off or data reloads mid-action. | Safe handover/defer behavior; no lost custody or material state. |

### 18.3 Representative playthroughs

Run these as complete stories, not isolated method calls:

**The interrupted cook**

A cook gathers ingredients, begins production, is threatened, complies, is rescued, receives food if needed, and resumes her current shift. Repeat capture at each producer phase and during a reload. Count all inputs/outputs and compare profession progress before and after.

**The tired night guard**

A guard reaches her REST period without a bed. A public case causes a peaceful approach and challenge. She escorts the player, becomes exhausted, and hands over. The prisoner reaches a supplied valid cell and later receives a clean release. The original weekly schedule remains.

**The missing supplies**

A player and worker access a registered community chest while a hopper runs. Only the player's unauthorized committed withdrawal creates a property incident. A witness reports it; the player returns the actual goods; only the matching property obligation resolves.

**The rescued resident**

A resident is held unlawfully and misses work. A different player rescues her, takes her to safety, and helps her recover. Her actual family, profession, needs, age, inventory, and memories remain. Repeating the same recovery cannot award another reward.

**The immortal victim**

A villager survives an otherwise lethal attack through Townstead. The existing assault remains attributable where appropriate, but no death-only legal/economic effects occur. A separate real-death fixture verifies final loot and recovery.

### 18.4 Performance and contention

Compare matched worlds:

- Crime + MCA baseline.
- Townstead + MCA baseline.
- Both mods with integration enabled.
- Integration enabled with ordinary daily life, several concurrent incidents, and a larger loaded village.

Use an initial larger fixture of roughly **100–200 loaded villagers**, multiple workplaces, several guards, and up to five simultaneous active incidents. Record median/p95 server tick cost, path computations, snapshot refreshes, queue depth, cache sizes, and stop/reload cleanup.

The key gate is bounded behavior: no work proportional to all loaded villagers on every incident tick, no per-tick whole-village inventory scan, no unbounded retry/claim queue, and no entity references accumulating after unload. Investigate a material sustained cost increase relative to the combined baseline; report measured numbers rather than claiming an arbitrary performance guarantee.

### 18.5 Build and artifact gates

For Crime, preserve the existing gates:

~~~bash
./gradlew test build checkJarContents
~~~

Add the integration fixture gate once implemented:

~~~bash
./gradlew test build checkJarContents -PrequireTownsteadIntegration=true
~~~

The second property is proposed in this plan and will not do anything until the build implements it.

For Townstead, inspect `./gradlew projects` and relevant task lists, then run the Forge legacy and modern test/distribution tasks. Expected project selectors from the current Stonecutter settings are `:1.20.1-forge-legacy` and `:1.20.1-forge`; confirm generated task names and provide the required local MCA compile inputs.

Test the final remapped distribution jars in a clean dedicated-server fixture. Development classpaths and unit tests cannot establish mixin, namespace, client-classloading, or animation compatibility.

Keep Townstead build variants in isolated working copies/worktrees when the preprocessing flow rewrites active sources. Do not race variant transformations in one working tree.

---

## 19. Release gates and outstanding verification

### 19.1 Must be complete for the first integrated release

- [ ] Exact supported artifact matrix documented.
- [ ] Optional adapter always included in the intended Crime artifact.
- [ ] No shaded Townstead/MCA/companion classes.
- [ ] Effective collapse/stage awareness applied across Crime.
- [ ] Guard approach, challenge, escort, and handover coexist with rest.
- [ ] Work interruption preserves materials, claims, and progression.
- [ ] Display equipment is accounted for without duplication.
- [ ] Auto-recruitment and criminal presentation preserve Townstead assignments.
- [ ] Death cancellation produces no death-only Crime consequences.
- [ ] Temporary cells respect Townstead work areas.
- [ ] Assigned facilities and care have safe failure/recovery paths.
- [ ] Townstead dialogue opens and closes Crime interactions cleanly.
- [ ] Public consequences respect actual knowledge.
- [ ] Save/reload, removal, and reinstallation retain legal and material state.
- [ ] Required unit, real-artifact, and multiplayer evidence recorded.

Property law, civic work, and economic profiles should have independent completion checklists and release notes. Their absence must not be disguised by a feature toggle that does nothing.

### 19.2 Verify these before coding beyond the foundation

| Question | Why it matters | Resolution method |
|---|---|---|
| Which Townstead legacy/modern jars are actually available for the selected MCA releases? | Source version alone does not identify a loadable installation. | Inspect artifact manifests, hashes, dependencies, and launch each pair. |
| Can the required Townstead cooperation facade be included upstream or in a companion patch? | Read-only snapshots cannot safely arbitrate all work/material operations. | Prepare a bounded Townstead patch and record its contract version. |
| How does each work task handle interruption after consuming inputs? | Generic cleanup can lose transaction ownership. | Review concrete implementations and exercise each phase. |
| How does display equipment survive save/load in the tested MCA/Townstead combination? | Provenance and stashed hands can cross serialization boundaries. | Inventory fixtures with exact item accounting and reload. |
| Which building geometry/identity API is present on each MCA variant? | Modern floor systems and synthetic buildings need accurate matching. | Probe capabilities and test nested/open-air facilities. |
| Which custom rigs and traversal abilities should the initial release support? | Complete visual/escape support is model- and ability-specific. | Declare a tested set and functional fallback. |
| Which menu types can provide committed transfer attribution? | Global inventory deltas cannot prove player theft. | Start with explicit vanilla adapters and expand only with tests. |
| What are current Quests/Conversations extension contracts? | These companion repositories were outside this review. | Inspect their latest source before implementing optional adapters. |

### 19.3 Expected delivery from the implementing agent

Deliver:

1. Crime changes grouped by work package.
2. A separate Townstead cooperation patch, with exact required version/commit.
3. Build and integration fixtures, including dependencies/artifact hashes.
4. Automated test results and completed in-world checklists.
5. A precise supported/partial/unsupported matrix.
6. Configuration and datapack examples that are exercised, not merely declared.
7. Migration/removal documentation and unresolved limitations.

Do not claim upstream changes are available before they actually ship. If a Crime-only implementation lacks required Townstead hooks, present the supported subset honestly and include the cooperation patch needed for full behavior.

---

## 20. Coding-agent execution brief

Use the reviewed source map as the starting point, then recheck both current branches before editing. Preserve unrelated user changes and existing game behavior.

Implement WP0–WP5 as the first coherent integration milestone. Establish optional binding and effective capabilities, then solve ownership of navigation, work, equipment, and custody. Build the facility and social features on those contracts. Keep Townstead state owned by Townstead and legal/economic state owned by Crime.

Require evidence for the difficult cases: a resting guard approaching without ATTACK_TARGET, a collapsed witness, an interrupted producer with staged inputs, a displayed tool on death, a canceled death, a changing profession/schedule, simultaneous cell reservations, and clean removal/reinstallation.

After that milestone is stable, implement the separately configured property and civic-work packages. Use verified actions and durable identities for every legal or material consequence. Reuse the existing case, report, settlement, recovery, and Reputation paths.

The intended result is a village whose daily life and legal life agree: residents can work, rest, defend themselves, report crimes, receive care, make amends, and return to their lives without the two mods fighting over the same entity.

---

## 21. Source register

All links below refer to the exact reviewed source snapshots. Linked implementation is the basis for existing-behavior claims; proposed design remains explicitly identified throughout the document.

### Townstead

| Reference | Primary source and relevance |
|---|---|
| T00 | [Reviewed repository tree][T00]. |
| T01 | [Forge build][T01]; [Stonecutter targets](https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/settings.gradle.kts); [version properties](https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/gradle.properties); [Forge metadata](https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/resources/META-INF/mods.toml). |
| T02 | [TownsteadAPI][T02] and the adjacent snapshot records: existing read-only integration surface and its limits. |
| T03 | [FatigueVillagerTicker][T03]; [RestCoordinator](https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/fatigue/RestCoordinator.java): independent collapse, sleep, and schedule override behavior. |
| T04 | [GuardRestEnforcerTicker][T04]: REST/no-HOME/no-ATTACK_TARGET movement clearing. |
| T05 | [ProducerWorkTask][T05]: staged inputs, output state, task lifecycle, and claims. |
| T06 | [VillagerHungerMixin][T06]; [VillagerServerTickDispatcher](https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/tick/VillagerServerTickDispatcher.java); [RefuelTask](https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/hunger/RefuelTask.java). |
| T07 | [ReactionLockTracker][T07]: freeze and saved WALK_TARGET restoration. |
| T08 | [WorkToolTicker][T08]: copied display tools and stashed hand state. |
| T09 | [TownsteadVillageSavedData][T09]: dimension/village keys, building overlays, revisions. |
| T10 | [LifeStage][T10]; [CanonicalStage](https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/root/CanonicalStage.java); [PersonalityDef](https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/root/personality/PersonalityDef.java); [PersonalityResolver](https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/root/personality/PersonalityResolver.java). |
| T11 | [Townstead Forge damage/death callbacks][T11]; [Immortality](https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/root/Immortality.java); [life-stage death loot](https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/root/loot/DeathLoot.java). |
| T12 | [InteractScreenMixin][T12]: Talk redirection, needs display, custom personality/stage presentation, transition handling; [RpgDialogueScreen](https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/client/gui/dialogue/RpgDialogueScreen.java): camera/HUD ownership, asynchronous reopening, and close messages. |
| T13 | [VillageStorageIndex][T13]; [StorageSearchContext](https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/storage/StorageSearchContext.java); [TownsteadConfig](https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/TownsteadConfig.java): sourcing/protection semantics. |
| T14 | [VillageSpiritAggregator][T14]; [SpiritRegistry](https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/spirit/SpiritRegistry.java). |
| T15 | [HungerData][T15]; [ThirstData](https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/thirst/ThirstData.java); [FatigueData](https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/fatigue/FatigueData.java): different scales and thresholds. |
| T16 | [TownsteadNetwork profession assignment][T16]; [ProfessionSlotRules](https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/profession/ProfessionSlotRules.java); [VillageResidentRoster](https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/village/VillageResidentRoster.java). |
| T17 | [BuildingReportReconciler][T17]; [building-type resources](https://github.com/AetherianArtificer/Townstead/tree/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/resources/data/mca/building_types). |
| T18 | [TownsteadMemoryLifecycle][T18]: cache pruning and server cleanup. |

### MCA: Crime

| Reference | Primary source and relevance |
|---|---|
| C00 | [Reviewed repository tree][C00]. |
| C01 | [Crime build][C01]; [version/probe properties](https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/gradle.properties); [MCA binding](https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/compat/mca/McaBinding.java). |
| C02 | [Actual mixin configuration][C02], compared with [README](https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/README.md). |
| C03 | [CrimeDataMigrations][C03]: current schema 10 and migration policy. |
| C04 | [McaCrimeApi][C04]: custody, sentence, player/case views, and fallback standing accessor. |
| C05 | [LawHold][C05]; [ReactionControlPolicy](https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/ai/ReactionControlPolicy.java); [ResponderAssignments](https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/enforcement/ResponderAssignments.java). |
| C06 | [IncidentService][C06]: common commit path and present community/context construction. |
| C07 | [CrimeCommunityKey][C07]; [CrimeCommunityResolver](https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/detect/CrimeCommunityResolver.java). |
| C08 | [CrimeIntegrationHooks][C08]; [CrimeIntegrationPump](https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/integration/CrimeIntegrationPump.java): current Reputation-specific routing and fallback. |
| C09 | [NpcAwareness][C09]: alive/vanilla-sleeping predicate. |
| C10 | [GuardEnforcement][C10]; [McaCompat navigation/control](https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/compat/McaCompat.java); [CrimeNavigation](https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/ai/CrimeNavigation.java). |
| C11 | [VillagerDeathLoot][C11]; [MobDeathEquipmentMixin](https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/mixin/MobDeathEquipmentMixin.java): equipment identity and drop handling. |
| C12 | [GuardPopulationService][C12]; [GuardPopulation policy](https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/enforcement/GuardPopulation.java). |
| C13 | [WorldCriminalJobService][C13]: stored criminal role and visible profession transitions. |
| C14 | [McaInteractionScreenBridge][C14]: screen matching, target discovery, button placement. |
| C15 | [JailRegistry][C15]; [CellBuilder](https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/jail/CellBuilder.java); [HoldingCellService](https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/jail/HoldingCellService.java); [NpcCustodyService](https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/enforcement/NpcCustodyService.java). |
| C16 | [VillagerPurse][C16] and [PurseAccess](https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/economy/account/PurseAccess.java). |
| C17 | [ObservationService][C17]; [ThreatContexts](https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/ai/ThreatContexts.java): observation, identification, and support inputs. |
| C18 | [DamageIncidentService][C18]; [CrimeDetectionHandlers](https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/detect/CrimeDetectionHandlers.java). |
| C19 | [RestraintWristLayer][C19]: HumanoidModel arm assumptions. |
| C20 | [ReportService][C20]; [JusticeService](https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/justice/JusticeService.java): report validation and local legal decisions. |

[T00]: https://github.com/AetherianArtificer/Townstead/tree/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e
[T01]: https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/build.forge.gradle.kts
[T02]: https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/api/TownsteadAPI.java
[T03]: https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/tick/FatigueVillagerTicker.java
[T04]: https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/tick/GuardRestEnforcerTicker.java
[T05]: https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/ai/work/producer/ProducerWorkTask.java
[T06]: https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/mixin/VillagerHungerMixin.java
[T07]: https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/reaction/ReactionLockTracker.java
[T08]: https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/tick/WorkToolTicker.java
[T09]: https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/village/TownsteadVillageSavedData.java
[T10]: https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/root/LifeStage.java
[T11]: https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/Townstead.java#L935-L955
[T12]: https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/mixin/InteractScreenMixin.java
[T13]: https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/storage/VillageStorageIndex.java
[T14]: https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/spirit/VillageSpiritAggregator.java
[T15]: https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/hunger/HungerData.java
[T16]: https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/TownsteadNetwork.java#L996-L1155
[T17]: https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/compat/mca/BuildingReportReconciler.java
[T18]: https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/memory/TownsteadMemoryLifecycle.java
[C00]: https://github.com/otectus/MCACrime/tree/fdb602428c101c2364a0e7bb0cc5cba57aeedce1
[C01]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/build.gradle
[C02]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/resources/mcacrime.mixins.json
[C03]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/state/world/CrimeDataMigrations.java
[C04]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/api/McaCrimeApi.java
[C05]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/enforcement/LawHold.java
[C06]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/incident/IncidentService.java
[C07]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/api/model/CrimeCommunityKey.java
[C08]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/integration/CrimeIntegrationHooks.java
[C09]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/ai/NpcAwareness.java
[C10]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/enforcement/GuardEnforcement.java
[C11]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/loot/VillagerDeathLoot.java
[C12]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/enforcement/GuardPopulationService.java
[C13]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/job/WorldCriminalJobService.java
[C14]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/compat/mca/client/McaInteractionScreenBridge.java
[C15]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/jail/JailRegistry.java
[C16]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/economy/account/VillagerPurse.java
[C17]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/memory/ObservationService.java
[C18]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/detect/DamageIncidentService.java
[C19]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/client/render/RestraintWristLayer.java
[C20]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/memory/ReportService.java

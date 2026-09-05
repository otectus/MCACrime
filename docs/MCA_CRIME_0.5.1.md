# MCA: Crime 0.5.1 — Complete Development Specification

## Product intent and release contract

**Target release:** `0.5.1`  
**Target platform:** Minecraft `1.20.1`, Forge `47.x`, Java `17`. The current project is version `0.5.0`; it targets Minecraft `1.20.1`, Forge `47.4.10`/Forge `47+`, and uses MCA Reborn `7.6.20+1.20.1` as its runtime development reference while deliberately supporting the broader MCA `7.6.x–7.7.x` line through reflection/probing rather than a hard compile-time MCA dependency. fileciteturn6file0L2-L2

**Release purpose:** 0.5.1 should turn MCA: Crime’s existing player-centric crime framework into a substantially more symmetric social-crime framework: victims visibly submit when restrained or held at weapon-point, criminal MCA villagers can commit crimes themselves, criminal occupations participate in the economy, lawful bounty killing is consistent with guard hostility, and the existing MCA interaction-screen integration becomes a first-class entry point rather than an incidental button.

The release should be implemented as an extension of the systems already present in 0.5.0 rather than as a parallel subsystem. This is particularly important because 0.5.0 already contains several architectural foundations that directly anticipate this work: a `CrimeActor` abstraction intended to support NPC-on-NPC/NPC-originated crime, a victim reaction state machine with `THREATENED`, `COMPLYING`, `RESISTING`, `FLEEING`, `SEEKING_HELP`, `REPORTING`, `HIDING`, `RECOVERING`, and `CAPTIVE` states, observations/reports, a case ledger, guard response, restraint handling, and an MCA-screen Crime button. fileciteturn4file0L2-L2

The implementation therefore has five overriding design rules:

| Rule | 0.5.1 requirement |
|---|---|
| **Server authority** | Crime outcomes, mugging theft, legal-target decisions, bounty payouts, guard arrests, criminal jobs, fence prices, restraint state, and inventory mutation MUST be decided server-side. Clients display state only. |
| **Reuse existing abstractions** | `CrimeActor`, `CrimeGate`, `LegalTarget`, reaction states, guard enforcement, currency abstractions, and restraint systems MUST be extended instead of duplicated. |
| **Optional integrations stay optional** | MCA Reborn, MCA: Quests, and Locks Reforged integration MUST NOT produce class-loading failures when an optional integration is absent. Existing reflection/runtime-probe strategy should be preserved. |
| **Data-driven policy** | Weapons, contraband, fence inventories, armed-villager exemptions, prices, thief behavior, bounty rewards, and stealable slots SHOULD be configurable or tag-driven wherever practical. |
| **No client/server disagreement** | Any UI gate such as “Crime requires a weapon” is convenience UX only; the same condition MUST be rechecked on the server when the packet/action is received. |

The current 0.5.0 weapon classifier already recognizes conventional melee and ranged weapons, supports firearm heuristics, configurable allow/deny lists, MCA: Crime weapon tags, and attack-damage fallback classification. Right-clicking an MCA villager with an accepted weapon can already open the crime interaction path. fileciteturn4file0L2-L2 That classifier should become the **single canonical definition of “weapon drawn”** throughout 0.5.1. Do not create a second “is weapon” utility for mug immunity, button activation, or armed-villager exemptions.

### Functional release definition

0.5.1 is complete only when all of the following work together:

| Feature | Release acceptance condition |
|---|---|
| New restraint artwork | TheWiggleDuck’s open handcuffs, closed handcuffs, and rope artwork present under `src` is actually wired to the relevant items/models/UI/rendering without changing stable registry IDs unnecessarily. |
| Restrained villager pose | MCA villagers restrained with cuffs or rope visibly hold both arms/hands behind the torso to all tracking clients. |
| Weapon-point compliance | Unarmed civilian MCA villagers stop escaping while a valid coercive crime is actively being performed and move more slowly where the reaction requires movement. Armed villagers can resist/fight. |
| MCA menu button | A dedicated bottom-positioned Crime button exists in the MCA interaction UI; it is disabled without a weapon and explains why through a tooltip. |
| Thief occupation | Thieves exist in settlements and, rarely, outside them; they can autonomously select and mug players, be interrupted, steal safely, flee, be killed for recovery of stolen property, and be arrested by guards. |
| Fence occupation | Fences trade contraband, support optional Locks Reforged goods, and use criminality/Heat-aware pricing. |
| Criminal social alignment | Criminal villagers react economically/socially to low karma more favorably, but regard high Heat/wanted status as business risk. |
| Lawful outlaw combat | A player does not gain crime liability for force the law itself recognizes as lawful against an outlaw. |
| Bounty payment | A qualified player kill of an eligible outlaw can result in a configurable, exploit-resistant bounty reward. |
| MCA: Quests | MCA: Quests can present bounty-related objectives/contracts without making it a required dependency and without paying the same bounty twice. |
| Regression safety | Existing crime, witness, arrest, jail, player restraint, economy, MCA 7.6/7.7 compatibility, and dedicated-server behavior remain functional. |

**Scope clarification for “criminal jobs.”** For 0.5.1, only **Thief** and **Fence** are to be exposed as criminal occupations. The data model should nevertheless use an enum/registry-friendly structure so later releases can add smuggler, hitman, poacher, outlaw, loan shark, corrupt guard, etc. without another persistence redesign.

**Creative direction.** The system should feel like a social simulation rather than random hostile-mob AI. A thief assesses an opportunity, approaches, threatens, waits for compliance, steals, then escapes. A fence is friendly to the underworld but reacts to police attention as economic risk. A guard recognizes an actual mugging as an arrest-worthy offense. An outlaw’s legal status is the same whether evaluated by a guard, the crime detector, or the bounty system. This coherence is more important than maximizing the number of individual mechanics.

The existing project already follows a similar philosophy: 0.4.0 introduced `CrimeActor` explicitly as groundwork for non-player criminal actors rather than assuming a `ServerPlayer` is always the offender. fileciteturn4file0L2-L2 0.5.1 should finally capitalize on that abstraction.

## Baseline architecture and constraints

### Existing code that should be treated as the integration surface

The repository already contains the major conceptual layers required for this update, including crime action handlers, actor abstractions, reaction AI, currency/economic services, guard enforcement, legal-target logic, restraint services, MCA client bridging, and restraint renderers/models. The current source tree includes classes such as `CrimeActor`, `PlayerActor`, `CrimeReactionService`, `ActiveCrimeReactionController`, `VictimReactionState`, `Currency`, `EmeraldCurrency`, `EconomicTransactionService`, `VillagerPurse`, `GuardEnforcement`, `LegalTarget`, `RestraintHandlers`, `EscortService`, `CuffsLayer`, `EscortRopeRenderer`, `PlayerModelRestraintMixin`, and `McaInteractionScreenBridge`. fileciteturn2file0L2-L2

That is a strong indication that 0.5.1 should be an **incremental architecture extension**, approximately:

```text
CrimeActor
├── PlayerActor                 existing
└── VillagerCrimeActor          NEW

Crime legality
├── CrimeGate                   existing
├── LegalTarget                 existing
└── OutlawResolver              NEW facade / consolidation layer

Reaction
├── CrimeReactionService        existing
├── ActiveCrimeReactionController
└── ThreatComplianceController  NEW or extracted helper

Restraints
├── RestraintHandlers
├── EscortService
├── player rendering            existing
└── MCA villager rendering      NEW

Criminal NPCs
├── CriminalJobService          NEW
├── ThiefBehaviorService        NEW
├── NpcMuggingService           NEW
├── FenceTradeService           NEW
└── StolenGoodsLedger           NEW

Bounties
├── LegalTarget
├── Case / wanted data
├── BountyService               NEW / extended
├── BountyClaimLedger           NEW
└── McaQuestsCompat             NEW optional adapter
```

### `CrimeActor` must become genuinely actor-neutral

The most important architectural change is a `VillagerCrimeActor` implementation.

Recommended interface semantics:

```java
public final class VillagerCrimeActor implements CrimeActor {
    private final UUID actorUuid;

    @Override
    public UUID uuid();

    @Override
    public Entity resolve(ServerLevel level);

    @Override
    public Vec3 position(ServerLevel level);

    @Override
    public Component displayName(ServerLevel level);

    @Override
    public boolean isAlive(ServerLevel level);

    // Actor-specific inventory/economy functionality
    // belongs behind capability interfaces, not instanceof spread
}
```

Where actor abilities differ, prefer capability-style interfaces:

```java
public interface InventoryCrimeActor extends CrimeActor {
    ItemStack extract(...);
    boolean give(...);
}

public interface EconomicCrimeActor extends CrimeActor {
    long currencyBalance(...);
    long debitCurrency(...);
    long creditCurrency(...);
}
```

Do not expand every existing `CrimeActor` implementation with methods that make no semantic sense for that actor.

The immediate payoff is that the autonomous thief’s mugging can flow through the same broad event sequence as a player mugging:

```text
actor identifies victim
        ↓
crime attempt starts
        ↓
witness context created
        ↓
victim reaction changes
        ↓
crime progresses
        ↓
transaction commits
        ↓
crime/case/report generated
        ↓
guards may respond
```

That makes future NPC crime substantially easier and avoids two independent implementations of “mugging.”

### Separate intent, attempt, and completed crime

Thief behavior makes the distinction essential.

Use three event levels:

```java
CrimeIntentEvent
CrimeAttemptEvent
CrimeCommittedEvent
```

An NPC deciding that somebody looks vulnerable is not itself a crime. Beginning a weapon threat can be an attempted mugging/assault for guard purposes. Property actually changing hands is the completed theft/mugging transaction.

Recommended semantics:

| Phase | Witness can react? | Guard can intervene? | Case generated? | Property transferred? |
|---|---:|---:|---:|---:|
| Scouting | No | No | No | No |
| Approaching | Normally no | No | No | No |
| Threatening | Yes | Yes | Attempt/active incident | No |
| Mugging progress | Yes | Yes | Active incident | No |
| Transaction commit | Yes | Yes | Completed offense | Yes |
| Escape | Existing case remains | Yes | Already generated | Already done |

This distinction solves the requirement that a guard can **disrupt the mugging before the player loses anything**.

### Add a shared “armed” determination

The requirement exempts guards, archers, and “other armed villagers” from forced civilian compliance. This must not become:

```java
if (profession == GUARD || profession == ARCHER)
```

alone.

Recommended resolver:

```java
public ArmedStatus classifyLivingEntity(LivingEntity entity)
```

where:

```java
record ArmedStatus(
    boolean armed,
    WeaponSource source,
    InteractionHand hand,
    @Nullable ItemStack weapon
) {}
```

Classification order:

1. explicit datapack/config exemption;
2. MCA guard/archer role;
3. currently equipped qualifying main-hand weapon;
4. optionally qualifying offhand weapon according to existing offhand settings;
5. entity/type tags designating combatants;
6. otherwise unarmed.

Suggested tags:

```text
mcacrime:armed_villager_roles
mcacrime:always_resists_weapon_threats
mcacrime:never_resists_weapon_threats
```

The current weapon-classification rules should then provide item-level qualification. This ensures modded guards with modded guns can resist even when MCA: Crime has never heard of their profession.

### Establish transactional operations before adding theft

Mugging introduces the possibility of duplication if an entity dies, unloads, disconnects, or the server crashes between inventory removal and thief-stash persistence.

Treat the theft as an atomic conceptual transaction:

```text
PREPARE
  identify eligible property
  serialize transaction plan
        ↓
COMMIT
  debit victim
  credit thief stolen-goods ledger
        ↓
FINALIZE
  mark mug completed
  emit crime event
  begin escape
```

A transaction ID such as a UUID must identify each mug:

```java
record MugTransaction(
    UUID transactionId,
    UUID thiefUuid,
    UUID victimUuid,
    long createdGameTime,
    TheftPayload payload,
    TransactionState state
) {}
```

The server must never “roll random item once to remove and a second time to remember what was removed.” The exact removed `ItemStack` is the exact stack persisted as stolen property.

### Avoid hard optional-mod references

The present project intentionally uses a reflection strategy around MCA compatibility, including probes against MCA `7.6.20`, `7.7.0-beta.2`, and `7.7.1-alpha.2`. fileciteturn6file0L2-L2 Maintain the same philosophy.

Optional compatibility classes may be registered through:

```java
if (ModList.get().isLoaded("...")) {
    CompatBootstrap.enable(...);
}
```

but **do not put optional-mod types in method signatures, fields, annotations, static initializers, or common event subscribers that JVM class loading can touch before the mod-presence check**.

Prefer:

```text
compat/
  mca/
  mcaquests/
  locksreforged/
```

with a tiny common-facing interface per integration.

## Restraints, threat control, and MCA interaction UI

### Wire TheWiggleDuck’s restraint assets

The existing repository already contains restraint-related models/textures for cuffs, locked/closed cuffs, rope, and restraint rendering. fileciteturn2file0L2-L2 0.5.1 should treat the newly supplied TheWiggleDuck artwork as an **asset wiring and attribution task**, not an opportunity to rename established item IDs.

Requirements:

1. Identify the three provided art assets in `src/main/resources`.
2. Associate them with the correct existing item/model identifiers for:
   - handcuffs/open cuffs;
   - handcuffs closed/locked;
   - rope.
3. Preserve old registry names unless an existing name is clearly erroneous.
4. If asset filenames change, use model JSON indirection rather than registry renaming.
5. Verify inventory, GUI, held-item, dropped-item, JEI-equivalent display, and any Crime-menu presentation.
6. Add TheWiggleDuck to the applicable project credits/attribution documentation and 0.5.1 changelog.

Do not conflate **item icon** and **restraint-on-body texture**. The inventory image may be new while the body-layer representation needs separate mapping.

### Put restrained MCA villagers’ arms behind their back

0.5.0 already implements the visual concept for a **restrained player**: the player’s arms can be rendered behind their back with cuffs at the wrists, plus an escort rope to the guard; the rope intentionally uses custom visual state because vanilla leash APIs do not naturally model this player-target use case. fileciteturn5file0L2-L2

0.5.1 should generalize that visual state to MCA villagers.

The desired silhouette is:

```text
front / normal                     restrained / rear view

   O                                   O
  /|\                                  |
   |                                  /|\
  / \                                /   \
                                 hands meet behind lower torso
```

The animation should be a **pose override at final model setup time**, not a replacement animation that causes locomotion/body rotation to stop.

Recommended data contract:

```java
public enum RestraintVisualType {
    NONE,
    HANDCUFFS,
    ROPE
}

public record RestraintVisualState(
    boolean restrained,
    RestraintVisualType type,
    @Nullable UUID escortingGuard
) {}
```

The server owns this state. Tracking clients receive it when:

- the restraint starts;
- restraint type changes;
- an escort starts/stops;
- the restraint ends;
- a client starts tracking an already-restrained villager.

**Pose behavior.** For both arms, suppress ordinary walk/swing animation and rotate the shoulder/arm model parts backward and inward until the hands meet approximately around the back waist. Exact radians will require visual tuning against MCA skins/models, so constants should be isolated rather than buried in a mixin.

For example:

```java
final class RestrainedPoseConstants {
    static final float ARM_X_ROT = ...;
    static final float LEFT_ARM_Y_ROT = ...;
    static final float RIGHT_ARM_Y_ROT = ...;
    static final float ARM_Z_ROT = ...;
}
```

The renderer must then position a closed-cuffs or rope layer at the wrists. Cuffs should not merely remain in ordinary hand-held rendering.

**Required visual cases:**

| Entity state | Arm pose | Wrist visual | Escort rope |
|---|---|---|---|
| not restrained | MCA normal | none | none |
| cuffed | behind back | closed cuffs | optional |
| rope-bound | behind back | rope binding | optional/custom |
| restrained + escorted | behind back | appropriate binding | guard-to-prisoner rope if enabled |
| dead | ordinary death transform takes precedence | optional until entity gone | none |
| swimming/sleeping/riding | restraint pose remains semantically authoritative, but avoid model corruption | yes | context-dependent |

If MCA model internals differ among supported MCA versions, centralize those model-part lookups in the existing MCA compatibility layer rather than sprinkling reflection throughout render code.

### Weapon-point compliance and speed reduction

The current reaction model already includes `THREATENED`, `COMPLYING`, `RESISTING`, and `FLEEING`; `COMPLYING` is intended for a victim satisfying the offender’s demands rather than continuing ordinary movement. fileciteturn4file0L2-L2 0.5.1 should make those states produce much stronger movement semantics.

The central rule:

> During an active coercive crime, an unarmed civilian MCA villager being held at weapon-point MUST stop fleeing and remain effectively stationary for the duration of the actual crime interaction. An armed/authorized combatant MAY reject compliance and enter resistance/combat.

State transitions:

```text
normal
  |
  | qualifying threat + valid crime action
  v
THREATENED
  |
  +---- armed / rolls resistance ----> RESISTING ---> combat/help
  |
  +---- civilian compliance ---------> COMPLYING
                                          |
                                          | crime begins
                                          v
                                      HELD_IN_PLACE
                                          |
               +--------------------------+----------------------+
               |                          |                      |
          crime completes          threat cancelled        guard intervention
               |                          |                      |
               v                          v                      v
          RECOVERING                 FLEE/REPORT          SEEK_HELP/RECOVER
```

`HELD_IN_PLACE` does not necessarily need to become a new persisted public enum if `COMPLYING` can cleanly encode it. Prefer not to add a redundant state unless controller clarity substantially improves.

**Freeze is contextual, not permanent.** While the criminal transaction progress is active:

```java
navigation.stop();
setDeltaMovement(horizontal suppressed as appropriate);
```

and the crime controller should continuously cancel ordinary navigation goals that would restart fleeing.

Do **not** globally set the villager’s movement-speed attribute to zero in a way that can survive interruption or removal incorrectly.

For non-frozen frightened movement—such as moving to a demanded location or fleeing once released—apply a configurable slowdown such as:

```text
civilianCrimeReactionSpeedMultiplier = 0.65
```

Recommended default range validation: `0.10–1.00`.

A transient attribute modifier is appropriate for long-lived speed adjustment, but make it UUID-stable and always removed during state exit, death, unload cleanup, or service reconciliation.

**Armed exceptions.** Guards, archers, and other qualifying combatants should evaluate resistance instead:

```java
if (armedResolver.classify(victim).armed()) {
    return ReactionDecision.RESIST;
}
return ReactionDecision.COMPLY;
```

For guards, resistance should route into the existing MCA guard-brain integration. MCA: Crime already had to populate MCA’s relevant guard-enemy memory in addition to vanilla attack targeting because simply calling `Mob.setTarget` is insufficient to drive MCA guard behavior reliably. fileciteturn5file0L2-L2 Reuse that pathway rather than setting a generic target and assuming the guard will fight.

**Do not freeze on mere proximity.** A player walking around with a sword must not immobilize villagers. Freeze requires a live server-side `CrimeSession` or equivalent coercive interaction directed at that exact victim.

Recommended invariant:

```java
boolean shouldComplyFrozen(victim) {
    return sessionManager.hasActiveCoerciveSession(victim)
        && session.threatActorIsValid()
        && session.threatWeaponStillQualifies()
        && !armedResolver.classify(victim).armed()
        && victimReaction == COMPLYING;
}
```

Whether removing the offender’s weapon should terminate the victim freeze should follow the existing action’s threat-validity rule. For consistency and exploit prevention, the recommended behavior is **yes**: once no valid weapon is presented, the coercive session fails/cancels.

### Rework the MCA Reborn Crime button

There is already a `McaInteractionScreenBridge`. The existing button is created reflectively on MCA’s own interaction screen; its current layout is based on offsets around screen center and its active state primarily reflects whether a target entity was resolved, while `client.showButtonOnMcaScreen` can disable the feature. fileciteturn4file0L2-L2

0.5.1 should refine this rather than add another competing button.

**Required behavior:**

```text
MCA Interaction
┌─────────────────────────────────┐
│                                 │
│ existing MCA interaction UI     │
│                                 │
│                                 │
│            [ Crime ]            │  ← bottom
└─────────────────────────────────┘
```

The Crime button MUST:

- appear at the bottom of the MCA interaction UI;
- preserve the existing client config that can hide it;
- be enabled only when the local player has a qualifying weapon drawn;
- use the same main-hand/offhand rules as the actual crime action system;
- still be visible when disabled;
- display a localized tooltip explaining the weapon requirement;
- not react to clicks while disabled;
- have its eligibility revalidated server-side if a malicious client sends the open-menu packet anyway.

Suggested localization:

```json
{
  "gui.mcacrime.crime.button": "Crime",
  "gui.mcacrime.crime.requires_weapon": "Draw a weapon to commit a crime."
}
```

Potential richer tooltip:

```text
Draw a weapon to commit a crime.
```

or, when offhand weapons are disallowed:

```text
Hold a weapon in your main hand to commit a crime.
```

Button state:

```java
boolean hasWeapon = ClientWeaponClassifier.hasQualifyingDrawnWeapon(player);

button.active = targetId != null && hasWeapon;

if (!hasWeapon && button.isHoveredOrFocused()) {
    renderTooltip(...requires_weapon...);
}
```

The server packet handler MUST independently do:

```java
if (!weaponClassifier.isArmed(sender)) {
    return; // reject
}
```

before opening or authorizing the Crime menu.

Because button position can overlap MCA elements on differing MCA builds or GUI scales, “bottom” should be anchored relative to the MCA panel bounds where those bounds can be discovered, with screen-bottom fallback. Do not hardcode only a single `screen.height / 2 + constant` and consider the problem solved.

## Criminal villagers, mugging, fencing, and guard response

### Criminal-job representation

Introduce:

```java
public enum CriminalJob {
    NONE,
    THIEF,
    FENCE
}
```

and a service:

```java
public interface CriminalJobService {
    CriminalJob get(UUID villager);
    void set(UUID villager, CriminalJob job);
    boolean isCriminal(UUID villager);
}
```

The storage layer should be independent of MCA’s internal profession implementation. MCA-visible profession/job presentation can be provided by the MCA adapter, but the authoritative fact that “this villager is a thief” must remain readable even if MCA internals shift in a later compatible version.

This provides a much safer compatibility boundary than making criminal behavior depend on a fragile reflected ordinal or private MCA profession class.

Persist:

```text
villager UUID
criminal job
assigned timestamp/day
optional generated-spawn origin
last mug timestamp
optional criminal personality seed
```

Do not persist live Java entity references.

### Assignment and spawning

**Thieves** may occur:

- as a small fraction of eligible adult MCA villagers in ordinary villages;
- rarely as independent/wandering criminals in the wild;
- through commands/admin assignment for testing and modpacks.

**Fences** should overwhelmingly belong to settlements or fixed underworld trading locations. A lone fence wandering wilderness is mechanically much less useful and should not be the standard spawn path.

Recommended configurable defaults:

```toml
[criminalJobs]
enableThieves = true
enableFences = true

# Chance an eligible village adult becomes criminal during assignment.
villageThiefChance = 0.025
villageFenceChance = 0.010

# Independent world appearance should be substantially rarer.
wildThiefChance = 0.0025

minVillagePopulationForFence = 5
criminalAssignmentCooldownDays = 3
```

These are recommended balance starting points, not claims about current code.

Avoid assignment to:

- children;
- dead/removed villagers;
- guards/archers where the role would conflict;
- already-specialized criminal villagers;
- jailed prisoners unless preserving their criminal job is intended;
- quest-critical/protected NPCs when compatibility can identify them.

A criminal job should normally survive arrest and imprisonment. Going to jail does not magically stop someone from being a thief.

### Thief behavioral state machine

Do not make thieves generic hostile mobs that periodically call `hurt()`.

Recommended state machine:

```text
IDLE
  ↓
SCOUTING
  ↓ suitable victim found
STALKING / APPROACHING
  ↓ close + safe enough
THREATENING
  ↓
MUGGING
  ├── victim draws weapon ──────> ABORTED / FLEEING
  ├── guard intervenes ─────────> ARRESTED
  ├── thief takes lethal damage > DEAD
  ├── target invalid ───────────> ABORTED
  └── timer completes ──────────> THEFT_COMMITTED
                                      ↓
                                   FLEEING
                                      ↓
                                   COOLDOWN
```

Suggested service split:

```java
ThiefBehaviorService
MugTargetSelector
GuardRiskEvaluator
NpcMuggingService
StolenGoodsLedger
```

The behavior service decides **whether** to try a mugging. `NpcMuggingService` owns the transactional crime once the threat starts.

### Victim selection

A thief should prefer plausible opportunities rather than the nearest player regardless of context.

Candidate requirements:

```text
server-side living ServerPlayer
not creative/spectator
within configurable acquisition radius
not already victim of another thief
not already holding a qualifying weapon
not invulnerable
not protected by a configured immunity
not within thief mugging cooldown
has line of sight or plausible approach path
```

Opportunity score can use:

```text
score =
  distanceScore
+ isolationScore
+ wealthHint
- guardExposurePenalty
- crowdExposurePenalty
- recentFailurePenalty
```

Do **not** inspect the player’s entire inventory every AI tick merely to estimate wealth.

### Thieves must avoid guards

The requirement is behavioral, not just a random “don’t mug near guards” check.

A `GuardRiskEvaluator` should periodically cache nearby guard risk:

```java
record GuardRisk(
    int nearbyCount,
    boolean hasLineOfSight,
    double nearestDistance,
    double riskScore
) {}
```

During target selection:

```java
if (guardRisk >= hardAvoidThreshold) {
    rejectTarget();
}
```

During approach:

```java
if (guardRisk rises sharply) {
    abortAndReposition();
}
```

During escape:

- path away from visible guards;
- favor alleys/buildings/terrain that breaks sight where ordinary navigation allows;
- do not evaluate an expensive pathfinding solution against every guard every tick.

This should make witnessing a thief crime feel like a behavioral failure by the thief, not the routine outcome of an NPC obliviously robbing people beside the police station.

### Player mugging interaction

The mug begins only after the thief has approached the victim and entered the threat state.

The player receives a progress display comparable to the player-originated crime progress UI:

```text
THIEF IS MUGGING YOU
[██████████████--------]
Draw a weapon to resist
```

Recommended duration:

```toml
thiefMugDurationTicks = 80   # 4 seconds at 20 TPS
```

Use the existing crime-progress visual system if one exists and can represent reversed actor/victim roles. Otherwise add a dedicated S2C mug progress packet and HUD overlay. A vanilla boss bar is an acceptable low-complexity fallback, but a matching MCA: Crime HUD treatment is preferable.

**Critical requirement from the requested design:**

- A player **already holding a qualifying weapon** cannot be selected for mugging.
- If an unarmed player is already being mugged, **drawing a qualifying weapon disrupts the mugging**.
- Normal voluntary actions should not trivially reset the mug timer.
- Guard intervention also disrupts it.
- Thief death obviously terminates it.

Do not make taking incidental damage, stepping one block away, looking away, opening inventory, or tapping sprint reset progress unless there is a deliberate later configuration for such behavior. The requested counterplay is drawing a weapon.

Eligibility should be checked every server tick or at a modest interval during the short session:

```java
if (weaponClassifier.isArmed(victim)) {
    abort(REASON_VICTIM_ARMED);
    thiefBehavior.enterFleeOrFight();
}
```

The weapon should not need to be the weapon physically rendered on screen in a subjective “draw animation”; “drawn” in 0.5.1 should mean held in an accepted hand according to MCA: Crime’s weapon rules.

### Theft resolution

At completion, theft uses this strict priority:

```text
configured currency available?
       |
      yes
       ↓
steal randomized currency amount
       |
      no
       ↓
eligible ordinary inventory item available?
       |
      yes
       ↓
steal one eligible item/stack unit according to configured policy
       |
      no
       ↓
nothing stolen; thief flees anyway
```

The existing project already has currency/economy abstractions such as `Currency`, `EmeraldCurrency`, and `EconomicTransactionService`; theft should go through that abstraction rather than directly hardcoding `Items.EMERALD`. fileciteturn2file0L2-L2

Recommended currency amount configuration:

```toml
[criminalJobs.thief]
minCurrencySteal = 1
maxCurrencySteal = 8
stealAllIfBelowMinimum = true
```

Actual amount:

```java
maxStealable = min(balance, configuredMax);
amount = randomBetween(min(configuredMin, maxStealable), maxStealable);
```

The thief must never produce a negative balance or steal nonexistent currency.

### Inventory theft eligibility

By default:

```text
CAN steal:
  normal main inventory slots 9–35

CANNOT steal:
  hotbar 0–8
  armor slots
  offhand
  curio/accessory slots unless explicitly integrated and configured
  items protected by tag
```

Suggested tag:

```text
mcacrime:thief_theft_immune
```

Suggested config:

```toml
protectHotbarFromThieves = true
protectArmorFromThieves = true
protectOffhandFromThieves = true
stealWholeStack = false
```

The safest default interpretation of “lose one item” is **one item count**, not an entire randomly selected 64-stack.

For example, if an eligible slot contains 37 iron ingots, the default theft result is:

```text
victim: 36 iron ingots
thief stolen ledger: 1 iron ingot
```

This avoids a wildly different punishment based only on stack consolidation.

A modpack can expose:

```toml
itemTheftMode = "SINGLE_ITEM"
# optional later: "WHOLE_STACK", "RANDOM_COUNT"
```

### Stolen-goods recovery

Every successful theft creates a persisted entry associated with the thief:

```java
record StolenProperty(
    UUID transactionId,
    UUID originalOwner,
    long stolenGameTime,
    ItemStack stack,
    @Nullable CurrencyAmount currency
) {}
```

On thief death, MCA: Crime must atomically:

1. claim the undropped ledger entries;
2. create appropriate drops/recovery output;
3. mark or remove the entries;
4. only then allow subsequent death handling.

This prevents a second death event or reload from dropping the same item twice.

For currency, two behaviors are sensible. The recommended one is to drop the corresponding currency items when the configured currency has a physical-item representation. If a future currency provider is abstract/non-item-based, use a recovery token or direct reward mechanism supplied by that provider.

**Recently stolen goods should remain attributable.** If the victim kills the thief immediately, recovery should be straightforward. A configurable expiry can eventually “launder” old thefts so persistent world data does not grow forever:

```toml
stolenGoodsPersistenceDays = 7
```

Do not expire property while the thief is actively loaded in a mugging/escape state.

### Guards and thief arrests

Once `THREATENING` begins, the thief is committing an observable attempted mugging. A guard who detects it should:

```text
register thief as active criminal target
cancel current mugging
pursue thief
capture/arrest if possible
escort to jail
```

A thief **caught in the act must always be jail-eligible**, regardless of any ordinary fine-only outcome that might apply to another case.

Recommended incident flag:

```java
EnumSet<CrimeFlag> flags;
flags.add(CAUGHT_IN_ACT);
flags.add(MANDATORY_CUSTODY);
```

Guard behavior should not depend solely on the victim reporting the incident after the fact.

The project already has explicit guard enforcement and arrest flows, including guard targeting and restraint/escort functionality. fileciteturn3file1L24-L34 Extend that machinery to accept a `VillagerCrimeActor`.

After capture:

```text
thief stops mugging
thief is restrained
rear-arm pose becomes visible
guard escorts thief
thief enters jail/prison state
stolen property remains recoverable according to arrest rules
```

A valuable extension is to return **known stolen property** to a victim automatically upon successful arrest when the victim is nearby, rather than requiring the prisoner to be killed. This is recommended because otherwise the system perversely encourages murdering an already-captured thief. If automatic return is enabled, removal from the ledger must be transactional exactly like death recovery.

### Fence behavior and inventory

A Fence is not merely a renamed normal merchant. It should have a separate illicit-goods pool.

Use tags as the public datapack API:

```text
# items eligible for either direction
mcacrime:illicit_goods

# optional finer control
mcacrime:fence_buys
mcacrime:fence_sells

# never accepted despite other matching rules
mcacrime:fence_blacklist
```

Vanilla defaults should emphasize items plausibly connected to theft, violence, evasion, or dangerous activity rather than dumping every weapon into one enormous table. Suggested defaults can include selected explosives, dangerous weapons, and other crime-relevant goods, but the final list should be a data file so packs can redefine their own concept of contraband.

MCA: Crime items such as restraints or other explicitly criminal tools should be tagged appropriately.

### Locks Reforged compatibility

The relevant Locks Reforged project is a Forge 1.20.1 port in which locks can be attached to blocks, lock security is tiered, and lockpicks are an established part of the system; its own documentation notes lockpicks as obtainable through the game’s economy. citeturn0search3turn0search6

Therefore, when Locks Reforged is present:

- fences should be able to sell its lockpicks;
- fences should sell appropriate locks;
- optionally, fences should buy those goods;
- the integration must disappear cleanly when the mod is absent.

Prefer runtime registry/tag discovery to Java linking:

```java
ResourceLocation id = new ResourceLocation("locksreforged", "...");

BuiltInRegistries.ITEM.getOptional(id).ifPresent(item -> ...);
```

Actual IDs must be sourced from the loaded registry/integration adapter rather than guessed into common code.

Recommended structure:

```text
compat/locksreforged/
  LocksReforgedCompat.java
```

with:

```java
interface IllicitGoodsProvider {
    void contribute(FenceGoodsRegistry registry);
}
```

Then future integrations—guns, drugs/contraband systems, lock mods—can contribute stock without editing `FenceTradeService`.

### Fence prices: karma versus Heat

The social distinction should be very explicit:

- **Karma** reflects whether the player is “one of us.”
- **Heat** reflects how much law-enforcement attention doing business with that player attracts.

Those effects should not be collapsed.

A highly criminal player with very low karma but very high Heat should therefore experience:

```text
criminal affinity discount
            +
law-enforcement risk surcharge
```

Potentially the surcharge can outweigh the discount.

Recommended normalized variables:

```java
double criminalAffinity = normalizeNegativeKarma(karma); // 0..1
double heatRisk = normalizeHeat(heat);                    // 0..1
boolean wanted = outlawResolver.isWanted(player);
```

Recommended selling-price formula:

```text
multiplier =
    1.00
  - 0.25 * criminalAffinity
  + 0.35 * heatRisk
  + 0.20 * (wanted ? 1 : 0)
```

with:

```text
minimum multiplier = 0.55
maximum multiplier = 2.50
```

Example:

| Player | Criminal affinity | Heat risk | Wanted | Price tendency |
|---|---:|---:|---:|---|
| law-abiding, cold | 0 | 0 | no | ordinary / outsider price |
| notorious criminal, cold | 1 | 0 | no | strong discount |
| notorious criminal, hot | 1 | 1 | no | modest surcharge or near-normal |
| notorious criminal, hot + wanted | 1 | 1 | yes | risk premium despite respect |

This directly honors the request that criminal villagers “respect” criminals while refusing to make wanted status economically advantageous in every circumstance.

For items the **fence buys from the player**, use a separate formula and cap so buy/sell modifiers cannot create infinite emerald arbitrage:

```text
fenceBuyPrice < fenceSellPrice for the same commodity
```

after all modifiers.

A small dialogue reaction layer would make the system feel more intentional:

```text
low karma + low heat:
  "You look like you know how business works."

high positive karma:
  "You don't look like my usual clientele."

high heat:
  "You've got half the guards looking for you. That's going to cost extra."

wanted:
  "Keep your head down. I charge for this kind of attention."
```

These should be translation keys, not hardcoded English.

## Outlaws, bounty hunting, and MCA: Quests integration

### Correct the outlaw-lawfulness invariant

This requested issue deserves special treatment because the **current source is already partially designed to do what the user wants**.

The existing `LegalTarget` logic recognizes multiple bases for lawful targeting, including `WANTED`, optionally a sufficiently criminal/red karma band, and guard-aggression context. `CrimeGate` also checks legal-target status in player assault/murder handling so attacks against a legal target can be excluded from ordinary PvP crime processing. In other words, 0.5.1 should treat the reported behavior as a regression/consistency bug to eliminate, not blindly add an unrelated exception.

The specification should therefore establish the invariant:

> **Any player whom MCA: Crime currently authorizes ordinary guards to attack because of their criminal/outlaw status is also a lawful target for another player’s proportional combat under the same legal-status snapshot.**

Do not maintain separate logic such as:

```text
GuardEnforcement: wanted means attack
CrimeGate:       wanted means victim
BountyService:   something else again
```

Instead introduce or consolidate:

```java
public final class OutlawResolver {
    public OutlawStatus resolve(ServerPlayer target, LawContext context);
}
```

with:

```java
public record OutlawStatus(
    boolean lawfulCombatTarget,
    boolean bountyEligible,
    LegalTarget.Basis basis,
    int heat,
    int karma,
    @Nullable UUID warrantId,
    long warrantRevision
) {}
```

`LegalTarget` can remain the low-level policy implementation; `OutlawResolver` becomes the single public service used by:

```text
CrimeGate
GuardEnforcement
BountyService
tooltip/dialogue where needed
quest integration
```

### Lawful force does not mean every interaction becomes consequence-free

Recommended rules:

| Action against valid outlaw | Crime result |
|---|---|
| ordinary attack | no assault crime |
| combat kill | no murder crime |
| kill qualifying for bounty | no murder + bounty claim |
| steal from outlaw | still theft unless explicitly legalized |
| kidnap/restrain outlaw as ordinary civilian | should require bounty/arrest authority rather than being globally free |
| harm bystander while attacking outlaw | ordinary collateral rules still apply |
| outlaw status expires before attack | victim is no longer automatically lawful |

This avoids turning “outlaw” into “all rules cease to exist.”

### Bounty eligibility

Do not equate every temporarily attackable entity with a cash bounty.

Recommended distinction:

```text
lawfulCombatTarget = true
bountyEligible      = false/true independently
```

For example, immediate self-defense against someone who just attacked a guard can justify force without necessarily producing a government bounty payment.

Bounty eligibility should normally require:

```text
active wanted/outlaw status
+
qualifying warrant/case severity
+
nonzero bounty
```

### Bounty value

Base the bounty on persisted wrongdoing rather than a random kill reward.

Recommended conceptual formula:

```text
bounty =
    baseWantedReward
  + unresolvedSeverityValue
  + outstandingFineShare
  + repeatOffenderBonus
```

then:

```text
bounty = clamp(bounty, minBounty, maxBounty)
```

Possible defaults:

```toml
[bounty]
enabled = true
baseBounty = 8
severityRewardScale = 2.0
fineRewardShare = 0.25
repeatOffenderBonus = 4
maxBounty = 128
```

Use the configured MCA: Crime currency abstraction for payout.

If the currency provider ultimately uses emeralds, the player receives emerald-equivalent payment. If a pack configures another currency, the bounty system should not silently pay emeralds anyway.

### Prevent repeated bounty farming

Never implement:

```java
if (victim.isWanted()) giveEmeralds(killer);
```

in a death event with no claim identity.

Persist a claim key:

```java
record BountyClaimKey(
    UUID targetUuid,
    UUID warrantId,
    long warrantRevision
) {}
```

and:

```java
record BountyClaim(
    BountyClaimKey key,
    UUID claimantUuid,
    long claimedGameTime,
    long rewardAmount
) {}
```

Only the first accepted claim for a warrant revision pays the principal bounty.

A new offense that materially updates the warrant can increment `warrantRevision`, making the target eligible for a later legitimately new bounty.

Further anti-exploit checks:

- target must be a qualifying outlaw at death resolution;
- killer must be a real eligible server player;
- suicide pays nothing;
- environment-only death pays nothing;
- owner-controlled pet/projectile attribution resolves to the responsible player only where reliable;
- same-player/fake-player farming can be optionally denied;
- claimant cannot be the bounty target;
- claim must atomically mark paid before or with currency issuance;
- disconnect/reconnect does not reset claim state.

### Do not accidentally double-reward combat events

Use a dedicated event/result object:

```java
public record BountyResolution(
    BountyClaimKey claimKey,
    UUID target,
    UUID claimant,
    long principalReward,
    BountyResolutionType resolutionType
) {}
```

Possible resolution types:

```text
KILLED
ARRESTED
CAPTURED_ALIVE
```

Even if 0.5.1 initially pays only `KILLED`, including the enum now makes “dead or alive” contracts easy later.

A more interesting recommended expansion is to make **alive capture worth more than killing**:

```toml
aliveCaptureMultiplier = 1.25
killMultiplier = 1.00
```

This uses MCA: Crime’s existing restraint/jail mechanics and creates a reason to interact with them rather than treating bounty hunting as ordinary PvP.

### MCA: Quests integration

MCA: Quests is an otectus project built around quests offered through MCA villagers, including accepting or declining villager-provided objectives. citeturn0search2 That makes it a natural optional presentation layer for bounty contracts, while MCA: Crime should remain the authority over actual crime/warrant validity.

The dependency direction should be:

```text
MCA: Crime owns:
  wanted state
  warrant
  bounty amount
  legal target status
  arrest/kill resolution
  payout ledger

MCA: Quests integration owns:
  presenting a bounty as a quest
  quest objective text
  quest acceptance
  optional quest-specific bonus / reputation
  completion when Crime reports resolution
```

Do **not** let both systems independently award the principal bounty.

Recommended optional integration:

```java
public interface BountyQuestBridge {
    boolean available();

    void publish(BountyContract contract);

    void onBountyResolved(BountyResolution resolution);

    void invalidate(BountyContractId id);
}
```

Common code sees only this interface. The MCA: Quests-specific implementation is loaded only when the mod is present.

Suggested contract:

```java
record BountyContract(
    UUID contractId,
    UUID targetUuid,
    Component targetName,
    UUID warrantId,
    long warrantRevision,
    long principalReward,
    boolean killAllowed,
    boolean captureAllowed,
    long expiresAtGameTime
) {}
```

Potential quest text:

```text
BOUNTY: <name>

Wanted for:
  <top unresolved offense>
  <additional offense count>

Reward:
  <currency amount>

Bring the target to justice.
Dead or alive.
```

If capture is enabled:

```text
Alive:  125% reward
Dead:   100% reward
```

This should remain data/configurable.

### Contract generation

Do not generate a quest for every point of Heat.

Create one when a player crosses into a qualifying warrant band or when a warrant revision becomes bounty-eligible.

Lifecycle:

```text
crime raises wanted status
        ↓
warrant becomes bounty-eligible
        ↓
BountyService creates contract
        ↓
MCA: Quests bridge publishes it
        ↓
hunter accepts contract
        ↓
target killed/captured
        ↓
BountyService validates legal resolution
        ↓
claim ledger atomically claims reward
        ↓
Crime pays principal
        ↓
Quests marks objective complete
```

If the target ceases to be legally bounty-eligible before completion—because of sentence service, expiration, administrative clearing, or another valid resolution—the contract should become invalid rather than instructing the player to murder a now-lawful civilian.

### Shared versus exclusive contracts

For multiplayer, recommended default:

```toml
contractMode = "OPEN"
```

Multiple bounty hunters can possess the objective, but only the player credited with the accepted resolution receives the principal bounty. Other copies of that contract become failed/expired.

A later mode could use:

```text
EXCLUSIVE
PARTY
OPEN
```

but only `OPEN` needs to ship in 0.5.1 unless MCA: Quests already provides a clean concept matching another mode.

### Bounty-hunter social feedback

A modest positive response can make the loop feel integrated:

- capture/kill of a legal outlaw should not reduce the hunter’s karma as murder would;
- a lawful bounty resolution may provide a small configurable positive reputation/karma adjustment;
- criminal Fences/Thieves may dislike a prolific bounty hunter even when ordinary civilians approve.

That produces an interesting social distinction: **lawful does not necessarily mean universally liked**.

Do not hardwire a massive karma reward that makes bounty farming the dominant reputation strategy.

## Data, networking, configuration, and compatibility

### Persistence model

0.5.1 adds enough persistent concepts to justify a small explicit versioned world-data layer.

Recommended root:

```text
McaCrimeWorldData
  schemaVersion
  criminalVillagers
  activeNpcMuggings          only if sessions intentionally survive restart
  stolenGoods
  bountyWarrants
  bountyClaims
```

Entity-local ephemeral AI state can remain transient. World-significant state must survive reload.

Suggested records:

```java
record CriminalVillagerRecord(
    UUID villagerUuid,
    CriminalJob job,
    long assignedAt,
    long lastMugAt
) {}

record StolenGoodsRecord(
    UUID transactionId,
    UUID thiefUuid,
    UUID ownerUuid,
    ItemStack stack,
    @Nullable CurrencyAmount currency,
    long stolenAt
) {}

record BountyClaimRecord(
    UUID targetUuid,
    UUID warrantId,
    long warrantRevision,
    UUID claimantUuid,
    long reward,
    long claimedAt
) {}
```

Use Minecraft’s established `ItemStack` serialization so modded stack NBT/components are preserved correctly. Never persist an item as only `ResourceLocation + count` when it can carry enchantments or custom data.

### World-data schema migration

Do not overwrite 0.5.0 saves assuming new collections were always present.

Example:

```java
static final int SCHEMA_0_5_0 = ...;
static final int SCHEMA_0_5_1 = ...;
```

Migration:

```text
missing criminalVillagers -> empty
missing stolenGoods       -> empty
missing bountyClaims      -> empty
existing wanted/case data -> preserved exactly
```

Unknown or malformed records should be skipped with a bounded warning, not crash the entire server.

### Bounded persistence

NPC histories can otherwise grow indefinitely.

Implement maintenance policies:

- purge records for stale, nonexistent generated criminal villagers after a configurable grace period;
- expire very old unclaimed stolen-goods records;
- retain bounty claim identity long enough to prevent exploit resurrection;
- cap malformed/untrusted loaded collections;
- avoid storing tick-by-tick mug progress.

### Networking

Recommended new or revised packet surface:

| Direction | Packet | Purpose |
|---|---|---|
| S2C | `RestraintVisualSyncPacket` | restrained MCA villager pose/type/escort |
| S2C | `NpcMuggingStartPacket` | victim-only mug HUD initialization |
| S2C | `NpcMuggingProgressPacket` | victim-only authoritative progress |
| S2C | `NpcMuggingStopPacket` | success/abort/guard-intervention reason |
| S2C | optional weapon-policy snapshot | prevents client button rules from visibly disagreeing with server config |
| C2S | existing/open Crime-menu packet | must now enforce server weapon gate |
| S2C | optional criminal-job state | only if client rendering/dialogue needs it |

Progress packets do not need to be sent every tick. For a four-second progress bar, updates every 2–5 ticks plus client interpolation are sufficient.

Every packet handler should validate:

```text
correct logical side
sender exists
target exists
UUID/entity ID match when both supplied
reasonable interaction distance
expected state/session
sender authorization
```

Never trust a client-provided “mug finished” or “I killed a bounty target” packet. Those outcomes arise from server events.

### Configuration additions

A coherent proposed configuration surface:

```toml
[client]
showButtonOnMcaScreen = true
showNpcMuggingHud = true
showRestraintVisuals = true

[crimeInteraction]
civilianCrimeReactionSpeedMultiplier = 0.65
freezeComplyingVictims = true
armedVillagersCanResist = true

[criminalJobs]
enableThieves = true
enableFences = true
villageThiefChance = 0.025
wildThiefChance = 0.0025
villageFenceChance = 0.010
criminalAssignmentCooldownDays = 3

[criminalJobs.thief]
mugDurationTicks = 80
mugCooldownTicks = 12000
targetSearchRadius = 20.0
guardAvoidRadius = 16.0
guardHardAbortRadius = 8.0
minCurrencySteal = 1
maxCurrencySteal = 8
protectHotbar = true
protectArmor = true
protectOffhand = true
itemTheftMode = "SINGLE_ITEM"
stolenGoodsPersistenceDays = 7

[criminalJobs.fence]
maxKarmaDiscount = 0.25
maxHeatMarkup = 0.35
wantedMarkup = 0.20
minimumPriceMultiplier = 0.55
maximumPriceMultiplier = 2.50

[bounty]
enabled = true
baseBounty = 8
severityRewardScale = 2.0
fineRewardShare = 0.25
repeatOffenderBonus = 4
maxBounty = 128
payForKills = true
payForAliveCapture = true
aliveCaptureMultiplier = 1.25
killMultiplier = 1.0

[compat]
mcaQuestsBounties = true
locksReforgedFenceTrades = true
```

All numeric inputs need validation/clamping at config load.

Where the existing config architecture divides common/server/client values differently, follow that structure rather than creating a separate parser solely to match these names verbatim.

### Public datapack tags

0.5.1 should deliberately expose modpack customization through tags:

```text
# Weapons: retain existing tags
mcacrime:weapons
mcacrime:weapon_blacklist

# New
mcacrime:thief_theft_immune
mcacrime:illicit_goods
mcacrime:fence_buys
mcacrime:fence_sells
mcacrime:fence_blacklist

# Entity/role policy where feasible
mcacrime:always_resists_weapon_threats
mcacrime:never_resists_weapon_threats
```

The current weapon system already uses data/config-driven inclusion and exclusion, so these additions are consistent with existing design. fileciteturn4file0L2-L2

### Currency abstraction

Every new money-moving system must use the same currency provider:

```text
player mug victim loss
thief currency stash
fence prices
bounty payout
quest bounty display
```

If existing `EmeraldCurrency` is presently the only provider, design 0.5.1 against the `Currency` abstraction anyway. The repository already separates `Currency` from `EmeraldCurrency`, making direct emerald calls in the new code architectural backsliding. fileciteturn2file0L2-L2

A useful API shape:

```java
public interface Currency {
    ResourceLocation id();

    long balance(ServerPlayer player);

    CurrencyDebit debit(
        ServerPlayer player,
        long requested,
        TransactionReason reason
    );

    CurrencyCredit credit(
        ServerPlayer player,
        long amount,
        TransactionReason reason
    );

    Component format(long amount);
}
```

`debit()` should return the amount actually removed rather than assuming the requested amount existed.

### MCA version compatibility

The release must continue probing the MCA versions currently represented by the build configuration, including 7.6.20 and tested 7.7 pre-release variants, rather than coding only against one model/screen layout. fileciteturn6file0L2-L2

The highest-risk MCA-sensitive areas in this update are:

```text
villager arm model access
MCA interaction-screen bounds/button insertion
job/profession presentation
guard identification
guard combat brain
villager merchant integration
```

Isolate each behind existing/new MCA bindings.

Compatibility failure should degrade narrowly. Example:

```text
MCA villager rear-arm model hook unavailable
→ log once
→ keep restraint gameplay
→ skip only special arm pose
```

not:

```text
reflection failed
→ entire crime mod crashes
```

Where a feature is essential to the release, automated compatibility probes should catch this during build/testing rather than relying on runtime degradation as the normal outcome.

### Locks Reforged loading

Locks Reforged should be treated as a pure optional goods provider. Its Forge 1.20.1 documentation establishes the lock/lockpick ecosystem that makes the Fence integration appropriate. citeturn0search3turn0search6

Acceptance:

```text
Locks absent:
  MCA: Crime starts
  fences function
  no missing-class errors

Locks present:
  detected lock/lockpick goods are inserted into configured fence pools
  trades serialize correctly
  removing Locks later does not corrupt MCA: Crime world data
```

Do not persist arbitrary optional-mod `ItemStack`s as mandatory fence inventory forever if the underlying mod is removed; generated merchant offers should be rebuilt/validated.

### Inspiration and design synthesis

The strongest comparison for this implementation is not to copy another game’s UI wholesale, but to adopt several proven crime-system principles:

| Pattern | Application to MCA: Crime |
|---|---|
| **Crime has an attempt phase before completion** | Guards can stop a thief during the mugging bar rather than reacting only after theft. |
| **Criminal status is contextual** | Low karma earns underworld respect; Heat creates business risk. |
| **Lawful target status has one authority** | Guards, player crime detection, bounty payouts, and quests cannot disagree about whether an outlaw may be attacked. |
| **Bounties are claims on offenses, not mob drops** | A warrant revision can pay once, preventing respawn/re-kill farming. |
| **Property retains provenance** | A thief can drop or return the actual stolen object rather than spawning generic loot. |
| **Police presence affects offender behavior before the crime** | Thieves avoid guards rather than waiting to be arrested every time. |
| **Capture can be mechanically preferable to killing** | MCA’s restraints/jail system becomes part of bounty hunting. |
| **Contraband is data-driven** | Fence economies naturally integrate Locks Reforged and future mods. |

The optional Locks Reforged integration is particularly well suited to this approach because locks and lockpicks provide a natural illicit-goods category rather than requiring MCA: Crime to invent a parallel burglary tool ecosystem. citeturn0search6 Likewise, MCA: Quests supplies an appropriate villager-facing quest presentation layer, while MCA: Crime should retain authority over legal and economic resolution. citeturn0search2

## Verification, migration, and delivery checklist

### Required automated test groups

The update is too stateful for manual-only verification. Expand the repository’s existing test coverage around every new authority boundary.

**Restraints and rendering**

| Test | Expected result |
|---|---|
| restrain MCA villager with cuffs | server restraint state true |
| track already-restrained villager | late client receives visual state |
| release villager | rear-arm/cuffs state removed |
| cuffed villager escorted | restrained pose and rope state coexist |
| guard dies while escorting | rope cleans up; restraint state follows enforcement policy |
| victim unloads/reloads | state either persists intentionally or restores correctly |
| two clients observe captive | both see same pose |
| dedicated server starts | no client renderer class loading server-side |

Rendering itself should have at least an in-game visual smoke test because arm rotations cannot be usefully validated only through ordinary unit tests.

**Threat compliance**

```text
unarmed civilian + active gun/sword-point crime
→ COMPLYING
→ navigation suppressed
→ remains near start position

armed guard
→ not frozen
→ resistance/guard enforcement path

archer with weapon
→ not frozen

modded armed villager matching weapon classifier
→ not frozen

civilian merely near armed player
→ normal AI

crime action cancelled
→ navigation/speed restored immediately

weapon removed mid-session
→ session follows configured cancellation semantics
→ no stuck victim
```

**Crime button**

```text
MCA interaction + qualifying main-hand weapon
→ enabled

MCA interaction + no weapon
→ disabled
→ tooltip shown
→ click does nothing

offhand weapon allowed by config
→ enabled

offhand weapon disabled by config
→ disabled

malicious OpenCrimeMenuPacket without weapon
→ server rejects

weapon dropped between click and packet processing
→ server rejects

target entity gone
→ packet rejected safely
```

**Thief target selection**

```text
unarmed survival player
→ eligible

creative player
→ ineligible

spectator
→ ineligible

player already holding qualifying weapon
→ ineligible

player near visible guard
→ strongly disfavored/rejected

player with guard obscured/far away
→ configurable risk outcome

two thieves targeting same player
→ no accidental overlapping mug sessions by default
```

**Mug progression**

```text
mug begins
→ HUD starts

victim draws weapon
→ abort
→ no property loss

guard intervenes
→ abort
→ no property loss
→ thief enters guard enforcement

timer completes
→ exactly one transaction

victim disconnects before commit
→ no phantom debit

thief dies before commit
→ no debit

thief dies after commit
→ exact stolen property recoverable
```

**Currency theft**

Test:

```text
0 currency
1 currency
below configured minimum
exact configured maximum
above maximum
custom currency provider
```

No test may produce a negative balance.

**Fallback item theft**

Create inventories in which:

- only hotbar has items;
- only armor has items;
- only offhand has an item;
- one ordinary slot has an item;
- multiple ordinary slots have items;
- eligible item is NBT-rich;
- ordinary inventory is entirely protected by `thief_theft_immune`.

Expected default:

```text
protected equipment untouched
one count removed from exactly one eligible ordinary slot
exact stack/NBT recorded in thief ledger
```

**Stolen-property duplication test**

Sequence:

```text
steal diamond sword with custom name/enchantments
save
reload
kill thief
collect drop
save/reload again
```

Expected total number of that stolen sword created by MCA: Crime: **one**.

Run equivalent test for guard-return recovery if implemented.

**Guard intervention**

```text
guard sees threat phase
→ targets thief
→ mug cancels

guard becomes aware after completion
→ pursues thief based on crime/case

caught-in-act thief apprehended
→ mandatory jail branch

ordinary noncriminal villager
→ not arrested merely for being near victim

thief approaches but never threatens
→ no arrest solely for thoughtcrime
```

This last test is important: guard avoidance AI should not make guards psychic.

**Fence pricing**

At minimum test a matrix:

| Karma | Heat | Wanted | Expected |
|---:|---:|---|---|
| positive | 0 | no | no criminal discount |
| strongly negative | 0 | no | discount |
| strongly negative | high | no | risk increase |
| strongly negative | high | yes | wanted premium |
| boundary values | max/min | either | clamped safely |

Also assert:

```text
same-item buy price < sell price
```

under every supported modifier combination to close arbitrage loops.

**Optional mods**

Run separate launch/compatibility configurations:

```text
MCA only
MCA + MCA: Quests
MCA + Locks Reforged
MCA + Quests + Locks
```

MCA: Quests is an optional extension built around quests delivered through MCA villagers, so presence/absence testing is essential to ensure the bridge remains optional. citeturn0search2

Locks Reforged likewise must be tested both present and absent. citeturn0search3turn0search6

**Outlaw legality regression**

This is one of the highest-priority tests because the current architecture already contains legal-target logic and the reported behavior suggests at least one execution path may bypass it.

Build a parameterized matrix:

| Victim status | Guard attacks victim? | Hunter attack crime? | Kill bounty? |
|---|---:|---:|---:|
| lawful/cold | no | yes | no |
| wanted/outlaw | yes | **no** | yes if warrant eligible |
| red karma but config says legal target | yes | **no** | configurable/eligibility |
| red karma but `redIsLegalTarget=false` | according to same resolver | according to resolver | no unless independently warranted |
| temporary guard aggressor | possibly | lawful according to policy | normally no bounty |
| warrant expired | no | yes | no |

The key invariant assertion should literally compare the shared resolver result rather than maintain expected values independently in every subsystem.

**Bounty exploit tests**

```text
one warranted outlaw killed once
→ one payout

same warrant/revision somehow resolves twice
→ second payout denied

outlaw respawns without new warrant revision
→ no second payout

new qualifying crimes increment warrant revision
→ later new bounty can be claimed

environment kills outlaw
→ no hunter payout

outlaw kills self
→ no payout

hunter kills ordinary player
→ ordinary murder logic unaffected

target ceases being outlaw immediately before damage
→ legal decision follows authoritative timing policy

quest active + bounty resolves
→ principal paid once
→ quest completes once
```

### Performance requirements

The new AI must avoid “scan the village every tick” patterns.

Recommended scheduling:

```text
thief target scan:        every 20–40 ticks, jittered
guard risk update:        every 10–20 ticks while active
mug weapon check:         every tick or 2 ticks
mug HUD packet:           every 2–5 ticks, interpolated
criminal-job maintenance: coarse interval / entity lifecycle
stale-record cleanup:     daily or world-save interval
```

Spatial queries should use bounded AABBs and existing entity queries.

Do not perform:

```text
all players × all thieves × all guards
```

globally every server tick.

### Logging and diagnostics

Add debug logging behind existing debug configuration where possible:

```text
[crime] thief <uuid> selected victim <uuid>, score=...
[crime] npc mug <tx> started
[crime] npc mug <tx> aborted: VICTIM_ARMED
[crime] npc mug <tx> committed: currency=...
[crime] guard intervention against thief <uuid>
[crime] bounty <warrant/revision> claimed by <uuid>
[compat] MCA: Quests bounty bridge enabled
[compat] Locks Reforged illicit-goods provider enabled
```

Do not log player inventories, every AI tick, or spam a reflection warning continuously.

### Suggested implementation order for Claude

The work has dependencies; implementing it in this order minimizes rework:

1. **Stabilize shared policy primitives first:** common `ArmedResolver`, `OutlawResolver`, actor-neutral transaction helpers, new persistence schema.
2. **Complete visual restraint support:** new art wiring, generic visual state, MCA villager rear-arm pose.
3. **Fix threat reaction semantics:** civilian freeze/slowdown and armed-resistance exceptions.
4. **Refine the MCA interaction Crime button:** bottom anchoring, weapon gating, tooltip, server validation.
5. **Introduce `VillagerCrimeActor` and criminal-job persistence.**
6. **Implement thief target-selection and mug session without theft first.**
7. **Add transactional currency/item theft and stolen-property ledger.**
8. **Integrate guard intervention, mandatory custody, and property recovery.**
9. **Add Fence trade pools and pricing.**
10. **Add Locks Reforged goods provider.**
11. **Consolidate outlaw legal-force behavior and bounty claims.**
12. **Add MCA: Quests bounty-contract bridge.**
13. **Run the full compatibility and exploit matrix before version bump/release packaging.**

These are implementation phases, not separate user-facing releases; 0.5.1 should ship only after the complete feature set passes its release gates.

### Definition of done

Claude should not mark 0.5.1 complete until this checklist is true:

- [ ] `mod_version` is changed from `0.5.0` to `0.5.1` only as part of the finished release; the current build metadata confirms `0.5.0` and the Minecraft/Forge/MCA compatibility baseline. fileciteturn6file0L2-L2
- [ ] TheWiggleDuck’s three supplied restraint assets are wired and credited.
- [ ] Existing item registry IDs are preserved unless migration is explicitly provided.
- [ ] Restrained MCA villagers put their arms/hands behind their backs.
- [ ] Cuffs/rope visual state synchronizes to late-tracking clients.
- [ ] No client-only renderer code loads on a dedicated server.
- [ ] Unarmed civilian victims stop fleeing during an active coercive crime.
- [ ] Movement modifiers/navigation suppression are removed on every exit path.
- [ ] Guards, archers, and qualifying armed/modded villagers can resist.
- [ ] The exact same weapon policy powers crime initiation, mug immunity/interruption, armed classification where applicable, and the MCA Crime-button gate.
- [ ] MCA’s Crime button is bottom-positioned, disabled without a weapon, and explains the requirement through a tooltip.
- [ ] Server packet handling rejects weaponless attempts regardless of client UI.
- [ ] `VillagerCrimeActor` exists; thief crime does not require pretending an NPC is a `ServerPlayer`.
- [ ] Thief and Fence are persistent criminal jobs.
- [ ] Thieves occur in villages and at a significantly lower configurable wild rate.
- [ ] Thieves will not intentionally begin ordinary mugging against an already-armed player.
- [ ] Mugging has visible timed progress.
- [ ] Drawing a qualifying weapon stops an active mug.
- [ ] A visible/intervening guard stops it.
- [ ] Successful mugging steals configured currency first.
- [ ] Lack of currency falls back to exactly one eligible inventory item by default.
- [ ] Hotbar, armor, and offhand are immune by default.
- [ ] Custom item data/NBT survives theft and recovery.
- [ ] Killing a thief cannot duplicate stolen property.
- [ ] Thieves evaluate and avoid guards before crimes.
- [ ] Guards witnessing an active mugging pursue the thief.
- [ ] Thieves caught in the act enter mandatory-custody/jail flow.
- [ ] Fences buy/sell tag-driven contraband.
- [ ] Low karma produces criminal-affinity discounts.
- [ ] Heat produces a risk surcharge.
- [ ] Wanted status can apply an additional risk surcharge.
- [ ] Price clamps prevent negative, zero, overflowing, or arbitrage-producing trades.
- [ ] Locks Reforged contributes locks/lockpicks only when installed; its documented lock-and-lockpick system makes it an appropriate illicit-trade integration. citeturn0search3turn0search6
- [ ] Locks Reforged absence cannot cause class loading or registry errors.
- [ ] A single `OutlawResolver`/equivalent authority governs guard hostility, assault legality, murder legality, and bounty eligibility.
- [ ] If guards may lawfully attack an outlaw due to the same outlaw state, an ordinary hunter is not charged with assault/murder for the corresponding lawful attack.
- [ ] Lawful outlaw status does not automatically legalize unrelated theft/kidnapping.
- [ ] Bounty rewards are tied to a warrant/revision or similarly unique claim identity.
- [ ] A bounty can be paid only once per eligible claim.
- [ ] Rewards use the configured currency abstraction.
- [ ] Environment deaths/suicides do not create hunter payouts.
- [ ] Alive capture can be supported by the same bounty-resolution model even if a pack disables its reward.
- [ ] MCA: Quests receives optional bounty contracts/objectives when installed; the project is itself centered on MCA-villager quest interactions, making it the appropriate presentation layer. citeturn0search2
- [ ] MCA: Quests absence cannot prevent MCA: Crime from loading.
- [ ] Crime, not Quests, owns the authoritative warrant and principal bounty payout.
- [ ] Completing a bounty quest cannot double-pay the principal bounty.
- [ ] Existing player restraint rendering remains intact; 0.5.0 already contains the player rear-arm/cuffs/escort-rope functionality that the new villager visuals should generalize rather than replace. fileciteturn5file0L2-L2
- [ ] Existing MCA guard brain integration remains intact. fileciteturn5file0L2-L2
- [ ] Existing 0.5.0 crime actions and reaction states pass regression testing. fileciteturn4file0L2-L2
- [ ] MCA 7.6.20 and the project’s existing 7.7 compatibility probes remain green. fileciteturn6file0L2-L2
- [ ] Dedicated-server launch test passes with every optional-integration combination.
- [ ] Save/load/restart tests pass with active criminal villagers, stolen goods, wanted players, and previously claimed bounties.
- [ ] `CHANGELOG.md` documents every major 0.5.1 behavior, migration/config change, new compatibility layer, and contributor credit.

**The most important architectural conclusion for 0.5.1 is that these features should not be implemented as seven isolated additions.** The repository already has the beginnings of a generalized crime simulation—actor abstraction, reaction state, observations, law enforcement, economy, legal-target logic, and restraint presentation. fileciteturn4file0L2-L2 The update should make those systems agree with one another: a thief is a genuine `CrimeActor`; mugging is a genuine crime transaction; guards react to that transaction; stolen property has provenance; criminal merchants understand karma and enforcement risk; an outlaw’s status comes from one legal authority; bounty quests observe that authority rather than recreating it; and restraint/threat states become visually and behaviorally obvious in MCA villagers. Implemented that way, 0.5.1 is not merely a content patch—it establishes a reusable architecture for later robbery, burglary, kidnapping, smuggling, organized crime, informants, corruption, bounty capture, and other NPC-driven crime systems without requiring another rewrite.
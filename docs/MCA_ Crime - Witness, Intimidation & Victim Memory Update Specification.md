# MCA: Crime - Witness, Intimidation & Victim Memory Update Specification

## 1. Overview

This update should substantially deepen the simulation surrounding crimes committed against MCA villagers without turning MCA: Crime into an excessively complex policing or forensic system.

The update should focus on three closely connected pillars:

1. **Witness-based crime awareness and reporting**
2. **Dynamic intimidation and victim compliance**
3. **Persistent victim memory and post-crime reactions**

Together, these systems should make crimes feel like events that happen to actual people within a settlement rather than abstract triggers that immediately modify a bounty value.

A villager should need to perceive a crime before acting as a witness. A threatened villager should react according to their circumstances and personality rather than universally freezing. A victim who survives a crime should remember what happened and behave differently toward the perpetrator afterward.

These systems should integrate cleanly with existing MCA: Crime functionality and provide optional hooks for MCA Reborn, MCA: Conversations, MCA: Reputation, MCA: Quests, MCA Capitals, and other compatible addons without making any of them mandatory.

---

# 2. Core Design Principles

## 2.1 Crimes Should Be Perceived, Not Broadcast

Crime information should no longer behave as though every nearby villager or authority automatically knows everything that happens.

Information should originate from:

- the victim;
- direct witnesses;
- nearby villagers who hear sufficiently obvious events;
- villagers directly informed by another witness;
- existing explicit MCA: Crime mechanisms that intentionally propagate criminal information.

This distinction is essential.

A crime occurring behind a closed door should behave differently from the same crime occurring in the middle of a crowded village square.

---

## 2.2 Villagers Should Behave Like Individuals

NPC reactions should depend on existing MCA properties wherever possible.

Relevant considerations may include:

- personality;
- profession;
- current relationship toward the player;
- family relationship to the victim;
- health;
- equipment;
- combat capability;
- nearby allies;
- nearby guards;
- current activity;
- previous experiences with the perpetrator;
- severity of the current threat.

Avoid assigning every villager an identical response profile.

---

## 2.3 Systems Must Remain Understandable

Depth should not come at the expense of predictability.

The player should generally be able to understand why an NPC:

- saw them;
- became frightened;
- resisted;
- ran for help;
- refused conversation afterward;
- reported them;
- continued holding a grudge.

Avoid opaque random behavior.

Randomness may modify decisions, but decisions should primarily emerge from comprehensible conditions.

---

# 3. Witness and Awareness System

## 3.1 Objective

Replace simplistic proximity-based or global crime detection with a reusable perception model determining which MCA villagers actually witnessed a criminal event.

This should become a foundational service that future crime types can use.

Suggested conceptual architecture:

```text
Crime occurs
    ↓
CrimeEvent created
    ↓
Potential observers collected
    ↓
Perception evaluated
    ↓
Witness records created
    ↓
Immediate witness reactions
    ↓
Reporting / gossip / guard notification
    ↓
Crime consequences
```

---

# 4. Crime Event Representation

Introduce or formalize an internal crime event representation.

Example conceptual structure:

```java
CrimeEvent {
    UUID eventId;
    CrimeType type;

    UUID perpetrator;
    @Nullable UUID victim;

    BlockPos location;
    ResourceKey<Level> dimension;

    long gameTime;

    float severity;

    boolean violent;
    boolean audible;
    boolean ongoing;

    Set<UUID> directWitnesses;
}
```

Do not treat this exact structure as mandatory if equivalent infrastructure already exists.

The important goal is to centralize enough information that witnesses, victims, dialogue systems, reporting logic, and memory systems can reference the same event coherently.

---

# 5. Witness Candidate Collection

When a crime begins or reaches a reportable state, search for nearby MCA villagers.

The search radius should depend on the crime.

Examples:

| Crime type | Typical visual radius | Typical sound radius |
|---|---:|---:|
| Pickpocket | 6-10 blocks | 0-2 blocks |
| Theft from container | 8-12 blocks | 2-5 blocks |
| Threatening | 10-16 blocks | 8-14 blocks |
| Assault | 16-24 blocks | 16-24 blocks |
| Murder | 20-32 blocks | 24-40 blocks |
| Kidnapping | contextual | contextual |
| Restraint | 10-16 blocks | 4-8 blocks |
| Mugging | 12-20 blocks | 12-20 blocks |

All values should be configurable or data-driven where practical.

Avoid scanning the entire settlement or dimension.

Use spatial queries bounded tightly enough to remain inexpensive.

---

# 6. Visual Perception

## 6.1 Line of Sight

A villager should not become a visual witness merely because they are within radius.

Check whether the villager can reasonably see:

- the perpetrator;
- the victim;
- or the criminal act itself.

Use Minecraft ray tracing or equivalent visibility checks.

Doors, walls, terrain, and large obstacles should prevent direct visual confirmation.

---

## 6.2 Facing and Awareness

Optional but recommended:

Reduce detection likelihood when the crime occurs clearly behind the observer.

Do not require an extremely strict field-of-view cone because Minecraft NPC movement can make this frustrating.

Instead use a forgiving model:

```text
Facing event:
    full visual awareness

Peripheral:
    slightly reduced awareness

Directly behind:
    significantly reduced awareness unless crime is loud
```

---

## 6.3 Visibility Modifiers

Account for relevant conditions.

Possible modifiers:

- darkness;
- invisibility;
- blindness;
- smoke or heavy particle effects;
- concealment;
- distance;
- weather;
- sneaking;
- disguised identity where compatible systems exist.

These should primarily affect **identification**, not necessarily awareness that a crime happened.

Example:

```text
Witness sees somebody attack a villager at night from 25 blocks away.

Crime witnessed: YES
Perpetrator confidently identified: NO
```

This distinction is valuable for future expansion.

---

# 7. Auditory Awareness

Some crimes should be detectable even without line of sight.

Examples:

- attacks;
- explosions;
- shouting;
- victims screaming;
- breaking doors;
- forceful restraints;
- gunshots from compatible weapon mods.

A witness hearing a crime should know that **something happened**, but should not automatically know the perpetrator unless they subsequently see them.

Example states:

```text
HEARD_INCIDENT
SAW_INCIDENT
IDENTIFIED_PERPETRATOR
```

Do not over-engineer this into a full acoustic simulation.

Distance plus basic obstruction attenuation is sufficient.

---

# 8. Witness Confidence

Each witness should receive an identification confidence value or equivalent classification.

Suggested tiers:

### Confirmed

The witness clearly saw the perpetrator commit the act.

### Probable

The witness saw enough contextual evidence to strongly suspect them.

### Uncertain

The witness saw suspicious activity but cannot reliably identify the perpetrator.

### Unknown

The witness knows a crime occurred but has no credible suspect.

Internally this may be represented numerically.

Example:

```text
0.00 - 0.24 = Unknown
0.25 - 0.49 = Uncertain
0.50 - 0.79 = Probable
0.80 - 1.00 = Confirmed
```

Do not require these exact thresholds.

---

# 9. Witness Reactions

Witnesses should react immediately according to their circumstances.

Possible reactions include:

- flee;
- seek a guard;
- alert nearby villagers;
- scream;
- confront the perpetrator;
- protect the victim;
- remain frozen in fear;
- retreat indoors;
- ignore the incident;
- observe from a distance.

Reaction selection should consider:

```text
personality
profession
combat capability
current health
relationship to victim
relationship to perpetrator
nearby allies
nearby guards
crime severity
threat level
```

---

# 10. Guard Reporting

Witnesses should not universally transmit crimes telepathically to guards.

When appropriate, witnesses should attempt to report.

Preferred behaviors:

1. Witness identifies crime.
2. Witness determines whether reporting is appropriate.
3. Witness locates a nearby guard.
4. Witness moves toward the guard.
5. Witness reaches report range.
6. Guard receives the crime information.
7. Existing MCA: Crime law-enforcement behavior begins.

For performance and pathfinding reliability, reporting should not depend on excessively long physical travel.

If no guard is reasonably accessible, allow alternative mechanisms such as:

- reporting when entering a settlement center;
- delayed settlement-level notification;
- dialogue with a guard encountered later.

---

# 11. Witness Personality Effects

Use MCA personality where available.

Illustrative behavior:

### Brave

More likely to:

- confront;
- assist victim;
- resist intimidation;
- immediately report.

### Timid

More likely to:

- flee;
- hide;
- comply with threats;
- hesitate before reporting.

### Aggressive

More likely to:

- attack perpetrator;
- escalate confrontations.

### Sensitive or compassionate personalities

More likely to:

- help victims;
- react strongly to violence.

### Criminal-aligned villagers

Future-compatible criminal professions such as thieves or fences may:

- ignore minor crimes;
- avoid reporting;
- react selectively.

Do not hardcode personality assumptions if MCA already exposes more suitable personality traits or APIs.

---

# 12. Relationship-Based Witness Reactions

Relationship to the victim should strongly influence behavior.

Priority examples:

```text
Spouse > child/parent > close family > close friend > acquaintance > stranger
```

A villager who witnesses harm against their spouse should behave differently from one witnessing theft from a stranger.

Possible effects include:

- higher intervention probability;
- stronger anger;
- faster guard reporting;
- stronger post-crime memory;
- greater reputation impact.

---

# 13. Witness Memory

Witnesses should retain a lightweight record of serious incidents.

Do not persist every petty event forever.

Recommended stored information:

```text
WitnessMemory {
    CrimeType crimeType;
    UUID perpetrator;
    @Nullable UUID victim;

    long timestamp;

    float identificationConfidence;
    float emotionalImpact;
}
```

Expiration should depend on crime severity.

Minor crimes may fade quickly.

Violent crimes may persist significantly longer.

---

# 14. Gossip and Information Spread

Witness information may spread gradually through social interactions.

This should be restrained to prevent universal hive-mind knowledge.

Possible propagation pathways:

```text
Witness → spouse
Witness → family
Witness → nearby friend
Witness → guard
Witness → compatible Conversations dialogue
```

Do not automatically distribute every crime to every village resident.

Each propagation should reduce certainty.

Example:

```text
Direct witness confidence: 1.0
Friend hears account: 0.7
Friend repeats story: 0.45
```

This can support rumors without equating rumor with direct testimony.

---

# 15. Intimidation and Compliance System

## 15.1 Objective

Replace binary weapon-point behavior with a dynamic threat evaluation.

A threatened MCA villager should continuously determine:

```text
How dangerous is the player?
Can I escape?
Can I fight?
Is help nearby?
Do I care enough to resist?
What has this player already done?
```

The system should support multiple responses rather than simply freezing every civilian.

---

# 16. Threat Evaluation

Create a reusable threat score.

Conceptually:

```text
Threat Score =
    weapon threat
  + player proximity
  + recent violence
  + victim health disadvantage
  + perpetrator reputation/fear
  + allies disadvantage
  + previous victimization
  - nearby guard confidence
  - victim combat capability
  - numerical advantage
```

The score should then feed into reaction selection.

---

# 17. Weapon Threat

Different weapons should carry different intimidation values.

Examples:

### High threat

- drawn bow;
- loaded crossbow;
- firearm from supported mods;
- powerful magic weapon;
- weapon currently causing severe damage.

### Medium threat

- sword;
- axe;
- spear;
- mace;
- other melee weapons.

### Lower contextual threat

- improvised tools;
- weak weapons;
- distant melee weapons.

Reuse MCA: Crime's weapon classification infrastructure wherever possible.

Do not create duplicate competing classification systems.

---

# 18. Distance and Position

Threat depends heavily on range.

A sword held 12 blocks away should not immobilize a villager.

A loaded crossbow aimed from the same distance should remain threatening.

Introduce per-weapon or per-category effective intimidation range.

Example:

```text
Melee:
0-3 blocks = maximum threat
4-6 blocks = reduced
7+ blocks = negligible

Ranged:
0-12 blocks = maximum
13-24 blocks = strong
25+ blocks = progressively reduced
```

Values should remain configurable.

---

# 19. Threatened Villager Response States

Support the following primary outcomes.

## 19.1 Comply

The villager obeys.

Possible presentation:

- stop moving;
- back away slowly;
- lower hands;
- surrender valuables;
- allow restraint;
- follow simple commands.

---

## 19.2 Panic

The villager reacts irrationally.

Possible behavior:

- scream;
- run;
- stumble between destinations;
- flee toward a building;
- unintentionally attract witnesses.

---

## 19.3 Stall

The villager pretends to cooperate while waiting for conditions to improve.

Possible behavior:

- delayed surrender;
- slow movement;
- looking toward exits;
- attempting escape when the player turns away;
- seeking nearby guards once line of sight breaks.

---

## 19.4 Resist

The villager decides they are capable of fighting back.

Likely candidates:

- guards;
- archers;
- armed professions;
- heavily equipped villagers;
- aggressive/brave personalities;
- villagers with nearby allies.

---

## 19.5 Defy

The villager refuses without immediately fighting.

Especially appropriate when:

- protecting family;
- refusing to abandon a child;
- defending spouse;
- refusing to surrender a loved one;
- protecting important property.

---

## 19.6 Plead or Negotiate

Victims may attempt dialogue.

Examples:

```text
"Take what you want. Just don't hurt anyone."

"Please, leave my family out of this."

"I'll give you what I have."

"You don't need to do this."

"Please put the weapon away."
```

Prefer contextual dialogue generated through data files or conversation pools rather than hardcoded strings.

---

# 20. Dynamic Reevaluation

Compliance should not be decided once and locked permanently.

A victim should periodically reconsider.

Examples:

### Guard arrives

```text
COMPLY → ESCAPE
COMPLY → RESIST
STALL → CALL_FOR_HELP
```

### Player looks away

```text
STALL → FLEE
```

### Player attacks someone

```text
UNCERTAIN → PANIC
COMPLY → EXTREME_COMPLIANCE
```

### Player puts weapon away

```text
COMPLY → CAUTIOUS
PANIC → FLEE
```

### Victim acquires numerical advantage

```text
COMPLY → RESIST
```

Use sensible cooldowns to prevent rapid state oscillation.

---

# 21. Weapon Aim Detection

For ranged intimidation, distinguish between merely holding a weapon and actively threatening someone.

Examples:

- bow drawn;
- crossbow loaded and raised;
- gun aiming state where compatible;
- magic spell charging;
- melee attacker within close range and facing victim.

Do not make every villager within sight panic simply because the player possesses a sword.

---

# 22. Commands During Intimidation

Where compatible with existing MCA: Crime interfaces, intimidation should enable contextual commands such as:

```text
Stay there
Back away
Give me your valuables
Follow me
Get on the ground
Don't call for help
Let them go
```

Only implement commands that fit the current scope and mechanics.

The system should be extensible so more crime interactions can later reuse the same framework.

---

# 23. Hostage and Family Dynamics

Victims should react especially strongly when loved ones are involved.

Examples:

- threatening a child can cause a parent to comply;
- threatening a spouse may cause surrender;
- harming a family member may trigger otherwise timid villagers to attack;
- a villager may attempt to trade themselves for a loved one;
- guards may hesitate to use risky attacks when a hostage is immediately adjacent.

Do not create complex hostage negotiation AI in this update.

The purpose is contextual reaction, not a standalone hostage simulator.

---

# 24. Animation and Body Language

Where technically practical, intimidation states should have visual feedback.

Potential poses:

### Compliant

- hands raised;
- arms partially elevated;
- restrained stance;
- reduced head movement.

### Frightened

- backing away;
- looking between player and exits;
- trembling or subtle idle variation.

### Defiant

- normal stance;
- facing player;
- weapon ready if armed.

Animations should gracefully degrade if MCA model hooks make particular poses difficult.

Gameplay behavior is higher priority than elaborate animation.

---

# 25. Victim Memory System

## 25.1 Objective

A villager who personally experiences a crime should remember it.

This memory should affect future interactions with the perpetrator.

Victim memory should persist across:

- chunks unloading;
- server restart;
- player logout;
- villager movement;
- reasonable amounts of game time.

---

# 26. Crime Memory Data

Suggested structure:

```java
VictimCrimeMemory {
    UUID perpetrator;
    CrimeType crimeType;

    long timestamp;

    float severity;
    float fear;
    float anger;
    float trauma;

    boolean playerApologized;
    boolean restitutionPaid;
    boolean sentenceServed;

    int repeatCount;
}
```

Do not necessarily expose "trauma" directly to players.

It is an internal behavioral metric.

---

# 27. Memory Categories

Victims should distinguish between materially different experiences.

Suggested categories:

```text
THEFT
ROBBERY
THREAT
ASSAULT
KIDNAPPING
RESTRAINT
EXTORTION
MURDER_ATTEMPT
FAMILY_HARM
PROPERTY_CRIME
```

Map existing MCA: Crime offenses into these higher-level behavioral memory categories.

---

# 28. Emotional Dimensions

Instead of one generic hostility value, store at least two useful behavioral dimensions:

## Fear

Influences:

- avoidance;
- fleeing;
- refusing private conversations;
- seeking guards;
- future compliance.

## Anger

Influences:

- confrontation;
- refusal to trade;
- hostile dialogue;
- resistance;
- gossip.

Optional third dimension:

## Trust Damage

Influences normal MCA relationship interactions.

This can be derived rather than stored separately if existing MCA relationship systems already support suitable values.

---

# 29. Post-Crime Behavior

When the victim next encounters the perpetrator, behavior should reflect their memory.

Possible responses:

- stepping backward;
- terminating casual conversation;
- refusing trade;
- refusing romance interactions;
- avoiding being alone with the player;
- searching for guards;
- refusing to follow;
- warning relatives;
- angry confrontation;
- frightened dialogue;
- refusing gifts temporarily;
- reduced willingness to accept assistance.

Avoid completely breaking MCA's interaction system.

Use contextual modifiers rather than permanently disabling all interactions.

---

# 30. Contextual Dialogue

Provide dialogue hooks for MCA: Conversations when installed.

Examples of memory-sensitive lines:

### Recent robbery

```text
"You already took enough from me."
```

### Threatened previously

```text
"Keep your hands where I can see them."
```

### Assault

```text
"Stay away from me."
```

### Family victimization

```text
"I haven't forgotten what you did to my family."
```

### After restitution

```text
"Returning what you took doesn't erase it, but it matters."
```

### After sufficient reconciliation

```text
"I still remember what happened. But I'm trying to move past it."
```

MCA: Crime should expose enough context that MCA: Conversations can select dialogue without hard dependencies.

---

# 31. Family Memory Effects

Crimes against close relatives should create secondary social consequences.

Example:

```text
Player attacks villager A.

A receives:
100% victim memory

A's spouse receives:
strong indirect memory

A's child receives:
moderate indirect memory

Close friends receive:
smaller indirect memory if informed
```

Do not give family members the exact same memory object as the direct victim.

Use a reduced indirect-memory severity.

---

# 32. Repeat Offenses

Repeatedly victimizing the same villager should matter.

Each repeat offense should strengthen relevant reactions.

Suggested progression:

### First incident

Fear or anger.

### Second incident

Substantially reduced trust and stronger avoidance.

### Repeated abuse

Strong refusal to interact, immediate attempts to seek assistance, and potentially persistent hostility.

Use diminishing caps to prevent numeric overflow or permanent impossible-to-repair relationships.

---

# 33. Memory Decay

Memories should soften over time, but severe crimes should persist much longer than minor ones.

Suggested conceptual rates:

| Crime | Memory duration |
|---|---|
| Minor theft | days |
| Threat | several days |
| Robbery | longer |
| Assault | long |
| Kidnapping | very long |
| Attempted murder | extremely long |
| Harm to close family | extremely long |

Do not necessarily delete old memories immediately.

Instead allow emotional values to decay gradually.

Example:

```text
fear *= decay
anger *= decay
```

Severe memories may retain a minimum residual value.

---

# 34. Reconciliation and Restitution

The system should allow some relationships to recover.

Potential mitigating actions:

- returning stolen goods;
- paying restitution;
- serving a jail sentence;
- completing an appropriate quest;
- apologizing;
- significant passage of time;
- saving the victim later;
- positive relationship interactions.

Avoid making forgiveness automatic.

Personality and severity should affect recovery.

---

# 35. Apology Interaction

Consider adding a contextual apology option where technically appropriate.

Possible requirements:

- player is not currently threatening the villager;
- sufficiently low active hostility;
- crime memory exists;
- cooldown prevents spam.

Possible results:

```text
Rejected
Acknowledged
Partially accepted
Accepted
```

Acceptance should depend on:

- severity;
- time passed;
- relationship;
- restitution;
- repeat offenses;
- personality.

Apology should never completely erase serious crimes by itself.

---

# 36. Restitution

For theft and robbery, allow returned items or equivalent payment to reduce anger.

Where exact stolen-item tracking already exists, use it.

Otherwise use a simplified monetary or value-based settlement.

Avoid implementing the previously rejected forensic/evidence system merely to support restitution.

---

# 37. Interaction With Existing MCA Relationship Values

Do not create an entirely isolated relationship system.

Crime memory should influence existing MCA mechanics such as:

- hearts;
- mood;
- interaction willingness;
- marriage behavior;
- family interactions;
- trading;
- follow behavior.

However, avoid permanently modifying core MCA data in ways that cannot be reversed.

Prefer calculated modifiers.

Example:

```java
effectiveInteractionChance =
    baseInteractionChance
    * crimeMemoryModifier;
```

---

# 38. MCA: Reputation Integration

When MCA: Reputation is present, expose crime consequences through its APIs rather than duplicating reputation logic.

Potential integration:

```text
Direct victim:
large personal reputation impact

Close family:
moderate impact

Witness:
small to moderate impact

General village:
only if information actually spreads
```

Crime memory and reputation should complement one another.

They are not identical.

A villager may:

```text
know the player has a bad reputation
```

without personally fearing them.

Likewise, a victim may personally fear the player even if the player's broader reputation remains acceptable.

---

# 39. MCA: Conversations Integration

Expose the following contextual information where feasible:

```text
hasBeenVictimOf(player, crimeType)
lastCrimeBy(player)
fearOf(player)
angerAt(player)
crimeRepeatCount(player)
witnessedCrimeBy(player)
familyWasVictimOf(player)
restitutionStatus(player)
```

This allows conversation packs to define contextual dialogue without coupling conversation logic directly into MCA: Crime internals.

---

# 40. MCA: Quests Integration

Provide hooks enabling quests such as:

- reconcile with a victim;
- pay restitution;
- escort frightened witness;
- convince a witness to testify;
- help a crime victim;
- seek forgiveness from a family;
- protect a witness.

Do not require any of these quests to ship in this update unless desired.

The integration API is the key requirement.

---

# 41. MCA Capitals Integration

Where MCA Capitals is installed, witness reporting should optionally interact with settlement-level jurisdiction.

Potential future hooks:

```text
report crime to local authority
identify settlement where crime occurred
associate victim/witness with jurisdiction
allow capital guards to know crimes formally reported to their authority
```

Do not turn this into a global wanted-level system unless MCA Capitals explicitly provides such functionality.

Keep crime awareness grounded in actual propagation.

---

# 42. Persistence

Persistent memory should use stable UUID references.

Persist:

- perpetrator UUID;
- victim UUID where appropriate;
- crime category;
- timestamp;
- emotional values;
- repeat counts;
- reconciliation state.

Do not retain live entity references.

Handle deleted players and villagers safely.

Data migration must tolerate missing or malformed entries.

---

# 43. Save Data Limits

Memory must be bounded.

Recommended mechanisms:

- maximum memories per villager;
- merge repeated crimes by perpetrator/category;
- decay or delete insignificant memories;
- preserve high-severity memories preferentially.

Example:

```text
Maximum direct memories: 16-32

Repeated same-category incidents:
merge rather than append endlessly
```

---

# 44. Performance Requirements

The new systems must remain efficient in large villages.

Avoid:

- scanning every MCA villager every tick;
- ray tracing every NPC continuously;
- repeated full-world guard searches;
- saving large crime histories;
- synchronous expensive path queries.

Use event-driven evaluation.

Example:

```text
Crime begins:
evaluate potential witnesses once.

Crime continues:
recheck only periodically or when relevant conditions change.

Intimidation:
evaluate nearby target at moderate intervals.

Memory:
calculate behavior modifiers when interaction occurs.
```

---

# 45. Recommended Tick Rates

Example only:

```text
Witness discovery:
on event creation

Ongoing intimidation reevaluation:
every 10-20 ticks

Escape opportunity:
every 10 ticks

Nearby guard confidence:
cached/rechecked every 20-40 ticks

Memory decay:
once per Minecraft day or when loaded
```

Tune through profiling.

---

# 46. Configuration

Add a dedicated configuration group.

Example:

```toml
[crime_awareness]
enableWitnessSystem = true
visualWitnessRadiusMultiplier = 1.0
auditoryWitnessRadiusMultiplier = 1.0
enableLineOfSightChecks = true
enableWitnessGossip = true
witnessMemoryDurationMultiplier = 1.0

[intimidation]
enableDynamicCompliance = true
enablePanic = true
enableStalling = true
enableResistance = true
enablePleading = true
threatReevaluationTicks = 10
meleeThreatRange = 4.0
rangedThreatRange = 24.0

[victim_memory]
enableVictimMemory = true
enableFamilyMemory = true
memoryDecayMultiplier = 1.0
enableRestitution = true
enableApologies = true
maximumMemoriesPerVillager = 24
```

Names should follow the existing project's configuration conventions.

---

# 47. Datapack Support

Where practical, move tunable behavior into data.

Potential datapack-defined data:

```text
crime severity
witness radius
sound radius
base intimidation value
memory severity
memory duration
dialogue tags
reportability
```

This allows modpack authors to integrate custom crime types without modifying Java code.

---

# 48. API and Extensibility

Expose clean APIs or events for:

```java
CrimeCreatedEvent
CrimeWitnessedEvent
CrimeReportedEvent
VictimThreatenedEvent
VillagerComplianceEvent
VictimCrimeMemoryCreatedEvent
VictimCrimeMemoryChangedEvent
VictimReconciledEvent
```

Other mods should be able to observe these systems without depending on internal implementation classes.

---

# 49. Compatibility

The update must degrade safely when optional integrations are absent.

Never reference optional mod classes during common class initialization.

Use:

- mod-presence checks;
- isolated compatibility modules;
- reflective or event-based adapters where appropriate;
- soft dependencies only.

The base MCA: Crime experience must work with only required dependencies installed.

---

# 50. Multiplayer

All authoritative decisions should occur server-side.

The server should determine:

- who witnessed a crime;
- threat state;
- compliance reaction;
- victim memory;
- reporting;
- restitution state.

The client should receive only synchronization needed for:

- animation;
- UI;
- particles;
- dialogue;
- visible NPC state.

Never trust client-side witness or intimidation claims.

---

# 51. Network Synchronization

Do not continuously transmit entire memory objects.

Send only relevant state.

Examples:

```text
villager currently frightened
villager currently compliant
villager currently fleeing
villager remembers local player
active intimidation animation state
```

Detailed memory values should remain server-side unless explicitly required by a GUI.

---

# 52. UI Feedback

Avoid turning these mechanics into visible RPG meters by default.

The player should infer state primarily through behavior.

Useful subtle feedback could include:

- MCA dialogue;
- animations;
- refusal messages;
- frightened sounds;
- contextual tooltips;
- subtitles.

Optional debug commands can expose internal values.

---

# 53. Debugging Tools

Add developer-focused commands.

Examples:

```text
/mcacrime debug witness <villager>

/mcacrime debug threat <villager>

/mcacrime debug memory <villager>

/mcacrime debug crimeevents

/mcacrime clear memory <villager> <player>
```

Outputs should show relevant factors.

Example:

```text
Threat evaluation for Villager UUID...

Weapon: iron_sword +30
Distance: 2.1 +25
Recent assault: +20
Nearby guards: -15
Brave personality: -12

Final threat: 48
Selected response: STALL
```

This will be extremely valuable when tuning complex emergent behavior.

---

# 54. Logging

Use debug-level logs for:

- crime event creation;
- witness identification;
- reporting;
- compliance changes;
- memory creation;
- memory decay;
- compatibility failures.

Avoid log spam in normal configurations.

---

# 55. Test Coverage

## Witness Tests

Verify:

- visible villager becomes witness;
- villager behind solid wall does not visually witness;
- loud crime can still be heard;
- distant crime is ignored;
- witness does not automatically notify guards;
- witness can reach guard and report;
- low identification confidence behaves correctly;
- witness gossip does not become instant global knowledge.

---

## Intimidation Tests

Verify:

- unarmed player does not trigger threat state;
- melee weapon outside practical range does not force compliance;
- ranged weapon remains threatening at appropriate distance;
- civilian can comply;
- civilian can panic;
- civilian can stall;
- armed villager can resist;
- guard arriving can alter response;
- player lowering weapon reduces threat;
- victim does not rapidly oscillate between states.

---

## Victim Memory Tests

Verify:

- victim remembers attacker after reload;
- memory persists across server restart;
- theft memory differs from assault memory;
- repeated offenses increase reaction;
- memory decays appropriately;
- restitution reduces relevant values;
- apology does not erase severe crime;
- family receives reduced indirect consequences;
- unrelated villagers do not automatically receive victim memory.

---

# 56. Edge Cases

Handle:

### Victim dies

Witness memory remains valid.

Victim memory obviously does not need further behavioral processing.

Family memory may still be generated.

### Perpetrator dies

Memory may remain associated with UUID.

NPC should not continuously search for an absent player.

### Player changes dimension

Active intimidation immediately ends.

### Villager unloads

State persists where necessary.

Temporary active-AI state should restore safely on reload.

### Weapon breaks

Threat should immediately or rapidly reevaluate.

### Player switches item

Threat should reevaluate.

### Villager becomes armed

Compliance may change.

### Guard becomes unavailable

Witness should abandon impossible reporting behavior after a timeout.

### Villager trapped while fleeing

Pathfinding failure should not cause endless expensive recalculation.

---

# 57. AI Priority and Goal Management

Avoid permanently injecting competing AI goals.

Threat-response goals should have explicit priority and lifecycle.

Suggested hierarchy:

```text
Immediate survival
↓
Intimidation response
↓
Crime reporting
↓
Existing combat behavior
↓
Normal MCA activity
```

Exact ordering should account for MCA Reborn's actual AI architecture.

Ensure normal behavior resumes cleanly when the crime state ends.

---

# 58. State Machine Recommendation

Use explicit state machines rather than collections of unrelated booleans.

Example:

```java
enum ThreatResponse {
    NONE,
    CAUTIOUS,
    COMPLY,
    PANIC,
    STALL,
    RESIST,
    DEFY,
    FLEE
}
```

Likewise:

```java
enum WitnessReaction {
    NONE,
    OBSERVE,
    FLEE,
    REPORT,
    INTERVENE,
    HIDE
}
```

This will greatly reduce contradictory states.

---

# 59. Suggested Package Structure

Adapt to the existing project.

Example:

```text
crime/
    CrimeEvent.java
    CrimeEventManager.java

awareness/
    WitnessEvaluator.java
    WitnessRecord.java
    WitnessReactionManager.java
    CrimeReportingService.java

intimidation/
    ThreatEvaluator.java
    ThreatContext.java
    ThreatResponse.java
    IntimidationManager.java

memory/
    VictimCrimeMemory.java
    CrimeMemoryManager.java
    CrimeMemoryPersistence.java

compat/
    conversations/
    reputation/
    quests/
    capitals/

api/
    event/
    crime/
    memory/

client/
    animation/
    hud/
```

---

# 60. Implementation Sequence

## Phase 1: Foundation

Implement:

- normalized crime-event representation;
- reusable villager lookup;
- crime severity metadata;
- shared compatibility hooks.

---

## Phase 2: Witness System

Implement:

- candidate scanning;
- LOS;
- auditory detection;
- confidence;
- reaction states;
- guard reporting.

Validate thoroughly before enabling gossip.

---

## Phase 3: Intimidation

Refactor existing weapon-point logic into:

```text
ThreatContext
ThreatEvaluator
ThreatResponse
```

Then introduce:

- compliance;
- panic;
- stalling;
- resistance;
- defiance;
- dynamic reevaluation.

---

## Phase 4: Victim Memory

Implement:

- persistence;
- fear;
- anger;
- repeat count;
- decay;
- interaction modifiers.

---

## Phase 5: Social Propagation

Add:

- family consequences;
- witness memory;
- limited gossip;
- reporting propagation.

Keep this tightly bounded.

---

## Phase 6: Integrations

Implement optional compatibility for:

- MCA: Conversations;
- MCA: Reputation;
- MCA: Quests;
- MCA Capitals.

---

## Phase 7: Presentation

Add:

- dialogue;
- animations where feasible;
- subtle feedback;
- debug commands.

---

## Phase 8: Optimization

Profile:

- large villages;
- simultaneous crimes;
- multiplayer;
- repeated weapon-point reevaluation;
- save size;
- pathfinding.

Optimize any hot paths before release.

---

# 61. Acceptance Criteria

The update should not be considered complete until all of the following are true.

### Witnessing

- A villager behind a wall does not automatically know who committed a quiet crime.
- A nearby villager with clear visibility can witness the crime.
- Loud crimes can attract villagers without direct LOS.
- Witnesses do not magically transmit perfect information to the entire village.
- Witnesses can meaningfully react or report.

### Intimidation

- Weapon-point victims are not universally frozen.
- Victims evaluate threat dynamically.
- Personality and circumstances affect decisions.
- Armed villagers may resist.
- Civilians may panic, comply, stall, or defy.
- Lowering the weapon can alter behavior.
- Family circumstances influence decisions.

### Memory

- Direct victims remember the player.
- Repeated victimization strengthens reactions.
- Serious offenses remain meaningful for a long period.
- Memories influence future MCA interactions.
- Family can react to crimes against loved ones.
- Memories can soften through time and reconciliation.
- Apology alone cannot trivialize severe crimes.

### Technical

- All state is server-authoritative.
- Persistent data survives restarts.
- No significant tick-time regression occurs in normal villages.
- Optional compatibility mods remain optional.
- Old saves load without requiring manual conversion.

---

# 62. Non-Goals

Explicitly avoid expanding this update into systems that were not selected.

Do **not** implement:

- forensic evidence collection;
- fingerprints;
- blood-trail investigation mechanics;
- spent casing tracking;
- complex detective gameplay;
- universal escalating law-enforcement tiers;
- GTA-style wanted levels;
- global settlement lockdowns;
- automatic nationwide bounty propagation.

Those concepts can be considered separately in future releases.

---

# 63. Desired Player Experience

A successful implementation should create scenarios such as:

> The player quietly threatens a villager inside their home. The frightened villager initially complies, but notices their armed spouse returning through the doorway and suddenly attempts to flee. A neighbor hears the commotion but never gets a clear look at the player. The victim survives and remembers the incident. Days later, the victim still backs away when approached, refuses friendly conversation, and warns their spouse. The neighbor knows that something happened but cannot confidently identify who was responsible.

Or:

> The player attempts to rob a villager in a busy marketplace. Several villagers clearly witness it. One panics and flees, another runs toward a guard, while the victim stalls instead of surrendering immediately. Because multiple villagers actually saw the perpetrator, identification is reliable and consequences follow naturally.

Or:

> A player who repeatedly targets the same family discovers that the victims no longer respond as generic NPCs. One villager immediately runs for help, another refuses to speak to the player, while an otherwise timid parent becomes unusually defiant when their child is threatened.

These emergent situations should be the core success metric.

The objective is not merely to add more punishment for crime.

The objective is to make MCA villagers behave as though they **saw, experienced, remembered, and emotionally reacted to what the player did**.
# MCA: Crime — Thief Reports, Village News, and World Rules

Implementation specification for a coding agent · 23 September 2026

**Repository:** [otectus/MCACrime](https://github.com/otectus/MCACrime)  
**Inspected baseline:** `main` at [`2ef8081517448a21eb6ee0d2d9098fdef5f45f2a`](https://github.com/otectus/MCACrime/tree/2ef8081517448a21eb6ee0d2d9098fdef5f45f2a)  
**Baseline platform:** Minecraft 1.20.1, Forge 47.4.10, Java 17; mod version 0.7.4; world-data schema 14.  
**Deliverable:** Implement the features below in the next appropriate release. Recheck the branch and instructions before editing; do not assume the inspected commit remains current.

This document is based on source inspection, including MCA and MCA: Reputation. It is an implementation plan, not a claim that the changes exist, compile, or have been tested in Minecraft. Proposed classes, APIs, configuration keys, and commands are identified as proposals. Upstream source names and mappings must be checked against the actual supported runtime jars.

## 1. Intended result

Make thieves feel like participants in the village's justice system, give players a useful peaceful response to theft, turn MCA's existing mailbox into a source of relevant local news, and expose selected gameplay settings as genuine per-world game rules.

The user requests four connected outcomes:

1. Players can report thieves to guards through an understandable interaction.
2. Thieves can be fought and killed without reputation loss under the chosen world policy.
3. `/mca mail` delivers useful news about actual events in the world.
4. Players can configure selected MCA: Crime settings before world creation and change them while playing with the normal permissions required for `/gamerule`.

Complete the player journeys, including persistence, server validation, integrations, feedback, and failure states. A button with no effective guard response, a letter sent directly to inventory, a setting exposed but never read, or a reputation exemption that only suppresses one mod's penalty does not satisfy the request.

### 1.1 Recommended product decisions

These are deliberate defaults for this implementation, rather than claims about current behavior.

| Decision | Required behavior | Reason |
|---|---|---|
| Default thief combat policy | Adult canonical Thief targets are exempt from assault/killing penalties, subject to the protected-target exceptions below. | Fulfills the stated promise without requiring players to discover a hidden reporting prerequisite. |
| Alternative combat policy | Offer a stricter mode requiring current threat, recent victim evidence, or an actionable local report. | Supports worlds that want evidence before force. |
| Reporting | Use server-recorded victim or eyewitness evidence. The profession label alone cannot create a criminal charge. | Prevents arbitrary accusations and multiplayer griefing. |
| Peaceful outcome | Guards pursue, arrest, recover stolen goods, and use the existing sentence/release system. | Makes reporting materially useful. |
| Rewards | No payment or standing gain for merely clicking Report, and no automatic reward for killing a Thief. | Prevents easy farming through assigned professions or repeated reports. |
| News | One nonempty digest per subscribed player per Minecraft day by default, combining relevant communities and personal case updates. | Makes mail useful without flooding it. |
| Configuration | Existing configs remain effective until that world explicitly enables world-rule overrides. | Preserves configured servers and existing saves. |
| Scope | Build on current incidents, observations, cases, custody, loot recovery, and compatibility boundaries. | Avoids competing authorities over the same event. |

The default combat exemption is a gameplay rule, not a finding that every Thief has committed a reportable crime. Explain that distinction in configuration help. Server owners who dislike that distinction can choose the stricter policy.

### 1.2 Scope limits

Do not introduce a separate police faction, a courtroom simulation, fabricated newspaper stories, an AI writing service, a second inventory-based newspaper delivery system, or an unrelated mod port. Do not replace the current custody system or require Townstead, MCA: Reputation, MCA: Quests, or MCA: Conversations for the core features. Preserve normal condolence letters and family death behavior.

## 2. What the code already provides

Paths below are relative to `src/main/java/dev/otectus/mcacrime/` unless otherwise specified. See the pinned source links in §13.

| Existing area | Verified baseline | Implementation consequence |
|---|---|---|
| Bootstrap/config | `McaCrime` registers COMMON and CLIENT `ForgeConfigSpec` specs. No SERVER spec is registered there. | Add world rules and an effective-settings resolver; do not simply mutate global COMMON values from a command. |
| Canonical occupation | `job/WorldCriminalJobService` persists occupations; Thief assignment goes through the occupation transaction. `isCriminal()` also includes Fence. | Test specifically for `CriminalJob.THIEF`, with live role validity. Never use `isCriminal()` as the combat exemption. |
| Thief identity | Current occupation code always presents a Thief as a Thief; the older presentation switch is retained as deprecated input. | Do not reuse `presentThiefAsMcaProfession` as a behavior or legal-status toggle. |
| NPC mugging | `mug/npc/NpcMuggingService` uses a transaction ID, an active threat phase, transactional stolen-goods accounting, and `IncidentService.commitNpc`. | Preserve the transaction ID across evidence, reporting, recovery, and news. |
| Existing observation gap | `memory/ObservationService` explicitly adds MCA villagers as direct victims. Its reporting and reaction flow is written around NPC observers. | A player victim needs an explicit evidence path. Accepting a client-supplied suspect is not a replacement. |
| Reporting | `memory/ReportService` files responder observations or delivers an NPC's observation to a nearby responder. It posts pre/post events and checks case authority. | Reuse its filing semantics, but add a player-aware entry point and remove inappropriate NPC-only checks from that route. |
| NPC law enforcement | `enforcement/NpcCriminalPursuit`, `NpcArrestEvidence`, `NpcArrestService`, and `NpcCustodyService` already handle pursuit, lawful custody, sentencing, and release. | Extend dispatch and status visibility. Do not create a second arrest implementation. |
| Recovery | `StolenGoodsLedger` and `StolenGoodsReturn` already handle ownership and recovery. Arrest invokes recovery. | Reporting must never mint replacements from a remembered item name or quantity. |
| Combat | `DamageIncidentService` reconciles damage/death finality; `CombatIncidentProcessor` deduplicates events; `CombatEncounters` distinguishes aggression and self-defense. | Preserve finality, cancellation, attribution, and reentry protections. |
| Lethal self-defense | `CombatEncounters` classifies lethal defensive force as `EXCESSIVE_DEFENSIVE_FORCE`, which is not exempt. | Add a targeted Thief policy; do not globally change every NPC or PvP self-defense rule. |
| Social penalties | `relationship/RelationshipConsequences` applies local hearts/standing; the Reputation bridge has a canonical authority/outbox path. | Suppressing only Heat or only a kill case cannot implement the full promise. |
| Public information | `api/model/CrimePublicView` filters community knowledge. Its current predicate requires witnessed/authority-known cases before consulting accepted reports. | A valid player report must be able to make a previously unwitnessed NPC case public without falsifying old witness data. |
| Persistence | `CrimeWorldData` is overworld-owned, bounded, versioned, and protected by `ServerMutationGate`; schema is currently 14. | Add an additive migration and indexes; preserve read-only handling of future schemas. |
| MCA integration | Runtime access uses `compat/McaCompat` and `compat/mca/McaBinding`, with no static MCA types. Probe versions are 7.6.20, 7.7.0-beta.2, and 7.7.1-alpha.2 for 1.20.1. | Extend binding/probe coverage and retain all package-root support. |
| Build | JUnit 5, MCA binding probes, static-link checks, optional-adapter tests, and `checkJarContents` exist. | Extend these gates. The inspected project has no GameTests; gameplay validation still matters. |

Two defaults are especially easy to misread: `npccrime.enableNpcCrime` is **false**, while `criminalJobs.thief.enableNpcMugging` is **true**. They are distinct controls. Preserve the distinction when exposing world rules.

### 2.1 MCA findings that affect the design

At upstream MCA's inspected 1.20.1 source snapshot:

- `PlayerSaveData` owns a persistent inbox and exposes `sendMail`, `sendLetter`, `hasMail`, and `getMail`.
- `sendMail` respects MCA's `enableVillagerMailingPlayers` setting and returns no acceptance result. `sendLetter` calls the notification path separately.
- Letters use a `pages` NBT list containing serialized text components. `sendLetter` inserts entries at index zero, so blindly passing multiple pages can reverse them.
- `/mca mail` drains the inbox into letter items using the existing inventory-or-drop behavior.
- `VillagerEntityMCA.damage` applies direct heart loss before delegating to normal damage processing.
- `Relationship.onTragedy` separately applies a heart penalty toward a player attacker; mourning and family-state changes are separate parts of the method.
- `VillagerEntityMCA.onDeath` also pushes stored hearts into village state.

These are confirmed source-level integration risks. They are not verified injection descriptors for the three runtime jars. Recheck the actual jars before writing mixins or reflection bindings.

## 3. Shared implementation boundaries

Use three narrow services backed by existing world data, plus one settings resolver. Names are proposed and may be adjusted to repository conventions.

| Proposed component | Responsibility | Must not do |
|---|---|---|
| `ThiefCombatPolicy` | Pure decision over target identity, custody, evidence, and world policy; returns a reasoned exemption decision. | Apply damage, repair scores, create a warrant, or dispatch guards. |
| `PlayerCrimeReportService` | Build eligible report choices, validate submission, commit reporting state, and request existing NPC enforcement. | Trust a client identity claim or charge the same case twice. |
| `CrimeNewsService` | Project committed public/private facts into bounded digests and a delivery outbox. | Read private ledger fields into public articles or cause gameplay consequences. |
| `CrimeWorldSettings` | Resolve selected settings for a specific server/world and expose their effective source. | Cache mutable values process-wide or read a multiplayer client's COMMON config as authority. |

Keep persistent truth in existing records. A report is evidence reaching an authority; a dispatch is a guard assignment; custody is an actual capture; news is a projection of these facts. Do not collapse these into one boolean such as `reportedThief=true`.

All state changes run on the logical server thread and respect `ServerMutationGate`. Emit external events only after the relevant local state is committed, retaining the repository's notification isolation and replay protections.

## 4. Thieves as reputation-safe combat targets

### 4.1 Explicit policy modes

Add a config enum with stable serialized names and map the world-rule integer to it explicitly. Do not persist enum ordinals.

| World-rule value | Config name | Meaning |
|---|---|---|
| `0` | `NORMAL_LAW` | No additional Thief exemption. Existing combat law applies. |
| `1` | `EVIDENCE_REQUIRED` | Exempt qualifying force against an eligible Thief when the actor has a valid defensive or local legal basis. |
| `2` | `ALL_THIEVES` | Exempt qualifying force against any eligible canonical Thief. **Recommended default.** |

An eligible target must be an adult, living MCA villager whose authoritative current occupation is `THIEF`, with no contradictory protected role. A display name, skin, held weapon, client cache, Fence occupation, or arbitrary entity tag is insufficient.

For both exemption modes, **do not exempt attacks against a target in custody/restraints, a child, a responder/guard, or an entity whose role cannot be reliably resolved**. This is an explicit protected-prisoner exception to the default. Do not silently extend the exemption to kidnapping, extortion, theft from the Thief, attacking the guard escort, or damage to bystanders. Non-damaging defensive disruption such as a Sand Bottle must use the same policy as ordinary nonlethal force.

`EVIDENCE_REQUIRED` accepts one of:

1. A currently valid, server-established mugging threat involving the attacker as victim, or a directly observed intervention to protect its victim.
2. Identified victim/eyewitness evidence from that robbery, within a proposed `thiefDefenseGraceTicks` of 1,200 ticks, captured before the threat session is cleared.
3. An unresolved, accepted, identified report supporting arrest in the current jurisdiction, validated through the existing justice/case machinery.

The grace period authorizes stopping the fleeing assailant, not perpetual revenge. It expires on monotonic game time and ends immediately at custody. A profession change removes the occupation-based exemption for new hits; ordinary self-defense remains independently applicable. `ALL_THIEVES` intentionally remains broader than the strict mode, including after a served sentence once the Thief is free again. State this plainly in help text.

### 4.2 Define the guarantee

For a qualifying action, do not create an assault/killing crime, Heat, negative Karma, a bounty/warrant, a local village-standing penalty, an MCA: Reputation assault/killing incident, or a permanent heart penalty blaming the player for that action. Preserve any preexisting criminal record and existing negative relationship history.

The Thief may flee or fight back. Relatives may mourn, the family tree must record a real death, a grave may be created, and normal condolence mail must continue. Exempting blame does not cancel death or make the villager friendly.

No free bounty, Karma, reputation gain, or extra loot follows from the exemption. Existing independent reward rules still require their own valid contract and deduplication. Do not turn a player-placed Mask Station into an unlimited reward generator.

### 4.3 Integrate with damage finality

1. Resolve the real actor through the existing attribution logic, including projectiles and owned tame animals. Preserve FakePlayer and self-target exclusions.
2. Capture a small immutable `ThiefCombatDecision` at the action's relevant entry point: actor UUID, target UUID, dimension, action kind, policy revision, exemption reason, and supporting case/session ID when applicable.
3. Use that same decision for both the native MCA consequence hooks and the deferred MCACrime reconciliation of that hit. Later job removal, session cleanup, or entity death must not change the meaning of an already sampled action.
4. Keep canceled/zero-effect damage and canceled deaths out of committed kill outcomes. Separate provisional consequence suppression from confirmation of death, case resolution, loot, and news.
5. Feed the explicit lawful decision into the existing combat processor rather than letting the generic `EXCESSIVE_DEFENSIVE_FORCE` branch override it. Extend the pure decision model with a reason such as `THIEF_EXEMPTION` where useful for diagnostics.
6. New attacks re-evaluate settings and target state. A decision must not authorize a subsequent hit after arrest or a different target in a sweeping/AOE attack.
7. Do not invent player attribution for unowned environmental deaths. Do not globally mark a Thief as unprotected in `EntitySelectors`, since that would bypass other systems and target distinctions.

Use an exception-safe, scoped invocation context or an equivalently precise token to bridge native callbacks. Key it by the actual actor/target/action, support nested damage, and clear it reliably. A global `ignoreReputation` flag or a ThreadLocal cleared only on a successful return is unacceptable.

### 4.4 Suppress the actual native MCA penalty sites

MCACrime's Forge event hooks alone run too late to prevent all observed native MCA penalties. Add a small, separately gated MCA compatibility mixin configuration if no adequate upstream hook exists.

This deliberately extends the current vanilla-only mixin convention. Document the extension in `CLAUDE.md` and update the static-link and mixin tests. Keep all MCA targets as strings, use the existing runtime root-resolution strategy, and isolate compatibility code. Do not import or shade MCA classes.

Probe and narrowly gate:

- The damage-path heart decrement for the responsible player.
- The tragedy-path blame decrement for the same death/actor, without suppressing mood, grief, relationship updates, or condolence letters.
- The death-path standing propagation attributable to the exempt actor. In particular, do not let spreading the deceased's stored negative hearts produce a fresh standing loss for the exempt kill; preserve existing relationships and unrelated players' propagation.
- Any vanilla villager gossip or other native standing path reached by the supported MCA builds. Inspect and test rather than assuming MCA hearts are the only system.

Do not cancel `hurt`, `die`, `onTragedy`, or every call to `modHearts`. Do not restore a whole standing/heart snapshot afterward: that can erase legitimate changes from another action in the same tick. Suppress only the causal penalty at its source.

Require verified target descriptors and injection counts for every supported jar. Missing hooks must be reported as a degraded capability, with a bounded warning and `/crime validate` detail. Required baseline jars must pass before the feature is advertised as reputation-safe.

### 4.5 MCA: Reputation ownership

The existing bridge claims only `MCA_VILLAGER_ASSAULT` and `MCA_VILLAGER_KILL`. Keep that narrow ownership and the outbox's normal delivery behavior. Do not claim rescues, cures, raids, or unrelated player kills.

With the current healthy authority handshake, Reputation's detectors stand down and an exempt action should produce no negative incident from MCACrime. Test this explicitly. Also inspect the handback states: disabled detection, disabled integration, a paused delivery pump, and a degraded adapter. The current authority API is kind-scoped, not actor/target-scoped; it cannot express a selective Thief exemption on those paths.

For full supported companion coverage, plan a **small additive MCA: Reputation compatibility change** if the implementation-time API still has this limitation:

- Introduce a capability-gated, per-incident exemption query with actor, target, dimension, damage/action context, and assault-versus-kill kind.
- The query returns only `PASS` or `EXEMPT` with a diagnostic reason. It is synchronous, cheap, read-only, and isolated from incident submission.
- Run it before Reputation's own automatic negative incident is created, independently of who owns normal incident delivery. It is a legal-exemption hook, not a claim that Crime will submit a replacement penalty.
- Register a Crime adapter only for the two villager harm/death kinds, delegating to the same exact-action policy decision. Exceptions yield `PASS` and a bounded diagnostic.
- Preserve source/binary compatibility through a new optional interface/registration or defaulted extension; advertise a new capability rather than guessing versions.

Do not solve this by leaving a blanket ownership claim enabled while its normal delivery pump is disabled. That would suppress unrelated villagers' penalties. Keep the companion patch separate and document which companion build provides the complete guarantee. Older companions retain their existing behavior outside the supported handshake; never silently label that combination fully supported.

## 5. Reporting thieves to guards

### 5.1 Player journey

1. When a player experiences a genuine robbery attempt or completed theft, retain server-authored evidence. Show a short outcome hint: **“You can report this to a guard.”** Keep the existing stolen-item/currency feedback.
2. On an available guard's existing peaceful interaction/action menu, offer **Report a crime**. Make it reachable with an empty hand and from existing MCA/Townstead dialogue entry points. It must not require drawing a weapon or enabling cheats.
3. Open a compact case selection panel using existing screen styles. List only that player's eligible evidence, newest first. Show known name or **Unidentified thief**, incident type, village/area, approximate age, and submission status.
4. Selecting a case shows the account being submitted and one **Report** action. Do not ask for a UUID, a free-text suspect name, or a second confirmation dialog.
5. Confirm what actually happened: **“Report accepted. A guard is responding.”**, **“Report accepted. No guard is currently available.”**, or a precise rejection reason.
6. The player can inspect their reports later. Arrest, recovered property, sentence completion, and other real outcomes can appear in the next personal mail digest.

Keep an explanatory empty state: **“You have no recent crimes to report. Reports become available after a robbery or a witnessed incident.”** A disabled action must explain whether reporting is turned off, observations are disabled, the guard is unavailable, or the account has expired.

Proposed non-cheat fallbacks:

```text
/crime report       # Open report choices for the guard currently targeted within interaction reach.
/crime reports      # Read your own submitted reports and their current status.
```

The command and GUI must call the same service. `/crime report` cannot contact distant guards or bypass line of sight, jurisdiction, evidence, or custody restrictions.

### 5.2 Evidence acquisition

Extend `CrimeObservation`/`ObservationService` to represent player victim and eyewitness accounts, reusing `DIRECT_VICTIM` and `EYEWITNESS` where their semantics fit. Add player-specific validation without routing players into villager reaction AI or checking whether a player is an MCA adult.

- For completed theft, the canonical incident and stolen-goods transaction establish that a loss happened. Capture what identity the player could perceive at the time; a server's internal knowledge of the Thief's UUID is not automatically the player's knowledge.
- For an active threat, create a bounded, persistent threat receipt once the session is successfully claimed and its visible threat begins. Do not create evidence during scouting, failed eligibility checks, or a canceled start event.
- For nearby player witnesses, use one bounded server-side perception check at the actual threat/commit event. Require same dimension, appropriate distance, line of sight, and ability to perceive. Never accept a packet saying “I saw this.”
- Keep identity confidence and incident truth separate. Blindness, invisibility, masks where applicable, and obstructed sight must not be defeated by the report picker.
- Store an unknown identity as unknown; do not later substitute the nearest Thief, an entity with a matching name, or a globally known offender UUID.
- Do not synthesize historic player eyewitness accounts when migrating an old save. Existing valid evidence may remain usable; missing history stays missing.

Use existing observation expiry by default. A report cannot extend a case's statute or produce fresh evidence merely by opening its UI. The broad occupation combat mode does not create an eyewitness account.

Keep the theft hint, mugging HUD, report picker, and news identity presentation consistent. If an existing notification exposes a true name that the new evidence model treats as unknown, fix that notification on the same path; do not make privacy depend on which UI the player reads.

### 5.3 Attempted mugging must remain reportable

The existing successful-mug path commits a case, but ordinary abort clears the active threat. A player who escapes should still be able to report the attempt.

Use the original session transaction ID as a single terminal-case identity:

| Session outcome | Persistent fact |
|---|---|
| Start rejected/canceled before a visible threat | No attempt evidence or criminal case. |
| Visible threat, still active | A private threat receipt; no fabricated completed theft. |
| Visible threat ends without theft | One `ATTEMPTED_MUGGING` case from the receipt, with the original victim/perception/time snapshot. |
| Theft commits | One `MUGGING` case and its actual stolen-goods transaction. |
| Guard confirms/intervenes or an accepted report terminates a live threat | Commit the attempted case once, then terminate the session before theft can proceed. |

Refactor the existing intervention and abort paths to share this finalization. If a terminal case already exists, reuse it. Do not first commit an attempted case and then attempt to insert a completed theft under the same immutable case ID. Handle a disappearing/dead Thief from captured evidence without inventing a fresh living entity or rescanning witnesses after the event.

Respect existing canceled-event semantics. If a listener vetoes evidence or reporting, do not manufacture stronger evidence elsewhere. A stopped/frozen world must not gain new mutations through a report action.

### 5.4 Submission contract

The client supplies only server-issued menu/session identity, revision, offered evidence ID, selected responder, and request nonce. The server derives suspect, victim, crime, time, confidence, and jurisdiction.

Validate at commit time:

1. Real authenticated player; correct connection and server; no FakePlayer or spectator reporting.
2. Player is able to interact and not restrained by a conflicting custody/action flow.
3. Player reporting and its required observation layer are enabled.
4. Live responder in the same dimension, within the established interaction reach, in sight, and authorized by the existing responder-role checks.
5. Evidence belongs to the reporter and is unexpired, unsuppressed, and actually offered by the menu.
6. The referenced case/session exists and agrees with the evidence. Its resolution and the suspect's current state still permit the requested action.
7. Jurisdiction is eligible, with no hidden cross-community propagation.
8. Nonce and evidence/report deduplication pass.

Reuse `CrimeReportEvent.Pre/Post` and `ReportService` semantics. A civilian player's account remains `authoritative=false`: delivery to a guard does not mean the guard witnessed it. Never add `CAUGHT_IN_ACT` unless the guard's actual observation independently supports it.

An identified victim account above the existing confidence threshold can support arrest. Unknown identity or inadequate confidence receives **“Account recorded; no identified suspect to pursue.”** Do not invent a detective simulation for this release or promise an arrest that has no implementation.

Deduplicate enforcement by case, and submission by reporter plus evidence/case. A second honest witness can contribute a distinct observation; twenty clicks cannot create twenty charges, sentences, standing changes, or dispatches. No random success roll and no penalty for an honest insufficient report.

### 5.5 Jurisdiction and public knowledge

Retain the case's existing community key. `IncidentService` currently anchors NPC cases to the offender's community. Reporting somewhere else must not rewrite that historical fact or create a second case.

- A matching local authority can accept an actionable report.
- An unrelated village explains where to report it, unless existing configured propagation grants authority there.
- For a wilderness case with no community, allow a responder to accept the player's account and, when local and identified, pursue under a receiving-authority scope stored on the report. Keep the case's original null community. Do not broadcast it to every village or grant other guards automatic knowledge.
- A stored receiving jurisdiction is provenance, not a reason to move or duplicate the original incident.

Consolidate the public-knowledge decision used by `CrimePublicView`, integration hooks, and news. A valid accepted, identified report must be sufficient to publish the permitted case fact to its lawful receiving community even if the original incident had no NPC witnesses. Do not mutate `witnessed` or insert invented witness UUIDs to bypass the current predicate. An unsupported allegation or unknown identity must not publish a named suspect.

Keep the existing distinction between player and NPC offenders. In particular, reporting an NPC must not create a player-standing record keyed to that villager UUID through `ReportService.propagate` or the Reputation bridge.

### 5.6 Effective guard response and recovery

On an accepted actionable report, request an available responder through the existing assignment/activity system, then use `NpcCriminalPursuit` → `NpcArrestService` → `NpcCustodyService`.

The first guard may accept the report while another responds. Do not steal an escorting guard, incapacitated worker, or a responder committed to a higher-priority action. Townstead availability and ownership rules continue to apply.

If the Thief is not loaded, is outside the local search area, has changed dimension, or cannot be reached, keep the report and show an honest pending state. Do not force-load chunks, teleport guards, or give a pursuit permanent knowledge of the target's live position. Retry with bounded local checks and react to relevant load/availability events; respect the existing pursuit timeout and last-seen behavior.

A report is not an arrest. Persist report acceptance separately from the transient dispatch lease. Proposed UI states are **Accepted**, **Awaiting a guard**, **Searching**, **In custody**, **Sentence completed**, **Case closed**, and **Expired**. Derive states from canonical cases/custody where possible; store only the receipt and dispatch information that cannot be derived.

A proved incident does not disappear because its offender changes profession. The current `pursueCriminalSuspect` occupation check needs review on this route: a former Thief who is now an ordinary worker can remain liable for an unresolved reported robbery. Use the valid NPC case as the basis, retaining current protected-role/availability checks; do not let `isCriminal()` alone decide whether a case exists or may be resolved.

Call the existing recovery system only after actual custody or a confirmed death path. It owns exact item/currency provenance, offline-owner handling, inventory-full behavior, and single consumption. Do not give the reporter another victim's property or return goods both as drops and inventory inserts. Preserve whatever legitimate return destination the current recovery contract specifies; describe that destination accurately in feedback.

Serving a sentence resolves the bound case through the existing justice flow. Re-reporting it cannot re-arrest the Thief. A new robbery is a new case. A confirmed death closes pursuit and reporting as a death outcome without pretending a sentence was served; extend the resolution vocabulary compatibly if no existing reason represents it.

## 6. Village news through `/mca mail`

### 6.1 Useful, factual content

Build a small deterministic editorial layer over real committed events. News must reflect outcomes, not just event-handler entry or an attempted mutation.

| Story | Audience and publication condition | Content |
|---|---|---|
| Local theft warning | Community with accepted public knowledge; aggregate repeated incidents. | What kind of trouble occurred, community name, whether an identified suspect is still sought. |
| Thief arrested | The arrest actually committed, in a community entitled to know. | Name when public, place, custody status; no fabricated sentence completion. |
| Property recovered | Affected owner privately; a generic public recovery note only when justified. | Actual recovery result and destination. Public version omits owner inventory details and amounts. |
| Sentence completed / case closed | Reporter or affected player; public follow-up if the case was already public. | Correct terminal outcome, replacing stale “wanted” wording. |
| Local public threat resolved | Existing publicly known case reaches a confirmed appropriate resolution. | Factual outcome without granting a new bounty or reward. |
| Rescue or civic result | Optional later input from an existing committed event with a proven public audience. | One concise outcome; absence of the relevant system is normal. |

The first five form the initial release. Use existing local data for them. Optional Quests/Conversations/Townstead material may be added later through the same projection contract; do not make an unverified external event a core dependency.

Example of the intended tone, with illustrative names and facts:

> **Oakridge Watch — Village News**  
> A Thief was arrested following a resident's report. The suspect is now in custody.  
> **Your report:** Property recovered from the suspect has been returned through the usual recovery process.  
> No other significant public incidents were reported in this edition.

Only include a recovery sentence if recovery actually succeeded, and name its real destination when known. Never generate an empty edition just to say there was no news.

### 6.2 Selection, relevance, and privacy

- Default subscription is enabled, but recipients qualify through their own village connections: home/residence where reliably available and up to three recently visited/interacted-with communities, expiring after seven Minecraft days without renewed contact. Store only known, observed connections.
- Personal report/recovery updates belong only to the reporter or affected owner, regardless of current location. A witness is not entitled to the victim's inventory detail.
- Build one combined digest per player per interval, not one letter for every community or every event. Default: one Minecraft day, at most four stories.
- Give personal case outcomes priority, then important local outcomes, then aggregated warnings. Rotate lower-priority communities when there is room, so one busy village cannot permanently dominate.
- Use `server.overworld().getGameTime()` for scheduling. `/time set`, sleeping through the night, and disabled daylight cycling must not create duplicate editions or stall mail indefinitely. Offline time while the server is stopped does not advance the schedule.
- An offline player receives a bounded catch-up digest of relevant recent facts on return, not a letter for every missed day. Do not scan player save files or generate mail for every player UUID ever recorded.
- Opting out drops undelivered optional digests and advances the personal cursor. Opting in starts from current eligible news, without replaying the entire history. Already-delivered letters remain normal mail.

Proposed commands:

```text
/crime news on
/crime news off
/crime news status
```

These change only the caller's preference and require no cheats. `status` explains whether personal preference, world policy, MCA's own mailing setting, or compatibility capability currently prevents delivery.

Use the shared public-knowledge projection from §5.5. Never publish an unwitnessed hidden player crime, withheld family observation, hidden mask identity, secret witness list, exact remote coordinates, or another player's private case. A public arrest does not make every prior private crime public. Publishing news must never itself create observations, warrants, reputation changes, quest progress, or guard awareness.

### 6.3 Event reduction and deduplication

Use stable keys such as `(worldNewsId, caseId, eventKind, resolutionRevision)` for source events. Capture immutable, bounded facts when the event becomes publishable. A late report publishes from the report time, even if the underlying private crime is older.

Before composing, reduce related facts:

- Theft reported and suspect arrested in the same interval → one arrest story with useful context.
- Arrest and release in the same interval → a resolved-case story, not a currently-jailed claim.
- Multiple witness reports about one incident → one story.
- Repeated petty theft in one community → one warning with a count of actual distinct public cases.
- Death/case closure before publication → remove stale wanted wording.

Preserve separate facts when genuinely distinct, and revalidate volatile status at composition. Once a letter exists, it is a dated historical edition; subsequent events belong to later editions. Do not rewrite letters already in a player's inventory.

### 6.4 Actual MCA inbox integration

Add a `McaMailBridge` facade or equivalent extension to the existing compatibility boundary. Resolve the appropriate `PlayerSaveData` through verified runtime bindings; operate in the correct overworld-backed persistence scope, including when the player is in another dimension.

The bridge should return typed outcomes such as `ENQUEUED`, `ALREADY_DELIVERED`, `DISABLED`, `DEFERRED_FULL`, `UNAVAILABLE`, and `RETRYABLE_FAILURE`. A successful reflective invocation of a void `sendMail` method is not sufficient proof of delivery.

Required behavior:

1. Check MCA's actual mailing setting before queueing or notifying. Do not enable it behind the player's back.
2. Build normal MCA letter NBT with serialized `Component` content. Use serializers, not string-concatenated JSON containing village or entity names.
3. Verify page order and layout on each supported jar. Prefer a deliberately constructed `pages` list through `sendMail` over relying on untested `sendLetter` insertion order.
4. Include a namespaced edition ID and origin marker in Crime-owned letters. Do not change unrelated mail.
5. Notify only after a new letter is actually appended. Retries and duplicate callbacks must not create another notification.
6. `/mca mail` must retrieve it using MCA's normal command and normal letter item. Do not replace the command or directly give the player an unrelated book as the normal delivery path.
7. Bound pages, component length, and NBT size. Target at most four pages and 2,000 serialized-text characters of content initially; visually test the real letter screen and reduce budgets if necessary. Truncate by readable content boundaries, not through JSON or Unicode sequences.
8. Treat item/character names as plain text arguments. Do not inherit arbitrary click, hover, or command events from entity display components.

The bridge should perform capability checks once and cache verified bindings, while checking the live MCA mailing preference for each delivery. Keep a temporary offline recipient or full mailbox distinct from a permanently unsupported member signature. Neither should trigger unbounded retries.

### 6.5 Delivery persistence and save ordering

The existing code explicitly does not promise atomic persistence across Minecraft save files. Do not describe an in-memory `Set` or an outbox flag as exactly-once crash-safe delivery.

Use a stable recipient/edition ID, a retained delivery envelope, and a durable receipt. Prefer a narrow `PlayerSaveData` extension that persists Crime's receipt set in the **same saved object as MCA's inbox**. Implement this through the optional MCA mixin/binding layer if no appropriate native extension hook exists; keep the extension's public types MCA-independent.

The append-once operation must check the native-mailing gate, check its receipt, append the tagged letter, add its receipt, and mark that same object dirty on the server thread. Retain the receipt after `/mca mail` removes the letter. Scanning only the current inbox fails as soon as the player collects mail.

Keep delivered envelopes eligible for bounded reconciliation until their retention deadline. The world outbox's acknowledgement is advisory: if it saved before the mailbox, a retry must still repair a missing mailbox append; if the mailbox saved first, its receipt prevents duplication. Retain receipts longer than any possible envelope retry period. Default envelope retention: seven Minecraft days; receipt retention: thirty days, with a capacity consistent with the maximum edition rate.

Inspect dirty-marking on mail collection in the actual jars. The inspected upstream `getMail` removes an inbox entry without a visible `markDirty` call. If that gap exists at runtime, add a narrow dirty-mark hook for successful removal so a normal save/restart cannot resurrect collected mail.

Do not force a full world save for every letter. Validate ordinary save/restart and both outbox/mailbox save-order simulations. Be explicit that arbitrary process crashes spanning player-inventory saves remain constrained by Minecraft's broader persistence model; never promise cross-file ACID guarantees.

If verified native mailbox hooks are unavailable, preserve bounded pending news and expose a diagnostic without crashing crime gameplay or spamming inventory. Binding failures are not a reason to silently switch to a different mailbox. The required supported versions must provide the intended native delivery before release.

## 7. Genuine per-world game rules

### 7.1 Precedence and migration without surprises

Register real Minecraft `GameRules` early enough to appear in **Create World → Game Rules**. Use booleans and integers supported by this Minecraft version. Keep client presentation preferences, item/entity lists, probability curves, currency adapters, and advanced integration settings in their current config/data systems.

Add `mcaCrimeUseWorldRules`, default **false**. This gives a precise contract:

```text
If mcaCrimeUseWorldRules is false:
    selected gameplay settings come from the server's existing COMMON config.
If mcaCrimeUseWorldRules is true:
    selected gameplay settings come from this world's registered game-rule values.
All settings not listed for world override retain their existing source.
```

New and upgraded worlds therefore preserve config behavior until they opt in. Turning the override on makes **all listed settings** world-owned, not merely settings whose values differ from defaults. Explain this in the master rule's tooltip and command feedback.

Use fixed registration defaults, matching existing config defaults where applicable. Never change the global rule registry defaults to one world's config or infer whether a rule was edited by comparing its value with the default: an explicit user choice can equal the default.

Provide an operator command **`/crime rules import`** that snapshots the currently validated COMMON values into every listed world rule and enables world overrides as one validated batch. This is the recommended way to convert a configured existing world without resetting its balance. It must report the number of imported values and the effective mode.

Also provide:

```text
/crime rules status          # Show effective values and whether each comes from config or world rules.
/crime rules defaults        # Explicitly reset listed rules to shipped defaults and enable overrides.
/gamerule mcaCrimeUseWorldRules false   # Return to config-driven values; preserve stored overrides.
```

`import`, `defaults`, and all rule mutation require ordinary level-2 command permission. Do not bypass permission because a server is singleplayer. World creation may expose the vanilla Game Rules editor independently of enabling cheats; changing rules in a running world follows vanilla cheats/operator permissions, including Open to LAN behavior. A dedicated server's console remains supported.

### 7.2 Initial rule set

Identifiers below are proposed public names. Verify uniqueness and localize the name/description keys expected by the actual 1.20.1 editor. The “Config source” column uses existing **Java fields** to avoid confusing nested TOML paths; add new fields under clear `reporting`, `thiefLaw`, and `news` sections.

| Game rule | Type/default | Range | Config source when overrides are off |
|---|---|---|---|
| `mcaCrimeUseWorldRules` | boolean `false` | — | World-only selector. |
| `mcaCrimeDetection` | boolean `true` | — | Existing `enableCrimeDetection`. |
| `mcaCrimeObservations` | boolean `true` | — | Existing `enableObservations`. |
| `mcaCrimeThieves` | boolean `true` | — | Existing `enableThieves`. |
| `mcaCrimeNpcMugging` | boolean `true` | — | Existing `enableNpcMugging`. |
| `mcaCrimeSeriousNpcCrime` | boolean `false` | — | Existing `enableNpcCrime`; preserve its separate semantics. |
| `mcaCrimePvpCrime` | boolean `false` | — | Existing `pvpCountsAsCrime`. |
| `mcaCrimeRequireWitnessForHeat` | boolean `true` | — | Existing `requireWitnessForHeat`. |
| `mcaCrimePlayerReports` | boolean `true` | — | New `enablePlayerReports`. Requires observations for new report evidence. |
| `mcaCrimeThiefCombatPolicy` | integer `2` | `0–2` | New enum described in §4.1. |
| `mcaCrimeNews` | boolean `true` | — | New `enableCrimeNews`. MCA's own mail gate and personal opt-out still apply. |
| `mcaCrimeNewsIntervalDays` | integer `1` | `1–30` | New `crimeNewsIntervalDays`. |
| `mcaCrimeNewsMaxStories` | integer `4` | `1–8` | New `crimeNewsMaxStories`; page/NBT limits still apply. |
| `mcaCrimeThiefMugCooldownTicks` | integer `24000` | `0–240000` | Existing `thiefMugCooldownTicks`. |
| `mcaCrimePlayerMugProtectionTicks` | integer `36000` | `0–1728000` | Existing `playerMugProtectionTicks`. |
| `mcaCrimeMaxMuggingsPerDay` | integer `2` | `0–64` | Existing `maxMuggingsPerPlayerPerDay`; `0` means no cap, not disabled mugging. |
| `mcaCrimeThiefJailTicks` | integer `12000` | `200–240000` | Existing `thiefJailTicks`. |
| `mcaCrimeThiefProtectHotbar` | boolean `true` | — | Existing `thiefProtectHotbar`. Armor/offhand remain governed by their existing settings. |

Keep the initial list curated. Do not expose deprecated profession-presentation settings or an integer pretending to encode item IDs, lists, or arbitrary floating-point probabilities. Additional rules can use the same resolver later without changing precedence.

### 7.3 Effective-settings implementation

Create a typed `CrimeWorldSettings` snapshot/resolver that requires `MinecraftServer` or `ServerLevel` for authoritative behavior. Use the server's shared world game-rule state consistently across dimensions. Do not allow a Nether handler to read a different static config copy.

Audit **all call sites** of every mapped COMMON field. Replace gameplay reads, derived policies, command diagnostics, compatibility ownership checks, AI gates, and newly added services with the resolver. Examples include `TheftPolicy.fromConfig`, `JusticeService.Settings.fromConfig`, thief assignment/refresh/ticking, `MugProtection`, combat detection, and the Reputation authority handshake. Keep unmapped settings untouched.

If a helper has no world argument, pass one or pass an immutable resolved settings object; do not add a hidden global “current world.” During registration or UI construction before a server exists, show fixed rule defaults without creating `CrimeWorldData`.

For integer rules, inspect Forge/vanilla's actual registration API. Use bounded Brigadier argument types where possible, with only the narrow vanilla accessor/invoker needed if visibility requires it. Independently validate values arriving from the creation editor, saved NBT, and direct APIs. Invalid values must normalize to a documented legal value or be rejected; commands must report the **effective** value. Guard callbacks against recursive normalization, use `long` for tick arithmetic, and avoid timer overflows.

Do not seed or overwrite rules during every login, config reload, or server startup. Game-rule persistence is the authority after opt-in. `import` is explicit, and config reload only changes effective mapped settings while world overrides are off.

### 7.4 Live changes

| Change | Required transition |
|---|---|
| Disable NPC mugging | Stop new attempts and safely end active uncommitted sessions on the next server tick. Keep completed thefts, evidence, property provenance, cases, and custody. |
| Disable Thieves | Preserve the setting's existing assignment/behavior semantics; do not delete occupations, entities, or historical cases. Existing valid reports remain inspectable. |
| Disable reporting/observations | Reject new affected submissions, close/invalidate offered report actions, preserve accepted reports and existing enforcement. Explain the specific gate. |
| Disable news | Stop publication/delivery and optional notifications. Preserve ordinary MCA mail and already-delivered letters. Re-enable without a backlog storm. |
| Change combat policy | Apply to new actions immediately; preserve the immutable decision for a hit already being reconciled. Do not erase prior crimes or rewrite prior penalties. |
| Change sentence duration | New sentences use the new duration. Existing sentences finish with their original assigned duration. |
| Change cooldown/protection duration | New grants use the new value. Existing protection deadlines remain valid; do not suddenly expose a protected player. |
| Change edition interval/size | Recompute the next schedule once without duplicating the last edition. New composition uses the new size within hard content limits. |
| Switch override mode | Resolve a coherent new snapshot, apply transition cleanup once, and invalidate relevant UI revisions. No partially imported policy may be observed mid-command. |

Sync only the effective presentation values a client needs, at login, relevant rule changes, and menu open. All enforcement stays server-side. Clear per-world caches on stop so opening another singleplayer save in the same JVM cannot inherit them.

## 8. Persistence, bounds, and migration

Extend the existing overworld store with the next schema step after the implementation-time head. It is `14 → 15` only if no intervening release has already advanced the schema. Use named schema constants and additive reads/writes.

| Data | Preferred home and invariant |
|---|---|
| Player victim/eyewitness accounts | Existing observations and indexes, with player-aware role handling. No duplicate full case ledger. |
| Visible active-threat receipts | Bounded table keyed by original mugging transaction; immutable observation snapshot and terminal state. |
| Player report receipts | Reporter/evidence/case/report/responder IDs, receiving authority, accepted time, and minimal dispatch metadata. Case and custody remain authoritative for resolution. |
| Recent strict-mode defensive basis | Reuse threat/observation records where possible; otherwise a bounded actor/target/case expiration entry. |
| Public news facts | Bounded, redacted, revisioned event projections; no full inventory NBT or private witness list. |
| Subscriptions | Player preference, eligible known communities, last edition/cursor, and next eligible time. |
| Delivery envelopes | Stable recipient/edition ID, bounded rendered payload, timestamps, attempts, and advisory status. |
| Native mail receipts | Namespaced extension in the same persisted MCA object as the inbox, independent of whether the letter remains unclaimed. |
| Settings | Native world game-rule storage. Do not duplicate rule values in CrimeWorldData. |

Recommended starting bounds: 32 reportable player accounts and 32 report receipts per player, 4,096 player accounts globally, 2,048 report receipts globally, 1,024 active threat receipts, 4,096 news facts globally with at most 64 per community, eight retained envelopes per recipient and 8,192 globally, and 64 native mail receipt IDs per recipient. Keep at most seven unclaimed Crime news letters in an inbox before deferring/coalescing more. Tune from measured server behavior, not by removing caps.

These limits require deliberate separation from the existing eight-pending-observations-per-NPC policy. Do not globally expand NPC memory just to support player reporting. Extend observer-specific retention or add a small player index while retaining existing legal evidence semantics.

Prune expired/terminal records first. Never evict active custody, exact stolen-goods ownership, or the only evidence supporting an active case to make room for news. News is optional: coalesce or drop its lowest-priority expired/unpublished facts, increment a diagnostic counter, and preserve justice state. When a player cannot gain a new report receipt due to a hard cap, return a clear capacity result before changing enforcement.

Rebuild indexes from persisted records. Validate UUIDs, resource locations, enums, counts, string lengths, nonnegative times, and linked IDs. Quarantine malformed entries using existing patterns; do not crash the whole save or accept a malformed record as strong evidence. Unknown future schema remains read-only, including report, mail, and rule-driven mutations that would touch protected Crime data.

Migration defaults: empty new tables, no historic news generation, no invented eyewitness knowledge, existing mail unchanged, and `mcaCrimeUseWorldRules=false`. New features take their documented config defaults, but existing scores, cases, jobs, custody, and recovery transactions remain intact. Historical eligible Thieves gain the selected policy for future actions only; do not refund earlier penalties automatically.

## 9. Networking, UI consistency, and operating cost

Reuse the current menu/session/nonce pattern. Extend protocol versioning when packet layouts change; do not silently accept incompatible payloads. Keep common packets free of client screen imports.

Report choices should be paginated or capped at twenty rows per response. Only serialize fields the player is entitled to see. Bound every incoming string/list and use the existing `ServerPacketGuard` plus a per-player request budget, for example a burst of four read/submit requests with a refill of two per second. Rate limiting supplements authorization; it cannot replace it.

Opening a menu grants no durable permission. Revalidate settings, evidence, responder reach, case resolution, role, and custody when the player clicks. Concurrent players may see the same situation; only one case-level dispatch or recovery transition may commit. A valid empty result or already-filed result should not be treated as a network error.

Use the existing action styles and localization system. Keep text readable at normal GUI scales, keyboard navigation functional, and long names safely truncated with complete tooltips where appropriate. Do not require color alone to distinguish accepted, searching, and closed cases. Preserve normal trades, dialogue, apologies, arrest challenges, and Epic Fight's interaction compatibility.

Keep work event-driven and indexed:

- Evidence capture happens at actual threat/incident boundaries; no every-tick scan of all players and villagers.
- Report lookup uses observer/reporter/case indexes.
- Dispatch retries run on a bounded cadence, such as every twenty ticks with at most eight queued cases examined per server tick. Existing assignment ownership prevents duplicate responders.
- News scheduling can run every two hundred ticks with a bounded recipient/fact budget. Do not sort the entire crime ledger for every player.
- No new path requests chunk tickets, loads distant villagers, scans all offline player files, or logs once per AI tick.

Add concise `/crime validate`/debug output for effective rule source, native penalty-hook availability, mailbox capability, player-report count, pending dispatches, pending/coalesced news, rejected invalid packets, and bounded delivery failures. Do not expose private case contents to ordinary users through diagnostics. Log unexpected failure once per capability/state transition rather than on each retry.

## 10. Suggested code changes

This table identifies responsibility, not a demand to create one class per row. Prefer small pure policy types plus services at existing boundaries over a large new framework.

| Existing area / proposed addition | Work |
|---|---|
| `McaCrime`, `McaCrimeConfig`, `config/ConfigValidator` | Register rules early, declare new config defaults, validate mappings and dependent settings. |
| Proposed `config/CrimeGameRules`, `config/CrimeWorldSettings` | Native rule definitions, precedence, typed snapshots, batch import, bounded integer handling, and setting transitions. |
| `detect/CrimeGate`, `DamageIncidentService`, `CombatIncidentProcessor`, `CombatEncounters` | Shared exact-action Thief eligibility and reasoned lawful-force integration without breaking finality or attribution. |
| Proposed `justice/ThiefCombatPolicy`, `ThiefCombatDecision` | Pure truth table and immutable per-action decisions. |
| `compat/McaCompat`, `compat/mca/McaBinding` | Mail, role, and penalty capability bindings as needed; root-independent behavior. |
| Proposed optional `mixin/mca/` configuration/plugin | Precisely gated native penalty suppression and mailbox receipt persistence, with target probes and no static MCA linkage. |
| `compat/CrimeAuthorityPolicy`, `compat/reputation/CrimeReputationCompat` | World-aware normal ownership; per-incident exemption handshake where supported. |
| MCA: Reputation optional companion patch | Add the narrow exemption contract from §4.5; no change to unrelated incident kinds or core score semantics. |
| `NpcMuggingService`, `IncidentService`, `ObservationService` | Player evidence, visible-threat receipts, single terminal attempt/theft finalization, and correct perception snapshot timing. |
| `ReportService`, `JusticeService`, `CrimePublicView`, integration public checks | Shared accepted-report authority, receiving jurisdiction, no NPC-as-player standing, and no fabricated witness facts. |
| Proposed `report/PlayerCrimeReportService` | Eligible choices, commit validation, reporting receipt, and dispatch request. |
| `NpcCriminalPursuit`, `NpcArrestService`, `NpcCustodyService` | Bounded report dispatch, existing custody execution, and publishable committed outcomes. |
| `StolenGoodsReturn`, existing death consequences | Recovery outcome notifications without changing ownership or duplicating goods. |
| `action/CrimeActionIds`, `CrimeActionService`, handlers/screens | Peaceful report entry, short case picker, explanatory states, and invalidation of stale offers. |
| `network/` and `command/CrimeCommand` | Report list/submit/status protocol, personal news preferences, operator rules commands, and diagnostics. |
| Proposed `news/` service/policy/records and `McaMailBridge` | Redacted facts, digest reduction, subscriptions, bounded outbox, native append-once delivery. |
| `CrimeWorldData`, `CrimeDataMigrations` | Additive persistence, retention, index rebuilding, and future-schema safety. |
| Language/assets/docs/tests | User-facing names, rule tooltips, acceptance coverage, support matrix, and migration notes. |

For the optional MCA mixins, update `NoMcaStaticLinkTest`, `MixinConfigTest`, optional classloading expectations, manifest registration, and compatibility probes together. Merely setting `require=0` is not verification: it can make every intended hook silently fail.

## 11. Implementation order and release gates

### Phase 0 — Reconfirm the working baseline

- Read current `AGENTS.md` if present, `CLAUDE.md`, `MODMAP.md`, `gradle.properties`, relevant docs, and these feature paths.
- Record the implementation commit, current world schema, companion versions, and existing test/build results. Inspect local changes before editing.
- Verify MCA mailbox/penalty members against the actual probe jars. Determine exactly which injection points and mailbox extension hooks exist.
- Verify whether Reputation has acquired a per-incident exemption API since the inspected snapshot. Reuse it if adequate; otherwise prepare the small additive compatibility change.
- Inspect the full call-site set for selected config fields. The settings migration is incomplete until all gameplay readers agree.

Exit evidence: a short implementation notes file naming actual supported runtime seams, any changed assumptions, and concrete test commands. Do not turn uncertain API names in this plan into hardcoded guesses.

### Phase 1 — Settings and persistence foundations

Implement native rules, the resolver, transition hooks, new config fields, pure policy inputs, required records/indexes, and the additive migration. Wire existing mapped gameplay readers before presenting rules as functional.

Exit evidence: two worlds with different settings remain independent; an old configured save retains behavior; explicit import copies its balance; the creation screen preserves selections on first load; invalid values cannot corrupt timers.

### Phase 2 — Complete reputation-safe Thief combat

Implement the decision table and exact-action context, then the native MCA penalty hooks and Reputation cooperation. Extend strict-mode threat evidence only as needed for this phase. Preserve ordinary villagers and existing death finality.

Exit evidence: the full reputation vector stays unchanged for an eligible Thief kill, while a control attack on an ordinary villager still produces expected consequences. Run with and without Reputation, across supported MCA package roots. A compiling event handler is not sufficient.

### Phase 3 — Complete the reporting journey

Add player evidence and attempt finalization, the peaceful report action/picker, server validation, authoritative report filing, bounded dispatch, state feedback, and recovery/result notifications.

Exit evidence: in a normal non-operator session, a player is robbed, reports to a guard, sees an actual arrest, receives the correct recovery outcome, and cannot cause a second arrest/recovery by replaying the report. Also demonstrate the honest pending state when no guard can respond.

### Phase 4 — Complete native news delivery

Add public/private projection, subscriptions, aggregation, edition IDs, retained envelopes, durable native receipts, and the verified mailbox bridge. Wire facts from completed Phase 3 outcomes and existing public case events.

Exit evidence: relevant news arrives through `/mca mail`, stays localized and bounded, does not reveal a hidden crime, and does not duplicate after collection plus save/restart or replayed events. Normal condolence mail continues.

### Phase 5 — Integration validation and release documentation

Finish the matrix below, update user/admin/API documentation, verify the final packaged jar, and report results with their limits. Include any coordinated companion patch and its compatibility requirement. Keep unverified optional combinations marked unverified.

Use the repository's requested `.mcmod-tools/gradlew-quiet.ps1 -Project . -Task <task>` where it is actually present. If that wrapper is absent from the checkout/environment, document the fact and use the checked-in Gradle wrapper for the same task rather than stopping or inventing a tool path. Required gates include `compileJava`, `check`, and `build` with `checkJarContents`.

The current build may exclude the Reputation adapter when the compiled sibling is absent. A standalone successful build therefore does **not** verify Reputation support. Run the relevant build with the companion API/classes available and confirm the adapter is present. Existing `townsteadProbeTest` is a separate task requiring a real supplied Townstead jar; do not count a skipped task as a pass.

## 12. Acceptance and regression matrix

Add meaningful pure-policy and persistence tests using the existing JUnit structure. Add targeted integration/bytecode probes where source-level tests cannot prove runtime hooks. Use a dedicated server plus at least two clients for multiplayer behavior; document manual scenarios when they cannot be automated in the existing harness.

### 12.1 Combat and reputation

| ID | Scenario | Required result |
|---|---|---|
| C1 | Default policy; valid adult Thief; direct nonlethal hit and confirmed kill. | No new crime/Heat/negative Karma/standing loss/native blame hearts/Reputation incident. |
| C2 | Ordinary villager, Fence, renamed “Thief,” child, guard, or unresolved role. | No Thief exemption; ordinary protection remains. |
| C3 | Thief in lawful custody or player restraints. | Protected-prisoner exception applies; no execution exemption. |
| C4 | Strict mode: active mugging, recent identified victim response, valid local report. | Each valid basis independently grants the intended exemption. |
| C5 | Strict mode: stale receipt, resolved case, different jurisdiction, or invented suspect. | No exemption from that basis. |
| C6 | Thief loses job or enters custody between hits. | Next action re-evaluates; an already sampled action keeps its original decision. |
| C7 | Arrow, owned tame animal, indirect attributed hit, AOE hitting Thief and bystander. | Same policy for attributable force; collateral damage assessed separately. |
| C8 | Canceled damage/death, totem revival, multiple death callbacks, nested event reentry. | No false kill outcome, duplicate case/recovery/news, or leaked exemption context. |
| C9 | Kill with preexisting negative hearts, nearby relatives, unrelated simultaneous social changes. | No fresh blame/standing loss from exempt kill; preserve unrelated history/changes, death, mourning, and condolences. |
| C10 | No Reputation; healthy current authority; delivery paused; detection toggled; new exemption-capable companion. | Behavior matches the documented capability contract; no blanket suppression of ordinary-villager penalties. |
| C11 | Repeatedly assign and kill Thieves. | Exemption grants no automatic reward and cannot duplicate stolen property or claim a bounty without its own valid basis. |

Measure C1/C9 using before-and-after snapshots of **all** relevant stores, not only the HUD band: Crime Karma/Heat/cases/local standing, native target/relative hearts and village state, and companion incident/standing records. Wait through the integration pump and public propagation before assessing the result.

### 12.2 Reports, guards, and property

| ID | Scenario | Required result |
|---|---|---|
| R1 | Player robbed with no NPC witnesses; reports normally. | Player evidence exists; report can authorize local action without invented NPC witnesses. |
| R2 | Player escapes a visible attempt before any goods move. | One reportable attempted case; no completed-theft claim or recovery payout. |
| R3 | Start event canceled / scouting never becomes a threat. | No reportable attempt. |
| R4 | Empty-hand guard interaction, dialogue integration, command fallback. | Same server service and reach/evidence checks; no cheats needed. |
| R5 | Another player submits an evidence UUID, modifies suspect, replays nonce, or uses stale menu. | Rejected without mutation, identity disclosure, or duplicate feedback floods. |
| R6 | Two honest reports of one case arrive together. | Distinct legitimate testimony may be retained; one legal charge, dispatch, sentence, and property return. |
| R7 | Reporting guard did not witness the incident. | Civilian report remains non-authoritative; no fake `CAUGHT_IN_ACT`. |
| R8 | Unknown identity, blindness, mask/invisibility, name collision. | No fabricated named suspect or arrest; explanatory recorded-account result. |
| R9 | No guard, busy escort, incapacity, obstructed path, unloaded or dimension-changing suspect. | Honest bounded pending/search behavior; no stolen assignments, teleports, or chunk loading. |
| R10 | Matching/different community; wilderness report; propagation toggle. | Exact documented jurisdiction; no global leak or case duplication. |
| R11 | Successful arrest with items and currency, offline owner, full inventory, concurrent death. | Existing recovery contract honored; exact goods consumed/returned at most once; no minting. |
| R12 | Sentence served, dead suspect, former Thief with unresolved case, or a later new robbery. | Correct terminal/pending state; no repeat arrest for resolved case; old valid case survives profession change; new incident remains reportable. |
| R13 | Restart while report pending, searching, or in custody. | Acceptance and canonical custody persist; safe dispatch reconstruction; no double sentence. |
| R14 | Observation/report pre-event veto; read-only future schema; tables at capacity. | No bypass, partial unauthorized enforcement, or save corruption. |

### 12.3 News and mail

| ID | Scenario | Required result |
|---|---|---|
| N1 | Genuine public report → arrest → recovery. | Relevant concise digest appears as native MCA mail; text reflects final facts. |
| N2 | Hidden crime, withheld witness, unknown identity, remote community. | No unauthorized disclosure or guard knowledge caused by publication. |
| N3 | Several reports and outcome events from one case in one interval. | One reduced story with current status; distinct real cases stay distinguishable. |
| N4 | Online/offline recipient; `/time set`; sleep; server restart. | Bounded catch-up, stable schedule, no duplicate or empty daily letters. |
| N5 | Replay event/outbox after mail is collected. | Durable receipt prevents another copy and another toast. |
| N6 | Save-order simulation: mailbox first or world outbox first. | Reconciliation repairs missing delivery or recognizes a durable receipt; no false exactly-once claim across inventory crash saves. |
| N7 | World news off, personal opt-out, MCA mailing off, unsupported mailbox capability. | Distinct correct status; no bypass, notification storm, or substitute inventory-book delivery. |
| N8 | Inbox full of Crime letters, many facts, long/quoted/Unicode names, maximum stories. | Bounded backlog and valid component/NBT data; no broken page order or unreadable overflow. |
| N9 | Player changes dimension; opens different save in same JVM. | Correct native player save and no world/cursor leakage. |
| N10 | Condolence letter and news queued together; full inventory during `/mca mail`. | Both native mail types preserved and delivered through MCA's established behavior. |

### 12.4 World settings and compatibility

| ID | Scenario | Required result |
|---|---|---|
| W1 | Configure rules in Create World, including values equal to defaults. | Selections persist and apply on first load when overrides are enabled. |
| W2 | Upgrade a customized schema-14 world without opting in. | Existing mapped config values remain effective; migration preserves state. |
| W3 | `/crime rules import`, defaults, and toggling the master selector. | Documented all-rule precedence, coherent batch transition, correct status output. |
| W4 | Non-op, operator, console, singleplayer cheats disabled/enabled. | Vanilla permission expectations; no special singleplayer bypass. |
| W5 | Rule change during mugging, report picker, damage reconciliation, sentence, or delivery. | Transition semantics from §7.4; no orphan session, retroactive sentence rewrite, or duplicate edition. |
| W6 | Config reload with overrides off and on. | Correct source used in both modes; no rewriting stored world rules. |
| W7 | Invalid integer from command, editor, save, or API; very large timers. | Bounded effective values, honest feedback, no overflow or recursive callback loop. |
| W8 | Two worlds, multiple dimensions, multiplayer clients with different local configs. | Server/world authority throughout; no global/static leakage. |
| W9 | Each supported MCA package root; companion present/absent; dedicated server startup. | Verified hooks, correct adapter loading, no static MCA linkage or client-class load crash. |
| W10 | Townstead and Epic Fight combinations using existing supported versions. | Reporting entry usable, responder activity respected, existing trade/dialogue/combat interactions retained. |

Run existing combat, incident, report authority, custody, recovery, packet, config-isolation, data-migration, and integration tests as regressions. Extend current tests where the behavior belongs; do not replace them with tests that merely assert a new class or string exists.

Performance acceptance: a bounded stress fixture must exercise many public incidents, offline subscribers, duplicate submissions, and unreachable suspects. Assert queue caps, deterministic pruning, bounded per-tick work, and zero new force-loaded chunks. Record observed timings and test scale without claiming a universal millisecond budget from one machine.

### 12.5 Required documentation and completion report

Update `README.md`, `CONFIG.md`, `API.md` where contracts change, `CHANGELOG.md`, migration notes, and relevant compatibility/verification docs. Regenerate `MODMAP.md` through the normal project workflow if package/registration inventory changes. Keep version metadata in `gradle.properties` as instructed by the repository.

Document:

- The exact default Thief combat policy, prisoner exception, stricter mode, and absence of automatic rewards.
- How players gain evidence, report with an empty hand, interpret pending results, and recover property.
- Native mail delivery, local relevance, frequency, opt-out, and MCA's own mail switch.
- World-rule precedence, import/default commands, create-world setup, and runtime permissions.
- Supported MCA/Reputation combinations, verified injection/capability coverage, and any remaining limits.

The final coding-agent report must separate **implemented**, **automatically verified**, **manually verified**, and **unverified**. Include the commit/build artifact, schema change, commands run, actual gameplay scenarios completed, companion patch if needed, and material limitations. Do not claim success for a skipped probe or an adapter omitted from the build.

## 13. Source reference map

All code claims above use the snapshots listed here. Links are pinned so a later branch update does not silently change the evidence behind this plan.

### MCACrime — `2ef8081517448a21eb6ee0d2d9098fdef5f45f2a`

- [Repository instructions](https://github.com/otectus/MCACrime/blob/2ef8081517448a21eb6ee0d2d9098fdef5f45f2a/CLAUDE.md), [platform and MCA probe versions](https://github.com/otectus/MCACrime/blob/2ef8081517448a21eb6ee0d2d9098fdef5f45f2a/gradle.properties), [build and adapter inclusion](https://github.com/otectus/MCACrime/blob/2ef8081517448a21eb6ee0d2d9098fdef5f45f2a/build.gradle).
- [Configuration defaults](https://github.com/otectus/MCACrime/blob/2ef8081517448a21eb6ee0d2d9098fdef5f45f2a/src/main/java/dev/otectus/mcacrime/McaCrimeConfig.java), [mod bootstrap](https://github.com/otectus/MCACrime/blob/2ef8081517448a21eb6ee0d2d9098fdef5f45f2a/src/main/java/dev/otectus/mcacrime/McaCrime.java).
- [Canonical criminal occupations](https://github.com/otectus/MCACrime/blob/2ef8081517448a21eb6ee0d2d9098fdef5f45f2a/src/main/java/dev/otectus/mcacrime/job/WorldCriminalJobService.java), [NPC mugging lifecycle](https://github.com/otectus/MCACrime/blob/2ef8081517448a21eb6ee0d2d9098fdef5f45f2a/src/main/java/dev/otectus/mcacrime/mug/npc/NpcMuggingService.java).
- [Incident commit boundary](https://github.com/otectus/MCACrime/blob/2ef8081517448a21eb6ee0d2d9098fdef5f45f2a/src/main/java/dev/otectus/mcacrime/incident/IncidentService.java), [observation generation](https://github.com/otectus/MCACrime/blob/2ef8081517448a21eb6ee0d2d9098fdef5f45f2a/src/main/java/dev/otectus/mcacrime/memory/ObservationService.java), [existing report service](https://github.com/otectus/MCACrime/blob/2ef8081517448a21eb6ee0d2d9098fdef5f45f2a/src/main/java/dev/otectus/mcacrime/memory/ReportService.java).
- [NPC arrest](https://github.com/otectus/MCACrime/blob/2ef8081517448a21eb6ee0d2d9098fdef5f45f2a/src/main/java/dev/otectus/mcacrime/enforcement/NpcArrestService.java), [pursuit](https://github.com/otectus/MCACrime/blob/2ef8081517448a21eb6ee0d2d9098fdef5f45f2a/src/main/java/dev/otectus/mcacrime/enforcement/NpcCriminalPursuit.java), [NPC sentence and release](https://github.com/otectus/MCACrime/blob/2ef8081517448a21eb6ee0d2d9098fdef5f45f2a/src/main/java/dev/otectus/mcacrime/enforcement/NpcCustodyService.java).
- [Damage/death reconciliation](https://github.com/otectus/MCACrime/blob/2ef8081517448a21eb6ee0d2d9098fdef5f45f2a/src/main/java/dev/otectus/mcacrime/detect/DamageIncidentService.java), [combat decision model](https://github.com/otectus/MCACrime/blob/2ef8081517448a21eb6ee0d2d9098fdef5f45f2a/src/main/java/dev/otectus/mcacrime/detect/CombatEncounters.java), [local relationship consequences](https://github.com/otectus/MCACrime/blob/2ef8081517448a21eb6ee0d2d9098fdef5f45f2a/src/main/java/dev/otectus/mcacrime/relationship/RelationshipConsequences.java).
- [Public information policy](https://github.com/otectus/MCACrime/blob/2ef8081517448a21eb6ee0d2d9098fdef5f45f2a/src/main/java/dev/otectus/mcacrime/api/model/CrimePublicView.java), [Reputation authority policy](https://github.com/otectus/MCACrime/blob/2ef8081517448a21eb6ee0d2d9098fdef5f45f2a/src/main/java/dev/otectus/mcacrime/compat/CrimeAuthorityPolicy.java), [runtime MCA binding](https://github.com/otectus/MCACrime/blob/2ef8081517448a21eb6ee0d2d9098fdef5f45f2a/src/main/java/dev/otectus/mcacrime/compat/mca/McaBinding.java).
- [World-data store](https://github.com/otectus/MCACrime/blob/2ef8081517448a21eb6ee0d2d9098fdef5f45f2a/src/main/java/dev/otectus/mcacrime/state/world/CrimeWorldData.java), [schema migrations](https://github.com/otectus/MCACrime/blob/2ef8081517448a21eb6ee0d2d9098fdef5f45f2a/src/main/java/dev/otectus/mcacrime/state/world/CrimeDataMigrations.java), [action/menu authorization](https://github.com/otectus/MCACrime/blob/2ef8081517448a21eb6ee0d2d9098fdef5f45f2a/src/main/java/dev/otectus/mcacrime/action/CrimeActionService.java).

### MCA upstream — 1.20.1 at `4d824551b30654e5792e19e84f3933e3e3d90ea2`

- [Persistent inbox and letter construction](https://github.com/Luke100000/minecraft-comes-alive/blob/4d824551b30654e5792e19e84f3933e3e3d90ea2/common/src/main/java/net/mca/server/world/data/PlayerSaveData.java).
- [The `/mca mail` command](https://github.com/Luke100000/minecraft-comes-alive/blob/4d824551b30654e5792e19e84f3933e3e3d90ea2/common/src/main/java/net/mca/server/command/Command.java), [native letter item](https://github.com/Luke100000/minecraft-comes-alive/blob/4d824551b30654e5792e19e84f3933e3e3d90ea2/common/src/main/java/net/mca/item/ItemsMCA.java).
- [Villager damage/death consequences](https://github.com/Luke100000/minecraft-comes-alive/blob/4d824551b30654e5792e19e84f3933e3e3d90ea2/common/src/main/java/net/mca/entity/VillagerEntityMCA.java), [tragedy blame and mourning](https://github.com/Luke100000/minecraft-comes-alive/blob/4d824551b30654e5792e19e84f3933e3e3d90ea2/common/src/main/java/net/mca/entity/ai/Relationship.java).

### MCA: Reputation — `d1f5decdaf9104ddee7898f115864569196b4b29`

- [Kind-scoped authority contract](https://github.com/otectus/MCAReputation/blob/d1f5decdaf9104ddee7898f115864569196b4b29/src/main/java/dev/otectus/mcareputation/api/CoreIncidentAuthority.java), [automatic gameplay detectors](https://github.com/otectus/MCAReputation/blob/d1f5decdaf9104ddee7898f115864569196b4b29/src/main/java/dev/otectus/mcareputation/event/ReputationGameplayEvents.java).

## 14. Definition of done

The feature set is complete when a non-operator player can experience a robbery, report it through a discoverable peaceful interaction, receive an honest guard response and the existing lawful recovery outcome, and later collect relevant local news through `/mca mail`; an eligible Thief can be killed under the configured policy without hidden penalties in the supported social systems; and a world owner can set the listed rules before creation or change them during play with normal permissions and predictable persistence.

All four outcomes must survive normal save/restart, multiplayer concurrency, and the documented compatibility matrix. Ship the working behavior, tests, migration, and clear player/admin documentation together.

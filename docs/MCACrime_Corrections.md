MCA: CRIME — CRASH, THIEF, GUARD, AND JAIL CORRECTION PROMPT

Investigate and implement a complete correction of the issues below in https://github.com/otectus/MCACrime. Deliver working code, regression coverage, migration/recovery for affected existing saves, and updated documentation. Do not stop at an audit or proposed plan.

SCOPE AND EVIDENCE

Read the applicable repository instructions and build configuration first. The crash is on Minecraft 1.21.1, NeoForge 21.1.251, MCA: Crime 0.7.5, MCA Reborn 7.7.36-beta.3+1.21.1, with Dramatic Doors 1.21.1-3.3.2 installed. Prioritize the neoforge/1.21.1 branch. Inspect main (Forge 1.20.1 / Java 17) for existing solutions and shared defects, and apply equivalent relevant corrections to both maintained lines using their own loader APIs. NeoForge uses Java 21. Do not accidentally deliver only a Forge fix for this NeoForge report.

Read BOTH logs in full, preserving causal order:
- Crash report: https://mclo.gs/MhpXNKB
- Full startup log: https://mclo.gs/Iw3IqsJ
- Raw fallbacks: https://api.mclo.gs/1/raw/MhpXNKB and https://api.mclo.gs/1/raw/Iw3IqsJ

Source inspection on 2026-10-04 examined main at 2fff3f3f0cd6fb5b86cb512418d8f03d79ea1bea and neoforge/1.21.1 at 943741bfd1f75a79c403da997b99553cb10628e1. Both declare 0.7.6, while the supplied logs identify a 0.7.5 jar. Recheck HEAD and compare the shipped artifact/version before assigning regressions. The findings below come from logs and static inspection; the gameplay reports were not reproduced in a running game during this investigation.

User reports to resolve:
- New version crashes on startup.
- Thieves should carry stolen items in an accessible inventory so players can recover them from a living thief or kill the thief and recover their property.
- Punching a thief during an active mugging is treated as a crime.
- A nitwit robbed a player.
- Guards appear unable to attack.
- A player is arrested again immediately after leaving jail.
- A player remains stuck in jail with no apparent reason or way out.

The original inventory suggestion says "raise thieves"; its precise meaning is unclear. Implement the concrete inventory/recovery request through the existing search/frisk, recruitment, and death systems as applicable. Do not silently invent an unrelated thief-breeding or training feature.

1. FIX BOTH STARTUP FAILURES

Confirmed first MCA: Crime registration failure in Iw3IqsJ, at 10:08:42.305:
  IllegalArgumentException: Cannot set property BooleanProperty{name=waterlogged,...}
  as it does not exist in Block{[unregistered]}
  DoorBlock.handler$...$dramaticdoors$enhanceConstructor
  DoorBlock.<init>
  CellDoorBlock.<init>(CellDoorBlock.java:93)
  CrimeBlocks.lambda$static$1

CellDoorBlock.createBlockStateDefinition manually adds HALF, FACING, OPEN, HINGE, POWERED, IN_BARS instead of calling the superclass implementation. This bypasses superclass mixin additions such as Dramatic Doors' WATERLOGGED property. Inspect the exact Dramatic Doors version to verify the contract. Preserve the superclass state definition, then add only this block's additional properties; avoid registering vanilla properties twice. Preserve the superclass-initialized default state and any injected property defaults when adding IN_BARS. Verify the codec, placement, both door halves, rotation, neighbour/fluid updates, saved states, collision shapes, barred appearance, lock interactions, and the deliberate redstone policy. Do not make Dramatic Doors mandatory or blindly add a property without its required behavior. Audit comparable custom subclasses for the same superclass-bypass pattern.

The later unbound mcacrime:cell_door failures in CrimeItems and CrimeBlockEntities follow the block-registration failure. Diagnose subsequent registry errors after fixing the first failure; do not assume every other mod warning is an independent root cause or claim every pack error is thereby fixed.

Confirmed second failure at 10:08:52.258, also the headline of MhpXNKB:
  IllegalStateException: Cannot get config value before config is loaded
  McaInteractionScreenBridge.onScreenInit(McaInteractionScreenBridge.java:105)

That handler reads CLIENT.showButtonOnMcaScreen before checking whether the screen is an MCA InteractScreen. Restrict the handler to its intended screen before accessing configuration, and use a lifecycle-safe loaded-state check or initialized presentation snapshot. Unrelated screens, especially the mod-loading error screen, must not trigger configuration access. Audit other client callbacks for the same lifecycle hazard, including refresh, render, connect/disconnect, and config reload. Do not blanket-catch failures and silently disable the integration forever. Preserve the original loader error when initialization has already failed. Test early screens with configuration deliberately not loaded, plus normal MCA screens after loading, with the button enabled and disabled.

2. MAKE STOLEN GOODS ACTUALLY SEARCHABLE AND RECOVERABLE

Existing infrastructure must be extended, not duplicated: mug/npc/TheftExecutor, NpcMuggingService, StolenGoodsLedger, StolenGoodsRecovery, state/world/StolenGoodsRecord, PropertyEscrow, frisk/InventoryProviders, VillagerInventoryProvider, and FriskTransaction. Currently stolen stacks are stored in the world ledger; confirmed death moves them to owner escrow and attempts delivery. The search providers expose player inventory, equipment, and the MCA villager container, not those ledger-held goods.

Implement one authoritative thief-held inventory representation, with real recoverable contents exposed through the existing search/frisk UI. A ledger-backed inventory adapter is acceptable if the player can actually inspect and transfer its contents; an invisible receipt alone does not satisfy the request. Never maintain two independently claimable copies in the entity inventory and ledger. Keep ownership and transaction provenance separate from stack contents when necessary.

Preserve exact item identity, count, damage, enchantments, custom names, and all NBT/data components supported by the target version. Handle partial stacks and multiple victims. Currency must retain its original provider identity and units; abstract balances must not be materialized as arbitrary coins. Capacity must be reserved before debiting a victim. Failed insertion, persistence, or recovery must not destroy or duplicate property.

Allow an owner to recover their own property through the authorized live-thief search/recovery flow without another theft charge. Keep taking unrelated belongings, other victims' property, and ordinary innocent-NPC inventory subject to the appropriate existing rules. Validate server-side identity, distance, visibility, session, permissions, and slot revision on every transfer. Handle two players searching simultaneously and stale/replayed packets.

On confirmed death, make the recoverable goods available exactly once through the existing loot/recovery experience. Coordinate physical drops, automatic owner delivery, and escrow so one item cannot both drop and return automatically. Preserve a durable fallback for offline owners, full inventories, canceled drops, and unavailable currency providers. Canceled death, resurrection, chunk unload, dimension transfer, or ordinary entity removal must not count as a final death. Survive save/restart and migrate legacy ledger-only holdings without recreating already claimed goods. Do not promise cross-file atomicity unless it is actually implemented; use receipts/reconciliation for ambiguous transfers.

3. MAKE DEFENDING AGAINST A MUGGER LAWFUL

NeoForge's DamageIncidentService/CombatIncidentProcessor/CombatEncounters classify the first damaging action without incorporating the existing NPC mugging threat as a defensive basis. A threatening thief need not deal damage before the victim punches them. Forge main already contains justice/ThiefCombatPolicy, ThiefCombatService, ThiefCombatDecision and native MCA justice integration; these are absent from the inspected NeoForge branch. Examine and adapt the complete behavior, including binding probes, rather than copying one condition.

Capture the defensive justification before the punch, weapon draw, sand effect, session cancellation, or death can remove the mugging session. The threatened player must be able to interrupt an active mugging immediately, without first accepting damage. Retain a bounded, server-authoritative post-threat/recovery window so a fleeing mugger does not become an innocent target one tick later. Integrate this with the established configurable policy for combat against adult Thieves, preserving the project's existing defaults and explicit server choices.

Apply the same decision to melee, projectile and attributed pet damage, non-damaging interruption, lawful lethal recovery, and witness reactions. Prevent incorrect Heat, cases, wanted status, guard aggression, MCA native hearts/gossip/blame, and optional MCA: Reputation penalties for exempt actions. A thief exemption must not authorize attacks on children, guards, innocent villagers, or already restrained prisoners. Do not use appearance, an arbitrary attack-target field, or a stale role record alone as proof. Do not turn a lawful defense into a bounty/reputation farming reward. Test with and without optional companion mods, and test native MCA consequences as well as this mod's ledger.

4. ENSURE NITWITS CANNOT MUG PLAYERS

The inspected WorldCriminalJobService.evaluate(EXECUTION) already calls mayActAsThief, which requires a THIEF record, a permitted occupation status, and a live mcacrime:thief profession. NpcMuggingService calls this at start, continuation, and commit. Therefore the nitwit report is not yet a proven missing profession check.

Reproduce using the reported/supported MCA versions and inspect native profession, displayed profession, record/status, profession synchronization, worksite ownership, lifecycle reconciliation, migration, and all forced/debug/API paths. Distinguish an actual minecraft:nitwit from a canonical Thief incorrectly displayed as Nitwit. Repair the failing binding/UI or bypass rather than adding a redundant check that leaves the cause intact.

Enforce the canonical role consistently: children, actual nitwits, ordinary non-thief villagers, guards/archers/responders, suspended occupations, and unreadable classifications cannot start or finish autonomous theft. Check age at execution as well as assignment. Abort a session immediately if an actor becomes ineligible. Reconcile old contradictory records without losing stolen property, erasing legal history, or overwriting an independently assigned profession. Add diagnostics showing the canonical profession, visible role, occupation status, and exact eligibility refusal.

5. RESTORE FUNCTIONING GUARD COMBAT

Trace GuardEnforcement, GuardChallengeService, NpcCriminalPursuit, NpcArrestService, McaCompat.setGuardTarget/clearGuardTarget, NpcAwareness, LawHold, ResponderAssignments, CrimeActivityRegistry, and the reaction/Townstead adapters. Writing Mob.setTarget or vanilla ATTACK_TARGET is not sufficient evidence that the actual supported MCA brain consumes it and attacks successfully. Verify MCA's live melee and archer behaviors, equipment, reach, navigation, memory lifetime, and interactions with work/rest/panic/escort control.

Separate intentional restraint from failure: guards wait during a valid challenge, do not attack a compliant prisoner, and currently pursue unarmed thieves for contact arrest; force against armed thieves is configurable. Keep those rules while ensuring refusal/timeout, a lawful hostile target, and configured armed-thief force actually lead to pursuit and successful attacks. Check that expired challenges, competing reaction ticks, failed paths, inappropriate ownership filters, or stale hold claims cannot suppress combat indefinitely. Guards must retain normal combat against hostile mobs and stop targeting a player when the relevant legal basis ends.

Reproduce with MCA alone, then relevant optional integrations. Inspect damage cancellation and actual health loss, not just target assignment or animations. Treat Epic Fight/Townstead interactions as conditional test cases, not proven causes from these startup logs. Report compatibility limitations precisely; do not bypass another mod's protections globally or add synthetic damage to conceal a broken AI binding.

6. END RE-ARREST LOOPS AND JAIL SOFTLOCKS

Relevant code: JailService, JailState, JailConfine, CellReleaseHandler, HoldingCellService, SafeCustodyDestination, SentenceAssignmentService, SentenceResolutionService, JusticeService, CrimeState, ArrestService, ArrestReconcile, CustodyService, the physical restraint/tether services, and GuardChallengeService.

Static inspection exposes a credible re-arrest path: JailService.release(SENTENCE_SERVED) resolves sentence-bound cases, but the inspected release/resolution path does not settle their Heat. CrimeState.isWanted reads Heat directly, and NeoForge JusticeService accepts WANTED as an independent detention basis. Reproduce with a sentence short enough that Heat remains above threshold on release. Audit warrants, reports, alerts, refusing/resisting state, masked pursuit, and native MCA hostility too.

Make sentence completion a coherent, idempotent disposition. Resolve only the cases bound to that sentence, retire the corresponding enforcement/Heat obligations exactly once, recompute wanted/warrant state, and release the associated custody, restraints, tethers, challenges, responder assignments, targets, reservations, and HUD state. Preserve unrelated or newly committed crimes and their Heat. Account for decay, partial payments, surrender reductions, and Heat-only administrative sentences without double subtraction or granting a blanket pardon. A short cooldown alone is not a fix: the same settled basis must stay settled after that cooldown and after restart.

Define and test each exit separately: time served, bail/payment, pardon, admin release, captivity cap, invalid/destroyed jail, escape/recapture, and death/respawn. The inspected captivity-cap release clears jail without resolving cases; ensure the safety cap cannot repeatedly put a player into the same sentence. Recovery/admin release should not accidentally erase unrelated crimes, but must terminate the broken custody instance and avoid automatic re-entry into an unrecoverable loop. Genuine new offenses remain enforceable.

Another concrete risk is HoldingCellService.releaseAndDismantle: if no safe exterior stand is found within its search, it returns and leaves the generated cell standing while release proceeds. Give legally released players a safe, bounded fallback exit/unlock/relocation and reliable deferred restoration; never leave them physically trapped, suffocate them, destroy unrelated builds, or teleport into unloaded/unsafe terrain. Confirm final outcomes rather than merely posting a release event.

Trace sentence ticking and persistence through lag, logout/reconnect, restart, death, respawn, dimension changes, chunk unload, disabled settings, missing guards, missing cells, and interrupted escorts. Validate paused/escaped status against actual custody so a physically jailed player cannot have an indefinitely paused sentence due to stale escape state. Preserve intended online-time accounting; do not silently replace it with wall-clock time. Cleanup must finish even if an optional event listener fails. Reconcile orphaned jail/custody/restraint records on load without deleting the whole crime save.

Show the reason for arrest, relevant charges, remaining sentence, any legitimate pause reason, and available release options. Extend existing operator inspection/recovery commands for stuck players, with permission checks, precise reasons, and idempotent repair. Do not require save-file deletion or disabling the whole mod.

VALIDATION AND DELIVERY

Use meaningful behavior tests plus the repository's real-client/dedicated-server gameplay tooling. Existing pure tests are useful but do not establish loader compatibility, successful damage, or physical release. At minimum demonstrate:

1) NeoForge launch without Dramatic Doors and with 3.3.2; cell-door registration/placement/save-load/fluid and lock behavior; early error/title screens with unloaded config; client and dedicated server start successfully.
2) Active mugger punched before dealing damage, mugger interrupted by drawing a weapon, legitimate recovery chase, lawful lethal recovery, and negative controls for innocents/restrained targets; no incorrect Crime or native MCA penalties.
3) Stolen custom items and currency visible/recoverable through a live thief; death recovery; partial/full inventories; offline owners; two searchers; replayed requests; canceled death; save/reload; migrated ledger holdings; no duplication or loss.
4) Real nitwit, ordinary villager, child, guard, valid Thief, stale THIEF record, role change mid-mugging, and misleading UI label. Only canonical eligible actors steal.
5) Melee guard and archer pursue and damage valid hostile targets; challenge wait, refusal/timeout, armed-thief force, unarmed-thief arrest, normal monster combat, Townstead control conflicts, and stand-down behave as intended.
6) Sentence served with residual Heat above threshold, bail, cap, pardon, blocked cell exit, lost cell/dimension/guard, stale escaped flag, reconnect/restart/death, multiple jurisdictions, and crimes committed during custody. No repeated punishment of settled cases and no stuck player; unrelated crimes survive.

Run the appropriate test/check/build, binding probes, packaging/no-static-link checks, and supported-version smoke tests for each modified branch. If environment access blocks a runtime check, identify the exact unverified scenario and provide a reproducible command/checklist; do not call compilation a gameplay pass. Keep MCA Reborn compatibility reflective/isolated as required, and optional integrations optional. Preserve registry IDs, world data, and existing user configuration. Bump data/network versions only when their contracts actually change, with migrations and explicit client/server compatibility behavior.

Provide: a concise root-cause table distinguishing confirmed log failures, reproduced gameplay defects, and remaining uncertainties; implemented changes and touched files; tests executed and outcomes; any remaining limitations; upgrade and existing-save recovery instructions; and the built release artifact paths when the build succeeds. Update changelog and relevant config/API/player documentation. Do not publish or upload a release unless separately requested.

PINNED INVESTIGATION REFERENCES

NeoForge source snapshot:
https://github.com/otectus/MCACrime/tree/943741bfd1f75a79c403da997b99553cb10628e1
Forge source snapshot:
https://github.com/otectus/MCACrime/tree/2fff3f3f0cd6fb5b86cb512418d8f03d79ea1bea
All Java paths above are relative to src/main/java/dev/otectus/mcacrime/ unless explicitly stated otherwise. These references are evidence and starting points, not a substitute for checking the actual branch and shipped jar you fix.

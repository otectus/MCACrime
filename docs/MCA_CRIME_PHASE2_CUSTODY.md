# Phase 2 development: arrest evidence, custody and sentencing

Development date: 2026-09-07. Starting commit: `d0d4934` (maintenance release work already present).

This implements the arrest and custody portion of [the audit specification](MCA_CRIME_AUDIT_IMPLEMENTATION_SPEC.md), building on the witness/intimidation/memory changes in the working tree. It is not a claim that every item in the audit's larger Phase 2 has shipped.

## Implemented behavior

### Arrest evidence

- An NPC pursuit carries the original actionable case. Existing callers that supply a report ID are normalized through that report's incident ID. An arrest on a completed mugging report does not manufacture a second attempted mugging or append caught-in-the-act flags.
- A guard may record an interrupted NPC mugging only while the matching live threat and mug session still exist. It must be alive, awake, able to see both the perpetrator and victim, within the configured response radius, and able to identify the visible perpetrator. The incident is consumed before event callbacks, recorded through `CrimeDetector.commitNpc`, and the mug is aborted before the chase starts. The resulting case survives failed pursuit or capture.
- The arrest itself revalidates case identity, current actionability, responder eligibility, dimension, visibility and two-block reach. Failed capture does not put the thief controller into arrested/captive mode.
- Report authority requires a matching, actionable ledger case with the same offender and offense. Resolution removes enforcement authority immediately, without waiting for report expiry. A recorded null jurisdiction remains null rather than being replaced by a villager's later home.
- The player arrest path assesses locally known cases when a responder is present. Direct administrative jailing and surrender at a jail without a responder retain their existing broad assessment behavior.

### Sentence identity and membership

- `CustodyRecord.sentenceId` is assigned at successful arrest, before escort. Player arrest and NPC arrest share `SentenceAssignmentService`; case membership remains in the immutable ledger records.
- `CrimeWorldData.bindSentence(..., caseIds)` binds an explicit selection. Empty means zero cases. Other offenders, resolved cases and cases assigned to another sentence are excluded.
- A repeated assignment of the same custody sentence is a no-op. It cannot add crimes committed during escort. A different sentence ID is rejected.
- Jail intake adopts the durable custody identity if arrest state is unavailable. Conflicting identities are refused before teleportation. Direct jail intake assesses once; sentence extensions do not sweep in later cases.
- New jail states mark membership as established even when their assessment was empty. Login no longer mistakes an empty assessment for missing legacy data.
- NPC release reads its sentence ID from custody, so configured or fallback detention without a generated cell can still settle the assessed cases.
- Failed intake/recovery removes outstanding case membership without resolving those cases. Terminal resolution history keeps its sentence identity. A later valid arrest can assess outstanding cases again.
- Immediate intake failure propagates to the arrest caller before surrender credit or delivery payout. Existing public void escort methods remain as compatibility wrappers around checked methods.

### Escort and controller recovery

- NPC escort time accumulates in persisted `realTicksHeld` while the NPC is loaded. Replacing a guard or restarting the server does not restart the timeout. A zero timeout means immediate intake, matching player escort behavior.
- Timeout completes intake through the safe destination validator. If neither the destination nor nearby fallback is safe, custody is released and its cases remain outstanding.
- A missing NPC detention dimension releases custody rather than leaving an untickable record.
- Guard selection consults existing custody and pursuit assignments. Another player's approach, stand-down, or escort cleanup cannot borrow a busy guard or clear its escort/pursuit AI hold.
- Guard UUIDs are not treated as disconnected player captors by the captivity ticker.
- Thief controllers reconcile against durable custody at tracking and before decisions. Loaded captives cannot resume scouting/mugging; a missed release callback transitions into cooldown once.
- NPC mugging validates participant custody and eligibility at start and again before property transfer. Captive thieves, captive/jailed victims, creative/spectator victims, dead entities and dimension changes cannot complete a theft.

### Physical escape and conversations

- PHYSICAL escape pauses both sentence credit and captivity-cap credit. Offline or escaped time is not time served. Entering the jail region resumes credit.
- Guards approach an escaped prisoner and recapture at visible close range. Recapture returns the prisoner to the existing validated jail anchor without reassessing charges, replacing sentence identity, or applying another surrender discount.
- An escaped prisoner can surrender to a valid nearby authority to resume the same sentence. A prisoner still serving cannot repeatedly reduce the sentence.
- Challenge opening, responses and expiry revalidate the live guard and conversation range/visibility. Missing authority or resolved legal basis closes into recovery, without a refusal penalty.
- A failed fine payment leaves the challenge open. It does not itself set resisting arrest; the existing response deadline still applies.

## Main implementation boundaries

| System | Responsibilities |
| --- | --- |
| `enforcement/NpcArrestEvidence` | Resolve report/case provenance; reject completed or stale threat snapshots |
| `enforcement/NpcArrestService`, `NpcCriminalPursuit` | Witnessed intervention, capture checks, original-case arrest, pursuit revalidation |
| `ledger/SentenceAssignmentService` | Freeze membership, reconcile intake identity, detach outstanding cases after failed custody |
| `state/world/CrimeWorldData` | Explicit case selection and existing ledger persistence |
| `captivity/CustodyRecord`, `CustodyService` | Persist sentence identity, preserve it through transfer, release cleanup |
| `enforcement/NpcCustodyService` | Bounded escort, safe intake, cell-independent sentence release, legacy NPC reconciliation |
| `enforcement/ArrestService`, `EscortService`, `jail/JailService` | Player assessment, checked immediate intake, identity propagation, recapture |
| `enforcement/ResponderAssignments` | Query guard ownership without a second persistent ownership index |
| `memory/ReportService` | Match testimony to live cases and retain original jurisdiction |
| `ai/thief/ThiefBehaviorService`, `mug/npc/NpcMuggingService` | Reconcile custody and prevent invalid property transfers |

All MCA operations continue through `McaCompat`. There are no new mixins, dependencies, registrations, packet types or static MCA links in this pass. Optional integration notifications continue through the existing record and NPC event paths.

## Save and configuration compatibility

- The working release already uses world schema 9 for the witness/memory update. This pass adds optional `custody.sentenceId` to that unreleased schema; it does not renumber the release or network protocol.
- Older custody without a sentence ID is distinguishable from a new empty assessment. NPC reconciliation adopts an existing cell's identity where available, otherwise creates one, then performs the existing explicitly marked legacy membership inference once. Already-bound cases and cells marked as migrated are preserved.
- Legacy inference cannot reconstruct the actual historical charge list. It retains the previous policy of adopting eligible outstanding cases and marking `legacy_sentence_inferred`. New arrests never use that inference.
- Outstanding cases previously fabricated by an older arrest are not automatically deleted: there is insufficient saved provenance to distinguish every duplicate from a real second attempt. Review and pardon known duplicates administratively.
- Existing `arrestEscortTimeoutTicks`, `npcEscortOrphanTicks`, `guardChallengeRadius`, `surrenderNearRadius`, guard scan/navigation intervals, and cell settings are reused. No additional common/client config keys are introduced.
- Existing owner, restraint, custody clock and case fields retain their NBT representations. Sentence assignment refuses read-only/future-schema stores before mutation. Rollbacks should restore the matching save backup; older schema-8 builds already treat schema-9 data as future data.
- NPC jail time retains its existing server-tick accounting, including unloaded sentenced NPCs; only NPC escort timeout counts loaded custody time. Player jail time remains online time in custody.

## Automated validation

Run `gradlew.bat build --offline`. It compiles/reobfuscates the mod, runs the JUnit suite, and checks the distributable for shaded companion, MCA and Architectury classes.

Final verification on 2026-09-07: **900 tests passed, zero failures/errors/skips**, successful reobfuscated build and `checkJarContents`, and a clean `git diff --check`. This pass adds 25 tests. Build log: `build/custody-phase-build.log`. Artifact: `build/libs/mcacrime-0.6.0.jar` (1,219,230 bytes), SHA-256 `9c365bcc021628c9035f323d35807eecface4dabc7cebb176d182492be6191db`.

New regression coverage is in:

- `ArrestSentenceAssignmentTest`: explicit/empty assessments, replay, identity conflicts, world reload, no-cell NPC resolution, failed intake, legacy data, read-only stores, guard ownership, persisted escort deadline.
- `NpcArrestEvidenceTest`: original completed crime on report-based arrest, resolved/missing/mismatched cases, stale threats, defensive incident flag copies.
- `ReportCaseAuthorityTest`: resolved or missing cases, mismatched suspect/offense, anonymous reports, escaped case actionability.
- `ThiefCustodyRecoveryTest`: reload while held, missed release callback, one-time cooldown, ordinary behavior preservation.
- `JailTickTest`: physical escape pauses sentence/cap credit across NBT reload and resumes after return.

The existing sentence binding, surrender transaction, capture, safe destination, API, networking, MCA binding and jar-content tests remain part of the full run. Unit tests exercise service/data policy boundaries; they do not prove live MCA navigation, rendering or multiplayer timing.

## Required in-game verification before release

1. Have a witness report one completed NPC mugging. Let a guard arrest the NPC. Confirm one original mugging case, no added attempt, one custody record and that exact case served on release.
2. Interrupt a live NPC mugging with a guard who can see both actors. Confirm one attempted-mugging case, no property transfer, and no duplicate when a second guard arrives. Resolve the case during pursuit and confirm the guard gives up.
3. Repeat with a wall between guard and suspect, a hidden victim, an invisible suspect, a dead guard, and a dimension change. Confirm no invalid immediate capture.
4. Arrest with generated cells disabled; test a configured jail and safe fallback. Save/restart before arrival and while serving. Confirm the same sentence ID and assessed case IDs, with eventual release.
5. Commit another crime during escort and while escaped. Complete the original sentence; later/unassessed cases must remain outstanding. Repeat after resolving every original case during escort to exercise an empty live membership set.
6. Block the NPC escort route, replace/kill the guard, and restart mid-escort. Confirm accumulated loaded time reaches the original timeout and recovery either safely detains or releases; it never teleports into a hazard.
7. Use two players and an NPC prisoner near several guards. Verify approaching an innocent player, opening another encounter, paying a fine, or surrendering does not steal the assigned escort guard or clear its AI hold.
8. Reload a jailed/captive thief. Confirm no scouting, threat, or theft until release, followed by cooldown. Restrain a thief/victim during an active mugging and confirm no property changes on completion.
9. Escape a PHYSICAL jail, remain outside past the original remaining term and captivity cap, and relog. Confirm both clocks stay paused. Return, surrender, and let a guard recapture in separate runs; verify the original sentence resumes with no repeat waiver and no newly adopted charges.
10. Kill/unload the challenging guard, leave conversation range, resolve its last case, or submit a response from an obsolete screen. Confirm safe closure and no refusal penalty. Try paying without enough money; confirm surrender remains available until the unchanged deadline.
11. Make an immediate arrest destination unsafe/unavailable. Confirm no success credit, payout, retained cell or stuck custody; original cases remain unresolved and can be assessed later.

## Remaining audit work

Follow-up: [local law and settlement](MCA_CRIME_PHASE2_JUSTICE.md) implements the scoped guard assessment and displayed-quote work identified below. The rest of this section records the scope remaining at the end of this custody pass.

This pass addresses the arrest/sentence portions of B03, B13/B14, B18–B21 and B24. It does not complete every subissue under those findings.

The rest of Phase 2 still needs a common incident transaction coordinator for all player/NPC/ransom detection paths, a complete shared law/settlement decision model, post-damage classification and explicit self-defense rules. Guard fine quotes and commands still need fully scoped case selection and displayed-quote revalidation; this pass only changes failed-payment escalation. Global heat, historical crimes and public evidence remain distinct inputs whose balance requires that follow-up. Last-seen search, broader thief target/risk policy, full custody projection consolidation, captivity quotas and configurable locked-cuff escape work are also separate work items.

Treat those as follow-up implementation tasks and the in-game list above as release validation, rather than describing the entire audit as implemented.

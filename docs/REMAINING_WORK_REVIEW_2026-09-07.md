# MCA: Crime remaining-work review — 2026-09-07

Reviewed the working tree on `d0d4934`, including the existing uncommitted 0.6.0 witness/memory, custody, justice, incident and death/property recovery work. This is a review of the current implementation, not a repetition of the older design checklists. No gameplay code was changed during this review.

The mod has substantial working infrastructure and regression coverage, but I would hold a release for the money/property integrity issues below and production MCA validation. The large feature expansions in the original audit are optional roadmap work, not prerequisites for calling the existing gameplay complete.

## Verification and limits

### HUD and AI follow-up (2026-09-08)

Implemented the reported HUD/cell/AI refinements within 0.6.0: shared bottom-left Heat/Sentence
placement with legacy-default migration and chat clearance; a 15-second confrontation window with
bounded first-display acknowledgment; normalized crime movement and one flee controller; responder
exclusion from civilian fear states; reachable exterior intake stands, detour-aware progress and
bounded route segments; occupancy rejection before construction and safe bystander repair for old
cells. These changes do not close R05/R06 or replace production gameplay validation. Regression
coverage and the live matrix are recorded in [HUD and AI verification](HUD_AI_VERIFICATION.md).

### Development follow-up (same-day working tree)

The original findings below are retained as the review baseline. Development using this review has
now implemented the following; this does **not** constitute a release sign-off:

| Finding | Implementation status |
|---|---|
| R01 | PREPARED capacity reserved before provider calls; frozen/full stores and mismatched replays refused; provider pinned for each live transfer; ambiguous debit failures retained. Added capacity, reentry, debit-exception and changed-replay regressions. Cross-file crash atomicity and operator reconciliation remain open. |
| R02 | NPC removal runs inside a provenance-capacity reservation; a full table aborts without removal and nested writes cannot consume the last slot. Added removal/composition regressions. External provider exceptions and cross-file crash recovery remain limitations. |
| R03 | Arrest recovery now uses selected-owner escrow and shared provider-aware delivery receipts. Nearby-owner selection is preserved; undelivered property remains recorded. Added owner-selection/provider regression; existing recovery tests cover delivery failures. Live inventory/provider combinations still need verification. |
| R04 | Job assignment activates loaded thieves; clearing/changing a job aborts sessions and removes controllers. Config reload repopulates enabled thieves. Thinking and theft commit recheck occupation. Live assignment/reload validation and deferred profession presentation for unloaded villagers remain open. |
| R07 | Reloads filter to Crime's own COMMON spec, repeat config validation and schedule live changes on the server thread. Cuff attempts recheck current pick policy. A fully immutable policy snapshot and live reload matrix remain open. |
| R08 | Locked cuffs have a finite `escapeWorkTicksLockedCuffs` default of 1,200 ticks when Locks is absent and timed escape chance is nonzero. Locks-present cuff escapes instead require its native minigame. |
| R09 | Sentence views now expose the real sentence UUID and exact actionable case membership, with a projection regression. Custody identity/link semantics remain deliberately unfilled. |
| R10 | New bounty entitlements reserve provider-bound payment receipts before credit. Exact emerald remainders remain collectible; ambiguous external outcomes suspend retry. Pending claims survive expiry. Added permission-three receipt/escrow/quarantine/audit inspection, JSON export, revision-checked acknowledgement/rearming and durable operator notes. Legacy unknown payouts, audit archival and production provider/inventory validation remain open. |

**Locks Reforged addition:** native menu, server-validated pins, persistent custody combination,
five-pin ordinary cuffs and seven-pin locked cuffs. `kidnapping.cuffEscapeRequiresLockpick` defaults
to `false`; when enabled, a lockpick anywhere in inventory is required throughout the attempt. Cuff
attempts use itemless miss/reset rules without item wear. Timed and distance escapes cannot bypass
the puzzle. Lawful cuff escapes retain sentence/surrender credit and count as jailbreak. Rope and
authorized release/rescue/failsafe paths retain their existing behavior.

The adapter compiles against the published Locks Reforged 1.7.3 dependency, which is not bundled.
The 1.7.3/1.7.4 native API shapes and newer local source were inspected. In-game minigame operation,
production dedicated-server loading, two-client behavior and the newer native protocol still require
live validation. An incompatible installed adapter reports an error and does not silently fall back
to timed cuff escape. See [the cuff verification checklist](CUFF_LOCKPICKING_VERIFICATION.md).

**Next priorities:** R05/R06 (local sentencing and jurisdiction), production validation of bounty
collection/reconciliation, deferred profession presentation, immutable reload policy, custody identity and the
release-validation work below. The original capacity probe expected the old failing behavior; use
`ReviewCapacityRegressionTest` for the corrected behavior.

Follow-up verification: final offline `build` passed, including fresh execution of **1,017 tests
across 129 suites (zero failures, errors or skips)**, reobfuscation and `checkJarContents`.
Log: `build/gradle-MCACrime-build-20260907-144042.log`. Artifact:
`build/libs/mcacrime-0.6.0.jar`. `git diff --check` passed. No live Minecraft session was run.

**Bounty/reconciliation follow-up:** world schema is now **10** (network protocol remains 10),
protecting queued payments and operator audit records from older builds. `/crime collectbounty`
collects known unpaid rewards, also attempted at login. `/crime recovery` offers bounded inspection,
full-fidelity export and explicit bookkeeping corrections with stale-token checks. Neither automatic
collection nor operator acknowledgement replays uncertain external credits or completed bounty effects.
See [recovery operations](RECOVERY_OPERATIONS.md) for exact semantics, commands and crash limits.

The final offline build for this follow-up passed **1,035 tests across 132 suites**, with zero failures,
errors or skips, plus reobfuscation and clean-jar checks. Log:
`build/gradle-MCACrime-build-20260907-154439.log`. Artifact remains `build/libs/mcacrime-0.6.0.jar`.
`git diff --check` passed. No live Minecraft session/provider matrix or actual command export was run;
the tests cover services, persistence, permission gating and command syntax. The cross-file save
window remains explicitly unresolved.

### Original review verification

- Offline `build` passed: compilation, reobfuscation and `checkJarContents`. Log: `build/gradle-MCACrime-build-20260907-135505.log`.
- A subsequent forced test run passed **1,001 tests across 126 suites, zero failures, errors or skips**. Log: `build/gradle-MCACrime-test-20260907-135607.log`. This was a fresh run; the earlier build reused test results.
- The local build had both Reputation and Quests sibling class directories available. This validates that local adapter build configuration, not a reproducible clean-clone or every runtime companion combination.
- Isolated in-memory capacity probes ran against the compiled production classes. Log: `build/gradle-MCACrime-auditProbe-20260907-140047.log`. Probe source and Gradle task are under ignored `build/review-probes/`.
- No production Minecraft instance, dedicated server, two-client session or density profile was run. Static MCA binding probes and service tests do not establish live navigation, rendering, event ordering with other mods or server tick performance.
- `git diff --check` found no whitespace errors; Git emitted line-ending warnings.

Evidence labels below distinguish reproduced service behavior, direct code findings and work still requiring live validation. P1 means address before release; P2 means important correctness or completion work; P3 means refinement or documentation.

## Prioritized findings

### R01 — P1: Transfers debit before securing receipt capacity

**Reproduced.** [EconomicTransactionService.java](C:/Projects/MCACrime/src/main/java/dev/otectus/mcacrime/economy/account/EconomicTransactionService.java:66) calls the external debit before storing its receipt and ignores both `putTransaction` results. [CrimeWorldData.java](C:/Projects/MCACrime/src/main/java/dev/otectus/mcacrime/state/world/CrimeWorldData.java:1245) rejects new receipts when the 4,096-row table is full.

The probe filled the table with unresolved transfers, then attempted a 40-unit transfer whose credit failed:

```text
capacity=4096 debit=40 creditAttempts=1
returned=NEEDS_RECONCILIATION persisted=false legacyMarker=true
```

Money was taken, but the amount/provider/parties needed for recovery were not retained. A legacy UUID marker is insufficient to reconcile the debt. The method also returns an existing receipt solely by transaction ID without checking that the new request has matching terms, and the low-level transfer lacks its own frozen-store guard.

**Remaining work:** reserve a PREPARED receipt before any debit; reject frozen/full stores; validate replay identity and terms; retain the same currency provider throughout the operation. Clarify that `setDirty()` schedules saving and is not a durable disk commit. Cover capacity, failed debit/credit, callback reentry, mismatched replay and save/reload boundaries in the transfer service tests.

### R02 — P1: NPC theft can remove property without recording it

**Code-confirmed caller gap; storage refusal reproduced.** [NpcMuggingService.java](C:/Projects/MCACrime/src/main/java/dev/otectus/mcacrime/mug/npc/NpcMuggingService.java:301) commits inventory/currency removal before calling `StolenGoodsLedger.record`, then ignores its return value. [StolenGoodsLedger.java](C:/Projects/MCACrime/src/main/java/dev/otectus/mcacrime/mug/npc/StolenGoodsLedger.java:71) explicitly says the caller must reserve provenance capacity, but this caller does not.

At 4,096 stolen-goods records, the probe supplied a completed 40-unit theft and recording returned `null`. The live caller would continue after the removal with no new recovery record. This is an ordinary capacity failure, separate from the documented cross-file crash window.

**Remaining work:** reserve provenance before `TheftExecutor.commit`; abort without removing anything when unavailable; finalize the exact removed stack/provider into the reserved record. Add a service-composition regression proving the victim keeps their property when storage is full and that callback changes cannot consume the reservation.

### R03 — P1: Arrest recovery still bypasses the new safe recovery path

**Code-confirmed.** [StolenGoodsReturn.java](C:/Projects/MCACrime/src/main/java/dev/otectus/mcacrime/mug/npc/StolenGoodsReturn.java:115) removes owner records through the legacy `claimForOwner` overload before `give` runs. `give` uses `Currencies.active()` rather than the record's saved provider, credits directly, and drops item overflow at the owner's feet without retaining delivery state.

A provider change between theft and arrest can return the wrong currency. A failed credit, canceled drop or unavailable item can lose the already-removed record. The confirmed-death path now uses owner escrow and delivery receipts; arrest does not have the same guarantee.

**Remaining work:** route arrest returns through provider-aware owner escrow and the shared delivery receipt machinery. Preserve the configured nearby-owner policy if desired, but retain every undelivered remainder. Test provider replacement, full inventory, canceled drops, missing items, failed credit and reload after partial delivery.

### R04 — P2: Thief job changes do not reconcile live controllers

**Code-confirmed.** [WorldCriminalJobService.java](C:/Projects/MCACrime/src/main/java/dev/otectus/mcacrime/job/WorldCriminalJobService.java:108) writes/presents the job and posts `CriminalJobChangedEvent`; no production subscriber handles that event. Normal tracking starts only on entity join in [ThiefTicker.java](C:/Projects/MCACrime/src/main/java/dev/otectus/mcacrime/ai/thief/ThiefTicker.java:51). The debug `forceTarget` path also tracks, which can mask the issue during manual testing.

Consequently, automatically assigning or command-assigning THIEF to an already-loaded villager does not activate its controller until unload/reload or `/crime mugtest`. Turning thieves off removes controllers; turning the setting back on does not repopulate them. Conversely, active controllers do not recheck their criminal job during ordinary thinking, so clearing/changing a job can leave thief behavior running. NPC mug participant validation checks custody/liveness, but not current criminal occupation.

**Remaining work:** reconcile tracking and active mug sessions on job changes and enable/disable transitions; recheck occupation before committing theft. Test assignment, clearing, THIEF→FENCE, disable→enable and restart without using the debug force command. Reconcile deferred profession presentation on entity reload too; its current refresh entry point is config reload.

### R05 — P2: Local case selection still uses global sentencing and surrender Heat

**Code-confirmed and acknowledged in the Phase 2 notes.** [ArrestService.java](C:/Projects/MCACrime/src/main/java/dev/otectus/mcacrime/enforcement/ArrestService.java:146) selects the responder's local cases, but computes sentence length from the player's aggregate Heat. [SurrenderService.java](C:/Projects/MCACrime/src/main/java/dev/otectus/mcacrime/economy/SurrenderService.java:94) clamps aggregate Heat below the jailable threshold, and writes that global result after acceptance.

A small village-A case can therefore be sentenced using Heat from village B/private cases; surrendering over A's case can also reduce pressure generated elsewhere. The newer fine path is scoped more carefully, so the discrepancy is now particularly visible.

**Remaining work:** persist attributable/local Heat and use one assessed disposition for sentence, payment and surrender. Until then, make any aggregate fallback explicit. Test A/B/private cases after decay, surrender, partial settlement, recapture and restart. Preserve the intentional voluntary whole-record `/crime payfine` behavior outside encounters unless the product policy is deliberately changed.

### R06 — P2: Jurisdiction and lawful force remain split across models

**Confirmed architectural gap.** [IncidentService.java](C:/Projects/MCACrime/src/main/java/dev/otectus/mcacrime/incident/IncidentService.java:154) still anchors NPC crime jurisdiction to the thief's home and player crimes to the victim's home. [ReportService.java](C:/Projects/MCACrime/src/main/java/dev/otectus/mcacrime/memory/ReportService.java:282) returns no jurisdiction for non-MCA responders. The default local evidence selection cannot match a null responder jurisdiction to reported cases.

Meanwhile `JusticeService` governs guard knowledge, but `OutlawResolver` still derives combat/lethal/bounty permissions from aggregate Wanted/band/resistance state. Wilderness law, crime-location jurisdiction, non-MCA responder jurisdiction and configurable force tiers are not complete.

**Remaining work:** define these policies explicitly and carry observer/jurisdiction/force context through a shared legal decision. Cover visiting thieves, traveling villagers, player victims, home-less villagers and configured external guard entities. Do not implement local law by making unknown jurisdiction globally authoritative.

### R07 — P2: Config reloads are not isolated or scheduled safely

**Code-confirmed isolation gap; live threading behavior needs validation.** [McaCrime.java](C:/Projects/MCACrime/src/main/java/dev/otectus/mcacrime/McaCrime.java:62) handles every COMMON config reload without checking the owning mod/config spec. It immediately reloads currency, sends packets, mutates villager presentation and rebuilds fence goods, without a server-thread handoff. The reload handler also does not run the startup validation pass.

**Remaining work:** filter to Crime's COMMON spec, validate a complete policy snapshot and apply world/entity changes on the server thread. Give active sessions defined behavior when relevant policy changes. Test another mod's config reload, invalid Crime config, currency changes and loaded/unloaded criminal presentation.

### R08 — P2: Nonzero locked-cuff escape chance is unusable

**Code-confirmed.** [CustodyService.java](C:/Projects/MCACrime/src/main/java/dev/otectus/mcacrime/captivity/CustodyService.java:630) sets required work for locked cuffs to `Integer.MAX_VALUE`, even though `restraintEscapeChanceLockedCuffs` accepts values above zero and permits starting the attempt. That is roughly 3.4 years at 20 ticks/second, exceeding even the maximum configured captivity cap.

**Remaining work:** add a finite locked-cuff work-duration policy when probabilistic escape is enabled, or reject/deprecate that configuration and consistently describe key/rescue-only escape. Test both zero and nonzero chance, visible progress and restart continuation.

### R09 — P2: Public sentence and custody projections remain incomplete

**Code-confirmed.** [McaCrimeApi.java](C:/Projects/MCACrime/src/main/java/dev/otectus/mcacrime/api/McaCrimeApi.java:258) returns an empty sentence ID and empty linked-case set. The sentence ID already exists on `JailState`, and `CrimeWorldData.casesForSentence` supplies the membership. The custody projection likewise always returns empty custody/link IDs.

**Remaining work:** populate available sentence identity and case membership now; define the canonical identity/link semantics needed for custody instead of inventing IDs at read time. Add public-facade tests against arrest, escort, jail, recapture and reload. Companions need these fields to match progress/reconciliation to the right case.

### R10 — P2: Bounty failures and reconciliation debts lack a complete recovery workflow

**Code-confirmed and documented limitation.** [BountyService.java](C:/Projects/MCACrime/src/main/java/dev/otectus/mcacrime/bounty/BountyService.java:315) consumes a claim before `finishPayment` invokes `currency.credit`. A provider exception can consume the entitlement without delivering payment; there is no bounty-payment escrow. The newer death finality checks establish when a claim is legitimate, not whether its money arrived.

Uncertain economic/property transfers deliberately stop automatic retries. [CrimeCommand.java](C:/Projects/MCACrime/src/main/java/dev/otectus/mcacrime/command/CrimeCommand.java:108) exposes ledger/debug/release tools, but no receipt/escrow/quarantine inspection and explicit reconciliation workflow. Preserving a debt without a supported way to inspect and resolve it leaves operator recovery unfinished.

**Remaining work:** receipt-backed bounty delivery and restricted inspect/export/resolve tooling for retained debts. Record operator decisions and require explicit handling of ambiguous external credits; automatically retrying them is unsafe. Test successful, failed and ambiguous delivery across restart. Broader bounty reward lots, local budgets and daily caps remain separate balancing work.

## Completion and refinement backlog

| Priority | Work | Evidence and acceptance target |
|---|---|---|
| P1 release gate | Production MCA integration validation | Execute the current 0.6.0 and Phase 2 checklists with MCA alone, companions present/absent, dedicated server and two clients. Retain actual results, versions, logs and artifact hashes. Check delayed reports, threat interruption, canceled deaths, restitution, arrest ownership and restart recovery. No checked-off runtime evidence was found in the reviewed checklists. |
| P2 | Reproducible builds and automated release checks | There is no `.github` workflow in this checkout and no GameTests. `build.gradle:145` and `:163` consume unversioned sibling class directories and silently omit adapters when absent. Pin companion API inputs/build revisions; exercise standalone and adapter-enabled builds, probe matrix, headless production startup and artifact checks in CI. |
| P2 | Search behavior that loses sight fairly | `NpcCriminalPursuit.java:180` repaths to the thief's exact live coordinates without requiring current sight. Player guard approach similarly uses live coordinates. Add bounded last-seen/search states and de-escalation; arrest reach/LOS checks already exist and should be preserved. |
| P2 | Performance evidence and budgets | Run the documented 300-villager density/churn profile, multiple offenders, repeated aim changes and long saves. Measure evidence selection, reaction/thief scans, navigation requests, SavedData size and maintenance spikes. Some periodic full-entity work still exists in `CrimeMaintenanceSweep`; throttling alone is not measurement. |
| P2 | Canonical custody and runtime ownership | Finish consolidating `CustodyRecord`, player jail/captor references, `ArrestState` and transient controllers. Existing cleanup is substantial; do not characterize all static maps as leaking. Prove consistent projections across death, logout, dimension travel, restart, quota settings and optional-system shutdown. |
| P3 | Dossier and timer clarity | The dossier packet has a witnessed boolean but no report confidence/status or explanation of the guard's knowledge; it caps rows without pagination. Distinguish witnessed, reported and actionable. `ClientSelfData.tick` always decrements remaining sentence time although PHYSICAL escape pauses the server clock; include a paused/serving state. Keep compact-screen, keyboard, narrator and two-client UI checks. |
| P3 | Minimize client disclosure | `CrimeNetwork.broadcastRestraint` sends to all clients and login bulk sync sends global restrained subjects. Prefer subject/tracking-client scope, including appropriate removal messages, and verify late tracking. Criminal-job updates already demonstrate tracking-scoped delivery. |
| P3 | Resolve inert configuration | `karma.rewardWeights`, `antifarm` and legacy `npccrime` keys remain reserved/unwired. Decide which to deprecate versus implement. Trade/gift rewards and NPC-on-NPC crime are explicitly excluded by current product scope; do not add them merely to make every old config key active. |
| P3 | Correct release documentation | README has eight broken local link occurrences pointing to documents now under `docs/`. It describes seven crimes/eleven API events despite later additions. CONFIG says no positive Karma source exists, but `BountyService` grants configured bounty Karma. Phase 7 still describes death drops, while current recovery uses escrow. Align Minecraft/Forge support claims and metadata ranges with tested versions. |
| P3 | Localization and roadmap hygiene | Only `en_us.json` ships; the suite plan's Brazilian Portuguese acceptance criterion is unmet if retained. `MODMAP` still has an empty roadmap template. Replace historical unchecked implementation lists with a current status register: implemented, runtime-unverified, deferred or rejected. |

## What is already implemented

Do not reopen these as wholly missing features: final-damage/confirmed-death classification; explicit aggression provenance; an incident coordinator; confidence-aware observations and reports; dynamic intimidation and persistent victim memory; scoped guard fine quotes and revision validation; case-bound player/NPC sentences; NPC arrest evidence checks and escort deadlines; custody-aware thief pause/release; confirmed-death owner escrow; schema-9 migration and future-schema protections; directed/bounded packets; persistent fence stock; and cross-version reflective MCA probes.

Their service tests are useful evidence. Their production MCA behavior still needs the release checks above. Older phase notes sometimes list work completed by the subsequent phase; use the follow-up links and current code before treating those lists as open tasks.

## Optional enhancements, after the existing loops are reliable

The audit specification's later phases propose ownership-aware container theft/trespass/pickpocketing, contraband searches and evidence lockers, richer witness negotiation, restorative sentence activities, case-specific bounty boards/NPC warrants/hunter parties, guard posts/patrol/rescue, and datapack law profiles. These are substantial additions with storage, attribution, UI and compatibility dependencies. They should be separate opt-in milestones rather than bundled into the remaining integrity fixes.

Recommended order: **R01–R03 money/property guarantees → R04/R07 live lifecycle correctness → R05/R06 local law/dispositions → R08–R10 escape/API/recovery completion → production matrix and profiling → UI/docs/localization → optional expansions.** Add regressions that exercise each failing production composition; another passing pure-policy test alone would not cover the missing callers found here.

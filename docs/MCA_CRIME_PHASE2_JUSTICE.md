# Phase 2 development: local law and displayed settlements

Development date: 2026-09-07. Starting commit: `d0d4934`, with the witness/memory and
[custody pass](MCA_CRIME_PHASE2_CUSTODY.md) already present as uncommitted development work.
This is the next bounded portion of audit Phase 2, not completion of the entire audit phase.

## Implemented behavior

- `JusticeService` produces an immutable `LegalDecision` with local cases and named authority
  reasons. It reads indexed reports once per assessment and requires a matching actionable case,
  identification confidence, unexpired testimony and the configured jurisdiction policy.
- Guard pursuit, challenge, review, payment and responder-based player arrest use this assessment.
  Low-Heat reported cases can still receive a challenge. Wanted Heat and active refusal now
  authorize interception independently of local reports, including command-set Heat. They do
  not reveal private or remote cases or expand a guard's case list or fine.
- Existing command/jailbreak evidence and observations-disabled legacy behavior remain explicit
  reasons. Escape and active unlawful captivity remain independent intervention reasons.
- A guard's fine prices exactly its locally known cases. It cannot silently add an unreported
  or remote case, or price the fine from whole-world Heat. Its Heat reduction is capped by the
  selected cases' recorded contribution rather than resetting the aggregate. An empty explicit selection
  means nothing owed; missing, foreign, resolved or duplicate IDs reject the entire selection.
- Local finability applies the Heat threshold to the lesser of current Heat and the selected
  cases' generated Heat. Case prices use assessed amounts or the existing per-case calculation.
  Mandatory-custody and sentence-bound cases reject payment. The configured Outlaw payment gate
  applies even when Heat has decayed to zero.
- `DispositionService` retains the displayed offer, policy, band, currency identity and immutable
  assessment. Before a payment it checks live case membership, resolution, penalties, flags,
  sentence binding, jurisdiction, authority basis, Heat, config, currency and offer expiry.
- A changed offer refreshes the same encounter with a higher revision. The old click takes nothing;
  another response accepts the new offer. Requoting preserves the original response deadline.
- Guard responses carry the encounter revision. The screen updates its buttons for a new quote,
  disables repeated payment clicks while waiting, and stays open after insufficient funds or a
  stale quote. The server acknowledges failed payment so surrender and another attempt remain usable.
- `/crime payfine` and the self-action menu answer the displayed offer while a challenge is open.
  The private dossier shows that offer's amount during the encounter. Outside an encounter these
  paths retain voluntary whole-record settlement, including unreported cases and aggregate Heat.
  This compatibility behavior is deliberate; it does not give guards knowledge of those cases.
- Surrender during a challenge retains the owning guard, even if another village's guard is closer.
  If that guard becomes unavailable, it cannot silently fall back to another jurisdiction or jail.
- Payment preflights each case once, checks for callback mutation before debit, commits all selected
  cases before notifying listeners, and updates live Heat before case notifications. A failing
  resolution listener is logged without undoing settlement or suppressing subsequent notifications.
- `/crime debug guards` adds up to 16 nearby responders' jurisdictions, legal reasons and case IDs,
  plus the active encounter's revision, price and remaining time. Case lists are capped for output.

## Compatibility

Mod version remains 0.6.0 and world schema remains 9. Network protocol changes from 9 to **10**
because both guard packet forms now carry a revision. Older clients fail the existing handshake
rather than misreading a quote. Existing in-process constructors remain available.

No new config, dependency, registration, mixin or public API generation is introduced. New legal
and offer records are temporary projections; cases/reports/custody retain their saved representations.
Null jurisdiction keeps its existing conservative meaning; it is not promoted to a global warrant.

## Automated verification

The initial project-local offline cache lacked MixinGradle. Verification uses the existing user cache:

```powershell
$env:GRADLE_USER_HOME = 'C:\Users\crims\.gradle'
& 'C:\Projects\.mcmod-tools\gradlew-quiet.ps1' -Project 'C:\Projects\MCACrime' -Task build -GradleArgs @('--offline') -LogDir 'C:\Projects\MCACrime\build'
```

Regression coverage in `LocalJusticeSettlementTest` exercises production evidence selection,
offer validation and payment composition: local/private/remote cases, confidence, expiry, resolution,
dimension, legacy authority, escape/captivity, empty and invalid selections, Heat preservation,
penalty and custody changes, config/band/currency changes, encounter replay/deadlines, save reload,
read-only data, insufficient funds, callback mutation, preflight ordering and notification failure.
`PacketBoundsTest` exercises offer/response revision round trips and rejects negative revisions.

Final verification: **924 tests passed, zero failures/errors/skips**, including 24 new tests in this
pass. The offline `build` completed compilation, reobfuscation and `checkJarContents` successfully;
`git diff --check` was clean. Log: `build/gradle-MCACrime-build-20260907-050924.log`.
Artifact: `build/libs/mcacrime-0.6.0.jar` (1,234,554 bytes), SHA-256
`0ec35c03ea52771dc251ab15e121b2aec1fd3f375a0f2ee197dc5b9e0f33c58f`.
These are automated service and codec checks, not proof of live MCA navigation or two-client rendering.

## In-game verification before release

1. Give the offender a small reported case in village A, an unreported serious case and a reported
   case in village B. Guard A must count, review, quote and settle only A's case; B/private cases
   and their remaining Heat persist. Compare `/crime debug guards` with the ledger.
2. Open A's offer, then resolve/reprice its case, add another local report, bind the case to a
   sentence, or change pricing/currency config. The first old payment response takes nothing;
   the refreshed amount requires another click and the response deadline does not restart.
3. Attempt payment without enough money. The same screen stays open with surrender available;
   adding money permits another attempt. Rapid/delayed duplicate clicks debit at most once.
4. Exercise `/crime payfine` and the Crime action menu during that encounter and outside it.
   The former settles the guard's selection; the latter retains voluntary whole-record settlement.
5. Put a second village's guard closer to the offender after A opens its challenge. Surrender
   remains assigned to A. Remove A and confirm no new surrender discount or silent authority swap.
6. Resolve/expire the last local report after refusal. The guard releases its target despite
   remaining Heat/private cases. Test a valid minor report after Heat decays below Wanted.
7. Resize the guard screen and use keyboard navigation after a requote; verify the price, available
   actions, pending-payment control and countdown. Repeat on a dedicated server with two clients.

## Remaining development

Heat is still one aggregate: after decay, this cap cannot reconstruct which incident contributed
each remaining point. Exact attribution requires the deferred local Heat model.

The [incident and combat follow-up](MCA_CRIME_PHASE2_INCIDENTS.md) implements the next bounded portion:
incident commit coordination, post-damage finality and explicit aggression/self-defense provenance.
Full law profiles, local Heat persistence, dimension-scoped wilderness law,
permitted-force tiers, bounty disposition unification, server-scoped lifecycle consolidation and
expanded public API views remain separate work. Existing aggregate sentencing Heat and voluntary
whole-record settlement are not a complete jurisdictional sentencing model.

The monetary receipt machinery retains its existing external-provider and cross-save crash limits.
There is no new durable retry mechanism for a listener that throws after payment. Live AI, guard
ownership during broader pursuits, last-seen searches and density profiling still require later work.

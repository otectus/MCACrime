# Phase 2 development: confirmed death and owner recovery

Development date: 2026-09-07. Continues the
[incident/combat pass](MCA_CRIME_PHASE2_INCIDENTS.md) in the existing uncommitted development tree.
This completes the next portion of Phase 2, not the full audit roadmap.

## Death confirmation

`DeathConsequences` captures dependent work at the death hook and runs it only when
`DamageFinality` confirms an uncanceled death with the victim still dead/dying at reconciliation.
The normal boundary is server tick END; the existing logout/respawn pre-save drain also applies.
Canceled or revived deaths do not pay kill bounties, move stolen goods, release custody through
death cleanup, or clear reaction/thief controllers through death cleanup.

The consequences run in order: bounty, owner recovery, capture/action/custody cleanup, reaction
cleanup, and thief cleanup. Each action is consumed before invoking its callback. A runtime
exception in one action is logged and does not suppress the subsequent actions. Incident
reconciliation failures are isolated from these actions too. This is not rollback or a durable
retry mechanism for failed actions.

Server-owned weak entity tombstones reject repeated callbacks for an already confirmed corpse,
including callbacks in later ticks and reentry from a consequence. Observed revival clears the
tombstone. Reaction and thief ticks pause while an entity has zero health but remains loaded;
confirmation or removal performs cleanup, and revival allows the controller to resume. Ordinary
entity removal, logout, and invalid-session checks retain their own cleanup responsibilities.

## Kill rewards

- A real player victim must have an open warrant, be bounty-eligible, and separately permit lawful
  lethal force. Bounty eligibility alone does not authorize a kill reward.
- The death hook snapshots the warrant identity/revision, attributable claimant, price, currency
  provider and victim display name before death cleanup can change legal state. Existing player,
  projectile and owned-animal attribution still rejects unknown, fake-player and self claims.
- Confirmation requires the exact same open warrant and active currency, enabled kill rewards,
  a writable store and an online real claimant. It never adopts a newer warrant revision or price.
  The victim can already have respawned or left the player list; its original snapshot is retained.
- Existing claim-once and already-paid-delta accounting remains the payment boundary. Saturating
  arithmetic prevents overflow while adding case Heat/fines and applying the kill multiplier.

The live currency credit still uses the prior bounty provider contract. A thrown credit can leave
a consumed claim without a payout; this pass does not introduce bounty payment escrow or solve
cross-file crash recovery. Alive capture and disposition unification remain separate work.

## Owner property recovery

Confirmed thief death moves each stolen-goods row into a deterministic owner escrow lot before
attempting any external transfer. Both store writes occur on the server thread in the same
SavedData, without intervening callbacks. An existing conflicting or partial lot is never
overwritten with the original amount. If escrow is full, the stolen-goods row remains available.

**Stolen property is no longer added to native death drops.** Online, living owners receive an
immediate escrow delivery attempt; offline owners retain their property for login. Full inventory
remainders stay in escrow for a later login. Canceling native drops therefore cannot delete this
property. Ordinary profession-generated death loot still uses Forge's native drops path.

New live theft rows retain the currency provider used by the theft. Named currency lots wait if a
different provider is active. Older rows with no provider remain explicitly unidentified and keep
the legacy current-provider behavior; their original provider cannot be reconstructed from the
amount. Item NBT is defensively copied on construction and access. Missing item registrations
leave the saved lot intact rather than interpreting an empty decoded stack as delivered.

## Escrow delivery receipts

Each lot/remainder derives a stable delivery ID from its saved payload. Delivery records a
`DELIVERY_PENDING` receipt before invoking the inventory/provider adapter. Receipt capacity is
checked before external transfer. A reentrant call sees that receipt and does not repeat delivery.

- An unchanged lot means no transfer occurred; `REJECTED` permits a later ordinary attempt.
- An exact smaller remainder updates the lot and marks this attempt `DELIVERED`. The remaining
  payload gets a different ID on its next attempt. Only an empty confirmed remainder removes the lot.
- Null, an invalid remainder, a callback that mutates the stored lot, or a runtime exception marks
  `NEEDS_RECONCILIATION`. Automatic retry is suspended, including after reload and ordinary receipt
  retention sweeps. Logs identify the attempt; there is no new operator reconciliation command.
- A failed lot does not prevent attempts for later lots. Pending, ambiguous or retained delivered
  receipts never trigger another transfer of the same payload.

Receipts make uncertain results explicit; they do not make SavedData, player inventory saves and
external economy accounts one atomic transaction. A provider that silently reports success without
crediting cannot be detected here. Recovery still depends on that provider honoring its contract.
Legacy raw `StolenGoodsLedger.claim*`/arrest handoff callers are outside this delivery protocol and
remain a separate audit item.

## Compatibility and remaining work

Mod version 0.6.0, network protocol 10 and world schema 9 are unchanged. The stolen-row `provider`
tag is optional; receipts use the existing collection and states. No new dependency, packet,
mixin or registry entry is introduced. API v1 stays unchanged; internal services are not public API.
Older schema-9 development builds do not enforce these recovery rules; preserve a matching world
backup for rollback rather than relying on the schema number alone.

The existing 4,096-sample per-tick finality capacity and detection error kill switch still apply.
Unusual resurrection after reconciliation, hooks bypassed by other mods, and cross-mod reentrant
damage still require live verification. Remaining Phase 2 work includes local Heat persistence,
wilderness jurisdiction/law profiles, configurable force tiers, alive bounty/disposition integration,
legacy property handoffs and consolidation of older runtime/custody projections.

## Verification

`DeathConsequencesTest` and `ConfirmedPropertyRecoveryTest` add 41 service/persistence regressions.
They compose production finality, death actions, bounty claims, stolen-goods escrow and delivery
receipts with real SavedData. Coverage includes late cancellation, revival, duplicate/reentrant
actions, callback failure isolation, stale warrants, independent legal permissions, frozen prices,
provider changes, full tables, future-schema refusal, exact item/currency remainders, missing
provider metadata, callback mutation, ambiguity and reload/retention behavior.

The full offline check passed **1,001 tests, zero failures/errors/skips**.
Check log: `build/gradle-MCACrime-check-20260907-082305.log`.
The final offline release build passed **1,001 tests, zero failures/errors/skips**, reobfuscation
and `checkJarContents` (no shaded MCA/companion/Architectury classes). `git diff --check` is clean.
Build log: `build/gradle-MCACrime-build-20260907-083208.log`.
Artifact: `build/libs/mcacrime-0.6.0.jar`, **1,267,665 bytes**; SHA-256
`be064bf95de29fa11aad3f04d09273d9a04e0c688ebff6afe2ad594f9599f0de`.

These are service/codec regressions, not an in-world Forge/MCA fixture or a two-client test.

## In-game verification before release

1. Repeat armor reduction, full absorption, totems and late death cancellation from the incident
   checklist. Test cancellation both with restored health and at zero health. No kill reward or
   owner recovery may occur; loaded zero-health controllers pause and resume if health is restored.
2. Confirm a real death, then replay its corpse death callback in the same tick and a later tick.
   Verify one reward, one property recovery and one set of death cleanup effects. Try rapid victim
   respawn and killer logout around the pre-save reconciliation boundary.
3. Compare a bounty-eligible target with and without lawful lethal permission. During the death
   callback change/close the warrant, switch currency, or disable rewards: stale offers must not
   pay. Check player arrows, pets, unavailable owners, environmental deaths, self kills and automation.
4. Give a thief goods from several owners, including named/enchanted stacks. Kill it while owners
   are online, offline, in another dimension, dead, or carrying full inventories. Verify exact
   remainders and delivery on later login. Cancel native death drops and repeat.
5. Change the active currency or temporarily remove an item provider before recovery. Property must
   remain recorded. Restore the provider and log in again. Test a provider that throws after credit:
   the attempt must become ambiguous and must not automatically credit again after relog/restart.
6. Fill escrow and receipt tables, retry recovery, and inspect SavedData. Capacity refusal must
   retain property and avoid external delivery. Check ambiguous receipts across retention sweeps.
7. Confirm bounty-credit and death-cleanup listener exceptions do not suppress later death actions.
   Exercise ordinary entity unload and unusual resurrection mods separately from cancellation.

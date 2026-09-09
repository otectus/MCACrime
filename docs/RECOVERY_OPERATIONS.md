# Bounty payments and operator recovery — 0.6.0

New bounty claims reserve a payment receipt before calling a currency provider. The claim and receipt
are in the same world SavedData snapshot. Full receipt or claim tables refuse a new entitlement
before any currency moves. A receipt records its provider, target, recipient, remaining amount and
delivery state. Claim amounts continue to consume the warrant's principal across revisions, including
money still owed; a later revision cannot give that pending amount to a second hunter.

## Player collection

`/crime collectbounty` collects known undelivered bounty payments. Login also attempts collection.
The saved currency provider is resolved from the registry; changing the active currency never converts
the reward. An unavailable provider leaves the payment queued. Existing debts can be collected even
if new bounty awards have subsequently been disabled.

For emeralds, only main-inventory capacity is filled. Overflow stays in the receipt instead of dropping
on the ground; freeing space and collecting again delivers the exact remainder. No repeated bounty
claim, reconnect, or successful collection pays that remainder twice within the saved-state rules.
Other currency providers must confirm a whole credit; an exception or unconfirmed outcome becomes
`NEEDS_RECONCILIATION` and is not automatically retried.

`BountyResolvedEvent`, its completed-payment message and bounty Karma are emitted only when collection
confirms full payment. Queued/partial payments defer those effects. A zero-principal claim still follows
the existing completion policy. A legacy claim without a payment receipt is never guessed into a new
payout. Its historical provider/delivery outcome may be unknown.

## Inspect and export (permission level 3)

| Command | Result |
|---|---|
| `/crime recovery receipts [page]` | Nonterminal payment/transfer receipts, ten per page. |
| `/crime recovery escrow [page]` | Property lots, owners, amounts and associated delivery receipt IDs. |
| `/crime recovery quarantine [page]` | Truncated previews of unreadable retained rows. |
| `/crime recovery audit [page]` | Operator decisions with actor, time, receipt ID and note. |
| `/crime recovery inspect <receiptUUID>` | Full receipt, associated bounty/lot and current revision token. Also works for terminal receipts. |
| `/crime recovery export` | Unique JSON file under `<world>/mcacrime-recovery/`; no existing file is overwritten. |

Exports include receipts, property lots, bounty claims, quarantine and audit entries as full-fidelity
SNBT strings inside JSON arrays. They do not modify the store or attempt payment. A future-schema
read-only store reports that status; its uninterpreted contents remain in the original world file.

## Reconcile an uncertain receipt

Inspect the external currency ledger/inventory and the exact receipt or property remainder first.
Use the UUID revision token returned by `inspect`:

```text
/crime recovery resolve <receiptUUID> <revisionUUID> delivered <operator note>
/crime recovery resolve <receiptUUID> <revisionUUID> cancelled <operator note>
/crime recovery resolve <receiptUUID> <revisionUUID> retry <operator note>
```

- **delivered** acknowledges that the outstanding amount/payload arrived or was compensated outside
  this workflow. It closes the receipt and removes its matching property lot, if any.
- **cancelled** acknowledges that no transfer remains owed, for example an ambiguous debit verified
  never to have occurred or a source already refunded externally. It is refused for property lots,
  which must remain owed until delivered. It does not reopen a consumed bounty entitlement.
- **retry** records verification that **none of the currently recorded remainder** arrived. It rearms
  only a linked bounty payment or property delivery. It never replays arbitrary source debits.
  Bounties then collect on login or `/crime collectbounty`; property follows normal login recovery.
  Partial external outcomes require manual compensation and acknowledgement instead of a full retry.

These commands issue no immediate money/items and do not replay bounty events or Karma. A changed
receipt/remainder requires another inspection. Notes must be nonblank, at most 256 characters, with
no control characters. Terminal receipts cannot be changed. Already queued bounties need collection,
not rearming. Every accepted decision saves the operator identity, note, timestamp and exact prior
receipt/property payload before changing bookkeeping. The audit is bounded at 4,096 entries and
never automatically pruned; a full audit refuses further corrections. Export/archival tooling for an
exhausted audit is future maintenance work. Quarantined rows are inspection-only.

## Persistence and validation limits

Schema 10 adds `reconciliationDecisions` and the `AWAITING_DELIVERY` receipt state. Schema 9 and older
saves migrate without inventing payment receipts for historical bounty claims. Older builds with the
future-schema guard enter read-only mode on schema 10; use a matching backup when rolling back.
Pending bounty claims are retained even after the warrant has gone so their queued payments can be
collected/reconciled. Terminal receipts and finished claims retain their normal maintenance policies.

Minecraft inventories, external banks and world SavedData do not commit atomically. A process crash
can persist either side independently; `setDirty()` is not a disk flush. In-flight receipts saved as
`DELIVERY_PENDING` remain suspended after restart. A crash before the in-flight state is saved can
still leave a saved queued receipt alongside an external credit. Events/Karma also have a crash
window after the delivered state is written. Do not infer exactly-once cross-system delivery from
the unit tests or retry an uncertain external credit automatically.

Automated checks cover receipt/claim capacity, partial delivery, replay, reentry, changed providers,
ambiguous results, save/reload, pending-claim retention, audit capacity, stale inspection tokens,
property rearming/acknowledgement, future-schema refusal and command permission/syntax. Before release,
run live tests for partial/full emerald inventories, provider removal/failure, console/player commands,
JSON export, companion bounty completion, restart at delivery boundaries and the existing MCA matrix.

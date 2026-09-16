# S2 — Thief as an exclusive occupation, and the Mask Station

How this port turns `mcacrime:thief` from a cosmetic overlay into a real villager job, what it costs,
and what it deliberately does not promise.

## 1. The target state

For every active Thief, all four of these agree, or the occupation is not active:

```
native profession   = mcacrime:thief
Crime criminal job  = THIEF
occupation status   = an employed OccupationStatus
worksite            = one native Mask Station claim, or a documented unbound state
```

A single transition service maintains that agreement. Nothing else in the mod writes a Thief
profession, and nothing outside `job/OccupationTransaction` changes the world for one.

## 2. Integration with MCA

MCA keeps vanilla's acquisition, validation and assignment behaviours in its own occupational task
list, so the station is reached through the **native POI mechanism** rather than through a second
workstation ledger maintained beside it.

| Concern | How it is reached |
|---|---|
| Profession set/read, clothing, family-tree profession, despawn delay | `compat/mca/McaBinding` `MethodHandle`s, behind `compat/McaCompat` |
| Villager data, trading XP, brain memories, merchant offers, POI tickets | `compat/OccupationCompat`, one checked `instanceof Villager` |
| Generic acquisition, native assignment, POI validation, work yielding | four narrowly scoped **vanilla-targeted** mixins, plus one accessor |

No MCA type is named anywhere in `src/main/java`; `NoMcaStaticLinkTest` still fails the build if that
changes.

### The capability bundle

The occupation work added five MCA members to the manifest: `VillagerLike#getClothes`,
`VillagerLike#setClothes(String)`, `FamilyTreeNode#getProfessionId`, `FamilyTreeNode#setProfession`
and `VillagerEntityMCA#getDespawnDelay`.

Each is declared **optional globally** and checked **as a bundle**
(`McaBinding.THIEF_OCCUPATION_CAPABILITY`, which also lists the profession getter and setter, the
relationship accessor and the family entry). A required member that vanished would turn the whole
resolution `PARTIAL` and disable every MCA-backed feature — far too much to pay for a profession
change. A plainly optional one would let the transaction commit a Thief whose clothing and family
profession could never be rolled back. So the bundle is checked as a unit before any mutation, and
`McaBindingProbeTest` requires the whole bundle to resolve on every probe jar.

### The mixins

| Mixin | Target | What it does |
|---|---|---|
| `MaskStationAcquisitionMixin` | `VillagerProfession.acquirableJobSite()` RETURN | Removes only the Mask Station, for every profession except Thief. Also filters MCA's own "set workplace", which reads the same accessor. |
| `NativeJobAssignmentMixin` | `AssignProfessionFromJobSite.create()` RETURN | Wraps the returned `BehaviorControl`. The Mask Station is handled by Crime; every other site is delegated unchanged. |
| `ThiefPoiValidationMixin` | `ValidateNearbyPoi.create(...)` RETURN | Defers validation of an employed Thief's site while its chunk cannot be inspected. |
| `ThiefBrainMixin` | `Brain.tick` HEAD | Wraps only this brain's `Activity.WORK` entries, once per brain instance, for employed Thieves only. |
| `MerchantOffersAccessor` | `AbstractVillager.offers` | Field accessor, no behaviour. |

Factories are targeted at RETURN rather than at their declarative lambdas: a synthetic `lambda$create$N`
is a compiler artefact with no stability guarantee.

`ThiefBrainMixin` is re-applied per brain because MCA's profession setter refreshes the brain, which
builds a completely new task list — anything installed once at commit time would be discarded by the
very call that creates the Thief. Its first statement is an `isEmpty()` on a normally empty set, so a
world with no thieves pays nothing for it being installed.

## 3. The transaction

`OccupationTransaction.run` is the only sequence:

1. Refuse a stationless settlement request, an unreadable villager, a temporary inn occupant and a
   villager whose previous profession is `UNREADABLE` (nothing to roll back to) — all before mutating.
2. Reserve or adopt the station ticket.
3. Release a replaced claim and clear the occupational memories.
4. Apply the profession through MCA's setter, exactly once, and verify it reads back.
5. Install empty offers, write the validated job site, apply the trading-XP floor.
6. Verify profession, claim and job-site agreement.

The caller persists the record, starts the controller and publishes `CriminalJobChangedEvent` — in
that order, and only for a committed result. The transaction itself has no way to publish anything.

### Reset protection

Both MCA's `LoseUnimportantJobTask` and vanilla's `ResetProfession` require **zero** trading XP before
taking a profession away, so `compat/OccupationCompat#applyXpFloor` — `setVillagerXp(max(1, xp))` — is
the whole protection. It is applied on commitment including to novices, so a native reset cannot
bypass Crime's own grace period, and it costs a player nothing because a Thief has no trades. It also
excludes committed Thieves from MCA's automatic guard recruitment, which wants a zero-XP level-one
adult.

### Rollback, and its limits

Rollback undoes what the transaction did: a ticket it took (never an adopted one, and never twice), a
claim it released, the profession, villager data, trading XP, merchant offers, MCA's clothing string,
the family-tree profession and the occupational memories.

Offers are captured as a **detached deep copy** rather than serialized: 1.21.1 removed
`MerchantOffers#createTag()` and its `CompoundTag` constructor in favour of a registry-aware codec,
and `MerchantOffers#copy()` copies every offer, so the copy is as detached as the Forge build's tag
was and needs no registry to make. `OccupationSnapshot` keeps a separate `offersPresent` flag next to
the nullable field, because "never traded" and "an empty trade list" are different states and
restoring the wrong one leaves a villager permanently unable to acquire trades.

**Not** rewound: dirty flags, consumed randomness, anything another mod changed while the transaction
ran, and any state outside the list above. If the old claim changed hands, or the restore itself
fails, the result is `SUSPENDED` with a diagnostic — never a claim that the villager is clean.

`McaHandles.setProfession` returning `false` does not mean "nothing happened": the setter writes data,
randomises clothing and rewrites the family entry before it refreshes, so verification is a read-back
and the rollback is the captured snapshot.

## 4. Lifecycle and timings

| Constant | Value | Where |
|---|---|---|
| Discovery radius | 48 | `ThiefWorksiteService.SEARCH_RADIUS` |
| Reservation timeout | 400 ticks | `ThiefWorksiteService.RESERVATION_TIMEOUT_TICKS` |
| Reconciliation cadence | 20 ticks | `ThiefOccupationLifecycle.INTERVAL_TICKS` |
| Replacement search, staggered | 200–400 ticks | `RETRY_MIN_TICKS` / `RETRY_MAX_TICKS` |
| Novice grace without a claim | 1200 **loaded** ticks | `NOVICE_GRACE_TICKS` |
| Establishment after a visit | 24000 eligible employment ticks | `ESTABLISHMENT_TICKS` |

States: `NONE` (no occupation at all, which is nearly every villager in the world), `PENDING`,
`ACTIVE_BOUND_NOVICE`, `ACTIVE_BOUND_ESTABLISHED`, `ESTABLISHED_UNBOUND`, `SUSPENDED`, `RETIRED`.
Only the employed states may mug; a pending, suspended or retired occupation
cannot, so a novice whose claim failed cannot use its grace period to operate stationless.

Employment accumulation pauses in custody and while thieves are disabled. Grace is measured in loaded
ticks only: an unloaded villager is skipped entirely, so nothing is retired while nobody is watching.
An unloaded or cross-dimension station is unresolved, not destroyed — no chunk is ever force-loaded.

A pending recruitment is persisted as a record with job `NONE`, so a candidate walking to a station is
never counted as a thief, never mugs, and its ticket can still be given back after a restart.

## 5. Schema 12

`state/world/CrimeDataMigrations.SCHEMA_OCCUPATION` is `12` and is the current schema.
`CriminalVillagerRecord` gains `status`, `source`, `establishedAt`, `lastVisitAt`, `employedTicks`,
`worksite`, `reservation`, `reservationAt`, `unboundSince`, `previousProfessionKind`, and an `extra`
tag that round-trips keys this build does not recognise.

A worksite is stored as a `WorksiteRef` (dimension id plus `BlockPos`) rather than a `GlobalPos`:
building a `GlobalPos` means building a `ResourceKey<Level>`, which reaches `BuiltInRegistries` and
throws outside a running game, and a saved record must be readable and testable without a Minecraft
bootstrap. Conversion in both directions borrows the key from a live `ServerLevel`, so a dimension a
datapack removed reads back intact and simply never matches.

`previousProfessionKind` splits the three meanings a nullable string carried: `NONE` (nothing was
displaced), `UNREADABLE` (something was, and could not be read — never rolled back to a guess), `ID`.

Migration is **structural only**. Every existing Thief becomes `ESTABLISHED_UNBOUND`; no station is
invented, because no pre-0.7.2 world contains one. Fences gain no occupation — exclusivity is
specifically a Thief rule. Running it twice changes nothing the second time. Everything
entity-sensitive is reconciled on legitimate load, which is the only place it can be answered for a
sleeping chunk.

## 6. Deprecations and behaviour changes

- `criminalJobs.presentThiefAsMcaProfession` is **deprecated and inert**. It is still parsed and the
  config file is not rewritten; `WorldCriminalJobService#warnAboutDeprecatedThiefPresentation` logs one
  warning at setup when it is `false`. The Fence presentation key is untouched.
- Disabling thieves blocks new requests and aborts sessions; existing professions stay visible and
  inert rather than being stripped.
- The mugging gate additionally requires a current native `mcacrime:thief` profession and an eligible
  occupation state on top of the shared role rule in `job/NpcMuggerEligibility`. A stale `THIEF` record
  alone never authorises a mugging, so an externally converted guard stops immediately rather than at
  the next sweep.
- MCA's generic "set workplace" command can no longer assign a Mask Station directly. That is the
  intended cost of the acquisition boundary; recruitment remains automatic through Crime's adapter.

## 7. Proposed runtime acceptance — not yet performed on this port

None of the following has been exercised on this port. They are proposed checks, per MCA version on
the probe list, on both a dev client and a dedicated server:

- **Production-mapping validation of the four behaviour mixins.** Source-shape tests and a successful
  build do not prove `Brain.tick`, `AssignProfessionFromJobSite.create`, `ValidateNearbyPoi.create` or
  `VillagerProfession.acquirableJobSite` are hit in a packaged jar.
- End-to-end occupation checks: all four station facings, a station across a chunk edge, an
  unreachable station, two villagers racing for one station, unload/reload, custody, cure and
  pickup/restore, and a dimension change.
- That MCA's own interaction, name and job views display the localized **Thief** profession.
- That MCA's profession setter refreshes the brain exactly once per commit, and that no reservation is
  released by the behaviour shutdown that refresh causes.
- That a committed Thief has no previous active trades in an actual trade screen.
- That an established unbound Thief finds and claims a replacement station on the staggered cadence.

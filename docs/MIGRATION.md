# MCA: Crime — migration, removal, and rollback

MCA: Crime keeps two stores: per-player data on a Forge capability serialised into player NBT, and
world data in `<world>/data/mcacrime.dat`, pinned to the Overworld's storage. This document is about
the second one, because that is what changes shape between versions.

---

## 0.7.2

The 0.7.2 release establishes Thief as an exclusive native villager profession (`mcacrime:thief`) with a
Mask Station worksite, introduces the 16-style mask catalogue, non-sneak empty-hand apologies, and the Sand
Bottle. This update uses **network protocol 13** and **world save schema 12**.

### Criminal-record schema 12 migration

World data in `<world>/data/mcacrime.dat` advances from schema 11 to schema 12 (`SCHEMA_OCCUPATION` in
`state/world/CrimeDataMigrations`). The migration brings criminal records into agreement with native villager
professions:

- **Record fields:** `state/world/CriminalVillagerRecord` gains nine persistent fields plus an unparsed
  passthrough tag:
  - `status` (`job/OccupationStatus`): The current state of the occupation (`NONE`, `PENDING`,
    `ACTIVE_BOUND_NOVICE`, `ACTIVE_BOUND_ESTABLISHED`, `ESTABLISHED_UNBOUND`, `SUSPENDED`, `RETIRED`).
    Only the three employed states (`ACTIVE_BOUND_NOVICE`, `ACTIVE_BOUND_ESTABLISHED`,
    `ESTABLISHED_UNBOUND`) are authorized to mug.
  - `source` (`job/OccupationSource`): Which route created or requested the occupation (`UNKNOWN`,
    `SETTLEMENT_SWEEP`, `STATION_RECRUITMENT`, `NATIVE_REBIND`, `OPERATOR`, `API`, `WILD`, `MIGRATION`).
    Routes requiring a station (`SETTLEMENT_SWEEP`, `STATION_RECRUITMENT`) require an unemployed adult
    villager; repair and migration routes do not.
  - `previousProfessionKind` (`job/HistoricalProfessionKind`): Distinguishes the three states previously
    packed into a nullable string: `NONE` (no previous profession was displaced), `UNREADABLE` (a previous
    profession existed but could not be read, preventing rollback from guessing an unemployed state), or
    `ID` (a valid registry ID was captured).
  - `establishedAt`: Overworld game tick when establishment was earned (0 for unestablished).
  - `lastVisitAt`: Overworld game tick of the thief's last physical arrival at their claimed worksite.
  - `employedTicks`: Cumulative loaded, active employment ticks (24000 ticks required for establishment).
  - `worksite`: A `state/world/WorksiteRef` (dimension ResourceLocation + BlockPos) recording the claimed
    Mask Station POI.
  - `reservation` and `reservationAt`: A temporary reservation reference and timestamp for candidates
    walking to claim a station.
  - `unboundSince`: Overworld game tick when a station claim was lost, gating novice grace (1200 loaded
    ticks).
  - `extra`: A `CompoundTag` that preserves any unrecognized root keys, providing forward compatibility
    if a save is touched by a newer build.

- **Lazy and idempotent migration:** Tag-to-tag migration (`v11to12`) runs on world load. It is purely
  structural: running the migration step multiple times changes nothing the second time because records
  containing `status` are skipped. Entity-dependent reconciliation (such as reading live villager
  professions or checking if an NPC became a guard) is lazy: it runs only when the villager's chunk is
  legitimately loaded in `job/ThiefOccupationLifecycle`. Unloaded chunks are never force-loaded.

- **Legacy and wild Thieves become established:** Existing pre-0.7.2 Thief records migrate to
  `status = established_unbound` with `source = migration` (or `source = wild` if `wildOrigin` was true).
  No Mask Station is placed or invented during migration; migrated thieves operate unbound until they
  discover and claim a Mask Station POI. Fences remain at `status = none` because occupational exclusivity
  applies only to Thieves.

- **Responders outrank historical Thief overlays:** If an entity with a historical Thief record is or
  becomes a law responder (MCA guard, archer, or registered responder under `detect/EntitySelectors#isResponder`),
  the law responder identity wins immediately (`ThiefOccupationLifecycle#reconcileEmployed`). The Thief
  occupation is retired with source `MIGRATION` without forcing or contesting the role.

### Deprecated thief presentation config

In 0.7.2, Thief is a visible, exclusive native villager profession (`mcacrime:thief`). The common
configuration key `criminalJobs.presentThiefAsMcaProfession` is **deprecated and inert**. The key is still
parsed on load so existing configuration files do not break or get rewritten, but setting it to `false` no
longer hides the profession; instead, it logs a single startup warning
(`WorldCriminalJobService#warnAboutDeprecatedThiefPresentation`). A hidden Thief overlay on an ordinary
profession is no longer a supported state. The companion `presentFenceAsMcaProfession` key remains active
and unchanged.

### Preserved mask item IDs

All 0.7.0 mask items retain their exact registry IDs, wear budgets, and recipes:
- `mcacrime:clay_mask` (Blank Clay Mask): retains its `clay_mask` item ID, 64-wear budget, and 0.7.0
  crafting-table recipe (4 clay balls + 2 string).
- `mcacrime:leather_mask` (Cutpurse Leather Mask): retains its `leather_mask` item ID, 192-wear budget,
  and 0.7.0 crafting-table recipe (2 leather + 1 string).

Existing item stacks in player inventories, containers, saved data, and datapack loot tables continue to
resolve without renaming or migration. Both masks participate in the 16-style catalogue and may be
restyled or dyed in the Mask Station.

### Villager trading XP floor

When a villager commits to the Thief occupation (`job/OccupationTransaction`), the transaction applies a
trading XP floor via `compat/OccupationCompat#applyXpFloor`, setting
`villager.setVillagerXp(Math.max(1, villager.getVillagerXp()))`. This ensures trading XP is at least 1,
which guarantees:
1. Vanilla's `ResetProfession` and MCA's `LoseUnimportantJobTask` cannot strip the Thief profession away
   when workstation claims are temporarily lost or churned.
2. MCA's automatic village guard recruitment (which requires an adult villager with zero trading XP)
   cannot convert an active Thief into a guard.

### Backup advice and unsupported rollback

**Always back up your world before upgrading to 0.7.2:**

```bash
# with the server stopped
cp -r world world-backup-pre-0.7.2
```

Schema 12 migration runs automatically on world load in one direction and **cannot run backwards**.
Downgrading a world saved by 0.7.2 to an older MCA: Crime version is unsupported:
- Older jars that include the future-schema safety gate will detect schema 12, open the world in read-only
  mode, and refuse all mutations to protect against data loss (`state/world/ServerMutationGate`).
- Older jars without future-schema protection cannot address schema 12 record structures or native Mask
  Station occupations. While unrecognized fields are preserved in NBT through the reserved passthrough tag,
  downgrading will result in lost or unaddressable occupational state.
- Restoring a pre-upgrade backup copy is the only supported rollback procedure.

## Before you upgrade

The HUD/AI follow-up uses **network protocol 11** and **world schema 10**. Update clients and server
together for the guard-menu display acknowledgment. No world-data conversion is needed for these
changes. Client HUD layout migration moves only the former TOP_LEFT default with offsets 4/4;
custom placements remain selectable. Guard response windows below 300 ticks become 300 (15 seconds).
Loaded older cells receive a bounded bystander repair sweep; only safe exterior destinations are used.

The bounty/reconciliation follow-up writes **world schema 10** and retains **network protocol 10**.
It adds queued bounty receipts and durable operator audit records. Schema 9 and older saves load
without inventing payouts for historical claims. Older builds with the future-schema guard become
read-only on schema 10; restore a matching backup for rollback. See [recovery operations](RECOVERY_OPERATIONS.md).

The earlier local settlement update introduced **network protocol 10** with **world schema 9**.
Update both clients and server: guard offers and responses now include a revision. No additional
save conversion is needed, and open conversations/offers remain temporary server state.

The [incident/combat follow-up](MCA_CRIME_PHASE2_INCIDENTS.md) retains those versions. New cases
may contain bounded combat provenance; old cases are not reclassified. Combat encounters and
pending damage samples are temporary and cleared on server stop. `raidGrace` now covers only
one eligible nonlethal indirect explosion per encounter, rather than all crime during a raid.
NPC integrations must stop posting a second `NpcCrimeCommittedEvent` after the legacy commit
facade, which now emits the event centrally using the committed case identity.

**Take a copy of your world.** The schema migration runs on load, in one direction, and does not run
backwards.

The [death/recovery follow-up](MCA_CRIME_PHASE2_DEATH_RECOVERY.md) also retains protocol 10/schema 9.
New stolen-goods rows optionally name their currency provider. Absent names stay unknown and keep
the legacy current-provider behavior; no provider is inferred from old amounts. New named currency
lots wait when a different provider is active. Confirmed thief deaths now move property to owner
escrow instead of native death drops. Delivery attempts use the existing receipt collection;
pending/ambiguous attempts do not automatically retry. Older schema-9 development builds do not
enforce these rules and may discard the optional metadata, so same-schema rollback is not a recovery
procedure. Restore the matching backup when rolling back.

```bash
# with the server stopped
cp -r world world-backup-pre-0.2.0
```

That is the entire mitigation, and it is the only complete one. Everything below explains what the
migration does and what safety nets exist, but none of them replace the copy.

## What happens on load

`mcacrime.dat` carries a `schema` integer. On load, every step needed to bring it to the current
schema runs in order, and the result is stamped. This build writes **schema 10**.

Schema 8 development saves remain supported. Schema 9 adds optional `crimeMemories` under each
villager profile and a `relayed` flag on observations. An absent suspect UUID represents an
unidentified actor; hearing-only observations no longer retain an inferred suspect. Old purse and
robbery cooldown data are preserved, and absent category memories remain empty. No historical
memory or family knowledge is fabricated on upgrade.

The migration is deliberately **pure tag-to-tag work**. It does not consult the server, the config,
the world, or where any player happens to be standing — a migration that read live state would
produce different results depending on who logged in first. A tag already at or beyond the current
schema is returned untouched.

A missing `schema` key means the original, unversioned format, which is treated as schema 0.

### 0 → 1 — dimension-aware village identity

Every legacy village integer becomes `minecraft:overworld/<id>`, on both crime records and the
built-in standing store. Records get a context stamp recording that the dimension was assumed rather
than known. A negative village id is impossible and is dropped rather than carried forward.

One aggregate warning is logged, not one line per record — a long-running world can hold thousands.

### 1 → 2 — the case lifecycle

Each record gains a resolution revision starting at zero. Existing dispositions are preserved
exactly; only the machinery around them is initialised, so nothing a player already earned or owes
changes.

Records already marked witnessed but with no witness list are stamped as predating witness
identities. Empty lists are not written — absent already means empty everywhere that reads them, and
writing empties would only grow the file.

### 2 → 3 — the integration outbox

The outbox, dead-letter list, and dedupe store are created empty. A world that existed before any
companion mod has, by definition, nothing pending.

### 3 → 4 — the finite action economy

Villager profiles, action counters, village treasuries, and transaction receipts are initialized 
empty. A world still at schema 3 has no prior economy records to carry forward.

### 4 → 5 — observations and reports

Observation and report collections are initialized empty. The witness identities already on old 
crime records are preserved exactly; role, confidence, place and line-of-sight are not synthesised 
because none of that was ever recorded.

### 5 → 6 — the holding-cell roster

The holding-cell roster is initialized empty. Existing jail anchors point at structures players 
built by hand and registered with `/crime assignjail`; they are not assumed to be owned by this 
mod, so no cell records are seeded from them.

### 6 → 7 — criminal villagers, stolen goods, and warrants

This step stamps the schema and writes nothing except the schema number. Six new collections
are created: criminal job assignments, stolen goods ledgers, bounty warrants, bounty claims,
bounty contracts, and fence restock timers. All start empty; a world still at schema 6 has
no prior criminal activity to migrate.

### 7 → 8 — sentence identity, payment tracking, and world recovery

This step stamps the schema and writes nothing whatsoever. Every field added is optional with a 
safe legacy default:

- **`CrimeRecord.sentenceId`:** null (case belongs to no sentence; legacy cases are bound at 
  first login, see below).
- **`JailState.surrenderCredited` and `JailState.legacyBound`:** false (nobody claimed the 
  discount yet, legacy binding not attempted).
- **`HoldingCell.legacyBound`:** false (same reason, for NPC custody).
- **`BountyClaimRecord.paidAmount`:** absent (claim treated as fully consumed, preventing 
  double-pay on warrant revision).
- **Collections** (`fenceStock`, `transactions`, `propertyEscrow`, `pendingCellRestorations`, 
  `quarantine`): empty (absent already reads as empty everywhere that consumes them).

**Legacy sentence binding:** On first login, `JailService.reconcileOnLogin` examines each player's 
active jail sentence. If the sentence has no case bindings yet (`legacyBound` is false), it binds 
every currently actionable, unbound case against the offender and marks the binding as reconciled. 
NPC custody reconciliation works the same way. This happens once per offender per world load; 
subsequent logins find sentences already bound.

**World-data safety:** A save written by a newer schema version is opened read-only. Operators 
with permission level 2 or higher see `mcacrime.readonly` on login; all Crime mutations are 
blocked until the version mismatch is resolved (downgrade the mod or upgrade the save). Malformed 
records that fail to deserialize are quarantined in a bounded list for manual inspection rather 
than crashing the load.

## What changes that you will notice

**Village standing that was previously merged across dimensions is now separated.** If your players
had a village in the Nether or the End that happened to share an id with an Overworld village, this
mod treated them as one village and now treats them as two. This is a correction, but on an existing
world it is a visible one: standing that was pooled is now split, with the whole pooled value landing
on the Overworld entry.

**Records committed in another dimension before 0.2.0 cannot be recovered.** They had no dimension
recorded, so the migration assumes Overworld and says so in the record's context. The information to
do better does not exist in the save. This is the one genuinely lossy part of the upgrade.

**Witnessed records that predate witness identities stay witnessed with nobody named.** They are not
retro-assigned witnesses, so villagers are never handed knowledge of crimes they were not recorded as
having seen.

## Downgrading

The migration does not run backwards, but a separate mechanism keeps a downgrade from being
destructive.

**Schema is backward compatibility; the reserved passthrough is forward compatibility.** They are two
different things and they stay separate. When any build of this mod reads `mcacrime.dat`, tags it does
not recognise are kept verbatim and written back out untouched. So an older jar reading a schema-3
save does not delete the outbox, the dedupe store, or anything else it was never taught about — it
hands them back on the next write.

### The honest limitation

That passthrough protects *unrecognised* data. It does not undo the migration itself.

Concretely, if you upgrade to 0.2.0 and then go back:

- The `schema` key still says `3`. An older jar reads a schema it does not know about. It will not
  migrate, and it will not refuse — it reads what it understands.
- Village keys are now `minecraft:overworld/3`, not `3`. An older jar's standing store expects bare
  integers, so **it will not find the standing it wrote before the upgrade**. The data is still in
  the file; the old code cannot address it.
- Crime records now carry a community compound rather than a `villageId` integer, with the same
  consequence.

So a downgrade does not corrupt the save, but it does not restore the old behaviour either. It leaves
you with a file that the old jar can open and largely cannot read. **The backup copy is what you
actually roll back to.** Nothing in this design replaces it, and this section exists to say so
plainly rather than to imply a rollback path that works better than it does.

**Rolling back from schema 8 to schema 7:** The `schema` key remains 8. A schema-7 jar (the last
release before the 7 → 8 migration; historically 0.5.1) reads an unknown schema and does not
migrate it. The fields added in that migration — `CrimeRecord.sentenceId`,
`JailState.surrenderCredited`, `JailState.legacyBound`, `BountyClaimRecord.paidAmount`,
`fenceStock`, `transactions`, `propertyEscrow`, `pendingCellRestorations`, and `quarantine` —
are preserved verbatim in the reserved section and are not read by that older loader. They survive
the downgrade and are still present when you upgrade to a schema-8 build again.

## Removing the mod

Take the jar out and the world loads. `mcacrime.dat` is simply never read again, and the player
capability data becomes inert NBT on each player. Nothing is deleted, so putting the jar back later
picks up exactly where it left off — karma, Heat, sentences, the ledger, and standing all intact.

Two things end at removal, by nature rather than by choice: any jail sentence stops being enforced,
and any captive stops being held. Nobody is left teleport-locked, because the mechanism that would
lock them is gone with the jar.

## Removing MCA: Reputation while keeping MCA: Crime

This is supported and is the reason `mirrorReputationFallback` defaults to `true`. With it on, every
standing change committed through Reputation is also copied into the built-in store, so pulling
Reputation out does not reset every player to a stranger — Crime falls back to its own numbers, which
have been kept current all along.

Cross-mod writes that were queued when Reputation disappeared are **not** discarded. They stay in the
outbox and are retried, because a companion that is not there is treated as a delay rather than a
failure. Put Reputation back and the queue drains. `/crime debug outbox` shows what is waiting, and
`/crime debug outbox dead` shows what gave up after six attempts.

If you are removing Reputation permanently, set `enableReputation = false` as well. Otherwise the
outbox keeps accumulating work for a mod that is never coming back.

## Adding MCA: Reputation to an existing MCA: Crime world

Nothing is backfilled. Crimes committed before Reputation was installed do not become public
incidents retroactively — the village learns about things when they happen, and inventing history it
never witnessed would be worse than starting from now.

From the moment the bridge registers and the authority claim is accepted, new crimes produce
incidents. Check with:

```
/crime debug integrations
```

It reports the API version, whether Reputation is installed, enabled, and whether the bridge holds
authority, plus outbox and dead-letter counts and the last failure. It emits no player UUIDs, so the
output can go straight into a bug report.

## Verifying a migration went as expected

After the first load on the upgraded world:

- [ ] The log contains the aggregate migration line, and the record and community counts are roughly
      what you expect for the world's age.
- [ ] `/crime query <player>` on a player with history shows the karma, Heat, and band they had
      before.
- [ ] `/crime ledger <player>` lists their cases with their dispositions unchanged.
- [ ] `/crime validate` reports no problems.
- [ ] `/crime debug integrations` reports the state you expect for the mods actually installed.
- [ ] The world has been saved at least once, so schema 3 is written back to disk before you draw any
      conclusions from a second load.

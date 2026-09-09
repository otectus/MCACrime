# MCA: Crime — migration, removal, and rollback

MCA: Crime keeps two stores: per-player data on a NeoForge attachment (`mcacrime:player_crime`) serialised into player NBT, and
world data in `<world>/data/mcacrime.dat`, pinned to the Overworld's storage. This document is about
the second one, because that is what changes shape between versions.

---

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

## Moving from Forge 1.20.1 to NeoForge 1.21.1

This guide is for server owners and players upgrading MCA: Crime from Forge 1.20.1 to NeoForge 1.21.1 on an existing world.

### What Migrates Automatically

Player data from Forge 1.20.1 player files is imported once on load:

- When a player joins the NeoForge server for the first time after the upgrade, their crime data (karma, heat, band, wanted status) is read from their Forge player `.dat` file.
- The data is loaded into the NeoForge attachment storage (an internal change; player behavior is unchanged).
- If a player already has NeoForge-native data, it takes precedence and the Forge data is ignored.
- The original Forge player file is left untouched and never written to. Normal player saves re-emit the data in the new format automatically.
- A fallback to `<uuid>.dat_old` is attempted if the primary player file is unreadable.

### What Does Not Migrate

- **1.20.1 Forge clients cannot join a NeoForge 1.21.1 server.** The network protocol is incompatible (changed to protocol 8). Clients must update to NeoForge 1.21.1.
- World files (block data, entity data) are compatible; only the network protocol differs.
- Config files (keys and TOML structure) remain identical across both versions.

### Step-by-Step Upgrade

1. Back up the world and configs directory.
2. Install NeoForge 1.21.1 and the production MCA: Crime port JAR on your server.
3. Install MCA Reborn 1.21.1 and only the declared dependencies (no optional mods yet).
4. Start the server. Watch the logs for import confirmations (below).
5. Have each player join once. Each login triggers a single automatic import (if needed).
6. Stop the server cleanly. Inspect the world data file at `world/data/mcacrime.dat` to confirm the upgrade completed (see below).
7. Restart twice and confirm players' karma, heat, and band are persistent and unchanged.
8. If you have MCA: Reputation installed, join once more with a player who had reputation data in the Forge world.
9. Exercise normal gameplay: arrests, jails, cases, crimes. Data should persist across restarts.
10. If any data is missing or wrong, restore the backup and contact support with the full server log from the first startup.

### Verifying the Import

Check the server log for these lines (one per player, on first join):

```
[MCA: Crime] Imported 1.20.1 Forge crime data for player <UUID>.
```

If a player file is unreadable or does not contain legacy crime data, the log shows:

```
[MCA: Crime] Could not read the player file for <UUID>; skipping the 1.20.1 crime-data import. The player keeps whatever state loaded normally.
```

Both outcomes are normal. New players or players with no Forge crime data simply start with defaults.

### Rollback

If the upgrade fails or data is corrupt, restore the backup and downgrade to Forge 1.20.1. Your original player `.dat` files and world data are preserved in the backup.

### Known Differences

- World file (`world/data/mcacrime.dat`) schema is updated to version 8.
- All configuration keys remain the same; the TOML structure is identical.
- Client resource packs and datapacks work the same.
- Block positions and memory data in the world file keep their 1.20.1 structure.

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

### 0.5.1 — Schema 6 → 7

This release adds criminal job assignments, stolen-goods ledgers, warrant tracking, bounty claims,
fence pricing history, and contract board state. All new collections default to empty when absent,
so no data is synthesised and a 0.5.0 world loads at schema 6 and is rewritten at schema 7 on
first save. The maintenance sweep expires old stolen-goods records after their grace period
(`criminalJobs.thief.stolenGoodsPersistenceDays`), stale job assignments after theirs
(`criminalJobs.staleRecordGraceDays`), and bounty claims from expired warrants.

### 0.6.0 — Schema 7 → 8

This release adds only optional fields and empty collections: sentence identity, bounty claim
payment tracking, transaction receipts, property escrow, the restored-cell journal, and fence
stock. The migration step itself (`v7to8`) stamps the schema number and writes nothing else, so a
0.5.1 world's data is not rewritten by the upgrade — every added field simply reads as absent until
something in the new logic writes to it.

Two things follow from a field reading as absent rather than being backfilled:

- **Legacy jail sentences bind their cases once, at the prisoner's first login after the upgrade.**
  A sentence from before schema 8 has no record of which cases it covers, so on that first login the
  cases still standing against the prisoner are adopted as what the sentence was for. The `JailState`
  itself is then marked bound (`setLegacyBound(true)`), unconditionally, so the guess is never
  repeated on a later login — a per-case stamp could not do this alone, since a legacy sentence with
  nothing left standing against it would be indistinguishable from one that had never been inferred.
- **Legacy bounty claims count as already paid in full.** A claim written before schema 8 has no
  recorded payment amount, so a revision or expiry check against it takes the claim as having
  consumed the warrant's current price rather than nothing — the conservative reading, which costs a
  hunter a re-claim rather than risking paying an old warrant twice.

This build also protects the other direction: if `mcacrime.dat` is ever stamped with a schema
number higher than 8 — a save from a future version — this build does not attempt to read it as
schema 8 or guess at fields it does not know. It loads the file, keeps it exactly as it was, and
runs the session **read-only**: no crime is recorded and no Crime mutation is accepted until a build
that understands the newer schema is installed.

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

## Removing the mod

Take the jar out and the world loads. `mcacrime.dat` is simply never read again, and the player
attachment (`mcacrime:player_crime`) data becomes inert NBT on each player. Nothing is deleted, so putting the jar back later
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

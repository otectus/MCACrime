# MCA: Crime — Java API

Package `dev.otectus.mcacrime.api`. Everything a companion mod needs is here: a read-only facade,
immutable model types, and Forge events.

## Victim and witness context (0.6.0)

`McaCrimeApi.victimMemories(MinecraftServer, UUID villager, UUID player)` returns immutable
`VictimMemoryView` records scoped to that pair. Each includes incident/victim identity, category,
timestamp, severity, current fear/anger, repeat count, whether the memory is indirect, and apology,
restitution and sentence status. Empty means absent or disabled memory; queries never create purse
or memory records. Invoke on the server thread. Detailed emotional values are not automatically
sent to clients.

`VictimCrimeMemoryChangedEvent` is posted after a stored memory changes. `getVillager()`,
`getMemory()` and `getReason()` expose the new immutable context; reasons are `created`, `witnessed`,
`family`, `informed`, `apology`, and `reconciled`. Lazy decay is reflected in queries and does not
emit an event every tick. Conversation and quest addons can combine these with existing
`CrimeObservationEvent`, `CrimeReportEvent` and `WitnessReactionChangedEvent` hooks. Report events
retain incident identity and local jurisdiction for optional settlement integrations.

**Nullable suspect contract:** observations and reports may have no suspect in 0.6.0. A hearing-only
observation carries no perpetrator identity. Consumers of `getSuspectId()` must accept null;
guard authorship alone no longer makes a low-confidence report sufficient for arrest. One-hop
family accounts use `ObserverRole.INFORMED` and cannot become direct eyewitness testimony.

No Conversations, Quests or Capitals class is referenced by the new common code. The existing
Reputation adapter receives civic crime incidents only after sufficiently confident local reporting.

---

## Contracts

Bounty payment completion is now separate from entitlement reservation. `BountyResolvedEvent` and
Karma follow confirmed full delivery, which may be delayed until login or `/crime collectbounty`.
The claim continues consuming its full principal while payment is queued. A provider failure retains
an uncertain receipt; operator acknowledgement changes bookkeeping without replaying events/Karma.
Companions must not infer immediate payment from an arrest, death or `Payout.claimed()` alone.

Kill `BountyResolvedEvent` notifications now follow shared death confirmation at tick end (or the
pre-save reconciliation boundary), rather than running inside `LivingDeathEvent`. Canceled deaths
and revival before reconciliation emit no kill reward. Kill claims require separate bounty and
lethal-force permission and retain the sampled warrant revision, price and currency. Internal
death/recovery services are not additions to API v1. See the
[death and recovery phase notes](docs/MCA_CRIME_PHASE2_DEATH_RECOVERY.md) for provider failure limits.

Guard assessments now share `justice/JusticeService` internally. Challenge review, quotes and arrest
use the same local case selection; `/crime debug guards` exposes the basis and selected IDs to operators.
These implementation classes are not additions to API v1. Existing `FinePaidEvent` case IDs remain
the exact cases settled. Resolution preflights run before debit; all selected case writes and the
live player's Heat update precede resolution notifications. A notification failure is logged and
does not undo payment or skip later case notifications; there is no new durable event retry mechanism.

Three rules hold for every method in `McaCrimeApi`, and they are the reason the surface looks the way
it does.

1. **Nothing throws at an integration.** A dialogue evaluation or a quest condition must never crash
   because of this mod. Failures come back as an empty `Optional` or a neutral value. If you find a
   method that can throw, that is a bug here, not something to guard against on your side.
2. **Only immutable views cross the boundary.** Never a `CrimeRecord`, never a capability, never the
   `SavedData`. Every collection inside a view is defensively copied, so holding a view cannot let
   you reach back into the ledger.
3. **Reads are scoped to the player you ask about.** There is no "any player" query, and that is what
   structurally prevents one player enumerating another's history through a companion mod.
   Administrative cross-player reads go through `/crime ledger`, which has its own permission check.

**Mutation is deliberately not exposed.** Every write stays server-internal behind one state
chokepoint and one case service, which is what makes replays and cross-mod retries safe to make
idempotent. If you need to change state, do it through an event you react to, not a setter.

Everything here is **server-side**. Call it with a `ServerPlayer` or a `MinecraftServer`; there is no
client-side equivalent, because the client is never authoritative about any of it.

## Versioning

```java
int version = McaCrimeApi.getApiVersion();   // currently 1
```

`getApiVersion()` is a **method, not a constant**, and that is load-bearing. `javac` copies a
`public static final int` straight into the consumer's own constant pool, so a companion compiled
against v1 would keep reading `1` forever and the handshake it was written to perform would silently
never fire. Read it at runtime and refuse a version you do not understand:

```java
if (McaCrimeApi.getApiVersion() != EXPECTED_API_VERSION) {
    LOGGER.warn("MCA: Crime API v{} is not supported; integration disabled.",
            McaCrimeApi.getApiVersion());
    return;
}
```

## Depending on it

Compile against it, do not ship it:

```gradle
compileOnly files("../MCACrime/build/classes/java/main")
```

Declare it optional in `mods.toml`, ordered `AFTER` so its registration has happened:

```toml
[[dependencies.yourmod]]
    modId="mcacrime"
    mandatory=false
    versionRange="[0.2,)"
    ordering="AFTER"
    side="BOTH"
```

Then reach it only behind a `ModList` check, inside `enqueueWork` during common setup so there is no
registration race. If your adapter names any `mcacrime` type directly, keep it in its own class and
load that class reflectively — otherwise the class loader resolves it on the seam class and a
standalone install fails:

```java
public static void init() {
    if (!ModList.get().isLoaded("mcacrime")) return;
    try {
        Class.forName("com.example.compat.crime.CrimeCompat")
                .getMethod("register").invoke(null);
    } catch (Throwable t) {
        LOGGER.warn("MCA: Crime integration unavailable", t);
    }
}
```

`ModList` is authoritative about what is installed; a `Class.forName` probe on its own is not.

## Core types

### `CrimeCommunityKey`

```java
public record CrimeCommunityKey(ResourceLocation dimension, int villageId)
```

The dimension-aware identity of a village, written `minecraft:overworld/3`. MCA allocates village ids
**per dimension**, so a bare id is ambiguous and this mod does not accept one anywhere.

Has a `Codec`, NBT `save`/`load`, `FriendlyByteBuf` read and write, `tryParse(String)`, `asString()`,
and a natural ordering. Every construction path returns empty rather than throwing, so hand-edited
NBT cannot fail a world load.

It is NBT-key-for-NBT-key equivalent to MCA: Reputation's `CommunityKey`, which makes conversion
total in both directions — but it is deliberately **our own type**, not an import, so nothing here
depends on Reputation being on the classpath.

### `CrimePlayerSnapshot`

```java
public record CrimePlayerSnapshot(
        UUID playerId, long karma, long heat, Band band,
        boolean wanted, boolean legalTarget,
        boolean jailed, long remainingJailTicks,
        boolean captive, boolean holdingCaptive,
        int unresolvedCaseCount, long outstandingFineTotal)
```

Everything about a player's legal standing at one instant. One object rather than six calls, so you
cannot end up holding a karma reading from before a change beside a jail state from after it.
`hasOutstandingBusiness()` is the cheap "is this player in trouble?" test.

### `CrimeRecordView`

```java
public record CrimeRecordView(
        UUID id, UUID offenderId, Optional<UUID> victimId,
        ResourceLocation crimeType, Optional<CrimeCommunityKey> community,
        Set<UUID> witnessIds, boolean witnessed,
        long committedGameTime, long heatGenerated, long karmaDelta,
        long fineAmount, long jailTicks,
        Resolution resolution, long resolutionRevision,
        Optional<UUID> linkedReputationIncidentId,
        Map<String, String> context)
```

One case. `witnessed` and `witnessIds` are **both** present because they genuinely diverge: a
jailbreak is witnessed by the authority with no named witnesses, and a record migrated from an older
save knows it was witnessed without knowing by whom. Never infer one from the other.

`actionable()` is true for `UNRESOLVED` and `ESCAPED` — the two states where something is still owed.

### `CrimeRecordQuery` and `CrimeRecordSelector`

A bounded filter builder and the pure function that applies it. The query **clamps rather than
throws**: at most 100 results (25 by default) and at most 16 filter entries, because a query can be
built from a decoded packet and a throw inside a decoder drops the connection.

`CrimeRecordQuery.ACTIONABLE` is the prebuilt `{UNRESOLVED, ESCAPED}` filter; `ANY` matches
everything. Ordering is deterministic with an explicit tiebreak — a quest reward resolves a selector
to an exact case before mutating, so an ordering bug there would not produce a wrong list, it would
pardon the wrong crime.

### `CustodyView`

```java
public record CustodyView(
        UUID captiveId, boolean captiveIsPlayer, boolean lawful,
        Optional<UUID> captorId, RestraintType restraint,
        long realTicksHeld, long remainingJailTicks,
        Optional<ResourceLocation> holdDimension,
        Optional<UUID> custodyId, Optional<UUID> linkedCaseId)
```

Works for players *and* villagers. `lawful` is the whole point — `kidnapping()` is its inverse, and
the difference decides whether escaping is a crime. Holds no entity reference, because a view may
outlive the entity it describes.

### `JailSentenceView`

`McaCrimeApi.sentence(player)` includes the persisted sentence UUID and the still-actionable cases
bound to that exact sentence. A lawful cuff escape keeps the same UUID and cases and reports
`escaped = true`; it does not settle them. The custody view's separate `custodyId` and `linkedCaseId`
remain empty pending a canonical custody identity/link contract; neither is synthesized from a sentence UUID.

```java
public record JailSentenceView(
        Optional<UUID> sentenceId,
        long remainingOnlineTicks, long realOnlineTicksServed,
        Optional<ResourceLocation> jailDimension, boolean escaped,
        JailContainmentMode containmentMode, Set<UUID> linkedCaseIds)
```

Both tick counts are **online** ticks. `linkedCaseIds` is what makes serving a sentence mean
something specific: those cases resolve, and no others. It is the difference between atonement and
blanket amnesty.

### `CrimeMutationStatus`

One enum shared by every mutation result rather than one per operation: `APPLIED`, `DUPLICATE`,
`NO_MATCH`, `NOT_ALLOWED`, `INSUFFICIENT_PAYMENT`, `INVALID_STATE`, `DISABLED`, `ERROR`.

`successful()` is true for `APPLIED` **and `DUPLICATE`**. This matters more than it looks: treating
"this exact transaction already landed" as a failure is the easiest way to turn a safe replay into a
double charge. `parse(String)` is fail-safe and returns `ERROR` for anything unrecognised.

## `McaCrimeApi`

```java
static int getApiVersion();

static long    getKarma(ServerPlayer player);
static long    getHeat(ServerPlayer player);
static Band    getBand(ServerPlayer player);
static boolean isWanted(ServerPlayer player);

static Optional<CrimePlayerSnapshot> snapshot(ServerPlayer player);

static Optional<CrimeRecordView> record(MinecraftServer server, UUID recordId);
static List<CrimeRecordView>     selectRecords(ServerPlayer player, CrimeRecordQuery query);
static int                       unresolvedCaseCount(ServerPlayer player);

static Optional<CustodyView>      custody(MinecraftServer server, UUID entityId);
static Optional<JailSentenceView> sentence(ServerPlayer player);

static int communityStanding(MinecraftServer server, UUID playerId, CrimeCommunityKey community);
```

`custody` takes any entity UUID, player or villager. `communityStanding` reads from whichever store
is authoritative — the built-in one, or MCA: Reputation when it holds authority — so you do not have
to know which is in charge.

## Forge events

All on `MinecraftForge.EVENT_BUS`, all server-side, and **none of them are cancellable**. They fire
after the state has already changed; they are notifications, not vetoes. Most extend `CrimeEvent`,
which carries `getPlayer()`.

| Event | Fires when | Notable |
|---|---|---|
| `CrimeWitnessedEvent` | A crime was seen, immediately **before** `CrimeCommittedEvent` | "The law saw it" is a different fact from "a crime happened". Carries the witness count *and* their ids. |
| `CrimeCommittedEvent` | Once per recorded crime, after karma and Heat are applied and the ledger is written | Carries the karma and Heat **actually applied** (after witnessing scaling), the record id, an `Optional<CrimeRecordView>`, and the community. The seven-argument constructor is deprecated in favour of the view-carrying one. |
| `CrimeRecordResolvedEvent` | A case genuinely changes disposition | Carries `getBefore()` **and** `getAfter()` plus the resolution entry. Unresolved-to-fined is restitution; escaped-to-fined is settling up after running. A listener given only the destination cannot tell them apart. A no-op transition posts nothing. |
| `FinePaidEvent` | Once per payment | Never once per case. Carries the transaction id, every case the payment settled, the amount, and the old and new Heat. |
| `HeatChangedEvent` | Every committed Heat move | Not just threshold crossings — an objective like "get your Heat under 20" needs the ones in between. Carries a `source` and a `dedupeKey` so a redelivery is recognisable. |
| `KarmaChangedEvent` | Every committed karma move | Carries old and new karma, old and new band, `isBandChanged()`, and a `KarmaSource`. |
| `WantedStatusChangedEvent` | Only on the Wanted flip | Not on every Heat change. |
| `PlayerJailedEvent` | On the not-already-jailed transition | Ticks are online ticks; the anchor may be null. |
| `PlayerReleasedFromJailEvent` | Any release path | Carries a `ReleaseReason` — served, captivity cap, admin, pardon, invalid jail. Idempotent: one release, one event. |
| `EntityKidnappedEvent` | Custody begins unlawfully | **Extends `Event`, not `CrimeEvent`**, because either party may be an NPC. Captive and captor are UUIDs with `isCaptivePlayer()` / `isCaptorPlayer()` flags and nullable `ServerPlayer` convenience accessors. Carries the `RestraintType`. |
| `EntityReleasedFromCaptivityEvent` | Custody ends | Same shape, with a `CustodyReleaseReason` — escaped, rescued, captor gone, cap reached, admin, ransom paid, served, died. Idempotent. |

### Choosing between the two crime events

During an incident commit, the ledger row is inserted first, both player values are applied, then
observations and victim memories are installed. Karma/Heat/Wanted, observation/report/reaction/memory
post-events, and the final witnessed/committed notifications are deferred until that work is complete.
Cancelable evidence preflights still run synchronously and can suppress evidence, not undo the act.
Incident Heat events use the case UUID as their `dedupeKey`. A throwing notification is logged and
later queued notifications still run; delivery to later listeners of the same Forge event is subject
to Forge's event-bus exception behavior and is not retried durably.

NPC commits now emit `NpcCrimeCommittedEvent` centrally, after property transfer and case/evidence
installation, with the existing mug session ID as both transaction and case ID. Interrupted attempts
use that session ID too, so their later completion cannot create another case under it. The legacy
NPC facade emits this notification itself; integrations must not post it again. Paid ransom records
use their demand ID, retain the existing zero Karma/Heat audit behavior, and do not emit a second
player crime event or second set of private capture consequences.

New damage cases include `combat_encounter`, `combat_initiator`, `combat_basis`, and
`damage_attribution` in bounded record context. These are diagnostic provenance, not new public
legal permissions. Combat is reconciled at server tick end; `LivingHurtEvent` is too early to read
a resulting Crime case. No public API version, packet shape or schema changes in this pass.

Use `CrimeWitnessedEvent` when you care that the village *knows*: gossip, guard reactions, public
standing. Use `CrimeCommittedEvent` when you care that it *happened*: a quest that counts kills, a
statistic, a hidden-history record. An unwitnessed murder posts only the second.

## Idempotency

Cross-mod work in this mod is queued durably and retried, which means your listener may see the same
logical deed reported more than once across a crash or a companion being reinstalled. Three things
make that safe, and you should use all three:

- **Dedupe keys.** `HeatChangedEvent.getDedupeKey()` and `FinePaidEvent.getTransactionId()` identify
  the transaction, not the moment. Same key means same event, however many times you see it.
- **Revisions.** `CrimeRecordView.resolutionRevision()` is monotonic per record. A resolution you
  have already applied has a revision you have already seen.
- **`DUPLICATE` is success.** If you expose your own mutation and it returns a status, model it on
  `CrimeMutationStatus` and make already-applied a success. The world reflects what the caller
  wanted; it simply was not this call that did it.

A no-op posts nothing at all. If you receive an event, something actually changed — the question is
only whether you have already accounted for it.

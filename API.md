# MCA: Crime — Java API

Package `dev.otectus.mcacrime.api`. Everything a companion mod needs is here: a read-only facade,
immutable model types, and events on `NeoForge.EVENT_BUS`.

---

## Contracts

Three rules hold for every method in `McaCrimeApi`, and they are the reason the surface looks the way
it does.

1. **Nothing throws at an integration.** A dialogue evaluation or a quest condition must never crash
   because of this mod. Failures come back as an empty `Optional` or a neutral value. If you find a
   method that can throw, that is a bug here, not something to guard against on your side.
2. **Only immutable views cross the boundary.** Never a `CrimeRecord`, never an attachment, never the
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

Declare it optional in `neoforge.mods.toml`, ordered `AFTER` so its registration has happened:

```toml
[[dependencies.yourmod]]
    modId="mcacrime"
    mandatory=false
    versionRange="[0.2,)"
    ordering="AFTER"
    side="BOTH"
```

Then reach it only behind a `ModList` check, inside `enqueueWork` during common setup so there is no
registration race. **MCA is bound reflectively, so addons must not assume MCA on the compile
classpath.** If your adapter names any `mcacrime` type directly, keep it in its own class and load
that class reflectively — otherwise the class loader resolves it on the seam class and a
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

## Events

All on `NeoForge.EVENT_BUS`, all server-side. Two of them are cancellable Pre events implementing
`ICancellableEvent`. They fire after the state has already changed; they are notifications, not
vetoes for most of them. Most extend `CrimeEvent`, which carries `getPlayer()`.

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
| `CrimeObservationEvent.Pre` | An NPC learns about a crime | **Cancellable.** Implementing `ICancellableEvent`; cancel to prevent this NPC from witnessing anything. Call `setCanceled(true)` to cancel. |
| `CrimeObservationEvent.Post` | An NPC has learned about a crime | Notification; already stored. Not cancellable. |
| `CrimeReportEvent.Pre` | An observation reaches an authority | **Cancellable.** Implementing `ICancellableEvent`; cancel to suppress this one report, leaving other observations of the same incident untouched. Call `setCanceled(true)` to cancel. |
| `CrimeReportEvent.Post` | A report has been filed | Notification; already stored. Not cancellable. |

### Choosing between the two crime events

Use `CrimeWitnessedEvent` when you care that the village *knows*: gossip, guard reactions, public
standing. Use `CrimeCommittedEvent` when you care that it *happened*: a quest that counts kills, a
statistic, a hidden-history record. An unwitnessed murder posts only the second.

### Choosing between observation and report events

Use `CrimeObservationEvent.Pre` when you want to prevent an NPC learning about a crime
(charmed/blinded/sleeping NPC). Use `CrimeReportEvent.Pre` when you want to suppress the filing of a
report (bribery, intimidation, corrupt jurisdiction) without affecting other observations of the
same incident.

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

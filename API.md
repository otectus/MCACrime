# MCA: Crime — Java API

Package `dev.otectus.mcacrime.api`. Everything a companion mod needs is here: a facade that reads —
with two named exceptions in 0.7.5, clemency over a capital sentence — immutable model types, a
registration front door for third-party restraints, and NeoForge events.

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
int version = McaCrimeApi.getApiVersion();   // currently 2
```

Version 2 is 0.7.5. Everything it adds is **additive** — no existing method changed shape, and a v1
consumer keeps working — but the surface is large enough to be worth a handshake: the physical
restraint, transport, detention and lock views; the capital-sentence view; eight new events; the
third-party registration front door; and `CustodyView.custodyId`, which finally carries a real value.

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

`linkedReputationIncidentId` is present once the civic incident for this case has actually been
filed in MCA: Reputation. An empty one means "not filed *yet*", never "not a real crime": the write
is queued in the outbox and may be waiting on a companion that is absent, disabled, or whose village
ledger is temporarily full. Since 0.7.3 it may also name a record MCA: Reputation deliberately kept
private, because nobody witnessed the deed. Do not use its absence as evidence about the case — the
legal facts are `resolution`, `fineAmount`, `jailTicks` and the crime type, all of which are this
mod's own and none of which a companion mod decides.

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
        Optional<UUID> captorId, RestraintType restraint,   // deprecated projection since 0.7.5
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
`escaped = true`; it does not settle them. Since 0.7.5 the custody view's `custodyId` carries a real value —
schema 15 stamps a `custodyId` and a `generation` on every custody row — while `linkedCaseId` is
still populated only where a canonical link exists. Neither is synthesized from a sentence UUID.

```java
public record JailSentenceView(
        Optional<UUID> sentenceId,
        long remainingOnlineTicks, long realOnlineTicksServed,
        Optional<ResourceLocation> jailDimension, boolean escaped,
        JailContainmentMode containmentMode, Set<UUID> linkedCaseIds,
        String sentenceKind)                                    // 0.7.5 (API v2)
```

`sentenceKind` is `"custodial"` or `"capital"`, with a `capital()` helper. It is a lowercase string
rather than an enum on purpose: an enum constant a consumer compiled against would be copied into
their constant pool, and a future third kind would break them at a `switch` rather than at the
handshake. Absent and unknown both read as custodial, which is what every sentence before 0.7.5 was.
**The 0.7.4 seven-argument constructor is kept**, so a call written before this release still
compiles and still means custodial.

Both tick counts are **online** ticks. `linkedCaseIds` is what makes serving a sentence mean
something specific: those cases resolve, and no others. It is the difference between atonement and
blanket amnesty.

### The 0.7.5 physical views

Four read-only projections of the physical half of the physical/legal split, plus one for a lock.
None of them holds an entity reference, and none of them carries a secret: no key binding, no
frisked inventory, no private case evidence.

```java
public record RestraintView(UUID subject, long generation, long revision,
                            List<RestraintSlotView> slots,
                            Optional<UUID> tetherId, Optional<UUID> detentionId,
                            Optional<ResourceLocation> dimension)

public record RestraintSlotView(String slot, ResourceLocation definitionId, float durability,
                                Optional<UUID> applier, Optional<UUID> custodyId,
                                boolean systemIssued)

public record TransportView(UUID tetherId, UUID subject, String kind, Optional<UUID> holder,
                            Optional<long[]> anchorPos, Optional<ResourceLocation> dimension,
                            double maxLength, boolean lawful)

public record DetentionView(UUID detentionId, UUID subject, String kind,
                            Optional<ResourceLocation> dimension, long[] device,
                            long generation, boolean condemned)

public record LockView(UUID lockId, boolean locked, boolean reinforced,
                       Optional<ResourceLocation> dimension, Optional<long[]> pos)
```

`RestraintView.slots` is one entry per filled slot — head, arms, legs — each with its own durability
fraction and its own applier, because durability lives on the worn instance rather than on an item
somebody is carrying. `generation` distinguishes two successive holds of the same subject;
`revision` orders snapshots. `systemIssued` marks gear the mod supplied at arrest, which is never
returned on removal.

`TransportView.lawful` is the whole point of the record existing separately: a guard's escort and a
kidnapper's chain are the same physical fact and different legal ones. `DetentionView.condemned`
says a capital sentence is live, not that an execution is authorised.

`LockView` never carries the binding. A view cannot mint a key.

### `CapitalSentenceView`

```java
public record CapitalSentenceView(UUID subject, boolean subjectIsPlayer, UUID sentenceId,
                                  long holdingTicks, boolean pendingExecution,
                                  Optional<long[]> device, long expiresAt)
```

`windowRemaining(long now)` reports how much of the ceremony window is left, or zero when none is
running. `pendingExecution` is the only state in which a device may act, and it is cleared by
clemency, rescue, escape, guard death, device destruction or a chunk unload — each of which returns
the subject to custody, never to freedom. A view with `pendingExecution = false` and a live sentence
is a condemned prisoner sitting in a cell, which is the normal state when there is no usable device.

### `FriskSessionView`

```java
public record FriskSessionView(UUID sessionId, UUID searcher, UUID subject, boolean lawful,
                               long revision, int seizures)
```

A search in progress, **without its contents**, and that omission is the design. Only the authorised
searcher ever receives what is in somebody's pockets, and it goes to their own client through the
frisking payloads — never into a public view a third mod could read, queue or log. What a companion
may know is that a search is happening, who is searching whom, whether it is lawful, and how many
stacks have changed hands.

### `CrimePublicView`

What one community may know about one person, and nothing else — the projection with the knowledge
rule applied.

```java
record CrimePublicView(CrimeCommunityKey community, UUID subject, Band band, boolean wanted,
                       int standing, int publicIncidents, int openIncidents,
                       long openBountyAmount, List<PublicIncident> recent) {

    record PublicIncident(UUID caseId, ResourceLocation crimeType, long gameTime,
                          Resolution resolution) {
        boolean open();           // UNRESOLVED or ESCAPED
    }

    static final int MAX_RECENT = 16;
    static CrimePublicView empty(CrimeCommunityKey community, UUID subject);
    boolean known();              // any public incident, wanted, or a posted bounty
}
```

`recent` is capped at `MAX_RECENT`, newest first; the counts carry the magnitude. `standing` is
`effectiveStanding` for that community. A `PublicIncident` deliberately carries no witnesses, no
victim, no heat and no fine: it is a thing the village can say out loud.

The membership rule is `CrimePublicView.isPublic`, and it is the same rule the civic incident filing
uses, so what a village reacts to and what MCA: Reputation recorded cannot drift apart. In order: the
case must belong to this community; somebody must have seen it, unless an authority knew by its
nature (a jailbreak, an operator command); and, unless the authority already knew, with the
observation layer enabled it must also have reached an authority.

The client-facing sibling is `civic/VillageSecurityView`, a whole settlement rather than one person.
It reaches a client as the `mcacrime:village_security` payload, answered on request of
`mcacrime:request_village_security`; both are `CustomPacketPayload` records with `StreamCodec`s, like
every other packet on this line.

### `ServiceRefusalView`

Whether one villager will serve one person, and what they say if not.

```java
record ServiceRefusalView(boolean refused, String kind, String reasonKey, String repairKey)
```

`kind` is the service's stable id — one of `essential_food`, `essential_shelter`, `trade`, `luxury`,
`fence`. Both `reasonKey` and `repairKey` are **translation keys, never sentences**, and are empty
when nothing was refused; MCA: Crime never sends rendered text over the wire. A refusal is either
personal (this villager remembers being harmed, and it fades as the memory decays) or public (a case
this settlement knows about is open against the subject). Requires `townstead.serviceRestrictions`;
with it off, every answer is "not refused".

### `CivicContractView`

One civic service contract, as much of it as anybody outside MCA: Crime needs.

```java
record CivicContractView(UUID contractId, UUID caseId, UUID offender, String community,
                         String task, int requiredUnits, int completedUnits, long deadline,
                         String state)
```

`community` is a `CrimeCommunityKey` in its `dimension/villageId` string form, with
`communityKey()` to parse it; `remainingUnits()` is the work still owed. `task` is one of
`guard_assist_patrol`, `restitution_delivery`, `victim_amends`, and `state` one of `offered`,
`active`, `completed`, `failed`, `cancelled`.

It deliberately carries no price, no case contents and no offender record: this is the extension
point a quest mod would draw from — not an adapter, because MCA: Quests has no runtime
quest-creation API — and a presentation layer that could see those could disagree with the screen
the player already has. Crime keeps case and completion authority. No contract is created while
`townstead.communityService` is off, so on a server that has never enabled it the list is always
empty; contracts created earlier remain listed.

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

// 0.7.5 (API v2). The physical half of the physical/legal split: what is on a subject, what holds
// them, what device has them, and what a lock is. Read-only, and none of them carries a secret --
// no key binding, no frisked inventory, no private case evidence.
static Optional<RestraintView>  restraints(MinecraftServer server, UUID subjectId);
static Optional<TransportView>  transport(MinecraftServer server, UUID subjectId);
static Optional<DetentionView>  detention(MinecraftServer server, UUID subjectId);
static Optional<LockView>       lock(MinecraftServer server, UUID lockId);

// 0.7.5 (API v2). The capital sentence, if there is one. There is deliberately no method to assign
// one or to carry one out: an execution is a deliberate act at a device. Clemency is the one
// exception, immediately below.
static Optional<CapitalSentenceView> capitalSentence(MinecraftServer server, UUID subjectId);

// 0.7.5 (API v2). The two exceptions to "this facade only reads": clemency over a capital sentence,
// which has to be reachable as an explicit privileged transaction rather than only from an
// operator's keyboard. Both take the same service path as `/crime capital commute|pardon`.
// `Clemency` is GRANTED / NOT_CONDEMNED / REFUSED.
static CapitalSentenceService.Clemency commuteCapitalSentence(
        MinecraftServer server, UUID subjectId, @Nullable UUID authority);
static CapitalSentenceService.Clemency pardonCapitalSentence(
        MinecraftServer server, UUID subjectId, @Nullable UUID authority);

static int communityStanding(MinecraftServer server, UUID playerId, CrimeCommunityKey community);
static int effectiveStanding(MinecraftServer server, UUID playerId, CrimeCommunityKey community);

static Optional<CrimePublicView> publicView(MinecraftServer server, CrimeCommunityKey community,
                                            UUID subject);
static Optional<CrimePublicView> publicView(ServerPlayer player, CrimeCommunityKey community);

static ServiceRefusalView       serviceRefusal(ServerLevel level, Entity provider,
                                               UUID subject, String serviceKind);
static List<CivicContractView>  civicContracts(MinecraftServer server, UUID offender);
```

`serviceRefusal` answers the same question MCA: Crime asks itself before a villager serves somebody,
so a companion does not re-implement the rule from a band and a wanted flag. An unrecognised service
kind, a failure, or the feature being off all answer "not refused" — the safe direction, because the
one rule that must never be got wrong is that an essential service is never refused.

`civicContracts` is read-only and has no companion method to accept or advance a contract. Work is
credited only from transitions MCA: Crime observed itself, so a presentation layer can draw a
contract and cannot complete one.

`commuteCapitalSentence` rewrites the binding to custodial, keeps the holding term, clears any
pending execution and posts `SentenceCommutedEvent`. `pardonCapitalSentence` pardons the cases the
sentence was for, attributed to `authority`. Nothing else on the facade touches a capital sentence:
there is no way to assign one, arm a device or carry an execution out, and `CapitalClemencyApiTest`
asserts that these two are the only capital mutators.

`custody` takes any entity UUID, player or villager.

**The two standing methods are not interchangeable.** `communityStanding` reads MCA: Crime's *own*
per-village store and nothing else — it always did, and it was left that way so no existing caller
silently changes meaning. `effectiveStanding` is the number a settlement should act on: MCA:
Reputation's, when it is installed and holding the authority for these deeds, and MCA: Crime's own
store otherwise. It falls back rather than failing on every uncertainty — companion absent,
integration switched off, a score it does not hold.

`publicView` is the method to call from a settlement reaction, a dialogue condition, or anything that
draws a status where other people can see it. `selectRecords` is the player's own file and is scoped
to them for exactly that reason; a public view is one *community's* knowledge, and the difference is
every unwitnessed crime, every crime in another village, and every crime nobody has reported yet.
Both overloads answer `Optional.empty()` on bad input or an internal failure rather than throwing.

`compat/TownsteadBridge` and everything under `compat/` is **not** public API. It is this mod's own
optional-classloading seam, it may change shape in any release, and a companion should read the
`api` package instead.

## `RestraintRegistrationApi` (0.7.5)

The front door for another mod adding a restraint definition or a searchable inventory. It exists
because the alternative is what companion mods do when there is none: reflect into the definition
registry, mutate the map, and break on the release that changes its shape.

```java
public static boolean  open();
public static Result   registerDefinition(RestraintDefinition definition);
public static Result   registerInventoryProvider(InventoryProvider provider);
public static Optional<RestraintDefinition> definition(ResourceLocation id);
public static int      thirdPartyDefinitionCount();
public static final int MAX_THIRD_PARTY_DEFINITIONS = 64;
```

It registers **definitions and inventory providers, and nothing else**. Four rules make it safe to
keep working:

- **Unique, foreign ids.** A registration carries its own namespace. Nothing may claim an id in
  `mcacrime`, and nothing replaces an existing definition — including another mod's. A silently
  replaced definition is a prisoner whose cuffs change behaviour when an unrelated mod updates.
- **A closed window.** Registrations are accepted during mod setup and refused afterwards
  (`open()` reports whether the window is still open). A definition arriving after a world loaded
  would be absent from every row already written and present in every row written next.
- **Bounded.** `MAX_THIRD_PARTY_DEFINITIONS` caps how many exist at all, because each is a row in a
  client snapshot and a branch in a restriction composition.
- **No registry sniffing.** Nothing scans another mod's registries or guesses at its items.

Every method reports rather than throws: `Result` is `ACCEPTED`, `TOO_LATE`, `BAD_ID`, `DUPLICATE`,
`FULL` or `INVALID`, with `accepted()` for the happy path. A companion that fails to register should
log one line and keep working, not take the game down during setup.

## `CrimeDialogueHooks` — voicing this mod's lines (0.7.5)

`api/CrimeDialogueHooks` holds an ordered chain of `api/CrimeDialogueResolver`s. `dialogue/CrimeDialogueService.speak`
asks the chain before every line it sends — the guard's challenge, the stand-down, a filed report, an
accepted apology, the fence's greetings — passing the speaker (nullable), the listener, the event id
(a `dialogue/DialogueEvents` constant), the selection `DialogueContext` and the datapack line as the
fallback. The first non-null `Component` wins; a resolver that throws hands over to the next, and the
datapack line is used when none answers, so a misbehaving add-on can never silence a guard.

```java
CrimeDialogueHooks.addResolver("mcaconversations",
        (speaker, listener, event, context, fallback) -> voiced(speaker, listener, event).orElse(null));
```

`addResolver(id, resolver)` replaces an existing id in place; `removeResolver(id)` is idempotent;
`resolverIds()` lists the chain for `/crime debug integrations`. Registered from an add-on's common
setup; MCA: Conversations 1.8.0 is the first consumer.

## Deprecations

- **`dev.otectus.mcacrime.captivity.RestraintType` (0.7.5).** A subject now has three independent
  restraint slots, so one enum cannot describe what they are wearing. The type is retained only as
  the projection behind `EntityKidnappedEvent#getRestraint` and `CustodyView#restraint`, filled by
  `restraint/LegacyRestraintProjection`, and it is **never written to world data again**. Read
  `McaCrimeApi.restraints(...)` instead.
- The seven-argument constructors on `CrimeCommittedEvent` and `CrimeWitnessedEvent` remain
  deprecated in favour of the view-carrying ones (unchanged from 0.7.4).

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
| `RestraintAppliedEvent` (0.7.5) | A restraint has been committed onto a subject | Post-commit. Carries the slot, the definition id, the context (`voluntary`, `unlawful`, `lawful`, …) and a `RestraintSlotView`. Applying a restraint is not an arrest; the legal side has its own events. |
| `RestraintRemovedEvent` (0.7.5) | A restraint comes off | Carries a `Cause`: released, struggled, broken, administrative. **Removing one restraint is not a release** — the subject may still be chained, detained and sentenced. |
| `PhysicalEscapeEvent` (0.7.5) | A subject gets free of everything holding them | Says whether it was lawful custody and whether a jailbreak was filed. A kidnapping escape files none and is not a crime; a listener treating this as one would punish victims. |
| `SubjectSeizedEvent` (0.7.5) | Somebody is chained, anchored or escorted | Carries the kind and whether the hold is lawful. A guard's escort and a kidnapper's chain are the same physical event and different legal ones. |
| `DetentionOutcomeEvent` (0.7.5) | A device stops holding somebody | Every ending, including the ones nobody chose: broke out, device gone, occupant died, executed. |
| `CapitalSentenceAssignedEvent` (0.7.5) | An arrest bound a capital sentence | Not a countdown: with no usable device the subject serves the holding term in a cell. |
| `ExecutionCarriedOutEvent` (0.7.5) | A capital sentence was carried out | Only on a **confirmed** death. A totem, PlayerRevive or a cancelled damage event raises nothing at all. |
| `SentenceCommutedEvent` (0.7.5) | Clemency ended a capital sentence | `isPardon()` tells a commutation (keeps the term and the cases) from a pardon (closes the cases too). |
| `CrimeObservationEvent.Pre` | An NPC learns about a crime | **Cancellable.** Implementing `ICancellableEvent`; cancel to prevent this NPC from witnessing anything. Call `setCanceled(true)` to cancel. |
| `CrimeObservationEvent.Post` | An NPC has learned about a crime | Notification; already stored. Not cancellable. |
| `CrimeReportEvent.Pre` | An observation reaches an authority | **Cancellable.** Implementing `ICancellableEvent`; cancel to suppress this one report, leaving other observations of the same incident untouched. Call `setCanceled(true)` to cancel. |
| `CrimeReportEvent.Post` | A report has been filed | Notification; already stored. Not cancellable. |

### Choosing between the two crime events

During an incident commit, the ledger row is inserted first, both player values are applied, then
observations and victim memories are installed. Karma/Heat/Wanted, observation/report/reaction/memory
post-events, and the final witnessed/committed notifications are deferred until that work is complete.
Cancelable evidence preflights still run synchronously and can suppress evidence, not undo the act.
Incident Heat events use the case UUID as their `dedupeKey`. A throwing notification is logged and
later queued notifications still run; delivery to later listeners of the same NeoForge event is subject
to Forge's event-bus exception behavior and is not retried durably.

NPC commits now emit `NpcCrimeCommittedEvent` centrally, after property transfer and case/evidence
installation, with the existing mug session ID as both transaction and case ID. Interrupted attempts
use that session ID too, so their later completion cannot create another case under it. The legacy
NPC facade emits this notification itself; integrations must not post it again. Paid ransom records
use their demand ID, retain the existing zero Karma/Heat audit behavior, and do not emit a second
player crime event or second set of private capture consequences.

New damage cases include `combat_encounter`, `combat_initiator`, `combat_basis`, and
`damage_attribution` in bounded record context. These are diagnostic provenance, not new public
legal permissions. Combat is reconciled at server tick end; `LivingDamageEvent.Pre` (and Epic Fight's `LivingIncomingDamageEvent` stage) is too early to read
a resulting Crime case. No public API version, packet shape or schema changes in this pass.

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

# 0.7.3 verification record (NeoForge 1.21.1 port)

Every check that was actually run for the MCA: Reputation 0.6.0 adoption on this line, with its log.
Anything that needs a running client or dedicated server was **not** run in this session and is
listed as outstanding rather than as passed.

## Provenance

| | |
|---|---|
| Branch | `neoforge/1.21.1`, created from `master` @ `b495e5f` (clean tree) |
| Reputation companion | MCA: Reputation NeoForge **0.6.0**, API version **2**, compiled against a stable snapshot of its `build/classes/java/main` passed as `-PmcaReputationClasses=<dir>` (the sibling `../MCAReputation_1.21.1` was being rebuilt concurrently) |
| Quests companion | MCA: Quests NeoForge **1.6.6**, `../MCAQuests_1.21.1/build/classes/java/main` passed as `-PmcaQuestsClasses=<dir>` |
| Companion checkouts | read only — neither was edited, and no Gradle task was run in either |
| Forge checkout | read only — `05953b0` on `feature/reputation-0.6.0` was inspected for its message and diffs; nothing in it was modified |
| Version bump | `gradle.properties` `mod_version=0.7.2` → `0.7.3`, nowhere else |

Every Gradle invocation carried all four properties:

```
-PmcaReputationClasses=<snapshot>/classes
-PmcaQuestsClasses="/home/otectus/Projects/1.21.1 Ports/MCAQuests_1.21.1/build/classes/java/main"
-PrequireReputation=true -PrequireQuests=true
```

`-PrequireReputation=true` and `-PrequireQuests=true` are what make this evidence mean anything: they
turn a missing companion into a hard build failure instead of quietly excluding
`compat/reputation/**` or `compat/mcaquests/**` from compilation. They passed, so the two adapters —
the only files in the mod that name either companion — really were compiled against MCA: Reputation
0.6.0 and MCA: Quests 1.6.6 rather than skipped.

## Commands

All through `/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh "/home/otectus/Projects/1.21.1 Ports/MCACrime_1.21.1" <task>` with the four properties above.

| Command | Result | Log |
|---|---|---|
| `check` — **baseline**, before any source change, after the build.gradle property work only | PASS (36s); 1610 tests, 0 failures, 1 skipped | `/tmp/gradle-MCACrime_1.21.1-check-20260916-164118.log` |
| `compileJava` | PASS (16s) | `/tmp/gradle-MCACrime_1.21.1-compileJava-20260916-164612.log` |
| `check` | FAIL — `compileTestJava`: `ResourceLocation(String,String) has private access` in the new `SupersedePolicyTest`, a Forge-only idiom the port had to re-express | `/tmp/gradle-MCACrime_1.21.1-check-20260916-164635.log` |
| `check` (after `ResourceLocation.fromNamespaceAndPath`) | PASS (31s) | `/tmp/gradle-MCACrime_1.21.1-check-20260916-164702.log` |
| `build --rerun-tasks` (runs `checkJarContents`) | PASS (39s) | `/tmp/gradle-MCACrime_1.21.1-build-20260916-165048.log` |
| `javap -classpath <snapshot> -p -constants dev.otectus.mcareputation.api.McaReputationApi` | `private static final int API_VERSION = 2;` — the evidence behind the API-gate fix | stdout |
| `javap -classpath <snapshot> -constants dev.otectus.mcareputation.api.ReputationCapabilities` | all ten `FEATURE_*` strings `ReputationCapabilitySnapshot` mirrors are present with the same values on this loader (the companion declares eighteen; the ten mirrored ones are `delivery`, `receipts`, `read_only_lookup`, `supersede`, `bound_resolution` and the five `*_v1` profile strings) | stdout |
| `javap -classpath <snapshot> -p dev.otectus.mcareputation.incident.IncidentDefinition` | the record carries `socialProfile`, which is what allowed `social_profile` onto `CrimeIncidentDataTest`'s known-key allow-list | stdout |

`--rerun-tasks` was used on the final `build` so the whole suite and the jar checks re-ran rather
than being served from the up-to-date cache.

`check_mod.py` and `modmap.py` were **not** used as evidence: both are Forge-1.20.1-shaped and this
project's `CLAUDE.md` rules them out here. `modmap.py` was tried once against this tree and its
output was discarded — see "MODMAP" below.

## Tests

`build/test-results/test/*.xml`: **1663 tests, 0 failures, 0 errors, 1 skipped** across 212 suites,
under ModDevGradle's NeoForge JUnit runner. Baseline on the same tree before any source change was
**1610 / 0 / 0 / 1**, so this release adds 53 tests and the single pre-existing skip is unchanged.

| Suite | Tests | What it pins |
|---|---:|---|
| `ReputationDeliveryMappingTest` (new) | 15 | Every `ReputationDelivery.Outcome` maps to an outbox decision both for a create and for a resolve; an accepted-private deed completes instead of dead-lettering; a full ledger is retryable and counts against the budget; a disabled companion does not; `AWAITING_LINK` eventually dead-letters |
| `SupersedePolicyTest` (new) | 14 | A killing folds the linked, unsettled assault on the same victim in the same village inside the window, newest first and deterministically — and refuses on every other combination, including a settled precursor, an undelivered one, a zero window and a non-fatal incident |
| `CrimeAuthorityPolicyTest` (new) | 9 | The claim is exactly `MCA_VILLAGER_ASSAULT` and `MCA_VILLAGER_KILL`; the four kinds this mod does not detect are never claimed however healthy the bridge; each gate hands detection back; `canDeliver` differs from `owns` only through the outbox-pump switch |
| `ReputationCapabilitySnapshotTest` (new) | 6 | A companion that cannot describe itself supports nothing new rather than everything; receipt lookup needs both halves of the promise; the profile features can go dark without the rest of the surface going with them, and the debug line says which are live |
| `CrimeIncidentDataTest` (extended, 15 → 22) | 22 | All eight incidents name a shipped profile that admits them; no orphan profiles; channel bounds, lifetimes as whole multiples of the decay step, facet existence and sign; credit class/policy agreement; schedules that actually reach zero with no tail; no repeated JSON key at any depth in the seven profiles and two policies. Its existing unknown-key test now also accepts `social_profile`, which was confirmed present on the companion's `IncidentDefinition` record before being added to the allow-list |
| `OutboxTest` (extended, 19 → 21) | 21 | The queue and retry policy still behave with three new outcome values in the enum, and an NPC offender's case is never a player's civic deed while an unstamped one still is |
| `OptionalClassloadTest` (unchanged, 7) | 7 | Still green with three new `compat` classes and one new `integration` class: none of them names `dev/otectus/mcareputation/`, and `compat/reputation/` still contains exactly `CrimeReputationCompat` and its nested classes |
| `NoMcaStaticLinkTest`, `DedicatedServerIsolationTest`, `MixinConfigTest`, `McaBindingProbeTest` (unchanged) | — | Re-ran green; no MCA static linkage, no client class named from common networking, mixin sides unchanged, and the MCA binding still resolves against every probe jar |

## Compiled output

From the final `build --rerun-tasks`:

- `build/classes/java/main/dev/otectus/mcacrime/compat/reputation/CrimeReputationCompat.class`, plus
  `CrimeReputationCompat$1.class`, `CrimeReputationCompat$CrimeCoreAuthority.class` and
  `CrimeReputationCompat$CrimeStandingMirror.class` — the Reputation adapter compiled, not excluded.
- `build/classes/java/main/dev/otectus/mcacrime/compat/mcaquests/McaQuestsBountyCompat.class` and
  `BountyObjective.class` — the Quests adapter likewise.

## Jar

`build/libs/mcacrime-0.7.3.jar`, **1,797,519 bytes**, SHA-256
`8c87dbb3d76b3fd3882a4dad824946ed212b2fc47f6464772393e64ebc4fc5e4`. Measured locally and not
independently reproduced; two builds of identical sources do **not** produce the same hash here
because the jar carries entry timestamps, so the size is the stable figure to compare.

- `checkJarContents`: clean — 1103 entries, nothing shaded, no Forge or relocated-MCA bytecode
  references, all classes Java 21.
- Carries the new data: seven `data/mcacrime/mcareputation/incident_profiles/*.json` and two
  `data/mcacrime/mcareputation/credit_policies/*.json`, alongside the eight incidents.
- `META-INF/neoforge.mods.toml`: version `0.7.3`, and the MCA: Reputation dependency is still
  `type="optional"` with `versionRange="[0.2,)"` — unchanged, so no installation is refused a load
  over this.

## MODMAP

Not regenerated. `.mcmod-tools/modmap.py` was run once from `/home/otectus/Projects/1.21.1 Ports`
and its output was deleted rather than committed: it reported `Mixins | no` for a mod with eight,
`recipes | 0` against the eight files under `data/mcacrime/recipe/`, `item tags | 0` against the
five under `data/mcacrime/tags/item/`, and only ten registered entries with no items or blocks at
all, because it does not understand `DeferredRegister.createItems` or NeoForge's 1.21 data
directories. This project's `CLAUDE.md` already says the tool is Forge-1.20.1-only and must not be
run here, and no MODMAP.md has ever been committed on this line; a generated file wrong in four
places is worse than none. The **Structure** block in `CLAUDE.md` remains the map for this port.

## What these checks do and do not establish

The unit suite deliberately runs with **MCA: Reputation absent from the test classpath**, exactly as
it runs with MCA absent. That is what makes the degradation paths genuine, and it is also the limit
of what is verified here: the always-loadable half (outcome mapping, authority policy, supersession
rules, capability snapshot, shipped JSON) is tested, while
`compat/reputation/CrimeReputationCompat` is **compile-verified only** — against MCA: Reputation
0.6.0's real class output, which is what catches a signature that does not exist, but no test
executes it.

`CrimeReputationCompat.verifyMirroredNames()` is the stronger check and it compiled: it compares
each of those ten mirrored strings against `ReputationCapabilities.FEATURE_*` and the declared kind
set against `CrimeAuthorityPolicy.declaredKinds()`, in the one place both sides are on the classpath
at once. Compilation proves the companion constants exist with those names on this loader; the
comparison itself runs at adapter registration on a real server.

The API gate fix is likewise compile- and read-verified: `API_VERSION = 2` was read out of the
companion's own class file, and the gate is now equal to it, but nothing here runs the handshake. A
server or client run with both mods installed is the only thing that can show the integration
actually coming up, and that is the first outstanding item below.

The profile and credit-policy JSON is validated against a local copy of MCA: Reputation's documented
schema, not by calling its codecs — the same approach the incident definitions have always used, and
for the same reason: the files ship in this mod's jar, so an authoring mistake must fail this build.
It does mean a schema change on the companion's side would not be caught here.

## Outstanding runtime acceptance (not run in this session)

None of the following was executed; all of it needs a client or dedicated server with both mods
installed.

1. **The gate itself.** `/crime debug integrations` reporting `state=ready` with MCA: Reputation
   0.6.0 installed, which is the check that would have caught the API-version defect this release
   fixes. On 0.7.2 the same command reported `incompatible API v2`.
2. **Capability handshake.** The same command reporting `delivery=true receipts=true supersede=true
   bound_resolution=true` and all five profile strings under `profiles=` against 0.6.0, and
   `profiles=none` against an older companion or one with profiles switched off.
3. **Profile publication.** `/mcareputation reload` reporting zero errors with this jar installed,
   and a player's standing screen showing recognition and a violence/lawfulness facet after a theft
   and a killing. A silently refused profile bundle is the specific failure mode to look for.
4. **Repeat credit.** Paying four fines inside one 14-day window and confirming recognition stops
   accruing (100 / 50 / 25 / 0) while the standing delta stays `+3` each time.
5. **Assault-to-killing parity.** Hitting a villager, letting the incident deliver, then killing them
   inside 1200 ticks, and confirming one incident with the killing's figure rather than two.
6. **Accepted-private.** An unwitnessed retained crime completing the operation with no public
   incident, no dead letter, and no local village penalty applied afterwards.
7. **Resolution before link.** Paying a fine in the same tick as the crime and confirming the
   settlement is delivered once the create lands, rather than dropped or dead-lettered.
8. **Authority scope.** With this mod installed, confirming MCA: Reputation still records villager
   rescues, cures, repelled raids and in-village player kills itself —
   `/mcareputation debug authorities` naming `mcacrime:crime_detector` for assault and killing only.
9. **No second producer.** `replayPendingOperations=false` handing assault and killing detection back
   to MCA: Reputation (`canDeliver` false) rather than leaving them recorded by nobody.
10. **NPC offenders.** An NPC thief's theft producing a case in `/crime history` and **no** standing
    record under `/mcareputation inspect` for that villager's UUID. Standing records written for NPC
    offenders by 0.7.2 or earlier are not retroactively removed by this release; nothing in this mod
    can delete another mod's records.

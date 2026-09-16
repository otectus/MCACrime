# 0.7.3 verification record

Every check that was actually run for the MCA: Reputation 0.6.0 adoption, with its log. Anything that
needs a running client or dedicated server was **not** run in this session and is listed as
outstanding rather than as passed.

## Provenance

| | |
|---|---|
| Branch | `feature/reputation-0.6.0` off `main` @ `0079641` |
| Companion | MCA: Reputation `feature/0.6.0-profiles`, compiled against `../MCAReputation/build/classes/java/main`; API version 1 |
| Companion checkout | read only — not edited, and no Gradle task was run in it |
| Version bump | `gradle.properties` `mod_version=0.7.2` → `0.7.3`, nowhere else |

## Commands

| Command | Result | Log |
|---|---|---|
| `.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCACrime compileJava` | PASS (5s) | `/tmp/gradle-MCACrime-compileJava-20260916-151412.log` |
| `.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCACrime check` | PASS (17s) | `/tmp/gradle-MCACrime-check-20260916-151316.log` |
| `.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCACrime build -PrequireReputation=true --rerun-tasks` | PASS (21s) | `/tmp/gradle-MCACrime-build-20260916-151340.log` |
| `.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCACrime build --rerun-tasks` (runs `checkJarContents`) | PASS (28s) | `/tmp/gradle-MCACrime-build-20260916-151417.log` |
| `python3 .mcmod-tools/check_mod.py MCACrime` | 1 pre-existing error, 0 warnings, 16 notes — unchanged from 0.7.2 | stdout |
| `python3 .mcmod-tools/modmap.py MCACrime` | regenerated; 15 registered entries; everything below `AUTO:END` preserved | stdout |
| `git diff --check` | no whitespace errors | stdout |

Earlier runs of the same four Gradle commands passed on intermediate states of this work
(`compileJava-20260916-145731`, `check-20260916-150224`, `build-20260916-150302`,
`build-20260916-150329`, `check-20260916-151014`, `build-20260916-151028`,
`build-20260916-151035`). The table lists the last run of each, all of them on the committed tree.

The property matters: `-PrequireReputation=true` makes a missing sibling
checkout a hard build failure instead of quietly excluding `compat/reputation/**`. It passed, which
is what proves the adapter — the only file that names the companion — actually compiled against MCA:
Reputation 0.6.0 rather than being skipped. `--rerun-tasks` was added so the whole suite re-ran under
that property rather than being served from the up-to-date cache.

## Tests

`build/test-results/test/*.xml`: **1586 tests, 0 failures, 0 errors, 7 skipped** across 204 suites.
The seven skips are the pre-existing bootstrap-gated `ItemCurrencyInventoryTest` cases, unchanged
from 0.7.2's 1533/0/0/7.

| Suite | Tests | What it pins |
|---|---:|---|
| `ReputationDeliveryMappingTest` (new) | 15 | Every `ReputationDelivery.Outcome` maps to an outbox decision both for a create and for a resolve; an accepted-private deed completes instead of dead-lettering; a full ledger is retryable and counts against the budget; a disabled companion does not; `AWAITING_LINK` eventually dead-letters |
| `CrimeAuthorityPolicyTest` (new) | 9 | The claim is exactly `MCA_VILLAGER_ASSAULT` and `MCA_VILLAGER_KILL`; the four kinds this mod does not detect are never claimed however healthy the bridge; each gate hands detection back; `canDeliver` is strictly stronger than `owns` only through the outbox-pump switch |
| `SupersedePolicyTest` (new) | 14 | A killing folds the linked, unsettled assault on the same victim in the same village inside the window, newest first and deterministically — and refuses on every other combination, including a settled precursor, an undelivered one, a zero window and a non-fatal incident |
| `CrimeIncidentDataTest` (extended, 14 → 21) | 21 | All eight incidents name a shipped profile that admits them; no orphan profiles; channel bounds, lifetimes as whole multiples of the decay step, facet existence and sign; credit class/policy agreement; schedules that actually reach zero with no tail; no repeated JSON key at any depth in the seven profiles and two policies |
| `OptionalClassloadTest` (unchanged, 7) | 7 | Still green with three new `compat` classes and one new `integration` class: none of them names `dev/otectus/mcareputation/`, and `compat/reputation/` still contains exactly `CrimeReputationCompat` and its nested classes |
| `ReputationCapabilitySnapshotTest` (new) | 6 | A companion that cannot describe itself supports nothing new rather than everything; receipt lookup needs both halves of the promise; the profile features can go dark without the rest of the surface going with them, and the debug line says which are live |
| `OutboxTest` (extended, 19 → 21) | 21 | The queue and retry policy still behave with three new outcome values in the enum, and an NPC offender's case is never a player's civic deed while an unstamped one still is |

`McaBindingProbeTest` ran against all three probe jars (0 skipped).

## Jar

`build/libs/mcacrime-0.7.3.jar`, 1,797,878 bytes, SHA-256
`e7735f7e33ceda6a8679a6dd2d7068248681500b86e6d80f6e25a70e02f2ab2a`, from the final
`build --rerun-tasks`. Measured locally and not independently reproduced; note that two builds of
identical sources do **not** produce the same hash here, because the jar carries entry timestamps —
the size is the stable figure to compare.

- `checkJarContents`: clean — no entry under `dev/otectus/mcareputation/`, `dev/otectus/mcaquests/`,
  `dev/architectury/`, `net/mca/`, `forge/net/mca/`, `net/conczin/mca/` or `melonslise/locks/`.
- Carries the new data: seven `data/mcacrime/mcareputation/incident_profiles/*.json` and two
  `data/mcacrime/mcareputation/credit_policies/*.json`.
- `META-INF/mods.toml`: `version="0.7.3"`, and the MCA: Reputation dependency is still
  `mandatory=false` with `versionRange="[0.2,)"` — unchanged, so no installation is refused a load
  over this.

## What these checks do and do not establish

The unit suite deliberately runs with **MCA: Reputation absent from the test classpath**, exactly as
it runs with MCA absent. That is what makes the degradation paths genuine, and it is also the limit of
what is verified here: the always-loadable half (outcome mapping, authority policy, supersession
rules, shipped JSON) is tested, while `compat/reputation/CrimeReputationCompat` is **compile-verified
only**. Its translation tables were read against MCA: Reputation 0.6.0's own
`ReputationService.outcomeFor`, `ReputationResult.Reason` and `ResolutionResult.Reason`, but no test
executes them.

The profile and credit-policy JSON is validated against a local copy of MCA: Reputation's documented
schema, not by calling its codecs — the same approach the incident definitions have always used, and
for the same reason: the files ship in this mod's jar, so an authoring mistake must fail this build.
It does mean a schema change on the companion's side would not be caught here.

## Outstanding runtime acceptance (not run in this session)

None of the following was executed; all of it needs a client or dedicated server with both mods
installed.

1. **Capability handshake.** `/crime debug integrations` reporting `delivery=true receipts=true
   supersede=true bound_resolution=true` and all five profile strings under `profiles=` against MCA:
   Reputation 0.6.0, and `profiles=none` against an older companion or one with profiles switched
   off.
2. **Profile publication.** `/mcareputation reload` reporting zero errors with this jar installed, and
   a player's standing screen showing recognition and a violence/lawfulness facet after a theft and a
   killing. A silently refused profile bundle is the specific failure mode to look for.
3. **Repeat credit.** Paying four fines inside one 14-day window and confirming recognition stops
   accruing (100 / 50 / 25 / 0) while the standing delta stays `+3` each time.
4. **Assault-to-killing parity.** Hitting a villager, letting the incident deliver, then killing them
   inside 1200 ticks, and confirming one incident with the killing's figure rather than two.
5. **Accepted-private.** An unwitnessed retained crime completing the operation with no public
   incident, no dead letter, and no local village penalty applied afterwards.
6. **Resolution before link.** Paying a fine in the same tick as the crime and confirming the
   settlement is delivered once the create lands, rather than dropped or dead-lettered.
7. **Authority scope.** With this mod installed, confirming MCA: Reputation still records villager
   rescues, cures, repelled raids and in-village player kills itself —
   `/mcareputation debug authorities` naming `mcacrime:crime_detector` for assault and killing only.
8. **No second producer.** `replayPendingOperations=false` handing assault and killing detection back
   to MCA: Reputation (`canDeliver` false) rather than leaving them recorded by nobody.
9. **NPC offenders.** An NPC thief's theft producing a case in `/crime history` and **no** standing
   record under `/mcareputation inspect` for that villager's UUID. Standing records written for NPC
   offenders by 0.7.2 or earlier are not retroactively removed by this release; nothing in this mod
   can delete another mod's records.

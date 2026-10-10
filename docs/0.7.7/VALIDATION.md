# MCA Crime 0.7.7 validation — 2026-10-03

This patch was tested with the automated suites, Forge dedicated-server harnesses, NeoForge
GameTests and real Minecraft clients, all against the packaged jars. Coverage is substantial but
bounded: this is not certification of every feature, mod combination or multiplayer race. Nothing
was committed or pushed; both checkouts hold the changes uncommitted for review.

## Branches, dependencies and packaging

| Variant | Branch | Minecraft | Build loader / Java | Production smoke loader |
| --- | --- | --- | --- | --- |
| Forge | `main` | 1.20.1 | Forge 47.4.10 / Java 17 | Forge 47.4.23 |
| NeoForge | `neoforge/1.21.1` | 1.21.1 | NeoForge 21.1.248 / Java 21 | NeoForge 21.1.250 |

Both branches advance from 0.7.6 to 0.7.7 in `gradle.properties` only. Runtime runs used
MCA 7.7.1-beta.2+1.20.1 with Architectury 9.2.14 and GeckoLib 4.8.3 on Forge, and
MCA 7.7.36-beta.3+1.21.1 on NeoForge. Companion runs added MCA: Reputation 0.6.1, MCA: Quests 1.7.1
and MCA: Conversations 1.8.0 for the respective loader. Neither jar bundles a dependency or a test
harness, and neither changes a world schema or network protocol.

## The five reports

1. **Established villagers recruited as thieves.** Only the settlement thief route asked whether a
   candidate was employed. The wild route (any villager with no MCA home village, which is a player's
   trading hall) and the fence roll asked nothing, and no route read trading history, so the
   occupation transaction cleared a master's offers. `job/RecruitmentEligibility` now gates every
   automatic source on a readable `none` profession, zero trading XP, level one and no used offer,
   before any random roll. Existing thieves, `/crime job` and the API's operator paths are unchanged.
   Trades already lost to an earlier conversion cannot be restored.
2. **Multiplayer dialogue.** A villager line reached its listener anywhere, as long as another player
   kept the villager loaded, and the refusal gate read fear and anger but never the apology flag. Lines
   now obey `dialogue.speechRadius` (same dimension, 16 blocks), checked before the pair cooldown is
   stamped. `memory/ConversationGate` honours an accepted apology, and only a grave memory stays wary,
   with the remaining days. Memories were already keyed by villager and offender; tests now pin that a
   second player is never refused for somebody else's crime.
3. **Murder hearts.** MCA's `Relationship.onTragedy` docks 20 hearts from every MCA villager within 32
   blocks and the victim's loaded family with no sight check. MCA: Crime then stacked its own family and
   witness loss on top, and NeoForge had no hook at all. Each mourner now passes through
   `relationship/NativeHeartPolicy` (`murderHeartPenalty`, `murderHeartsRequireWitness`, masks and the
   Thief exemption, which is pending on NeoForge and always reads "not exempt"). MCA: Crime's own loss skips any mourner MCA already charged, and NeoForge gains a
   third, optional, plugin-gated mixin config carrying the two heart hooks.
4. **Minor friendly fire.** Any damaging hit on a protected villager was an assault, however light; only
   the existing harm cooldown merged repeats. `detect/MinorHarmPolicy` forgives one light
   hit (at most 2.0 final damage) per attacker and victim per 6000 ticks, for at most three victims per
   attacker. It never forgives a lethal hit, a mugging, or a victim who already remembers the attacker.
   Self-defence, lawful force and `raidGrace` are decided first and are unchanged. MCA's hurt hearts
   for a candidate hit are held back until the end of the tick and applied unless the hit is forgiven.
5. **Cell-door startup crash.** Not reproduced, and no fix shipped. No crash report, log or mod list was
   supplied, so plan Package 5 steps 1–4 (collect, classify, bisect, fix what the evidence confirms) are
   blocked. Step 5 is done: `CellDoorBlockstateCoverageTest` on both trees, the NeoForge
   `CellDoorPlacementGameTests` (all 32 facing, hinge, open and barred combinations) and the client smoke
   role below. No door code changed.

## Corrections to the plan and findings

- The plan cites `OccupationTransaction` lines 192–199 for the offer clearing; on Forge it is lines 94–97.
- The plan says NeoForge fleet jars MCA 7.7.13 and 7.7.33 are not cached locally. Both have been in the
  Gradle cache since 2026-09-28 and 2026-09-10 respectively, and `McaJusticeTargetTest` verified all five
  NeoForge fleet builds. Each has the `hurt`, `die` and four-argument `onTragedy` sites and the
  `Mob entity` field.
- The plan's blocked item "the NeoForge hook applying at runtime" is resolved. The GameTest server log
  shows both marker mixins applied to MCA's `VillagerEntityMCA` and `Relationship`, and the murder-hearts
  and one-punch GameTests assert `NativeJusticeCapabilities.combatComplete()` before anything else.
- **Click routing (pre-existing, not changed).** `MemoryInteractionHandler` listens to `EntityInteract`
  only. Every NeoForge fleet build, and Forge MCA 7.6.20 and 7.6.26, open MCA's interaction screen from
  `interactAt`, which a real client sends first. With those builds a real right-click reaches MCA's
  screen past the refusal gate and the empty-hand apology gesture; the Crime menu still offers the
  apology. Forge MCA 7.7.x opens the screen from `mobInteract`, behind the gate. NeoForge was confirmed
  at runtime (the NOTE below) and Forge 7.6.x by bytecode only. Tracked in NeoForge `docs/PORT_PARITY.md`.
- A direct `WorldCriminalJobService.tryAssign(id, THIEF, true)` (or `assign`) call opens the thief
  transaction with the automatic WILD source, so it is now gated like the wild route. The plan's
  "operator and API calls keep today's behaviour" still holds: `/crime job` passes `false`, and nothing in
  the `api/` facade reaches this path.
- Fence rolls now require the same blank-slate villager as thieves, as the plan specifies.

## Automated results

| Check | Forge | NeoForge |
| --- | --- | --- |
| Clean build, unit suite, package/API checks | PASS; 2,707 executed, 19 skipped | PASS; 2,738 executed, 9 skipped |
| Unit failures / errors | 0 / 0 | 0 / 0 |
| Live server fixtures | Review 20/20; justice 13/13 before and after a restart with Reputation, 12/12 without | 66/66 GameTests |
| Static mod consistency | 0 errors, 0 warnings, 20 notes | Forge-only checker, not applicable |

Skips are the 0.7.6 set. On Forge: Townstead probes (8), BundleHoodFamily (3),
ProfessionRestorationRevision (1) and ItemCurrencyInventory (7). On NeoForge: Townstead probes (8) and a
missing legacy fixture (1). The notes are the pre-existing mask and key-ring model findings.

New suites, identical on both trees: `RecruitmentEligibilityTest` (6), `SpeechLocalityTest` (5),
`ConversationGateTest` (9), `NativeHeartPolicyTest` (10), `MinorHarmPolicyTest` (10) and
`CellDoorBlockstateCoverageTest` (3). Extended suites:

- `CriminalJobAssignerTest` (now 18) and `VictimCrimeMemoryTest` (12);
- `CombatIncidentTest` (31) and `ConfigValidatorTest` (39);
- `LangCoverageTest`, which now enumerates `NpcMuggerEligibilityReason`;
- `McaJusticeTargetTest`, which is new on NeoForge and replays every `mca_probe_versions` jar on both;
- NeoForge `MixinConfigTest`.

The six new GameTests are:

- `anEstablishedLibrarianSurvivesAForcedSweep`;
- `aSecondPlayerIsNeverRefusedForAnotherPlayersCrime`;
- `aListenerFortyBlocksAwayReceivesNothing`;
- `anUnseenKillingLeavesAWalledNeighboursHearts`;
- `oneFistHitLeavesNoChargeAndASecondIsChargedOnce`;
- `everyFacingHingeOpenAndBarredDoorPlacesAndBehaves`.

Commands, run from the respective checkout:

```sh
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh "$PWD" clean build reviewRuntimeJar justiceRuntimeJar townsteadRuntimeJar maskRuntimeJar justiceClientRuntimeJar gameplayRuntimeJar -I tools/review/runtime.gradle -I tools/justice/runtime.gradle -I tools/townstead/runtime.gradle -I tools/masks/runtime.gradle -I tools/justice-client/runtime.gradle -I tools/gameplay/runtime.gradle
```

```sh
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh "$PWD" clean build townsteadRuntimeJar gameplayRuntimeJar runGameTestServer -I tools/townstead/runtime.gradle -I tools/gameplay/runtime.gradle -PrequireReputation=true -PrequireQuests=true -PrequireLocks=true
```

```sh
python3 tools/review/run_runtime.py --forge-server <forge-47.4.23 server> --crime build/libs/mcacrime-0.7.7.jar --harness build/libs/crime-review-runtime-checks-0.7.7.jar --mca <mca> --geckolib <geckolib> --architectury <architectury> --timeout 300
python3 tools/justice/run_runtime.py --forge-server <forge-47.4.23 server> --mca <mca> --geckolib <geckolib> --architectury <architectury> [--reputation <mcareputation-0.6.1.jar>] [--restart]
```

Logs and evidence:

- Forge build: `/tmp/gradle-MCACrime-clean-20261003-142552.log`
- NeoForge build and GameTests: `/tmp/gradle-MCACrime_1.21.1-clean-20261003-142707.log`
  ("All 66 required tests passed")
- Forge review harness: `build/review-runtime/check-sbim4_jz/runtime-results.txt`
- Forge justice harness, with Reputation and a restart: `build/justice-runtime/check-tcl3b358/`
  (`results-0.txt`, `results-1.txt`); without companions: `build/justice-runtime/check-zxs86c9d/`
- Static findings: `/tmp/mcacrime-impl-scratch/check-mod-0.7.7.txt`

The review harness adds "forced recruitment sweep leaves an established librarian intact". The justice
harness adds "walled-off neighbour keeps hearts, sighted witness loses MCA's twenty once, penalty off
costs nobody", and asserts through Reputation's per-incident exemption query that a 1.0 hit is exempt
and a 3.0 assault is not.

## Runtime scenarios on the packaged jars

Clients ran under Xvfb with software OpenGL, sending real attack and interaction packets. Servers were
disposable loopback-only offline servers; existing worlds were untouched. The two-client runs connect
an operator offender and a plain bystander at the same time, then stop the server, restart it, and
reconnect the offender alone. Reaction timers are shortened and debug logging is on, so the server log
records each ordinary villager line it refused to deliver as out of earshot (refusal replies are skipped
without a log line).

| Plan scenario | Forge, companions present / absent | NeoForge, companions present / absent |
| --- | --- | --- |
| 1. Offender hits, leaves 200 blocks, then the Nether; bystander keeps the village loaded; no line | PASS / PASS; 4 / 4 refused lines logged | PASS / PASS; 3 / 4 refused lines logged |
| 2. Bystander clicks the same villager and gets MCA's screen | PASS / PASS | PASS / PASS |
| 3. Apology after the settling minute; next click not refused; grave crime stays wary with a time | PASS / PASS, and a real right-click on the wary villager was refused | PASS / PASS; NOTE: the real right-click on the wary villager opened MCA's screen (click routing, above) |
| 4. One hit forgiven with a warning, no Karma; a second charged exactly once; a third villager under the cap; the bystander's own grace | PASS / PASS | PASS / PASS |
| 5. Unseen killing; penalty off | Not run with clients; justice harness PASS with and without Reputation | Not run with clients; GameTest PASS |
| 6. Restart: Karma, doors, apology, grave memory, thief record persist; grace ledger resets | 7/7 PASS / 7/7 PASS | 7/7 PASS / 7/7 PASS |
| 7. Crime alone: startup, `/reload`, creative inventory (45 items), all 32 door combinations | 3/3 PASS | 3/3 PASS |
| Single-player core flow (fines, deaths, jail, assault, masks) | 11/11 PASS | 11/11 PASS |

Totals per two-client run: Forge 20 PASS with and without companions; NeoForge 19 PASS and 1 NOTE with
and without companions. Each count includes the runner's own line, and every run exited 0. The refused
lines are reaction lines (`mug_recovery`, `mug_witnessed`) logged as "not delivered: out of earshot" while
the offender was away.

Evidence root: `/tmp/mcacrime-077-qa/`.

- Two-client, final harness: `forge-duo-companions-5/`, `forge-duo-absent-2/`, `neo-duo-companions-3/`,
  `neo-duo-absent/`
- Crime alone: `forge-smoke/`, `neo-smoke/`; single-player: `forge-sp-3/`, `neo-sp/`

Each client directory has `PASS.txt`, `client.log`, `artifacts.json` and `launch-command.json`.
Two-client run directories also hold `server/console-{0,1}.log`, `server/artifacts.json` and
`suppressed-lines.txt`. Every manifest records the production jar hashes listed at the end. The
single-player and smoke runs used an earlier harness build. Its single-player flow and smoke role
disassemble identically to the final harness's, apart from the added NOTE channel; only the two-client
roles changed.

## Harness corrections before the accepted runs

- `forge-sp/`, `forge-sp-2/`: "second real client attack did not damage the villager". The health check
  raced MCA's natural regeneration. The second punch is now checked through synchronized Karma and Heat.
- `forge-duo-companions/`: "villager 'Crime QA target' is missing". MCA replaces custom names, so
  villagers are now found by position.
- `forge-duo-companions-2/`: "a first light punch on a third villager was not forgiven". That villager had
  witnessed the charged punches and remembered the attacker, which correctly rules out grace. The
  villagers now stand forty blocks apart.
- `forge-duo-companions-3/`: passed, but logged no refused lines. NBT-summoned villagers skipped MCA's age
  initialization, so no reaction started. They are now summoned plainly, then given NBT.
- `neo-duo-companions/`, `-2/`: "the bystander's click did not open MCA's screen". The harness sent only
  the plain interaction, and MCA 1.21.1 opens the screen from `interactAt`. A click now emulates a real
  client (`interactAt`, then `interact` if unconsumed), and the gesture sends the plain interaction alone.
  The bystander clicks before punching, because MCA's own panic makes a freshly hit villager shake its
  head at everybody.
- `forge-duo-companions-4/` and `forge-duo-absent/` passed on the previous harness and are superseded.

## Log findings

- Client logs report the unavailable narrator `libflite`, and offline fixtures cannot authorize to
  Realms. One NeoForge bystander client logged a Mojang session-server read timeout.
- Each NeoForge server logged one "Can't keep up" warning (2.3 s and 2.7 s behind). Both came 2–3 seconds
  after the scripted first teleport into the Nether, while fresh Nether chunks generated with two
  software-rendered clients on a host with heavily used swap. The Forge servers logged none.
- No server or client log from the accepted runs contains an MCA: Crime stack frame.

## Not verified or blocked

- **Cell-door crash:** blocked until the reporter supplies the crash report, `latest.log`, `debug.log`,
  the exact jar names and the mod list. Scenario 7 proves only that Crime alone is healthy.
- Scenario 5 did not run with live clients. It is covered by the Forge justice harness on a production
  server with real MCA and the transformed classes, and by the NeoForge GameTest.
- "Standing still follows its own controls" with the penalty off has no runtime assertion. Standing
  (`applyVillagePenalty`, and MCA: Reputation) is outside every changed path.
- The clients assert the `mcacrime.apologize.still_wary` key, the variant that carries a day count, but
  not the number itself. The count is unit tested in `ConversationGateTest` and `VictimCrimeMemoryTest`.
- On Forge, "no memory" for a forgiven hit is shown by unit tests: a forgiven completion never commits,
  and victim memory is written only from a committed incident. The NeoForge GameTest asserts it
  directly. The Forge client run asserts the warning and unchanged Karma.
- The Crime-menu apology route (keybind, MCA's Crime button) is confirmed in code only; the clients used
  the gesture.
- Forge MCA 7.6.x click routing is established by bytecode, not at runtime.
- MCA's own guard warnings after a hit are native MCA behaviour outside Crime.
- NeoForge has no MCA: Reputation per-incident exemption bridge, so only Forge answers Reputation's own
  assault detector for a forgiven hit.
- These were short scripted sessions, not a soak, performance profile or packet-fuzzing pass. Two
  concurrent players were scripted, not freely played.

## Final artifacts

Each is in `build/libs/` of its own checkout.

- Forge `mcacrime-0.7.7.jar`: `e09275c0951ea79745c3f26aafbcef18e3156320e2cffc61f817545ac4d6e418`
- Forge `mcacrime-0.7.7-api.jar`: `05d8a1d9d03684d767e7c2749de7aea440f524a1ef0b13d8377070c5aba6772a`
- NeoForge `mcacrime-0.7.7.jar`: `b904b1e9a037755b88eb4e1e6020b85483c73df3e85f53ff83ec409aba7299ae`
- NeoForge `mcacrime-0.7.7-api.jar`: `9371b5bd19fbb2b1189195709353709a5c591346ccd7d5c01865f3cabe82116b`

The gameplay harness jars (`crime-gameplay-checks-0.7.7.jar`: Forge `89ad83ee…`, NeoForge `03187798…`)
are test-only and never a distributable dependency.

## Corrections pass — 2026-10-04

Scope: every item in `docs/MCACrime_Corrections.md`, on both trees. Nothing was committed, pushed or
published. The jars below supersede the artifacts listed above; the 2026-10-03 "cell-door crash" and
"no Reputation exemption bridge on NeoForge" entries under *Not verified or blocked* are resolved here.

### Root causes

| Report | Root cause | Evidence |
| --- | --- | --- |
| Startup fails with Dramatic Doors | `CellDoorBlock` and `ReinforcedBarsBlock` restated their vanilla parents' properties, so Dramatic Doors' `waterlogged`, added to every `DoorBlock` by mixin, was missing and its constructor hook threw during registration; every MCA: Crime registry object was then absent | Confirmed from the reporter's NeoForge log; reproduced on a Forge 47.4.23 client with the shipped 0.7.5 jar and Dramatic Doors 3.3.3 (`IllegalArgumentException ... waterlogged` at `DoorBlock.handler$zdh000$enhanceConstructor` from `CellDoorBlock.<init>`, then `Registry Object not present: mcacrime:cell_door`) |
| A second crash hides the loading error (NeoForge) | The MCA screen button read the CLIENT config on NeoForge's loading error screen, where it is never loaded | Confirmed from the reporter's NeoForge log; the gate is covered by `McaScreenGateTest`, not by a staged error-screen run |
| Stolen goods cannot be searched for or taken back | Nothing exposed the stolen-goods ledger to the search; only arrest and death moved stolen property | Code; the new path verified at runtime on both loaders |
| Fighting off a mugger is charged | The exemption depended on the live session, which the defensive blow (or weapon, sand, death) ended before the blow was classified; `NORMAL_LAW` had no threat basis at all; MCA's own guard alert and bounty fired regardless; the NeoForge port had no Thief combat policy | Code trace; the original symptom was not replayed on an old jar; the fixed behaviour is verified at runtime on both loaders |
| Nitwits mugging | Not reproduced: no path on the current code lets a villager with the nitwit profession act. Gaps closed: age was not asked of an acting Thief, and an external profession change ran the full retirement, which rewrote the new profession's state. 0.7.7's recruitment rule already excludes every profession but `none` | Code trace; `/crime debug thief` added for a live case |
| Guards no longer fight | Ending a reaction released the villager by clearing whatever attack target it had, including a fight MCA had started itself | Code trace; the original symptom was not replayed on an old jar; real MCA guards keep and win a fight with a husk and hurt a Wanted player who refused arrest, on both loaders |
| Re-arrest loops and jail softlocks | Release settled no Heat, so a freed prisoner was still Wanted; the captivity cap resolved no case; release cleanup ran inside an event listener that one throwing listener could stop; a holding cell let its prisoner out only through the door side | Code trace; the fixed behaviour verified at runtime on both loaders |
| Found during validation: a player thief's death duplicated property | Confirmed death escrowed every ledger record naming the dead entity, including a player who still carried the item | Code trace; fix verified at runtime on both loaders |
| Found during validation (Forge, never released): the search's provenance lore accumulated on the ledger record | 1.20.1 `ItemStack.of` keeps the source's `tag` compound, and `StolenGoodsRecord.stack()` decoded from its own field; `PropertyLot` and `AppliedRestraint` had the same pattern | Found by the review harness (eight lore lines after a few reads); fixed by decoding from a copy |
| Found during validation: an existing NeoForge GameTest polluted its neighbour | The taut-tether test moves its subject eight blocks out and detaches there, dropping a chain inside the next test's bounds; adding the new GameTests changed the test layout so that `aHoldersDeathReleasesEverybodyTheyWereLeading` became that neighbour | A/B run without the new class (66/66 pass) and per-item positions; the test now puts its subject back first |

### Automated and runtime results

| Check | Forge | NeoForge |
| --- | --- | --- |
| Build, unit suite, package and API checks | PASS; 2,752 tests, 19 skipped, 0 failures, 0 errors | PASS; 2,776 tests, 9 skipped, 0 failures, 0 errors |
| Server fixtures | Review harness 35/35 on a production Forge 47.4.23 server (20 existing, 15 new); justice harness 12/12 without and 13/13 with MCA: Reputation 0.6.1, each across a save and restart | 78/78 GameTests (66 existing, 12 new `CorrectionsGameTests`) |
| Static mod consistency | 0 errors, 0 warnings, 20 notes (the existing mask-model notes) | Forge-only checker, not applicable |
| Real clients, single-player smoke | Without Dramatic Doors: PASS. With Dramatic Doors 3.3.3: PASS, cell door properties include `waterlogged`. Shipped 0.7.5 jar with Dramatic Doors 3.3.3: fails as reported | Without Dramatic Doors: PASS. With Dramatic Doors 3.3.2: PASS, `waterlogged` present. With MCA: Reputation 0.6.1: PASS, exemption bridge "capability 1 registered" |

Skips are the 0.7.6 set. Unit additions on both trees: `McaScreenGateTest`, `SentenceDispositionTest`,
`StolenGoodsTransferTest`, `JailStateSentenceHeatTest`, `MuggingThreatsTest` and `ThreatEncounterTest`;
`NpcMuggerEligibilityTest`, `CellDoorBlockstateCoverageTest` and `McaJusticeTargetTest` were extended,
and `ThiefCombatPolicyTest` now runs on NeoForge.

Commands, run from the respective checkout:

```sh
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh "$PWD" build reviewRuntimeJar -I tools/review/runtime.gradle
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh "$PWD" justiceRuntimeJar -I tools/justice/runtime.gradle
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh "$PWD" gameplayRuntimeJar -I tools/gameplay/runtime.gradle
python3 /home/otectus/Projects/.mcmod-tools/check_mod.py "$PWD"
python3 tools/review/run_runtime.py --forge-server build/review-runtime/forge-47.4.23 --crime build/libs/mcacrime-0.7.7.jar --harness build/libs/crime-review-runtime-checks-0.7.7.jar --mca <mca> --geckolib <geckolib> --architectury <architectury> --timeout 300
python3 tools/justice/run_runtime.py --forge-server build/review-runtime/forge-47.4.23 --mca <mca> --geckolib <geckolib> --architectury <architectury> [--reputation <mcareputation-0.6.1.jar>] --restart
python3 tools/gameplay/run_client.py --work <new dir> --install <launcher install> --crime build/libs/mcacrime-0.7.7.jar --harness build/libs/crime-gameplay-checks-0.7.7.jar --mca <mca> [--architectury <a> --geckolib <g>] --xvfb <Xvfb> --loader <forge-47.4.23 | neoforge-21.1.250> --minecraft <1.20.1 | 1.21.1> --role smoke [--extra-mod <DramaticDoors jar>]
```

```sh
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh "$PWD" build runGameTestServer -PrequireReputation=true -PrequireQuests=true -PrequireLocks=true
```

The Forge 47.4.23 server was installed from Forge's installer into `build/review-runtime/forge-47.4.23`,
and the client runs used an Xvfb binary extracted from the distribution's `xorg-server-xvfb` package into
`build/review-runtime/xvfb`; neither is installed system-wide.

Logs and evidence:

- Forge build and unit suite: `/tmp/gradle-MCACrime-build-20261004-085144.log`
- NeoForge build, unit suite and GameTests: `/tmp/gradle-MCACrime_1.21.1-build-20261004-085146.log`
  ("All 78 required tests passed"); the clean build before the last change:
  `/tmp/gradle-MCACrime_1.21.1-clean-20261004-084358.log`
- Forge review harness: `build/review-runtime/check-3fjrugs8/runtime-results.txt`
- Forge justice harness on the final jar: `build/justice-runtime/check-5w1m01h2/` (no companions) and
  `build/justice-runtime/check-7upxtnik/` (MCA: Reputation 0.6.1), each with `results-0.txt` and
  `results-1.txt` for the two sides of the restart
- Static findings: `/tmp/mcacrime-corr/check-mod-0.7.7-corrections.txt`
- Client runs (each directory holds `client.log`, `PASS.txt`, `artifacts.json` with the sha256 of every
  jar used, and screenshots), under `build/review-runtime/` of the respective checkout. On the final jars:
  `client-forge-dd-final-085936` and `client-neo-dd-final-085936`. On the builds just before the
  death-escrow change, which is the only difference from the final jars: `client-forge-nodd-084628` and
  `client-forge-dd-084628` (`e47e9a89…`), `client-neo-nodd-084628`, `client-neo-dd-084628` and
  `client-neo-rep-084907` (`a2df8bd4…`). The reproduction with the shipped 0.7.5 jar (`eb4e3a34…`):
  `client-forge-dd-075-084628`, with its crash report under `crash-reports/`.
- A/B GameTest run without `CorrectionsGameTests`: `/tmp/gradle-MCACrime_1.21.1-runGameTestServer-20261004-082620.log`

### Not verified or limited

- The original symptoms of the mugging, nitwit, guard and jail reports were not replayed on an old jar;
  each root cause comes from the code, and the fixed behaviour is what the runtime checks assert.
- The loading-error-screen gate is unit tested; no run staged NeoForge's error screen with the fixed jar.
- Guard combat was checked with one guard against one husk and one Wanted player per test, in short
  runs. MCA's own pardon timer and hurt bounty can still drop a target for a moment; the enforcement scan
  re-applies it every `guardScanIntervalTicks`.
- On NeoForge the Reputation exemption bridge is verified to register at runtime; the end-to-end answer
  to Reputation's assault and killing detectors is exercised only by the Forge justice harness.
- A player who re-steals from a thief carries the item; when that player dies it drops by the game's
  own rules and is not returned to its owner automatically. Players' own inventories are searched
  directly, not through the stolen-goods slots.
- Heat a player kept from a release before the upgrade is not settled retroactively; the next sentence,
  or `/crime clearheat`, settles it.
- The client runs are single-player integrated-server smoke sessions. The two-client and restart
  gameplay scenarios from 2026-10-03 were not rerun for this pass.
- Dramatic Doors was tested at 3.3.3 on Forge and 3.3.2 on NeoForge.

### Final artifacts (2026-10-04)

Each is in `build/libs/` of its own checkout.

- Forge `mcacrime-0.7.7.jar`: `12e7d3d0c80dc5c4e1d898da5889797863f13ffbfc986be7742de943c31504ac`
- Forge `mcacrime-0.7.7-api.jar`: `e7b7085a3acc94e49387817acf62d805f9034002918d6c17a93b4e7b89b45c18`
- NeoForge `mcacrime-0.7.7.jar`: `b5cc39e787bd1fb1b13bbf5f5b5cde3993b3f354a2cb3063f605a2ca7780d440`
- NeoForge `mcacrime-0.7.7-api.jar`: `7498e326784bde4e0abb95c625d295c52606610ef069dc9929d3e1099abeae1b`

## Release — 2026-10-10

The released tree adds the 2026-10-09 changes recorded in `CHANGELOG.md` to the corrections pass above: a
jailbreak from a `PHYSICAL` jail, a paid ransom taking off the kidnapping's restraints, and
`enforcement.outlawCombatLegal`. It also carries the gameplay-harness additions of the 2026-10-04 QA run
under `tools/gameplay/`. The jars below supersede the 2026-10-04 artifacts.

### Automated and runtime results

- Forge `clean build`: 2,803 tests, 0 failures, 19 skipped; `verifySiblingApis`, `verifyApiJar` (201
  classes) and `checkJarContents` pass. Log: `/tmp/gradle-forge-077-clean-20261010-175212.log`.
- NeoForge `clean build runGameTestServer`, compiled against the MCA: Reputation, MCA: Quests and Locks
  Reforged ports with `-PrequireLocks=true`: 2,827 tests, 0 failures, 9 skipped; `checkJarContents` and
  `verifyApiJar` (187 classes) pass; all 84 required GameTests pass, the six in
  `OutlawRansomJailbreakGameTests` among them. Log: `/tmp/gradle-neoforge-077-clean-20261010-175255.log`.
- The Forge harnesses ran on 2026-10-09 against a jar of the same sources, on Forge 47.4.23 with MCA
  7.7.1-beta.2: justice 20/20 without companions (`build/justice-runtime/check-w14x8qy5/`) and 22/22 on
  both sides of a restart with MCA: Reputation 0.6.1 (`build/justice-runtime/check-onkcm1hl/`); review
  35/35 (`build/review-runtime/check-gx_4zonm/`). Every entry of the released Forge jar is byte-identical
  to that jar's except `mcacrime.refmap.json`, which holds the same mappings in a different key order.

### Not verified or limited

- The graphical client and two-client scenarios were not rerun on the released jars. On NeoForge the
  runtime evidence for the 2026-10-09 changes is the GameTest server.
- An extensive graphical QA run on 2026-10-04, kept outside this repository, used jars built before the
  2026-10-09 changes. It reproduced two defects that predate 0.7.7 and that this release does not fix:
  - On both loaders, a restrained player's own client misses each application and removal of their
    restraints until they reconnect. `CrimeNetwork` sends those deltas only to the players tracking the
    subject, and that set never includes the subject.
  - On NeoForge a newly placed padlock refuses its first blank key. The port has no first-key window;
    Forge keeps one in `LockRecord.keyInitialized`.

### Final artifacts (2026-10-10)

Each was produced by the clean build above, in a worktree holding the release commit's sources; no
documentation file is packaged. Release attachment filenames include the loader and Minecraft version;
renaming does not alter bytes.

- Forge `mcacrime-0.7.7.jar`: `acf91a0f878193d348a9bf0530bba975b4c122cf0c97354bf32f3808bd4f51c2`
- Forge `mcacrime-0.7.7-api.jar`: `0755e6325f100b8dfb90085b4335c6ce3afcac9270bf2626261ac29abb44c295`
- NeoForge `mcacrime-0.7.7.jar`: `3e03cefbd3b54ccb4218dcdce865095feb6d83ccebbf70636f8d9b8449d81991`
- NeoForge `mcacrime-0.7.7-api.jar`: `59ab6c56f734c8d3e0646f4ccf3981e3de41d9f96545da2bc827e6cb152d60e1`

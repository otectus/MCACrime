# 0.7.8 validation — NeoForge 1.21.1 port

Recorded 2026-10-10 for the 0.7.8 release. Every command was run through
`/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh`. The release build ran in a clean worktree holding the
release commit's sources, compiled against the MCA: Reputation, MCA: Quests and Locks Reforged ports with
`-PrequireLocks=true`; the Capitals probe ran earlier the same day from the repository root, before
`mod_version` moved to 0.7.8.

## Build and unit suite

| Check | Result | Log |
|---|---|---|
| `clean build runGameTestServer` (release) | PASS: 2906 tests, 0 failures, 12 skipped; `checkJarContents` 1932 entries, nothing shaded, no Forge or relocated-MCA references, all classes Java 21; `verifyApiJar` 194 classes; all 91 required GameTests, including `BlueprintPrisonGameTests` (MCA 7.7.36-beta.3) and `worldDataRoundTrip` at schema 17 | `/tmp/gradle-neoforge-078-clean-20261010-180443.log` |
| `capitalsProbeTest -PcapitalsJar=MCAConversations/build/mcacapitals-1.3.5-neoforge.jar` | PASS: every capability bound (`[REGISTRY, AUTHORITY, TITLES, COURT, CHRONICLE, PLAYER_WARRANTS]`), a real `CapitalRecord` read back through the handles, absence clean; the "without MCA" case skipped, because this runner has MCA on its classpath | `/tmp/gradle-MCACrime_1.21.1-capitalsProbeTest-20261010-115746.log` |

The decree suites shipped from Forge run here unchanged in substance: `DecreeRulesTest` (28),
`DecreeRecordTest` (8), `DecreeEnforcementTest` (8), `DecreeIsolationTest` (2), `CapitalsBridgeTest` (5),
`CapitalsConfigValidationTest` (5), `NoCapitalsStaticLinkTest` (4), `PrisonTagJsonTest` (5),
`DecreePacketTest` (4, on the payload codecs) and `CrimeDataMigrationsV15toV17Test` (4).
`McaPrisonDefinitionProbeTest` read the prison definition from all five `mca_probe_versions` builds.

## Blueprint prisons on MCA 7.7.36-beta.3

`BlueprintPrisonGameTests` drives MCA's add-building scan (`analyzeBuildingAddition`, then
`commitRoomAddition` answering "prison" when asked) and logs what MCA counted:

| Layout | Iron | Crime |
|---|---|---|
| Bars in the walls | house, counted `red_bed=1` | house, counted `red_bed=1` |
| Bars hung inside | house, counted `iron_bars=8, red_bed=1` | house, counted `reinforced_bars=7, reinforced_bars_gap=1, red_bed=1` |
| Bars and a door inside | **prison**, offered `[prison, house]`, counted `iron_bars=8, iron_door=2, red_bed=1` | **prison**, offered `[prison, house]`, counted `cell_door=2, reinforced_bars=7, reinforced_bars_gap=1, red_bed=1` |

`crimeBarsAndCellDoorAreInMcasPrisonTags` checks the merged tags in the running game.

## Not verified on this port

- No production server was run with MCA Capitals installed: the decree paths are covered by the unit
  suites and, for the shared logic, by the Forge build's production-server harness (31 checks per
  Capitals build). Issuing, challenging, paying and serving a decree in a NeoForge world remain manual
  acceptance.
- The decree screen (`RoyalDecreeScreen`) was compiled and its payloads round-tripped, but no client was
  launched.
- MCA builds before 7.7.22 on 1.21.1 have no `analyzeBuildingAddition`; the GameTests ran only on the dev
  runtime, 7.7.36-beta.3.
- The two pre-existing defects recorded in `docs/0.7.7/VALIDATION.md` (its Release section) are not
  addressed by 0.7.8: a restrained player's own client misses restraint updates until they reconnect, and
  a newly placed padlock refuses its first blank key.

## Final artifacts

Built by the release build above. The release attachment is named `mcacrime-0.7.8-neoforge-1.21.1.jar`;
renaming does not alter bytes.

- `mcacrime-0.7.8.jar`: `3abfa1832c89f7fbc9d79a0d2f9b124fe43a4e0ff7e68649ae369c0bc17254be`
- `mcacrime-0.7.8-api.jar`: `20d7234df95f5eb54c418461cdd4a970b458a3d87543b549421b99ca7775eb88`

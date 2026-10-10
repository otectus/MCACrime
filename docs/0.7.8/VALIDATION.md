# 0.7.8 validation — Forge 1.20.1

Recorded 2026-10-10 for the 0.7.8 release. Gradle was run through
`/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <dir> <task>`; the release build ran in a clean
worktree holding the release commit's sources, everything else from the repository root.

## Build and unit suite

| Check | Result | Log |
|---|---|---|
| `clean build capitalsRuntimeJar -I tools/capitals/runtime.gradle` (release) | PASS: 2881 tests, 0 failures, 22 skipped; `verifySiblingApis` every pinned jar matches; `verifyApiJar` 209 classes; `checkJarContents` clean | `/tmp/gradle-forge-078-clean-20261010-180404.log` |
| `capitalsProbeTest -PcapitalsJar=MCAQuests/build/rt/forge-capitals/mods/mcacapitals-1.3.7.jar` | PASS, 4 of 4 | `/tmp/gradle-MCACrime-capitalsProbeTest-20261010-121058.log` |
| `capitalsProbeTest -PcapitalsJar=MCAQuests/build/capitals-source-audit/mcacapitals-1.3.6.jar` | PASS, 4 of 4 | `/tmp/gradle-MCACrime-capitalsProbeTest-20261010-121109.log` |
| `capitalsProbeTest -PcapitalsJar=MCAQuests/build/capitals-source-audit/mcacapitals-1.3.5.jar` | PASS, 4 of 4 | `/tmp/gradle-MCACrime-capitalsProbeTest-20261010-121120.log` |
| `python3 .mcmod-tools/check_mod.py "$PWD"` | 0 errors, 0 warnings, 20 notes (the mask-style item models the variant system reaches through overrides; none from 0.7.8) | console |
| `python3 .mcmod-tools/modmap.py "$PWD"` | regenerated, 75 registered entries; the hand-maintained part below `AUTO:END` byte-identical apart from the 0.7.8 notes added there | console |

`McaPrisonDefinitionProbeTest` ran inside `test` against all six `mca_probe_versions` jars, 0 skipped. The
probe, `check_mod.py` and `modmap.py` rows ran earlier the same day, before `mod_version` moved to 0.7.8.

## Production-server harness (`tools/capitals`)

The harness jar came from the release build above, and every row ran on the release jar itself:
`python3 -I tools/capitals/run_runtime.py --forge-server MCAKingdoms/build/forge-server --crime
mcacrime-0.7.8-forge-1.20.1.jar --harness crime-capitals-runtime-checks-0.7.8.jar --mca <MCA>
[--capitals <Capitals>] --architectury architectury-9.2.14-forge.jar --geckolib geckolib-forge-1.20.1-4.8.3.jar
--restart` on Forge 47.4.23 (dedicated server, flat world, Java 17). Each run starts the same world twice;
`runtime-results-phase0.txt` and `-phase1.txt` and both consoles are kept in the artifact directory.

| MCA | Capitals | First start | After restart | Artifacts (`build/capitals-runtime/`) |
|---|---|---|---|---|
| 7.7.1-beta.2 | 1.3.7 | 28 PASS | 3 PASS | `check-51ux5x7t` |
| 7.7.1-beta.2 | 1.3.6 | 28 PASS | 3 PASS | `check-go98dquq` |
| 7.7.1-beta.2 | 1.3.5 | 28 PASS | 3 PASS | `check-z470yrdn` |
| 7.7.1-beta.2 | none | 9 PASS | 1 PASS | `check-_q4iy6lv` |
| 7.6.20 | none | 9 PASS | 1 PASS | `check-sjko1bp4` |

No FAIL line, no exception and no warning from MCA: Crime itself in any console. On each first start
Forge's config check corrects `integrations.currencyId` from null to its default (`mcacrime:emerald`) in the
freshly generated `mcacrime-common.toml`; 0.7.7's harness consoles log the same line. The mod jar is the
release jar (see Final artifacts) and the harness `7862c046…829de26`.

**First start.** Blueprint: the merged `mca:iron_bars`/`mca:iron_doors` tags carry the Crime and vanilla
blocks; MCA's own scan, driven as the Blueprint's add-room does (on 7.7.1 the room fits prison and house
and the harness answers prison), records a prison for reinforced bars with a gapped bar and a cell door,
for vanilla iron bars and an iron door, for five iron and three reinforced bars, and behind an open cell
door; seven bars are neither offered nor recorded as a prison; a prison survives `/reload` and a fresh
scan. With Capitals: the bridge binds every capability; a capital founded on the cell's village reads back
with its player throne; Capitals' own prison bounds cover a cell of Crime blocks; the decree screen's
server session is hosted by a guard for the ruler only, issues and withdraws, and refuses a replayed nonce
and a target it never offered; the ruler issues a decree and the target is told and listed, with Heat,
Karma, Wanted, warrants and cases untouched; a repeat stacks nothing; a non-ruler, a knight and a lord of
the capital are refused, a duke of another capital is not; the village guard challenges and takes the
32-emerald fine (40 → 8); a guard of no village sees no decree; after a succession the former ruler cannot
withdraw and the new one can; ennoblement voids; Capitals' own warrant is reported, never enforced; the
chronicle line is written only when switched on; a decree-only arrest carries at least `sentenceTicks`
and serving it answers the decree; an operator's release hands it back unanswered; the decree table
round-trips. Without Capitals the bridge is `ABSENT` and an issue is refused `NOT_VERIFIABLE` with nothing
stored or said.

**After the restart.** The prison is still a prison; with Capitals, the open decree and its capital
survive, the decree stands again for its offline target, and removing the capital voids it
(`capital_gone`).

## In-game acceptance (plan section 6) and what remains manual

| Plan item | Covered by |
|---|---|
| 1, 2 issue, notice, challenge, fine, custody floor, served | harness (issue, challenge and fine, decree-only arrest, served) |
| 4, 5 rank boundaries and a foreign title | harness (knight, lord, foreign duke); `DecreeRulesTest` (Hand, consort, unknown rank, unavailable) |
| 6, 7 succession, duplicate | harness |
| 8 a decree beside an ordinary case | `DecreeEnforcementTest` |
| 9 forged or replayed answers | harness (server session), `DecreePacketTest` |
| 11 restart and an offline target | harness, after the restart |
| 12 integration off or Capitals removed | harness without Capitals; `DecreeRulesTest`, `CapitalsConfigValidationTest` |
| 13 Heat, Karma, Wanted, bounty unchanged | harness; `DecreeIsolationTest` |
| 14–18 Blueprint prisons, mixed bars, open door, reload and restart | harness |
| 20 Capitals' prison | harness (`hasPrison` and the prison bounds) |

Still manual, because they need a connected client or a scenario the harness does not stage: the decree
screen itself (`RoyalDecreeScreen`; its payloads round-trip in `DecreePacketTest` and its server session
runs in the harness); a refusal escalating to resisting arrest and a voluntary surrender on a decree;
leaving and re-entering the village, and expiry while offline (covered as rules by `DecreeRulesTest`); a
stale screen after a capital is dissolved; a locked or padlocked cell door, a missing bed, a missing door
and a `"replace": true` datapack in the Blueprint; Capitals' own surrender teleport into a Crime-built
prison; the holding cell never being reported as a building.

The two pre-existing defects recorded in `docs/0.7.7/VALIDATION.md` (its Release section) are not
addressed by 0.7.8; on Forge that is a restrained player's own client missing restraint updates until they
reconnect.

## Final artifacts

Built by the release build above. The release attachment is named `mcacrime-0.7.8-forge-1.20.1.jar`;
renaming does not alter bytes.

- `mcacrime-0.7.8.jar`: `bb34b4b788547f23192f89a09845019f02d2baeb12c77f48eab9a4d3af70f1db`
- `mcacrime-0.7.8-api.jar`: `eabcbf5f8b99bf624ca063b2711d0d171a0b42395cba48bd242086747a0520f2`
- `crime-capitals-runtime-checks-0.7.8.jar`, the test harness (never a distributable): `7862c0460dab0265b5609d983e9e8db430f821198e576f8bc89494c1d829de26`

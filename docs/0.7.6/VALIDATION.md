# MCA Crime 0.7.6 validation — 2026-10-03

This patch was tested with real Minecraft clients, integrated servers, dedicated servers,
production jars, and the existing automated suites. Coverage is substantial but bounded:
this is not certification of every feature, mod combination, or multiplayer race.

## Branches, dependencies and packaging

| Variant | Branch | Minecraft | Build loader / Java | Production smoke loader |
| --- | --- | --- | --- | --- |
| Forge | `main` | 1.20.1 | Forge 47.4.10 / Java 17 | Forge 47.4.23 |
| NeoForge | `neoforge/1.21.1` | 1.21.1 | NeoForge 21.1.248 / Java 21 | NeoForge 21.1.250 |

Both branches advance exactly from 0.7.5 to 0.7.6. Version metadata is expanded from
`gradle.properties`; both production jars were inspected for version 0.7.6.

Forge uses MCA 7.7.1-beta.2+1.20.1 and GeckoLib 4.8.3. The server runners also supply
Architectury 9.2.14 for older MCA compatibility. Its sibling APIs are vendored and
hash-pinned. NeoForge uses MCA 7.7.36-beta.3+1.21.1 and sibling class outputs;
the release build requires Reputation, Quests and Locks integration inputs explicitly.
Neither jar bundles those dependencies or the test harness.

At the start, Forge's feature/reputation-0.6.0 and main pointed at the same commit;
main is GitHub's default branch and is the release destination. Existing unrelated
documentation/test-name edits were preserved outside the release commit. Related
pre-existing Kingdoms load-order metadata was retained in the release.

## Corrections and reproduction

- **Forge death data loss:** the capability invalidation listener killed the cached
  LazyOptional. Forge's temporary entity revival during Clone did not revive that
  handle, so the copy silently read nothing. A new handle now exposes the same data
  during a legitimate revived lookup; invalidated consumer handles remain invalid.
  The dedicated-server regression failed before the fix and passes after it for
  three clone cycles, including karma, heat and imprisonment.
- **Respawn synchronization, both loaders:** send fresh self status after respawn.
  The earlier client check could observe cached pre-death values. The replacement
  clears its display cache before each of three deaths and requires fresh, correct
  karma/heat updates and retained currency.
- **Jail intake, both loaders:** a prisoner already at the jail anchor counted as an
  obstruction. Exclude only the arriving player; other players still block the
  destination, and spectators retain their prior exemption. Reproduced through a
  real multiplayer client, with live-world occupancy regressions on both loaders.
- **Currency overflow, both loaders:** round-up stack arithmetic near Long.MAX_VALUE
  could overflow negative. Subtract before division and share the corrected
  implementation with the default Currency API. Both failing boundaries were
  reproduced before correction and now pass.
- Repair obsolete custody constructor arguments in the Townstead fixtures; provide
  GeckoLib in Forge server runners, valid flat-world settings in the justice runner,
  and the existing NeoForge EULA path. Add opt-in real-client acceptance runners.

The Forge death reproduction is retained at
`/tmp/mcacrime-release-qa/death-reproduction/`. Its pre-fix jar is deliberately
excluded from release assets. The first multiplayer reproduction is
`forge-extended-mp/` under that same QA root; its saved NBT confirmed zeroed server
state after additional deaths, not just a display failure.

## Automated results

| Check | Forge | NeoForge |
| --- | --- | --- |
| Clean build, unit suite, package/API checks | PASS; 2,653 executed, 19 skipped | PASS; 2,682 executed, 9 skipped |
| Unit failures / errors | 0 / 0 | 0 / 0 |
| Live server fixtures | 19 review checks passed | 60 GameTests passed |
| Static mod consistency | 0 errors, 0 warnings, 20 INFO | Forge-only checker intentionally not applicable |

Forge's informational scanner findings concern dynamically registered mask and
key-ring models, not missing registrations. Skipped Forge tests are Townstead
probes (8), BundleHoodFamily (3), ProfessionRestorationRevision (1), and
ItemCurrencyInventory (7). NeoForge skips Townstead probes (8) and a missing
legacy fixture (1). These are skips, not successful tests. Townstead also received
separate real-mod runtime tests below. No datagen providers are registered, so
runData was not used.

Final clean-build logs:

- Forge: `/tmp/gradle-MCACrime-clean-20261003-121456.log`
- NeoForge: `/tmp/gradle-MCACrime_1.21.1-clean-20261003-121457.log`
- Forge review runtime: `build/review-runtime/check-c2744qbd/runtime-results.txt`
- XML/HTML unit evidence: `build/test-results/test/`, `build/reports/tests/test/`
- Static findings: `/tmp/mcacrime-release-qa/final-check-mod.json`

Commands, run from the respective checkout (the helper executes the full argument list):

```sh
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh "$PWD" clean build reviewRuntimeJar justiceRuntimeJar townsteadRuntimeJar maskRuntimeJar justiceClientRuntimeJar gameplayRuntimeJar -I tools/review/runtime.gradle -I tools/justice/runtime.gradle -I tools/townstead/runtime.gradle -I tools/masks/runtime.gradle -I tools/justice-client/runtime.gradle -I tools/gameplay/runtime.gradle
```

```sh
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh "$PWD" clean build townsteadRuntimeJar gameplayRuntimeJar runGameTestServer -I tools/townstead/runtime.gradle -I tools/gameplay/runtime.gradle -PrequireReputation=true -PrequireQuests=true -PrequireLocks=true
```

## Final-artifact gameplay results

All rows below used the exact production-jar hashes listed at the end of this report.
Clients ran under Xvfb/software OpenGL with actual command and interaction packets.
Worlds and loopback-only offline servers were disposable; existing worlds were untouched.

| Scenario | Forge | NeoForge |
| --- | --- | --- |
| Single-player core flow, companions absent | 11 assertions passed | 11 assertions passed |
| Dedicated multiplayer core flow, companions present | 12 assertions + restart/reconnect passed | 12 assertions + restart/reconnect passed |
| Single-player guard/thief flow, companions absent | 6 assertions passed | 6 assertions passed |
| Dedicated multiplayer guard/thief flow, companions absent | 6 assertions + restart/reconnect passed | 6 assertions + restart/reconnect passed |
| Original death-failure topology, multiplayer without companions | 12 assertions + restart/reconnect passed | Earlier candidate core flow passed; final guard/thief row above verifies absent-companion startup/restart |

The core flow covers command synchronization, outlaw fine refusal, exactly 48 emeralds
debited for the configured fine, repeat-payment protection, three deaths/respawns,
jail intake at the prisoner's position, administrative release, real witnessed assault,
masked assault with deferred heat, witnessed unmasking, served-sentence release,
non-operator command denial, and saved karma/heat after a full server restart and a
new client connection. Guard/thief flow covers actual approach from twelve blocks,
confrontation, asking charges, forged encounter rejection, surrender into custody,
and native MCA thief recruitment/retirement visible to the client.

Present companions were Reputation 0.6.1, Quests 1.7.1 and Conversations 1.8.0 on the
respective loader. Separate earlier core client runs also passed with companions
present in single-player. The earlier Forge death assertion was superseded by the
fresh-update regression described above.

Evidence root: `/tmp/mcacrime-release-qa/`.

- Final core SP: `forge-final-sp/`, `neo-final-sp/`
- Final core multiplayer: `forge-final-mp/`, `neo-final-mp/`
- Final core Forge without companions: `forge-final-mp-absent/`
- Final guard/thief SP: `forge-law-sp-3/`, `neo-law-sp-3/`
- Final guard/thief multiplayer: `forge-law-mp/`, `neo-law-mp/`

Each client directory has `PASS.txt`, `client.log`, `artifacts.json` and
`launch-command.json`; multiplayer uses `client-0/`, `client-1/` and
`server/console-{0,1}.log`. Screenshots are in `screenshots/`.
The exact Python-runner commands are recorded in `final-gameplay-commands.sh` at the
evidence root. The test harness jar is never a distributable mod dependency.
The final accepted logs contain no MCA Crime exceptions or tick-overrun warnings;
the environment/upstream findings below remain explicitly documented.

## Supplemental runtime coverage

These checks ran earlier in this pass against candidate jars. Their covered code
was unchanged by the subsequent death/currency/intake corrections; they complement
the final-artifact acceptance runs rather than replace them.

- Townstead present: 27 real-mod dedicated-server checks per loader; absent: 4 per
  loader. Covers binding, needs/calendar, incapacitated witnesses/responders, guard
  rest, escort/reaction ownership, equipment restoration, storage dimensions,
  configuration switches, custody feeding/accounting/recovery and mixin application.
- Forge native justice, without Reputation: 11 checks across initial run/restart;
  with Reputation: 12. Covers thief exemption, normal law, lethal scope, witnessed
  reports, lawful arrest, combat protection, native MCA news and receipt persistence.
- NeoForge also passed 59 GameTests with the real optional Locks Reforged runtime
  jar before the added occupancy test increased the suite to 60.
- Forge client visuals: 16 masks on stands/player/MCA and resource reload; native
  reporting UI, keyboard selection, report status and multi-page MCA letter UI.
  Screenshots and PASS files are retained in `forge-masks-first/` and
  `forge-justice-ui/` under the QA root.

Archived server evidence is under
`/tmp/mcacrime-release-qa/baseline-evidence/{MCACrime,MCACrime_1.21.1}/`.
NeoForge GameTests additionally cover guard/archer confrontation and refusal,
sleep-aware witnesses, relationship-aware mugging, thief occupation/POI lifecycle,
mask crafting/dye/restyle, restraints, inventory replay/stale revisions, locks,
automation, tether death cleanup, capital sentences, detention, loot, cells,
dimension transfer and persistence.

## Log findings and remaining coverage

- Linux clients report the unavailable narrator `libflite`; visual/gameplay checks
  still run. Offline fixtures cannot authenticate to Realms.
- One NeoForge integrated-server shutdown emitted Netty ClosedChannelException
  after the harness requested exit; the world saved and the process exited normally.
  The trace contains Minecraft/Netty code. Do not describe these logs as error-free.
- The older Forge MCA 7.6.20 + Townstead + Patchouli combination emits client-class
  side errors on dedicated servers. An upstream-only control reproduced them with
  MCA Crime and its harness absent. No third-party jars were modified.
- This was short-session testing, not a performance profile or long-duration soak.
  Two real client sessions connected sequentially for restart tests; simultaneous
  human-player PvP/races and hostile packet fuzzing were not exhaustively played.
- Autonomous thief stealing over long sessions, every fence/bounty/ransom path,
  every GUI/control/configuration combination, crash/power-loss recovery, arbitrary
  mod versions, Epic Fight and every optional currency provider remain unverified.
- Existing documented limitations remain: rescue credits are not delivered to
  Reputation; Forge bundle hood use requires the Bundle experiment; NeoForge lacks
  several Forge-native reporting/news/justice features. These were not silently
  treated as passing gameplay tests or expanded into a new port in this patch.

## Final artifacts

Both are `build/libs/mcacrime-0.7.6.jar` in their own checkout. Release attachment
filenames include the loader and Minecraft version; renaming does not alter bytes.

- Forge SHA-256: `485a57fb39c0f52ab9c62007758d1c29a3a2019cea7a540b0ea09ac002050e61`
- NeoForge SHA-256: `375304efa5bb590467b3b65547e78dbcce0ce99997f60da12f3f95bd6b6e83db`

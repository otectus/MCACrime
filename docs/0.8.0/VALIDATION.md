# 0.8.0 validation — NeoForge 1.21.1

Recorded 2026-10-20 for the NeoForge port of 0.8.0, against `MCA_Crime_0.8.0_Coding_Agent_Instructions.md`
(sections 3 to 8; the document is in the Forge tree's `docs/0.8.0/`); the upgrade from the published 0.7.8 was
added on 2026-10-21, before publication. 0.7.9, the baseline of several runs below, is the development build
that preceded 0.8.0 and was never published on its own; 0.7.8 is the last published release. Gradle ran through
`/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <dir> <task>`. The release build ran in a fresh copy of
the working tree under `/home/otectus/Projects/.mcacrime-release/clean-0.8.0/neoforge`, compiled against the
sibling ports as a release must be (from that copy the default relative paths do not reach them):
`-PmcaReputationClasses=<ports>/MCAReputation_1.21.1/build/classes/java/main
-PmcaQuestsClasses=<ports>/MCAQuests_1.21.1/build/classes/java/main
-PlocksClasses=<ports>/Locks_Reforged_1.21.1/build/classes/java/main -PrequireLocks=true`, where `<ports>` is
`/home/otectus/Projects/1.21.1 Ports`. Logs are kept in `/home/otectus/Projects/.mcacrime-release/evidence-0.8.0/logs/`.
Run directories below are relative to the repository root.

Environment: Minecraft 1.21.1, Java 21. GameTests on the NeoForge 21.1.248 development server; production
runs on a NeoForge 21.1.250 dedicated server and client. MCA Reborn 7.7.36-beta.3+1.21.1, GeckoLib 4.8.3.
Clients ran under Xvfb with Mesa software OpenGL at 1600x1000.

## Build, unit suite and GameTests

| Check | Result | Log |
|---|---|---|
| `clean build gameplayRuntimeJar usabilityRuntimeJar -I tools/gameplay/runtime.gradle -I tools/usability/runtime.gradle` + the sibling properties above (release copy) | PASS: 3006 tests, 0 failures, 12 skipped. `verifyApiJar`: 208 classes. `checkJarContents`: 2170 entries, nothing shaded, no Forge or relocated-MCA references, all classes Java 21 | `neoforge-clean-build.log` |
| `McaBindingProbeTest` (inside `test`) | PASS: all five `mca_probe_versions` bind at `net.conczin.mca.` (EGdYukBI = 7.7.0, 7.7.13, 7.7.22, 7.7.33, 7.7.36-beta.3) | same |
| `runGameTestServer` (release copy, twice) | PASS: all 125 required GameTests, both runs | `neoforge-clean-gametests-1.log`, `-2.log` |
| `test` and `runGameTestServer` again in the release copy after the key-workflow parity cases (below), without rebuilding the jars | PASS: 3008 tests, 0 failures, the same 12 skipped; all 126 required GameTests | `gradle-neoforge-test-20261020-184627.log`, `gradle-neoforge-runGameTestServer-20261020-184709.log` |
| `capitalsProbeTest -PcapitalsJar=/home/otectus/Projects/MCAConversations/build/mcacapitals-1.3.5-neoforge.jar` (release copy) | PASS: 3 run, 1 skipped by design (the "without MCA" case: MCA is on this runner's classpath, so a loader without it cannot be built here) | `gradle-neoforge-capitalsProbeTest-20261020-183821.log` |
| `python3 tools/art/build_mask_dye_layers.py --check` | PASS: all 96 dyed-mask files match the script, byte-identical to the Forge tree's | console |

**The 12 skips, by category.** No jar supplied, by design: Townstead mixin targets 6, Townstead binding 2,
Capitals binding 3 (run separately above). `@Disabled` until its fixture is captured:
`LegacyFixturePresenceTest` 1. All pre-existing.

`/home/otectus/Projects/.mcmod-tools/check_mod.py` targets Forge 1.20.1 and reports only loader mismatches on
this tree (1.21 folder names, `DeferredHolder`, Java 21); the port's resources are covered by its own tests
instead.

**The final full build caught three things the focused runs had not**, each fixed before the release copy
was built again: `ClientCachesTest` now names the common `ServerRulesView` among the justified non-client
entries in the logout sweep; `ResourceLayoutTest` and `checkJarContents` no longer read vanilla 1.21.1's own
`advancement/recipes/` as a surviving 1.20.1 `recipes/` folder, and the test lists `key_mold_imprint` among
the recipes whose result is computed; and a GameTest race: the guillotine GameTests' shared `tidy` cleared
every execution order, so when it ran in the same tick a batch-mate armed its order, that order vanished
mid-fall (one failure in four runs). It now clears only its own subject's order; every run since (six
development, three release-copy) has passed.

**Key-workflow parity, after the release build.** A test-by-test comparison with the Forge tree found three
of Forge's eight `KeyWorkflowTest` cases missing here. The two pure ones are now in `recipe/KeyWorkflowTest`
(the impression and the clay recipe never matching the same grid; a ring refusing a full or duplicate
addition), with Forge's refusal assertions aligned; the third reads and writes a ring stack's data
components, so it is GameTest `replacingAStaleKeyOnARingAfterARekeyKeepsItsName`. Only test sources changed:
the jars and the runtime evidence below are unaffected, and the row above re-ran both suites.

**New tests**: `gametest/MaskMatrixGameTests` (14: the 16 x 16 matrix through the real station menu,
recolouring all 16 styles in all 16 dyes keeping their history, blending, exact no-ops, white states,
restyling, unsupported data, washing in a real cauldron, legal state, two players, the preview, shift-click,
a recipe-generation change under an open menu, the creative tab); `gametest/UsabilityGameTests` (16:
targeting 7, guillotine 4, keys 5 including the recipe-book unlock and the ring replacement);
`mask/MaskColorsTest` (4; binds `minecraft:dyeable` for its stand-in item, because no datapack tag is loaded
in unit tests); `resource/RecipeUnlockCoverageTest`; the dyed-art test in `MaskResourceCoverageTest`; and the
unit suites shared with Forge, `action/CrimeTargetResolverTest` (11), `detention/ExecutionTimingTest` (8),
`help/CrimeHelpPagesTest` (5) and `recipe/KeyWorkflowTest` (7).

## Scenario coverage

1. **Targeting.** `UsabilityGameTests` (targeting batch) proves the aimed villager wins over a closer
   bystander, a wall refuses, the sole visible candidate is the fallback, several candidates ask, wrong kinds
   are named, the console needs and accepts an explicit target, and the gameplay ray hits at 3 blocks. The
   wrong kinds are a pig and an MCA zombie villager: the GameTest server runs MCA with
   `overwriteOriginalVillagers` on, so a vanilla villager is converted before it can be aimed at. The
   NeoForge 0.7.9 jar makes the same broken `ProjectileUtil` call the Forge jar was reproduced with.
2. **The guillotine.** Assembly from the items and a refused frame, a stale fall unable to borrow a newer
   order, a blade longer than the window striking inside its order, and breaking the lower half (frame
   gone, occupancy and order cleared, sentence kept); plus the pre-existing `DetentionDeviceGameTests` and
   `CapitalSentenceGameTests`.
3. **Keys.** Bind and open, both impression routes and four casts, ring names, a full ring, the stale-key
   replacement, a real furnace, and the recipe-book unlock.
4. **Masks.** `MaskMatrixGameTests` above; rendered evidence below.
5. **Upgrade.** `tools/usability` (this port carries only the upgrade suite there),
   `build/usability-runtime/upgrade-q_27w3pu`: phase 0 on the 0.7.9 jar (`a6f39f95…`) 4 PASS (seed), phase 1
   on the release jar (`69719790…`) 6 PASS, 0 FAIL; the console logs `mcacrime (version 0.7.9 -> 0.8.0)`.
   0.7.9 seeded the world with old-API calls only — two locks and their keys (one renamed), a ring of both, a
   raw and a baked mold, masks undyed, dyed, blended, named and worn, and custom-coloured, an armour stand
   wearing one, and a condemned villager in a pillory with the guillotine blade mid-fall — and stopped.
   0.8.0 reopened it: no execution order survived, every stack came back with identical components, the old
   keys and ring entries still open their locks, a cast from the old mold and 0.8.0's blank-mold impression
   work, every mask reads as its colour, the stand still wears its mask, and the old blade lands on nobody
   with prisoner, custody, sentence and occupancy intact. The harness raises `spawnChunkRadius` to 10 at
   startup: 1.21.1 keeps only a 2-chunk spawn area ticking, and a guillotine whose chunk never ticks would
   never let its persisted blade land. `build/usability-runtime/upgrade-qb287p91` repeats the test from the
   published 0.7.8 jar (`3abfa183…`), the path a player on the last release takes, crossing 0.7.9's
   schema step on the way: 4 PASS (seed) on 0.7.8, 6 PASS, 0 FAIL on 0.8.0, the
   console logs `mcacrime (version 0.7.8 -> 0.8.0)` and nothing at ERROR, and the reopened world's
   `mcacrime.dat` is stamped schema 18.
6. **Compatibility.** The five-version MCA probe above; production runs on MCA 7.7.36-beta.3 (the declared
   development version). Older fleet versions were probed, not launched.
7. **Optional integrations.** Every production run had no optional companion installed. The release build
   compiles against the Reputation, Quests and Locks Reforged ports; the Capitals probe ran against 1.3.5.
   **Blocked**: no NeoForge Locks Reforged runtime jar, Townstead jar or Epic Fight jar exists on this
   machine, so no coexistence smoke ran for them.

## Rendered evidence (`tools/gameplay`, role `masks-*`)

`python3 tools/gameplay/run_client.py … --loader neoforge-21.1.250 --minecraft 1.21.1 --role masks-gallery`
and `tools/gameplay/run_multiplayer.py … --duo --duo-roles masks-wearer masks-observer masks-resume`, the
same roles as the Forge build's, adapted to 1.21.1. Both ran the release jar (`69719790…`) and gameplay
harness (`2e7e4b06…`) from the clean copy.

**Contact sheets** (`build/mask-sheets/neo-gallery-final/screenshots/`): `masks-icons.png` (272 icons),
`masks-worn-1.png` and `masks-worn-2.png` (272 worn, each row's 17 colours through one shared renderer in one
frame), `masks-contexts.png` (icon, first-person hand, held, on the ground, item frame, armour stand, local
player, remote player, MCA male and female villager), `masks-world-first-person.png`,
`masks-world-third-person.png`, `masks-station-preview.png` and `masks-icons-after-reload.png`. The run also
checked all 272 localized names and creative search. Inspected by eye with the same result as Forge.
`python3 tools/gameplay/mask_sheet_report.py build/mask-sheets/neo-gallery-final`: 48 rows, none below its
24.0 threshold; the closest pairs are 33.8 among the icons (Jackal Mask, brown and green) and 26.1 worn
(Highwayman's Domino, brown and red).

**Two clients** (`build/mask-sheets/neo-duo-final`): the wearer crafted, recoloured (`#AD5398`, a blend),
restyled and washed a mask through the station and a cauldron while the observer confirmed and photographed
each state it received; all 272 catalogue stacks on armour stands were checked and photographed by the
observer; after a restart the washed mask and all 272 stands came back exactly.

## Deviations and limits

- The server-side matrix runs as GameTests in the development server rather than in a production-server
  harness; the production harness here carries only the upgrade test.
- The Mask Station's hover tooltip was not photographed (Xvfb cannot place the cursor where the game reads it).
- Locks Reforged, Townstead and Epic Fight runtime smoke tests are blocked (no jars on this machine).

# 0.8.0 validation — Forge 1.20.1

Recorded 2026-10-20 for the 0.8.0 release, against
[`MCA_Crime_0.8.0_Coding_Agent_Instructions.md`](MCA_Crime_0.8.0_Coding_Agent_Instructions.md) (sections 3 to
8); the upgrade from the published 0.7.8 was added on 2026-10-21, before publication. 0.7.9, the baseline of
several runs below, is the development build that preceded 0.8.0 and was never published on its own; 0.7.8
is the last published release. Gradle ran through `/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <dir> <task>`, always with
`-Dnet.minecraftforge.gradle.check.certs=false`: on 2026-10-20 ForgeGradle's environment check stalled on a
TLS connection test and never returned, and the flag skips only that check. The release build ran in a fresh
copy of the working tree under `/home/otectus/Projects/.mcacrime-release/clean-0.8.0/forge` (the main
checkout is never cleaned: its `build/` holds the runtime evidence cited below). Logs are kept in
`/home/otectus/Projects/.mcacrime-release/evidence-0.8.0/logs/`. Run directories below are relative to the
repository root.

Environment: Minecraft 1.20.1, Forge 47.4.23 (dedicated server and client), Java 17, MCA Reborn
7.7.1-beta.2+1.20.1 unless stated (7.6.26+1.20.1 for the older-MCA run), GeckoLib 4.8.3, Architectury 9.2.14.
Clients ran under Xvfb with Mesa software OpenGL at 1600x1000.

## Build and unit suite

| Check | Result | Log |
|---|---|---|
| `clean build usabilityRuntimeJar gameplayRuntimeJar -I tools/usability/runtime.gradle -I tools/gameplay/runtime.gradle` (release copy) | PASS. `test`: 2922 tests, 0 failures, 22 skipped. `bootstrapTest`: 4 of 4. `verifySiblingApis`: every pinned jar matches. `verifyApiJar`: 210 classes. `checkJarContents`: clean | `forge-clean-build.log` |
| `McaBindingProbeTest` (inside `test`) | PASS: all six `mca_probe_versions` bind (7.6.20, 7.6.26, 7.7.0-beta.2 at `forge.net.mca.`; 7.7.1-alpha.2, -beta.1, -beta.2 at `forge.net.conczin.mca.`) | same |
| `capitalsProbeTest -PcapitalsJar=/home/otectus/Projects/MCAConversations/build/mcacapitals-1.3.6.jar` (release copy) | PASS, 4 of 4 | `gradle-forge-capitalsProbeTest-20261020-183812.log` |
| `python3 tools/art/build_mask_dye_layers.py --check` | PASS: all 96 dyed-mask files match the script | console |
| `python3 /home/otectus/Projects/.mcmod-tools/check_mod.py <repo>` | 0 errors, 0 warnings, 36 notes, every one an item model the scanner cannot tie to a registration: the 16 loop-registered masks, their 16 `_dyed` override targets and the four `key_ring_N` override targets | `check_mod-forge.txt` |
| `python3 /home/otectus/Projects/.mcmod-tools/modmap.py <repo>` | regenerated, 77 registered entries; the hand-maintained part below `AUTO:END` byte-identical apart from the 0.8.0 notes added there | console |

**The 22 skips, by category.** No jar supplied, by design (the probe tasks supply one): Townstead mixin
targets 6, Townstead binding 2, Capitals binding 3 (run separately above, 4 of 4). No usable Minecraft
bootstrap in the shared test JVM: `ItemCurrencyInventoryTest` 7, `BundleHoodFamilyTest` 3,
`ProfessionRestorationRevisionTest` 1 — the same pre-existing skips as earlier releases.
`MaskColorsTest` hit that bootstrap problem in the shared JVM (an earlier class leaves `BuiltInRegistries`
unable to initialise, and every case skipped), so 0.8.0 runs it in its own JVM as `bootstrapTest`, wired into
`check`, where all four cases pass.

**New unit tests**: `action/CrimeTargetResolverTest` (11), `detention/ExecutionTimingTest` (8),
`help/CrimeHelpPagesTest` (5), `recipe/KeyWorkflowTest` (8), `mask/MaskColorsTest` (4),
`resource/RecipeUnlockCoverageTest` (1), the dyed-art test in `MaskResourceCoverageTest`, and the 0.8.0 key
families in `LangCoverageTest`.

## Production-server harness (`tools/usability`)

`python3 tools/usability/run_runtime.py --forge-server .mcacrime-qa/env/servers/forge-47.4.23 --crime <jar>
--harness crime-usability-runtime-checks-0.8.0.jar --mca <MCA> --architectury <a> --geckolib <g>
[--suite full|baseline|upgrade] [--restart] [--phase1-crime <jar>] [--extra-mod <jar>]`. A dedicated server
on a flat world; fake players whose outgoing packets are recorded drive the production services, menus,
recipes and block interactions. Each run directory under `build/usability-runtime/` keeps both consoles and
`results-<phase>.txt`. Every row except the baseline ran on the release jar (`45dd10ea…`) and harness jar
(`ce616dd0…`) from the clean copy.

| Run | Phase 0 | After restart | Directory |
|---|---|---|---|
| `--suite full --restart` | 77 PASS, 12 OBSERVED, 0 FAIL | 4 PASS, 0 FAIL | `build/usability-runtime/full-sqohr0aa` |
| same, MCA 7.6.26 | 77 PASS, 12 OBSERVED, 0 FAIL | 4 PASS, 0 FAIL | `build/usability-runtime/full-2lpkqs_l` |
| same, with Locks Reforged 1.7.3 | 77 PASS, 12 OBSERVED, 0 FAIL | 4 PASS, 0 FAIL | `build/usability-runtime/full-weo6ydl4` |
| `--suite upgrade --restart`, phase 0 on 0.7.9 (`5141cd4e…`), phase 1 on 0.8.0 | 4 PASS (seed) | 6 PASS, 0 FAIL | `build/usability-runtime/upgrade-58asacs_` |
| `--suite upgrade --restart`, phase 0 on the published 0.7.8 (`bb34b4b7…`), phase 1 on 0.8.0 | 4 PASS (seed) | 6 PASS, 0 FAIL | `build/usability-runtime/upgrade-opqrnwej` |
| `--suite baseline` on 0.7.9 (`5141cd4e…`, with a development build of the harness) | 12 OBSERVED | — | `build/usability-runtime/baseline-ww__7zxc` |

OBSERVED lines are recorded answers, not verdicts: the same twelve probes run against 0.7.9 in the baseline
and against 0.8.0 in each full run, so the two can be read side by side.

### 1. Targeting (instructions 3, scenario 1)

The report gave no command, version or distance, so the 0.7.9 jar was run through the same probe first
(`baseline-ww__7zxc`, MCA vanilla-villager conversion off so a vanilla villager could exist):

- `/crime mug` and `/crime report` could not select anybody: aimed at an adult MCA villager and a guard
  three blocks away in clear sight, `rayTraceLiving(4)` returned nothing, and the commands answered
  `mcacrime.mug.notarget` and `mcacrime.report.rejected.guard_unavailable`. The cause: the squared reach was
  passed to `ProjectileUtil.getEntityHitResult(Level, …)` as the box *inflation*, putting the eyes inside
  every hitbox. This is the reported "no villager" failure for the gameplay commands.
- `/crime job assign` changed a closer bystander instead of the aimed villager, accepted a villager 10.61
  blocks away through the 8-block box's corner, and one behind a wall.
- A vanilla villager and an MCA zombie villager both got "No MCA villager within 8 blocks", and the console
  got `permissions.requires.player`.

On 0.8.0 the 25 targeting checks and both help checks pass (`full-sqohr0aa`), including every distinction
above, the exact 8-block boundary (8.0 accepted, 8.3 refused), several candidates in view, an explicit
wrong-kind target refused rather than replaced, a villager in another dimension, the console with and
without an explicit target, permission levels, `/crime debug target` and `/crime debug mca`. The same run's
OBSERVED lines show 0.8.0's answers to the baseline probes: the ray returns the aimed villager, the mug
reaches its own weapon check, the report opens, and the job command changes the aimed villager and refuses
the corner, the wall, the wrong kinds and the console without a target.

### 2. The guillotine (instructions 4, scenario 2)

Assembly from the items (both facings, a refused bare frame), the empty pillory's spot, capture naming the
occupant, a second player's click a moment later refused, the frame held over an occupied pillory never
working the boards, a harmless fall on an unsentenced villager that says why, a condemned occupant's order
armed by the lever and the blade striking once, a stale fall unable to borrow a newer order, a pardon during
the fall, breaking the pillory (occupant released once, order cleared, sentence kept), a blade longer than
`executionDelayTicks` (window 2, blade 20) still striking inside its order, `/crime capital execute` from the
console naming the missing guard and then the missing site, a guard-led execution through an assigned site
(placing, arming, the ceremony, one blow), raising the blade, releasing at the boards, and a restart with
the blade mid-fall: the order does not survive and the persisted fall lands on nobody, with custody,
sentence and occupancy intact. The timing matrix (window and blade boundaries, the effective window, the
guard ceremony) is `ExecutionTimingTest`.

### 3. Keys, rings and molds (instructions 5, scenario 3)

A blank key binding to its owner's new safe (and a second blank refused); both impression routes through the
real recipe manager; four casts and no fifth; a ring made from keys for two locks, used on both, a key taken
off and put back with its name; refused crafts (duplicate lock, full ring, two rings, recorded mold) consuming
nothing; repeated previews changing nothing; a real crafting menu's shift-click handing the key back and
refusing a full inventory; a rekey stopping old keys, ring entries and later casts; a full ring saying so; a
real furnace firing a mold directly and from a hopper; the recipe-book unlock from an iron nugget; and a
chest of keys, a ring and both molds coming back from a restart with their bindings.

### 4. Masks (instructions 6, scenario 4)

Server side (`full-sqohr0aa`): 16 make and 16 restyle recipes, all taking the dye; **every style crafted at a
real station undyed and in all 16 dyes, 272 masks, each equal to the canonical sample**, with the right style,
durability, no enchantability, unchanged attributes, the standard colour by name, exact input debits, and an
NBT and network round trip; every style recoloured in all 16 dyes keeping wear, name, lore, curse, provenance
and a foreign flag; repeated blending matching leather; exact no-ops refused without consuming anything;
explicit white, custom white and undyed kept distinct; a same-family restyle keeping colour and data and a
cross-family mask not offered; unsupported data refused with its reason; washing in a real water cauldron
(only the colour goes, one level of water, the statistic counted once, an undyed mask ignored, every style
washes clean); Heat, deferred masked Heat and masked pursuit untouched by dyeing, washing and restyling; two
players at one station; the preview following dye, style and removed inputs; a bounded shift-click batch, one
dye per mask, refused into a full inventory; a `/reload` with a station open dropping the stale selection; the
creative Masks tab holding 272 stacks in catalogue order with the originals still in the Crime tab; and all
276 stored stacks (catalogue plus edge cases) identical after a restart.

Client side, see "Rendered evidence" below.

### 5. Upgrade (scenario 5)

`upgrade-58asacs_`: 0.7.9 seeded a world with old-API calls only (two locks with bound keys, one renamed; a
ring of both; a raw and a baked mold; masks undyed, dyed, blended, named and worn, and custom-coloured; an
armour stand wearing an orange mask; a condemned villager in a pillory under a guillotine whose blade was
released with a 200-tick fall), then stopped mid-fall. Phase 1 on 0.8.0 (the console logs `mcacrime (version
0.7.9 -> 0.8.0)`): no execution order survived; every key, ring and mold stack identical, bindings unchanged,
the renamed key's name kept; the old keys and ring entries still open their locks, a cast from the old baked
mold opens lock A, and 0.8.0's blank-mold impression takes the old key B; every mask identical and read as
its colour (blue, black, a blend as custom, the named worn lime one, the custom `#123456`); the armour stand
still wearing its mask; and the old blade landing on nobody, with prisoner, custody, sentence, occupancy and
the crime record intact.

`upgrade-opqrnwej` repeats the test from the published 0.7.8 jar, the path a player on the last release
takes, and crosses 0.7.9's schema step on the way: the same seed passes on 0.7.8, all six checks pass on
0.8.0, the console logs `mcacrime (version 0.7.8 -> 0.8.0)` and nothing at ERROR, and the reopened world's
`mcacrime.dat` is stamped schema 18.

### 6. Compatibility (scenario 6)

The MCA binding fleet probe passes for all six configured versions (above). The full harness ran in
production on MCA 7.7.1-beta.2 (the declared development version) and on 7.6.26 (`full-2lpkqs_l`, the older
`forge.net.mca.` package root; `/crime debug mca` reports `status=BOUND root=forge.net.mca.`), with the same
results. Other fleet versions were probed, not launched.

### 7. Optional integrations (scenario 7)

Every run above except the Locks Reforged row had no optional companion installed: the baseline path.
Locks Reforged 1.7.3 installed beside MCA: Crime (`full-weo6ydl4`, startup logs "Locks Reforged detected")
changes no result. MCA Capitals: the binding probe against 1.3.6. **Blocked**: no Townstead jar and no Epic
Fight jar exists on this machine, so neither coexistence smoke ran. 0.8.0 changes no Townstead or Epic Fight
code path; command targeting and the gameplay ray are server-side and do not touch the Epic Fight
interaction shim.

## Rendered evidence (`tools/gameplay`, role `masks-*`)

`python3 tools/gameplay/run_client.py --work <dir> --install <launcher> --crime <jar> --harness
crime-gameplay-checks-0.8.0.jar --mca <MCA> --architectury <a> --geckolib <g> --xvfb <Xvfb> --loader
forge-47.4.23 --minecraft 1.20.1 --role masks-gallery`, and `tools/gameplay/run_multiplayer.py … --duo
--duo-roles masks-wearer masks-observer masks-resume` for two clients on one dedicated server. Both ran the
release jar (`45dd10ea…`) and gameplay harness (`2ff20a3b…`) from the clean copy.

**Contact sheets** (`build/mask-sheets/forge-gallery-final/screenshots/`), drawn by the game:

- `masks-icons.png`: all 16 styles, original and 16 colours, as inventory icons (3x).
- `masks-worn-1.png`, `masks-worn-2.png`: the same 272 worn on armour stands. Each row draws its 17
  colours on 17 stands through the one shared renderer of that style in one frame, the colour-leak check.
- `masks-contexts.png`: icon, first-person hand, held by a mob, on the ground, item frame, armour stand,
  local player, remote player, MCA male and female villager.
- `masks-world-first-person.png`, `masks-world-third-person.png`: in the world, a red Owl Mask worn and a
  lime Hockey Mask held by the player, a white Jackal Mask dropped, a blue Plague Doctor Mask on a stand.
- `masks-station-preview.png`: the Mask Station previewing a light-blue Plague Doctor Mask.
- `masks-icons-after-reload.png`: the icons again after a resource reload.

The same run checked all 272 localized names ("Blue Plague Doctor Mask") and creative search ("blue plague"
finds the blue and light-blue masks; "black balaclava" the Balaclava). Inspected by eye: every style shows
every colour, lenses, eyes, straps, trim, the skull print, hockey marks and stitching keep their own colours,
and black is distinct from gray on the darkest styles. `python3 tools/gameplay/mask_sheet_report.py
build/mask-sheets/forge-gallery-final` measures, per style and sheet, the closest two dyes' average colours
over the pixels that change between dyes: 48 rows (16 styles on the icons, the icons after the reload, and
worn), none below its 24.0 threshold. The closest pairs are 33.8 among the icons (Jackal Mask, brown and
green) and 26.0 worn (Highwayman's Domino, brown and red).

**Two clients** (`build/mask-sheets/forge-duo-final`): the wearer crafted a blue Plague Doctor Mask at the
station through real clicks and the selection packet, recoloured it red (a blend, `#AD5398`), restyled it
into a Jackal Mask keeping the blend, and washed it in a cauldron, wearing each result; the observer confirmed
each state from the stack it received for the wearer's head and photographed it
(`client-0-bystander/screenshots/observer-world-state-1..4.png`, `observer-closeup-state-1..4.png`). The
wearer then placed all 272 catalogue stacks on armour stands; the observer checked every one against its grid
position and photographed them (`observer-stands-1.png`, `observer-stands-2.png`). After a server restart the
wearer still wore the washed mask, undyed, and all 272 stands came back with their exact colours
(`client-1-offender`).

## Deviations and limits

- The NeoForge port runs the server-side matrix as GameTests and carries only the upgrade suite in its own
  `tools/usability`; see its validation record.
- Vanilla-villager targeting can only be exercised with MCA's vanilla-villager conversion off, which the
  harness sets in `config/mca.json`; with it on, a vanilla villager never exists to aim at.
- The Mask Station's hover tooltip was not photographed: Xvfb cannot place the cursor where the game reads
  it. Its colour and keeps/new lines are in the code and the preview item itself is in the screenshot.
- There is no JEI integration in this mod, so none was extended (section 7 asks only for an existing one).
- Townstead and Epic Fight smoke tests are blocked (no jars on this machine).

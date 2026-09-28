# MCA: Crime 0.7.5 — verification record

What was actually run, per milestone, on both lines. Every row below comes from an independent
verification report written against the tree at that milestone; nothing here is a prediction, and
nothing that has not run is reported as green. What has **not** been run yet is listed at the end.

- Baseline line: `/home/otectus/Projects/MCACrime`, branch `feature/reputation-0.6.0`, Forge 1.20.1.
- Port line: `/home/otectus/Projects/1.21.1 Ports/MCACrime_1.21.1`, branch `neoforge/1.21.1`,
  NeoForge 1.21.1.
- Gradle was invoked through `/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh <project> <task>`
  on both lines (the port's own instructions name the PowerShell wrapper; the Linux script is the
  substitute and works with the quoted project path).

Pre-edit baselines are in [BASELINE.md](BASELINE.md) for the Forge line; the port's pre-edit baseline
is summarised in the P0 row below.

---

## 1. Baseline line — Forge 1.20.1

Each milestone's verification aggregated the JUnit XML in `build/test-results/test/` and checked that
no file under `src/` post-dated the newest result file, so the numbers cover the tree as it stood.

| Milestone | Suite XMLs | Tests | Failures | Errors | Skipped | Jar (`checkJarContents`) | Result |
|---|---|---|---|---|---|---|---|
| M0 baseline | 240 | 1924 | 0 | 0 | 16 | clean | PASS |
| M1 state model and migration | 253 | 2065 | 0 | 0 | 16 | clean | PASS |
| M2 restraints, legacy removal | 261 | 2112 | 0 | 0 | 16 | clean | PASS |
| M3 lockpicking and locks | 276 | 2215 | 0 | 0 | 16 | clean | PASS |
| M4 transport and detention | 291 | 2363 | 0 | 0 | 16 | clean | PASS |
| M5 frisking and prison content | 304 | 2446 | 0 | 0 | 16 | clean | PASS |
| M6 enchantments, integrations, capital sentence | 315 | 2554 | 0 | 0 | 16 | clean | PASS |

The sixteen skips are the same throughout and are all companion-absent or fixture-conditional:
`TownsteadBindingProbeTest` (2), `TownsteadMixinTargetTest` (6), `ProfessionRestorationRevisionTest`
(1) and `economy.ItemCurrencyInventoryTest` (7).

### The protected textures

Checked at the baseline and again at M2, M3, M5 and M6, both in `src/main/resources` and **inside the
built jar**, by `sha256sum`:

- `restraint_cuffs.png` → `df1a7d30af0e4795cf706c94a4b37036dd748c4f9c750b793550daeb6f6997f4`
- `restraint_locked_cuffs.png` → `b0a86e25652e6d1acc7b2d1cf58a4be73b9e3bc4218760917337e324fd7d084f`

Every check matched the values the plan requires. `resource/ProtectedTextureHashTest` (3 tests) is the
in-suite gate for the same fact.

### What each milestone's verification looked at beyond the suite

- **M1.** The migration and reconciliation model: `CrimeDataMigrationsV14toV15Test` (10),
  `RestraintReconcilerTest` (21, covering all eleven reconciliation cases plus a repeated load),
  `PhysicalStateWorldDataTest`, `FrozenGuardCoverageTest`, `QuarantineAndFutureSchemaTest`,
  `CustodyIdentityTest`. The M1 gate — nothing reachable from gameplay yet — was confirmed.
- **M2.** All 21 deleted legacy types confirmed absent from `src/` with no import, type use or call
  site left; the only textual hits are Javadoc naming what replaced them. Mixin registration,
  item/model/lang/recipe coverage and provenance rows were checked.
- **M3.** Server authority for lockpicking: every C2S packet carries intent only
  (`LockpickAttemptC2SPacket` is `(long sessionId, int phase, int angleMilliDegrees)`,
  `LockpickCancelC2SPacket` is `(long sessionId)`), identity comes from the connection, and the
  outcome is computed server-side. Locks Reforged coexistence and the upstream defect fixes were
  checked against source.
- **M4.** `HopperLockMixin` was verified against the real SRG jar
  (`forge-1.20.1-47.4.10-srg.jar`, cross-checked against 47.4.23) with `javap`: `ejectItems` exists
  literally and is injected with `remap = false`, `suckInItems` is SRG-mapped to `m_155552_` and is
  injected remapped. Both injections resolve in production; neither name is overloaded. The M4.8
  physical/legal bridge and the removal of the legacy transport path were checked.
- **M5.** The frisking transaction: the menu rejects every click path (`clicked` empty,
  `quickMoveStack` empty, `canDragTo` false, every slot refusing place and pickup), the container
  projection is inert, and the transfer packet carries `(sessionId, viewIndex, expectedRevision,
  count)` with identity from the connection and nine ordered validation conditions.
- **M6.** Capital sentencing against plan §3.19, key by key: the new `mcacrime:kill_guard` id and its
  datapack row; `SentenceKind` on the binding and mirrored on custody; idempotent marking that never
  upgrades a bound sentence; ransom and bail refusals with their own reasons and lang keys; the
  escort service, execution authorisation and the once-only death outcome. **No automatic
  escalation:** the only writes of `SentenceKind.CAPITAL` anywhere in the tree are inside
  `CapitalSentenceService.mark`, which requires an actionable `mcacrime:kill_guard` case; every other
  write is a downward commutation. All eleven config keys carry their §3.19 defaults and ranges, with
  validator rules wired. The three art generators were re-run with `--check` and all three passed
  (`generate_dormant_art.py`, `generate_poster_art.py`, `generate_restraint_art.py`).

### M7 (this documentation pass)

```
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCACrime \
    test --tests '*ConfigGroupCoverageTest*'
```

`PASS - test (6s)`. `config/ConfigGroupCoverageTest` passes: every config key this release declares
is named in `CONFIG.md`, no key repeats its own group name, every 0.7.5 group is present, and the
legacy-undocumented list is still accurate.

A full `test` run also happened in that pass, at about 21:46-21:47: **2600 tests, 0 failures, 16
skipped**.

**Clemency API coverage.** `api/CapitalClemencyApiTest` (four tests) covers the two clemency
mutators, `commuteCapitalSentence` and `pardonCapitalSentence`. The M6 verification note that
clemency was "command-only in practice" is **superseded** by those M7 additions.

### M7 fix pass

| Command | Result |
|---|---|
| `compileJava` | **PASS** |
| `check` | **PASS** — 0 failures, 16 skipped. `ConfigGroupCoverageTest` failed mid-pass, while `CONFIG.md` was still missing the 75 legacy keys this documentation pass adds; it passes once they are documented. |
| `build` | **PASS**; the `build/libs/mcacrime-<version>.jar` artifact produced (version from `gradle.properties`), `checkJarContents` clean |
| `.mcmod-tools/check_mod.py` | 1 pre-existing error, 29 accepted notes |

### Final verification

Run against the finished 0.7.5 tree, after the profile-cap, `CrimeStats` and sound fixes.

**JUnit aggregate.** `clean` then `check`, both exit 0. Aggregating
`build/test-results/test/*.xml`: **2615 tests, 0 failures, 0 errors, 16 skipped**. The sixteen skips
are the usual companion-absent or fixture-conditional ones (`TownsteadBindingProbeTest` 2,
`TownsteadMixinTargetTest` 6, `ProfessionRestorationRevisionTest` 1,
`economy.ItemCurrencyInventoryTest` 7). (The earlier aggregates in this pass read 2610 and then 2611
tests; the difference is the profile-cap test and the new sound-coverage tests.)

Gates, all with 0 failures and 0 errors: `NoMcaStaticLinkTest`, `NoTownsteadStaticLinkTest`,
`compat.TownsteadMixinTargetTest`, `MixinConfigTest`, `ClientConfigIsolationTest`, `ConfigSweepTest`,
`compat.McaBindingProbeTest`, `OptionalClassloadTest`, `LangCoverageTest`,
`resource.ProtectedTextureHashTest`, `config.ConfigGroupCoverageTest`, `config.PresetTest`,
`config.RetiredKeyReportTest`, `resource.RecipeResultStackSizeTest`,
`restraint.RestraintProfileLoaderTest`, plus the resource-coverage suites
(`MaskResourceCoverageTest`, `resource.DetentionResourceCoverageTest`,
`resource.LockResourceCoverageTest`, `resource.PrisonResourceCoverageTest`,
`resource.RestraintResourceCoverageTest`) and the new `resource.SoundResourceCoverageTest`.

**Build and jar.** `build` exit 0; `reobfJar` ran, `jarJar` skipped.
`checkJarContents` reported `mcacrime-0.7.5.jar is clean (no shaded companion/Architectury/MCA
classes)`. Artifact `build/libs/mcacrime-0.7.5.jar`. The copy built after the statistics fix hashed to
`dbc1c55f56ac8c59087d38a138c8938d498195e85501e4bbe12ba9de7990c72e` and was the jar used for the
production-style server run below. The final artifact was rebuilt once more after the `sounds.json`
fix and hashes to `df519f278bf1055179ae91ce22d785fcdfc18d4932e81670bde5dbbcfc9aa112` (3,138,787
bytes); it postdates every source file and was not relaunched on the server. No `.class` outside `dev/otectus/mcacrime/`; the manifest lists both
mixin configs. Both protected textures are byte-identical **inside the jar** to the expected hashes
above.

**`check_mod.py`.** `python3 .mcmod-tools/check_mod.py MCACrime` — `1 errors, 0 warnings, 29 notes`,
the accepted set: the known false-positive error (`CrimeKeybinds` declaring `Bus.MOD` while handling a
Forge-bus `TickEvent`) and 29 informational “item model exists but no item with that id is
registered” notes for the mask, poster, key-ring and filled-container variant models. The sound fix
did not change that set.

**Author tools, all four `--check` clean:** `tools/gui/generate_gui_sheet.py`,
`tools/art/generate_restraint_art.py`, `tools/art/generate_poster_art.py`,
`tools/art/generate_dormant_art.py`.

**Static audits.** No duplicate listeners — exactly one manual `MinecraftForge.EVENT_BUS.register`
(`compat/mcaquests/McaQuestsBountyCompat`, which carries no `@Mod.EventBusSubscriber`), so no handler
is registered twice. No legacy names live: every hit for the removed capture/restraint types is inside
a Javadoc `{@code}` reference. Registry coverage: 15 `DeferredRegister`s, each registered exactly once
from the 13 holder `register(modBus)` calls in the `McaCrime` constructor; none registered twice, none
left unregistered. Licence and provenance files present: `LICENSE.md`, `CREDITS.md`,
`docs/0.7.5/PROVENANCE.md`. (No licence file is packaged inside the jar.)

### Runtime verification

**(a) Why the Gradle dev runs cannot launch on this line.** `runServer` and `runClient` both abort in
the bootstrap mixin phase, before FML mod construction:

```
Mixin apply failed forge-mca.mixin.json:MixinTranslatableText -> ...
Critical injection failure: @Inject annotation on mca$updateTranslations could not find any targets
matching 'm_237524_()V' ... No refMap loaded.
```

MCA Reborn bakes SRG member names into its own mixin annotations and ships no reference map, so under
this project's `official` mappings its mixins cannot resolve their targets. No `mcacrime` class is in
the failure path, and the identical FATAL line appears in archived logs from before 0.7.5
(`run/logs/2026-09-01-*.log.gz` and the matching FML crash report), so it is pre-existing and not a
0.7.5 defect. The standard refmap-remapping Gradle properties were tried and reverted: there is no MCA
refmap to remap. What the aborted launches did confirm is that ModLauncher received both
`--mixin.config` arguments, that the mod file was found at 0.7.5, and that both configs registered and
prepared (12 + 5) with no `mcacrime` apply failure.

**(b) Production-style dedicated server.** Because the dev run is blocked, a real server was assembled
in a scratch directory: the Forge `1.20.1-47.4.10` installer (`--installServer`, exit 0), MCA Reborn
`7.6.20+1.20.1`, Architectury `9.2.14`, the built `mcacrime-0.7.5.jar`, no optional companion, and an
`eula.txt` reusing the acceptance already recorded in the project's `run/eula.txt`.

*First run — failure.* The server died with a `NullPointerException` dispatching `RegisterEvent`:
`CrimeStats` called `Stats.CUSTOM.get(id, formatter)` **inside** the `DeferredRegister` supplier, i.e.
before the `ResourceLocation` the returned object needs exists, which throws during registration and
rolls the registries back. Fixed by binding the stat formatters in common setup instead of in the
supplier — the same rule the NeoForge port already follows. An audit of the tree found no other
supplier-time registry lookup.

*Fixed run — pass.* The mod file was found as `mcacrime-0.7.5.jar with {mcacrime} mods - versions
{0.7.5}` and appears in the mod list alongside Minecraft, Forge, Architectury and MCA only. Both mixin
configs registered and prepared (12 + 5). Nine common mixins applied — `RestraintJumpMixin`,
`MaskStationAcquisitionMixin`, `RestraintContainerClickMixin`, `MobDeathEquipmentMixin`,
`HopperLockMixin` (both injections), `MerchantOffersAccessor`, `RestraintPlayerActionMixin`,
`ThiefBrainMixin`, `SandSensingMixin` — with no apply failure anywhere in `latest.log` or
`debug.log`. `client.RestraintPoseMixin` is correctly not applied on a dedicated server;
`NativeJobAssignmentMixin` and `ThiefPoiValidationMixin` were never triggered because their targets
were not classloaded in an empty, player-free world; the five Townstead mixins were prepared and then
declined by the plugin gate, the designed silent path. The mod bound to MCA
(`Bound to Minecraft Comes Alive at 'forge.net.mca.'`), reported MCA: Reputation absent and used the
built-in standing store, loaded all of its own datapack content with 0 errors, and printed its
`ConfigValidator` startup report. The server reached `Done (2.978s)! For help, type "help"`, stopped
cleanly on `stop` with all dimensions saved, and wrote `world/data/mcacrime.dat` at **schema 15**.

*Caveat.* No player and no villagers joined, so gameplay behaviour, the statistic award path and the
two untriggered mixins are not exercised by this run. It establishes load-time health only.

**(c) The two `mcacrime` warnings on the fresh server.** Both are advisory `WARN`s, not faults; there
was no `ERROR` at all.

- The shipped defaults trip the validator: `guardThiefResponseRadius` (24.0) is larger than
  `guardAggroRadius` (16.0). Both defaults and the validator rule are identical at git `HEAD`, so this
  is **not a 0.7.5 regression** — but it does mean every stock install logs one config problem on
  first start. Not fixed in this release.
- `criminalJobs.presentThiefAsMcaProfession` logs its deprecation notice: since 0.7.2 the Thief is a
  real, exclusive villager profession with a Mask Station workplace, so the key has no effect.

**(d) Sounds.** The mod declared seven sound events with no `sounds.json`, so a client logged
`Missing sound for event` for each (observed on the NeoForge port's client, which has the same sound
structure). Fixed by shipping `assets/mcacrime/sounds.json` with seven `"type": "event"` redirects to
vanilla events — `restraint.apply_handcuffs` → `minecraft:block.chain.place`,
`restraint.apply_shackles` → `block.iron_trapdoor.close`, `block.pillory.use` →
`block.wooden_trapdoor.close`, `block.guillotine.use` → `block.iron_trapdoor.close`,
`block.guillotine.arm` → `block.bell.resonate`, `block.safe.open` → `block.iron_door.open` and
`block.safe.close` → `block.iron_door.close` — plus seven subtitle keys (none existed before 0.7.5). No audio files are
shipped. `resource/SoundResourceCoverageTest` guards the mapping.

**Profile cap.** `RestraintProfileLoader.install` now refuses a profile set larger than
`MAX_PROFILES` (64) **whole**, rather than partially loading it; a test covers the refusal.

---

## 2. Port line — NeoForge 1.21.1

The port has GameTests, which the baseline does not. Counts below are `runGameTestServer` results;
the source `@GameTest` method count was recounted each time and matched the number the server
reported.

| Milestone | Suite XMLs | Tests | Failures | Errors | Skipped | GameTests | Result |
|---|---|---|---|---|---|---|---|
| P0 baseline | 248 | 2002 | 0 | 0 | 9 | 32 passed | PASS |
| P1 | 261 | 2143 | 0 | 0 | 9 | 33 passed | PASS |
| P2 | 269 | 2192 | 0 | 0 | 9 | 35 passed | PASS |
| P3 | 284 | 2291 | 0 | 0 | 9 | 40 passed | PASS |
| P4 | 299 | 2440 | 0 | 0 | 9 | 49 passed | PASS |
| P5 | 312 | 2523 | 0 | 0 | 9 | 56 passed | PASS |
| Pre-release trim (section 4) | 326 | 2644 | 0 | 0 | 9 | 59 passed | PASS |

The nine skips are conditional-on-supplied-jar or fixture tests (`TownsteadBindingProbeTest`,
`TownsteadMixinTargetTest`, `LegacyFixturePresenceTest`).

`checkJarContents` passed on the port at every milestone, reporting no shaded classes, no Forge or
relocated-MCA bytecode references, and all classes at Java 21. Both protected textures match the
expected hashes on the port as well.

**Cross-line parity was checked explicitly**, not assumed: item registry names and the creative tab,
language keys added and removed, recipe sets, config key names and defaults, `ConfigValidator`
coverage, blockstates, models, loot tables and tags, `SafeInventoryPolicy` constants, tray nutrition,
excavation progress, fence data and the poster PNGs. Every difference found was a platform-forced
1.21.1 difference (data components, stream codecs, registry shape), never a behavioural one.

No P6 or P7 verification report exists yet; the port's M6-equivalent and release milestones are not
recorded here because they have not been verified.

---

## 3. Not yet performed

The following remain unrun on the Forge 1.20.1 line.

- [ ] **Forge client launch.** No dev run is possible with MCA present (see *Runtime verification*
      (a)), and no Minecraft launcher install exists on this machine, so the Forge client has never
      been started with this build. The client side was exercised on the NeoForge port only.
- [ ] **Fresh-world walkthrough** of specification section 21 against a newly created world.
- [ ] **Migrated-world walkthrough** of the same matrix against a world saved at schema 14, including
      the reconciler running exactly once and a second load being a no-op.
- [ ] **Single-player capital-sentence walkthrough**: a guard-carried execution at an assigned
      guillotine, and the same sentence with **no** guillotine present, which must end in continued
      custody rather than any substitute outcome. The port's `CapitalSentenceGameTests` cover the
      arrest-to-execution and no-device scenarios headlessly; no human walkthrough has happened on
      either line.

Until those boxes are ticked, this release is verified by its unit suite, its build, its static checks
and the headless server run above. Automated checks do not certify an in-game interaction as correct.

---

## 4. Pre-release trim — verification

Run after twelve items and the systems that existed only for them were removed (bandage, knife,
fork, spoon, prisoner tag, possessions box, fuzzy handcuffs, meal tray, poster, warden's guide,
weighted anchor, toilet) and frisking was reworked to open from the crime menu. What was removed and
why is in [CUFFED_INTEGRATION.md](CUFFED_INTEGRATION.md) (*What was dropped before release*); the
ledger rows are marked `dropped before release`.

### Forge 1.20.1 line

| Command | Result |
|---|---|
| `gradlew-quiet.sh /home/otectus/Projects/MCACrime compileJava` | **PASS** |
| `gradlew-quiet.sh /home/otectus/Projects/MCACrime compileTestJava` | **PASS** |
| `gradlew-quiet.sh /home/otectus/Projects/MCACrime check` | **PASS** — aggregate of `build/test-results/test/*.xml`: **2619 tests, 0 failures, 0 errors, 16 skipped**, 331 suites (was 2615 / 16 skipped before the trim: 13 test classes deleted, `FriskActionHandlerTest` added, and the frisk, enchantment, statistics, definition and resource tests re-scoped). The sixteen skips are the same companion-absent and fixture-conditional ones as before. |
| `gradlew-quiet.sh /home/otectus/Projects/MCACrime build` | **PASS** — `build/libs/mcacrime-0.7.5.jar` produced, `checkJarContents` clean |
| `python3 .mcmod-tools/check_mod.py /home/otectus/Projects/MCACrime` | 1 error, 0 warnings, 20 notes — the same pre-existing `CrimeKeybinds` bus note recorded in section 1; every note is a loop-registered mask or key-ring count model. No new finding. |
| `python3 tools/art/generate_restraint_art.py --check` | PASS — the two remaining authored sheets (worn handcuffs, worn shackles) match the script; the fuzzy generators are gone with the item. `generate_poster_art.py` and `generate_dormant_art.py` are deleted. |
| `python3 .mcmod-tools/modmap.py /home/otectus/Projects/MCACrime` | MODMAP.md regenerated: 75 registered entries (14 blocks, 30 items, 5 block entities, 3 entities, 2 mob effects, 7 recipe serializers; no particle). |
| Orphan sweep (`grep -rn` over `src/`, `tools/` and the tracked docs for every removed id, class and lang key) | Clean. The only hits are the `bounty/` prose about wanted posters, the nameplate note about other mods' nickname formatting, and the "removed before release" paragraphs themselves. |

**Runtime.** The disposable production server of section *Runtime verification (b)* was rebuilt
(Forge `1.20.1-47.4.10` installer into the session scratch directory, MCA Reborn `7.6.20+1.20.1`,
Architectury `9.2.14`, GeckoLib `4.8.3`, the rebuilt `crime-justice-runtime-checks-0.7.5.jar` and
the trimmed `mcacrime-0.7.5.jar`) and driven by `tools/justice/run_runtime.py`. The server reached
`Done (1.206s)`, bound to MCA (`forge.net.mca.`, 34 members), loaded 12 fence prices, 37 dialogue
events and 15 crime types with 0 errors, and the harness reported **PASS on all 11 checks**
(`build/justice-runtime/check-8i4uliot/runtime-results.txt`). The one `ERROR` line in
`logs/latest.log` is vanilla's `DedicatedServerProperties: No key layers in MapLike[{}]` for the
harness's `level-type=minecraft:flat` without generator settings, unrelated to the mod. The Forge
client launch remains unrun for the reason given in section 3.


### NeoForge 1.21.1 line

Same removal set and the same frisk rework, applied to
`/home/otectus/Projects/1.21.1 Ports/MCACrime_1.21.1` (branch `neoforge/1.21.1`) in the same job.
Platform-specific differences are recorded in that checkout's `docs/0.7.5/PARITY.md`
(*Removed before release, on both lines*).

| Command | Result |
|---|---|
| `gradlew-quiet.sh "<port>" compileJava` | **PASS** |
| `gradlew-quiet.sh "<port>" test` | **PASS** — aggregate of `build/test-results/test/*.xml`: **2644 tests, 0 failures, 0 errors, 9 skipped**, 326 suites. The nine skips are the same jar- and fixture-conditional tests as in section 2. A first run failed eight tests: `EnchantmentAvailabilityTagsTest` still listed `mcacrime:buoyant`, the three frisk tests copied from the baseline read source through a relative path the ModDevGradle runner cannot see (now `TestPaths.sources`), and `RecipeResultStackSizeTest`'s "CrimeItems was read" guard demanded more than fifteen stack-limited items when eleven remain. All four were test-side. |
| `gradlew-quiet.sh "<port>" build` | **PASS** — `build/libs/mcacrime-0.7.5.jar`; `checkJarContents`: 1815 entries, nothing shaded, no Forge or relocated-MCA bytecode references, all classes Java 21. |
| `gradlew-quiet.sh "<port>" runGameTestServer` | **PASS** — `All 59 required tests passed`; the source `@GameTest` count is 59 (17 holders, 0 optional). No `ERROR` line in the run log. |
| Orphan sweep over `src/` and the tracked docs | Clean: the remaining hits are the nameplate note about other mods' nickname formatting, `dropPossessionsOnExecution` (the escrow of the condemned's property, unrelated to the box) and the "removed before release" paragraphs. |

Protocol on this line moves 15 → 16 (the warden-guide payload is gone); the Forge line is at 18.

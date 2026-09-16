# 0.7.2 verification record

Coordinator-reported evidence for the 0.7.2 implementation of
`docs/MCA_Crime_0.7.2_Implementation_Spec.md`. Every line below names the command that was run and
where its log is. Runtime observations in a real client or dedicated server were **not** performed
in this session; they are listed as outstanding, not as passed.

## Provenance

| | |
|---|---|
| Source at start | `main` @ `b7726fad` (dirty; 90 pre-existing uncommitted entries preserved) |
| Release plan run | `~/.claude/external-workers/runs/20260914T005306Z-aab7598a` (scout Gemini 3.8 Flash, planner GPT-6 Astra; readiness `partial`: S1/S5/S6 ready, S2-S4/S7 blocked) |
| S2 investigation run | `~/.claude/external-workers/runs/20260914T011159Z-e9e4bf64` (readiness `ready` for S2 after inspecting decompiled MCA 7.6.20 / 7.7.0-beta.2 / 7.7.1-alpha.2 sources mounted read-only) |
| Delivery requirements | recorded on run `20260914T005306Z-aab7598a` before any edit (`worker.py delivery`) |
| Builders | Opus builder agents, one stage at a time, in the order S1, S5, S2, S6, S3, S4 |
| Nothing committed or installed | all changes remain in the working tree |

## Per-stage checks (as reported by each builder; logs are the wrapper's `/tmp/gradle-*` files)

| Stage | compileJava | check | build | tests after stage |
|---|---|---|---|---|
| S1 guard safety | PASS `/tmp/gradle-MCACrime-compileJava-20260913-212155.log` | PASS `/tmp/gradle-MCACrime-check-20260913-212211.log` | not run | 1333 / 0 failures |
| S5 apology | PASS `/tmp/gradle-MCACrime-compileJava-20260913-212926.log` | PASS `/tmp/gradle-MCACrime-check-20260913-213036.log` | not run | 1348 / 0 |
| S2 occupation | PASS `/tmp/gradle-.-compileJava-20260913-220452.log` | PASS `/tmp/gradle-.-check-20260913-220826.log` | PASS `/tmp/gradle-.-build-20260913-220845.log` | 1411 / 0 |
| S6 sand bottle | PASS `/tmp/gradle-MCACrime-compileJava-20260913-222829.log` | PASS `/tmp/gradle-MCACrime-check-20260913-223039.log` | PASS `/tmp/gradle-MCACrime-build-20260913-223235.log` | 1455 / 0 |
| S3 station | PASS `/tmp/gradle-MCACrime-compileJava-20260913-225422.log` | PASS (after one own-test fix) `/tmp/gradle-MCACrime-check-20260913-225759.log` | PASS `/tmp/gradle-MCACrime-build-20260913-225824.log` | 1501 / 0 |
| S4 catalogue | PASS `/tmp/gradle-MCACrime-compileJava-20260913-231200.log` | PASS `/tmp/gradle-MCACrime-check-20260913-231459.log` | PASS `/tmp/gradle-MCACrime-build-20260913-231648.log` | 1533 / 0 (7 pre-existing skips) |

`McaBindingProbeTest` ran against all three probe jars in every `check` (0 skipped), including the
S2 thief-occupation capability bundle, the `Villager` supertype assertion and the
`setClothes(String)` overload.

## Final verifier pass

Run by the Sonnet verifier after S4, before documentation was applied (documentation changes only
`.md` files and do not invalidate these results).

| Command | Result | Log |
|---|---|---|
| `.mcmod-tools/gradlew-quiet.sh . compileJava` | PASS | `/tmp/gradle-MCACrime-compileJava-20260913-232003.log` |
| `.mcmod-tools/gradlew-quiet.sh . check` | PASS | `/tmp/gradle-MCACrime-check-20260913-232010.log` |
| `.mcmod-tools/gradlew-quiet.sh . build` (includes `checkJarContents`) | PASS | `/tmp/gradle-MCACrime-build-20260913-232018.log` |
| `python3 .mcmod-tools/check_mod.py .` | 1 pre-existing error (below), 0 warnings, 16 INFO notes | stdout |
| `python3 tools/gui/generate_gui_sheet.py --check` | up to date | stdout |
| `git diff --check` | no whitespace errors | stdout |

Test results (`build/test-results/test/*.xml`): **1533 tests, 0 failures, 0 errors, 7 skipped**
(the skips are the pre-existing bootstrap-gated `ItemCurrencyInventoryTest`).

New or extended classes and their counts: NpcMuggerEligibilityTest 14, NpcMuggerBoundaryTest 6,
GuardPromotionEligibilityTest 4, ApologyInteractionPolicyTest 14, OccupationTransitionTest 14,
ThiefWorksiteServiceTest 9, ThiefOccupationLifecycleTest 9, NativeOccupationPolicyTest 10,
CriminalVillagerRecordTest 16, McaBindingProbeTest 2 (not skipped), MixinConfigTest 6,
NoMcaStaticLinkTest 1, SandExposurePolicyTest 13, SandRecoveryTest 9, SandIncidentTest 9,
SandPerceptionTest 5, ConfigValidatorSandTest 7, MaskCraftPlanTest 10, MaskMakingRecipeJsonTest 11,
MaskStationCatalogTest 8, MaskStationSelectionPacketTest 10, MaskStationRouteTest 7,
MaskCatalogTest 7, MaskCustomizationTest 14, MaskRecipeDataTest 6, MaskResourceCoverageTest 4,
LangCoverageTest 4.

Artifact: `build/libs/mcacrime-0.7.2.jar`, 1,766,793 bytes,
SHA-256 `5659873ed6ff50557d72edad03c5ec8af70e846f7c95b2b592e40404eaa12dfe` (hash measured locally by
the coordinator through the verifier; not independently reproduced on another machine). The jar
carries 17 armour-layer textures and 37 entries under `data/mcacrime/recipes/mask_station/`, and no
entry under `net/mca/`, `forge/net/mca/`, `net/conczin/` or `dev/architectury/`.

Static-linkage sweep: no `import` of `net.mca`/`net.conczin` in `src/main/java` (one Javadoc
mention in `compat/mca/McaBinding.java:26`). The 14 `network/*S2CPacket` classes, including the new
`MaskSelectionS2CPacket`, import `client/CrimeClientHandlers` inside a `DistExecutor` client lambda;
this is the repository's pre-existing packet pattern and not a 0.7.2 regression.

The 16 `check_mod.py` INFO notes say the mask item models have no literal `ITEMS.register("id"`;
the masks are registered in a loop over `item/MaskVariant`, and `MaskResourceCoverageTest` proves
model, icon, layer, lang and tag coverage for all 16.

## Documentation status

Documentation writing was routed through the `document` workflow (writer Gemini 3.8 Flash,
independent verifier Gemini 3.1 Pro; no Sol fallback was triggered).

| Targets | Outcome |
|---|---|
| `CHANGELOG.md`, `docs/MIGRATION.md` | Applied after an independent Gemini 3.1 Pro PASS (66 claims); run `20260914T033440Z-101bd440`, backups in its `documentation-before/`. Only the `[0.7.2]` changelog section changed. |
| `CONFIG.md` | Not applied. Runs `20260914T034114Z-44ae6a11`, `20260914T034833Z-685165a8`: the worker sandbox auto-denied the `command` permission the writer/verifier tried to use (`cat`, `jq`, heredoc scripts). |
| `DATAPACK.md`, `API.md` | Not applied. Runs `20260914T035306Z-9582d9bc`, `20260914T035909Z-c802b2fd`: same `command` auto-denial. |
| `README.md` | Not applied. Runs `20260914T040520Z-28d6122a` (claim-anchor uniqueness failure) and `20260914T041444Z-af6710f6` (verifier FAIL with 8 findings, correction draft hit the same auto-denial). The findings are retained in that run for the next draft. |

The `command` denial is a local rule rejection inside the worker sandbox, not a provider error, so it
authorised neither a model substitution nor a permission change. Updating the four remaining files
needs either an allow-rule for the documentation workers or a decision to write them another way.
`CLAUDE.md` (structure and mixin notes) and the hand-maintained section of `MODMAP.md` were updated
by the coordinator; `MODMAP.md` above `AUTO:END` was regenerated with `.mcmod-tools/modmap.py`.

## Known pre-existing issue (out of scope)

`check_mod.py` reports one error on every run: `client/CrimeKeybinds.java:50` declares a MOD-bus
subscriber that handles a Forge-bus `TickEvent`. The file was last changed in 0.5.0 and was not
touched by 0.7.2.

## Incident during S3

The S3 builder rewrote `assets/mcacrime/lang/en_us.json` through a JSON round-trip and then reverted
it with `git checkout`, discarding the uncommitted keys of S1/S2/S5/S6. It restored the file from
`build/resources/main/.../en_us.json` (built after S6) and re-appended its keys. The coordinator
re-verified afterwards: 644 unique keys, no duplicates, every stage key present, `check_mod.py` 0
warnings, `LangCoverageTest` PASS. Later builders were instructed never to use git to rewrite files.

## Outstanding runtime acceptance (not verified in this session)

Each stage record lists its runtime-only items: `S2_OCCUPATION.md` §7 (JOB-01..14 on all three MCA
versions, production-SRG validation of the four occupation mixins, MCA's own screens showing
"Thief"), `S3_STATION.md` (CRAFT-01..12, screen at GUI scale 2, narration), `S4_MASKS.md`
(MASK-02/05..12, equipped rendering on players/MCA/armor stands), `S6_SAND.md` (SAND-01..20, in
particular SAND-06 for MCA brain-driven archers), and the S5 items (APO-01..12 with the exact Easy
Villagers/MCA add-on jars, MCA Conversations modes, Epic Fight). Dedicated-server plus two-client
runs, legacy-save migration fixtures and the performance workload have not been executed.

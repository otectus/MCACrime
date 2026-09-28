# MCA: Crime 0.7.5 — Cuffed parity ledger

Milestone **M0.2** of `docs/MCA_Crime_0.7.5_Cuffed_Integration_Implementation_Plan.md` (the *plan*;
section 6 is the source of the milestone and test columns). Requirements come from
`docs/MCA_Crime_Cuffed_Full_Integration_Plan.md` (the *specification*), sections 4, 5 and Appendix A.
Registry counts are the ones verified against the pinned Cuffed checkout
(`48a336508abda1f4684bd337bc83199a212e5422`) by the reconnaissance report.

**Status column.** Every row starts at `planned`. A row marked `dropped before release` was implemented and then removed from the tree before 0.7.5 shipped; `docs/0.7.5/CUFFED_INTEGRATION.md` lists the dropped set and why. Every row starts at `planned`. Implementers update it (`planned` → `in progress` →
`implemented`, or `dropped` with a reason). `implemented` means the code, its resources and its named
acceptance test are all in the tree and the suite is green; a row whose milestone list still contains
an unfinished milestone stays `planned` however much of it exists. Nothing in this file asserts that
any manual or in-game check has run.

**Acceptance-test column.** Test names are taken from the plan's section 4 step tests and section 6.1.
The plan's section 9.3 lists only the names for the specification's section 21 acceptance tests, so
most names here do not appear there; that is expected and is not a discrepancy.

**ID mapping (plan section 3.1).** Registry ids follow Appendix A A.1 with three mandated exceptions:

| Cuffed item | MCA: Crime item id | Reason |
|---|---|---|
| `handcuffs` | `mcacrime:restraint_locked_cuffs` | existing protected locked-cuff icon; handcuffs family |
| `shackles` | `mcacrime:restraint_cuffs` | existing protected regular-cuff icon; shackles family |
| (none) | `mcacrime:restraint_rope` | hidden legacy conversion carrier for tape |
| `duck_tape` | `mcacrime:duck_tape` | path keeps `duck_tape`; display name is "Duct Tape" |

No `mcacrime:handcuffs` or `mcacrime:shackles` item is registered. Restraint **definition** ids are a
separate namespace and do use the Cuffed words (plan section 3.1).

**Counts.** 37 items, 15 blocks, 5 block entity types, 4 entity types, 10 restraint definitions,
6 enchantments, 2 effects, 7 recipe serializers, 6 sounds, 1 particle, 1 menu, 19 statistics,
43 recipe files — verified against the checkout, matching Appendix A with no count discrepancy.
Two Appendix A gaps recorded by the reconnaissance: Appendix A does not state that there is exactly
one menu type and no POI registration; and restraint durability comes
from Cuffed's config, not from item registration. Both are carried as rows/notes below.

Path prefixes: `S:` = `src/main/java/dev/otectus/mcacrime/`, `R:` = `src/main/resources/`.

---

## 1. Appendix A.1 — items (37)

| ID | Cuffed item | MCA: Crime id | Destination | Milestone | Acceptance test | Status |
|---|---|---|---|---|---|---|
| A1-01 | `key` | `mcacrime:key` | `S:item/lock/KeyItem` | M3.1, M3.2 | `LockAccessTest` | implemented |
| A1-02 | `key_ring` | `mcacrime:key_ring` | `S:item/lock/KeyRingItem` | M3.2 | `KeyRingRecipeTest` | implemented |
| A1-03 | `key_mold` | `mcacrime:key_mold` | `S:item/lock/` | M3.2 | `KeyMoldTest` | implemented |
| A1-04 | `baked_key_mold` | `mcacrime:baked_key_mold` | `S:item/lock/` | M3.2 | `KeyMoldTest` | implemented |
| A1-05 | `handcuffs_key` | `mcacrime:handcuffs_key` | `S:item/restraint/RestraintKeyItem` | M2.1, M2.7 | `EscapeServiceTest` | implemented |
| A1-06 | `shackles_key` | `mcacrime:shackles_key` | `S:item/restraint/RestraintKeyItem` | M2.1, M2.7 | `EscapeServiceTest` | implemented |
| A1-07 | `handcuffs` | `mcacrime:restraint_locked_cuffs` (existing id, protected icon) | `S:item/CrimeItems` | M2.1 | `RestraintDefinitionsTest` | implemented |
| A1-08 | `fuzzy_handcuffs` | `mcacrime:fuzzy_handcuffs` | `S:item/CrimeItems`; art bucket (c) | M2.1, M2.2, M5.12 | `CrimeItemsRegistrationTest`, `RestraintResourceCoverageTest` | dropped before release |
| A1-09 | `shackles` | `mcacrime:restraint_cuffs` (existing id, protected icon) | `S:item/CrimeItems` | M2.1 | `RestraintDefinitionsTest` | implemented |
| A1-10 | `weighted_anchor` | `mcacrime:weighted_anchor` | `S:item/`, `S:entity/WeightedAnchorEntity` | M4.2 | `WeightedAnchorPickupTest` | dropped before release |
| A1-11 | `possessions_box` | `mcacrime:possessions_box` | `S:inventory/PossessionsStore` | M5.1 | `PossessionsBoundsTest` | dropped before release |
| A1-12 | `padlock` | `mcacrime:padlock` | `S:entity/PadlockEntity`, `S:locks/LockService` | M3.4 | `PadlockLifecycleTest` | implemented |
| A1-13 | `lockpick` | `mcacrime:lockpick` | `S:lockpick/` | M3.3 | `LockpickSessionTest` | implemented |
| A1-14 | `prisoner_tag` | `mcacrime:prisoner_tag` | `S:identity/IdentityService` | M5.10 | `NicknameTest` | dropped before release |
| A1-15 | `fork` | `mcacrime:fork` | `S:block/prison/ExcavationService` | M5.5 | `ExcavationProgressTest` | dropped before release |
| A1-16 | `spoon` | `mcacrime:spoon` | `S:block/prison/ExcavationService` | M5.5 | `ExcavationProgressTest` | dropped before release |
| A1-17 | `knife` | `mcacrime:knife` | `S:wound/WoundService` | M5.9 | `WoundLifecycleTest` | dropped before release |
| A1-18 | `duck_tape` | `mcacrime:duck_tape` ("Duct Tape") | `S:item/tool/DuckTapeItem` | M2.1–M2.3, M2.7 | `RestraintDurabilityTest` | implemented |
| A1-19 | `bandage` | `mcacrime:bandage` | `S:item/tool/BandageItem` | M5.9 | `WoundLifecycleTest` | dropped before release |
| A1-20 | `cell_door` | `mcacrime:cell_door` | `S:block/CellDoorBlock` (BlockItem) | M3.4 | `CellDoorLockTest` | implemented |
| A1-21 | `reinforced_stone` | `mcacrime:reinforced_stone` | `S:block/prison/` (BlockItem) | M5.4 | `ReinforcedPolicyTest` | implemented |
| A1-22 | `reinforced_smooth_stone` | `mcacrime:reinforced_smooth_stone` | `S:block/prison/` (BlockItem) | M5.4 | `ReinforcedPolicyTest` | implemented |
| A1-23 | `reinforced_lamp` | `mcacrime:reinforced_lamp` | `S:block/prison/` (BlockItem) | M5.4 | `ReinforcedPolicyTest` | implemented |
| A1-24 | `chiseled_reinforced_stone` | `mcacrime:chiseled_reinforced_stone` | `S:block/prison/` (BlockItem) | M5.4 | `ReinforcedPolicyTest` | implemented |
| A1-25 | `reinforced_stone_slab` | `mcacrime:reinforced_stone_slab` | `S:block/prison/` (BlockItem) | M5.4 | `ReinforcedPolicyTest` | implemented |
| A1-26 | `reinforced_stone_stairs` | `mcacrime:reinforced_stone_stairs` | `S:block/prison/` (BlockItem) | M5.4 | `ReinforcedPolicyTest` | implemented |
| A1-27 | `reinforced_bars` | `mcacrime:reinforced_bars` | `S:block/prison/` (BlockItem) | M5.4 | `ReinforcedPolicyTest` | implemented |
| A1-28 | `reinforced_bars_gap` | `mcacrime:reinforced_bars_gap` | `S:block/prison/` (BlockItem) | M5.4 | `ReinforcedPolicyTest` | implemented |
| A1-29 | `pillory` | `mcacrime:pillory` | `S:block/PilloryBlock` (BlockItem) | M4.5 | `PilloryOccupancyTest` | implemented |
| A1-30 | `guillotine` | `mcacrime:guillotine` | `S:block/entity/GuillotineBlockEntity` (BlockItem) | M4.6 | `GuillotineExecutionTest` | implemented |
| A1-31 | `safe` | `mcacrime:safe` | `S:block/entity/SafeBlockEntity` (BlockItem) | M3.5 | `SafeAutomationTest`, `SafeContentsLifecycleTest` | implemented |
| A1-32 | `bunk` | `mcacrime:bunk` | `S:block/BunkBlock` (BlockItem) | M4.7 | `BunkRespawnTest` | implemented |
| A1-33 | `poster` | `mcacrime:poster` | `S:block/PosterBlock` (BlockItem) | M5.8 | `PosterVariantTest` | dropped before release |
| A1-34 | `tray` | `mcacrime:tray` | `S:block/entity/TrayBlockEntity` (BlockItem) | M5.7 | `TrayContentsTest` | dropped before release |
| A1-35 | `creative_restraint_cutter` | `mcacrime:creative_restraint_cutter` | `S:item/creative/` | M2.1 | `CreativeAuthorizationTest` | implemented |
| A1-36 | `creative_key` | `mcacrime:creative_key` | `S:item/creative/` | M2.1 | `CreativeAuthorizationTest` | implemented |
| A1-37 | `creative_bind_breaker` | `mcacrime:creative_bind_breaker` (corrected: distinct reset that rotates the binding revision) | `S:item/creative/` | M6.3 | `CreativeAuthorizationTest`, `LockAccessTest` | implemented |

Related, outside the 37: `minecraft:bundle` is used as a head restraint and gains a recipe upstream —
covered by feature row R07 and by recipe row `bundle.json`.

**Acquisition note (user decision).** `mcacrime:fuzzy_handcuffs` has **no crafting recipe**. It is
obtainable from the Fence (`economy/fence`) and from the creative tab. Upstream has no
`fuzzy_handcuffs.json` recipe either; its tooltip describes a Discord-supporter entitlement, which is
not reproduced.

## 2. Appendix A.2 — blocks (15)

| ID | Cuffed block | MCA: Crime id | Destination | Milestone | Acceptance test | Status |
|---|---|---|---|---|---|---|
| A2-B01 | `cell_door` | `mcacrime:cell_door` | `S:block/CellDoorBlock` | M3.4 | `CellDoorLockTest` | implemented |
| A2-B02 | `reinforced_stone` | `mcacrime:reinforced_stone` | `S:block/prison/` | M5.4 | `ReinforcedPolicyTest` | implemented |
| A2-B03 | `reinforced_lamp` | `mcacrime:reinforced_lamp` | `S:block/prison/` | M5.4 | `ReinforcedPolicyTest` | implemented |
| A2-B04 | `chiseled_reinforced_stone` | `mcacrime:chiseled_reinforced_stone` | `S:block/prison/` | M5.4 | `ReinforcedPolicyTest` | implemented |
| A2-B05 | `reinforced_stone_slab` | `mcacrime:reinforced_stone_slab` | `S:block/prison/` | M5.4 | `ReinforcedPolicyTest` | implemented |
| A2-B06 | `reinforced_stone_stairs` | `mcacrime:reinforced_stone_stairs` | `S:block/prison/` | M5.4 | `ReinforcedPolicyTest` | implemented |
| A2-B07 | `reinforced_smooth_stone` | `mcacrime:reinforced_smooth_stone` | `S:block/prison/` | M5.4 | `ReinforcedPolicyTest` | implemented |
| A2-B08 | `reinforced_bars` | `mcacrime:reinforced_bars` | `S:block/prison/` | M5.4 | `ReinforcedPolicyTest` | implemented |
| A2-B09 | `reinforced_bars_gap` | `mcacrime:reinforced_bars_gap` | `S:block/prison/` | M5.4 | `ReinforcedPolicyTest` | implemented |
| A2-B10 | `pillory` | `mcacrime:pillory` | `S:block/PilloryBlock`, `S:detention/DetentionService` | M4.5 | `PilloryOccupancyTest` | implemented |
| A2-B11 | `guillotine` | `mcacrime:guillotine` | `S:block/GuillotineBlock` | M4.6 | `GuillotineExecutionTest` | implemented |
| A2-B12 | `safe` | `mcacrime:safe` | `S:block/SafeBlock` | M3.5 | `SafeAutomationTest` | implemented |
| A2-B13 | `bunk` | `mcacrime:bunk` | `S:block/BunkBlock` | M4.7 | `BunkRespawnTest` | implemented |
| A2-B14 | `poster` | `mcacrime:poster` | `S:block/PosterBlock` | M5.8 | `PosterVariantTest` | dropped before release |
| A2-B15 | `tray` | `mcacrime:tray` | `S:block/TrayBlock` | M5.7 | `TrayContentsTest` | dropped before release |

## 3. Appendix A.2 — block entity types (5)

| ID | Cuffed type | MCA: Crime id | Destination | Milestone | Acceptance test | Status |
|---|---|---|---|---|---|---|
| A2-BE1 | `guillotine_block_entity` | `mcacrime:guillotine` BE | `S:block/entity/GuillotineBlockEntity` | M4.6 | `GuillotineExecutionTest` | implemented |
| A2-BE2 | `safe_block_entity` | `mcacrime:safe` BE | `S:block/entity/SafeBlockEntity` | M3.5 | `SafeContentsLifecycleTest` | implemented |
| A2-BE3 | `bunk_block_entity` | `mcacrime:bunk` BE | `S:block/entity/BunkBlockEntity` | M4.7 | `BunkRespawnTest` | implemented |
| A2-BE4 | `tray_block_entity` | `mcacrime:tray` BE | `S:block/entity/TrayBlockEntity` | M5.7 | `TrayContentsTest` | dropped before release |
| A2-BE5 | `cell_door_block_entity` | `mcacrime:cell_door` BE (`LockableBlockEntity`, stores only `lockId`) | `S:block/entity/LockableBlockEntity` | M3.4 | `CellDoorLockTest`, `LockAccessTest` | implemented |

## 4. Appendix A.2 — entity types (4)

| ID | Cuffed type | MCA: Crime id | Destination | Milestone | Acceptance test | Status |
|---|---|---|---|---|---|---|
| A2-E1 | `chain_knot` | `mcacrime:chain_knot` | `S:entity/ChainKnotEntity` | M4.2 | `AnchorTransferTest` | implemented |
| A2-E2 | `padlock` | `mcacrime:padlock` | `S:entity/PadlockEntity` | M3.4 | `PadlockLifecycleTest` | implemented |
| A2-E3 | `weighted_anchor` | `mcacrime:weighted_anchor` | `S:entity/WeightedAnchorEntity` | M4.2 | `WeightedAnchorPickupTest` | dropped before release |
| A2-E4 | `crumbling_block` | `mcacrime:crumbling_block` | `S:block/prison/` excavation | M5.5 | `ExcavationProgressTest` | dropped before release |

## 5. Appendix A.2 — restraint definitions (10)

Definition ids keep the Cuffed words (plan section 3.1); a definition names its item, never the reverse.

| ID | Definition id | Item | Destination | Milestone | Acceptance test | Status |
|---|---|---|---|---|---|---|
| A2-R01 | `mcacrime:handcuffs_arms` | `restraint_locked_cuffs` | `S:restraint/RestraintDefinitions` | M2.1–M2.3 | `RestraintDefinitionsTest` | implemented |
| A2-R02 | `mcacrime:shackles_arms` | `restraint_cuffs` | `S:restraint/RestraintDefinitions` | M2.1–M2.3 | `RestraintDefinitionsTest` | implemented |
| A2-R03 | `mcacrime:handcuffs_legs` | `restraint_locked_cuffs` | `S:restraint/RestraintAttributes` | M2.1–M2.3, M2.9 | `RestrictionCompositionTest` | implemented |
| A2-R04 | `mcacrime:shackles_legs` | `restraint_cuffs` | `S:restraint/RestraintAttributes` | M2.1–M2.3, M2.8 | `RestrictionCompositionTest` | implemented |
| A2-R05 | `mcacrime:fuzzy_handcuffs` | `fuzzy_handcuffs` (arms only) | `S:restraint/RestraintDefinitions` | M2.1 | `RestraintDefinitionsTest` | dropped before release |
| A2-R06 | `mcacrime:duck_tape_arms` | `duck_tape` | `S:item/tool/DuckTapeItem` | M2.1–M2.3 | `RestraintDurabilityTest` | implemented |
| A2-R07 | `mcacrime:duck_tape_legs` | `duck_tape` (independent leg durability/break settings) | `S:item/tool/DuckTapeItem` | M2.2 | `RestraintDurabilityTest` | implemented |
| A2-R08 | `mcacrime:duck_tape_head` | `duck_tape` | `S:item/tool/DuckTapeItem` | M2.1–M2.3 | `RestraintDurabilityTest` | implemented |
| A2-R09 | `mcacrime:bundle` | `minecraft:bundle` (empty only) | `S:restraint/RestraintDefinitions`, `S:client/HoodOverlayHandler` | M2.3, M2.10 | `HoodOverlayOwnershipTest` | implemented |
| A2-R10 | `mcacrime:pillory` | device-applied | `S:detention/DetentionService` | M4.5 | `PilloryOccupancyTest` | implemented |

Durability source note: upstream reads restraint durability from config, not item registration
(reconnaissance section 1.13). MCA: Crime takes it from the data-driven
`R:data/mcacrime/mcacrime/restraint_profiles/*.json` per plan section 3.13, and persists it on
`AppliedRestraint` (plan section 1.5).

**Datapack layer (plan section 3.13) — implemented.** `S:restraint/RestraintProfileLoader` is a
`SimpleJsonResourceReloadListener` on `AddReloadListenerEvent` reading
`data/<ns>/mcacrime/restraint_profiles/<definition path>.json`; `S:restraint/RestraintProfile` is the
validated override and `S:restraint/RestraintProfileOverrides` the layer
`RestraintDefinitions.get` reads through, so durability, restrictions, pick numbers, key family and
the rig predicate are all effective values at every call site. The file name is an existing definition
id — a file naming an unknown id is a logged validation error and is ignored, and a file with any bad
field is refused whole. Restrictions reach the client through
`S:network/RestraintProfileSyncS2CPacket` on login and on `/reload`, because the client composes its
own policy to predict blocked input. No profile file ships. Acceptance test:
`RestraintProfileLoaderTest`, plus `RestraintDurabilityTest::aDatapackProfileOverridesTheConfiguredDurability`.

## 6. Appendix A.2 — enchantments (6)

| ID | Cuffed id | MCA: Crime id | Destination | Milestone | Acceptance test | Status |
|---|---|---|---|---|---|---|
| A2-N1 | `imbue` | `mcacrime:imbue` | `S:enchantment/ImbueHandler` | M6.1 | `ImbueDistributionTest` | implemented |
| A2-N2 | `famine` | `mcacrime:famine` | `S:enchantment/CrimeEnchantments` | M6.1 | `EnchantmentApplicabilityTest` | implemented |
| A2-N3 | `shroud` | `mcacrime:shroud` | `S:enchantment/CrimeEnchantments` | M6.1 | `EnchantmentApplicabilityTest` | implemented |
| A2-N4 | `exhaust` | `mcacrime:exhaust` | `S:enchantment/CrimeEnchantments` | M6.1 | `EnchantmentApplicabilityTest` | implemented |
| A2-N5 | `silence` | `mcacrime:silence` | `S:enchantment/`, mana adapters | M6.1, M6.2 | `AdapterAbsenceTest` | implemented |
| A2-N6 | `buoyant` | `mcacrime:buoyant` | `S:entity/WeightedAnchorEntity` | M4.2, M6.1 | `WeightedAnchorPickupTest` | dropped before release |

## 7. Appendix A.2 — effects (2)

| ID | Cuffed id | MCA: Crime id | Destination | Milestone | Acceptance test | Status |
|---|---|---|---|---|---|---|
| A2-F1 | `restrained` | `mcacrime:restrained` | `S:effect/` + `R:assets/mcacrime/textures/mob_effect/restrained.png` | M2.1 | `RestrictionCompositionTest` | implemented |
| A2-F2 | `wounded` | `mcacrime:wounded` | `S:wound/WoundService`, `S:effect/` + `R:assets/mcacrime/textures/mob_effect/wounded.png` | M5.9 | `WoundLifecycleTest` | dropped before release |

## 8. Appendix A.2 — recipe serializers (7)

| ID | Cuffed id | MCA: Crime id | Destination | Milestone | Acceptance test | Status |
|---|---|---|---|---|---|---|
| A2-S1 | `key_ring_create` | `mcacrime:key_ring_create` | `S:recipe/lock/` | M3.2 | `KeyRingRecipeTest` | implemented |
| A2-S2 | `key_ring_add` | `mcacrime:key_ring_add` | `S:recipe/lock/` | M3.2 | `KeyRingRecipeTest` | implemented |
| A2-S3 | `key_ring_disassemble` | `mcacrime:key_ring_disassemble` | `S:recipe/lock/` | M3.2 | `KeyRingRecipeTest` | implemented |
| A2-S4 | `key_mold_copy` | `mcacrime:key_mold_copy` | `S:recipe/lock/` | M3.2 | `KeyMoldTest` | implemented |
| A2-S5 | `key_mold_bake` | `mcacrime:key_mold_bake` | `S:recipe/lock/` | M3.2 | `KeyMoldTest` | implemented |
| A2-S6 | `baked_key_mold_copy` | `mcacrime:baked_key_mold_copy` | `S:recipe/lock/` | M3.2 | `KeyMoldTest` | implemented |
| A2-S7 | `poster_change` | `mcacrime:poster_change` | `S:recipe/` | M5.8 | `PosterVariantTest` | dropped before release |

## 9. Appendix A.2 — sounds (6), particle (1), menu (1)

Sound **ids** are registered in `S:audio/CrimeSoundEvents` and are stable. The six upstream `.ogg`
files are **not** copied (provenance decision, `docs/0.7.5/PROVENANCE.md`). As shipped, each id
resolves to a named vanilla `SoundEvent` through a `"type": "event"` redirect in the authored
`R:assets/mcacrime/sounds.json`, which also gives every id a subtitle; **no audio file ships**. That
is what removes the client's `Missing sound for event` warning while leaving the ids free for
authored audio later. `S:audio/CrimeSoundEvents` registers a seventh id of MCA: Crime's own,
`block.guillotine.arm` (vanilla `block.bell.resonate`), which has no Cuffed counterpart and so no
Appendix A row. `resource/SoundResourceCoverageTest` gates the mapping.

| ID | Cuffed id | MCA: Crime id | Destination | Milestone | Acceptance test | Status |
|---|---|---|---|---|---|---|
| A2-D1 | `restraint.apply_handcuffs` | `mcacrime:restraint.apply_handcuffs` | `S:audio/CrimeSoundEvents`; `sounds.json` event redirect to vanilla `block.chain.place` | M2.10 | `RestraintDefinitionsTest` (sound id), `check_mod.py` | implemented |
| A2-D2 | `restraint.apply_shackles` | `mcacrime:restraint.apply_shackles` | `S:audio/CrimeSoundEvents`; `sounds.json` event redirect to vanilla `block.iron_trapdoor.close` | M2.10 | `RestraintDefinitionsTest` (sound id), `check_mod.py` | implemented |
| A2-D3 | `block.pillory.use` | `mcacrime:block.pillory.use` | `S:audio/CrimeSoundEvents`; `sounds.json` event redirect to vanilla `block.wooden_trapdoor.close` | M4.5 | `PilloryOccupancyTest` | implemented |
| A2-D4 | `block.guillotine.use` | `mcacrime:block.guillotine.use` | `S:audio/CrimeSoundEvents`; `sounds.json` event redirect to vanilla `block.iron_trapdoor.close` | M4.6 | `GuillotineExecutionTest` | implemented |
| A2-D5 | `block.safe.open` | `mcacrime:block.safe.open` | `S:audio/CrimeSoundEvents`; `sounds.json` event redirect to vanilla `block.iron_door.open` | M3.5 | `SafeAutomationTest` | implemented |
| A2-D6 | `block.safe.close` | `mcacrime:block.safe.close` | `S:audio/CrimeSoundEvents`; `sounds.json` event redirect to vanilla `block.iron_door.close` | M3.5 | `SafeAutomationTest` | implemented |
| A2-P1 | particle `blood_drip` | `mcacrime:blood_drip` | `S:entity/particle/CrimeParticles` + `R:assets/mcacrime/particles/blood_drip.json` | M5.9 | `WoundLifecycleTest`, `check_mod.py` | dropped before release |
| A2-M1 | frisking menu (the single upstream `MenuType`) | `mcacrime:frisking` | `S:menu/FriskingMenu` | M5.2, M5.3 | `FriskTransactionTest`, `ContainerClickRejectionTest` | implemented |

## 10. Appendix A.3 — statistics (19)

All under `S:stat/CrimeStats`, milestone **M5.11**, acceptance test `StatAwardOnceTest`
(A04 in plan section 6.1). Upstream never awards the tape, bundle or pillory statistics; the ledger
tracks the ids, not the upstream defect.

| ID | Statistic id | Status |
|---|---|---|
| A3-01 | `handcuffs_times_restrained` | implemented |
| A3-02 | `handcuffs_broken` | implemented |
| A3-03 | `handcuffs_time_spent_restrained` | implemented |
| A3-04 | `fuzzy_handcuffs_times_restrained` | dropped before release |
| A3-05 | `fuzzy_handcuffs_broken` | dropped before release |
| A3-06 | `fuzzy_handcuffs_time_spent_restrained` | dropped before release |
| A3-07 | `shackles_times_restrained` | implemented |
| A3-08 | `shackles_broken` | implemented |
| A3-09 | `shackles_time_spent_restrained` | implemented |
| A3-10 | `legcuffs_times_restrained` | implemented |
| A3-11 | `legcuffs_broken` | implemented |
| A3-12 | `legcuffs_time_spent_restrained` | implemented |
| A3-13 | `leg_shackles_times_restrained` | implemented |
| A3-14 | `leg_shackles_broken` | implemented |
| A3-15 | `leg_shackles_time_spent_restrained` | implemented |
| A3-16 | `times_nicknamed` | dropped before release |
| A3-17 | `successful_lockpicks` | implemented |
| A3-18 | `lockpicks_broken` | implemented |
| A3-19 | `open_safe` | implemented |

## 11. Appendix A.4 — recipe files (43)

**`key_reset.json` (A4-05) ships as a vanilla `minecraft:crafting_shapeless` recipe, not as a custom
one.** Under the 0.7.5 NBT scheme a shapeless craft already produces an unbound key — vanilla does not
copy NBT to the output — so the reset needs no serializer, and adding one would put an eighth entry in
the six-serializer table above. Upstream's own `key_reset.json` is the same plain shapeless recipe.

Destination is `R:data/mcacrime/recipes/<file>` unless a row says otherwise. Appendix A calls these a
transfer checklist, not an obligation to keep colliding duplicates; any consolidation must preserve
the acquisition route and be recorded in the Status column.

| ID | Recipe file | Destination | Milestone | Acceptance test | Status |
|---|---|---|---|---|---|
| A4-01 | `key.json` | `recipes/key.json` | M3.8 | `LockAccessTest` | implemented |
| A4-02 | `key_mold_copy.json` | `recipes/key_mold_copy.json` | M3.8 | `KeyMoldTest` | implemented |
| A4-03 | `key_mold_bake.json` | `recipes/key_mold_bake.json` | M3.8 | `KeyMoldTest` | implemented |
| A4-04 | `baked_key_mold_copy.json` | `recipes/baked_key_mold_copy.json` | M3.8 | `KeyMoldTest` | implemented |
| A4-05 | `key_reset.json` | `recipes/key_reset.json` | M3.8 | `KeyMoldTest` | implemented |
| A4-06 | `key_ring_create.json` | `recipes/key_ring_create.json` | M3.8 | `KeyRingRecipeTest` | implemented |
| A4-07 | `key_ring_add.json` | `recipes/key_ring_add.json` | M3.8 | `KeyRingRecipeTest` | implemented |
| A4-08 | `key_ring_disassemble.json` | `recipes/key_ring_disassemble.json` | M3.8 | `KeyRingRecipeTest` | implemented |
| A4-09 | `handcuffs_key.json` | `recipes/handcuffs_key.json` | M3.8 | `EscapeServiceTest` | implemented |
| A4-10 | `shackles_key.json` | `recipes/shackles_key.json` | M3.8 | `EscapeServiceTest` | implemented |
| A4-11 | `padlock.json` | `recipes/padlock.json` | M3.8 | `PadlockLifecycleTest` | implemented |
| A4-12 | `lockpick.json` | `recipes/lockpick.json` | M3.8 | `LockpickSessionTest` | implemented |
| A4-13 | `cell_door.json` | `recipes/cell_door.json` | M3.8 | `CellDoorLockTest` | implemented |
| A4-14 | `safe.json` | `recipes/safe.json` | M3.8 | `SafeAutomationTest` | implemented |
| A4-15 | `handcuffs.json` | `recipes/restraint_locked_cuffs.json` (id remap) | M2.12 | `RestraintResourceCoverageTest` | implemented |
| A4-16 | `shackles.json` | `recipes/restraint_cuffs.json` (id remap) | M2.12 | `RestraintResourceCoverageTest` | implemented |
| A4-17 | `duck_tape.json` | `recipes/duck_tape.json` | M2.12 | `RestraintResourceCoverageTest` | implemented |
| A4-18 | `bundle.json` | `recipes/bundle.json` (vanilla bundle) | M2.12 | `HoodOverlayOwnershipTest` | implemented |
| A4-19 | `weighted_anchor.json` | `recipes/weighted_anchor.json` | M4.9 | `WeightedAnchorPickupTest` | dropped before release |
| A4-20 | `pillory.json` | `recipes/pillory.json` | M4.9 | `PilloryOccupancyTest` | implemented |
| A4-21 | `guillotine.json` | `recipes/guillotine.json` | M4.9 | `GuillotineExecutionTest` | implemented |
| A4-22 | `bunk.json` | `recipes/bunk.json` | M4.9 | `BunkRespawnTest` | implemented |
| A4-23 | `bandage.json` | `recipes/bandage.json` | M5.12 | `WoundLifecycleTest` | dropped before release |
| A4-24 | `knife.json` | `recipes/knife.json` | M5.12 | `WoundLifecycleTest`, `RecipeResultStackSizeTest` | dropped before release |
| A4-25 | `fork.json` | `recipes/fork.json` | M5.12 | `ExcavationProgressTest`, `RecipeResultStackSizeTest` | dropped before release |
| A4-26 | `spoon.json` | `recipes/spoon.json` | M5.12 | `ExcavationProgressTest`, `RecipeResultStackSizeTest` | dropped before release |
| A4-27 | `prisoner_tag.json` | `recipes/prisoner_tag.json` | M5.12 | `NicknameTest` | dropped before release |
| A4-28 | `possessions_box.json` | `recipes/possessions_box.json` | M5.12 | `PossessionsBoundsTest` | dropped before release |
| A4-29 | `poster.json` | `recipes/poster.json` | M5.12 | `PosterVariantTest` | dropped before release |
| A4-30 | `poster_change.json` | `recipes/poster_change.json` | M5.12 | `PosterVariantTest` | dropped before release |
| A4-31 | `tray.json` | `recipes/tray.json` | M5.12 | `TrayContentsTest`, `RecipeResultStackSizeTest` | dropped before release |
| A4-32 | `reinforced_stone.json` | `recipes/reinforced_stone.json` | M5.12 | `ReinforcedPolicyTest` | implemented |
| A4-33 | `reinforced_smooth_stone.json` | `recipes/reinforced_smooth_stone.json` | M5.12 | `ReinforcedPolicyTest` | implemented |
| A4-34 | `reinforced_lamp.json` | `recipes/reinforced_lamp.json` | M5.12 | `ReinforcedPolicyTest` | implemented |
| A4-35 | `reinforced_bars.json` | `recipes/reinforced_bars.json` | M5.12 | `ReinforcedPolicyTest` | implemented |
| A4-36 | `reinforced_bars_gap.json` | `recipes/reinforced_bars_gap.json` | M5.12 | `ReinforcedPolicyTest` | implemented |
| A4-37 | `reinforced_stone_slab.json` | `recipes/reinforced_stone_slab.json` | M5.12 | `ReinforcedPolicyTest` | implemented |
| A4-38 | `reinforced_stone_slab_stonecutting.json` | `recipes/reinforced_stone_slab_stonecutting.json` | M5.12 | `ReinforcedPolicyTest` | implemented |
| A4-39 | `reinforced_stone_stairs.json` | `recipes/reinforced_stone_stairs.json` | M5.12 | `ReinforcedPolicyTest` | implemented |
| A4-40 | `reinforced_stone_stairs_mirrored.json` | `recipes/reinforced_stone_stairs_mirrored.json` | M5.12 | `ReinforcedPolicyTest` | implemented |
| A4-41 | `reinforced_stone_stairs_stonecutting.json` | `recipes/reinforced_stone_stairs_stonecutting.json` | M5.12 | `ReinforcedPolicyTest` | implemented |
| A4-42 | `chiseled_reinforced_stone.json` | `recipes/chiseled_reinforced_stone.json` | M5.12 | `ReinforcedPolicyTest` | implemented |
| A4-43 | `chiseled_reinforced_stone_stonecutting.json` | `recipes/chiseled_reinforced_stone_stonecutting.json` | M5.12 | `ReinforcedPolicyTest` | implemented |

No `fuzzy_handcuffs.json` exists upstream and none is authored here (see the acquisition note in
section 1).

**Deliberate deviation from upstream in four files (A4-24, A4-25, A4-26, A4-31).** Cuffed registers
the fork, spoon, knife and tray as `stacksTo(1)` items with durability and then yields **four** of
each. Vanilla clamps a craft to the item's maximum, so three of the four are destroyed and the player
pays four ingots' worth of iron for one tool. The ingredients are transferred unchanged; the result
count is `1`. `RecipeResultStackSizeTest` asserts the rule rather than the four files: no recipe under
`data/mcacrime/recipes` may yield more than its result item's maximum stack size, with the limits read
out of `item/CrimeItems`.

---

## 12. Specification section 4 — feature inventory (48)

Milestones, principal files and tests are transcribed from plan section 6.1.

| ID | Feature | Destination | Milestone | Acceptance test | Status |
|---|---|---|---|---|---|
| R01 | Arm handcuffs | `S:restraint/RestraintDefinitions`, `S:item/CrimeItems` | M1.1, M2.1–M2.3, M2.7, M2.8, M2.10 | `RestraintDefinitionsTest`, `RestrictionCompositionTest` | implemented |
| R02 | Arm shackles | as R01 | as R01 | `RestraintDefinitionsTest`, `RestrictionCompositionTest` | implemented |
| R03 | Leg handcuffs | `S:restraint/RestraintAttributes` | as R01 + M2.9 (`RestraintJumpMixin`) | `RestrictionCompositionTest` | implemented |
| R04 | Leg shackles (walking allowed) | `S:restraint/RestraintAttributes` | as R03 | `RestrictionCompositionTest::legShacklesWithGuiToggling` | implemented |
| R05 | Fuzzy handcuffs | `S:item/CrimeItems`, art bucket (c) | M2.1, M2.2, M5.12 | `CrimeItemsRegistrationTest`, `RestraintResourceCoverageTest` | dropped before release |
| R06 | Tape on head/arms/legs | `S:item/tool/DuckTapeItem` | M2.1–M2.3, M2.7 | `RestraintDurabilityTest` | implemented |
| R07 | Empty bundle hood | `S:restraint/RestraintDefinitions`, `S:client/HoodOverlayHandler` | M2.3, M2.10 | `HoodOverlayOwnershipTest` | implemented |
| R08 | Multiple simultaneous restraints | `S:restraint/RestrictionResolver` | M1.1, M2.8 | `RestrictionCompositionTest::headArmsLegsCompose` | implemented |
| R09 | Body-area application | `S:restraint/BodyRegionResolver` | M2.4, M2.5 | `BodyRegionResolverTest` | implemented |
| R10 | Self application/removal | `S:restraint/RestraintService`, `S:client/screen/SelfRestraintScreen` | M2.6 | `ApplicationTransactionTest`, `PacketBoundsTest` | implemented |
| R11 | Dispenser application | `S:restraint/DispenserRestraintBehavior` | M2.6 | `DispenserBehaviorTest` | implemented |
| R12 | Restraint keys | `S:item/restraint/RestraintKeyItem` | M2.1, M2.7 | `EscapeServiceTest` | implemented |
| R13 | Struggling and durability | `S:restraint/EscapeService`, HUD | M2.2, M2.7, M2.10 | `EscapeServiceTest`, `ItemReturnPolicyTest` | implemented |
| R14 | Native lockpicking | `S:lockpick/*` | M3.3 | `LockpickSessionTest`, `LockpickProfileTest` | implemented |
| T01 | Direct escort | `S:tether/EscortTransport` | M4.3 | `TransportArbiterTest`, `EscortHandoverTest` | implemented |
| T02 | Forced seating/mounting | `S:tether/MountTransfer` | M4.4 | `MountTransferTest` | implemented |
| T03 | Chains | `S:tether/TetherService` | M4.1 | `TetherOwnershipTest` | implemented |
| T04 | Fixed anchors | `S:entity/ChainKnotEntity` | M4.2 | `AnchorTransferTest` | implemented |
| T05 | Weighted anchors | `S:entity/WeightedAnchorEntity` | M4.2 | `WeightedAnchorPickupTest` | dropped before release |
| T06 | Tension and suspension damage | `S:tether/TetherDamage`, `mcacrime:hang` | M4.1 | `TetherPhysicsTest` | implemented |
| I01 | Frisking | `S:frisk/*`, `S:menu/FriskingMenu` | M5.2, M5.3 | `FriskTransactionTest`, `ContainerClickRejectionTest` | implemented — reworked before release: opens from the crime menu, seizes into the searcher's inventory, no box |
| I02 | Possessions boxes | `S:inventory/PossessionsStore` | M5.1 | `PossessionsBoundsTest` | dropped before release |
| L01 | Padlocks | `S:entity/PadlockEntity`, `S:locks/LockService` | M3.4 | `PadlockLifecycleTest` | implemented |
| L02 | Bound keys | `S:item/lock/KeyItem` | M3.1, M3.2 | `LockAccessTest` | implemented |
| L03 | Key rings | `S:item/lock/KeyRingItem` | M3.2 | `KeyRingRecipeTest` | implemented |
| L04 | Key copying | `S:recipe/lock/*` | M3.2 | `KeyMoldTest` | implemented |
| L05 | Cell doors | `S:block/CellDoorBlock` | M3.4 | `CellDoorLockTest` | implemented |
| L06 | Safes | `S:block/entity/SafeBlockEntity` | M3.5 | `SafeAutomationTest`, `SafeContentsLifecycleTest` | implemented |
| P01 | Reinforced construction | `S:block/prison/*` | M5.4 | `ReinforcedPolicyTest` | implemented |
| P02 | Pillories | `S:block/PilloryBlock`, `S:detention/DetentionService` | M4.5 | `PilloryOccupancyTest` | implemented |
| P03 | Guillotines | `S:block/entity/GuillotineBlockEntity` | M4.6 | `GuillotineExecutionTest` | implemented |
| P04 | Bunks | `S:block/BunkBlock` | M4.7 | `BunkRespawnTest` | implemented |
| P05 | Posters (seven variants) | `S:block/PosterBlock` | M5.8 | `PosterVariantTest` | dropped before release |
| P06 | Meal trays | `S:block/entity/TrayBlockEntity` | M5.7 | `TrayContentsTest` | dropped before release |
| P07 | Forks, spoons and excavation | `S:block/prison/ExcavationService` | M5.5 | `ExcavationProgressTest` | dropped before release |
| P08 | Knife and wounds | `S:wound/WoundService` | M5.9 | `WoundLifecycleTest` | dropped before release |
| P09 | Bandages | `S:item/tool/BandageItem` | M5.9 | `WoundLifecycleTest` | dropped before release |
| P10 | Prisoner tags | `S:identity/IdentityService` | M5.10 | `NicknameTest` | dropped before release |
| E01 | Imbue | `S:enchantment/ImbueHandler` | M6.1 | `ImbueDistributionTest` | implemented |
| E02 | Famine / Shroud / Exhaust | `S:enchantment/CrimeEnchantments` | M6.1 | `EnchantmentApplicabilityTest` | implemented |
| E03 | Silence | mana adapters | M6.1, M6.2 | `AdapterAbsenceTest` | implemented |
| E04 | Buoyant | `S:entity/WeightedAnchorEntity` | M4.2, M6.1 | `WeightedAnchorPickupTest` | dropped before release |
| E05 | Vanilla enchantments (Unbreaking, Curse of Binding) | `S:restraint/EscapeService`, death path | M2.7, M6.1 | `EscapeServiceTest` | implemented |
| A01 | Creative utility items | `S:item/creative/*` | M2.1 | `CreativeAuthorizationTest` | implemented |
| A02 | Commands and extension API | `S:api/*`, `S:command/*` | M6.4, M6.5 | `ThirdPartyDefinitionTest`, `CrimeCommandTest` | implemented |
| A03 | Presentation and resources | `R:assets/mcacrime/**`, `R:data/mcacrime/**` | M2.10, M2.12, M3.8, M4.9, M5.12 | `LangCoverageTest`, `RestraintResourceCoverageTest`, `LockResourceCoverageTest`, `DetentionResourceCoverageTest`, `PrisonResourceCoverageTest` | implemented |
| A04 | Statistics (19) | `S:stat/CrimeStats` | M5.11 | `StatAwardOnceTest` | implemented |
| C01 | Existing working compat | `S:compat/*` | M6.2 | `AdapterAbsenceTest`, `OptionalClassloadTest` | implemented |

---

## 13. Specification section 5 — dormant content (11)

Dispositions are transcribed from plan section 6.2.

| ID | Entry | Disposition | Destination | Milestone | Acceptance test | Status |
|---|---|---|---|---|---|---|
| D01 | Warden's Guide / booklet | Complete and register an MCA: Crime guide with rewritten text | `S:client/gui/`, `R:assets/mcacrime/` | M6.3 | `LangCoverageTest`, `check_mod.py` | dropped before release |
| D02 | Toilet | Complete as a furnishing with its own registration, storage, saving and drops; no sewage simulation | `S:block/prison/` | M6.3 | `check_mod.py` (model/lang/loot coverage) | dropped before release |
| D03 | Privacy restrictions | Complete as an optional server-enforced policy checked at every entry point | `S:identity/`, `S:restraint/` | M6.3 | `NicknameTest`, `ApplicationTransactionTest` | dropped before release |
| D04 | TacZ | Implement a real version-probed adapter or ship no claim; never set the voice-chat flag from it | `S:compat/` | M6.2 | `AdapterAbsenceTest`, `OptionalClassloadTest` | implemented |
| D05 | Knights of Britannia | Implement against an explicitly supported build or ship no claim; no speculative scoreboard objectives | `S:compat/` | M6.2 | `AdapterAbsenceTest` | implemented |
| D06 | Creative bind breaker | Implement a distinct reset that rotates the binding revision and invalidates old keys | `S:item/creative/` | M6.3 | `LockAccessTest::wrongKeyOldCopyForeignSession` | implemented |
| D07 | Fuzzy cuff scope | Arms-only, tooltip corrected; a leg variant is a documented future enhancement | `S:item/CrimeItems`, lang | M2.1, M6.3 | `RestraintDefinitionsTest`, `LangCoverageTest` | dropped before release |
| D08 | Leg shackles | Walking allowed, sprint/jump blocked, one policy on both sides; lang corrected | `S:restraint/RestraintAttributes`, lang | M2.8, M6.3 | `RestrictionCompositionTest::legShacklesWithGuiToggling` | implemented |
| D09 | Tape leg settings | Independent leg durability and break settings | `S:item/tool/DuckTapeItem` | M2.2 | `RestraintDurabilityTest` | implemented |
| D10 | Lockpicking a door/safe | Both outcomes: configurable non-destructive unlock (default) and the named Cuffed parity destruction; a broken safe moves its contents exactly once first | `S:lockpick/` | M3.3 | `SafeContentsLifecycleTest::pickDestroyUnloadWithContents` | implemented |
| D11 | Reinforced blocks | Honest qualified resistance, with hard containment as a separate explicit policy | `S:block/prison/ReinforcedPolicy`, `ReinforcedBreakingPolicy`, `ReinforcedBlockBehaviour` | M5.4 | `block/ReinforcedPolicyTest` | implemented |

Upstream's §5 entry for leg shackles is only **partly** reproduced as a defect: the reconnaissance
found the Cuffed *code* consistent at the pinned commit and the inconsistency confined to
`en_us.json`. D08 still stands as the disposition.

---

## 14. User-added scope

| ID | Item | Destination | Milestone | Acceptance test | Status |
|---|---|---|---|---|---|
| X01 | Capital sentence for killing a guard (user-scoped) | `S:ledger/SentenceKind`, `S:ledger/CapitalSentenceService`, `S:crime/type/CrimeIds` (`kill_guard`) | M6.6, M6.8 | `CapitalSentenceEligibilityTest`, `CapitalSentenceAssignmentTest`, `CapitalSentenceRefusalTest` | implemented |
| X02 | Execution at a guillotine (user-scoped) | `S:detention/ExecutionAuthorization`, `S:block/entity/GuillotineBlockEntity` | M4.6, M4.10 | `CapitalExecutionAuthorizationTest`, `CapitalDeathOutcomeTest` | implemented |
| X03 | Guard-carried execution (user-scoped) | `S:enforcement/CondemnedEscortService`, `S:facility/FacilityRole.EXECUTION_SITE` | M6.7 | `CondemnedEscortTest` | implemented |

X01-X03 are not part of the Cuffed inventory or of specification sections 4 and 5. The user scoped
them in; the plan specifies them in section 3.19, with steps M4.10, M6.6-M6.8 and M7.7, port steps
P4.3, P6.3 and P7.4, and acceptance tests in plan section 9.3 under heading 21.6. The plan records
section 3.19 as a working interpretation still open to user correction before M6.6.

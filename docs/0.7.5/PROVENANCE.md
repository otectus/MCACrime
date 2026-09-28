# MCA: Crime 0.7.5 — Cuffed provenance manifest

Milestones **M0.3** and **M0.5** of
`docs/MCA_Crime_0.7.5_Cuffed_Integration_Implementation_Plan.md` (the *plan*).

- **Source repository:** `https://github.com/LazrProductions/cuffed.git` (the `1.20.1` branch; the
  README's logo link names that branch).
- **Pinned commit:** `48a336508abda1f4684bd337bc83199a212e5422`.
- **Upstream mod id / version:** `cuffed`, `mod_version=1.3.15` (`gradle.properties:26,30`).

Path prefixes: `U:` = upstream `src/main/` (`U:java/com/lazrproductions/cuffed/` for code,
`U:resources/assets/cuffed/` and `U:resources/data/cuffed/` for resources);
`S:` = `src/main/java/dev/otectus/mcacrime/`; `R:` = `src/main/resources/`.

Nothing in this document reports a check, build or test as having run.

---

## 1. Licence evidence and the confirmed discrepancy

| Evidence | Value |
|---|---|
| `LICENSE` at the pinned commit | GNU General Public License v3 (verbatim FSF text) |
| `README.md:9` | a "License: GPL-3.0" badge linking to `https://www.gnu.org/licenses/gpl-3.0` |
| `gradle.properties:28` | `mod_license=All Rights Reserved` |
| `META-INF/mods.toml:3` | `license="${mod_license}"` — the "All Rights Reserved" string is expanded into every published jar |
| Credits file | **none exists**: no `CREDITS`, no `ATTRIBUTION`, and no credits section in the 25-line `README.md` |
| Attribution present in the repository | `gradle.properties:32` `mod_authors=Lazr Productions`, surfaced through `${mod_authors}` in `META-INF/mods.toml:9` |
| Git authorship | a single author across the whole history: `lazrproductions <laz3rb0y56@gmail.com>` |
| Other "credit" strings | only Blockbench's automatic `"credit": "Made with Blockbench"` in 37 model JSONs, including seven of the eight `models/block/poster*.json` (all but `poster_none.json`) and `fuzzy_handcuffs.json` — this attributes a tool, not a person |

**Stated plainly:** the upstream repository declares GPL-3.0 (LICENSE file and README badge) while
its `gradle.properties` declares `All Rights Reserved`, and that second string is what the published
`mods.toml` and therefore the distributed jar carry. The two declarations contradict each other, and
no credits file exists upstream to resolve authorship of individual assets.

**Policy adopted for 0.7.5 (user decision).**

1. Cuffed-origin **code and art that the repository's GPL-3.0 LICENSE and README cover** are adapted
   into MCA: Crime with attribution (this file and `CREDITS.md`). MCA: Crime itself is GPL-3.0-only
   (`gradle.properties:38` `mod_license=GPL-3.0-only`), so the adapted material stays under a
   compatible licence.
2. Every **unattributed** Cuffed-origin asset — the seven poster textures, the fuzzy worn texture,
   the six `.ogg` sounds, and anything else whose author cannot be determined from the repository —
   is **replaced by original MCA: Crime artwork or by vanilla sounds**, under the same registry id.
   Nothing in this integration waits on contacting the upstream author.
3. Registry ids are stable either way, so a later original-art drop needs no code change
   (plan section 3.11 for the sound indirection).

This supersedes the plan's M0.3 wording that flagged those assets as "blocked pending author
contact": they are not blocked, they are replaced.

---

## 2. Artefact manifest

Every row: source repository `https://github.com/LazrProductions/cuffed.git` at commit
`48a336508abda1f4684bd337bc83199a212e5422`. "Author" is taken from upstream git history where a
person is determinable, otherwise from the repository owner field.

### 2.1 Code

Credited author for every code row: **lazrproductions (`laz3rb0y56@gmail.com`), sole author in
upstream git history; `mod_authors=Lazr Productions`**. Licence evidence for every code row: the
repository `LICENSE` (GPL-3.0) and the `README.md:9` badge, against `gradle.properties:28`
`mod_license=All Rights Reserved` — see section 1.

| Upstream path | Resulting path | Modification |
|---|---|---|
| `U:java/.../init/ModItems.java`, `ModBlocks.java`, `ModBlockEntities.java`, `ModEntityTypes.java`, `ModRestraints.java`, `ModEnchantments.java`, `ModEffects.java`, `ModRecipes.java`, `ModSounds.java`, `ModStatistics.java`, `ModTags.java` | `S:item/CrimeItems`, `S:block/`, `S:entity/`, `S:restraint/RestraintDefinitions`, `S:enchantment/CrimeEnchantments`, `S:effect/`, `S:recipe/`, `S:audio/CrimeSoundEvents`, `S:stat/CrimeStats` | adapted (registry contents and ids; MCA: Crime `DeferredRegister` structure and the three id remappings) |
| `U:java/.../cap/RestrainableCapability.java`, `cap/base/IRestrainableCapability.java` | `S:restraint/` + `S:state/world/CrimeWorldData` tables | **rewritten** — state moves to world SavedData, not a capability (plan section 3.2) |
| `U:java/.../restraints/base/AbstractRestraint.java` and `restraints/custom/*` | `S:restraint/RestraintDefinitions`, `S:restraint/RestraintAttributes`, `S:restraint/EscapeService` | **rewritten** — server-owned durability, no client-supplied delta |
| `U:java/.../restraints/RestraintAPI.java` | `S:restraint/` definition registry | adapted |
| `U:java/.../api/CuffedAPI.java` (`Lockpicking`, lock registry) | `S:lockpick/LockpickService`, `S:locks/LockService` | **rewritten** — server-computed outcome, identity from the connection |
| `U:java/.../client/gui/screen/LockpickingScreen.java` | `S:client/` lockpick screen | adapted (presentation only; no client-decided outcome) |
| `U:java/.../inventory/FriskingMenu.java`, `inventory/FriskingContainer.java` | `S:menu/FriskingMenu`, `S:frisk/*` | **rewritten** — read-only projection plus an explicit transaction |
| `U:java/.../items/PossessionsBox.java` | `S:inventory/PossessionsStore` | adapted |
| `U:java/.../items/KeyItem.java`, `KeyRingItem.java`, key mold items | `S:item/lock/KeyItem`, `S:item/lock/KeyRingItem`, `S:item/lock/` | adapted (UUID equality corrected) |
| `U:java/.../entity/PadlockEntity.java`, `ChainKnotEntity.java`, `WeightedAnchorEntity.java`, `CrumblingBlockEntity` | `S:entity/PadlockEntity`, `S:entity/ChainKnotEntity`, `S:entity/WeightedAnchorEntity`, `S:block/prison/` | adapted (fence test, synched-data owner and drop id corrected) |
| `U:java/.../blocks/CellDoor.java`, `SafeBlock.java`, `PilloryBlock.java`, `GuillotineBlock.java`, `BunkBlock.java`, `TrayBlock.java`, `PosterBlock.java`, `ReinforcedBars*.java`, `blocks/base/*` | `S:block/*` | adapted |
| `U:java/.../blocks/entity/LockableBlockEntity.java`, `SafeBlockEntity.java`, `GuillotineBlockEntity.java`, `BunkBlockEntity.java`, `TrayBlockEntity.java` | `S:block/entity/*` | adapted (guarded NBT reads, persisted chop delay, one authoritative safe-slot profile) |
| `U:java/.../utils/ChainUtils.java` | `S:tether/`, `S:client/render/` chain rendering | adapted |
| `U:java/.../event/ModServerEvents.java` (Imbue distribution, excavation, application flow) | `S:enchantment/ImbueHandler`, `S:block/prison/ExcavationService`, `S:restraint/RestraintService` | **rewritten** — reverse indices instead of world scans, bounded Imbue, no blanket command cancel |
| `U:java/.../effect/WoundedEffect.java`, knife/bandage items | `S:wound/WoundService`, `S:item/tool/BandageItem` | **rewritten** — no damage on effect add/remove |
| `U:java/.../mixin/*` | `S:mixin/` (narrow, vanilla-only) | **rewritten**; `HumanoidModelMixin` and the `onCommand` handler are **not ported** (plan section 6.3) |
| `U:java/.../compat/*` | `S:compat/` | **rewritten** as runtime-degrading adapters; empty or speculative adapters are not carried over |
| `U:java/.../config/CuffedServerConfig.java` | `S:McaCrimeConfig` (COMMON spec) | adapted; dead keys dropped, duplicated inner classes not carried over |

### 2.2 Resources adapted or reproduced

| Upstream path | Resulting path | Author | Modification |
|---|---|---|---|
| `U:resources/data/cuffed/recipes/*.json` (43 files) | `R:data/mcacrime/recipes/*.json` | lazrproductions | adapted (namespace and the three item-id remappings) |
| `U:resources/data/cuffed/loot_tables/blocks/*.json` (14 active) | `R:data/mcacrime/loot_tables/blocks/*.json` | lazrproductions | adapted; the missing `tray` loot table is authored new; `toilet.json.txt` stays disabled until D02 |
| `U:resources/data/cuffed/tags/**` (4 cuffed tags) | `R:data/mcacrime/tags/**` | lazrproductions | adapted |
| `U:resources/data/cuffed/damage_type/hang.json` | `R:data/mcacrime/damage_type/hang.json` | lazrproductions | adapted; **not** added to `is_explosion` or `is_drowning` (plan section 3.13) |
| `U:resources/assets/cuffed/particles/blood_drip.json` | `R:assets/mcacrime/particles/blood_drip.json` | lazrproductions | adapted |
| `U:resources/assets/cuffed/models/item/*.json`, `models/block/*.json`, `blockstates/*.json` | `R:assets/mcacrime/models/**`, `R:assets/mcacrime/blockstates/**` | lazrproductions (Blockbench-authored; `"credit": "Made with Blockbench"` names the tool only) | adapted; the `key_ring_2` missing-texture fallback and the `guillotine_openjson` filename typo are corrected |
| `U:resources/assets/cuffed/lang/en_us.json` (257 keys) | `R:assets/mcacrime/lang/en_us.json` | lazrproductions | **rewritten** — MCA: Crime wording, corrected fuzzy-cuff and leg-shackle text; `ru_ru`/`zh_cn` are not carried over |
| `U:resources/assets/cuffed/textures/**` | see section 3 | see section 3 | see section 3 |
| `U:resources/assets/cuffed/sounds/**` (6 `.ogg`) | **not copied** | not recorded upstream | **replaced by original** (vanilla `SoundEvent`s for now) |
| `U:resources/assets/cuffed/sounds.json` | **not copied** | lazrproductions | exists upstream and declares all six events (`restraint.apply_handcuffs`, `restraint.apply_shackles`, `block.pillory.use`, `block.guillotine.use`, `block.safe.open`, `block.safe.close`), each pointing at an `.ogg` that ships; because none of those `.ogg` files is carried over, `R:assets/mcacrime/sounds.json` is authored new and, instead of naming files, redirects each id to a vanilla sound event |

Upstream has no `advancements/` directory, so nothing is carried from one.

---

## 3. Art buckets (M0.5)

### 3.1 Bucket (a) — protected MCA: Crime art, unchanged

These two files are MCA: Crime's own and must stay byte-identical. They are already attached to the
two remapped item ids.

| Path | SHA-256 (from the plan, section 1.3) |
|---|---|
| `R:assets/mcacrime/textures/item/restraint_cuffs.png` | `df1a7d30af0e4795cf706c94a4b37036dd748c4f9c750b793550daeb6f6997f4` |
| `R:assets/mcacrime/textures/item/restraint_locked_cuffs.png` | `b0a86e25652e6d1acc7b2d1cf58a4be73b9e3bc4218760917337e324fd7d084f` |

The upstream icons they replace (`U:resources/assets/cuffed/textures/item/handcuffs.png` and
`shackles.png`) are **not** copied. Milestone M0.4 adds a hash gate for both files; this document does
not claim that gate exists or has run.

### 3.2 Bucket (b) — Cuffed-origin art taken under GPL-3.0 with attribution

**Every file in this bucket that has actually been copied is a byte-for-byte copy of the upstream
PNG.** Nothing here is recoloured, re-cut or repainted: the only changes on copy are the namespace
the file is addressed in and, in the four cases named below, its filename. That is deliberate — the
models and UV layouts these images belong to are adapted too, and repainting a sheet without
reauthoring its geometry samples the wrong pixels. Credited to LazrProductions in `CREDITS.md`.

Item textures (`U:resources/assets/cuffed/textures/item/` → `R:assets/mcacrime/textures/item/`):
`key.png`, `key_ring.png`, `key_ring_0.png`, `key_ring_1.png`, `key_ring_3.png`, `key_ring_4.png`,
`key_mold.png`, `baked_key_mold.png`, `handcuff_key.png`, `shackles_key.png`, `padlock.png`,
`reinforced_padlock.png`, `lockpick.png`, `prisoner_tag.png`, `fork.png`, `spoon.png`, `knife.png`,
`bandage.png`, `duck_tape.png`, `weighted_anchor.png`, `empty_box.png`, `content_box.png`,
`cell_door.png`, `tray.png`, `tray_filled.png`, `leg_shackles.png`, `creative_key.png`,
`creative_bind_breaker.png`, `creative_restraint_cutter.png`, `poster.png` (the blank rolled-poster
icon behind `models/item/poster.json`'s default `layer0`; it is not one of the seven unattributed
artwork variants, which are bucket (c)).

Block textures (`…/textures/block/` → `R:assets/mcacrime/textures/block/`): `bunk.png`,
`cell_door_top.png`, `cell_door_bottom.png`, `chiseled_reinforced_stone.png`,
`chiseled_reinforced_stone_top.png`, `chiseled_reinforced_stone_bottom.png`, `guillotine.png`,
`pillory.png`, `possessions_box.png`, `reinforced_bars_top.png`, `reinforced_bars_middle.png`,
`reinforced_bars_bottom.png`, `reinforced_bars_gapped.png`, `reinforced_lamp.png`,
`reinforced_smooth_stone.png`, `reinforced_stone.png`, `reinforced_stone_top.png`,
`safe_closed.png`, `safe_open.png`, `safe_side.png`, `tray.png`.

Entity textures (`…/textures/entity/` → `R:assets/mcacrime/textures/entity/`): `chain_knot.png`,
`padlock.png`, `reinforced_padlock.png`, `weighted_anchor.png`, `crumbling_block_1.png` …
`crumbling_block_4.png`, and two **worn** textures, `bundle.png` and `duck_tape.png` — the hood and
tape worn art is adapted from upstream because MCA: Crime has no original equivalent for it. The
worn `handcuffs.png` and `shackles.png` are **not** adapted; they are bucket (c), because the worn
rig must match MCA: Crime's own cuff icons in bucket (a).

GUI textures (`…/textures/gui/` → `R:assets/mcacrime/textures/gui/`): `container/frisking.png`,
`container/possessions_box.png`, `container/tray.png`, `bundle_overlay.png`, `chained_bar.png`,
`chained_bar_hollow.png`, `progress_bar.png`, `widgets.png`. **None of these has been copied**, and
in particular no `gui/container/*.png` screen background is present in
`R:assets/mcacrime/textures/gui/` (which holds only MCA: Crime's own `panel.png`): every screen this
release ships is drawn from `CrimeSprites`, so a second chrome set would be a second thing to keep in
step. The rows stay in the manifest to record what was available and refused.

Mob-effect textures: `mob_effect/restrained.png`, `mob_effect/wounded.png`.

**Two renames on copy.** Upstream's file name and MCA: Crime's registry id differ in two places, so
the copy is renamed rather than the id bent to match a file:
`item/handcuff_key.png` → `R:assets/mcacrime/textures/item/handcuffs_key.png` (the item id is
`mcacrime:handcuffs_key`, plural, matching the family it opens), and
`item/empty_box.png` → `R:assets/mcacrime/textures/item/possessions_box.png` (the item id is
`mcacrime:possessions_box`; upstream's `content_box.png` is its filled state and is carried in M5
with the container that switches between them).

**Copied so far (milestone M3).** Block textures `cell_door_top.png`, `cell_door_bottom.png`,
`safe_closed.png` and `safe_open.png`; the item texture `cell_door.png`; the mob-effect texture
`mob_effect/restrained.png`. With them, adapted under the same terms: the sixteen `cell_door_*`
block models, `safe_closed.json` and `safe_open.json`, the `cell_door` and `safe` blockstates and
item models, and the `cell_door`/`safe` block loot tables — all namespace-rewritten, and the safe
models' bogus `"parent": "safe"` line (a Blockbench artefact naming a model that does not exist) is
dropped rather than carried. `safe_side.png` is **not** copied: no model in this set samples it.

**Copied so far (milestone M4).** Block textures `pillory.png`, `guillotine.png` and `bunk.png`.
With them, adapted under the same terms and namespace-rewritten: the block models `pillory_base`,
`pillory_open`, `pillory_closed`, `pillory_item`, `guillotine_closed`, `guillotine_closed_bloody`,
`guillotine_open_bloody`, `bunk_left` and `bunk_right`, the item models `guillotine`, `bunk` and
`weighted_anchor`, the four crafting recipes `pillory.json`, `guillotine.json`, `bunk.json` and
`weighted_anchor.json` (upstream ingredients unchanged), the three block loot tables, and the
`chainable_entities` entity-type tag. **One rename on copy:** upstream's `models/block/guillotine_openjson.json`
is a typo for `guillotine_open.json` and is copied under the corrected name; the file it should have
been called is what the blockstate names. **Not copied:** upstream's `models/block/guillotine.json`
(the bladeless frame its block-entity renderer draws over) — MCA: Crime drives the blade and the
bloody state from blockstate properties instead, so the four open/closed × clean/bloody models are the
whole set and a fifth would never be selected. `minecraft:player` and `minecraft:villager` are dropped
from the copied `chainable_entities` values because both are chainable in code; a tag that could
remove them would leave a chained prisoner with no route off the chain.

**Bucket (b) not taken for M4's entities.** `entity/chain_knot.png` and `entity/weighted_anchor.png`
are listed above and were **not** copied: following the M3.4 padlock precedent, the chain knot draws as
a vanilla chain item and the weighted anchor as its own bucket (b) item model, so neither needs an
entity texture. Both rows stay in the manifest for a future bespoke entity model.

**Copied so far (milestone M2).** Item textures `key.png`, `key_ring.png`, `key_ring_0.png`,
`key_ring_1.png`, `key_ring_3.png`, `key_ring_4.png`, `key_mold.png`, `baked_key_mold.png`,
`handcuff_key.png` (as `handcuffs_key.png`), `shackles_key.png`, `padlock.png`, `lockpick.png`,
`prisoner_tag.png`, `fork.png`, `spoon.png`, `knife.png`, `bandage.png`, `duck_tape.png`,
`weighted_anchor.png`, `empty_box.png` (as `possessions_box.png`), `creative_key.png`,
`creative_bind_breaker.png`, `creative_restraint_cutter.png`; worn entity textures `bundle.png` and
`duck_tape.png`, into `R:assets/mcacrime/textures/entity/restraint/`. The remaining bucket (b) rows
above are copied by the milestone that registers the block, entity or screen that uses them.

**Copied so far (milestone M6).** Entity textures `crumbling_block_1.png` … `crumbling_block_4.png`,
into `R:assets/mcacrime/textures/entity/` — all four are present in the tree and are verbatim copies,
unchanged but for the namespace they are addressed in. They are the four excavation stages
`S:client/render/CrumblingBlockRenderer` draws over a wall a prisoner is scraping at, adapted under
the same terms and unmodified except for the namespace they are addressed in. Nothing else was
copied for M6: the blood-drip particle definition names two **vanilla** drip textures, so its client
provider (`S:client/particle/BloodDripParticle`, registered in M6) ships no image of its own, and the
enchantments (six then, five after the trim in section 5) are text and code with no art at all.

**Copied so far (milestone M5).** Block textures `reinforced_stone.png`, `reinforced_stone_top.png`,
`reinforced_smooth_stone.png`, `chiseled_reinforced_stone.png`, `chiseled_reinforced_stone_top.png`,
`chiseled_reinforced_stone_bottom.png`, `reinforced_lamp.png`, `reinforced_bars_top.png`,
`reinforced_bars_middle.png`, `reinforced_bars_bottom.png`, `reinforced_bars_gapped.png` and
`tray.png`; item textures `tray.png`, `tray_filled.png`, `poster.png` (the blank rolled-poster icon,
which is *not* one of the seven artwork variants) and `content_box.png` **renamed on copy** to
`possessions_box_filled.png`, because MCA: Crime's box has one id and two states rather than two
item names; and the mob-effect texture `mob_effect/wounded.png`.

With them, adapted under the same terms and namespace-rewritten: the nine blockstates
`reinforced_stone`, `reinforced_smooth_stone`, `chiseled_reinforced_stone`, `reinforced_lamp`,
`reinforced_stone_slab`, `reinforced_stone_stairs`, `reinforced_bars`, `reinforced_bars_gap` and
`tray`; the block models for all of those plus the eighteen `reinforced_bars_{bottom,middle,top}_*`
connection models and `reinforced_stone_slab_top`, `reinforced_stone_stairs_inner` and
`_outer`; the ten item models listed in section 2.2; the eight block loot tables plus `poster.json`;
the twenty-one recipe files of Appendix A.4 rows A4-23 to A4-43; the `reinforced_blocks` block tag;
and the `particles/blood_drip.json` definition, which names two vanilla drip textures and so ships
no texture of its own.

**Authored new in M5, not adapted:** `R:data/mcacrime/loot_tables/blocks/tray.json`. Upstream ships
fourteen block loot tables and has none for the tray, so a broken tray upstream leaves nothing at
all; this one drops the tray, and its *contents* are spilled by the block entity rather than by the
table, so neither can drop the same meal twice.

**Bucket (b) not taken for M5.** `textures/block/possessions_box.png`: no model in this set samples
it — the box is an item, and its two icons are `item/possessions_box.png` (bucket (b), copied in M2)
and `item/possessions_box_filled.png` above. The row stays in the manifest for a future placed-box
block. Also not taken: the `gui/container/*.png` screen backgrounds and `gui/widgets.png`, because
the frisking screen is drawn from `CrimeSprites` like every other screen this mod ships, and a second
chrome set would be a second thing to keep in step.

**Worn geometry.** The tape and hood worn models in
`S:client/render/restraint/RestraintModels` are adapted from upstream's `DuckTapeArmsModel`,
`DuckTapeLegsModel`, `DuckTapeHeadModel` and `BundleModel`: the same cube sizes and the same texture
offsets, because the textures they sample are the adapted ones above and geometry authored against a
different sheet would sample the wrong pixels. The cuff, shackle, leg-cuff, leg-shackle and fuzzy
geometry in the same file is bucket (c), authored against the UV layout
`tools/art/generate_restraint_art.py` draws.

Not carried over from upstream: the orphaned `entity/cupcake.png`, `entity/test.png`,
`entity/chained_overlay.png`, `item/master_key.png`, the `.aseprite` working files, the dormant
toilet textures (until D02 authors its own) and both booklet textures, `gui/information_booklet.png`
and `item/information_booklet.png` — the plan completes and registers an MCA: Crime guide with
rewritten text (plan sections 5/6.2, milestone M6.3), so both are bucket (c), authored by D01.

### 3.3 Bucket (c) — art MCA: Crime authors itself

Original MCA: Crime work under the same registry ids. No upstream file is copied for any row here.

| What | Resulting path / target | Reason |
|---|---|---|
| Seven poster variants | `R:assets/mcacrime/textures/block/poster/*.png` and the matching `R:assets/mcacrime/textures/item/poster_*.png` | upstream `poster_serenity`, `poster_skeleton`, `poster_impunity`, `poster_zooom`, `poster_ashadowlockedaway`, `poster_prisoner`, `poster_lantern` (plus `poster_none`) carry **no attribution**; their names read as third-party artwork titles and no artist is named anywhere in the repository — **replaced by original** |
| Fuzzy worn texture | `R:assets/mcacrime/textures/entity/restraint/fuzzy_handcuffs.png` and the item icon `R:assets/mcacrime/textures/item/fuzzy_handcuffs.png`; both drawn by `tools/art/generate_restraint_art.py` | the only distinct fuzzy artwork upstream, tied to a supporter entitlement and unattributed — **replaced by original**. There is no distinct upstream inventory icon: `models/item/fuzzy_handcuffs.json` reuses the handcuffs texture; MCA: Crime authors an item icon too |
| Six sounds | `mcacrime:restraint.apply_handcuffs`, `restraint.apply_shackles`, `block.pillory.use`, `block.guillotine.use`, `block.safe.open`, `block.safe.close` | upstream's `sounds.json` declares all six events and each `.ogg` ships, but the audio itself is unattributed — **replaced by vanilla**: the authored `R:assets/mcacrime/sounds.json` redirects each id to a named vanilla `SoundEvent` with `"type": "event"`, plus a subtitle, and ships no audio file, until MCA: Crime authors its own |
| Worn restraint entity textures | `R:assets/mcacrime/textures/entity/restraint/` worn set replacing the deleted `R:assets/mcacrime/textures/entity/cuffs.png`; drawn by `tools/art/generate_restraint_art.py` | the nine-model worn rig (plan section 3.15) needs textures the single legacy `cuffs.png` cannot supply. **Authored here:** the worn cuff and shackle art, replacing upstream `entity/handcuffs.png`, `entity/shackles.png` and `entity/fuzzy_handcuffs.png`, so the worn models match MCA: Crime's own bucket (a) cuff icons. **Adapted instead (bucket (b)):** the worn `entity/bundle.png` and `entity/duck_tape.png` |
| New HUD sprites | the `CrimeSprites` sheet, regenerated by `tools/gui/generate_gui_sheet.py` | struggle progress, lockpick progress and tether/escort indicators. Upstream's 54-frame `gui/interuptbar/` frame set is **not** copied; MCA: Crime authors its own sheet entries |
| Guide and toilet art | `R:assets/mcacrime/textures/item/warden_guide.png`, `textures/block/toilet.png`, `textures/block/toilet_top.png`; all three drawn by `tools/art/generate_dormant_art.py` (D01, D02, milestone M6.3) | the dormant upstream booklet art — both `gui/information_booklet.png` and `item/information_booklet.png` — and the toilet art are not reused; both completions author their own. The script opens no upstream file at all and has the same `--check` mode as the poster and restraint generators, which is what makes the originality of these three PNGs checkable rather than asserted |

The regenerated sheet is checked with `tools/gui/generate_gui_sheet.py --check` at M0.5 — a
*proposed* check; it has not been run for this document.

The bucket (c) restraint art is drawn by `tools/art/generate_restraint_art.py` with a `--check`
mode. The eight poster sheets were drawn the same way by `tools/art/generate_poster_art.py` (M5.8)
until the poster went in the trim of section 5; that script and `PosterVariantTest` went with it.
The restraint script reads MCA: Crime's own two protected icons to sample their steel palette and
writes nothing to them; no upstream file is read by it at all, which is what makes the originality
of its PNGs checkable rather than asserted.

---

## 4. Summary of the distribution position

MCA: Crime ships GPL-3.0-only (`gradle.properties:38`). Adapted Cuffed code and the bucket (b) art
are taken under the upstream repository's GPL-3.0 LICENSE and README badge, with attribution in
`CREDITS.md` and in this manifest. The contradictory `mod_license=All Rights Reserved` line in
upstream `gradle.properties` is recorded above and is the reason every unattributed asset — posters,
fuzzy artwork and sounds — is replaced by original MCA: Crime work rather than copied. No credits
file exists upstream, so no individual artist is named beyond what the repository itself attributes.

## 5. Removed before release

The manifest above describes the tree as it stood when the transfer finished. Before release, the
following Cuffed-derived artefacts were deleted from the repository again, together with the
MCA: Crime code and art that existed only for them. Rows above that name them are historical.

- **Bucket (b) art deleted:** the item icons `bandage.png`, `knife.png`, `fork.png`, `spoon.png`,
  `prisoner_tag.png`, `possessions_box.png` (upstream `empty_box.png`), `possessions_box_filled.png`
  (upstream `content_box.png`), `tray.png`, `tray_filled.png`, `weighted_anchor.png` and the blank
  rolled-poster icon `poster.png`; the block texture `tray.png`; the four
  `entity/crumbling_block_*.png` stage textures; and the adapted tray, poster and toilet models,
  blockstates and loot tables.
- **Bucket (c) art deleted:** the fuzzy item and worn textures, the seven poster sheets, the warden's
  guide icon and the two toilet textures. `tools/art/generate_poster_art.py` and
  `tools/art/generate_dormant_art.py` are deleted with their outputs;
  `tools/art/generate_restraint_art.py` keeps only the worn handcuff and shackle sheets and its
  `--check` mode still proves them.
- **Adapted code deleted:** `inventory/PossessionsStore`, `entity/WeightedAnchorEntity`,
  `entity/CrumblingBlockEntity`, `block/TrayBlock`, `block/PosterBlock`, `block/ToiletBlock`, their
  block entities, `wound/WoundService`, `item/tool/BandageItem` and the identity package.
- **Kept:** everything else in sections 2 and 3, including the key, key-ring and key-mold designs,
  the padlock, safe, cell door, pillory, guillotine and bunk, and the worn handcuff, shackle, tape
  and hood art.

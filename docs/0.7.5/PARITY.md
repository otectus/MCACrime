# NeoForge 1.21.1 parity — 0.7.5

Reference: the Forge 1.20.1 MCA: Crime 0.7.5 working tree at `/home/otectus/Projects/MCACrime` and
its [CHANGELOG](../../CHANGELOG.md) entry for this release. This document records what the NeoForge
1.21.1 port includes, how the platform supplies it, and where the two lines deliberately differ.
Everything below was read out of this port's own source, or out of a check that ran against it.

## Included behaviour, by milestone

| Milestone | What landed on this line |
|---|---|
| P1 | The physical restraint engine: three independent slots (head, arms, legs), `AppliedRestraint` with its own item snapshot, durability, applier and provenance, composed restriction policy, schema 15 tables and the pure `v14to15` step plus the idempotent `RestraintMigrationReconciler`. |
| P2 | Keys, key rings, key molds, the bind breaker, and server-owned struggle work in `restraint/EscapeService` with per-player input rate limiting. |
| P3 | Locks and native lockpicking: `locks/LockService`, padlocks, the safe, the cell door, the automation policy, and `lockpick/LockpickService` as a server-decided mini-game needing neither Cuffed nor Locks Reforged. |
| P4 | Transport and detention: `tether/TetherService` (chains, fence and tripwire-hook knots, escort), and the pillory, guillotine and bunk devices with persisted occupancy and activation delay. |
| P5 | Frisking (now opened from the crime menu, seizing into the searcher's inventory or the property escrow) and the reinforced building set. The possessions box, excavation, trays and utensils, posters, prisoner tags and identity privacy, wounds and bandages were transferred and then removed before release. |
| P6 | The five restraint enchantments, the statistics set, and capital sentencing (`sentencing.capitalPunishment`), as reported by the implementing pass. The warden guide, the toilet and the Buoyant enchantment were transferred and then removed before release. |
| P7 | M7 parity work, as reported by the implementing pass: the two presets, the config renames and retirements, session cancellation on reload, restraint profiles, the public API v2 surface, item display renames and the documentation in this release. |

## Native platform handling, per area

| Area | How this line does it |
|---|---|
| Player state | Data attachments (`state/CrimeAttachments`, `AttachmentType.serializable(...).copyOnDeath()`). There is no capability provider, no `LazyOptional` and no clone event wiring for the copy. |
| Block inventories | NeoForge block capabilities plus explicit `invalidateCapabilities` for the safe. |
| Menus | `IMenuTypeExtension` for registration and `player.openMenu(MenuProvider, …)` for opening; no `NetworkHooks`. |
| Networking | NeoForge's common-side `CustomPacketPayload` API with stream codecs, routed to the client through `CrimeClientPayloadRouter`, which common code names but never imports. |
| Item state | Four registered `DataComponentType`s (below) in `state/CrimeDataComponents`, in place of the baseline's inline item-NBT reads in `item/lock/KeyRingItem.java` and `item/lock/KeyMoldItem.java`. 1.21.1 has no item NBT. |
| Enchantments | Registry **data** entries: `data/mcacrime/enchantment/*.json` plus `ResourceKey` constants, with availability expressed by vanilla enchantment tags. |
| Chain knot entity | `entity/ChainKnotEntity` extends `BlockAttachedEntity` — what vanilla's own `LeashFenceKnotEntity` extends in 1.21.1 — where the baseline's extends `HangingEntity`. |
| Mixin configs | Both declared by `[[mixins]]` blocks in `META-INF/neoforge.mods.toml`; NeoForge does not honour the Forge-era `MixinConfigs` manifest attribute. `checkJarContents` asserts exactly two configs and no refmap in either. Every mixin is `remap = false` — NeoForge 1.21.1 runs on Mojang names. |
| Reload listeners | `AddReloadListenerEvent` for the restraint profile loader, `OnDatapackSyncEvent` for its sync. |
| Data paths | 1.21 singular directories: `data/<ns>/recipe/`, `loot_table/`, `tags/block`, `tags/item`, `tags/entity_type`, `tags/enchantment`. |

## Schema, protocol and components

- World schema: `CrimeDataMigrations.CURRENT_SCHEMA = SCHEMA_CUFFED_PHYSICAL = **15**`
  (`src/main/java/dev/otectus/mcacrime/state/world/CrimeDataMigrations.java:113-114`).
- Packet protocol: `CrimeNetwork.PROTOCOL_VERSION = **"15"**`
  (`src/main/java/dev/otectus/mcacrime/network/CrimeNetwork.java:44`). Identical to the Forge line by
  design; it still does not make the two loaders compatible, and a Forge 1.20.1 client cannot join.
- The four `DataComponentType`s in `state/CrimeDataComponents`: `lock_binding`, `key_ring_contents`,
  `key_mold_binding`, `key_mold_quality`. The `possessions_contents`, `possessions_identity`,
  `tray_contents` and `poster_variant` components went with their items before release.

## Test counts per milestone

| Milestone | JUnit | GameTests | Verified independently |
|---|---|---|---|
| P1 | 2143, 0 failures, 9 skipped | 33 | yes |
| P2 | 2192, 0 failures, 9 skipped | 35 | yes |
| P3 | 2291, 0 failures, 9 skipped | 40 | yes |
| P4 | 2440, 0 failures, 9 skipped | 49 | yes |
| P5 | Frisking (now opened from the crime menu, seizing into the searcher's inventory or the property escrow) and the reinforced building set. The possessions box, excavation, trays and utensils, posters, prisoner tags and identity privacy, wounds and bandages were transferred and then removed before release. |
| P6 | The five restraint enchantments, the statistics set, and capital sentencing (`sentencing.capitalPunishment`), as reported by the implementing pass. The warden guide, the toilet and the Buoyant enchantment were transferred and then removed before release. |
| P7 | folded into the final run below | 63 | yes, through the final run |
| Final | 2693, 0 failures, 9 skipped | 63 ("All 63 required tests passed") | yes |

Packaging, from the final verification run: `build/libs/mcacrime-0.7.5.jar`, 3,138,591 bytes,
`checkJarContents` clean over 1960 entries — nothing shaded, no Forge or relocated-MCA reference, no
refmap in either mixin config, and both protected textures byte-identical inside the jar. The jar was
3,138,156 bytes at the verification run immediately before the sound fix; the sounds.json addition
accounts for the difference. The JUnit total went 2692 → 2693 with that fix, and
`ConfigGroupCoverageTest` — the documentation gate that failed during P7 — now passes with its
exemption list removed and all 75 keys documented.

**Launches performed for 0.7.5 on this line.** A dedicated server run reached `Done (5.134s)!` with
mcacrime 0.7.5 on MC 1.21.1 / NeoForge 21.1.248 and mca 7.7.36-beta.3, both mixin configs selected,
nine mixins applied with no apply failure, no client class loaded on the server, and `mcacrime.dat`
written with a schema root. *Caveat:* Gradle did not forward the piped `stop`, so the save was taken
on the timeout's SIGTERM — it was clean, but it was not an operator-issued shutdown. A client run
loaded 0.7.5, applied `client.RestraintPoseMixin`, registered the `blood_drip` particle provider,
reported no model, texture, sprite, renderer or keybind error, completed a resource reload and idled
at the title screen. *Caveat:* there was no visual confirmation — nothing in world was rendered or
interacted with.

**Sound-event redirects.** `assets/mcacrime/sounds.json` is byte-identical to the baseline's: seven
`"type": "event"` redirects (`restraint.apply_handcuffs` → `minecraft:block.chain.place`,
`restraint.apply_shackles` → `block.iron_trapdoor.close`, `block.pillory.use` →
`block.wooden_trapdoor.close`, `block.guillotine.use` → `block.iron_trapdoor.close`,
`block.guillotine.arm` → `block.bell.resonate`, `block.safe.open`/`close` →
`block.iron_door.open`/`close`), with seven subtitle keys (none existed before 0.7.5) and `SoundResourceCoverageTest` guarding
the set. Before it, the client logged seven `Missing sound for event: mcacrime:…` warnings; a bounded
client relaunch after it showed none.

**Restraint profile cap.** `RestraintProfileLoader.install` refuses a profile set of more than 64
entries whole, matching the accept-or-refuse-whole rule the rest of the loader follows.

**Warnings seen, and their assessment.** `ConfigValidator` logs a shipped-default mismatch on every
fresh install — `guardThiefResponseRadius` 24.0 against `guardAggroRadius` 16.0. That is
**pre-existing and identical on both lines**, not a 0.7.5 regression. The remaining lines were the
`criminalJobs.presentThiefAsMcaProfession` deprecation notice, one scenario-driven GameTest line, and
the seven missing-sound warnings the sound fix removed.

Static audits over the tree found no duplicate event registration across 67 subscriber classes,
legacy names only in javadoc, and 16 `DeferredRegister`s each bound once.

## Intentional divergences from the Forge 1.20.1 line

1. **The enchanting-table gate.** The baseline's five enchantment classes answer
   `canApplyAtEnchantingTable`, `isDiscoverable` (true *while* `enchantments.allowed` lists them),
   `isTradeable` (false) and `isTreasureOnly` (never). On 1.21 those answers are tag memberships, so
   this port ships all five in `#minecraft:in_enchanting_table` and `#minecraft:on_random_loot`, and in
   no `tradeable`, `treasure`, `non_treasure`, `double_trade_price` or `curse` file — vanilla folds
   `#minecraft:non_treasure` into the trade, mob-spawn-equipment and traded-equipment tags, so joining
   it would put prison equipment on a librarian's trade table. A tag cannot read
   `enchantments.allowed`, so the table or a loot roll may still **offer** a disallowed enchantment.
   The **effect** stays gated exactly as on the baseline: every service asks
   `enchantment/EnchantmentApplicability` first, which is also what the baseline does for an
   enchantment already on an item when an operator removes it from the list. Asserted by
   `src/test/java/dev/otectus/mcacrime/enchantment/EnchantmentAvailabilityTagsTest.java`.
2. **`BlockAttachedEntity` as the chain knot's parent**, in place of the baseline's `HangingEntity`.
   It is what vanilla's `LeashFenceKnotEntity` extends in 1.21.1; same behaviour.
3. **C2S enum refusal.** Every C2S payload decodes enums through `CrimeStreamCodecs.enumCodec`, which
   **refuses** an ordinal no build ever wrote rather than falling back to a default value.
4. **`mixin/HopperLockMixin` targets** `HopperBlockEntity.suckInItems` and `ejectItems` (HEAD,
   cancellable), which is where 1.21.1 performs the transfer; the baseline's target does not exist
   here.
5. **Utensil recipe results.** 1.21.1 refuses a recipe whose result count exceeds the result item's
   maximum stack size outright, so the upstream four-yield utensil recipes would never have loaded at
   all. Those items are gone from both lines; `RecipeResultStackSizeTest` still guards the rule for
   every remaining recipe, as the baseline's does with a milder symptom on 1.20.1.

## Baseline classes this port deliberately omits

`state/CrimeCapabilities`, `state/PlayerCrimeDataProvider`, `state/CrimeCapabilityEvents`; the
`LazyOptional` capability wrappers; `IForgeMenuType` menu registration; `NetworkHooks.openScreen` and
`NetworkHooks.getEntitySpawningPacket`; the jar-manifest `MixinConfigs` attribute; the inline item
NBT in `item/lock/KeyRingItem.java`, `item/lock/KeyMoldItem.java` and `item/PosterBlockItem.java`;
the nested `CrimeEnchantment` class in `enchantment/CrimeEnchantments.java` with its two custom
`EnchantmentCategory` values; and `HangingEntity` as the chain knot's parent. Each is replaced
structurally by the platform equivalent named in
*Native platform handling* above, and each is listed with its reason in the 0.7.5
[CHANGELOG](../../CHANGELOG.md) *Notes* section.

## Capital sentencing

Capital sentencing is **present on both lines with identical defaults**. The only capital offence is
killing a guard (`mcacrime:kill_guard`); nothing escalates automatically; execution is always a
deliberate act at a guillotine by a player or an on-duty guard; with no usable guillotine the
condemned stays in custody; pardon and commutation are the only legal exits; and
`sentencing.capitalPunishment.enabled` switches the whole group off. It is a user-scoped feature
rather than Cuffed parity.

## Preset divergence, shared with the baseline

`lockpicking.destructiveOutcome` ships `false` while the `CUFFED_PARITY` preset holds `true`. That is
the one documented divergence between shipped defaults and the parity preset, and it is **the same on
both lines**; each line's preset test asserts it. If the baseline ever aligns its shipped default,
this port needs the same one-line change and the same test update.

## Remaining live checks

The dedicated-server and client launches above were both automated and bounded. Restraint rendering
and the HUD restraint panel, the lockpicking mini-game's client behaviour, device interaction, and a
whole-world save/reload of the five new physical tables have still not been exercised interactively,
and the client session produced no visual confirmation.

## Removed before release, on both lines

Bandage, knife, fork, spoon, prisoner tag, possessions box, fuzzy handcuffs, meal tray, poster,
warden's guide, weighted anchor and toilet were removed from both lines before 0.7.5 shipped, with
the wound, excavation, identity/consent and Buoyant systems that existed only for them. Frisking
stays and opens from the crime menu (`action/handler/FriskActionHandler`); what it takes goes to the
searcher's own inventory (`frisk/InventoryCapacity` asks for the whole count first, through
`ItemStack.isSameItemSameComponents` on this line) or into the property escrow for a lawful search.
Keys, key rings, key molds, padlocks, the safe, the cell door and lockpicking stay.

Platform notes for the trim: the Buoyant datapack entry, its `enchantable/anchor` item tag, its
`exclusive_set/anchor` enchantment tag and its rows in the vanilla `in_enchanting_table` and
`on_random_loot` tags are deleted here, where the baseline deletes a `RegistryObject`; the
`possessions_contents`, `possessions_identity`, `tray_contents` and `poster_variant` data components
are gone from `state/CrimeDataComponents` (the lock, ring and mold components stay). This line's protocol moves 15 → 16 for the dropped
warden-guide payload; the Forge line is at 18 because it also carries the player-report payloads
that are not on this line yet.

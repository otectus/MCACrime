# S4 — Mask catalogue and customization

The 0.7.2 mask collection: sixteen styles in four material families, one tint model, and one
protected path by which a mask becomes a different mask without becoming a different item.

Covers spec §3 invariants 8 and 9, §4, §5, §7.2–§7.4, §14.4, §19.1–§19.2 and MASK-01…12.

## The catalogue

Style ids are the item's own registry id. They are persisted; enum ordinals never are
(`MaskVariant.styleId()`, spec §5.1). `clay_mask` and `leather_mask` keep their 0.7.0 ids, their
0.7.0 wear budgets and their 0.7.0 crafting-table recipes, so every saved stack, datapack reference
and recipe that named them still resolves (§5.5).

| Family | Wear | Craft cost (per style) | Restyle binding | Styles (`sort_order` 0…3) |
|---|---|---|---|---|
| Cloth | 48 | 1 `#minecraft:wool` + 1 string | 1 string | `bandana`, `highwaymans_domino`, `wrapped_scarf`, `half_veil` |
| Leather | 192 | 2 leather + 1 string | 1 string | `leather_mask` (Cutpurse), `raven_mask`, `jackal_mask`, `stitched_mask` |
| Clay | 64 | 4 clay balls + 2 string | 1 string | `hockey_mask`, `clay_mask` (Blank), `comedy_mask`, `tragedy_mask` |
| Metal | 256 | 2 iron ingots + 1 leather | 1 leather | `iron_skull_mask`, `brigand_visor`, `owl_mask`, `blank_iron_mask` |

Wear budgets are a documented design choice, not a measurement. Clay's 64 and leather's 192 are the
shipped 0.7.0 numbers, deliberately untouched. Cloth's 48 and metal's 256 order the two new families
around them by recipe cost: a rag wears through before a leather half-mask, an iron plate outlasts
it. An operator who disagrees turns wear off entirely with `mask.maskDurabilityEnabled`, which is
applied at damage time where config is genuinely loaded.

Within a family, all four styles are identical in every respect a player can measure: same
concealment, same wear, same cost, same equip behaviour. That is structural rather than promised —
the numbers live on `MaskFamily`, and `MaskCatalogTest` asserts no style can carry its own. No mask
of any family grants armour, toughness, knockback resistance, sand immunity or a concealment tier:
`MaskArmorMaterial` reports zero for all of them, and masks are not enchantable. The only thing a
family changes beyond wear is the equip sound.

Concealment stays centralized. `Masks.isMask` asks the `mcacrime:masks` item tag and nothing else, so
a pack can still make a carved pumpkin hide a face, and all sixteen styles feed the one disguise
resolver (§4.3). The four `mcacrime:masks/<family>` sub-tags exist only so a restyle recipe can say
"any clay mask" in one ingredient.

## Tint model

One 24-bit RGB value in the vanilla place: `display.color`, through `DyeableLeatherItem`. A mask with
no colour tag reports white rather than `DEFAULT_LEATHER_COLOR`, so an undyed mask is untinted rather
than washed brown (`MaskItem.getColor`).

Both halves of the tint are covered:

- **Inventory** — a Forge item-colour handler registered in `client/CrimeClientSetup` over
  `CrimeItems.masks()`, so adding a seventeenth style cannot leave it dyed in the bag and grey on the
  face. Layer 0 only.
- **Equipped** — vanilla `HumanoidArmorLayer` already multiplies an armour piece's `layer_1` texture
  by the `DyeableLeatherItem` colour and then draws an untinted `_overlay` layer if one exists. The
  shipped mask layers keep their dark outlines and shading under multiplication, so **no `_overlay`
  texture is shipped and none is required**. Adding one would only be necessary for a style with a
  structural element that must stay its own colour.

Dye costs one dye per operation and changes appearance only (`MaskCustomization.tint`, vanilla's own
leather blend so one dye produces the same colour it produces on a leather cap).

## Restyle rules

The station's `restyle` operation is the only path that moves a player's item history from one stack
to another. Four refusals, three of them enforced by the recipe simply not matching, so the style is
never offered rather than offered and then denied:

| Refusal | Cause | Visible to the player |
|---|---|---|
| `WRONG_FAMILY` | Source and target are different families, and therefore different wear budgets. | Style not offered |
| `NOT_A_REGISTERED_STYLE` | The material is tagged as a mask but is not one this mod registered. | Style not offered |
| `NO_CHANGE` | The selected style is the current one and no dye would change the colour. | Style not offered |
| `UNSUPPORTED_DATA` | The material carries data that cannot be moved honestly. | "This mask cannot be safely restyled." |

Cross-family is disallowed outright rather than proportionally rescaled (§5.4's stated preference):
damage is transferred *exactly*, and exact transfer only means anything between masks that share a
maximum durability. A restyle never repairs — the damage value is set through the stack, after an
assertion that both maximum durabilities agree, so no merge can smuggle a low damage number onto a
longer-lived item.

`UNSUPPORTED_DATA` is deliberately let through the recipe match and refused at assembly, because it
is a fact about the particular stack rather than about the pair of styles, and an unexplained empty
grid would read as a bug.

### Metadata policy

`MaskNbtTransfer` is registry-free and operates on `CompoundTag`s, which is what makes the whole
matrix a unit test. Three classes of root key:

- **Known and carried** — `display` (custom name, lore, `color`), `Enchantments` including curses,
  `RepairCost`, `Unbreakable`, `HideFlags`, and this mod's own `mcacrime` block (provenance, flags).
- **Unknown but recoverable — carried verbatim.** A foreign mod that stamped `somemod:owner` on a
  mask meant it to follow the item. Dropping it would make the station a laundering service for
  exactly the linkage §14.4 says must survive customization.
- **Unsupported — refuses the conversion.** `ForgeCaps`, `BlockEntityTag`, `EntityTag`. These are not
  data: `ForgeCaps` is serialized state belonging to capability providers the *old* item attached and
  the new one will never attach, and the other two are payloads for a placement a mask cannot
  perform. Copying any of them produces a stack whose contents nothing will ever read again, which is
  silent loss wearing a copy's clothes.

`Damage` is excluded from the merge on purpose and applied through the stack instead, so the
same-budget rule is enforced against real maximum durabilities rather than two integers that happen
to be present.

### Identity is untouchable from here

Deferred Heat, the masked-pursuit clock and witness knowledge live on the player and on the witness,
never on the stack. `MaskCustomization` is handed two item stacks and returns a third — no player, no
ledger, no witness — so MASK-12 holds because there is no expression in the customization path that
could clear any of it, not because anyone remembered to be careful.
`MaskHeatLedgerTest.restylingAMaskLeavesDeferredHeatAndPursuitExactlyWhereTheyWere` is the tripwire
that starts caring if that ever changes.

Restyle consumes exactly one mask and never returns it as a remainder — `MaskMakingRecipe`'s
remainder list only ever carries container items — so a flagged mask cannot be duplicated into a
clean copy (§14.4).

## What still needs a real client

Nothing below can be established by `compileJava`, `check` or `build`; all of it is runtime-only
acceptance:

- MASK-09/MASK-10 rendering: all sixteen equipped appearances on the player, on armour stands, and on
  every supported MCA head shape; tint correctness on the worn layer; head rotation, sneaking,
  swimming; no lingering helmet layer after replacement; no clipping.
- MASK-02 preview/result agreement in the live station, and MASK-05 (removing the dye before taking
  the output recomputes the result server-side).
- MASK-06/MASK-07 against real worn, named, enchanted and capability-bearing stacks.
- MASK-08's disabled no-op as the player experiences it (the style absent from the grid).
- MASK-11 loading a world containing 0.7.0 `clay_mask`/`leather_mask` stacks.
- Creative-tab ordering and the item-search behaviour of sixteen new entries.

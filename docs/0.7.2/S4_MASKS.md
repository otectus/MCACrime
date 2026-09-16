# S4 — Mask catalogue and customization

The 0.7.2 mask collection on this port: sixteen styles in four material families, one tint model, and
one protected path by which a mask becomes a different mask without becoming a different item.

## The catalogue

Style ids are the item's own registry id. They are persisted; enum ordinals never are
(`item/MaskVariant#styleId`). `clay_mask` and `leather_mask` keep their earlier ids, their wear
budgets and their crafting-table recipes, so every saved stack, datapack reference and recipe that
named them still resolves.

| Family | Wear | Craft cost (per style) | Restyle binding | Styles (`sort_order` 0…3) |
|---|---|---|---|---|
| Cloth | 48 | 1 `#minecraft:wool` + 1 string | 1 string | `bandana`, `highwaymans_domino`, `wrapped_scarf`, `half_veil` |
| Leather | 192 | 2 leather + 1 string | 1 string | `leather_mask`, `raven_mask`, `jackal_mask`, `stitched_mask` |
| Clay | 64 | 4 clay balls + 2 string | 1 string | `hockey_mask`, `clay_mask`, `comedy_mask`, `tragedy_mask` |
| Metal | 256 | 2 iron ingots + 1 leather | 1 leather | `iron_skull_mask`, `brigand_visor`, `owl_mask`, `blank_iron_mask` |

A restyle always consumes one mask of the family's own `mcacrime:masks/<family>` tag plus that
family's binding.

Wear budgets are a documented design choice, not a measurement. Clay's 64 and leather's 192 are the
shipped earlier numbers, deliberately untouched; cloth's 48 and metal's 256 order the two new families
around them by recipe cost. An operator who disagrees turns wear off entirely with
`mask.maskDurabilityEnabled`, which is applied at damage time where config is genuinely loaded —
`Item.Properties` is built during registration, long before any config file has been read.

Within a family, all four styles are identical in every respect a player can measure: same
concealment, same wear, same cost, same equip behaviour. That is structural rather than promised — the
numbers live on `item/MaskFamily`, and no style can carry its own. No mask of any family grants
armour, toughness, knockback resistance, sand immunity or a concealment tier: `item/MaskArmorMaterial`
reports zero for all of them, and masks are not enchantable.

Concealment stays centralized. `mask/Masks#isMask` asks the `mcacrime:masks` item tag and nothing
else, so a pack can still make another head item hide a face, and all sixteen styles feed the one
disguise resolver. The four `mcacrime:masks/<family>` sub-tags exist only so a restyle recipe can say
"any clay mask" in one ingredient.

## Tint model

One 24-bit RGB value in the vanilla place. On 1.21.1 that is the `minecraft:dyed_color` **data
component**, not `display.color` and not `DyeableLeatherItem`, which no longer exists; vanilla gates
its dye path on the `minecraft:dyeable` item tag, and every shipped mask is in it. A mask with no
`dyed_color` reports white rather than vanilla's default leather colour, so an undyed mask is untinted
rather than washed brown (`item/MaskItem`) — a plain `dyed_color` of white would otherwise be averaged
in by `DyedItemColor.applyDyes`.

Both halves of the tint are covered:

- **Inventory** — an item-colour handler registered from `client/CrimeClientSetup` on
  `RegisterColorHandlersEvent.Item` over the registered masks, so adding a seventeenth style cannot
  leave it dyed in the bag and grey on the face.
- **Equipped** — vanilla's armour layer multiplies the mask's dyeable armour layer by the same colour.
  The shipped mask layers keep their outlines and shading under multiplication, so no separate overlay
  texture is shipped and none is required.

Dye costs one dye per operation and changes appearance only (`mask/MaskCustomization#tint`), through
the same vanilla machinery a leather cap uses, so one dye produces the same colour it would there.

## Restyle rules

The station's `restyle` operation is the only path that moves a player's item history from one stack
to another. `mask/MaskRestyleRejection` carries five refusal reasons plus the `NONE` sentinel that
means the conversion is supported; three of the five are enforced by the recipe simply not matching,
so the style is never offered rather than offered and then denied:

| Refusal | Cause | Visible to the player |
|---|---|---|
| `NOT_A_MASK` | The stack handed in is empty, or is not a mask at all. | Style not offered |
| `WRONG_FAMILY` | Source and target are different families, and therefore different wear budgets. | Style not offered |
| `NOT_A_REGISTERED_STYLE` | The material is tagged as a mask but is not one this mod registered. | Style not offered |
| `NO_CHANGE` | The selected style is the current one and no dye would change the colour. | Style not offered |
| `UNSUPPORTED_DATA` | The material carries data that cannot be moved honestly. | An explicit refusal message |

Cross-family is disallowed outright rather than proportionally rescaled: damage is transferred
*exactly*, and exact transfer only means anything between masks that share a maximum durability. A
restyle never repairs — the damage is applied through the stack after both maximum durabilities are
checked to agree, so no merge can smuggle a low damage number onto a longer-lived item.

`UNSUPPORTED_DATA` is deliberately let through the recipe match and refused at assembly, because it is
a fact about the particular stack rather than about the pair of styles, and an unexplained empty grid
would read as a bug.

### Metadata policy

`mask/MaskNbtTransfer` keeps its name while the unit of work changed underneath it: 1.21.1 replaced
item NBT with typed **data components**, so the three-way policy is expressed over
`DataComponentType`s and the `DataComponentPatch` that carries them. Nothing in it touches an
`ItemStack`, an `Item` or a level, which is what makes the whole matrix a unit test.

- **Known and carried** — `damage`, `custom_name`, `lore`, `enchantments` (curses included),
  `repair_cost`, `unbreakable`, `dyed_color`, `hide_additional_tooltip`.
- **Unknown but recoverable — carried verbatim.** A foreign mod that stamped `somemod:owner` on a mask
  meant it to follow the item; dropping it would make the station a laundering service for exactly the
  linkage that must survive customization. On 1.21.1 this is strictly better than it was: a component
  the game can read back is a component this transfer can carry, whoever registered it.
- **Unsupported — refuses the conversion.** `block_entity_data` and `entity_data`: payloads for a
  placement a mask cannot perform, so copying them produces a stack whose contents nothing will ever
  read again. The Forge build's third refusal, `ForgeCaps`, has **no analogue here** — capability
  serialization is gone, and 1.21.1 item stacks carry no data attachments either, so the component
  patch really is the whole of a stack's state.

### Identity is untouchable from here

Deferred Heat, the masked-pursuit clock and witness knowledge live on the player and on the witness,
never on the stack. `MaskCustomization` is handed two item stacks and returns a third — no player, no
ledger, no witness — so a restyle cannot clear any of it, not because anyone remembered to be careful
but because there is no expression in the path that could.

A restyle consumes exactly one mask and never returns it as a remainder, so a flagged mask cannot be
duplicated into a clean copy.

## Proposed runtime acceptance — not yet performed on this port

Nothing below can be established by `compileJava`, `test` or `build`; all of it needs a real client,
and none of it has been run here:

- Rendering: all sixteen equipped appearances on the player, on armour stands and on every supported
  MCA head shape; tint correctness on the worn layer; head rotation, sneaking, swimming; no lingering
  helmet layer after replacement; no clipping.
- Preview/result agreement in the live station, and that removing the dye before taking the output
  recomputes the result server-side.
- The refusal matrix against real worn, named, enchanted and foreign-component-bearing stacks.
- The disabled no-op as the player experiences it (the style absent from the grid).
- Loading a world containing pre-0.7.2 `clay_mask` / `leather_mask` stacks. A Forge world's mask tint
  was item NBT and is **not** carried over by this port.
- Creative-tab ordering and the item-search behaviour of sixteen entries.

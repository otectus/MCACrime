# S3 — Mask Station crafting

The Mask Station's data contract, transaction contract, denied extraction routes and reload policy,
as shipped on this port.

## Recipe JSON contract

Type `mcacrime:mask_making`, loaded from `data/<namespace>/recipe/…` (1.21.1 moved the directory to
the singular form). The station's layout is fixed at two counted ingredient slots plus one optional
dye, so the format has no open ingredient list.

| Field | Required | Meaning |
|---|---|---|
| `operation` | yes | `craft` or `restyle`. Anything else is refused, never defaulted. |
| `group` | no | Free-form family key. Sorts the catalogue; defaults to `""`. |
| `material.ingredient` | yes | An ordinary vanilla ingredient object or array (`item` or `tag`). |
| `material.count` | no | 1–64. Defaults to 1. |
| `binding.ingredient` | yes | As above. |
| `binding.count` | no | 1–64. Defaults to 1. |
| `allow_dye` | no | Whether one dye may be spent in the optional slot. Defaults to `true`. |
| `result.id` | yes | A registered item. 1.21.1 spells a result's item key `id`, not `item`. |
| `result.count` | no | Must be `1` if present. A mask operation makes one mask. |
| `sort_order` | no | Sorts styles within a group. Defaults to 0. |

Validation lives in `recipe/MaskRecipeJson`, which is registry-free and reports **every** problem in
one list rather than throwing at the first: an unknown or missing operation, a missing or non-object
slot, a slot with neither `item` nor `tag`, an empty ingredient array, a malformed resource id, a
nonpositive or oversized count, a non-integer count, a missing/empty/unknown result, a result count
other than 1, an oversized group, an out-of-range sort order, and a mistyped `allow_dye`. Registry
resolution — the ingredient and the result item — happens afterwards in
`recipe/MaskMakingRecipe.Serializer`, only for files that already passed.

Matching uses `Ingredient.test` **and** the explicit counts. A `restyle` recipe additionally requires
the material stack to be a mask (`mask/Masks.isMask`) — a permissive tag is not permission to convert
an arbitrary item. A non-dye in the dye slot makes the recipe not match; it is never consumed or
silently ignored. Neither matching nor assembly writes to the container, and assembly always returns a
fresh stack.

Thirty-two recipes ship under `data/mcacrime/recipe/mask_station/<family>/`: one `craft` and one
`restyle` per style, for all sixteen styles. The block itself has an ordinary shaped recipe
(`data/mcacrime/recipe/mask_station.json`), and the two pre-existing crafting-table recipes
`clay_mask.json` and `leather_mask.json` are unchanged and still work.

## Transaction contract

`prepare` is `recipe/MaskCraftPlan` — pure arithmetic over counts. `commit` is `menu/MaskStationMenu`
plus `menu/MaskStationResultSlot`, which is the only owner of the debit.

* A plan is refused whole. `crafts()` is 0 and every cost is 0, with a named reason
  (`recipe/MaskCraftRejection`) the screen shows.
* Destinations are **one pool**: a craft spends one free inventory slot for the mask and one per
  crafting remainder, because in a player's inventory those slots compete. Counting whole free
  destinations rather than best-case stacking makes the plan under-promise and never over-promise.
* The single-click routes are not capacity-bound: the cursor always accepts the mask and a remainder
  falls on the floor rather than vanishing, so only affordability decides them.
* Shift-click repeats individually validated crafts, capped at `MaskCraftPlan.MAX_BATCH` (64), and
  re-checks matching, assembly and room before **each** debit. It stops rather than half-paying.
* `Slot#remove` is inherited and consumes nothing; `onTake` runs the one commit. There is no second
  debit path and no custom "craft" button payload.
* Delivery uses `Inventory#placeItemBackInInventory`, which drops what will not fit. Nothing is
  deleted on a full inventory.

## Selection and networking

`network/SelectMaskRecipeC2SPacket` is a NeoForge `CustomPacketPayload` (`mcacrime:select_mask_recipe`)
carrying only a container id, a bounded `ResourceLocation` and the recipe generation. The id is decoded
under `PacketBounds.MAX_ID_LENGTH` and rejected as a protocol error if oversized or malformed.
`ServerPacketGuard` takes the `IPayloadContext`, supplies the sender and the logical-side check, and
charges the existing `RequestBudget.Category.MENU` budget; refusals are rate-limited rather than
logged per attempt. Nothing in the payload is a position, so no client can cause a chunk access.

`menu/MaskSelectionPolicy` decides acceptance: the sender's open menu must be this container, the
generation must match the current catalogue, and the id must be in that catalogue. The answer travels
back as `network/MaskSelectionS2CPacket` (`mcacrime:mask_selection`). The generation travels as an
ordinary `DataSlot`; the id never does. The registrar's protocol version moved from `12` to `13`.

The client builds its grid from the recipes vanilla already synced to it, reading each id from its
`RecipeHolder` — 1.21.1 removed `Recipe#getId()` — and using the same `matches` and the same
comparator (`group`, `sort_order`, id) as `recipe/MaskStationCatalog`, so it shows the server's order
without the server sending a list.

## Denied extraction routes

`menu/MaskStationRoute` is the exhaustive table, asserted by `MaskStationRouteTest`.

| Route | Verdict |
|---|---|
| Left click | Allowed — one craft |
| Right click | Allowed — one craft (the mask is unstackable) |
| Shift-click | Allowed — bounded batch |
| Number-key (hotbar) swap | Denied |
| Off-hand swap | Denied |
| Q / drop from the result slot | Denied |
| Click-drag distribution | Denied |
| Double-click collect | Denied |
| Creative middle-click clone | Denied |
| Placing anything into the result slot | Denied |

Denials are enforced in three places that agree: `clicked` refuses the route outright,
`canTakeItemForPickAll` and `canDragTo` exclude the preview container, and the result slot's
`mayPlace` is `false`.

## Reload policy

`recipe/MaskRecipeGeneration` holds one counter, bumped by `AddReloadListenerEvent` and by observing a
replaced `RecipeManager` instance. Open menus compare it once per tick (`broadcastChanges`).

A generation change clears the selection and the preview outright — including a recipe replaced under
its own id, because the file behind that id may have changed completely and re-adopting it would show
a preview the player never chose. A deleted recipe therefore removes the preview immediately, and a
stale client cannot take it: `mayPickup` consults the current plan, which has no selection.

## Configuration

`maskStation.enableMaskStationCrafting` (default `true`) and `maskStation.enableMaskRestyling`
(default `true`), both COMMON. Turning crafting off makes `stillValid` false, so any open menu closes
at the next tick and returns the real inputs through the ordinary cleanup path; the block then passes
the interaction through instead of opening anything. Turning restyling off hides restyle recipes from
the server's catalogue.

## Proposed runtime acceptance — not yet performed on this port

The JUnit suite covers the plan arithmetic, the JSON contract, catalogue order and invalidation, the
selection policy and wire bounds, and the route table. The following are behavioural, need an actual
client and server, and have **not** been run on this port:

1. Every allowed route debits once and every denied route does nothing, including in creative mode.
2. Shift-craft with one or two free slots and a datapack ingredient that leaves a remainder.
3. Close, die, disconnect, change dimension, walk out of range, and break the station under two
   simultaneous viewers — inputs return once per owner, the preview never becomes an item.
4. Two players at one station with different inputs and different selections.
5. `/reload` while a preview is showing, including deleting that recipe file.
6. A claim-protection mod cancelling the block right-click — the menu must not open.
7. A recipe-viewer mod present and then removed.
8. Screen usability at GUI scale 2 on 854×480, keyboard-only navigation, and narrator output.
9. Dyed masks: the item icon and the worn layer both take the dye, and an undyed mask is unchanged.
10. A client whose own COMMON config disagrees with the server about `enableMaskRestyling` sees the
    style offered and the selection refused.

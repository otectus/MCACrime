# Mask Station visuals on this port

## The screen

`menu/MaskStationLayout` supplies one set of coordinates to both the menu and
`client/screen/MaskStationScreen`, so the slots the server owns and the slots the screen draws cannot
drift. The panel is 256×234, with labelled material, binding and optional dye inputs in a row, a
separate result preview to the right of them, a 4×2 scrollable style gallery with its own scrollbar,
and a centred player inventory. At 256×234 it fits the 427×240 logical viewport an 854×480 window
gives at GUI scale 2.

The gallery lists styles the server is currently willing to make, affordable or not, so a player can
discover what a style costs before owning the ingredients. Unavailable entries are marked and cannot
send a selection request. The grid is keyboard-navigable and narrates what is under the cursor. The
server-supplied result remains the only ready indication, and taking the result slot remains the only
crafting action — the client never decides that a craft happened.

The client builds the grid from the recipes vanilla already synced to it, taking each id from its
`RecipeHolder` and sorting with the same comparator the server's `recipe/MaskStationCatalog` uses.

## The block

`assets/mcacrime/models/block/mask_station.json` is a hand-built model of ordinary Minecraft cuboids
under `minecraft:block/block` with `render_type: minecraft:cutout` — an open bench with legs, a shelf,
a drawer, a mask on display, a thread spool and a knife. It references four 16×16 textures of its own
(`mask_station_top/side/front/bottom`), the existing clay-mask item icon for the display, and three
vanilla block textures. The four horizontal facings rotate the model with the block's shape.

## Verification status

The layout constants, the catalogue order, the selection policy and the route table are covered by the
unit suite in the working tree. The following are **proposed and have not been run on this port**:

- A live client capture of the baked block model and of the empty, insufficient-material, ready and
  restyle screens at 427×240 logical pixels.
- Grid interaction under a real client: out-of-bounds clicks, scrolling, scrollbar dragging, keyboard
  navigation, a server-confirmed selection, and clicking the result slot after a relayout.
- That crafting one dyed clay mask consumes exactly four clay balls, two string and one dye in game.
- Narration output, which needs a working narrator library.
- Resource validation that every model texture reference resolves to a 16×16 PNG, that every UV lies
  within its texture, and that every element stays inside the block's 16-unit bounds. The four own
  textures are 16×16; the rest of that sweep has not been performed here.

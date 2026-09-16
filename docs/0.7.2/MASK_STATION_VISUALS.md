# Mask Station visual refresh

The screen uses a 256×234 layout with labelled material, binding and optional dye inputs,
a scrollable style gallery, a separate 2× result preview, and a centred player inventory.
It fits the 427×240 logical viewport provided by an 854×480 window at GUI scale 2.
`MaskStationLayout` supplies the slot coordinates to both the menu and screen.

The gallery now shows recipes before they are affordable. Adding a base filters it to that
material; adding a mask switches it to restyling. Unavailable styles have a corner mark and
cannot send a selection request. Hovering shows quantities and any restyling restriction;
keyboard focus shows the name and cost below the gallery. Costs use the actual supplied
ingredient when a recipe accepts several interchangeable items.

Arrow keys, Home/End, Page Up/Down and Enter/Space operate the gallery. The scrollbar supports
dragging, and scrolling/clicking outside the gallery cannot change its selection. Long labels
are shortened within their region with their full text available on hover. Narration includes
recipe costs and availability. The server-supplied result remains the only ready indication,
and taking its slot remains the only crafting action.

The block is now an open wooden bench with four legs, a lower shelf, a drawer and metal pull,
a teal cutting mat, a mask display, thread spool and carving knife. The model uses ordinary
Minecraft cuboids and cutout rendering. All referenced textures are exactly 16×16, including
the existing clay mask icon used on the display. The four horizontal facings rotate the
outline/collision shape with the model; the open bench no longer hides adjacent block faces.

The wood and mat were generated with the built-in image generation tool, then sampled with
nearest-neighbor resampling and reduced palettes without dithering. Production PNGs are in
`src/main/resources/assets/mcacrime/textures/block/`; prompts and visual captures are in
`output/imagegen/mask_station/`.

## Verification

- `./gradlew build --offline`: 1,533 tests passed, including mask transactions, recipe data,
  selection packets, resource/language coverage, and screen render lifecycle; jar checks passed.
- Resource validation: all model texture references resolve to 16×16 PNGs, all UVs lie within
  their textures, and all 18 model elements remain inside the block's 16-unit bounds.
- A temporary development-client harness captured the actual baked model and the empty,
  insufficient-material, ready and restyle screens, with the container screen initialized at
  427×240 logical pixels. It exercised out-of-bounds grid clicks,
  scrolling, scrollbar dragging, Home navigation, server-confirmed selection and clicking the moved result slot.
  Crafting one dyed clay mask consumed exactly four clay balls, two string and one dye.
- The runtime harness used an isolated build and world without MCA Reborn, because its
  documented SRG-only mixins cannot load in the mapped ForgeGradle client. Production
  dependency metadata and the shipped jar retain the mandatory MCA dependency. These visual
  checks do not claim MCA villager/POI integration coverage. Spoken narration was not verified:
  this machine lacks the narrator's native `libflite` library.

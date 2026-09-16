# 0.7.2 implementation baseline (NeoForge 1.21.1 port)

The port's counterpart to the Forge baseline note. The Forge 1.20.1 line implemented 0.7.2 first;
this port brought the same features across afterwards, against its own source rather than by
translating the Forge diff. Where the two disagree, this port's source is what these notes describe.

## Checkout at the start of the parity work

| | |
|---|---|
| Branch | `master` |
| HEAD | `1470508` (Style spoken crime lines like MCA Conversations' chat mode) |
| Worktree | dirty: the 0.7.2 parity work is uncommitted |
| Versions | Mod, Minecraft, NeoForge, Java and mappings all live in `gradle.properties`; nothing here repeats them |
| Loader | NeoForge, ModDevGradle, official mappings |
| Test tasks | JUnit 5 under ModDevGradle's NeoForge runner (`build/minecraft-junit`); `build` also runs `checkJarContents` |

## What the port already had

Masks (0.7.1) as `item/MaskItem`, `item/MaskVariant` with two styles, `item/MaskArmorMaterial`,
`mask/Masks`, `mask/MaskHeatLedger`, `mask/MaskReactionPolicy` and `mask/MaskEventHandlers`; the
currency abstraction under `economy/`; and the Epic Fight seam
(`compat/EpicFightCompat`, `client/EpicFightInteractShim`, `docs/COMPATIBILITY_EPIC_FIGHT.md`).

## What 0.7.2 added here

The exclusive `mcacrime:thief` profession with a Mask Station point of interest, the station block,
menu and recipe type, the sixteen-style catalogue and its restyle path, the non-sneak apology, the
Sand Bottle, and the shared NPC-mugger eligibility evaluator. Each has its own note in this
directory.

## Platform rules these notes follow

The port never restates a Forge mechanism it does not use. In particular:

- Networking is NeoForge `CustomPacketPayload` types on a versioned `PayloadRegistrar` with
  `IPayloadContext`, not `SimpleChannel` or `NetworkHooks`.
- Per-entity, per-chunk and per-level state is a **data attachment**; capabilities and `ForgeCaps`
  do not exist here. Item stacks carry neither — their whole state is the data component patch.
- Item data is typed **data components**. There is no `DyeableLeatherItem`: a mask's tint is
  `minecraft:dyed_color`, and vanilla's dye path is reached through the `minecraft:dyeable` item tag.
- Registration is `DeferredRegister` / `DeferredHolder` on the mod bus, imperatively from the
  `McaCrime` constructor.
- A recipe's id lives on its `RecipeHolder`, not on the recipe; `Recipe#getId()` is gone.

## Verification status

These notes describe shipped source. Where the Forge documents record a run, this port's equivalent
run is named as **proposed and not yet performed** unless [`VERIFICATION.md`](VERIFICATION.md)
records it.

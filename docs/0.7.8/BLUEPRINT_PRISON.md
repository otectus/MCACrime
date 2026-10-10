# Building an MCA prison from MCA: Crime's cell blocks (0.7.8, NeoForge 1.21.1)

The NeoForge 1.21.1 port of the Forge change of the same name. MCA's Blueprint recognises a room as a
**prison** when it counts eight iron bars, an iron door and a bed. From 0.7.8 MCA: Crime's cell blocks
count toward the same requirement, in exactly the places iron bars and an iron door count.

## The change

Two tag files, both additive (`"replace": false`), under 1.21's singular directory:

| Tag | File | Gains |
|---|---|---|
| `mca:iron_bars` | `data/mca/tags/block/iron_bars.json` | `mcacrime:reinforced_bars`, `mcacrime:reinforced_bars_gap` |
| `mca:iron_doors` | `data/mca/tags/block/iron_doors.json` | `mcacrime:cell_door` |

MCA's prison definition is untouched: `{"#mca:iron_bars": 8, "#mca:iron_doors": 1, "#minecraft:beds": 1}`
in every build in `mca_probe_versions` (7.7.0 to 7.7.36-beta.3). `McaPrisonDefinitionProbeTest` reads it
from each of them and pins that no other MCA building type reads either tag. There is no mixin and no
fallback.

## What counts, on MCA 7.7.36

MCA 7.7.36 rebuilt its scanner around structures, floors and rooms. A room now counts the blocks standing
**on its own floor cells**, from the floor up through its height, and no longer the blocks in its walls.
That is MCA's rule, and it applies to iron bars exactly as to Crime's:

- **Bars set into the walls count toward nothing**, for iron and for Crime's bars alike. A cell like that
  is a house, if it has a bed.
- **Bars hung inside the room count**: eight of them, any mix of iron, reinforced and gapped.
- **The door must stand inside the room too.** The door in the wall is the way in, and is not counted;
  a second iron door or cell door standing on a floor cell is. MCA counts the block, not whether it is
  open (the GameTest's door stood open).
- **One vanilla bed.** The bunk is not a bed and is not in `#minecraft:beds`.

A room that fits more than one type makes the Blueprint ask which it is, and a cell with a bed fits both
prison and house; choose prison.

## Evidence

The port's GameTests (`gametest/BlueprintPrisonGameTests`, MCA 7.7.36-beta.3, `runGameTestServer`) drive
MCA's own add-building scan by name and record what it counted:

| Layout | Iron | Crime |
|---|---|---|
| Bars in the walls | house; counted the bed only | house; counted the bed only |
| Bars hung inside | house; counted 8 iron bars and the bed | house; counted 7 reinforced bars, 1 gapped bar and the bed |
| Bars and a door inside | **prison**; offered prison or house | **prison**; offered prison or house |

`crimeBarsAndCellDoorAreInMcasPrisonTags` checks the merged tags in the running game, vanilla entries kept.

## Datapacks, Capitals and limits

As on Forge: a pack that writes either tag with `"replace": true` drops Crime's entries (see
[DATAPACK.md](../../DATAPACK.md)); with MCA Capitals a recognised prison is also the capital's prison;
an MCA prison is not a destination for this mod's arrests (assign it with `/crime assignjail`); and a
cell MCA recorded before 0.7.8 keeps its type until it is removed and added again in the Blueprint. The
temporary holding cell an arrest raises has no bed, so it never meets the prison requirement.

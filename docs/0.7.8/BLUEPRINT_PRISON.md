# Building an MCA prison from MCA: Crime's cell blocks (0.7.8)

MCA's Blueprint recognises a room as a **prison** when it holds eight iron bars, an iron door and a
bed. From 0.7.8 MCA: Crime's cell blocks count toward the same requirement, so a cell built from them is
a prison to MCA, and to MCA Capitals when it is installed, without any vanilla bars or iron door.

## The change

Two tag files, both additive (`"replace": false`):

| Tag | Gains |
|---|---|
| `mca:iron_bars` | `mcacrime:reinforced_bars`, `mcacrime:reinforced_bars_gap` |
| `mca:iron_doors` | `mcacrime:cell_door` |

MCA's prison definition is untouched: `{"#mca:iron_bars": 8, "#mca:iron_doors": 1, "#minecraft:beds": 1}`,
the same in every MCA build MCA: Crime is tested against. `McaPrisonDefinitionProbeTest` reads it
from each of them, so an MCA release that renames a tag or changes the requirement fails MCA: Crime's
build rather than a player's prison. No other MCA building type reads either tag, so no other
classification can change. There is no mixin and no fallback: tags from several jars merging is
vanilla behaviour, and MCA's scanner resolves tags when it counts.

## What counts

- **Eight bars, any mix.** Reinforced bars, the gapped bars and vanilla iron bars are one group: five
  iron bars and three reinforced bars make eight.
- **One door.** The cell door or an iron door. Open, closed and locked all count, and so does a cell door
  with a padlock hung on it; the scanner counts the block, not its state.
- **One vanilla bed.** MCA: Crime's bunk is not a bed and is not added to `#minecraft:beds`, so a cell
  built only from Crime's blocks still needs one bed.
- **A closed room.** MCA's own rules for walls, roof, size and door are unchanged. Bars and doors are walls
  to the scanner, so the gapped bars close a wall like any other bars.

The temporary holding cell an arrest raises has no bed, so it never meets the prison requirement.

## Using the Blueprint

Stand inside the cell and use the Blueprint's add-room action.

- **MCA 7.7.1** asks a question when a room fits more than one building type, and a barred cell with a
  bed fits both **prison** and **house**. Choose prison. A vanilla iron-bar cell gets exactly the same
  question; the Crime blocks change nothing about it. Without that choice the room is not recorded.
- **MCA 7.6** has no such question and records the prison by itself.

A cell that MCA recorded before 0.7.8, when its Crime bars did not count, keeps the type it was given.
Remove it in the Blueprint and add the room again to have it counted.

A recorded prison stays a prison across `/reload` and restarts: MCA stores the building's type, and the
tags are read again whenever a room is scanned.

## With MCA Capitals

Capitals finds a capital's prison through MCA's buildings of type `prison`, so a cell built from Crime's
blocks also unlocks Capitals' own justice and gives its surrender a place to send prisoners. This needs
nothing from MCA: Crime's Capitals integration; it follows from the tags alone, with or without royal
decrees switched on.

## Datapacks

A datapack that writes `mca:iron_bars` or `mca:iron_doors` with `"replace": true` removes MCA: Crime's
entries along with MCA's own. That is the pack's decision; a pack that wants Crime's cells counted lists
the three `mcacrime:` ids itself. See [DATAPACK.md](../../DATAPACK.md).

## What it does not do

An MCA prison is not a destination for MCA: Crime's arrests. Guards still take prisoners to an assigned
jail, a Townstead facility cell or a holding cell they build. To use a recognised prison as the jail,
assign it with `/crime assignjail <pos> [radius]` at a position inside it. Making MCA prisons automatic destinations needs capacity and
enclosure rules that an MCA prison does not guarantee, and is left for a later release.

## Evidence

On a production Forge 47.4.23 server, MCA's own scan recorded each of these as a prison: reinforced bars
with one gapped bar and a cell door; vanilla iron bars and an iron door; five iron and three reinforced
bars; an open cell door. Seven bars were neither offered nor recorded as a prison. A prison stayed a
prison after `/reload` and a fresh scan, and after a restart. This held on MCA 7.7.1-beta.2 with and
without Capitals, and on MCA 7.6.20 without it. With Capitals installed, Capitals' own prison lookup
covered a cell built only from Crime's bars and cell door. Commands and logs are in
[VALIDATION.md](VALIDATION.md). The NeoForge 1.21.1 port records its own results for MCA 7.7.36, whose
scanner counts the blocks inside a room rather than its walls; see that port's
`docs/0.7.8/BLUEPRINT_PRISON.md`.

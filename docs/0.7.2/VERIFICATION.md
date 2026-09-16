# 0.7.2 verification record (NeoForge 1.21.1 port)

What is actually evidenced in this repository for the port's 0.7.2 parity work, and what is not.
Anything not listed under "Evidence in the tree" is proposed, not performed.

## Provenance

| | |
|---|---|
| Source at start | `master` @ `1470508`, dirty; the 0.7.2 parity work is uncommitted |
| Versions | `gradle.properties` only — mod, Minecraft, NeoForge, Java and mappings |
| Scope | Phases 0–7 of the parity plan, plus this documentation pass |
| Nothing committed or installed | all changes remain in the working tree |

## Evidence in the tree

- **Unit suite.** `build/test-results/test/*.xml` aggregates to **1610 tests, 0 failures, 0 errors,
  1 skipped**, run under ModDevGradle's NeoForge JUnit runner. The builders reported this same count
  as green; the XML in the tree is the artefact behind that report, not a separate run.
- **Packaged jar.** The jar-contents check is performed by the gated `build` task
  (`checkJarContents`), which fails on a shaded MCA, Reputation, Architectury or Forge class. A jar
  under `build/libs/` is only evidence for the current source when its timestamp is newer than every
  file under `src/main`; no result is asserted here.
- **Shipped data.** Thirty-two Mask Station recipes (`craft` and `restyle` for all sixteen styles)
  under `src/main/resources/data/mcacrime/recipe/mask_station/`, four family sub-tags plus the parent
  `mcacrime:masks` tag, and the sixteen masks in `minecraft:dyeable`.
- **Mixin manifest.** `src/main/resources/mcacrime.mixins.json` declares seven common mixins and one
  client mixin; `MixinConfigTest` asserts the side separation and registration.
- **GameTest classes.** `src/main/java/dev/otectus/mcacrime/gametest/` exists and is excluded from the
  production jar by `build.gradle`. **No GameTest run is evidenced in this tree.**

## Not performed on this port

No claim is made that any of the following has been executed for 0.7.2 here:

- A dev-client or dedicated-server session of any kind, including the mixin behaviour checks that only
  a packaged, production-mapped run can settle.
- A GameTest batch.
- The per-stage runtime acceptance listed in [`S2_OCCUPATION.md`](S2_OCCUPATION.md) §7,
  [`S3_STATION.md`](S3_STATION.md), [`S4_MASKS.md`](S4_MASKS.md),
  [`S6_SAND.md`](S6_SAND.md) and [`MASK_STATION_VISUALS.md`](MASK_STATION_VISUALS.md).
- Legacy-save migration against real pre-0.7.2 world fixtures, in particular the schema 11 → 12 step
  and the fact that a Forge world's NBT mask tint is **not** carried over to `minecraft:dyed_color`.
- A re-run of the Epic Fight / `mcea` / Numismatic compatibility matrix. That matrix was recorded in
  an earlier session against the pre-parity tree (see
  [docs/COMPATIBILITY_EPIC_FIGHT.md](../COMPATIBILITY_EPIC_FIGHT.md)) and has **not** been re-run
  since the parity pass; no Locks Reforged matrix exists for 0.7.2 at all.

## Documentation status

This pass changed Markdown only: `CHANGELOG.md`, `README.md`, `CLAUDE.md`,
`docs/COMPATIBILITY_EPIC_FIGHT.md` and the seven files in this directory. No Java, JSON or resource
file was touched by it, and no build or test was re-run because of it.

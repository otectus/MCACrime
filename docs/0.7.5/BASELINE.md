# MCA: Crime 0.7.5 — Forge 1.20.1 pre-edit baseline

Milestone **M0.1** of `docs/MCA_Crime_0.7.5_Cuffed_Integration_Implementation_Plan.md`. This is what
the Forge 1.20.1 line looked like **before** any 0.7.5 edit, so that anything found later can be told
apart from something that was already there.

- Repository: `/home/otectus/Projects/MCACrime`
- Branch: `feature/reputation-0.6.0`
- HEAD: `d5b738df7241b216f327597f640f8955c1c795f2` (0.7.4 release line)
- Recorded: 2026-09-17
- Minecraft, Forge and mod version: as in `gradle.properties` at that commit
- **No repository file was modified by the baseline run.**

Every result below is a command that was actually run, with its exit code. Nothing here is a
prediction.

---

## 1. JUnit suite — `check`

```
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCACrime check
```

Exit 0 — `PASS - check (10s)`, wall time 9.5 s.
Log: `/tmp/gradle-MCACrime-check-20260917-144054.log`.

`:test` reported `UP-TO-DATE`; the result set on disk was produced earlier the same day from the
same HEAD and an unmodified tracked tree, so it is the valid baseline. Aggregated from the 240 JUnit
XML files in `build/test-results/test`:

| Tests | Passed | Failed | Errors | Skipped |
|---|---|---|---|---|
| 1924 | 1908 | 0 | 0 | 16 |

No failing tests, so there are no assertion lines to report.

## 2. Full build — `build` (includes `checkJarContents`)

```
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCACrime build
```

Exit 0 — `PASS - build (8s)`, wall time 8.3 s.
Log: `/tmp/gradle-MCACrime-build-20260917-144113.log`.
`checkJarContents` passed: no companion, Architectury or MCA class was shaded into the jar.

Artifact: `build/libs/mcacrime-0.7.4.jar`, 2,206,741 bytes. `build/libs` also held
`townstead-runtime-checks-0.7.4.jar` (16,353 bytes) and the older release jars from 0.3.0 onwards.

## 3. Static consistency — `check_mod.py`

```
python3 /home/otectus/Projects/.mcmod-tools/check_mod.py MCACrime     # cwd /home/otectus/Projects
```

Exit 1, wall time 1.9 s. Summary: `1 errors, 0 warnings, 16 notes`.

**Pre-existing error (not introduced by 0.7.5).**

- `CrimeKeybinds.java:50` — `@Mod.EventBusSubscriber` declares `Bus.MOD` but the class handles the
  Forge-bus `TickEvent`, which will therefore never fire.

**Pre-existing notes (16).** All of the form "item model exists but no item with that id is
registered (leftover from a rename?)", under `assets/mcacrime/models/item/`: `bandana.json`,
`blank_iron_mask.json`, `brigand_visor.json`, `clay_mask.json`, `comedy_mask.json`, `half_veil.json`,
`highwaymans_domino.json`, `hockey_mask.json`, `iron_skull_mask.json`, `jackal_mask.json`,
`leather_mask.json`, `owl_mask.json`, `raven_mask.json`, `stitched_mask.json`, `tragedy_mask.json`,
`wrapped_scarf.json`.

No missing models, missing language keys or missing textures were reported.

## 4. Protected restraint texture hashes

```
sha256sum src/main/resources/assets/mcacrime/textures/item/restraint_cuffs.png \
          src/main/resources/assets/mcacrime/textures/item/restraint_locked_cuffs.png
```

Exit 0. Both match the values the plan's section 1.3 requires:

| File | SHA-256 | Result |
|---|---|---|
| `restraint_cuffs.png` | `df1a7d30af0e4795cf706c94a4b37036dd748c4f9c750b793550daeb6f6997f4` | matches |
| `restraint_locked_cuffs.png` | `b0a86e25652e6d1acc7b2d1cf58a4be73b9e3bc4218760917337e324fd7d084f` | matches |

## 5. Git state at baseline

`git status --short` (exit 0) listed only untracked files — the two plan documents and an `output/`
directory. **No tracked file was modified.**

---

## What this baseline means for the release

Two findings pre-date 0.7.5 and are recorded here so a later run does not attribute them to this
work: the `CrimeKeybinds` MOD/FORGE bus error, and the sixteen orphaned mask item models. Neither is
a 0.7.5 regression, and neither is fixed by 0.7.5.

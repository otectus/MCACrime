# Phase 2 — Crime Detection: In-World Verification Checklist

The Phase 2 logic is unit-tested where it's pure (false-positive gate fragments, witnessed/unwitnessed
math, ledger NBT round-trip). The items below exercise the parts that touch **MCA Reborn** and live
world state, which **cannot run under the dev `runClient`/`runServer`** (MCA's Forge mixins are
SRG-named with no refmap, so MCA only loads in a production-style instance). Run these in a real client
+ server with MCA Reborn 7.6.x installed alongside `mcacrime-0.1.0.jar`.

Setup: a witnessing MCA villager/guard within `witnessRadius` (default 12) with line of sight is required
for the "witnessed" rows; break line of sight (a wall, or lure witnesses away) for the "unwitnessed" rows.
Inspect results with `/crime ledger <you>` and `/crime status`.

## A. False positives — each MUST produce NO ledger entry and NO karma/heat change

- [ ] **Environmental death** — villager walks into lava / drowns / falls / suffocates → not a crime.
- [ ] **Dispenser arrow** kills/hurts a villager (no responsible player) → not a crime.
- [ ] **FakePlayer / automation** (autonomous activator or similar) hits a villager → not a crime.
- [ ] **Mob or iron golem** kills a villager → not a crime.
- [ ] **Raid-cleave** — sweep/AoE clips a villager during an **active** village raid → not a crime (`raidGrace`).
- [ ] **Self-defense** — a villager already targeting you is struck back → not a crime.
- [ ] **Thorns / reflected** damage to the player (villager/golem has thorns) → not a crime by the player.

## B. Positive detection + witnessed vs unwitnessed split (§3.5)

- [ ] **Witnessed harm** (witness in line of sight): karma ↓, heat ↑, ledger row `witnessed=true`; if heat crosses the Wanted threshold the player becomes Wanted (player card / `/crime status`).
- [ ] **Unwitnessed harm** (no line of sight): karma ↓ by `karmaDelta × unwitnessedKarmaFactor` (full at default 1.0), **heat unchanged** (`requireWitnessForHeat=true`), ledger row `witnessed=false`.

## C. Crime classification + double-count guard

- [ ] **One-shot kill** of a low-health villager → **exactly one** `kill_villager` record, **zero** `harm_villager` (lethal hit skips harm).
- [ ] **Sustained beating** (non-lethal hits) → one `harm_villager` per `harmCooldownTicks` window (flurry collapses to one), not one per swing.
- [ ] **Harm a guard** (`mca:guard`, non-lethal) → `assault_guard` (not `harm_villager`).
- [ ] **Kill a guard** → `kill_villager` (kill classification covers all MCA villagers, guards included).

## D. Data-driven registry + fail-safe

- [ ] Edit a `crimes/*.json` with a malformed field → `/crime reload` and `/crime validate` both report the error.
- [ ] Delete/disable the crime JSONs → detection still works off `BuiltinCrimeTypes` (fail-safe).

## E. Persistence

- [ ] Commit crimes, **restart the server**, then `/crime ledger <player>` still lists them (the converted `mcacrime.dat` ledger slot round-trips).

---
_Mark each box when verified. Anything that fails is a Phase 2 bug to fix before tagging 0.1.0._

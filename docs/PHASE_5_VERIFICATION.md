> This document describes the Forge 1.20.1 era implementation and is kept for history. The current NeoForge 1.21.1 port is described in README.md, docs/MIGRATION.md, and docs/MCA_CRIME_1.21.1_NEOFORGE_PORT_SPEC.md.

# Phase 5 — Suite Integration Foundation & Reputation Bridge: In-World Verification Checklist

Phases 0–2 of the suite integration plan (`MCA_CRIME_SUITE_INTEGRATION_IMPLEMENTATION_PLAN.md`,
PRs `R1`, `C1`, `C2`) are unit-tested where the logic is pure — 181 tests in MCA: Crime and 255 in
MCA: Reputation cover the community key, witness capture, the case transition table, schema migration
0→3, the outbox and its retry policy, fine allocation, the incident data, the optional-classload seam,
and every row of the detection-authority truth table.

The items below exercise what **cannot** run under `gradlew runClient` / `runServer`. MCA's Forge
mixins are SRG-named with no refmap, so MCA only loads in a production-style instance — every check
here needs a real client and dedicated server with **MCA Reborn 7.6.x, the `mcacrime` jar, and
(where noted) the `mcareputation` jar**.

Build order for a full-suite instance:

```bash
cd C:/Projects/MCAReputation && ./gradlew build
```
```bash
cd C:/Projects/MCACrime && ./gradlew build -PrequireReputation=true
```

`-PrequireReputation=true` is what stops a suite build silently shipping without the adapter. Omit it
for a standalone MCA: Crime jar; the adapter package is then excluded and the mod behaves exactly as
it does with MCA: Reputation uninstalled.

---

## A. MCA: Crime alone — nothing may have regressed

Install **only** `mcacrime`. Everything in phases 1–4 must behave exactly as it did.

- [ ] **Detection** — each of the seven crime types still fires: harm, kill, guard assault, jailbreak,
      kidnap, theft, mugging-murder. Karma and Heat move by the same amounts as before.
- [ ] **`/crime payfine`** — the message, the cost, and the outcome are unchanged: all Heat clears in
      one payment. (The per-case machinery is underneath, but the command still pays everything off.)
- [ ] **`/crime status`, the inventory card, guard pursuit, jail, escape, kidnap, ransom, rescue** —
      all unchanged.
- [ ] **`/crime debug integrations`** reports `installed=false` and `state=not installed`, and nothing
      else in the mod behaves differently.
- [ ] `/crime validate` reports no problems on a default config.

## B. Old-world migration (§11) — the one-way door

Take a **copy** of a world saved by 0.1.0. Keep the original; migration is not reversible.

- [ ] The world loads, and the log carries **one aggregate** line about assuming
      `minecraft:overworld` for legacy villages — not one line per record.
- [ ] `/crime ledger <player>` shows every pre-existing record with its **original** id, type, victim,
      time, karma/heat deltas and resolution intact.
- [ ] A previously-witnessed record still reads as witnessed, and `/crime case <id>` shows **no**
      witness names — migration must never invent them.
- [ ] Per-village standing survives: a player who was disliked in a village still is.
- [ ] Save, quit, reload. The store round-trips at schema 3 with no warnings the second time.
- [ ] **Downgrade guard** — put the 0.1.0 jar back on the migrated world. It loads (the reserved
      passthrough holds), and putting the new jar back again loses nothing.

## C. Cross-dimension village identity (§11.2) — the headline data fix

- [ ] Find or `/place` a village in the Overworld and one in the Nether that MCA assigns the **same**
      numeric id (`/crime debug villager` shows the id).
- [ ] Commit a crime in each. `/crime case <id>` shows a different community for each
      (`minecraft:overworld/N` vs `minecraft:the_nether/N`).
- [ ] Village standing in one is unaffected by the crime in the other.

## D. Detection authority (§7.1) — the truth table, in world

Install **both** mods. Each row is a separate server start.

- [ ] **Healthy** — `/crime debug integrations` says `holds detection authority=true`, and
      `/mcareputation debug integrations` shows `MCA_VILLAGER_ASSAULT -> external authority`.
      Hit a villager: **one** Reputation incident appears, not two. Check with
      `/mcareputation incidents` (or the standing screen), not just by the absence of errors.
- [ ] **Crime side disabled** — set `integrations.enableReputation=false` in MCA: Crime's config.
      Reputation's own detector resumes; hitting a villager still produces exactly **one** incident.
- [ ] **Reputation side disabled** — set `[integration] enableCrimeIntegration=false` in MCA:
      Reputation's config. Same result: exactly one incident, produced natively.
- [ ] **Detection off** — `detection.enableCrimeDetection=false`. MCA: Crime stops claiming, and
      Reputation records the assault itself.
- [ ] Count, do not eyeball. For one hit the expected totals are: 1 Crime case, 1 karma application,
      1 heat application, 1 Reputation incident, 1 score delta, 1 player notification.

## E. The overlapping-deed sequence (§2.3 of the plan) — a known, deliberate divergence

With both mods and authority held, hit a villager twice non-lethally, then kill it.

- [ ] **Two** Crime cases: one `harm_villager` (the second hit is inside `harmCooldownTicks`), one
      `kill_villager`.
- [ ] **Two** Reputation incidents: `villager_assaulted` and `villager_killed`.
- [ ] Total standing change is **−48**, not the −40 MCA: Reputation alone would apply.

> This is expected, not a bug. MCA: Reputation folds a precursor assault into the killing that
> follows; MCA: Crime keeps them as two separately resolvable legal cases, so both stand. If the −40
> behaviour is wanted instead, it needs a fold operation added to the Reputation API — see the note
> in the plan's Risks section. **Decide before release, and put whichever way it lands in the
> changelog**, because a server owner who installs MCA: Crime will otherwise see standing drop faster
> than the wiki says.

## F. One deed, one public consequence (§7.6)

- [ ] With Reputation present and authority held, a witnessed assault does **not** also apply MCA:
      Crime's own `villageRepDrop`. Check `/crime debug villager` before and after.
- [ ] Personal MCA hearts still drop for the victim and their family — exactly once. Reputation does
      not touch hearts, so this must not be suppressed.
- [ ] Only **one** message reaches the player per crime, not one from each mod.
- [ ] With `integrations.suppressLocalVillagePenalty=false`, both apply. (Confirm this is visibly
      worse; it exists as an escape hatch, not a recommendation.)

## G. The outbox (§5.2) — crash and companion-removal recovery

- [ ] **Crash replay** — commit a crime, then kill the server process (not a clean stop) within a
      second. On restart the log reports queued writes being replayed, and the crime ends up with
      **one** linked incident, not two. `/crime debug outbox` is empty afterwards.
- [ ] **Companion removed** — remove `mcareputation`, commit crimes, restart a few times. The world
      loads, `/crime debug outbox` shows the queue growing but **bounded**, and nothing errors.
- [ ] **Companion restored** — put `mcareputation` back. Pending work delivers, each crime gets
      exactly one incident, and the queue drains to zero.
- [ ] **Dead letter** — remove one of the shipped incident JSONs with a datapack override, commit that
      crime type, and confirm it dead-letters with `UNKNOWN_TARGET` after one clear log line rather
      than retrying forever — **and** that MCA: Crime then applies its own village penalty after all,
      so the deed is not silently free.
- [ ] `/crime debug outbox dead` lists it; `/crime debug integrations` names the last failure.

## H. Fines settle specific cases (§6.10)

- [ ] Commit three crimes. `/crime payfine` clears all Heat as before **and** `/crime ledger` now
      shows those cases as `FINED` rather than `UNRESOLVED`.
- [ ] With Reputation installed, the linked incidents move to `ATONED` (or `APOLOGIZED` per
      `integrations.reputation.fineResolutionStatus`). The incident is resolved, not deleted.
- [ ] Pay with insufficient emeralds: nothing is taken, no case changes, the same message appears.
- [ ] Spam the payment command: the charge happens once.

## I. Witness capture (§6.7)

- [ ] Gather **20+** villagers around a victim and commit a crime. `/crime case <id>` shows at most
      `detection.maxStoredWitnesses` names, and the context records the real crowd size.
- [ ] Commit a crime with no villager in line of sight: the case exists, witnessed is false, and no
      Reputation incident appears for an unwitnessed ordinary assault.
- [ ] **A jailbreak is witnessed with no witnesses named.** `/crime case <id>` on a jailbreak shows
      witnessed=true and an empty witness list — the authority knows without anyone having seen it.

## J. Server hygiene

- [ ] Dedicated server startup loads no client-only class (search the log for `Screen`, `Minecraft`,
      or `RenderSystem` classload errors).
- [ ] An idle server with an empty outbox shows no periodic log noise and no measurable tick cost.
- [ ] `/crime debug integrations` output contains no player UUIDs — it should be safe to paste into a
      bug report.

---

_Mark each box when verified. Anything that fails is a blocker for tagging the release._
_Section E is a decision, not a defect — it needs an answer either way before release._

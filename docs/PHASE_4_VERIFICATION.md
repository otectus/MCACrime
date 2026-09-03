# Phase 4 — Captivity & Kidnapping: In-World Verification Checklist

The Phase 4 logic is unit-tested where it's pure (custody NBT round-trip + forward-compat, the captivity
cap accounting, the capture vulnerability matrix + channel break geometry, the legal-target kidnapper term,
ransom payer-priority / cooldowns / amount math / state transitions). The items below exercise the parts
that touch **MCA Reborn** and live world state, which **cannot run under the dev `runClient`/`runServer`**
(MCA's Forge mixins are SRG-named with no refmap, so MCA only loads in a production-style instance). Run
these in a real client + server with MCA Reborn 7.6.x installed alongside `mcacrime` jar.

Setup: get the restraints from the "MCA: Crime" creative tab (Rope / Cuffs / Locked Cuffs). Right-click a
target with a restraint to start a capture. Use `/crime debug custody` to dump custody + the relationship
adapter, `/crime status` and the inventory card to read state, and `/crime release <player>` as the admin
backstop.

## A. MCA adapter — confirm the ⚠ verification targets (`/crime debug custody`, `/crime debug villager`)

- [ ] **Hearts read/write** — `hearts=` matches MCA's own interaction screen for that player↔villager pair.
- [ ] **Family graph** — for a married, parented villager: `villager.spouse`, `parents`, `children`,
      `siblings` match MCA's family tree; `isAdult` is correct for an adult vs a child villager.
- [ ] **Player as relative** — a player married to a villager resolves as that villager's spouse.
- [ ] **Degradation** — on a world/build where the relationship API can't resolve, `relationshipApi=false`
      logs once and ransom uses the village fallback instead of crashing.
- [ ] **Leash** — capturing an NPC leashes it; releasing drops the leash; the villager is never deleted.

## B. Capture conditions + channel (§8.2)

- [ ] **Vulnerability gate** — a healthy, awake, un-surrendered **player** cannot be captured (message); one
      that is low-health / asleep / recently `/crime surrender`ed can.
- [ ] **Villager relaxed** — an ordinary villager can be captured without a vulnerability (default config);
      a **guard** cannot be captured instantly (requires the full path; `guard_immune` message).
- [ ] **Channel breaks** — the cast breaks if the captor is **hit**, **moves too far**, the target leaves
      **range**, or **line of sight** is lost; it completes only if held the full duration.

## C. Custody + the §7.1 matrix during BOTH kidnapping and jail

- [ ] Kidnap a player; confirm `heldByRef`/`heldBy` survive **logout, death, dimension change, restart**
      (the captive is never deleted, refs copied on clone).
- [ ] As a **captor**, the same survives; the held-captive ref reconciles on login.
- [ ] Both a jailed player and a kidnapped player show their status on the inventory card / `/crime status`.

## D. Softlock backstops (§7.2, §8.4) — the must-never-stick guarantees

- [ ] **Captivity cap** — a held player is force-freed after `maxCaptivityRealMinutes` of online time.
- [ ] **Admin release** — `/crime release <player>` frees a kidnapping captive (and a captor's captive),
      not just a jailed player.
- [ ] **Captor gone** — on the captive's next login with an invalid captor reference, they are freed.
- [ ] The captive **entity is never deleted** on captor logout/death/dimension — only unleashed/left in place.

## E. The legality split (§8.1) — the headline invariant

- [ ] **Escaping kidnapping** (`/crime escape`, or straying past the tether with `captiveCanEscapeByDistance`)
      yields **NO Heat, NO bounty, NO karma loss, and is NOT a jailbreak**.
- [ ] **Escaping jail** physically still flags `jailbreak` (Heat + escaped Legal Target) — unchanged from Phase 3.
- [ ] Restraint escape odds honor config: rope easy, cuffs hard, locked cuffs need a key/rescue (≈0 by default).

## F. Ransom (§8.5)

- [ ] `/crime ransom` while holding a captive resolves a payer by priority (spouse→parent→adult-child→…);
      a reachable **player** relative gets a `/crime payransom` notice; with no player relative it settles as
      a lower-value **village** ransom (when the fallback is enabled), or is refused if disabled.
- [ ] `/crime payransom` charges emeralds (atomic), pays the captor, frees the captive, writes a ledger entry.
- [ ] **Fail cases** — a demand cannot be paid after the victim **dies / escapes / is rescued / is jailed**;
      it flips to the matching failure and notifies. An expired demand (`ransomDemandTtlTicks`) clears.
- [ ] **Cooldowns** — per-victim / per-family / per-village windows block immediate re-extraction.

## G. Mugging vs murder (§8.6)

- [ ] `/crime mug` on a nearby villager grants loot and a moderate `theft` crime (witnessed scales Heat).
- [ ] Killing that villager **while mid-mug** records the heavier `mugging_murder` (no bonus loot, more
      Karma/Heat, bounty-eligible) — the default favors robbery over murder.

## H. Toggles, events, and feedback

- [ ] `enableKidnappingPlayer=false` blocks player capture but NPC capture still works; `enableKidnappingNpc=false`
      blocks NPC capture — honored independently.
- [ ] `EntityKidnappedEvent` / `EntityReleasedFromCaptivityEvent` fire once per transition with the right reason.
- [ ] **Phase 1–3 corrections** — the band-change, "a guard saw your crime", and why-a-guard-attacks messages
      appear (and don't spam); `chatNameColorEnabled` adds a band prefix in chat non-destructively.
- [ ] The captive sees a **HELD BY** line on the inventory card; `/crime escape` is the always-available escape.

> Deferred polish (not blocking): a dedicated modal captive Screen with an in-screen escape button (and its
> single validated C2S packet). The card line + `/crime escape` cover the §10.3 captive experience for now.

---
_Mark each box when verified. Anything that fails is a Phase 4 bug to fix before tagging the release._

# Phase 3 — Enforcement & Jail: In-World Verification Checklist

The Phase 3 logic is unit-tested where it's pure (online-tick jail accounting incl. the captivity-cap
backstop, the Legal-Target truth table, fine math, jail-region geometry, anchor/state NBT round-trips).
The items below exercise the parts that touch **MCA Reborn** and live world state, which **cannot run
under the dev `runClient`/`runServer`** (MCA's Forge mixins are SRG-named with no refmap, so MCA only loads
in a production-style instance). Run these in a real client + server with MCA Reborn 7.6.x installed
alongside `mcacrime` jar.

Setup: assign a jail with `/crime assignjail` (or enable `jailFallbackEnabled` in config). Inspect state
with `/crime status` and the inventory reputation card. An op can drive sentences with `/crime jail` and
`/crime release`.

## A. Online-ticks sentence — survives the full §7.1 matrix

- [ ] **Logout pauses** — jail a player, note `/crime status` remaining; log out for a while; on return the
      remaining ticks are unchanged (offline time did not count).
- [ ] **Death does not clear** — a jailed player who dies is still jailed on respawn (sentence copied on clone).
- [ ] **Restart resumes** — jail, stop the server, restart; the sentence continues from where it paused.
- [ ] **Dimension change keeps ticking** — a jailed player who changes dimension still counts down (player-tick based).
- [ ] **Offline never decrements** — confirm an entire offline session subtracts nothing from the sentence.

## B. No softlock without a jail (§7.4)

- [ ] With **no anchor assigned and `jailFallbackEnabled=false`**, `/crime jail <player>` is **refused** with a
      clear message — the player is never put into a stuck state.
- [ ] With `jailFallbackEnabled=true`, the same command jails to the configured fallback position.
- [ ] **Invalid-jail login reconcile** — jail in a dimension, remove/disable that dimension, log the player
      back in with no fallback → they are released (INVALID_JAIL), never stuck.

## C. Containment vs physical breakout (§7.3)

- [ ] **CONTAINMENT** — a prisoner who strays outside the region is teleported back.
- [ ] **PHYSICAL** — a prisoner who leaves the region is flagged **escaped** (becomes a Legal Target), a
      `jailbreak` crime is recorded (Heat), and they are **not** teleported back; the sentence continues.
- [ ] **REINFORCED** — behaves like CONTAINMENT in this version (documented).

## D. Real-time captivity cap (§7.2)

- [ ] Jail with a very long sentence; confirm the player is force-released once held for
      `maxCaptivityRealMinutes` of online time (CAPTIVITY_CAP), independent of the MC-tick sentence.

## E. Guard hostility & villager reactions (§4)

- [ ] A **Wanted** (or otherwise Legal-Target) player draws nearby MCA guards into pursuit within
      `guardAggroRadius`; pursuit stops when they are no longer a Legal Target.
- [ ] A **Red** player makes nearby villagers flee within `villagerFleeRadius` (when `enableVillagerFlee`).
- [ ] The **why-a-guard-is-attacking** message appears (Wanted / escaped / outlaw / captor) and does not spam.

## F. Fines & surrender (§6)

- [ ] `/crime payfine` clears Wanted by charging emeralds; it is **refused** at/above `jailableHeatThreshold`
      (must jail or surrender) and **refused for Red** players until they surrender (unless `redCanPayFine`).
- [ ] `/crime surrender` near a guard / jail / Blue player reduces Heat below the jailable threshold, shortens
      any sentence by `surrenderSentenceReductionPct`, and clears the escaped flag.

## G. Admin & persistence

- [ ] `/crime release <player>` always frees a jailed player.
- [ ] `/crime assignjail` registers an anchor that **persists across a server restart** (in `mcacrime.dat`).

---
_Mark each box when verified. Anything that fails is a Phase 3 bug to fix before tagging the release._

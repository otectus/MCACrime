# S6 — Sand Bottle

Implementation notes for the 0.7.2 Sand Bottle (spec §13, §14; invariants 12, 13, 16).
Scope: what was built, the numbers it ships with, the provenance model, and what only a running
game can confirm.

## Defaults

Tuning lives in `effect/SandExposurePolicy` as constants and is exposed through the COMMON
`[sandBottle]` config block. `McaCrimeConfig` names the policy constants rather than repeating the
numbers, so there is one tuning table.

| Setting | Default | Config key |
|---|---:|---|
| Player item cooldown | 80 ticks | `sandBottle.sandCooldownTicks` |
| Direct-hit duration | 80 ticks | `sandBottle.sandDirectDurationTicks` |
| Maximum splash duration | 40 ticks | `sandBottle.sandSplashDurationTicks` |
| Splash radius | 2.0 blocks | `sandBottle.sandRadius` |
| Post-effect recovery | 60 ticks | `sandBottle.sandRecoveryTicks` |
| Player-versus-player | off | `sandBottle.sandAffectsPlayers` |
| Projectile lifetime | 60 ticks | constant |
| Minimum applied duration | 10 ticks | constant |
| Close-contact perception | 1.5 blocks | constant |
| Maximum affected per impact | 64 | constant |
| Maximum candidates examined | 256 | constant |

Client presentation only: `client.sandParticles` (`NORMAL` / `REDUCED` / `OFF`).

Cross-field checks are in `ConfigValidator.validateSandBottle` and run at setup and on every reload.

## Provenance model

A throw is separated from its impact by up to three seconds, so the projectile carries what was true
at release rather than re-deriving it on landing.

* `SandThrowService` captures, at release: a random launch id, the game tick, whether the thrower was
  validly masked, and the witness scan taken **around the thrower** (there is no victim yet).
* `SandBottleProjectile` stores that snapshot, saves it to NBT, and exposes it as a
  `SandIncidentPolicy.LaunchSnapshot`.
* `IncidentService.commitPlayer` gained an `ObservationSnapshot` overload. When one is supplied the
  witness set and the mask decision come from it verbatim instead of being re-scanned and re-read at
  commit time. `ObservationService.record` gained a matching `observedAt` overload so stored
  observations are dated to the throw, not the landing.
* Per-victim incident ids are `UUID.nameUUIDFromBytes("mcacrime:sand/<launchId>/<victimId>")`. They
  are deterministic (a replayed impact collides with the record it already wrote) and distinct per
  victim (`IncidentService` deduplicates by incident id, so a shared id would silently discard every
  victim after the first).

## Incident entry reused

No `hurt(0)`. Two new entry points, both mirroring the damage path rather than duplicating it:

* `CrimeGate.resolveNonDamageOffender` — the existing numbered gate minus the two checks only a
  `DamageSource` can answer (projectile-to-shooter tracing, environmental exclusion). Protected
  victim, real non-fake player actor, no self-crime and the Legal Target exemption are identical.
* `DamageIncidentService.completeNonDamage` — builds a `CombatIncidentProcessor.Hit` with
  `DamageFinality.Outcome.HARM` already decided and runs it through the same processor, so the
  encounter/self-defence basis, the per-pair harm cooldown and the replay guard all apply. The crime
  id is `CrimeClassifier.classifyHarm(victim)`, detection string `sand_bottle`, damage attribution
  `non_damaging`.

## Avoiding double consequences

* Exactly one `IncidentService.commitPlayer` per genuinely blinded, genuinely hostile victim.
* No reputation call anywhere in the sand path. `IncidentService` already owns `CrimeIntegrationHooks`
  and the `CrimeCommittedEvent` / `CrimeWitnessedEvent` pair; emitting standing here as well is the
  double penalty §14.5 forbids.
* No fake damage event, so no third-party assault listener fires for a blow that never landed.
* `CombatIncidentProcessor.processed` plus the deterministic incident id plus
  `CrimeWorldData.tryAddRecord` give three independent replay guards.
* Nothing is committed for: a miss, a vetoed effect (`addEffect` returned false), self-exposure, a
  defensive throw against an active mugger, or a bottle with no identifiable owner.

## Recovery persistence

`SandRecovery` is the pure rule; `SandRecoveryLedger` is the storage.

* Two timestamps per target — `activeUntil` and `recoveryUntil` — both on the **overworld** game
  clock, so a dimension change mid-effect does not land on an unrelated tick count.
* Authoritative copy: an in-memory map, bounded at 4096 and cleared on `ServerStoppingEvent`.
* Durable copy: a lazy `mcacrime_sand` compound on the entity's Forge persistent data, written on
  every state change and read back once per target after a load.
* Reconciliation on read: a timestamp further ahead than one full effect plus one recovery window is
  treated as a rolled-back world clock and dropped; anything in the past is clear.
* The active duration is never extended, and the recovery window applies against **all** throwers.
* `SandEventHandlers` covers effects this mod did not apply: `MobEffectEvent.Added` gives a
  third-party application the same protection without inventing a Sand Bottle attacker, and
  `Remove` / `Expired` start the recovery window from the moment sight returns.

## Awareness integration

`SandBlindness.canSee` / `blocksSight` is the single question; `NpcAwareness.canSeeNow` is the name
the rest of the mod calls it by. Sand is deliberately **not** folded into `NpcAwareness.isAwake` — a
sanded NPC is awake, talks, and keeps every memory and legal identity it had.

Gated (fresh visual tracking only): `PerceptionRules` / `WitnessChecker.perceive`,
`ObservationService` face-to-face identification, `GuardEnforcement` guard selection (both sites),
`NpcCriminalPursuit` arrest reach and repath, `ThiefBehaviorService` victim selection and guard-risk
sighting, `NpcMuggingService` (new `NpcMugAbortReason.THIEF_BLINDED`).

Not gated: remembered identity, last-seen positions, already-held targets, open cases, hearing.
A sanded pursuing guard walks to the last position it genuinely saw the suspect at and keeps walking
there rather than re-reading live coordinates.

Vanilla mobs go through one common mixin, `SandSensingMixin`, injected at HEAD of
`Sensing.hasLineOfSight` — before the cached-positive return, so a sight check cached earlier in the
tick cannot survive the sand.

## Runtime-only acceptance

These cannot be settled by `compileJava`, `check` or `build` and need a client and a server:

* SAND-01/02 — recipe, actual throw arc and collision, no returned glass bottle, and that neither
  hand nor a stack swap bypasses the shared cooldown.
* SAND-03 — a cancelled spawn, a miss into empty space, and the 60-tick lifetime discard.
* SAND-05 — the radius boundary, a closed doorway, and crowd performance at the 256/64 ceilings.
* SAND-06/07 — tested vanilla melee and ranged mobs, MCA guards, archers and Thieves, including
  whether MCA's brain-driven archers bypass `Sensing` and need an additional hook.
* SAND-08 — a blinded thief mid-mugging aborting with `THIEF_BLINDED`, and the self-defence context
  genuinely surviving the abort.
* SAND-10 — cure, unload/reload, logout, dimension transfer and restart against a live recovery window.
* SAND-11 — sand expiring under an active Blindness or Darkness.
* SAND-12 — the immunity tag entities, team allies, and a server with PvP disabled.
* SAND-17 — a reputation bridge and an assault listener installed, confirming one penalty.
* SAND-19 — that dispensing the item remains ordinary item dispensing.
* SAND-20 — fog readability at several GUI scales, with shaders, and at each `sandParticles` setting.

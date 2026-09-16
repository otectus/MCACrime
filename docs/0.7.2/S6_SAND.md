# S6 — Sand Bottle

What the Sand Bottle is on this port, the numbers it ships with, its provenance model, and what only a
running game can confirm.

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

Cross-field checks are in `config/ConfigValidator#validateSandBottle` and run at setup and on every
reload.

## Provenance model

A throw is separated from its impact by up to three seconds, so the projectile carries what was true
at release rather than re-deriving it on landing.

* `item/SandThrowService` is the single throw path — both `use` and `interactLivingEntity` on
  `item/SandBottleItem` reach it, so the cooldown, the consumption and the projectile are decided in
  one place. It captures at release: a random launch id, the game tick, whether the thrower was validly
  masked, and the witness scan taken **around the thrower** (there is no victim yet).
* `entity/SandBottleProjectile` stores that snapshot, saves it with its own entity data, and exposes it
  as a `effect/SandIncidentPolicy.LaunchSnapshot`. Nothing is added to the spawn packet: 1.21.1 spawns
  thrown entities with vanilla's `ClientboundAddEntityPacket`, and every field the projectile carries
  is launch provenance the server alone reads.
* `incident/IncidentService.commitPlayer` takes an optional `incident/ObservationSnapshot`. When one is
  supplied, the witness set, the mask decision and the observation timestamp come from it verbatim
  instead of being re-scanned and re-read at commit time, so stored observations are dated to the
  throw, not the landing.
* Per-victim incident ids are `UUID.nameUUIDFromBytes("mcacrime:sand/<launchId>/<victimId>")`. They are
  deterministic (a replayed impact collides with the record it already wrote) and distinct per victim
  (`IncidentService` deduplicates by incident id, so a shared id would silently discard every victim
  after the first).

## Incident entry reused

No `hurt(0)`. Two entry points, both mirroring the damage path rather than duplicating it:

* `detect/CrimeGate#resolveNonDamageOffender` — the existing numbered gate minus the two checks only a
  `DamageSource` can answer (projectile-to-shooter tracing, environmental exclusion). Protected victim,
  real non-fake player actor, no self-crime and the Legal Target exemption are identical.
* `detect/DamageIncidentService#completeNonDamage` — builds the same hit the damage path builds, with
  the harm outcome already decided, and runs it through the same processor, so the
  encounter/self-defence basis, the per-pair harm cooldown and the replay guard all apply. The crime id
  is `CrimeClassifier.classifyHarm(victim)`, the detection string is `sand_bottle`, and the damage
  attribution is `non_damaging`.

## Avoiding double consequences

* Exactly one `IncidentService.commitPlayer` per genuinely blinded, genuinely hostile victim.
* No reputation call anywhere in the sand path. `IncidentService` already owns the integration hooks
  and the `CrimeCommittedEvent` / `CrimeWitnessedEvent` pair; emitting standing here as well would be a
  double penalty.
* No synthesised damage event, so no third-party assault listener fires for a blow that never landed.
* The processor's own replay set, the deterministic incident id and the world data's add-record guard
  are three independent replay guards.
* Nothing is committed for: a miss, a vetoed effect, self-exposure, a defensive throw against an active
  mugger, or a bottle with no identifiable owner.

## Recovery persistence

`effect/SandRecovery` is the pure rule; `effect/SandRecoveryLedger` is the storage.

* Two timestamps per target — `activeUntil` and `recoveryUntil` — both on the **overworld** game clock,
  so a dimension change mid-effect does not land on an unrelated tick count.
* Authoritative copy: an in-memory map, bounded at 4096 entries and cleared when the server stops.
* Durable copy: a lazy `mcacrime_sand` compound on the entity's persistent data, written on every state
  change and read back once per target after a load. (This is the NeoForge persistent-data tag, not a
  capability; 1.21.1 data attachments exist for entities, but this ledger uses the tag.)
* Reconciliation on read: a timestamp further ahead than one full effect plus one recovery window is
  treated as a rolled-back world clock and dropped; anything in the past is clear.
* The active duration is never extended, and the recovery window applies against **all** throwers.
* `effect/SandEventHandlers` covers effects this mod did not apply: `MobEffectEvent.Added` gives a
  third-party application the same protection without inventing a Sand Bottle attacker, and `Remove` /
  `Expired` start the recovery window from the moment sight returns. NeoForge's `Remove` names the
  effect as a `Holder`, which is where the identity check reads it from.

## Awareness integration

`effect/SandBlindness` is the single question; `ai/NpcAwareness#canSeeNow` is the name the rest of the
mod calls it by. Sand is deliberately **not** folded into `NpcAwareness.isAwake` — a sanded NPC is
awake, talks, and keeps every memory and legal identity it had.

Gated (fresh visual tracking only): perception and witness checks, face-to-face identification in
`ObservationService`, guard selection in `GuardEnforcement`, arrest reach and repath in
`NpcCriminalPursuit`, victim selection and guard-risk sighting in `ai/thief/ThiefBehaviorService`, and
`mug/npc/NpcMuggingService` (which aborts with `NpcMugAbortReason.THIEF_BLINDED`).

Not gated: remembered identity, last-seen positions, already-held targets, open cases, hearing. A
sanded pursuing guard walks to the last position it genuinely saw the suspect at and keeps walking
there rather than re-reading live coordinates.

Vanilla mobs go through one common mixin, `mixin/SandSensingMixin`, injected at HEAD of
`Sensing.hasLineOfSight` — before the cached-positive return, so a sight check cached earlier in the
tick cannot survive the sand.

## Proposed runtime acceptance — not yet performed on this port

These cannot be settled by `compileJava`, `test` or `build`, need a client and a server, and have not
been run here:

* The recipe, the actual throw arc and collision, no returned glass bottle, and that neither hand nor a
  stack swap bypasses the shared cooldown.
* A cancelled spawn, a miss into empty space, and the 60-tick lifetime discard.
* The radius boundary, a closed doorway, and crowd performance at the 256/64 ceilings.
* Vanilla melee and ranged mobs, MCA guards, archers and Thieves — including whether MCA's brain-driven
  archers bypass `Sensing` and need an additional hook.
* A blinded thief mid-mugging aborting with `THIEF_BLINDED`, and the self-defence context surviving the
  abort.
* Cure, unload/reload, logout, dimension transfer and restart against a live recovery window.
* Sand expiring under an active Blindness or Darkness.
* Immunity-tagged entities, team allies, and a server with PvP disabled.
* A reputation bridge and an assault listener installed, confirming one penalty.
* That dispensing the item remains ordinary item dispensing.
* Fog readability at several GUI scales, with shaders, and at each `sandParticles` setting.

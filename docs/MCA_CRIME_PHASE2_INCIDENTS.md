# Phase 2 development: incident commits and combat finality

Development date: 2026-09-07. Continues the uncommitted witness/memory, custody and
[local justice work](MCA_CRIME_PHASE2_JUSTICE.md) on `d0d4934`. This completes the next bounded
portion of audit Phase 2; it does not complete all of the audit's law/custody architecture.

## Implemented behavior

- `IncidentService` coordinates player direct/action/command/jailbreak commits, NPC commits, and
  paid ransom audit records. `CrimeDetector` remains a compatibility facade.
- Checked ledger insertion rejects a duplicate case ID or read-only store before player state,
  evidence, integration work or notifications. Live adapters require the server thread.
- Both Karma and Heat are installed before incident listeners run. Noncancelable state,
  observation/report/reaction/memory and final crime notifications wait until the commit's
  evidence work is finished. Cancelable evidence preflights still decide whether that evidence
  can be stored. A notification exception is logged without suppressing subsequent queued events.
- NPC mug completion and witnessed interruption use the existing mug transaction ID as case ID.
  NPC committed events come from the coordinator and carry that ID, with no caller-side duplicate
  event and no event claiming a nonexistent case. Completed and interrupted outcomes remain
  mutually exclusive through the existing session consumption and the checked case insertion.
- Ransom audit cases use the demand ID, retain their zero Karma/Heat behavior and assessed amount,
  and release custody through the coordinator after inserting the audit row. They do not repeat
  capture observations, private relationship penalties, or `CrimeCommittedEvent`.
- Forge `LivingDamageEvent` and `LivingDeathEvent` are sampled at LOWEST, including canceled
  events. The original event objects are retained until server tick END so later same-priority
  listeners' final amount/cancellation is respected. No early `amount >= health` prediction remains.
- Positive finite, uncanceled final damage becomes harm. Zero damage, full absorption, fully
  blocked damage and canceled damage do not charge or consume a harm cooldown. Totem survival
  becomes harm. A canceled death becomes harm when damage was applied, even if health remains zero.
- Killing requires a death callback that remains uncanceled and a victim still dead/dying at
  reconciliation. Nested player death callbacks are combined; cancellation of either prevents a
  kill. The terminal hit produces one killing case, rather than an assault plus killing. An earlier
  separate assault remains a separate act, and its cooldown cannot suppress a later killing.
- Custody release and capture/action death cleanup in `CrimeDetectionHandlers` now use the same
  confirmed death result, so a canceled death cannot free a captive through that handler.
- Pending acts also reconcile before vanilla's logout save and before the respawn capability copy,
  with the original capabilities revived for the copy. A rapid disconnect/respawn cannot leave the
  new case written while omitting its pending player consequences from the saved/copied state.
- DamageSource owner attribution covers player melee/projectiles and the loaded player owner of
  a tame animal. Fake players and unknown/environmental causes are not assigned a guessed offender.

## Aggression and force policy

Combat encounters are scoped to one server and dimension, keyed by the actor pair, and expire
following 200 ticks without confirmed harm between them. Records retain the initiating actor,
encounter UUID, classification basis and attribution in bounded case context.

The original attacker cannot claim self-defense merely because a victim retaliates or a guard
selects them as its AI target. An unprovoked NPC/player attack can justify a nonlethal response.
The default defense permission does not authorize killing; lethal force still needs the existing
separate lawful-target permission. A guard with a current local legal basis, a victim resisting a
recent mug threat, or a captive resisting its kidnapper does not create a new defense exemption
for the offender. Broader configurable permitted-force tiers remain future work.

`detection.raidGrace` now forgives only the first nonlethal indirect explosion in an encounter
against a protected non-player, non-responder during an active raid. This is a narrow mechanical
friendly-fire allowance, not an inference of player intent. Direct attacks, arrows, repeated
splash and all killing remain chargeable. Disabling the setting removes that allowance.

Quick logout or dimension travel does not reset recent aggression. The dimension-local store
still expires normally, and server stop clears the new runtime. Restart does not invent an
aggressor from an old AI target. Persisting entire combat encounters and reconstructing every
retaliation across restart remain outside this pass.

## Bounds and compatibility

- Up to 4,096 pending events per server tick; overflow logs once per server runtime and drops
  excess samples rather than allocating without bound. Event references are discarded when the
  batch is drained. A listener causing new damage during notification creates the next batch.
- Up to 4,096 encounters and bounded cooldown/replay entries per active dimension. Encounter
  saturation grants no inferred self-defense or raid allowance. A bounded replay cache handles
  in-process reentry; saved case IDs also reject duplicate commits after reload while retained.
- Existing crime IDs, config layout, mod version 0.6.0, protocol 10 and schema 9 are retained.
  Optional context fields need no migration. Historical cases are not retroactively reclassified.
  A legacy NPC facade caller must stop posting its own `NpcCrimeCommittedEvent` now that the
  coordinator supplies it. No dependency, mixin, registration or new packet is introduced.
- This is ordered server-thread work, not an atomic transaction across player inventories,
  player capability files and SavedData. External currency/provider and save-order crash limits
  remain. Failed notification delivery is not a durable retry queue.

## Verification

The exact locally cached Forge **1.20.1-47.4.10** mapped sources were inspected for
`LivingEntity.actuallyHurt`, `LivingEntity.hurt`/`die` and `Player.actuallyHurt`/`die`: final damage
follows armor/magic/absorption; totem handling precedes death callbacks; death remains cancelable.
The [Forge LivingDamageEvent source](https://raw.githubusercontent.com/MinecraftForge/MinecraftForge/1.20.x/src/main/java/net/minecraftforge/event/entity/living/LivingDamageEvent.java)
also documents that the hook is after those reductions and that cancellation prevents health damage.

`IncidentCommitTest` and `CombatIncidentTest` add 36 regression tests. They compose the production
finality, combat/cooldown and incident services with the real SavedData/ledger, covering final
amount/cancellation, revival, nested deaths, terminal-hit deduplication, earlier assault plus later
killing, callbacks, failed commit, guard/victim retaliation, raid limits, expiry, bounded storage,
separate dimension processors, immutable context persistence and future-schema refusal.
The initial full check passed **960 tests**, with no failures/errors/skips.

Build command (existing offline user cache):

```powershell
$env:GRADLE_USER_HOME = 'C:\Users\crims\.gradle'
& 'C:\Projects\.mcmod-tools\gradlew-quiet.ps1' -Project 'C:\Projects\MCACrime' -Task build -GradleArgs @('--offline') -LogDir 'C:\Projects\MCACrime\build'
```

Final offline release build passed with **960 tests, zero failures/errors/skips**, reobfuscation,
`checkJarContents` (no shaded MCA/companion/Architectury classes), and clean `git diff --check`.
Log: `build/gradle-MCACrime-build-20260907-074007.log`.
Artifact: `build/libs/mcacrime-0.6.0.jar`, **1,261,547 bytes**; SHA-256
`84954cbaedb5d1f5d89efb5f2044c2f9a7c76b34a932388d68697b7155f36719`.
These are service and codec tests, not an in-world Forge/MCA combat fixture or two-client test.

## In-game verification before release

1. Configure an MCA villager as the protected victim. Hit with damage nominally above its health
   while armor/resistance reduces the applied amount below lethal. At tick end, inspect the ledger:
   one assault, no killing. Repeat with full absorption and a shield: zero health damage gives no case.
2. Use a second test mod/listener to cancel or set damage to zero after Crime's LOWEST handler.
   Confirm no charge; immediately perform a real hit and confirm the canceled hit consumed no cooldown.
3. Give the victim a totem, then apply a lethal hit. Confirm assault and survival. Separately cancel
   death with and without restoring health, and restore health later in the same tick. None may
   produce a killing case. A confirmed kill produces one case for that terminal hit.
4. Repeat death cancellation with a captive player and NPC. Crime's confirmed-death cleanup must
   retain custody after cancellation. Check other mods' and Crime's separate death listeners too.
5. Hit a villager/guard first, let it retaliate, then hit and kill it. The original attacker remains
   culpable. Compare with an unprovoked NPC attack: nonlethal defense is exempt, lethal retaliation
   still requires independent lethal permission. Test enabled PvP and separate harm/lethal target rules.
6. Repeat with a locally authorized guard, an active mug victim and a captive attacking its captor.
   Quick logout and portal travel must not reset who started the original dimension's encounter.
   After 200 ticks without harm, an old encounter cannot justify delayed revenge.
7. During an active raid compare an indirect explosion, a second explosion, a direct hit, an arrow,
   and a killing hit. Only the first eligible nonlethal splash is forgiven. Repeat against a guard,
   player and with `raidGrace=false`: no raid allowance.
8. Test player arrows, tame-animal damage, a pet whose owner is unavailable, dispenser arrows,
   fire/fall damage and FakePlayer automation. Only supported responsible players are charged.
9. Complete an NPC mug, interrupt a different mug, and replay their completion paths. Each session
   yields at most one case/committed event and keeps its transaction ID. Pay both family and village
   ransoms: one demand-ID audit row, no repeated Karma/Heat or capture relationship penalty.
10. Attach a listener that reads case, player values and memories during incident state/post-events,
    then another that throws. State is installed before notification, later queued events still run,
    and further combat continues. Restart/reload and inspect the persisted combat context.
11. Damage a protected victim and disconnect before the normal end-of-tick drain; reconnect and
    verify both the case and player values. Repeat with immediate respawn of an offender who dies
    in the same tick, confirming the copied capability includes the queued consequences.

## Remaining Phase 2 work

Full local Heat persistence, dimension-scoped wilderness law, law profiles and force tiers, bounty
capture/disposition unification, and consolidation of older static runtime/custody projections are
still separate work. The current NPC case jurisdiction retains the earlier thief-home rule; a full
crime-location jurisdiction resolver is not introduced here. Ransom economic recovery retains the
existing provider/escrow limits. The subsequent
[death and property recovery pass](MCA_CRIME_PHASE2_DEATH_RECOVERY.md) moves bounty/property/thief
death consequences to this finality queue and documents their remaining live compatibility checks.

Unusual mods that bypass Forge's damage/death hooks, restore entities after this tick's reconciliation,
or nest unrelated damage calls inside damage callbacks still need live compatibility verification.
Full intent tracking, hostile acts stopped entirely by shields/absorption, indirect environmental
attribution and durable combat-encounter recovery are not claimed as implemented.

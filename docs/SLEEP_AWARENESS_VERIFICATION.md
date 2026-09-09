# Sleep-awareness verification — 2026-09-08

This 0.6.0 follow-up applies to Forge 1.20.1 and NeoForge 1.21.1. It addresses sleeping MCA villagers
and archers being assigned perception, combat and movement work.

## Behavior

Sleep blocks visual and auditory witnessing, including the legacy witness scan and observations supplied
with stale witness IDs. Sleeping victims do not invent observations. Previously acquired knowledge is
retained; an awake witness can resume carrying a pending report through the existing social scheduler.
Reports need an awake sender and receiver. Proximity and crime sounds do not wake an NPC automatically.

Guards and archers retain their legal role while sleeping, but are unavailable as responders. New
challenges and pursuit orders are refused; a guard falling asleep invalidates its open challenge before
expiry/refusal is processed. Escorts can transfer to an awake replacement using existing recovery rules.
Archers now share the responder selector with guards and do not acquire civilian fear controllers.

The sleep tick cleanup removes existing attack and walk targets, drawing a bow, and reaction movement
modifiers. It leaves the tick, bed, sleep state and normal damage/schedule wake-up behavior intact.
Civilian reaction controllers stop when their owner sleeps; criminal job identity and memories persist.
Sleeping thieves do not advance mugging sessions. Mugging demands, apologies, dialogue and fence trades
require an awake participant; restraining an asleep target remains possible and the physical leash wakes
that captive. No new config key, network protocol or save-schema change is required.

## Verification

The pure perception regression checks a loud, visible, close-range act produces no awareness during
sleep and restores normal perception after waking. NeoForge server GameTests use real MCA entities:

- No direct, visual, hearing or forged-ID observation for a sleeper, with advanced witnessing enabled
  or disabled; no new flee controller, and an existing reaction ends when the villager sleeps.
- A real archer in its assigned bed rejects challenge/navigation/combat orders; stale brain and mob
  targets and a drawn bow are cleared. After 40 ticks it remains asleep in the bed, with no arrows.
  Actual damage wakes it and it can accept a new combat order.
- Physical restraint wakes a sleeping captive before transport.

- Forge 1.20.1: 1056 tests passed; 0 skipped; zero failures/errors.
  JAR: `C:\Projects\MCACrime\build\libs\mcacrime-0.6.0.jar`
  SHA-256: `adba96795e1c0c121cae7abc178a5c97f5ae971700f6248a16b482c64a5db923`
- NeoForge 1.21.1: 1115 tests passed; 1 skipped; zero failures/errors.
  JAR: `C:\Projects\1.21.1 Ports\MCACrime_1.21.1\build\libs\mcacrime-0.6.0.jar`
  SHA-256: `ee12fd409aa12d8f98831dae8a4f7a03bd97ffd5b2b788077ce103e806c48748`
- All 10 NeoForge server GameTests passed with MCA 7.7.36-beta.3 and the current Locks Reforged
  1.7.5 JAR, including the native cuff pin sequence. The earlier no-Locks server run also passed all 10.
- `checkJarContents` passed in both builds. GameTests are excluded from production JARs. Both Git
  whitespace checks passed. The one skipped NeoForge unit test is the pre-existing authentic legacy
  player-save fixture test; it has not been supplied.

Logs under the Forge repository's `build/sleep-awareness`:

- `gradle-MCACrime-build-20260908-015617.log`
- `gradle-MCACrime_1.21.1-build-20260908-015622.log`
- `gradle-MCACrime_1.21.1-runGameTestServer-20260908-015402.log`

The local Locks dependency changed to its sequenced pin API during verification. The NeoForge cuff
adapter now compiles with that API while retaining its legacy entry point; the integration test selects
and exercises the installed protocol. No companion classes are bundled.


## Manual check

Use the rebuilt 0.6.0 JAR for the matching loader. With a villager, guard and archer asleep, walk past
as a wanted player, display a weapon and commit a nearby visible/noisy crime. They should remain asleep
and acquire no observation. Repeat after they wake; perception and enforcement should resume. Also
check a conversation and fence trade interrupted by sleep. Client animation has not been inspected in
an interactive session; server position, sleep state, targets and arrow spawning are covered above.

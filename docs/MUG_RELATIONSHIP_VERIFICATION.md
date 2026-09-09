# Mugging relationship protection

Configuration: `criminalJobs.thief.mugProtectionHearts` in `config/mcacrime-common.toml`.
Default: **50**. Range: **-1 to 1000**. The comparison is inclusive; **-1** disables only
relationship protection, while **0** also protects neutral relationships.

The value uses MCA's hearts for the specific villager/player pair. It applies to persisted
thief jobs whether or not the thief is displayed as an MCA profession. It does not grant
immunity from guards enforcing crimes, change player-initiated mugging, or refund a theft
that already committed. MCA's existing missing-heart-lookup fallback is zero hearts.

## Automated coverage

- `NpcMugProtectionTest`: default, inclusive boundaries, custom/zero/disabled thresholds,
  negative and extreme heart values, changing inputs, protected target scoring and transitions.
- `NpcMugSessionTest`: timer bounds, victim claims, premature completion, aborted phase and
  released claims when final participant resolution fails.
- Existing thief state, target selection, custody recovery, theft planner, stolen-property,
  localization and MCA compatibility checks run with the full Gradle build.

## NeoForge 1.21.1 server verification

`RelationshipAndHeatGameTests` passed with real MCA villagers on the dedicated server:

- Exact 49/50-heart boundary, separate player relationships, custom 75-heart, zero and disabled
  thresholds, and continued creative protection when relationship protection is disabled.
- A `CrimeAttemptEvent.Started` listener raising hearts to 50 prevents the session opening.
- Raising hearts during a session or changing the threshold immediately before completion
  aborts the session, releases the victim, preserves their emeralds and sends the friendship
  cancellation payload to close the HUD.

The full release build passed 1,137 unit tests, with one existing disabled legacy-save fixture
test. All 21 dedicated-server GameTests passed with MCA and Locks Reforged present. These
tests record outgoing packets; visual rendering and the remaining manual scenarios below
still require a client.

## Manual client verification (not executed)

Use an awake, uncuffed thief and an unarmed survival player, outside jail and guard range.
Allow the scan to run, or use `/crime mugtest` with the intended thief nearest to the player.

1. At 49 hearts with the default setting, verify a normal mugging can start and finish.
   At exactly 50 and above, verify no approach, threat, progress bar or theft starts.
2. Give two players different heart values with one thief, then give one player different
   heart values with two thieves. Verify protection follows each pair independently.
3. Test thresholds 75, 0 and -1. Reload the common config during approach and while the bar
   is filling. A newly protected victim must be released before any property moves.
4. Raise hearts to the threshold during approach and during the mug, including its final
   tick. The active bar must close with the friendship message, the incident must close,
   and the thief must leave the attempt. Lowering hearts later permits future attempts.
5. Change the victim to creative, spectator, invulnerable, jailed or captive during an
   attempt. Verify it aborts without taking anything. Repeat with a sleeping/captive thief,
   a removed thief job, and the thieves master switch disabled.
6. With a weapon-check interval greater than one tick, draw a weapon just before completion.
   Verify the final check still stops theft. Check moving beyond mug reach and changing
   dimensions as well.
7. With an integration listener, change hearts or eligibility in `CrimeAttemptEvent.Started`
   or a currency balance callback. Verify no protected attempt opens or commits. Repeat
   `/crime mugtest` during a running session and verify it cannot redirect the thief.

Runtime checks share the same participant gate during scanning, approach, session start,
every active tick and both sides of theft preparation. Weapon/range checks also run at
start and completion. Final aborts use the same HUD, incident and attempt-event cleanup
as an ordinary interrupted session.

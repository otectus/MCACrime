# Wanted Heat pursuit

Wanted status authorizes interception independently of reported ledger cases. The live
`JusticeService.forGuard` assessment reads `CrimeState.isWanted`, so commands, crime gains,
login state and config reloads all use the same threshold. An active refusal is also an
independent basis until resistance expires or is resolved.

The default `bands.wantedHeatThreshold` remains 50. Thus 100 Heat qualifies; a pack that
changes that threshold still controls the boundary. The threshold is inclusive.

An available nearby guard or archer approaches on the next enforcement scan (10 ticks by
default), with the normal 16-block acquisition radius and 6-block visible conversation
range. No report is needed. Sleeping/captive/unloaded responders and responders already
escorting or pursuing somebody else cannot take the encounter. Existing arrest and
recovery phases keep their normal protections; challenges can still be disabled by config.

Wanted status does not add, reveal, settle or manufacture a crime record. Local case lists
and fine quotes retain their existing jurisdiction rules. A stop with no local cases
requests surrender, explains its basis in chat and on Ask Charges, and offers no invented
fine. Surrender uses the normal Heat-based sentence. Refusal permits force; losing Wanted
status before refusal closes a stop that has no remaining basis.

## Automated coverage

- `WantedPursuitTest`: 100 Heat with an empty ledger, configurable inclusive boundaries,
  declining Heat, private-case isolation across jurisdictions, no invented fine, refusal
  after Heat declines, response/custody phases and read-only world protection.
- `CrimeCommandTest`: command-block permission and parsing for `crime set heat TestPlayer 100`.
- Existing local justice, settlement, arrest, guard response, MCA role compatibility and
  localization checks run in the full Gradle build.

## In-game checks (not run by the unit suite)

1. With defaults and a clean player ledger, place an awake guard 10 blocks from a survival
   player in an open area. Run `/crime set heat <player> 100`, including from a command block.
   The guard should approach on the next scan and confront within 6 blocks. Repeat with an
   archer, a player who has positive hearts with that NPC, and a guard outside a village.
2. Ask Charges: the guard should cite Wanted Heat, with no fake ledger charges. Surrender
   should proceed through the normal arrest/escort/sentence flow. Refusal or timeout should
   permit force. The displayed response window must remain at least 15 seconds.
3. Clear Heat while approaching and during an unanswered challenge. With no cases, escape,
   active kidnapping or resistance, enforcement should stop. Repeat after refusal: its
   separate resistance timer should continue to authorize pursuit.
4. Test threshold 100 with Heat 99, 100 and 101 (keep `jailableHeatThreshold` at least as high
   when editing the config). Reload thresholds and relog an already-Wanted player. Crossing
   a boundary should have the same effect as a command, without a new witnessed crime.
5. Repeat with two players, different village jurisdictions, a sleeping guard, a captive
   guard and an already-assigned escort. Available guards/archers should respond while
   unavailable ones retain their existing duties. Existing prisoners must not be challenged
   again through the bars.

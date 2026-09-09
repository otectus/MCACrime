# Guard confrontation rendering crash — 0.6.4

## Report and diagnosis

[Reported crash](https://paste.kostromdan.dev/mclogs/8VsrGyW), dated 2026-09-08:
Minecraft 1.21.1, NeoForge 21.1.249, MCA: Crime 0.6.0. The player reported the crash
when clicking **Surrender**. The report's exception is `StackOverflowError: Rendering screen`.
Its repeated frames alternate between `GuardChallengeScreen.renderBackground` (line 168
in that release) and Minecraft's `Screen.render` (line 122).

Minecraft 1.21.1's `Screen.render` invokes the screen's background hook before drawing
widgets. The confrontation background hook called `super.render`, immediately invoking
itself again. That duplicated tail also tried to draw the countdown and acknowledge the
displayed menu. Rendering could never finish, regardless of the chosen response. The
translation frames at the top are where the stack ran out, not evidence of a bad translation.
The report does not establish a separate failure in the server's surrender/arrest processing.

## Changes in both projects

- **NeoForge 1.21.1:** remove the recursive widget-rendering call and the duplicated status
  and acknowledgment calls from `renderBackground`. Keep these operations in `render`.
  The normal order remains background/panel, widgets, status, then display acknowledgment.
- **Forge 1.20.1:** this version did not contain the recursive call. Separate the existing
  panel drawing into its one-argument background override, retaining the explicit call
  from `render` required by that Minecraft version. Widgets, status and acknowledgment
  stay in `render`, matching the responsibilities used in the port.
- Set both mod versions to 0.6.4. Surrender packets and server punishment rules are unchanged.

## Automated verification

`ScreenRenderLifecycleTest` reads compiled screen bytecode without loading client classes
or needing a graphics context. It checks that screen background hooks do not call screen
rendering, and that the guard screen renders widgets, draws status and acknowledges display
once each, in that order, from the main render method.

Both new checks failed against the original NeoForge code, identifying the recursive call
and its duplicate rendering operation. Both pass with the fix.

Full offline release builds passed with the Reputation and Quests adapters required on both
loaders, and the Locks adapter additionally required on NeoForge:

| Build | Passed | Skipped | Failures/errors | Packaging |
| --- | ---: | ---: | ---: | --- |
| Forge 1.20.1 | 1,092 | 0 | 0 | `checkJarContents` passed; reobfuscated jar built |
| NeoForge 1.21.1 | 1,151 | 1 | 0 | `checkJarContents` passed |

The skipped NeoForge test is the previously disabled captured Forge player-fixture check
(`LegacyFixturePresenceTest`, disabled because its fixture has not yet been captured).
Both jars' manifest and loader metadata were verified to contain version 0.6.4.

## Manual client verification

This report does not claim a live reproduction in the player's modpack. On each loader:

1. Approach an available guard or archer while Wanted (for example, set Heat to 100 in a
   test world). Verify the confrontation renders continuously and its countdown is visible.
2. Click **Surrender**. Verify the screen closes and the configured arrest/escort/custody
   flow proceeds without a client crash. Repeat with both a guard and an archer.
3. Exercise both the three-button detention/non-finable offer and four-button finable offer.
   Ask to see charges, then surrender; also check fine payment and refusal in fresh encounters.
4. Close and reopen an unanswered confrontation, and let another expire. Verify reopening
   does not restart the response window and expiry still follows the configured enforcement.
5. Repeat in the reported modpack on NeoForge 21.1.249 to cover its additional screen mixins.

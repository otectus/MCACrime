# Cuff lockpicking verification

Implementation is built against the optional Locks Reforged 1.7.3 API. Locks classes are excluded from
the shipped jar and kept out of common-code linkage. Unit tests exercise progress, replay/rate/bounds
rejection, inventory-requirement policy, saved combinations, default config and escaped sentence
persistence. They do not instantiate a live Forge server or native client screen.

Before release, run this matrix with production MCA and Locks jars on a dedicated server and two
clients. Include Locks absent, published 1.7.3, 1.7.4 if distributing that build, and any newer build
actually intended for support. Do not claim newer protocol compatibility from source inspection alone.

1. Kidnap a player with ordinary cuffs, then locked cuffs. Escape through the captive panel and
   `/crime escape`. Confirm five/seven pins and the native Locks screen. No block lock should appear
   in the world. Opening the puzzle while holding another item must not move or delete that item.
2. Guess a correct prefix, then a wrong pin. The native screen and server must both reset progress.
   Close/reopen and reconnect: the combination stays the same, progress starts over, and neither
   action frees the player. Completing the combination releases exactly once and removes restraints
   on both clients. Confirm inventory/cursor contents remain intact.
3. Leave `kidnapping.cuffEscapeRequiresLockpick = false`: solve without a pick, even with Locks'
   itemless block-lock option disabled. Enable the Crime option: deny attempts with no pick; permit
   main inventory, hotbar and offhand picks, including tagged addon picks. Removal/loss of the last
   pick or enabling the requirement during an itemless attempt must invalidate the session.
4. Try stale/out-of-order/burst pin packets, death, dimension changes, rescue and recapture during a
   puzzle. No old menu may release a replacement custody record. A screen close must not unlock a
   nearby real Locks lock or produce loot. Both native packet shapes need this check when supported.
5. With Locks present, set cuff escape chances to 1 and enable distance escape. Cuffs must still
   require the puzzle and tether the player back. Rope must retain its ordinary behavior. Captor
   release, rescue, disconnect grace, administration and the captivity cap must still work.
6. Escape a guard escort using the puzzle. Verify jailbreak, original sentence UUID/case membership,
   remaining sentence and surrender credit are retained. The escort stops and cuffs disappear.
   Restart while escaped: sentence credit remains paused. Returning inside the jail radius alone
   must not resume it. Recapture/surrender resumes the original sentence without a second discount.
   Also test jail custody with actual cuffs; ordinary guard intake removes cuffs and therefore has
   no cuff-picking action. Check containment and physical jail modes.
7. Remove Locks in a disposable test profile. Ordinary cuffs use their original timer/chance.
   Locked cuffs with zero chance refuse timed escape; a nonzero chance uses
   `escapeWorkTicksLockedCuffs` (default 1,200), with progress and restart continuity. The optional
   pick requirement has no effect without Locks. Record screenshots/logs and exact jar versions.

The existing sentence HUD extrapolates a countdown between server updates and does not yet carry
an explicit paused flag (see Remaining Work Review). Judge escaped sentence credit from persisted
server state/API as well as the HUD; completing paused-timer presentation remains follow-up work.

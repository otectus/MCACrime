# Townstead integration verification — 2026-09-17

The tested Forge integration passes the automated matrix after fixes to equipment ownership,
guard/reaction navigation, storage dimensions, and config reload behavior. This is evidence for
specified scenarios, not a guarantee that every gameplay interaction is bug-free.

## Environment and results

Repository base: `70e5e56d98f2926f39e6e16d38296f3ea4dfade8`, plus the changes accompanying this report.
Compilation: Minecraft 1.20.1 / Forge 47.4.10, official mappings, OpenJDK 17.0.19.
Runtime: production jars in Forge 47.4.23 dedicated servers, fresh isolated flat worlds.

| Verification | Result |
|---|---|
| Full JUnit suite | 1,924 discovered; 1,908 passed, 16 skipped; no failures/errors |
| Both Townstead jar probes | 12 passed; no skips; all 12 declared capabilities bind in both variants |
| Release build and `checkJarContents` | Passed; no companion classes bundled |
| Modern Townstead + MCA 7.7.1-alpha.2 | 27 runtime checks passed |
| Legacy Townstead + MCA 7.7.0-beta.2 + Architectury 9.2.14 | 27 runtime checks passed |
| MCA without Townstead | 4 runtime checks passed |
| Townstead installed, integration disabled at startup | 4 runtime checks passed |
| Townstead + MCA + Patchouli, without Crime or the harness | Control server starts and stops successfully |
| Explicitly supplied nonexistent probe jar | Expected build failure, with the invalid path identified |

Eight ordinary-suite skips are Townstead jar checks; those are exercised by the separate probe task.
The other eight skips are seven `ItemCurrencyInventoryTest` cases and one
`ProfessionRestorationRevisionTest` case. They are not counted as passes.

## Regressions and fixes

- **Equipment owner:** cleanup previously consulted the most recent villager tick rather than the
  argument to `WorkToolTicker.forget`. A foreign context left the target's record behind and could
  delete another villager's record. Copy, restore, and forget hooks now capture the actual argument
  as a vanilla `LivingEntity` using `@Coerce`, supporting both MCA package roots. Cleanup remains
  active when recording is disabled, preventing obsolete stashes from returning after re-enabling.
- **Guard rest:** with a foreign tick context, Townstead's rest ticker erased a claimed guard's
  escort walk order. Both redirects now capture the actual guard argument.
- **Reaction handover:** refusing new reaction locks did not handle an animation that started before
  the escort. Existing reactions continued erasing escort walk orders. Freeze and saved-walk
  restoration now yield while Crime's activity policy owns movement.
- **Storage:** sourcing protection previously used the last ticking villager's dimension and had no
  usable fallback on ordinary multi-dimension servers. It failed outside entity ticks and could
  consult the wrong dimension. The constructor hook now captures each search context's own level.
- **Config:** a bridge bound at startup continued serving queries after the global switch was disabled;
  a startup-disabled bridge had no reload path to activate it. Queries and cached awareness now honor
  the live switch, and common-config reloads rebind and invalidate snapshots.
- **Probe reliability:** an explicitly supplied missing jar could silently turn jar tests into skips.
  The task now rejects missing/unreadable inputs and supplies absolute paths. The mixin probe now
  actually asserts argument counts, which its earlier documentation promised but did not check.

Baseline failures were reproduced in disposable servers before the relevant fixes. The final
runtime harness verifies ordinary behavior as well as the regression cases, including free guards,
free reaction locks, work-tool restoration, unclaimed storage, disabled property protection, and
cleanup with recording disabled.

## Live behavior exercised

Real MCA villagers are created through the registry. Townstead state supplies identity, adult life
stage, needs, schedule, and calendar readings. Collapse disables observation and guard response;
recovery restores observation. Cache reuse and invalidation are checked. Empty building enumeration
and nonexistent village revision/spirit queries return the appropriate empty or unavailable result.

Real transformed Townstead methods verify guard walk-order preservation, new reaction locks,
preexisting reaction handover, work-tool copy provenance and restoration, cleanup with a different
previous villager, and storage policies outside entity ticks and across Overworld/Nether contexts.

A real chest provides custody food: care takes exactly one bread, a same-tick repeat does not take
another, and feeding preserves the remaining sentence. Critical hunger with no usable supplies
enters recovery; saving/loading the custody record and recovering preserve the sentence. These
checks verify meal initiation and supply accounting, not every food's eventual effect or remainder.

The test-only mod and runner are under `tools/townstead/`. The runner uses fresh output directories,
requires a result file from the current run, fails on any assertion, and shuts down each server.
Reproduction commands and fixture details are in [its README](../../tools/townstead/README.md).

## Remaining coverage and upstream observations

- No interactive client was exercised. Dialogue button layout, camera/HUD restoration, asynchronous
  reopening, and client/server packet round trips still need a client playtest. Client method and
  field targets were checked in both real jars; client mixins were confirmed absent on the server.
- No two-player session, long-duration settlement soak, or populated-building lifecycle was run.
  Existing unit tests cover property attribution, facilities/reservations, civic contracts, migrations,
  and equipment policy; this report does not relabel those as live multiplayer or whole-world tests.
- Full villager death/unload/reload item accounting and consumption of every food/container type
  remain outside the runtime fixtures.
- Each Townstead server logged six nonfatal `RuntimeDistCleaner` errors about loading client Minecraft
  on a dedicated server. The same six errors reproduce in the companion-only control with Crime
  and the harness absent. They are upstream of this integration and were not patched here. The
  Crime + MCA server without Townstead logged none. All tested servers still reached ready state
  and shut down cleanly. Optional Townstead compatibility-target warnings also remain in its logs.
- Work suspension remains the documented partial start gate; this work does not add a full upstream
  task-cancellation/rollback API or rig-attachment capability.

## Artifacts

The tested companion jars are source-built Townstead 0.7.7 variants paired with their matching MCA
package layouts. Exact SHA-256 hashes and final local result paths follow below.

| Artifact | SHA-256 |
|---|---|
| `mcacrime-0.7.4.jar` | `5e19832bbbae6885f9f319ec1e39d2d6170449dbf887de4a9de1537b94623189` |
| `townstead-runtime-checks-0.7.4.jar` | `c4319342a065e85146e7219be0911bc4872523e1ed22598df66b59e237d4cdee` |
| `townstead-mca-legacy-0.7.7+1.20.1.jar` | `d16d907a2f773eb755cf51930dfe43705a4291e850a3c3c30ecda158cf20b0e7` |
| `townstead-mca-modern-0.7.7+1.20.1.jar` | `407c37e53e52f255a024405884d68072a465406144dd86181cfa5078733a6519` |
| `mca-7.7.0-beta.2+1.20.1.jar` | `a202fbf1354786e7beeaabddfc9e79396b0a088acbec3b996837ebb0d9883c0a` |
| `mca-7.7.1-alpha.2+1.20.1.jar` | `ea37239455860eedfdbe0b30338a43d24a7b47af489da180a5b18dd8f167d16d` |
| `Patchouli-1.20.1-85-FORGE.jar` | `05f7b5d52f6b8f0fd7f8b4822fa07a192d1394d83a264309c9d221d6d4fd21c5` |
| `architectury-9.2.14-forge.jar` | `218b471d0b8a1f6cda14cfc1beb9eeb0df54304500acc6c5613d9b88ec65d9af` |

Local final run outputs (logs and assertion results):

- [Modern](../../build/townstead-runtime/final-modern-9ximuf3f/runtime-results.txt)
- [Legacy](../../build/townstead-runtime/final-legacy-nnpg0p8j/runtime-results.txt)
- [Absent](../../build/townstead-runtime/final-absent-4c4nb1z2/runtime-results.txt)
- [Disabled](../../build/townstead-runtime/final-disabled-8yhnj4fc/runtime-results.txt)
- [Companion-only control log](../../build/townstead-runtime/control-qb_wwziz/console.log)
- [Initial targeted regression failures](../../build/townstead-runtime/baseline-results.txt)
- [Full JUnit report](../../build/reports/tests/test/index.html)
- [Townstead jar probe report](../../build/reports/tests/townsteadProbeTest/index.html)

The initial baseline file includes an uninitialized life-stage fixture failure; that fixture was
corrected by assigning the bundled adult Overworlder root before the final checks. It is not counted
as a product defect. The later guard and reaction handover failures are retained in
`build/townstead-runtime/modern-f36hh10o` and `build/townstead-runtime/modern-m2lqqx2p` respectively.
Build output is retained in `/tmp/mcacrime-townstead-final-build.log`; the expected negative-probe
failure is in `/tmp/mcacrime-townstead-invalid-probe.log`.

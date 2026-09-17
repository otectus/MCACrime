# Townstead integration verification — NeoForge 1.21.1 — 2026-09-17

The Forge Townstead fixes from commit `d5b738d` are ported to the native NeoForge integration.
The automated checks below pass. Client gameplay and broader settlement scenarios still need
playtesting; the results do not establish that every possible interaction is bug-free.

## Environment and results

Repository base: `b8a47b6913c68e9fd0080a6e2129c2754641ae13`, plus the changes accompanying this report.
Minecraft 1.21.1, NeoForge 21.1.248, Java 21, ModDevGradle 2.0.141. Production mixins use Mojang
names with `remap = false` and no refmap. Both configs remain declared in `neoforge.mods.toml`.

| Verification | Result |
|---|---|
| Full JUnit suite | 2,002 discovered; 1,993 passed, 9 skipped; no failures/errors |
| Townstead jar probes | 12 passed, no skips; all 12 declared reflective capabilities bind |
| Release build and `checkJarContents` | Passed; 1,280 entries, Java 21 classes, no shaded companions, Forge or relocated-MCA bytecode references |
| NeoForge GameTests | All 32 required tests passed |
| Townstead 0.7.7 + MCA 7.7.36-beta.3 + Patchouli 93 | 27 production-server checks passed |
| MCA without Townstead | 4 production-server checks passed |
| Townstead installed, integration disabled at startup | 4 production-server checks passed |
| Townstead + MCA + Patchouli, without Crime or the harness | Control server starts and stops successfully |
| Explicitly supplied nonexistent probe jar | Expected failure identifying the invalid path |

Eight ordinary-suite skips are Townstead jar tests, exercised by the separate probe task. The ninth
is `LegacyFixturePresenceTest`, disabled because its captured Forge player-data fixture is absent.
It is not counted as a pass. The probe fleet includes MCA 7.7.33 and 7.7.36-beta.3; production runtime
uses the required 7.7.36-beta.3 release. Townstead 0.7.7 is a source-built NeoForge artifact.

The release was built with the Reputation, Quests, and Locks adapters required and included, using
the companion projects' compiled Java classes. The Townstead production scenarios install MCA,
Townstead, and Patchouli; they do not exercise live Reputation/Quests gameplay. The GameTest server
also installs Locks Reforged 1.7.5 for cuff behavior. GameTests run in the development environment;
the separate Townstead fixtures run the packaged production jar in fresh dedicated servers.

## Fixes and parity

- Work-tool copy, restore, and forget hooks use the actual villager argument through `@Coerce
  LivingEntity`. A previous entity tick cannot misattribute the stash or erase another villager's
  record. Cleanup still runs with recording disabled, preventing stale equipment from returning
  when the switch is enabled again.
- Guard-rest redirects use the actual guard argument and preserve claimed escort walk orders
  outside that guard's entity tick.
- Existing reactions yield their freeze and saved-walk restoration after Crime takes movement
  control, as well as refusing new incompatible reaction locks.
- Storage searches capture their constructor's `ServerLevel`, preserving protected containers
  outside entity ticks and distinguishing Overworld and Nether policies at the same coordinates.
- The live global switch gates queries and cached awareness. Common-config reloads clear snapshots
  and rebind the bridge, including enabling an integration disabled at startup.
- Explicitly missing/unreadable probe jars fail the task; mixin probes assert target argument counts.
- The NeoForge world-data GameTest expected schema 12 despite this release already writing schema
  14. The initial run passed 31 tests and failed that expectation. Correcting the expectation to 14
  restores all 32 passes while retaining the real registry-aware save/load and counter assertions.

Native NeoForge event types, registries, data components, payloads, and mixin metadata are preserved.
The test harness uses NeoForge events and registry lookups and needs no Forge reobfuscation task.
The runner requires an explicit installed NeoForge version so installations with multiple versions
cannot select a launcher ambiguously.

## Live behavior exercised

The 27 Townstead checks create real MCA villagers, bind identity, adult life stage, needs, schedule,
and calendar, and check empty-building enumeration and missing village revision/spirit results.
Snapshot reuse/invalidation and collapse/recovery awareness are verified.

Transformed Townstead methods exercise guard navigation, new reaction locks, preexisting reaction
handover, work-tool copies and restoration, cleanup following another villager's tick, and storage
protection outside a tick and across dimensions. Ordinary behavior is checked alongside each policy:
free guards and reaction locks, unclaimed storage, and disabled property protection continue working.

Custody care consumes exactly one bread from a real supply chest, a same-tick repeat does not consume
another, and the sentence remains intact. Non-food cannot initiate eating. Missing supplies under
critical hunger enter recovery; custody-record save/load and recovery preserve the sentence.
The checks cover meal initiation and accounting, not every food's eventual effect or remainder.

The 32 existing GameTests cover damage/death loot, cells, cuffs, thief occupation/workstation behavior,
relationships/heat, sand bottles, sleep awareness, event cancellation, and world-data round trips.
They add broader regression coverage without claiming all these scenarios were run with Townstead.

## Coverage limits and upstream observations

- No interactive client or two-player session was run. Dialogue layout, camera/HUD restoration,
  asynchronous reopening, and packet round trips remain client playtest work. Client targets were
  checked in the real jar, and the dedicated server excludes the dialogue mixin.
- No long-duration settlement soak, populated-building lifecycle, or whole-world property/civic
  save-quit-reload scenario was run. Unit coverage of attribution, facilities/reservations, civic
  contracts, and migrations is not a substitute for these gameplay scenarios.
- Full Townstead work-tool death/unload/reload accounting and every food/container type remain
  outside the runtime fixtures. Existing death-loot GameTests are separate coverage without Townstead.
- Townstead servers log eight nonfatal `RuntimeDistCleaner` errors attempting to load client
  Minecraft on a dedicated server. The same eight reproduce in the companion-only control with
  Crime and the harness absent. The Crime + MCA server without Townstead logs none. All reach
  ready state and stop cleanly. Missing optional Townstead compatibility-target warnings also remain.
- Work suspension remains partial start-gating. No upstream task-cancellation/rollback or rig
  attachment API is added by these fixes.

## Reproduction and artifacts

See [the harness README](../../tools/townstead/README.md) for build, probe, production-server,
and GameTest commands. The runner reads installed libraries and copies selected mods into fresh
flat worlds under `build/townstead-runtime`. It requires a current result file and stops each server.

The same Crime jar hash was confirmed before and after the GameTest-only correction. The final
harness uses NeoForge's native `type="required"` dependency metadata; its 27-check Townstead scenario
was rerun after that metadata adjustment. The absent/disabled runs used the otherwise identical
harness before that metadata spelling was corrected.

| Artifact | SHA-256 |
|---|---|
| `mcacrime-0.7.4.jar` | `927636e8ea676b6b5386b78438387feef303b4945c688abaa7df67c7652451bb` |
| `townstead-runtime-checks-0.7.4.jar` | `383c299a44dd8947e7e058ed83487a2d2a376b567f6e827c0c48992c85893271` |
| `townstead-0.7.7+1.21.1.jar` | `6dca76cb057143b8e5d6a9b73bd88eddba7bdbba3bbe92e550fe87479890a63e` |
| `mca-neoforge-7.7.36-beta.3+1.21.1.jar` | `de4763d34a41cb84ffa392b87cdb23191beddda2323b56552a1a2fcd7c436fc3` |
| `Patchouli-1.21.1-93-NEOFORGE.jar` | `959af52ed6640c316c3a8469203420be4aeea11ad6603890ba83bf48f5d9f993` |
| `locks_reforged-neoforge-1.21.1-1.7.5.jar` | `f7fac9966163e46d8e923ab60f5d120c5bbb65d6dc6cd0489c0a0c2adb3c663e` |

Local evidence (generated build outputs are not committed):

- [Townstead final production results](../../build/townstead-runtime/final-neoforge-m_7zr_s4/runtime-results.txt)
- [Townstead initial production results](../../build/townstead-runtime/neoforge-etjvwiht/runtime-results.txt)
- [Absent results](../../build/townstead-runtime/absent-upqe8nxz/runtime-results.txt)
- [Disabled results](../../build/townstead-runtime/disabled-5fms0yz_/runtime-results.txt)
- [Companion-only control log](../../build/townstead-runtime/control-j4hf0ask/console.log)
- [JUnit report](../../build/reports/tests/test/index.html)
- [Townstead probe report](../../build/reports/tests/townsteadProbeTest/index.html)

The final build log is `/tmp/mcacrime-neoforge-townstead-final-build.log`; the GameTest logs are
`/tmp/mcacrime-neoforge-gametest.log` (initial stale expectation) and
`/tmp/mcacrime-neoforge-gametest-final.log` (32 passes). Expected invalid-path rejection is recorded
in `/tmp/mcacrime-neoforge-invalid-probe.log`. These paths belong to the isolated verification
checkout, not an end-user server installation.

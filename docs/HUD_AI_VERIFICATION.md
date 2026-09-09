# 0.6.0 HUD and AI refinement verification

The original build evidence below is from the Forge baseline. NeoForge 1.21.1 validation and
platform-specific coverage are recorded in [the parity report](NEOFORGE_PARITY_2026-09-08.md).

Final build on 2026-09-08 passed **1,055 tests across 136 suites**, with no failures, errors or skips.
`checkJarContents` and `git diff --check` passed. Build log:
`build/gradle-MCACrime-build-20260908-002852.log`. Artifact: `build/libs/mcacrime-0.6.0.jar`.
SHA-256: `F67E87A9304742FD90EE72AE8879D52BCAE1254C3DA01FF3D3939134010C6239`.

Automated checks cover shared HUD bounds at GUI widths 320/427/640/854/1920, long labels, sentence
alone, old default migration and extreme offsets; 15-second minimum response time, delivery delay,
bounded missing acknowledgment and replay protection; navigation pace, guard reaction ownership,
detour progress and useful intermediate route endpoints; occupied cell geometry and exterior intake
stands. The full build also checks optional class loading and production jar contents.

No live Minecraft session was run for this pass. The following checks remain necessary before release:

| Scenario | Expected result |
|---|---|
| Old client config TOP_LEFT, offsets 4/4 | One-time migration to BOTTOM_LEFT; restarting preserves the result; a later deliberate TOP_LEFT choice remains selectable. |
| Heat + Wanted + Sentence, or Sentence alone | One panel at the bottom left; no empty reserved row. Test GUI scales 1–4 and long translations. |
| MCA: Quests log, recent chat and queued chat | Quest log stays clear; Crime sits below passive chat and left of the hotbar. Opening chat hides the Crime panel until chat closes. |
| Guard challenge, ordinary latency and delayed first frame | Fifteen full seconds displayed; guard stays facing player; server closes on expiry. A longer configured window still works. |
| Ask charges, failed payment, reopen, resize, duplicate acknowledgment | Same encounter deadline. No repeated fresh response windows. Missing acknowledgment closes after the bounded allowance plus the configured response window. |
| Armed player threatens healthy/injured or empty-handed guard | No civilian panic/compliance or fear refusal. Guard retains enforcement/combat behavior; mere weapon possession does not itself grant authority to attack. |
| Direct victim panic, interrupted mugging, witness seeks help | No legacy navigation override, no flee during compliance/captivity, measured movement, stable usable routes and cleanup after recovery. |
| Generated cell in crowded village | Search selects an empty footprint; neither bystanders nor escort are enclosed. Only prisoner moves into the finished cell. |
| Old cell already contains villagers/guards | Bounded sweep moves non-prisoner NPCs to safe exterior stands. If none exists, retry later. Prisoner remains confined. |
| Escort around walls, slopes and a distant assigned jail | Try reachable intake stands and bounded route segments. Detours count as progress. Guard waits for prisoner; stalled/failed routes retain intake fallback. |
| Player and NPC surrender/capture, guard death, logout/restart, release | Custody/escorts reconcile, route caches clear and guards stop walking at intake. Verify Minecraft's lead behavior on stairs and narrow corners. |

Network protocol 11 requires matching clients/server. World schema remains 10. Runtime MCA navigation
and third-party HUD modifications may require further tuning after these in-game checks.

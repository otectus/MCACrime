# Family loyalty, accomplices, mugging frequency and contraband verification — 0.7.0

This applies to Forge 1.20.1. It covers three additions (family loyalty, family accomplices and
bail, configurable contraband) and one fix (excessive NPC mugging of the same player).

## The mugging diagnosis

The complaint was that a player could be robbed by several different villager thieves in quick
succession. Concurrent overlap was already impossible — `mug/npc/NpcMuggingService` claims a victim
by UUID before a session starts, so two thieves cannot be mid-mugging on the same player at once.
The defect was temporal and cumulative instead:

- The only cooldown that existed was scoped to the **thief**, not the victim:
  `job/WorldCriminalJobService.touchMug` stamps `CriminalVillagerRecord.lastMugAt`, and
  `ai/thief/ThiefBehaviorService.onMugCooldown` checks only that one thief's own stamp against
  `mugCooldownTicks`. A second thief with no record of its own was never blocked by the first
  thief's cooldown.
- There was no grace period at all after a respawn, a login, or a release from custody — a player
  who had just been mugged, died, and respawned nearby was immediately eligible again.
- Thieves could accumulate without bound in one jurisdiction: `job/CriminalJobAssignmentSweep`
  throttles how *often* a village produces a thief (`criminalAssignmentCooldownDays`), but nothing
  capped how many thieves a village could end up with over time, so a long-lived world could
  accumulate several thieves in the same village, each with its own independent cooldown against
  the same players.

Ordinary villagers could not be mis-flagged as thieves during this: criminality is a persisted
`CriminalVillagerRecord`, checked by `WorldCriminalJobService.isCriminal`, and the `mcacrime:thief`
profession is presentation-only (`CriminalProfessions`, `PoiType.NONE`) — a villager wearing the
skin is not what makes it rob anyone.

**The fix** moves protection onto the player: `state/PlayerCrimeData` gained
`mugProtectionUntilTick`, a bounded `recentMuggers` map, and daily-mugging counters, all read by
`mug/npc/MugProtectionRules` (pure) and granted by `mug/npc/MugProtection`. Grants happen on mugging
completion/abort, on respawn, on login, and on release from custody or jail, and a grant never
shortens an existing window — the longest protection already in effect always wins. A live
thief-per-jurisdiction cap in `CriminalJobAssignmentSweep` bounds the accumulation directly. See
`CONFIG.md` under `criminalJobs.thief` for the resulting keys and the three retuned defaults.

## Regression tests added

All of the following are new pure JUnit 5 suites (no Minecraft bootstrap), grouped by the feature
they pin. `ReconciliationTest` was also updated for the world-schema bump.

**Family loyalty**
- `FamilyLoyaltyTest` — score arithmetic and each of the six hard exclusions in isolation.
- `WitnessLoyaltyFilterTest` — loyal witnesses removed from the reporting set; `witnessed = false`
  when every witness is loyal; totals exclude loyal witnesses.
- `FamilyGraphTest` — scope composition and `IN_LAW` derivation.
- `WitnessResultTest` — legacy factories still produce an empty `loyalIds`.
- `ReportStateTest` — `WITHHELD` round-trips by name.

**Family accomplices and bail**
- `AccompliceEligibilityTest` — the recruitment gate matrix.
- `AccompliceRecordTest` — NBT round-trip, expiry, and the wanted-state transition.
- `WitnessModifiersTest` — lookout/distraction clamping and composition, civilians-only, exact
  expiry.
- `AccompliceExposureTest` — the exposure decision (witnessed, principal-arrest implication,
  already-wanted is never re-exposed).
- `AccompliceCustodyInvariantTest` — release restores control and leash, and the villager is never
  removed.
- `ThiefArrestUnchangedTest` — a villager with no accomplice record, or one nobody is looking for,
  still serves exactly the thief sentence it served before this feature existed.
- `BailQuoteTest` — the pricing formula, its clamps, and compounding across prior arrests.
- `BailPaymentFlowTest` — insufficient funds charges nothing, a duplicate payment charges once,
  and an already-released target charges nothing.

**Mugging protection**
- `MugProtectionRulesTest` — the grant-never-shortens rule and the frequency multiplier.
- `MugRepeatCooldownTest` — the same-pair repeat cooldown.
- `MugDailyCapTest` — the daily cap, including `0` disabling it.
- `ThiefJurisdictionCapTest` — the live thief-per-jurisdiction cap.
- `OrdinaryVillagerNotCriminalTest` — pins that criminality is record-based, not profession-based.
- `PlayerCrimeDataMugFieldsTest` — the new capability fields round-trip through save/load/copyFrom,
  and read as zero when absent.

**Contraband**
- `ContrabandRulesTest` — id/tag matching.
- `ContrabandFingerprintTest` — order-independence and the recharge window.
- `ContrabandChargeDedupeTest` — the same haul is not charged twice inside `rechargeTicks`; a
  changed haul is charged immediately.
- `ContrabandScannerTest` — the equipped/offhand/nested toggles, and the depth-1 hard cap.
- `ContrabandDiscoveryGateTest` — the search gate (enabled, mode, range, line of sight, suspicion,
  chance) and that an arrest search bypasses the roll and the line-of-sight requirement.
- `ContrabandConfigValidationTest` — non-fatal validation of the list and the discovery settings.

Offline result: **1,230 tests, 0 failures, 0 skipped**, from the latest `check` run
(`/tmp/gradle-MCACrime-check-20260910-213838.log`, `build/reports/tests/test/index.html`).

## Unverified production checks

Nothing below was exercised against a live Minecraft session or real MCA data; each needs an
in-game pass before release.

| Check | Why it cannot be verified offline |
|---|---|
| Family loyalty against real MCA relationship data | Hearts, family edges and adult status come from MCA's live entity state, not from a fixture, on all three probe versions in `gradle.properties` (`mca_probe_versions`). |
| Actual personality names MCA returns | `loyalPersonalities`/`lawfulPersonalities` compare against whatever string MCA's personality system actually reports; the pure tests use synthetic names. |
| Accomplice pathing | `AskDistractionActionHandler` hands the relative to MCA's own navigation (`McaCompat.moveVillagerTo`); whether that walk looks sensible in a real village is not something a pure test can see. |
| Leash and control restoration after a sentence | Release is expected to restore MCA's control of a captured accomplice and hand back its inventory; only the ordering of the calls is pinned, not MCA's actual behaviour afterward. |
| Bail under a duplicated packet | `BailQuote.pay` is proven idempotent as a pure function; a genuinely duplicated network packet arriving from a real client has not been sent. |
| Mugging frequency over a multi-hour session | The protection windows and caps are unit-tested individually; whether the combination feels right over hours of real play is a judgment call the tests cannot make. |
| Shulker and bundle nested scanning against real NBT | `ContrabandScanAdapter` reads a bundle's raw `"Items"` tag and a shulker's block-entity data; the scanner tests exercise it with constructed probes, not a real placed and filled container. |
| Guard search readability in game | The `contraband.searched`/`found`/`confiscated` messages are wired and sent at the right moments; how they read to a player being searched has not been observed in a client. |
| The deferred MCA `Mood` binding | Deliberately not read anywhere in this feature: MCA's `Mood` is transient, so a mood-weighted loyalty or accomplice decision would flip between two identical crimes minutes apart and read as a bug rather than a choice. This is a design decision, not an oversight, and is recorded here rather than left silent. |

**Pre-existing, unrelated to this pass:** `check_mod.py` reports
`CrimeKeybinds.java:50` — `@Mod.EventBusSubscriber` declaring `Bus.MOD` on a class that also
handles a Forge-bus `TickEvent`. That class was last touched in the 0.5.0 release and is untouched
by this work; it is noted here only so it is not mistaken for a regression introduced by 0.7.0.

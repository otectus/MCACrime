# Family loyalty, accomplices, mugging frequency and contraband verification — 0.7.0

This applies to the NeoForge 1.21.1 port. It covers three additions (family loyalty, family
accomplices and bail, configurable contraband) and one fix (excessive NPC mugging of the same
player), ported from the audited Forge 1.20.1 baseline of the same release.

## The mugging diagnosis

The complaint was that a player could be robbed by several different villager thieves in quick
succession. Concurrent overlap was already impossible — a thief claims a victim before a session
starts, so two thieves cannot be mid-mugging on the same player at once. The defect was temporal
and cumulative instead:

- The only cooldown that existed was scoped to the **thief**, not the victim: the job service
  stamps each thief's own last-mug time, and `ai/thief/ThiefBehaviorService` checks only that one
  thief's own stamp against `mugCooldownTicks`. A second thief with no record of its own was never
  blocked by the first thief's cooldown.
- There was no grace period at all after a respawn, a login, or a release from custody — a player
  who had just been mugged, died, and respawned nearby was immediately eligible again.
- Thieves could accumulate without bound in one jurisdiction: `assignmentScanIntervalTicks` and
  `criminalAssignmentCooldownDays` throttle how *often* a village produces a thief, but nothing
  capped how many thieves a village could end up with over time.

**The fix** moves protection onto the player: `state/PlayerCrimeData` — a NeoForge data attachment
(`mcacrime:player_crime`) — gained `mugProtectionUntilTick`, a bounded `recentMuggers` map (evicted
at `MAX_RECENT_MUGGERS = 8`), and daily-mugging counters, all read by the pure
`mug/npc/MugProtectionRules` and granted by `mug/npc/MugProtection`. Every new field is additive:
a save written before this release has no such fields, and reads them back as `0`/empty, which is
correct — nobody was protected before the code that protects them existed, so no migration step
writes anything for it (`CrimeDataMigrations.v10to11` only stamps the schema number). Grants happen
on mugging completion/abort, on respawn, on login, and on release from custody or jail, and a grant
never shortens an existing window — the longest protection already in effect always wins
(`MugProtectionRules.grant`). A live thief-per-jurisdiction cap (`maxActiveThievesPerJurisdiction`)
bounds the accumulation directly. See `CONFIG.md` under `criminalJobs.thief` for the resulting keys
and the three retuned defaults.

## Family loyalty

`relationship/FamilyLoyalty.evaluate` is a pure, RNG-free scoring function:
`loyaltyHeartsWeight × hearts + tierBonus + personalityModifier`, checked against
`loyaltyThreshold` only after six hard exclusions (victim, the victim's own relative, a responder,
a minor, a tier out of `familyLoyaltyScope`, or Heat above `loyaltyMaxCrimeHeat`) all fail to apply.
`detect/WitnessLoyaltyFilter.partition` removes loyal witnesses from the candidate set **before**
`WitnessResult` is built, so Heat, community standing and family heart loss are computed only from
the witnesses who actually reported — a crime seen only by loyal family produces none of those
effects. `memory/ReportState.WITHHELD` is a state distinct from intimidation's `SUPPRESSED`, so a
withheld observation is still recorded for dialogue to reference, but is never filed or gossiped.

## Family accomplices and bail

Three Crime-menu actions in `ActionCategory.CONSPIRE` — `AskLookoutActionHandler`,
`AskDistractionActionHandler`, `AskEscapeHelpActionHandler` — are gated by `AccompliceGate` on
family tier, `accompliceHeartsRequired`, and recruitment cooldown. Effects are read by
`detect/WitnessModifiers` (lookout witness-radius shrink and warn) and
`enforcement/AccompliceService` (distraction attention-holding; escape-help responder shake-off
and the restraint-work divisor `1.0 + escapeHelpEscapeBonus`, so the default `0.5` divides the
requirement by `1.5`). `enforcement/AccompliceExposure.exposed` is a pure decision: an accomplice
already wanted is never re-exposed; otherwise exposure follows either a witness sighting (the same
perception rules a crime's own witnesses use, excluding loyal relatives and other accomplices) or
`implicateOnPrincipalArrest` firing when the principal is jailed with the agreement still active.
An exposed accomplice is charged `mcacrime:aiding_a_criminal` and goes through the existing NPC
custody path, serving `accompliceJailTicks` rather than `thiefJailTicks`
(`ThiefArrestUnchangedTest` pins that an unrelated thief's own sentence is untouched by this).

`enforcement/BailQuote.cost` computes
`clamp(round(bailBase + bailPerThousandTicks × remainingTicks/1000) × bailRepeatMultiplier^priorArrests, bailMin, bailMax)`,
and `BailQuote.pay` is a four-outcome pure state machine (`QUOTED`, `ALREADY_RELEASED`,
`INSUFFICIENT`, `PAID`) ordered custody-check → balance-check → charge → release, so a duplicate
payment attempt after release is idempotent (`ALREADY_RELEASED`, no charge). The quote reaches the
client as `BailQuoteS2CPacket`, a `CustomPacketPayload` routed through
`network/CrimeClientPayloadRouter.handleBailQuote` and rendered by `client/screen/FamilyBailScreen`.

## Contraband

`item/contraband/ContrabandScanAdapter` reads a player's inventory, armour and offhand, and — when
`searchNestedContainers` is on — the contents of any shulker box or bundle among them, using their
1.21 data components (`DataComponents.CONTAINER`, `DataComponents.BUNDLE_CONTENTS`) rather than
block-entity NBT or a bundle's private accessor. Depth is hard-capped in code at
`ContrabandInventoryScanner.MAX_NESTED_DEPTH = 1`, enforced independently in both the scanner (a
probe deeper than the cap is never listed) and the adapter (nested contents are never themselves
descended into). `illegalItems` entries are `namespace:path` or `#namespace:path`; a `#tag` entry
cannot be checked while the config loads, so `enforcement/ContrabandPolicy` accepts it
provisionally and confirms it once NeoForge's `TagsUpdatedEvent` fires, by asking
`BuiltInRegistries.ITEM.getTag(...)` — an entry naming a tag nobody registered is dropped from the
active list at that point rather than failing the load. The shipped example tag,
`#mcacrime:illicit_goods`, lives at `data/mcacrime/tags/item/illicit_goods.json` (the singular
`item` folder is 1.21's tag path, not `items`) and is not added to `illegalItems` automatically.
Discovery is gated through `enforcement/ContrabandSearchService`: a `GUARD_PATROL`/`BOTH` pass
requires range, accumulated line-of-sight ticks, the suspicion toggle, and a chance roll in
sequence; `ARREST_ONLY` skips patrol searches and always searches on arrest, with no roll and no
line-of-sight requirement. A find is charged as `mcacrime:possess_contraband`, deduplicated by an
order-independent fingerprint of the haul's item ids and counts, stored as
`lastContrabandFingerprint`/`lastContrabandChargeTick` on the `mcacrime:player_crime` attachment and
compared against `rechargeTicks`.

## Regression tests added

All of the following are new pure JUnit 5 suites (no Minecraft bootstrap), grouped by the feature
they pin. `CrimeDataMigrationTest` and `ReconciliationTest` were also updated for the world-schema
bump.

**Family loyalty**
- `FamilyLoyaltyTest` — score arithmetic and each of the six hard exclusions in isolation.
- `WitnessLoyaltyFilterTest` — loyal witnesses removed from the reporting set; `witnessed = false`
  when every witness is loyal; totals exclude loyal witnesses.
- `FamilyGraphTest` — scope composition and `IN_LAW` derivation.
- `WitnessResultTest` — legacy factories still produce an empty `loyalIds`.
- `ReportStateTest` — `WITHHELD` round-trips by name and is distinct from `SUPPRESSED`.

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
- `MugDailyCapTest` — the daily cap, the day rollover, and that `0` disables it.
- `ThiefJurisdictionCapTest` — the live thief-per-jurisdiction cap and the retuned shipped defaults.
- `OrdinaryVillagerNotCriminalTest` — pins that criminality is record-based, not profession-based.
- `PlayerCrimeDataMugFieldsTest` — the new attachment fields round-trip through save/load/death/copy,
  and a pre-0.7.0 save loads them as zero with no migration.

**Contraband**
- `ContrabandRulesTest` — id/tag matching.
- `ContrabandFingerprintTest` — order-independence and the recharge window.
- `ContrabandChargeDedupeTest` — the same haul is not charged twice inside `rechargeTicks`; a
  changed haul is charged immediately.
- `ContrabandScannerTest` — the equipped/offhand/nested toggles, and the depth-1 hard cap.
- `ContrabandDiscoveryGateTest` — the search gate (enabled, mode, range, line of sight, suspicion,
  chance) and that an arrest search bypasses the roll and the line-of-sight requirement.
- `ContrabandConfigValidationTest` — non-fatal validation of the list and the discovery settings.

Offline result: **1,290 tests, 0 failures, 1 skipped**, from the latest `test` run
(`/tmp/gradle-MCACrime_1.21.1-test-20260910-214954.log`, `build/reports/tests/test/index.html`).
The one skip is the pre-existing `LegacyFixturePresenceTest`, which needs an authentic captured
Forge player save that has not been supplied; it predates this pass and is unrelated to it.

## Where this port structurally differs from the Forge baseline

A reader comparing the two repositories should not look for these on this side:

- No `NoMcaStaticLinkTest`-style capability wiring for `PlayerCrimeData` and no separate
  reconciliation step for "does the field exist yet" — `PlayerCrimeData` is a NeoForge data
  attachment, always present and never null once a player exists, so every new field simply reads
  as its default rather than needing an absent-vs-present check the baseline's capability had to
  make explicit.
- No block-entity NBT or bundle-accessor code in `ContrabandScanAdapter` — 1.21 exposes both
  container shapes as `ItemStack` data components, so nested scanning is a component read, not an
  NBT walk.
- The tag path is `data/mcacrime/tags/item/illicit_goods.json` (singular `item`), not the `items`
  folder path used pre-1.21.
- The mod file is `neoforge.mods.toml`; there is no `mods.toml` in this project.

## Unverified production checks

Nothing below was exercised against a live Minecraft session or real MCA data; each needs an
in-game pass before release.

| Check | Why it cannot be verified offline |
|---|---|
| Family loyalty against real MCA relationship data | Hearts, family edges and adult status come from MCA's live entity state, not from a fixture. |
| Actual personality names MCA returns | `loyalPersonalities`/`lawfulPersonalities` compare against whatever string MCA's personality system actually reports; the pure tests use synthetic names. |
| Accomplice pathing | `AskDistractionActionHandler` hands the relative to MCA's own navigation (`McaCompat.moveVillagerTo`); whether that walk looks sensible in a real village is not something a pure test can see. |
| Leash and control restoration after a sentence | Release is expected to restore MCA's control of a captured accomplice and hand back its inventory; only the ordering of the calls is pinned, not MCA's actual behaviour afterward. |
| Bail under a duplicated packet | `BailQuote.pay` is proven idempotent as a pure function; a genuinely duplicated network packet arriving from a real client has not been sent. |
| Mugging frequency over a multi-hour session | The protection windows and caps are unit-tested individually; whether the combination feels right over hours of real play is a judgment call the tests cannot make. |
| Shulker and bundle nested scanning against a placed, filled container | `ContrabandScanAdapter` reads `ItemStack` data components; the scanner tests exercise it with constructed `ContrabandProbe` records, not a real placed and filled container synced to a client. |
| Guard search readability in game | The `contraband.searched`/`found`/`confiscated` messages are wired and sent at the right moments; how they read to a player being searched has not been observed in a client. |
| The deferred MCA `Mood` binding | Deliberately not read anywhere in this feature: MCA's `Mood` is transient, so a mood-weighted loyalty or accomplice decision would flip between two identical crimes minutes apart and read as a bug rather than a choice. This is a design decision, not an oversight, and is recorded here rather than left silent. |

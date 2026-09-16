# Changelog

All notable changes to MCA: Crime.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.7.3] — unreleased

Adopts MCA: Reputation 0.6.0 on the NeoForge 1.21.1 line, mirroring the Forge 0.7.3 adoption.
Nothing here requires it: an older companion, or none at all, behaves exactly as it did in 0.7.2,
because what this mod uses is decided by a capability handshake rather than by a version number.

### Added

- **Capability negotiation with MCA: Reputation.** `compat/ReputationCapabilitySnapshot` holds what
  the installed companion advertised, queried once per server by `ReputationBridge.negotiate` at
  `ServerStartedEvent` and reported by `/crime debug integrations`. The API-version handshake still
  runs and still refuses a generation this build cannot speak, but it is no longer what decides
  which facilities are used: MCA: Reputation adds capabilities without moving its API generation, so
  comparing versions could not tell a 0.4.1 install from a 0.6.0 one. Each of keyed delivery,
  read-only receipt lookup, supersession, profiled delivery and bound resolution is used only when
  its feature string is advertised, and the fallback in every case is the older surface working as
  before.
- **Social profiles for every shipped incident.** Seven `incident_profiles` and two
  `credit_policies` under `data/mcacrime/mcareputation/`, attached through the new
  `social_profile` field on all eight incident definitions. They say what a crime makes a player
  *known for* — recognition plus violence, lawfulness and compassion evidence — separately from what
  it costs them in standing. The two commendable profiles carry repeat-credit policies, so paying a
  fine or serving a sentence repeatedly stops earning recognition rather than farming it; the
  adverse ones deliberately carry none, because repetition must never make harm cheaper. No scalar
  standing value changed. Nothing in a profile decides guilt, sentencing or bail: the legal case
  remains entirely this mod's.
- **Assault-to-killing parity.** `integration/SupersedePolicy` finds the linked assault a killing
  finished, and the delivery supersedes it instead of stacking on top of it — the same fold MCA:
  Reputation's own detector performs for the deed we took off it. Bounded by the new
  `integrations.reputation.supersedeWindowTicks` (default 1200, `0` disables), and only within one
  village, against the same victim, while the precursor case is still unsettled.
- **Resolutions that arrive before their link.** A settlement committed before the incident it
  settles has been filed is now queued and delivered once the link exists, rather than dropped. It
  waits under the new `AWAITING_LINK` outcome, which counts against the attempt budget so a
  settlement whose create dead-lettered does not retry forever.

### Changed

- **Typed delivery outcomes.** `compat/ReputationOps` no longer answers with `Optional<UUID>`. The
  new `compat/ReputationDelivery` distinguishes accepted, accepted-with-no-public-incident,
  duplicate, refused-for-capacity, refused-because-disabled, invalid, unknown-definition,
  missing-incident, unavailable and lost-answer, and `integration/DeliveryOutcome` maps each one
  honestly. Three real behaviour changes follow. An unwitnessed deed the companion legitimately
  keeps private is now a completed operation instead of being dead-lettered as a missing datapack
  definition (and no longer triggers the local village penalty, which contradicted the companion's
  own unwitnessed policy). A full village ledger is now a retryable `REFUSED_CAPACITY` instead of
  being reported as the companion being uninstalled — and never as the crime not having happened.
  And the companion's own integration being switched off is a delay rather than a failure, so it
  does not burn the retry budget.
- **Civic writes go through keyed delivery.** Where the companion advertises it, incidents are
  filed with `deliver`/`deliverProfiled` under a `mcacrime`-scoped operation key and resolutions
  through `resolveBound`, so a replay after a crash returns the settled answer rather than
  re-deciding anything. Crime's own dedupe keys are unchanged, so no queued operation changes
  identity across the upgrade.
- **The detection-authority claim respects the kind it is asked about.**
  `compat/CrimeAuthorityPolicy` declares exactly `MCA_VILLAGER_ASSAULT` and `MCA_VILLAGER_KILL`
  through the companion's `declaredKinds()`, and implements `canDeliver(kind)` so a claim we cannot
  currently file — the outbox pump switched off — hands detection straight back.

### Fixed

- **The MCA: Reputation integration was silently off on this whole line.**
  `ReputationBridge.REQUIRED_API_VERSION` was `1` and is compared for exact equality, but the
  NeoForge 1.21.1 companion has advertised API `2` since 0.4.1 for the same additive surface the
  Forge line numbers `1`. Every NeoForge MCA: Reputation from 0.4.1 onwards therefore took the
  `ops = null; status = "incompatible API v2"` branch: MCA: Crime kept village standing in its own
  store, filed no civic incidents, and said so in exactly one error line. The gate is now `2`, and
  the too-old log hint moves from `0.2.0` to `0.4.1`, which is the oldest NeoForge companion
  carrying the surface this adapter is written against. The optional dependency range in
  `neoforge.mods.toml` stays permissive at `[0.2,)` on purpose — an optional integration must never
  stop the game launching.
- **A blanket authority claim took four deeds away from the mod that records them.** `owns(kind)`
  ignored its argument, so while the bridge was healthy this mod claimed villager rescues, cures,
  repelled raids and in-village player kills as well — none of which it detects. MCA: Reputation
  stood down for all four and nobody recorded them at all. The claim is now exactly the two kinds
  this mod produces.
- **An NPC offender's crime was filed as a player's civic deed.** `CrimeIntegrationHooks.onCommitted`
  did not look at `offender_kind`, so an NPC thief's theft was queued and delivered to MCA:
  Reputation against the *villager's* UUID — opening a standing record for somebody the system only
  ever described players with, and under 0.6.0 attaching public profile evidence to it. The new
  `isPlayerAttributed` check reads the flag the record already stamps at commit time rather than
  probing the world for an entity that may be dead or unloaded by the time the outbox drains.
  Existing records written that way are not retroactively removed; nothing here can delete another
  mod's data.
- **A lost incident link was recovered by recording a fake assault.** `findIncident` sent a
  synthetic `mcareputation:villager_assaulted` through the write path and read the duplicate
  refusal to learn the id. On a companion that had forgotten the key — a receipt horizon passed, a
  pre-0.4.1 build, retention — that probe *recorded a villager assault that never happened*. It is
  replaced by `findReceipt`/`findIncident`/`receiptFloor`, which write nothing; where those are
  unavailable, the answer is "unknown" and the keyed retry settles it.

### Platform

- The optional companion class paths are now overridable: `-PmcaReputationClasses=<dir>` and the new
  symmetric `-PmcaQuestsClasses=<dir>`, so a build can compile the adapters against a snapshot of
  the companion's class output instead of a sibling checkout that may be mid-rebuild. The defaults,
  `-PrequireReputation` and `-PrequireQuests` are unchanged.
- `gradlew` is committed executable again (it had regressed to mode `100644`, so a fresh clone on a
  POSIX host could not run the wrapper without `sh gradlew`).
- Loader idioms this port re-expresses against the Forge adoption: `org.jetbrains.annotations.Nullable`
  for `javax.annotation.Nullable`, `ModConfigSpec` for `ForgeConfigSpec`, `ServerTickEvent.Post` for
  `TickEvent.ServerTickEvent` at phase END, `@EventBusSubscriber` for `@Mod.EventBusSubscriber`,
  `net.neoforged.neoforge.server.ServerLifecycleHooks`, and `ResourceLocation.fromNamespaceAndPath`
  for the now-private constructor. The datapack files under `data/mcacrime/mcareputation/**` are
  byte-identical to the Forge line: MCA: Reputation's own loader reads them, and they are untouched
  by the 1.21 `recipe/`/`loot_table/` directory renames.

---

Compatibility: Minecraft 1.21.1 on NeoForge; requires the MCA Reborn version pinned in
`gradle.properties`. Optional: MCA: Reputation `[0.2,)` — the integration itself needs `0.4.1` on
this line, `0.6.0` unlocks public profiles, and every older companion degrades to the surface it
does have rather than failing the load. Also optional: MCA: Quests, Locks Reforged.

## [0.7.2] — unreleased

### Added

- **Exclusive native Thief profession.** `mcacrime:thief` (`job/CriminalProfessions#THIEF_ID`) is a
  real vanilla-registered villager profession with its own Mask Station worksite rather than a
  cosmetic overlay on an existing one. `job/OccupationTransaction` is the only sequence that makes
  one: it reserves or adopts the station ticket, applies the profession through MCA's setter, installs
  empty offers, writes the validated job site, and rolls the whole thing back — ticket, claim,
  profession, villager data, trading XP, merchant offers, MCA clothing, family-tree profession and the
  four occupational memories — when any step fails. `job/WorldCriminalJobService#requestThiefOccupation`
  coordinates settlement sweeps and station recruitment and requires an unemployed adult on the
  settlement route. `job/ThiefWorksiteService` discovers and claims Mask Station points of interest
  within `SEARCH_RADIUS` (48) blocks with a `RESERVATION_TIMEOUT_TICKS` (400) reservation window.
  `job/ThiefOccupationLifecycle` reconciles every `INTERVAL_TICKS` (20) across `PENDING`,
  `ACTIVE_BOUND_NOVICE`, `ACTIVE_BOUND_ESTABLISHED`, `ESTABLISHED_UNBOUND`, `SUSPENDED` and `RETIRED`,
  with `NOVICE_GRACE_TICKS` (1200) loaded ticks of grace and establishment at `ESTABLISHMENT_TICKS`
  (24000) employment ticks. The five MCA members the transaction needs are still resolved by name:
  they are declared optional individually and checked as one bundle
  (`compat/mca/McaBinding#THIEF_OCCUPATION_CAPABILITY`) before any mutation, so a future MCA that
  drops one suspends this feature instead of the mod.
- **Mask Station worksite and crafting.** `block/MaskStationBlock` (`mcacrime:mask_station`) is a
  horizontal-directional, piston-immovable crafting block and a village point of interest
  (`job/CrimePoiTypes#MASK_STATION_KEY`). Right-clicking opens `menu/MaskStationMenu`, a per-viewer
  session with its own material, binding and optional dye slots and no block entity, so two players at
  one station never see each other's inputs. `recipe/MaskMakingRecipe` implements the
  `mcacrime:mask_making` recipe type with exact ingredient counts and an optional dye, producing
  exactly one mask; `recipe/MaskRecipeJson` validates a file registry-free and reports every problem
  at once rather than throwing at the first. `menu/MaskStationResultSlot` is the single debit: left
  click, right click and shift-click batching (capped at `MaskCraftPlan.MAX_BATCH`, 64) are allowed,
  while hotbar number swaps, off-hand swaps, drops, creative clones, double-click collection and
  click-drag distribution are denied by the exhaustive table in `menu/MaskStationRoute`.
- **16-style mask catalogue.** `item/MaskVariant` ships sixteen styles across four families
  (`item/MaskFamily`), four each, all with zero armour, no enchantability and one wear budget per
  family: Cloth 48 (`bandana`, `highwaymans_domino`, `wrapped_scarf`, `half_veil`), Leather 192
  (`leather_mask`, `raven_mask`, `jackal_mask`, `stitched_mask`), Clay 64 (`hockey_mask`, `clay_mask`,
  `comedy_mask`, `tragedy_mask`) and Metal 256 (`iron_skull_mask`, `brigand_visor`, `owl_mask`,
  `blank_iron_mask`). `clay_mask` and `leather_mask` keep their 0.7.1 ids and wear budgets, so saved
  stacks and the two crafting-table recipes still resolve. Every style takes a 24-bit RGB tint
  (`mask/MaskCustomization#tint`); on 1.21.1 that is the `minecraft:dyed_color` data component and the
  `minecraft:dyeable` item tag rather than item NBT, so vanilla's own dye blend applies and an undyed
  mask reads as untinted white instead of leather brown. A Forge world's mask tint lived in NBT and is
  not carried over.
- **Mask restyling.** The station's `restyle` operation (`mask/MaskCustomization#restyleResult`) moves
  a mask's history onto another style in the same family — wear, custom name, lore, enchantments and
  curses, repair cost, dye and unrecognised data alike. `mask/MaskNbtTransfer` does that arithmetic on
  the `DataComponentPatch` rather than on a `CompoundTag`, carrying known component types with
  understood semantics, copying unknown ones verbatim, and refusing `block_entity_data` and
  `entity_data` outright. Cross-family conversion (`WRONG_FAMILY`), an unregistered style
  (`NOT_A_REGISTERED_STYLE`), a no-op selection (`NO_CHANGE`) and unmovable data (`UNSUPPORTED_DATA`)
  are all refused (`mask/MaskRestyleRejection`). Item stacks on 1.21.1 carry no capabilities and no
  data attachments, so the component patch really is the whole of a stack's state and the Forge
  build's `ForgeCaps` refusal has no analogue here.
- **Non-sneak empty-hand apology.** Right-clicking a villager you wronged while **not** sneaking, with
  an empty main hand and no weapon in the off hand, apologizes directly
  (`memory/ApologyInteractionPolicy`, `memory/MemoryInteractionHandler`).
  `memory.emptyHandApologyMode` chooses the behaviour: `CONTEXTUAL_DIRECT` (the default) or
  `MENU_ONLY`, which restricts apologies to the Crime menu, its keybind and the MCA screen button.
  Neither mode changes settling time, cooldowns or how much is repaired.
- **Sand Bottle.** `item/SandBottleItem` (`mcacrime:sand_bottle`) throws an
  `entity/SandBottleProjectile` behind an 80-tick player item cooldown shared across both hands. On
  impact `effect/SandExposurePolicy` blinds with `effect/SandBlindedEffect`: up to 80 ticks on a direct
  hit, up to 40 ticks within a 2.0-block splash falling off linearly to a 10-tick minimum, capped at 64
  affected victims out of 256 candidates examined. A blinded entity loses line of sight beyond 1.5
  blocks (`effect/SandBlindness`, `ai/NpcAwareness#canSeeNow`, and one common `SandSensingMixin` for
  vanilla mobs), which halts pursuit repaths, mugging target acquisition and witness identification
  without clearing a single memory or legal status. `effect/SandRecovery` holds a 60-tick recovery
  window per target against **all** throwers, so two players cannot alternate bottles to stun-lock one
  victim. A `[sandBottle]` common block tunes every exposure parameter and `client.sandParticles`
  (`NORMAL` / `REDUCED` / `OFF`) controls the dust. The projectile carries launch provenance the server
  alone reads and adds nothing to the spawn packet — 1.21.1 spawns it with vanilla's own
  `ClientboundAddEntityPacket`.
- **Shared NPC-mugger eligibility.** `job/NpcMuggerEligibility` is one role evaluator for both the
  `ASSIGNMENT` and `EXECUTION` contexts, so the places that ask "may this villager mug" cannot drift
  apart. Responder identity is decided before any temporary state, and a failure to read MCA's
  classification fails closed with `CLASSIFICATION_UNAVAILABLE`.
- **Epic Fight compatibility.** `compat/EpicFightCompat` detects Epic Fight and the three MCA bridges
  that ship alongside it — "MC-Epicly-A" (`mcea`), "EpicFight-MCA Patch" (`efmca`) and "MCA Skin x Epic
  Fight Compatibility" (`mcaefcompat`) — by mod id only, with none of them named as a type anywhere in
  the mod. The client-only `client/EpicFightInteractShim` forwards an armed or restraint interaction
  that Epic Fight's battle mode had already cancelled at the use key, so MCA: Crime's menu still opens
  under the server's existing weapon-trigger rules (synced via two `WeaponPolicySnapshot` fields,
  `triggerEnabled` and `triggerRequireSneak`) without ever sending a duplicate packet. A startup log
  warning and `/crime debug compat` report the one thing this mod cannot work around: `mcea` blocks all
  player damage to MCA villagers with an unconditional mixin on `VillagerEntityMCA.hurt` plus an
  unconditional `LivingIncomingDamageEvent` cancel and has no friendly-fire option at all, and `efmca`
  carries the same mixin under its own id with a `friendlyFire` field that governs only a separate
  cancel and never lifts it — so assault, killing, self-defence and kill bounties against villagers do
  not fire while either is present. `mcaefcompat` is client rendering only and is reported, never acted
  on. See [docs/COMPATIBILITY_EPIC_FIGHT.md](docs/COMPATIBILITY_EPIC_FIGHT.md).
- **Configurable currency.** `integrations.currencyId` now resolves through a real registry
  (`economy/Currencies`) instead of always meaning emeralds. The built-in `mcacrime:item`
  provider (`economy/ItemCurrency`) charges in any single registered item named by
  `integrations.currencyItem`, sharing its inventory-walk logic with the emerald default via
  `economy/ItemStackCurrencySupport` and `economy/ItemCurrencyMath`. A queued payout records the
  item it was earned in as a bound provider id (`mcacrime:item/<namespace>/<path>`), so a config
  change afterwards cannot redirect a payment already owed. `Currency.creditBounded` credits
  without ever dropping a payout on the ground and reports the undelivered remainder;
  `BountyService` routes every payout through it. Economy mods can add their own id with
  `McaCrimeApi.registerCurrency`. An unregistered `currencyId`, or an unresolvable `currencyItem`,
  falls back to emeralds with one warning rather than taking fines, bail, ransom and theft
  offline; `/crime validate` also reports an unregistered `currencyId`
  (`config/ConfigValidator#currencyIdRegistryCheck`).
- **Numismatic Overhaul purse.** When Numismatic Overhaul (Reforged Again) is installed,
  `compat/NumismaticBridge` and `compat/numismatic/NumismaticCurrency` register
  `mcacrime:numismatic`, a currency backed by that mod's `CurrencyHolder` data-attachment purse
  rather than an inventory item; amounts are in bronze (100 bronze = 1 silver, 10000 = 1 gold). It
  has no item form, so it cannot be dropped, traded by a fence, or produced on the ground.
- Chat messages that used to say "emeralds" unconditionally — the guard challenge fine line, the
  case dossier, and the `/crime payfine` and mugging text — now read from whatever currency is
  active, formatted server-side so a client (which is forbidden to read the common config) never
  has to guess or hard-code a name.

### Changed

- **Protocol version 12 → 13.** `network/CrimeNetwork` raises the payload protocol version to `13` for
  the two new payloads, `mcacrime:select_mask_recipe` (`network/SelectMaskRecipeC2SPacket`) and
  `mcacrime:mask_selection` (`network/MaskSelectionS2CPacket`). These are NeoForge
  `CustomPacketPayload` types on a versioned `PayloadRegistrar`, not channel packets: a client whose
  registrar version does not match is rejected at the handshake, and a Forge client cannot join this
  port at all.
- **World save schema 11 → 12.** `state/world/CrimeDataMigrations.SCHEMA_OCCUPATION` is the current
  schema. `state/world/CriminalVillagerRecord` gains `status`, `source`, `establishedAt`,
  `lastVisitAt`, `employedTicks`, `worksite` (a `WorksiteRef`, dimension id plus `BlockPos`, so a
  saved record is readable without a bootstrapped registry), `reservation`, `reservationAt`,
  `unboundSince`, `previousProfessionKind` (`HistoricalProfessionKind`) and an `extra` compound that
  round-trips keys this build does not recognise. Migration is structural, lazy and idempotent;
  pre-existing thieves become `ESTABLISHED_UNBOUND`, because no pre-0.7.2 world contains a station.
- **Vanilla integration mixins.** `src/main/resources/mcacrime.mixins.json` now declares seven common
  mixins and one client mixin. Six of the seven are new or support 0.7.2:
  `MaskStationAcquisitionMixin` (`VillagerProfession.acquirableJobSite`, RETURN) hides the Mask Station
  from every profession except Thief; `NativeJobAssignmentMixin` (`AssignProfessionFromJobSite.create`,
  RETURN) routes Mask Station assignment through Crime and delegates every other site unchanged;
  `ThiefPoiValidationMixin` (`ValidateNearbyPoi.create`, RETURN) defers validation while a chunk cannot
  be inspected; `ThiefBrainMixin` (`Brain.tick`, HEAD) wraps this brain's `Activity.WORK` entries so
  station work yields to sleep, custody, panic and enforcement; `MerchantOffersAccessor`
  (`AbstractVillager.offers`) is a field accessor with no behaviour; and `SandSensingMixin`
  (`Sensing.hasLineOfSight`, HEAD) enforces sand blindness before the cached-positive return. The
  pre-existing common `MobDeathEquipmentMixin` (`Mob.setItemSlot`, HEAD) and the client-only
  `mixin/client/RestraintPoseMixin` (`LivingEntityRenderer.render`) are unchanged.
- **Villager XP floor on occupation commit.** `compat/OccupationCompat#applyXpFloor` raises trading XP
  to at least 1 when a Thief commits, which is the whole protection against vanilla's `ResetProfession`
  and MCA's `LoseUnimportantJobTask` — both of which require zero XP to strip a job. It costs a player
  nothing, because a Thief has no trades, and it also excludes committed Thieves from MCA's automatic
  guard recruitment.
- **Removal of sneak-to-open-menu.** `memory/MemoryInteractionHandler` no longer opens the Crime menu
  on a sneaking empty-hand right-click, which was fighting every mod that binds villager pickup to
  sneak. The menu remains available from the keybind (`key.mcacrime.crime_menu`), the Crime button on
  MCA's screen, and an armed interaction.
- **Guard promotion role exclusion.** `enforcement/GuardPopulationService` consults
  `job/NpcMuggerEligibility#guardPromotionBlocked`, so an adult villager carrying an active criminal
  record is not promoted to guard.

### Deprecated

- **`criminalJobs.presentThiefAsMcaProfession` is deprecated and inert.** Thief is exclusively a real
  native profession with its own worksite now, so a hidden Thief overlay on an ordinary profession is
  no longer a supported state. The key is still parsed, the config file is not rewritten, and setting
  it to `false` logs one startup warning through
  `job/WorldCriminalJobService#warnAboutDeprecatedThiefPresentation`. The companion
  `presentFenceAsMcaProfession` key is unchanged and still active.

### Fixed

- **Stale criminal records on guards and mugger aborts.** When an active mugger becomes, or is
  discovered to be, a law responder, `mug/npc/NpcMuggingService` and `ai/thief/ThiefBehaviorService`
  abort the session immediately with `mug/npc/NpcMugAbortReason#ACTOR_BECAME_RESPONDER`, and
  `job/NpcMuggerEligibility#contradictory` flags the contradictory record for reconciliation instead of
  leaving it to the next sweep.
- **Non-damaging disruption for Sand Bottles.** Sand blinds without dealing damage and without
  synthesising a damage event, so no false assault charge is raised and no third-party assault listener
  fires for a blow that never landed. `detect/CrimeGate#resolveNonDamageOffender` and
  `detect/DamageIncidentService#completeNonDamage` reuse the ordinary harm path — the same per-pair
  harm cooldown, the same replay guards, detection string `sand_bottle` and damage attribution
  `non_damaging` — rather than duplicating it, so one exposure is one consequence.

## [0.7.1] — unreleased

### Added

- **Masks.** A Clay Mask or Leather Mask (crafted from clay balls or leather; helmet slot, zero
  armour, non-enchantable) defers the Heat of most crimes while worn (command, jailbreak and
  contraband detections are excluded; gated on `mask.maskSuppressesHeat`); Karma still lands
  immediately, so a mask hides law-enforcement pressure, not standing. The banked Heat comes
  due the moment somebody witnesses the mask come off (`mask.maskRemovalWitnessRadius`, default
  `12.0` blocks) or the wearer is jailed. A guard who saw the crime keeps hunting the anonymous
  figure for `mask.maskedPursuitTicks` (default `1200` ticks) — a lawful, non-lethal-by-default
  target that is never bounty-eligible. Guards can also challenge any mask on sight
  (`mask.guardsChallengeMaskWearers`, off by default), and a masked player's nameplate can be
  hidden client-side (`maskHidesNameTag`, on by default). Master switch `mask.maskEnabled` is
  on by default. With `mask.maskHidesIdentityFromWitnesses` (on by default), a masked crime
  writes no villager observation, victim memory or direct guard report, but the victim and
  non-responder eyewitnesses still react in real time through the same evaluator as an unmasked
  crime — a mask hides who did it, not that somebody with a weapon is standing there.

### Changed

- A wanted player is no longer offered the MCA: Quests "Bounty Board" quest when the only postings
  on the board are their own; the guard's offer reason for them is the new translation key
  `quest.mcacrime.bounty.own_warrant` ("The only name on that board is yours."), while an empty
  board still reports `quest.mcacrime.bounty.none_posted`.
- The poster shown to a hunter (the objective text in the MCA: Quests log and tracker) never lists
  the hunter themselves.
- Holders of the contract now lose it (failed as TARGET_LOST) as soon as no posting they can claim
  remains, checked per holder when a bounty is paid, when a warrant is withdrawn, and when a new
  contract is posted; previously copies were only failed when the whole board emptied, so a hunter
  who became wanted kept an unfinishable copy listing their own name.

### Fixed

- An unarmed, non-brave villager under a live aimed threat now complies even with a guard nearby,
  instead of bolting to seek help on the first tick and instantly cancelling the mug; a refused mug
  now reports why ("They bolted for a guard." / "They ran." / "A guard is not mugged.") instead of
  the misleading "Something else has a claim on that action."

## [0.7.0] — unreleased

Family loyalty, family accomplices and bail, tighter mugging frequency, and configurable
contraband.

### Added

- **Family loyalty.** A relative in `relationship.familyLoyalty.familyLoyaltyScope` who witnesses a
  crime may decline to report it: a deterministic, RNG-free score against relationship hearts,
  family tier and personality, with six hard exclusions (victim, the victim's own relative, a
  responder, a minor, a tier out of scope, or Heat above `loyaltyMaxCrimeHeat`). A crime seen only
  by loyal family is un-witnessed for Heat, community standing and family heart loss; the relative's
  memory of it is still recorded, withheld rather than reported (`ReportState.WITHHELD`). On by
  default.
- **Family accomplices.** Three new actions in the Crime menu's `Conspire` category — Ask for a
  Lookout, Ask for a Distraction, Ask for Escape Help — let a player recruit an eligible relative to
  shrink the witness radius, hold civilians' attention elsewhere, or shake off pursuing guards for a
  window. An accomplice can be exposed by a witness who is neither loyal nor in on it, or when the
  principal is arrested while the agreement is active, and is then individually wanted, arrestable
  through the existing NPC custody path, and jailed for `accompliceJailTicks`. On by default.
- **Family bail.** With `enableFamilyBail`, a relative may buy a lawfully held accomplice out of
  the rest of their sentence for a price based on time remaining and prior arrests of that same
  relative, quoted to the client through a new `BailQuoteS2CPacket`. On by default.
- **Mugging protection.** A per-player protection window, shared across every thief rather than
  scoped to one, plus a longer repeat cooldown on the same thief/player pair, a daily mugging cap,
  a live thief-per-jurisdiction cap, and an `enableNpcMugging` master switch. All of it is stored on
  the `mcacrime:player_crime` data attachment as additive fields that read as zero on a save
  written before this release.
- **Configurable contraband.** A new `[contraband]` block lets an operator list illegal items and
  tags; guards discover them through patrol searches or arrest searches, gated on suspicion, line
  of sight and a chance roll. Nested shulker boxes and bundles are scanned one level deep, read
  from their 1.21 data components (`DataComponents.CONTAINER`, `DataComponents.BUNDLE_CONTENTS`).
  Off with an empty list by default.
- **Configurable spoken-line format.** `dialogueMessageFormat`, `dialogueNameColor` and
  `dialogueNameBold` under `[dialogue]` let an operator control how a villager's name and line are
  laid out in chat. Defaults now match MCA Conversations' chat mode (`<Name> line`, bold gold name)
  instead of the previous plain `Name: line`.

### Changed

- Three defaults retuned to reduce excessive mugging: `criminalJobs.villageThiefChance` `0.025` →
  `0.01`; `criminalJobs.assignmentScanIntervalTicks` `1200` → `2400`;
  `criminalJobs.thief.mugCooldownTicks` `12000` → `24000`.
- World data schema `10` → `11` (`SCHEMA_FAMILY`, additive: existing saves load unchanged, no new
  field is required to be present).
- Network protocol `11` → `12` (adds the `BailQuoteS2CPacket` S2C payload; a mismatched client is
  refused).
- NPC crime scope now includes player-recruited family accomplices — villagers still never decide
  to commit a crime on their own; `enableNpcCrime` remains a declared, unwired seam.

### Fixed

- Repeated NPC mugging of the same player by several different thieves in quick succession. Every
  existing cooldown was scoped to the thief, not the victim, so N thieves could each rob the same
  player back to back while individually staying inside their own limit. See
  [the verification doc](docs/FAMILY_CONTRABAND_MUGGING_VERIFICATION.md) for the diagnosis and the
  regression coverage.

### Notes

- `PlayerCrimeData` is a NeoForge data attachment (`mcacrime:player_crime`); every field this
  release adds is additive, so a legacy attachment (or one imported from the Forge capability) reads
  every new field as its zero value rather than needing a migration step of its own.

---

Compatibility: Minecraft 1.21.1 on NeoForge; requires the MCA Reborn version pinned in
`gradle.properties`. Optional: MCA: Reputation, MCA: Quests, Locks Reforged, NeoForge 1.21.1 builds.

## [0.6.4] — unreleased

### Fixed

- Fix the guard confrontation screen overflowing the stack while rendering, reported when
  clicking Surrender. Its background hook no longer calls `Screen.render()`, which immediately
  reentered the hook. Draw widgets, the countdown and the menu-displayed acknowledgment once
  from the main render method, and add regression checks shared with the Forge version.
- See [Surrender crash verification](docs/SURRENDER_CRASH_VERIFICATION.md) for the diagnosis,
  platform differences and client verification steps.

## [0.6.3] — unreleased

### Fixed

- Allow unarmed players to reach Apologize through the Crime button, menu keybind and
  sneak + empty-main-hand interaction, even when a frightened villager refuses conversation.
  Keep coercive actions behind their weapon requirements. Explain the one-minute settling
  period, repeat-apology cooldown, already accepted apologies and active threats separately.
  Refusal dialogue now explains the reconciliation shortcut.
- Asking a guard to show the charges, or opening a detention with no local charges, now sends
  readable fallback text alongside the translation key. A client missing the Wanted explanation
  no longer prints `mcacrime.challenge.reason.wanted` in chat. Existing client translations and
  resource-pack overrides still take precedence. The same protection covers resisting arrest,
  escaped custody, unlawful captivity and the no-charges response.

## [0.6.2] — unreleased

### Added

- Villagers, including thieves, will not mug players with 50 or more MCA relationship hearts
  with that villager. Configure `criminalJobs.thief.mugProtectionHearts` in the common config;
  `-1` disables relationship protection and `0` protects neutral and positive relationships.

### Fixed

- Wanted Heat now independently authorizes guard and archer pursuit and confrontation, including
  `/crime set heat <player> 100` without a recorded offense. Keep the configurable Wanted threshold
  (default 50), local case/fine scope, and existing response window. Explain Heat-only detention
  instead of displaying zero charges and claiming there is nothing against the player.
- Keep an active refusal enforceable after Heat drops, permit arrests on the same Wanted/refusal
  basis, and exclude captive responders from starting or continuing a confrontation.
- Recheck mugging eligibility during selection, approach, active sessions and immediately before
  theft. Relationship/config changes now stop an attempt before items or currency move, and
  `/crime mugtest` respects the same protections.
- Honor invulnerability, configured victim protection, custody and game-mode changes throughout
  an attempt. Check weapons and reach at both the start and completion, even when periodic weapon
  checks are configured less frequently. Recheck eligibility after external attempt/balance callbacks.
- Close the victim's HUD and mark the session aborted when a final check refuses theft. Refuse
  premature completion and starting another mugging while the thief already has a session.

## [0.6.1] — 2026-09-08

### Fixed

- Allow `/crime set heat <player> <value>` at permission level 2, so command blocks can set
  a selected player's heat for scripted areas such as bank vaults. Previously, the level-3
  requirement rejected command-block execution before the target selector was evaluated.
- Keep `/crime set karma` and other administrative changes at permission level 3, and continue
  rejecting heat commands from sources below level 2.
- Add regression tests for command parsing without an executing player, permission boundaries,
  and nonnegative heat values. Update the command reference with the heat-command permission.

## [0.6.0] — unreleased

Villager death loot:

- Limit death loot to one purchase worth of output per available trade (for example, one iron
  axe), rather than multiplying the output by remaining trade uses. Apply the same limit to fences.
- Drop actual equipped gear and one output bundle per unlocked, non-exhausted trade by default, including
  guard/archer equipment. Preserve item names, enchantments, durability and other item data.
- Recover equipment before MCA clears it during death, without duplicating gear carried in MCA's
  inventory or already included in normal drops. Newly added gear respects Curse of Vanishing.
- Check fences' persisted stock before dropping one item per available selling offer; exclude
  their buying orders and do not restock on death.
- Honor `doMobLoot` and loot-event cancellation. Add independent gear/stock toggles and a bounded
  trade-stack limit under `[loot]`; retain the old small profession drops as an opt-in fallback.
- See [death loot verification](docs/DEATH_LOOT_VERIFICATION.md) for coverage and scope.

Sleep and archer follow-up:

- Sleeping villagers cannot see or hear crimes, acquire new observations, report, gossip, flee,
  threaten players, trade, or respond to conversations. Existing memories are retained for after waking.
- Sleeping guards and archers are unavailable for challenges, pursuit, escort assignments and bounty
  hand-ins. Sleep during a challenge ends the conversation without treating it as refusal.
- Clear stale navigation, attack targets, fear speed modifiers and drawn bows while an MCA NPC sleeps,
  without cancelling its tick or forcing it awake. Damage keeps its normal wake-up behavior; physical
  restraint wakes a captive before a leash can drag them out of bed.
- Recognize MCA archers as law responders alongside guards, including immunity to civilian fear AI.
- Reject sleep-dependent interactions on the server and close a fence trade when the fence falls asleep.

HUD, escort and reaction refinements:

- Combine Heat/Wanted and Sentence/Captivity into one bottom-left panel, below passive chat and
  beside the hotbar. Fit the panel to the available GUI space and hide it while typing in chat.
  Migrate the former unmodified top-left default once; preserve custom placements.
- Give guard confrontations at least **15 seconds**, starting with the first displayed menu frame.
  Delivery acknowledgment has a bounded five-second allowance; reopening, requoting and replaying
  acknowledgments cannot renew the response window. Keep the speaking guard facing the suspect.
- Remove the competing legacy flee navigator. Apply civilian speed penalties on direct reaction
  entry too, use normal MCA walking multipliers and cap crime-directed movement. Retain successful
  flee paths and distinguish an interrupted path from arrival.
- Keep guards and other responders out of civilian fear/compliance states and fear-based interaction
  refusals. Reaction cleanup preserves active law routes and targets; responders file their own reports.
- Route escorts to reachable intake stands outside generated cells, consider alternative entrances
  and bounded segments toward distant assigned jails, allow detours and wait for the prisoner before
  the lead gets taut. Stop the guard's route at intake; retain the blocked-route teleport failsafe.
- Reject generated-cell sites containing living occupants, including the interior and wall margins.
  Repair older cells by moving trapped non-prisoner villagers/guards to checked safe exterior stands.
- Network protocol is now **11**; world schema remains **10**. See
  [HUD and AI verification](docs/HUD_AI_VERIFICATION.md) for coverage and remaining live checks.

Bounty payment recovery and operator tools:

- Reserve a provider-bound payment receipt alongside each new bounty claim before attempting credit.
  Retain exact emerald overflow in the receipt; `/crime collectbounty` and login collect known unpaid
  remainders. Uncertain external credits stop automatic retries. Pending claims survive normal expiry.
- Defer bounty completion events, Karma and paid messages until full payment is confirmed. Changing
  the active currency never converts a saved reward; collection resolves its recorded provider.
- Add permission-level-three `/crime recovery` receipt/escrow/quarantine/audit inspection and JSON
  export, plus revision-checked, noted operator acknowledgement/rearming. Corrections retain the prior
  receipt/property payload and do not directly issue money/items or replay bounty completion effects.
- Advance world data to schema 10 to protect the new audit records from older builds. Older saves
  migrate without inventing payment receipts for historical claims. See
  [recovery operations](docs/RECOVERY_OPERATIONS.md) for commands and cross-system crash limits.

Remaining-work review follow-up and cuff lockpicking:

- When Locks Reforged is installed, ordinary and locked cuff self-escapes require solving its native
  lockpicking minigame. Store the combination with custody, validate pins on the server, and invalidate
  attempts when custody, restraint, dimension or required-pick possession changes. Rope keeps its
  existing behavior. `kidnapping.cuffEscapeRequiresLockpick` defaults to `false`; enabling it requires
  a pick anywhere in inventory. Picks are not consumed or damaged by cuff attempts.
- Successful lawful cuff escapes preserve the sentence and surrender credit, end the escort, pause
  sentence service and file jailbreak. Recapture resumes the original sentence.
- Reserve economic receipts and stolen-property capacity before removing money/items. Reject changed
  transaction replays and retain ambiguous debit failures for operator reconciliation. These stores
  still cannot atomically save an external economy and Minecraft inventory/world data.
- Return stolen goods on arrest through provider-aware owner escrow and shared delivery receipts.
- Reconcile thief controllers after job changes and enable/disable reloads. Ignore other mods' config
  reloads and schedule live reload work on the server thread.
- Populate the public sentence view's identity and exact linked cases. Add a finite configurable
  locked-cuff work duration for nonzero timed escape chances when Locks Reforged is absent.

Confirmed death and property recovery follow-up
([phase notes](docs/MCA_CRIME_PHASE2_DEATH_RECOVERY.md)):

- Apply kill rewards, stolen-property recovery and death-specific custody/reaction/thief cleanup
  only after shared death confirmation. Reject cancellation, revival and repeated corpse callbacks.
- Require both bounty eligibility and lawful lethal force for kill rewards. Freeze the warrant
  revision, price and currency; refuse closed/replaced/revised warrants or a changed currency.
- Move stolen property to owner escrow after confirmed thief death instead of adding it to native
  death drops. Attempt delivery for online owners and retain undelivered property for login.
- Record escrow delivery attempts before external transfer. Keep exact partial remainders; suspend
  automatic retry after ambiguous outcomes. Full receipt/escrow tables preserve the property record.
- Preserve the currency provider on new stolen-goods records and retain legacy records with an
  unidentified provider. No additional schema, protocol or mod-version change.

Incident and combat follow-up ([phase notes](docs/MCA_CRIME_PHASE2_INCIDENTS.md)):

- Coordinate player, NPC and ransom records through checked incident insertion; reject repeated IDs
  and read-only stores before consequences, and defer incident notifications until state/evidence exist.
- Reconcile final damage and death events at server tick end. Armor reduction, zero/canceled damage,
  absorption, totems and canceled deaths no longer depend on an early lethal prediction.
- Record initiating aggression and lawful response in bounded, server/dimension-scoped encounters.
  AI targeting grants no self-defense exemption; nonlethal defense and lethal permission remain separate.
- Narrow raid grace to one nonlethal indirect explosion per encounter against a non-player,
  non-responder. Attribute supported tame-animal damage to its loaded player owner.
- Bind NPC commit notifications and ransom audit rows to their existing transaction/demand IDs.
  Confirm death before releasing custody. No save-schema, protocol or mod-version change.

Local law and settlement follow-up ([phase notes](docs/MCA_CRIME_PHASE2_JUSTICE.md)):

- Share local evidence assessment across guard pursuit, challenge, charge review and arrest; private case counts and stale refusal alone grant no guard authority.
- Freeze each displayed fine to its cases, penalties, policy, currency and Heat. Changed offers require another response and never debit on the stale click.
- Settle exact selections without adopting unrelated cases; cap Heat reduction by the selected cases' contribution. Commands and the Crime menu answer the open guard offer; outside an encounter, voluntary whole-record settlement remains available.
- Keep failed-payment screens open, refresh prices and controls by encounter revision, and retain the original response deadline. Surrender stays with the challenging guard.
- Run each resolution preflight once before debit, commit all selected cases before notifying listeners, and publish live case notifications after Heat is updated.
- Add responder basis and case IDs to `/crime debug guards`. No save-schema or mod-version change in this pass; the changed guard packets require protocol 10.

Arrest and custody follow-up ([implementation and migration notes](docs/MCA_CRIME_PHASE2_CUSTODY.md)):

- Preserve the original reported NPC case through arrest; record witnessed interventions once, before pursuit.
- Bind assessed charges at arrest and persist sentence identity independently of generated cells; prevent arrival/relogin from adopting later crimes.
- Bound NPC escort recovery across restarts, validate detention destinations, and protect guards assigned to another prisoner.
- Reconcile captive thief AI, recheck mugging participants before transfer, and remove resolved cases from report authority.
- Pause PHYSICAL escape sentence credit; return, surrender and guard recapture resume the original sentence without a second discount.
- Validate challenge conversations and retain the response window after a failed payment.

Witness, intimidation, victim memory and integrity release. Development reference and the in-world
verification checklist are in [the 0.6.0 phase notes](docs/MCA_CRIME_0.6.0_WITNESS_MEMORY.md).
This update closes reliability defects in payment, capture,
sentencing, packets, persistence, and world recovery; guards the frontier between older and newer
saves; and hardens the finite-economy guarantees. Fines now quote the exact amount per case and
refuse outright when not allowed; surrender only writes its discount after successful arrest;
sentences bind only their own cases; and a save written by a newer version is opened read-only
until the problem is fixed. Protocol and schema bump to prevent incompatible clients and worlds
from silently disagreeing.

### Added

- Per-crime visual and auditory awareness, anonymous hearing, identification confidence and local guard reporting.
- Dynamic compliance, panic, stalling, defiance and resistance using MCA personality, weapons, health, memory and nearby support.
- Bounded persistent fear/anger memories, repeated-offense merging, lazy decay, reduced family/witness memories and one-hop local gossip.
- Contextual interaction refusal, bounded apologies, matching-case restitution and sentence reconciliation.
- Immutable memory API context, a memory-change event, dialogue pools and witness/threat/memory debug commands.

- **New config option `criminalJobs.fence.offerMaxUses`.** How many times each fence trade may be
  repeated before that specific offer runs out. Uses are persisted per fence and survive relogging
  and restarts; they reset when the fence restocks (default: 8, range: 1–4096).
- **Fence stock persistence.** Stock no longer resets on screen close — each fence's uses and
  available trades are persisted in the save and survive restocking intervals.
- **Five new lang keys** for new player-facing outcomes: `mcacrime.surrender.already_serving`,
  `mcacrime.surrender.failed`, `mcacrime.arrest.no_custody`, `mcacrime.readonly`, and
  `mcacrime.escrow.delivered`.
- **Transaction receipts** track payment state (PREPARED, SOURCE_DEBITED, DELIVERY_PENDING, DELIVERED,
  REJECTED, NEEDS_RECONCILIATION) for finite-economy auditing and recovery. Debit is recorded before
  credit; credit failure marks the receipt as NEEDS_RECONCILIATION rather than retrying.
- **Property escrow.** Stolen goods that cannot be delivered to their owner become a property lot
  and are delivered on player login.
- **Restored-cell journal.** Cells that fail block restoration mid-dismantle record the unresolved
  block positions for retry rather than silently abandoning them.
- **Safe-destination validation** before teleporting a player into jail. Collision, support,
  hazards, fluids, border, chunk load state, and occupied cells are all checked; arrest fails
  with a clear outcome if teleport cannot succeed.
- **Sentence identity** linking case resolutions to their sentence. NPC release now settles the
  NPC's cases, not unrelated ones.
- **Bounty claim tracking.** Each claim carries how much was paid, so revision and expiry do not
  reopen rewards that were already collected.
- **Read-only mode** for worlds written by a newer schema. Operators with sufficient permission
  see a `mcacrime.readonly` message; all Crime mutations are blocked until the version mismatch
  is resolved.

### Changed

- **Protocol version 7 → 10.** Every packet now declares its direction explicitly; array and list
  bounds are validated at decode rather than clamped; and a `RequestBudget` per player throttles
  menu and ledger requests. Clients on older protocols are rejected at handshake.
- **World data schema 7 → 9.** This build adds optional category memories and witness relay markers,
  as well as the schema 8 development fields and collections for sentence
  identity, bounty claim payment tracking, transaction receipts, property escrow, restored-cell
  journal, and fence stock. Legacy fields are absent by default; existing worlds are not rewritten
  on load. Legacy sentences bind their cases at the offender's first login; legacy bounty claims
  count as fully paid.
- **Fines now quote and settle per-case.** `SettlementPolicy` computes an exact per-case amount
  before any debit; the quote is used by the guard screen, dossier, command and action handler
  alike. Cases flagged `MANDATORY_CUSTODY` refuse the fine regardless of entry point, and a
  failed resolution leaves Heat untouched.
- **Surrender only writes its discount after arrest succeeds.** Heat reduction, surrender
  timestamp and discount are now deferred until custody is committed. Repeat surrender while a
  sentence is active is refused with `mcacrime.surrender.already_serving`. Failure to establish
  custody produces `mcacrime.surrender.failed` and leaves the offender exactly as found.
- **Sentences now bind their cases.** `SentenceResolutionService` settles only the cases that
  were sentenced, not all open cases. NPC release now settles the NPC's own cases through the
  same path, rather than shadowing player releases.
- **Restraint consumption timing.** The restraint item is consumed only after custody is
  committed, not when capture starts. Failed captures leave inventory untouched.
- **Restraint visual policy.** Players and villagers held by hunters or kidnappers now render
  with pose and cuffs, not only lawfully arrested players. `RestraintPolicy` decides who is
  posed and restricted.
- **Capture commit result.** `ArrestService.Outcome` carries typed outcomes including `NO_CUSTODY` and
  `NO_CELL` (distinct from other failures), which abort an arrest instead of accepting the capture.
  `CaptureCommitResult` carries its own typed outcomes: CAPTURED, ALREADY_HELD, QUOTA_FULL,
  TARGET_INVALID, RESTRAINT_MISSING, SESSION_LOST, GATED.
- **Fence pricing validator.** `ConfigValidator` now warns and clamps effective `buyPriceRatio`
  if it would make the final price negative or non-finite. `FencePriceLoader` rejects entries
  that are non-finite, out of range, or name unknown items; keeps the last good map when a file
  fails; and replaces stock (rather than OR-merging) when `"sells"` or `"buys"` appears in a
  price file.
- **World-data robustness.** `ServerMutationGate` closes mutations when the save is from a newer
  schema or failed to load. Malformed records are quarantined in a bounded list and saved back
  out for manual inspection. Capacity limits are enforced at insertion via `CapacityResult`
  rather than truncating at load.

### Fixed

- **Payment no longer risks double-crediting.** `EconomicTransactionService` writes
  `SOURCE_DEBITED` and dirties the store before attempting the credit; a credit that fails or
  throws leaves the receipt `NEEDS_RECONCILIATION` instead of being retried.
- **Cell removal restores blocks before its restoration record is cleared.**
  `HoldingCellService.dismantle` demolishes the cell and only clears the pending-restoration
  journal entry once every block position came back; unresolved positions stay journaled for a
  later sweep instead of being silently abandoned.
- **Jail and custody teleports validate their destination.** `SafeCustodyDestination.validate` is
  checked before `JailService` and `HoldingCellService` move a player, instead of assuming a loaded
  chunk or three air blocks is safe.

### Notes

- **Three baseline defects were deliberately not ported:**
  - The baseline's `ServerPacketGuard` was not ported. NeoForge's payload registrar already runs
    every server-bound handler on the main thread, and each handler in `CrimeNetwork` already
    checks for a `ServerPlayer` before doing anything.
  - The baseline's `PacketBounds` was not ported. This port's payloads already bound every string,
    list, and map they carry, and an unknown enum ordinal already throws at decode — which is the
    whole of that policy.
  - The baseline's packet protocol bump was not carried over. No encoded payload shape changed in
    this release, so `CrimeNetwork`'s protocol version stays at `8` and the compatibility invariant
    is unchanged.
- **B09 and B22 are partially addressed.** B09 (bounty revision) has a no-repay delta guard
  (`BountyService.pay`, `BountyClaimLedger.alreadyPaid`) so a reopened warrant pays only the
  difference, but there is no reward-lot redesign. B22 (cell removal reliability) journals
  unresolved block positions for retry (`HoldingCellService`, `pendingCellRestorations`), but there
  is no policy for a cell altered by pistons or explosions.

---

Compatibility: Minecraft 1.21.1 on NeoForge; requires the MCA Reborn version pinned in
`gradle.properties`. Optional: MCA: Reputation, MCA: Quests, NeoForge 1.21.1 builds.

## [0.5.1] — unreleased

Criminal NPCs and their social ecology. Restrained villagers render with arms behind their back
and wrist restraints on every tracking client. Civilians freeze when threatened; armed villagers
resist. Thieves scout, mug, and flee autonomously; fences trade contraband with pricing that
reflects community standing and police attention. A unified outlaw authority governs guard
hostility, assault legality, and bounty eligibility. Bounties for killing or delivering escaped
prisoners reward players without gaming through repeated suicide.

### Added

- **Restraint artwork.** The three restraint items (open cuffs, locked cuffs, rope) now use custom
  icons by TheWiggleDuck instead of placeholder textures.
- **Restrained villager pose and wrist visuals.** Restrained MCA villagers hold both arms behind
  their back on all tracking clients, including those joining after the restraint was applied.
  Cuffed and rope-bound wrists render with distinct visuals.
- **Weapon-point compliance.** Unarmed civilian MCA villagers freeze in place and move more slowly
  during an active coercive crime. Armed villagers (guards, archers, weapon-holders, tagged combat
  NPCs) resist instead. Cancelling the coercive action restores full movement.
- **Criminal occupation system.** Thief and Fence are persisted criminal jobs, assigned to
  villagers through an optional sweep and shown as vanilla professions when enabled by config.
- **Autonomous thieves.** Thieves scout for targets, approach and threaten players, mug them for
  currency and one eligible inventory item (with protection for hotbar, armor, and off-hand by
  config), and flee. Mugging displays a timed HUD channel. Drawing a weapon or guard intervention
  aborts the mug before any theft occurs. Stolen goods are persisted by thief and owner, recovered
  on the thief's death or arrest, and protected from duplication on save/reload cycles.
- **Contraband trading.** Fences trade tag-driven illicit goods with pricing that adjusts for the
  buyer's karma, Heat, and Wanted status: discounts for ally NPCs and surcharges for criminals and
  fugitives, with Locks Reforged lock/pick support (optional, degrades gracefully). Fence stock
  cycles on a configurable day interval. Fences accept trade unarmed when opened from the Crime
  menu.
- **Guard intervention in NPC crime.** Guards pursue thieves, arrest them, apply restraints, escort
  them to jail, and release them after a sentence. Caught-in-the-act mugging creates mandatory
  custody that cannot be paid off with a fine.
- **Unified outlaw authority.** A single `OutlawResolver` now governs guard hostility, whether
  force is lawful, and bounty eligibility, so no player gains crime liability for force the law
  itself permits against an outlaw.
- **Bounty system.** Qualified kills of eligible outlaws can result in configurable bounty rewards
  (enabled by default). Bounty claims are keyed on warrant id + revision to prevent double-payment
  across respawns and failed arrests. Alive captures pay a multiplier of the kill reward. Optional
  MCA: Quests integration publishes bounties as guard-given contracts that never double-pay.
- **Crime button at bottom of MCA screen.** The Crime button now sits at the bottom of MCA's
  villager interaction screen (configurable position), is disabled without a drawn weapon, and
  shows a tooltip explaining why.
- **Crime button server validation.** The server re-validates the weapon requirement when the menu
  packet arrives, so client and server always agree on what is permitted.
- **Weapon policy sync.** The server sends the exact weapon classification policy to every client on
  login and `/crime reload`, so the Crime button state matches the server's rules.
- **Fence unarmed access.** Fences can be traded with unarmed through the Crime menu, bypassing the
  weapon requirement gate only for fence-specific trades.
- **Thief command suite.** `/crime job assign thief` and `/crime job clear` manage criminal
  assignments; `/crime job list` and `/crime debug thieves` report state.
- **Warrant system.** Warrants track open bounties per outlaw with revisions bumped on each new
  qualifying crime, preventing bounty claims from one warrant across multiple versions.
- **Bounty command suite.** `/crime warrant <player>` and `/crime bounty <player>` query outlaw
  status and bounty quotes; `/crime debug bounty` reports active bounties.
- **New config sections.** `criminalJobs`, `criminalJobs.thief`, `criminalJobs.fence`, and
  `bounty` sections added with every tuning value. All numeric keys are validated by
  `/crime validate`.
- **New entity tags.** `mcacrime:always_resists_weapon_threats`, `mcacrime:never_resists_weapon_threats`,
  and `mcacrime:armed_villager_roles` allow datapacks to customize threat compliance per entity type.
- **New item tags.** `mcacrime:illicit_goods`, `mcacrime:fence_sells`, `mcacrime:fence_buys`,
  `mcacrime:fence_blacklist`, and `mcacrime:thief_theft_immune` allow datapacks to drive fence
  pricing and thief targeting.
- **New API event classes.** `BountyResolvedEvent`, `CrimeAttemptEvent`, `CrimeIntentEvent`,
  `CriminalJobChangedEvent`, `FenceTradeEvent`, `NpcCrimeCommittedEvent`, and `WarrantChangedEvent`
  fire on bounty claims, crime attempts, crime intents, criminal job changes, fence transactions,
  NPC crime commission, and warrant changes respectively.
- **New commands.** `/crime job`, `/crime warrant`, `/crime bounty`, `/crime debug thieves`,
  `/crime debug bounty`, and `/crime debug jobs` for criminal management and bounty queries.

### Changed

- **World data schema 6 → 7.** Adds six new collections — criminal villagers, stolen goods,
  warrants, bounty claims, bounty contracts, and fence restock stamps. All new collections default
  to empty; absent entries read as empty everywhere, so existing worlds load without size increase.
- **Network protocol 7 → 8.** New S2C payloads for weapon policy sync and criminal job sync. Two
  new payload types; 17 total.
- **Restrained pose mixin target clarified.** The mixin injects at AFTER `EntityModel.setupAnim`
  call within `LivingEntityRenderer.render`, executed on clients that track the restrained entity,
  so late-joining clients see the pose immediately on entity spawn.
- **HUD outcome text now pre-formatted.** `ActionProgressS2CPacket` carries `outcomeText` as a
  pre-formatted `Component` rather than a bare key, so outcomes with dynamic content (fine amounts,
  stolen item names, ransom fees) render correctly without client-side re-translation.

### Fixed

- **HUD outcome line showed literal `%s` placeholder.** Pre-existing issue: outcome keys carried
  arguments but were re-translated clientside without the argument values. The server now sends both
  the identity key and a pre-formatted `Component` with arguments substituted, eliminating the
  placeholder visibility and the need for client-side translation logic.

### Notes

- Locks Reforged integration (optional) supports registry-based lock/pick lookups for fence pricing
  tiers. No 1.21.1 port of Locks Reforged exists at this time, so the integration cannot be tested
  but degrades gracefully if the mod is absent.
- MCA: Quests integration (optional) publishes bounties as guard-given bounty-board quests that
  never double-pay the principal. Compiled only when `../MCAQuests_1.21.1/build/classes/java/main`
  exists; `-PrequireQuests` forces the build to fail if absent.
- `CrimeDebug` now supports live toggling of debug logging via config reloads.

---

Compatibility: Minecraft 1.21.1 on NeoForge; requires the MCA Reborn version pinned in
`gradle.properties`. Optional: MCA: Reputation, MCA: Quests, NeoForge 1.21.1 builds.

## [0.5.0] — NeoForge 1.21.1 port

The maintainer deliberately kept the same version number across the loader change. The Minecraft/NeoForge
dependency ranges distinguish this artifact from any future Forge build.

**Back up your worlds before upgrading.** Player data is migrated from the legacy Forge capability format
on first load and the migration is not reversible.

### Changed (Forge 1.20.1 → NeoForge 1.21.1)

- **Moved to NeoForge for Minecraft 1.21.1; Java 21 toolchain; MCA dependency pinned to the tested NeoForge build in `gradle.properties`**.
- Player data is stored as a NeoForge data attachment `mcacrime:player_crime` rather than a Forge
  capability. Legacy Forge capability data under `ForgeCaps` is imported once on player load; new-format
  data always wins.
- Network protocol bumped from `6` to `7`: custom payloads with named identifiers replace numeric
  discriminators. Protocol 6 cannot talk to protocol 7.
- Resource paths are now singular: `data/<ns>/mcacrime/crimes/*.json`, `data/<ns>/mcacrime/dialogue/*.json`,
  and `data/<ns>/mcareputation/incidents/*.json` (was `recipes/`, `tags/items/`).
- Item tags now use the common `c:` prefix: `c:ropes` for restraints (was `forge:ropes`). Custom item
  tags like `#mcacrime:weapons` remain under the `mcacrime` namespace.
- The one client-side mixin that poses a restrained player's arms is now registered via `neoforge.mods.toml`
  rather than JAR manifest attributes (Forge-era MixinConfigs).
- Config API changed from `ForgeConfigSpec` to `ModConfigSpec` (implementation detail; TOML files are
  compatible).
- Event annotations changed from `@Mod.EventBusSubscriber` to `@EventBusSubscriber` (NeoForge).
- Events fire on `NeoForge.EVENT_BUS` instead of `MinecraftForge.EVENT_BUS`.
- Architectury is no longer required at runtime. The MCA version in `gradle.properties` does not use it.
- **MCA: Reputation companion requirement:** if you use the optional MCA: Reputation integration, use the NeoForge build. The integration handshake is API-version-gated and degrades gracefully if absent, but a Forge-era companion cannot load on this version.
- World data file `data/mcacrime.dat` remains schema 6; no migration needed for world data.

---

Compatibility: Minecraft 1.21.1 on NeoForge; requires the MCA Reborn version pinned in `gradle.properties`.
Optional: MCA: Reputation, NeoForge build.

## [0.5.0] — unreleased

Armed interactions. The crime menu now opens the way the fiction already implied it should: by
drawing a weapon on somebody.

### Added

- **A weapon classifier.** Swords, axes, tridents, bows, crossbows and modded firearms are recognised
  automatically; a `[weapons]` config block adds a whitelist and a blacklist (item ids or `#tags`),
  and the `mcacrime:weapons` / `mcacrime:weapons_blacklist` item tags let a datapack contribute
  without editing anybody's config. Guns are found by name (`gunKeywords`) and by namespace
  (`weaponMods`, a list of editable guesses), and the namespace rule ignores stackable and block items
  so a gun mod's ammo and workbenches are not weapons. The last-resort rule is a bonus-attack-damage
  threshold, with digging tools excluded above it so a diamond pickaxe stays a pickaxe.
- **An unbound "open the crime menu" keybind**, aimed at whoever is under the crosshair. It replaces
  the removed unarmed gesture for players who would rather not draw on a villager to open a menu; the
  server validates range and line of sight exactly as it does for every other way in.
- **`/crime debug weapon`** prints the held item's id, its class, the rule layer that decided it, and
  the threshold in force — so "why is my sword not a weapon" has a one-line answer.
- **`client.showButtonOnMcaScreen`** turns off the Crime button this mod adds to MCA's own
  interaction screen.

### Changed

- **The crime menu opens on right-clicking an MCA villager while holding a weapon.** The old
  sneak + empty-hand fallback is gone: it was a gesture nobody discovers and it competed with every
  ordinary interaction a villager has. Sneaking is not required by default
  (`weaponTrigger.requireSneak`), the off hand counts (`weaponTrigger.allowOffHand`), and the whole
  trigger can be switched off (`weaponTrigger.enabled`).
- **Mugging requires a weapon** (`weapons.mugRequiresWeapon`, on by default). Unarmed, the row stays
  visible on the menu and says what is missing, rather than vanishing.
- Restraints keep their claim on the interaction: the weapon trigger runs after capture, and a
  restraint is never classified as a weapon.
- **The Heat and sentence boxes now default to the bottom-left corner.** The top-left is where
  MCA: Quests draws its quest log, and the quest log won. Bottom anchors are also lifted clear of the
  hotbar and the health and armor HUD elements, with a configuration section for players who want to
  move them elsewhere.
- **Interaction priority is now strict.** Restrained villagers always show their restraint status
  and interaction menu rather than running ordinary MCA dialogue, fences with open offers bypass
  conversation directly into trading, and hostile or challenged guards reject unarmed talk without
  falling through to default chat.
- **Jail dismantle no longer drops cell blocks as items.** Cell removal cleanly restores the prior
  world state without dropping iron bars, doors, or lights on the floor.

### Fixed

- Fix player disguise not resetting properly when removing armor via inventory shift-click or number keys.
- Fix guard pursuit pathing failing when the suspect navigates across slab or stair block transitions.
- Fix fine calculation multiplying the base charge twice when multiple offenses of the same category were recorded.
- Fix fence trading GUI closing unexpectedly when the player's inventory changes due to an incoming server sync.

## [0.4.0] — unreleased

Bail, custody overhaul and fence trading refinements.

### Added

- **Bail.** Players serving a custodial sentence can have their bail paid by another player at a guard
  or warden. The cost is calculated from remaining time, crime severity, and prior convictions.
- **Fence stock rotation.** Fences restock and rotate their offers on a configurable day cycle.
  Persistent fence records track available stock and prices across server restarts.
- **Restraint durability.** Ropes and cuffs take durability damage when restraining resisting targets
  and break when depleted.

### Changed

- Guard aggression during an active hunt no longer transfers to unrelated innocent bystanders who
  enter the guard's field of view.
- Custody transfers to holding cells now verify target cell occupancy before moving the captive.

### Fixed

- Prevent players from escaping custody via ender pearl teleportation while restrained in cuffs.
- Fix duplicate fine assessments when an offender is simultaneously confronted by two guards.
- Fix corrupted sentence state when a server restarts while a player is in transit to a cell.

---

Compatibility: Minecraft 1.21.1 on NeoForge; requires the MCA Reborn version pinned in `gradle.properties`.

## [0.3.0] — unreleased

First playable preview of the detention and holding cell system.

### Added

- Holding cell generation and assignment for arrested players.
- Guard confrontation dialogue and surrender workflow.
- Sentence timer tracking and automatic release upon serving time.

### Changed

- Crime tracking records are now scoped to the Overworld dimension by default.

### Fixed

- Resolve server crash when attempting to sentence an offline player.

## [0.2.0] — unreleased

Core crime detection and guard response mechanics.

### Added

- Heat and Karma tracking systems.
- Guard suspicion and pursuit mechanics.
- Theft and assault detection in village boundaries.

### Changed

- Tune villager line of sight and awareness cones for crime detection.

### Fixed

- Correct Heat decay rates during uninterrupted peaceful periods.

## [0.1.0] — unpublished

Initial prototype.

### Added

- Project skeleton, build scripts, data attachment boilerplate and preliminary command tree.
- Capability migration stub from Forge 1.20.1.

---

**Server surfaces**

- The `/crime` command tree: status and self-service for players, reads at permission level 2, and
  mutation at level 3. Every mutator routes through the state chokepoint rather than writing
  capability NBT directly.
- A dedicated network channel, independent of MCA's. Every packet is server to client and
  display-only; the client never mutates state.
- Player data on a capability copied across death and dimension change, and world data in saved
  data pinned to the Overworld's storage.
- Configuration in which every number, chance, threshold, duration, and toggle is an option rather
  than a constant, with a validator that runs at load and on `/crime validate`.

### Compatibility

- **Works standalone.** MCA Reborn is the only requirement.
- **No Architectury dependency.** MCA 7.6 declares it itself and 7.7 dropped it; this mod names no
  Architectury type, so a 7.7 user who removed it is not blocked.
- **No mixins.** Everything that could have wanted one — the inventory card, nameplate colouring,
  chat colouring — uses a Forge event instead, so there is no MCA-internal signature to drift
  against.
- Every MCA symbol is reached through a single adapter class, so MCA API drift is a one-file fix.
  MCA Reborn ships a Forgix-merged universal jar whose Forge classes are relocated, in both the
  production and dev-remapped jars; the adapter consumes the relocated names.

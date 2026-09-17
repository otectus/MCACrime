# Changelog

All notable changes to MCA: Crime.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Compatibility: Minecraft 1.20.1 · Forge 47.x · requires MCA Reborn `[7.6,8)`, built against
`7.6.20`. Architectury is deliberately not declared — MCA 7.6 pulls it in itself and MCA 7.7
dropped it. Optional: MCA: Reputation `[0.2,)`; the integration itself needs `0.3.0`, `0.4.1`
unlocks receipted delivery and read-only lookup, `0.6.0` unlocks public profiles, and every older
companion degrades to the surface it does have rather than failing the load. Also optional:
Townstead, reached only by reflection and a plugin-gated mixin config, and silently absent on an
install that does not have it.

## [0.7.4] — unreleased

Two optional layers on top of the Townstead seam: explicit property law, and civic work with
optional village economy profiles. Every switch added here defaults to **off**, and an install with
them off — or without Townstead at all — behaves exactly as 0.7.3 did.

### Added

- **Explicit property law (`townstead.propertyLaw`, off by default).** A new `property/` package.
  `PropertyPolicy` is one statement of ownership — owner kind, container or building scope, an
  access rule, and the source that declared it — held by `PropertyRegistry`. `PropertyAccess` is the
  whole permission table as a pure function and answers `ALLOWED`, `DENIED` or `UNKNOWN`, where
  **`UNKNOWN` never charges anyone**: no policy, an unidentifiable actor or an owner nobody can
  compare against is reported, never prosecuted. Ownership is never inferred — a generated building
  does not create ownership, and an existing player chest stays unclaimed until somebody claims it.
- **Transfer attribution that has to prove who did it.** `ContainerTransferWatcher` re-counts the
  container side and the actor side once per server tick and attributes a quantity only when the
  container lost exactly what that actor gained, of the same item, in the same tick. Open-and-close
  diffing is deliberately not used. Only four vanilla storage menus are watched — `ChestMenu`,
  `ShulkerBoxMenu`, `HopperMenu`, `DispenserMenu` — positioned by the player's own right-click.
  `TransferAttribution` then produces exactly one of five outcomes, and only `CHARGED` creates a
  crime: `PERMITTED`, `AUTHORISED_WORK` (a live `WorkTransferContext` covers this worker, this
  source and this moment — a role label alone never does), `UNATTRIBUTED` and `UNSUPPORTED`.
  Transfers group into one incident per actor, policy and 100-tick window.
- **Property receipts and restitution.** `PropertyReceipt` is the durable record of one loss —
  `LOST`, `RESTORED` or `UNRESOLVED` — keyed by transfer id, so one committed transfer yields one
  receipt however often the detector re-observes it, and the owner is copied in at the moment of
  loss so a later change of owner cannot rewrite who was robbed. Returning the goods to the same
  container, or paying the fine, marks the receipt restored.
- **Bounded automatic protection (`townstead.autoProtectGeneratedProperty`, off by default).**
  `PropertyAutoProtection` derives policies only from facilities an operator already assigned whose
  role is `evidence_storage` or `jail_cell`, plus the recognised building at those anchors where
  building enumeration is available. It never walks the world looking for chests and never touches a
  policy an operator wrote.
- **A fifth Townstead mixin, `mixin/townstead/StoragePolicyMixin`.** It forces
  `StorageSearchContext#isProtectedStorage` to `true` for containers MCA: Crime marks protected from
  auto-sourcing, so a settlement worker cannot quietly empty an impound chest. It never forces the
  answer the other way. This is what moves the `storage_policy` capability from `unavailable` to
  `available (mixin)`.
- **Typed jurisdiction for incidents.** `incident/IncidentContext` decides which community owns a
  case in a fixed order — the property's community first, then the victim's home, then the event
  location — and `ledger/CrimeContext` carries the new incident dimension and position, the
  community basis, the property id and revision, and the transfer id and grouping key.
- **Civic work (`townstead.communityService`, off by default).** `civic/CivicTask` has three kinds,
  each credited by something MCA: Crime already observes — the bounty and apology tasks from
  `BountyResolvedEvent` and `VictimCrimeMemoryChangedEvent`, restitution inline from the path that
  marks a receipt restored: `GUARD_ASSIST_PATROL` (a bounty resolved
  with the target taken alive), `RESTITUTION_DELIVERY` (a property receipt in the offender's name
  marked restored) and `VICTIM_AMENDS` (an apology accepted by a villager who remembers the crime).
  `civic/ServiceContract` is the durable state machine — `OFFERED`, `ACTIVE`, `COMPLETED`, `FAILED`,
  `CANCELLED` — bound to one case id and carrying no price. `CivicWorkService` never discounts the
  fine up front; completion settles through the ordinary fine path, and a failed or cancelled
  contract leaves the original sentence exactly as it was. A `civic_service` action sits in the self
  menu beside bail and settle.
- **Service restrictions (`townstead.serviceRestrictions`, off by default).**
  `ServiceRestrictionPolicy` is pure and applies four rules in order: an essential service is never
  refused; a villager who personally remembers being harmed may refuse, and that refusal decays with
  the memory rather than being cached; an open case this settlement knows about refuses a
  non-essential service, but only when the subject is wanted, or the service is a luxury and their
  band is Outlaw — and a fence is exempt from the public rule entirely, because its trade is with
  people the law is after; standing alone never refuses anything. `ServiceKind` marks food and shelter essential, and
  surrender, restitution and settling a case are not routed through the decision at all.
- **Economy profiles (`townstead.economyProfiles`, off by default).** `EconomyProfile` is three
  fixed profiles — `FRONTIER`, `TOWN`, `PROSPEROUS` — with every multiplier clamped to `[0.5, 2.0]`,
  applied to `SettlementPolicy` fines, `BountyService` bounties, `FenceOfferBuilder` prices and
  `VillagerPurse` refill. The refill multiplier may only ever select an amount at or below the
  configured one, so no village can mint currency. `EconomyProfileResolver` re-reads the profile
  rather than persisting it, behind the ordinary `townstead.snapshotCacheTicks` cache, and answers
  `TOWN` for every question it cannot answer, which is why the
  switch reports degraded rather than off when the settlement mod is absent.
- **Read-only API for the new layers.** `McaCrimeApi.serviceRefusal` returns a `ServiceRefusalView`
  (refused, service kind, and translation keys for the reason and the route back);
  `McaCrimeApi.civicContracts` returns `CivicContractView`s. There is deliberately no method to
  accept or advance a contract: this is an extension point for a quest mod's presentation, not an
  adapter, because MCA: Quests has no runtime quest-creation API and completion authority stays here.
- **Commands.** `/crime property list|inspect [pos]` at permission 2, `/crime property protect
  <rule> [pos]` and `/crime property release [pos]` at permission 3; `/crime service list
  [offender]` at permission 2, `/crime service offer <offender> <task>` and `/crime service cancel
  <contract>` at permission 3.
- Sixteen new language keys for the civic action, the three tasks, contract progress and the two
  refusal reasons with their repair lines.

### Changed

- **World data is schema 14** (`CrimeDataMigrations.SCHEMA_PROPERTY_LAW`): root `propertyPolicies`,
  `propertyReceipts` and `serviceContracts`. The 13→14 step writes only the version, so a schema-13
  world loads unchanged and stays loadable by a build with every new switch off.
- **`communityService` now requires `activity_coordination` + `read_schedule`** in
  `TownsteadDiagnostics.REQUIREMENTS`, instead of `work_suspension`. `TownsteadBridge.has` never
  grants `work_suspension` — the start-gate is MCA: Crime's own vanilla brain hook and is
  permanently partial — so the old row could only ever report degraded however well the feature was
  working. What a contract actually consumes is an activity claim on an NPC offender and that
  villager's schedule.
- **Two new `/crime validate` rules.** `townstead.autoProtectGeneratedProperty` on while
  `townstead.propertyLaw` is off is reported, because automatic protection only writes policies and
  nothing evaluates one until property law is on. `townstead.communityService` on while
  `jail.enableFines` is off is reported, because civic work is offered only where a fine could have
  been paid.
- `activity/CrimeActivityView.Kind` gains `CIVIC_SERVICE`, so a villager working off a contract
  takes an ordinary routine claim.

### Known limits

- **What transfer detection cannot see.** Throwing an item out of a container slot (`Q`) is a loss
  with no matched gain and is reported as unattributed rather than charged. Any menu outside the
  four whitelisted vanilla storage menus is `UNSUPPORTED` and nothing is attributed. A container
  opened without a right-click MCA: Crime saw is not positioned and so is not watched. An identical
  item leaving the actor in the same tick it arrived nets to zero. Building-scope "no sourcing" is
  enforced by neither the transfer hook nor the sourcing mixin: `PropertyRegistry` answers the
  hot-path question from the container index only, so a container-scope policy at a known position
  is what keeps settlement workers out.
- **Everything new here is off by default**, and two of the switches need capabilities an install
  may not have: `propertyLaw` needs `storage_policy` (now provided by MCA: Crime's own mixin) and
  `read_building`; `autoProtectGeneratedProperty` needs `building_enumeration`. A switch on without
  its capability reports `DEGRADED` rather than working silently.
- **The runtime matrix has not been run for this release.** No dedicated server,
  no client session, no save-quit-reload with property policies and contracts in the world data. The
  production jar was built but has not been launched.
  What has run is the unit suite (1924 tests), `build` including `checkJarContents`, and the
  Townstead probe against both Forge jars with all five mixins.

## [0.7.3] — unreleased

Adopts MCA: Reputation 0.6.0. Nothing here requires it: an older companion, or none at all, behaves
exactly as it did in 0.7.2, because what this mod uses is decided by a capability handshake rather
than by a version number.

### Added

- **Capability negotiation with MCA: Reputation.** `compat/ReputationCapabilitySnapshot` holds what
  the installed companion advertised, queried once per server by `ReputationBridge.negotiate` at
  `ServerStartedEvent` and reported by `/crime debug integrations`. The API-version handshake still
  runs and still refuses a generation this build cannot speak, but it is no longer what decides
  which facilities are used: MCA: Reputation deliberately keeps its API version at 1 while adding
  capabilities, so comparing versions could not tell a 0.3.0 install from a 0.6.0 one. Each of
  keyed delivery, read-only receipt lookup, supersession, profiled delivery and bound resolution is
  used only when its feature string is advertised, and the fallback in every case is the older
  surface working as before.
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
- **Townstead integration, reflection plus four plugin-gated mixins.** `compat/TownsteadBridge` is
  the facade — `State` is `ABSENT`, `OFF`, `DISABLED`, `PARTIAL` or `FULL` — over eighteen
  independently reported `TownsteadCapability` values, twelve of which bind through the reflective
  manifest; every query answers through a `TownsteadQueryResult` that is never a zeroed record and
  never throws. The implementation lives under `compat/townstead`
  (`TownsteadBinding`, `TownsteadHandles`, `ReflectiveTownsteadBridge`), matches Townstead members by
  owner, name, arity and staticness against a manifest, and holds them as erased handles; the only
  reference into it from anywhere else is a dotted `Class.forName`. That is not fastidiousness:
  Townstead is compiled against MCA, so naming one Townstead class would put a *relocated MCA* type
  into this mod's constant pool and re-link it to one MCA package layout. Binding happens once at
  `ServerStartedEvent` in `integration/CrimeIntegrationPump`, beside the MCA: Reputation handshake,
  and is released on server stop.
- **`/crime debug townstead [entity <target>|village]`** reports the bridge state, the detected
  Townstead version, which capabilities bound and how each one is provided — `available (api)`,
  `available (mixin)`, `degraded (mixin applied, hook not yet observed)`, `degraded (start-gate
  only)` or `unavailable` — plus the unresolved manifest members and the mixin layer's applied/fired
  record. `compat/TownsteadMixinStatus` is what makes "applied" and "actually reached" two separate
  facts, so a moved injection point can be told from a world where nothing has happened yet.
- **A `[townstead]` config section**, sixteen keys, documented in [CONFIG.md](CONFIG.md). Everything
  in it is a no-op with Townstead absent. `config/ConfigValidator.validateTownstead` reports a switch
  that is on while the capability behind it is missing as **DEGRADED** — on, but not running — rather
  than letting it look configured while nothing checks anything.
- **A second mixin config, `mcacrime.townstead.mixins.json`.** `required: false`, `defaultRequire 0`,
  the shared refmap, and a `mixin/townstead/TownsteadMixinPlugin` that applies nothing unless
  Townstead is loaded *and* the named target class really resolves. Four mixins: `GuardRestYieldMixin`
  (Townstead's guard-rest ticker yields to an MCA: Crime claim), `ReactionLockGateMixin` (a reaction
  lock is refused over a villager this mod holds), `WorkToolProvenanceMixin` (a display tool is
  recorded as Townstead's, not the villager's) and client `RpgDialogueEntryMixin` (a Crime entry point
  on Townstead's dialogue screen). Both configs are listed in the jar manifest's `MixinConfigs`.
- **`activity/`** — `CrimeActivityRegistry`, `CrimeActivityView`, `OperationPolicy` and
  `CrimeActivityOperation`: one place that says which villagers an enforcement action currently owns
  and what that ownership refuses.
- **`facility/`** — `TownsteadBuildingRef`, `FacilityRole`, `FacilityAssignment`, `CellReservation`,
  `CustodyCarePolicy`, `CareHandoverPolicy` and `CrimeFacilityService`, with `captivity/CustodyCareService`,
  `enforcement/GuardDutyService` and custody-recovery state on `captivity/CustodyRecord`. New commands
  `/crime duty inspect|suggest [village]` and `/crime facility list|validate|assign|remove|recognise`.
  `jail/JailRegistry.automatic` puts an assigned facility at the top of the *automatic* arrest ladder;
  `/crime jail` still means the jail an operator assigned, wherever it is.
- **`civic/VillageSecurityView` and `VillageSecurityService`**, plus `api/model/CrimePublicView` and
  `McaCrimeApi.publicView` / `effectiveStanding` — what one community may know about one person, with
  the same knowledge rule the civic incident filing uses. See [API.md](API.md).
- **Three datapack tables** under `data/mcacrime/townstead/`: `building_roles`,
  `personality_profiles` and `reaction_bindings`, loaded by `compat/TownsteadBuildingRoles`,
  `TownsteadPersonalityProfiles` and `TownsteadReactionBindings`. Each publishes whole or not at all.
  See [DATAPACK.md](DATAPACK.md).
- **A `townsteadProbeTest` Gradle task** (`-PtownsteadLegacyJar`, `-PtownsteadModernJar`) that
  resolves the binding manifest and the mixin targets against a real Townstead jar in its own JVM,
  paired with the MCA build that jar was compiled against. `checkJarContents` now also fails if any
  `com/aetherianartificer/townstead/` class is ever shaded into the jar.

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
- **Mixins are no longer "vanilla only".** They were, and the required config still is; the rule that
  replaces it is narrower where it matters. Townstead targets live in a second, optional,
  plugin-gated config, and a mixin in it may name Townstead **only** as a dotted `targets=` string and
  may never name a Townstead or an MCA type. `NoTownsteadStaticLinkTest`, `NoMcaStaticLinkTest`,
  `MixinConfigTest` and `TownsteadMixinTargetTest` enforce that, and `OptionalClassloadTest` adds
  `compat/townstead/` to the adapters nothing outside them may name — with `mixin/townstead/` the one
  permitted namer, because those classes are not loaded on an install without Townstead either.
- **Work suspension generalised.** `job/ThiefWorkRegistry` used to wrap the `Activity.WORK` behaviours
  of employed Thieves only. It now also wraps the brain of any villager this mod holds an activity
  claim on, so an arrest can stand a villager down from work MCA: Crime never installed — vanilla's,
  MCA's or a settlement companion's. The cheap first question is still two `isEmpty()` calls.
- **Guard recruitment consults the settlement.** `enforcement/GuardPopulationService` now produces a
  `RecruitmentReport` — roster, available, protected workers, unreadable roles, and whether the pass
  was halted — shared with `/crime debug guards` so the number an operator reads is the number the
  pass acted on. `compat/TownsteadRolePolicy` supplies the stance, and an *unreadable* role halts
  recruitment in that village rather than being read as "not a worker": a village briefly short of
  guards recovers, a village whose baker was drafted does not. With Townstead absent, recruitment
  behaves exactly as it did.
- **World data schema 13** (`state/world/CrimeDataMigrations`): root `facilities` and
  `cellReservations`, and the custody `recovery*` keys. The 12→13 step deliberately writes nothing but
  the version — both new collections read their absence as empty, and seeding facilities from the
  existing jail roster would invent buildings and capacities nobody ever validated.
- **Network protocol 14.** Three packets: `RestraintRigSyncS2CPacket`, `RequestVillageSecurityC2SPacket`
  and `VillageSecurityS2CPacket`. `integration/IntegrationTargets.TOWNSTEAD_REACTION` is a delivery
  target of its own, routed separately in the pump — a reaction that does not play is dropped, not
  retried like a civic write — and `DeliveryPolicy.appliesLocalVillagePenalty` now decides the local
  penalty per target.
- **Restrained-player rendering** picks its attachment points from a rig read: `client/ClientRestraintRig`
  and `client/render/RestraintWristLayer` fall back to the humanoid assumption on every uncertainty —
  no Townstead, an unbound stage read, an unknown villager.

### Fixed

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
- **A settlement's display tool dropped as a second real one.** `loot/VillagerDeathLoot` decided
  whether an equipped stack was extra loot by reference identity against the villager's inventory —
  the only rule that works for MCA, which equips inventory items by reference. Townstead adds a third
  case that rule cannot see: a copy it puts in the hand for the look of a work shift, owned by nobody.
  That is how one hoe became two. `compat/TownsteadEquipmentProvenance` now classifies the stack, and
  the old reference test survives as its `UNKNOWN` answer. Nothing anywhere compares by equality: two
  identical hoes on one villager are still two drops.
- **A criminal-job revert could overwrite somebody else's profession.** Restoring a villager's
  previous profession no longer reverts when the current profession is not the one MCA: Crime last
  wrote. `job/ProfessionPresentationRevision` makes that decision, and a player, a datapack or a
  settlement companion that assigned a profession afterwards keeps it; the remembered value is dropped
  with the claim rather than being left to stomp the new owner on the next revert.

### Known limits

- **Work suspension is start-gating only.** A work behaviour can be refused before it starts and
  stopped through its own stop path; a Townstead producer task that has already committed staged
  inputs is allowed to finish, because reconciling another mod's half-done recipe from the outside is
  not something this mod can do correctly. `/crime debug townstead` reports the capability as
  `degraded (start-gate only)` for exactly this reason.
- **The restraint rig fallback only sees a stage-level rig override.** A rig difference expressed
  anywhere other than the life stage record is not visible to it, and rendering falls back to the
  humanoid assumption.
- **The runtime matrix has not been run for this work.** Production jar, dedicated server, client,
  multiplayer, save-and-reload, and Townstead removal from an existing save are all unexercised. What
  has run is the unit suite, `build`, and the Townstead probe tests against Townstead 0.7.7 jars built
  from source.

## [0.7.2] — unreleased

### Added

- **Epic Fight compatibility.** `compat/EpicFightCompat` detects Epic Fight, "EpicFight-MCA
  Patch" (`efmca`) and "MCA Skin x Epic Fight Compatibility" (`mcaefcompat`) by mod id only, with
  none of the three named as a type anywhere in the mod. The client-only
  `client/EpicFightInteractShim` forwards an armed or restraint interaction that Epic Fight's
  battle mode had already cancelled at the use key, so MCA: Crime's menu still opens under the
  server's existing weapon-trigger rules (now synced via two new `WeaponPolicySnapshot` fields,
  `triggerEnabled` and `triggerRequireSneak`) without ever sending a duplicate packet. A startup
  log warning and `/crime debug compat` report the one thing this mod cannot work around: with
  `efmca` installed, its unconditional mixin on `VillagerEntityMCA.hurt` blocks all player damage
  to MCA villagers regardless of its own `friendlyFire` setting, so assault, killing, self-defence
  and kill bounties against villagers do not fire while it is present.
- **Configurable currency.** `integrations.currencyId` now resolves through a real registry
  (`economy/Currencies`) instead of always meaning emeralds. The new built-in `mcacrime:item`
  provider (`economy/ItemCurrency`) charges in any single registered item named by the new
  `integrations.currencyItem`, sharing its inventory-walk logic with the emerald default via
  `economy/ItemStackCurrencySupport` and `economy/ItemCurrencyMath`. A queued payout records the
  item it was earned in as a bound provider id (`mcacrime:item/<namespace>/<path>`), so a config
  change afterwards cannot redirect a payment already owed. `Currency.creditBounded` is a new
  default method that credits without ever dropping a payout on the ground and reports the
  undelivered remainder; `BountyService` now routes every payout through it. Economy mods can add
  their own id with the new `McaCrimeApi.registerCurrency`. An unregistered `currencyId`, or an
  unresolvable `currencyItem`, falls back to emeralds with one warning rather than taking fines,
  bail, ransom and theft offline; `/crime validate` now reports an unregistered `currencyId` too
  (`config/ConfigValidator#currencyIdRegistryCheck`).
- **Numismatic Overhaul purse.** When Numismatic Overhaul (Reforged Again) is installed,
  `compat/NumismaticBridge` and `compat/numismatic/NumismaticCurrency` register
  `mcacrime:numismatic`, a currency backed by that mod's purse capability rather than an inventory
  item; amounts are in bronze (100 bronze = 1 silver, 10000 = 1 gold). It has no item form, so it
  cannot be dropped, traded by a fence, or produced on the ground.
- Chat messages that used to say "emeralds" unconditionally — the guard challenge fine line, the
  case dossier, and the `/crime payfine` and mugging text — now read from whatever currency is
  active, formatted server-side so a client (which is forbidden to read the common config) never
  has to guess or hard-code a name.
- **Shared NPC-mugger eligibility.** `job/NpcMuggerEligibility` provides a unified role evaluator
  for both `ASSIGNMENT` and `EXECUTION` contexts, preventing drift across the six places that check
  mugger eligibility. Responder identity is evaluated first before temporary states, and failure to
  read MCA's classification fails closed with `CLASSIFICATION_UNAVAILABLE`.
- **Exclusive native Thief profession.** `mcacrime:thief` is now a real vanilla-registered villager
  profession (`job/CriminalProfessions#THIEF_ID`) with a Mask Station worksite, rather than a
  cosmetic overlay on an existing profession. `job/OccupationTransaction` manages transactional
  profession acquisition and rollback (releasing claimed POI tickets, clearing/restoring merchant
  offers, trading XP, clothing, and occupational memories). `job/WorldCriminalJobService#requestThiefOccupation`
  coordinates settlement sweeps and station recruitment, requiring an unemployed adult for settlement
  routes. `job/ThiefWorksiteService` discovers and claims Mask Station POIs within 48 blocks with a
  400-tick reservation timeout. `job/ThiefOccupationLifecycle` runs a 20-tick reconciliation managing
  lifecycle states (`PENDING`, `ACTIVE_BOUND_NOVICE`, `ACTIVE_BOUND_ESTABLISHED`, `ESTABLISHED_UNBOUND`,
  `SUSPENDED`, `RETIRED`), with 1200 loaded ticks of novice grace and establishment earned after 24000 ticks.
- **Mask Station worksite and crafting.** `block/MaskStationBlock` (`mcacrime:mask_station`) is a
  horizontal-directional, piston-immovable crafting block and village point of interest
  (`job/CrimePoiTypes#MASK_STATION_KEY`). Right-clicking opens `menu/MaskStationMenu`, providing a private
  session with per-viewer input slots (material, binding, optional dye) without requiring a block entity.
  `recipe/MaskMakingRecipe` implements the custom `mcacrime:mask_making` recipe type with exact ingredient
  counts and optional dye spending, producing exactly one mask. `menu/MaskStationResultSlot` acts as a
  single-debit preview slot supporting left-click, right-click, and shift-click batching (capped at 64
  crafts), while explicitly denying hotbar number swaps, off-hand swaps, drops, creative clones,
  double-click collections, and click-drag distributions (`menu/MaskStationRoute`). `network/SelectMaskRecipeC2SPacket`
  synchronizes recipe selection using bounded IDs and generation counters under the menu request budget.
- **16-style mask catalogue.** `item/MaskVariant` introduces sixteen distinct mask styles distributed
  evenly across four material families (`item/MaskFamily`), each with zero armor, non-enchantable status,
  and uniform wear within the family: Cloth (48 wear: `bandana`, `highwaymans_domino`, `wrapped_scarf`,
  `half_veil`), Leather (192 wear: `leather_mask`, `raven_mask`, `jackal_mask`, `stitched_mask`), Clay
  (64 wear: `hockey_mask`, `clay_mask`, `comedy_mask`, `tragedy_mask`), and Metal (256 wear:
  `iron_skull_mask`, `brigand_visor`, `owl_mask`, `blank_iron_mask`). All masks support 24-bit RGB dye
  tinting (`mask/MaskCustomization#tint`) via vanilla `DyeableLeatherItem`, defaulting to untinted white.
- **Mask restyling.** The Mask Station restyle operation (`mask/MaskCustomization#restyleResult`)
  transfers durability, custom names, lore, enchantments, and unknown NBT tags between styles within the
  same material family, while strictly rejecting cross-family conversions (`WRONG_FAMILY`), unregistered
  styles (`NOT_A_REGISTERED_STYLE`), identical style conversions without color change (`NO_CHANGE`), and
  unsupported capability or entity tags (`UNSUPPORTED_DATA`).
- **Non-sneak empty-hand apology.** Right-clicking a wronged villager while not sneaking, with an empty
  main hand and no weapon in the off hand, performs an apology directly (`memory/ApologyInteractionPolicy`,
  `memory/MemoryInteractionHandler`). The behavior is configurable via `memory.emptyHandApologyMode`
  (`CONTEXTUAL_DIRECT` by default, or `MENU_ONLY` to restrict apologies to the Crime menu).
- **Sand Bottle.** `item/SandBottleItem` (`mcacrime:sand_bottle`) throws a `entity/SandBottleProjectile`
  with an 80-tick player item cooldown. On impact, `effect/SandExposurePolicy` blinds targets with
  `effect/SandBlindedEffect` for up to 80 ticks on direct hit and up to 40 ticks splash within a 2.0-block
  radius (linear falloff to 10 ticks minimum, capped at 64 affected victims from 256 candidates). Blinded
  entities lose line of sight beyond close contact (1.5 blocks), halting pursuit repaths, mugging target
  acquisition, and witness identification without clearing memories or legal status. `effect/SandRecovery`
  enforces a 60-tick post-blindness recovery window on targets against all throwers, preventing stun locks.
  A new `[sandBottle]` common config block tunes all exposure parameters, and `client.sandParticles`
  (`NORMAL` / `REDUCED` / `OFF`) controls client dust rendering.

### Changed

- **Protocol version 12 → 13.** `network/CrimeNetwork` bumps the network protocol version from 12 to 13
  to support `SelectMaskRecipeC2SPacket` and `MaskSelectionS2CPacket`. Mismatched clients are rejected at
  handshake.
- **World save schema 11 → 12.** `state/world/CriminalVillagerRecord` and `state/world/CrimeDataMigrations`
  advance to schema 12 (`SCHEMA_OCCUPATION`). Records gain `status`, `source`, `establishedAt`,
  `lastVisitAt`, `employedTicks`, `worksite` (`WorksiteRef`), `reservation`, `reservationAt`, `unboundSince`,
  `previousProfessionKind` (`HistoricalProfessionKind`), and an `extra` compound tag for forward compatibility
  with future fields. Migration is lazy and idempotent; pre-existing thieves become `ESTABLISHED_UNBOUND`
  with source `MIGRATION` or `WILD`.
- **Vanilla integration mixins.** Five targeted mixins in `src/main/resources/mcacrime.mixins.json` support
  the exclusive Thief profession: `MaskStationAcquisitionMixin` excludes Mask Stations from vanilla job
  sites for non-thieves; `MerchantOffersAccessor` reads and sets raw villager offers without triggering trade
  generation; `NativeJobAssignmentMixin` intercepts vanilla job assignment for Mask Stations; `ThiefBrainMixin`
  wraps Activity.WORK behaviors so thief workstation tasks yield to sleep, custody, panic, and enforcement;
  and `ThiefPoiValidationMixin` defers POI validation when chunks are uninspectable. `SandSensingMixin`
  injects at HEAD of `Sensing.hasLineOfSight` to enforce mob blindness.
- **Villager XP floor on occupation commit.** `compat/OccupationCompat#applyXpFloor` sets villager trading
  XP to at least 1 (`Math.max(1, xp)`) when a Thief commits (`job/OccupationTransaction`), preventing vanilla
  `ResetProfession` and MCA's `LoseUnimportantJobTask` from stripping the job and excluding committed
  Thieves from MCA's automatic guard recruitment.
- **Removal of sneak-to-open-menu interaction.** `memory/MemoryInteractionHandler` no longer opens the
  Crime menu on sneak + empty-hand right-click, preventing conflicts with mods that bind villager pickup to
  sneaking. The Crime menu remains accessible via keybind (`key.mcacrime.crime_menu`), the MCA screen Crime
  button, and armed interaction.
- **Guard promotion role exclusion.** `enforcement/GuardPopulationService` now checks
  `job/NpcMuggerEligibility#guardPromotionBlocked`, preventing adult villagers with active criminal records
  from being promoted to guards.

### Deprecated

- **`criminalJobs.presentThiefAsMcaProfession` is deprecated and inert.** In 0.7.2, Thief is exclusively a
  real native villager profession (`mcacrime:thief`) with its own Mask Station worksite. The configuration
  key remains in the file and is parsed without error, but setting it to `false` logs a single startup
  warning via `job/WorldCriminalJobService#warnAboutDeprecatedThiefPresentation`. A hidden Thief overlay on
  an ordinary profession is no longer a supported state. The companion `presentFenceAsMcaProfession` key
  remains active and unchanged.

### Fixed

- **Stale criminal records on guards and mugger aborts.** When an active mugger becomes or is discovered
  to be a law responder, `mug/npc/NpcMuggingService` and `ai/thief/ThiefBehaviorService` abort the session
  immediately with `mug/npc/NpcMugAbortReason#ACTOR_BECAME_RESPONDER`, and
  `job/NpcMuggerEligibility#contradictory` flags contradictory records for reconciliation.
- **Non-damaging disruption for Sand Bottles.** Sand Bottles blind targets without dealing damage or
  invoking fake damage events, avoiding false assault charges and preventing duplicate reputation
  penalties while reusing `DamageIncidentService` harm cooldowns and replay guards.

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

### Added

- **Family loyalty.** A relative in `relationship.familyLoyalty.familyLoyaltyScope` who witnesses a
  crime may decline to report it: a deterministic, RNG-free score against relationship hearts,
  family tier and personality, with six hard exclusions (victim, the victim's own relative, a
  responder, a minor, a tier out of scope, or Heat above `loyaltyMaxCrimeHeat`). A crime seen only
  by loyal family is un-witnessed for Heat, community standing and family heart loss; the relative's
  memory of it is still recorded, withheld rather than reported. On by default.
- **Family accomplices.** Three new actions in the Crime menu's `Conspire` group — Ask for a
  Lookout, Ask for a Distraction, Ask for Escape Help — let a player recruit an eligible relative to
  shrink the witness radius, hold civilians' attention elsewhere, or shake off pursuing guards for a
  window. An accomplice can be exposed by a witness who is neither loyal nor in on it, or when the
  principal is arrested while the agreement is active, and is then individually wanted, arrestable
  through the existing NPC custody path, and jailed for `accompliceJailTicks`. On by default.
- **Family bail.** With `enableFamilyBail`, a relative may buy a lawfully held accomplice out of
  the rest of their sentence for a price based on time remaining and prior arrests of that same
  relative. On by default.
- **Mugging protection.** A per-player protection window, shared across every thief rather than
  scoped to one, plus a longer repeat cooldown on the same thief/player pair, a daily mugging cap,
  a live thief-per-jurisdiction cap, and an `enableNpcMugging` master switch.
- **Configurable contraband.** A new `[contraband]` block lets an operator list illegal items and
  tags; guards discover them through patrol searches or arrest searches, gated on suspicion, line
  of sight and a chance roll. Off with an empty list by default.
- **Configurable spoken-line format.** `dialogueMessageFormat`, `dialogueNameColor` and
  `dialogueNameBold` under `[dialogue]` let an operator control how a villager's name and line are
  laid out in chat. Defaults now match MCA Conversations' chat mode (`<Name> line`, bold gold name)
  instead of the previous plain `Name: line`.

### Changed

- Three defaults retuned to reduce excessive mugging: `criminalJobs.villageThiefChance` `0.025` →
  `0.01`; `criminalJobs.assignmentScanIntervalTicks` `1200` → `2400`;
  `criminalJobs.thief.mugCooldownTicks` `12000` → `24000`.
- World save schema `10` → `11` (additive; existing saves load unchanged, no new field is
  required to be present).
- Network protocol `11` → `12` (a new packet quotes family bail; a mismatched client is refused).
- NPC crime scope now includes player-recruited family accomplices — villagers still never decide
  to commit a crime on their own; `enableNpcCrime` remains a declared, unwired seam.

### Fixed

- Repeated NPC mugging of the same player by several different thieves in quick succession. Every
  existing cooldown was scoped to the thief, not the victim, so N thieves could each rob the same
  player back to back while individually staying inside their own limit. See
  [the verification doc](docs/FAMILY_CONTRABAND_MUGGING_VERIFICATION.md) for the diagnosis and the
  regression coverage.

## [0.6.4] — unreleased

### Fixed

- Keep guard confrontation background drawing separate from widget rendering, the countdown and
  the menu-displayed acknowledgment. Retain Forge 1.20.1's explicit background call and add the
  same rendering regression checks as the NeoForge port, where reentering `Screen.render()` from
  `renderBackground()` caused a stack overflow reported when surrendering.
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

- **Payment and settlement no longer race.** `EconomicTransactionService` records the debit before
  the credit is attempted; if the debit fails, the receipt is marked REJECTED and nothing is persisted.
  If the credit fails after the debit succeeds, the receipt is marked NEEDS_RECONCILIATION for operator
  reconciliation rather than being retried.
- **Bounty claims no longer reopen on revision.** `BountyService.pay` pays only the unpaid delta 
  on warrant revision, and legacy claims count as fully paid. `BountyClaimLedger.expire` keeps 
  claims whose warrant still exists.
- **Fence pricing arithmetic is saturating.** Price calculations use `SafeMath` for fine, fence 
  and bounty multiplications, clamping to long min/max rather than wrapping.
- **Better error recovery for malformed world data.** Records that fail to deserialize are 
  quarantined rather than crashing the load. The quarantine list survives saves so an operator 
  can inspect and manually fix or discard them.

### Notes

- **B09 and B22 are partially addressed.** B09 (bounty revision) has a no-repay delta guard on warrant
  revisions (`BountyService.pay`, `BountyClaimLedger.alreadyPaid`) so re-opened warrants pay only the
  difference, but no reward-lot redesign. B22 (cell removal reliability) journals unresolved block
  positions for retry (`HoldingCellService`, `pendingCellRestorations`), but has no policy for cells
  altered by pistons or explosions.
- **B18 is deferred.** NPC-on-NPC crime fabrication is out of scope for this maintenance release 
  and will be addressed after the settlement and custody guarantees ship.
- **19 new test classes** under `src/test/java/dev/otectus/mcacrime` exercise schema migration, 
  payment settlement, bounty revision handling, capture outcomes, fence pricing, world data 
  robustness, transaction receipts, and safe-destination validation.

## [0.5.0] — 2026-09-02

Armed interactions. The crime menu now opens the way the fiction already implied it should: by
drawing a weapon on somebody.

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
  `bounty` sections added with every tuning value (see CONFIG.md for the full key list). All
  numeric keys are validated by `/crime validate`.
- **New entity tags.** `mcacrime:always_resists_weapon_threats`, `mcacrime:never_resists_weapon_threats`,
  and `mcacrime:armed_villager_roles` allow datapacks to customize threat compliance per entity type.
- **New item tags.** `mcacrime:illicit_goods`, `mcacrime:fence_sells`, `mcacrime:fence_buys`,
  `mcacrime:fence_blacklist`, and `mcacrime:thief_theft_immune` allow datapacks to drive fence
  inventory and theft exceptions without editing config.
- **`debugLogging` now active.** The config key now actually gates debug output instead of being
  read but unused.
- **Network protocol 6 → 7.** Restraint sync packets changed shape to include visual type; two new
  S2C packets added for weapon policy and criminal job sync.
- **World data schema 6 → 7.** New collections for criminal villagers, stolen goods, bounty claims,
  bounty contracts, warrants, and fence restock timers. A maintenance sweep every 6000 ticks expires
  stale records and old bounty claims; optional MCA: Quests integration declares bounty contracts.

### Changed

- **Mixin retargeted.** The single mixin moved from `PlayerModel.setupAnim` TAIL to
  `LivingEntityRenderer.render`, still exactly one `@Inject`, now applying to both player and
  villager restraint poses.
- **MCA interaction Crime button positioning.** Anchor moved from top-right to bottom, automatically
  placed above the hotbar and any other HUD elements. Configurable via `client.crimeButtonAnchor`.
- **Crime menu weapon gate.** Moved from opening-the-menu stage (now just disabled+tooltip) to the
  `Mug` action itself and to permission checks on coercive handlers, so unarmed players see why they
  cannot act rather than seeing a hidden menu.
- **Weapon policy client-server consistency.** Client weapon rule snapshot is now shipped on login
  and reload so the Crime button never lies about what the server will permit.

### Fixed

- **Late-joining clients see restrained pose.** Restrained villagers now sync their pose state to
  clients who join after they were already restrained, not just to those watching from the moment
  of restraint.
- **Restraint compliance gate.** Weapons drawn during a coercive mug are now checked every tick; the
  action cancels if a weapon is removed mid-mug.
- **HUD outcome line displays formatted values instead of placeholders.** The action progress bar's
  outcome text used to show a literal `%s` instead of the mug success amount or other formatted
  values — a pre-existing defect from when the action HUD was introduced. `ActionProgressS2CPacket`
  now carries `outcomeText` as a pre-formatted `Component` computed from `ActionResult.message()`,
  so the client renders the complete outcome without re-translating the key.

### Notes

- **Restraint artwork credit.** The three new restraint item textures were created by
  TheWiggleDuck.
- **Seven new event types** under `api/event`: `BountyResolvedEvent`, `CrimeAttemptEvent`,
  `CrimeIntentEvent`, `CriminalJobChangedEvent`, `FenceTradeEvent`, `NpcCrimeCommittedEvent`, and
  `WarrantChangedEvent` carry bounty resolution, warrant changes, and fence trades for companion mods.
- **Two new abstraction interfaces** — `EconomicCrimeActor` and `InventoryCrimeActor` — make NPC
  crime pluggable without widening `CrimeActor` itself.
- **Locks Reforged optional support.** When installed and enabled, fences offer locks and picks
  with tiered pricing; the integration degrades to the tag-driven default when absent.
- **MCA: Quests optional support.** When installed and enabled, bounties are published as guard-given
  quests with completion tracked through the Crime signal system; the integration degrades to direct
  bounties when absent.
- **Dedicated server tested.** Builds verified against MCA Reborn 7.6.20, 7.7.0-beta.2, and
  7.7.1-alpha.2 with zero client class leakage to the server.

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
  hotbar and the health and armor rows automatically, and `BOTTOM_CENTER` clear of this mod's own
  channel bar, so picking a bottom corner no longer hides a box behind vanilla's HUD.
- **`hudOffsetX` / `hudOffsetY` now measure inward from the anchored edge** at every anchor rather
  than always counting down and right from the origin, so one pair of values means the same visual
  gap in any corner. A saved offset on a top-left anchor behaves exactly as before.

### Notes

- **Gifting caveat.** While the trigger is on, right-click gifting a weapon to an MCA villager is
  pre-empted by the crime menu. Blacklist that item under `[weapons]`, or turn the trigger off.
- **Config sync caveat.** The weapon lists are COMMON config, which Forge does not sync. A client
  whose lists differ from the server's will mispredict the swing — the server's answer still decides
  what happens.
- **The new HUD corner reaches new installs only.** Forge writes a default only for a key that is
  missing, so an existing `mcacrime-client.toml` keeps whatever `hudAnchor` it already holds. To move
  an existing setup, pick Bottom Left in the mod's settings screen or delete the `hudAnchor` line.

## [0.4.0] — unreleased

The village answers back. This release closes the spec's Phase 1 gate, gives the mod a real
presentation layer, and fixes the case-lifecycle hole that made serving a jail sentence meaningless.

### Added

- **Every declared action now enters through the action engine.** `escape`, `surrender`,
  `settle_case`, and `pay_ransom` were declared ids with no registered handler; those four flows
  mutated state directly from the command layer, skipping the target locks, request nonces, replay
  cache, and cooldowns every UI action is bound by. All nine ids now route through one contract, and
  `ActionRoutingTest` fails the build if a tenth is ever declared without one.
- **A `CrimeActor` abstraction.** Handlers no longer name `ServerPlayer` in their signatures, which
  is the precondition spec §10.5 sets before NPC-on-NPC crime can exist without a parallel simulator
  that bypasses the finite purses and witness systems.
- **Action presentation metadata** — category, legality marker, duration, requirement markers,
  hostility, and a description key per action, carried on the menu packet so the screen can group and
  explain rows instead of listing them.
- **A redesigned action screen.** Rows are grouped into Coerce / Restrain / Resolve / Other, carry a
  legality colour and a duration chip, scroll when they overflow, and explain on hover what an action
  does, what it needs, and — when it is unavailable — the server's reason.
- **An action HUD.** Active and queued actions show an aligned title, icon, and progress bar with
  their countdown or wait state; completion and interruption flash the final status before fading.
- **Fine settlement from the Crime menu.** A player with open cases can pay their fines voluntarily
  through the Crime menu's Resolve tab when out of combat, without waiting for a guard confrontation.
- **Jail sentence tracking and release.** Sentences decrement on the server, persist across restarts,
  and automatically release the player when completed. Escaping pauses the timer and adds a warrant.

### Changed

- **Protocol version 5 → 6.** Action presentation metadata and HUD state packets added to the S2C
  stream; `ActionExecutionPacket` carries client validation tokens.
- **World data schema 5 → 6.** Active jail sentences and escaped warrants persisted in `mcacrime_data`.

### Fixed

- Jail sentences no longer reset to full duration when the server restarts or a player relogs.
- Voluntary fine payment now clears the corresponding cases from the offender's record immediately,
  preventing guards from attempting to collect already-settled fines.

## [0.3.0] — unreleased

The law arrives. Guards challenge suspects, assess fines, make arrests, and escort prisoners to
holding cells.

### Added

- **Guard confrontations.** When a player's Heat exceeds the confrontation threshold, nearby guards
  approach and initiate a dialogue challenge, offering settlement by fine or demanding surrender.
- **Holding cells and intake.** Generated holding cells outside village boundaries serve as short-term
  detention sites; guards escort surrendered or captured suspects to the intake marker.
- **Fines and bail.** Configurable fine amounts per crime category; guards accept payment directly
  or transfer the suspect to custody if the fine cannot be paid.
- **`/crime` command overhaul.** Command tree rewritten with subcommands for inspection, tuning,
  and administrative overrides. Tab completion covers players, categories, and config paths.

### Changed

- Heat accumulation formulas tuned for graduated guard escalation.
- Witness reporting delay jitter added so guards do not converge simultaneously on a crime scene.

### Fixed

- Guards no longer attempt to arrest players across dimension boundaries.
- Escort pathfinding recovers gracefully when the prisoner is temporarily obstructed.

## [0.2.0] — unreleased

Crime, detection, and consequence foundations.

### Added

- Core crime detection engine tracking theft, assault, murder, and trespassing.
- Heat and Karma systems measuring short-term law attention and long-term standing.
- Witness and reporting pipeline: villagers observe crimes in line of sight and report to guards.
- Basic crime menu opened by drawing a weapon on an MCA villager.

### Changed

- Replaced placeholder event listeners with Forge event bus subscribers.

### Fixed

- Corrected raycasting edge case where transparent blocks occluded line-of-sight checks.

## [0.1.0] — unreleased

Initial prototype.

### Added

- Mod skeleton, registration pipeline, and base configuration.
- Placeholder interaction hooks for MCA Reborn entities.

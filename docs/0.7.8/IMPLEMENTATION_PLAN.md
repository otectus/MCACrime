# MCA: Crime 0.7.8 implementation plan — MCA Capitals integration and Blueprint prison recognition

Planned 2026-10-09 against the `main` working tree at `2fff3f3` plus the uncommitted 0.7.7 changes
(`mod_version=0.7.7`, world schema 16, network protocol `"19"`). Implemented on 2026-10-10 with the
defaults D1 to D7 below; section 9 records where the implementation departs from this plan, and
[VALIDATION.md](VALIDATION.md) what was verified.
Every claim below was read from source or disassembled bytecode; the few assumptions and proposed
defaults are labelled **ASSUMPTION** and **PROPOSED DEFAULT**.

---

## 1. Baseline

### 1.1 Versions and trees

| | `main` (Forge) | `neoforge/1.21.1` (`../1.21.1 Ports/MCACrime_1.21.1`) |
|---|---|---|
| Minecraft / loader | 1.20.1, Forge 47.4.10, Java 17 | 1.21.1, NeoForge 21.1.248, Java 21 |
| MCA Reborn range / dev build / probe fleet | `[7.6,8)` / 7.7.1-beta.2 / six 1.20.1 builds | `[7.7,8)` / 7.7.36-beta.3 / 7.7.0, 7.7.13, 7.7.22, 7.7.33, 7.7.36-beta.3 |
| World schema / protocol | 16 / `"19"` | 15 / `"17"` |
| Capitals build to target | **1.3.7 Forge** (`mcacapitals-1.3.7.jar`, `Implementation-Timestamp 2026-09-06`); requires MCA `[7.7.1-alpha.2+1.20.1,7.7.1)` and Architectury `[9.2.14,)` | **1.3.5 NeoForge** (`mcacapitals-1.3.5.jar`); requires MCA `[7.7.35-beta.3,7.8.0)` |

The public Capitals repository (`MajesttyX/mca_capitals_addon`, branch `master`, 34 commits, no tags)
still identifies itself as 1.1.0; the local shallow clone at `MCAConversations/build/capitals-source`
is that 1.1.0 and contains none of the justice, authority or warrant classes. As MCA: Conversations'
`docs/MCA_CAPITALS_SUPPORT.md` already records, **the released jars are the evidence**; everything
below about Capitals was read with `javap` from the 1.3.7 jar at
`MCAQuests/build/rt/forge-capitals/mods/mcacapitals-1.3.7.jar` and cross-checked against 1.3.6 and
the NeoForge 1.3.5 jar (same class set for every member this plan binds).

### 1.2 Crime systems this work touches (verified)

- **Jurisdiction.** `api/model/CrimeCommunityKey` (dimension + MCA village id) is the jurisdiction;
  `enforcement/Jurisdictions.label` names it. A guard enforces for its home village
  (`memory/ReportService.jurisdictionOf:359` → `detect/CrimeCommunityResolver.resolve:56`, home village or
  nearest village within `communitySearchRadius`). `api/JurisdictionPolicyApi` /
  `api/jurisdiction/JurisdictionPolicies` admit **one** provider per server (`attach` uses `putIfAbsent`
  and throws on a second); MCA: Kingdoms registers a reflective proxy there
  (`MCAKingdoms/.../compat/crime/JurisdictionPolicyBridge`). The snapshot is consumed at
  `justice/JusticeService.forGuard:44` and `api/McaCrimeApi.mayEnforce:168`.
- **Wanted and warrants.** Wanted is global: `engine/CrimeState.isWanted:52` =
  `crime/CrimeMath.isWanted(heat, wantedHeatThreshold)`. `ledger/Warrant` is one record per offender
  opened by `ledger/WarrantService.onWantedChanged` and is the bounty identity
  (`bounty/BountyService`, `bounty/BountyContractBoard.onWarrantChanged`). Nothing is per village.
- **Legal force.** `enforcement/LegalTarget` + `enforcement/OutlawResolver.evaluate` produce one
  `enforcement/OutlawStatus` (`lawfulCombatTarget`, `lethalForceLawful`, `bountyEligible`,
  `attackLawful`, `killLawful`) from the terms in `enforcement/LegalBasis`.
- **Guard pipeline.** `enforcement/GuardEnforcement.tick:120-135` decides pursue/stand-down from
  `OutlawResolver` and `hasKnownCases:244` (which evaluates with `jurisdiction = null`, public scope).
  `enforcement/GuardChallengeService.challenge:124` builds the challenge from
  `JusticeService.forGuard` → `justice/LegalDecision(cases, basis)`; `mayChallenge()` is "basis not
  empty". `enforcement/ArrestService.arrest:141-190` assesses `forGuard(...).cases()`, refuses with
  `NO_SENTENCE` when `charges <= 0 && heat <= 0` (`:186`), and prices the term with
  `economy/SentenceCalculator.sentenceFor:40`. `jail/JailService.jail:95` resolves the anchor
  (explicit → `JailRegistry.nearestTo` → `configFallbackAnchor`); the automatic arrest ladder is
  `JailRegistry.automatic` (Townstead facility `JAIL_CELL` → manual anchor) → holding cell
  (`jail/CellBuilder`) → config fallback (`ArrestService:335-341`).
- **Persistence.** `state/world/CrimeWorldData` (bounded maps, `MAX_WARRANTS = 4096`),
  `state/world/CrimeDataMigrations.CURRENT_SCHEMA = SCHEMA_VILLAGE_JUSTICE = 16` with stamp-only steps
  since 13. Player state is the `state/PlayerCrimeData` capability.
- **Network.** `network/CrimeNetwork.PROTOCOL_VERSION = "19"`; every C2S packet passes
  `network/ServerPacketGuard` (side, sender, `RequestBudget`); menu actions are replay-guarded by
  `action/ActionMenuSession` (menu id, revision, expiry, offered set) and a nonce
  (`network/StartActionC2SPacket`).
- **Action system.** Handlers implement `action/CrimeActionHandler` and register in
  `action/ActionHandlerRegistry`; menus are `ActionMenuKind.VILLAGER | CAPTIVE | SELF`
  (`action/CrimeActionService.openMenu:255`, `openSelfMenu:275`). The entity menu opens only on an MCA
  villager with a drawn weapon (`action/CrimeActionInteractHandler.onInteract:48`) or from MCA's
  interaction screen (`compat/mca/client/McaInteractionScreenBridge`). **Another player is never a
  menu target today.** `client/screen/CrimeConfirmationScreen` exists for two-step confirmations.
- **Optional-mod conventions.** One isolated adapter package per companion, reached by a dotted string
  from an always-loadable bridge (`OptionalClassloadTest.ADAPTERS`, `FORBIDDEN_PACKAGES`), bound at
  `ServerStartedEvent` (`compat/TownsteadBridge.bind`), reflective member manifest matched by name and
  arity with erased `Object` handles (`compat/townstead/TownsteadBinding`), a `*Diagnostics` class
  behind `/crime validate` and `/crime debug <mod>`, optional `mods.toml` entry `[0,)` with ordering
  pinned by `ModsTomlOrderingTest`, and a supplied-jar probe task (`build.gradle:248 townsteadProbeTest`,
  `compat/TownsteadBindingProbeTest`) paired with the MCA build the companion was compiled against.
- **Sibling Capitals integrations to reuse.** MCA: Quests `compat/capitals/` (`CapitalsBinding`
  manifest keyed by capability, `CapitalsBridge` interface with only `java.*`/`net.minecraft.*` in its
  signatures, opaque `CapitalRef(Object record, UUID capitalId, int villageId, String dimensionId)`,
  `NoopCapitalsBridge`, `ReflectiveCapitalsBridge`, `capitalsProbeTest -PcapitalsJar`,
  `CAPITALS.md`) and MCA: Conversations `compat/capitals/` (`CapitalsBinding`, `CapitalsHandles`,
  read-only, `NoCapitalsStaticLinkTest`). Both probe `capital.CapitalManager`, bind
  `getCapitalByVillageId(ServerLevel, Integer)` (the two-argument overload, because the one-argument
  overload guesses the dimension), and read player thrones from `isPlayerSovereign` +
  `getPlayerSovereignId` rather than from the villager `getSovereign`.

### 1.3 Capitals 1.3.7 surfaces (verified by `javap`)

| Area | Members | Notes |
|---|---|---|
| Registry | `capital.CapitalManager#getCapitalByVillageId(ServerLevel,Integer)`, `getAllCapitalRecords()`, `getCapital(UUID)`, `isCapitalInLevel(CapitalRecord,ServerLevel)`, `getCapitalBySovereign(UUID)`, `getCapitalForResident(UUID)`; `capital.CapitalRecord#getCapitalId/getVillageId/getVillageDimensionId/getState`; `capital.CapitalState {PENDING, FOUNDED, ACTIVE}` | In-memory map mirrored to saved data; no dynamic registry. |
| Thrones and court | `CapitalRecord#getSovereign`, `isPlayerSovereign()`, `getPlayerSovereignId()`, `isPlayerConsort()`, `getPlayerConsortId()`, `getHand/getCommander/getMasterOfLaws/getHerald/getGrandMaester`, `isLord/isDuke/isKnight(UUID)`, `getRoyalGuards()`, `isRoyalGuard(UUID)` | A player throne is a separate field pair from the villager sovereign. |
| Player authority | `capital.CapitalPlayerAuthorityResolver#resolve(ServerLevel, CapitalRecord, UUID) → ResolvedAuthority(rank, displayTitle, permissions, capitalId)`; `PlayerRank {STRANGER, COMMONER, LORD, DUKE, PRINCE, PRINCE_CONSORT, SOVEREIGN, SOVEREIGN_CONSORT, LORD_COMMANDER, HAND, HIGH_SOVEREIGN}`; `Permission {CHANGE_TAXES, CHANGE_BIRTH_POLICY, CHANGE_MARRIAGE_POLICY}` | `resolve` returns `stranger()` unless the capital is `ACTIVE`. Rank is derived from: the record's lord/duke sets, `PlayerCapitalTitleService.getGrantedTitle/getMarriageTitle/getDowagerBaseTitle`, heir/royal-child/legitimized membership, consort fields, `isHand`/`isCommander`. `HIGH_SOVEREIGN` = sovereign of two or more active capitals. **`KNIGHT`/`DAME` has no `PlayerRank`; a knighted player resolves as `COMMONER`.** |
| MCA rank override | `resolveCompatibilityRank` maps SOVEREIGN/HIGH_SOVEREIGN → MCA `Rank.MONARCH`; Duke, Prince, Sovereign, Hand (all three permissions) → `MAYOR`; Lord (CHANGE_TAXES) → `MERCHANT`; everyone else → `PEASANT`. `mixin/TasksCapitalRankMixin` makes MCA's `Tasks.getRank(Village, ServerPlayer)` return this in an active capital and suppresses MCA's rank tasks; `RankCriterionSuppressionMixin`, `MayorConstraintCapitalAuthorityMixin`, `SaveVillageMessageCapitalAuthorityMixin` complete the override. | **MCA `Rank.NOBLE` is never produced in a capital.** MCA's native rank is therefore not a usable definition of "Noble" there. |
| Titles | `noble.NobleTitle {COMMONER, LORD, LADY, KNIGHT, DAME, DUKE, DUCHESS, ARCHDUKE, ARCHDUCHESS, PRINCE, PRINCESS, KING, QUEEN}`; `isNoble()` = not `COMMONER`; `isSovereign()` = KING/QUEEN; `isRoyal()` = PRINCE/PRINCESS (+ KING/QUEEN). `player.PlayerCapitalTitleService#getGrantedTitle/getMarriageTitle/getDowagerBaseTitle(ServerLevel, CapitalRecord, UUID)`, `isHand`, `isCommander`, `hasAnyOffice`. `noble.NobleManager` is an unpersisted villager map (not bound, as Quests found). | Per capital: a title is held *in* one `CapitalRecord`. |
| Capitals' own justice | Villager accusations: `data.CapitalJusticeSavedData` (`arrestWarrantsByCapital`, `detainedPrisonersByCapital`, `judgmentsByCapital`, `CapitalJudgmentType {IMPRISONMENT, EXECUTION, PARDON, EXILE}`), driven by `/capitallaw accuse <capitalId> <targetId>` and `/capitaljustice review/options/judge/recognize/restore` (`util.CapitalLawCommands`, `util.CapitalJusticeCommands`), ticked by `capital.CapitalPrisonerHandler` (villager targets only: `isTeenOrAdultVillager`). Player side: `data.CapitalPlayerWarrantSavedData` (leave orders, warrants keyed player×capital, 6000-tick sentences, penalised case keys); `capital.CapitalPlayerWarrantService.orderToLeave` (2400 ticks, called only from `item.SealedPurseHandler`), warrant issued automatically on failure to leave (`CapitalPlayerLegalHandler.tickLeaveOrders`, key `mcacapitals.justice.player_warrant.issued_for_failure_to_leave`), `payFine` (exactly 32 emeralds), `surrender` (teleport to `CapitalBuildingService.getPrisonCenters`), dialogue buttons `dialogue.main.mcacapitals_pay_warrant_fine` / `_surrender_warrant`. `item.RoyalPardonHandler` (sovereign, Hand, Master of Laws). | **Capitals has no sovereign-initiated warrant against a player.** Its player warrants are automatic trespass penalties. Nothing here is written by this plan; one read (`CapitalPlayerWarrantDataAccess.hasWarrant(ServerLevel, UUID, UUID)`) is optional diagnostics. |
| Prisons | `capital.CapitalBuildingService#hasPrison/getPrisonBounds/getPrisonCenters` read MCA `Village.getBuildingsOfType("prison")` through `util.MCAVillageBridge`; `CapitalMasterOfLawsService.hasUnlockedJustice` and NPC justice are gated on a recognised prison. | A Blueprint prison built from Crime blocks (section 4) therefore also unlocks Capitals' justice. |
| Chronicle and notices | `capital.CapitalChronicleService#addEntry(ServerLevel, CapitalRecord, String)`, `addEntryWithoutHerald(CapitalRecord, String)` (both present in 1.3.6 and 1.3.7), semantic `addEvent(..., CapitalChronicleEventId, Object...)` with a closed Capitals enum; `capital.CapitalPlayerNotificationService#notifyPlayersInCapital(ServerLevel, CapitalRecord, Component)`, `isPlayerWithinCapital` (MCA last-seen village id equals the capital's village). `CapitalPrisonerHandler` treats 96 blocks from the village centre as "inside the capital". | Flat server-locale text; the Quests binding already uses the two string entry points. |
| Blueprint | `mixin/GetVillageRequestCapitalAuthorityMixin` → `network/SyncBlueprintAuthorityPacket` (rank, permissions, population, reputation, master professionals) → client `BlueprintScreenCapitalAuthorityMixin` renders capital stats and rank requirements (`CapitalRankRequirements`). | Capitals already owns Blueprint authority display; this plan adds nothing there. |
| Events / API | None. No Forge `Event` subclasses, no `api` package, mixins only. | Lifecycle changes must be re-validated lazily (Quests polls). |

### 1.4 MCA prison definition and scanner (verified across the fleet)

`data/mca/building_types/prison.json` is byte-identical in all six 1.20.1 probe jars and the three
1.21.1 jars: `{"#mca:iron_bars": 8, "#mca:iron_doors": 1, "#minecraft:beds": 1}`, priority 4,
visible. Of the 26 building types, **only `prison.json` references `#mca:iron_bars` or
`#mca:iron_doors`**, so widening those tags cannot change any other classification.
`data/mca/tags/blocks/iron_bars.json` = `minecraft:iron_bars` + optional `#immersive_weathering:bars`;
`iron_doors.json` = `minecraft:iron_door` + seven optional Immersive Weathering doors; both
`"replace": false`. The 1.21.1 jars keep the same contents under `data/mca/tags/block/`.

`forge.net.conczin.mca.server.world.data.Building.validateBuilding` (7.7.1-beta.2; 7.6.20 has the same
members under `forge.net.mca`): flood-fills from the requesting player's position; `isPassable` is
`isAir()` (plus fluid), so bars and doors are walls; a `DoorBlock` sets `hasDoor` (else `NO_DOOR`) and is
walked through unless `strictScan`; at the end every visited position whose state satisfies
`isBuildingBlock` — any `BuildingType.matchesBlock(BlockState)`, which resolves `#tag` groups with
`BlockState.is(TagKey)` — is `addBlock`ed **by block registry id** (both door halves; beds only the
`HEAD` half). `determineType` resolves each id to a group through `BuildingType.getGroupForBlock`
(`Holder.is(TagKey)`), requires every group count ≥ the required count, and takes the highest
priority. Tags are resolved lazily through holders, and `BuildingTypes` is a
`SimpleJsonResourceReloadListener`, so `/reload` and datapack changes take effect at the next scan.
Scans run from the Blueprint (`network.c2s.ReportBuildingMessage` → `VillageManager.analyzeBuilding` /
`processBuilding`) and from MCA's own `VillageManager.tick` queue (`reportBuilding`).

Consequences for Crime's blocks: `block/CellDoorBlock extends DoorBlock` (`:59`) satisfies `hasDoor`
and is counted; `block/prison/ReinforcedBarsBlock extends IronBarsBlock` and
`ReinforcedBarsGappedBlock extends Block` are both counted by id once tagged; door open/closed,
hinge, half, `waterlogged`, and Crime's lock records (`locks/LockRecord` in `CrimeWorldData`, not a
blockstate) are irrelevant to counting. **`block/BunkBlock extends BaseEntityBlock`, not `BedBlock`,
and is not in `#minecraft:beds`**, so a Crime-only prison still needs one vanilla bed (section 4.3).

---

## 2. Sovereign-issued local wanted status ("royal decree")

Naming: the record is a **decree** (`RoyalWarrant` class names would collide in meaning with
`ledger/Warrant`, the bounty identity). Lang and commands say "decree"; the basis is
`LegalDecision.Basis.ROYAL_DECREE`.

### 2.1 Authority (who may issue)

Issuer `I` may act for community `C` (a `CrimeCommunityKey`) iff, read live through the Capitals bridge:

1. the `AUTHORITY` capability is bound (section 2.9) — otherwise **deny** (`not_verifiable`);
2. `CapitalManager.getCapitalByVillageId(levelOf(C), C.villageId)` returns a record `R` whose
   `getVillageDimensionId()` equals `C.dimension` and whose `getState() == ACTIVE`;
3. `R.isPlayerSovereign() && I.equals(R.getPlayerSovereignId())`.

Everything else is refused with its own reason: a villager sovereign (no player may issue), the
player consort (`SOVEREIGN_CONSORT`), the Hand, the Lord Commander, the Master of Laws, a granted
title, a displayed title (`ResolvedAuthority.displayTitle` is never read for authority), a throne in
another capital (`HIGH_SOVEREIGN` is simply the sovereign of each capital they actually hold — the
check is per record, never "is a king anywhere"). The issuer must also currently stand in `C`
(`CrimeCommunityResolver.resolve(issuer, level)` equals `C`) at validation time, so a ruler cannot
decree into a village they are not in. **PROPOSED DEFAULT**; a config switch
`capitals.decrees.requireIssuerPresent=true` keeps it adjustable.

### 2.2 Eligibility (who may be named)

The requirement is "less than Noble status in that same village". Two vocabularies exist and this plan
uses **Capitals' own, per capital**, because MCA's native rank is overridden in every active capital
and never yields `NOBLE` there (section 1.3). Target `T` in capital `R` is **protected** when either:

- `CapitalPlayerAuthorityResolver.resolve(level, R, T).rank().name()` is **not** one of the two
  explicitly unprotected names `STRANGER`, `COMMONER` (an allow-list of names, never an ordinal
  comparison; any unknown name is treated as protected and logged once); or
- any of `getGrantedTitle`, `getMarriageTitle`, `getDowagerBaseTitle` for `(level, R, T)` returns a
  `NobleTitle` whose `name()` is not `COMMONER` (Capitals' `isNoble()` contract) — this is what covers
  knights and dames, which the rank enum omits.

Resulting table (all read for the **same** capital record `R`):

| Target holds in `R` | Protected? | Why |
|---|---|---|
| Nothing (`COMMONER`), or not a member (`STRANGER`) | No | Eligible. |
| Granted `KNIGHT`/`DAME` | **Yes** | Capitals `NobleTitle.isNoble()`; rank alone would miss it. |
| `LORD`/`LADY` by record set, grant or marriage | Yes | `PlayerRank.LORD`. |
| `DUKE`/`DUCHESS` incl. marriage-derived and dowager base | Yes | `PlayerRank.DUKE`. |
| Heir, royal child, legitimised child (`PRINCE`), prince consort (`PRINCE_CONSORT`) | Yes | Inherited and marriage-derived royalty. |
| Hand, Lord Commander (court offices) | Yes | Capitals ranks both above commoner; protection ≠ policing power. |
| Sovereign / consort of `R` | Yes | Also `target == issuer` is refused separately. |
| Any title held only in **another** capital | No | `resolve` is evaluated against `R`; a foreign duke is a stranger here. |
| MCA native rank (reputation tasks) | Ignored | Overridden by Capitals in capitals; outside capitals there is no sovereign. |
| Capability unbound, record missing, state not `ACTIVE`, lookup threw | **Deny** | Unavailable is not "no title". |

Nobles remain fully subject to ordinary crime law: a decree is one instrument; witnessed crimes,
reports, Heat and bounties are untouched by any title.

### 2.3 Server validation (one path for every door)

`decree/DecreeService.issue(ServerPlayer issuer, UUID target, CrimeCommunityKey community,
DecreeReason reason, DecreeProvenance provenance)` on the server thread, behind
`state/world/ServerMutationGate`, re-deriving everything:

1. `capitals.enabled && capitals.decrees.enabled`, server not read-only (`CrimeWorldData.isReadOnlyFutureData`);
2. issuer online, alive, standing in `community` (dimension and village re-resolved from position);
3. authority (2.1) re-read now; eligibility (2.2) re-read now; `target != issuer`;
4. target identity: an online `ServerPlayer` resolved by UUID through the player list (the UI only
   offers players currently inside the community; the command accepts `EntityArgument.player()`);
   **PROPOSED DEFAULT** online-only issuance, enforcement applies whenever they are next present;
5. no `OPEN` decree already exists for `(target, community)` — repeat submission answers `duplicate`
   and changes nothing (no stacking, no revision bump);
6. cooldown `(community, target)` since the last resolution (`capitals.decrees.cooldownTicks`);
7. capacity `capitals.decrees.maxOpenPerCapital` and the store ceiling.

Packets reach the service only through `ServerPacketGuard.accept(..., RequestBudget.Category.ACTION,
...)`, with menu id + revision + nonce validated against the `ActionMenuSession` that offered the
decree screen, exactly as `StartActionC2SPacket` does today; a target not in the offered candidate
set is rejected before eligibility is even consulted (defence in depth, not the authority check).

### 2.4 Player access

**Gameplay route (recommended).** A new entity action `mcacrime:royal_decree`
(`action/handler/RoyalDecreeActionHandler`, `ActionDescriptor.of(..., ActionCategory.SPECIAL,
ActionLegality.LAWFUL, ActionDuration.INSTANT, hostile=false, ActionRequirement.AUTHORITY)`) appears in
the existing villager menu when the actor is the sovereign of the villager's community and the
villager is a **court officer or guard of that capital** (Hand, Lord Commander, Master of Laws,
Herald, a royal guard by `isRoyalGuard`, or any MCA guard of that village by `McaCompat.isGuard`).
Starting it sends `RoyalDecreeMenuS2CPacket` (menu id, revision, community label, the capital's open
decrees, candidates = online players currently resolved to that community with `eligible` and a reason
key, the reason vocabulary, the configured duration) and opens `client/screen/RoyalDecreeScreen`:
a candidate list, a reason picker, Issue (confirmed through `CrimeConfirmationScreen`) and Withdraw.
The client answers with `RoyalDecreeC2SPacket(menuId, revision, nonce, op ISSUE|WITHDRAW, target,
reason)`. This is thematically Capitals' own pattern (justice goes through the Master of Laws), needs
no proximity to the target, and reuses the menu replay guard. It adds two packets, so
`PROTOCOL_VERSION` becomes `"20"`.

Alternatives considered: (a) opening the entity menu on another player — today
`CrimeActionInteractHandler.onInteract:48` requires an MCA villager, so this needs a second interact
route and a new `ActionMenuKind.PLAYER` (also a protocol bump) and makes the ruler stand next to the
target; (b) a picker from the player card (`SELF` menu). Both are viable; see decision D2.

**Command fallback.** `/crime decree issue <player> [reason]`, `/crime decree withdraw <player>`,
`/crime decree list` — `requires(src -> src.isPlayer())`, **no operator permission**; authority is the
service's decision. Operator-only: `/crime decree void <player> <village>` (permission 3) for stuck
states, recorded with provenance `OPERATOR`. Every refusal has a specific key:
`mcacrime.decree.refused.{disabled,not_verifiable,not_sovereign,not_in_village,target_self,
target_noble,target_unknown,duplicate,cooldown,capital_full}`; confirmations
`mcacrime.decree.issued`, `.withdrawn`, `.voided`; the target hears `mcacrime.decree.notice.issued`
("You are wanted in <village> by decree of <name>: pay the fine, surrender to a guard, or stay out")
on issue and whenever they next enter the community, and `.notice.lifted` on withdrawal or expiry.

### 2.5 Local consequences (reuse of existing services)

Record `decree/Decree(id, offender, community, capitalId, issuedBy, issuedByName, reason, note,
issuedAt, expiresAt, state, resolvedAt, resolvedBy, resolution, provenance, sentenceId, revision)`,
`DecreeState {OPEN, WITHDRAWN, FINED, SERVED, EXPIRED, VOID}`, `DecreeReason {TRESPASS, DISORDER,
DEFIANCE, TREASON, OTHER}` (bounded note ≤ 96 chars, control characters rejected like
`JurisdictionSnapshot.token`), `DecreeProvenance {MENU, COMMAND, OPERATOR, API}`. Stored in
`CrimeWorldData.decrees` (keyed by id, indexed by offender; `MAX_DECREES = 1024`; closed decrees kept
30 in-game days like transaction receipts, then swept). One `OPEN` per `(offender, community)`.

- `justice/LegalDecision.Basis.ROYAL_DECREE` is added in `JusticeService.evaluate` when
  `jurisdiction != null` and `data.openDecree(offender, jurisdiction)` exists and still validates
  (section 2.7). `mayChallenge()` becomes true for guards **of that community only**; `requiresCustody()`
  stays false.
- `GuardEnforcement.tick:132` gains `|| DecreeService.openIn(server, player, currentCommunityOf(player))`
  so pursuit starts for a decreed player with no cases; stand-down applies when they leave the community.
- `GuardChallengeService.challenge:124` is unchanged; `sendCharges` adds one line
  `mcacrime.challenge.decree` ("By decree of <sovereign>, <village> wants you for <reason>") so a
  zero-case challenge explains itself. `GuardChallengeS2CPacket` is not changed.
- `ArrestService.arrest:141-190`: when the decision's basis contains `ROYAL_DECREE`, the sentence floor
  is `capitals.decrees.sentenceTicks` (`max(assessed, floor)`), so `NO_SENTENCE` cannot trigger on a
  decree alone; the decree's `sentenceId` is bound to the intake id; `economy/DispositionService.offer`
  adds a decree fine line (`capitals.decrees.fineAmount`, in the active `economy/Currency`) to the
  `SettlementQuote`. Paying through `FineService.pay`/`PayBailActionHandler` resolves the decree
  `FINED`; `PlayerReleasedFromJailEvent` with a served reason resolves `SERVED`; voluntary surrender
  (`economy/SurrenderService`, `action/handler/SurrenderActionHandler`) goes through the same arrest
  path and keeps its existing reduction.
- Not touched, by design and pinned by tests: `CrimeState` Heat/Karma/Band; `ledger/Warrant` and
  `WarrantService`; `OutlawResolver` (`lawfulCombatTarget`, `attackLawful`, `killLawful` unchanged, so
  another player's attack on a decreed player is still a crime and guards use force only through the
  existing refusal → `RESISTING_ARREST` escalation); `bountyEligible` (no open `Warrant`, so no bounty
  and `BountyContractBoard` posts nothing); the case ledger (no `CrimeRecord`, so `/crime ledger`,
  `CrimePublicView`, MCA: Reputation delivery and village news see nothing); MCA hearts.
- `api/McaCrimeApi.mayEnforce` picks the basis up automatically through `JusticeService.evaluate`.
- API: `api/model/DecreeView`, `McaCrimeApi.decrees(MinecraftServer, UUID)`, and
  `api/event/DecreeChangedEvent {ISSUED, WITHDRAWN, RESOLVED, EXPIRED, VOIDED}` (UUID-based like
  `WarrantChangedEvent`, so offline targets are reportable). Update `apiReadModel` in `build.gradle` and
  `ApiJarClosureTest`.

### 2.6 Legal semantics, stated

A decree is **not** Wanted: it never changes `CrimeState.isWanted`, the HUD's global WANTED flag, Heat,
Karma, the Outlaw band, or any `Warrant`. It is a local, time-bounded order that makes the target
challengeable and arrestable by that village's guards while inside that village, with a fine and a
custody term as the two lawful ends. It carries no evidence and creates no case. Kill-on-sight is not
granted: lethal force arises only from the pre-existing resisting-arrest rules, which the challenge
screen already explains.

### 2.7 Lifecycle

| Event | Behaviour |
|---|---|
| Duration | `expiresAt = issuedAt + capitals.decrees.durationTicks` on **overworld game time** (**PROPOSED DEFAULT** 72,000 = three in-game days; offline time counts). `CrimeMaintenanceSweep` marks `EXPIRED`. |
| Cooldown | `capitals.decrees.cooldownTicks` (**PROPOSED DEFAULT** 24,000) per `(community, target)` after any resolution. |
| Withdrawal | By the **current** player sovereign of that capital (re-read), regardless of who issued; `WITHDRAWN`. Operator `void`. |
| Sentence / fine | `SERVED` / `FINED` as in 2.5; a decree bound to a sentence is not expired by time while the sentence runs. |
| Later ennoblement | Checked lazily at every enforcement read and by the sweep: a now-protected target voids the decree (`VOID`, note `target_ennobled`); nothing is checked only at issue. |
| Succession, abdication, deposition | Decrees belong to the capital, not the person: they persist; the new player sovereign may withdraw; a former sovereign loses authority immediately. A villager sovereign after succession means no player can withdraw; decrees run to expiry or an operator void. |
| Vacant throne, capital not `ACTIVE`, village dissolved, dimension missing | Enforcement is **suspended** (authority cannot be re-verified); the decree is kept until expiry and reported by `/crime validate`. |
| Offline target | Persists; enforced on return; expiry still runs. |
| Restart | `CrimeWorldData` persistence; schema 17 stamp-only step (`v16to17`), absent key loads empty. |
| Integration disabled or Capitals removed | No enforcement, no issuing; records kept; `/crime validate` says why. Re-enabling resumes. |
| Resolution scope | Only the decree record changes. Case binding (`CrimeWorldData.bindSentence`) and case resolution are untouched; concurrent ordinary cases keep their own lifecycle. |

### 2.8 Coexistence with Capitals' own justice

Crime never writes Capitals' justice or warrant saved data and never calls its services that mutate
state (no `issueWarrant`, `orderToLeave`, `issueArrestWarrant`, `setJudgment`). The optional
`PLAYER_WARRANTS` capability reads `CapitalPlayerWarrantDataAccess.hasWarrant` for `/crime status`
("Capitals also holds a warrant against you here") and diagnostics only. Capitals' "inside the capital"
(96 blocks from the village centre, or MCA last-seen village) and Crime's community resolution can
disagree near the border; Crime enforces by its own community rule and documents the difference.

### 2.9 The Capitals seam

- `compat/CapitalsBridge` (always loadable facade, string-only reference to the adapter), `compat/
  CapitalsCapability {REGISTRY, AUTHORITY, TITLES, COURT, CHRONICLE, PLAYER_WARRANTS}`,
  `compat/CapitalView(capitalId, villageId, dimension, active, playerSovereignId)`,
  `compat/CapitalAuthorityView(rankName, grantedTitle, marriageTitle, dowagerTitle, isPlayerSovereign,
  isHand, isCommander)`, `compat/CapitalsQueryResult` (reuse `TownsteadQueryResult`'s shape or
  generalise it), `compat/CapitalsDiagnostics`.
- `compat/capitals/CapitalsBinding` (manifest: owner, name, arity, staticness; erased `Object`
  handles; `PACKAGE = "com.majesttyx.mcacapitals."`, probe `capital.CapitalManager`, `initialize=false`),
  `compat/capitals/CapitalsHandles`, `compat/capitals/ReflectiveCapitalsBridge`. Members: the registry
  and record getters in 1.3, `CapitalPlayerAuthorityResolver.resolve`/3, `ResolvedAuthority.rank`/0,
  `PlayerCapitalTitleService.getGrantedTitle|getMarriageTitle|getDowagerBaseTitle`/3, `isHand`/3,
  `isCommander`/3, `CapitalRecord.isRoyalGuard`/1, `getHand|getCommander|getMasterOfLaws|getHerald`/0,
  `CapitalChronicleService.addEntry`/3 and `addEntryWithoutHerald`/2,
  `CapitalPlayerWarrantDataAccess.hasWarrant`/3. Enum values cross as `Enum.name()` strings.
- Binds at `ServerStartedEvent` beside `TownsteadBridge.bind`, released on stop; re-probed by
  `/crime reload` and `/crime debug capitals probe`.
- `mods.toml`: `mcacapitals` optional, `[0,)`, `AFTER` (Capitals declares nothing about Crime; no
  cycle). `OptionalMods` row `adapted("mcacapitals", ...)`. `OptionalClassloadTest`: add
  `"com/majesttyx/mcacapitals/"` to `FORBIDDEN_PACKAGES` and the adapter triple to `ADAPTERS`;
  new `NoCapitalsStaticLinkTest` mirroring `NoTownsteadStaticLinkTest`.
- `capitalsProbeTest -PcapitalsJar=<jar>` Gradle task modelled on `townsteadProbeTest`, pairing the
  Capitals jar with MCA `7.7.1-beta.2+1.20.1` (`mcaProbe` index lookup as `townsteadProbePairs` does)
  because Capitals' descriptors name `forge.net.conczin.mca` types; `compat/CapitalsBindingProbeTest`
  asserts every required member binds and that resolution without Capitals is `ABSENT` and silent.

### 2.10 Configuration (`[capitals]`, COMMON, all no-ops without Capitals)

| Key | PROPOSED DEFAULT | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch; off behaves like absent but reports `OFF`. |
| `decrees.enabled` | `true` | Royal decrees. |
| `decrees.durationTicks` | 72000 | Overworld game ticks a decree stands. |
| `decrees.cooldownTicks` | 24000 | Per community and target after resolution. |
| `decrees.maxOpenPerCapital` | 8 | Open decrees one capital may hold. |
| `decrees.sentenceTicks` | 6000 | Custody floor for a decree-only arrest (Capitals' own sentence length). |
| `decrees.fineAmount` | 32 | In the active currency (Capitals charges 32 emeralds). |
| `decrees.requireIssuerPresent` | `true` | Issuer must stand in the village. |
| `decrees.chronicle` | `false` | Write an issue/withdrawal line to the capital's chronicle. |
| `decrees.chronicleHerald` | `false` | Let the herald announce it (only with `chronicle`). |
| `readCapitalsWarrants` | `true` | Show Capitals' own warrant presence in `/crime status`. |

`config/ConfigValidator`: `cooldownTicks ≤ durationTicks`, `sentenceTicks ≤ jail.maxJailCommandTicks`,
`fineAmount ≥ 0`; a decree setting that is on while its capability is unbound is reported degraded, as
Townstead's are. Keys never repeat their section name (`config/ConfigGroupCoverageTest` style).

---

## 3. Further Capitals integration — include / defer matrix

| Surface | Decision | Verified hook | Dependencies | Acceptance |
|---|---|---|---|---|
| Village jurisdiction ↔ capital, sovereign recognition | **Include** (read-only) | `CapitalManager.getCapitalByVillageId(ServerLevel,Integer)` + `getVillageDimensionId`; `isPlayerSovereign`/`getPlayerSovereignId` | REGISTRY, AUTHORITY | `/crime debug capitals` names the capital and ruler of the current community; a Nether village with the same id is not confused with the overworld one. **No `JurisdictionPolicyApi` registration** (one provider slot, held by Kingdoms when present); a test asserts the bridge never calls `JurisdictionPolicies.attach`. Kingdoms' snapshot (`recognizedSovereign`) is not merged with Capitals' answer. |
| Royal guards and court officials in enforcement | **Include the menu host role only**; **defer** policing powers | `isRoyalGuard(UUID)`, `getHand/getCommander/getMasterOfLaws/getHerald` | COURT | Court officers host the decree screen. Royal guards enforce only if they already are MCA guards (`McaCompat.isGuard`); **ASSUMPTION** to verify in-game: Capitals' `appointRoyalGuard` leaves the guard profession in place. No office gains a new power. |
| Withdrawal / clemency | **Include** sovereign withdrawal; **defer** Hand/Master-of-Laws clemency | section 2.7 | AUTHORITY | Only the current player sovereign withdraws; `handMayWithdraw` is not shipped (single authority keeps the model auditable). |
| Chronicle / court / village news | **Include** optional chronicle line, off by default | `addEntry`/3, `addEntryWithoutHerald`/2 | CHRONICLE | Text names only the target, the village and the reason category; no evidence exists to leak. Crime's own `news/CrimeNewsService` is **not** extended (it composes committed reports; a decree is not a report) — defer. |
| Succession, vacant throne, title changes, village lifecycle | **Include** lazy re-validation (every enforcement read + sweep) | `getState`, `getPlayerSovereignId`, `resolve` | AUTHORITY | Promotion voids, abdication transfers withdrawal, dissolution suspends. Event-driven reactions are **deferred** (Capitals exposes no events; Quests polls). |
| MCA: Reputation | **Include as non-behaviour** | n/a | — | Test: issuing, enforcing and resolving a decree enqueues no `CrimeIntegrationOperation` and changes no standing. |
| MCA: Quests | **Include as non-behaviour** | `BountyContractBoard.onWarrantChanged` | — | Test: no contract is posted for a decree. |
| MCA: Conversations | **Defer** content; **include** the `DecreeView` API so guard lines can later mention a decree through `api/CrimeDialogueHooks`. | — | — | API exposed; no lines authored here. |
| Townstead | **Defer** reactions; facilities unaffected | — | — | A decree arrest uses the same facility ladder. |
| MCA: Kingdoms | **Include** coexistence test | `JurisdictionPolicies.attach` | — | Both mods installed: Kingdoms' provider still attaches; Capitals bridge reads only. |
| Capitals' own player warrants | **Include** read-only status line | `CapitalPlayerWarrantDataAccess.hasWarrant`/3 | PLAYER_WARRANTS | Shown in `/crime status` and diagnostics; never drives a Crime challenge. |
| Capitals NPC justice (accusations, judgments) | **No claim** | — | — | Not read, not written. |
| Blueprint authority panel | **No claim** | Capitals' own mixins | — | Nothing added. |

---

## 4. MCA Blueprint recognition of Crime prison blocks

### 4.1 Change

Two additive tag files per tree, nothing else:

- `src/main/resources/data/mca/tags/blocks/iron_bars.json`:
  `{"replace": false, "values": ["mcacrime:reinforced_bars", "mcacrime:reinforced_bars_gap"]}`
- `src/main/resources/data/mca/tags/blocks/iron_doors.json`:
  `{"replace": false, "values": ["mcacrime:cell_door"]}`
- NeoForge port: the same two files under `data/mca/tags/block/`.

No mixin, no fallback: tag merging across mod jars is vanilla behaviour, every fleet jar's
`prison.json` requires exactly `#mca:iron_bars`, `#mca:iron_doors`, `#minecraft:beds`, and the scanner
resolves tags through holders (section 1.4). The only way this stops working is a server datapack
that sets `"replace": true` on `mca:iron_bars`/`iron_doors`, which is that operator's decision;
`DATAPACK.md` documents it. `CrimeBlocks` already registers the three ids (`block/CrimeBlocks.java:44,
142, 151`); `MODMAP.md` lists them.

### 4.2 What is preserved (by construction)

Vanilla materials keep working (the tag is widened, not replaced); mixed materials count as one group
(5 iron bars + 3 reinforced bars = 8); required quantities, beds, enclosure (flood fill, roof, door)
and priority are MCA's and untouched; the other 25 building types do not reference these tags;
datapack overrides of `prison.json` still apply since only the tags change. Door halves: both halves of
a door are counted by id (≥ 1 satisfied); hinge, facing, open/closed, `waterlogged` and Crime's lock
state are not part of the count; a locked cell door is still a `DoorBlock` for the `hasDoor` check.
Scanner reachability: the gapped bars block is not air, so it is a wall like any bar; a cell whose only
opening is a Crime cell door scans like one with an iron door.

### 4.3 Limits to document

- A Crime-only prison needs **one vanilla bed** (`#minecraft:beds`); `mcacrime:bunk` is not a bed and
  must not be added to `#minecraft:beds` (villagers would claim bunks as homes).
- Crime's temporary holding cell (`jail/CellBlueprint`: reinforced floor, pillars, bars, cell door, lamp,
  no bed) does not satisfy `#minecraft:beds: 1`, so it is never classified as a prison or a house.
  Verify once in-game after MCA's own `VillageManager.tick` scan queue runs.
- Recognition is MCA's; existing prisons are rescanned by pointing the Blueprint at the room again
  (`ReportBuildingMessage.ADD_ROOM`) or by MCA's periodic `processBuilding`; recognition survives
  `/reload` and restarts because MCA persists `Building.type` and re-resolves tags lazily.
- Works without Capitals; with Capitals, a recognised prison also satisfies
  `CapitalBuildingService.hasPrison`, unlocking Capitals' NPC justice and giving its `surrender` a
  teleport target. Mention in the changelog.

### 4.4 Should recognised MCA prisons join Crime's jail selection?

**Recommendation: not in 0.7.8.** Crime's automatic ladder (`JailRegistry.automatic` → Townstead
`FacilityAssignment` → manual `JailAnchor` → holding cell → config fallback) selects *custody*
destinations that were explicitly assigned or built by Crime, with capacity, reservation
(`facility/CrimeFacilityService.reserve`) and revalidation. An MCA "prison" is eight bars, a door and a
bed, with no enclosure guarantee a `JailConfine` region could rely on, no lockable door requirement and
no capacity. Making it a destination would need: new `McaBinding` members (`Village#getBuildingsOfType`,
`Building#getPos0/getPos1/getCenter/getId/getType`, all present in 7.6.20 and 7.7.1), a second
building-reference kind beside `facility/TownsteadBuildingRef` (today `FacilityAssignment.ref` is
Townstead-typed), and a `JailAnchor` radius derived from the building box. That is a 0.7.9 feature with
its own acceptance work. Operators already get the effect today with `/crime assignjail` inside the
recognised prison. Ship in 0.7.8 only a read-only diagnostic (`/crime debug prisons`: MCA prisons in the
current village, via the new binding members) if the binding is added for the diagnostic; otherwise defer
the binding too (decision D5).

---

## 5. Ordered implementation phases

Each phase compiles and passes `check` on its own; Phase 1 is independent of every other phase.

### Phase 0 — Baseline guard
Confirm the uncommitted 0.7.7 tree is committed/released (134 files today) before 0.7.8 work begins, or
branch from it. `mod_version` moves to 0.7.8 in `gradle.properties` only, at the end of Phase 6.

### Phase 1 — Blueprint prison tags (both trees)
Files: the two tag JSONs (4.1). Tests: `block/PrisonTagJsonTest` (parses both files, `replace == false`,
exact value sets, every id is a registered `CrimeBlocks` id); `compat/McaPrisonDefinitionProbeTest`
(opens every `mcacrime.probe.jars` entry, reads `data/mca/building_types/prison.json` and both tag files,
asserts the three groups and the `#mca:` tag ids this plan relies on — so an MCA release that renames
the tag fails the build, not a player's prison). Run `check_mod.py` (tags are counted under
"block tags"). Docs: README ("Blueprint prisons" under *With the rest of the suite*), `DATAPACK.md`
(the two tags and the `replace: true` caveat), CHANGELOG, `docs/0.7.8/BLUEPRINT_PRISON.md`.

### Phase 2 — Capitals seam
Files: `compat/CapitalsBridge`, `compat/CapitalsCapability`, `compat/CapitalView`,
`compat/CapitalAuthorityView`, `compat/CapitalsDiagnostics`, `compat/capitals/CapitalsBinding`,
`compat/capitals/CapitalsHandles`, `compat/capitals/ReflectiveCapitalsBridge`; `[capitals]` config block
in `McaCrimeConfig` + `ConfigValidator`; `META-INF/mods.toml` entry; `OptionalMods` row;
`McaCrime.onCommonSetup` nothing (binding is at server start, in the same handler that calls
`TownsteadBridge.bind`); `command/CrimeCommand` `/crime debug capitals [probe]` and `/crime validate`
lines; `build.gradle` `capitalsProbeTest`. Tests: `OptionalClassloadTest` (forbidden package + adapter
entry), `NoCapitalsStaticLinkTest`, `ModsTomlOrderingTest` (`mcacapitals` is `AFTER`),
`compat/CapitalsBindingProbeTest` (with the jar) and `compat/CapitalsAbsentTest` (no jar: `ABSENT`,
no log line, every query answers unavailable), `CapitalsConfigValidationTest`.

### Phase 3 — Decree model and service
Files: `decree/Decree`, `decree/DecreeState`, `decree/DecreeReason`, `decree/DecreeProvenance`,
`decree/DecreeEligibility` (pure: authority and protection tables from `CapitalView`/
`CapitalAuthorityView` inputs), `decree/DecreeService` (pure state machine `issue/withdraw/resolve/
void/expire` + the server wiring), `CrimeWorldData.decrees` (+ NBT, bounds, `openDecree(offender,
community)`, `decreesOf(offender)`, `decreesIn(capitalId)`), `CrimeDataMigrations.SCHEMA_DECREES = 17`
+ `v16to17` (stamp only), `CrimeMaintenanceSweep` expiry/ennoblement/void pass, `api/model/DecreeView`,
`api/event/DecreeChangedEvent`, `McaCrimeApi.decrees`, `build.gradle apiReadModel`. Tests:
`DecreeEligibilityTest` (every row of the 2.2 table, foreign-village titles, unavailable → deny,
unknown rank name → protected), `DecreeServiceTest` (duplicate, cooldown, capacity, withdraw by current
sovereign only, former sovereign refused, resolution kinds, expiry on overworld time, suspended when
capital inactive), `DecreeNbtRoundTripTest`, `CrimeDataMigrationsV16toV17Test`, `ApiJarClosureTest`.

### Phase 4 — Enforcement wiring
Files: `justice/LegalDecision.Basis.ROYAL_DECREE` + `JusticeService.evaluate`; `GuardEnforcement.tick`
gate; `GuardChallengeService.sendCharges` decree line; `ArrestService.arrest` sentence floor and decree
binding; `economy/DispositionService` / `SettlementQuote` decree fine line; `FineService` and
`JailService.release` resolution hooks (`PlayerReleasedFromJailEvent` listener in `DecreeService`);
`/crime status` decree lines; lang keys. Tests: `JusticeServiceDecreeTest` (basis only when
jurisdiction matches; `hasKnownCases`'s null-jurisdiction scope never includes it),
`GuardEnforcementDecreeGateTest`, `ArrestServiceDecreeSentenceTest` (floor applied; `NO_SENTENCE` not
returned; cases unaffected), `OutlawResolverUnchangedByDecreeTest` (`lawfulCombatTarget`,
`attackLawful`, `killLawful`, `bountyEligible` identical with and without a decree),
`BountyNoContractForDecreeTest`, `ReputationNoIncidentForDecreeTest`, `ConcurrentCaseIsolationTest`
(resolving a decree leaves an open `CrimeRecord` unresolved and vice versa).

### Phase 5 — Player access
Files: `action/CrimeActionIds.ROYAL_DECREE`, `action/handler/RoyalDecreeActionHandler` (+ registry),
`network/RoyalDecreeMenuS2CPacket`, `network/RoyalDecreeC2SPacket`, `network/PacketBounds` caps
(candidates ≤ 64, note ≤ 96), `CrimeNetwork.register` + `PROTOCOL_VERSION = "20"`,
`client/screen/RoyalDecreeScreen` (uses `CrimeConfirmationScreen`), `client/ClientDecreeData` if the
HUD shows "Wanted in <village>" (decision D6), `/crime decree …` in `CrimeCommand`. Tests: packet
round trips, `RoyalDecreeActionHandlerTest` (hidden for non-sovereigns, hidden on a non-court villager,
available for the sovereign on a guard of that capital), `DecreeReplayTest` (stale menu revision,
reused nonce, target outside the offered set, forged community id all rejected before authority),
`CrimeCommandTest` additions (no permission level required; same refusals as the menu).

### Phase 6 — Documentation and release notes
`CHANGELOG.md` (0.7.8 section: decrees, Blueprint prisons, protocol 20, schema 17, new keys),
`README.md` (Compatibility list + suite table rows for Capitals and Blueprint prisons), `CONFIG.md`
(`[capitals]`), `docs/MIGRATION.md` (0.7.8: schema 17 stamp-only, protocol 20 client+server update,
rollback keeps decrees inert), `API.md` (`DecreeView`, `DecreeChangedEvent`, `decrees`), `CLAUDE.md`
(structure line `compat/capitals`, convention bullet), `MODMAP.md` hand-maintained 0.7.8 notes,
`docs/0.7.8/CAPITALS.md` (support matrix, capability table, limits), `docs/0.7.8/VALIDATION.md`
(recorded results), `CURSEFORGE.md` blurb, and a row in `MCAReputation/docs/FAMILY_COMPATIBILITY.md`
for the verified Crime 0.7.8 × Capitals 1.3.7 × MCA 7.7.1-beta.2 pair.

### Phase 7 — NeoForge 1.21.1 port
Tags under `data/mca/tags/block/`; the `compat/capitals` adapter (NeoForge Capitals 1.3.5 has every
bound class; `neoforge.mods.toml` optional `mcacapitals`); the decree store and enforcement (the port
has `justice/`, `enforcement/`, `jail/`); protocol bump there (`"18"`); schema: add **stamp-only**
`SCHEMA_VILLAGE_JUSTICE = 16` and `SCHEMA_DECREES = 17` so the two trees agree on what 16 and 17 mean
even while news (16) stays pending there; `docs/PORT_PARITY.md` rows; probe pairs Capitals 1.3.5 with
MCA 7.7.36-beta.3. Optional: a GameTest that builds a bars/cell-door/bed room and asks MCA's
`VillageManager.processBuilding` for `prison` (MCA-typed, so it lives in the port's `gametest` source set
only).

---

## 6. Automated tests and in-game acceptance

Automated coverage is listed per phase above. In-game acceptance (dedicated server, Forge 47.4.x, MCA
7.7.1-beta.2, Capitals 1.3.7, Architectury 9.2.14, GeckoLib 4.8.3; repeat the Blueprint scenarios
without Capitals and once on NeoForge 21.1.x with MCA 7.7.36-beta.3 and Capitals 1.3.5):

Decrees
1. Player sovereign (via Capitals' own founding/claiming) in the capital, target commoner inside: menu
   on a guard shows the action; issue → target notified, guard challenges within the village, refusal
   escalates as today, surrender → custody with the decree floor, release resolves `SERVED`.
2. Same, pay the fine at the challenge → `FINED`; `/crime status` on the target shows nothing after.
3. Target leaves the village → guards stand down; re-entry → challenge resumes; expiry after the
   configured ticks while offline (advance `/time`).
4. Rank boundaries: grant the target `KNIGHT` (`/capitals` title commands) → refused `target_noble`;
   grant `LORD` → refused; make them Hand → refused; strip the title → allowed.
5. Foreign title: a Duke of capital B is eligible in capital A; the Duke of A is not.
6. Former sovereign: abdicate (Capitals' declaration), try to issue and to withdraw → refused; the new
   player sovereign withdraws successfully; with a villager sovereign nobody can issue.
7. Duplicate: issue twice → second refused, one record, one notification; `/crime decree list` shows one.
8. Concurrent crimes: target with an open assault case plus a decree; paying the decree fine leaves the
   case; serving the case sentence resolves the decree bound to the same intake only.
9. Forged/replayed: resend a captured `RoyalDecreeC2SPacket` after the menu expired or with another
   UUID → rejected, no record, one rate-limited log line.
10. Stale menu: open the screen, have an operator void the capital (`/capitals` dissolution) → Issue fails
    `not_sovereign`/`not_verifiable`; nothing written.
11. Offline target: issue by command against an online player who then logs off; restart the server; the
    record persists; log in inside the village → notice and challenge.
12. Integration off (`capitals.enabled=false`) and Capitals removed: no menu entry, command refuses
    `disabled`/`not_verifiable`, existing decrees not enforced, `/crime validate` reports it; MCA:
    Reputation shows no new incident; MCA: Quests posts no contract; Kingdoms' provider still attaches.
13. HUD/global state: the WANTED HUD flag, Heat, Karma, band and `/crime bounty` stay unchanged throughout.

Blueprint prisons
14. Vanilla-only prison (8 iron bars, iron door, bed) still recognised.
15. Crime-only prison (reinforced bars incl. one gapped bar, cell door, vanilla bed) recognised as
    `prison`; with 7 bars → not recognised; without a bed → not recognised; with the door removed →
    `NO_DOOR`.
16. Mixed: 5 iron bars + 3 reinforced bars + cell door → recognised.
17. Door states: closed, open, locked with a padlock (Crime lock), hinge left/right, bars joined to the
    door → all recognised; strict scan still stops at the door.
18. Rescan: an existing recognised prison stays recognised after `/reload`, after a restart, and after
    the room is reported again; replacing iron bars with reinforced bars in place keeps the type.
19. Datapack override: a pack with `replace: true` on `mca:iron_bars` drops Crime bars (documented).
20. With Capitals: the village gains a prison for Capitals (`hasPrison`), its `surrender` teleports
    to the Crime-built prison centre.
21. A Crime temporary holding cell raised by an arrest is not reported as any MCA building.

---

## 7. Recommended scope, deferrals, risks

**Ship in 0.7.8:** Phases 1–6 on `main`; Phase 7 in the same release for the tags, and for the
adapter and decrees if the port's lag (news, game rules) does not block them (decision D7).

**Defer:** MCA prisons as automatic custody destinations (4.4); Hand/Master-of-Laws clemency; royal
guards as a distinct responder class; Conversations lines; Townstead reactions; decree facts in
village news; event-driven succession reactions; any coupling to Capitals' NPC justice.

**Risks**
- Capitals moves a member in a point release: per-capability degrade handles it, but the probe needs
  the jar; Capitals 1.3.7 pins MCA `[7.7.1-alpha.2,7.7.1)`, so Capitals players are on exactly the MCA
  line the fleet's last three builds cover.
- Protocol `"20"` forces client and server to update together (documented in `MIGRATION.md`).
- The Noble definition is a product choice (D1); shipping the wrong one is a visible balance change.
- Chronicle text is flat server-locale text (Capitals' `addEntry` contract).
- Capitals' 96-block "inside the capital" versus Crime's village bounds: a target can be inside for one
  and outside for the other near the edge (documented; enforcement follows Crime).
- Widening `mca:iron_bars` means any bars + door + bed room in a village becomes an MCA prison, which
  also flips Capitals' justice gate for that village; intended, but new for existing worlds.
- The working tree carries 134 uncommitted 0.7.7 changes; the plan layers on them.
- **ASSUMPTION** Capitals royal guards keep the MCA guard profession (affects only who can host the
  decree menu and which guards respond; verify in acceptance 1).

---

## 8. Unresolved decisions

- **D1 — "Noble" definition.** Recommended: Capitals' vocabulary per capital (rank not in
  {STRANGER, COMMONER} **or** any held `NobleTitle` ≠ COMMONER), which protects knights and court
  offices. Alternative: rank-only (knights eligible). A third reading, MCA `Rank.isAtLeast(NOBLE)`, is
  rejected because Capitals never produces `NOBLE` in a capital.
- **D2 — Gameplay route.** Recommended: decree screen from the villager menu on a court officer or
  guard of the capital. Alternatives: entity menu on the target player; picker from the player card.
- **D3 — Duration clock.** Recommended: overworld game time (offline counts). Alternative: online ticks
  like a jail sentence.
- **D4 — Chronicle default.** Recommended off (`decrees.chronicle=false`); on without herald is the
  other defensible default.
- **D5 — MCA prison binding members in 0.7.8.** Add `Village#getBuildingsOfType` and the `Building`
  getters now for a read-only `/crime debug prisons`, or defer the whole binding with the feature.
- **D6 — HUD.** Show "Wanted in <village>" on the HUD (changes `SelfStatusS2CPacket`, already covered by
  the protocol bump) or rely on chat notices and `/crime status` only.
- **D7 — Port cadence.** Decrees in the NeoForge port in the same release, or tags now and decrees after
  the port catches up on news and game rules.

---

## 9. Implementation record (2026-10-10)

The plan above is kept as written. Where the shipped code differs, this section is authoritative.

**Decisions taken.** D1 Capitals' vocabulary per capital, protecting knights, dames and court offices;
D2 the decree screen from the villager menu, hosted by a guard of the village or by the Hand, Lord
Commander, Master of Laws, Herald or a royal guard; D3 overworld game time; D4 chronicle off; D5 no MCA
building binding and no `/crime debug prisons`; D6 chat and `/crime status` only, no HUD change; D7 the
NeoForge port carries the tags, the adapter and decrees.

**Seam.**
- `CapitalAuthorityView` shipped as two records: `CapitalStanding(rankName, grantedTitle, marriageTitle,
  dowagerTitle)` and `CapitalCourtView(hand, commander, masterOfLaws, herald)`. `CapitalsQueryResult` is
  its own sealed type (`Available`, `Absent` for a confirmed "nothing", `Unavailable`, `Failed`). There is
  no `CapitalsHandles`: `CapitalsBinding` and `ReflectiveCapitalsBridge` are the whole adapter.
- `PlayerCapitalTitleService.isHand/isCommander` are not bound. The Hand and the Lord Commander are
  protected through the resolver's `HAND` and `LORD_COMMANDER` ranks, and court membership is read from
  the record's `getHand/getCommander/getMasterOfLaws/getHerald/isRoyalGuard`.
- Added `CapitalDataAccess.markDirty` (after a chronicle line, as Capitals' own record changes do) and
  use `getAllCapitalRecords` to tell a capital that is gone from a registry that is empty or not loaded:
  only the first voids a decree.
- The bridge re-binds on a COMMON config reload, not on `/crime reload`, because `capitals.enabled` is
  config.

**Configuration.** `decrees.requireIssuerPresent` was not shipped: a decree is always for the village
the issuer stands in, so the issuer is present by construction. `readCapitalsWarrants` shipped as
`capitals.readPlayerWarrants`. `ConfigValidator.validateCapitals` also reports a herald without a
chronicle, a decree term longer than `maxJailCommandTicks`, and a decree fine while `enableFines` is off.

**Decree model.**
- Pure rules are `decree/DecreeRules` (the plan's `DecreeEligibility`). The record carries
  `offenderName` and has no separate `resolution`: `state` and `note` carry it (`target_ennobled`,
  `capital_gone`, `paid`, `served:<reason>`, `withdrawn:<route>`).
- `DecreeProvenance` is `MENU, COMMAND, OPERATOR, SYSTEM`; there is no `API` value because the API is
  read-only. `DecreeChangedEvent.Change` splits the plan's `RESOLVED` into `FINED` and `SERVED`.
- The refusal vocabulary has 18 keys, adding `not_a_capital`, `capital_inactive`, `store_full`,
  `read_only`, `not_found`, `stale`, `not_payable` and `cannot_pay`. Notices are `.notice.issued`,
  `.notice.entered`, `.notice.withdrawn`, `.notice.voided`, `.notice.expired` and `.notice.served` in
  place of `.notice.lifted`.
- Commands: `withdraw` takes a game profile, so it works on an offline target; `void <player>` voids
  every village's decree against that player; `pay` pays the decree standing where the player is.

**Enforcement.**
- `JusticeService.forGuard` and `McaCrimeApi.mayEnforce` pass the in-force decree into a new
  `JusticeService.evaluate` overload rather than `evaluate` looking it up, so the null-jurisdiction public
  scope (`hasKnownCases`) can never see one.
- A decree-only challenge is paid through `DecreeService.pay`, not through `SettlementQuote`. When a local
  case is present the challenge settles the case and the decree waits for the next challenge, so one
  payment never settles both.
- Serving is wired directly from `JailService.release` to `DecreeService.sentenceEnded` rather than
  through a `PlayerReleasedFromJailEvent` listener. A release that answers nothing (an operator's
  release, an invalid jail) unbinds the decree, which then stands again, as cases do.

**Network.** The packets are `DecreeMenuS2CPacket` and `DecreeActionC2SPacket` (the plan's
`RoyalDecree*`), protocol `"20"`.

**Blueprint.** Section 1.4's "takes the highest priority" holds for MCA 7.6. MCA 7.7.1-beta.2's
`analyzeBuilding` reports every type a room fits, and when there are several the Blueprint asks the
player (`BuildingPolymorphMessage`, answered by `ConfirmBuildingPolymorphMessage`, committed through
`commitBuilding(scan, type)`). A barred cell with a bed fits both prison and house, vanilla or Crime
alike, so on 7.7.1 the player chooses prison; the plain `processBuilding(pos)` answers `INVALID_TYPE` for
such a room. Nothing in the tags or in MCA: Crime changes this. On the NeoForge port, MCA 7.7.36-beta.3's
rebuilt scanner counts the blocks on a room's floor cells rather than its walls, so bars set into a wall
count for no room, iron or Crime; with eight bars and a door standing inside the room both make a prison.
The port's `BlueprintPrisonGameTests` record each layout for both materials.

**Tests.** The shipped suites are `decree/DecreeRulesTest`, `DecreeRecordTest`,
`DecreeEnforcementTest`, `DecreeIsolationTest`, `compat/CapitalsBridgeTest`,
`compat/McaPrisonDefinitionProbeTest`, `compat/capitals/CapitalsBindingProbeTest`,
`config/CapitalsConfigValidationTest`, `network/DecreePacketTest`, `NoCapitalsStaticLinkTest`,
`resource/PrisonTagJsonTest` and `CrimeDataMigrationsV16toV17Test`, plus additions to
`OptionalClassloadTest`, `ModsTomlOrderingTest`, `LangCoverageTest` and `GuardChallengeTextTest`. Most
of section 6's in-game acceptance is replaced by the production-server harness `tools/capitals`;
[VALIDATION.md](VALIDATION.md) lists which items it covers and which remain manual.


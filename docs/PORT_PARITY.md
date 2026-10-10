# Port parity ledger — MCA: Crime, NeoForge 1.21.1

Every difference between this port and the Forge 1.20.1 tree at `../../MCACrime`, and its status. The
family rule is that this tree mirrors every change made there; anything that does not is listed here as
**pending** (owed, not yet ported), **not ported** (a recorded decision), or a **loader adaptation**
(the same behaviour, built the way 1.21.1 and NeoForge require). Compiled from a file-level comparison
of both working trees on 2026-09-28, with 1.21.1's singular data directories and `neoforge.mods.toml`
mapped onto their Forge names. `docs/0.7.5/PARITY.md` describes how the port implements what it has;
this file says what it lacks.

## Numbers

| | Forge 1.20.1 | This port |
|---|---|---|
| `mod_version` | 0.7.7 | 0.7.7 |
| World schema (`CrimeDataMigrations.CURRENT_SCHEMA`) | 16 | 15 |
| Network protocol (`CrimeNetwork.PROTOCOL_VERSION`) | `"19"` | `"17"` |
| MCA range | `[7.6,8)` | `[7.7,8)` |
| MCA probe fleet | six 1.20.1 builds | 7.7.0, 7.7.13, 7.7.22, 7.7.33, 7.7.36-beta.3 |
| GeckoLib | required | not declared |

A world stamped 16 by the Forge build cannot reach this port (the Minecraft versions differ), but the
guard exists anyway: a schema newer than the build's own loads read-only and is written back unchanged.

## Features the port does not have

| Feature (Forge 0.7.5) | Forge sources | Status |
|---|---|---|
| Institutional services and jurisdiction API for MCA: Kingdoms (formerly Ultima Kingdoms) | `api/InstitutionalServiceApi`, `api/JurisdictionPolicyApi`, `api/jurisdiction/*`, `civic/InstitutionalWorkshopPolicy` | **Not ported.** Ultima Kingdoms, its only consumer, is Forge-only. |
| Peaceful player crime reports, attempted-robbery receipts, responder dispatch and report screens | `action/handler/ReportCrimeActionHandler`, `report/*`, `client/screen/PlayerReport(s)Screen`, four `network/*Report*` packets | **Pending.** |
| Village news through MCA's mailbox (world schema 16) | `news/*`, `compat/McaMailBridge`, `mixin/mca/MailboxMarker` | **Pending.** Porting it moves this tree to schema 16 with the explicit `v15to16` step. |
| Native MCA justice layer: descriptor-gated mixins into MCA's heart, blame, standing and mailbox sites | `mcacrime.mca.mixins.json`, `mixin/mca/*` (`McaJusticeMixinPlugin` and three markers), `compat/mca/NativeCombatContext`, `compat/mca/NativeJusticeCapabilities`, `mixin/ThiefGossipMixin` | **Partly ported (0.7.7).** The heart hooks and the guard-alert friend test ship: MCA's hurt and killing-penalty sites (`VillagerPenaltyMarker`, `TragedyPenaltyMarker`) and the `isFriend` call in its hurt handler, in an optional, plugin-gated `mcacrime.mca.mixins.json`, probed by `McaJusticeTargetTest` against all five `mca_probe_versions` builds and applied in the GameTest server; `ThiefGossipMixin` ships in `mcacrime.mixins.json`; `NativeCombatContext` carries the Thief combat decision, the murder-heart policy and the minor-harm hold-back, and `/crime validate` reports the hooks. **Pending:** `MailboxMarker`, with village news. |
| World-owned settings as game rules (`/crime rules`) | `config/CrimeGameRules`, `config/CrimeWorldSettings`, `config/CrimeSettingsTransitions`, `command/CrimeRulesCommand`, `mixin/GameRuleTypeAccessor` | **Pending.** The port reads the COMMON config directly. The per-tick settings memo (2026-09-28) comes with it. |
| GeckoLib 3D mask models | `client/render/MaskArmorModel`, `MaskArmorRenderer`, `MaskClientExtensions`, 20 `geo`/`animation` assets | **Pending.** Masks here are worn through the vanilla helmet armor layer (`MaskArmorMaterial` textures); GeckoLib is not a dependency of this port. |

## Loader adaptations (same behaviour, different mechanism)

| Area | Forge | This port |
|---|---|---|
| Player state | `state/CrimeCapabilities`, `CrimeCapabilityEvents`, `PlayerCrimeDataProvider` | `state/CrimeAttachments`, `CrimeAttachmentLifecycle`, `LegacyPlayerCrimeImporter` |
| Item state | inline item NBT | `state/CrimeDataComponents` |
| Networking | `SimpleChannel` | `CustomPacketPayload` with `network/CrimeStreamCodecs`, `CrimeClientPayloadRouter`, `client/network/CrimeClientPayloadHandler` |
| Client entry point | `client/CrimeClientSetup` and Forge client events | adds `client/McaCrimeClient` (`@Mod(dist = CLIENT)`) and `client/ClientCaches`, one list of every static client cache |
| Mask Station recipe input | a `Container` | `recipe/MaskStationInput` (1.21.1's `RecipeInput`) |
| Enchantments | `Enchantment` subclasses registered through a `DeferredRegister` | data entries under `data/mcacrime/enchantment/` plus enchantment tags |
| Common tags | `data/forge/tags/items/rope.json` | `data/c/tags/item/ropes.json` |
| Resource pack metadata | ships `pack.mcmeta` | ships none; its resources load without one |

## Port-only structure

- **GameTests in `src/main/java/dev/otectus/mcacrime/gametest/`** (18 classes). They compile with the
  main source set and run under `runGameTestServer`, but the `jar` task excludes the package and
  `checkJarContents` fails the build if any of it reaches the jar. The Forge tree has no GameTests; its
  runtime regressions live in the `tools/` production harnesses instead.

## Changes from the 2026-09-28 remediation pass

| Change | Here |
|---|---|
| Bounty contract holders read from persisted quest data, plus login reconciliation | Mirrored |
| Reputation capability handshake refreshed after `/reload` and when stale | Mirrored |
| MCA range widened from `[7.7.36-beta.3+1.21.1]` after probing five 1.21.1 builds | This port only |
| Explicit `v15to16` migration step | Not applicable (schema 15) |
| Per-tick `CrimeWorldSettings` memo | Not applicable (no world rules) |

## Changes from 0.7.7

| Change | Here |
|---|---|
| Established villagers never recruited by an automatic route (`job/RecruitmentEligibility`, `OccupationSource.automatic()`, `Candidate.established`, source-aware `tryAssign`) | Mirrored; GameTest `anEstablishedLibrarianSurvivesAForcedSweep` (Forge: `tools/review`) |
| `dialogue.speechRadius` and the apology-aware `memory/ConversationGate` | Mirrored. The reach check uses 1.21.1's `Player.canInteractWithEntity` where Forge uses `IForgePlayer.canReach`, both with three blocks of padding |
| Murder hearts: `relationship/NativeHeartPolicy`, the charged-mourner ledger, no stacking in `RelationshipConsequences`, `relationship.murderHeartPenalty` and `murderHeartsRequireWitness` | Mirrored, on the newly ported heart hooks, including the Thief exemption row |
| Minor friendly fire: `detect/MinorHarmPolicy`, the `detection.minorHarm` keys, the hurt-heart hold-back | Mirrored, including MCA: Reputation's exemption answer for a forgiven hit through `ReputationExemptionBridge` |
| Thief combat policy (`justice/ThiefCombat*`) and the MCA: Reputation exemption bridge (`compat/ReputationExemptionBridge`), ported with the 2026-10-04 corrections | Mirrored. The policy is the COMMON key `villageJustice.thiefCombatPolicy` only: Forge's world-rule override has no equivalent here (see world-owned settings above). The bridge registered `incident_exemptions_v1` with Reputation 0.6.1 in a NeoForge client run |
| Cell door audit: `CellDoorBlockstateCoverageTest` | Mirrored; plus GameTest `CellDoorPlacementGameTests` (port only) |
| Startup: the cell door and the reinforced bars extend their parents' state definitions (Dramatic Doors' `waterlogged`) | Mirrored; GameTest `theCellDoorAndBarsKeepEveryPropertyTheirParentsHave`, and a client launch with Dramatic Doors 3.3.2 |
| `McaCrimeConfig.clientLoaded()` gating the MCA screen button, player card, name tags and HUD | Mirrored; `McaScreenGateTest` |
| Stolen goods in the search screen: `frisk/StolenGoodsInventoryProvider`, `frisk/StolenGoodsTransfer` | Mirrored, decoding stacks with the registry lookup as 1.21.1 requires; GameTest `aVictimRecoversTheirStolenSwordFromALivingThief`. Forge's copy-before-`ItemStack.of` fix for stored snapshots has no counterpart here: 1.21.1 decodes through codecs into fresh components |
| Death escrow only for non-player thieves (`mug/npc/StolenGoodsRecovery`) | Mirrored; GameTest `aPlayerThiefsDeathEscrowsNothingTheyCarry` |
| Mugging defence: `mug/npc/MuggingThreats`, the victim's interruption, `NORMAL_LAW` excessive force, the guard-alert hook | Mirrored; GameTests `aMuggingVictimMayHitTheMuggerFirstButNotKillUnderNormalLaw`, `aMuggerInCustodyIsProtected` |
| Actor eligibility: age at execution, record-only retirement after an external profession change, `/crime debug thief` | Mirrored; GameTests `onlyACanonicalThiefMayStartOrFinishAMugging`, `aChildWithAThiefRecordCannotMug` |
| Guard targets: a release clears only this mod's assignment, `releaseAllControl` for sleepers | Mirrored; GameTests `aGuardFightsAMonsterAndKeepsItsOwnFight`, `aGuardHurtsAWantedPlayerWhoRefusedArrest`, `sleepingArcherDropsOldOrdersWithoutWakingOrFiring` |
| Sentence ends: `jail/SentenceDisposition`, the sentence Heat obligation, `jail.adminReleaseRecoveryTicks`, guarded release cleanup, the exit fallback, the cuff-escape resume, `/crime status` sentence lines, `jail/JailRecovery` and `/crime repair` | Mirrored; GameTests `servingASentenceSettlesItsHeatButNotTheCellsOwnCrimes`, `theCapServesAdminReleaseForgivesNothingAndAPardonFrees`, `aCellWithItsDoorWalledOffStillHasAWayOut` |
| Breaking out of a `PHYSICAL` jail: the live `jailContainmentMode` (`jail/JailConfine.mode`), no snap-back for an escapee (`JailConfine.Outside`), a built cell's blocks never loot (`ReinforcedPolicy.generatedCellBlock`), a dug-out wall restored on demolition (`CellBuilder.stillOurs`), the validator's `PHYSICAL` warning | Mirrored. `getDrops` is `protected` on 1.21.1 where Forge overrides it as `public`. GameTests `physicalLetsAServingPrisonerDigOutOfAnAssignedJail` and `aBuiltCellDugOutUnderPhysicalDropsNothingAndRestoresTheHole` (Forge: the justice harness) |
| Ransom release: a kidnapping adopts the gear that began it and owns gear added during it (`CustodyTransitionService.adoptGear`, `custodyFor`), a villager's device opened on release, the release before the ransom audit, `RansomService.checkPayment`, an unloaded captive's owed item handed to its applier (`RemovalService.removeUnloaded`) | Mirrored. This port's `EscapeService` keeps no per-restraint memory, so the unloaded removal has no escape step to clear, exactly as the port's ordinary removal has none. GameTests `aPaidRansomTakesOffTheKidnappingsGearAndSettlesOnce` and `aFailedRansomPaymentFreesNobody` |
| Outlaw combat: `enforcement.outlawCombatLegal`, `OutlawStatus.attackLawful` and `killLawful`, `ThiefCombatPolicy.Reason.OUTLAW`, `McaCompat.isOutlaw` | Mirrored; every build in `mca_probe_versions` registers `mca:outlaw`. A player victim is gated by `pvpCountsAsCrime` alone, as everywhere on this port. GameTests `fightingMcasOutlawIsLegalEvenWatched` and `fightingARedPlayerIsLegalWithPvpCrimeOn` |
| Real right-clicks reaching `memory/MemoryInteractionHandler` (found during 0.7.7 runtime validation, not a 0.7.7 change) | **Pending.** Every MCA build in `mca_probe_versions` (7.7.0 to 7.7.36-beta.3) opens its interaction screen from `VillagerEntityMCA.interactAt`, which a real client sends first as INTERACT_AT (`EntityInteractSpecific`). On 1.20.1, MCA 7.7.x delegates `interactAt` to vanilla and opens the screen from `mobInteract`, behind `EntityInteract`, while MCA 7.6.x opens it from `interactAt` as here, so the Forge baseline shares the gap with 7.6.x (bytecode inspection of its fleet; Forge's runtime runs used 7.7.1-beta.2). The handler listens to `EntityInteract` only, so on this port a real right-click opens MCA's screen past the refusal gate and the empty-hand apology gesture; the gate's own decisions are correct when the plain interaction arrives (GameTests, and the two-client run's interaction packets). Routing `EntityInteractSpecific` for MCA villagers through the same handler, with one decision per click, would close it. |

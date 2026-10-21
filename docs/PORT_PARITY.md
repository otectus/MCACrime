# Port parity ledger — MCA: Crime, NeoForge 1.21.1

Every difference between this port and the Forge 1.20.1 tree at `../../MCACrime`, and its status. The
family rule is that this tree mirrors every change made there; anything that does not is listed here as
**pending** (owed, not yet ported), **not ported** (a recorded decision), or a **loader adaptation**
(the same behaviour, built the way 1.21.1 and NeoForge require). First compiled from a file-level
comparison of both working trees on 2026-09-28; re-audited on 2026-10-11 against Forge 0.7.9 (every
source, test, resource and document, with 1.21.1's singular data directories and `neoforge.mods.toml`
mapped onto their Forge names), after which nothing is pending. 0.8.0 was built in both trees together
(2026-10-20, "Changes from 0.8.0" below), and nothing from it is pending. `docs/0.7.5/PARITY.md` describes
how the port implements what it has.

## Numbers

| | Forge 1.20.1 | This port |
|---|---|---|
| `mod_version` | 0.8.0 | 0.8.0 |
| World schema (`CrimeDataMigrations.CURRENT_SCHEMA`) | 18 | 18 |
| Network protocol (`CrimeNetwork.PROTOCOL_VERSION`) | `"21"` | `"21"` |
| MCA range | `[7.6,8)` | `[7.7,8)` |
| MCA probe fleet | six 1.20.1 builds | 7.7.0, 7.7.13, 7.7.22, 7.7.33, 7.7.36-beta.3 |
| GeckoLib | required, 4.8.3 for Forge 1.20.1 | required, 4.8.3 for NeoForge 1.21.1 |

A Forge world cannot reach this port (the Minecraft versions differ), but the guard exists anyway: a
schema newer than the build's own loads read-only and is written back unchanged. Both trees mean the
same thing by every schema number; until 0.7.9 this port stamped 16 without its village-news table,
which is additive, so no step was needed when the table arrived.

## Features ported in 0.7.9

Each was listed here as pending or not ported until 0.7.9.

| Feature (Forge 0.7.5) | Forge sources | Status |
|---|---|---|
| Institutional services and jurisdiction API for MCA: Kingdoms (formerly Ultima Kingdoms) | `api/InstitutionalServiceApi`, `api/JurisdictionPolicyApi`, `api/jurisdiction/*`, `civic/InstitutionalWorkshopPolicy` | **Ported.** Kingdoms is Forge-only today; the API is in the port so a NeoForge Kingdoms can bind it unchanged, and `mcakingdoms`/`ultima_kingdoms` are ordered BEFORE as on Forge (`ModsTomlOrderingTest`). `McaCrimeApi.mayEnforce` carries the 0.7.8 decree basis and the 0.7.9 boundary. |
| Peaceful player crime reports, attempted-robbery receipts, responder dispatch and report screens | `action/handler/ReportCrimeActionHandler`, `report/*`, `client/screen/PlayerReport(s)Screen`, four `network/*Report*` packets | **Ported**, the packets as `CustomPacketPayload` records (`PlayerReportPacketTest` on the stream codecs). |
| Village news through MCA's mailbox (world schema 16) | `news/*`, `compat/McaMailBridge`, `mixin/mca/MailboxMarker` | **Ported.** MCA 1.21.1's inbox holds `PlayerSaveData$Letter` records, so a Crime letter is marked by its title, `mcacrime:news/<edition>`, and its page JSON is parsed against the server's registries at delivery. `McaJusticeMixinPlugin` matches the mailbox members by exact descriptor (identical on all five `mca_probe_versions` builds) before adding the receipt save, load and collection hooks; `McaJusticeTargetTest` checks them on every fleet jar, and GameTest `aNewsLetterReachesMcasMailboxOnceAndItsReceiptSurvivesMcasSave` drives a letter through MCA 7.7.36-beta.3's real mailbox. |
| Native MCA justice layer | `mcacrime.mca.mixins.json`, `mixin/mca/*`, `compat/mca/NativeCombatContext`, `compat/mca/NativeJusticeCapabilities`, `mixin/ThiefGossipMixin` | **Ported.** The heart hooks and guard-alert friend test since 0.7.7; `MailboxMarker` in 0.7.9. |
| World-owned settings as game rules (`/crime rules`) | `config/CrimeGameRules`, `config/CrimeWorldSettings`, `config/CrimeSettingsTransitions`, `command/CrimeRulesCommand`, `mixin/GameRuleTypeAccessor` | **Ported**, with the per-tick settings memo. GameTests that flip a mapped COMMON key call `CrimeWorldSettings.invalidate()`, as Forge's harness does. |
| GeckoLib 3D mask models | `client/render/mask/MaskArmorModel`, `MaskArmorRenderer`, `MaskClientExtensions`, 17 `geo`/`animation` assets | **Ported** with the Forge art. GeckoLib for NeoForge reads the worn model from the item's `GeoRenderProvider` (`client/render/mask/MaskRenderProvider`, installed by `McaCrimeClient`) rather than from `IClientItemExtensions`, and its own armour hook draws the piece once, so Forge's second-draw guard has nothing to suppress. `MaskArmorMaterial` stays one material per style, its layer naming the same texture. |
| `DECEASED` case resolution | `ledger/Resolution`, `ledger/CaseTransitions`, `detect/DeathConsequences` | **Ported.** |

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
| MCA mailbox letters | NBT compounds in `PlayerSaveData.inbox` | `PlayerSaveData$Letter` records, marked by title (see above) |
| GeckoLib worn model | `IClientItemExtensions#getHumanoidArmorModel` | `GeoItem#createGeoRenderer`, through `MaskItem.installRenderProviders` |
| News page JSON | `Component.Serializer.toJson(component)` | the registry-aware form, with `RegistryAccess.EMPTY` when no server is given |
| Sibling API artifacts | vendored API jars pinned by SHA-256 (`gradle/sibling-apis.properties`, `verifySiblingApis`) | compiled against the sibling ports' class directories (`-PmcaReputationClasses`, `-PmcaQuestsClasses`), as `CLAUDE.md` describes; build tooling, no behavioural difference |

## Port-only structure

- **GameTests in `src/main/java/dev/otectus/mcacrime/gametest/`** (24 classes, 125 tests). They compile with the
  main source set and run under `runGameTestServer`, but the `jar` task excludes the package and
  `checkJarContents` fails the build if any of it reaches the jar. The Forge tree has no GameTests; its
  runtime regressions live in the `tools/` production harnesses instead (`tools/review`, `justice`,
  `justice-client`, `capitals`, `gui`, `masks`), which are Forge dedicated-server and client fixtures and
  are not ported; the port's equivalents are GameTests. Forge's 0.8.0 `tools/usability` is ported as its
  `upgrade` suite only, because a world saved by one release and reopened by the next is the one thing a
  GameTest cannot give; the rest of it is `UsabilityGameTests` and `MaskMatrixGameTests` here.
- **`action/McaVillagerClicks`** (0.7.9): one decision per right-click on an MCA villager, for the
  precise-event routing described under "Changes from 0.7.7".

## Documentation

User-facing documents (`README.md`, `CONFIG.md`, `API.md`, `CURSEFORGE.md`, `CHANGELOG.md`,
`docs/MIGRATION.md`, `CREDITS.md`) carry the same content with 1.21.1 wording. Not ported, by decision:
Forge's verification records and plans, which describe that tree's own harness runs
(`docs/compatibility/VILLAGE_JUSTICE.md` and its Reputation patch, `docs/0.7.5/VERIFICATION.md`,
`BASELINE.md`, `PARITY_LEDGER.md`, `CUFFED_INTEGRATION.md`, `PROVENANCE.md`, `docs/0.7.8/IMPLEMENTATION_PLAN.md`
and the implementation plans under `docs/`); `CREDITS.md` points at Forge's provenance manifest, which
covers the art and code both trees share. Forge also still carries three unreferenced block textures
(`toilet.png`, `toilet_top.png`, `tray.png`) that its own provenance manifest records as deleted before
release; they are deliberately not copied here.

## Changes from the 2026-09-28 remediation pass

| Change | Here |
|---|---|
| Bounty contract holders read from persisted quest data, plus login reconciliation | Mirrored |
| Reputation capability handshake refreshed after `/reload` and when stale | Mirrored |
| MCA range widened from `[7.7.36-beta.3+1.21.1]` after probing five 1.21.1 builds | This port only |
| Explicit `v15to16` migration step | Mirrored in 0.7.8 as a stamp; carries the news table from 0.7.9 (`CrimeDataMigrationsV15toV16Test`) |
| Per-tick `CrimeWorldSettings` memo | Mirrored in 0.7.9 with the world rules (`CrimeWorldSettingsTest`) |

## Changes from 0.7.7

| Change | Here |
|---|---|
| Established villagers never recruited by an automatic route (`job/RecruitmentEligibility`, `OccupationSource.automatic()`, `Candidate.established`, source-aware `tryAssign`) | Mirrored; GameTest `anEstablishedLibrarianSurvivesAForcedSweep` (Forge: `tools/review`) |
| `dialogue.speechRadius` and the apology-aware `memory/ConversationGate` | Mirrored. The reach check uses 1.21.1's `Player.canInteractWithEntity` where Forge uses `IForgePlayer.canReach`, both with three blocks of padding |
| Murder hearts: `relationship/NativeHeartPolicy`, the charged-mourner ledger, no stacking in `RelationshipConsequences`, `relationship.murderHeartPenalty` and `murderHeartsRequireWitness` | Mirrored, on the newly ported heart hooks, including the Thief exemption row |
| Minor friendly fire: `detect/MinorHarmPolicy`, the `detection.minorHarm` keys, the hurt-heart hold-back | Mirrored, including MCA: Reputation's exemption answer for a forgiven hit through `ReputationExemptionBridge` |
| Thief combat policy (`justice/ThiefCombat*`) and the MCA: Reputation exemption bridge (`compat/ReputationExemptionBridge`), ported with the 2026-10-04 corrections | Mirrored. Until 0.7.9 the policy was the COMMON key `villageJustice.thiefCombatPolicy` only; the `mcaCrimeThiefCombatPolicy` world rule came with the world-owned settings. The bridge registered `incident_exemptions_v1` with Reputation 0.6.1 in a NeoForge client run |
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
| Real right-clicks reaching `memory/MemoryInteractionHandler` (found during 0.7.7 runtime validation, not a 0.7.7 change) | **Fixed in 0.7.9, port-only.** Every MCA build in `mca_probe_versions` (7.7.0 to 7.7.36-beta.3) opens its interaction screen from `VillagerEntityMCA.interactAt` and answers SUCCESS on both sides, so a real client's click arrives as INTERACT_AT (`EntityInteractSpecific`) alone. On 1.20.1, MCA 7.7.x delegates `interactAt` to vanilla and opens the screen from `mobInteract`, behind `EntityInteract`, where the memory gate, the armed-menu trigger, the Sand Bottle throw and the chain tether listen (MCA 7.6.x opens it from `interactAt`, as here, so the Forge baseline shares the gap on that line). All four now also take `EntityInteractSpecific` for an MCA villager, at their own priorities, and `action/McaVillagerClicks` keeps one decision per click when the plain half also arrives. GameTests `aRealRightClickIsRefusedAtItsPreciseHalfAndDecidedOnce` and `anArmedPreciseClickOpensExactlyOneCrimeMenu` |

## Changes from 0.7.8

| Change | Here |
|---|---|
| MCA prison tags: `mca:iron_bars` gains both reinforced bar blocks, `mca:iron_doors` the cell door, `"replace": false` | Mirrored under `data/mca/tags/block/`; `PrisonTagJsonTest`, `McaPrisonDefinitionProbeTest` over all five `mca_probe_versions` builds, and GameTests `BlueprintPrisonGameTests` (port only), which drive MCA 7.7.36's own scan. MCA 7.7.36 counts the blocks on a room's floor cells rather than its walls, for iron and Crime's blocks alike; the GameTests record both |
| Royal decrees through MCA Capitals: `compat/Capitals*`, `compat/capitals/`, `decree/`, the decree table and stamp-only schema 17, the decree basis in `JusticeService`, the decree fine in `DispositionService` and the guard challenge, the arrest floor and sentence binding, the pursuit gate, the maintenance sweep, `/crime decree`, `/crime debug capitals`, the decree screen and its two payloads, `[capitals]` config and validation, 68 language keys | Mirrored; the same unit suites (`DecreeRulesTest`, `DecreeRecordTest`, `DecreeEnforcementTest`, `DecreeIsolationTest`, `CapitalsBridgeTest`, `CapitalsConfigValidationTest`, `NoCapitalsStaticLinkTest`, `DecreePacketTest` on the payload codecs, and from 0.7.9 Forge's `CrimeDataMigrationsV15toV16Test` and `V16toV17Test` in place of the port's combined test) and `capitalsProbeTest` against NeoForge Capitals 1.3.5 with MCA 7.7.36-beta.3. The probe's "without MCA" case is skipped here, because the unit-test runner has MCA on its classpath. Protocol `"18"` here until 0.7.9, `"20"` since |
| `McaCrimeApi.mayEnforce` treats a decree of the responder's village as a local basis | **Mirrored in 0.7.9**, with the jurisdiction API (see above) |
| Production-server Capitals harness (`tools/capitals`) | **Not ported.** It is a Forge dedicated-server fixture; this port's evidence is the probe, the unit suites and the GameTests. No NeoForge production run with Capitals installed has been made |

## Changes from 0.7.9

| Change | Here |
|---|---|
| Stolen-goods custody: `StolenGoodsRecord.Custody` (LEDGER, INVENTORY, LEGACY), custody-aware `StolenGoodsLedger` claims, escrow and expiry, INVENTORY provenance from `StolenGoodsTransfer` and `SeizureLedger`, schema 18 and `CrimeWorldData`'s legacy classification from online players and `playerdata` saves | Mirrored, keeping the port's registry-aware signatures; `StolenGoodsCustodyTest`, and GameTests `legacyPropertyCustodyIsResolvedFromPlayerIdentities` and the custody assertions in `aVictimRecoversTheirStolenSwordFromALivingThief` (Forge: `tools/capitals` and `tools/review`) |
| Royal decrees stop at their village's boundary (`DecreeService.enforceableHere`, `inForceHere`), in guard challenges, arrest and `McaCrimeApi.mayEnforce` | Mirrored. Exercised on Forge by `tools/capitals` against a live Capitals; this port has no Capitals runtime fixture, so the shared logic is covered by the decree unit suites and remains manual acceptance here, as in 0.7.8 |
| Paying a decree in custody releases a sentence held solely by it (`DecreeService.pay` now runs `liftIfSoleReason`, as withdrawal and voiding do); the arrest path reads `DecreeService.inForceHere` | Mirrored; manual acceptance here, as above |
| Physical-state delta, removal and rig reach the wearer as well as trackers | Mirrored as `PacketDistributor.sendToPlayersTrackingEntityAndSelf`; GameTest `aRestrainedPlayerReceivesTheirOwnUpdates` |

## Changes from 0.8.0

Built in both trees together; `docs/0.8.0/VALIDATION.md` in each records the evidence.

| Change | Here |
|---|---|
| Command targeting: `action/CrimeTargetResolver`, the corrected 4-block gameplay ray in `CrimeActionService.rayTraceLiving`, the operator commands' crosshair-or-single-visible rule in a true 8-block sphere, the typed refusals, `/crime debug target` | Mirrored; `CrimeTargetResolverTest` and the targeting GameTests. They aim at a pig and an MCA zombie villager for the wrong kinds, because the GameTest server converts vanilla villagers; Forge's harness turns MCA's conversion off instead |
| Guillotine and pillory: assembly from the items, `detention/DeviceFeedback`, a fall that cannot borrow a newer execution order, a blade longer than the window still striking inside its order, the corrected descriptions of `executionDelayTicks` and `guillotineActivationDelayTicks` | Mirrored; `ExecutionTimingTest` and the guillotine GameTests |
| Keys, rings and molds: the blank-mold impression (`recipe/lock/KeyMoldImprintRecipe`, serializer `mcacrime:key_mold_imprint`), names that ride a ring, the clearer refusals | Mirrored. A ring's names are the `mcacrime:key_ring_labels` component, keyed by lock, where Forge writes a `CustomName` into each `Keys` entry. 1.21.1 refuses an item stack carrying a component it does not know, so a ring holding a renamed key would not survive a swap back to the 0.7.9 jar; `docs/MIGRATION.md` says to roll back from the backup. `KeyWorkflowTest` keeps seven of Forge's eight cases; the eighth reads a real ring stack, so it is GameTest `replacingAStaleKeyOnARingAfterARekeyKeepsItsName` |
| Masks: the 272-stack catalogue and the creative Masks tab, colour names, cauldron washing (`mask/MaskWashing`), dyed art with an untinted detail layer (`client/render/mask/MaskArt`, the `mcacrime:dyed` item property), the station's preview lines and help button | Mirrored on `DataComponents.DYED_COLOR` (`mask/MaskColors`), where Forge reads `display.color`; 1.21.1's dye recipe and cauldron interaction also need the masks in `#minecraft:dyeable`, which this port already shipped. `MaskMatrixGameTests` (14) stand in for Forge's `MaskChecks`; the contact sheets come from the same `tools/gameplay` roles |
| `/crime help` and its topics, `help/TooltipHelp`, the `mcacrime:server_rules` payload (protocol `"21"`), recipe-book unlock advancements for the 32 fixed recipes | Mirrored, the advancements under 1.21.1's `advancement/recipes/`; `CrimeHelpPagesTest`, `RecipeUnlockCoverageTest`, GameTest `pickingUpAnIronNuggetUnlocksTheKeyPadlockAndLockpickRecipes` |
| Forge's `bootstrapTest` task, which runs `MaskColorsTest` in its own JVM | **Not needed.** This port's `MaskColorsTest` bootstraps inside the shared `test` JVM |

## Drift closed on 2026-10-11

Behaviour the Forge tree had gained after the port last mirrored it, found by the 0.7.9 audit and ported
with it.

| Change | Here |
|---|---|
| First-key initialization: `LockRecord.keyInitialized`, `LockService.initializeKey`/`mayInitializeKey`, the canonical two-block lock and its member migration (`atBlockMembers`), the interaction path that issues the first key | Mirrored; `FirstKeyInitializationTest`. This fixes the "newly placed padlock refuses its first blank key" defect recorded in `docs/0.7.7/VALIDATION.md` |
| Bunk respawn per custody episode: the persisted snapshot in player data (`bunkRespawn`), login reconciliation and jail-release fallback | Mirrored; `BunkSnapshotPersistenceTest` and GameTest `aBunkReturnsTheOldSpawnOnlyWhileItStillOwnsIt`. The port's `BunkBlock` also gained NeoForge's `isBed`, `getBedDirection`, `getRespawnPosition` and `setBedOccupied`, without which a sleeper was woken the next tick |
| Per-restraint escape memory (`EscapeService.forget` by instance and by player) | Mirrored |
| `MountTransfer.trustedTransfer`, honoured by the restraint mount gate | Mirrored |
| Escort tether moved with custody and renewed rather than stacked | Mirrored |
| `ImbueDamage.isImbue`, honoured by the restraint attack gate | Mirrored |
| `NpcArrestService.arrestableSuspect` gating NPC criminal pursuit | Mirrored |
| Village security counting only identified reports accepted in that community | Mirrored |
| `CrimePublicView` publishing an identified accepted report to its receiving authority, wilderness included | Mirrored; Forge's two `PublicProjectionTest` cases |
| The arrest-phase reconciliation no longer writes the custody row it may not have; the legacy conversion clears it instead | Mirrored |
| Restraint restrictions read the detention profile (`RestraintService.policy`) | Mirrored |
| Language (80 keys, four values), mask, padlock, duct tape and Sand Bottle art, the Domino's 3D item model, the Safe's loot table without an explosion-survival roll | Mirrored |

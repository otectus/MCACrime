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
| `mod_version` | 0.7.5 | 0.7.5 |
| World schema (`CrimeDataMigrations.CURRENT_SCHEMA`) | 16 | 15 |
| Network protocol (`CrimeNetwork.PROTOCOL_VERSION`) | `"18"` | `"16"` |
| MCA range | `[7.6,8)` | `[7.7,8)` |
| MCA probe fleet | six 1.20.1 builds | 7.7.0, 7.7.13, 7.7.22, 7.7.33, 7.7.36-beta.3 |
| GeckoLib | required | not declared |

A world stamped 16 by the Forge build cannot reach this port (the Minecraft versions differ), but the
guard exists anyway: a schema newer than the build's own loads read-only and is written back unchanged.

## Features the port does not have

| Feature (Forge 0.7.5) | Forge sources | Status |
|---|---|---|
| Institutional services and jurisdiction API for Ultima Kingdoms | `api/InstitutionalServiceApi`, `api/JurisdictionPolicyApi`, `api/jurisdiction/*`, `civic/InstitutionalWorkshopPolicy` | **Not ported.** Ultima Kingdoms, its only consumer, is Forge-only. |
| Peaceful player crime reports, attempted-robbery receipts, responder dispatch and report screens | `action/handler/ReportCrimeActionHandler`, `report/*`, `client/screen/PlayerReport(s)Screen`, four `network/*Report*` packets | **Pending.** |
| Village news through MCA's mailbox (world schema 16) | `news/*`, `compat/McaMailBridge`, `mixin/mca/MailboxMarker` | **Pending.** Porting it moves this tree to schema 16 with the explicit `v15to16` step. |
| Native MCA justice layer: descriptor-gated mixins into MCA's heart, blame, standing and mailbox sites | `mcacrime.mca.mixins.json`, `mixin/mca/*` (`McaJusticeMixinPlugin` and three markers), `compat/mca/NativeCombatContext`, `compat/mca/NativeJusticeCapabilities`, `mixin/ThiefGossipMixin` | **Pending.** Every target must be probed against the 1.21.1 fleet before it ships. |
| Thief combat policy and its MCA: Reputation exemption | `justice/ThiefCombat*`, `compat/ReputationExemptionBridge` | **Pending.** The Reputation port already offers `incident_exemptions_v1`; nothing here registers against it. |
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

- **GameTests in `src/main/java/dev/otectus/mcacrime/gametest/`** (17 classes). They compile with the
  main source set and run under `runGameTestServer`, but the `jar` task excludes the package and
  `checkJarContents` fails the build if any of it reaches the jar (the built `mcacrime-0.7.5.jar` has
  none). The Forge tree has no GameTests.

## Changes from the 2026-09-28 remediation pass

| Change | Here |
|---|---|
| Bounty contract holders read from persisted quest data, plus login reconciliation | Mirrored |
| Reputation capability handshake refreshed after `/reload` and when stale | Mirrored |
| MCA range widened from `[7.7.36-beta.3+1.21.1]` after probing five 1.21.1 builds | This port only |
| Explicit `v15to16` migration step | Not applicable (schema 15) |
| Per-tick `CrimeWorldSettings` memo | Not applicable (no world rules) |

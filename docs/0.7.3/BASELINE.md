# 0.7.3 implementation baseline (NeoForge 1.21.1 port)

The checkout this release's work started from, and what was already true of the MCA: Reputation
integration before any of it changed. The Forge 1.20.1 line adopted MCA: Reputation 0.6.0 first
(`05953b0` on `feature/reputation-0.6.0`); this port applies the same adoption against its own
source. Where the two disagree, this port's source is what these notes describe.

## Checkout at the start of the adoption (2026-09-16)

| | |
|---|---|
| Branch | `neoforge/1.21.1`, created from `master` at the start of this work; `master` left where it was |
| HEAD at branch point | `b495e5f` (Bring MCA Crime 0.7.2 to full parity with the Forge 1.20.1 baseline), identical to `origin/neoforge/1.21.1` |
| Worktree | clean |
| `mod_version` | `0.7.2` → `0.7.3`, in `gradle.properties` only |
| Loader | NeoForge 1.21.1, ModDevGradle, Java 21, official mappings — all pinned in `gradle.properties` |
| Companion compiled against | MCA: Reputation NeoForge 0.6.0, from a stable snapshot of its `build/classes/java/main`, passed as `-PmcaReputationClasses=<dir>` because the sibling `../MCAReputation_1.21.1` was being rebuilt concurrently |
| Companion API generation | **2** (`McaReputationApi.API_VERSION`, read with `javap` from the snapshot) |
| Quests compiled against | MCA: Quests NeoForge 1.6.6, `../MCAQuests_1.21.1/build/classes/java/main`, passed as `-PmcaQuestsClasses=<dir>` |
| Test tasks | JUnit 5 under ModDevGradle's NeoForge runner (`build/minecraft-junit`); `check` and `build` both run `checkJarContents` |

Neither companion checkout was edited and no Gradle task was run in either. The Forge MCA: Crime
checkout was read only — its commit, message and per-file diffs — and nothing in it was modified.

## What the integration looked like at `b495e5f`

The API gate, which is this port's own defect rather than an inherited one:

| Finding | Where |
|---|---|
| `REQUIRED_API_VERSION = 1`, compared for exact equality against `McaReputationApi.getApiVersion()`, which on this loader has returned `2` since MCA: Reputation 0.4.1. Every NeoForge companion from 0.4.1 onwards therefore took the `ops = null; status = "incompatible API v2"` branch: the integration was off for this entire line, silently apart from one error line, with village standing kept in Crime's own store | `compat/ReputationBridge.java:35` (constant), `:106-114` (the comparison) |
| `MINIMUM_COMPANION_VERSION = "0.2.0"`, the human-facing hint in the too-old log line, named a companion older than the surface this adapter actually needs | `compat/ReputationBridge.java:54` |

The five findings inherited from the Forge baseline, at the lines they were on here:

| Finding | Where |
|---|---|
| The delivery result was reduced to `Optional<UUID>`, so six different answers arrived as one empty optional | `compat/ReputationOps.java:39-41`, `compat/reputation/CrimeReputationCompat.java:147-183` |
| The pump then guessed which answer it had been given: empty plus authority held → `UNKNOWN_TARGET`, empty plus authority lost → `UNAVAILABLE`, `false` → `TRANSIENT_FAILURE` | `integration/CrimeIntegrationPump.java:182-213` |
| A lost incident link was recovered by **recording a synthetic `mcareputation:villager_assaulted`** and reading the duplicate refusal | `compat/reputation/CrimeReputationCompat.java:186-204` |
| `CrimeCoreAuthority.owns(CoreIncidentKind)` ignored its argument, claiming all six core kinds | `compat/reputation/CrimeReputationCompat.java:295-300` |
| Negotiation was an API-version equality check and nothing else; `capabilities()` was never called | `compat/ReputationBridge.java:99-117` |

Also true at baseline, and changed here:

- A resolution whose case had no companion-side link yet was dropped
  (`integration/CrimeIntegrationHooks.java:127`).
- `onCommitted` never looked at `offender_kind`, so an NPC offender's case was delivered as a
  player's civic deed (`integration/CrimeIntegrationHooks.java:81-114`).
- The eight shipped incident definitions under `data/mcacrime/mcareputation/incidents/` carried no
  `social_profile`, so every crime this mod files produced zero recognition and zero facet evidence
  under MCA: Reputation 0.6.0.

## What was already correct and was preserved

- The optional-classloading seam: `ModList.isLoaded` plus `Class.forName` into
  `compat/reputation/CrimeReputationCompat`, which stays the only file in the mod that imports the
  companion, with `OptionalClassloadTest` enforcing it by byte-scanning the compiled output. The four
  files this release adds outside that package (`compat/ReputationDelivery`,
  `compat/ReputationCapabilitySnapshot`, `compat/CrimeAuthorityPolicy`, `integration/SupersedePolicy`)
  name only Crime-local and Minecraft types, which is what keeps them always loadable.
- The `neoforge.mods.toml` optional dependency stays `mcareputation` `type="optional"`
  `versionRange="[0.2,)"`. A range that gated the game load over an optional integration would be a
  worse outcome than running without it; the shortfall is handled at runtime instead. Raising the API
  gate does not change it.
- Crime's own dedupe keys (`crime:<recordId>`, `crime-resolution:<recordId>:<revision>`) are
  unchanged, so no operation already sitting in a player's outbox changes identity across the
  upgrade. They are now also used as the delivery operation key.
- The outbox contract: enqueue in the same dirty cycle as the crime, deliver later, and never call
  into an optional mod while loading saved data.
- Assault and killing continue to reuse `mcareputation:villager_assaulted` and
  `mcareputation:villager_killed` rather than minting `mcacrime:` duplicates.

## Loader differences this port re-expressed

The Forge adoption's always-loadable seam files were byte-identical to what this tree needs apart
from one import each; the divergent files were re-expressed against this tree's own version rather
than patched blind.

| Forge form | This port |
|---|---|
| `javax.annotation.Nullable` | `org.jetbrains.annotations.Nullable` (`ReputationDelivery`, `ReputationOps`, `ReputationBridge`, `CrimeIntegrationPump`, `CrimeReputationCompat`) |
| `ForgeConfigSpec.IntValue` | `ModConfigSpec.IntValue` for `reputationSupersedeWindowTicks` |
| `TickEvent.ServerTickEvent` at `Phase.END` | `ServerTickEvent.Post` |
| `@Mod.EventBusSubscriber` | `@EventBusSubscriber` (`net.neoforged.fml.common`) |
| `net.minecraftforge.server.ServerLifecycleHooks` | `net.neoforged.neoforge.server.ServerLifecycleHooks` |
| `new ResourceLocation(ns, path)` | `ResourceLocation.fromNamespaceAndPath` (test fixtures) |
| `Paths.get("src", "main", "resources", …)` | `TestPaths.resources(…)`, because the NeoForge JUnit runner does not run from the project directory |
| `CrimeWorldData.load(tag)` / `save(tag)` | the `RegistryAccess`-carrying overloads this tree already uses |

The datapack files under `data/mcacrime/mcareputation/**` are byte-identical to the Forge line: MCA:
Reputation's own loader reads them out of this jar, and they are untouched by the 1.21
`recipe/`/`loot_table/` directory renames, which only affect vanilla-owned directories.

## Scope boundary taken for this release

`ReputationProfileChangedEvent` is deliberately **not** subscribed to. This mod has no consumer for a
profile change: it decides guilt, sentencing and bail from its own ledger, and adding a listener with
nothing behind it would be an unused subscription on the game event bus. Reading profiles for
gameplay — guards reacting to a known violent reputation, for instance — is a design decision, not an
adoption step, and is not made here.

# MCA: Crime - NeoForge 1.21.1 Mod

## Orientation

This is the NeoForge 1.21.1 port of MCA: Crime. It is a separate project from the Forge 1.20.1 baseline. The source layout is documented in the **Structure** block below (`.mcmod-tools/modmap.py` does not work on NeoForge).

## Quick Reference

- **Mod ID**: `mcacrime` (display name `MCA: Crime`)
- **Package**: `dev.otectus.mcacrime` - main class `McaCrime.java`
- **MC / NeoForge / Java / mappings**: see `gradle.properties` (mappings are `official`)
- **Baseline**: NeoForge 1.21.1 / ModDevGradle (plugin version pinned in build.gradle) / Java 21

Version numbers live only in `gradle.properties`; `processResources` expands them into `neoforge.mods.toml` (NeoForge synthesizes pack metadata). Never hard-code one elsewhere, this file included.

## Build

Use `C:\Projects\.mcmod-tools\gradlew-quiet.ps1 -Project "<project-dir>" -Task <task>` rather than a bare `./gradlew`.

- `compileJava` - the normal iteration loop
- `test` - the JUnit 5 suite (runs under ModDevGradle's NeoForge runner from `build/minecraft-junit`)
- `build` - also runs `checkJarContents`, which fails if an MCA, Reputation, Architectury, or Forge class was shaded into the jar

## Structure

```
McaCrime / McaCrimeConfig  mod entrypoint and the whole config spec, at the package root
api        public facade, events, and model records for companion mods - keep it stable
crime engine state ledger memory   core value types, decay, cases, villager memory
detect     classification, witness selection, community resolution
enforcement jail captivity ransom  arrest, guards, restraints, cells, custody, kidnapping
action ai dialogue economy mug relationship loot  gameplay behaviour and outcomes
job        criminal occupation system (Thief, Fence); Thief is a native profession with a worksite
block      the Mask Station block and its registry; a village point of interest, no block entity
menu       the Mask Station container menu, its layout, selection policy and denied click routes
recipe     the mcacrime:mask_making recipe type, its JSON contract, craft plan and catalogue
mask       mask concealment, deferred Heat, restyling and data-component transfer
effect     sand blindness, exposure policy, recovery ledger and the sand incident path
entity     the thrown Sand Bottle projectile and its registry
bounty     warrant tracking, bounty payouts, claim ledgers, contract board
ai/thief   autonomous thief controller, target selection, guard evasion
mug/npc    NPC mugging sessions, theft planning, stolen-goods recovery
economy/fence  contraband pricing, goods registry, trading UI
compat integration locksreforged mcaquests numismatic  optional companions; degrade at runtime
client mixin/client  client-only; common code must never import these
mixin      seven common vanilla-only mixins: equipment capture, Thief worksite and brain, sand sight
network item audio command config util  plumbing
```

## Key Dependencies

- **MCA Reborn** - mandatory at runtime, but `localRuntime` and `testRuntimeOnly` in Gradle; no MCA type may appear anywhere in `src/main/java` (enforced by `NoMcaStaticLinkTest`). Every MCA class and member is resolved by name at runtime by `compat/mca/McaBinding`, so one jar works across MCA's package-root migrations.
- **MCA: Reputation** - optional companion, resolved by name at runtime. Compiles only when `../MCAReputation_1.21.1/build/classes/java/main` exists; override that path with `-PmcaReputationClasses=<dir>` to build against a snapshot instead of a sibling checkout. Pass `-PrequireReputation=true` to force the build to fail if it is absent.
- **MCA: Quests** - optional integration, resolved by name at runtime. Publishes bounties as guard-given contracts. Compiles only when `../MCAQuests_1.21.1/build/classes/java/main` exists; override that path with `-PmcaQuestsClasses=<dir>`. Pass `-PrequireQuests=true` to force the build to fail if it is absent.
- **Locks Reforged** - optional fence pricing and native cuff lockpicking. The isolated cuff menu compiles against `../Locks_Reforged_1.21.1/build/classes/java/main` (override with `-PlocksClasses=...`); release builds must set `-PrequireLocks=true`. Runtime presence is checked before loading the adapter. `-PlocksRuntimeJar=...` enables real-mod integration runs without bundling it.
- **Architectury** - MCA's own runtime requirement; deliberately not declared in `neoforge.mods.toml`.

## Conventions

- Registration is imperative on the MOD bus in the `McaCrime` constructor. DeferredRegisters for items and creative tabs are attached in `item/CrimeItems.register`; villager professions are registered in `job/CriminalProfessions.register`. Gameplay handlers are `@EventBusSubscriber` (inherits FORGE bus) or on the mod bus. NeoForge uses a different annotation system; check the imports (`net.neoforged.*`).
- Config is hand-written `ModConfigSpec`, **COMMON + CLIENT only, no SERVER spec**: common is server-authoritative, client is presentation only. `config/ConfigValidator` runs at setup and on every reload. 0.7.2 adds the common `[maskStation]` (`enableMaskStationCrafting`, `enableMaskRestyling`) and `[sandBottle]` sections, `memory.emptyHandApologyMode`, and client `sandParticles`.
- Eight narrowly scoped mixins, every one of them on a vanilla class. Seven common:
  `MobDeathEquipmentMixin` (`Mob.setItemSlot`, HEAD) observes equipment before MCA clears a dying
  villager's; `MaskStationAcquisitionMixin` (`VillagerProfession.acquirableJobSite`, RETURN) hides the
  Mask Station from every profession except Thief; `NativeJobAssignmentMixin`
  (`AssignProfessionFromJobSite.create`, RETURN) routes Mask Station assignment through Crime;
  `ThiefPoiValidationMixin` (`ValidateNearbyPoi.create`, RETURN) defers validation on uninspectable
  chunks; `ThiefBrainMixin` (`Brain.tick`, HEAD) wraps this brain's `Activity.WORK` entries for
  employed Thieves; `MerchantOffersAccessor` (`AbstractVillager.offers`) is a field accessor with no
  behaviour; `SandSensingMixin` (`Sensing.hasLineOfSight`, HEAD) enforces sand blindness before the
  cached-positive return. One client-only: `mixin/client/RestraintPoseMixin`
  (`LivingEntityRenderer.render`) poses restrained arms. `MixinConfigTest` verifies side separation and
  registration. No MixinExtras.
- All MCA access is `MethodHandle` lookups in `compat/mca/McaBinding` behind the `compat/McaCompat` facade, because MCA's package root has moved between releases; missing members degrade to stubs. `NoMcaStaticLinkTest` fails the build if static linkage returns, and `McaBindingProbeTest` replays the binding against every jar in `mca_probe_versions`.
- Optional integrations must degrade at runtime - `ModList.isLoaded` plus `Class.forName` into an isolated adapter (`compat/ReputationBridge` -> `compat/reputation/`) with an API-version handshake, never a `neoforge.mods.toml` range that would gate the game load.
- `compat/EpicFightCompat` detects Epic Fight and its three MCA bridges - "MC-Epicly-A" (`mcea`), "EpicFight-MCA Patch" (`efmca`) and "MCA Skin x Epic Fight Compatibility" (`mcaefcompat`) - by id only; no type of any of them is named anywhere in the mod. `mcea` and `efmca` each set the blocked-damage verdict, `mcaefcompat` is reported only. Client-only `client/EpicFightInteractShim` forwards an interaction Epic Fight's battle mode cancelled at the use key, so MCA: Crime's menu (armed or restraint right-click) still opens; MCA's own screen is left to Epic Fight; `mcea`'s unconditional damage block on MCA villagers has no config that lifts it and is only reported, not worked around.
- MCA is on `localRuntime` and `testRuntimeOnly` (build.gradle ~123-133) because ModDevGradle's unit-test runner must boot the mod loader with MCA present; MCA is absent only from `compileClasspath` and `testCompileClasspath`; `NoMcaStaticLinkTest` and `OptionalClassloadTest` verify bytecode references, not runtime absence, and `McaBindingProbeTest` uses a child-first classloader so each probe jar is genuinely the version tested.

## Testing

- Unit tests run under ModDevGradle's NeoForge JUnit runner from `build/minecraft-junit`, so tests resolve project paths through the `mcacrime.projectRoot` system property (set in `build.gradle`).
- `NoMcaStaticLinkTest` ensures no compiled class references an MCA package root (checks bytecode constant pool).
- `DedicatedServerIsolationTest` ensures `CrimeNetwork` and `CrimeClientPayloadRouter` do not name a client class (prevents client-class resolution on dedicated servers).
- GameTests (when present) live in `src/main/java/dev/otectus/mcacrime/gametest/` and are excluded from the production jar by `build.gradle`.

## Compatibility Invariants

From spec §2.1, these must never change:

- Mod ID: `mcacrime`
- Data attachment ID: `mcacrime:player_crime`
- SavedData name: `mcacrime` (world-level data file key)
- World schema is defined by `CrimeDataMigrations.CURRENT_SCHEMA`; preserve every older migration and unknown-data quarantine.
- Config keys: common and client sections, exact keys stable across patches
- Packet protocol is defined by `CrimeNetwork.PROTOCOL_VERSION`; clients and server must match. Forge clients cannot join this NeoForge port.

## Tooling Notes

- `.mcmod-tools/check_mod.py` and `.mcmod-tools/modmap.py` are Forge-1.20.1-only and will report NeoForge idioms as errors. **Do not run them on this project.**
- `.mcmod-tools/find_api.py --project "<project-dir>" --class <Class> --symbol <method>` works and queries the cached NeoForge 1.21.1 sources.
- `gradlew-quiet.ps1` wraps `./gradlew` with error-only output and a result line.

## Packaging

- Production JAR is checked by `checkJarContents` task in build.gradle. Fails if MCA, Reputation, Architectury, Forge, test, or generated cache classes are bundled.
- Client code stays under `client/` and common code must never import it (enforced by `DedicatedServerIsolationTest`).
- Every payload and S2C handler uses NeoForge's common-side `CustomPacketPayload` API; client routing happens through `CrimeClientPayloadRouter`, which is named only from common code (not imported).

## Terminology

- `Band.BLUE / GREY / RED` are shown to players as Lawful / Neutral / Outlaw.
- `CrimeCommunityKey` (`minecraft:overworld/0`) is never shown; `enforcement/Jurisdictions` turns it into a place name.

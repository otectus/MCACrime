# MCA: Crime - NeoForge 1.21.1 Mod

## Orientation

This is the NeoForge 1.21.1 port of MCA: Crime. It is a separate project from the Forge 1.20.1 baseline. The source layout is documented in the **Structure** block below (`.mcmod-tools/modmap.py` does not work on NeoForge).

## Quick Reference

- **Mod ID**: `mcacrime` (display name `MCA: Crime`)
- **Package**: `dev.otectus.mcacrime` - main class `McaCrime.java`
- **MC / NeoForge / Java / mappings**: see `gradle.properties` (mappings are `official`)
- **Baseline**: NeoForge 1.21.1 / ModDevGradle (plugin version pinned in build.gradle) / Java 21

Version numbers live only in `gradle.properties`; `processResources` expands them into `neoforge.mods.toml` and `pack.mcmeta`. Never hard-code one elsewhere, this file included.

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
job        criminal occupation system (Thief, Fence); persisted, optionally MCA-visible
bounty     warrant tracking, bounty payouts, claim ledgers, contract board
ai/thief   autonomous thief controller, target selection, guard evasion
mug/npc    NPC mugging sessions, theft planning, stolen-goods recovery
economy/fence  contraband pricing, goods registry, trading UI
compat integration locksreforged mcaquests  optional companions; degrade at runtime
client mixin  client-only; common code must never import these
network item audio command config util  plumbing
```

## Key Dependencies

- **MCA Reborn** - mandatory at runtime, but `localRuntime` and `testRuntimeOnly` in Gradle; no MCA type may appear anywhere in `src/main/java` (enforced by `NoMcaStaticLinkTest`). Every MCA class and member is resolved by name at runtime by `compat/mca/McaBinding`, so one jar works across MCA's package-root migrations.
- **MCA: Reputation** - optional companion, resolved by name at runtime. Compiles only when `../MCAReputation_1.21.1/build/classes/java/main` exists. Pass `-PrequireReputation=true` to force the build to fail if it is absent.
- **MCA: Quests** - optional integration, resolved by name at runtime. Publishes bounties as guard-given contracts. Compiles only when `../MCAQuests_1.21.1/build/classes/java/main` exists. Pass `-PrequireQuests=true` to force the build to fail if it is absent.
- **Locks Reforged** - optional integration for fence lock/pick pricing tiers. Resolved at runtime by registry ID lookups; no 1.21.1 port exists at this time.
- **Architectury** - MCA's own runtime requirement; deliberately not declared in `neoforge.mods.toml`.

## Conventions

- Registration is imperative on the MOD bus in the `McaCrime` constructor. DeferredRegisters for items and creative tabs are attached in `item/CrimeItems.register`; villager professions are registered in `job/CriminalProfessions.register`. Gameplay handlers are `@EventBusSubscriber` (inherits FORGE bus) or on the mod bus. NeoForge uses a different annotation system; check the imports (`net.neoforged.*`).
- Config is hand-written `ModConfigSpec`, **COMMON + CLIENT only, no SERVER spec**: common is server-authoritative, client is presentation only. `config/ConfigValidator` runs at setup and on every reload.
- Exactly one mixin: `mixin/client/RestraintPoseMixin`, targeting `LivingEntityRenderer.render`, one `@Inject` at AFTER `EntityModel.setupAnim` call, guarded by `MixinConfigTest`. **MixinExtras is neither declared nor used** - no `@WrapOperation`.
- All MCA access is `MethodHandle` lookups in `compat/mca/McaBinding` behind the `compat/McaCompat` facade, because MCA's package root has moved between releases; missing members degrade to stubs. `NoMcaStaticLinkTest` fails the build if static linkage returns, and `McaBindingProbeTest` replays the binding against every jar in `mca_probe_versions`.
- Optional integrations must degrade at runtime - `ModList.isLoaded` plus `Class.forName` into an isolated adapter (`compat/ReputationBridge` -> `compat/reputation/`) with an API-version handshake, never a `neoforge.mods.toml` range that would gate the game load.
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
- World file schema version: 7 (allows schema migrations, not rewrites)
- Config keys: common and client sections, exact keys stable across patches
- Packet protocol version: 8 (1.20.1 clients cannot join)

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

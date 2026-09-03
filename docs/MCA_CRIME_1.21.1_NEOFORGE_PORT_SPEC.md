# MCA: Crime — Forge 1.20.1 to NeoForge 1.21.1 Port Specification

> Implementation brief for a coding agent. This document is intentionally prescriptive: follow the phases in order, keep the compatibility gates, and do not declare the port complete merely because it compiles.

## 1. Scope, baseline, and target

This specification covers a **full port** of [otectus/MCACrime](https://github.com/otectus/MCACrime) from Minecraft 1.20.1 Forge to Minecraft 1.21.1 NeoForge. It is based on repository commit [`abcea64879655307a617d90025cd434950a845d9`](https://github.com/otectus/MCACrime/tree/abcea64879655307a617d90025cd434950a845d9), the `main` branch head inspected on 2026-09-03.

The reference tree contains 408 tracked files, including 264 production Java files, 58 test Java files, 61 resources, and 435 JUnit `@Test` methods. The port therefore includes build tooling, loader APIs, persistence, networking, resources, client rendering, MCA integration, optional MCA: Reputation integration, documentation, automated tests, and real-game validation.

Use this target matrix unless the maintainer deliberately approves a newer patch baseline before work starts:

| Component | Target | Policy |
|---|---:|---|
| Minecraft | `1.21.1` | Exact; metadata range `[1.21.1]` |
| NeoForge | `21.1.249` | Compile/dev pin; runtime minimum `[21.1.249,)` |
| ModDevGradle | `2.0.146` | Replace ForgeGradle and the Sponge Gradle plugin |
| Java | `21` | Toolchain and bytecode target |
| Gradle wrapper | `9.2.1` | Match the inspected official 1.21.1 MDK |
| Parchment | `2024.11.17` for MC `1.21.1` | Use for readable parameters and Javadocs |
| MCA Reborn | release 7.7.36; artifact/tag `7.7.36-beta.3+1.21.1` | Runtime and binding-probe baseline; use an exact initial metadata range |
| MCA: Crime | next port release | Do not reuse `0.5.0` as though the loader/MC change were invisible |
| Network protocol | `7` | Bump from Forge SimpleChannel protocol `6` |

The NeoForge and tool versions above come from the official 1.21.1 MDK at commit `70d335c962ee8a773b38fb0690c7e7f30d1bafa6`. The MCA target tag resolves to commit `80bfe7d0edc06d6e6cb7363321aace316752ad65`; its declared Minecraft, Parchment, ModDevGradle, and NeoForge baselines were inspected along with the binding members in §12.1. Pin first; update dependencies only after the port is green so dependency churn is not mixed with migration work.

### 1.1 What “fully ported” means

The port is done only when all of the following are true:

- `./gradlew clean check build` passes on JDK 21 from a fresh checkout.
- The client dev run starts with the target MCA NeoForge build, reaches a world, and exercises every MCA: Crime screen, HUD element, keybinding, render layer, and MCA interaction hook.
- The dedicated-server dev run starts without loading any `net.minecraft.client.*` class and two real clients can connect.
- A production, remapped MCA: Crime JAR starts in a clean NeoForge 1.21.1 instance with a production MCA JAR. Dev-only success is insufficient.
- A copied 1.20.1 Forge world retains all MCA: Crime player and world state after conversion: karma, heat, arrests, warrants/cases, jail state, observations/reports, captives, holding cells, ransom state, reputation bridge operations, and configuration values.
- Fresh-world persistence, death/respawn, dimension transfer, disconnect/reconnect, and server restart all preserve intended state.
- All 15 existing packets have bounded codecs, correct direction registration, wrong-side protection, and server-authoritative validation.
- Datapacks reload successfully; all three recipes craft; all item tags resolve under 1.21.1 paths and `c:` conventions.
- No Forge API, ForgeGradle, obsolete capability, old SimpleChannel, old resource path, or Architectury residue remains, except literal strings used by a documented legacy-data importer or historical documentation clearly marked as such.
- Public API and user documentation describe NeoForge 1.21.1 accurately.

### 1.2 Non-goals

Do not redesign crime balance, rewrite the server-authoritative domain model, replace reflection-based MCA compatibility with static MCA linkage, change persistent IDs, or opportunistically refactor unrelated gameplay while porting. Behavioral changes make regression diagnosis and save compatibility harder. Record desirable redesigns as follow-up issues.

## 2. Repository architecture and migration risk

The domain layer is already sensibly separated from loader code. Preserve that structure. The largest migration surfaces are:

| Surface | Current implementation | 1.21.1 target | Risk |
|---|---|---|---|
| Build/loader | ForgeGradle 6, Forge 47.4.10, Java 17 | ModDevGradle, NeoForge 21.1.249, Java 21 | High |
| Player state | Forge capability `mcacrime:player_crime` | NeoForge data attachment with legacy import | Critical: world loss if mishandled |
| World state | `SavedData` schema 6 | Updated 1.21.1 signatures, same file/key | High |
| Networking | `SimpleChannel`, numeric discriminators | `CustomPacketPayload`, `StreamCodec`, payload registrar | Critical: 15 packets |
| Events | Forge buses, tick phases, cancellable annotation | NeoForge buses, Pre/Post tick classes, `ICancellableEvent` | High |
| Client | old GUI overlays, screen/list signatures, vertex API | GUI layers, `DeltaTracker`, new list/render APIs | High |
| Resources | plural recipe/tag folders, `forge:` tags | singular folders, `c:` tags, result `id` | Medium |
| MCA integration | reflective multi-root binding | retain reflection; probe 1.21.1 MCA | High |
| Optional Reputation | sibling 1.20.1 class output | require a matching 1.21.1 NeoForge sibling build | Blocking for integration lane |
| Mixin | Sponge Gradle plugin, Java 17 config, manifest registration | ModDev integration, Java 21, `[[mixins]]` metadata | High |

Package size is not an excuse for a global search-and-replace. Exactly 76 production files directly name Forge at the pinned commit, while many Forge-free files still depend on changed Minecraft APIs such as NBT, `ResourceLocation`, GUI widgets, attributes, registry lookup, and packet buffers.

### 2.1 Compatibility invariants

Treat these as tests, not suggestions:

1. Keep mod ID `mcacrime` and every existing registry ID unchanged.
2. Keep the overworld `SavedData` name `mcacrime`; the file must remain `<world>/data/mcacrime.dat`.
3. Keep player attachment ID `mcacrime:player_crime`, while explicitly importing its old `ForgeCaps` representation.
4. Do not reset `CrimeDataMigrations.CURRENT_SCHEMA` or bump it just because a Java method signature changed.
5. Preserve existing config filenames, keys, defaults, ranges, and enum spellings.
6. Preserve custom datapack listener IDs and paths unless a documented schema migration is added.
7. Preserve packet semantics and authority: clients request; the server validates and mutates.
8. Keep MCA classes off the normal compile classpath. `NoMcaStaticLinkTest` must remain meaningful.
9. The base mod must build and run without MCA: Reputation. The adapter is optional and isolated.
10. Never test an upgraded world without first copying it. Minecraft world upgrades are one-way operationally even when the code intends compatibility.

## 3. Work strategy and commit boundaries

Implement in the following order. Each phase must end with a focused commit and its listed gate. Do not stack all compiler errors into one unreviewable change.

Suggested commits:

1. `build: establish 1.21.1 NeoForge toolchain`
2. `port: migrate loader registration and events`
3. `port: replace player capability and preserve old saves`
4. `port: update world data and Minecraft API changes`
5. `port: replace SimpleChannel with payload networking`
6. `port: migrate registries tags and recipes`
7. `port: update client screens HUD rendering and mixin`
8. `compat: validate MCA and optional reputation integration`
9. `test: add upgrade fixtures and production smoke coverage`
10. `docs: publish NeoForge 1.21.1 support contract`

If a phase becomes too broad, split by package, but never mix save migration, protocol migration, and rendering in one commit.

## 4. Phase 0 — Freeze behavior and capture upgrade fixtures

Before changing build files:

1. Create a port branch from the pinned source commit.
2. Run the existing 1.20.1 suite in a network-enabled environment and archive the JUnit XML. The inspection environment could not download Gradle 8.8 because outbound dependency access was unavailable, so this document does **not** claim that the current baseline suite is green.
3. Build the current Forge production JAR and launch it with its declared MCA version.
4. Create a small named fixture world with at least two player UUIDs and exercise every persistent subsystem listed below.
5. Stop the server cleanly and copy, do not move, the entire world and configs into test fixtures outside any normal run directory.

The legacy fixture must contain:

- non-zero karma and heat in at least two communities;
- a witnessed crime, a reported crime, and an unwitnessed crime;
- open and resolved case-ledger entries;
- one active arrest/escort and one completed jail sentence;
- a registered holding cell with replaced blocks;
- an unlawful captive and ransom state;
- weapon/restraint inventory items;
- observation positions in at least two dimensions;
- optional MCA: Reputation data if a compatible source build is available;
- non-default common and client config values.

Record expected values in a small checked-in manifest, keyed by UUID and community ID. Do not rely on screenshots alone. Preserve an untouched copy of each `.dat`, the `data/mcacrime.dat` file, `level.dat`, and config TOMLs.

**Gate:** existing tests and the Forge fixture are captured, or the commit explicitly documents why a pre-existing failure is accepted. No port code begins with an unknown baseline.

## 5. Phase 1 — Replace the build and metadata

Start from the official 1.21.1 NeoForge MDK shape rather than incrementally coercing ForgeGradle.

### 5.1 Files to replace or update

- `build.gradle`
- `settings.gradle`
- `gradle.properties`
- `gradle/wrapper/gradle-wrapper.properties`
- `src/main/resources/META-INF/mods.toml` → `src/main/templates/META-INF/neoforge.mods.toml`
- `src/main/resources/mcacrime.mixins.json`
- remove `src/main/resources/pack.mcmeta`

### 5.2 Plugin and Java setup

The top of `build.gradle` should have this shape:

```groovy
plugins {
    id 'java-library'
    id 'maven-publish'
    id 'net.neoforged.moddev' version '2.0.146'
    id 'idea'
    id 'eclipse'
}

version = mod_version
group = mod_group_id
base { archivesName = mod_id }
java.toolchain.languageVersion = JavaLanguageVersion.of(21)
```

Delete:

- `net.minecraftforge.gradle`;
- `org.spongepowered.mixin` Gradle plugin;
- the ForgeGradle `minecraft { ... }` block;
- `minecraft "net.minecraftforge:forge:..."`;
- every `fg.deobf(...)` call and `fg.repository` reference;
- `reobfJar` configuration/dependencies;
- the Architectury repository, property, and runtime dependency.

Use the ModDev model:

```groovy
neoForge {
    version = project.neo_version

    parchment {
        minecraftVersion = project.parchment_minecraft_version
        mappingsVersion = project.parchment_mappings_version
    }

    runs {
        client {
            client()
            systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id
        }
        server {
            server()
            programArgument '--nogui'
            systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id
        }
        gameTestServer {
            type = 'gameTestServer'
            systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id
        }
        data {
            data()
            programArguments.addAll '--mod', project.mod_id, '--all',
                    '--output', file('src/generated/resources').absolutePath,
                    '--existing', file('src/main/resources').absolutePath
        }
        configureEach {
            systemProperty 'forge.logging.markers', 'REGISTRIES'
            logLevel = org.slf4j.event.Level.DEBUG
        }
    }

    mods {
        "${mod_id}" { sourceSet(sourceSets.main) }
    }
}
```

Keep `src/generated/resources` on the main resource source set. Update the wrapper to Gradle 9.2.1 and keep the Foojay toolchain resolver in `settings.gradle` so a JDK 21 can be provisioned consistently.

Use at least these properties:

```properties
minecraft_version=1.21.1
minecraft_version_range=[1.21.1]
neo_version=21.1.249
loader_version_range=[1,)
parchment_minecraft_version=1.21.1
parchment_mappings_version=2024.11.17
mca_version=7.7.36-beta.3+1.21.1
mca_probe_versions=7.7.36-beta.3+1.21.1
```

Delete `forge_version`, `forge_version_range`, `mapping_channel`, `mapping_version`, and `architectury_version`.

### 5.3 MCA runtime and probe configurations

MCA: Crime intentionally uses reflection rather than compiling against MCA. Preserve that design:

```groovy
configurations {
    localRuntime
    runtimeClasspath.extendsFrom localRuntime
    testRuntimeClasspath {
        exclude group: 'maven.modrinth', module: 'minecraft-comes-alive-reborn'
    }
}

dependencies {
    localRuntime "maven.modrinth:minecraft-comes-alive-reborn:${mca_version}"

    testImplementation 'org.junit.jupiter:junit-jupiter-api:5.10.2'
    testRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine:5.10.2'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
```

Retain the separate, non-transitive `mcaProbeN` configurations and pass their resolved paths to `McaBindingProbeTest`. However:

- include only **Minecraft 1.21.1 NeoForge MCA artifacts** in this build;
- never mix the old 1.20.1 MCA jars into the 1.21.1 probe process because their Minecraft method descriptors are ABI-incompatible;
- continue opening each artifact in an isolated class loader;
- keep MCA off `testRuntimeClasspath`, ensuring the “MCA absent” path is genuinely tested.

The current target uses package root `net.conczin.mca`, already present in `McaBinding.CANDIDATE_ROOTS`. Keep older roots only as inert compatibility history; do not claim they are verified on 1.21.1 unless matching 1.21.1 artifacts are actually probed.

### 5.4 Optional MCA: Reputation build seam

The current adapter compiles from `../MCAReputation/build/classes/java/main`. A 1.20.1 sibling output cannot be used in a 1.21.1 build. Preserve the standalone behavior:

- if a matching 1.21.1 NeoForge sibling output exists, add it as `compileOnly`;
- if `-PrequireReputation=true` is supplied and it is missing, fail with an actionable error;
- otherwise exclude `dev/otectus/mcacrime/compat/reputation/**` and build a fully functional standalone JAR;
- keep `ReputationBridge` free of static `mcareputation` type references.

This is an external dependency gate, not permission to silently drop the integration. A release claiming MCA: Reputation compatibility needs a dedicated CI lane built and run against a ported companion.

### 5.5 Generated mod metadata

Adopt the MDK `generateModMetadata` task and add every MCA: Crime replacement property to its input map. The output template should contain:

```toml
modLoader="javafml"
loaderVersion="${loader_version_range}"
license="${mod_license}"

[[mods]]
modId="${mod_id}"
version="${mod_version}"
displayName="${mod_name}"
authors="${mod_authors}"
description='''${mod_description}'''

[[mixins]]
config="mcacrime.mixins.json"

[[dependencies.${mod_id}]]
modId="neoforge"
type="required"
versionRange="[${neo_version},)"
ordering="NONE"
side="BOTH"

[[dependencies.${mod_id}]]
modId="minecraft"
type="required"
versionRange="${minecraft_version_range}"
ordering="NONE"
side="BOTH"

[[dependencies.${mod_id}]]
modId="mca"
type="required"
versionRange="[7.7.36-beta.3+1.21.1]"
ordering="AFTER"
side="BOTH"

[[dependencies.${mod_id}]]
modId="mcareputation"
type="optional"
versionRange="[0.2,)"
ordering="AFTER"
side="BOTH"
```

NeoForge uses dependency `type`, not Forge's `mandatory`. Keep the optional Reputation range permissive because `ReputationBridge` already performs an API-version handshake and intentionally degrades instead of stopping the game.

The exact MCA range above matches the published 1.21.1 NeoForge tag and avoids claiming untested older patch compatibility. Widen it (for example to a tested `[7.7.x,8)` line) only after adding those versions to isolated binding probes and running the gameplay compatibility matrix against the oldest declared version.

Remove `pack.mcmeta`; NeoForge synthesizes the mod pack metadata. Do not carry an obsolete pack format forward.

### 5.6 Mixin build handling

Register `mcacrime.mixins.json` through the `[[mixins]]` block, not the old `MixinConfigs` JAR manifest attribute. Change:

```json
"compatibilityLevel": "JAVA_21"
```

Retain the refmap declaration and ensure an annotation processor generates it under the ModDev build. If `annotationProcessor 'org.spongepowered:mixin:0.8.5:processor'` is retained, prove the generated `mcacrime.refmap.json` is in the production JAR and prove the mixin applies outside dev. Do not assume that removing the Sponge Gradle plugin also makes the refmap unnecessary.

Keep the single mixin client-only. Re-audit its target descriptor for `PlayerModel#setupAnim` under 1.21.1 and use Mixin debug export/audit during development. No client mixin class may be referenced from common initialization.

### 5.7 Build integrity task

Port `checkJarContents` so it inspects the normal ModDev `jar`/`build` output, not `reobfJar`. Preserve these assertions:

- MCA: Crime packages and metadata exist;
- `mcacrime.mixins.json` and its refmap exist;
- no `net/conczin/mca`, historical MCA package, `dev/architectury`, `net/minecraftforge`, or `dev/otectus/mcareputation` class is bundled;
- no dependency JAR contents have been shaded accidentally.

Add the task to `check`.

**Gate:** `./gradlew help`, `./gradlew dependencies`, `./gradlew compileJava`, and `./gradlew processResources` succeed on JDK 21; generated metadata expands with no `${...}` placeholders; no ForgeGradle task remains.

## 6. Phase 2 — Port initialization, registries, config, and events

### 6.1 Entrypoint

Replace static loading-context lookups with constructor injection:

```java
@Mod(McaCrime.MOD_ID)
public final class McaCrime {
    public static final String MOD_ID = "mcacrime";
    public static final Logger LOGGER = LogUtils.getLogger();

    public McaCrime(IEventBus modBus, ModContainer container) {
        CrimeItems.register(modBus);
        CrimeAttachments.register(modBus);
        modBus.addListener(CrimeNetwork::register);
        modBus.addListener(this::commonSetup);

        container.registerConfig(ModConfig.Type.COMMON, McaCrimeConfig.COMMON_SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, McaCrimeConfig.CLIENT_SPEC);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
```

Use a separate client entrypoint for client-only construction and the config screen:

```java
@Mod(value = McaCrime.MOD_ID, dist = Dist.CLIENT)
public final class McaCrimeClient {
    public McaCrimeClient(IEventBus modBus, ModContainer container) {
        container.registerExtensionPoint(
                IConfigScreenFactory.class,
                (ignored, parent) -> new CrimeConfigScreen(parent));
        CrimeClientPayloadRouter.install(new CrimeClientPayloadHandler());
    }
}
```

The architectural requirement is a client-only owner; do not touch `Minecraft` from `McaCrime`.

### 6.2 Config API

Replace `ForgeConfigSpec` with `net.neoforged.neoforge.common.ModConfigSpec` and `net.minecraftforge.fml.config.ModConfig` with `net.neoforged.fml.config.ModConfig`. Update nested imports in `CrimeConfigScreen` (`BooleanValue`, `EnumValue`, and other value types).

Preserve config types and filenames unless intentionally versioning the format. In particular, do not casually change COMMON to SERVER: that changes storage and synchronization semantics. Verify that a copied `mcacrime-common.toml` and `mcacrime-client.toml` load with no rewrite of keys or enum strings.

`ReputationBridge` should import `net.neoforged.fml.ModList`. Replace `javax.annotation.Nullable` with `org.jetbrains.annotations.Nullable` where the target classpath no longer supplies the former.

### 6.3 Deferred registries

Refactor `CrimeItems`:

```java
public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McaCrime.MOD_ID);
public static final DeferredRegister<CreativeModeTab> TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, McaCrime.MOD_ID);

public static final DeferredItem<Item> RESTRAINT_ROPE =
        ITEMS.registerSimpleItem("restraint_rope", new Item.Properties());
```

Use `DeferredItem<Item>` or an appropriately narrowed subtype. Register both deferred registers on the injected mod bus. Preserve all current paths and creative-tab behavior.

Replace direct `ForgeRegistries` use:

| Current location | Replacement |
|---|---|
| `ConfigValidator` entity/item existence checks | `BuiltInRegistries.ENTITY_TYPE.containsKey(id)` and `BuiltInRegistries.ITEM.containsKey(id)` |
| `WeaponDetector` item ID lookup | `BuiltInRegistries.ITEM.getKey(item)` |
| `CrimeCommand` item ID output | `BuiltInRegistries.ITEM.getKey(stack.getItem())` |
| `McaCompat` guard profession lookup | `BuiltInRegistries.VILLAGER_PROFESSION.getOptional(id)` or registry access equivalent |

Change `ConfigValidator.registryCheck` from `IForgeRegistry<?>` to a small predicate/function or vanilla `Registry<?>`. Continue accepting tag and wildcard patterns and continue reporting syntactically valid but unregistered IDs.

### 6.4 Event bus migration

Use these packages:

- `net.neoforged.neoforge.common.NeoForge`
- `net.neoforged.bus.api.IEventBus`
- `net.neoforged.bus.api.SubscribeEvent`
- `net.neoforged.bus.api.Event`
- `net.neoforged.fml.common.EventBusSubscriber`
- `net.neoforged.api.distmarker.Dist`

Replace `MinecraftForge.EVENT_BUS` with `NeoForge.EVENT_BUS`. On 1.21.1, use standalone `@EventBusSubscriber(modid = ..., value = Dist.CLIENT)`; remove old `Mod.EventBusSubscriber.Bus.MOD` annotation syntax. NeoForge routes mod-bus event types implementing `IModBusEvent` appropriately.

Do not blindly replace event names. Apply this table:

| Forge 1.20.1 | NeoForge 1.21.1 |
|---|---|
| `TickEvent.ServerTickEvent` with `phase == END` | `ServerTickEvent.Post` |
| `TickEvent.PlayerTickEvent` with `phase == END` | `PlayerTickEvent.Post`; still guard logical side where needed |
| `TickEvent.ClientTickEvent` with `phase == END` | `ClientTickEvent.Post` |
| `RegisterGuiOverlaysEvent` | `RegisterGuiLayersEvent` |
| `VanillaGuiOverlay.HOTBAR.id()` | `VanillaGuiLayers.HOTBAR` |
| old server/player lifecycle imports | corresponding `net.neoforged.neoforge.event.*` types |
| `FakePlayer` | `net.neoforged.neoforge.common.util.FakePlayer` |
| `ServerLifecycleHooks` | NeoForge package, though prefer the server supplied by event/context |

`CrimeObservationEvent.Pre` and `CrimeReportEvent.Pre` are cancellable. NeoForge no longer uses the old `@Cancelable` marker here:

```java
public static final class Pre extends CrimeObservationEvent implements ICancellableEvent {
    // existing fields and accessors
}
```

Posting code must inspect the returned event:

```java
CrimeObservationEvent.Pre pre = new CrimeObservationEvent.Pre(...);
if (NeoForge.EVENT_BUS.post(pre).isCanceled()) {
    return;
}
```

All non-cancellable posts simply call `NeoForge.EVENT_BUS.post(event)`.

### 6.5 Event-handler conversion checklist

Convert and verify every subscriber in this inventory:

| Class | Event(s) after port |
|---|---|
| `CrimeActionInteractHandler` | `PlayerInteractEvent.EntityInteract` |
| `CrimeActionTicker` | `ServerTickEvent.Post` |
| `CrimeReactionTicker` | `ServerTickEvent.Post`, `LivingDeathEvent`, `ServerStoppingEvent` |
| `CaptureInteractHandler` | `PlayerInteractEvent.EntityInteract` |
| `CaptureTicker` | `ServerTickEvent.Post` |
| `CrimeClientSetup` | `FMLClientSetupEvent`; client logout |
| `CrimeKeybinds` | `RegisterKeyMappingsEvent`, `ClientTickEvent.Post` |
| `CrimeNameRenderHandlers` | `RenderNameTagEvent`, logout, `ClientTickEvent.Post` |
| `PlayerCardScreenHooks` | `ScreenEvent.Init.Post`, `ScreenEvent.Render.Post` |
| `CrimeHudOverlays` | `RegisterGuiLayersEvent` |
| `CrimeRenderLayers` | `EntityRenderersEvent.RegisterLayerDefinitions`, `.AddLayers` |
| `EscortRopeRenderer` | `RenderLevelStageEvent` at `AFTER_ENTITIES` |
| `CrimeCommand` | `RegisterCommandsEvent` |
| `McaInteractionScreenBridge` | `ScreenEvent.Init.Post` |
| `CrimeTypeLoader` | `AddReloadListenerEvent` |
| `CrimeDetectionHandlers` | `LivingHurtEvent`, `LivingDeathEvent`, `PlayerLoggedOutEvent` |
| `CrimeDialogueLoader` | `AddReloadListenerEvent` |
| `GuardEnforcement` | `ServerTickEvent.Post` |
| `RestraintHandlers` | attack/use/block/mount/jump/respawn events listed in §16 |
| `CrimeDecayHandler` | `PlayerTickEvent.Post` |
| `CrimeReconciler` | `PlayerLoggedInEvent` |
| `AmbientMessages` | custom `CrimeWitnessed` plus logout |
| `ChatNameColor` | `ServerChatEvent` |
| `CrimeBandSync` | custom karma change plus login |
| `CrimeIntegrationPump` | server start/stop, login, `ServerTickEvent.Post` |
| `CellReleaseHandler` | custom release event |
| `ContainmentHandler` | `BlockEvent.BreakEvent` |
| `ProfessionDeathDrops` | `LivingDropsEvent` |
| `RansomTickHandler` | `ServerTickEvent.Post` |
| `RelationshipConsequences` | custom committed event |
| `CrimeCapabilityEvents` | remove; replace with attachments and legacy importer |

For each tick conversion, remove the old `phase` branch rather than leaving unreachable compatibility code. Confirm frequency and side with a counter/log in a dev run because accidental Pre+Post registration doubles gameplay effects.

**Gate:** common initialization and all non-network, non-client packages compile; a dedicated server reaches the title/startup boundary without a client-class linkage error; custom cancellable-event tests prove that cancellation prevents the mutation.

## 7. Phase 3 — Replace player capabilities without losing worlds

This is the highest-risk phase. NeoForge data attachments serialize under a different root key than Forge capabilities. Merely registering an attachment with the same ID does **not** migrate old players.

### 7.1 New attachment type

Delete `CrimeCapabilities`, `CrimeCapabilityEvents`, and `PlayerCrimeDataProvider` after their callers and migration tests have moved. Introduce `CrimeAttachments`:

```java
public final class CrimeAttachments {
    private static final DeferredRegister<AttachmentType<?>> TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, McaCrime.MOD_ID);

    public static final Supplier<AttachmentType<PlayerCrimeData>> PLAYER_CRIME =
            TYPES.register("player_crime", () -> AttachmentType
                    .serializable(PlayerCrimeData::new)
                    .copyOnDeath()
                    .build());

    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
    }

    public static PlayerCrimeData get(Player player) {
        return player.getData(PLAYER_CRIME);
    }
}
```

Use the exact generic type returned by the pinned NeoForge `DeferredRegister#register`; `Supplier` is acceptable and minimizes call-site coupling.

Make `PlayerCrimeData` implement NeoForge's provider-aware serializer:

```java
public final class PlayerCrimeData implements INBTSerializable<CompoundTag> {
    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        return save();
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        load(tag);
    }
}
```

The current NBT is registry-independent, so delegation is correct. Pass the provider down in the future if any nested field becomes registry-aware.

`.copyOnDeath()` replaces the old `PlayerEvent.Clone` code. Its default copy handler serializes the old value and deserializes a new instance, which preserves the current deep-copy intent for jail and arrest state. Keep `PlayerCrimeData#copyFrom` only if tests or other code still use it; otherwise remove it after proving attachment copy behavior.

### 7.2 Convert all access sites

The old `CrimeCapabilities.get(player)` returns `LazyOptional<PlayerCrimeData>`. Attachment `getData` returns the value and creates it on demand. Do not mechanically replace `ifPresent` with nullable logic.

Preferred migration pattern:

```java
// old
CrimeCapabilities.get(player).ifPresent(data -> mutate(data));

// new
mutate(CrimeAttachments.get(player));
```

Use `player.hasData(PLAYER_CRIME)` or `getExistingDataOrNull` only when the distinction between “never created” and “default object” matters, especially in the importer. Normal gameplay should use one direct accessor.

Audit at least these callers:

- `McaCrime`
- `api/McaCrimeApi`
- `action/handler/BailActionHandler`
- `captivity/CaptureService`, `CustodyService`
- `command/CrimeCommand`
- `economy/SurrenderService`
- `enforcement/ArrestService`, `ArrestStates`, `EscortService`, `LegalTarget`
- `engine/CrimeDecayHandler`, `CrimeState`
- `jail/ContainmentHandler`, `HoldingCellService`, `JailService`

There are roughly 47 capability access expressions across the tree. End the phase with zero `getCapability`, `LazyOptional`, `CapabilityToken`, `AttachCapabilitiesEvent`, `reviveCaps`, or `invalidateCaps` references in production code.

### 7.3 On-disk formats

The same logical object appears at different paths:

```text
Forge 1.20.1 player .dat:
  ForgeCaps
    mcacrime:player_crime
      karma, heat, band, wanted, ...

NeoForge 1.21.1 player .dat:
  neoforge:attachments
    mcacrime:player_crime
      karma, heat, band, wanted, ...
```

NeoForge's entity load code only recognizes `neoforge:attachments`; it will ignore `ForgeCaps`. Implement a narrow, read-only legacy importer subscribed to `PlayerEvent.LoadFromFile`, which fires after the player entity is loaded but before it is added to the world.

Algorithm:

1. Obtain `Player player = event.getEntity()`.
2. If `player.hasData(CrimeAttachments.PLAYER_CRIME)` is true, return. The NeoForge value always wins.
3. Build the vanilla file path as `event.getPlayerDirectory().toPath().resolve(event.getPlayerUUID() + ".dat")`. Do not call `getPlayerFile("dat")`; NeoForge deliberately rejects the reserved suffix.
4. If the file does not exist, return.
5. Read it with `NbtIo.readCompressed(path, NbtAccounter.create(MAX_LEGACY_PLAYER_NBT_BYTES))`, using a bounded quota such as 16 MiB.
6. Require `ForgeCaps` to be a compound and `mcacrime:player_crime` within it to be a compound. Missing or wrong types are a no-op.
7. Construct a new `PlayerCrimeData`, call `load` with the legacy payload, then `player.setData(CrimeAttachments.PLAYER_CRIME, imported)`.
8. Log one concise informational message for a successful import and one error with UUID/path for malformed or unreadable NBT. Do not log player NBT contents.
9. Do not write, rename, delete, or “clean up” the player `.dat` from this event. The next normal player save writes the attachment representation.

Sketch:

```java
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class LegacyPlayerCrimeImporter {
    private static final long MAX_NBT_BYTES = 16L * 1024L * 1024L;

    @SubscribeEvent
    public static void onLoad(PlayerEvent.LoadFromFile event) {
        Player player = event.getEntity();
        if (player.hasData(CrimeAttachments.PLAYER_CRIME)) return;

        Path path = event.getPlayerDirectory().toPath()
                .resolve(event.getPlayerUUID() + ".dat");
        if (!Files.isRegularFile(path)) return;

        try {
            CompoundTag root = NbtIo.readCompressed(
                    path, NbtAccounter.create(MAX_NBT_BYTES));
            if (root == null || !root.contains("ForgeCaps", Tag.TAG_COMPOUND)) return;
            CompoundTag forgeCaps = root.getCompound("ForgeCaps");
            if (!forgeCaps.contains("mcacrime:player_crime", Tag.TAG_COMPOUND)) return;

            PlayerCrimeData imported = new PlayerCrimeData();
            imported.load(forgeCaps.getCompound("mcacrime:player_crime"));
            player.setData(CrimeAttachments.PLAYER_CRIME, imported);
            McaCrime.LOGGER.info("Imported legacy MCA: Crime data for player {}", event.getPlayerUUID());
        } catch (IOException | RuntimeException ex) {
            McaCrime.LOGGER.error("Could not import legacy MCA: Crime player data for {} from {}",
                    event.getPlayerUUID(), path, ex);
        }
    }
}
```

Compile this sketch against the pinned mappings; `NbtIo` may return a nullable tag and exact overload exception declarations can differ. Preserve the algorithm even if syntax needs adjustment.

The precedence check makes the migration idempotent. If both roots are present, the new attachment is authoritative and the old data never overwrites it. Retaining the old `ForgeCaps` compound in a later `.dat` is harmless; it is ignored. Do not add a “migration completed” bit inside gameplay data unless testing reveals the attachment is not present at event time.

### 7.4 Required player-data tests

Add fixture-based tests, not only hand-built tags:

| Test | Required assertion |
|---|---|
| Old Forge payload import | Every field equals the manifest: karma, heat, cached band, wanted, clocks, daily counters, captive refs, jail, arrest |
| Missing `ForgeCaps` | No attachment created; no exception |
| Missing mod key | No attachment created |
| Wrong NBT types | Safe no-op/error, no partial object |
| Both old and new | New attachment wins |
| Repeated load/import | No duplication or reset |
| NeoForge round trip | `serializeNBT`/`deserializeNBT` preserves all fields |
| Death/respawn | `.copyOnDeath()` preserves all intended fields |
| Dimension transfer | State survives portal/End return |
| Corrupt/oversized file | Read is bounded and fails safely |

The fixture should be a real 1.20.1 Forge player NBT produced in Phase 0. If committing binary NBT is undesirable, commit an equivalent SNBT plus a test that converts it through Mojang's parser; still retain the real binary privately for release validation.

**Gate:** a copied Forge player file is imported exactly once, saved under `neoforge:attachments`, and reloads with exact field equality; death no longer depends on a clone event.

## 8. Phase 4 — Port world `SavedData`, NBT, IDs, and attributes

### 8.1 `CrimeWorldData`

Keep `DATA_NAME = "mcacrime"` and use 1.21.1's provider-aware APIs:

```java
private static final SavedData.Factory<CrimeWorldData> FACTORY =
        new SavedData.Factory<>(CrimeWorldData::new, CrimeWorldData::load, null);

public static CrimeWorldData get(MinecraftServer server) {
    return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
}

public static CrimeWorldData load(CompoundTag tag, HolderLookup.Provider provider) {
    CrimeWorldData data = new CrimeWorldData();
    data.loadFromTag(tag, provider);
    return data;
}

@Override
public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
    // existing fields; pass provider to registry-aware nested serializers
    return tag;
}
```

Pass `null` as the optional data fixer unless a real Mojang `DataFixTypes` association is appropriate. Do not invent one.

The Java signature change is not a schema migration. Keep `CrimeDataMigrations.CURRENT_SCHEMA == 6` unless the serialized structure itself changes.

### 8.2 Block positions: support both NBT shapes

The current `CrimeObservation` stores a block position using the old compound form (`{X,Y,Z}`-style fields through 1.20.1 `NbtUtils`). The 1.21.1 helper writes an integer array and reads from a parent tag/key. A loader that only calls the new helper may reject old observations.

Implement a compatibility reader:

```java
private static Optional<BlockPos> readCompatibleBlockPos(CompoundTag parent, String key) {
    Tag value = parent.get(key);
    if (value instanceof IntArrayTag) {
        return NbtUtils.readBlockPos(parent, key);
    }
    if (value instanceof CompoundTag legacy) {
        // Use the exact old key casing emitted by the current 1.20.1 code.
        return Optional.of(new BlockPos(
                legacy.getInt("X"), legacy.getInt("Y"), legacy.getInt("Z")));
    }
    return Optional.empty();
}
```

Inspect a Phase 0 fixture for actual `X/Y/Z` versus `x/y/z` casing before finalizing. Reject malformed arrays/compounds safely.

Choose one write policy:

- **Preferred for a loader-only port:** continue writing the legacy compound representation to avoid an on-disk schema change.
- **Alternative:** write the new integer-array form, bump schema to 7, add an explicit v6→v7 migration, and test it with the real v6 fixture.

Never silently change the format while leaving schema 6 and only testing new saves.

Audit `HoldingCell` block-state NBT. The 1.21.1 `NbtUtils.writeBlockState`/`readBlockState` signatures are provider/lookup-aware. Use the supplied `HolderLookup.Provider` or `BuiltInRegistries.BLOCK.asLookup()` only where the latter is semantically correct. Preserve unknown/missing block fallback behavior.

### 8.3 `ResourceLocation`

Constructors are private in 1.21.1. Apply this rule everywhere, including tests:

| Input | API |
|---|---|
| MCA: Crime namespace plus trusted code path | `McaCrime.id(path)` → `ResourceLocation.fromNamespaceAndPath` |
| Two trusted literal parts | `ResourceLocation.fromNamespaceAndPath(namespace, path)` |
| Trusted complete literal | `ResourceLocation.parse(value)` |
| Config, command, packet, or datapack input | `ResourceLocation.tryParse(value)` plus validation |

Known production locations include `McaCrime`, `CrimeIds`, `CrimeDataMigrations`, `CrimeIncidentMapping`, `IntegrationTargets`, `McaCompat`, `RestraintTags`, `WeaponRules`, `CrimeSprites`, the attachment ID, and every payload type. End with no `new ResourceLocation(` in production or tests.

### 8.4 Restraint movement attribute

Minecraft 1.21.1 identifies attribute modifiers by `ResourceLocation`, not a UUID/name pair. In `RestraintHandlers` use:

```java
private static final ResourceLocation RESTRAINED_SPEED = McaCrime.id("restrained_speed");

new AttributeModifier(
        RESTRAINED_SPEED,
        configuredAmount,
        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
```

Map old `MULTIPLY_TOTAL` to `ADD_MULTIPLIED_TOTAL`. Remove with `removeModifier(RESTRAINED_SPEED)`. Ensure repeated tick/login/respawn handling never stacks modifiers and unrestraining removes exactly MCA: Crime's modifier.

### 8.5 General 1.21.1 compile sweep

Fix Minecraft API errors deliberately rather than hiding them behind raw types. Common categories expected in this repository:

- registry access now favors holders and `BuiltInRegistries`;
- NBT serializers may receive `HolderLookup.Provider`;
- components and item serialization may use codecs/provider-aware overloads;
- interaction result/event accessor names may differ;
- enum switches should be exhaustive and reject unknown serialized values;
- `javax.annotation` may need conversion to JetBrains annotations.

For every fix, add or retain a test at the nearest pure-data boundary. Avoid broad `@SuppressWarnings` or reflection around vanilla APIs solely to make compilation pass.

**Gate:** old and new world NBT fixtures round-trip, schema migration tests pass, all ID parsing is explicit, and restraint modifiers behave identically without stacking.

## 9. Phase 5 — Replace SimpleChannel with 1.21.1 payload networking

Delete the `SimpleChannel`/`NetworkRegistry.ChannelBuilder` design, numeric discriminators, `registerMessage`, `NetworkEvent.Context`, and packet-local `encode/decode/handle` boilerplate.

### 9.1 Packet inventory and direction

Keep all existing semantic messages, assigning stable payload IDs based on their current class names:

| Direction | Existing class | Suggested payload ID | Principal validation |
|---|---|---|---|
| C2S | `RequestActionMenuC2SPacket` | `request_action_menu` | target exists, MCA villager, range, LOS, menu kind allowed |
| C2S | `StartActionC2SPacket` | `start_action` | action/menu token valid, target/range/state rechecked |
| C2S | `RequestSelfMenuC2SPacket` | `request_self_menu` | kind is self/captive and current state permits it |
| C2S | `GuardChallengeResponseC2SPacket` | `guard_challenge_response` | active challenge belongs to sender and has not expired |
| C2S | `RequestCaseLedgerC2SPacket` | `request_case_ledger` | sender may only request authorized scope/page |
| S2C | `SelfStatusS2CPacket` | `self_status` | client cache update only |
| S2C | `BandSyncS2CPacket` | `band_sync` | client cache update only |
| S2C | `BandBulkSyncS2CPacket` | `band_bulk_sync` | bounded player map |
| S2C | `CaptiveStatusS2CPacket` | `captive_status` | client cache/update screen only |
| S2C | `ActionMenuS2CPacket` | `action_menu` | bounded entries/requirements; open UI |
| S2C | `ActionProgressS2CPacket` | `action_progress` | bounded identifiers/progress; update UI |
| S2C | `GuardChallengeS2CPacket` | `guard_challenge` | bounded prompt/options/deadline; open UI |
| S2C | `CaseLedgerS2CPacket` | `case_ledger` | bounded rows and text; populate dossier |
| S2C | `RestraintSyncS2CPacket` | `restraint_sync` | one entity restraint cache update |
| S2C | `RestraintBulkSyncS2CPacket` | `restraint_bulk_sync` | bounded entity map |

Use protocol registrar version `"7"`; changing from a Forge numeric-message protocol to named NeoForge payloads is a wire redesign. Do not advertise compatibility with protocol 6.

### 9.2 Payload class pattern

Each record implements `CustomPacketPayload` and owns its type and codec:

```java
public record RequestActionMenuC2SPacket(
        int targetEntityId,
        ActionMenuKind kind) implements CustomPacketPayload {

    public static final Type<RequestActionMenuC2SPacket> TYPE =
            new Type<>(McaCrime.id("request_action_menu"));

    private static final StreamCodec<RegistryFriendlyByteBuf, ActionMenuKind> KIND_CODEC =
            safeEnumCodec(ActionMenuKind.values(), "action menu kind");

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestActionMenuC2SPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    RequestActionMenuC2SPacket::targetEntityId,
                    KIND_CODEC,
                    RequestActionMenuC2SPacket::kind,
                    RequestActionMenuC2SPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
```

Use codecs appropriate to actual fields:

- `UUIDUtil.STREAM_CODEC` for UUIDs;
- `ResourceLocation.STREAM_CODEC` for IDs;
- `ComponentSerialization.STREAM_CODEC` for components;
- `ByteBufCodecs.BOOL`, `VAR_INT`, `VAR_LONG`, `LONG`, or other exact primitive codec;
- `ByteBufCodecs.STRING_UTF8` only with an explicit application-level length bound;
- explicit ordinal codec that checks `0 <= ordinal < values.length` for enums.

Use `StreamCodec.composite` for small immutable records. Use `StreamCodec.of` or a custom codec for complex lists/maps so lengths can be checked **before** allocation. Never restore the current unbounded `readMap` behavior.

### 9.3 Registration

Register during the mod-bus event:

```java
public static void register(RegisterPayloadHandlersEvent event) {
    PayloadRegistrar registrar = event.registrar("7");

    registrar.playToServer(RequestActionMenuC2SPacket.TYPE,
            RequestActionMenuC2SPacket.STREAM_CODEC,
            CrimeNetwork::handleRequestActionMenu);
    // four more C2S payloads

    registrar.playToClient(SelfStatusS2CPacket.TYPE,
            SelfStatusS2CPacket.STREAM_CODEC,
            CrimeClientPayloadRouter::handleSelfStatus);
    // nine more S2C payloads
}
```

On NeoForge 21.1, the registrar defaults to main-thread handlers. Do not retain `enqueueWork` reflexively; use it only if registration is explicitly changed to `executesOn(HandlerThread.NETWORK)`.

Do not mark these payloads optional. MCA: Crime requires the mod on both sides and the UI/state protocol must match.

### 9.4 Physical-side safety for S2C handlers

There is no separate later-version `RegisterClientPayloadHandlersEvent` in the pinned 1.21.1 API. Registration happens from common code, yet a dedicated server must not resolve client classes.

Use a common-safe router with a no-op default, installed by the client-only `@Mod` constructor:

```java
public final class CrimeClientPayloadRouter {
    private static volatile Handler handler = Handler.NOOP;

    public static void install(Handler clientHandler) {
        handler = Objects.requireNonNull(clientHandler);
    }

    public static void handleSelfStatus(SelfStatusS2CPacket payload, IPayloadContext context) {
        handler.handleSelfStatus(payload, context);
    }

    public interface Handler {
        Handler NOOP = new Handler() {};
        default void handleSelfStatus(SelfStatusS2CPacket payload, IPayloadContext context) {}
        // Give each remaining S2C method the same default no-op body.
    }
}
```

Implement the handler in `client/network/CrimeClientPayloadHandler`, which may reference `Minecraft`, screens, and client caches. The client entrypoint installs it before play payloads can arrive. A thin `DistExecutor` indirection is also acceptable, but prove with a dedicated-server classloading test that the common registrar's constant pool does not name a client class.

### 9.5 Handlers and sends

Server-bound handlers obtain the sender from context and treat every field as hostile:

```java
private static void handleRequestActionMenu(
        RequestActionMenuC2SPacket payload,
        IPayloadContext context) {
    if (!(context.player() instanceof ServerPlayer sender)) return;
    CrimeActionService.handleMenuRequest(sender, payload.targetEntityId(), payload.kind());
}
```

Preserve the existing service-layer checks and rate limits. Never accept a client-supplied player UUID as authority. Re-resolve entities in the sender's current level; check distance, line of sight, current arrest/captive state, action availability, menu token/deadline, and ownership at handling time.

Replace sends with:

```java
PacketDistributor.sendToServer(payload);
PacketDistributor.sendToPlayer(player, payload);
PacketDistributor.sendToAllPlayers(payload);
```

For restraint state, consider `sendToPlayersTrackingEntityAndSelf` only if it matches existing visibility semantics; do not broaden/narrow recipients incidentally. Update direct sends in `CrimeActionService` as well as `CrimeNetwork` wrappers.

### 9.6 Codec bounds

Define constants and test them:

| Payload/data | Bound requirement |
|---|---|
| Action menu entries | existing `MAX_ACTIONS`; each requirement list bounded |
| Case ledger rows | existing `MAX_ROWS`; page metadata validated |
| Band bulk map | at most current server player count with a hard ceiling, e.g. 1024 |
| Restraint bulk map | same hard ceiling, e.g. 1024 |
| Resource IDs | valid `ResourceLocation`; reject malformed input |
| Strings/components | smallest useful UTF-8/component limit, documented per field |
| Enum ordinals | explicit range check; never raw `values()[readInt()]` |
| Counts/durations | reject negative values and impossible maxima |

An over-limit decoder should fail the payload/connection predictably; it must not allocate from attacker-controlled sizes or leave unread bytes that desynchronize the stream.

### 9.7 Network tests

Add:

- codec round-trip tests for all 15 payloads;
- empty, normal, maximum, and over-maximum list/map cases;
- invalid enum ordinal, malformed ID, negative count, excessive string/component cases;
- a registration test proving all types are unique and each direction is correct;
- server-handler tests for wrong target, distance, LOS, expired token/challenge, unauthorized ledger scope, and stale entity ID;
- client-handler tests that mutate only client caches/UI;
- dedicated-server classloading smoke test;
- two-client integration test covering menu request/action, guard response, case ledger, band/restraint bulk sync, logout/rejoin, and tracking changes.

**Gate:** every payload is named, bounded, direction-specific, main-thread safe, and covered; a mismatched client is refused cleanly; a dedicated server never resolves the client handler implementation.

## 10. Phase 6 — Port resources, tags, recipes, and reload listeners

Minecraft 1.21 uses singular data-pack registry folders. Move files; do not leave duplicate active copies under both old and new paths.

### 10.1 Required path and schema changes

| Current 1.20.1 path/schema | 1.21.1 target |
|---|---|
| `data/mcacrime/recipes/*.json` | `data/mcacrime/recipe/*.json` |
| `data/mcacrime/tags/items/*.json` | `data/mcacrime/tags/item/*.json` |
| `data/forge/tags/items/rope.json` | `data/c/tags/item/ropes.json` |
| shaped recipe result `{ "item": "..." }` | result `{ "id": "..." }` |
| code/tag `forge:rope` | common tag `c:ropes` / NeoForge `Tags.Items.ROPES` |

Update all three recipes:

- `restraint_cuffs.json`
- `restraint_locked_cuffs.json`
- `restraint_rope.json`

Preserve output counts, including the rope recipe's count of 2.

Move these item tags to the singular folder without changing their MCA: Crime IDs:

- `cutting_tools`
- `keys`
- `restraints`
- `weapons`
- `weapons_blacklist`

### 10.2 Common tags

Replace `RestraintTags.ROPE` with NeoForge's common convention:

```java
public static final TagKey<Item> ROPE = Tags.Items.ROPES;
```

Create `data/c/tags/item/ropes.json` and add MCA: Crime's restraint rope plus vanilla lead if the old behavior intended both:

```json
{
  "replace": false,
  "values": [
    "mcacrime:restraint_rope",
    "minecraft:lead"
  ]
}
```

Review rather than mechanically translate the weapon tag. For the 1.21.1 common-tag model, use optional nested tags such as:

```json
{
  "replace": false,
  "values": [
    { "id": "#c:tools/melee_weapon", "required": false },
    { "id": "#c:tools/ranged_weapon", "required": false }
  ]
}
```

The exact 1.21.1 NeoForge common tags cover swords, axes, maces, tridents, bows, and crossbows. Preserve explicit MCA: Crime allow/deny tags and config precedence. Old `forge:*` entries may be retained only as non-required bridges for third-party packs; target logic must not depend on them.

### 10.3 Custom reload paths

These nested locations are custom listener inputs and should **not** be singularized as vanilla registries:

- `data/mcacrime/mcacrime/crimes/*.json`
- `data/mcacrime/mcacrime/dialogue/*.json`
- `data/mcacrime/mcareputation/incidents/*.json`

Keep their existing `ResourceManager` IDs unless changing the published datapack contract. Port `CrimeTypeLoader` and `CrimeDialogueLoader` to the NeoForge `AddReloadListenerEvent`, then verify initial load and `/reload` behavior. Error accumulation in `CrimeTypeRegistry.lastErrors()` must still feed `/crime config validate`.

For Reputation incident definitions, confirm the ported companion still consumes the same custom path and schema. If it changed, version and document the integration schema rather than moving files speculatively.

### 10.4 Resource validation

Add an automated resource test that:

- rejects plural `recipes` and `tags/items` directories;
- parses every JSON file;
- confirms every recipe result uses `id`;
- confirms every MCA: Crime tag and recipe ID resolves;
- loads crime/dialogue definitions through their production deserializers;
- checks translation keys used by items, creative tab, screens, packets, HUD, and keybindings;
- checks model texture references exist.

Run the data generator and compare generated output. Never commit `.cache` files.

**Gate:** a clean server reports no resource errors; `/reload` succeeds; the three recipes appear and craft; the restraint rope, cutting tool, key, restraint, weapon, and weapon blacklist tests all pass.

## 11. Phase 7 — Port client initialization, screens, HUD, rendering, and mixin

Compile the common/server side first, then enable the client source. Keep every class that imports `net.minecraft.client` behind client-only ownership.

### 11.1 Client event subscribers and keybindings

Convert client annotations/imports to NeoForge and remove the old nested `bus = Mod.EventBusSubscriber.Bus.MOD` declaration. `KeyConflictContext` still exists at:

```java
net.neoforged.neoforge.client.settings.KeyConflictContext
```

`RegisterKeyMappingsEvent` remains a mod-bus event. `CrimeKeybinds#onClientTick` should accept `ClientTickEvent.Post` and remove its old phase check. Preserve click draining, pause behavior, default J/K bindings, and the two intentionally unbound mappings.

Replace all client sends from `CrimeNetwork.CHANNEL.sendToServer(...)` with `PacketDistributor.sendToServer(...)` or a thin `CrimeNetwork.sendToServer` wrapper.

On logout, continue clearing every static client cache: challenge, self, captive, action, band/name, restraint, and screen-specific data. Add a test/list assertion so a newly introduced cache cannot be forgotten.

### 11.2 HUD layers

`CrimeHudOverlays` becomes a `RegisterGuiLayersEvent` subscriber. The layer callback is `LayeredDraw.Layer`, whose arguments are `(GuiGraphics, DeltaTracker)`; dimensions come from the graphics object:

```java
@SubscribeEvent
public static void register(RegisterGuiLayersEvent event) {
    event.registerAbove(
            VanillaGuiLayers.HOTBAR,
            McaCrime.id("crime_channel"),
            (graphics, deltaTracker) -> renderChannel(
                    graphics, graphics.guiWidth(), graphics.guiHeight()));
    // crime_status and crime_custody
}
```

Layer IDs are full `ResourceLocation`s. Preserve anchor calculations, config suppression, spectator/hide-GUI suppression, stacking, colors, and action-outcome fade. Test all GUI scales and both window aspect extremes.

### 11.3 Screen rendering signatures

The 1.21.1 `Screen` background method requires pointer/tick arguments:

```java
renderBackground(graphics, mouseX, mouseY, partialTick);
```

Update every call in:

- `CaseDossierScreen`
- `CrimeConfirmationScreen`
- `CrimeConfigScreen`
- `CrimeInteractionScreen`
- `GuardChallengeScreen`

Do not add a local compatibility overload that masks the real method. Check tooltip, narration, focus, escape/pause, and resize behavior after compilation.

### 11.4 `CrimeRowList`

`AbstractSelectionList`/`ContainerObjectSelectionList` changed substantially. Port the widget as follows:

```java
public CrimeRowList(Minecraft minecraft, int left, int top,
                    int bottom, int width, int itemHeight) {
    super(minecraft, width, bottom - top, top, itemHeight);
    setX(left);
    this.scrollbarX = scrollbarX(left, width);
    this.rowWidth = rowWidth(width);
}
```

Then:

- replace `getLeft()` with `getX()`;
- use `getY()`, `getBottom()`, and `getWidth()` for geometry;
- replace the removed `setLeftPos` with `setX`;
- remove `setRenderBackground(false)` and `setRenderTopAndBottom(false)`;
- override `renderListBackground(GuiGraphics)` to draw `CrimeSprites.well(...)`;
- override `renderListSeparators(GuiGraphics)` with a no-op to suppress vanilla strips/separators;
- retain the custom `renderDecorations` scrollbar only after comparing its thumb calculations with the 1.21.1 superclass;
- keep row widget position/size updated on every render so hitboxes follow scrolling;
- retain `CrimeRowListGeometryTest`, updating expected accessors rather than weakening assertions.

Manually test wheel scrolling, thumb dragging, click hitboxes at both edges, Tab/Shift-Tab, arrow navigation, narration, resize, and a dataset long enough to reach maximum scroll.

### 11.5 Escort rope world rendering

`RenderLevelStageEvent#getPartialTick()` now returns `DeltaTracker`:

```java
float partialTick = event.getPartialTick()
        .getGameTimeDeltaPartialTick(false);
```

Keep stage `AFTER_ENTITIES`, camera-relative translation, light interpolation, stale entity checks, and one end-batch call. Update `VertexConsumer` calls:

```java
buffer.addVertex(matrix, x, y, z)
        .setColor(r, g, b, a)
        .setLight(light);
```

There is no `endVertex()` in this chain. Verify the exact float/int color overload selected by the compiler. Render-test daylight/night, cross-chunk edges, distance, first/third person, invisible prisoner, dimension changes, multiple escorts, and Fabulous graphics. Confirm no buffer corruption or rope duplication.

### 11.6 Player model restraint layer and mixin

Recompile `CrimeRenderLayers` against `EntityRenderersEvent.RegisterLayerDefinitions` and `.AddLayers`. Recheck `ModelPart#render` overloads; if the no-color overload no longer matches, pass an explicit opaque ARGB tint (`-1`) rather than changing appearance.

For `PlayerModelRestraintMixin`:

- keep it in the mixin config's `client` list;
- update compatibility to Java 21;
- confirm the target `setupAnim(LivingEntity, float, float, float, float, float)` descriptor in the pinned mappings;
- verify both normal and slim player models;
- verify the production refmap and mixin audit, not just runClient;
- test restrained standing, walking, swimming, riding, sleeping, item use, armor, and another modded render layer.

If the descriptor or target class changed, update the injection explicitly and add an assertion/log proving it ran. Never set `require: 0` just to make startup succeed silently.

### 11.7 MCA interaction-screen hook

The current reflection bridge injects a button into MCA's interaction screen without statically importing MCA. Keep that separation. In target MCA 1.21.1, `net.conczin.mca.client.gui.InteractScreen` still has a `VillagerLike` field whose runtime value is an entity, but field privacy/name may be remapped.

Use structural discovery as today, cache the resolved field, and fail softly with one useful log. Verify:

- button placement does not overlap MCA controls at every GUI scale;
- config disable removes it;
- target villager is resolved correctly after screen resize/reopen;
- click sends only the target ID and lets the server revalidate;
- no MCA client type appears in MCA: Crime bytecode descriptors or signatures.

### 11.8 Client acceptance matrix

| Area | Manual cases |
|---|---|
| Keybindings | defaults, remapping, conflicts, unbound keys, pause menu, held-repeat drain |
| Dossier/config/action/challenge screens | mouse, keyboard, narration, resize, all GUI scales |
| HUD | all anchors/offsets/toggles, hide GUI, spectator, action success/cancel, jail/captive clocks |
| Name colors/player card | join/leave, dimension, stale cache, inventory screen |
| Restraint visuals | normal/slim skins, armor, all common poses |
| Escort rope | visibility, lighting, multiple entities, stale IDs, graphics modes |
| MCA screen button | normal villager, guard, family member, config off, server denial |
| Disconnect | every client cache empty before next server connection |

**Gate:** runClient has no mixin, rendering, narration, or resource errors; every matrix row passes; the production JAR behaves the same in a non-dev client.

## 12. Phase 8 — Validate MCA Reborn and optional MCA: Reputation compatibility

### 12.1 MCA binding contract

Do not replace `McaBinding`/`McaHandles` with direct imports. Their runtime manifest is deliberate and protects MCA: Crime from MCA package-root changes. Probe the target 1.21.1 NeoForge MCA artifact for all of these members:

| Target concept | Required member(s) observed in target MCA |
|---|---|
| MCA villager | `getVillagerBrain`, `getResidency`, `setProfession`, `getProfessionId`, `isProfessionImportant`, `isGuard` |
| Villager-like | `getAgeState` |
| Brain/memories | `getMemoriesForPlayer(Player)`, `rewardHearts(ServerPlayer,int)`, `getHearts` |
| Residency/village | `getHomeVillage`, `Village#getName`, `getId`, `getPopulation`, `getResidents(ServerLevel)`, `isVillage` |
| Village manager | static `get(ServerLevel)`, `getOrEmpty(int)` |
| Relationship | `EntityRelationship.of`, family entry lookup, partner UUID |
| Family tree | children, parents stream, siblings, all relatives |

Update method descriptors for Minecraft 1.21.1 types, especially any registry holder, player, level, or component changes. A method name match with a 1.20.1 descriptor is not a pass.

Required automated gates:

- `McaBindingProbeTest` opens the exact target production MCA JAR and validates every manifest entry;
- `NoMcaStaticLinkTest` scans production class files for forbidden MCA references;
- the normal unit-test runtime excludes MCA and exercises graceful degradation;
- a test with a deliberately broken/missing member produces one diagnostic and disables only the affected bridge rather than crashing unrelated gameplay.

Required production runtime cases:

- identify adult/child MCA villagers and guards;
- resolve village/home/community and resident population;
- read and reward hearts;
- derive spouse/parent/child/sibling/relative relationships;
- match important professions and custom responders;
- drive guard detection/arrest and profession death drops;
- open the MCA interaction screen and injected crime action;
- start without MCA and confirm the loader rejects the missing **required** dependency cleanly rather than throwing MCA: Crime linkage errors.

### 12.2 Remove Architectury

The target MCA NeoForge build does not require MCA: Crime to supply Architectury, and MCA: Crime names no Architectury symbol. Remove its repository/dependency/property and keep it on the forbidden-package list. Let MCA declare its own dependencies.

### 12.3 MCA: Reputation

Port only against a real 1.21.1 NeoForge MCA: Reputation build. The base mod must still work if that build is absent.

Verify the adapter's public contract rather than assuming source compatibility:

- API generation remains `1`, or update `REQUIRED_API_VERSION` and the adapter together;
- authority registration/release remains lifecycle-safe;
- MCA villager assault and killing have exactly one producer while authority is held;
- refused/lost authority stops MCA: Crime from duplicating those incidents;
- dedupe keys survive restart/retry;
- mirror registration/unregistration remains balanced;
- community, incident subject/status, resolution, and game-time types are 1.21.1 compatible;
- the custom incident JSON path/schema remains accepted;
- installed-but-old/broken companion degrades to the built-in store with an actionable status;
- no `mcareputation` class is bundled in MCA: Crime.

Update `MINIMUM_COMPANION_VERSION` and metadata only after the companion port establishes a real first-compatible version. Do not publish a guessed range.

**Gate:** target MCA probe and runtime matrix pass; standalone and Reputation-enabled build lanes both pass; dependency jars are not bundled.

## 13. Phase 9 — Test, package, and document the release

### 13.1 Automated test layers

Retain all existing tests and add coverage in these layers:

| Layer | Command/example | Purpose |
|---|---|---|
| Pure unit | `./gradlew test` | crime math, state machines, parsing, NBT, codecs, reflection manifests |
| Resource/static | part of `check` | JSON, paths, translations, forbidden imports/classes, metadata |
| GameTest | `./gradlew runGameTestServer` | attachments, SavedData, events, server gameplay interactions |
| Dev client | `./gradlew runClient` | screens, input, HUD, rendering, mixin, MCA hook |
| Dev dedicated server | `./gradlew runServer` | physical-side safety and server lifecycle |
| Production JAR client/server | external clean instances | refmap/remapping/dependency reality |
| Legacy-world upgrade | copied Forge fixture | data compatibility and behavior continuity |

Do not delete or disable a failing test merely because it encodes a 1.20.1 API. Port its fixture/harness while retaining its behavioral assertion. When an assertion genuinely describes loader-specific behavior, replace it with a NeoForge-equivalent assertion in the same commit.

### 13.2 Full gameplay regression matrix

Exercise at minimum:

| System | Cases |
|---|---|
| Detection | villager/player/guard harm and kill, theft, false positives, fake players, protected/responder config |
| Witness/report | LOS/range, memory, report propagation, cancellable Pre events, custom listeners |
| Karma/heat | thresholds, daily caps, online-only decay, band sync, login reconciliation |
| Guard enforcement | challenge accept/refuse/timeout, surrender, pursuit, legal target, logout/restart |
| Arrest/escort | phase transitions, guard death/unload, prisoner death, dimension, rope sync/render |
| Jail | holding cell creation, containment, sentence clock, bail/release, death/relog/restart |
| Captivity | capture/release/rescue, cap, ownership, player and MCA villager targets |
| Mugging/ransom | channel interruption, empty/repeat/resist/success, demand/counter/pay/refuse/expiry |
| Economy/loot | fines, bail, purse/capacity, profession drops, duplication resistance |
| Case ledger | pagination, open/resolved status, dossier display, unauthorized requests |
| Relationships | spouse/family/village consequences, hearts, missing/degraded MCA bindings |
| Config/datapacks | default/custom/invalid config, reload, invalid JSON diagnostics, tag override |
| Multiplayer | two clients, late join, tracking start/stop, logout cache clear, reconnect to another server |

For server-authoritative actions, include a malicious/stale-client case. Normal UI success does not prove packet security.

### 13.3 Legacy-world upgrade procedure

1. Back up the Phase 0 Forge world and configs.
2. Copy the fixture to a new NeoForge test instance.
3. Install the production MCA: Crime port JAR, target production MCA JAR, and only declared dependencies.
4. Start the dedicated server and capture the full first-upgrade log.
5. Join each fixture player once, causing legacy player attachment import.
6. Compare in-game/API/command state against the manifest before any gameplay mutation.
7. Stop cleanly. Inspect NBT: world file name/key unchanged; each joined player has `neoforge:attachments.mcacrime:player_crime`.
8. Restart twice and compare again, proving idempotence.
9. Exercise death, dimension transfer, sentence completion, captive release, case resolution, and new crime writes.
10. Re-run with the optional companion lane if supported.

Any missing field is a release blocker. Do not compensate by telling users to run commands manually.

### 13.4 Production artifact inspection

After `./gradlew clean build`:

```bash
jar tf build/libs/mcacrime-*.jar | sort
unzip -p build/libs/mcacrime-*.jar META-INF/neoforge.mods.toml
unzip -p build/libs/mcacrime-*.jar mcacrime.mixins.json
unzip -p build/libs/mcacrime-*.jar mcacrime.refmap.json
jdeps --multi-release 21 --ignore-missing-deps build/libs/mcacrime-*.jar
```

Check:

- one mod JAR, no `-sources` mistaken for release;
- correct version and dependency metadata;
- mixin config/refmap present;
- singular data paths only;
- no MCA, Reputation, Architectury, Forge, test, generated cache, or sibling output classes bundled;
- Java class-file version 65;
- no unresolved client dependency reachable from common initialization.

### 13.5 Documentation updates

Update:

- `README.md`
- `API.md`
- `CONFIG.md`
- `DATAPACK.md`
- `CURSEFORGE.md`
- `CHANGELOG.md`
- `docs/MIGRATION.md`
- verification/phase documents that claim an active platform version

Required content:

- Minecraft 1.21.1, NeoForge minimum, Java 21, target MCA range;
- explicit statement that Forge 1.20.1 and NeoForge 1.21.1 builds are not cross-compatible;
- automatic player/world data upgrade behavior and a backup warning;
- singular `recipe` and `tags/item` paths, `c:` common tags, and recipe result `id`;
- payload/network version expectations for servers and clients;
- optional Reputation compatibility status and exact supported version once known;
- removal of Architectury as MCA: Crime's dependency;
- accurate API-event package/import examples for NeoForge.

`API.md` currently describes “Forge events” and says none are cancellable. Correct it: the public events are NeoForge events, and `CrimeObservationEvent.Pre` plus `CrimeReportEvent.Pre` are cancellable. Count the actual public event types/classes from source when updating the prose rather than copying an old number.

Historical specifications may keep their original 1.20.1 content, but add a conspicuous banner identifying them as historical. Do not rewrite history into an inaccurate claim that the old design targeted NeoForge.

### 13.6 Release gates

Before tagging:

- CI uses JDK 21 and a fresh Gradle cache at least once.
- `clean check build` and GameTest pass.
- client and dedicated-server dev runs pass.
- production client and dedicated server pass with exact target MCA.
- the copied Forge world passes the manifest comparison and two restarts.
- standalone build passes without Reputation classes.
- integration build/runtime passes with the declared Reputation version, or documentation explicitly marks the integration unavailable for this release.
- no Forge/old-path residue checks fail.
- changelog and upgrade notes are reviewed.

Only then tag and publish. Retain the legacy world fixture and production smoke script for future 1.21.1 patches.

## 14. File-level worklist

This is a coverage map, not a substitute for compiler/search results. Check off every group in the pull request description.

### 14.1 Build and metadata

- `build.gradle`: ModDevGradle, Java 21, NeoForge runs, MCA runtime/probes, optional Reputation lane, tests, metadata generation, JAR checks.
- `settings.gradle`: current plugin repositories and Foojay resolver.
- `gradle.properties`: remove Forge/Architectury/mapping properties; add NeoForge/Parchment/target MCA.
- `gradle/wrapper/gradle-wrapper.properties`: Gradle 9.2.1.
- `META-INF/mods.toml`: remove and replace with template `META-INF/neoforge.mods.toml`.
- `pack.mcmeta`: remove.
- `mcacrime.mixins.json`: Java 21, client mixin/refmap verification.

### 14.2 Entrypoint, config, registries, and state

- `McaCrime`: constructor injection, deferred registrations, config, payload event, `id` helper.
- add `McaCrimeClient`: config screen and client payload implementation installation.
- `McaCrimeConfig`, `CrimeConfigScreen`: `ModConfigSpec` and client extension point.
- `CrimeItems`: NeoForge deferred item/tab registers.
- `ConfigValidator`, `WeaponDetector`, `CrimeCommand`, `McaCompat`: vanilla/NeoForge registry access.
- add `CrimeAttachments` and `LegacyPlayerCrimeImporter`.
- `PlayerCrimeData`: `INBTSerializable<CompoundTag>`.
- delete `CrimeCapabilities`, `CrimeCapabilityEvents`, `PlayerCrimeDataProvider` after call sites move.
- `CrimeWorldData`, `CrimeDataMigrations`, `CrimeObservation`, `HoldingCell` and nested NBT types: provider-aware serialization and legacy position shape.
- all state consumers listed in §7.2: direct attachment access.

### 14.3 Direct Forge-import production files

Every class below must be individually compiled/reviewed; a passing global import replacement is not enough.

| Package | Files |
|---|---|
| root/config | `McaCrime`, `McaCrimeConfig`, `config/ConfigValidator` |
| action | `ActionFeedback`, `CrimeActionInteractHandler`, `CrimeActionService`, `CrimeActionTicker` |
| AI | `CrimeReactionService`, `CrimeReactionTicker` |
| public API events | `CrimeEvent`, `CrimeObservationEvent`, `CrimeReportEvent`, `EntityKidnappedEvent`, `EntityReleasedFromCaptivityEvent`, `WitnessReactionChangedEvent` |
| captivity | `CaptureInteractHandler`, `CaptureTicker`, `CustodyService` |
| client | `CrimeClientSetup`, `CrimeKeybinds`, `CrimeNameRenderHandlers`, `PlayerCardScreenHooks`, `hud/CrimeHudOverlays`, `render/CrimeRenderLayers`, `render/EscortRopeRenderer`, `screen/CrimeConfigScreen` |
| command/compat | `CrimeCommand`, `ReputationBridge`, `compat/reputation/CrimeReputationCompat`, `compat/mca/client/McaInteractionScreenBridge` |
| data reload/detection | `CrimeTypeLoader`, `CrimeDetectionHandlers`, `CrimeDetector`, `CrimeGate`, `EntitySelectors`, `CrimeDialogueLoader` |
| economy/enforcement | `FineService`, `GuardEnforcement`, `RestraintHandlers` |
| engine/events | `CrimeDecayHandler`, `CrimeReconciler`, `CrimeState`, `AmbientMessages`, `ChatNameColor`, `CrimeBandSync` |
| integration/items | `CrimeIntegrationPump`, `CrimeItems`, `WeaponDetector` |
| jail/ledger/loot/memory | `CellReleaseHandler`, `ContainmentHandler`, `JailService`, `CrimeCaseService`, `ProfessionDeathDrops`, `ObservationService`, `ReportService` |
| network | `CrimeNetwork` and all 15 packet files in §9.1 |
| ransom/relationship | `RansomTickHandler`, `RelationshipConsequences` |
| old capability | `CrimeCapabilities`, `CrimeCapabilityEvents`, `PlayerCrimeDataProvider` — delete/replace |

### 14.4 Forge-free files that still need targeted review

Do a compile/API review across every production package, particularly:

- `action/handler/**`: attachment access and packet send paths;
- `api/McaCrimeApi`: attachment semantics and public NeoForge event documentation;
- `captivity/CaptureService`: attachment access, entity APIs;
- `economy/SurrenderService`: player data and messages;
- `enforcement/ArrestService`, `ArrestStates`, `EscortService`, `LegalTarget`: attachment and dimension behavior;
- `jail/HoldingCellService`: block-state NBT and SavedData;
- `client/screen/**`: 1.21.1 screen/widget methods and `ResourceLocation`;
- `compat/mca/McaBinding`, `McaHandles`: 1.21.1 method descriptors;
- `state/world/**`: holder lookup providers and old fixtures;
- `mixin/PlayerModelRestraintMixin`: target descriptor.

### 14.5 Tests needing known API updates

At minimum, review these tests because they directly name Forge APIs, `ResourceLocation` constructors, buffers, capabilities, or changed NBT types:

`ActionSessionManagerTest`, `ArrestStateNbtTest`, `ConfigSweepTest`, `CrimeApplicationTest`, `CrimeCommunityKeyTest`, `CrimeIncidentDataTest`, `CrimeTypeTest`, `CrimeWorldDataCustodyTest`, `CustodyRecordNbtTest`, `DialogueCoverageTest`, `DialogueTest`, `JailRegionTest`, `JailRegistryNbtTest`, `JailStateNbtTest`, `ObservationTest`, `ReactionTest`, `SentenceResolutionTest`, and `WeaponRulesTest`.

Also add the attachment-import and 15-payload codec suites described above. Keep all existing domain tests; the source contains 435 test methods and a port should not reduce that count without an explained consolidation that preserves assertions.

### 14.6 Resource moves

Move, do not copy:

```text
data/mcacrime/recipes/restraint_cuffs.json
  -> data/mcacrime/recipe/restraint_cuffs.json
data/mcacrime/recipes/restraint_locked_cuffs.json
  -> data/mcacrime/recipe/restraint_locked_cuffs.json
data/mcacrime/recipes/restraint_rope.json
  -> data/mcacrime/recipe/restraint_rope.json

data/mcacrime/tags/items/cutting_tools.json
  -> data/mcacrime/tags/item/cutting_tools.json
data/mcacrime/tags/items/keys.json
  -> data/mcacrime/tags/item/keys.json
data/mcacrime/tags/items/restraints.json
  -> data/mcacrime/tags/item/restraints.json
data/mcacrime/tags/items/weapons.json
  -> data/mcacrime/tags/item/weapons.json
data/mcacrime/tags/items/weapons_blacklist.json
  -> data/mcacrime/tags/item/weapons_blacklist.json

data/forge/tags/items/rope.json
  -> data/c/tags/item/ropes.json
```

## 15. Zero-residue audit

Run these from the repository root after all phases. Each command should return no matches unless explicitly noted.

### 15.1 Java/API residue

```bash
rg -n 'net\.minecraftforge|MinecraftForge|ForgeConfigSpec|ForgeRegistries|IForgeRegistry' src/main src/test
rg -n 'SimpleChannel|NetworkRegistry\.ChannelBuilder|NetworkEvent\.Context|registerMessage' src/main src/test
rg -n 'LazyOptional|CapabilityToken|AttachCapabilitiesEvent|reviveCaps|invalidateCaps' src/main src/test
rg -n 'RegisterGuiOverlaysEvent|VanillaGuiOverlay|TickEvent\.(Server|Player|Client)TickEvent' src/main src/test
rg -n 'new ResourceLocation\(' src/main src/test
rg -n 'MULTIPLY_TOTAL|removeModifier\([^)]*UUID' src/main src/test
```

`ForgeCaps` is allowed only in `LegacyPlayerCrimeImporter`, its fixture/tests, and migration documentation. Words such as “Forge 1.20.1” are allowed in explicit historical/upgrade prose.

### 15.2 Build/dependency residue

```bash
rg -n 'net\.minecraftforge\.gradle|org\.spongepowered\.mixin.*version|fg\.deobf|fg\.repository|reobfJar' . \
  --glob '!docs/**' --glob '!CHANGELOG.md'
rg -n 'architectury|forge_version|mapping_channel|mapping_version' build.gradle gradle.properties settings.gradle
rg -n 'MixinConfigs' build.gradle src/main
```

Retaining the Mixin annotation processor is not the same as retaining the old Sponge Gradle plugin; inspect the actual plugin block.

### 15.3 Resource residue

```bash
find src/main/resources -type f -path '*/recipes/*' -print
find src/main/resources -type f -path '*/tags/items/*' -print
rg -n '"item"\s*:' src/main/resources/data/mcacrime/recipe
rg -n 'forge:(rope|tools/)' src/main/resources src/main/java
test ! -f src/main/resources/pack.mcmeta
```

### 15.4 JAR residue

```bash
jar tf build/libs/mcacrime-*.jar | rg \
  '^(net/minecraftforge|net/conczin/mca|dev/architectury|dev/otectus/mcareputation|org/junit)/'
```

No output is expected.

## 16. High-risk behavior audit for `RestraintHandlers`

This class subscribes to many events and is particularly susceptible to a “compiles but no longer enforces” port. Confirm exact NeoForge equivalents and cancellation/result semantics for:

- attack entity;
- right-click item;
- right-click block;
- left-click block;
- entity interact and entity interact-specific;
- block break;
- entity mount;
- living jump;
- player respawn.

For every handler, record:

1. physical/logical side;
2. whether the event is cancellable;
3. whether cancellation alone prevents vanilla behavior or a result must also be set;
4. whether the player's attachment is available at that lifecycle point;
5. whether a denial message can be rate-limited to avoid spam.

Add an integration test or manual case for every action. A restrained player must not bypass restrictions through offhand use, entity-specific interaction, vehicle mounting, respawn, dimension transfer, or a packet sent without the normal client gesture.

## 17. Decisions already resolved

A coding agent should not reopen these without concrete contrary evidence from the pinned APIs:

- Use NeoForge data attachments, not a recreated custom capability layer.
- Add an explicit old `ForgeCaps` importer; matching the attachment ID alone is insufficient.
- Keep world data as `SavedData` with the same filename/key.
- Use named custom payloads and protocol 7; do not emulate `SimpleChannel`.
- Keep payload handlers on the main thread by default.
- Keep client payload implementation physically isolated.
- Use singular recipe/tag paths and the `c:` common namespace.
- Remove `pack.mcmeta` and Architectury.
- Keep reflection-based MCA binding and production-JAR probes.
- Require Java 21.
- Preserve optional Reputation degradation and require a real 1.21.1 companion build for its integration lane.

The one intentional implementation choice left to the agent is whether observation block positions continue writing the legacy compound shape or undergo an explicit schema-7 migration. Prefer retaining the old write shape for a loader-only port unless another 1.21.1 API forces the schema change.

## 18. Stop conditions and escalation points

Pause and report evidence rather than improvising if any of these occurs:

- the target MCA production artifact lacks one of the binding members in §12.1;
- a 1.21.1 MCA: Reputation build/API contract is unavailable but release requirements demand that integration;
- `PlayerEvent.LoadFromFile` observes attachments before/after entity deserialization differently from the pinned source and the new-value precedence cannot be guaranteed;
- a real Forge fixture uses a different capability path or block-position NBT shape than documented here;
- `copyOnDeath()` does not preserve a field in an actual respawn GameTest;
- a client-bound handler cannot be registered without common code resolving client classes;
- the mixin works in dev but its refmap/target fails in the production JAR;
- a required behavior has no NeoForge event with equivalent timing/cancellation semantics.

For each stop, provide the exact dependency version, stack trace or NBT excerpt (redacted of player-identifying data), minimal reproduction, and the smallest decision required. Do not mask it with catch-all reflection, optional injections, default-empty data, or broad exception suppression.

## 19. Suggested pull-request checklist

```markdown
- [ ] Pinned source commit and target versions recorded
- [ ] Existing Forge tests run and legacy fixture captured
- [ ] ModDevGradle / NeoForge / Java 21 build established
- [ ] NeoForge metadata and mixin registration verified
- [ ] Forge event imports and tick phases fully converted
- [ ] Cancellable public events implement ICancellableEvent
- [ ] Items/tabs/config/registries ported
- [ ] Player attachment registered with copyOnDeath
- [ ] Legacy ForgeCaps importer covered by real fixture
- [ ] SavedData/provider-aware NBT and BlockPos compatibility covered
- [ ] ResourceLocation and attribute modifier migrations complete
- [ ] All 15 payloads typed, bounded, directed, and tested
- [ ] Dedicated server proves client-class isolation
- [ ] Recipe/tag paths and JSON schemas ported
- [ ] Screens/list/HUD/rope/render layer ported and manually verified
- [ ] Production mixin/refmap verified
- [ ] Target MCA production JAR binding probe and gameplay matrix pass
- [ ] Standalone optional-Reputation lane passes
- [ ] Compatible Reputation integration lane passes or is explicitly unavailable
- [ ] Legacy world passes exact manifest comparison and two restarts
- [ ] clean check/build, GameTest, dev runs, and production smoke runs pass
- [ ] Zero-residue audits pass
- [ ] User/API/datapack/config/release documentation updated
```

## 20. Primary references

Use versioned 1.21.1 documentation and the pinned source as the authority; later NeoForge docs may describe events that do not exist in 21.1.

- [MCA: Crime source baseline](https://github.com/otectus/MCACrime/tree/abcea64879655307a617d90025cd434950a845d9)
- [NeoForge 1.21.1 documentation](https://docs.neoforged.net/docs/1.21.1/)
- [NeoForge custom payload networking](https://docs.neoforged.net/docs/1.21.1/networking/payload/)
- [NeoForge data attachments](https://docs.neoforged.net/docs/1.21.1/datastorage/attachments/)
- [NeoForge event system](https://docs.neoforged.net/docs/1.21.1/concepts/events/)
- [NeoForge configuration](https://docs.neoforged.net/docs/1.21.1/misc/config/)
- [NeoForge tags](https://docs.neoforged.net/docs/1.21.1/resources/server/tags/)
- [NeoForge recipes](https://docs.neoforged.net/docs/1.21.1/resources/server/recipes/)
- [NeoForge 1.20.4 → 1.20.5 migration primer](https://docs.neoforged.net/primer/docs/1.20.5/)
- [NeoForge 1.21 → 1.21.1 migration primer](https://docs.neoforged.net/primer/docs/1.21.1/)
- [Official NeoForge MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle)
- [ModDevGradle](https://github.com/neoforged/ModDevGradle)
- [MCA Reborn](https://github.com/Luke100000/minecraft-comes-alive)
- [MCA Reborn target tag `7.7.36-beta.3+1.21.1`](https://github.com/Luke100000/minecraft-comes-alive/tree/7.7.36-beta.3%2B1.21.1)

When documentation and the pinned 21.1 source disagree, follow the pinned source and add a comment/test explaining the version-specific choice.

---

### Final instruction to the implementing agent

Make the port **observable and reversible**. Use focused commits, preserve untouched world backups, keep fixture expectations explicit, and attach the command/test evidence for every phase. The two failure modes most likely to hurt users are silent player-data reset and dev-only client success; neither is acceptable even if the project compiles.

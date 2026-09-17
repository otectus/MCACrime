# Townstead support matrix (NeoForge 1.21.1)

What MCA: Crime can actually do with Townstead installed, as the working tree stands. Every row here
is a statement about *this* build; what an individual server sees also depends on which Townstead is
installed, because the whole seam is resolved by reflection at `ServerStartedEvent` and reports what
it found.

Run `/crime debug townstead` on a live server to get the same table for that install, including the
detected Townstead version, the MCA package root it was compiled against, and every manifest member
that did not bind. `/crime debug townstead entity <target>` reports one villager; `village` reports
the settlement around the caller.

This is the NeoForge 1.21.1 port of the Forge 1.20.1 integration and is meant to behave the same way.
The platform differences are listed under [NeoForge divergences](#neoforge-divergences).

## How to read the states

The five strings come from `compat/TownsteadDiagnostics` and are the same ones the command prints.

| State | Meaning |
|---|---|
| `available (api)` | Every required member of the capability bound against the installed Townstead through `compat/townstead/TownsteadBinding`. |
| `available (mixin)` | The mixin that provides it was applied **and** its hook has actually been reached at least once. |
| `degraded (mixin applied, hook not yet observed)` | Applied, but nothing has run through it yet. Ambiguous by nature: it is what a freshly started world looks like, and also what a Townstead point release that moved an injection point looks like forever. |
| `degraded (start-gate only)` | Real, and permanently partial. See work suspension below. |
| `unavailable` | Nothing provides it. For the cooperation capabilities this is the expected answer today. |

A capability is the unit of failure for the whole seam: a miss disables exactly the capability that
needed it, makes the feature resting on it report degraded, and produces one line in the command. It
never disables the bridge and never throws into gameplay.

## Capabilities

`compat/TownsteadCapability` declares eighteen capabilities. Twelve of them bind through the
reflective manifest; the rest are provided by this mod's own hooks, or by nothing at all today.

### Read surface — bound by the reflective manifest

Each of these is `available (api)` when its required members resolve against the installed Townstead,
and `unavailable` when they do not. There is no middle state: the bridge answers every query with a
`TownsteadQueryResult`, and an unbound capability answers empty rather than with a zeroed record.

| Capability | What it reads | Provided by |
|---|---|---|
| `read_villager` | Identity, life stage, age, personality, fertility | `TownsteadBinding` manifest |
| `read_needs` | Hunger, thirst, fatigue, collapse, the fatigue recovery gate | manifest |
| `read_schedule` | Shift mode, template, current and planned activity | manifest |
| `read_profession` | Townstead's own profession id, tier and XP | manifest |
| `read_building` | The registered building at a position: type, size, bounds, owning village | manifest |
| `read_root` | A root (species/ancestry/lineage) definition and its life cycle | manifest |
| `read_calendar` | World day, season, weekday, active calendar profile | manifest |
| `read_spirit` | Village spirit totals, tier and classification | manifest |
| `stage_capabilities` | Whether the current life stage can move, has needs, can be talked to | manifest |
| `dispatch_reaction` | Playing a Townstead reaction on a public consequence transition | manifest |
| `building_enumeration` | A village's buildings with a revision, instead of one lookup per position | manifest |
| `consumption_in_custody` | Letting a held villager eat and drink | manifest |

### Provided by MCA: Crime's own hooks

| Capability | State | Provided by |
|---|---|---|
| `activity_coordination` | `available (mixin)` once both hooks have fired; `degraded (mixin applied, hook not yet observed)` until then; `unavailable` without both mixins | `ReactionLockGateMixin` **and** `GuardRestYieldMixin`, both required — refusing a reaction lock without defending the walk order leaves the guard standing still anyway |
| `equipment_provenance` | the same shape, on one mixin: the work-tool copy hook must have been reached | `WorkToolProvenanceMixin` |
| `client_dialogue_entry` | the same, on the dialogue `init()V` hook. Always `unavailable` on a dedicated server, where the client mixin is never applied, and nothing there depends on it | client `RpgDialogueEntryMixin` |
| `work_suspension` | `degraded (start-gate only)` whenever the gate can refuse anything at all | MCA: Crime's own vanilla brain hook (`ThiefBrainMixin` → `ThiefWorkRegistry` → `ThiefWorkGate`), not a Townstead surface |

`ConfigValidator` asks a deliberately weaker question than the command does — "applied", not "has
fired" — because it runs at setup and on every config reload, before any villager has rested or
reacted. A validator that waited for a handler to run would report every boot as degraded and never
correct itself.

### Not provided by anything today

| Capability | Why | What is affected |
|---|---|---|
| `storage_policy` | Townstead exposes no container-permission surface | `townstead.propertyLaw` (off by default) |
| `rig_attachments` | No surface for querying which humanoid attachment points a root's rig supports | The restraint rig falls back to the humanoid assumption |

These are declared on purpose rather than left out: a switch that needs one has to be able to say
"configured on, capability unavailable, feature degraded" instead of quietly doing nothing.

## Mixins

Second config, `src/main/resources/mcacrime.townstead.mixins.json`: `required: false`,
`defaultRequire 0`, **no refmap key**, and `mixin/townstead/TownsteadMixinPlugin`, which applies
nothing unless Townstead is loaded and the named target class actually resolves as a resource. Every
target is a dotted string, and no mixin here names a Townstead or an MCA type.

Both configs are declared by `[[mixins]]` blocks in
`src/main/resources/META-INF/neoforge.mods.toml`; NeoForge does not honour the Forge-era
`MixinConfigs` JAR manifest attribute, so a config left out of that file would simply never be read.

NeoForge 1.21.1 production runs on Mojang names, so every `@Mixin`, `@Inject` and `@Redirect` is
`remap = false`, as is every `@At` that names a vanilla descriptor, and every vanilla descriptor in a
target is written in Mojang names.

| Mixin | Target class | Hook |
|---|---|---|
| `GuardRestYieldMixin` | `com.aetherianartificer.townstead.tick.GuardRestEnforcerTicker` | Two `@Redirect`s in `tick`, on `Lnet/minecraft/world/entity/ai/Brain;eraseMemory` and `Lnet/minecraft/world/entity/ai/navigation/PathNavigation;stop()V`, so a rest enforcement yields over a villager MCA: Crime holds |
| `ReactionLockGateMixin` | `com.aetherianartificer.townstead.reaction.ReactionLockTracker` | `@Inject` at the head of `lock(Lnet/minecraft/world/entity/LivingEntity;JILnet/minecraft/resources/ResourceLocation;)V`, refusing a reaction lock over a held villager |
| `WorkToolProvenanceMixin` | `com.aetherianartificer.townstead.tick.WorkToolTicker` | Two `@Redirect`s on `Lnet/minecraft/world/item/ItemStack;copy()Lnet/minecraft/world/item/ItemStack;` in `tick` (record the display tool as Townstead's), plus `@Inject`s at the head of `restore` and `forget` (drop the record) |
| client `RpgDialogueEntryMixin` | `com.aetherianartificer.townstead.client.gui.dialogue.RpgDialogueScreen` | `@Inject` at the tail of `init()V` and the head of `removed()V`, adding and clearing MCA: Crime's entry point. Single Mojang-name selectors, where the Forge baseline needs a dual Mojang/SRG selector |

`compat/TownsteadMixinStatus` records applied (from the plugin's `postApply`) and fired (from the
handler itself) as two separate facts, which is what lets the command tell a moved injection point
from a world where nothing has happened yet.

`checkJarContents` fails the build if any `com/aetherianartificer/townstead/` class is ever shaded
into the jar, if the jar carries any mixin config other than exactly `mcacrime.mixins.json` and
`mcacrime.townstead.mixins.json`, if a packaged config is not named by a `[[mixins]]` block in
`neoforge.mods.toml`, or if either config declares a `refmap`. `NoTownsteadStaticLinkTest`,
`NoMcaStaticLinkTest`, `MixinConfigTest`, `TownsteadMixinTargetTest` and `OptionalClassloadTest`
enforce the naming rules.

## Townstead versions this was checked against

The `townsteadProbeTest` Gradle task resolves the binding manifest **and** the mixin target classes
against a real Townstead jar, in its own JVM and its own class loader, paired with the MCA probe
fleet (`mcaProbe<N>`, from `mca_probe_versions` in `gradle.properties`) this branch already uses for
`McaBindingProbeTest`:

```
gradlew townsteadProbeTest -PtownsteadJar=/path/townstead-0.7.7+1.21.1.jar
```

One jar, not two: a NeoForge 1.21.1 Townstead is built against the single MCA package root this
branch ships (`net.conczin.mca`), where the 1.20.1 baseline needs a legacy and a modern variant
because MCA's Forge-era builds straddled a rename. The pairing is still load-bearing — Townstead's
descriptors carry MCA types, so enumerating its API with no MCA on the probe loader reads every
method as unbound.

The probe was run against the NeoForge Townstead 0.7.7 jar and bound all twelve declared manifest
capabilities. The jar had to be built from source; no Maven serves it.

With no jar supplied, the task logs that it was skipped rather than failing, and
`TownsteadBindingProbeTest` skips on an assumption inside the ordinary `test` run.

## Commands

| Command | What it reports |
|---|---|
| `/crime debug townstead` | Bridge state, detected version, MCA variant, per-capability state, unresolved members, mixin layer |
| `/crime debug townstead entity <target>` | The same for one villager |
| `/crime debug townstead village` | The settlement around the caller |
| `/crime validate` | Any `[townstead]` switch that is on while a capability it needs is missing, plus refused `townstead/` datapack tables |
| `/crime duty inspect\|suggest [village]` | Guard coverage per village — guards, on duty, engaged, resting, unfit — and what to assign |
| `/crime facility list\|validate\|assign\|remove\|recognise` | The civic facility table with each entry's current validation status — `validate` is today an alias for `list` and runs no separate check — plus `assign`, `remove`, and the datapack-driven `recognise` shortcut |

## Config

Sixteen keys under `[townstead]`, documented in [CONFIG.md](../../CONFIG.md). Everything is a no-op
with Townstead absent. `enabled` is the master switch. Seven switches default to off: two of them
(`propertyLaw`, which needs `storage_policy`, and `communityService`, which needs `work_suspension`)
because nothing can currently satisfy the capability they name, and the rest because they change
numbers a server owner has already tuned, or because the layer they belong to is a later release.

## Datapack

`data/<namespace>/townstead/{building_roles,personality_profiles,reaction_bindings}/`, documented in
[DATAPACK.md](../../DATAPACK.md). Read on every reload whether or not Townstead is installed; nothing
acts on them until the bridge is bound.

## NeoForge divergences

The integration is the same one the Forge 1.20.1 baseline carries. These are the places where the
platform, not the design, differs:

- **No refmap anywhere.** Neither mixin config declares one, no `@At` is remapped, and there is no
  annotation processor on ModDevGradle — so the baseline's `-AMSG_MIXIN_SOFT_TARGET_NOT_FOUND`
  compiler argument has no counterpart here. The dialogue mixin uses single `init()V` / `removed()V`
  selectors rather than the baseline's dual Mojang/SRG selector.
- **Mixin configs are declared in `neoforge.mods.toml`**, by two `[[mixins]]` blocks, rather than by
  a JAR manifest attribute; `checkJarContents` asserts exactly those two configs and no refmap.
- **Packets are `CustomPacketPayload` records with `StreamCodec`s.** The three added on this line are
  `mcacrime:restraint_rig_sync`, `mcacrime:request_village_security` and `mcacrime:village_security`,
  at `CrimeNetwork.PROTOCOL_VERSION` `"14"`; client handling goes only through
  `CrimeClientPayloadRouter`.
- **One probe jar** (`-PtownsteadJar`), paired with this branch's `mcaProbe<N>` fleet, instead of the
  baseline's legacy/modern pair.
- **Data attachments instead of capabilities**, and client caches are cleared through the
  `client/ClientCaches.ALL` registry; `ClientCachesTest` carries a justified `NON_CACHE_ENTRIES`
  exception for `TownsteadDialogueState` — a one-session dialogue marker that is cleared with the
  caches but lives in `compat/`, because a Townstead mixin reads it and may name no client type.
- **Edibility in custody care** is read as `ItemStack.getFoodProperties(null)`
  (`captivity/CustodyCareService`), the 1.21 shape of the same question.
- **`SavedData` load and save carry a `HolderLookup.Provider`** on this line; the new facility and
  reservation records serialise only scalars, so nothing in them needs it.
- **World schema 13 is byte-identical to the baseline**: root `facilities` and `cellReservations`,
  and the custody `recovery*` keys, with a 12→13 step that writes nothing but the version.

## Not run in this job

The runtime matrix is outstanding. None of the following has been exercised for this work:

- the production jar, as opposed to a dev run;
- a dedicated server, or a multiplayer client against one;
- a client session at all, which is where `client_dialogue_entry` and the restraint rig live;
- save, quit and reload with facilities and cell reservations in the world data;
- removing Townstead from a save that had it.

What has run is the JUnit suite, `build` (including `checkJarContents`), and the Townstead probe
against the NeoForge Townstead 0.7.7 jar.

## Known limits

- **Work suspension is start-gating only.** MCA: Crime start-gates every `Activity.WORK` behaviour on
  a villager it holds a claim on, by wrapping that brain's entries. It cannot stop a producer task
  that is already running: those stage inputs and hold a pending output, there is no external entry
  point that would reconcile them, and a committed recipe is therefore allowed to finish.
- **The restraint rig fallback only sees a stage-level rig override.** A rig difference expressed
  anywhere other than the life stage record is invisible to it, and rendering falls back to the
  humanoid assumption. Every uncertainty answers the same way.
- **An unreadable Townstead role halts guard recruitment for that village** rather than being read as
  "not a worker". That is deliberate — a village briefly short of guards recovers, a village whose
  baker was drafted does not — but it means a partially-bound bridge can leave a village under its
  guard target with no in-game message. `/crime debug guards` carries the reason.

## Asking Townstead for more

The surfaces this integration would need from upstream, and what each one would unlock, are written
up in [docs/TOWNSTEAD_API_REQUESTS.md](../TOWNSTEAD_API_REQUESTS.md).

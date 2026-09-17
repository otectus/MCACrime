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
| `storage_policy` | `available (mixin)` once the hook has fired; `degraded (mixin applied, hook not yet observed)` until then; `unavailable` without the mixin. New in 0.7.4 — Townstead still exposes no container-permission API, so MCA: Crime supplies the exclusion itself | `StoragePolicyMixin`. The hook is reached constantly on a village with workers, so "applied but not observed" here is a sharp signal that the method moved |
| `work_suspension` | `degraded (start-gate only)` whenever the gate can refuse anything at all | MCA: Crime's own vanilla brain hook (`ThiefBrainMixin` → `ThiefWorkRegistry` → `ThiefWorkGate`), not a Townstead surface |

`ConfigValidator` asks a deliberately weaker question than the command does — "applied", not "has
fired" — because it runs at setup and on every config reload, before any villager has rested or
reacted. A validator that waited for a handler to run would report every boot as degraded and never
correct itself.

### Not provided by anything today

| Capability | Why | What is affected |
|---|---|---|
| `rig_attachments` | No surface for querying which humanoid attachment points a root's rig supports | The restraint rig falls back to the humanoid assumption |

These are declared on purpose rather than left out: a switch that needs one has to be able to say
"configured on, capability unavailable, feature degraded" instead of quietly doing nothing.

## Mixins

Second config, `src/main/resources/mcacrime.townstead.mixins.json` — four common mixins and one
client one — is `required: false`,
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
| `StoragePolicyMixin` | `com.aetherianartificer.townstead.storage.StorageSearchContext` | `@Inject` at the `RETURN` of `isProtectedStorage(BlockPos, BlockState)` — the descriptor written in Mojang names, `remap = false`, `require = 0` — forcing `true` for a container MCA: Crime marks protected from auto-sourcing. It never forces the answer the other way: a block Townstead already protects stays protected. Never throws — this runs inside another mod's per-block sourcing scan |
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

## Property law (0.7.4, `townstead.propertyLaw`, off by default)

Ownership is **explicit and never inferred**. A recognised settlement building does not create
ownership and a player's existing chest stays unclaimed; a policy exists only because an operator, a
datapack, or the bounded automatic protection put it there. `property/PropertyAccess` is a pure
table with three answers, and the one carrying the design is `UNKNOWN` — no policy, an actor nobody
could identify, an owner nothing can be compared against — which **never charges anyone**.

Detection is a per-tick matched pair, not open-and-close diffing. `ContainerTransferWatcher`
re-counts the container side and the actor side once per server tick and attributes a quantity only
when the container lost exactly what that actor gained, of the same item, in the same tick. A hopper
emptying a chest produces a loss with no matching gain and charges nobody. An item is fingerprinted
as its registry id plus a hash of the stack's `DataComponentPatch` — 1.21.1's replacement for item
NBT, and the same semantics the Forge baseline gets from a stack tag.

`TransferAttribution` then yields one of five outcomes, and only the first creates a crime:

| Outcome | When |
|---|---|
| `CHARGED` | An identified actor took from a container whose policy refused them |
| `PERMITTED` | The policy allowed it — a deposit, a public ration chest, a resident at their own village's stores |
| `AUTHORISED_WORK` | A live `WorkTransferContext` covers exactly this worker, this source and this moment. A role label alone never does |
| `UNATTRIBUTED` | Nobody could be identified, or nothing claims this container |
| `UNSUPPORTED` | The menu or source is not one a transfer can be proved through; reported as a gap |

Transfers group into one incident per actor, policy and 100-tick window, and one durable
`PropertyReceipt` per transfer id — so a reconnect or a re-observation cannot double-charge.
A receipt is `LOST`, `RESTORED` or `UNRESOLVED`; returning the goods to the same container, or paying
the fine for the case it was charged as, marks it restored. The owner is copied onto the receipt at
the moment of loss, so changing a policy's owner afterwards cannot rewrite who was robbed.

`incident/IncidentContext` decides jurisdiction in a fixed order: the property's community, then the
victim's home, then the event location.

The hooks live in `property/PropertyLawHandlers`: `PlayerInteractEvent.RightClickBlock` positions the
watcher, `PlayerContainerEvent.Open` and `.Close` open and close it, and the sweep runs on
`ServerTickEvent.Post`, NeoForge 1.21.1's end-of-tick half of the Forge-era phased server tick event.

### What transfer detection deliberately cannot see

- **Throwing from a container slot (`Q`).** The container loses and nothing of the actor's gains.
  It is a loss with no matched pair, so it is reported as unattributed rather than charged.
- **Any menu outside the four whitelisted vanilla storage menus** — `ChestMenu`, `ShulkerBoxMenu`,
  `HopperMenu`, `DispenserMenu`. A custom menu, a storage network or a sided item handler is
  `UNSUPPORTED`; nothing is attributed and the gap is reported.
- **A container opened without a right-click MCA: Crime saw.** The watcher is positioned by the
  player's own `RightClickBlock`; a menu opened any other way is not positioned and not watched.
- **An identical item leaving the actor in the same tick it arrived.** The net counts cancel.
  Bounded by the tick, and the failure is in the safe direction.
- **Building-scope "no sourcing" is enforced by neither the transfer hook nor the sourcing mixin.**
  `PropertyRegistry.protectedFromAutoSourcing` — the only question `StoragePolicyMixin` asks — is a
  `(dimension, position)` lookup in the container index, because answering a building claim would
  mean asking the settlement mod which building each block is in, inside its own per-block scan. A
  container-scope policy at a known position is what keeps workers out; a building-scope policy is
  evaluated when a container is opened.

Automatic protection (`townstead.autoProtectGeneratedProperty`) derives policies only from assigned
facilities whose role is `evidence_storage` or `jail_cell`. It always writes the anchor as a
container policy — that is the block the sourcing hot path can answer for — and, where building
enumeration is available, the recognised building at that anchor as a second, container-open-scope
policy. It never walks the world looking for chests, writes derived ids so re-running rewrites the
same rows, and never touches a policy an operator wrote.

## Civic work and economy profiles (0.7.4)

`townstead.communityService` (off by default) offers civic work in place of a fine, for one eligible
case at a time. `civic/CivicTask` has three kinds, and each exists only because something MCA: Crime
already observes can credit it:

| Task | Credited by |
|---|---|
| `guard_assist_patrol` | A bounty resolved with this offender as claimant and the target taken alive or arrested. A kill does not count |
| `restitution_delivery` | A property receipt in this offender's name marked restored. Needs `propertyLaw`, which is what writes the receipt |
| `victim_amends` | An apology by this offender accepted by a villager who remembers the crime |

`civic/ServiceContract` is durable and bound to one case id, moving through `OFFERED`, `ACTIVE`,
`COMPLETED`, `FAILED`, `CANCELLED`. It carries no price: the fine is re-quoted at completion through
`SettlementPolicy`, so a contract cannot lock in yesterday's figure. The fine is never discounted up
front, completion settles that case and nothing else through the ordinary fine path, and a failed or
cancelled contract leaves the original sentence untouched. The `civic_service` action sits in the
self menu beside bail and settle. `civic/CivicWorkHandlers` credits from `BountyResolvedEvent` and
`VictimCrimeMemoryChangedEvent`, sweeps on `ServerTickEvent.Post`, and clears its own state on
`ServerStoppingEvent`.

`townstead.serviceRestrictions` (off by default) applies four rules in order: an essential service is
never refused; a villager who personally remembers being harmed may refuse, and that refusal fades
with the memory rather than being cached; an open case this settlement itself knows about refuses a
non-essential service, but only when the subject is wanted, or the service is a luxury and their band
is Outlaw — and a fence is exempt from the public rule altogether, since its whole trade is with
people the law is after; standing alone never refuses anything. Surrender, restitution and settling a
case are not routed through the decision at all, so a route back always exists.

`townstead.economyProfiles` (off by default) is three fixed profiles — `FRONTIER`, `TOWN`,
`PROSPEROUS` — with every multiplier clamped to `[0.5, 2.0]`, applied to `SettlementPolicy` fines,
`BountyService` bounties, `FenceOfferBuilder` prices and `VillagerPurse` refill. The refill
multiplier may only ever select an amount at or below the configured one, so no village can mint
currency. `EconomyProfileResolver` re-reads the profile rather than persisting it, behind the ordinary
`snapshotCacheTicks` cache, and answers `TOWN` for every question it cannot answer, which is why the
switch reports *degraded* rather than off when the capability is missing: every village is being
priced, as an ordinary town.

Companions read both layers through `api/McaCrimeApi.serviceRefusal` and `civicContracts`, returning
`ServiceRefusalView` and `CivicContractView` — read-only, with no way to accept or advance a
contract. No contract is created while `communityService` is off, so a server that has never enabled
it always reads an empty list; contracts created earlier stay listed. See [API.md](../../API.md).

## World data

Schema **14** (`state/world/CrimeDataMigrations.SCHEMA_PROPERTY_LAW`) adds the root tags
`propertyPolicies`, `propertyReceipts` and `serviceContracts`. The 13→14 step writes only the
version, because every new switch is off by default and nothing is written until an operator turns
one on; a schema-13 world therefore loads unchanged. The layout is byte-identical to the Forge
baseline's schema 14, written and read inside this line's lookup-aware `SavedData` save and load.

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

The probe was run against the NeoForge Townstead 0.7.7 jar, with all five mixin targets including
`StorageSearchContext.isProtectedStorage`, and bound all twelve declared manifest capabilities. The jar had to be built from source; no Maven serves it.

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
| `/crime property list\|inspect [pos]` | The property policies in the world, and the policy plus access decision at one position. Permission 2 |
| `/crime property protect <rule> [pos]\|release [pos]` | Declare or withdraw a policy by hand. Permission 3 |
| `/crime service list [offender]` | Civic service contracts and their progress. Permission 2 |
| `/crime service offer <offender> <task>\|cancel <contract>` | Issue or withdraw a contract. Permission 3 |
| `/crime facility list\|validate\|assign\|remove\|recognise` | The civic facility table with each entry's current validation status — `validate` is today an alias for `list` and runs no separate check — plus `assign`, `remove`, and the datapack-driven `recognise` shortcut |

## Config

Sixteen keys under `[townstead]`, documented in [CONFIG.md](../../CONFIG.md). Everything is a no-op
with Townstead absent. `enabled` is the master switch. Seven switches default to off, and five of
them are what this release is about:

| Switch | What turning it on does | Needs |
|---|---|---|
| `propertyLaw` | Marked containers and buildings become property with an owner; taking from one can be a crime, and those containers are reserved from settlement auto-sourcing | `storage_policy` + `read_building` |
| `autoProtectGeneratedProperty` | Derives policies for assigned `evidence_storage` and `jail_cell` facilities, and the recognised building at those anchors — nothing else | `building_enumeration` |
| `serviceRestrictions` | A settlement may refuse non-essential services to an outlaw; essentials never, and the route back is never refused | `read_profession` + `read_schedule` |
| `communityService` | An eligible case can be settled by civic work instead of its fine | `activity_coordination` + `read_schedule` |
| `economyProfiles` | A village's character scales fines, bounties, fence prices and purse refills | `read_spirit` |

`communityService` asked for `work_suspension` in 0.7.3, which `TownsteadBridge.has` never grants —
the start-gate is MCA: Crime's own vanilla brain hook and is permanently partial, so the row could
only ever report degraded however well the feature worked. What a contract actually consumes is an
activity claim on an NPC offender and that villager's schedule. The start-gate's limitation is stated
in the config comment instead, which is where a limitation belongs.

`/crime validate` reports two contradictions between switches: `autoProtectGeneratedProperty` on
while `propertyLaw` is off (policies are written and nothing evaluates them), and `communityService`
on while `jail.enableFines` is off (civic work only replaces a fine that could have been paid).

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
- **World schema 14 is byte-identical to the baseline** as well: root `propertyPolicies`,
  `propertyReceipts` and `serviceContracts`, with a 13→14 step that writes nothing but the version,
  inside this line's lookup-aware `SavedData` save and load.
- **Item identity is a `DataComponentPatch` hash.** 1.21.1 has no item NBT, so
  `property/ContainerTransferWatcher` fingerprints a stack as its registry id plus a hash of the
  stack's data components — the same semantics as the baseline's tag-based fingerprint, documented in
  that class's javadoc.
- **The property and civic hooks use this line's event shapes**: `ServerTickEvent.Post` for both
  sweeps, `PlayerInteractEvent.RightClickBlock`, and `PlayerContainerEvent.Open` / `.Close`.
- **`StoragePolicyMixin` names its target and its descriptor in Mojang names with `remap = false`**
  and carries no refmap, like every other mixin on this line; `checkJarContents` still asserts exactly
  the two mixin configs and no refmap in either.

## Not run in this job

The runtime matrix is outstanding. None of the following has been exercised for this work:

- the production jar, as opposed to a dev run;
- a dedicated server, or a multiplayer client against one;
- a client session at all, which is where `client_dialogue_entry` and the restraint rig live;
- save, quit and reload with facilities, cell reservations, property policies, receipts and service
  contracts in the world data;
- removing Townstead from a save that had it;
- property law or civic work exercised in a live world at all — no container has been robbed, no
  contract completed, and no worker has been turned away from a reserved chest in play.

What has run is the JUnit suite, `build` (including `checkJarContents`), and the Townstead probe,
whose mixin-target checks now include `StorageSearchContext.isProtectedStorage`, against the
NeoForge Townstead 0.7.7 jar.

## Known limits

- **Work suspension is start-gating only.** MCA: Crime start-gates every `Activity.WORK` behaviour on
  a villager it holds a claim on, by wrapping that brain's entries. It cannot stop a producer task
  that is already running: those stage inputs and hold a pending output, there is no external entry
  point that would reconcile them, and a committed recipe is therefore allowed to finish.
- **The restraint rig fallback only sees a stage-level rig override.** A rig difference expressed
  anywhere other than the life stage record is invisible to it, and rendering falls back to the
  humanoid assumption. Every uncertainty answers the same way.
- **Property law can only charge what it can prove.** The blind spots above are deliberate: every
  one of them fails towards reporting rather than accusing, because a rule that charges a player for
  a loss it cannot prove they caused is worse than one that misses a theft.
- **`storage_policy` is MCA: Crime's own mixin, not a Townstead API.** Townstead still exposes no
  container-permission surface. A Townstead release that moves or renames
  `StorageSearchContext#isProtectedStorage` degrades the capability — `require = 0`, so the game
  still loads — and settlement workers may then source from a reserved container again.
- **The storage hook needs to know which level it is in.** It takes the level from the villager
  whose tick the search is running inside, and falls back to the overworld only when the server has
  exactly one level. Anything less certain leaves Townstead's own answer alone, so on a multi-level
  server a search outside a villager tick is not filtered.
- **An unreadable Townstead role halts guard recruitment for that village** rather than being read as
  "not a worker". That is deliberate — a village briefly short of guards recovers, a village whose
  baker was drafted does not — but it means a partially-bound bridge can leave a village under its
  guard target with no in-game message. `/crime debug guards` carries the reason.

## Asking Townstead for more

The surfaces this integration would need from upstream, and what each one would unlock, are written
up in [docs/TOWNSTEAD_API_REQUESTS.md](../TOWNSTEAD_API_REQUESTS.md).

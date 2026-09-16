# MCA: Crime 0.7.2
## Masks, the Mask Station, Exclusive Thief Professions, Sand Bottles, and Peaceful Apologies

**Document type:** Implementation specification and coding-agent handoff  
**Requested release:** 0.7.2  
**Primary target:** Minecraft 1.20.1, Forge, Java 17  
**Repository:** `otectus/MCACrime`  
**Review date:** September 13, 2026  
**Inspected ref:** `main` at `fdb602428c101c2364a0e7bb0cc5cba57aeedce1`

> **Important baseline distinction:** The inspected public ref declares version **0.6.4**, not 0.7.1. Its `CrimeItems` registration contains the three restraint items, not the masks mentioned in the player feedback. The branch listing returned `main` and `neoforge/1.21.1`. This specification targets the requested **0.7.2** release, but does not pretend to have inspected an unpublished or otherwise unavailable newer mask implementation. Reconcile this document with the actual latest development checkout before coding. Preserve newer mask identifiers, currency support, family behavior, and compatibility fixes rather than replacing them with the older inspected implementation. [R01][R02][R03]

**Evidence labels used below:** **Observed** means supported by the inspected source. **Inference** means a plausible explanation requiring reproduction. **Required** means a release requirement. **Proposed** means a concrete design selected for this update, rather than a claim about existing behavior. All new names, recipe formats, numerical tuning, and class additions are proposals unless identified as existing.

This was a read-only source review. No production code was modified and no Minecraft build, dedicated server, client, or compatibility pack was executed during preparation. The verification work below is part of implementation, not a claim that these features already work.

---

## Contents

1. [Release intent and scope](#1-release-intent-and-scope)
2. [Repository findings and engineering constraints](#2-repository-findings-and-engineering-constraints)
3. [Non-negotiable invariants](#3-non-negotiable-invariants)
4. [Mask collection and gameplay identity](#4-mask-collection-and-gameplay-identity)
5. [Mask appearance, equipment, and customization](#5-mask-appearance-equipment-and-customization)
6. [Mask Station block and user experience](#6-mask-station-block-and-user-experience)
7. [Recipe and customization data contracts](#7-recipe-and-customization-data-contracts)
8. [Authoritative crafting and networking](#8-authoritative-crafting-and-networking)
9. [Thief as an exclusive, visible profession](#9-thief-as-an-exclusive-visible-profession)
10. [Worksite acquisition and occupational lifecycle](#10-worksite-acquisition-and-occupational-lifecycle)
11. [Guards must never mug](#11-guards-must-never-mug)
12. [Empty-hand apologies and interaction compatibility](#12-empty-hand-apologies-and-interaction-compatibility)
13. [Sand Bottle item and combat behavior](#13-sand-bottle-item-and-combat-behavior)
14. [Witnesses, identity, and legal consequences](#14-witnesses-identity-and-legal-consequences)
15. [Compatibility and companion-mod contracts](#15-compatibility-and-companion-mod-contracts)
16. [Configuration and synchronization](#16-configuration-and-synchronization)
17. [Save migration and preservation](#17-save-migration-and-preservation)
18. [Implementation map and architecture](#18-implementation-map-and-architecture)
19. [Assets, accessibility, and player-facing polish](#19-assets-accessibility-and-player-facing-polish)
20. [Implementation phases](#20-implementation-phases)
21. [Verification matrix](#21-verification-matrix)
22. [Performance, diagnostics, and release criteria](#22-performance-diagnostics-and-release-criteria)
23. [Further expansion without scope creep](#23-further-expansion-without-scope-creep)
24. [Coding-agent execution instructions](#24-coding-agent-execution-instructions)
25. [Source references](#25-source-references)

---

## 1. Release intent and scope

### 1.1 The player experience

A player should be able to build a Mask Station, place familiar materials into it, see several attractive mask styles that use those same materials, choose one, and receive exactly the mask previewed. The process should feel like a focused vanilla workstation, not a new research tree or a collection of nearly identical crafting-table recipes.

Thieves should be understandable inhabitants of the village. A Thief is actually a **Thief**, not a farmer who secretly retains a second criminal job. The Mask Station is their workstation. Guards, archers, and other configured law responders must never initiate or complete an NPC mugging, including after an old save is upgraded or another mod changes an occupation.

A Sand Bottle supplies a short, readable escape or disruption opportunity. It blinds eyes and interrupts visual awareness; it does not erase an identity, cancel criminal history, permanently disable an enemy, or authorize attacks through protected areas.

A player who wants to make amends should put away their weapon and right-click the affected villager with an empty main hand. Sneaking must not be required. The interaction must not accidentally pick the villager up, and it must not replace normal MCA conversation for villagers who have no relevant unresolved grievance.

### 1.2 Required 0.7.2 deliverables

| Workstream | Required outcome |
|---|---|
| Mask collection | A coherent collection including a bandana and a hockey-style mask, with multiple styles for each supported material family. |
| Mask Station | One craftable workstation, a selectable-output menu, complete block assets, recipes, loot, and Thief worksite integration. |
| Customization | Dyeing and paid same-material restyling through the same menu, without identity or item-data laundering. |
| Thief occupation | An exclusive, persisted occupation reflected in MCA's real profession and normal profession presentation. |
| Guard correction | Central actor eligibility, checks throughout mugging, prevention of cross-assignment, and repair of conflicting saved roles. |
| Apology correction | Contextual non-sneaking empty-hand apology, preserving normal conversation, pickup gestures, cooldowns, and legal consequences. |
| Sand Bottle | A complete craftable throwable with bounded effects, functional AI awareness changes, provenance, protection checks, assets, and tests. |
| Preservation | Safe migration of existing NPCs, masks, records, cooldowns, inventories, relationships, and supported integrations. |
| Release quality | Documentation, automated coverage, actual client/server verification, and no placeholder assets. |

### 1.3 Deliberate boundaries

Do not turn this update into a loader port, a replacement for MCA's entity system, a rewrite of the justice economy, a new reputation implementation, or a procedural disguise simulation. Do not add a GUI for every mask material. Do not add an entire gang-management system, automatic player-chest theft, or new compulsory magic/animation dependencies.

Do not make masks, mask crafting, or employment at a Mask Station intrinsically criminal. A visible occupation is not proof that a particular offense occurred. Preserve witness-based enforcement and the separation between Heat, moral standing, relationship memory, and legal cases.

---

## 2. Repository findings and engineering constraints

### 2.1 Verified baseline

The inspected properties and build configuration identify Minecraft **1.20.1**, Forge **47.4.10**, Java **17**, ForgeGradle **[6.0,6.2)**, and official mappings. The development MCA runtime is **7.6.20+1.20.1**. The listed binding probes additionally cover **7.7.0-beta.2+1.20.1** and **7.7.1-alpha.2+1.20.1**. These are inspected configuration values, not a new promise that every combination has been tested for this update. [R01][R04]

MCA is mandatory at runtime, but common production code accesses its internals through the existing compatibility binding rather than static MCA imports. The repository instructions require COMMON and CLIENT configuration, server authority for COMMON values, side separation, packaging checks, and preservation of the public API. Follow those conventions. [R05][R06]

### 2.2 Findings that directly affect this update

| Finding | Evidence and consequence |
|---|---|
| Criminal professions are currently presentation-only. | `CriminalProfessions` explicitly describes that model and registers both professions with `PoiType.NONE`. Thief needs a real Mask Station POI and occupational behavior. Fence must not accidentally inherit that worksite. [R07] |
| A criminal role can be independent of a displayed profession. | `WorldCriminalJobService` stores the role, optionally applies a visible profession, and restores a previous profession when presentation is disabled. That model conflicts with the new exclusive, always-visible Thief requirement. [R08] |
| Assignment-time guard checks are insufficient. | `CriminalJobAssigner` excludes a candidate already classified as guard/archer, and its sweep gathers that state at assignment time. It does not establish a lifetime invariant. [R09][R10] |
| The actor's law role is not checked in the inspected mugging participant gate. | `NpcMuggingService.participantAbortReason` checks the saved Thief role, awareness, custody, relationship protection, and other conditions, but contains no actor `isResponder` exclusion. A stale role can therefore remain eligible under that function. This is a concrete gap, not proof of the exact player's failure sequence. [R11] |
| Another promotion path can cross the role boundary. | The inspected `GuardPopulationService.topUp` accepts adult, non-important-profession candidates without an explicit criminal-role exclusion before `makeGuard`. That is a plausible contributor to conflicting guard/Thief records. Verify the actual latest code and MCA's own promotion paths. [R12] |
| A useful responder predicate already exists. | `EntitySelectors.isResponder` includes MCA guards, MCA archers, and configured responder IDs/tags. Reuse and strengthen it rather than inventing a name-based classifier. [R13] |
| The current apology shortcut requires sneaking. | `MemoryInteractionHandler` uses sneaking plus an empty main hand and opens the Crime menu from an `EntityInteract` subscriber. Its off-hand handling and refusal behavior must be deliberately reconciled, not duplicated. [R14] |
| The actual apology is already bounded. | `ApologizeActionHandler` checks eligibility and held weapons, delegates to memory reconciliation, and adds one heart only while hearts are negative. Reuse that authority. [R15] |
| Previous-profession serialization has a relevant inconsistency. | The job service uses an empty string to mean an unreadable previous profession, but `CriminalVillagerRecord.save` omits blank strings. The distinction from “no snapshot” does not survive that serialization. Replace this ambiguity in the new migration model. [R08][R16] |

### 2.3 Preflight for the coding agent

Read the current `AGENTS.md`, if present, then `CLAUDE.md`, `MODMAP.md`, build configuration, and relevant current implementation. Capture branch, commit, worktree changes, runtime dependencies, mod version, and test tasks in `docs/0.7.2/BASELINE.md`.

Search the actual checkout for mask/disguise code, newer family safeguards, shared mugging protection, configurable currencies, and combat compatibility work. Map those implementations onto this specification. Existing functional services take precedence over the proposed class names below.

The inspected instructions say no GameTests exist, while the apology verification document discusses real-MCA GameTests on the NeoForge branch. Treat documentation and branch-specific test availability separately: inspect the actual target's source sets and Gradle tasks before selecting commands. Do not copy a claimed test count from a document and report it as a new run. [R05][R17]

---

## 3. Non-negotiable invariants

These invariants should appear in code comments near their owners and in executable tests.

1. **A law responder is never an NPC mugger.** Sleeping, being off duty, having low karma, being unarmed, wearing a mask, or retaining an obsolete Thief record does not remove that exclusion.
2. **An active Thief has one occupation.** The native MCA profession, Crime occupation record, workstation behavior, and player-visible profession agree. “Farmer + hidden Thief” is not a supported state.
3. **No hidden Thief fallback.** An unsupported profession binding suspends Thief acquisition/behavior with a useful diagnostic; it does not silently create covert dual-profession thieves.
4. **One worksite claim has one owner.** A Mask Station can have multiple player viewers, but no more than one villager worksite claim.
5. **Selecting a style is not crafting.** Scrolling, previewing, selecting, and closing a screen never consume materials or mint items.
6. **The server selects only an allowed output.** The client expresses a selection from an authoritative recipe list; it never supplies the item, quantity, NBT, price, or entitlement.
7. **A successful craft has one debit and one delivery.** Failed capacity checks, stale menus, invalid recipes, and replays produce neither.
8. **Appearance changes preserve identity and item history.** Dyeing, renaming, or restyling does not repair damage or erase curses, provenance, ownership, criminal evidence, or recognition already established by a witness. A separately supported legitimate repair may restore durability, but must still preserve identity and history.
9. **A mask matters only when validly worn.** Inventory contents and cosmetic duplicates do not stack concealment benefits.
10. **Apologies are not pardons.** Preserve settling time, per-incident limits, bounded heart repair, active-threat checks, case state, and charges.
11. **One interaction produces one action.** Main/off-hand and generic/specific entity interaction paths cannot produce duplicate apologies, menus, projectiles, or consumption.
12. **Sand affects sight, not history.** It cannot erase an observed offense, discovered identity, sound, a valid warrant, or a previous legal response.
13. **Blindness is temporary and bounded.** No stun-lock from alternating attackers or item stacks; no permanently removed goals or AI ownership.
14. **Unloaded does not mean deleted.** No forced chunk loading for worksite searches, migration, packet validation, or stale-role cleanup.
15. **Preserve the entity.** Never replace a villager to change an occupation. UUID, family, relationships, home, inventory, quests, custody, and remembered incidents survive.
16. **Canceled and protected actions stay canceled.** Do not un-cancel another mod's event to force an apology, craft, projectile effect, or profession change.

---

## 4. Mask collection and gameplay identity

### 4.1 Launch collection

Implement four material families with four styles each: **16 launch styles**. Existing equivalent styles count toward the collection; do not duplicate a working mask under a second registry ID just to satisfy the table.

| Family | Style | Visual direction | Gameplay intention |
|---|---|---|---|
| Cloth | Bandana | Folded triangular covering over the lower face, with a small tied back. | The accessible outlaw classic requested by players. |
| Cloth | Highwayman's Domino | A narrow eye mask and discreet ties. | Duelist, burglar, and masquerade outfits. |
| Cloth | Wrapped Scarf | Layered fabric around nose and mouth, not a full new hood model. | Desert traveler or roadside bandit. |
| Cloth | Half Veil | A light, draped lower-face silhouette. | Elegant and understated disguises. |
| Leather | Cutpurse Mask | A shaped leather half-mask with a restrained seam. | A practical everyday thief identity. |
| Leather | Raven Mask | A compact beaked silhouette, not an enormous protrusion. | A plague-doctor-inspired fantasy style, without promising filtration. |
| Leather | Jackal Mask | Angular cheeks and short stylized ears. | An animal-themed rogue look. |
| Leather | Stitched Mask | Patchwork panels and visible coarse stitching. | A rough scavenger or intimidator style. |
| Clay | Hockey Mask | A generic perforated pale faceplate. | The requested hockey-mask style, without copying a film character's markings. |
| Clay | Blank Clay Mask | A smooth, neutral expression with clean eye openings. | An anonymous theatrical look. |
| Clay | Comedy Mask | A readable smiling theatrical face. | Festival, actor, or mischievous thief themes. |
| Clay | Tragedy Mask | A contrasting sorrowful theatrical face. | Dramatic and gothic outfits. |
| Metal | Iron Skull Mask | Stylized cheekbones and jaw openings. | An intimidating armored aesthetic. |
| Metal | Brigand Visor | A simple riveted faceplate with a horizontal opening. | A utilitarian mercenary aesthetic. |
| Metal | Owl Mask | Compact brow and symmetrical eye forms. | A nocturnal burglar theme. |
| Metal | Blank Iron Mask | An austere metal plate with minimal features. | A cleaner alternative to the skull style. |

Cloth, leather, clay, and metal should look materially different. Do not produce sixteen recolors of the same model. Conversely, keep the silhouettes restrained enough to fit Minecraft heads, normal animations, and MCA presentation without severe clipping.

### 4.2 Balance: style is primarily expression

Reuse the newer mask system's existing concealment rules once located. Within one material family, all four styles must have equal baseline concealment and any existing wear budget. Do not make the hockey mask the only rational option or attach undocumented intimidation bonuses to horror-themed art.

For newly introduced families, use the existing baseline mask effect unless a tested design requires a distinction. Material can change appearance and recipe cost without creating a new stat ladder. New masks should not grant armor, toughness, poison resistance, sand immunity, or magical anonymity merely because their model resembles protective equipment.

Do not add mask durability solely to justify higher material costs. If the actual newer implementation already has durability or concealment wear, retain it and document the behavior. Avoid inventing an additional durability system alongside it.

### 4.3 Identity rules

A mask can reduce the quality of a **new visual observation** under the established disguise system. It must not clear accumulated Heat, change an actor UUID, erase a wanted record, or make a witness forget a face they already identified.

A witness who observes an offender put on, remove, recolor, or change a mask can retain continuity. Two players wearing identical masks are not thereby the same person. A hidden actor UUID available to the server must not leak into the witness's knowledge merely because an item or projectile is server-owned.

Do not create sixteen parallel disguise systems. All valid masks feed one authoritative disguise resolver. Retain the existing resolver if present in the actual target.

---

## 5. Mask appearance, equipment, and customization

### 5.1 Item representation

First preserve any existing registry and NBT contract. For genuinely new styles, the recommended default is one registered item per style, with reusable `MaskItem` behavior and shared material/render definitions. This makes names, recipes, creative inventory, tags, loot, and item search predictable.

If the newer code already uses one item with a persistent style key, extend that representation instead. Do not migrate a functioning style system only for architectural preference. In either representation, persisted style identifiers are namespaced IDs, not array positions or enum ordinals.

The server derives gameplay properties from registered definitions and validated tags. An arbitrary client-supplied NBT field such as `concealment: 999` must never grant a benefit.

### 5.2 Equipment behavior

The default functional slot is the normal head slot, unless the current shipped mask system already provides a different supported slot. A mask should be equipable by the normal inventory flow and consistent right-click equipping behavior. Replacing a helmet must obey normal equipment restrictions; do not silently delete or drop it.

Preserve existing optional Curios or cosmetic-slot integration if present. Do not introduce a mandatory dependency for 0.7.2. If more than one supported slot can contain a mask, choose one authoritative mask using a documented precedence and grant the effect once. A purely cosmetic mask must not grant hidden gameplay effects.

Test players, armor stands where supported, and MCA NPCs separately. Player head rendering is not evidence that an MCA villager mask renders correctly. First-person screens should not acquire a compulsory dark overlay that obstructs normal play.

### 5.3 Dyeing

Support a single primary tint on appropriate fabric, leather, clay decoration, and metal accent layers. Untinted structural layers preserve readable details. Use the actual existing color format; otherwise use one documented 24-bit RGB value compatible with the chosen item's dye handling.

Dyeing costs one valid dye per operation and changes appearance only. A resource pack supplies textures and model geometry; it does not supply server-side concealment values. Forge's item-color mechanism provides a client-side tint path, but equipped models need their own consistent tint application rather than relying on the inventory icon handler alone. [F05]

If standard leather-item dye recipes are deliberately supported, verify that interface and recipe behavior against the actual 1.20.1 implementation. Do not assume that a leather-looking item automatically participates.

### 5.4 Restyling at the same station

The station has two modes: **Make** and **Restyle**. A mask placed in the material slot selects Restyle automatically; a compact mode label makes the change visible.

Restyling is restricted to compatible styles in the same material and wear-budget class. It costs one binding item appropriate to that family; an optional dye costs one additional dye. It consumes exactly one old mask and produces exactly one selected replacement. Selecting the already-current style with no actual color change is a disabled no-op, not a way to cycle recipe rewards.

Preserve custom name, damage/wear, enchantments, curses, relevant ownership and provenance metadata, and compatible capabilities. Do not copy unsafe or meaningless internal caches into the new item. Explicitly enumerate metadata handled by the existing mask implementation. If a capability cannot be safely transferred, block that conversion with an explanatory message instead of losing it silently.

Do not allow restyling to repair an item or convert a limited-life mask into a permanent one. Where two allowed items have different maximum durability, preserve used-life proportion conservatively and verify no wear is recovered; preferably disallow that pairing in the initial release. Recoloring an item of the same type preserves the exact damage value.

### 5.5 Existing masks and removed definitions

Keep shipped item IDs and their recipes working unless a documented compatibility-preserving migration is necessary. A missing style supplied by a removed add-on uses a safe fallback appearance and no unexpected extra benefit; it does not crash rendering or erase the item. Unknown persisted data must never be interpreted as an unrestricted concealment profile.

---

## 6. Mask Station block and user experience

### 6.1 The block

Proposed ID: `mcacrime:mask_station`. Use a compact woodworking/leatherworking table with a mask form, material scraps, and a few restrained tools. One horizontal-facing block is enough. No ticking block entity is required for the proposed station.

Provide a block item, state/model variants for all horizontal directions, a sensible collision shape, a survival drop, pick-block support, correct mining tags, and localized name. Match the project's normal wood-workstation hardness and tool behavior after inspecting its conventions.

Proposed crafting-table recipe:

```text
S L S
P C P
P P P

S = string
L = leather
P = any planks
C = clay ball
Result = 1 Mask Station
```

The workstation is usable by anyone allowed to interact with that location. Its presence does not mark a settlement criminal. A villager's worksite claim does not lock the player out of crafting.

### 6.2 Stateless workstation, private working inventories

Use a per-open-menu working inventory, backed by a server-side container owned by that viewer's menu session. The block has no shared item storage, no crafting timer, no fuel, and no hopper inventory. This avoids one player taking another player's inputs and avoids shared-result races.

Two players can use one station simultaneously. Each sees only their own inputs and selected style. On ordinary close, death, disconnect handling, invalidation, or station removal, return real input items to that player's inventory using the platform's established container cleanup path; overflow is dropped once at the appropriate player location. A virtual output preview is never returned or dropped.

Do not write transient menu inputs into the villager's inventory or worksite record. Crash recovery should be no worse than the platform's normal ephemeral crafting menus; do not claim transactional durability across a process crash without implementing and testing it.

### 6.3 Layout

Use a familiar vanilla-sized workstation layout, with compact adaptive spacing where necessary:

```text
+--------------------------------------------------+
| Mask Station                         Make/Restyle |
|                                                  |
| Material [ ]   Binding [ ]   Optional dye [ ]     |
|                                                  |
| Styles:  [icon] [icon] [icon] [icon]   Preview     |
|          [icon] [icon] [icon] [icon]     [mask]    |
|                                                  |
| Selected: Hockey Mask                 Output [ ] |
| Cost: 4 Clay Balls + 2 String                     |
|                                                  |
| Player inventory                                 |
| [ ][ ][ ][ ][ ][ ][ ][ ][ ]                       |
| [ ][ ][ ][ ][ ][ ][ ][ ][ ]                       |
| [ ][ ][ ][ ][ ][ ][ ][ ][ ]                       |
| [ ][ ][ ][ ][ ][ ][ ][ ][ ]                       |
+--------------------------------------------------+
```

The sketch is conceptual, not pixel-exact. Implement a layout that remains usable at the supported GUI scales and small window sizes. The preview can be a larger rendered item; a complex spinning player mannequin is unnecessary.

### 6.4 Interaction details

With no materials, show a subdued catalog and a short instruction: **“Add materials, then choose a mask style.”** Once a material family is recognized, show its allowed outputs. If materials are insufficient, show the styles but disable extraction and explain the missing quantity.

Selection has a border, name, and focus state, not just a color shift. Hover and keyboard focus reveal material, cost, and any relevant wear rule. Arrow keys navigate the grid; Tab moves between functional controls; activation selects a style; taking the output remains the crafting action. Support narration for name, selected state, cost, and unavailable reason.

Keep the last valid selection while changing stack counts or dye. If the material family or server recipe generation changes incompatibly, clear the selection and preview rather than silently selecting a different item. Never auto-craft on selection.

Use only a short sound on successful crafting. No screen shake, flashing warnings, camera zoom, looping crafting animation, or animated background is needed.

### 6.5 Worksite integration is independent

Register the Mask Station POI with all valid station block states and one villager claim. Bind only the Thief profession to that POI. Establish actual acquire/hold behavior and verify it using the supported MCA jars; setting a string in the profession record is not a substitute for a functioning worksite.

A station can be claimed while its player menu is open. NPC work animation is cosmetic and must not consume player inputs, generate free stock, or trigger client-side crafting.

---

## 7. Recipe and customization data contracts

### 7.1 Use identical inputs, not randomized outputs

The simplest reliable implementation is a custom `mcacrime:mask_making` recipe type with **one deterministic output per recipe**, and multiple recipes sharing exactly the same input requirements. The menu gathers all relevant recipes and lets the player choose among them. This delivers the requested “same recipe/materials, different selectable styles” behavior without ambiguous crafting-table recipes.

Do not use a first-match-only lookup and accidentally hide three of the four styles. Do not roll the output randomly. Do not require a unique rare ingredient or a separate template for each ordinary launch style.

Forge 1.20.1 custom recipes use a recipe implementation, recipe type, and serializer. Input matching and preview assembly must not mutate the supplied inventory, and assembly must return a fresh output stack. [F02]

### 7.2 Proposed default costs

| Material family | Material slot | Binding slot | Styles available from these exact inputs |
|---|---|---|---|
| Cloth | 1 item in `minecraft:wool` | 1 string | All four cloth styles |
| Leather | 2 leather | 1 string | All four leather styles |
| Clay | 4 clay balls | 2 string | All four clay styles |
| Metal | 2 iron ingots | 1 leather | All four metal styles |

One dye in the optional slot applies a supported tint during crafting. When that slot is empty, the mask uses its default appearance. A non-dye in the slot is rejected, not silently ignored or consumed.

The clay recipe intentionally uses a simple Minecraft crafting abstraction: forming a wearable clay mask does not introduce a kiln or mandatory intermediate item in this update. Pack authors can replace the recipe with terracotta, a fired blank from another mod, or other ingredients. Do not imply that the workstation has simulated firing without implementing a fuel/heat system.

Use dedicated input tags where pack authors benefit, but do not treat every item in a broad “metal” tag as equivalent to iron without a deliberate recipe override.

### 7.3 Proposed craft JSON

Path: `data/mcacrime/recipes/mask_station/clay/hockey_mask.json`

```json
{
  "type": "mcacrime:mask_making",
  "operation": "craft",
  "group": "mcacrime:clay_masks",
  "material": {
    "ingredient": { "item": "minecraft:clay_ball" },
    "count": 4
  },
  "binding": {
    "ingredient": { "item": "minecraft:string" },
    "count": 2
  },
  "allow_dye": true,
  "result": { "item": "mcacrime:hockey_mask", "count": 1 },
  "sort_order": 0
}
```

The blank, comedy, and tragedy mask recipes have the **same material and binding fields**, changing only the output and display order. This example specifies a new serializer contract; it is not a JSON format already known to exist in the inspected repository.

### 7.4 Proposed restyle JSON

```json
{
  "type": "mcacrime:mask_making",
  "operation": "restyle",
  "group": "mcacrime:clay_masks",
  "material": {
    "ingredient": { "tag": "mcacrime:masks/clay" },
    "count": 1
  },
  "binding": {
    "ingredient": { "item": "minecraft:string" },
    "count": 1
  },
  "allow_dye": true,
  "result": { "item": "mcacrime:hockey_mask", "count": 1 },
  "sort_order": 0
}
```

The assembly service additionally checks same-material/wear compatibility and metadata transfer. The tag alone is not permission to erase arbitrary data from a third-party item. Recipe authors can select an output, but only registered supported mask conversions are allowed to use the protected restyle path.

### 7.5 Validation and reload

Reject empty/unknown results, result counts other than one for these mask operations, nonpositive ingredient counts, unsupported operations, malformed resource IDs, invalid conversion pairs, and oversized payloads. The fixed station layout supports two counted ingredient slots plus optional dye; do not advertise an unlimited ingredient list that its menu cannot represent.

Recipe matching uses explicit quantities as well as `Ingredient.test`. Recipe assembly copies stacks. Consumed items' crafting remainders must be handled correctly, including data-driven ingredients with containers. Simulate remainder placement before committing a craft; a blocked remainder destination cannot silently delete an item.

Build a deterministic server catalog ordered by group, sort order, then recipe ID. A recipe generation changes after a successful reload. Revalidate open menus against that generation and invalidate old selections. A deleted recipe removes its preview immediately; it never leaves a collectible ghost output.

A datapack can add and replace recipes, tags, costs, and combinations of registered outputs. It cannot create brand-new registered items or arbitrary equipped geometry without code/resource assets. State that limitation plainly in `DATAPACK.md`.

---

## 8. Authoritative crafting and networking

### 8.1 Menu authority

Use the project's established registration and networking conventions. Forge's menu model separates the server-backed data view from the client screen; its documented hooks include `stillValid`, `quickMoveStack`, and server-side menu opening. [F01]

The server owns inputs, matching recipes, selection, output construction, material consumption, and item delivery. The client is permitted to preview and request a selection. It cannot request an arbitrary stack.

A proposed selection request contains only the active `containerId`, namespaced `recipeId`, and current catalog/session revision if needed. Use the actual sender from network context. Verify that sender's current menu is the correct type and session. Keep payload lengths bounded and reject unknown IDs without logging once per packet.

Use an existing menu button path only if it can maintain stable selection identity and reject stale indexes. Do not compress a resource ID or wide revision into a truncating data slot. Forge documents the narrow integer transfer used by menu data slots; use an appropriate custom packet for wider values. [F01]

### 8.2 One craft transaction

The following is a behavioral contract, not an instruction to duplicate vanilla container internals:

```text
prepareCraft(actor, liveMenu, selectedRecipe):
    validate actor, menu identity, worksite block, dimension, distance, loaded state
    validate current server recipe generation and selected recipe
    snapshot the exact relevant input stacks and quantities
    assemble a fresh permitted result; compute remainders
    verify full delivery/remainder capacity for this extraction mode
    return a craft plan or a reason for rejection

commitCraft(plan):
    revalidate the current menu, recipe, inputs, and destination
    debit exactly the planned quantities
    deliver exactly one result and all required remainders
    update inputs, preview, state IDs, crafting hooks, and listeners once
```

Run it on the server thread. Integrate through a result-slot implementation whose extraction paths have been traced and tested. Do not independently consume ingredients in both `remove` and `onTake`, or both a custom button packet and the native slot transaction. Do not give a real output to the client and only afterward discover that ingredients were missing.

For normal pickup, make one output. For shift-click, repeat individually validated crafts only while sufficient inputs and complete output/remainder capacity exist. Bound the batch; never rely on an unbounded loop waiting for an item transfer to make progress.

### 8.3 Every extraction route counts

Cover normal click, right click, shift-click, number-key swaps, off-hand swaps if the menu exposes them, drag behavior, double-click collection, dropping from the result slot, and creative inventory interactions. Where a route is inappropriate for a virtual result, explicitly deny it rather than leaving an untested fallback.

An output mask is non-stackable unless the existing mask contract explicitly says otherwise. A full inventory must not partially consume a craft. A cursor containing an incompatible item must not accept the result. Two extraction requests arriving together may craft twice only when two legitimate crafts were fully paid for.

### 8.4 Invalidation and packet safety

At every extraction, verify the station still exists, the player is alive and permitted to use it, the chunk is already loaded, the dimension is unchanged, and the normal menu distance is valid. A station broken while a menu is open causes safe closure and input return.

Process custom packets using the existing main-thread enqueue convention. Never load or generate a chunk because a client sent its position. Forge explicitly warns about untrusted serverbound data and chunk access; the workstation does not require arbitrary client-selected coordinates at all. [F03]

A stale client can receive a refreshed selection/preview or a clear rejection. It must never receive a different mask than the one it actually selected merely because recipes were resorted.


## 9. Thief as an exclusive, visible profession

### 9.1 Replace the hidden-overlay contract

Keep the stable profession ID `mcacrime:thief`. Change its meaning from optional presentation to a real exclusive occupation. The existing persisted criminal record remains the owner of Crime-specific data such as mugging cooldowns; native MCA profession data owns native occupational presentation and behavior. A single transition service maintains their agreement.

The target state is:

```text
native profession = mcacrime:thief
Crime criminal job = THIEF
occupation status = active or an explicitly documented non-working Thief state
other active occupation = none
Mask Station worksite = one valid claim, or a documented unbound state
```

A Thief must not retain farmer work, smith work, cleric trades, a Fence shop role, guard recruitment, or another profession's job-site claim alongside that occupation. Do not confuse the previous-profession migration snapshot with an active secondary job.

### 9.2 Player-visible presentation

Use the actual profession field and its correct localized name, not only a custom name tag or Crime HUD badge. MCA interaction screens and normal profession displays must say **Thief**. A profession-specific outfit or icon is welcome but not a substitute for the real job.

Verify translation paths against the current MCA builds, including any profession-name key conventions. Supply the native profession translation and any Crime-specific fallback text. Do not render a raw `mcacrime:thief` resource location to players.

The old `presentThiefAsMcaProfession` setting must no longer allow a hidden Thief. Deprecate it with a one-time migration notice; old `false` values do not override this new release requirement. Leave the independent Fence presentation option alone unless another change genuinely requires touching it.

### 9.3 Safe transition service

Extend the existing criminal job service rather than installing a second writer. Introduce a validated transition entry point with a result/reason, while keeping existing public signatures compatible through delegation where possible.

A transition to Thief must:

1. Resolve the loaded entity and verify MCA villager identity, adulthood, required binding availability, server mutation permission, and occupational eligibility.
2. Reject current guards, archers, configured responders, protected/important NPCs, incompatible custody states, and ordinary employed villagers on the automatic path.
3. Reserve or validate a worksite when that acquisition path requires one.
4. Capture the existing occupational state needed for rollback, without cloning the entire entity.
5. Detach only the previous occupational claim/work behavior being replaced through supported interfaces.
6. Apply the native Thief profession and verify it can be read back.
7. Persist the Crime occupation transition, keeping identity, legal records, personality, and cooldowns intact.
8. Refresh the appropriate occupational brain once, synchronize presentation, and start Thief behavior only after validation succeeds.
9. Publish the existing job-change event only after a committed transition. Failure rolls back the changes this transaction actually made.

Do not refresh the entire brain every tick or reflectively write arbitrary internal fields based on guessed names. Extend `McaBinding`/`McaCompat` with tested capabilities where needed. Preserve the no-static-MCA-linkage rule. [R05][R08]

### 9.4 Unloaded assignments and failed bindings

Do not activate a Thief record against an unloaded UUID that cannot be classified. Record a pending operator request if necessary, then validate on entity load before activation. A command must report “pending validation” rather than “assigned” until it is true.

When required profession/worksite capabilities are unavailable, reject new automatic assignments and suspend affected Thief behavior. Keep the game and unrelated Crime features running. Produce an actionable, rate-limited compatibility diagnostic naming the missing capability. The supported MCA test matrix must demonstrate actual visible professions; silent hidden-role degradation is not an acceptable release fallback.

### 9.5 Scope of occupational exclusivity

This update specifically makes **Thief** exclusive. Do not accidentally turn every historical criminal label into a compulsory native profession, or move Fence stock into the thief's workstation. A Thief can retain a family, spouse, home, relationships, and legal obligations; those are not competing occupations.

MCA: Crime owns the legality and mugging behavior. MCA retains NPC identity and general life behavior. Family loyalty or accomplice features must not bypass the guard exclusion or make a relative both a guard and a mugger.

---

## 10. Worksite acquisition and occupational lifecycle

### 10.1 Normal village acquisition

A placed Mask Station should be discoverable through the supported native job-site mechanism. Normal settlement acquisition is limited to an eligible unemployed adult with no conflicting profession or protected role. A player placing a station may recruit an existing eligible villager; it must not spawn a new thief entity.

Prefer native profession acquisition where MCA supports it. If a compatibility adapter must bridge the process, it uses the native POI claim rather than maintaining a separate competing workstation ownership system. Crime records the resulting validated occupation. Never let native acquisition produce a visible Thief who has no corresponding Crime role, or vice versa.

Existing random assignment logic must not secretly convert employed villagers or create stationless settlement thieves. For settlement assignment, any retained probability/cooldown logic operates on eligible unemployed adults and an available reachable Mask Station. A failed reservation or transition does not consume the village's assignment cooldown as though it succeeded.

The public workstation path and the ambient assignment sweep must converge on the same transition service. They cannot both independently “win” the same NPC or POI.

### 10.2 Worksite claim behavior

Represent a location using dimension plus position, such as a `GlobalPos`, not coordinates alone. Respect the native POI ticket/claim mechanism and distinguish a proposed site from an accepted claim.

Do not permanently occupy a station that the villager cannot reach. Bound search radius and path attempts. Release failed reservations after a short, tested acquisition timeout. Do not scan every station in the world or force-load a destination chunk.

Test all facing states, block replacement, piston behavior if allowed, stations across chunk boundaries, relocation to another dimension, and two unemployed villagers racing for the same station. One station must never supply two active villager worksite claims.

### 10.3 Working and not working

A peaceful Thief may visit its station during an appropriate work period. The station visit is brief and cosmetic. It does not mint masks, create fence inventory, steal player inputs, or consume server CPU while the NPC is unloaded.

Work navigation yields to custody, sleep, panic, a valid enforcement action, and an already-running crime action. Conversely, an idle crafting animation must not block the player's station menu. Avoid directly fighting MCA's navigation controller with two simultaneous movement owners.

Keep the existing mugging behavior conservative: being employed as a Thief does not mean constantly hunting the nearest player. Preserve all current relationship/family protection, victim cooldowns, armed-target avoidance, and shared anti-harassment protections from the actual latest checkout. This update is not permission to increase mugging frequency.

### 10.4 Loss of the station

Distinguish a novice newly acquiring a role from an established Thief.

| Situation | Required behavior |
|---|---|
| Acquisition fails before confirmation | Release reservation; leave the villager's original occupation unchanged. |
| Newly assigned, unestablished Thief loses an accessible station | After a modest grace period, release the claim and return to unemployed through the transition service. Preserve legal history and cooldowns. |
| Established Thief loses its station | Remain visibly a Thief, become unbound, and search locally for an available replacement on a bounded cadence. Do not acquire an unrelated job in parallel. |
| Station chunk unloads | Treat it as unresolved/unavailable, not confirmed destroyed. Do not revoke ownership solely because it cannot currently be inspected. |
| Thief is jailed | Pause occupation movement and crime actions. Do not erase the occupation, family, or legal history. Revalidate the worksite after release. |
| Entity dies | Follow existing death/custody/loot handling, then release its claim and retire occupational state without duplicating equipment or stolen goods. |
| Explicit administrative retirement | Stop sessions, release the worksite, and change occupation through one validated transition. Never clear a legal case as a side effect. |

Proposed establishment rule: one Minecraft day of active employment after a successful worksite visit. Record that milestone once. It is not a trading-XP reset trick or a reward farm. Existing migrated Thieves are established by default so upgrading cannot silently remove them from the world.

An established unbound Thief is still an employed Thief and can perform the already permitted conservative behavior when the feature is enabled. “Unbound” describes its missing workplace, not an additional job or automatic behavioral suspension. Pending or compatibility-suspended occupations cannot mug; a novice whose required claim fails must not use a grace period to operate as an unauthorized stationless Thief.

### 10.5 Existing wandering thieves

Grandfather valid existing wild/itinerant Thieves as visibly employed but unbound. They may claim a suitable station later. Do not place workstations into a player's world during migration.

If the actual current mod already has explicitly configurable wild-thief assignment, retain it only through the same exclusive-profession and guard-safe transition. Document it as the stationless exception, keep its current conservative frequency, and do not add a new spawning system. The default settlement path still uses a Mask Station.

### 10.6 Trade and UI expectations

A Thief must not retain the previous profession's active merchant offers. Archive only migration data needed for a safe rollback; do not expose two tradesets. The player's ordinary right-click still opens MCA interaction unless a higher-priority contextual action is valid.

Do not add a separate Thief shop GUI. Fence remains the specialist merchant. Small mask or Sand Bottle additions to an existing Fence catalog are optional polish and must use the current currency abstraction, finite stock rules, and existing shop screen.

---

## 11. Guards must never mug

### 11.1 Correct classification

Build one semantic law-role check from the existing responder infrastructure. It should cover MCA guards, MCA archers, configured responder IDs/tags, and positively identified supported external guard roles. It must use registry IDs, tags, or verified adapter capabilities, not display names or substring searches such as `name.contains("guard")`.

Use persistent role identity, not availability. **Do not use `isAvailableResponder` as the exclusion.** A sleeping or temporarily unavailable guard remains a guard. `EntitySelectors` already distinguishes responder identity from current awareness. [R13]

If an essential MCA binding is unavailable, return an explicit unknown/unsupported eligibility outcome rather than assuming “not a guard.” New criminal assignments should fail closed in that case.

### 11.2 Shared actor eligibility

Introduce or consolidate a function equivalent to `evaluateNpcMugger(entity, context)` that produces a typed reason. Separate this from victim eligibility: preventing guards from mugging does not imply that players lose every existing attack, talk, or coercion interaction against guards.

An actor must be alive, loaded, a supported adult MCA villager, an active exclusive Thief, awake, unrestrained, outside custody, not a protected occupational NPC, and **not a law responder**. Preserve all existing additional gates. Actor checks are cheap; scanning for potential victims happens only after they pass.

### 11.3 Check every entry and commit boundary

Apply shared eligibility at automatic assignment, operator/API assignment, controller tracking, target selection, forced/debug targeting, session start, session continuation, and final property transfer. Recheck after a cancellable callback that can mutate the world.

The current `NpcMuggingService` already rechecks eligibility around session start and before theft commit. Add the missing actor law-role/occupation semantics to that central path instead of leaving a guard check only in AI selection. [R11]

Audit direct callers of `claim`, `begin`, `complete`, and any debug helpers. A test command can force target choice or skip waiting for random selection; it must never bypass the invariant and authorize a guard to mug.

### 11.4 Occupation changes during a mugging

If a thief becomes a guard or configured responder while approaching, threatening, or completing a mugging:

- Cancel the session before any further debit.
- Close the target's HUD and transient incident/attempt state exactly once.
- Release the victim reservation and thief movement ownership.
- Clear or suspend the incompatible active Thief occupation record as appropriate to the transition.
- Preserve any previously committed stolen-goods record and legal consequence.
- Do not reapply the Thief label over the newly authoritative guard role.

Use a specific abort reason such as `ACTOR_BECAME_RESPONDER` for diagnostics. Normal player feedback can simply say **“The threat has ended.”** Do not make an invalid guard shout a success line or retain a mugging weapon pose.

### 11.5 Prevent the conflict from being created

Update `GuardPopulationService` candidate eligibility to exclude active/pending criminal occupations and conflicting protected states before promotion. The inspected implementation has no explicit criminal-job exclusion in that candidate check. [R12]

Also inspect MCA's own recruitment, villager aging, curing, profession assignment, third-party conversion, entity restore, and operator commands. Use available events or a narrowly scoped adapter where possible. A bounded reconciliation pass remains a safety net; it is not a replacement for atomic transitions inside Crime's own code.

Law-role changes originating outside Crime win the immediate safety decision: stop mugging first, then reconcile the occupation. Never repeatedly flip a villager between guard and Thief on successive ticks.

### 11.6 Repair existing conflicts

On first relevant load after migration, classify the actual NPC before applying any historical criminal presentation. A current law responder carrying an old Thief record remains a law responder. Retire the incompatible Thief occupation without calling a generic “restore previous profession” path that could overwrite the guard.

A historical previous-profession snapshot indicating that a guard was relabeled as Thief deserves a conservative, separately logged reconciliation rule. Verify the snapshot and native role evidence before restoration; never infer a guard from outfit alone. Retain enough diagnostic history for an operator to understand the repair.

Do not “fix” old muggings by minting compensation or deleting records without an actual authoritative transaction history. This update guarantees prevention and state repair, not an invented refund ledger.

---

## 12. Empty-hand apologies and interaction compatibility

### 12.1 Default gesture

**Required default:** right-click the affected villager while **not sneaking**, with an **empty main hand** and no actively held weapon in the off hand. A sword elsewhere in inventory does not block an apology. A harmless off-hand torch does not by itself block it.

When the shared apology evaluation says the action is ready, execute the apology directly through the authoritative action service. Do not force the user to open another screen and select the same action again. Keep the existing Crime menu action and optional keybind as alternative access paths.

### 12.2 Contextual routing

| Context | Result |
|---|---|
| Another mod already canceled the interaction | Respect cancellation. Do not restore the entity or un-cancel the event. |
| Player is sneaking | Do not claim the interaction for the new apology shortcut. Preserve the pickup/other deliberate sneaking gesture. |
| Main hand contains an item | Use normal item/MCA handling, including the Sand Bottle's deliberate use path. |
| No relevant grievance for this player-villager pair | Pass through to normal MCA interaction. |
| Apology already accepted and no new eligible incident | Pass through to normal interaction; do not repeat a reward or trap the player in apology feedback. |
| Ready, peaceful, empty-hand apology | Execute once; consume the matching interaction; show brief acceptance feedback. |
| Settling period or repeat cooldown still active | Preserve normal conversation where permitted. Where grievance already blocks conversation, show the correct reason and remaining wait; do not replace it with “no history.” |
| Held weapon or active coercive threat | Do not apply reconciliation. Explain the reason through the existing refusal/menu path where relevant. |
| Sleeping, inaccessible, removed, dead, or invalid target | Do not execute. Preserve relevant existing handling and give feedback only when needed. |
| Arrest, custody release, rescue, or another committed contextual flow owns this interaction | That flow keeps its established priority. Apology remains available afterward or through the existing menu. |

A player should never have to apologize repeatedly just to get back to MCA's main screen. A valid apology's terminal state immediately makes the next ordinary click behave normally unless a separate legitimate refusal remains.

### 12.3 Shared authority, not duplicate reconciliation

Reuse `ApologyStatus`, `VictimMemoryService`, and `ApologizeActionHandler`. The source already separates eligibility from the bounded memory mutation; preserve that separation. [R15][R17]

Extract a trusted contextual-action entry point in the existing action service if one is needed. It must enforce sender/actor identity, valid target, interaction distance, dimension, awareness, held-weapon policy, active-threat policy, mutation permission, and per-incident eligibility. It must not pretend the client supplied a valid previously offered menu nonce.

The menu route and direct interaction route call the same authoritative execution logic. Menu submissions retain their offered-action and replay protection. A server-observed direct interaction uses its own trusted context, not a general-purpose unauthenticated action packet.

### 12.4 Preserve the actual apology semantics

The inspected implementation has a 1,200-tick settling period documented for the current behavior, one-benefit-per-incident rules, and at most one negative-heart repair per accepted operation. Preserve the actual newer implementation's configured values and reconciliation semantics rather than hard-coding a new one-minute timer in the shortcut. [R15][R17]

An accepted apology does not resolve charges, zero Heat, erase fear, forgive all unrelated incidents, or give positive hearts indefinitely. A new offense creates new eligibility according to the existing policy; it does not recycle the reward from an old incident.

### 12.5 Main hand, off hand, and event ordering

Trace the actual 1.20.1 `EntityInteractSpecific`, `EntityInteract`, item interaction, MCA handling, and any compatibility handlers. The existing source handles only the generic event at LOWEST priority; do not blindly add a second subscriber that duplicates it. [R14]

Process the action in one canonical place. Consume an accompanying off-hand path only when this same interaction has genuinely been claimed, not whenever any villager has memories. Use a short-lived server-side interaction token if necessary to correlate duplicate paths; it is not a replacement for persistent per-incident reward protection.

Raising event priority is not a complete fix for incompatible gestures. Forge orders event priorities and supports cancellation, but the right design is non-conflicting routing plus correct cancellation semantics. Never subscribe with `receiveCanceled=true` and then force an apology on a villager another mod has already removed. [F04]

### 12.6 Easy Villagers × MCA verification

The quoted report identifies the combination, but the exact compatibility jar and its source were not verified during this review. Record the exact Easy Villagers, MCA, and compatibility-addon versions in the implementation test fixture. Inspect their actual pickup gesture and event/callback path before writing an adapter.

Test both the reported combination and the game without that add-on. Confirm that the default nonsneaking gesture apologizes, while the add-on's explicit pickup gesture still picks up an eligible villager. Check that the UUID and persistent Crime state survive pickup/restore according to the adapter's supported behavior.

If an add-on has a configurable nonsneaking pickup gesture that truly collides, document the conflict and use a narrowly scoped verified adapter or preserve the existing Crime menu/keybind fallback. Do not globally disable pickup, replace another mod's configuration, or claim every possible pickup binding works simultaneously.

---

## 13. Sand Bottle item and combat behavior

### 13.1 Core item

Proposed ID: `mcacrime:sand_bottle`. Player-facing name: **Sand Bottle**. Tooltip: **“Throw to briefly blind nearby targets. Does not erase crimes they already witnessed.”**

Proposed shapeless recipe: one glass bottle plus one item in a deliberately defined sand-input tag produces one Sand Bottle. The default tag contains normal sand and red sand. The sand and bottle are consumed on crafting. The thrown bottle breaks; do not also return an intact glass bottle on impact.

Default maximum stack size: **16**. This is a thrown item, not a drink and not an explosive that modifies blocks. Do not add sand harvesting from terrain, a refill GUI, fire, entity damage, or container looting.

### 13.2 Throw lifecycle

Right-clicking air throws along the player's look direction. Right-clicking an entity with the bottle must deliberately use the item instead of opening MCA conversation first. Route both entry paths to one server-side throw implementation, with the same cooldown and exactly-once consumption.

On successful projectile creation, consume one bottle unless the player has creative instabuild, apply the item cooldown, play the throw sound, and award the appropriate item-use statistic once. If spawning is canceled or fails, do not consume the item. The client may animate immediately, but only the server creates an authoritative projectile and debits inventory.

Use an appropriate registered throwable entity with normal spawn synchronization, a small bottle renderer, tested collision behavior, and a bounded lifetime. The projectile carries validated owner/launch provenance, not an arbitrary client-provided victim list.

### 13.3 Initial tuning

These are proposed starting values and must be playtested, not presented as existing config defaults.

| Setting | Proposed default | Purpose |
|---|---:|---|
| Player item cooldown | 80 ticks | Four seconds between throws; shared across all Sand Bottle stacks/hands. |
| Projectile lifetime | 60 ticks | Prevents lingering projectiles; tune throw speed and gravity in the actual runtime. |
| Splash radius | 2.0 blocks | A local disruption tool, not a village-wide cloud. |
| Direct-hit duration | 80 ticks | Four seconds for an accurately hit target. |
| Maximum splash duration | 40 ticks | Two seconds near the impact, falling off with distance. |
| Minimum applied duration | 10 ticks | Omit negligible edge hits below this duration. |
| Post-effect recovery protection | 60 ticks | Three seconds in which sand cannot blind that target again. |
| Close-contact perception range | 1.5 blocks | Blind enemies can still react to someone immediately beside them. |
| Maximum affected targets per impact | 64 | Apply effects to the direct target first, then nearest eligible targets within the bounded candidate set. |
| Maximum spatial candidates examined per impact | 256 | Bound candidate collection before expensive filtering; do not build and sort an unlimited entity list. |
| Player-versus-player effects | Disabled by default | Can be enabled by server policy, but must also respect server PvP and team protection. |

Collect the direct target separately, then use a supported bounded spatial collector for at most 256 nearby candidates before expensive visibility/protection checks. Choose the nearest eligible candidates within that bounded set, with a stable tie-breaker, and cap actual affected targets at 64. In a pathological crowd, this deliberately does not promise the globally nearest 64 out of an unlimited population. Verify the actual collection API and profile the query; applying a limit only after an unlimited list has been built is not sufficient.

Use a distance-to-hit-region calculation that behaves sensibly for different entity sizes. A directly struck target receives one direct application, not direct plus splash. Other targets receive one falloff application. Require a clear impact-to-target sight path appropriate to the burst; a wall blocks sand rather than allowing through-wall blinding.

### 13.4 Dedicated “Sand in Eyes” effect

Recommended implementation: one custom harmful effect, `mcacrime:sand_blinded`, displayed as **Sand in Eyes**, with its own icon and duration. This makes the effect identifiable for AI, removal, immunity, and provenance without confusing it with unrelated blindness sources.

For players, implement a short-range fog/visibility treatment through the appropriate client-only 1.20.1 hooks, plus a restrained dust burst. Do not require a shader mod, full-screen flashing, camera shaking, or an opaque blinking overlay. Existing stronger blindness or darkness must not become less restrictive when sand is applied or expires.

For NPCs, the effect must have actual awareness consequences. A status icon alone is not sufficient. Keep both the effect and supporting state server-authoritative. Cures/removal must restore behavior cleanly and must not remove unrelated potion effects.

### 13.5 AI awareness behavior

For MCA civilians, witnesses, thieves, guards, and archers, integrate through the existing awareness and observation layer. While sand-blinded, they cannot obtain new detailed visual observations outside the close-contact range. They can retain existing targets, remembered identities, last-seen positions, and previously justified legal responses.

A guard can pursue the last known location or react at close range. It should not flawlessly track a hidden target through walls. An archer should not continue perfectly aimed ranged fire without a valid current visual solution. A blinded thief's ongoing mugging should abort when its action requires visual control of the victim.

For supported vanilla mobs, provide a tightly scoped perception integration. Inspect the actual 1.20.1 sensing/attack code and choose the smallest hook that makes the tested melee and ranged cases work. A common sensing hook guarded by this exact effect can be appropriate; a global AI rewrite is not. Limit any new common mixin to the relevant call and retain side-separation tests.

Do not repeatedly clear every target, delete goals, set `NoAI` to stop the entity, freeze navigation, or restore stale goals from an old snapshot. Some enemies can continue toward a remembered position or attack at contact range. The intended effect is blindness, not guaranteed total paralysis.

### 13.6 Preventing permanent blindness

A target already under this sand effect cannot have its duration extended by another Sand Bottle. After the effect ends or is cured, enforce the configured recovery period against **all** throwers. Per-attacker protection is insufficient because two attackers could alternate bottles.

Persist a bounded recovery timestamp/state on the affected entity or through the project's established capability mechanism. Use a consistent server-world clock and reconcile it on load. Clear transient caches on world shutdown. Unload, logout, dimension transfer, and cure must not leave a permanent AI impairment or an immediate duration-refresh loophole.

If another mod applies this same effect through a legitimate API, handle its expiration safely without inventing a Sand Bottle attacker for a legal incident.

### 13.7 Immunity and protections

Provide an entity-type immunity tag. Proposed defaults include entities intentionally treated as eyeless, mechanical, or boss-immune for this mechanic: armor stands, iron golems, snow golems, wardens, withers, and the ender dragon. This is a game-design default, not a claim that every mob in those categories has identical underlying AI.

Honor effect applicability/cancellation and the project's established protected-NPC and friendly-target policies. Server-disabled PvP overrides a mod toggle. Team friendly-fire rules, creative/spectator protection, and explicit protected entities must be considered before applying effects. The owner may be caught in their own splash as a deliberate risk; self-effect is not PvP and must not create a crime against oneself.

Masks do not automatically block sand. A Raven Mask is decorative, not an implied respirator or sealed eye covering. Specialized protective equipment can be a future feature with an explicit data contract.

### 13.8 Dispensers and automation

Do not register a new offensive dispenser behavior in the core 0.7.2 scope. Ordinary item dispensing may remain ordinary item dispensing. This avoids unowned trap attacks and a second attribution system. If the actual existing projectile infrastructure already supports controlled fake-player/dispenser actors, document and test that path separately before enabling it.

---

## 14. Witnesses, identity, and legal consequences

### 14.1 Sand is non-damaging but not consequence-free

Do not call `hurt(0)` merely to trigger assault handling; that can activate unrelated hooks, armor behavior, or duplicate incidents. Feed a successful hostile sand exposure into the existing incident pipeline using an appropriate existing assault/harassment classification, or a narrowly defined subtype if the current schema requires one.

Use actual actor and victim provenance. Do not treat every burst particle as an offense or mint one charge every tick of the effect. A single impact creates one logical attempt and deduplicated victim consequences using the existing incident model. If that model aggregates several victims, follow it; do not build a parallel case ledger.

A missed throw or a target whose effect was vetoed must not be represented as a successfully blinded victim. An existing system may distinguish a witnessed hostile attempt from a completed offense, but this update should not invent an unsupported automatic charge merely for holding or throwing the item into empty space.

### 14.2 Self-defense

Apply the current self-defense/legal-context rules. Using sand to interrupt an actual NPC mugging can be a lawful defensive action under those rules; using it on an uninvolved villager is not automatically lawful because the item does no health damage.

Capture the relevant attack/mugging context **before** aborting the aggressor's session. Otherwise the defensive action may cancel the very evidence that proves it was defensive. Sanding a guard does not automatically become self-defense simply because the player is wanted.

### 14.3 Observation timeline

Evaluate knowledge in chronological order:

```text
launch -> visible throw/owner observations -> flight -> impact -> effect application
       -> interrupted perception -> recovery
```

A witness who identified the thrower before impact keeps that identification. A target struck from behind does not gain perfect attacker knowledge from the projectile's hidden owner UUID. A witness who saw only an anonymous masked figure records only the information the disguise system permits.

The projectile should retain launch provenance needed by the authoritative incident system. Do not read only the owner's currently worn mask at impact: switching masks during flight must not retroactively alter an observation of the throw.

### 14.4 Masks and restyling

Mask style and tint may contribute to descriptive witness flavor, but a changed description is not a new legal identity. Do not link two unknown people with certainty just because they wore the same mask. Do not unlink an already known culprit merely because they restyled it.

Any item evidence or stolen-goods linkage used by the actual newer mask system survives station customization. A craft/restyle operation must not accidentally create a clean duplicate of a flagged mask while returning the original as a crafting remainder.

### 14.5 Integration event discipline

Reuse the current incident, attempt, victim-memory, and reputation event pipeline. Emit each semantic event once from its authoritative owner. A Sand Bottle impact must not both manually reduce reputation and then trigger an existing reputation listener that reduces it again.

Keep information boundaries intact: administrative debug data can expose internal actor IDs; normal witness speech and client UI must not expose an identity the witness did not learn.

---

## 15. Compatibility and companion-mod contracts

| Integration | Required treatment |
|---|---|
| MCA Reborn | Verify actual profession read/write, worksite acquisition, presentation, aging, curing, equipment, conversation, and awareness against each supported jar. Preserve reflective runtime binding and no static linkage. |
| Easy Villagers plus MCA compatibility add-on | Verify the exact installed pickup path; nonsneaking apology must not pick up an NPC. Preserve explicit pickup and persistent state on restore. Do not claim untested jar support. |
| MCA: Conversations | Preserve normal dialog entry, all supported presentation modes, and accessible Crime actions. Direct apology must not open a second screen or strand the conversation UI. |
| MCA: Quests | Preserve NPC UUID and profession-aware eligibility. Profession changes must not orphan active quests or pay rewards twice. A visible Thief is not an automatic bounty target without a valid case. |
| MCA: Reputation | Route law/moral consequences once through the existing bridge. Do not create a second standing store or erase reputation through disguise/restyling. |
| Locks Reforged | Keep restraint and lockpicking interaction priorities intact. The Mask Station does not add a dependency or accidentally consume lockpicks as generic bindings. |
| Guard Villagers, Recruits, or other responders | Use verified role adapters or existing additive responder selectors. Never classify by translated names. Their presence does not require converting their entities into MCA villagers. |
| Epic Fight and relevant MCA patches | Test held-item use, combat interaction, mask/head rendering, and sand perception under actual supported versions. Do not disable attacks globally to solve a right-click conflict. |
| Existing currency integrations | Any optional Fence offers use the current currency abstraction. Crafting consumes ingredients, not hard-coded emeralds. |
| JEI/EMI or other recipe viewers | Add a narrowly isolated optional category if already supported by the project. Show each selectable output and identical inputs; recipe transfer must not auto-craft. No new hard dependency. |
| Resource packs/shaders | Texture overrides and custom fog should degrade safely. Missing cosmetic assets cannot affect server identity or legality. |
| Claim/protection systems | Use supported interaction/effect cancellation hooks. Do not promise universal compatibility with an unidentified protection mod; record tested combinations. |

Optional integration code must be isolated and checked before loading. A missing integration cannot crash a dedicated server or make the standalone project unbuildable. Do not copy companion classes into the release jar. [R04][R05]

For MCA: Conversations and Quests, preserve API contracts rather than adding ad hoc direct calls into screens or world data. Prefer a single job-change notification after a successful occupational transition and existing action/incident events for gameplay.

---

## 16. Configuration and synchronization

### 16.1 Configuration philosophy

Retain the repository's COMMON plus CLIENT architecture. Do not add a second SERVER spec just for this feature set. The server's COMMON values govern gameplay; expose only the presentation-relevant policy that clients need, through the project's established sync approach. Local client config must not control crafting cost, concealment, crime eligibility, or actual blindness duration. [R05]

Use existing settings whenever they already express the policy. New keys should be few, bounded, documented, and validated. The names below are proposed contracts to reconcile with the actual checkout, not necessarily literal fields to add unchanged.

### 16.2 Proposed settings

| Area | Setting or policy | Default / treatment |
|---|---|---|
| Masks | `enableMaskStationCrafting` | `true`; disabling closes/invalidate relevant active menus safely but keeps blocks/items registered. |
| Masks | `enableMaskRestyling` | `true`; disables only that operation when false. |
| Apology | `emptyHandApologyMode` | `CONTEXTUAL_DIRECT`; alternate `MENU_ONLY` retains explicit access. Do not make sneaking required by default. |
| Sand | `enableSandBottles` | `true`; disables throwing, not registry entries or safe loading of existing items. |
| Sand | `sandBottleCooldownTicks` | `80`; validated positive lower bound suitable for network spam protection. |
| Sand | `sandBottleDirectDurationTicks` | `80`; bounded maximum, proposed 200. |
| Sand | `sandBottleSplashDurationTicks` | `40`; no greater than the direct-hit duration. |
| Sand | `sandBottleRadius` | `2.0`; bounded, proposed maximum 4.0. |
| Sand | `sandBottleRecoveryTicks` | `60`; positive minimum preserves anti-chain behavior. |
| Sand | `sandBottleAffectsPlayers` | `false`; true still cannot override server PvP/team rules. |
| Thieves | Existing enable/frequency/cooldown keys | Preserve actual current values; reconcile worksite eligibility without increasing harassment. |
| Thieves | Existing `presentThiefAsMcaProfession` | Deprecated; false cannot enable hidden dual-profession Thieves. Explain migration once. |
| Client | `sandParticles` | Normal/reduced/off for decorative particles only. |
| Client | Optional mask preview rotation | Off by default; static previews remain fully usable. |

Do not expose `guardsMayMug`, `allowHiddenThieves`, or another option that silently defeats the user's explicit requirements. Do not make mandatory guard exclusion depend on a user-maintained blacklist.

Recipes own ingredient costs and outputs. Tags own material and immunity membership. Config owns broad enablement and numerical policy. Avoid representing the same setting independently in all three systems.

### 16.3 Reload behavior

Disabling the existing Thief feature also blocks new automatic/native-worksite Thief acquisition and aborts existing mugging sessions through their normal cancellation path. The Mask Station remains usable for player crafting unless its separate crafting setting is disabled. Existing Thief professions may remain visible and inert; disabling behavior does not silently restore hidden secondary professions. The native acquisition bridge must not leave a newly selected native Thief profession without a valid Crime occupation. Re-enabling resumes from safe occupational state and preserves existing cooldowns.

Disabling sand throwing prevents new throws. Already spawned bottles use a documented policy: on reload, disable further effect application and finish/discard safely, without returning consumed ammunition. Do not let a config change duplicate items. Existing applied short effects can finish normally unless an explicit safe cleanup policy exists.

A recipe reload invalidates menu selections as described earlier. A responder-selector reload can turn an existing NPC into a law responder; that must stop its mugging behavior immediately through the same eligibility/reconciliation path.

---

## 17. Save migration and preservation

### 17.1 Version the occupational schema, not every feature independently

Extend the existing world-data schema with a documented migration version. Do not overwrite or bump an unknown newer schema downward. Prefer typed optional fields to overloaded empty strings.

A proposed Thief occupational extension includes:

```text
villager UUID                      existing identity
job                                existing criminal role
occupation schema version
occupation status                  active / unbound / pending / suspended
assignment source                  workstation / legacy / configured_wild / operator
assigned game time or existing day
established flag
optional worksite GlobalPos
optional acquisition reservation expiry
optional historical profession snapshot with explicit presence/kind
lastMugAt                          preserve existing value
personalitySeed                    preserve existing value
lastSeenDay                        preserve existing value
```

Use stored names/IDs rather than enum ordinals. Keep historical profession information separate from an active occupation. Do not serialize live entity references, client screen state, transient controller state, or a duplicate copy of MCA's relationship graph.

### 17.2 Migrate lazily and idempotently

Perform structural data migration when the world data loads, then entity-sensitive reconciliation when each relevant NPC actually loads. Never force-load all villages to finish an upgrade. Mark completed migrations so a second load cannot consume another worksite ticket, reassign a role, clear another job, or reset a cooldown.

| Old state | Migration outcome |
|---|---|
| Valid Thief record, current native Thief | Keep Thief, preserve state, mark established, validate worksite or become unbound. |
| Hidden Thief overlay on an ordinary non-protected profession | Transition in place to exclusive Thief; detach old occupational behavior/claim safely; archive historical profession data. |
| Thief overlay on a guard/archer/configured responder | Preserve law role, stop mugging, retire the conflicting Thief occupation; retain legal/provenance records. |
| Thief overlay on a protected/important NPC | Do not overwrite its protected career. Suspend or retire the incompatible occupation with a diagnostic. |
| Existing wild Thief | Preserve a visible, established, unbound Thief; do not invent a workstation in the world. |
| Missing entity / unloaded entity | Retain bounded persisted data and reconcile upon legitimate load; do not treat absence as death. |
| Missing required MCA capability | Suspend unsafe occupation behavior; preserve data for later recovery and report the capability problem. |
| Current occupation already changed legitimately by newer code | Respect the newer authoritative transition; do not resurrect an old hidden job during migration. |

### 17.3 Fix the previous-profession ambiguity

The inspected service deliberately stores an empty string for one previous-profession case, while the record writer drops blank strings. This makes the old “snapshot present but unreadable” state indistinguishable from “no snapshot saved” after a round trip. [R08][R16]

Use an explicit presence/kind field in the new snapshot. For old records lacking the distinction, make a conservative decision; do not invent a previous profession. A schema test must cover null, blank, valid ID, invalid ID, removed profession ID, and unknown future fields.

Also audit every immutable-record reconstruction. In the inspected service, methods such as `touchMug` and `touchSeen` construct a fresh `CriminalVillagerRecord`. When fields are added, these update paths must preserve worksite, status, source, and migration data rather than quietly resetting them. [R08]

### 17.4 Do not overwrite newer native roles during presentation refresh

A migration or config refresh must classify the actual loaded NPC first. Do not call the old generic presentation-restoration logic on an entity that has since legitimately become a guard. Do not replace an operator's explicitly completed job transition with a remembered old profession.

After a successful migration, ordinary presentation refresh should synchronize an agreed occupational state, not act as a second independent job-assignment engine.

### 17.5 Existing mask items

Preserve shipped registry IDs. Adapt old NBT lazily and safely on inspection/use where needed, without changing unrelated inventory contents. Keep names, enchantments, wear, ownership, and provenance. Unknown data should remain recoverable rather than being discarded wholesale.

If the actual newer mask implementation has no per-item identity, do not invent one and imply that earlier items possessed it. Only migrate state that actually exists; new metadata must not retroactively rewrite witness knowledge.

### 17.6 Old saves and rollback

Create representative pre-update fixtures and backup copies before testing. Record exactly which versions the migration test covers. Loading a new-schema save in an older jar is not guaranteed; document that limitation and recommend restoring the matching backup rather than “downgrading” by stripping data.

Keep job migration separate from unrelated case, bounty, custody, and currency schema changes. No update step should reset stolen goods, jail sentences, family relationships, or earned progression merely to simplify occupation reconciliation.


---

## 18. Implementation map and architecture

### 18.1 Modify existing owners, rather than creating parallel systems

The paths below are relative to `src/main/java/dev/otectus/mcacrime/`, except where explicitly stated. They are review targets at the inspected ref; reconcile renamed or expanded files against the implementation checkout.

| Existing owner | Targeted work |
|---|---|
| `McaCrime.java` and `item/CrimeItems.java` | Connect the necessary deferred registrations; retain shipped items and creative-tab entries. Add only genuinely new masks, the station item, and Sand Bottle. [R02][R05] |
| `job/CriminalProfessions.java` | Give Thief its Mask Station POI predicates and appropriate work sound. Keep Fence's registration and behavior independent. [R07] |
| `job/WorldCriminalJobService.java` | Own validated occupation transitions, native profession agreement, migration reconciliation, behavior refresh, and committed notifications. Remove the hidden-Thief presentation model without breaking unrelated Fence logic. [R08] |
| `job/CriminalJobAssigner.java` and `job/CriminalJobAssignmentSweep.java` | Add occupational/worksite eligibility; reuse shared responder classification; reconcile existing records instead of skipping contradictory actors forever. Preserve bounded scans and probabilities. [R09][R10] |
| `state/world/CriminalVillagerRecord.java` and its world-data owner | Add explicit schema/state fields, preserve all existing fields through updates, and implement idempotent legacy migration. [R16] |
| `enforcement/GuardPopulationService.java` | Exclude active criminal occupations from automatic promotion candidates; coordinate a real external promotion with occupation retirement rather than allowing two jobs. [R12] |
| `detect/EntitySelectors.java` | Reuse the law-role predicate; distinguish role identity from awareness; invalidate any derived eligibility state on selector reload. Do not redefine all protected villagers as ineligible for all gameplay. [R13] |
| `ai/thief/ThiefBehaviorService.java` and the existing thief ticker | Gate tracking and execution with the shared actor eligibility result. Stop and release only Crime-owned control when the actor becomes invalid. [R18] |
| `mug/npc/NpcMuggingService.java` | Revalidate actors at every critical boundary, including after external callbacks and before property transfer. Preserve existing transaction, HUD, abort, and stolen-goods handling. [R11] |
| `memory/MemoryInteractionHandler.java` | Replace the mandatory-sneak access path with contextual nonsneaking routing; preserve ordinary conversation/refusal behavior and other mods' canceled interactions. [R14] |
| `action/handler/ApologizeActionHandler.java` and existing action service | Expose a shared trusted execution path without bypassing eligibility or weakening ordinary menu authorization. Preserve bounded relationship repair. [R15] |
| Existing mask/disguise owner in the newer checkout | Add styles and customization to that owner; do not install a competing resolver merely because the older reviewed ref lacks it. |
| `compat/McaCompat.java` and `compat/mca/` | Add only tested, version-probed capabilities needed for actual occupation/worksite and awareness behavior. Do not leak runtime MCA classes into common code. [R05] |
| Existing awareness, incident, memory, and companion bridges | Integrate the exact sand effect and its provenance once. Keep AI blindness, witness knowledge, and legal outcome distinct. |
| Existing network registration, configuration, and validation owners | Register bounded station messages, authoritative settings, and validation; preserve side separation and update protocol compatibility deliberately. |

### 18.2 Proposed new components

These are responsibilities, not compulsory class names. Follow the current project layout; merge a responsibility into an existing suitable owner rather than producing a class for every sentence.

| Responsibility | Suggested component |
|---|---|
| Shared mask family, style, tint, and metadata operations | `mask/MaskDefinition`, `mask/MaskCustomization` |
| Station block and POI registration | A small block-registration owner and a POI-registration owner |
| Temporary inputs and authoritative selected recipe | `menu/MaskStationMenu` |
| Craft/restyle recipe parsing and validation | `recipe/MaskMakingRecipe`, its type and serializer |
| Pure cost/output/remainder plan | `recipe/MaskCraftPlan` or an equivalent testable helper |
| Screen and client-only item/equipment visuals | Existing client registration/render packages plus `MaskStationScreen` |
| Shared occupation and mugger eligibility | One typed policy/helper near `job/`, reused by assignment and mugging |
| Native worksite transitions | A focused service near `job/`, backed by verified MCA compatibility capabilities |
| Sand item, projectile, and status effect | Appropriate item/entity/effect owners using a shared impact policy |
| Sand-aware perception | A narrow adapter into existing awareness, plus the smallest verified general-mob sensing hook needed |

Register blocks, items, POIs, menus, recipe types/serializers, projectile entity types, and effects at the proper lifecycle stage. The proposed station has no inventory-bearing block entity; do not add one out of habit. Keep entity renderers, colors, fog handling, and screens on the client side.

### 18.3 Data flows

```text
MASK CRAFTING
client selects recipe ID
    -> server validates open menu, generation, inputs, and selected recipe
    -> pure craft plan: costs + output + remainders
    -> commit exactly once through authoritative extraction path
    -> synchronize inventory/output
    -> normal crafting notification, when applicable

THIEF OCCUPATION
native worksite acquisition / allowed assignment / operator request
    -> resolve and classify NPC
    -> shared eligibility + claim validation
    -> one occupation transition owner
    -> verify native profession and persist Crime occupation
    -> synchronize job display + activate permitted behavior

NPC MUGGING
eligible exclusive Thief
    -> victim selection and existing protection checks
    -> begin and callback revalidation
    -> continuing actor/victim checks
    -> final revalidation
    -> existing stolen-goods transaction

SAND IMPACT
server projectile collision
    -> bounded eligible target set and impact visibility
    -> effect/cancellation/protection checks
    -> successful exposure, once per target
    -> restricted new perception + timed recovery
    -> authoritative incident outcome using launch provenance

APOLOGY
nonsneaking empty-main-hand entity interaction
    -> contextual route + shared eligibility
    -> trusted server execution, once
    -> existing memory reconciliation and bounded heart repair
    -> feedback, without a second menu or pickup
```

### 18.4 Keep state ownership explicit

Native MCA data remains the owner of the villager's actual profession, identity, relationships, and normal life behavior. Crime's occupational record stores Crime-specific state and a validated agreement with that profession. It is not an alternate hidden career. Native POI mechanisms own claims; the Crime record can remember a verified `GlobalPos`, but that memory cannot manufacture an extra ticket.

Menus own temporary crafting inputs. The server recipe manager owns valid recipes. Registered definitions own mask gameplay values. A projectile owns its launch/impact lifetime. The status effect owns its active impairment; a bounded recovery record owns only the post-effect immunity window. Treat occupational status as a typed state machine: active-bound and established-unbound can be behaviorally eligible, while pending and suspended cannot; enabling the feature and the current actor checks still apply. Existing incident and stolen-goods services remain the owners of legal and property consequences.

Avoid static maps containing live entities across server lifetimes. UUID-indexed transient controllers must be cleaned up on unload/stop and revalidated before use. Persist only information needed across saves.

### 18.5 Public API discipline

Preserve existing event names and method contracts where possible. Add richer result methods rather than silently changing a public `void` setter into a throwing method. Legacy setters must delegate to safe validation and cannot remain a back door for assigning guards.

A query returning `THIEF` is not sufficient authorization to mug. Public/debug mutation paths must use the same validated occupation service; every execution path still checks current actor eligibility. Publish a job-change event after commitment, not while native and persisted state disagree.

If an adapter must change because an existing API cannot express failure or pending validation, document the compatibility boundary and add an explicit API-version check. Do not quietly continue with a half-bound feature.

---

## 19. Assets, accessibility, and player-facing polish

### 19.1 Asset acceptance inventory

For each new style, ship a distinct readable inventory icon, functional equipped appearance, localized name, appropriate model/tint definitions, tags, and a selectable station recipe. A mask that exists only as an icon is unfinished.

The station needs all facing states, block and item models, textures, mining tags, loot, crafting recipe, menu background/widgets, selection states, and worksite presentation. Sand needs the filled-bottle icon, projectile renderer, effect icon, particles, sound/subtitle handling, and recipe. Reuse appropriate vanilla sounds where that avoids unnecessary custom assets; do not refer to an unregistered custom sound.

Test transparency, mask straps, rear/head clipping, head rotation, sneaking, swimming, and differing supported MCA head shapes. Confirm that replacing a helmet does not leave its render layer behind. Do not attach the mask texture to the entire face in a way that recolors eyes or skin.

### 19.2 Localization and clear feedback

Suggested text is illustrative; localize it through the project's existing language conventions.

| Context | Suggested text |
|---|---|
| Empty station | “Add materials, then choose a mask.” |
| Style selected, insufficient materials | “Needs 4 Clay Balls and 2 String.” |
| Optional dye | “Add a dye to change the accent color.” |
| Restyle mode | “Restyle this mask. Its wear and history are preserved.” |
| Unsupported item data | “This mask cannot be safely restyled.” |
| Invalidated selection | “Recipes changed. Choose a style again.” |
| Thief profession | “Thief” |
| Temporary compatibility failure | “Thief behavior paused: required MCA occupation support is unavailable.” |
| Apology waiting | “Give them a little time before apologizing.” |
| Apology already accepted | Use the existing accepted/not-needed feedback only when relevant, not on every ordinary conversation. |
| Sand effect | “Sand in Eyes” |
| Sand Bottle tooltip | “Throw to briefly obscure vision. Does not erase witnesses' memories.” |

Do not display untranslated profession IDs or internal community keys to players. Use “Bandana” consistently in names; search aliases can include “bandanna” without creating a duplicate item.

### 19.3 Keyboard and narration

The style grid must be usable with keyboard focus. Narration should include style name, material family, cost, selected state, and whether it is craftable. Selection cannot be communicated only by color. Use a border/check indicator and a stable preview label.

Tooltips should explain an unavailable action, not just say “Unavailable.” Keep controls in predictable positions when an input changes. Scrolling through styles must not scroll the player's hotbar simultaneously. Escape closes through the normal menu cleanup path.

Test at small window sizes and multiple GUI scales. Long translations should wrap or truncate with an accessible full tooltip, not overlap the output slot. Do not shrink text until it becomes unreadable to fit the original sketch.

### 19.4 Restrained animation and effects

Use static item previews by default. A brief selected-border highlight or subtle work sound is enough; do not add sliding panels, compulsory screen zooms, or continuous camera motion.

Sand uses readable impact particles and short reduced visibility, not strobing, violent screen shake, or a completely opaque overlay. Particle intensity is a client presentation option. Disabling decorative particles must not remove authoritative AI impairment or grant longer visual range than the effect is intended to allow.

### 19.5 Small narrative additions

Thieves can have a few localized worksite lines, such as “A good disguise starts with a good fit” or “Just making costumes.” These are flavor, not a confession that justifies arrest. Use the existing dialogue framework and its throttling; do not broadcast a line every time a villager reaches the block.

A mask's item tooltip may name its family and explain that styles within that family have equal concealment. Avoid numeric promises that the newer disguise system cannot actually support. A hockey-style mask should be an original design, not a direct reproduction of a named film character's distinctive asset.

---

## 20. Implementation phases

Each phase ends with a usable, tested increment. Fixes may be committed independently, but the 0.7.2 release must include every required workstream; optional polish must not replace unfinished core functionality.

| Phase | Implementation work | Exit criteria |
|---|---|---|
| **0. Reconcile and baseline** | Read current repository instructions; record branch, SHA, worktree changes, actual version, dependency jars, test tasks, and newer mask/currency/family systems. Map this document to real owners. Capture representative save fixtures and baseline checks. | A short implementation-baseline note distinguishes inspected older code from actual target code. Existing changes are preserved. Failures or unavailable runtime dependencies are recorded, not reported as passing. |
| **1. Guard safety first** | Add shared actor eligibility, guard/archer/responder exclusions, controller/session invalidation, final-commit checks, and guard-population candidate safeguards. Add the stale-role regression before fixing it. | Guards cannot begin or complete muggings through ordinary, stale-record, callback, or debug paths. Invalid sessions close without property loss. |
| **2. Real occupation and worksite foundation** | Add station block/POI registration, validated native occupation transitions, exclusive profession presentation, protected-role handling, migration, and bounded acquisition. Keep the station menu minimal until Phase 3. | A real MCA unemployed adult can claim a station and become a visible Thief; the prior job is not active. Saves reload safely, guards win conflicts, and claims are not duplicated. |
| **3. Selectable crafting** | Implement recipe type/serializer, menu, selection protocol, input cleanup, output transactions, remainder handling, reload safety, and the complete station screen. | Identical material inputs produce the explicitly selected style. Every extraction route is single-debit/single-output; multiplayer and invalidation cases preserve items. |
| **4. Mask collection and customization** | Integrate the sixteen-style catalog with the actual mask system; finish icons/equipment rendering, tinting, restyling, tags, recipes, and metadata preservation. | All styles are usable, visually distinct, and consistent with existing concealment. Restyling neither repairs nor launders items. |
| **5. Peaceful apology interaction** | Add nonsneaking contextual direct execution, shared action validation, deduplication, feedback, and exact Easy Villagers compatibility testing. | A qualifying empty-hand right-click apologizes once without pickup. Normal conversation and intentional pickup still work. Existing menu access and legal rules are preserved. |
| **6. Sand Bottle** | Implement item, projectile, custom effect, bounded splash, recovery immunity, actual perception changes, legal provenance, self-defense ordering, protection checks, and assets. | Tested enemies lose new visual tracking appropriately; witnesses retain prior knowledge; effect stacking, protection, and attribution cases pass. |
| **7. Integration and release** | Run the complete test matrix; verify clients and dedicated servers, optional companions, legacy saves, config reloads, packaging, assets, and documentation. Update release metadata to 0.7.2 in its canonical source. | Every mandatory acceptance criterion is met or explicitly reported as a release blocker. Deliver reproducible validation results and the correct release artifact, not only a successful compilation. |

Do not postpone the exclusivity transition until after decorative masks. The guard bug and native profession model are the foundation that prevents the new station from amplifying conflicting jobs.

Within a phase, use focused commits: test demonstrating the issue, minimal implementation, integration verification, and documentation as appropriate. Do not combine unrelated formatting, dependency upgrades, loader ports, or mass package moves with these changes.

---

## 21. Verification matrix

### 21.1 Test layers and honest reporting

Use pure/unit tests for policies, data parsing, migrations, and transaction plans. Use real server integration tests for entity professions, POIs, interaction events, effect application, and incident handling. Use actual client checks for equipment rendering, menus, input focus, fog, and compatibility gestures.

A mocked native profession setter does not prove MCA displays a Thief. A test that directly calls the apology handler does not prove right-click avoids pickup. A test that finds an effect in an entity's effect map does not prove its attack or witness behavior changed. Exercise the real entry points.

The inspected repository instructions and historical verification documents do not describe identical test infrastructure across branches. Inspect the actual target's Gradle tasks and source sets instead of assuming that an older branch's GameTests are available. Historical test counts in repository documents are not results for this release. [R05][R17]

### 21.2 Mask and workstation tests

| ID | Scenario | Required result |
|---|---|---|
| MASK-01 | Supply each material family's exact recipe inputs. | All four default styles are available at the same cost within that family. |
| MASK-02 | Select Bandana, Hockey, and every remaining catalog entry. | Preview and final item agree, with correct name, style, tint, and count. |
| MASK-03 | Change selection repeatedly without taking output. | No ingredients are consumed and no real output is created. |
| MASK-04 | Insert insufficient, wrong, or tag-alternative inputs. | Correct availability; no partial consumption; documented tag alternatives work. |
| MASK-05 | Insert dye; remove it before taking output. | Server recomputes output; no free retained tint from a stale client preview. |
| MASK-06 | Restyle named, worn, enchanted, cursed, or flagged masks. | Allowed metadata survives; wear is not repaired; provenance is not cleared. |
| MASK-07 | Restyle an unsupported capability-bearing mask. | Safe explicit rejection instead of silent data loss. |
| MASK-08 | Restyle to the same style/color, or to another family. | No-op and disallowed cross-family conversions are blocked. |
| MASK-09 | Equip through inventory, ordinary use, and any supported accessory slot. | Valid restrictions and one authoritative concealment effect; existing equipment is preserved. |
| MASK-10 | Render all styles on supported player and MCA models. | Correct equipped appearance, tint, positioning, and no lingering helmet/mask layer. |
| MASK-11 | Load shipped older masks and missing add-on style definitions. | Stable identifiers and safe fallback; no crash, item erasure, or benefit escalation. |
| MASK-12 | Change/recolor a mask after a witness has identified its wearer. | Known identity and legal history remain; no false identity merge between matching masks. |
| CRAFT-01 | Take output by normal click, shift-click, hotbar swap, throw/drop, drag, and double-click routes where supported. | Every legal extraction debits once; illegal routes are denied; no ghost output becomes a free real stack. |
| CRAFT-02 | Shift-craft with nearly full inventory and limited ingredients. | Bounded quantity, complete capacity planning, correct remainders, no consumed-but-lost output. |
| CRAFT-03 | Use a datapack ingredient with a crafting remainder. | Remainder is returned exactly once; it does not duplicate the source mask during restyling. |
| CRAFT-04 | Close, disconnect, die, change dimension, or walk out of range with inputs. | Normal cleanup preserves real inputs once; preview is never returned as an item. |
| CRAFT-05 | Break or replace the station while two players have it open. | Both menus invalidate independently and return only their owners' real inputs. |
| CRAFT-06 | Two players choose different styles at one station. | No shared inputs, selection, output, or inventory mutation. |
| CRAFT-07 | Forge recipe ID, container ID, generation, count, or output NBT. | Server refuses invalid requests; supplied output data is not trusted. |
| CRAFT-08 | Replay a selection/extraction message or send many rapidly. | No duplication, unbounded work, arbitrary chunk loads, or cross-menu writes. |
| CRAFT-09 | Reload recipes while output is previewed, including removal of that recipe. | Selection is invalidated/revalidated safely; stale output cannot be taken. |
| CRAFT-10 | Feed malformed JSON, excessive counts, unknown results, or empty required tags. | Validation produces useful bounded diagnostics; malformed recipes cannot enter a usable state. |
| CRAFT-11 | Claim-protection mod cancels station interaction. | No unauthorized menu, block mutation, or special bypass. |
| CRAFT-12 | Enable recipe-viewer integration, then remove that optional mod. | Recipes remain usable; optional client classes do not load on a server or absent-mod configuration. |

Add property-based or randomized transaction tests where the existing test infrastructure supports them. Across arbitrary valid click sequences, total real output must correspond to committed costs; closing a menu cannot materialize previews. Use deliberately small inventories and remainder-producing fixtures to expose edge cases.

### 21.3 Profession, migration, and guard tests

| ID | Scenario | Required result |
|---|---|---|
| JOB-01 | Eligible unemployed adult reaches an available station. | One confirmed native Thief profession, one Crime occupation, one POI claim, and visible “Thief.” |
| JOB-02 | Employed villager, child, protected NPC, captive, guard, or archer attempts automatic acquisition. | No forbidden conversion or accidental loss of the existing role. |
| JOB-03 | Two candidates race for one station; native acquisition and the sweep run together. | Exactly one winner/claim; losing attempts leave no active occupation or consumed success cooldown. |
| JOB-04 | Station uses each facing state, sits across a chunk edge, or is inaccessible. | Valid states register; inaccessible claims time out; no forced chunk loading. |
| JOB-05 | Novice loses station, established Thief loses station, station chunk merely unloads. | Distinct lifecycle rules from Section 10; no premature retirement or competing job. |
| JOB-06 | Thief is jailed, released, killed, cured, picked up/restored, or moved across dimensions. | Identity and history persist where appropriate; claims/controllers revalidate; no equipment or entity duplication. |
| JOB-07 | Migrate a hidden Thief overlay on an ordinary profession. | Actual exclusive visible Thief, old active work/trades detached, relationships and cooldowns retained. |
| JOB-08 | Migrate a Thief overlay on a guard/archer/configured responder. | Law role survives; mugging stops; conflicting occupation is retired without deleting legal history. |
| JOB-09 | Migrate existing wild Thieves and unloaded records. | Visible established/unbound roles when loaded; no invented stations or premature deletion. |
| JOB-10 | Apply migration twice, then call `touchSeen`, `touchMug`, and config refresh. | No duplicate claim or reset of new fields, previous history, or cooldowns. |
| JOB-11 | Load null/blank/invalid/removed historical professions or unknown future schema data. | Conservative recoverable handling; no fabricated previous job or downgrade of newer state. |
| JOB-12 | Native job setter/worksite capability fails or is absent. | No hidden active Thief; safe suspension/rejection and actionable diagnostic. |
| JOB-13 | Existing config says `presentThiefAsMcaProfession=false`. | Thief is still an actual visible job; deprecated setting cannot restore a second job. Fence behavior is unchanged. |
| JOB-14 | Supported MCA variants display the same NPC through normal interaction/name/job views. | Correct localized Thief profession, not only a Crime-specific overlay. |
| GUARD-01 | Directly seed a saved Thief record onto a loaded guard. | Guard cannot track, approach, threaten, or commit a mugging. |
| GUARD-02 | Change a Thief to a guard during approach, threat, timer completion, and pre-commit callback. | Each path aborts before property transfer and closes HUD/reservations/active incident cleanly. |
| GUARD-03 | Make guard unavailable, sleeping, unarmed, injured, or temporarily idle. | Law identity still prohibits mugging. |
| GUARD-04 | Add/remove a responder tag or config entry at reload. | Eligibility updates promptly; newly recognized responders stop mugging. |
| GUARD-05 | Run ordinary guard population promotion with criminals present. | Existing active criminal occupations are not silently double-assigned. |
| GUARD-06 | Use debug commands, forced targeting, API assignments, or callback-driven job changes. | No path bypasses actor eligibility or falsely reports an unsuccessful assignment as complete. |
| GUARD-07 | Abort a session after a previous legitimate theft exists in the ledger. | New theft does not occur; prior provenance remains; no invented refund or deletion. |
| GUARD-08 | A player interacts with or legitimately attacks a guard under existing rules. | Guard-actor exclusion does not globally disable unrelated player interactions. |

Run the guard transition cases both with MCA's own role change and the mod's guard population path where available. Test all explicitly supported external guard adapters; an entity's translated display name is not a valid test fixture for role classification.

### 21.4 Apology interaction tests

| ID | Scenario | Required result |
|---|---|---|
| APO-01 | Hit an awake civilian once, put weapons away, reach the actual configured settling boundary, and nonsneaking right-click with empty main hand. | Exactly one eligible apology; no pickup and no compulsory menu; bounded negative-heart repair. |
| APO-02 | Test one tick before and exactly at the settling boundary. | Waiting and ready states agree between direct interaction, menu feedback, and execution. |
| APO-03 | Keep a sword in inventory but hold nothing; repeat with an offhand torch and then an offhand weapon. | Stored weapons do not block; benign offhand follows policy; an actually held weapon blocks peacefully making amends. |
| APO-04 | Right-click a villager with no grievance or an already accepted apology. | Ordinary MCA conversation remains available; no repeated heart benefit or intrusive refusal spam. |
| APO-05 | Use deliberate sneak-pickup with the verified compatibility add-on. | Pickup remains its own supported gesture; Crime does not also apologize. |
| APO-06 | Generate main/offhand and generic/specific interaction events in one physical action. | Only the canonical path executes; no duplicate message, memory mutation, menu, or pickup. |
| APO-07 | Target is asleep, restrained, in another actor's active threat, or unavailable. | Correct existing action restrictions; no bypass through the new shortcut. |
| APO-08 | Another mod cancels interaction or a higher-priority custody/lock interaction applies. | Crime does not un-cancel or supersede it improperly. |
| APO-09 | Attempt again after an accepted apology; later create a genuinely new qualifying incident. | Existing per-incident and repeat-cooldown rules hold; a new incident is not permanently blocked by stale deduplication. |
| APO-10 | Submit old menu nonce, unoffered action, distant target, or wrong actor. | Existing packet/action authorization remains intact despite the trusted direct-interaction entry point. |
| APO-11 | Run MCA: Conversations in its supported presentation modes. | Normal dialog and explicit Crime access work; successful direct apology does not open two screens. |
| APO-12 | Repeat with Easy Villagers alone and the exact MCA add-on combination. | Record exact jar versions and actual entry-path results; no claim of compatibility from handler-only unit tests. |

Retain unresolved charges, existing fear rules, and any appropriate restitution/sentence state after an apology. Assert those facts directly, not only the heart count.

### 21.5 Sand Bottle tests

| ID | Scenario | Required result |
|---|---|---|
| SAND-01 | Craft with normal and red sand; throw once in survival and creative. | Correct recipe, projectile, consumption policy, cooldown, and no returned glass bottle on impact. |
| SAND-02 | Right-click air/entity with main/offhand; swap stacks during cooldown. | One throw per accepted action and shared player cooldown; no dual-hand or new-stack bypass. |
| SAND-03 | Spawn or collision is canceled; projectile misses or reaches lifetime limit. | No unauthorized effect, duplicate refund, stuck projectile, or repeated burst. |
| SAND-04 | Direct hit plus splash contains the same target. | Target receives one direct-hit application, not direct plus splash stacking. |
| SAND-05 | Target is at radius boundary, behind a wall, across a closed doorway, or in a dense crowd. | Correct visibility/distance rule, bounded target count, deterministic prioritization, no through-wall splash. |
| SAND-06 | Hit supported melee and ranged mobs, MCA guards, archers, and Thieves. | New visual tracking/aim changes as specified; close contact and remembered positions remain possible; no universal stun. |
| SAND-07 | Blinded guard loses line of sight to a fleeing player. | No perfect live tracking through concealment; prior wanted identity and lawful memory remain. |
| SAND-08 | Blinded thief is actively mugging the thrower. | Appropriate mugging interruption; self-defense context is captured before the session closes. |
| SAND-09 | Apply a second bottle during the effect and during recovery, including another thrower. | No duration refresh, cross-player chain blindness, or inventory-swap workaround. |
| SAND-10 | Cure effect early, unload/reload, log out/in, transfer dimension, or restart. | No permanent impairment; recovery policy remains safe; no stale controller state. |
| SAND-11 | Target has unrelated Blindness/Darkness or another stronger visual restriction. | Sand ending does not clear other effects or improve vision beyond the remaining effect's rules. |
| SAND-12 | Hit immunity-tag entities, protected entities, team allies, creative/spectator players, and PvP-disabled players. | Applicable immunities and protection/cancellation rules win; toggles cannot override server PvP prohibition. |
| SAND-13 | Catch the thrower in splash. | Deliberate self-risk follows policy; no self-crime record or invented attacker. |
| SAND-14 | Witness sees launch; a different witness sees only impact; target is struck from behind. | Knowledge reflects actual observations, not hidden projectile owner identity. |
| SAND-15 | Throw while masked, then change mask during flight. | Launch observations retain their original appearance/provenance; no retroactive identity rewrite. |
| SAND-16 | Affect several lawful victims in one burst; effect is vetoed on one. | One logical attempt and correctly deduplicated successful victim consequences through the existing model. |
| SAND-17 | A reputation bridge, assault listener, or protected-area hook is installed. | No duplicate reputation penalty or fake damage event; vetoed effects are not logged as completed blindness. |
| SAND-18 | Disable sand through reload after launch and while another effect is active. | Documented safe in-flight policy; no free ammunition or unintended global effect clearing. |
| SAND-19 | Dispense the item or use an unverified fake-player path. | No accidentally enabled unattributed weapon automation. |
| SAND-20 | Run client with reduced particles, multiple GUI scales, shaders/resource packs where supported. | Readable non-flashing presentation; client settings do not change server perception rules. |

### 21.6 Suite-wide regression and runtime matrix

At minimum, validate the primary Forge target with every MCA jar the project explicitly claims to support for the affected bindings. Test the normal runtime jar and every listed compatibility probe; update the list when the actual checkout has added versions. A reflection probe is necessary but not sufficient for visible professions and behavior. [R01][R05]

Run a dedicated server with a connecting client, not only an integrated single-player world. Include a second client for shared station, mugging, witness identity, and PvP/protection cases. Exercise optional companions both present and absent, with exact version pins in the report.

Regression coverage must preserve restraints and release interactions, family protections, current currency behavior, stolen-goods recovery, Fence trading, guard custody, quest bindings, reputation events, and conservative mugging frequency. A Thief returning to work or changing mask cannot reset a victim-protection timer.

Build and packaging checks must confirm no bundled MCA/Architectury/companion classes, no client imports reachable during dedicated-server startup, valid registration ordering, and complete resources. Use the repository's available helper scripts where instructed; when a platform-specific helper is unavailable, record the equivalent supported invocation rather than pretending it ran.

---

## 22. Performance, diagnostics, and release criteria

### 22.1 Performance rules

Worksite discovery must be loaded-area-only, bounded, and staggered. Occupation reconciliation should run on relevant transitions/load events plus a modest recovery cadence, not rebuild every villager's brain every tick. Cache stable classification capabilities, but invalidate role-dependent results on real changes and configuration reloads.

Sand impact scans once per actual burst with the configured radius and hard target cap. Do not create a persistent cloud that rescans every nearby entity each tick. Perception checks should be cheap effect/policy checks; they must not perform a world scan or a reflective binding discovery on each call.

Menus should recompute recipe availability when inputs, selection, or recipe generation changes. Avoid running a complete recipe search per rendered frame or sending the whole recipe catalog after every click. Bound client selection messages and malformed-packet work.

Profile before and after under the same reproducible loaded-village workload. Record entity count, players, active Thieves, stations, server tick behavior, and any hotspots. Do not claim a performance percentage from a different world or a visual impression. Fix unbounded work even when a small test world appears smooth.

### 22.2 Diagnostics

Extend existing diagnostic commands rather than adding an unrelated operator command system. Proposed read-only role output should show NPC UUID, native profession, Crime occupation/status, responder classification and reason, worksite dimension/position, whether the native claim agrees, required capability availability, and any active mugging/session reason.

Recipe validation should identify a recipe ID, exact failing field, unknown item/tag, excessive count, incompatible restyle family, or missing registered output. Deduplicate warnings per reload; never log a bad definition on every GUI frame or entity tick.

Expose why an actor cannot mug without exposing another player's inventory or hidden identity to ordinary clients. Detailed administrative information requires operator permission. Normal player feedback should remain concise and in-world.

Any repair command should offer a read-only diagnosis/dry run before mutation, identify its exact target, use the shared transition service, and log what changed. Automatic safe reconciliation may resolve guard/Thief conflicts, but must not delete case or property history to “clean up.”

### 22.3 Shutdown and lifecycle cleanup

On menu close and invalidation, return only real inputs once. On player departure, cancel relevant temporary action state through existing owners. On entity unload, relinquish transient control without assuming death. On server stop, clear transient caches so opening a second single-player world cannot inherit the first world's actors, effects, pending crafts, or selections.

No cleanup path may perform an additional theft, reward, craft, apology, or profession assignment. Cleanup releases resources; it does not commit gameplay outcomes that were not completed.

### 22.4 Release acceptance gate

The release is ready only when the agent can demonstrate all of the following:

- The selectable station crafts the exact chosen style, all sixteen catalog styles or documented preserved equivalents are complete, and customization preserves item state.
- A real supported MCA villager acquires an exclusive visible Thief profession through the Mask Station, saves/reloads correctly, and does not retain its previous job.
- Guards and other law responders never initiate or complete muggings, including stale saves, reloads, commands, and mid-action role changes.
- The verified Easy Villagers/MCA combination supports nonsneaking contextual apology without pickup while intentional pickup and ordinary conversation remain intact.
- Sand blindness changes actual supported AI perception, respects protections, cannot be chained indefinitely, and preserves honest witness/legal state.
- Migration, multiplayer, packaging, assets, and required regression checks pass with reproducible evidence.

Update `README.md`, `CONFIG.md`, `DATAPACK.md`, `CHANGELOG.md`, relevant verification/migration documentation, and `API.md` where contracts changed. Regenerate `MODMAP.md` through the available repository tool rather than editing its generated block by hand. Update translations and any example packs to the implemented schema.

Set the release version in its canonical metadata source, respecting the repository's existing expansion mechanism. Do not scatter hard-coded build versions through source files. Documentation may naturally name release 0.7.2. [R01][R05]

An unavailable external test environment is an explicitly unverified release criterion, not a passing test. Do not report “fully compatible” on the strength of compilation alone.

---

## 23. Further expansion without scope creep

### 23.1 Included expansion: make the core systems feel connected

The most valuable expansion is already built into the required scope: expressive mask families, optional tinting, paid restyling at one station, a visible occupation with an understandable workplace, and a defensive consumable that interacts meaningfully with mugging and witnesses.

Use material identity and flavor to make those systems memorable. Cloth belongs to travelers and highwaymen, leather to practical cutpurses, clay to festivals and unsettling disguises, and metal to conspicuous armored silhouettes. These are themes, not hidden stat advantages or proof of alignment.

### 23.2 Low-risk additions after mandatory acceptance passes

| Addition | Recommended scope | Guardrails |
|---|---|---|
| **Fence stock variety** | A few existing-style masks and small quantities of Sand Bottles in the current Fence shop. | Use current currency/stock rules; do not make ordinary crafting dependent on meeting a Fence. |
| **Three small advancements** | Build a Mask Station; craft a first mask; escape an actual mugging using sand. | Flavor only by default; trigger on authoritative completed actions. Restyling or repeatedly selecting previews cannot farm rewards. |
| **Thief worksite flavor** | Occasional mask inspection or restrained crafting pose using existing animations. | No new animation dependency, no continuous particles, no manufactured inventory or criminal confession. |
| **Mask description variety** | Optional witness lines such as “a pale clay mask” or “a dark scarf.” | Describe only what was observed; never convert matching clothing into certain identity. |
| **Pack-author example pack** | One alternate clay cost, one additional recipe for an already registered style, and an immunity-tag override. | Demonstrate the actual shipped schema; do not suggest a datapack can register new Java items or create model geometry. |
| **Compact profession help** | Existing help/tooltip explains that a Mask Station supplies the Thief occupation. | No additional tutorial GUI and no suggestion that the job itself authorizes arrest. |

These are optional polish, not prerequisites that should delay a necessary guard or apology fix. Mark each implemented or deferred in the handoff; do not leave half-connected menu buttons.

### 23.3 Good future directions, deliberately outside core 0.7.2

A later cosmetic pack could add regionally themed designs, rare decorative patterns, or display stands. Keep the base bandana, hockey mask, and other default styles accessible through the ordinary station. Cosmetic rarity should not turn essential disguise access into a random loot requirement.

NPC sand use could eventually give exceptional Thieves a limited escape tool, but it needs its own fairness, ammunition, telegraphing, ally-splash, and anti-harassment design. Do not automatically give every Thief unlimited bottles in this update. Guards must still never mug regardless of future tools or personality traits.

More nuanced recognition could model observed clothing descriptions and continuity across short chases. That should extend the existing witness model, not add omniscient matching by hidden item IDs, real-time location, or player identity. In particular, a mask cosmetic update is not a reason to rebuild the entire wanted system.

Avoid adding a gang hierarchy, faction economy, contraband auto-search, or player-storage raid system here. Those features introduce new consent, protection, economy, and performance problems unrelated to making this update work well.

---

## 24. Coding-agent execution instructions

Treat Sections 1–22 as the implementation contract. Proposed class names may change to fit the actual codebase, but required player behavior, invariants, and acceptance criteria must not be quietly dropped.

**Begin in the actual working checkout.** Read repository instructions and confirm the current branch, commit, version, Java/loader target, dependency versions, test infrastructure, and uncommitted changes. Preserve user work. Locate the newer mask implementation reported by the user before deciding whether a component is new. Where the checkout differs from the reviewed 0.6.4 ref, adapt this specification to the newer source instead of reverting it.

**Implement the requested 0.7.2 update, not just another plan.** Work through the phases, starting with reproducible guard/role failures. Add the station, styles, customization, exclusive occupation, apology path, and Sand Bottle as complete usable features with assets and documentation. Do not replace an existing sound design with a broad rewrite merely because this document names a possible helper class.

**Use real execution evidence.** Run available tests after each relevant increment. Validate actual MCA behavior and the exact pickup add-on path, not only mocks. Inspect logs and failed assertions; do not suppress them or weaken tests to make a build green. Preserve dedicated-server safety and the existing no-static-MCA-linkage/packaging rules.

**Protect data and permissions.** Use authoritative server transactions and current protection hooks. No destructive repository resets, silent save resets, bulk villager replacement, unchecked packet-supplied output, or state-erasing migration shortcuts. Never bypass a guard prohibition, duplicate an item, or clear a case in the name of compatibility.

**Finish with a traceable implementation report.** Record the final branch/SHA and version; summarize files and public/data contracts changed; list actual commands, tests, runtime jar combinations, and results; explain save migration and deprecated configuration; identify optional polish included/deferred; and state any remaining release blockers or unverified environments precisely.

Do not call a feature complete while its screen is a placeholder, its mask lacks an equipped model, its profession is only a label, or its blindness is only an icon. Do not claim the exact reported compatibility bug is fixed until the relevant user gesture is exercised in the tested combination.

---

## 25. Source references

Repository references below are pinned to the inspected commit, except the branch-list response. They establish the review baseline, not a claim that the user's latest local code is identical. External references are official Forge documentation for the 1.20.1 target. Access/review date: September 13, 2026.

| Reference | Source and relevance |
|---|---|
| [R01] | `gradle.properties`: inspected version, Minecraft/Forge target, runtime dependencies, and MCA probe versions. |
| [R02] | `CrimeItems.java`: item registrations in the reviewed ref. |
| [R03] | Repository branch listing: branches returned during this review; this endpoint is mutable. |
| [R04] | `build.gradle`: Java, ForgeGradle, runtime/compile dependency separation, and available run configuration. |
| [R05] | `CLAUDE.md`: repository conventions, compatibility binding, build/packaging rules, configuration, and side separation. |
| [R06] | `mods.toml`: mandatory MCA runtime dependency and optional companion declarations. |
| [R07] | `CriminalProfessions.java`: presentation-only professions and absence of a Thief workstation in the reviewed ref. |
| [R08] | `WorldCriminalJobService.java`: job authority, optional presentation, previous-profession handling, and immutable record updates. |
| [R09] | `CriminalJobAssigner.java`: pure assignment policy and candidate exclusions. |
| [R10] | `CriminalJobAssignmentSweep.java`: candidate discovery and existing-record handling. |
| [R11] | `NpcMuggingService.java`: participant checks, session boundaries, callbacks, final transfer, and cleanup. |
| [R12] | `GuardPopulationService.java`: automatic guard-promotion candidate path. |
| [R13] | `EntitySelectors.java`: responder identity versus current availability and configured selectors. |
| [R14] | `MemoryInteractionHandler.java`: inspected sneak-based apology/menu interaction path. |
| [R15] | `ApologizeActionHandler.java`: shared eligibility and bounded relationship repair. |
| [R16] | `CriminalVillagerRecord.java`: persisted occupational fields and historical profession serialization. |
| [R17] | `docs/APOLOGY_VERIFICATION.md`: previous apology design/verification notes, not test results for this specification. |
| [R18] | `ThiefBehaviorService.java`: tracking, role checks, movement ownership, and runtime controller behavior. |
| [F01] | Forge menus: server/client menu responsibilities, validity, slot handling, and synchronization. |
| [F02] | Forge custom recipes: recipe/type/serializer responsibilities and output assembly. |
| [F03] | Forge networking: server-thread handling, packet validation, and avoiding unsafe world access. |
| [F04] | Forge events: priority, cancellation, and event behavior. |
| [F05] | Forge model tinting: client item-color and tint-index handling. |

[R01]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/gradle.properties "Reviewed build properties"
[R02]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/item/CrimeItems.java "Reviewed item registrations"
[R03]: https://api.github.com/repos/otectus/MCACrime/branches?per_page=100 "Repository branch listing"
[R04]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/build.gradle "Reviewed Gradle build"
[R05]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/CLAUDE.md "Repository engineering instructions"
[R06]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/resources/META-INF/mods.toml "Runtime dependency declarations"
[R07]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/job/CriminalProfessions.java "Criminal profession registration"
[R08]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/job/WorldCriminalJobService.java "Criminal job service"
[R09]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/job/CriminalJobAssigner.java "Assignment policy"
[R10]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/job/CriminalJobAssignmentSweep.java "Assignment sweep"
[R11]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/mug/npc/NpcMuggingService.java "NPC mugging transactions and eligibility"
[R12]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/enforcement/GuardPopulationService.java "Guard promotion path"
[R13]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/detect/EntitySelectors.java "Entity and responder selectors"
[R14]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/memory/MemoryInteractionHandler.java "Memory interaction routing"
[R15]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/action/handler/ApologizeActionHandler.java "Apology action execution"
[R16]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/state/world/CriminalVillagerRecord.java "Criminal occupation persistence"
[R17]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/docs/APOLOGY_VERIFICATION.md "Historical apology verification notes"
[R18]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/ai/thief/ThiefBehaviorService.java "Thief runtime behavior"
[F01]: https://docs.minecraftforge.net/en/1.20.1/gui/menus/ "Forge 1.20.1 menus"
[F02]: https://docs.minecraftforge.net/en/1.20.1/resources/server/recipes/custom/ "Forge 1.20.1 custom recipes"
[F03]: https://docs.minecraftforge.net/en/1.20.1/networking/simpleimpl/ "Forge 1.20.1 networking"
[F04]: https://docs.minecraftforge.net/en/1.20.1/concepts/events/ "Forge 1.20.1 events"
[F05]: https://docs.minecraftforge.net/en/1.20.1/resources/client/models/tinting/ "Forge 1.20.1 model tinting"

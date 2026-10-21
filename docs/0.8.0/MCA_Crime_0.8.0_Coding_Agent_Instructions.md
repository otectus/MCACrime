# MCA: Crime 0.8.0 — Coding Agent Implementation Instructions

**Repository:** https://github.com/otectus/MCACrime  
**Requested release:** `0.8.0`  
**Reviewed baseline:** `main`, commit [`f7564ed888fd081a893fbc779166f09c40303f2b`](https://github.com/otectus/MCACrime/commit/f7564ed888fd081a893fbc779166f09c40303f2b), currently declaring `0.7.8`.  
**Review date:** October 20, 2026.  
**Target:** Minecraft 1.20.1, Forge, Java 17, using the repository's existing dependency and compatibility configuration.

## 1. Mission and release intent

Implement a focused reliability, usability, and customization update responding to this player feedback:

> “for some reason when i try to use the commands, it says no mca villager is around.. can someone tell me how to fix it”

> “how do you use the guillotine”

> “how do I use a keyring / keymold”

The owner also requires **dyed variants of every mask**.

Treat these reports as evidence that players cannot reliably discover or complete the intended workflows. Investigate real faults, improve the interaction paths, and teach the prerequisites at the point of use. Documentation alone does not finish this update.

The completed release must provide:

1. Predictable command targeting and specific, actionable failures.
2. A guillotine a player can assemble, load, operate, and troubleshoot using in-game guidance.
3. Understandable, working key, key-ring, and key-mold workflows with no lost bindings or duplicated items.
4. All **16 standard Minecraft dye colors for every shipped mask style**, available in survival and discoverable in creative.
5. Focused regression tests, real client/server verification, accurate documentation, and release metadata.

This document specifies implementation work for the receiving coding agent. The review that produced it was a source inspection; it did not reproduce the player's issue, launch Minecraft, or run the repository's build. Do not describe suspected causes below as confirmed runtime bugs without reproducing them.

## 2. Start from the actual repository

Read `CLAUDE.md`, `MODMAP.md`, `gradle.properties`, the relevant existing tests, and any applicable `AGENTS.md` present in the implementation checkout. Record the branch and starting SHA. If the branch has advanced, reconcile these instructions with its newer behavior and retain intervening fixes.

### Verified starting points

| Area | What the reviewed code actually contains | Implication for 0.8.0 |
| --- | --- | --- |
| Version | `gradle.properties` declares `0.7.8` | Set the requested release to `0.8.0`; no intermediate release is required by this handoff. |
| Operator targeting | `/crime job` uses the nearest recognized MCA villager in an inflated search box, nominally 8 blocks. `/crime debug villager` uses 10. | The code does not choose the villager under the crosshair merely because a comment describes that intent. |
| Gameplay targeting | `/crime mug` and `/crime report` use a 4-block entity ray through `CrimeActionService.rayTraceLiving`. | Preserve close-range gameplay and action validation while improving selection and explanations. |
| MCA identity | `McaCompat` delegates to `McaHandles`; `McaBinding` probes multiple package roots. | A failed identity binding and an empty search must be distinguishable. Do not introduce direct MCA imports. |
| Guillotine assembly | The blade frame must cap the upper half of a two-block pillory: three blocks total. | Teach the assembly explicitly; it is not a standalone execution block. |
| Execution | A live capital sentence and a valid execution authorization are required. The operator execute path refuses when no usable assigned site exists. | Restraint, Wanted status, or high Heat alone must not become permission to execute. |
| Key rings | Create from multiple bound keys, add bound keys, remove the last key by crafting a ring alone; capacity defaults to 16. | Preserve this behavior and make it visible. A ring is a collection of keys, not universal access. |
| Key molds | Bound key + clay ball creates an impression; a furnace fires it; baked mold + iron ingot creates a copy. A fresh baked mold allows four casts. | Preserve input NBT and crafting remainders throughout the chain. |
| Masks | `MaskVariant` contains 16 styles; all report `tintable() == true`. `MaskItem` already implements `DyeableLeatherItem`. | Extend and verify existing dye support instead of inventing a parallel item system. |
| Mask presentation | Item colors and the GeckoLib armor renderer already read dye color. The creative tab currently publishes only the base masks. | Explicit dyed availability and visual quality still need work and verification. |
| Network | `CrimeNetwork` currently uses protocol `20` with exact matching. | Bump only if the wire contract changes; keep client and server declarations consistent. |

### Preserve these architectural contracts

- Use existing server services for actions, occupation changes, custody, locks, sentencing, and inventory transactions. A tooltip, command, or new screen must not implement a second gameplay authority.
- Keep MCA access behind `McaCompat` / `compat/mca`. Preserve supported package-root probing and the existing MCA version fleet.
- Keep client classes out of dedicated-server loading paths. Retain GeckoLib and the current native mask models.
- Follow the existing COMMON + CLIENT configuration design. Do not introduce a SERVER spec merely for this update.
- Preserve registry IDs, existing worlds, custom datapacks, and public APIs unless a demonstrated incompatibility requires a documented migration.
- Keep optional companions optional. Cuffed and Locks Reforged are not prerequisites for these native features.
- Preserve the distinction between physical restraints, physical device occupancy, legal custody, and a capital sentence.
- Do not expand the list of capital crimes or change mask concealment strength as part of usability work.
- Keep searches bounded to relevant loaded entities/chunks. No per-tick world scans or forced chunk loading for help, targeting, or diagnostics.

## 3. Workstream A — Reliable command targeting

### A1. Establish the failure before choosing a fix

The report does not identify a command, mod version, distance, or entity type. Do not assume it proves a package rename, an insufficient radius, or a missing MCA installation.

Trace every command path that emits the reported wording or a similar “no villager” response. Start with:

- `command/CrimeCommand.java`: `assignJob`, `debugVillager`, `nearestMcaVillager`, and targeted debug commands.
- `action/CrimeActionService.java`: `startMugFromCommand`, `rayTraceLiving`, contextual reach checks, and command fallback gating.
- `report/PlayerCrimeReportService.java`: `openTargetedFromCommand` and responder validation.
- `compat/McaCompat.java`, `compat/mca/McaHandles.java`, and `compat/mca/McaBinding.java`.
- `assets/mcacrime/lang/en_us.json`: particularly `mcacrime.command.job.none_nearby`, `mcacrime.command.debug.none`, and gameplay target messages.

Record reproducible cases for: a nearby MCA adult; an MCA child; a villager without a home village; multiple villagers; a wall between player and target; a vanilla villager; an MCA zombie variant; an unrelated nearby mob; a guard; and a target at the exact range boundary. Determine whether failure occurs during identity recognition, target selection, eligibility checks, or action execution.

### A2. Separate selection from permission to act

Introduce or consolidate a small targeting helper with explicit policies. Suggested names are `CrimeTargetResolver`, `TargetRequest`, and `TargetResolution`; use existing equivalent abstractions if available.

The result must preserve the difference between:

- No entity hit or no nearby candidate.
- An entity was found but is not a supported MCA human villager.
- MCA's required entity identity binding is unavailable.
- A valid target is outside reach, obstructed, dead, removed, or in another dimension.
- Several nearby candidates make automatic selection ambiguous.
- The selected entity is valid, but the requested action is unavailable for a specific reason.

Do not discard the selected villager simply because the action cannot run and then report that no villager exists. For example, an ineligible child, a protected profession, an occupied worksite, or a guard unavailable to receive reports needs its own explanation from the owning service.

### A3. Make default operator selection predictable

For job assignment/clearing and comparable nearby operator commands, implement this policy:

1. An explicit entity argument selects exactly that entity and is validated without fallback.
2. Otherwise, prefer the visible entity under the player's crosshair within the command's documented range.
3. If the player is not aiming at an entity, allow a fallback only when exactly one valid, visible MCA candidate exists in the local range.
4. If multiple candidates remain, ask the operator to look at one or provide an explicit target.
5. If the aimed-at entity is unsupported or ineligible, explain that result. Do not mutate a different villager behind or beside it.

Use an inflated box as a broad-phase search only. Apply the intended distance test afterward so a corner of the box does not silently extend a documented spherical range. Define the distance metric once and test its boundaries. For crosshair selection, intersect entity hitboxes and clip the ray against blocking geometry; do not ray-test entity centers alone.

Preserve existing no-argument syntax. Add single-target forms where missing, for example:

```text
/crime job assign thief [target]
/crime job assign fence [target]
/crime job clear [target]
/crime debug villager [target]
```

These are proposed extensions. Implement them in Brigadier before documenting them as available. Use single-entity arguments and reject ambiguous selectors. Job mutation stays at its existing permission level 3; diagnostic access stays at its existing level. Explicit operator selection may use normal loaded-entity selector semantics without pretending to be a close-range gameplay action. Document that distinction.

Player-only implicit selection must tell console/command-block sources that an explicit target is required. An explicit operator command should work from console where its service does not require a player. Where a diagnostic genuinely needs player-relative data, omit that field or explain the requirement.

### A4. Keep gameplay targeting strict and consistent

`/crime mug` and `/crime report` should target the visible entity the player means, using the existing 4-block gameplay limit and the appropriate action/responder rules. They must not inherit the wider operator search radius or silently select a convenient nearby victim or guard.

- Clip entity rays against intervening blocks and retain server-side line-of-sight validation.
- Use the same reach convention for selection and final validation, or explicitly account for any hitbox-versus-position difference. Do not offer an action that inevitably fails because two helpers measure reach differently.
- Route actions through the existing service, including cooldowns, ownership, active-session conflicts, nonce/replay protection, and restraint restrictions.
- Revalidate before the action commits. A target selected a moment ago may have moved or disappeared.
- Keep self-directed commands such as status, escape, surrender, and ransom payment independent of nearby-villager selection.
- Preserve `allowGameplayCommandFallback` semantics. Help remains available when gameplay command fallbacks are disabled. Audit `/crime report` deliberately against its existing policy instead of accidentally changing it through a shared helper.

### A5. Improve errors and diagnostics

Use translated, specific messages. Examples of the intended level of detail:

| Situation | Example player-facing response |
| --- | --- |
| Nothing in gameplay reach | “Look directly at an MCA villager within 4 blocks.” |
| Wrong entity | “This command needs an MCA villager. You are targeting a vanilla villager.” |
| Obstructed target | “Move where you can see the villager clearly.” |
| Ambiguous operator fallback | “Several MCA villagers are nearby. Look at one or specify a target.” |
| Identity binding unavailable | “MCA villager recognition is unavailable. Ask an operator to check `/crime debug mca`.” |
| Responder found, wrong role | “You are targeting an MCA villager, but reports must be given to an available guard.” |
| Action disallowed | Use the service's actual reason, naming the selected target when useful. |

Resolve numeric limits from the real policy, not duplicated strings. Do not send both a precise service failure and a second generic command failure.

Extend the existing MCA diagnostic output and add a read-only `/crime debug target [target]` if no equivalent exists. At permission level 2, it should report selection mode, entity registry ID, distance, visibility, MCA identity result, matched package root/binding status, and the relevant rejection. Limit candidate output; avoid dumping unrelated entity NBT or private case data.

A partial MCA binding should disable only the capabilities that are actually missing. Do not reject recognized villagers because an unrelated optional accessor failed. If a new package layout is the reproduced cause, fix the binding manifest and extend real-jar probes; never replace recognition with a name substring or a generic vanilla villager cast.

### A6. Acceptance criteria

- The villager visibly selected by the operator is the one changed, even with a closer bystander.
- No villager, wrong entity, broken identity binding, obstruction, ambiguous selection, and action ineligibility yield distinguishable responses.
- A valid MCA villager without a home village remains recognizable; a village requirement is a separate action condition.
- Exact boundaries, diagonal search-box corners, targets behind walls, despawn, and dimension changes behave as documented.
- Old command forms remain usable. New explicit forms obey permissions and work from appropriate non-player sources.
- Gameplay commands cannot acquire extra reach or bypass their normal action rules.

## 4. Workstream B — Make the guillotine understandable and dependable

### B1. Teach the actual assembly and controls

Provide a short in-game guide with this sequence, validated in the client:

1. Craft and place a **Pillory** on a suitable surface. It occupies two vertical blocks.
2. Place the **Guillotine** frame directly above its upper half. The complete structure needs three blocks of height; the frame takes its facing from the pillory.
3. Bring a valid subject to the pillory's capture position using the existing movement/escort mechanics, or have them stand there.
4. Right-click the pillory boards to secure that subject. Show who was actually secured.
5. Operate the upper guillotine frame to lower or raise the blade. A lethal operation requires the existing sentencing and authorization conditions.
6. Operate the pillory boards to release physical occupancy through the established transition service. Physical release does not automatically pardon the subject.

Explain crouch-placement where necessary: the current crouching interaction passes through to normal block placement. Do not take over that gesture with a help action while the player is holding a block.

Validate placement near build-height limits, obstructed spaces, both orientations, broken lower halves, and an occupied device. Refused placement should explain “Place this above the upper half of a pillory” when that is the actual issue. Avoid generic failure for an otherwise valid assembly blocked by terrain or height.

### B2. Show the next useful action

Add short tooltips to the pillory and guillotine BlockItems, plus an expanded help section. Add contextual feedback when the player interacts with the device; a permanent HUD is unnecessary.

Useful states include:

- Incomplete assembly.
- Empty pillory: explain the standing/capture position.
- Occupied: name the subject and explain which part opens the boards.
- Occupied, no valid capital sentence: explain why the blade cannot execute.
- Capital punishment or guillotine damage disabled by the server.
- Valid order armed, blade moving, operation canceled, or blade ready to raise.
- Guard execution unavailable because no assigned execution site exists.

Keep the primary response to one or two lines with a help link. Use an action-bar update or equivalent immediate feedback, and suppress repeated narration of unchanged state. A no-damage blade animation must be explained; it must not look like a broken item.

### B3. Preserve sentencing and distinguish manual from guard operation

Start with `PilloryBlock`, `GuillotineBlock`, `GuillotineBlockEntity`, `DetentionService`, `ExecutionAuthorization`, `CapitalSentenceService`, `CondemnedEscortService`, and `ExecutionSiteRegistry`.

- Keep the current capital-sentence requirement. Do not turn the guillotine into an unrestricted damage trap to make it “work.”
- The baseline's capital-offense gate is killing a guard. Do not teach Heat, ordinary Wanted status, royal decrees, or handcuffs as substitutes for a capital sentence.
- Explain that NPC capital eligibility is disabled by default. A test with an arbitrary villager is not a valid execution test.
- Guard-led execution requires the existing assigned-site/facility path. Teach its actual verified operator syntax in the operator help page.
- `/crime capital inspect <target>` inspects the legal condition. `/crime capital execute <target>` is an operator action that uses the device/escort path; it is not a generic kill command.
- Retain current authority rules for player operation unless a concrete flaw is found. Do not silently require OP for a previously permitted player action or permit unverified authority through a new UI.

The relevant existing settings include `detention.guillotineEnabled`, `detention.guillotineActivationDelayTicks`, and `sentencing.capitalPunishment.*`. Explain effective server values and distinct concepts: blade travel, the guard ceremony/order window, feature enablement, guard permission, NPC eligibility, and site requirements.

**Audit timing instead of trusting labels.** The manual lever starts the blade delay directly; the guard path uses `CondemnedEscortService.dropAt`. Do not advertise a full ceremony countdown for manual operation unless the implementation actually provides one. Verify zero/short ceremony settings, a blade delay longer than the order window, and tick-order boundaries. Fix valid settings that cause an order to expire before its intended strike, or reject impossible combinations with an explicit configuration diagnostic. No timer may execute a subject without an existing valid order.

### B4. Reconcile misleading comments and cancellation behavior

The reviewed source has comments claiming that an unauthorized blade drop opens the device and lets the occupant walk away. The visible early-return path in `GuillotineBlockEntity.strike` does not itself release occupancy. Other comments claim unload persistence keeps an execution active, while `ExecutionAuthorization` is deliberately memory-only and unload clears it. `capitalExecute` also retains older comments describing a no-device shortcut that its body no longer performs.

Trace these paths end to end and correct their comments, configuration descriptions, help, and tests to match the intended behavior:

- An invalid or canceled order causes **no execution damage**.
- Canceling an execution order does not erase the sentence or legal custody.
- The normal board-opening, rescue, escape, pardon, and commutation paths retain their separate effects and legal consequences.
- Chunk unload/restart invalidates the transient execution order; an old persisted blade delay must not resurrect it.
- A destroyed device clears occupancy and authorization through the existing services, without duplicate release events or unjustified legal freedom.

Do not add an automatic jail release to satisfy an inaccurate comment.

### B5. Verify authorization at the actual strike

At the damage point, validate the current occupant, canonical device position, dimension, live sentence, order, actor attribution, and relevant occupancy generation. `Pending` already stores a generation; prove that a stale order cannot affect a replacement occupant or a new custody episode. Reuse existing checks where they already establish these facts.

Keep one attributed damage action through the normal damage pipeline. Totems, invulnerability, canceled deaths, and revive integrations must not produce fabricated deaths, duplicate heads, premature sentence resolution, duplicate property recovery, or extra bounties. Distinguish a canceled/no-order operation from a strike that occurred but did not kill.

### B6. Acceptance criteria

- A player can assemble and use the device from in-game instructions without reading repository source.
- Interacting with an empty device, a misplaced frame, an unsentenced subject, or a disabled feature gives the correct explanation.
- Successful capture identifies the actual occupant; two simultaneous users cannot claim it twice.
- Manual and guard operation behave as their help text states; operator examples use valid registered syntax.
- Pardon, commutation, rescue, escape, actor removal, device destruction, unload, and restart cannot leave a stale lethal action.
- A valid execution has exactly one damage attempt and only confirmed death produces death-dependent outcomes.

## 5. Workstream C — Keys, key rings, and molds

### C1. Preserve and teach the existing crafting chain

Use the exact item names **Key**, **Key Ring**, **Key Mold**, and **Baked Key Mold**. Accept `keyring` and `keymold` as help-topic aliases while retaining the existing registry IDs.

| Operation | Inputs | Result and retained items |
| --- | --- | --- |
| First key | A blank native Key used on an eligible native lock holder | Binds using the existing first-key/owner rules; explain the resulting lock state. |
| Create ring | Two or more bound native keys for distinct locks, within capacity | One ring containing those bindings; input keys are consumed into it. |
| Add to ring | One ring + one or more valid bound keys | One updated ring; reject duplicate locks, extra rings, and overflow without consuming anything. |
| Remove from ring | One nonempty ring alone in the crafting grid | The last-added key is returned; the ring remains in the grid with one fewer key. |
| Make impression | One bound Key + one clay ball | An imprinted Key Mold; return the original key. |
| Fire impression | An imprinted Key Mold in a normal furnace with fuel | A Baked Key Mold preserving the recorded binding. |
| Cast key | A usable Baked Key Mold + one iron ingot | One matching key; return the mold with one fewer cast, consuming it on its final cast. |
| Reset key | The existing key-alone crafting recipe | A blank key. Clearly explain that this intentionally erases the binding. |

Source the recipe behavior from `recipe/lock/KeyCraftLogic` and its adapters. Do not replace dynamic NBT recipes with ordinary fixed-result recipes: a generic furnace or crafting output can lose the lock identity or revision.

### C2. Make creative and blank states understandable

The creative tab currently exposes unbound keys, empty rings, and mold templates. These states need an explanation rather than an apparently functional item with no workflow.

Add a small, compatible convenience recipe:

```text
Blank raw Key Mold + bound Key -> imprinted Key Mold
Original key is returned.
```

Keep the existing bound-key-plus-clay recipe. Only an actually blank raw mold may be imprinted by the new recipe. Refuse overwriting a recorded mold, imprinting a baked mold, or copying from an unbound/foreign key. The new path must conserve materials and use the same binding-copy logic and remainder handling as the existing impression recipe.

An empty ring should explain both adding keys in a crafting grid and any existing permitted first-key initialization behavior. A blank baked mold should explicitly say it has no recorded key and cannot cast; do not pretend it is a usable four-copy template. Do not invent a lock binding merely to make a creative sample functional.

### C3. Upgrade tooltips and help

Give each item a short always-visible instruction and a compact expanded description. Use the project's chosen tooltip expansion convention and localizable modifier hint.

- **Blank Key:** how to establish the first binding, which native targets support it, and why an already initialized/foreign lock may reject it.
- **Bound Key:** readable binding label, how to make a clay impression, and the key-reset behavior.
- **Key Ring:** actual count and effective capacity; how to add and remove keys; automatic matching when used on a compatible lock. Show a bounded list of readable labels, including a sensible unnamed-key fallback.
- **Raw Key Mold:** whether an impression exists, the copied key's label if known, and the furnace step.
- **Baked Key Mold:** whether the impression is usable, the exact remaining casts, and the iron-ingot step.
- **Handcuffs/Shackles Keys:** explain that they are restraint-family keys, distinct from native lock keys and the lock-key copying chain.

Do not claim a key is current, invalidated, or matches a distant lock solely from client-side item NBT. A key's revision can only be judged against authoritative lock state. Use observed interaction results for such statements.

If the server's ring-capacity setting is not available to the client, synchronize the narrow value needed or label the client value as unknown. Do not display a client default as an authoritative server limit.

### C4. Make failed interactions useful

Preserve distinct outcomes for:

- A key or ring does not contain a matching current binding.
- A matching identity has an obsolete revision after rekeying.
- First-key initialization is not permitted for this actor or has already been used.
- Ring capacity has been reached.
- A native lock is disabled, missing, or otherwise unavailable.
- Another mod owns the lock and the current coexistence policy refuses it.
- A mold has no impression or no casts left.

Only claim a specific reason the server can establish. Do not expose secret lock codes or another player's lock contents in public error text. For crafting-grid failures, provide help/recipe descriptions rather than chat spam on every slot change. Any dedicated item UI may show an inline reason, but a new ring inventory screen is not required to finish 0.8.0.

Use names from the actual held item and target. Do not tell players a Key Mold opens a lock directly or that a native Key Ring can absorb arbitrary keys from other mods.

### C5. Preserve transaction integrity

- Carry the complete `KeyBinding`, including lock ID and revision, through ring operations, raw molds, furnace output, and cast keys.
- Rekeying must invalidate old keys, ring entries, and subsequent copies made from old molds.
- A ring-capacity reduction must preserve existing entries while refusing further additions.
- Repeated result previews must be pure: no input mutation, quality reduction, binding creation, or duplication until the craft is taken.
- Check shift-click crafting, item remainders, full inventory, closing the menu, server resync, hopper-fed furnace behavior, and sequential molds for different locks.
- Preserve the original key's relevant metadata when returning it. Do not promise arbitrary original ItemStack metadata survives conversion into a ring unless that data is actually stored; explicitly resolve any demonstrated loss of meaningful names/provenance instead of silently discarding it.
- Preserve custom ring labels and unrelated supported ring metadata on add/remove operations.
- Return one well-formed ring and the correct key count for disassembly. Multiple rings in a grid must never collapse into a single returned key.
- Keep the existing intentional reset recipe separate from copying and ring operations so it cannot match a larger valid workflow by accident.

### C6. Acceptance criteria

- A new player can bind two keys, create a ring, use both locks, remove one key, and restore it using only in-game help.
- A bound key can be copied through both raw-impression entry paths, furnace firing, and four casts; the original survives and the fifth cast is unavailable.
- Copies open the original lock and fail after its revision changes.
- Empty/blank, duplicate, over-capacity, wrong-mod, and obsolete-binding cases are understandable and preserve inputs.
- All workflows work in a dedicated-server session and survive save/reload with their bindings intact.

## 6. Workstream D — Every mask, every standard dye

### D1. Define completion precisely

Ship every combination of the 16 existing styles and these 16 Minecraft colors:

`white`, `orange`, `magenta`, `light_blue`, `yellow`, `lime`, `pink`, `gray`, `light_gray`, `cyan`, `purple`, `blue`, `brown`, `green`, `red`, `black`.

That is **256 standard dyed combinations**, plus **16 original undyed appearances**. Generate enumeration from `MaskVariant` and the game's dye enumeration; do not maintain 256 unrelated registrations or a second hard-coded mask list.

| Family | Registry paths and current display names |
| --- | --- |
| Cloth | `bandana` — Bandana; `highwaymans_domino` — Highwayman's Domino; `wrapped_scarf` — Wrapped Scarf; `half_veil` — Half Veil |
| Leather | `leather_mask` — Leather Mask; `raven_mask` — Plague Doctor Mask; `jackal_mask` — Jackal Mask; `stitched_mask` — Stitched Mask |
| Clay | `hockey_mask` — Hockey Mask; `clay_mask` — Clay Mask; `comedy_mask` — Comedy Mask; `tragedy_mask` — Tragedy Mask |
| Metal | `iron_skull_mask` — Balaclava; `brigand_visor` — Brigand Visor; `owl_mask` — Owl Mask; `blank_iron_mask` — Blank Iron Mask |

Keep the legacy `iron_skull_mask` ID and its existing crafting family despite its Balaclava display name. Do not rename or rebalance it to tidy the table.

### D2. Extend the current color representation

Retain one registered item per style and the existing `display.color`/`DyeableLeatherItem` representation. Standard dyed variants are ordinary item stacks with color data, not 256 new item IDs. Preserve existing custom RGB and blended colors.

Use one canonical helper for producing standard-color examples and checking dye appearance. Creative samples must use the same dye calculation as crafting an undyed mask with that dye; arbitrary enum RGB values can differ from the existing leather-dye blend.

Distinguish **no custom color** from **an explicitly stored color**, even when their numeric RGB values happen to match. The current `colorOf` fallback is white; an RGB-only no-change comparison is insufficient if undyed and explicitly dyed render paths differ.

Color remains cosmetic. Dyeing, washing, or restyling must not:

- Change family durability, repair damage, grant armor, or make masks enchantable.
- Clear deferred Heat, witness knowledge, masked pursuit, identity exposure, or any legal state.
- Strip names, lore, curses, provenance, or supported foreign data.
- Rewrite an arbitrary third-party helmet just because it is tagged as a mask.

Continue using `MaskCustomization` and `MaskNbtTransfer` for style conversions. Preserve their explicit unsupported-data refusal. Do not weaken the safeguards to make a preview appear successful.

### D3. Make all colors obtainable and discoverable

**Survival:** the Mask Station remains the primary workflow. Every shipped craft/restyle recipe must permit the appropriate dye operation, and every style must be recolorable without requiring a different style first. Keep recipe-defined material/binding costs and one dye per successful operation. Reject exact no-ops without consuming inputs.

The station must show:

- Which slot accepts dye and whether it is optional.
- A result preview with the actual color and damage/metadata behavior.
- A clear reason when the current style is filtered out, an operation is unchanged, data cannot be transferred, or materials are missing.
- Correct output after changing dye, switching styles, removing ingredients, or reloading recipes while the menu is open.

**Creative:** add a dedicated Masks tab containing each style's original followed by all 16 standard colors in a stable order. Keep existing base-mask access in the Crime tab. Derive the new tab from the canonical catalog. Confirm creative search can find the color variants and that taking a stack preserves its NBT. If the current UI infrastructure offers an equally clear filtered catalog, it must still expose all 272 stacks without command-only access.

**Naming:** show localized color information. A standard result can display “Blue Plague Doctor Mask” or a clear “Color: Blue” line; custom RGB should show “Custom color,” with a hex value confined to advanced details if useful. Preserve player-assigned custom names. Do not label a blend as an exact standard color unless its stored value actually matches the canonical sample.

**Reversibility:** add or verify washing dyed masks in a water cauldron using the appropriate vanilla/Forge interaction path. Washing removes only the color field and consumes the intended water level once; it does not erase the entire display compound. Do not assume implementing `DyeableLeatherItem` automatically registers cauldron behavior for custom items. An undyed mask is a no-op.

### D4. Make color visible on the actual artwork

The current renderer multiplies the whole GeckoLib texture by the stored color. Verify all styles visually before deciding that this is sufficient. Dark or strongly colored original textures can make several dyes nearly indistinguishable; tinted lenses, straps, and metal details may also become muddy.

For styles that fail the visual review, use a neutral dyeable base plus preserved detail layers or equivalent material regions:

- Dye the intended cloth, leather surface, painted clay, or enamel panel.
- Preserve readable eyes, openings, rivets, seams, straps, lenses, and highlights.
- Maintain the existing silhouette and pixel-art scale.
- Keep all original undyed appearances intact. If neutralization changes the original artwork, retain a separate original-texture path for undyed stacks.
- Match inventory icon, held/dropped item, station preview, and worn appearance.

Handle the custom `highwaymans_domino` item model as well as generated item models. Preserve correct tint indices for any newly split layers.

For GeckoLib, account for `MaskArmorRenderer.renderToBuffer` suppressing vanilla's duplicate armor pass. A new untinted detail layer must render through a deliberate, compatible path; it must not be accidentally suppressed or draw the whole model twice. Do not regress head attachment, animation following, player skins, or armor-stand display.

No runtime texture per individual stack, per-frame image generation, or hundreds of duplicated color textures are needed. Cache immutable shared resources and keep resource reload behavior correct.

### D5. Validate the full matrix

Automate catalog/recipe/data checks across all **16 × 16** combinations. For each, prove that the output is the correct style, has the canonical color, preserves its expected item properties, and survives serialization.

Create actual game-rendered contact sheets for all standard colors in inventory and equipped views. Inspect them visually; a test that only finds a PNG cannot establish that the color is visible. Include the original undyed appearance as a comparison. Use these as verification artifacts in the implementation PR or validation document.

Exercise:

- Fresh crafting with every dye; same-style recoloring; repeated blending; washing; same-family restyling.
- Explicit white/custom white versus undyed, and black/dark dyes on the darkest mask styles.
- Damaged, named, cursed, lore-bearing, custom-RGB, and unsupported-capability stacks.
- Rapid alternation between differently colored wearers to catch shared-renderer color leakage.
- Inventory, hand, dropped item, armor stand, local player, remote player, and supported MCA wearers.
- Two players using stations simultaneously; input removal; full inventory; shift-click; stale selection after `/reload`.
- Loading existing undyed and dyed masks from an older world without losing their data.

Completion means every combination is obtainable and visibly distinct where the dye colors differ, not merely that `tintable()` returns true.

## 7. Shared help and presentation

### Required in-game entry points

Implement `/crime help` with concise, paginated topics:

```text
/crime help commands
/crime help targeting
/crime help guillotine
/crime help keys
/crime help keyring
/crime help keymold
/crime help masks
```

These topic names are proposed for this release. Keep help read-only, available to ordinary players, and useful from console. The root `/crime` may open the same overview if it currently has no useful default action; preserve established behavior if it does.

Each topic should contain a short practical sequence, prerequisites, one or two troubleshooting branches, and links to related topics. Operator-only examples should be clearly labeled and permission-filtered where practical. Chat links for mutating commands must suggest the command for review, not execute it automatically.

Use a small shared help registry for topic IDs, localization keys, aliases, and related items so commands and tooltips do not drift. Resolve live server facts through the existing services; avoid an elaborate new generic tutorial framework.

Add a lightweight Help affordance to the Mask Station linking to its dye/restyle instructions. Key and device tooltips should point to their matching help topics. A physical guidebook, forced onboarding screen, extra keybind, and mandatory guide dependency are unnecessary.

### Recipe discovery

Make fixed recipes and their unlocks discoverable using the existing vanilla recipe-book conventions. NBT-dependent `CustomRecipe` implementations need special attention: a registered recipe is not necessarily a useful visible explanation.

For this release, the in-game help must teach every dynamic key recipe without requiring a recipe-viewer mod. If an existing JEI integration is available, extend it with representative bound-key/mold examples and clear remainder descriptions. A new optional JEI adapter is a worthwhile follow-up only after the required release behavior is complete; it must never be a hard dependency or fabricate usable world bindings in preview stacks.

### Writing and accessibility

- Use the same names in tooltips, commands, screens, help, and documentation.
- Explain a prerequisite and the next action, rather than exposing class names or raw internal identifiers to ordinary players.
- Make all new player text translatable and narratable. Avoid color-only status cues.
- Keep tooltips readable at normal GUI scales; put longer sequences in help pages.
- Narrate a state transition once. Do not repeat the same guidance every tick while the player is looking at a device.
- Static instructions must remain useful when optional companions are absent. Contextual values must reflect the server, not a mismatched client configuration.

## 8. Verification and completion gates

### Targeted automated coverage

Extend the existing suite rather than creating a separate testing framework.

| Workstream | Existing tests/areas to build on | New behavior to prove |
| --- | --- | --- |
| Targeting | `compat/McaBindingProbeTest`, `NoMcaStaticLinkTest`, action/network validation tests | Aimed-target priority, explicit target, ambiguity, range/occlusion, wrong type versus missing binding, console behavior, and permissions. |
| Guillotine | `detention/GuillotineExecutionTest`, `CapitalExecutionAuthorizationTest`, `PilloryOccupancyTest`, `enforcement/CondemnedEscortTest`, capital death/sentence tests | Device structure, occupant/order identity, cancellation, timing boundaries, restart, and at-most-once outcomes. |
| Keys | `recipe/KeyRingRecipeTest`, `recipe/KeyMoldTest`, `locks/LockAccessTest`, `FirstKeyInitializationTest`, lock resource coverage | Blank-mold imprinting, exact remainders, revision preservation, limits, invalid input, and real furnace/crafting behavior. |
| Masks | `MaskCatalogTest`, `MaskCustomizationTest`, `MaskRecipeDataTest`, `MaskResourceCoverageTest`, station and Heat-ledger tests | Full dye matrix, creative variants, metadata-preserving wash/restyle, no-op handling, preview agreement, and unchanged legal state. |
| Presentation | Resource/translation and screen tests where relevant | Missing translation keys, formatting arguments, bounded help, and client/server class separation. |

Test observable behavior. String searches that merely prove a method name exists are not substitutes for executing the transaction or validating the rendered output. Retain existing artifact checks; update genuinely changed resource assumptions without removing coverage just to pass.

### Required runtime scenarios

Use a clean client and dedicated server, then an upgrade copy of an existing world. Record exact mod versions, steps, expected result, actual result, and evidence.

1. **Targeting:** repeat the player's reported failure as closely as the available evidence allows; if not reproducible, record that honestly and demonstrate the newly covered failure distinctions.
2. **Guillotine:** assemble from survival items, load/unload a subject, test unsentenced versus condemned occupants, manual and guard operation, disabled settings, and cancellation during the delay.
3. **Keys:** complete the entire ring/mold workflow, including invalid attempts and full-inventory/shift-click cases; test two different locks and then rekey one.
4. **Masks:** complete the visual matrix and exercise station behavior with a second client observing worn colors.
5. **Upgrade:** reopen pre-update keys, rings, molds, masks, and an occupied device; confirm identities and legal state survive. Do not resume an old transient execution order.
6. **Compatibility:** run the configured MCA binding fleet. Launch with the declared development MCA version; test older MCA versions in the appropriate production environment where development mappings prevent a valid launch.
7. **Optional integrations:** verify baseline operation with optional companions absent. Where installed, exercise Locks Reforged coexistence, and the relevant Townstead/MCA Capitals site or guard path. Smoke-test existing Epic Fight interaction compatibility if targeting/input changes touch it.

The checked-in instructions specify `.mcmod-tools/gradlew-quiet.ps1 -Project . -Task <task>` as the preferred build entry point. Use it when available. If the helper is absent from the implementation environment, record that fact and use the repository Gradle wrapper directly; do not claim an unavailable helper ran.

Run focused tests during implementation, then `check` and `build`, including the existing sibling API, API-jar, and jar-content gates. Run optional real-jar probes when their jars are available. A skipped probe is not a pass. Do not claim all supported MCA versions were launched merely because reflective binding probes passed.

## 9. Implementation order and handoff deliverables

### Suggested implementation sequence

1. Record the current baseline and reproduce/characterize targeting failures.
2. Implement selection results, explicit operator targets, and accurate errors.
3. Add the shared help topics and item/device guidance alongside each finished workflow.
4. Fix verified guillotine interaction, authorization, or timing faults; reconcile stale documentation.
5. Add blank-mold imprinting and complete key workflow feedback and conservation checks.
6. Expose the full mask color catalog, complete dye/wash behavior, and correct visual issues style by style.
7. Perform dedicated-server, upgrade, and rendering verification; resolve concrete remaining failures.
8. Set `mod_version=0.8.0`, update documentation, and prepare the implementation summary.

Keep changes organized by behavior so reviewers can assess targeting, devices, key recipes, and mask rendering independently. Avoid unrelated formatting changes or a broad rewrite of the crime engine.

### Documentation to update

- `README.md`: short command-targeting, guillotine, key-copying, and mask-color instructions.
- `CHANGELOG.md`: player-facing 0.8.0 changes, separating fixes from new convenience features.
- `CONFIG.md`: only real setting changes and corrected existing descriptions; distinguish manual blade delay from guard ceremony behavior accurately.
- `DATAPACK.md`: any new recipe serializer or changed mask recipe contract.
- `MODMAP.md`: registrations, new files, and changed assumptions, following its generated/manual section rules.
- `docs/MIGRATION.md`: only migrations or compatibility considerations that actually apply.
- A release verification record, preferably `docs/0.8.0/VALIDATION.md`, with results and remaining limitations.
- `CURSEFORGE.md` or other existing player documentation if the affected instructions are duplicated there.

Do not invent new version constants. `gradle.properties` remains the source of mod version metadata. If networking changes, update the protocol deliberately and explain mixed-version behavior. Do not bump saved-world schemas without a corresponding data-format change.

### Final response expected from the implementing agent

Provide:

1. The starting and ending commit/branch information and a concise summary of changes.
2. The diagnosed command failure(s), reproduction evidence, and any report that remained unconfirmed.
3. The exact final in-game steps for guillotine, key ring, key mold, and dyed masks.
4. Confirmation of the 16-style × 16-color matrix, plus undyed preservation, with rendered evidence.
5. Tests and runtime scenarios actually run, with pass/fail/skip status and exact environment versions.
6. Save, recipe, config, network, or compatibility changes that affect users.
7. Remaining blockers, if any. Do not describe 0.8.0 as release-ready while a required workflow remains broken or unverified.

Publishing a release or uploading a jar is outside this implementation handoff unless separately requested.

## 10. Source map for the reviewed baseline

Paths below are relative to the repository. For Java paths, the common prefix is `src/main/java/dev/otectus/mcacrime/`. References are pinned to the reviewed commit so later branch changes do not silently change the evidence.

| Source | Why it matters |
| --- | --- |
| [Repository instructions](https://github.com/otectus/MCACrime/blob/f7564ed888fd081a893fbc779166f09c40303f2b/CLAUDE.md) and [version/dependencies](https://github.com/otectus/MCACrime/blob/f7564ed888fd081a893fbc779166f09c40303f2b/gradle.properties) | Build conventions, dependency isolation, and 0.7.8 baseline. |
| [CrimeCommand](https://github.com/otectus/MCACrime/blob/f7564ed888fd081a893fbc779166f09c40303f2b/src/main/java/dev/otectus/mcacrime/command/CrimeCommand.java) | Nearby searches, command grammar, permissions, diagnostics, and capital execution path. |
| [CrimeActionService](https://github.com/otectus/MCACrime/blob/f7564ed888fd081a893fbc779166f09c40303f2b/src/main/java/dev/otectus/mcacrime/action/CrimeActionService.java) | Gameplay command adapters, action checks, and entity ray. |
| [McaBinding](https://github.com/otectus/MCACrime/blob/f7564ed888fd081a893fbc779166f09c40303f2b/src/main/java/dev/otectus/mcacrime/compat/mca/McaBinding.java) and [McaHandles](https://github.com/otectus/MCACrime/blob/f7564ed888fd081a893fbc779166f09c40303f2b/src/main/java/dev/otectus/mcacrime/compat/mca/McaHandles.java) | Package-root probes, capabilities, and villager recognition. |
| [PilloryBlock](https://github.com/otectus/MCACrime/blob/f7564ed888fd081a893fbc779166f09c40303f2b/src/main/java/dev/otectus/mcacrime/block/PilloryBlock.java) and [GuillotineBlock](https://github.com/otectus/MCACrime/blob/f7564ed888fd081a893fbc779166f09c40303f2b/src/main/java/dev/otectus/mcacrime/block/GuillotineBlock.java) | Assembly, capture position, board interaction, blade controls, and destruction. |
| [GuillotineBlockEntity](https://github.com/otectus/MCACrime/blob/f7564ed888fd081a893fbc779166f09c40303f2b/src/main/java/dev/otectus/mcacrime/block/entity/GuillotineBlockEntity.java), [ExecutionAuthorization](https://github.com/otectus/MCACrime/blob/f7564ed888fd081a893fbc779166f09c40303f2b/src/main/java/dev/otectus/mcacrime/detention/ExecutionAuthorization.java), and [CondemnedEscortService](https://github.com/otectus/MCACrime/blob/f7564ed888fd081a893fbc779166f09c40303f2b/src/main/java/dev/otectus/mcacrime/enforcement/CondemnedEscortService.java) | Execution state, timing, damage, and unload/cancellation behavior. |
| [KeyCraftLogic](https://github.com/otectus/MCACrime/blob/f7564ed888fd081a893fbc779166f09c40303f2b/src/main/java/dev/otectus/mcacrime/recipe/lock/KeyCraftLogic.java), [KeyMoldBakeRecipe](https://github.com/otectus/MCACrime/blob/f7564ed888fd081a893fbc779166f09c40303f2b/src/main/java/dev/otectus/mcacrime/recipe/lock/KeyMoldBakeRecipe.java), and [LockInteractions](https://github.com/otectus/MCACrime/blob/f7564ed888fd081a893fbc779166f09c40303f2b/src/main/java/dev/otectus/mcacrime/locks/LockInteractions.java) | Ring recipes, copying and remainders, NBT-preserving furnace behavior, binding and native-lock interaction. |
| [MaskVariant](https://github.com/otectus/MCACrime/blob/f7564ed888fd081a893fbc779166f09c40303f2b/src/main/java/dev/otectus/mcacrime/item/MaskVariant.java), [MaskItem](https://github.com/otectus/MCACrime/blob/f7564ed888fd081a893fbc779166f09c40303f2b/src/main/java/dev/otectus/mcacrime/item/MaskItem.java), and [CrimeItems](https://github.com/otectus/MCACrime/blob/f7564ed888fd081a893fbc779166f09c40303f2b/src/main/java/dev/otectus/mcacrime/item/CrimeItems.java) | All 16 styles, existing dyeability, legacy IDs, item registrations, and creative catalog. |
| [MaskCustomization](https://github.com/otectus/MCACrime/blob/f7564ed888fd081a893fbc779166f09c40303f2b/src/main/java/dev/otectus/mcacrime/mask/MaskCustomization.java), [MaskNbtTransfer](https://github.com/otectus/MCACrime/blob/f7564ed888fd081a893fbc779166f09c40303f2b/src/main/java/dev/otectus/mcacrime/mask/MaskNbtTransfer.java), and [MaskStationMenu](https://github.com/otectus/MCACrime/blob/f7564ed888fd081a893fbc779166f09c40303f2b/src/main/java/dev/otectus/mcacrime/menu/MaskStationMenu.java) | Dye calculations, no-op rules, metadata protection, and station transactions. |
| [MaskArmorRenderer](https://github.com/otectus/MCACrime/blob/f7564ed888fd081a893fbc779166f09c40303f2b/src/main/java/dev/otectus/mcacrime/client/render/mask/MaskArmorRenderer.java) and [CrimeClientSetup](https://github.com/otectus/MCACrime/blob/f7564ed888fd081a893fbc779166f09c40303f2b/src/main/java/dev/otectus/mcacrime/client/CrimeClientSetup.java) | Worn and inventory tinting and duplicate-pass suppression. |
| [English translations](https://github.com/otectus/MCACrime/blob/f7564ed888fd081a893fbc779166f09c40303f2b/src/main/resources/assets/mcacrime/lang/en_us.json) and [configuration](https://github.com/otectus/MCACrime/blob/f7564ed888fd081a893fbc779166f09c40303f2b/src/main/java/dev/otectus/mcacrime/McaCrimeConfig.java) | Current player names, failure messages, and effective-setting definitions. |

**Definition of done:** the three reported workflows are understandable and reliable in-game, every standard color of every mask is accessible and visually verified, old items and worlds retain their identities, and the release record clearly distinguishes tested behavior from any remaining uncertainty.

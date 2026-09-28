# MCA: Crime — datapack reference

Three kinds of content are data rather than code:

```
data/<namespace>/mcacrime/crimes/*.json                  what a crime costs
data/<namespace>/mcareputation/incidents/*.json          what the village hears about it
data/<namespace>/mcareputation/incident_profiles/*.json  what the deed makes you known for
data/<namespace>/mcareputation/credit_policies/*.json    how repeated good deeds are discounted
```

The first is read by this mod. The others are read by MCA: Reputation, out of this mod's jar.

Both reload with `/reload`; crimes also reload with `/crime reload`, which reports how many loaded
and how many failed. `/crime validate` lists every content problem it knows about with the exact file
and field.

---

## Crime definitions

`data/<namespace>/mcacrime/crimes/<name>.json`. One file, one crime type.

### Schema

| Field | Type | Required | Default | Meaning |
|---|---|---|---|---|
| `id` | resource location | **yes** | — | The crime's identity. Must match what the detector resolves, so overriding a shipped crime means reusing its id. |
| `karmaDelta` | long | **yes** | — | Signed. **A penalty is negative.** |
| `heatDelta` | long | **yes** | — | Law-enforcement pressure added when the crime generates Heat. Non-negative. |
| `witnessedMultiplier` | double | no | `1.0` | Scales both karma and Heat when the crime *was* witnessed. |
| `victimTag` | string | no | `""` | Classification hint — `"villager"`, `"guard"`, or empty. Informational: the detector resolves the actual victim itself and does not match on this. |
| `awareness` | object | no | Per-crime defaults | 0.6.0 perception and memory metadata, shown below. |

```json
"awareness": {
  "visualRadius": 20.0,
  "soundRadius": 22.0,
  "severity": 0.7,
  "violent": true,
  "memoryDays": 24
}
```

Radii accept `0–64` blocks, severity `0–1`, and duration `1–365` Minecraft days.
Within an explicitly supplied object the defaults are visual 12, sound 0, severity 0.25,
violent false, duration 3. An omitted object inherits offense-specific defaults (quiet theft,
louder violence, and longer kidnapping/murder memory). The existing player mug action is still a
theft case and uses a robbery awareness profile of visual/sound 16, severity 0.6 and duration 16 days.
Auditory awareness never assigns a suspect. A wall halves the effective sound radius.

New dialogue pools under `data/<namespace>/mcacrime/dialogue/` are `threat_plead`, `threat_stall`,
`threat_defy`, `threat_panic`, `memory_fear`, `memory_anger`, `memory_family`, and `memory_restitution`.
They use the existing `event`/`fallback`/`variants` format. Additional context facts are
`crime_victim`, `crime_family`, `crime_witness`, `crime_restitution` and low/medium/high bands
`crime_fear` and `crime_anger`.

### A worked example

Making theft hurt and murder catastrophic:

```json
{
  "id": "mcacrime:theft",
  "karmaDelta": -20,
  "heatDelta": 30,
  "witnessedMultiplier": 1.5,
  "victimTag": "villager"
}
```

Placed at `data/mypack/mcacrime/crimes/theft.json`, this replaces the shipped definition — the id is
what matters, not the file name or the namespace it sits in.

### How the two deltas interact with witnessing

This is the part that catches people out. There are **two** separate mechanisms:

- `witnessedMultiplier` scales karma and Heat **upward** when the crime *was* seen.
- `unwitnessedKarmaFactor` in the config scales karma **downward** when it *was not*, and
  `requireWitnessForHeat` (default `true`) suppresses Heat entirely in that case.

So with the shipped defaults, an unwitnessed murder costs full karma and no Heat at all. A pack that
wants private crime to weigh less on conscience lowers `unwitnessedKarmaFactor`; a pack that wants
public crime to weigh more raises `witnessedMultiplier`. They are not the same knob.

### The ten shipped definitions

| Crime id | `karmaDelta` | `heatDelta` | `victimTag` |
|---|---:|---:|---|
| `mcacrime:theft` | −8 | 12 | `villager` |
| `mcacrime:harm_villager` | −10 | 15 | `villager` |
| `mcacrime:assault_guard` | −15 | 25 | `guard` |
| `mcacrime:jailbreak` | −20 | 30 | *(none)* |
| `mcacrime:kidnap` | −40 | 35 | `villager` |
| `mcacrime:kill_villager` | −50 | 40 | `villager` |
| `mcacrime:mugging_murder` | −70 | 55 | `villager` |
| `mcacrime:assault_player` | −10 | 15 | `player` |
| `mcacrime:extortion` | −12 | 10 | `villager` |
| `mcacrime:murder_player` | −50 | 40 | `player` |

All ship with `witnessedMultiplier: 1.0`. `jailbreak` has no victim tag because its victim is the
authority, not a person — which is also why it is recorded as witnessed with no named witnesses. `assault_player` and `murder_player` are only recorded when `pvpCountsAsCrime` is enabled.

### The fail-safe mirror

Every shipped crime is also hardcoded. If the JSON is deleted, malformed, or shadowed by a broken
pack, detection falls back to the built-in definition rather than silently switching that crime off —
a pack error should not quietly legalise murder. Set `strictJsonValidation = true` in `[debug]` while
authoring to make malformed content a hard error instead.

### Adding a new crime

You can add a definition with a new id, and the API and ledger will carry it, but **nothing detects
it automatically**. Detection is wired to the seven shipped ids. A new crime type is useful today for
a companion mod or a command-driven pack that records cases itself; it is not a way to make the mod
notice a new kind of wrongdoing on its own.

---

## Incident definitions

`data/<namespace>/mcareputation/incidents/<name>.json`, read by **MCA: Reputation**, not by this mod.
These are what the village actually hears; the crime definition above is what the law charges you.

MCA: Crime ships eight of them, under `data/mcacrime/mcareputation/incidents/`. They live in this
mod's data namespace deliberately — the jar-hygiene check that keeps companion classes out of the jar
is about class packages, and data files under `data/mcacrime/` are unaffected by it while still being
loaded into Reputation's registry.

### Fields

| Field | Meaning |
|---|---|
| `display.translate` | Lang key for the incident's name. |
| `default_delta` | Standing change. Negative is a penalty. |
| `visibility` | `witnessed` — only those who saw it learn it; `village` — everyone knows at once; `private`. |
| `severity` | `minor`, `moderate`, `major`, `severe`. |
| `tags` | Free-form tags other content can query on. |
| `retention_ticks` | How long the record is kept. Omitted means kept indefinitely. |
| `decay` | `{"type": "none"}`, or `linear_to_zero` with `delay_ticks` and `amount_per_day`. |
| `resolution` | Multipliers for the remaining penalty under each status: `apologized`, `atoned`, `forgiven`, `disproven`. Lower means more of the penalty is removed. |
| `gossip` | `tone` (`approval` / `condemnation`), a `phrase` lang key, and the `with` arguments to fill it. |
| `retain_unwitnessed` | Keep the record as hidden history even when nobody saw it. |
| `max_override_abs` | Ceiling on how far another system may override this incident's delta. |
| `social_profile` | The incident profile that says what the deed makes a player *known for*, read by MCA: Reputation 0.6.0 and newer. Absent means no recognition and no facet evidence at all — never a default derived from severity. |

### The eight shipped incidents

| Incident | Delta | Visibility | Severity | Decay |
|---|---:|---|---|---|
| `mcacrime:theft` | −6 | witnessed | moderate | linear, 2/day after 24000t |
| `mcacrime:guard_assaulted` | −12 | witnessed | major | linear, 2/day after 48000t |
| `mcacrime:jailbreak` | −16 | village | major | linear, 1/day after 72000t |
| `mcacrime:kidnapping` | −32 | witnessed | severe | **none**, retained unwitnessed |
| `mcacrime:mugging_murder` | −45 | witnessed | severe | **none**, retained unwitnessed |
| `mcacrime:fine_paid` | +3 | village | minor | linear, 1/day after 24000t |
| `mcacrime:sentence_served` | +3 | village | minor | linear, 1/day after 24000t |
| `mcacrime:captive_rescued` | +5 | witnessed | moderate | none |

Two more crimes map to incidents this mod does **not** ship:

| Crime | Incident |
|---|---|
| `mcacrime:harm_villager` | `mcareputation:villager_assaulted` |
| `mcacrime:kill_villager` | `mcareputation:villager_killed` |

Those deliberately reuse MCA: Reputation's own ids rather than minting parallel ones. Existing
dialogue, gossip phrases, and incident-tag queries across the suite already speak them; a parallel
`mcacrime:villager_killed` would fork every piece of content that mentions a killing.

### Retuning them

Override any of the eight by writing a file with the same id in your own pack. The mapping from crime
to incident is fixed in code — exactly one incident per crime, so a case can never produce two public
deeds — but what that incident is *worth* is entirely yours.

---

## Public profiles

`data/<namespace>/mcareputation/incident_profiles/<name>.json` and
`.../credit_policies/<name>.json`, read by **MCA: Reputation 0.6.0** and newer. Ignored entirely by
an older companion, which is why every incident above keeps its own `default_delta`: the profile says
what a player is *known for*, never what the deed costs them in standing.

MCA: Crime ships seven profiles and two credit policies under `data/mcacrime/mcareputation/`. The
facets they reference (`mcareputation:violence`, `lawfulness`, `compassion`, `bravery`) are MCA:
Reputation's own — minting parallel ones would fork every description and observer weight in the
suite.

| Profile | Attached to | Recognition | Facets | Credit |
|---|---|---:|---|---|
| `mcacrime:theft` | `mcacrime:theft` | 3 | lawfulness −8 (evaluative) | adverse |
| `mcacrime:guard_assaulted` | `mcacrime:guard_assaulted` | 6 | violence +8 (historical), lawfulness −10 (evaluative) | adverse |
| `mcacrime:jailbreak` | `mcacrime:jailbreak` | 8 | lawfulness −12 (evaluative) | adverse |
| `mcacrime:kidnapping` | `mcacrime:kidnapping` | 12 | violence +10, compassion −8, lawfulness −12 | adverse, major evidence |
| `mcacrime:mugging_murder` | `mcacrime:mugging_murder` | 18 | violence +20, compassion −12, lawfulness −14 | adverse, major evidence |
| `mcacrime:captive_rescued` | `mcacrime:captive_rescued` | 8 | bravery +8, compassion +8 (both historical) | commendable, `mcacrime:captive_rescue` |
| `mcacrime:legal_settlement` | `mcacrime:fine_paid`, `mcacrime:sentence_served` | 2 | — | commendable, `mcacrime:legal_settlement` |

Recognition channels last `1344000` ticks (56 in-game days) and facet channels `672000` (28), which
are MCA: Reputation's own shipped lifetimes. `historical` evidence is not undone by an apology — the
violence a killing demonstrated still happened — while `evaluative` evidence settles as the case is
atoned or forgiven, and recognition is never undone at all: being known for something is not reversed
by apologising for it.

### What the profiles deliberately do not say

- **No credit policy on an adverse profile.** A repeat-credit schedule only ever *reduces* a positive
  contribution, and authoring one on a crime is a validation error on MCA: Reputation's side rather
  than a silent no-op. Repetition must never make harm cheaper.
- **No virtue for settling your own case.** `legal_settlement` carries recognition and no facet at
  all: paying your own fine or serving your own sentence is not generosity, compassion or bravery. Its
  credit policy is there so that repeatedly paying fines stops earning recognition (100%, 50%, 25%, 0
  within a 14-day window) rather than farming it, and the same allowance covers both incidents, so
  alternating between them does not reset it.
- **No greed facet for theft, and no bravery for killing.** Both would be inferred rather than
  observed. Theft costs lawfulness because this mod's own legal case establishes the act was unlawful;
  nothing about a stolen item says what the thief wanted it for.
- **No beneficiary ceiling.** MCA: Reputation's shipped rescue and cure policies limit repeat credit
  per beneficiary as well as per player. Ours do not, because this mod's incident subject carries the
  victim's *kind* (`villager`, `guard`, `child`) rather than their part in the deed, and a subject
  limit that failed to match would silently reduce every rescue to zero credit rather than only the
  repeated ones.

`mcacrime:captive_rescued`, `mcacrime:fine_paid` and `mcacrime:sentence_served` ship as complete
content but are not yet produced by any code path in this mod; the crime-to-incident table maps the
five adverse ones. Overriding any profile, or pointing an incident at one of your own, works the same
way as retuning an incident: write a file with the same id. An unresolvable `social_profile` costs the
incident only its profile, not its whole definition — which also means a typo there is silent, so
check it with `/mcareputation debug` rather than assuming.

---

## Item tags

Tags are stored under `data/<namespace>/tags/item/`. This mod ships tags under the `mcacrime` namespace
and publishes items through the common `c:` namespace.

| Tag | Purpose |
|---|---|
| `mcacrime:weapons` | Items that trigger the Crime menu when held and right-clicked on a villager. Defaults to `#minecraft:swords` and `#minecraft:axes` plus the common `c:tools/melee_weapon`, `c:tools/ranged_weapon`, `c:tools/bow`, `c:tools/crossbow` and `c:tools/spear` tags. A datapack can add to it. |
| `mcacrime:weapons_blacklist` | Items that are never weapons, overriding any whitelist or automatic detection. |
| `c:ropes` | Common rope tag consumed by the restraint-rope recipe and rope detection; `data/c/tags/item/ropes.json` adds `mcacrime:restraint_rope` to it. |

---

## Dialogue

Villager dialogue is data-driven:

```
data/<namespace>/mcacrime/dialogue/*.json
```

Reload with `/reload`. The server picks a translation key and the client renders it, so lines localise
and no text crosses the wire; the choice is deterministic per encounter, so reopening a conversation
cannot be used to reroll until a preferred line appears.

Every fired event has a pool of dialogue in the mod or a custom datapack. The 22 events ship with
content; `/crime validate` fails the build if a fired event has no pool or a pool names a key with no
translation.

---

## Townstead tables

Three optional tables under `data/<namespace>/townstead/`. They are read on every reload whether or
not Townstead is installed — they are plain data — but nothing reads their results until the
integration is bound. This mod ships one `default.json` in each directory; replace an entry by
shipping a file at the same path (`data/mcacrime/townstead/<table>/default.json`) from a pack that
loads later. Declaring the same key in a different file is a duplicate, and a duplicate refuses the
whole table.

Each file is one JSON object or a list of them. All three publish **whole or not at all**: if any
entry in a reload fails, the last good table stays in effect, every problem is logged, and
`/crime validate` lists them. With `strictJsonValidation = true` the reload throws on the first
problem instead, which is what you want while authoring.

### `townstead/building_roles/`

What a Townstead building type means to the law. Backs `/crime facility recognise`.

```json
[{ "building": "townstead:jail_l1", "role": "jail_cell", "capacity": 2 }]
```

| Key | Required | Rule |
|---|---|---|
| `building` | yes | A valid resource location for the Townstead building type. An unparsable id is an error. |
| `role` | yes | One of `guard_post`, `jail_cell`, `guardhouse`, `evidence_storage`, `care_room`, `public_notice`. |
| `capacity` | no | How many prisoners one such building holds. Defaults to the role's own; must be `0 … 16`, and a value outside that is an error rather than a clamp. |

A type mapped to `evidence_storage` or `jail_cell` is also what automatic property protection works
from: with `townstead.propertyLaw` and `townstead.autoProtectGeneratedProperty` both on, the facility
anchor is written as protected property and reserved from settlement auto-sourcing; the recognised
building at that anchor is written as a policy too, where building enumeration is available, but a
building-scope policy is evaluated when a container is opened, not on the per-block sourcing scan. No
other role is treated that way, and nothing is protected while either switch is off.

### `townstead/personality_profiles/`

How a Townstead personality nudges a villager's reaction to a crime.

```json
[{ "personality": "townstead:brave", "threat": -0.05, "report": 0.05, "flee": -0.05 }]
```

| Key | Required | Rule |
|---|---|---|
| `personality` | yes | A valid resource location for the Townstead personality id. |
| `threat` / `report` / `flee` | no | Additive nudges, each `-0.10 … 0.10`. A non-numeric value is an error, and so is one outside the cap — refused rather than clamped, so a `0.8` you meant does not silently become `0.1`. |

The cap is deliberate: these are nudges on numbers a server owner has already tuned, not a second
tuning surface. A personality with no entry is neutral.

### `townstead/reaction_bindings/`

Which Townstead reaction plays when something becomes public knowledge.

```json
[{ "event": "custody_started", "reaction": "townstead:concerned", "radius": 24 }]
```

| Key | Required | Rule |
|---|---|---|
| `event` | yes | One of MCA: Crime's ten public events: `crime_witnessed`, `crime_threatened`, `alarm_heard`, `report_delivered`, `custody_started`, `custody_ended`, `jailbreak`, `rescued`, `property_returned`, `restitution_completed`. |
| `reaction` | yes | A valid resource location for the Townstead reaction to play. This mod does not and cannot verify that Townstead defines it. |
| `radius` | no | Blocks, `1 … 48`; defaults to `24`. Outside the range is an error. The ceiling bounds an entity scan attached to every arrest. |

One event has exactly one reaction: binding the same event twice, in one file or across two, is an
error naming both files. An event with no binding simply plays nothing.

Nothing here fires when `townstead.publicReactions` is off, when the integration is off, or when the
installed Townstead does not provide the `dispatch_reaction` capability — in the last case
`/crime validate` reports the switch as degraded.

---

## Prison construction tags

`data/mcacrime/tags/block/reinforced_blocks.json` is the set the breaking policy reads
(`prison.reinforcedBreakingPolicy`, plus the separate explosion and piston settings).
A pack extends it to bring its own prison masonry under the same rules — there is one list, not two.

Adding a block to this tag makes it subject to the breaking policy. It does **not** make it hard to
mine: hardness, blast resistance and the tool requirement are the block's own properties, and a soft
block in this tag is still a soft block.

The eight blocks MCA: Crime registers are additionally in the vanilla tags
`minecraft:mineable/pickaxe` and `minecraft:needs_iron_tool`, and the slab and stair are in
`minecraft:slabs` and `minecraft:stairs`. That combination is what "pickaxe qualified" means in
practice, and it is the honest description of the set: an iron pickaxe opens any of them, slowly.
Nothing here is unbreakable, and no setting in `prison` makes it so.

---

## Restraints, locks and transport (0.7.5)

### Restraint profiles

Which restraint definitions exist — and which slot each occupies, its render pose, its statistics
and its item — stays **code-only**, in `restraint/RestraintDefinitions`. Those are authorisation
decisions: a pack that could declare what "arms restrained" means would be rewriting the rules the
server enforces, not the content it serves. What a pack *may* move are the numbers around a
definition that already exists, through a **restraint profile**:

```
data/<namespace>/mcacrime/restraint_profiles/<definition path>.json
```

The file name is the definition id. `data/mcacrime/mcacrime/restraint_profiles/handcuffs_arms.json`
overrides `mcacrime:handcuffs_arms`; a third-party definition is overridden under its own namespace.
A file naming an id that does not exist is logged and ignored — a profile can never create a
definition. **No profile files ship with the mod**, so the shipped behaviour is the code table.

A file is accepted or refused **whole**. Any invalid field refuses the entire file and the code
values stand; half a profile — durability from the pack, restrictions from the code — is a state
neither the author nor the player can reason about. An unknown field is an error, not a warning.

Every field is optional and an absent field means "keep what the code says". A file that sets
nothing is legal and changes nothing.

| Field | Type | Rules |
|---|---|---|
| `durability` | integer | 1 … 4096. The struggle work the worn instance withstands. |
| `restrictions` | object | Named components mapped to booleans; see below. |
| `pick` | object | The whole pick profile, replaced rather than merged; see below. |
| `key_family` | string | One of `handcuffs`, `shackles`, `tape`, `hood`, `legacy_rope`, or `none` for keyless. Setting it also sets the key item that family opens with. |
| `supported_rigs` | array | Non-empty array of rig ids, or `["*"]` for any body. An empty array is an error: a definition nothing can wear is not expressible. |

**`restrictions`** takes the sixteen `RestrictionPolicy` components, spelled in snake case. Fourteen
are *permissions*, where `true` still means "may": `mine_blocks`, `use_item`, `attack`,
`interact_entity`, `interact_block`, `drop_item`, `mutate_inventory`, `swap_offhand`,
`change_hotbar`, `voluntary_movement`, `jump`, `sprint`, `steer_vehicle`, `dismount`. Two are
*imposed effects*, where `true` means "on": `obscure_vision` and `voice_gag`. A pack that loosens
`sprint` and imposes `obscure_vision` therefore writes `true` in both places and means two different
things. A name that is not a component is an error.

**`pick`** takes `pickable` (boolean, default `true`), `progress_increase` and `speed_increase`.
Both numbers are **required** when `pickable` is true and **forbidden** when it is false.

```json
{
  "durability": 240,
  "restrictions": { "sprint": false, "obscure_vision": true },
  "pick": { "pickable": true, "progress_increase": 6, "speed_increase": 30 },
  "key_family": "shackles",
  "supported_rigs": ["*"]
}
```

Profiles are loaded by a `SimpleJsonResourceReloadListener` added on NeoForge's
`AddReloadListenerEvent`, so they reload with `/reload`, and they are synced to every client on login
and on each reload through the `mcacrime:restraint_profiles` payload — always, including when nothing
is overridden, because a client that joins a second server has to be told the first server's numbers
no longer apply. Removing the pack and reloading restores the code table exactly; the code table
(`RestraintDefinitions.base`) is never mutated.

Operator-side tuning of the same numbers stays under `[restraints.definitions]` in
`mcacrime-common.toml`. Third-party **code** can add a whole definition through
`api/RestraintRegistrationApi` during mod setup; see [API.md](API.md).

### The tags a pack may write

1.21 directory names: block tags live under `tags/block`, item tags under `tags/item`, entity type
tags under `tags/entity_type` and enchantment tags under `tags/enchantment`, all singular.

| Tag | Kind | What it decides |
|---|---|---|
| `mcacrime:restrainable_entities` | entity type | Who can be restrained *beyond* players and MCA villagers, which are always restrainable. Ships **empty**. The tag only ever widens the set — emptying it cannot make a player unrestrainable, because a restraint already worn would then have no route off. |
| `mcacrime:chainable_entities` | entity type | What a chain may be tied to. Players and MCA villagers are chainable in code and are deliberately **not** listed, so removing them from the tag cannot strand a chained prisoner. |
| `mcacrime:cuff_keys` | item | Which items count as a key for a worn restraint: `handcuffs_key` and `shackles_key`. Whether a given key opens a given restraint is still the definition's key family — this tag is the coarse "is this a cuff key at all" test. |
| `mcacrime:keys` | item | Which items are keys for a **lock**. |
| `mcacrime:lockpicks` | item | What may be used to pick a lock. Ships with `mcacrime:lockpick`. |
| `mcacrime:lockable_blocks` | block | What a padlock may be placed on. Adding a block here makes it lockable; it does not make it unbreakable, which `[locks]` decides. |
| `mcacrime:can_reinforce_padlock` | item | What reinforces a padlock. Reinforcement only makes a lock harder to pick; it is never impossible. |
| `mcacrime:reinforced_blocks` | block | The prison masonry set — see *Prison construction tags* below. |
| `mcacrime:enchantable/restraint` | item | Which items the five restraint enchantments may sit on: the restraint set. |

The `mcacrime:restraints` and `mcacrime:illicit_goods` item tags keep all three original restraint
ids, including the hidden `mcacrime:restraint_rope` carrier.

### Enchantments are datapack entries on this line

On 1.21 an `Enchantment` is a registry **data** entry, so the five restraint enchantments ship as
`data/mcacrime/enchantment/{imbue,famine,shroud,exhaust,silence}.json` rather than as
classes, with `ResourceKey` constants in code. A pack may retune their definitions the way it retunes
any vanilla enchantment.

Their availability is vanilla tag membership, and this release writes into two of them:

- `data/minecraft/tags/enchantment/in_enchanting_table.json` — all five, `replace: false`.
- `data/minecraft/tags/enchantment/on_random_loot.json` — all five, `replace: false`.

None of the five joins `#minecraft:treasure` or `#minecraft:double_trade_price`, which is how
non-treasure status is expressed here, and none joins `#minecraft:tradeable`: vanilla folds
`#minecraft:non_treasure` into the trade, mob-spawn-equipment and traded-equipment tags, so joining
it would put prison equipment on a librarian's trade table.

**One divergence from the Forge 1.20.1 line.** A tag cannot read `enchantments.allowed`, so an
enchanting table or a loot roll may still *offer* one of the five that the config has switched off.
The effect stays gated: every service asks `enchantment/EnchantmentApplicability` first, so a
disallowed enchantment does nothing.

### The vanilla tags this release writes into

- `minecraft:bypasses_armor` gains `mcacrime:hang`, and nothing else — the reasoning is under
  *Damage types*.
- `minecraft:needs_iron_tool` gains ten blocks: the eight reinforced blocks (`reinforced_stone`,
  `reinforced_smooth_stone`, `chiseled_reinforced_stone`, `reinforced_lamp`, `reinforced_stone_slab`,
  `reinforced_stone_stairs`, `reinforced_bars`, `reinforced_bars_gap`) plus `cell_door` and `safe`.
- `minecraft:mineable/pickaxe` gains eleven: the same eight, plus `cell_door`, `safe` and `bunk`.
  Together with the tag above, that combination is what "pickaxe qualified" means in practice.
- `minecraft:mineable/axe` gains `mcacrime:mask_station`, `mcacrime:pillory` and
  `mcacrime:guillotine`.
- `minecraft:slabs` and `minecraft:stairs` gain the reinforced slab and stair.

### The bundle recipe is a deliberate collision

`data/mcacrime/recipe/bundle.json` adds the vanilla bundle recipe — string over leather, producing
`minecraft:bundle`. The bundle hood restraint is made from a bundle, and the vanilla bundle recipe is
not enabled in ordinary 1.21.1 survival, so without this file the restraint would be uncraftable.

This is stated here because it is the one recipe in this mod that is **not** about an `mcacrime:`
item, and because it will collide: any other mod or pack that adds a bundle recipe, and any future
version where vanilla enables its own, produces a duplicate result rather than an error. A pack that
does not want MCA: Crime's copy overrides `mcacrime:bundle` with a recipe file of its own; nothing in
the mod depends on that recipe existing, only on bundles being obtainable somehow.

Note the 1.21 directory name: recipes are under `data/<namespace>/recipe/`, singular, and loot tables
under `loot_table/`.

### The rope conversion recipe

`rope_to_duck_tape.json` is a shapeless one-for-one conversion of the retired
`mcacrime:restraint_rope` into `mcacrime:duck_tape`. It exists so rope sitting in a chest from an
older world is not stranded when the id leaves the creative tab; see
[docs/MIGRATION.md](docs/MIGRATION.md).

### Recipe results are bounded by stack size

1.21.1 refuses a recipe whose result count exceeds the result item's maximum stack size outright —
the recipe never loads. `RecipeResultStackSizeTest` walks every file under `data/mcacrime/recipe` and
fails the build if any result would exceed its item's ceiling.

---

## Damage types

`data/mcacrime/damage_type/hang.json` declares `mcacrime:hang`, the damage a tether deals to a subject
dragged past `transport.overextensionLength` and the damage a guillotine deals when it carries out an
authorised execution. It is a datapack file rather than a hard-coded source so a pack can reweigh it:
`scaling` and `exhaustion` are yours to change, and `message_id` is what selects the
`death.attack.hang` / `death.attack.hang.player` lines in the language file.

**Its tags are chosen, not copied.** `mcacrime:hang` is in `minecraft:bypasses_armor` and in nothing
else. In particular it is deliberately **not** in `is_explosion` and **not** in `is_drowning`, where
the upstream mod this content is adapted from puts its equivalent — a chain is neither an explosion
nor water, and being in those tags would make Blast Protection reduce it, make a Respiration helmet
irrelevant to it, and make every "is this an explosion?" listener in every other mod answer yes for a
prisoner on a short lead. `bypasses_armor` is there because a chain round the neck is not something a
breastplate helps with.

`data/mcacrime/damage_type/imbue.json` declares `mcacrime:imbue`, the damage an Imbue-enchanted
restraint moves from a captor onto the prisoners they hold. It is a **separate** type from
`mcacrime:hang` — upstream used one source for both, which made a chain and a curse indistinguishable
to every listener — and it is in **no tag at all**: it is not armour-bypassing, because the share is
already bounded by `enchantments.imbueMaxTransferFraction`.

If you add `mcacrime:hang` to another damage-type tag, note that it is the same type a guillotine uses:
a tag that makes hanging survivable makes an execution survivable too, which the device will then
record as a cancelled death and resolve nothing from.

---

## Prison construction tags

`data/mcacrime/tags/block/reinforced_blocks.json` is the set the breaking policy reads
(`prison.reinforcedBreakingPolicy`, plus the separate explosion and piston settings).
A pack extends it to bring its own prison masonry under the same rules — there is one list, not two.

Adding a block to this tag makes it subject to the breaking policy. It does **not** make it hard to
mine: hardness, blast resistance and the tool requirement are the block's own properties, and a soft
block in this tag is still a soft block.

Nothing here is unbreakable, and no setting in `prison` makes it so.

---

## Validation checklist

Before shipping a pack:

- [ ] `/crime reload` reports the expected number loaded and **zero** errors.
- [ ] `/crime validate` reports no problems. It surfaces crime-JSON parse errors from the last load
      as well as config problems, so run it after the reload rather than before.
- [ ] Every `karmaDelta` you meant as a penalty is **negative**. A positive one is a reward for
      committing a crime, and nothing will warn you.
- [ ] Band thresholds still make sense against your karma values. If the heaviest crime is −70 and
      the Outlaw threshold is −1000, nobody will ever be an outlaw.
- [ ] `strictJsonValidation = true` during authoring, so a typo is an error rather than a silent
      fallback to the built-in definition.
- [ ] If you overrode an incident, check it in-game with MCA: Reputation installed — this mod cannot
      validate content that another mod's registry owns.
- [ ] If you overrode a profile or a credit policy, remember MCA: Reputation reads those four
      directories with a strict parser: a repeated JSON key at any depth, a facet nothing defines, or
      two policy files disagreeing about one credit group drops the **whole** profile bundle for that
      reload, while the scalar incident definitions keep loading. `/mcareputation reload` reports
      which file failed.
- [ ] If you wrote a `townstead/` table, run `/crime validate` **and** `/crime debug townstead`: the
      first reports a refused table, the second reports whether the capability the table feeds is
      actually available.

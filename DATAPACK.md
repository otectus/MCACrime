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

### The seven shipped definitions

| Crime id | `karmaDelta` | `heatDelta` | `victimTag` |
|---|---:|---:|---|
| `mcacrime:theft` | −8 | 12 | `villager` |
| `mcacrime:harm_villager` | −10 | 15 | `villager` |
| `mcacrime:assault_guard` | −15 | 25 | `guard` |
| `mcacrime:jailbreak` | −20 | 30 | *(none)* |
| `mcacrime:kidnap` | −40 | 35 | `villager` |
| `mcacrime:kill_villager` | −50 | 40 | `villager` |
| `mcacrime:mugging_murder` | −70 | 55 | `villager` |

All ship with `witnessedMultiplier: 1.0`. `jailbreak` has no victim tag because its victim is the
authority, not a person — which is also why it is recorded as witnessed with no named witnesses.

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

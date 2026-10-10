# MCA Capitals support (0.7.8, NeoForge 1.21.1)

MCA Capitals turns an MCA village into the seat of a kingdom: a capital with a throne, a court and titles.
MCA: Crime reads it, optionally and only by reflection, for one feature: **royal decrees**, through which
the player who rules a capital declares another player wanted in that village. Nothing else about Crime
changes when Capitals is installed, and nothing about Capitals' own justice is touched.

## Support matrix

| Capitals | Loader | Paired MCA | Binding | Runtime |
|---|---|---|---|---|
| 1.3.5 | NeoForge 1.21.1 | 7.7.36-beta.3 | every member (`capitalsProbeTest`), and a real `CapitalRecord` read back through the handles | not run on this port; the Forge build's 31 production-server checks cover the shared logic |
| absent | NeoForge 1.21.1 | 7.7.36-beta.3 | `ABSENT`, silent | unit suite and GameTests run without Capitals |

NeoForge Capitals 1.3.5 requires MCA `[7.7.35-beta.3,7.8.0)`, so its probe is paired with
MCA 7.7.36-beta.3 from the probe fleet. The decree code, the bridge and the binding are the Forge build's,
loader adaptations aside (payloads, events, config spec); results and commands are in
[VALIDATION.md](VALIDATION.md).

## How it is reached

- `compat/CapitalsBridge` is the always-loadable facade. It names the adapter only as a string, so a
  server without Capitals never loads a class that mentions one; `NoCapitalsStaticLinkTest` and
  `OptionalClassloadTest` keep it that way.
- `compat/capitals/CapitalsBinding` holds the manifest: 22 members, each bound by owner, name, arity
  and staticness to an erased handle. A bridge or synthetic method is never bound, and a name with two
  matching overloads is a miss, not a guess.
- It binds at server start, is released at stop, and binds again when the COMMON config is reloaded,
  since `capitals.enabled` may have changed. A missing member turns off its capability and nothing
  else, except that losing the registry turns off everything.
- `neoforge.mods.toml` declares `mcacapitals` with `type="optional"`, `[0,)`, ordered `AFTER`. Capitals declares nothing
  about Crime, so there is no cycle.

| Capability | Members | Used for | Without it |
|---|---|---|---|
| `registry` | `CapitalManager.getCapitalByVillageId(ServerLevel, Integer)`, `getAllCapitalRecords()`; `CapitalRecord.getCapitalId/getVillageId/getVillageDimensionId/getState` | which village is a capital, in which dimension, in which state | every capability is off |
| `authority` | `CapitalRecord.isPlayerSovereign/getPlayerSovereignId`; `CapitalPlayerAuthorityResolver.resolve` and `ResolvedAuthority.rank` | who sits on the throne; a target's rank | no decrees |
| `titles` | `PlayerCapitalTitleService.getGrantedTitle/getMarriageTitle/getDowagerBaseTitle` | knights, dames and titles the rank does not show | no decrees |
| `court` | `CapitalRecord.getHand/getCommander/getMasterOfLaws/getHerald/isRoyalGuard` | which villagers may receive a decree besides guards | only guards host the decree screen |
| `chronicle` | `CapitalChronicleService.addEntry/addEntryWithoutHerald`, `CapitalDataAccess.markDirty` | the optional chronicle line | no chronicle line |
| `player_warrants` | `CapitalPlayerWarrantDataAccess.hasWarrant` | `/crime status` mentions Capitals' own warrant | no such line |

Decrees need `registry`, `authority` and `titles`. The two-argument `getCapitalByVillageId` is used
on purpose, because the one-argument overload beside it ignores the dimension.

**What Crime writes to Capitals.** Only the optional chronicle line, followed by `markDirty` as
Capitals' own record changes are. Crime never issues, clears or reads Capitals' arrest warrants,
judgments, leave orders or prisoners, never grants a title and never moves a throne. Capitals has no
events, so every fact is read when it is needed.

## Royal decrees

### Who may issue one

The player recorded on the capital's **player throne** (`isPlayerSovereign` and
`getPlayerSovereignId`), standing in that capital's village, when the capital is `ACTIVE`. Nobody else:
not a villager sovereign, the consort, the Hand or any other court office, a titled noble, or the ruler
of another capital. A player who rules two capitals rules each one separately. Everything is read again
for every action, so a ruler who abdicates loses the power at once.

### Who may be named

A player below Noble status **in that capital**, in Capitals' own terms, because MCA's native rank is
overridden inside an active capital and never says "Noble" there. A target is protected when either:

- Capitals' resolved rank for them in that capital is anything but `STRANGER` or `COMMONER`: lord,
  duke, prince, prince consort, sovereign, sovereign consort, Lord Commander, Hand or High Sovereign.
  A rank name Crime does not recognise counts as protected.
- Any title they hold in that capital, granted, by marriage or as a dowager, is not `COMMONER`. This is
  what protects knights and dames, who have no rank of their own.

A title held in another capital counts for nothing here: a duke of one capital is a stranger in the
next. The ruler cannot name themselves. A Noble is still subject to ordinary crime law; a decree is the
only instrument the rule applies to.

### Issuing

- **In play.** The ruler opens the Crime menu on one of the village's guards, or on a court officer or
  royal guard of the capital, and chooses **Royal decree**. The screen lists the online players standing
  in the village (up to 32), says why any of them cannot be named, and offers a reason: trespass,
  disorder, defiance of the Crown, treason, or the Crown's own reasons. Issuing asks for confirmation.
  The screen is a server-issued session that lapses after a minute, and the server checks every answer
  against it as well as against Capitals.
- **By command.** `/crime decree issue <player> [reason]`, `/crime decree withdraw <player>`,
  `/crime decree list`, `/crime decree pay`. None needs an operator level, and each applies exactly the
  checks the screen does.

Every refusal says why, with one message per reason (`mcacrime.decree.refused.*`), for example:
switched off, cannot be confirmed, not a capital, no reigning court, not the ruler, not in the village,
self, Noble, already standing, too soon, capital full.

### What a decree does

Inside that village, and only there, the village's guards treat the player as they would a local case.
They challenge them, explain the decree, and offer the fine (`fineAmount`, 32 in the active currency,
when fines are on) or surrender. An arrest on a decree alone carries a term of at least `sentenceTicks`
(6000 online ticks) and takes the ordinary path: escort, custody and the jail ladder. Paying, or serving
that term, ends the decree. When the player also has a local case the same guard can act on, the case is
settled first and the decree is answered at the next challenge.

The target is told when it is issued and again when they next enter the village. `/crime status` and
`/crime decree list` show it.

It changes nothing else: Heat, Karma, band, the Wanted flag and the HUD stay as they are, no bounty
warrant or contract is opened, no case is filed, no MCA: Reputation incident is sent and no hearts move.
Guards never use force on a decree alone: force comes only from the existing resisting-arrest rules,
after a refusal. Attacking a decreed player is still a crime.

### Lifecycle

| Event | What happens |
|---|---|
| Time | Lapses after `durationTicks` of overworld game time (72000, three in-game days), offline time included. A decree being served in custody does not lapse under the prisoner. |
| Repeat | One decree per player per village; issuing another answers that one already stands. After one ends, another waits `cooldownTicks` (24000). |
| Capacity | At most `maxOpenPerCapital` (8) standing decrees per capital, and 1024 in the world. |
| Withdrawal | Only by the current ruler of that capital, whoever issued it. |
| Succession | The decrees belong to the capital and stand. The new ruler may withdraw them, and the former one cannot. |
| Ennoblement | Stops the decree being enforced from the next read, and voids it at the next maintenance sweep. |
| Capital gone | A capital that has left its village (with others still registered) stops its decrees being enforced, and the next sweep voids them. |
| No reigning court, or Capitals missing, unbound or switched off | Kept but not enforced, and none can be issued; time still runs. |
| Sentence | Serving it ends the decree. An operator's release (`/crime release`) hands it back unanswered. |
| Restart | Persisted in `mcacrime.dat` (schema 17). |
| Operator | `/crime decree void <player>` (permission 3) clears every decree against a player without asking Capitals. |

The standing of a decree is read from Capitals when a guard needs it and cached for at most five
seconds; paying and the maintenance sweep always read it fresh.

### Capitals' own warrants

Capitals issues its own warrant when a player ignores an order to leave a capital, and its own guards
and dialogue handle it. MCA: Crime reads that warrant only to say so in `/crime status`
(`readPlayerWarrants`); its guards never act on it, and a Capitals warrant never becomes a decree.

### The chronicle

With `chronicle = true`, issuing and withdrawing a decree each add one line to the capital's chronicle
(and, with `chronicleHerald`, let the herald announce it). The line names the ruler, the target, the
village and the reason category, in the server's language, because that is what Capitals' chronicle
stores. It is off by default.

## Configuration

`[capitals]` and `[capitals.decrees]` in the COMMON config; every key is a no-op without Capitals.
Defaults, ranges and the disabled behaviour of each are in [CONFIG.md](../../CONFIG.md).

| Key | Default |
|---|---|
| `capitals.enabled` | `true` |
| `capitals.readPlayerWarrants` | `true` |
| `capitals.decrees.enabled` | `true` |
| `capitals.decrees.durationTicks` | 72000 |
| `capitals.decrees.cooldownTicks` | 24000 |
| `capitals.decrees.maxOpenPerCapital` | 8 |
| `capitals.decrees.sentenceTicks` | 6000 |
| `capitals.decrees.fineAmount` | 32 |
| `capitals.decrees.chronicle` | `false` |
| `capitals.decrees.chronicleHerald` | `false` |

## Diagnostics

- `/crime debug capitals` (level 2): the binding state and detected version, each capability, whether
  decrees are ready, any unbound member, the capital and ruler of the village you stand in, and how many
  decrees the world holds open and ended.
- `/crime validate` (level 3): a line for the Capitals binding, and a warning for a decree setting that
  is on while what it needs is missing.
- The log, once at start: `MCA: Crime — MCA Capitals <version>: adapted (...)`. A member call that fails
  at runtime is logged once at WARN with the member and the cause. That answer reads as failed, and a
  decree treats it as it treats anything Capitals cannot confirm: refused, or suspended, never assumed.

## Limits

- **Capitals' "inside the capital" is not Crime's village.** Capitals counts a player as inside within
  96 blocks of the village centre, or by MCA's last-seen village. Crime uses its own village resolution,
  the same one every case uses, so near the border one can say inside and the other outside. Decrees
  follow Crime's rule.
- **Only a player ruler can decree.** A capital with a villager sovereign has nobody who can issue or
  withdraw. Its standing decrees lapse on their own, or an operator voids them.
- **Court officers and royal guards host the screen only.** No office gains a power, and a royal guard
  enforces only if it is also one of the village's guards.
- **No political overlay.** Crime registers nothing for Capitals in any jurisdiction or policy slot; this
  port carries no jurisdiction provider API at all, and Capitals' answer is never merged with another
  mod's.
- Deferred to a later release: clemency by the Hand or the Master of Laws, decree lines in MCA:
  Conversations (the `DecreeView` API is ready for them), Townstead reactions, decrees in village news,
  and MCA prisons as custody destinations.

## For companion authors

`McaCrimeApi.decrees(server, player)`, `api/model/DecreeView` and `api/event/DecreeChangedEvent`; see
[API.md](../../API.md). (The Forge build's `McaCrimeApi.mayEnforce` belongs to the jurisdiction API, which
this port does not carry; see `docs/PORT_PARITY.md`.)

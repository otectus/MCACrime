# MCA: Crime

**Law for MCA villages: guards who come for you, jails that hold you, fines that name what you
did, and a village that only knows what somebody actually saw.**

Hearts are personal. *MCA: Crime* is legal. MCA Reborn already tracks how much one villager likes
you — this mod tracks what the **law** does about you: what you did, who saw it, how badly the
town wants you right now, and what it will take to settle up. Kill a farmer alone in a dark field
and no guard ever comes. Do it in the square at noon and you will be running before you finish.

It's a lightweight add-on: no AI, no text generation, no servers phoned home. Just a
server-authoritative crime and enforcement system wired into MCA's own villagers and guards — and
an optional bridge that turns every case into something the village can gossip about.

---

## What it does

### ⚖️ Two numbers, not one
**Karma** is who you are: slow, long-term, and it decides your standing — Lawful, Neutral, or
Outlaw. **Heat** is what the law is doing about it right now: fast, short-lived, and past a
threshold it makes you **Wanted**. They never read each other, so a career criminal lying low is
not being hunted, and one bad afternoon does not make you an outlaw. Both clocks count only the
time you're actually online — you cannot serve a sentence or cool off by logging out.

### 👁️ Nobody hangs for what nobody saw
A crime needs **witnesses**. When something happens, villagers and guards near the *victim* with
line of sight become the ones who know — recorded by name, at that instant, not re-derived later.
An unwitnessed crime scales its karma penalty and generates no Heat at all: no guard comes,
because nobody told them. The world still records it happened. The village genuinely doesn't know.

### 🛡️ Guards that actually come for you
Being a **legal target** makes force against you lawful — you're Wanted, you broke out of jail,
you're holding somebody captive, or (if the server wants) you're simply a known Outlaw. Guards
pursue; ordinary villagers turn and run. And when a guard is chasing you, the game tells you
*which* of those four reasons is why.

### 🔒 Jail that survives logout, death, and a restart
Sentences run in online ticks. Log out and the clock pauses. Die and it doesn't clear. Walk
through a portal and it keeps ticking. Restart the server and it resumes exactly where it was.
Servers choose how hard the walls are: **containment** puts strays back and protects the blocks,
**physical** lets you break out for real — which is its own crime, with its own Heat, and the
sentence keeps running while you run. A real-time ceiling force-releases anyone held too long, so
nobody is ever stuck.

### 💰 Pay the fine, or serve the time
A fine is priced off your Heat and settles **specific cases**, oldest first — afterwards the
ledger says which offences you answered for, not just that you paid something. Above the jailable
threshold a fine is simply refused: *your crimes are too serious to fine away.* Outlaws can't buy
their way out at all until they **surrender** — which drops your Heat, shortens your sentence, and
clears the escaped flag. Being sorry isn't the same as making it right, but it is a start.

### 🪢 Rope, cuffs, and locked cuffs
Restraining someone is a **channel**, not a click: get hit, take a step, lose line of sight, or
let them get away and it breaks. And it only works on a target who is genuinely vulnerable — low
on health, asleep, or freshly surrendered. Guards are immune, always. Rope is quick to tie and
easy to slip; cuffs are slower and hold better; locked cuffs don't come off with a struggle at
all. Captives are never deleted — an NPC is held on a leash, and wandering too far frees them.

### 💸 Somebody has to pay for you
Hold a captive and you can demand a **ransom**, but you don't pick the price or the payer. It goes
to their spouse first, then a parent, an adult child, a sibling, a close relative — and only if
none of them can be reached, to the village itself, at a noticeably worse rate. Family have to
actually be online to pay. Demands expire, cooldowns stop a village being farmed, and the moment
your captive dies, escapes, is rescued, or is jailed, the demand is dead and everyone is told.

### 🔪 Robbery pays better than murder
**Mug** a villager and they hand over emeralds, and you take a moderate theft charge for it. Kill
that same villager in the next few seconds and the death stops being an ordinary killing and
becomes **murder during a robbery** — the heaviest charge in the mod — and you get no extra loot
for it. Villagers killed resisting a mugging don't drop profession gear by default either. The
whole design points one way: take the money and leave.

### 📒 Every crime is a case
Not a score — a **case**, with an id, a victim, a village, its witnesses, and a disposition:
unresolved, fined, served, pardoned, escaped, or expired. Escaping is not forgiveness: an escaped
case stays actionable and can still be settled later. A pardon takes a deliberate act. A settled
case never quietly reopens. `/crime ledger` reads the whole history back.

### 💔 The village takes it personally
Crime isn't only numbers. The victim loses hearts toward you. So does their family. So does every
villager who watched. Rescue somebody's captive relative and you get those hearts back with
interest; pay a fine and part of it comes back as restitution to the person you wronged.

### 🔧 One jar, every MCA build
MCA Reborn renamed its own packages partway through the 7.7 line, and mods that hard-linked the old
names simply stopped working. This one doesn't name them at all — it finds MCA's classes by name at
startup, whichever layout is installed, and anything a future MCA removes turns into one missing
feature instead of a crash. The build re-checks that against three separate real MCA versions every
time it runs.

### 🧩 Data, not code
All seven crimes ship as JSON and a datapack can retune every karma and Heat value or add new
ones. `/crime reload` swaps them live and `/crime validate` reports every problem with its exact
file and field — and if the JSON is deleted or broken outright, a built-in fallback keeps
detection working rather than silently switching the law off.

---

## Requirements

**Language:** English.

| | |
|---|---|
| **Minecraft** | 1.20.1 |
| **Mod loader** | Forge 47.x |
| **Required** | [MCA Reborn](https://www.curseforge.com/minecraft/mc-mods/minecraft-comes-alive-reborn) `7.6` – `7.7` — one jar covers the whole range, including the package rename in 7.7.1 (verified against 7.6.20, 7.7.0-beta.2, and 7.7.1-alpha.2) |
| **Only if MCA needs it** | [Architectury API](https://www.curseforge.com/minecraft/mc-mods/architectury-api) — required by MCA 7.6, dropped by MCA 7.7. This mod never asks for it |
| **Optional** | MCA: Reputation `0.2.0+` — every case becomes a public deed the village remembers and gossips about |

> This is an **add-on** — MCA Reborn must be installed for it to do anything.

Everything is server-authoritative. The client is told what to draw and can never send a karma
value, a Heat value, a case, or a witness that the server trusts.

---

## Optional: MCA: Reputation integration

With **MCA: Reputation** (0.2.0+) installed, a crime stops being private business between you and
the guards and becomes something the town knows. Each of the seven crimes maps to exactly one
public incident, and three good deeds map too — paying a fine, serving your sentence, and rescuing
a captive all read publicly as making good.

The two mods negotiate rather than guess. MCA: Crime becomes the single producer for villager
assault and killing **only** once its bridge registers successfully and Reputation accepts the
claim; if the bridge is absent, disabled, or incompatible, Reputation keeps detecting those itself.
There is never a state where both record the same deed, or neither does. Cross-mod writes are
queued durably, so a crash — or temporarily pulling Reputation out of the pack — doesn't lose them.

**One thing to know before you install both:** MCA: Reputation on its own folds a beating into the
killing that follows it, so hitting a villager twice and then killing them costs you −40 standing.
With MCA: Crime holding authority it costs **−48**, because Crime keeps the assault and the
killing as two separately resolvable legal cases and both stand on their own. This is intended,
not a bug — but standing will drop faster than Reputation's own numbers suggest, and a server
owner should know that going in.

---

## Configuration

Everything is toggleable, and every number is a config value rather than a constant.
`config/mcacrime-common.toml` (server-authoritative) covers band thresholds, karma and Heat decay,
detection and witnessing, guard and villager behavior, kidnapping and restraints, jail and its
containment mode, fines, surrender, ransom, mugging, the hearts consequences, ambient messages,
and each integration independently. `config/mcacrime-client.toml` covers presentation only:
nameplate coloring, the inventory card button, and whether it opens by itself.

Turning a subsystem off changes behavior only. Nothing in this mod deletes a saved record.

---

## Commands

`/crime` lets any player check their own standing and act on their own situation — status, pay a
fine, surrender, mug, demand or pay a ransom, try to escape. Operators get the rest: read another
player's status and full ledger, set karma and Heat, jail and release, assign a jail region,
reload and validate content, and inspect the cross-mod delivery queue. `/crime release` is a
universal backstop that frees a player from a sentence or a kidnapper, whichever applies, and
`/crime debug integrations` prints a bug-report-safe summary with no player UUIDs in it.

---

## For datapack & modpack authors

Crimes are **data**, not code — JSON under `data/<namespace>/mcacrime/crimes/`. Each definition
sets its own karma delta, Heat delta, witnessed multiplier, and which kind of victim it applies
to, so a pack can make theft trivial and murder unforgivable, or the reverse. The incidents
published to MCA: Reputation are datapack-driven too, with their own severity, visibility, decay,
resolution weights, and gossip lines. `/crime validate` reports every content problem at once with
the exact file and field.

---

## For mod developers

A stable, read-only Java API (`dev.otectus.mcacrime.api`) and eleven Forge events covering crimes
witnessed and committed, karma and Heat changes, Wanted flips, jailing and release, kidnapping and
release, fines paid, and cases resolved. Reads are scoped to the player you ask about, only
immutable views cross the boundary, and nothing in the API ever throws at you — a failed lookup
comes back empty, so a dialogue check or quest condition can't be crashed by this mod. Mutation is
deliberately not exposed: it stays behind one server-side chokepoint so replays and cross-mod
retries can be made idempotent.

---

## Status & license

Alpha — first public release, actively developed; feedback and bug reports welcome. Two honest
notes: the mod is English-only for now, and the three restraint items currently use placeholder
textures borrowed from vanilla items. Licensed **GPL-3.0-only**, matching MCA Reborn.

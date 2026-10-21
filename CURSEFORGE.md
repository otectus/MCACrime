# MCA: Crime

**Village law for Minecraft Comes Alive: Reborn—witnessed crimes, persistent cases, guards, jail, fines, kidnapping, ransom, mugging, and consequences that survive a restart.**

MCA Reborn already tracks how individual villagers feel about you. MCA: Crime adds the legal layer around those relationships: what happened, who witnessed it, which village has jurisdiction, how much Heat you have attracted, whether force against you is lawful, and how each case was resolved.

Commit a crime unseen and the village may never learn about it. Do it in front of witnesses and the incident can produce Heat, reports, guard intervention, relationship damage, and—when MCA: Reputation is installed—public gossip that spreads through the community.

MCA: Crime is server-authoritative. It does not use generative AI, send gameplay data to an external service, or manufacture outcomes on the client.

---

## What is new in this release

**0.8.0 is a usability release: the things that worked but were hard to find or hard to trust.**

- **Commands find the villager you mean.** `/crime mug` and `/crime report` now pick the villager or guard under your crosshair within 4 blocks (before 0.8.0 they could not select anybody). Operator job and debug commands use the villager you are looking at, never a closer bystander or one behind a wall, take an explicit `[target]` from the console, and say exactly why when they refuse. `/crime debug target` shows what a command saw.
- **The pillory and guillotine explain themselves.** The action bar names who was secured or released, why a blade fell harmlessly, and why a placement was refused. A blade slower than the order window no longer lets every execution expire unstruck, and the blow lands only for the exact order, device, occupant and live sentence it was released for.
- **Key copying you can learn in the game.** A blank Key Mold now takes an impression from a bound key, renamed keys keep their names on a ring, every key item explains its next step, and a ring shows the server's real capacity.
- **Every mask in every dye.** All 16 styles in all 16 colours, named by colour ("Blue Plague Doctor Mask"), with dyed art that keeps lenses, eyes, straps and trim their own colours. A **Masks** creative tab holds all 272, and a water cauldron washes the colour out.
- **Help in the game.** `/crime help` covers commands, targeting, the guillotine, keys, key rings, key molds and masks; tooltips point at the right topic; fixed recipes appear in the recipe book when you pick up an ingredient.

0.8.0 also ships everything prepared as 0.7.9, which was never published on its own: the Forge build's fixes for duplicated stolen goods, decree boundaries, decree payment in prison and restrained players' own updates; a newly placed padlock giving its owner the first key; and the features this port had been missing: player crime reports, village news through MCA's mailbox, per-world game rules (`/crime rules`) and the GeckoLib mask models.

0.8.0 uses **network protocol 21**: update the client and the server together. Coming from 0.7.8, **GeckoLib is now required**, four config keys are added, and worlds migrate automatically to **world schema 18**: back up your world first, and roll back only from that backup. One new optional item data component, `mcacrime:key_ring_labels`, keeps renamed keys' names on a ring; roll back with your pre-upgrade backup rather than by swapping jars. A 1.20.1 Forge client cannot join this NeoForge build, as before.

---

## Crime actions

Open MCA's normal villager interaction screen and select **Crime…**, or simply **right-click the villager while holding a weapon**. An unbound keybind opens the same server-issued menu for whoever is under your crosshair, and the Crime button on MCA's screen can be turned off in the client config. Gameplay commands are accessibility fallbacks and use the same validation, cooldowns, locks, and finite accounts as the menu.

### Mug

Threaten the exact villager you selected through a short channel. Moving away, losing sight of the victim, or being interrupted can break the action, but beginning the threat still creates memory and legal consequences.

A completed mug transfers emerald value from the victim's finite purse. It does not create free money, switch to a nearby bystander, or reset merely because the villager unloaded. Repeating the same victim is limited by memory and cooldowns, while offender and village caps prevent rotating through an entire settlement for unlimited profit.

Killing a recently mugged victim becomes **murder during a robbery**, the heaviest built-in charge, and does not grant another mugging payout. Profession death drops during that window are disabled by default so murder is not more profitable than robbery.

### Restrain and capture

A restraint is applied to a slot on the subject's body and stays there as real gear with its own durability. Getting out of it is work: struggling wears the restraint down, a key opens it at once, and a lockpick is a mini-game the server runs and decides.

- **Duct tape:** quick and weak.
- **Shackles:** the ordinary restraint.
- **Handcuffs:** the strongest; struggling alone rarely wins.
- **A bundle hood** covers the head, and leg restraints are a separate slot from arm restraints.

Two presets ship. **Cuffed parity** is the default: restraints apply instantly and nothing gates who may be restrained. **Balanced village** adds an application duration, vulnerability requirements and stricter rules for capturing players, and is never applied to your config without you choosing it.

Captured NPCs are never deleted. Custody is persisted, bounded by safety timers, and unwound on release, escape, rescue, death, invalid ownership, or administrative recovery.

### Demand ransom

An unlawful captor can demand a ransom for the selected captive. The payer is resolved by family priority—spouse, parent, adult child, sibling, then close relative—with an optional lower-value village-authority fallback. Family payers must be reachable online players.

Demands expire and have victim, family, and village cooldowns. A demand immediately becomes invalid if the captive dies, escapes, is rescued, or enters lawful custody. Village-authority payments come from a finite persisted treasury rather than newly created emeralds.

### Apologize and release

Apologize is a bounded restorative action. It can slowly repair negative MCA hearts toward zero, but cannot create positive-heart farms, erase a crime case, remove stolen-value memory, or replace paying a fine.

Release Captive appears for the exact villager you unlawfully hold. **/crime releasecaptive** provides the same player-facing recovery path when an old or unloaded captive cannot be selected in the world.

---

## Law, witnesses, and enforcement

### Karma and Heat

MCA: Crime separates long-term identity from immediate police attention.

- **Karma** changes slowly and determines your band: Lawful, Neutral, or Outlaw.
- **Heat** represents current enforcement pressure. It decays while you are online and makes you Wanted after the configured threshold.

The two values do not automatically overwrite one another. A known Outlaw can lie low without being Wanted, while a normally Lawful player can attract serious short-term Heat.

### Witnessed crimes

Witnesses are resolved around the victim at the moment the crime occurs. Distance, line of sight, entity type, and configuration determine who actually saw it. Their identities are stored on the case rather than guessed later.

Unwitnessed crimes still affect the offender's private Karma, but normally generate no Heat because the village does not know what happened. Direct victims can retain offender-specific memory and report actions such as mugging.

### Guards and legal targets

Guards pursue players who are lawful targets because they are Wanted, escaped prisoners, active kidnappers, or—when enabled—known Outlaws. The player receives a message explaining why guards are intervening.

Nearby guards challenge the suspect first and allow a short surrender window. The challenge timer does not begin from nowhere: a real guard must be present in the configured radius. Ordinary villagers are not placed into a permanent global flee loop; active fleeing is limited to the direct victim's short panic state.

---

## Jail, fines, and case resolution

Every offence is recorded as a case with a stable ID, offender, victim, jurisdiction, witness set, time, legal values, and resolution. Cases can be unresolved, fined, served, pardoned, escaped, or otherwise closed through explicit transitions.

- **Fines** settle eligible unresolved cases oldest-first and debit real emeralds.
- Serious cases can exceed the fine threshold and require jail or another resolution.
- **Surrender** clears escape state, reduces Heat, and can shorten the resulting sentence.
- **Sentences count online ticks:** logging out pauses time instead of serving the sentence offline.
- Jail state survives death, dimension changes, logout, and server restart.
- Configurable containment mode can return prisoners to the jail area and protect its blocks.
- Physical-jail mode permits a real escape, which creates a jailbreak case rather than silently cancelling the sentence.
- Safety ceilings and **/crime release** provide administrative recovery from invalid confinement.

---

## MCA: Reputation integration

MCA: Reputation is optional. MCA: Crime works by itself using its built-in village standing store.

When MCA: Reputation 0.2.0 or newer is present and the bridge handshake succeeds, MCA: Crime becomes the single producer for villager assault and death incidents. This prevents the same act from being recorded twice. Crime cases are published as public incidents, while paying a fine, serving a sentence, and rescuing a captive can publish restorative outcomes.

Cross-mod writes use a persisted outbox with deduplication and retry handling. Temporarily removing the companion mod or crashing during delivery does not silently discard the legal event.

One balancing difference is intentional: MCA: Reputation alone may fold an assault into a later killing, while MCA: Crime keeps the assault and killing as separately resolvable cases. A beating followed by murder can therefore reduce public standing more than Reputation's standalone defaults.

---

## MCA Capitals: royal decrees

MCA Capitals is optional and reached entirely by reflection. With it installed, the player who rules a capital can issue a **royal decree**: another player, below Noble status in that capital, is wanted in that village and nowhere else. The ruler chooses **Royal decree** from the Crime menu on one of the village's guards or court officers, or uses `/crime decree issue <player> [reason]`, and no operator permission is involved. Capitals is asked on the server for every action, and anything it cannot confirm is refused.

The village's guards then stop that player inside the village to pay a fine or surrender, and serving a decree's term or paying its fine ends it. A decree is not a crime: it touches no Heat, Karma, band, bounty, case or reputation, guards never use force on a decree alone, and it lapses on its own after three in-game days by default. Knights, lords, dukes, princes, the Hand, the Lord Commander and the throne are protected, and a title from another capital counts for nothing.

## Blueprint prisons

MCA's Blueprint counts MCA: Crime's reinforced bars, the gapped bars and the cell door exactly like iron bars and an iron door, so a cell built from them, with one vanilla bed, is an MCA prison, and Capitals' prison too when Capitals is installed. Vanilla materials still count, and mixed materials count together. No companion is needed.

---

## Player information and presentation

- The inventory reputation card shows Karma, band, Heat, Wanted state, sentence time, lawful-target state, and captivity status.
- Player nameplates can be tinted by band without replacing nickname formatting.
- Ambient messages report band changes, witnessed crimes, and the reason a guard is pursuing you.
- Server-authoritative chat coloring by band is available but disabled by default.

---

## Commands

All commands are under **/crime**. Players can inspect and act on their own state without operator permission. Reading another player's legal state requires permission level 2; mutations and administration require level 3.

~~~text
/crime help [topic]                          the in-game guide (commands, targeting, guillotine, keys, keyring, keymold, masks)
/crime karma                                 show your Karma and band
/crime status                                show Karma, Heat, Wanted, jail, and escape state
/crime payfine                               settle eligible cases, oldest first
/crime surrender                             surrender near an eligible authority
/crime mug                                   mug the exact villager in your crosshair
/crime ransom                                demand ransom for an active captive
/crime payransom                             pay a ransom for a held relative
/crime escape                                begin or continue timed escape work
/crime releasecaptive                        release unlawful captive records you own
/crime decree issue|withdraw <player>        as a capital's ruler, decree or withdraw (MCA Capitals)
/crime decree pay|list                       pay a decree against you, or list decrees

/crime query <player>                        inspect another player                 (level 2)
/crime ledger <player>                       inspect another player's cases         (level 2)
/crime debug villager [target]               inspect a villager's crime and job state (level 2)
/crime debug custody|actions                 inspect runtime feature state          (level 2)
/crime debug target [target]                 show how a command would choose its target (level 2)
/crime debug mca                             show the MCA binding and villager recognition (level 2)
/crime debug integrations                    inspect compatibility and outbox state  (level 2)
/crime debug outbox [dead]                   inspect queued or failed deliveries     (level 2)

/crime validate                              validate configuration and content      (level 3)
/crime reload                                reload datapack crime definitions       (level 3)
/crime set karma|heat <player> <value>                                              (level 3)
/crime clearheat <player>                                                           (level 3)
/crime jail <player> <ticks>                                                       (level 3)
/crime release <player>                      universal jail/custody backstop         (level 3)
/crime assignjail <pos> [radius]                                                    (level 3)
/crime job assign thief|fence [target]       give the aimed or named villager a criminal job (level 3)
/crime job clear [target]                    clear the aimed or named villager's job (level 3)
~~~

**/crime debug integrations** deliberately avoids printing player UUIDs so its output can be included in a public bug report.

---

## Compatibility and requirements

| Component | Requirement |
|---|---|
| Minecraft | 1.21.1 on NeoForge |
| NeoForge | the version pinned in `gradle.properties` or newer within the same minor |
| Required | MCA Reborn, the exact NeoForge 1.21.1 build pinned in `gradle.properties`, plus GeckoLib for NeoForge 1.21.1 (range in `gradle.properties`) |
| Optional | MCA: Reputation, the NeoForge 1.21.1 companion build |
| Optional | MCA: Quests, and Townstead — the latter reached entirely by reflection and silently absent without it |
| Optional | MCA Capitals 1.3.5 (NeoForge 1.21.1), reached entirely by reflection, for royal decrees; silently absent without it |
| Optional | Locks Reforged, for its own locks and for fence stock. **Not required**: since 0.7.5 lockpicking is native. On a block Locks Reforged already owns, MCA: Crime refuses to place a second lock. |
| Not required | **Cuffed.** 0.7.5 absorbs its feature set natively. Installed alongside, it is detected by mod id and MCA: Crime logs one startup warning; a config option can additionally stop MCA: Crime applying new restraints. No Cuffed data is ever read, cleared or disabled, and there is no world-import tool. |
| Java | 21 (provisioned by the Gradle toolchain) |

MCA: Crime resolves MCA integration through a runtime compatibility layer rather than linking one specific MCA package layout.

Architectury does not need to be declared separately by this mod. MCA's 1.21.1 NeoForge build does not use it.

### Installation

1. Install Minecraft 1.21.1 and the NeoForge version pinned in `gradle.properties`, or newer within the same minor.
2. Install a compatible MCA Reborn 1.21.1 NeoForge build and GeckoLib for NeoForge 1.21.1.
3. Put the MCA: Crime jar in the mods folder on both the client and server.
4. Optionally install MCA: Reputation 0.2.0 or newer (NeoForge 1.21.1 build) on both sides.
5. Start the game once to generate config/mcacrime-common.toml and config/mcacrime-client.toml.

For an existing 1.20.1 world, back up before upgrading. Player data is migrated from the legacy Forge
capability format on load. The migration is designed to be forward-safe but is not reversible by
installing an older jar.

---

## Configuration and datapacks

Nearly every subsystem and balancing value is configurable: Karma bands, Heat and decay, witness rules, guard radii, victim panic, fines, jail, surrender, capture vulnerability, restraint strength, escape timing, ransom prices and cooldowns, mugging purses and daily caps, relationship effects, messages, presentation, and optional integrations.

Crime definitions are datapack-driven:

~~~text
data/<namespace>/mcacrime/crimes/*.json
~~~

MCA: Reputation incident definitions are datapack-driven as well:

~~~text
data/<namespace>/mcareputation/incidents/*.json
~~~

**/crime reload** reloads those definitions, and **/crime validate** reports configuration or content problems with their precise file and field.

Full documentation is maintained in the repository:

- [Configuration reference](https://github.com/otectus/MCACrime/blob/main/CONFIG.md)
- [Datapack formats](https://github.com/otectus/MCACrime/blob/main/DATAPACK.md)
- [Migration notes](https://github.com/otectus/MCACrime/blob/main/MIGRATION.md)
- [Java API](https://github.com/otectus/MCACrime/blob/main/API.md)
- [Changelog](https://github.com/otectus/MCACrime/blob/main/CHANGELOG.md)

---

## For mod developers

MCA: Crime exposes a server-authoritative Java API (read-only but for two clemency methods over a capital sentence, new in 0.7.5 alongside API version 2) and NeoForge events for crimes, witnessing, Karma and Heat changes, Wanted changes, jail, release, custody, fines, and case resolution. Public reads return immutable views and safe empty results instead of exposing mutable internal state. Two events are cancellable Pre events implementing `ICancellableEvent`. Gameplay mutation remains behind the mod's authoritative services so integrations cannot bypass idempotency, case transitions, or economy rules.

---

## Credits

The 0.7.5 restraint, lock and prison feature set is adapted from **Cuffed** by Lazr Productions under GPL-3.0, the licence this mod ships under. Unattributed upstream art was replaced by original MCA: Crime work.

**Restraint item artwork:** TheWiggleDuck designed and provided custom textures for the three
restraint items (open cuffs, locked cuffs, and rope).

---

## Current status

MCA: Crime 0.5.0 is an **alpha release**. Back up important worlds and report problems with the MCA, NeoForge, and MCA: Crime versions you are using. **/crime debug integrations** and **/crime debug custody** provide useful diagnostic summaries.

Current presentation limitations:

- English localization only.

Licensed **GPL-3.0-only**, matching MCA Reborn.

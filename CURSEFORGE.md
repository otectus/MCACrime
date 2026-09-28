# MCA: Crime

**Village law for Minecraft Comes Alive: Reborn—witnessed crimes, persistent cases, guards, jail, fines, kidnapping, ransom, mugging, and consequences that survive a restart.**

MCA Reborn already tracks how individual villagers feel about you. MCA: Crime adds the legal layer around those relationships: what happened, who witnessed it, which village has jurisdiction, how much Heat you have attracted, whether force against you is lawful, and how each case was resolved.

Commit a crime unseen and the village may never learn about it. Do it in front of witnesses and the incident can produce Heat, reports, guard intervention, relationship damage, and—when MCA: Reputation is installed—public gossip that spreads through the community.

MCA: Crime is server-authoritative. It does not use generative AI, send gameplay data to an external service, or manufacture outcomes on the client.

---

## What is new in this release

**0.7.5 brings the working core of the Cuffed feature set into MCA: Crime as native code, and neither Cuffed nor Locks Reforged is required for any of it.** Restraints are now real gear in three independent slots—head, arms and legs—each with its own durability. Shackles, handcuffs, duct tape and a bundle hood; keys, struggling and a server-owned lockpicking mini-game; padlocks, a safe, a cell door and key rings; chains, fence and tripwire-hook anchors and guard escorts; a pillory, a guillotine and prison bunks; a search opened from the crime menu on a restrained subject; a reinforced prison building set; and five restraint enchantments.

**A capital sentence for killing a guard** is the one feature here that is not from Cuffed. The only capital offence is killing a guard; nothing escalates automatically; an execution is always a deliberate act at a guillotine by a player or an on-duty guard; and with no usable guillotine the condemned simply stays in custody. One config key switches the whole thing off.

0.7.5 uses **world schema 15** and **network protocol 16**: update the client and the server together, and back up your world before upgrading. Twenty-one config keys are retired, with their replacements named one for one in CONFIG.md. A 1.20.1 Forge client cannot join this NeoForge build, as before.

The previous release was an armed-interactions pass: the Crime menu opens by drawing a weapon.

- **Weapon-in-hand trigger:** right-click an MCA villager while holding a weapon to open the Crime menu. Sneaking is not required by default, the off hand counts, and the whole trigger can be switched off. The previous **Shift+interact with an empty hand** gesture has been removed.
- **Automatic weapon detection:** swords, axes, tridents, bows, crossbows, and modded firearms are recognised without configuration. Firearms are matched by name and by mod namespace, and stackable items and blocks from those mods are excluded, so ammo and workbenches are not weapons.
- **Server-owner control:** a whitelist and a blacklist accept item ids or `#tags`, the blacklist always wins, and the `mcacrime:weapons` / `mcacrime:weapons_blacklist` item tags let a datapack contribute without editing config. Digging tools are excluded before the bonus-attack-damage fallback, so a pickaxe stays a pickaxe.
- **Mugging requires a weapon** by default. Unarmed, the action stays visible on the menu and explains what is missing.
- **An unbound keybind** opens the Crime menu for whoever is under your crosshair, for players who would rather not draw a weapon to do it.
- **/crime debug weapon** reports how the held item classifies and which rule decided it.

Note: while the weapon trigger is on, right-click gifting a weapon to a villager is pre-empted by the Crime menu. Blacklist that item, or turn the trigger off, to gift it.

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

## Player information and presentation

- The inventory reputation card shows Karma, band, Heat, Wanted state, sentence time, lawful-target state, and captivity status.
- Player nameplates can be tinted by band without replacing nickname formatting.
- Ambient messages report band changes, witnessed crimes, and the reason a guard is pursuing you.
- Server-authoritative chat coloring by band is available but disabled by default.

---

## Commands

All commands are under **/crime**. Players can inspect and act on their own state without operator permission. Reading another player's legal state requires permission level 2; mutations and administration require level 3.

~~~text
/crime karma                                 show your Karma and band
/crime status                                show Karma, Heat, Wanted, jail, and escape state
/crime payfine                               settle eligible cases, oldest first
/crime surrender                             surrender near an eligible authority
/crime mug                                   mug the exact villager in your crosshair
/crime ransom                                demand ransom for an active captive
/crime payransom                             pay a ransom for a held relative
/crime escape                                begin or continue timed escape work
/crime releasecaptive                        release unlawful captive records you own

/crime query <player>                        inspect another player                 (level 2)
/crime ledger <player>                       inspect another player's cases         (level 2)
/crime debug villager|custody|actions        inspect runtime feature state          (level 2)
/crime debug integrations                    inspect compatibility and outbox state  (level 2)
/crime debug outbox [dead]                   inspect queued or failed deliveries     (level 2)

/crime validate                              validate configuration and content      (level 3)
/crime reload                                reload datapack crime definitions       (level 3)
/crime set karma|heat <player> <value>                                              (level 3)
/crime clearheat <player>                                                           (level 3)
/crime jail <player> <ticks>                                                       (level 3)
/crime release <player>                      universal jail/custody backstop         (level 3)
/crime assignjail <pos> [radius]                                                    (level 3)
~~~

**/crime debug integrations** deliberately avoids printing player UUIDs so its output can be included in a public bug report.

---

## Compatibility and requirements

| Component | Requirement |
|---|---|
| Minecraft | 1.21.1 on NeoForge |
| NeoForge | the version pinned in `gradle.properties` or newer within the same minor |
| Required | MCA Reborn, the exact NeoForge 1.21.1 build pinned in `gradle.properties` |
| Optional | MCA: Reputation, the NeoForge 1.21.1 companion build |
| Optional | MCA: Quests, and Townstead — the latter reached entirely by reflection and silently absent without it |
| Optional | Locks Reforged, for its own locks and for fence stock. **Not required**: since 0.7.5 lockpicking is native. On a block Locks Reforged already owns, MCA: Crime refuses to place a second lock. |
| Not required | **Cuffed.** 0.7.5 absorbs its feature set natively. Installed alongside, it is detected by mod id and MCA: Crime logs one startup warning; a config option can additionally stop MCA: Crime applying new restraints. No Cuffed data is ever read, cleared or disabled, and there is no world-import tool. |
| Java | 21 (provisioned by the Gradle toolchain) |

MCA: Crime resolves MCA integration through a runtime compatibility layer rather than linking one specific MCA package layout.

Architectury does not need to be declared separately by this mod. MCA's 1.21.1 NeoForge build does not use it.

### Installation

1. Install Minecraft 1.21.1 and the NeoForge version pinned in `gradle.properties`, or newer within the same minor.
2. Install a compatible MCA Reborn 1.21.1 NeoForge build.
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

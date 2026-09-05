# MCA: Crime

**Village law for Minecraft Comes Alive: Reborn—witnessed crimes, persistent cases, guards, jail, fines, kidnapping, ransom, mugging, and consequences that survive a restart.**

MCA Reborn already tracks how individual villagers feel about you. MCA: Crime adds the legal layer around those relationships: what happened, who witnessed it, which village has jurisdiction, how much Heat you have attracted, whether force against you is lawful, and how each case was resolved.

Commit a crime unseen and the village may never learn about it. Do it in front of witnesses and the incident can produce Heat, reports, guard intervention, relationship damage, and—when MCA: Reputation is installed—public gossip that spreads through the community.

MCA: Crime is server-authoritative. It does not use generative AI, send gameplay data to an external service, or manufacture outcomes on the client.

---

## Latest Release

The mod now opens the Crime menu by drawing a weapon on an MCA villager: right-click while holding any sword, axe, trident, bow, crossbow, or modded firearm to open the menu. Sneaking is not required by default, the off hand counts, and the whole trigger can be configured off. The unbound keybind lets players open the menu for whoever is under their crosshair without drawing a weapon. An unbound `/crime debug weapon` command shows how the held item classifies and which rule decided it.

The Heat and sentence status boxes now default to the bottom-left corner where they sit clear of the hotbar, health rows, and MCA: Quests' quest log. The HUD anchor is configurable and can be cycled from the in-game settings screen.

For the full list of changes, see [CHANGELOG.md](https://github.com/otectus/MCACrime/blob/main/CHANGELOG.md).

---

## Gameplay

### Crime actions

Open MCA's normal villager interaction screen and select **Crime…**, or simply **right-click the villager while holding a weapon**. An unbound keybind opens the same server-issued menu for whoever is under your crosshair. Gameplay commands under `/crime` are accessibility fallbacks and use the same validation, cooldowns, locks, and finite accounts as the menu.

#### Mug

Threaten the exact villager you selected through a short channel. Moving away, losing sight of the victim, or being interrupted can break the action, but beginning the threat still creates memory and legal consequences.

A completed mug transfers emerald value from the victim's finite purse. It does not create free money, switch to a nearby bystander, or reset merely because the villager unloaded. Repeating the same victim is limited by memory and cooldowns, while offender and village caps prevent rotating through an entire settlement for unlimited profit.

Killing a recently mugged victim becomes **murder during a robbery**, the heaviest built-in charge, and does not grant another mugging payout.

#### Restrain and capture

Capture is a channel rather than a single click. The target must be eligible and vulnerable, and the captor must have a supported restraint. Taking damage, moving too far, losing line of sight, or letting the target escape range breaks the attempt.

- **Rope:** fast to apply and easier to escape.
- **Cuffs:** slower to apply and harder to escape.
- **Locked cuffs:** strongest restraint; ordinary struggle alone cannot open them.

Captured NPCs are never deleted. Custody is persisted, bounded by safety timers, and unwound on release, escape, rescue, death, invalid ownership, or administrative recovery.

#### Demand ransom

An unlawful captor can demand a ransom for the selected captive. The payer is resolved by family priority—spouse, parent, adult child, sibling, then close relative—with an optional lower-value village-authority fallback. Family payers must be reachable online players.

Demands expire and have victim, family, and village cooldowns. A demand immediately becomes invalid if the captive dies, escapes, is rescued, or enters lawful custody. Village-authority payments come from a finite persisted treasury rather than newly created emeralds.

#### Apologize and release

Apologize is a bounded restorative action. It can slowly repair negative MCA hearts toward zero, but cannot create positive-heart farms, erase a crime case, remove stolen-value memory, or replace paying a fine.

Release Captive appears for the exact villager you unlawfully hold. **/crime releasecaptive** provides the same player-facing recovery path when an old or unloaded captive cannot be selected in the world.

---

## Law, witnesses, and enforcement

### Karma and Heat

MCA: Crime separates long-term identity from immediate police attention.

- **Karma** determines your band: Lawful, Neutral, or Outlaw. It changes slowly and persists across sessions.
- **Heat** represents current enforcement pressure and decays while you are online. You become Wanted after the configured Heat threshold.

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

When MCA: Reputation 0.3.0 or newer is present and the bridge handshake succeeds, MCA: Crime becomes the single producer for villager assault and death incidents. This prevents the same act from being recorded twice. Crime cases are published as public incidents, while paying a fine, serving a sentence, and rescuing a captive can publish restorative outcomes.

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
/crime debug weapon                          show weapon classification             (level 2)
/crime debug guards                          report guard population per village     (level 2)
/crime debug arrest                          report arrest lifecycle and phase       (level 2)
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

## Configuration and datapacks

Nearly every subsystem and balancing value is configurable: Karma bands, Heat and decay, witness rules, guard radii, victim panic, fines, jail, surrender, capture vulnerability, restraint strength, escape timing, ransom prices and cooldowns, mugging purses and daily caps, relationship effects, messages, presentation, and optional integrations.

### Common config (server-authoritative)

Key toggles and balancing values:

- **enableMugging** (default on): whether villagers can be mugged. When off, the Mug action is hidden.
- **enableBail** (default off): whether a player can pay bail to reduce a jail sentence before serving it.
- **enableProfessionDeathDrops** (default off): whether a villager killed while resisting a mugging drops profession loot. Off by default so robbery pays better than murder.
- **enableVillageRansomFallback** (default on): whether a ransom demand falls back to village-authority payment when no family member is reachable.
- **enableReputation** (default on): whether to record community standing through MCA: Reputation when installed, instead of the built-in store.

Many fine-grain settings are also available for Karma thresholds, Heat decay, witness distance and line-of-sight rules, jail configurations, ransom pricing, mugging economics, and relationship effects.

### Client config (presentation only)

- **hudAnchor** (default BOTTOM_LEFT): which screen corner or edge the Heat and sentence status boxes sit against. Configurable and can be cycled from the in-game settings screen.
- **hudOffsetX / hudOffsetY** (default 4): horizontal and vertical nudge from the anchored edge, in pixels.
- **showButtonOnMcaScreen** (default on): whether the Crime button appears on MCA's own interaction screen.
- **renderRestraintPose** (default on): whether to pose a restrained player's arms behind their back. Presentation only—the server validates restraint either way.
- **Ambient messages** (default on): whether to show on-screen messages for band changes, witnessed crimes, and guard pursuit.
- **Chat color** (default off): whether to color player names in chat by band.

There is no SERVER config spec. Configuration is COMMON + CLIENT only: the COMMON section is server-authoritative, and the CLIENT section controls presentation only.

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

## Installation

1. Install Minecraft 1.20.1 and Forge 47.x.
2. Install a compatible MCA Reborn version (7.6.x or 7.7.x).
3. Put the MCA: Crime jar in the mods folder on both the client and server.
4. Optionally install MCA: Reputation 0.3.0 or newer on both sides.
5. Start the game once to generate `config/mcacrime-common.toml` and `config/mcacrime-client.toml`.

For an existing world, make a backup before upgrading. When the world loads, saved crime data is migrated to the current schema if needed. The migration is designed to be forward-safe but is not reversible by installing an older jar.

---

## Requirements and compatibility

| Component | Requirement |
|---|---|
| Minecraft | 1.20.1 |
| Mod loader | Forge 47.4.10+; compatible with Forge 47.x range [47,) |
| Required | MCA Reborn 7.6.x or 7.7.x (version range [7.6,8)) |
| Optional | MCA: Reputation 0.3.0+ (the integration itself requires 0.3.0; older versions degrade to the built-in store) |
| Java | Java 17 |

MCA: Crime resolves MCA integration through a runtime compatibility layer rather than linking one specific MCA package layout. The same jar is tested against MCA Reborn 7.6.20, 7.7.0-beta.2, and 7.7.1-alpha.2 package layouts.

Architectury is MCA's own requirement and is deliberately not declared by this mod. Install the dependencies required by your chosen MCA Reborn build normally.

---

## For mod developers

MCA: Crime exposes a read-only, server-authoritative Java API and Forge events for crimes, witnessing, Karma and Heat changes, Wanted changes, jail, release, custody, fines, and case resolution. Public reads return immutable views and safe empty results instead of exposing mutable internal state. Gameplay mutation remains behind the mod's authoritative services so integrations cannot bypass idempotency, case transitions, or economy rules.

---

## Credits

Restraint item artwork (open cuffs, locked cuffs, rope) by TheWiggleDuck.

---

## Known limitations

- **English localization only.** The mod currently includes English translations only.

---

Licensed **GPL-3.0-only**, matching MCA Reborn.

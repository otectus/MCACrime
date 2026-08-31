# Changelog

All notable changes to MCA: Crime.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Compatibility: Minecraft 1.20.1 · Forge 47.x · requires MCA Reborn `[7.6,8)`, built against
`7.6.20`. Architectury is deliberately not declared — MCA 7.6 pulls it in itself and MCA 7.7
dropped it. Optional: MCA: Reputation `[0.2,)`.

## [0.3.0] — unreleased

Actions, finite economy, and anti-repeat safety. This update begins the interaction-first redesign
specified in `MCA_CRIME_ACTIONS_AI_BALANCING_IMPLEMENTATION_SPEC (1).md` and closes the known P0
duplication, reroll, and custody failure paths.

### Added

- A shared server action service with actor/target locks, timed sessions, exact target UUIDs,
  request nonces, replay results, and server-issued menu sessions.
- A mod-owned Crime action screen. Shift+interact with an empty hand is the compatibility fallback;
  both it and `/crime mug` enter the same server handler.
- Persisted finite villager purses, bounded lazy refill, victim/offender memory, direct-victim reports,
  recovery state, and actor/village daily robbery limits.
- Persisted transaction receipts and finite village treasuries for conserving mug/ransom payouts.
- Timed, persisted escape work with one deterministic roll per attempt and interruption/cooldown state.
- Crafting recipes and commit-time consumption for rope, cuffs, and locked cuffs.
- A guard challenge/surrender window and stale-target stand-down before combat enforcement.
- Contextual Mug, Restrain, Apologize, Demand Ransom, and Release Captive menu actions with
  server-authored availability reasons.
- World-data schema 4 with migration coverage for the new economy and memory sections.

### Fixed

- Mugging no longer selects a nearby bystander or creates unlimited emeralds.
- Repeated escape commands no longer reroll the escape probability.
- Village-authority ransom can no longer mint emeralds or overdraw its treasury.
- Ransom payer cooldowns are checked after resolving the actual payer; captor/victim identities are
  excluded from payer selection.
- A captor cannot begin or commit a second unlawful capture beyond configured capacity.
- Captive/captor death, logout, and release unwind action, custody, ransom, leash, and pointer state.
- Player captives are released after the configured captor-disconnect grace.
- Ransom settlement records an `extortion` outcome instead of duplicating the kidnapping charge.
- Fresh and upgraded villager purses can no longer begin empty, so a completed first mug awards at
  least one emerald when the configured purse capacity permits it.
- Custody checks, ransom selection, guard legal-target checks, and login reconciliation now read the
  authoritative world custody table instead of trusting a stale one-captive cache.
- `/crime releasecaptive` gives ordinary players a recovery path for invisible or legacy custody
  records; duplicate legacy records are also reconciled on login.
- Reported mugging now alerts nearby guards even before the offender reaches the normal Wanted Heat
  threshold; a guard must actually be nearby before the challenge timer begins.
- Negative standing no longer forces every nearby villager into a permanent flee loop. Only the
  direct victim panics, for the configurable short `muggingPanicTicks` window.
- Capture channels now atomically reject both duplicate actors and duplicate targets.

## [0.2.0] — unreleased

Suite integration. A crime stops being private business between a player and the guards and
becomes something the whole village can be told about, through MCA: Reputation — without either
mod guessing at what the other is doing. Underneath that: a real case lifecycle, dimension-aware
village identity, and a durable outbox so a cross-mod write survives a crash or a companion mod
being temporarily uninstalled. 0.1.0 was never published; 0.2.0 is the version that ships.

### Compatible versions

| Mod | Version |
|---|---|
| Minecraft | `1.20.1` |
| Forge | `47.4.10+` (metadata range `[47,)`) |
| MCA Reborn | `[7.6,8)` — one jar for the whole range; the manifest is verified against `7.6.20`, `7.7.0-beta.2`, and `7.7.1-alpha.2` |
| MCA: Reputation | `0.2.0+` (optional) |

### Fixed

- **A dedicated server died the instant any player right-clicked any entity on MCA 7.7.1-alpha.**
  MCA repackaged mid-version-line: through 7.7.0-beta.2 its Forge classes live at
  `forge.net.mca.*`, and 7.7.1-alpha.1 renamed the base package to `forge.net.conczin.mca.*`. The
  MCA adapter opened with an `instanceof` against the old name and no `try`, so the very first MCA
  reference threw `NoClassDefFoundError: forge/net/mca/entity/VillagerEntityMCA` out of an
  `EntityInteract` handler. **The root cannot be inferred from the version number**, which is why
  this is now a class probe and never a version comparison.

### Added

**Runtime MCA binding**

- **No class in this mod names an MCA type any more.** Every MCA class and member is resolved by
  name at runtime through a single binding manifest, against whichever of four candidate package
  roots the installed MCA actually uses, identified by probing for a class every layout has at the
  same relative name. Class-relative names are identical across every layout seen, so the whole
  difference is one prefix.
- **Resolution never throws and never returns null.** An unresolved member becomes a constant stub
  of the identical erased type returning that type's default, so per-member degradation is free:
  callers need no null checks, and a member MCA removed reads as "absent" rather than exploding.
  Whole-class failures degrade the same way.
- MCA is no longer a compile dependency at all; the version in `gradle.properties` is only what the
  dev runtime launches with.
- `McaBindingProbeTest` replays the whole manifest against **real MCA jars** — one configuration
  and one throwaway class loader per version, because two versions of one module in a single
  configuration would collapse to the newer and leave the older package root untested, which is
  exactly how the rename reached players. It restores the safety net the compiler used to provide:
  a renamed or removed member now fails CI instead of becoming a silently dead feature. A companion
  test enforces that no static MCA link creeps back in.

**Suite integration foundation**

**Suite integration foundation**

- `McaCrimeApi.getApiVersion()` — a version handshake so a companion can refuse an incompatible
  future build instead of dying on a `NoSuchMethodError`. Deliberately a method and not a
  `public static final int`: `javac` copies a constant straight into the consumer's own constant
  pool, so a companion compiled against v1 would keep reading `1` forever and the handshake it was
  written to perform would silently never fire.
- `CrimeCommunityKey` — dimension-aware village identity, `<dimension>/<villageId>`, with a codec,
  NBT and network forms, and a total conversion to MCA: Reputation's own community key. MCA
  allocates village ids per dimension, so village 3 in the Nether and village 3 in the Overworld
  were previously the same village to this mod.
- Immutable API views — `CrimePlayerSnapshot`, `CrimeRecordView`, `CustodyView`,
  `JailSentenceView` — so a `CrimeRecord`, a capability, or the `SavedData` never crosses the mod
  boundary. `snapshot()` returns one object rather than six calls, so a caller cannot hold a karma
  reading from before a change beside a jail state from after it.
- `CrimeRecordQuery` and `CrimeRecordSelector` — bounded, deterministically ordered case queries
  that **clamp rather than throw**, because they are built from decoded packets and a throw inside
  a decoder drops the connection.

**The case lifecycle**

- `CaseTransitions` — the disposition rules as a pure, testable table. An open case can be fined,
  served, or marked escaped; **escaping is not forgiveness**, so an escaped case stays actionable
  and can still be fined, served, or pardoned later; a pardon requires an explicit privileged
  transaction; and a settled case never silently reopens.
- `CrimeCaseService` — the single place a disposition changes. It takes an exact record id, never
  a selector, and performs the transition check, the revision bump, the outbox entry, and the
  event post as one operation.
- `CrimeResolutionEntry`, with a monotonic per-record revision, so a replayed cross-mod resolution
  is recognisable as a replay rather than applied twice.
- `CrimeContext` — a bounded string map on each record for provenance, truncating rather than
  throwing. Not for entity NBT and not for player-supplied text.

**Witness identities**

- Crimes now record **who** witnessed them, not just how many. Identities are snapshotted once at
  detection time and never re-derived, capped at the nearest few, serialised in UUID order so the
  save bytes are stable, while the true crowd size is kept separately.
- `witnessed` is a real field rather than `!witnesses.isEmpty()`. A jailbreak is witnessed by the
  authority with no named witnesses at all, and a migrated record knows it was witnessed without
  knowing by whom.

**The durable outbox**

- Two Forge mods cannot commit their saved data together, so MCA: Crime commits alone and records
  what it still owes its companion in the same dirty cycle. Work is enqueued **unconditionally**
  whenever a crime maps to a civic incident, even with no companion installed — a player who pulls
  MCA: Reputation for one boot and puts it back should find the village still remembers.
- `CrimeIntegrationPump` is the only thing that talks to a companion. It drains on server start,
  on player login for that player's work, and on a configurable interval with a per-pass budget —
  never all at once.
- `DeliveryPolicy` — pure exponential backoff from 200 ticks, doubling to a ceiling, giving up
  after six attempts into a **dead-letter** list an operator can read.
- `DeliveryOutcome` separates *retry* from *don't retry*. A companion that isn't there yet is a
  delay; a payload the companion rejects is not, because retrying it a thousand times would bury
  the one log line that explains the problem.
- A dedupe store, so a redelivered mutation changes nothing. Long-lived case-to-incident links
  live on the record itself and never expire; the dedupe store only covers the replay window for
  one-off mutations.

**MCA: Reputation bridge**

- `ReputationBridge` — the optional-classloading seam. It names no `mcareputation` type; the
  adapter is reached by reflection only after `ModList` confirms the mod is present, inside
  `enqueueWork` during common setup so there is no registration race, and it checks the API
  version before doing anything.
- **The authority handshake.** One producer per logical deed. MCA: Crime becomes the producer for
  villager assault and killing only after its adapter registers *and* the claim is accepted; if
  the adapter is absent, disabled, incompatible, or throws, MCA: Reputation keeps its own
  detector. There is never a state where both produce a deed or neither does. Authority is checked
  per crime rather than cached, so a lapsed claim stops producing overlapping records on the very
  next event, and an unretryable failure degrades the integration for the session *and* releases
  the claim — an unhonoured claim never becomes an incident black hole.
- `CrimeIncidentMapping` — always loadable and unit-testable with MCA: Reputation off the
  classpath entirely. Exactly one incident per crime type, enforced structurally by being a plain
  map. Assault and killing reuse Reputation's own incident ids, because existing dialogue, gossip,
  and incident-tag queries across the suite already speak them; minting parallel ids would fork
  that content.
- Eight shipped incident definitions covering theft, guard assault, jailbreak, kidnapping, and
  mugging-murder, plus three good deeds — a fine paid, a sentence served, a captive rescued.
- Resolutions map honestly: a fine or a served sentence reads as *atoned* (configurable to
  *apologised*), a pardon as *forgiven*, and unresolved, escaped, and expired map to **nothing at
  all**. Breaking out of jail is not atonement, and a case ageing out is not the village forgiving
  a murder.

**Events**

- `CrimeRecordResolvedEvent` — carries the record **before and after** plus the resolution entry.
  "Unresolved to fined" is restitution and "escaped to fined" is settling up after running; a
  listener given only the destination cannot tell them apart. A no-op transition posts nothing.
- `FinePaidEvent` — once per payment, never once per case, with the transaction id and every case
  the payment settled.
- `HeatChangedEvent` — on every committed Heat move, not only threshold crossings, because a quest
  objective like "get your Heat under 20" needs the ones in between. Carries its source and a
  dedupe key so a redelivery is recognisable.

**Commands**

- `/crime debug integrations` — API version, whether MCA: Reputation is installed, enabled, and
  holding authority, outbox and dead-letter counts, and the last failure. It emits no player
  UUIDs, so its output can be pasted straight into a bug report.
- `/crime debug outbox [dead]` — the queued or dead-lettered cross-mod writes.

**Build**

- `checkJarContents` fails the build if a companion mod's classes, Architectury, or MCA's own
  packages are ever shaded into the jar.
- `-PrequireReputation=true` makes a missing sibling checkout a build failure instead of silently
  producing a jar with no bridge in it. Without the flag the adapter package is excluded and the
  jar behaves exactly as it does with MCA: Reputation uninstalled.
- `mca_probe_versions` in `gradle.properties` lists the MCA builds the binding probe replays
  against. Add a version whenever MCA moves again, so the build catches the move instead of a
  player's log does.

### Changed

- **A fine now settles named cases rather than a Heat total.** Payment is allocated oldest-case
  first, capped at a configurable number of cases when it is not clearing everything, so the
  ledger afterwards records which offences were answered for. `/crime payfine` still pays
  everything off in one go and reads the same to a player.
- **Village standing is dimension-aware.** Standing that was previously merged across dimensions
  because two villages shared an id is now separated. This is a correction, but it is a visible
  one on an existing world.
- Crime records store witness UUIDs instead of a witness count.
- The absolute JDK path was removed from `gradle.properties`; the build now resolves a JDK 17
  through the toolchain and the foojay resolver, so it works on any machine.

### Compatibility

- **Standing drops faster with MCA: Reputation installed than Reputation's own numbers suggest,
  and that is intended.** Reputation alone folds a precursor assault into the killing that
  follows, so two non-lethal hits and then a kill costs −40. With MCA: Crime holding authority the
  same sequence produces two cases and two incidents totalling **−48**, because Crime keeps the
  assault and the killing as two separately resolvable legal cases and neither is absorbed by the
  other. Folding them would require a fold operation in the Reputation API and would make one of
  the two cases unresolvable on its own.
- **The saved-data migration is a one-way door.** A world is stepped from schema 0 to 3 on load.
  Take a copy before upgrading. The forward-compatibility passthrough means an older jar reading a
  newer save will hand back what it does not recognise rather than destroying it, but the
  migration itself does not run backwards. See [MIGRATION.md](MIGRATION.md).
- Pre-0.2.0 crime records had no dimension. They are migrated as Overworld and stamped as such;
  records genuinely committed in another dimension before this version cannot be told apart after
  the fact, and the dimension is never guessed from where a player happens to be standing at load
  time.
- Witnessed records that predate witness identities stay marked as witnessed with no witnesses
  named, so villagers are not handed knowledge of crimes they were never recorded as having seen.

### Notes

- 184 automated tests across 30 classes cover the pure domain: the community key, witness capture
  and selection, the case transition table, schema migration 0 to 3, the outbox and its retry
  policy, fine allocation, the shipped incident data, the optional-classload seam, every row of the
  detection-authority truth table, and the MCA binding manifest against three real MCA jars.
- The unit suite runs with MCA deliberately **off** the test runtime, so "MCA is absent" is a real
  exercised state rather than an assumption. The binding probe reaches the real jars by opening
  each in a throwaway class loader instead.
- Not production-verified. MCA Reborn does not load under a ForgeGradle dev runtime — its mixins
  only resolve against SRG names — so compilation and unit tests are explicitly not sufficient
  for anything MCA-facing. [PHASE_5_VERIFICATION.md](PHASE_5_VERIFICATION.md) is the matrix that
  must pass before a release is tagged.
- `/crime case <id>` appears in the Phase 5 checklist but is not implemented in this version.

## [0.1.0] — unpublished

The initial development build across four phases: the karma and Heat engine, crime detection,
enforcement and jail, and captivity. Superseded by 0.2.0 before any release was tagged.

### Added

**Karma and Heat**

- Two independent axes. Karma is long-term moral standing and derives the band — Lawful, Neutral,
  Outlaw — and Heat is short-term law-enforcement pressure that makes a player Wanted past a
  threshold. Bands never read Heat.
- `CrimeState` as the single server-authoritative writer. Every mutator clamps to configured
  bounds, short-circuits when nothing changes so a replay is safe, recomputes the band, posts the
  event, and pushes a display-only sync to the client.
- Decay on a mod-owned online-tick counter: karma normalises toward zero per online day and Heat
  bleeds off per online minute. Logging out pauses decay and a restart resumes it, so nothing in
  this mod can be waited out while offline.
- Login reconciliation that re-derives the band against the *current* config, so changing a
  threshold does not leave players in a band the config no longer describes.
- Band derivation refuses to produce nonsense from a mis-ordered config, falling back to the
  default thresholds rather than trusting an inverted pair.

**Crime detection**

- Seven crime types, defined as datapack JSON with a hardcoded fail-safe mirror so detection keeps
  working if the JSON is deleted or malformed: theft, harming a villager, assaulting a guard,
  jailbreak, kidnapping, killing a villager, and murder during a robbery.
- A false-positive gate, ordered cheapest check first, that rejects unprotected victims, deaths
  with no responsible entity (lava, falling, drowning, suffocation, dispensers), non-player
  attackers, fake players used by automation and farms, anything during an active raid, and
  genuine self-defence against a villager that was already targeting the player.
- Witness resolution against the **victim's** position, not the offender's, so an arrow through a
  portal is witnessed where it lands.
- The witnessed and unwitnessed split: an unwitnessed crime scales its karma penalty and, by
  default, generates no Heat at all.
- A per-victim harm cooldown so a melee flurry is one crime rather than many. The cooldown map is
  deliberately transient and does not survive a restart.
- A persisted crime ledger of immutable records with a full NBT round-trip.

**Enforcement and jail**

- The legal-target rule: force against a player is lawful when they are Wanted, an escaped
  prisoner, holding a captive, or an Outlaw where the server has enabled that.
- Guard pursuit on a throttled scan bounded by the number of online legal targets — never a
  per-tick world scan — and villagers who flee Outlaws using vanilla navigation. Every call into
  MCA fails safe, so a differing MCA version makes the scan a no-op rather than a crash.
- Jail sentences in online ticks, surviving logout, death, dimension change, and restart.
- Three containment modes, snapshotted at the moment of jailing so a mid-sentence config change
  cannot surprise a prisoner. Containment protects the region's blocks and returns strays;
  physical lets the walls be broken, which makes leaving a genuine breakout — its own crime, with
  its own Heat, and the sentence keeps running.
- Jail regions and anchors, assigned by command and persisted, with cheap cube geometry.
- Softlock backstops: a real-online-time ceiling force-releases regardless of anchor or dimension,
  a vanished jail dimension releases on login, admin release always works, and jailing is refused
  with a clear message when no anchor exists and no fallback is configured.
- Fines priced from a base plus a per-Heat term, discounted for Lawful players, refused above the
  jailable threshold, and refused to Outlaws until they surrender. Charges are atomic: a failed
  charge takes nothing.
- Surrender near a guard, a jail, or a Lawful player, which drops Heat below the jailable
  threshold, waives part of a remaining sentence, and clears the escaped flag.

**Captivity and kidnapping**

- Three restraint items — rope, cuffs, and locked cuffs — with their own creative tab, their own
  channel multipliers and escape chances, and tags that accept vanilla leads and modded rope.
- Capture as a channel that breaks on being struck, moving, the target getting away, or losing
  line of sight, gated on a target who is genuinely vulnerable: low health, asleep, or recently
  surrendered. Ordinary villagers may skip the gate by config; guards and combat NPCs never do.
- A custody state machine covering both players and NPCs. NPC captives are held on a vanilla leash
  and are never deleted, and an NPC captive in an unloaded chunk is virtually contained rather
  than force-loading the chunk.
- A tether that either frees a captive who strays or pulls them back, by config.
- **Escaping kidnapping is never a crime; escaping jail is.** The two systems are deliberately
  structural twins that stay legally distinct.
- Ransom, with a strict payer priority — spouse, parent, adult child, sibling, close relative, and
  a lower-value village-authority fallback — per-victim, per-family, and per-village cooldowns,
  expiring demands, and continuous re-validation that kills a demand the moment the captive dies,
  escapes, is rescued, or is jailed.
- Mugging, and the reclassification of a follow-up killing into murder during a robbery, with no
  bonus loot and profession death drops off by default, so robbery pays better than murder.
- Relationship consequences through MCA hearts: the victim, their family, and the witnesses all
  lose hearts, rescuing a captive earns them back, and paying a fine returns part of it as
  restitution.

**Interface**

- An inventory reputation card showing karma, standing, Heat, Wanted, jail, legal-target status,
  and who is holding you — drawn with Forge screen events, with no mixin.
- Non-destructive nameplate colouring by band. Neutral is untouched entirely, and the colour is
  applied so that only unstyled parts of a name inherit it, leaving nickname and formatting mods
  alone. Bands are broadcast on transitions only, never per karma point.
- Optional, server-authoritative chat colouring by band, off by default.
- Ambient messages for band changes, being witnessed, and each of the four distinct reasons a
  guard may be pursuing you, throttled except for band transitions.

**Server surfaces**

- The `/crime` command tree: status and self-service for players, reads at permission level 2, and
  mutation at level 3. Every mutator routes through the state chokepoint rather than writing
  capability NBT directly.
- A dedicated network channel, independent of MCA's. Every packet is server to client and
  display-only; the client never mutates state.
- Player data on a capability copied across death and dimension change, and world data in saved
  data pinned to the Overworld's storage.
- Configuration in which every number, chance, threshold, duration, and toggle is an option rather
  than a constant, with a validator that runs at load and on `/crime validate`.

### Compatibility

- **Works standalone.** MCA Reborn is the only requirement.
- **No Architectury dependency.** MCA 7.6 declares it itself and 7.7 dropped it; this mod names no
  Architectury type, so a 7.7 user who removed it is not blocked.
- **No mixins.** Everything that could have wanted one — the inventory card, nameplate colouring,
  chat colouring — uses a Forge event instead, so there is no MCA-internal signature to drift
  against.
- Every MCA symbol is reached through a single adapter class, so MCA API drift is a one-file fix.
  MCA Reborn ships a Forgix-merged universal jar whose Forge classes are relocated, in both the
  production and dev-remapped jars; the adapter consumes the relocated names.

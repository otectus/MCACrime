# Changelog

All notable changes to MCA: Crime.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Compatibility: Minecraft 1.20.1 · Forge 47.x · requires MCA Reborn `[7.6,8)`, built against
`7.6.20`. Architectury is deliberately not declared — MCA 7.6 pulls it in itself and MCA 7.7
dropped it. Optional: MCA: Reputation `[0.2,)`; the integration itself needs `0.3.0`, and an older
companion degrades to the built-in store rather than failing the load.

## [0.6.4] — unreleased

### Fixed

- Keep guard confrontation background drawing separate from widget rendering, the countdown and
  the menu-displayed acknowledgment. Retain Forge 1.20.1's explicit background call and add the
  same rendering regression checks as the NeoForge port, where reentering `Screen.render()` from
  `renderBackground()` caused a stack overflow reported when surrendering.
- See [Surrender crash verification](docs/SURRENDER_CRASH_VERIFICATION.md) for the diagnosis,
  platform differences and client verification steps.

## [0.6.3] — unreleased

### Fixed

- Allow unarmed players to reach Apologize through the Crime button, menu keybind and
  sneak + empty-main-hand interaction, even when a frightened villager refuses conversation.
  Keep coercive actions behind their weapon requirements. Explain the one-minute settling
  period, repeat-apology cooldown, already accepted apologies and active threats separately.
  Refusal dialogue now explains the reconciliation shortcut.
- Asking a guard to show the charges, or opening a detention with no local charges, now sends
  readable fallback text alongside the translation key. A client missing the Wanted explanation
  no longer prints `mcacrime.challenge.reason.wanted` in chat. Existing client translations and
  resource-pack overrides still take precedence. The same protection covers resisting arrest,
  escaped custody, unlawful captivity and the no-charges response.

## [0.6.2] — unreleased

### Added

- Villagers, including thieves, will not mug players with 50 or more MCA relationship hearts
  with that villager. Configure `criminalJobs.thief.mugProtectionHearts` in the common config;
  `-1` disables relationship protection and `0` protects neutral and positive relationships.

### Fixed

- Wanted Heat now independently authorizes guard and archer pursuit and confrontation, including
  `/crime set heat <player> 100` without a recorded offense. Keep the configurable Wanted threshold
  (default 50), local case/fine scope, and existing response window. Explain Heat-only detention
  instead of displaying zero charges and claiming there is nothing against the player.
- Keep an active refusal enforceable after Heat drops, permit arrests on the same Wanted/refusal
  basis, and exclude captive responders from starting or continuing a confrontation.
- Recheck mugging eligibility during selection, approach, active sessions and immediately before
  theft. Relationship/config changes now stop an attempt before items or currency move, and
  `/crime mugtest` respects the same protections.
- Honor invulnerability, configured victim protection, custody and game-mode changes throughout
  an attempt. Check weapons and reach at both the start and completion, even when periodic weapon
  checks are configured less frequently. Recheck eligibility after external attempt/balance callbacks.
- Close the victim's HUD and mark the session aborted when a final check refuses theft. Refuse
  premature completion and starting another mugging while the thief already has a session.

## [0.6.1] — 2026-09-08

### Fixed

- Allow `/crime set heat <player> <value>` at permission level 2, so command blocks can set
  a selected player's heat for scripted areas such as bank vaults. Previously, the level-3
  requirement rejected command-block execution before the target selector was evaluated.
- Keep `/crime set karma` and other administrative changes at permission level 3, and continue
  rejecting heat commands from sources below level 2.
- Add regression tests for command parsing without an executing player, permission boundaries,
  and nonnegative heat values. Update the command reference with the heat-command permission.

## [0.6.0] — unreleased

Villager death loot:

- Limit death loot to one purchase worth of output per available trade (for example, one iron
  axe), rather than multiplying the output by remaining trade uses. Apply the same limit to fences.
- Drop actual equipped gear and one output bundle per unlocked, non-exhausted trade by default, including
  guard/archer equipment. Preserve item names, enchantments, durability and other item data.
- Recover equipment before MCA clears it during death, without duplicating gear carried in MCA's
  inventory or already included in normal drops. Newly added gear respects Curse of Vanishing.
- Check fences' persisted stock before dropping one item per available selling offer; exclude
  their buying orders and do not restock on death.
- Honor `doMobLoot` and loot-event cancellation. Add independent gear/stock toggles and a bounded
  trade-stack limit under `[loot]`; retain the old small profession drops as an opt-in fallback.
- See [death loot verification](docs/DEATH_LOOT_VERIFICATION.md) for coverage and scope.

Sleep and archer follow-up:

- Sleeping villagers cannot see or hear crimes, acquire new observations, report, gossip, flee,
  threaten players, trade, or respond to conversations. Existing memories are retained for after waking.
- Sleeping guards and archers are unavailable for challenges, pursuit, escort assignments and bounty
  hand-ins. Sleep during a challenge ends the conversation without treating it as refusal.
- Clear stale navigation, attack targets, fear speed modifiers and drawn bows while an MCA NPC sleeps,
  without cancelling its tick or forcing it awake. Damage keeps its normal wake-up behavior; physical
  restraint wakes a captive before a leash can drag them out of bed.
- Recognize MCA archers as law responders alongside guards, including immunity to civilian fear AI.
- Reject sleep-dependent interactions on the server and close a fence trade when the fence falls asleep.

HUD, escort and reaction refinements:

- Combine Heat/Wanted and Sentence/Captivity into one bottom-left panel, below passive chat and
  beside the hotbar. Fit the panel to the available GUI space and hide it while typing in chat.
  Migrate the former unmodified top-left default once; preserve custom placements.
- Give guard confrontations at least **15 seconds**, starting with the first displayed menu frame.
  Delivery acknowledgment has a bounded five-second allowance; reopening, requoting and replaying
  acknowledgments cannot renew the response window. Keep the speaking guard facing the suspect.
- Remove the competing legacy flee navigator. Apply civilian speed penalties on direct reaction
  entry too, use normal MCA walking multipliers and cap crime-directed movement. Retain successful
  flee paths and distinguish an interrupted path from arrival.
- Keep guards and other responders out of civilian fear/compliance states and fear-based interaction
  refusals. Reaction cleanup preserves active law routes and targets; responders file their own reports.
- Route escorts to reachable intake stands outside generated cells, consider alternative entrances
  and bounded segments toward distant assigned jails, allow detours and wait for the prisoner before
  the lead gets taut. Stop the guard's route at intake; retain the blocked-route teleport failsafe.
- Reject generated-cell sites containing living occupants, including the interior and wall margins.
  Repair older cells by moving trapped non-prisoner villagers/guards to checked safe exterior stands.
- Network protocol is now **11**; world schema remains **10**. See
  [HUD and AI verification](docs/HUD_AI_VERIFICATION.md) for coverage and remaining live checks.

Bounty payment recovery and operator tools:

- Reserve a provider-bound payment receipt alongside each new bounty claim before attempting credit.
  Retain exact emerald overflow in the receipt; `/crime collectbounty` and login collect known unpaid
  remainders. Uncertain external credits stop automatic retries. Pending claims survive normal expiry.
- Defer bounty completion events, Karma and paid messages until full payment is confirmed. Changing
  the active currency never converts a saved reward; collection resolves its recorded provider.
- Add permission-level-three `/crime recovery` receipt/escrow/quarantine/audit inspection and JSON
  export, plus revision-checked, noted operator acknowledgement/rearming. Corrections retain the prior
  receipt/property payload and do not directly issue money/items or replay bounty completion effects.
- Advance world data to schema 10 to protect the new audit records from older builds. Older saves
  migrate without inventing payment receipts for historical claims. See
  [recovery operations](docs/RECOVERY_OPERATIONS.md) for commands and cross-system crash limits.

Remaining-work review follow-up and cuff lockpicking:

- When Locks Reforged is installed, ordinary and locked cuff self-escapes require solving its native
  lockpicking minigame. Store the combination with custody, validate pins on the server, and invalidate
  attempts when custody, restraint, dimension or required-pick possession changes. Rope keeps its
  existing behavior. `kidnapping.cuffEscapeRequiresLockpick` defaults to `false`; enabling it requires
  a pick anywhere in inventory. Picks are not consumed or damaged by cuff attempts.
- Successful lawful cuff escapes preserve the sentence and surrender credit, end the escort, pause
  sentence service and file jailbreak. Recapture resumes the original sentence.
- Reserve economic receipts and stolen-property capacity before removing money/items. Reject changed
  transaction replays and retain ambiguous debit failures for operator reconciliation. These stores
  still cannot atomically save an external economy and Minecraft inventory/world data.
- Return stolen goods on arrest through provider-aware owner escrow and shared delivery receipts.
- Reconcile thief controllers after job changes and enable/disable reloads. Ignore other mods' config
  reloads and schedule live reload work on the server thread.
- Populate the public sentence view's identity and exact linked cases. Add a finite configurable
  locked-cuff work duration for nonzero timed escape chances when Locks Reforged is absent.

Confirmed death and property recovery follow-up
([phase notes](docs/MCA_CRIME_PHASE2_DEATH_RECOVERY.md)):

- Apply kill rewards, stolen-property recovery and death-specific custody/reaction/thief cleanup
  only after shared death confirmation. Reject cancellation, revival and repeated corpse callbacks.
- Require both bounty eligibility and lawful lethal force for kill rewards. Freeze the warrant
  revision, price and currency; refuse closed/replaced/revised warrants or a changed currency.
- Move stolen property to owner escrow after confirmed thief death instead of adding it to native
  death drops. Attempt delivery for online owners and retain undelivered property for login.
- Record escrow delivery attempts before external transfer. Keep exact partial remainders; suspend
  automatic retry after ambiguous outcomes. Full receipt/escrow tables preserve the property record.
- Preserve the currency provider on new stolen-goods records and retain legacy records with an
  unidentified provider. No additional schema, protocol or mod-version change.

Incident and combat follow-up ([phase notes](docs/MCA_CRIME_PHASE2_INCIDENTS.md)):

- Coordinate player, NPC and ransom records through checked incident insertion; reject repeated IDs
  and read-only stores before consequences, and defer incident notifications until state/evidence exist.
- Reconcile final damage and death events at server tick end. Armor reduction, zero/canceled damage,
  absorption, totems and canceled deaths no longer depend on an early lethal prediction.
- Record initiating aggression and lawful response in bounded, server/dimension-scoped encounters.
  AI targeting grants no self-defense exemption; nonlethal defense and lethal permission remain separate.
- Narrow raid grace to one nonlethal indirect explosion per encounter against a non-player,
  non-responder. Attribute supported tame-animal damage to its loaded player owner.
- Bind NPC commit notifications and ransom audit rows to their existing transaction/demand IDs.
  Confirm death before releasing custody. No save-schema, protocol or mod-version change.

Local law and settlement follow-up ([phase notes](docs/MCA_CRIME_PHASE2_JUSTICE.md)):

- Share local evidence assessment across guard pursuit, challenge, charge review and arrest; private case counts and stale refusal alone grant no guard authority.
- Freeze each displayed fine to its cases, penalties, policy, currency and Heat. Changed offers require another response and never debit on the stale click.
- Settle exact selections without adopting unrelated cases; cap Heat reduction by the selected cases' contribution. Commands and the Crime menu answer the open guard offer; outside an encounter, voluntary whole-record settlement remains available.
- Keep failed-payment screens open, refresh prices and controls by encounter revision, and retain the original response deadline. Surrender stays with the challenging guard.
- Run each resolution preflight once before debit, commit all selected cases before notifying listeners, and publish live case notifications after Heat is updated.
- Add responder basis and case IDs to `/crime debug guards`. No save-schema or mod-version change in this pass; the changed guard packets require protocol 10.

Arrest and custody follow-up ([implementation and migration notes](docs/MCA_CRIME_PHASE2_CUSTODY.md)):

- Preserve the original reported NPC case through arrest; record witnessed interventions once, before pursuit.
- Bind assessed charges at arrest and persist sentence identity independently of generated cells; prevent arrival/relogin from adopting later crimes.
- Bound NPC escort recovery across restarts, validate detention destinations, and protect guards assigned to another prisoner.
- Reconcile captive thief AI, recheck mugging participants before transfer, and remove resolved cases from report authority.
- Pause PHYSICAL escape sentence credit; return, surrender and guard recapture resume the original sentence without a second discount.
- Validate challenge conversations and retain the response window after a failed payment.

Witness, intimidation, victim memory and integrity release. Development reference and the in-world
verification checklist are in [the 0.6.0 phase notes](docs/MCA_CRIME_0.6.0_WITNESS_MEMORY.md).
This update closes reliability defects in payment, capture,
sentencing, packets, persistence, and world recovery; guards the frontier between older and newer 
saves; and hardens the finite-economy guarantees. Fines now quote the exact amount per case and 
refuse outright when not allowed; surrender only writes its discount after successful arrest; 
sentences bind only their own cases; and a save written by a newer version is opened read-only 
until the problem is fixed. Protocol and schema bump to prevent incompatible clients and worlds 
from silently disagreeing.

### Added

- Per-crime visual and auditory awareness, anonymous hearing, identification confidence and local guard reporting.
- Dynamic compliance, panic, stalling, defiance and resistance using MCA personality, weapons, health, memory and nearby support.
- Bounded persistent fear/anger memories, repeated-offense merging, lazy decay, reduced family/witness memories and one-hop local gossip.
- Contextual interaction refusal, bounded apologies, matching-case restitution and sentence reconciliation.
- Immutable memory API context, a memory-change event, dialogue pools and witness/threat/memory debug commands.

- **New config option `criminalJobs.fence.offerMaxUses`.** How many times each fence trade may be 
  repeated before that specific offer runs out. Uses are persisted per fence and survive relogging 
  and restarts; they reset when the fence restocks (default: 8, range: 1–4096).
- **Fence stock persistence.** Stock no longer resets on screen close — each fence's uses and 
  available trades are persisted in the save and survive restocking intervals.
- **Five new lang keys** for new player-facing outcomes: `mcacrime.surrender.already_serving`, 
  `mcacrime.surrender.failed`, `mcacrime.arrest.no_custody`, `mcacrime.readonly`, and 
  `mcacrime.escrow.delivered`.
- **Transaction receipts** track payment state (PREPARED, SOURCE_DEBITED, DELIVERY_PENDING, DELIVERED,
  REJECTED, NEEDS_RECONCILIATION) for finite-economy auditing and recovery. Debit is recorded before
  credit; credit failure marks the receipt as NEEDS_RECONCILIATION rather than retrying.
- **Property escrow.** Stolen goods that cannot be delivered to their owner become a property lot 
  and are delivered on player login.
- **Restored-cell journal.** Cells that fail block restoration mid-dismantle record the unresolved 
  block positions for retry rather than silently abandoning them.
- **Safe-destination validation** before teleporting a player into jail. Collision, support, 
  hazards, fluids, border, chunk load state, and occupied cells are all checked; arrest fails 
  with a clear outcome if teleport cannot succeed.
- **Sentence identity** linking case resolutions to their sentence. NPC release now settles the 
  NPC's cases, not unrelated ones.
- **Bounty claim tracking.** Each claim carries how much was paid, so revision and expiry do not 
  reopen rewards that were already collected.
- **Read-only mode** for worlds written by a newer schema. Operators with sufficient permission 
  see a `mcacrime.readonly` message; all Crime mutations are blocked until the version mismatch 
  is resolved.

### Changed

- **Protocol version 7 → 10.** Every packet now declares its direction explicitly; array and list
  bounds are validated at decode rather than clamped; and a `RequestBudget` per player throttles 
  menu and ledger requests. Clients on older protocols are rejected at handshake.
- **World data schema 7 → 9.** This build adds optional category memories and witness relay markers,
  as well as the schema 8 development fields and collections for sentence
  identity, bounty claim payment tracking, transaction receipts, property escrow, restored-cell 
  journal, and fence stock. Legacy fields are absent by default; existing worlds are not rewritten 
  on load. Legacy sentences bind their cases at the offender's first login; legacy bounty claims 
  count as fully paid.
- **Fines now quote and settle per-case.** `SettlementPolicy` computes an exact per-case amount 
  before any debit; the quote is used by the guard screen, dossier, command and action handler 
  alike. Cases flagged `MANDATORY_CUSTODY` refuse the fine regardless of entry point, and a 
  failed resolution leaves Heat untouched.
- **Surrender only writes its discount after arrest succeeds.** Heat reduction, surrender 
  timestamp and discount are now deferred until custody is committed. Repeat surrender while a 
  sentence is active is refused with `mcacrime.surrender.already_serving`. Failure to establish 
  custody produces `mcacrime.surrender.failed` and leaves the offender exactly as found.
- **Sentences now bind their cases.** `SentenceResolutionService` settles only the cases that 
  were sentenced, not all open cases. NPC release now settles the NPC's own cases through the 
  same path, rather than shadowing player releases.
- **Restraint consumption timing.** The restraint item is consumed only after custody is 
  committed, not when capture starts. Failed captures leave inventory untouched.
- **Restraint visual policy.** Players and villagers held by hunters or kidnappers now render 
  with pose and cuffs, not only lawfully arrested players. `RestraintPolicy` decides who is 
  posed and restricted.
- **Capture commit result.** `ArrestService.Outcome` carries typed outcomes including `NO_CUSTODY` and
  `NO_CELL` (distinct from other failures), which abort an arrest instead of accepting the capture.
  `CaptureCommitResult` carries its own typed outcomes: CAPTURED, ALREADY_HELD, QUOTA_FULL,
  TARGET_INVALID, RESTRAINT_MISSING, SESSION_LOST, GATED.
- **Fence pricing validator.** `ConfigValidator` now warns and clamps effective `buyPriceRatio` 
  if it would make the final price negative or non-finite. `FencePriceLoader` rejects entries 
  that are non-finite, out of range, or name unknown items; keeps the last good map when a file 
  fails; and replaces stock (rather than OR-merging) when `"sells"` or `"buys"` appears in a 
  price file.
- **World-data robustness.** `ServerMutationGate` closes mutations when the save is from a newer 
  schema or failed to load. Malformed records are quarantined in a bounded list and saved back 
  out for manual inspection. Capacity limits are enforced at insertion via `CapacityResult` 
  rather than truncating at load.

### Fixed

- **Payment and settlement no longer race.** `EconomicTransactionService` records the debit before
  the credit is attempted; if the debit fails, the receipt is marked REJECTED and nothing is persisted.
  If the credit fails after the debit succeeds, the receipt is marked NEEDS_RECONCILIATION for operator
  reconciliation rather than being retried.
- **Bounty claims no longer reopen on revision.** `BountyService.pay` pays only the unpaid delta 
  on warrant revision, and legacy claims count as fully paid. `BountyClaimLedger.expire` keeps 
  claims whose warrant still exists.
- **Fence pricing arithmetic is saturating.** Price calculations use `SafeMath` for fine, fence 
  and bounty multiplications, clamping to long min/max rather than wrapping.
- **Better error recovery for malformed world data.** Records that fail to deserialize are 
  quarantined rather than crashing the load. The quarantine list survives saves so an operator 
  can inspect and manually fix or discard them.

### Notes

- **B09 and B22 are partially addressed.** B09 (bounty revision) has a no-repay delta guard on warrant
  revisions (`BountyService.pay`, `BountyClaimLedger.alreadyPaid`) so re-opened warrants pay only the
  difference, but no reward-lot redesign. B22 (cell removal reliability) journals unresolved block
  positions for retry (`HoldingCellService`, `pendingCellRestorations`), but has no policy for cells
  altered by pistons or explosions.
- **B18 is deferred.** NPC-on-NPC crime fabrication is out of scope for this maintenance release 
  and will be addressed after the settlement and custody guarantees ship.
- **19 new test classes** under `src/test/java/dev/otectus/mcacrime` exercise schema migration, 
  payment settlement, bounty revision handling, capture outcomes, fence pricing, world data 
  robustness, transaction receipts, and safe-destination validation.

## [0.5.0] — 2026-09-02

Armed interactions. The crime menu now opens the way the fiction already implied it should: by
drawing a weapon on somebody.

## [0.5.1] — unreleased

Criminal NPCs and their social ecology. Restrained villagers render with arms behind their back
and wrist restraints on every tracking client. Civilians freeze when threatened; armed villagers
resist. Thieves scout, mug, and flee autonomously; fences trade contraband with pricing that
reflects community standing and police attention. A unified outlaw authority governs guard
hostility, assault legality, and bounty eligibility. Bounties for killing or delivering escaped
prisoners reward players without gaming through repeated suicide.

### Added

- **Restraint artwork.** The three restraint items (open cuffs, locked cuffs, rope) now use custom
  icons by TheWiggleDuck instead of placeholder textures.
- **Restrained villager pose and wrist visuals.** Restrained MCA villagers hold both arms behind
  their back on all tracking clients, including those joining after the restraint was applied.
  Cuffed and rope-bound wrists render with distinct visuals.
- **Weapon-point compliance.** Unarmed civilian MCA villagers freeze in place and move more slowly
  during an active coercive crime. Armed villagers (guards, archers, weapon-holders, tagged combat
  NPCs) resist instead. Cancelling the coercive action restores full movement.
- **Criminal occupation system.** Thief and Fence are persisted criminal jobs, assigned to
  villagers through an optional sweep and shown as vanilla professions when enabled by config.
- **Autonomous thieves.** Thieves scout for targets, approach and threaten players, mug them for
  currency and one eligible inventory item (with protection for hotbar, armor, and off-hand by
  config), and flee. Mugging displays a timed HUD channel. Drawing a weapon or guard intervention
  aborts the mug before any theft occurs. Stolen goods are persisted by thief and owner, recovered
  on the thief's death or arrest, and protected from duplication on save/reload cycles.
- **Contraband trading.** Fences trade tag-driven illicit goods with pricing that adjusts for the
  buyer's karma, Heat, and Wanted status: discounts for ally NPCs and surcharges for criminals and
  fugitives, with Locks Reforged lock/pick support (optional, degrades gracefully). Fence stock
  cycles on a configurable day interval. Fences accept trade unarmed when opened from the Crime
  menu.
- **Guard intervention in NPC crime.** Guards pursue thieves, arrest them, apply restraints, escort
  them to jail, and release them after a sentence. Caught-in-the-act mugging creates mandatory
  custody that cannot be paid off with a fine.
- **Unified outlaw authority.** A single `OutlawResolver` now governs guard hostility, whether
  force is lawful, and bounty eligibility, so no player gains crime liability for force the law
  itself permits against an outlaw.
- **Bounty system.** Qualified kills of eligible outlaws can result in configurable bounty rewards
  (enabled by default). Bounty claims are keyed on warrant id + revision to prevent double-payment
  across respawns and failed arrests. Alive captures pay a multiplier of the kill reward. Optional
  MCA: Quests integration publishes bounties as guard-given contracts that never double-pay.
- **Crime button at bottom of MCA screen.** The Crime button now sits at the bottom of MCA's
  villager interaction screen (configurable position), is disabled without a drawn weapon, and
  shows a tooltip explaining why.
- **Crime button server validation.** The server re-validates the weapon requirement when the menu
  packet arrives, so client and server always agree on what is permitted.
- **Weapon policy sync.** The server sends the exact weapon classification policy to every client on
  login and `/crime reload`, so the Crime button state matches the server's rules.
- **Fence unarmed access.** Fences can be traded with unarmed through the Crime menu, bypassing the
  weapon requirement gate only for fence-specific trades.
- **Thief command suite.** `/crime job assign thief` and `/crime job clear` manage criminal
  assignments; `/crime job list` and `/crime debug thieves` report state.
- **Warrant system.** Warrants track open bounties per outlaw with revisions bumped on each new
  qualifying crime, preventing bounty claims from one warrant across multiple versions.
- **Bounty command suite.** `/crime warrant <player>` and `/crime bounty <player>` query outlaw
  status and bounty quotes; `/crime debug bounty` reports active bounties.
- **New config sections.** `criminalJobs`, `criminalJobs.thief`, `criminalJobs.fence`, and
  `bounty` sections added with every tuning value (see CONFIG.md for the full key list). All
  numeric keys are validated by `/crime validate`.
- **New entity tags.** `mcacrime:always_resists_weapon_threats`, `mcacrime:never_resists_weapon_threats`,
  and `mcacrime:armed_villager_roles` allow datapacks to customize threat compliance per entity type.
- **New item tags.** `mcacrime:illicit_goods`, `mcacrime:fence_sells`, `mcacrime:fence_buys`,
  `mcacrime:fence_blacklist`, and `mcacrime:thief_theft_immune` allow datapacks to drive fence
  inventory and theft exceptions without editing config.
- **`debugLogging` now active.** The config key now actually gates debug output instead of being
  read but unused.
- **Network protocol 6 → 7.** Restraint sync packets changed shape to include visual type; two new
  S2C packets added for weapon policy and criminal job sync.
- **World data schema 6 → 7.** New collections for criminal villagers, stolen goods, bounty claims,
  bounty contracts, warrants, and fence restock timers. A maintenance sweep every 6000 ticks expires
  stale records and old bounty claims; optional MCA: Quests integration declares bounty contracts.

### Changed

- **Mixin retargeted.** The single mixin moved from `PlayerModel.setupAnim` TAIL to
  `LivingEntityRenderer.render`, still exactly one `@Inject`, now applying to both player and
  villager restraint poses.
- **MCA interaction Crime button positioning.** Anchor moved from top-right to bottom, automatically
  placed above the hotbar and any other HUD elements. Configurable via `client.crimeButtonAnchor`.
- **Crime menu weapon gate.** Moved from opening-the-menu stage (now just disabled+tooltip) to the
  `Mug` action itself and to permission checks on coercive handlers, so unarmed players see why they
  cannot act rather than seeing a hidden menu.
- **Weapon policy client-server consistency.** Client weapon rule snapshot is now shipped on login
  and reload so the Crime button never lies about what the server will permit.

### Fixed

- **Late-joining clients see restrained pose.** Restrained villagers now sync their pose state to
  clients who join after they were already restrained, not just to those watching from the moment
  of restraint.
- **Restraint compliance gate.** Weapons drawn during a coercive mug are now checked every tick; the
  action cancels if a weapon is removed mid-mug.
- **HUD outcome line displays formatted values instead of placeholders.** The action progress bar's
  outcome text used to show a literal `%s` instead of the mug success amount or other formatted
  values — a pre-existing defect from when the action HUD was introduced. `ActionProgressS2CPacket`
  now carries `outcomeText` as a pre-formatted `Component` computed from `ActionResult.message()`,
  so the client renders the complete outcome without re-translating the key.

### Notes

- **Restraint artwork credit.** The three new restraint item textures were created by
  TheWiggleDuck.
- **Seven new event types** under `api/event`: `BountyResolvedEvent`, `CrimeAttemptEvent`,
  `CrimeIntentEvent`, `CriminalJobChangedEvent`, `FenceTradeEvent`, `NpcCrimeCommittedEvent`, and
  `WarrantChangedEvent` carry bounty resolution, warrant changes, and fence trades for companion mods.
- **Two new abstraction interfaces** — `EconomicCrimeActor` and `InventoryCrimeActor` — make NPC
  crime pluggable without widening `CrimeActor` itself.
- **Locks Reforged optional support.** When installed and enabled, fences offer locks and picks
  with tiered pricing; the integration degrades to the tag-driven default when absent.
- **MCA: Quests optional support.** When installed and enabled, bounties are published as guard-given
  quests with completion tracked through the Crime signal system; the integration degrades to direct
  bounties when absent.
- **Dedicated server tested.** Builds verified against MCA Reborn 7.6.20, 7.7.0-beta.2, and
  7.7.1-alpha.2 with zero client class leakage to the server.

### Added

- **A weapon classifier.** Swords, axes, tridents, bows, crossbows and modded firearms are recognised
  automatically; a `[weapons]` config block adds a whitelist and a blacklist (item ids or `#tags`),
  and the `mcacrime:weapons` / `mcacrime:weapons_blacklist` item tags let a datapack contribute
  without editing anybody's config. Guns are found by name (`gunKeywords`) and by namespace
  (`weaponMods`, a list of editable guesses), and the namespace rule ignores stackable and block items
  so a gun mod's ammo and workbenches are not weapons. The last-resort rule is a bonus-attack-damage
  threshold, with digging tools excluded above it so a diamond pickaxe stays a pickaxe.
- **An unbound "open the crime menu" keybind**, aimed at whoever is under the crosshair. It replaces
  the removed unarmed gesture for players who would rather not draw on a villager to open a menu; the
  server validates range and line of sight exactly as it does for every other way in.
- **`/crime debug weapon`** prints the held item's id, its class, the rule layer that decided it, and
  the threshold in force — so "why is my sword not a weapon" has a one-line answer.
- **`client.showButtonOnMcaScreen`** turns off the Crime button this mod adds to MCA's own
  interaction screen.

### Changed

- **The crime menu opens on right-clicking an MCA villager while holding a weapon.** The old
  sneak + empty-hand fallback is gone: it was a gesture nobody discovers and it competed with every
  ordinary interaction a villager has. Sneaking is not required by default
  (`weaponTrigger.requireSneak`), the off hand counts (`weaponTrigger.allowOffHand`), and the whole
  trigger can be switched off (`weaponTrigger.enabled`).
- **Mugging requires a weapon** (`weapons.mugRequiresWeapon`, on by default). Unarmed, the row stays
  visible on the menu and says what is missing, rather than vanishing.
- Restraints keep their claim on the interaction: the weapon trigger runs after capture, and a
  restraint is never classified as a weapon.
- **The Heat and sentence boxes now default to the bottom-left corner.** The top-left is where
  MCA: Quests draws its quest log, and the quest log won. Bottom anchors are also lifted clear of the
  hotbar and the health and armor rows automatically, and `BOTTOM_CENTER` clear of this mod's own
  channel bar, so picking a bottom corner no longer hides a box behind vanilla's HUD.
- **`hudOffsetX` / `hudOffsetY` now measure inward from the anchored edge** at every anchor rather
  than always counting down and right from the origin, so one pair of values means the same visual
  gap in any corner. A saved offset on a top-left anchor behaves exactly as before.

### Notes

- **Gifting caveat.** While the trigger is on, right-click gifting a weapon to an MCA villager is
  pre-empted by the crime menu. Blacklist that item under `[weapons]`, or turn the trigger off.
- **Config sync caveat.** The weapon lists are COMMON config, which Forge does not sync. A client
  whose lists differ from the server's will mispredict the swing — the server's answer still decides
  what happens.
- **The new HUD corner reaches new installs only.** Forge writes a default only for a key that is
  missing, so an existing `mcacrime-client.toml` keeps whatever `hudAnchor` it already holds. To move
  an existing setup, pick Bottom Left in the mod's settings screen or delete the `hudAnchor` line.

## [0.4.0] — unreleased

The village answers back. This release closes the spec's Phase 1 gate, gives the mod a real
presentation layer, and fixes the case-lifecycle hole that made serving a jail sentence meaningless.

### Added

- **Every declared action now enters through the action engine.** `escape`, `surrender`,
  `settle_case`, and `pay_ransom` were declared ids with no registered handler; those four flows
  mutated state directly from the command layer, skipping the target locks, request nonces, replay
  cache, and cooldowns every UI action is bound by. All nine ids now route through one contract, and
  `ActionRoutingTest` fails the build if a tenth is ever declared without one.
- **A `CrimeActor` abstraction.** Handlers no longer name `ServerPlayer` in their signatures, which
  is the precondition spec §10.5 sets before NPC-on-NPC crime can exist without a parallel simulator
  that bypasses the finite purses and witness systems.
- **Action presentation metadata** — category, legality marker, duration, requirement markers,
  hostility, and a description key per action, carried on the menu packet so the screen can group and
  explain rows instead of listing them.
- **A redesigned action screen.** Rows are grouped into Coerce / Restrain / Resolve / Other, carry a
  legality colour and a duration chip, scroll when they overflow, and explain on hover what an action
  does, what it needs, and — when it is unavailable — the server's reason for refusing it. A blocked
  action is now shown and explained rather than silently omitted.
- **A confirmation step for hostile actions**, with a client toggle that changes what you are asked
  and never what the server allows.
- **Sentence identity.** A jail sentence now carries a UUID, so the cases it settles can name it.
- **An arrest that actually happens.** Surrender hands the player to the law: lawful custody with the
  arresting guard (`CustodyOwner.guard`/`.jail` had existed since the custody table was written and
  were constructed only by tests), an escort to the cell, and a sentence from a new pure
  `SentenceCalculator` — the mirror of `FineCalculator`, and the answer to "how long" the mod
  previously had no way to compute. A guard who dies, unloads, or cannot path completes the arrest by
  teleport rather than cancelling it; a player who runs from their escort is not dragged back but
  marked resisting, because that is what they are doing.
- **Cells the mod builds, and takes down again.** A jail was a logical region and nothing else, so an
  arrest on a server where no operator had run `/crime assignjail` had nowhere to go. When no jail
  exists, a small iron-bar holding cell is now raised on clear ground near the arrest and removed on
  release, restoring the exact prior state of every block it replaced. It only builds where nothing
  was — any block entity, any fluid, or anything that is not air, replaceable foliage, or plain terrain
  rejects the site — it never force-loads a chunk, it never restores over a block somebody has since
  changed, and every cell carries a lifetime cap, so a player who is arrested and never logs in again
  cannot leave a cage standing for the life of the save. An operator-assigned jail always wins, and is
  never touched.
- **A HUD.** The mod had none: every piece of information it produced arrived as chat text. There is
  now an action channel bar, a Heat/Wanted indicator that draws nothing when you have neither, and a
  custody countdown — each individually switchable, repositionable to any screen edge, and clamped so
  an offset can never push an element off a smaller monitor. Hidden under F1 and in spectator.
- **Durations read as clocks.** A six-minute sentence displayed as `360s`; it now reads `6:00`, and
  rounds up so a timer never shows `0:00` while it is still running.

- **Villagers know who did it, not just that somebody did.** `countWitnesses` returned an integer,
  which cannot answer any question believable AI needs answered: who saw it, whether they saw the
  actor or only heard the act, whether they can still report it. Crimes now record
  identity-carrying **observations** — a role, a confidence, a place, and a reporting state — for the
  direct victim, every eyewitness the existing scan already identified, and everybody within a
  larger, obstruction-aware hearing radius. Sleeping, child and captive observers keep what they know
  and are marked unable to report it, rather than being silently dropped.
- **A crime the law was never told about is not a crime the law acts on.** Observations become
  **reports** only when a responder saw it directly, or when a civilian physically reaches a guard.
  A report is scoped to one jurisdiction, which is what finally makes per-village standing mean
  something and ends guards half a world away reacting to something nobody told them. Filing is
  cancellable through a public event, so a companion mod can implement intimidation or a corrupt
  jurisdiction by suppressing exactly one report and no others.
- **A villager reaction state machine.** Ten states — threatened, complying, resisting, fleeing,
  seeking help, reporting, hiding, recovering, captive — driven by a bounded server-side controller
  that only exists while a villager is actually reacting. There is no per-tick scan of anything: a
  world of three hundred villagers with one mugging in progress ticks one controller. Whether a
  villager runs, fights, or fetches a guard comes from personality and history rather than from a
  constant, so the same villager reacts the same way every time and different villagers do not.
- **Flight has a destination now.** The old behaviour pathed eight blocks directly away from the
  player, which walks a villager into a wall when the offender is in a doorway and walks them *away*
  from the guard who could help. Candidates are scored instead — responder, public building, home,
  ally, open ground — with distance from the threat capped so nobody sprints across the map rather
  than stepping into the guard house twenty blocks away.
- **A data-driven dialogue system.** Villagers speak, in `data/<ns>/mcacrime/dialogue/*.json`, with
  conditions on personality, relationship, role, repeat encounters, purse, band and time of day. The
  server picks a translation key and the client renders it, so lines localise and no text crosses the
  wire; the choice is deterministic per encounter, so reopening a conversation cannot be used to
  reroll until a preferred line appears. Twenty-two events ship with content, and
  `DialogueCoverageTest` fails the build if a fired event has no pool or a pool names a key with no
  translation.
- **A guard challenge you can answer.** Guards used to print one unanswerable chat line and attack
  sixty ticks later. A challenge is now a real encounter with an id: it names the jurisdiction and
  the number of charges, and offers surrender, paying the assessed fine, asking to see the charges,
  or refusing. Force is unlocked only by refusal or by the window expiring, so being attacked is
  downstream of a decision the player made. Asking for the charges deliberately does not close the
  window.
- **Rescue.** Kidnapping had no counterplay: a captivity ended only when the captor chose, the
  captive ground out the escape work, or the real-time cap expired — none of which involve anybody
  else in the world. A third party can now cut a captive free, with the restraint expressed as the
  rescuer's time and equipment rather than a die roll. Rescuing pays the previously-inert
  `rescueHeartGain` to the freed villager and, at half rate, to their family.
- **The case file is visible to the player it is about.** A record existed only in the save file and
  in operator commands: a player could see a decaying Heat number but never the list of specific,
  non-decaying cases that guards actually act on, and paying a fine settled cases they had never been
  shown. The dossier screen lists them, with status, jurisdiction, and whether anybody saw it.
- **A captive panel**, with the three facts a held player previously had to reconstruct from chat
  scrollback: who is holding them, how long the hard cap has left, and whether their escape work is
  running.
- **Key bindings** for the dossier and the situation panel, on keys vanilla leaves free, plus an
  unbound one for reopening a guard challenge.
- **An in-game settings screen**, reached from the Mods list. Client display options only — the
  rules stay in the server's TOML, where a client cannot reach them.
- **Art of its own.** The three restraints borrowed vanilla's lead, iron ingot and tripwire hook
  textures, so cuffs were indistinguishable from an iron ingot in a hotbar. They now have their own.
- **The screens are textured, not drawn.** Every panel, row, scrollbar and progress bar in the mod
  was a stack of `fill` rectangles in a dark violet of its own invention — a flat overlay pasted onto
  Minecraft rather than a part of it. They are now blitted from a single sprite sheet drawn in
  vanilla's own container palette, nine-sliced so a panel is the same bevelled grey as the inventory
  beside it at any size. Buttons stay vanilla's own. The sheet's generator is committed alongside it
  in `tools/gui/`, because a 256×256 PNG is an opaque blob in a diff and the reason each pixel is the
  colour it is should be readable.
- **The list rows are real widgets.** Action rows and dossier rows were rectangles hit-tested by
  dividing the mouse position by a row height, which meant the panels could only ever be used with a
  mouse. They are now buttons inside vanilla's own scrolling list: they take Tab focus, answer to
  Enter, play the click every other button plays, narrate to a screen reader, and carry native
  tooltips. The spec asked for this in as many words — "a real widget, not a render-only hotspot" —
  and the panel that tells a player which actions are crimes was the wrong one to leave unreachable.
  The player-card button in the inventory is a real widget for the same reason, and no longer has to
  cancel a mouse event to avoid eating a click meant for a slot.
- **Colour that survives a light background.** Legality, standing and resolution colours were chosen
  against a dark panel, where a saturated green reads clearly; on vanilla's grey it very nearly
  disappears. Each now appears twice — as a bright chip, which reads at any lightness, and as text
  darkened just far enough to be legible. `PanelColoursTest` measures the contrast of every one of
  them and fails the build rather than letting a colour quietly stop communicating.
- **Audio.** Twelve moments that previously happened in silence — a mugging opening, a purse
  changing hands, cuffs going on, a restraint breaking, a guard's challenge, a cell door — now make a
  sound. All of them are vanilla sounds, chosen so each moment sounds like the thing it is; a player
  already knows what a chain being placed means, and a new sample would have to be learned at exactly
  the moment the information is needed.

- **An arrest with one authoritative state behind it.** Whether a player was being arrested used to be
  a question you answered by asking whichever of five stores you happened to have to hand: an
  in-memory challenge map, an in-memory escort map, a persisted resisting flag, a world custody
  record, and a jail sentence. None of them said "this player is being arrested", two of them were
  erased by a restart, and they could disagree. There is now a single `ArrestPhase` on the player —
  `NONE → CONFRONTED → SURRENDERED → RESTRAINED → ESCORTING → JAILED`, with `RECOVERY` for an arrest
  that could not be completed — written through one chokepoint that validates every transition and
  refuses illegal ones. Wanted, resisting and released are deliberately *not* stored: they are
  projections of Heat, of the existing refusal clock, and of an event that already exists, and giving
  any of them a second writer is how the original bug happened. The phase is persisted in the player's
  NBT, survives death, and is reconciled on login, so reconnecting cannot bypass a sentence.
- **A surrender that is a physical arrest.** Surrendering no longer teleports you into a cell. You are
  cuffed, roped to the arresting guard, slowed, and walked there; the sentence starts when you are
  actually inside the jail region — the same region test the confinement and block-protection code
  already used, so arriving and being contained finally agree on one definition of "inside". While
  restrained you cannot attack, interact, break blocks, mount, jump or sprint, each refusal explained
  once rather than every click. The speed penalty is an attribute modifier rather than a potion
  effect, so it is invisible, particle-free, and cannot be drunk away with milk.
- **An escort that fails safely in every direction we could find.** A guard that dies, despawns or
  unloads hands the prisoner to another responder nearby; with nobody left, or a path that will not
  resolve, or a scan budget exhausted, the arrest completes by teleport rather than being cancelled —
  a pathfinding failure must never be a way to dodge a sentence. A displacement too large to be a
  sprint is treated as a teleport and re-snapped rather than read as fleeing, so an operator `/tp`
  does not silently end an arrest and mark somebody resisting for it. Breaking the tether deliberately
  is the one case that ends the escort, because that is a decision rather than a failure. If the jail
  itself vanishes mid-arrest, the player is released and the guards stand down: nobody is ever left
  restrained with nowhere to go.
- **Handcuffs, and a lead you can see.** A restrained player renders with their arms behind their
  back and cuffs on their wrists, with a rope drawn between them and the guard holding them. The rope
  is drawn from mod state rather than a vanilla lead, because `setLeashedTo` cannot target a player;
  it is cosmetic and cannot desync. All three are individually switchable client-side.
- **A configurable village guard population**, off by default. `[enforcement.guards]` targets
  `ceil(population × guardPopulationRatio)` guards per village — 10 villagers ask for 1, 20 for 2,
  50 for 5 — floored at a minimum, converting one eligible adult villager per pass and never
  converting a guard back. Existing guards count, including MCA's archers, command-spawned guards and
  anything added through `responderEntities`; babies and villagers MCA considers to hold an important
  profession never do; residents in unloaded chunks are credited their share rather than counted as
  missing. **MCA already does this** at `guardSpawnFraction`, which defaults to 0.175 — higher than
  the 0.10 default here — so with both running MCA reaches its target first and this pass finds
  nothing to do. That is documented at the option rather than left to be discovered, and
  `/crime debug guards` prints the counts so it is visible which of the two is doing the work.
- **`/crime debug arrest` and `/crime debug guards`.** The arrest lifecycle and the population
  arithmetic are both invisible from inside the game otherwise; the first prints the phase and what it
  is gating, the second prints population, loaded residents, guards, target and shortfall per village.

### Fixed

- **The guard confrontation menu reopened endlessly, including after surrendering.** The cause was not
  a missing cooldown, and adding one would have hidden it. `respond` closed the encounter — clearing
  both the open-challenge map and the resisting flag — *before* running the surrender, so every way an
  arrest could decline afterwards (no authority in range, nowhere to hold the prisoner, nothing to
  charge them with) dropped the player back into a state indistinguishable from an untouched suspect,
  and the ten-tick enforcement scan handed them a fresh screen. Worse, nothing anywhere asked whether
  an arrest was already under way: Heat and charges both survive an arrest, so a player being walked
  to a cell stayed a Legal Target for the entire walk and was re-challenged — and re-aggroed — every
  scan, by the very guard escorting them. The surrender now records the phase *before* the encounter
  closes, a failed arrest lands in a real `RECOVERY` state with its own expiry rather than in a hole,
  and one predicate gates every confrontation against the lifecycle. The client also stops rebuilding
  the screen when the server repeats an encounter it is already showing, which was taking the panel
  out from under a cursor mid-click.
- **The jail timer never moved.** The sentence was correct server-side and players were released on
  time, but the number on screen was captured the moment they were jailed and never touched again:
  the client's copy had exactly one writer, a status packet sent only on discrete events, none of
  which happen while a sentence is simply running. The only accidental refresh was Heat decay, which
  surrender deliberately drives to zero. The client now runs its own countdown between periodic
  server resyncs — the pattern the challenge timer already used — so the number moves every tick
  without a packet every tick, and the server remains the only thing that decides when a sentence
  ends. It reads `1m 42s remaining` rather than `1:42`, survives closing the GUI, reconnecting and a
  restart, and is held while the game is paused, which the challenge timer had also been getting
  wrong.
- **Surrendering did not actually shorten a sentence.** The 25% waiver was applied by writing straight
  into the live sentence, after which the arrest recomputed a sentence from full charges and took the
  *maximum* of the two — putting the whole quarter back. The waiver is now folded into the sentence
  before it is ever stored, and the surrender path is the one caller permitted to shorten a term.
- **`buildHoldingCell = false` plus a configured fallback jail refused every arrest**, even though
  `/crime jail` on the same player against the same anchor succeeded: the arrest path never consulted
  the fallback that the command did. It is now the last step of the same ladder.
- **One `/crime assignjail` captured every arrest in its dimension.** The nearest-anchor lookup had no
  distance ceiling, so a single assigned jail became the destination for arrests anywhere in the
  world, teleporting prisoners across the map and permanently suppressing cell-building. Arrests now
  respect `jailAssignedMaxDistance` (256 blocks by default; `0` restores the old behaviour).
  `/crime jail` stays unlimited, because an operator naming a jail means that jail.
- **A holding cell could never be told from an orphan.** `HoldingCell.sentenceId` was documented as
  the thing that lets a restart distinguish a live cell from a leftover one, but the cell was stamped
  with a fresh random id at the start of an arrest while the sentence minted its own on arrival, so
  the two never matched and nothing compared them. One id is now threaded through the whole arrest.

- **Refusing a guard did nothing, and surrendering to one did almost nothing.** The two headline
  answers on the challenge panel were both inert, for unrelated reasons, and between them they left the
  encounter a menu with no consequences behind it.

  *Refusal* was recorded in a map that the enforcement scan pruned for anybody who was not **already**
  a Legal Target — that is, for exactly the players whose refusal was the only thing making force
  lawful. It was erased ten ticks after it was made and a fresh challenge opened in its place, so
  refusing produced an endless queue of identical panels. Refusing is now resisting arrest: persisted
  on the player, a Legal-Target condition in its own right, and lapsing on the player's own online
  clock so logging out is not a way to wait it out.

  *Force itself* never worked either. `setGuardTarget` called vanilla `Mob.setTarget` on an MCA
  villager, whose AI is Brain-driven and never reads it — and returned `true` regardless, so every
  caller announced an aggression that had not happened. MCA's guard combat runs under `Activity.CORE`
  and picks its victim from `NEAREST_GUARD_ENEMY`, falling back to vanilla
  `MemoryModuleType.ATTACK_TARGET`; writing that memory is the lever, and because it is a vanilla
  module it needs no MCA binding at all. `setTarget` stays as a second lever for the goal-driven mobs a
  server can add through `responderEntities` — which could previously be made to challenge but never to
  attack, because the compat shim re-narrowed to MCA guards after the caller had already selected
  responders.

  *Surrendering* reduced Heat by thirty and stopped. `JailService.jail` had one caller in the entire
  mod, the operator command, so no sequence of in-game actions could put a player in a cell: the guard
  stood down, the cases stayed open, and a new challenge opened moments later.

- **A guard could stop you about nothing.** A challenge opened on proximity alone, so the panel could
  read "0 charge(s) outstanding", asking to see the charges answered "the guard checks, and finds
  nothing against you", and letting the window run out still counted as refusing. A challenge now needs
  a basis — an actionable case, a filed warrant, an escaped sentence, or a captive being held.
  `ReportService.warrantExists` and `reportConfidenceThreshold` shipped with no caller at all, and now
  gate both the challenge and a guard-initiated arrest.

- **The reaction system disarmed the law.** Guards are villagers, so a guard who witnessed a crime got
  a reaction controller, and every controller exit handed the villager back to MCA by clearing its
  target. The reaction ticker runs twice as often as the guard scan, so it cleared enforcement targets
  faster than they could be applied. A responder mid-arrest is now held against the reaction system and
  keeps its target, while still handing back its navigation.

- **A refused player with no guard within six blocks was never pursued.** The "walk over and talk"
  branch returned before the targeting loop, so with every guard between the challenge radius (6) and
  the pursuit radius (16), somebody who had refused got one guard slowly walked toward them and no
  aggression at all — indefinitely, if that path failed.

- **Surrendering, and paying a fine, never told the client the encounter had closed.** Both removed the
  encounter server-side without sending the closure packet, and the client only ever clears its cached
  challenge on that packet — so the screen stayed "active" for the rest of the session and the reopen
  keybind would resurrect an encounter settled minutes earlier. Every terminal branch now goes through
  one close.

- **The countdown was invisible.** It was drawn at a y computed from the panel height, which with all
  four answers on screen put it inside the Refuse button, and it was drawn before the widgets, so the
  button painted over it. The screen's own javadoc called the countdown "the most prominent thing on
  the panel". Layout is now a pure `ChallengePanelLayout` with a test asserting that the status line
  cannot overlap the buttons and the buttons cannot overflow the panel, because the numbers alone
  drift back.

- **The jurisdiction was a debug identifier.** The challenge panel and the dossier both rendered
  `CrimeCommunityKey.asString()` verbatim — `minecraft:overworld/0` — a form whose own javadoc calls it
  "the stable string form used for NBT map keys, command suggestions, and logs". Guards now name the
  village, through MCA's own `Village#getName`; a village with no name, and the wilderness, each get a
  translated label rather than a key. The new MCA members are bound as **optional**: a cosmetic string
  must not sit on a CI gate that runs against three MCA builds.

- **Any nearby Blue player legitimised a surrender**, which spec §13.3 says in as many words to remove.
  A Blue player cannot take custody, escort anybody, or hold a sentence, so all the branch did was let
  a Wanted player collect the surrender Heat discount by standing next to a well-liked friend.

- **Two more translation keys were used by code and absent from the language file** —
  `crime.mcacrime.assault_player` and `crime.mcacrime.murder_player`, reachable whenever
  `pvpCountsAsCrime` is on. `LangCoverageTest` missed them twice over: its literal scan did not cover
  the `crime.mcacrime` prefix, and its concatenated-family list did not enumerate `CrimeIds`. Both are
  fixed, and the crime ids are now read reflectively, so a twelfth id cannot skip the check the way the
  eleventh did.

- **The MCA: Reputation integration could not compile, let alone run.**
  `CrimeReputationCompat` — the whole adapter — is written against
  `McaReputationApi.registerCoreIncidentAuthority`, `CoreIncidentAuthority` and `CoreIncidentKind`,
  and none of those existed on the Reputation side. Any build with the companion's classes present
  failed outright, and the documented fallback (`build.gradle` drops the adapter when they are absent)
  meant the shipped jar simply had no Reputation support in it at all.

  The handshake now exists, in MCA: Reputation 0.3.0. It is what stops both mods recording the same
  deed: MCA: Crime claims villager assault and killing, Reputation stands down from detecting them,
  and Crime files the same incident types so the ledger, scores, decay, gossip and witnesses are
  exactly what Reputation would have produced on its own. The claim answers honestly and per event —
  `CrimeCoreAuthority.owns` is false the moment crime detection or the integration is switched off, and
  Reputation resumes on the very next hit.

  The declared dependency stays `[0.2,)` deliberately, even though every type the adapter imports
  arrived in 0.3.0. Tightening it would make Forge refuse to start at all for somebody running the
  older companion, and an *optional* integration is not worth a failed load. A 0.2.x companion instead
  loads the adapter into a `NoClassDefFoundError`, which `ReputationBridge` already catches: the
  integration switches off, MCA: Crime falls back to its own village standing store, and the game
  runs. What that path was missing was a legible explanation, so it now names the version needed
  rather than reporting a bare exception class.

- **Serving a full jail sentence resolved nothing.** `Resolution.SERVED` was produced nowhere in the
  mod: a player could be jailed, serve the entire term, walk out, and still have every charge open and
  actionable against them, with guards holding the same legal basis to pursue them as before. Serving
  a sentence now closes the cases it was served for, and the `mcacrime:sentence_served` incident —
  which shipped in the jar and could never previously be emitted — can reach MCA: Reputation.
- **Breaking out of jail left the ledger silent about it.** Escaping now marks the sentence's cases
  `ESCAPED`. That is a status and not a resolution: an escaped case stays fully actionable and can
  still be fined or served later. The jailbreak charge itself is filed after the sweep, so it is not
  marked escaped by its own escape.
- **Eleven translation keys were used by code and absent from the language file**, so players saw raw
  keys. Nine of them were the entire `action.cancel.*` family — every reason an action can be
  interrupted. `LangCoverageTest` now fails the build on a missing key, including the families built
  by string concatenation that a naive scan misses.
- **An interrupted action never said why.** Nine cancellation reasons — you moved, you lost sight of
  them, you were struck, they got away — were recorded into a server-side replay result that nothing
  ever read. The channel simply stopped. Each one now reaches the player who caused it.
- **Progress was action-bar spam.** Mugging rewrote the action bar every ten ticks, the capture
  channel every single tick, and escape work every second; each then vanished silently. All three now
  drive the same HUD bar, sending on a five-tick interval to the acting player alone — never
  broadcast, because a channel's remaining duration is exactly what the interruption rules turn on.
- **Two static maps leaked for the life of the server.** A player's open menu session was never
  dropped, and their nonce replay cache retained up to 256 entries per actor with nothing ever
  removing them. Both are now released on logout.
- **Kidnapped villagers were never released.** Only *player* captives were ticked, so an NPC's
  real-time captivity cap never advanced at all: a kidnapping that was supposed to end within
  `maxCaptivityRealMinutes` instead lasted until somebody released it, and if the captor walked away
  and the chunk unloaded, it lasted forever as an invisible row in world data. NPC captivities now
  tick, and `npcCaptiveVirtualizeWhenUnloaded` decides what happens when the chunk is not loaded —
  keep counting, or end the captivity rather than leave a record nothing can reach.
- **Eleven settings were declared, validated, and read by nothing.** `pvpCountsAsCrime` had a comment
  in the gate saying it could never fire. `protectedEntities` and `responderEntities` were
  parse-checked by `/crime validate` and then consulted nowhere, so an operator could add a modded
  guard, be told the id was fine, and watch it witness nothing. `allowKillingRed` was
  indistinguishable from `redIsLegalTarget`. `enableBail` offered a bail that did not exist.
  `professionMatchingMode` documented three strategies over a hard-coded comparison.
  `enableCloseFriendTier` said outright that it folded into the fallback.
  `restitutionFractionOfFine` meant a one-emerald fine repaired exactly as much goodwill as a
  hundred-emerald one. `enableProfessionDeathDrops`, `globalCrimePropagation` and
  `npcCaptiveVirtualizeWhenUnloaded` did nothing at all. Each is now implemented, and
  `/crime validate` reports the combinations that leave a feature quietly switched off.
- **Three more static maps leaked for the life of the server** — a guard encounter, an enforcement
  alert, and a dossier cooldown stamp, all keyed by player and removed by nothing. All three are
  dropped on logout, along with the reaction controller and the dialogue cooldowns.
- **Client caches survived a disconnect**, so joining a second world showed the first world's Heat,
  band colours and captivity countdown until each was overwritten — and anything the new server never
  sent, because the player was clean there, was never overwritten at all.

### Changed

- World-data schema `4` → `5`, adding the observation and report sections. The migration creates them
  empty and deliberately does **not** synthesise observations from the witness identities already on
  old records: role, confidence and place were never recorded, and inventing them would drive AI
  behaviour and dialogue from evidence nobody gathered.
- World-data schema `5` → `6`, adding the holding-cell roster. It starts empty for the same class of
  reason: a cell record asserts that this mod placed every block inside that box and may therefore
  delete them, while every jail anchor in an existing world points at a structure somebody built by
  hand and registered with `/crime assignjail`. Seeding one from the other would mean the first
  release after this update demolished a player's jail.
- `chatFormatToggle` is removed rather than kept as a reserved key. A client cannot opt out of text
  the server has already formatted, so the setting could never do what its name promised;
  `chatNameColorEnabled` is now the only switch.
- Player-versus-player crime, when enabled, produces its own `assault_player` and `murder_player`
  types rather than borrowing the villager ones — a ledger row reading "assaulting a villager" for a
  fight between two players would be wrong on the dossier and wrong to any companion mod mapping
  crime types to incidents.
- Network protocol version `2` → `3` → `4` → `5`. A 0.3.0 client is rejected cleanly at handshake rather
  than decoding a half-matching action menu.
- `/crime payfine`, `surrender`, `payransom`, and `escape` are now adapters over the action engine
  rather than direct service calls. Behaviour is unchanged; what changed is that a command can no
  longer skip a check the menu applies.
- The captive-screen client option is no longer marked "Reserved".
- **The mod now ships one mixin**, which reverses the "no mixins" line this project has held since
  0.1.0. It is client-side only, listed in the mixin config's `client` array alone so a dedicated
  server never loads it, injects rather than overwrites, and does exactly one thing: pose a restrained
  player's arms behind their back. That pose is genuinely unreachable through Forge events —
  `PlayerRenderer.setModelProperties` and `HumanoidModel.setupAnim` both run after
  `RenderPlayerEvent.Pre` and overwrite whatever it wrote, and no `ArmPose` constant represents bound
  hands. Everything else added here, including the cuffs and the rope, still uses ordinary events.
- Network protocol `5` → `6`, adding the restraint sync pair. A client that cannot draw an arrest is
  now refused rather than left silently mismatched.

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

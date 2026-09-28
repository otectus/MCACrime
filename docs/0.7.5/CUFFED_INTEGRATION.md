# MCA: Crime 0.7.5 — the Cuffed integration, for readers

What came from Cuffed, how it fits the mod that absorbed it, and what was deliberately left behind.
The licence and artefact manifest is in [PROVENANCE.md](PROVENANCE.md); the feature-by-feature status
table is in [PARITY_LEDGER.md](PARITY_LEDGER.md); the plan this was built to is
`docs/MCA_Crime_0.7.5_Cuffed_Integration_Implementation_Plan.md`.

---

## What this release did

MCA: Crime absorbed the working core of the Cuffed feature set as native code. Not a dependency,
not a compatibility layer: the restraints, locks, lockpicking, transport, detention devices,
frisking, prison construction and enchantments are MCA: Crime's own code now, and neither Cuffed
nor Locks Reforged needs to be installed for any of it. A further slice was transferred and then
dropped again before release; see *What was dropped before release* below.

That replaced a real overlap. MCA: Crime already had a single-slot restraint enum, a capture channel,
a cuff-escape path, a kidnapping teleport tether, an NPC leash and wrist-only rendering. All of it is
gone, replaced by one engine. Two mods each half-simulating the same prisoner is how a prisoner ends
up half-free.

Everything **legal** stayed and was extended: cases, sentences, witnesses, bounties, ransom, property,
facilities, occupations, masks and the companion integrations.

One feature here is **not** from Cuffed at all: the capital sentence for killing a guard. The user
scoped it in; it is behind its own config group, and the only capital offence is killing a guard.

## The state model: physical is not legal

This is the single most important idea in the release, and the thing most likely to surprise somebody
who knows either mod on its own. There are **two independent state machines** with exactly one bridge
between them.

| | Physical | Legal |
|---|---|---|
| Answers | what is on the body, what it is tied to, what device holds it | who is in custody, under what authority, for which case, for how long |
| Owned by | `restraint/`, `tether/`, `detention/` | `captivity/`, `enforcement/`, `jail/`, `ransom/`, `bounty/` |
| Knows about the other | nothing about cases or sentences | nothing about gear |

`restraint/CustodyTransitionService` is the **only** class that turns a committed physical event into
a legal transition or back. It is the only place the physical side may call
`CustodyService.capture*`/`release`.

The consequences are enforced by tests rather than convention:

- Removing one restraint is not a release.
- Opening a cell door is not a pardon.
- Escaping a kidnapper files no jailbreak and costs the victim no Heat.
- A self-applied hood is not a kidnapping.
- Two successive captures of the same subject do not accept each other's packets.

### Where each fact lives

Exactly one durable owner per fact. No capability and no entity persistent-data blob carries an
authoritative copy of physical restraint state.

| State | Owner | Persisted in | Client copy |
|---|---|---|---|
| Worn restraints, per slot | `CrimeWorldData` table `physicalRestraints` | world, schema 15 | bounded snapshot, render-only |
| Chains, anchors, escorts | table `tethers` (+ a transient reverse index) | world | holder/anchor refs |
| Pillory, guillotine, bunk occupancy | table `detentions` | world | occupancy and pose |
| Lock identity and reinforcement | table `locks` | world | locked/reinforced booleans |
| A lockable block | the block entity stores **only** the lock id | chunk | block state |
| A key's binding | item NBT | stack | — |
| Struggle, lockpick and frisk sessions | `restraint/SessionRegistry`, server-only and capped | **nothing** | progress only |

Sessions are deliberately not persisted. A struggle in progress is a moment, not a fact about the
world, and a saved one would be a way to resume yesterday's escape.

## The item identity mapping

Three ids are not what an upstream reader expects, and all three are deliberate: MCA: Crime already
had these ids and their artwork, and repurposing or removing a registered id breaks existing worlds.

| Cuffed item | MCA: Crime id | Shown as |
|---|---|---|
| `handcuffs` | `mcacrime:restraint_locked_cuffs` | **Handcuffs** |
| `shackles` | `mcacrime:restraint_cuffs` | **Shackles** |
| `duck_tape` | `mcacrime:duck_tape` | **Duct Tape** |
| — | `mcacrime:restraint_rope` | a hidden legacy carrier |

No `mcacrime:handcuffs` or `mcacrime:shackles` item exists. Restraint **definition** ids are a
separate namespace and do use the Cuffed words (`handcuffs_arms`, `shackles_arms`, …).

`restraint_rope` stays registered but is hidden from the creative tab and normalises to Duct Tape at
controlled boundaries, with a lossless `rope_to_duck_tape` recipe so old rope in a chest is not
stranded. The two protected item textures are preserved byte for byte and are gated by a hash test.

## The packet contract

Built on the existing `CrimeNetwork`, `ServerPacketGuard`, `RequestBudget` and `PacketBounds`, with
the protocol bumped to `"15"`.

- Every C2S action packet carries **intent only**: a session id, a target handle, a slot, an input
  kind, an input sequence and a bounded scalar. No durability delta, no actor UUID, no outcome, no
  block position the server has not already bound to the session.
- **Identity comes from the connection.** `ServerPacketGuard.accept` resolves the sender; the packet
  never names who sent it.
- Every handler additionally validates session freshness, dimension, distance, line of sight where
  relevant, the held item, target generation and revision, menu ownership and the allowed action.
- S2C snapshots carry render and interaction data only — slot, definition id, durability fraction,
  device references, animation flags. Never a lock secret, never another player's inventory, never
  private case evidence. Only the authorised frisker receives searchable contents.
- Full state on tracking start, reconnect and respawn; deltas on change; explicit removal messages;
  revision-ordered.

The protocol bump is what makes an old client fail its handshake cleanly rather than decode a
multi-slot snapshot as the old single-enum packet.

## What was deliberately not transferred

- **Upstream's defects.** Around two dozen were confirmed in the upstream source and fixed at the
  point the behaviour moved: client-decided lockpick outcomes, client-supplied durability deltas, a
  mutable frisking container, Imbue recursion,
  a guillotine that could kill twice, chain duplication, a forged dispenser captor, UUIDs compared
  with `==`. The full list is in the changelog's *Fixed* section.
- **The humanoid model mixin.** Upstream mutates shared model parts and cancels `setupAnim`. MCA:
  Crime uses its existing renderer injection point instead.
- **A blanket command cancel.** Upstream cancels *every* command for a restrained non-operator. That
  is not ported; commands are never blanket-cancelled here.
- **`hang` in `is_explosion` and `is_drowning`.** Upstream puts its equivalent damage type in both. A
  chain is neither an explosion nor water, and those tags would make Blast Protection reduce it and
  every "is this an explosion?" listener in every other mod answer yes for a prisoner on a short
  lead. `mcacrime:hang` gets `bypasses_armor` and nothing else.
- **A second lock on someone else's block.** Where Locks Reforged already owns a target, MCA: Crime
  refuses rather than installing a competing access check. Two independent access checks on one chest
  is how a player gets locked out of their own container.
- **Unattributed art and audio.** Upstream declares GPL-3.0 in its LICENSE and README while its build
  declares All Rights Reserved, and it has no credits file. Everything covered by the GPL declaration
  is adapted with attribution; everything unattributed — the poster art, the fuzzy artwork, the six
  sounds — was replaced by original MCA: Crime work or by vanilla sound events, under the same
  registry ids, and the poster and fuzzy replacements were later dropped with their items. See
  [PROVENANCE.md](PROVENANCE.md).
- **Donor-world import.** Importing state from an existing Cuffed world is **out of scope for
  0.7.5**. There is no import tool. Cuffed installed alongside is detected by mod id only; MCA: Crime
  logs one warning and, optionally, stops applying new restraints. No foreign data is read, cleared
  or disabled either way.

## What was dropped before release

After the transfer was complete, twelve items and the systems that existed only for them were cut
before anything shipped. They are gone from the tree rather than retired, because none of them ever
reached a release:

- **Bandage and knife**, and with them the whole wound model (`wound/`, the `wounded` effect, the
  `blood_drip` particle, `[wounds]`).
- **Fork and spoon**, and with them reinforced-block excavation (`ExcavationService`, the crumbling
  marker entity, `prison.excavationChance`, `excavationStages`, `utensilNutritionBonus`).
- **Prisoner tag**, and with it the identity layer: nicknames, the viewer-specific
  `IdentityProjectionS2CPacket`, the `identities` world table, `/crime nickname`, `[identity]`, and
  the opt-in `/crime privacy` consent gate, which had shipped off by default.
- **Possessions box.** Frisking stays, reworked: it opens from the crime menu on a restrained subject
  (`action/handler/FriskActionHandler`), a lawful search still seals its seizures into the property
  escrow, and everything else goes into the searcher's own inventory and is filed as a theft. The
  three `boxSlots`/`boxMax*` keys and the `voluntary_transfer` kind went with the box.
- **Fuzzy handcuffs**, their definition, family, durability key, lockpick profile, statistics and
  worn model.
- **Meal tray, poster, warden's guide and toilet**, their block entities, models, art generators and
  loot tables.
- **Weighted anchor**, and with it the Buoyant enchantment and `transport.allowWeightedAnchors`.
  Fence and tripwire-hook anchors stay.

Keys, key rings, key molds, padlocks, the safe, the cell door and native lockpicking were kept
deliberately. The parity ledger marks each dropped row `dropped before release`.

## Where to go next

| Question | Document |
|---|---|
| What are the settings and what do they default to? | [CONFIG.md](../../CONFIG.md) |
| What happens to my existing world? | [docs/MIGRATION.md](../MIGRATION.md) |
| What can my mod read? | [API.md](../../API.md) |
| What can a datapack change? | [DATAPACK.md](../../DATAPACK.md) |
| What was actually tested? | [VERIFICATION.md](VERIFICATION.md) |
| Who wrote what? | [PROVENANCE.md](PROVENANCE.md), [CREDITS.md](../../CREDITS.md) |

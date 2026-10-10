# Apology access and feedback

The report described an unarmed player unable to apologize after hitting an NPC once.
The exact player's save and rejection message were not available, but the investigation
confirmed an access failure under the default configuration:

- `MemoryInteractionHandler` offered sneak + empty-main-hand reconciliation, but called
  `CrimeActionService.openMenu`, which rejected unarmed players unless the villager was a fence.
- MCA's Crime button also disabled itself while unarmed. Drawing a weapon opened the menu,
  but `ApologizeActionHandler` then required putting that weapon away.
- A fresh incident has a 1,200-tick settling period. It incorrectly shared the “no damaged
  relationship” message with missing history, disabled apologies, cooldowns and prior apologies.
- An active coercive session against the villager shared the “put the weapon away” message,
  even if the session belonged to somebody else and the apologizing player was unarmed.

The menu and button now allow peaceful options while unarmed. Coercive rows remain filtered
under the configured weapon requirement, and action submissions still validate the server's
offered rows and current weapon state. Refusals explain the empty-hand shortcut. Both hand
events remain consumed where necessary, while only the main-hand event sends a menu or hint.

`ApologyStatus` supplies the same eligibility decision to menu feedback and memory mutation.
It distinguishes missing history, the initial wait, the repeat-apology cooldown and an already
accepted apology. Disabled settings and active threats also have their own explanations.
Only weapons currently held count; a weapon stored elsewhere in inventory does not.

To apologize, put weapons away, wait 1,200 ticks (one minute at normal tick speed), then
sneak and right-click the villager with an empty main hand and select **Apologize**.
The MCA Crime button and optional menu keybind also work unarmed. Reopen a menu that was
opened during the wait. An apology softens anger and repairs at most one negative relationship
heart; it does not immediately erase fear or resolve legal charges. The existing severity,
decay, settling period, cooldown and one-benefit-per-incident rules remain in effect.

## Completed validation

- Forge release build: 1,090 JUnit tests passed, none skipped; packaging checks passed.
- NeoForge release build: 1,149 JUnit tests passed, one existing legacy-save fixture test skipped;
  packaging checks passed, with the optional Locks adapter required.
- NeoForge dedicated server with real MCA and Locks Reforged: all 23 required GameTests passed.
- Both release artifacts contain the new eligibility class and apology language entries.

`ApologyStatusTest` checks the exact one-minute boundary, offender identity, saved acceptance,
repeat-offense cooldowns, mixed memory eligibility and duplicate incident observations.
`CrimeButtonStateTest` checks that peaceful access survives the weapon and off-hand settings.

`RelationshipAndHeatGameTests` applies exactly one point of player-attributed damage to a real
MCA villager with empty hands and a sword elsewhere in inventory. It verifies one memory and
one charge, ordinary conversation refusal, the shortcut hint, the actual interaction event and
outbound menu, rejection of an unoffered mugging, and no duplicate off-hand menu. After the full
1,200-tick wait, it submits the apology through the issued menu, checks bounded heart repair,
same-nonce replay protection, remembered acceptance and the retained unresolved charge.
A second GameTest checks both held-weapon hands, another actor's threat and disabled apologies.

## Manual client check

Client rendering was not exercised. In either loader, hit an awake civilian once, put weapons
away and try ordinary conversation. Follow the shortcut hint, check the waiting tooltip,
then reopen after one minute and apologize. Also check MCA's Crime button and the optional
menu keybind while unarmed. Confirm that a resource pack can translate the new messages.

## 0.7.7: an accepted apology reopens conversation

A second report found that the apology worked and then changed nothing. `MemoryInteractionHandler`
refused ordinary conversation on fear and anger alone (fear at least 0.45 or anger at least 0.6) and
never read the apology flag, so after an accepted apology the next click was still cancelled, and the
refusal printed the hint telling the player to apologize. A single punch made it worse: it is severity
0.7, the apology rule treated 0.7 as severe, and the apology removed three percent of the anger and no
fear.

The refusal is now `memory/ConversationGate`, a pure rule over this villager's memories of this player:

- a memory blocks at the same thresholds as before;
- any blocking memory not yet apologized for refuses, as before, with the true status reason
  (`give_space`, `cooldown`, `disabled`, or the reason a usable gesture was refused);
- once every blocking memory has been apologized for, conversation resumes at once, unless one is
  grave: severity `VictimCrimeMemory.GRAVE_SEVERITY` (0.8) or higher, or more than two repeats;
- a grave memory speaks the new `memory_apologized` line and sends `mcacrime.apologize.still_wary` with
  the whole in-game days until it decays below the thresholds (`still_wary_indefinite` when the decay
  multiplier is 0).

The how-to hint is sent only when an apology is available and the click was not the apology gesture
(sneaking, or a full hand) in `CONTEXTUAL_DIRECT` mode; in `MENU_ONLY` mode the gesture does not exist and
the hint is not sent. "Grave" is one constant now, used by the residual floor and by the apology rule
alike, so an apology for one punch softens fifteen percent of the anger. Nothing is migrated: the gate
reads the apology flag every existing save already stores. Memories remain keyed by offender, so a
second player's click is never refused for somebody else's crime, and every villager line obeys
`dialogue.speechRadius` (16 blocks, same dimension), so nobody is told off from across the map. A click is
routed only when the villager is within the player's entity reach plus three blocks.

From 0.7.7 the first light hit on a villager is normally forgiven as minor friendly fire and leaves no
memory at all, so there is nothing to apologize for until a second hit. To reproduce the original
apology report, hit twice, or set `detection.minorHarm.enableMinorHarmForgiveness=false`.

MCA 7.7.x opens its interaction screen from `mobInteract`, behind this gate. MCA 7.6.x (7.6.20 and 7.6.26
in `mca_probe_versions`) opens it from `interactAt`, which a real client sends before the plain
interaction and which `MemoryInteractionHandler` does not handle, so with those builds a real right-click
reaches MCA's screen past the gate and the empty-hand gesture; the Crime menu still offers the apology.
This predates 0.7.7 and was found by bytecode inspection, not at runtime. The NeoForge port has the same
gap with every MCA 1.21.1 build in its `mca_probe_versions`.

Coverage: `ConversationGateTest` (the gate, the grave boundary, repeats, another player's memories, the
day count and the refusal message), `VictimCrimeMemoryTest` (the 0.7 and 0.8 boundaries and the calm-time
solver), and on NeoForge `RelationshipAndHeatGameTests`: after the accepted apology the ordinary click is
no longer cancelled and sends no refusal, a second player is never refused, and a listener forty blocks
away or in the Nether receives nothing. The one-punch GameTest turns the minor-harm grace off for its own
duration. Results are recorded in `docs/0.7.7/VALIDATION.md`.

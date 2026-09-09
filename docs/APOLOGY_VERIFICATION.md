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

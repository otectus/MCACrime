# Guard charge explanations

The reported output was `mcacrime.challenge.reason.wanted` after selecting **Ask to see the
charges**. The corresponding English entry exists in both previously built artifacts. The
message used `Component.translatable(key)` with no fallback, which renders the raw key when
the receiving client's language lookup cannot resolve it. Merely checking that `en_us.json`
contains the key does not exercise that failure mode.

`GuardChallengeText.detentionReason` now sends a translatable component with Minecraft's
native fallback field. The client still selects its own translation when available, including
resource-pack overrides. Otherwise it renders the accompanying English explanation. No
server-side `getString()` conversion freezes the component into one language.

Both the initial detention notice and **Ask to see the charges** use this path. It covers
Wanted Heat, resisting arrest, escaped custody, unlawful captivity and the no-charges response.
It preserves the legal reason's existing priority and the open response window.

## Regression coverage

`GuardChallengeTextTest` runs on both loaders and checks:

- Reproduction of the original raw-key message with an empty translation lookup, followed
  by the corrected explanation under that same lookup.
- Every legal basis and the empty/missing-offer paths, with fallback text checked against
  the packaged English translations.
- Client translations taking precedence and reloading between present and absent entries.
- JSON serialization retaining both the key and fallback, and correct rendering after decode.
- The existing priority when several detention reasons apply at once.

NeoForge's `RelationshipAndHeatGameTests` also exercises the real `ASK_CHARGES` response for
both a guard and an archer after command-set Heat. It checks the outgoing system-chat packet,
readable text in the dedicated server's language environment, and continued confrontation
without enabling force. These tests inspect components and packets; they do not render a client.

## Completed validation

- Forge release build: 1,084 tests passed, none skipped; packaging checks passed.
- NeoForge release build: 1,143 tests passed, one existing legacy-save fixture test skipped;
  packaging checks passed.
- NeoForge dedicated server with MCA and Locks Reforged: all 21 required GameTests passed,
  including the guard and archer charge-response assertions.
- Both release JARs were inspected for the version metadata, fallback implementation, calling
  service and English translation resource. Manual client rendering was not performed.

## Manual client check

With a clean ledger and an awake nearby guard, set Heat to 100 and select **Ask to see the
charges**. Expect the Wanted explanation rather than a key, and leave the encounter open.
Repeat with an archer and with a resource pack overriding the Wanted explanation; the override
should display. Also check a client lacking that key: the English fallback should remain readable.

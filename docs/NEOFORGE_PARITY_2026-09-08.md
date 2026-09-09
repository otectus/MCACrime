# NeoForge 1.21.1 parity — 2026-09-08

Subsequent 0.6.0 sleep and archer fixes are documented in [sleep verification](SLEEP_AWARENESS_VERIFICATION.md).
The artifact hash and test counts below describe the earlier parity build.

Reference: the current Forge MCA: Crime 0.6.0 working tree and its
[Remaining Work Review](REMAINING_WORK_REVIEW_2026-09-07.md). This brings the NeoForge port to the
same implemented feature scope; the review's explicitly open design work remains open.

## Included behavior

- One Heat/Wanted and Sentence/Captivity panel, using the bottom-left safe area, with old-default
  configuration migration, GUI-size fitting, and suppression while entering chat.
- At least 15 seconds to answer a guard, starting at the first acknowledged menu frame, with bounded
  delivery grace and no deadline extension on reopening, repeated acknowledgments or revised fines.
- Shared movement-speed limits, civilian reaction penalties, stable flee paths, guards exempt from
  fear/compliance, guarded conversation positioning, and stronger escort route selection and recovery.
- Generated cells reject living occupants throughout their footprint; repair moves trapped bystanders
  to safe outside positions while retaining the prisoner.
- Native Locks Reforged cuff minigame whenever Locks is installed. Inventory lockpick requirement is
  disabled by default and optionally enforced throughout an attempt. Closing or losing an attempt does
  not release custody; lawful escape retains the sentence and records jailbreak. Rope is unchanged.
- Witness perception, intimidation, victim memory, incident commits, final damage/death reconciliation,
  local guard justice, quoted settlements and persistent sentence membership.
- Confirmed stolen-property escrow, provider-bound bounty payments, partial delivery, and permission-level
  three recovery inspection, audit, export and explicit reconciliation commands.

## Native platform handling

The port retains NeoForge attachments and the legacy importer, named payloads and client routing,
registry-aware item components and world serialization, GUI layers, and Java 21 packaging. World
schema is 10 and packet protocol is 11. Existing identities remain unchanged. The protocol number
does not imply cross-loader network compatibility.

Damage reconciliation samples `LivingDamageEvent.Pre` and reads its final mutable damage container at
tick end; death cancellation remains observable until reconciliation. Player clone handling flushes
pending consequences before copying the final attachment data. Item escrow compares the 1.21.1 `count`
field and preserves the remaining components exactly.

Locks Reforged's NeoForge menu uses `ServerPlayer.openMenu`, its native menu writer and boolean
correct/reset payload. No lock is placed in the world. Locks classes occur only in the isolated
adapter and are never bundled. Release builds require the Locks, Reputation and Quests adapters.

## Validation

The full unit suite and packaging checks pass: 1,115 tests discovered, 1,114 passed, no failures or
errors. One existing test remains disabled because it requires an authentic captured Forge player
save that has not been supplied (`LegacyFixturePresenceTest`). Synthetic legacy import, current-world
migrations, and attachment tests run normally.

The parity audit found every current config key and translation key in the port. All baseline Java
features are present; the five Forge-specific capability/packet helper classes are replaced by the
port's attachments, native handlers and stream codecs.

All seven required server GameTests passed with MCA 7.7.36-beta.3 and Locks Reforged 1.8.0.
They cover attachment copying, saved-data persistence, cancelled observations, generated-cell
occupant avoidance, trapped-bystander repair, and the native cuff menu. The cuff test checks
inventory-only picks, removal of a required pick, itemless attempts, failed-pin reset, closing
without release, and release only after the final correct pin.

Server log: `gradle-MCACrime_1.21.1-runGameTestServer-20260908-012731.log`
(under the reference repository's `build/neo-parity`).

Release build passed with `-PrequireLocks=true -PrequireReputation=true -PrequireQuests=true`,
including the unit suite, MCA compatibility probes, and production-jar checks. Optional Locks
classes were absent from this unit-test runtime, exercising the no-Locks loading path separately
from the installed-Locks server run.

Build log: `gradle-MCACrime_1.21.1-build-20260908-013228.log` in the same log directory.

Reproduce through `C:\Projects\.mcmod-tools\gradlew-quiet.ps1`, using this repository as
`-Project`, `build` as `-Task`, and the three required-adapter flags as `-GradleArgs`. For the
server checks, use `runGameTestServer` and additionally pass
`-PlocksRuntimeJar="C:/Projects/1.21.1 Ports/Locks_Reforged_1.21.1/build/libs/locks-1.8.0.jar"`.

The final JAR was regenerated after comment cleanup and passed `checkJarContents` again
(log: `gradle-MCACrime_1.21.1-jar-20260908-013351.log`).

Artifact: `build/libs/mcacrime-0.6.0.jar` (1314378 bytes).
SHA-256: `f5d7ae5f2e5b687603c4029f724052d9131692b6dd623c442d112075d0ab24ce`.

## Remaining live checks

No interactive client was used for this pass. Check the HUD with the actual Quest Log mod, chat,
GUI scales and resource packs; assess navigation around real village terrain and distant jails.
The native cuff server menu is exercised with a recording connection; visual animation and mouse
interaction in Locks' client screen still need an in-game check. The existing review's global-Heat,
jurisdiction/force, and custody-identity design limitations are not claimed as resolved here.

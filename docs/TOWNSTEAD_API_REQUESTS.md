# API requests for Townstead

From the author of MCA: Crime, to the maintainer of Townstead
(`AetherianArtificer/Townstead`). Reviewed against Townstead 0.7.7, commit `4d6206cd`;
line references below are from that checkout.

## Context

MCA: Crime is an MCA Reborn companion mod (Forge 1.20.1 and NeoForge 1.21.1) that adds
crimes, witnesses, guard response, arrests and custody on top of MCA villagers. It cannot
statically link Townstead: Townstead's method descriptors carry MCA types whose package
differs between the Forge legacy, Forge modern and NeoForge builds, so naming a Townstead
type in Crime's bytecode would pin Crime to one MCA layout. Crime therefore reaches
Townstead through reflection plus five narrowly scoped mixins into Townstead classes
(`GuardRestEnforcerTicker`, `WorkToolTicker`, `ReactionLockTracker`, `StorageSearchContext`,
and the client `RpgDialogueScreen`).

The additions below would let those five mixins be retired and replace most of the
reflection with a supported surface. None of them require Townstead to know anything about
MCA: Crime.

## Design constraints we can work with

- Townstead-owned interfaces and records, with vanilla `LivingEntity` / `Entity` /
  `BlockPos` / `ItemStack` arguments. No MCA-typed parameters on anything Crime must call
  or implement, and no Crime types anywhere.
- Anything reachable through `api/TownsteadAPI.java` (static, vanilla-argument entry
  points) is ideal; Crime already binds that class reflectively.
- A version or contract number Crime can read once and branch on, so Crime can support
  older Townstead releases without probing individual members.

## Requests

### Retires a mixin

**1. `ProducerWorkTask.interruptForExternalActivity(reason, token)`** (plus the equivalent
on the work-task adapter). *Only request that closes a correctness gap Crime cannot close
on its own.*
- Exposes: a material-safe abort that releases station, tool and consumable claims and
  reconciles `stagedInputs` (`ai/work/producer/ProducerWorkTask.java:96`) and
  `pendingOutput` (`:85`), presumably via the existing `rollbackGather` contract (`:151`).
- Why: arresting a villager mid-recipe today either loses or duplicates staged ingredients;
  there is no external interrupt on the task.
- Today: Crime only refuses to *start* work, by wrapping `Activity.WORK` behaviours in the
  villager's brain. A recipe already committed runs to completion.

**2. `TownsteadExternalActivity` provider registration.**
- Exposes: a Townstead-owned interface Crime implements (vanilla `LivingEntity` argument,
  returning something like "this entity is under an external activity claim until tick N"),
  which Townstead's tickers consult.
- Why: one contract would replace all five of Crime's mixins into Townstead.
- Today: Crime mixes `GuardRestEnforcerTicker`, `WorkToolTicker` and `ReactionLockTracker`,
  and gates brain behaviours, to achieve the same effect less safely.

**3. `GuardRestEnforcerTicker` yields to a declared external activity.**
- Exposes: a check, before the rest enforcement path, that skips a villager under an
  external claim even when `MemoryModuleType.ATTACK_TARGET` is absent.
- Why: a lawful guard approach has a walk target but no attack target, so the ticker erases
  `WALK_TARGET` and `LOOK_TARGET` and stops navigation every tick
  (`tick/GuardRestEnforcerTicker.java:45-47`), while Crime re-asserts only on its scan
  interval.
- Today: Crime `@Redirect`s both `Brain.eraseMemory` calls and the `PathNavigation.stop()`
  call.

**4. `WorkToolTicker.provenanceOf(LivingEntity, EquipmentSlot)`.**
- Exposes: whether the held stack is physical inventory, an inventory mirror or a temporary
  display copy, and the source slot.
- Why: the ticker equips `tool.copy()` (`tick/WorkToolTicker.java:154`), so Crime's
  reference-identity ownership check (`loot/VillagerDeathLoot.java:47`) treats the display
  tool as extra loot and drops a duplicate.
- Today: Crime `@Redirect`s both `ItemStack.copy()` calls in `tick` by ordinal — the stash
  copy (`:151`) and the equipped copy (`:154`) — and injects at the head of `restore` and
  `forget`, mirroring the stash in its own state. Ordinal selection is exactly the fragility
  this request would remove.

### Reads that would replace reflection

**5. Persist the stashed main-hand stack.**
- Exposes: the pre-work hand item surviving chunk unload, world reload and death.
- Why: `PREVIOUS_MAIN_HAND` is an in-memory map, so the original is lost on restart and the
  villager keeps the work tool.
- Today: Crime mirrors the stash in its own state.

**6. `TownsteadAPI.buildingsAt(ServerLevel, BlockPos)`.**
- Exposes: every overlapping building, with completion flag, revision and recognition
  source.
- Why: `buildingAt` (`api/TownsteadAPI.java:149`) returns a single match in the nearest
  village and no revision, which is not enough to place cells clear of village structures.
- Today: Crime reads `village/TownsteadVillageSavedData` reflectively — `get` (`:107`),
  `getRecord` (`:209`), `VillageRecord.revision()` (`:67`), `buildings()` (`:75`),
  `BuildingOverlay` (`:86`).

**7. A building-change event (merge, split, delete, id reuse, tier change).**
- Exposes: invalidation for a stored building reference.
- Why: Crime persists facility assignments against a building id and must know when the id
  no longer means the same place.
- Today: Crime lazily compares `VillageRecord.revision()` and hopes it moved.

**8. Carry `mobile` / `needs` / `talkable` on the life-stage snapshot.**
- Exposes: the three server-side stage behaviour flags already on
  `root/LifeStage.java:51-53`.
- Why: Crime must not have a witness observe, testify or be arrested in a stage that cannot
  move, be talked to, or have needs.
- Today: Crime reflects the record accessors directly.

**9. A purpose-aware storage-access hook on `StorageSearchContext`.**
- Exposes: context (worker, task, source, purpose, operation id) alongside
  `isProtectedStorage(BlockPos, BlockState)` (`storage/StorageSearchContext.java:84`), and
  a way to add protected containers.
- Why: Crime needs evidence, recovery and reserved-facility containers excluded from work
  searches, and needs to tell authorised withdrawal from theft.
- Today: Crime `@Inject`s at the return of `isProtectedStorage` and forces `true` for a
  container it has marked protected from auto-sourcing (evidence storage, reserved jail-cell
  supplies), behind the opt-in `townstead.propertyLaw` switch. It cannot see who is searching
  or why, so authorised withdrawal is still told apart from theft only by Crime's own
  attribution, not by Townstead.

**10. `VillagerConsumptionManager` overloads taking vanilla `LivingEntity`.**
- Exposes: `startConsuming` (`hunger/VillagerConsumptionManager.java:55`, `:63`),
  `applyConsumption` (`:128`, `:134`) and `returnRemainder` (`:273`, `:283`) callable
  without an MCA type.
- Why: prisoners must still eat and drink while in custody, fed by Crime rather than by the
  villager's own work loop.
- Today: Crime calls them through erased `MethodHandle`s.

**11. A public `ReactionDispatcher` entry with an idempotency key and expiry.**
- Exposes: a filtered fire that de-duplicates, so a resolved case produces exactly one
  durable social effect.
- Why: a crime resolution can be reported by several witnesses in the same tick.
- Today: Crime calls `ReactionDispatcher.onTaskTransition(...)` (`:201`) reflectively, with
  its own de-duplication in front.

**12. A stable `TownsteadAPI.contractVersion()`.**
- Exposes: one integer (or string) Crime can read to decide which of the above exist.
- Why: it makes the handshake explicit and cheap, and keeps Crime working on older
  Townstead releases.
- Today: Crime infers support by probing individual members, which is fragile across point
  releases.

### Client

**13. `RpgDialogueScreen` action-extension registration and an explicit external-close
reason.**
- Exposes: a way to register an extra dialogue action (Crime would add a "Law" entry), and
  a close reason distinguishing external interruption from a normal close.
- Why: on external close Crime must cancel the queued reopen and restore camera and HUD
  exactly once.
- Today: Crime mixes `init()` (`client/gui/dialogue/RpgDialogueScreen.java:76`) and
  `removed()` (`:312`).

## Closing

Happy to review a branch, or to test any of these against MCA: Crime on both Forge 1.20.1
and NeoForge 1.21.1 before you tag a release. Everything above would be consumed by MCA:
Crime's reflective Townstead binding (`compat/townstead/`), so additions can land
incrementally and Crime will pick them up as they appear.

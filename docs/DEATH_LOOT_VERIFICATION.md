# Villager death loot verification — 0.6.0

Verified on 2026-09-08 in the Forge 1.20.1 baseline and NeoForge 1.21.1 port.

## Behavior

- MCA villagers, including guards and archers, leave their actual equipped gear. Copies retain
  names, enchantments, durability and other NBT/data components. Newly added equipment drops
  respect Curse of Vanishing (NeoForge: the prevent-equipment-drop enchantment effect).
- Adult villagers leave **one purchase worth of output per unlocked, non-exhausted offer**.
  An iron-axe trade drops one axe; a three-bread trade drops three bread. Remaining uses never
  multiply the output. Sold-out offers contribute nothing. Input costs and
  higher-level locked offers are not invented as loot. Children contribute no trade stock.
- Fences use the same deterministic goods selection and persisted usage record as their menu.
  Each available selling offer leaves one item. Death never restocks or rerolls an overdue
  record; dropped stock is exhausted and remains exhausted after NBT reload.
- All death causes qualify. Crime adds items through the loader's `LivingDropsEvent` collection,
  honors `doMobLoot`, and does not spawn its additions when death/drops are canceled.
- MCA still handles carried inventory. Equipment that references an inventory stack is skipped;
  matching normal equipment drops are also subtracted before Crime adds anything. Separately
  owned equipment remains separate even when its item data matches another item.

Configuration is in [CONFIG.md](../CONFIG.md), under `[loot]`: `dropEquipment = true`,
`dropTradeStock = true`, and `maxTradeDropStacks = 128` (range 1–4096). The limit bounds the number
of trade stacks per death, splitting at each item's actual maximum; excess is discarded. Gear
is outside that trade limit. These new defaults also apply when loading an existing config.
The old `mugging.enableProfessionDeathDrops` remains false and is only a legacy fallback when
actual trade stock is disabled; it cannot add a second profession reward alongside that stock.

## Why the equipment hook is needed

The inspected MCA `VillagerEntityMCA.die` implementations clear all equipment slots **before**
calling vanilla death processing, then drop MCA's carried inventory afterward. Consequently,
`LivingDeathEvent` is already too late to capture equipped gear. The common vanilla-targeted
`MobDeathEquipmentMixin` observes `Mob.setItemSlot` before a dying MCA villager's gear is cleared.
It does not cancel death, change the inventory, or link statically against MCA classes.

Capture is server-side, transient and weakly keyed by entity. It only records dead MCA mobs
whose equipment is being emptied, handles one loot callback per death, and clears saved capture
when an entity is alive again. MCA's equipment task uses inventory stack references, allowing
the capture to distinguish carried equipment from independent profession equipment.

The death ordering was inspected in Forge MCA 7.6.20, 7.7.0-beta.2 and 7.7.1-alpha.2, and
NeoForge MCA 7.7.36-beta.3. The Forge production refmap maps `setItemSlot` to `Mob.m_8061_`.
The client restraint-pose mixin retains its original class name and client-only registration.

## Automated verification

| Check | Result |
|---|---|
| Forge release build, Reputation and Quests required | Pass; 1,060 tests, zero failures/skips |
| NeoForge release build, Reputation, Quests and Locks required | Pass; 1,119 tests passed, one existing disabled fixture, zero failures |
| NeoForge dedicated GameTest server, MCA and Locks Reforged 1.7.5 loaded | All 16 required tests passed |
| Packaged loot classes, mixin registration, Forge method refmap, excluded GameTests | Pass |
| Companion/MCA classes not shaded into either release jar | Pass (`checkJarContents`) |
| Both working trees' whitespace checks | Pass |

Four new unit tests cover one-purchase drops, exhausted/invalid offers, large use limits without
output multiplication, and fence stock exhaustion after reload. Six new server tests execute actual
MCA deaths and check: exactly one iron axe and one three-bread bundle, sold-out trades and duplicate callbacks; inventory-backed versus separate
enchanted gear and vanishing equipment; `doMobLoot`; canceled death, revival and canceled drops;
config switches, stack limits and trade components; and persisted fence stock without restocking.
The ten prior server checks, including sleep awareness, jail parity and native cuff lockpicking,
also passed in the same run.

The Forge repository scanner reports its existing `CrimeKeybinds` event-bus false positive:
the nested `Registration` class handles MOD-bus key registration, while the enclosing class's
tick subscriber correctly uses the FORGE bus. This does not affect the passing Gradle checks.

## Scope and artifacts

The dedicated in-world tests ran on NeoForge; Forge received compilation, unit/probe tests,
production mapping checks and inspection of the actual supported MCA death methods. A manual
client playthrough and third-party corpse/grave mod combinations were not exercised. These
settings control Crime's additions; they do not override MCA's own inventory-drop behavior,
including how MCA handles canceled deaths or the mob-loot rule for that inventory.

No mod-version, network-protocol or world-schema increment was made for this follow-up.
These artifacts include the one-purchase drop correction and supersede the earlier death-loot jars:

| Build | File | Bytes | SHA-256 |
|---|---|---:|---|
| Forge 1.20.1 | `build/libs/mcacrime-0.6.0.jar` | 1,336,103 | `ecb24f35c7e30194ec5b378968e7a6679ca765c7a112f90959fc7a13999f539e` |
| NeoForge 1.21.1 | `build/libs/mcacrime-0.6.0.jar` | 1,327,632 | `be4c3f92809e5bbbb8bfd52c9fcc8f38efec755c85230da86dd3d0fec5e59538` |

# Phase 6 — Armed Interactions: In-World Verification Checklist

The classification rules themselves are unit-tested (`WeaponRulesTest`, `ConfigValidatorTest`): the
precedence order, `#tag` versus plain-id parsing, the blacklist beating the whitelist, the restraint
and digger exclusions, the damage threshold, and both gun heuristics.

What cannot be tested there is everything that needs an MCA villager and a real right-click. MCA's
Forge mixins are SRG-named with no refmap, so MCA only loads in a production-style instance — every
check below needs a real client and dedicated server with **MCA Reborn 7.6.x and the `mcacrime`
jar**.

---

## A. The trigger

- [ ] **A sword opens the menu.** Right-click an MCA villager holding an iron sword: the Crime menu
      opens, and MCA's own interaction screen does not.
- [ ] **A pickaxe does not.** The same right-click with a diamond pickaxe opens MCA's normal
      interaction screen and no Crime menu, despite the pickaxe's attack damage.
- [ ] **A blacklisted sword does not.** Add `minecraft:iron_sword` to `weapons.blacklist`, run
      `/reload`, and confirm the sword now opens MCA's screen instead.
- [ ] **A whitelisted stick does.** Add `minecraft:stick` to `weapons.whitelist`, reload, and confirm
      the menu opens with a stick in hand.
- [ ] **A bow does not start drawing.** Right-click a villager with a bow: the menu opens and the bow
      does not begin its draw animation on the client.
- [ ] **The off hand obeys its setting.** With `weaponTrigger.allowOffHand` off, a weapon in the off
      hand alone no longer opens the menu.
- [ ] **Sneak gating works.** With `weaponTrigger.requireSneak` on, only a sneaking right-click opens
      the menu.
- [ ] **The trigger switches off.** With `weaponTrigger.enabled` false, a sword right-click gifts the
      sword to the villager exactly as MCA intends.

## B. What must not have regressed

- [ ] **Restraints still capture.** Rope, cuffs and locked cuffs still begin a capture on right-click
      and never open the Crime menu.
- [ ] **The MCA screen button toggles.** With `client.showButtonOnMcaScreen` false the Crime button
      is absent from MCA's interaction screen; with it true the button is back and still works.
- [ ] **Every other way into the menu still works** — the button, `/crime` gameplay commands, and the
      self panel.

## C. Mugging and the keybind

- [ ] **Mug is blocked unarmed.** With `weapons.mugRequiresWeapon` on, open the menu (by keybind, so
      you can be empty-handed) and confirm the Mug row is visible, disabled, and explains that a
      weapon is needed. Drawing a sword and reopening makes it available.
- [ ] **The keybind opens the menu.** Bind "Open the crime menu…" to a free key, look at an MCA
      villager empty-handed, press it, and confirm the menu opens. Looking away, or at a non-villager,
      does nothing.
- [ ] **The keybind is still server-validated.** Pressing it while far from the villager, or with a
      wall between you, opens nothing.

## D. Diagnostics

- [ ] **`/crime debug weapon`** with an iron sword reports the item id, `MELEE`, the deciding layer,
      and the configured threshold. With a pickaxe it reports `NONE` and names the digging-tool
      exclusion. With a whitelisted stick it names the config whitelist.
- [ ] **`/crime validate`** reports a wildcard in `weapons.whitelist`, an unparseable item id, and an
      entry present on both lists — and reports nothing for the shipped defaults.
- [ ] **A reload takes effect.** Editing the lists and running `/reload` changes the answer
      `/crime debug weapon` gives without a restart.

## E. Modded weapons, if available

- [ ] **A gun mod's firearm opens the menu** and reports `GUN`, while that mod's ammo, crafting
      components and blocks report `NONE`.

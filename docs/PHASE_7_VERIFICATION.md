# Phase 7 — Criminal NPCs and Bounties: In-World Verification Checklist

The weapon classifier and Crime button are tested in Phase 6 (PHASE_6_VERIFICATION.md). This
checklist focuses on NPC-initiated crime, restraint rendering, compliance mechanics, criminal jobs,
fences, bounties, and server-only constraints that cannot be unit-tested without an MCA villager,
a real right-click, stolen goods persistence, or multi-client tracking.

**Every check below needs a real client and dedicated server with MCA Reborn 1.21.1 (the version
pinned in `gradle.properties`), the `mcacrime` jar, and preferably two clients (one as the player,
one observing).**

---

## A. Restraint visuals — pose and wrist binding

- [ ] **Late-joining client sees the pose.** Have player 1 restrain an NPC with rope, then have
      player 2 log in to the same world. Player 2 sees the restrained NPC with both arms behind
      its back and wrist bindings (rope tinted), not before login and not animated in realtime.
- [ ] **Two clients track the pose consistently.** Both players open the same world. Player 1
      captures an NPC with locked cuffs while player 2 watches. Player 2 sees the exact same pose
      and wrist styling on their screen at the same time.
- [ ] **Rope visual differs from cuffs.** Three restraints with the same pose: rope has a tan tint,
      cuffs are grey/metallic, locked cuffs are the same as cuffs. The geometry is identical.

---

## B. Threat compliance — freeze and arm-resistance

- [ ] **Unarmed civilians freeze when threatened.** Draw a weapon and target an unarmed adult
      villager (not a guard, archer, or combat-tagged NPC) with the Mug action. The villager
      freezes in place and does not flee or rotate, just watches. Release the mug (time out, draw
      another weapon, or use `/crime mug` to cancel) and the villager resumes normal behavior.
- [ ] **Speed is reduced during freeze.** While mugging an unarmed civilian, their movement speed
      is visibly slower when they do move (e.g. head/body animation but no step forward). Check
      `reactions.civilianCrimeReactionSpeedMultiplier` in the config (default 0.65).
- [ ] **Armed villagers resist.** Target an armed villager (guard, archer, or with a drawn weapon),
      mug them, and they immediately resist: attacking the player, fleeing, or yelling for help
      rather than freezing.
- [ ] **Weapon removal cancels mug.** While mugging, drop your weapon, switch to empty hand, or
      have the weapon knocked away. The HUD channel ends immediately with outcome
      `You drew a weapon — the thief backs off`.
- [ ] **Cancelling restores movement.** An NPC that was frozen mid-mug can move freely again once
      the mug ends.

---

## C. Crime button — positioning, gating, tooltips

- [ ] **Button is at the bottom.** Open MCA's villager interaction screen. The Crime button sits at
      the bottom, above any HUD elements, not at top-right. Check `client.crimeButtonAnchor`
      config (default BOTTOM).
- [ ] **Button disabled without weapon.** Open the Crime menu with an empty hand (via keybind or
      button on another screen). The Crime button is greyed out. Hold a sword and reopen to see it
      active.
- [ ] **Tooltip explains the gate.** Hover over the disabled Crime button (empty-handed) and read
      `Draw a weapon to commit a crime.` or `Hold a weapon in your main hand to commit a crime.`
      (depending on `weaponTrigger.allowOffHand`). Hover while armed and read nothing or a fence
      trading tooltip if the target is a fence.
- [ ] **Offhand rule is live.** Set `weaponTrigger.allowOffHand = false` in the config, run
      `/crime reload` (stay ingame), then hold a weapon in the off hand only. The Crime button is
      disabled. Put the weapon in the main hand and it activates. Reload a third time with offhand
      true and off-hand weapon reactivates it.
- [ ] **Fence button differs.** Target a fence villager (checked by the Crime system, shows
      `CriminalJob == FENCE`). The Crime button tooltip reads `Trade with this fence.` and is
      active without a weapon.

---

## D. Thief loop — scouting, HUD, weapon abort, guard intervention, arrest-to-release

- [ ] **Thieves spawn and scout.** Assign a thief with `/crime job assign thief <villager>`. Visit
      the village a few times over several minutes. Eventually a villager changes profession to
      Thief (if `criminalJobs.presentThiefAsMcaProfession = true`) and begins scouting the area,
      looking at players and moving around.
- [ ] **Mug HUD appears.** A thief targets you and begins mugging. A HUD channel bar appears at
      the bottom showing `A thief is mugging you` and `Draw a weapon to resist`. The bar fills
      toward completion ticks per second.
- [ ] **Drawing weapon aborts.** While the HUD is active, draw any weapon. The HUD immediately
      shows outcome `You drew a weapon — the thief backs off` and the bar stops. The thief enters
      flee state.
- [ ] **Guard sees threat and intervenes.** While a thief is mugging you (HUD active), a guard
      walks toward the thief and attacks. The mug cancels with outcome `A guard intervenes` and
      the guard pursues the thief.
- [ ] **Guard arrests the thief.** The thief is caught, handcuffed (wrist binding visible, arms
      behind back), and escorted toward the nearest jail or holding cell. You see chat message
      `[Thief name] has been arrested for mugging.` The thief is now in jail.
- [ ] **Jailed thief is released.** Wait for the jail sentence to expire (check config
      `criminalJobs.thief.thiefJailTicks`, default 12000 = 10 minutes online). The thief is
      released from the cell and returns to scouting. Chat shows release message.

---

## E. Theft — currency, single item, death recovery, no duplication

- [ ] **Thief steals currency first.** Let a thief mug you successfully (no weapon, no guard
      interruption, mug completes). Check your inventory purse change or money display. You lost
      some currency (emeralds or per `integrations.currencyId`). HUD shows outcome `stolen` with
      currency amount.
- [ ] **Fallback to one item when no currency.** Empty your purse or set purse to zero with a
      command. Let a new thief mug you. The thief takes exactly one item from your inventory
      (excluding hotbar and armor by default per config). HUD shows outcome with the item name.
- [ ] **Protected slots are immune.** Carry a crafting table in hotbar, a diamond helmet in armor,
      and a shield in off-hand. Let a thief mug you. The thief takes neither. Check config
      `criminalJobs.thief.protectHotbar`, `protectArmor`, `protectOffhand` (all true
      by default).
- [ ] **Enchanted item survives death.** Let a thief mug you for an enchanted sword (Sharpness,
      Unbreaking, etc.). Immediately die by fall damage or mob. Drop and pick up the item; the
      enchantments are still there. Save and reload the world. The enchantments persist.
- [ ] **Death drops recovered loot.** Die while stolen goods are recorded against you. The death
      loot drops include the stolen items. Pick them up and check inventory to confirm they're
      back. No duplication in output (dropping and reloading does not spawn duplicates).
- [ ] **Arrest returns stolen goods.** Let a thief steal a unique item from you. Get a guard to
      arrest the thief (have a witness, get Heat high enough for guards to respond, or manually
      assign a guard with a command). When the guard captures the thief, you see message like
      `A guard returned your diamond sword.` and the item appears in your inventory (or at your
      feet if full).

---

## F. Fence — pricing by karma/heat/wanted, Locks Reforged support

- [ ] **Fence job assignment.** `/crime job assign fence <villager>`. The nearest villager becomes
      a Fence (changes profession if `criminalJobs.presentFenceAsMcaProfession = true`).
- [ ] **Fence trades unarmed.** Target the fence, open the Crime menu with an empty hand. The
      Crime button is active (not disabled). Click Trade. A merchant screen opens with buy/sell
      offers. Complete a trade and the currency changes hands.
- [ ] **Pricing reflects karma.** Raise your karma to Lawful (above `bands.karmaBlueThreshold`,
      default +100) and trade with the fence. Buy prices are lower than a Neutral baseline. Lower
      karma to Outlaw (below `bands.karmaRedThreshold`, default −100) and return to trade. Buy
      prices are higher (surcharge). Check config `criminalJobs.fence.maxKarmaDiscount`
      (default 0.25, 25% discount for Lawful).
- [ ] **Pricing reflects Heat.** Get caught committing crimes so Heat rises. Trade with the fence
      at high Heat. Prices increase (surcharge per `criminalJobs.fence.maxHeatMarkup`, default
      0.35). Heat decays back to zero and prices drop again.
- [ ] **Pricing reflects Wanted.** Get Heat above `bands.wantedHeatThreshold` (default 50) so you
      are Wanted. Trade with the fence. Prices increase by the Wanted surcharge
      (`criminalJobs.fence.wantedMarkup`, default 0.20). Surrender to a guard to drop Heat,
      lose Wanted status, and prices return.
- [ ] **Fence stock rotates.** A fence's inventory is rebuilt once per configured day
      (`criminalJobs.fence.restockIntervalDays`, default 1). Visit a fence twice on the same
      day and see the same offers. Log out, advance the day with commands/sleeping, log in and
      visit again. Offers have changed.
- [ ] **(Without Locks Reforged on 1.21.1)** **No errors when absent.** Locks Reforged has no
      1.21.1 port. Trade with a fence. No `ClassNotFoundException`, no log errors. Fence offers
      only tag-driven contraband (TNT, fire charge, restraint items, etc. per
      `data/mcacrime/tags/item/illicit_goods.json`). `/crime reload` prints no Locks warnings.

---

## G. Bounty system — kill pays once, alive delivery, no suicide/environment payout

- [ ] **Bounty for killing a Wanted outlaw.** Get a villager or player to Wanted state (Heat ≥ 50,
      Wanted band). Check `bounty.enabled = true` (default). Kill the outlaw with a weapon
      (not fall damage, suffocation, or lava). You receive emeralds as payment and message `Bounty
      collected: X emeralds for Y.` Your karma also increases slightly per config
      `bounty.karmaReward`.
- [ ] **Bounty paid only once per warrant revision.** Kill the same Wanted outlaw twice in the
      same session. First kill pays bounty. Second kill (after respawn) pays nothing — a message
      explains "bounty already claimed for this warrant revision". Wait for the system to reduce
      their Heat so they are no longer Wanted, commit another qualifying crime, and become Wanted
      again (warrant revision bumped). Kill them a third time: bounty pays again because the
      warrant is a new revision.
- [ ] **Alive capture pays a multiplier.** Restrain a Wanted outlaw with rope/cuffs. Have a guard
      nearby and let the guard arrest them (citizen's arrest by capture + guard proximity). You
      receive emeralds per config `bounty.aliveCaptureMultiplier` (default 1.25× the kill amount).
      Message reads `Bounty collected (alive): X emeralds for Y.`
- [ ] **Suicide pays nothing.** Get Wanted. Build a fall damage trap and die in it. No bounty is
      paid. Check the log to confirm `BountyService` rejected it with source type SELF_DAMAGE or
      FALL.
- [ ] **Environment damage pays nothing.** Get Wanted. Die to lava, suffocation, or a mob (not
      player). No bounty is paid. The system attributes the kill to the environment and declines
      payout.

---

## H. Dedicated server — MCA 1.21.1, with/without optional companions

Run four separate server tests with the standard launch config (`./gradlew runServer`), each with
a dedicated server (no client loader code should reach the server-side log):

### H1: MCA 1.21.1 (the version pinned in `gradle.properties`) alone
- [ ] No `NoClassDefFoundError`, no `ClassNotFoundException` in logs.
- [ ] No errors from `compat/reputation`, `compat/mcaquests`, or `compat/locksreforged` packages.
- [ ] `/crime status` works and prints Karma/Heat.
- [ ] `/crime job assign thief` works; thief is assigned in `/crime debug thieves` output.
- [ ] `/crime validate` runs without crashing.

### H2: MCA 1.21.1 + `mcaquests-1.5.4.jar` (1.21.1 NeoForge build)
- [ ] No `NoClassDefFoundError`, no `ClassNotFoundException` in logs.
- [ ] `/crime debug integrations` reports `mcaquests=on` (or equivalent bridge status).
- [ ] Killing a Wanted outlaw still pays bounty (Crime's own system).
- [ ] Check MCA: Quests log for successful quest objective registration (search for
      `BountyObjective`).

### H3: MCA 1.21.1 alone (test that Locks absence degrades gracefully)
- [ ] No `ClassNotFoundException` in logs (Locks Reforged has no 1.21.1 port).
- [ ] `/crime debug integrations` reports `locksreforged=off` or `unavailable`.
- [ ] Fences can be assigned and traded with unarmed.
- [ ] Fence offers only tag-driven contraband, no lock/pick items.

### H4: Full test with both MCA: Quests 1.5.4 and both bounty and fence systems

Run the server with the mod, `/crime job assign thief`, `/crime job assign fence`, commit a theft,
let a thief mug a player, have guards arrest them, verify the thief is jailed and released, and
verify bounty payout on Wanted outlaw kill. All without crashes.

---

## Compatibility notes

- MCA Reborn 1.21.1 NeoForge build (the exact version pinned in `gradle.properties`) is required.
- MCA: Reputation 1.21.1 NeoForge build (if installed) degrades gracefully if absent.
- MCA: Quests 1.5.4 NeoForge build (if used) should be the 1.21.1 port from
  `C:\Projects\1.21.1 Ports\MCAQuests_1.21.1\build\libs\mcaquests-1.5.4.jar`.
- Locks Reforged has no 1.21.1 port and cannot be tested; the integration degrades gracefully.

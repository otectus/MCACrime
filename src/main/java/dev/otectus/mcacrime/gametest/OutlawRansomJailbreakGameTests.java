package dev.otectus.mcacrime.gametest;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.block.CrimeBlocks;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.CustodyReleaseReason;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.crime.KarmaSource;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.detect.CrimeDetector;
import dev.otectus.mcacrime.detect.DamageIncidentService;
import dev.otectus.mcacrime.economy.Currencies;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.enforcement.ArrestStates;
import dev.otectus.mcacrime.enforcement.OutlawResolver;
import dev.otectus.mcacrime.enforcement.OutlawStatus;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.jail.HoldingCell;
import dev.otectus.mcacrime.jail.HoldingCellService;
import dev.otectus.mcacrime.jail.JailAnchor;
import dev.otectus.mcacrime.jail.JailConfine;
import dev.otectus.mcacrime.jail.JailContainmentMode;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.jail.ReleaseReason;
import dev.otectus.mcacrime.justice.JusticeService;
import dev.otectus.mcacrime.justice.ThiefCombatDecision;
import dev.otectus.mcacrime.justice.ThiefCombatPolicy;
import dev.otectus.mcacrime.justice.ThiefCombatService;
import dev.otectus.mcacrime.ransom.RansomService;
import dev.otectus.mcacrime.restraint.AppliedRestraint;
import dev.otectus.mcacrime.restraint.PhysicalRestraintState;
import dev.otectus.mcacrime.restraint.RestraintDefinitions;
import dev.otectus.mcacrime.restraint.RestraintService;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import dev.otectus.mcacrime.state.CrimeAttachments;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.tether.TetherKind;
import dev.otectus.mcacrime.tether.TetherService;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runtime regressions for outlaw combat, ransom release and breaking out of a PHYSICAL jail (0.7.7),
 * against real MCA on a real server. The same scenarios the Forge tree's justice harness runs.
 *
 * <p>Crime records are a permanent ledger with no removal, so every test works on players and villagers
 * of its own and asserts only about them; every setting a test changes is put back in its
 * {@code finally}.
 */
@GameTestHolder(McaCrime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OutlawRansomJailbreakGameTests {

    private static final ResourceLocation OUTLAW = ResourceLocation.fromNamespaceAndPath("mca", "outlaw");

    private OutlawRansomJailbreakGameTests() {
    }

    // ------------------------------------------------------------------ outlaw combat

    /**
     * Fighting MCA's Outlaw is legal: a blow, an arrow and a kill in front of a witness file nothing, move
     * no Karma or Heat, cost the outlaw's hearts nothing and give a guard no basis; an outlaw in cuffs and
     * an ordinary villager hit the same way are charged; with the setting off the outlaw is protected.
     */
    @GameTest(template = "cell_parity", timeoutTicks = 100)
    public static void fightingMcasOutlawIsLegalEvenWatched(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        ServerPlayer attacker = player(helper, 7);
        AutoCloseable online = online(helper, attacker);
        Mob outlaw = villagerAt(helper, new BlockPos(6, 1, 5));
        Mob witness = villagerAt(helper, new BlockPos(6, 1, 8));
        Mob cuffed = villagerAt(helper, new BlockPos(3, 1, 5));
        Mob ordinary = villagerAt(helper, new BlockPos(9, 1, 3));
        Mob plain = villagerAt(helper, new BlockPos(3, 1, 9));
        Mob guard = villagerAt(helper, new BlockPos(9, 1, 9));
        ModConfigSpec.BooleanValue legal = McaCrimeConfig.COMMON.outlawCombatLegal;
        ModConfigSpec.BooleanValue forgiveness = McaCrimeConfig.COMMON.enableMinorHarmForgiveness;
        boolean legalBefore = legal.get();
        boolean forgivenessBefore = forgiveness.get();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        try {
            legal.set(true);
            forgiveness.set(false);
            for (Mob v : List.of(outlaw, cuffed, plain)) {
                helper.assertTrue(McaCompat.setVillagerProfession(v, OUTLAW), "The outlaw profession was not set");
            }
            helper.assertTrue(McaCompat.setVillagerProfession(guard, ResourceLocation.fromNamespaceAndPath("mca", "guard")),
                    "The guard fixture could not be made a guard");
            helper.assertTrue(McaCompat.isOutlaw(outlaw) && !McaCompat.isOutlaw(ordinary),
                    "MCA's Outlaw is not recognised by its profession");
            ThiefCombatDecision decision = ThiefCombatService.sample(outlaw, attacker, "damage");
            helper.assertTrue(decision != null && decision.reason() == ThiefCombatPolicy.Reason.OUTLAW
                    && decision.exemptsKill(), "Every policy should exempt an outlaw, killing included: " + decision);
            long karma = CrimeState.getKarma(attacker);
            long heat = CrimeState.getHeat(attacker);
            int outlawHearts = McaCompat.getHearts(attacker, outlaw);
            helper.assertTrue(outlaw.hurt(level.damageSources().playerAttack(attacker), 3F), "The outlaw took no blow");
            DamageIncidentService.flush(level.getServer());
            helper.assertTrue(McaCompat.getHearts(attacker, outlaw) == outlawHearts,
                    "MCA's own hurt hearts were charged for hitting an outlaw");
            outlaw.invulnerableTime = 0;
            Arrow arrow = new Arrow(level, attacker, new ItemStack(Items.ARROW), null);
            helper.assertTrue(outlaw.hurt(level.damageSources().arrow(arrow, attacker), 3F), "The outlaw took no arrow");
            DamageIncidentService.flush(level.getServer());
            int witnessHearts = McaCompat.getHearts(attacker, witness);
            outlaw.invulnerableTime = 0;
            outlaw.hurt(level.damageSources().playerAttack(attacker), 1000F);
            helper.assertTrue(outlaw.isDeadOrDying(), "The outlaw did not die");
            DamageIncidentService.flush(level.getServer());
            helper.assertTrue(data.recordsForOffender(attacker.getUUID()).isEmpty(),
                    "Fighting an outlaw was charged: " + data.recordsForOffender(attacker.getUUID()));
            helper.assertTrue(CrimeState.getKarma(attacker) == karma && CrimeState.getHeat(attacker) == heat,
                    "Fighting an outlaw moved Karma or Heat");
            helper.assertTrue(McaCompat.getHearts(attacker, witness) == witnessHearts,
                    "A witness to an outlaw's death lost hearts");
            helper.assertTrue(!JusticeService.forGuard(level, guard, attacker).mayChallenge(),
                    "A guard has a basis to halt the outlaw's killer");

            RestraintService.applySystemIssued(cuffed, RestraintDefinitions.HANDCUFFS_ARMS, RestraintSlot.ARMS,
                    AppliedRestraint.ApplicationContext.ADMINISTRATIVE, null);
            helper.assertTrue(cuffed.hurt(level.damageSources().playerAttack(attacker), 3F), "The cuffed outlaw took no blow");
            DamageIncidentService.flush(level.getServer());
            helper.assertTrue(data.recordsForOffender(attacker.getUUID()).size() == 1,
                    "Hitting an outlaw in cuffs was not charged");
            helper.assertTrue(ordinary.hurt(level.damageSources().playerAttack(attacker), 3F), "The villager took no blow");
            DamageIncidentService.flush(level.getServer());
            helper.assertTrue(data.recordsForOffender(attacker.getUUID()).size() == 2,
                    "Hitting an ordinary villager was not charged");
            legal.set(false);
            helper.assertTrue(plain.hurt(level.damageSources().playerAttack(attacker), 3F), "The outlaw took no blow");
            DamageIncidentService.flush(level.getServer());
            helper.assertTrue(data.recordsForOffender(attacker.getUUID()).size() == 3,
                    "With outlawCombatLegal off an outlaw is still fair game");
            helper.succeed();
        } finally {
            legal.set(legalBefore);
            forgiveness.set(forgivenessBefore);
            CrimeState.setHeat(attacker, 0);
            for (Mob v : List.of(outlaw, witness, cuffed, ordinary, plain, guard)) {
                v.discard();
            }
            close(online);
        }
    }

    /**
     * Fighting a Red-band player is legal with PvP crime on: a blow, an arrow and a kill file nothing, the
     * outlaw is neither hunted by the guards nor bounty-eligible, and a Grey player hit the same way is
     * still charged.
     */
    @GameTest(template = "cell_parity", timeoutTicks = 100)
    public static void fightingARedPlayerIsLegalWithPvpCrimeOn(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        ServerPlayer attacker = player(helper, 5);
        ServerPlayer outlaw = player(helper, 6);
        ServerPlayer citizen = player(helper, 8);
        AutoCloseable onlineA = online(helper, attacker);
        AutoCloseable onlineO = online(helper, outlaw);
        AutoCloseable onlineC = online(helper, citizen);
        Mob guard = villagerAt(helper, new BlockPos(9, 1, 9));
        ModConfigSpec.BooleanValue legal = McaCrimeConfig.COMMON.outlawCombatLegal;
        ModConfigSpec.BooleanValue pvp = McaCrimeConfig.COMMON.pvpCountsAsCrime;
        boolean legalBefore = legal.get();
        boolean pvpBefore = pvp.get();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        try {
            legal.set(true);
            pvp.set(true);
            // pvpCountsAsCrime is a world-rule-mapped setting, resolved once per tick.
            dev.otectus.mcacrime.config.CrimeWorldSettings.invalidate();
            McaCompat.setVillagerProfession(guard, ResourceLocation.fromNamespaceAndPath("mca", "guard"));
            CrimeState.setKarma(outlaw, -500L, KarmaSource.ADMIN);
            helper.assertTrue(CrimeState.getBand(outlaw) == Band.RED, "The outlaw fixture is not Red");
            OutlawStatus status = OutlawResolver.resolve(outlaw);
            helper.assertTrue(status.attackLawful() && status.killLawful(), "Anybody may not fight an outlaw: " + status);
            helper.assertTrue(!status.lawfulCombatTarget() && !status.bountyEligible(),
                    "An outlaw with redIsLegalTarget off is hunted or bounty-eligible: " + status);
            DamageSource hit = level.damageSources().playerAttack(attacker);
            DamageSource shot = level.damageSources().arrow(new Arrow(level, attacker, new ItemStack(Items.ARROW), null),
                    attacker);
            CrimeDetector.onHarm(outlaw, hit, 3F, level);
            CrimeDetector.onHarm(outlaw, shot, 3F, level);
            DamageIncidentService.flush(level.getServer());
            outlaw.setHealth(0F);
            CrimeDetector.onKill(outlaw, hit, level);
            DamageIncidentService.flush(level.getServer());
            helper.assertTrue(data.recordsForOffender(attacker.getUUID()).isEmpty(),
                    "Fighting an outlaw was charged: " + data.recordsForOffender(attacker.getUUID()));
            helper.assertTrue(CrimeState.getHeat(attacker) == 0L, "Fighting an outlaw cost Heat");
            helper.assertTrue(!JusticeService.forGuard(level, guard, attacker).mayChallenge(),
                    "A guard has a basis to halt the outlaw's killer");
            CrimeDetector.onHarm(citizen, hit, 3F, level);
            DamageIncidentService.flush(level.getServer());
            helper.assertTrue(data.recordsForOffender(attacker.getUUID()).size() == 1,
                    "Hitting a Grey player was not charged");
            helper.succeed();
        } finally {
            legal.set(legalBefore);
            pvp.set(pvpBefore);
            dev.otectus.mcacrime.config.CrimeWorldSettings.invalidate();
            CrimeState.setHeat(attacker, 0);
            guard.discard();
            close(onlineA);
            close(onlineO);
            close(onlineC);
        }
    }

    // ------------------------------------------------------------------ ransom

    /**
     * A paid ransom frees the captive's body: the cuffs that began the kidnapping and a gag added during it
     * come off with one item each, the chain is paid back once, and a repeated settlement pays nothing.
     */
    @GameTest(template = "cell_parity", timeoutTicks = 100)
    public static void aPaidRansomTakesOffTheKidnappingsGearAndSettlesOnce(GameTestHelper helper) throws Exception {
        floor(helper);
        ServerLevel level = helper.getLevel();
        ServerPlayer captor = player(helper, 5);
        AutoCloseable online = online(helper, captor);
        Mob victim = villagerAt(helper, new BlockPos(6, 1, 5));
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        // The village fallback pays from the overworld's unincorporated treasury, which lives in the
        // persisted GameTest world: start it at the configured balance and put it back afterwards, so a
        // run neither depends on nor drains what earlier runs left.
        Map<String, Long> treasuries = treasuries(data);
        Long treasuryBefore = treasuries.remove(UNINCORPORATED_TREASURY);
        try {
            captor.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(CrimeItems.RESTRAINT_CUFFS.get()));
            var cuffs = RestraintService.apply(captor, victim, InteractionHand.MAIN_HAND, RestraintSlot.ARMS,
                    AppliedRestraint.ApplicationContext.UNLAWFUL);
            helper.assertTrue(cuffs.applied(), "The cuffs were not applied: " + cuffs.refusal());
            CustodyRecord custody = data.getCustody(victim.getUUID());
            helper.assertTrue(custody != null && !custody.isLawful(), "The cuffs did not begin a kidnapping");
            helper.assertTrue(custody.getCustodyId().equals(slot(data, victim, RestraintSlot.ARMS).custodyId()),
                    "The kidnapping did not adopt the cuffs that began it");
            captor.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(CrimeItems.DUCK_TAPE.get()));
            var gag = RestraintService.apply(captor, victim, InteractionHand.MAIN_HAND, RestraintSlot.HEAD,
                    AppliedRestraint.ApplicationContext.UNLAWFUL);
            helper.assertTrue(gag.applied(), "The gag was not applied: " + gag.refusal());
            helper.assertTrue(custody.getCustodyId().equals(slot(data, victim, RestraintSlot.HEAD).custodyId()),
                    "The gag added during the kidnapping does not belong to it");
            helper.assertTrue(TetherService.attach(data, victim, captor, TetherKind.CHAIN, captor.getUUID(), true).isPresent(),
                    "The victim could not be chained to the captor");
            long owed = data.physicalRestraint(victim.getUUID()).slots().values().stream()
                    .filter(w -> w.provenance() == AppliedRestraint.Provenance.PLAYER_OWNED
                            && w.returnPolicy() != AppliedRestraint.ReturnPolicy.NONE).count();
            int chains = carried(captor, Items.CHAIN);
            int drops = itemsNear(level, victim.blockPosition());
            long before = Currencies.active().balance(captor);
            helper.assertTrue(RansomService.demandFor(captor, victim.getUUID()) == 1, "The village fallback did not settle");
            helper.assertTrue(data.getCustody(victim.getUUID()) == null, "The captive is still in custody");
            PhysicalRestraintState after = data.physicalRestraint(victim.getUUID());
            helper.assertTrue(after == null || !after.restrained(), "A restraint was left on the ransomed villager: " + after);
            helper.assertTrue(TetherService.forSubject(data, victim.getUUID()).isEmpty(), "A chain was left on them");
            helper.assertTrue(carried(captor, Items.CHAIN) == chains + 1, "The chain was not paid back exactly once");
            helper.assertTrue(itemsNear(level, victim.blockPosition()) == drops + owed,
                    "Each supplied restraint did not drop exactly once (" + owed + " owed)");
            long settled = Currencies.active().balance(captor);
            helper.assertTrue(settled > before, "The captor was not paid");
            helper.assertTrue(RansomService.demandFor(captor, victim.getUUID()) == 0, "A repeated settlement was accepted");
            helper.assertTrue(Currencies.active().balance(captor) == settled
                    && itemsNear(level, victim.blockPosition()) == drops + owed, "A repeated settlement moved money or items");
            helper.succeed();
        } finally {
            if (treasuryBefore == null) {
                treasuries.remove(UNINCORPORATED_TREASURY);
            } else {
                treasuries.put(UNINCORPORATED_TREASURY, treasuryBefore);
            }
            CustodyService.release(level.getServer(), victim.getUUID(), CustodyReleaseReason.ADMIN);
            CrimeState.setHeat(captor, 0);
            victim.discard();
            close(online);
        }
    }

    /** An unaffordable village ransom is refused, and the captive stays held, restrained and unpaid for. */
    @GameTest(template = "cell_parity", timeoutTicks = 100)
    public static void aFailedRansomPaymentFreesNobody(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        ServerPlayer captor = player(helper, 5);
        AutoCloseable online = online(helper, captor);
        Mob victim = villagerAt(helper, new BlockPos(6, 1, 5));
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        ModConfigSpec.IntValue base = McaCrimeConfig.COMMON.ransomBaseAmount;
        int baseBefore = base.get();
        try {
            captor.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(CrimeItems.RESTRAINT_CUFFS.get()));
            helper.assertTrue(RestraintService.apply(captor, victim, InteractionHand.MAIN_HAND, RestraintSlot.ARMS,
                    AppliedRestraint.ApplicationContext.UNLAWFUL).applied(), "The cuffs were not applied");
            long unpaid = Currencies.active().balance(captor);
            base.set(1_000_000);
            helper.assertTrue(RansomService.demandFor(captor, victim.getUUID()) == 0, "An unaffordable ransom settled");
            helper.assertTrue(data.getCustody(victim.getUUID()) != null, "A failed payment freed the captive");
            helper.assertTrue(data.physicalRestraint(victim.getUUID()).restrained(), "A failed payment took the cuffs off");
            helper.assertTrue(Currencies.active().balance(captor) == unpaid, "A failed payment paid the captor");
            base.set(baseBefore);
            CustodyService.release(level.getServer(), victim.getUUID(), CustodyReleaseReason.ADMIN);
            PhysicalRestraintState released = data.physicalRestraint(victim.getUUID());
            helper.assertTrue(released == null || !released.restrained(),
                    "An operator's release left the kidnapping's cuffs on");
            helper.succeed();
        } finally {
            base.set(baseBefore);
            CustodyService.release(level.getServer(), victim.getUUID(), CustodyReleaseReason.ADMIN);
            CrimeState.setHeat(captor, 0);
            victim.discard();
            close(online);
        }
    }

    // ------------------------------------------------------------------ PHYSICAL jail

    /**
     * jailContainmentMode = PHYSICAL lets a prisoner already serving dig out of an assigned jail; walking
     * out is one jailbreak with no teleport back, and switching back to CONTAINMENT does not pull them in.
     */
    @GameTest(template = "cell_parity", timeoutTicks = 100)
    public static void physicalLetsAServingPrisonerDigOutOfAnAssignedJail(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        for (int x = 4; x <= 8; x++) for (int z = 4; z <= 8; z++) for (int y = 1; y <= 3; y++) {
            boolean shell = y == 3 || x == 4 || x == 8 || z == 4 || z == 8;
            helper.setBlock(new BlockPos(x, y, z), shell ? Blocks.STONE : Blocks.AIR);
        }
        ServerPlayer prisoner = player(helper, 6);
        AutoCloseable online = online(helper, prisoner);
        ModConfigSpec.EnumValue<JailContainmentMode> mode = McaCrimeConfig.COMMON.jailContainmentMode;
        JailContainmentMode modeBefore = mode.get();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        try {
            mode.set(JailContainmentMode.CONTAINMENT);
            JailAnchor anchor = new JailAnchor(helper.absolutePos(new BlockPos(6, 1, 6)), level.dimension().location(), 2);
            helper.assertTrue(JailService.jail(prisoner, 600, anchor), "The prisoner could not be jailed");
            BlockPos wall = helper.absolutePos(new BlockPos(8, 1, 6));
            helper.assertTrue(!prisoner.gameMode.destroyBlock(wall) && level.getBlockState(wall).is(Blocks.STONE),
                    "CONTAINMENT let the prisoner break a wall");
            mode.set(JailContainmentMode.PHYSICAL);
            helper.assertTrue(prisoner.gameMode.destroyBlock(wall) && level.getBlockState(wall).isAir(),
                    "Switching to PHYSICAL did not let a prisoner already serving dig");
            long charged = jailbreaks(data, prisoner);
            PlayerCrimeData crime = CrimeAttachments.get(prisoner);
            BlockPos out = helper.absolutePos(new BlockPos(11, 1, 6));
            prisoner.teleportTo(level, out.getX() + .5, out.getY(), out.getZ() + .5, Set.of(), 0F, 0F);
            JailConfine.tick(prisoner, crime);
            helper.assertTrue(crime.getJail().isEscaped(), "Walking out of a PHYSICAL jail was not an escape");
            helper.assertTrue(jailbreaks(data, prisoner) == charged + 1, "The escape was not exactly one jailbreak");
            helper.assertTrue(prisoner.blockPosition().equals(out), "The escaped prisoner was teleported back");
            JailConfine.tick(prisoner, crime);
            helper.assertTrue(jailbreaks(data, prisoner) == charged + 1, "The escape was charged twice");
            mode.set(JailContainmentMode.CONTAINMENT);
            JailConfine.tick(prisoner, crime);
            helper.assertTrue(prisoner.blockPosition().equals(out) && crime.getJail().isEscaped(),
                    "Switching back to CONTAINMENT pulled the escapee in");
            helper.succeed();
        } finally {
            mode.set(modeBefore);
            if (JailService.isJailed(prisoner)) {
                JailService.release(prisoner, ReleaseReason.ADMIN);
            }
            CrimeState.setHeat(prisoner, 0);
            ArrestStates.clear(prisoner);
            close(online);
        }
    }

    /**
     * Digging out of a built holding cell under PHYSICAL: its reinforced stone drops nothing even to a
     * diamond pickaxe, HARD_CONTAINMENT still holds a reinforced wall, and the escape takes the cell down
     * and restores the dug-out floor rather than leaving a hole.
     */
    @GameTest(template = "cell_parity", timeoutTicks = 100)
    public static void aBuiltCellDugOutUnderPhysicalDropsNothingAndRestoresTheHole(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        ServerPlayer prisoner = player(helper, 6);
        AutoCloseable online = online(helper, prisoner);
        ModConfigSpec.EnumValue<JailContainmentMode> mode = McaCrimeConfig.COMMON.jailContainmentMode;
        ModConfigSpec.ConfigValue<String> policy = McaCrimeConfig.COMMON.reinforcedBreakingPolicy;
        JailContainmentMode modeBefore = mode.get();
        String policyBefore = policy.get();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        UUID sentence = UUID.randomUUID();
        try {
            mode.set(JailContainmentMode.PHYSICAL);
            policy.set("PICKAXE_QUALIFIED");
            HoldingCell cell = HoldingCellService.provision(level, helper.absolutePos(new BlockPos(6, 1, 6)),
                    prisoner.getUUID(), sentence);
            helper.assertTrue(cell != null, "No holding cell was built");
            helper.assertTrue(JailService.jail(prisoner, 600, cell.toAnchor(), sentence, false),
                    "The prisoner could not be jailed in the built cell");
            PlayerCrimeData crime = CrimeAttachments.get(prisoner);
            helper.assertTrue(crime.getJail().isTemporaryCell(), "The sentence does not know its cell is temporary");
            var stone = CrimeBlocks.REINFORCED_STONE.get();
            BlockPos floorPos = null;
            BlockPos pillar = null;
            for (Map.Entry<BlockPos, BlockState> placed : cell.placed().entrySet()) {
                if (!placed.getValue().is(stone)) {
                    continue;
                }
                if (placed.getKey().getY() < cell.anchor().getY() && floorPos == null) {
                    floorPos = placed.getKey();
                } else if (placed.getKey().getY() >= cell.anchor().getY() && pillar == null) {
                    pillar = placed.getKey();
                }
            }
            helper.assertTrue(floorPos != null && pillar != null, "The cell has no reinforced floor or wall");
            BlockState original = cell.replaced().get(floorPos);
            prisoner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
            int drops = itemsNear(level, floorPos);
            helper.assertTrue(prisoner.gameMode.destroyBlock(floorPos) && level.getBlockState(floorPos).isAir(),
                    "PHYSICAL did not let the prisoner break the cell floor");
            helper.assertTrue(itemsNear(level, floorPos) == drops,
                    "A built cell's reinforced stone dropped as an item");
            policy.set("HARD_CONTAINMENT");
            helper.assertTrue(!prisoner.gameMode.destroyBlock(pillar) && level.getBlockState(pillar).is(stone),
                    "HARD_CONTAINMENT let a prisoner break a reinforced wall");
            policy.set("PICKAXE_QUALIFIED");
            long charged = jailbreaks(data, prisoner);
            BlockPos out = cell.outsideStand();
            prisoner.teleportTo(level, out.getX() + .5, out.getY(), out.getZ() + .5, Set.of(), 0F, 0F);
            JailConfine.tick(prisoner, crime);
            helper.assertTrue(crime.getJail().isEscaped() && jailbreaks(data, prisoner) == charged + 1,
                    "Leaving the dug-out cell was not exactly one jailbreak");
            helper.assertTrue(prisoner.blockPosition().equals(out), "The escaped prisoner was teleported back");
            helper.assertTrue(HoldingCellService.existingFor(level.getServer(), prisoner.getUUID()) == null,
                    "The cell did not come down");
            helper.assertTrue(level.getBlockState(floorPos).equals(original),
                    "The dug-out floor was left " + level.getBlockState(floorPos) + " instead of " + original);
            helper.succeed();
        } finally {
            mode.set(modeBefore);
            policy.set(policyBefore);
            if (JailService.isJailed(prisoner)) {
                JailService.release(prisoner, ReleaseReason.ADMIN);
            }
            HoldingCellService.dismantle(level.getServer(), prisoner.getUUID());
            CrimeState.setHeat(prisoner, 0);
            ArrestStates.clear(prisoner);
            close(online);
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static AppliedRestraint slot(CrimeWorldData data, Mob subject, RestraintSlot slot) {
        return data.physicalRestraint(subject.getUUID()).slot(slot).orElseThrow();
    }

    private static long jailbreaks(CrimeWorldData data, ServerPlayer offender) {
        return data.recordsForOffender(offender.getUUID()).stream()
                .filter(record -> record.type().equals(CrimeIds.JAILBREAK)).count();
    }

    private static int itemsNear(ServerLevel level, BlockPos at) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(3)).size();
    }

    private static int carried(ServerPlayer player, Item item) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 13; x++) {
            for (int z = 0; z < 13; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Mob villagerAt(GameTestHelper helper, BlockPos at) {
        EntityType<? extends Mob> type = (EntityType<? extends Mob>) BuiltInRegistries.ENTITY_TYPE
                .getOptional(ResourceLocation.fromNamespaceAndPath("mca", "male_villager")).orElseThrow();
        Mob mob = helper.spawn(type, at);
        ((Villager) mob).setAge(0);
        mob.setNoAi(true);
        mob.setOnGround(true);
        helper.assertTrue(McaCompat.isMcaVillager(mob), "The fixture is not an MCA villager");
        return mob;
    }

    /**
     * A server player built rather than logged in, as the other 0.7.7 GameTests make one: logging in
     * would have MCA open its destiny screen on a connection this player does not have.
     */
    private static ServerPlayer player(GameTestHelper helper, int x) {
        ServerLevel level = helper.getLevel();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "crime-077-outlaw");
        ServerPlayer player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        List<Packet<?>> sent = new ArrayList<>();
        player.connection = new ServerGamePacketListenerImpl(level.getServer(), connection,
                player, CommonListenerCookie.createInitial(profile, false)) {
            @Override public void send(Packet<?> packet) { sent.add(packet); }
            @Override public void send(Packet<?> packet, PacketSendListener listener) { sent.add(packet); }
        };
        BlockPos pos = helper.absolutePos(new BlockPos(x, 1, 6));
        player.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    /** The treasury key a ransom of a captive held in the overworld outside any village pays from. */
    private static final String UNINCORPORATED_TREASURY = "minecraft:overworld:unincorporated";

    /** The world's treasury balances; only the ransom fixture touches them, and puts back what it found. */
    @SuppressWarnings("unchecked")
    private static Map<String, Long> treasuries(CrimeWorldData data) throws ReflectiveOperationException {
        java.lang.reflect.Field field = CrimeWorldData.class.getDeclaredField("villageTreasuries");
        field.setAccessible(true);
        return (Map<String, Long>) field.get(data);
    }

    /** Only the fixture touches the login indexes; getPlayers() is an unmodifiable view in NeoForge. */
    @SuppressWarnings("unchecked")
    private static AutoCloseable online(GameTestHelper helper, ServerPlayer player) {
        try {
            PlayerList playerList = helper.getLevel().getServer().getPlayerList();
            var listField = PlayerList.class.getDeclaredField("players");
            var mapField = PlayerList.class.getDeclaredField("playersByUUID");
            listField.setAccessible(true);
            mapField.setAccessible(true);
            List<ServerPlayer> players = (List<ServerPlayer>) listField.get(playerList);
            Map<UUID, ServerPlayer> byId = (Map<UUID, ServerPlayer>) mapField.get(playerList);
            players.add(player);
            byId.put(player.getUUID(), player);
            return () -> {
                byId.remove(player.getUUID(), player);
                players.remove(player);
            };
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void close(AutoCloseable online) {
        try {
            online.close();
        } catch (Exception ignored) {
            // only the fixture's own bookkeeping
        }
    }
}

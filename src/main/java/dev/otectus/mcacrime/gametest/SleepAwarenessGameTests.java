package dev.otectus.mcacrime.gametest;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.ai.*;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.type.CrimeAwareness;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.detect.*;
import dev.otectus.mcacrime.enforcement.GuardChallengeService;
import dev.otectus.mcacrime.memory.ObservationService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

/** Real MCA entities exercise sleep across perception, crime controllers and the native combat brain. */
@GameTestHolder(McaCrime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SleepAwarenessGameTests {
    private SleepAwarenessGameTests() {}

    @SuppressWarnings("unchecked")
    private static Mob sleeper(GameTestHelper helper, boolean archer, boolean ai) {
        var type = (EntityType<? extends Mob>) BuiltInRegistries.ENTITY_TYPE.getOptional(
                ResourceLocation.fromNamespaceAndPath("mca", "male_villager")).orElseThrow();
        var mob = helper.spawn(type, new BlockPos(4, 1, 4));
        ((Villager) mob).setAge(0);
        mob.setNoAi(!ai);
        helper.assertTrue(helper.getLevel().getEntity(mob.getUUID()) == mob, "MCA villager missing from level");
        if (archer) {
            helper.assertTrue(McaCompat.setVillagerProfession(mob, ResourceLocation.fromNamespaceAndPath("mca", "archer")),
                    "Cannot assign MCA archer profession");
            helper.assertTrue(McaCompat.isArcher(mob) && EntitySelectors.isResponder(mob), "Archer did not have a law role");
            mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        }
        helper.setBlock(new BlockPos(4, 1, 4), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD));
        helper.setBlock(new BlockPos(4, 1, 5), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.FOOT));
        helper.getLevel().setDayTime(18000);
        mob.getBrain().setMemory(MemoryModuleType.HOME,
                net.minecraft.core.GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(4, 1, 4))));
        mob.getBrain().setActiveActivityIfPossible(net.minecraft.world.entity.schedule.Activity.REST);
        mob.startSleeping(helper.absolutePos(new BlockPos(4, 1, 4)));
        helper.assertTrue(mob.isSleeping(), "Fixture never entered sleep");
        return mob;
    }

    @GameTest(template = "cell_parity")
    public static void sleepersNeverBecomeWitnessesOrDirectVictimObservers(GameTestHelper helper) {
        var level = helper.getLevel();
        var sleeping = sleeper(helper, false, false);
        var actor = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(6, 1, 4));
        var victim = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(6, 1, 5));
        var awareness = CrimeAwareness.defaults(CrimeIds.HARM_VILLAGER);
        helper.assertTrue(!WitnessChecker.perceive(sleeping, actor, victim, awareness).aware(), "Sleeping villager perceived a loud nearby act");
        helper.assertTrue(!WitnessChecker.resolve(level, victim).witnessIds().contains(sleeping.getUUID()), "Legacy witness scan counted a sleeper");
        var prior = McaCrimeConfig.COMMON.enableWitnessSystem.get();
        try {
            McaCrimeConfig.COMMON.enableWitnessSystem.set(false);
            helper.assertTrue(!WitnessChecker.resolve(level, actor, victim, awareness).witnessIds().contains(sleeping.getUUID()),
                    "Disabling advanced perception re-enabled sleeping witnesses");
        } finally { McaCrimeConfig.COMMON.enableWitnessSystem.set(prior); }
        // Supplied witness IDs cannot bypass the runtime sleep gate, including through the public API.
        var stale = WitnessResult.of(java.util.Set.of(sleeping.getUUID()), 1, 1);
        var observations = ObservationService.record(level, actor, sleeping, CrimeIds.HARM_VILLAGER,
                UUID.randomUUID(), stale, awareness);
        helper.assertTrue(observations.stream().noneMatch(o -> o.observerId().equals(sleeping.getUUID())),
                "Sleeping victim acquired a direct/hearing/stale observation");
        helper.assertTrue(CrimeReactionService.trigger(level, sleeping, actor.getUUID(), VictimReactionState.FLEEING, null) == null,
                "Sleeping civilian acquired a flee controller");
        sleeping.stopSleeping();
        helper.assertTrue(WitnessChecker.perceive(sleeping, actor, victim, awareness).heardAct(), "Waking did not restore perception");
        helper.assertTrue(CrimeReactionService.trigger(level, sleeping, actor.getUUID(), VictimReactionState.FLEEING, null) != null,
                "Awake civilian could not start a reaction");
        sleeping.startSleeping(helper.absolutePos(new BlockPos(4, 1, 4)));
        CrimeReactionService.tick(level.getServer());
        helper.assertTrue(CrimeReactionService.stateOf(sleeping.getUUID()) == VictimReactionState.CALM,
                "Falling asleep left an existing flee controller active");
        sleeping.discard(); actor.discard(); victim.discard();
        helper.succeed();
    }

    @GameTest(template = "cell_parity", timeoutTicks = 100)
    public static void sleepingArcherDropsOldOrdersWithoutWakingOrFiring(GameTestHelper helper) {
        var guard = sleeper(helper, true, true);
        var target = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(8, 1, 4));
        var start = guard.position();
        helper.assertTrue(!EntitySelectors.isAvailableResponder(guard) && EntitySelectors.isResponder(guard),
                "Sleep changed the guard's identity or left them available");
        helper.assertTrue(!McaCompat.setGuardTarget(guard, target), "Sleeping guard accepted a combat order");
        helper.assertTrue(!McaCompat.moveVillagerTo(guard, target.getX(), target.getY(), target.getZ(), 1),
                "Sleeping guard accepted navigation");
        helper.assertTrue(!GuardChallengeService.challenge(helper.getLevel(), guard, FakePlayerFactory.getMinecraft(helper.getLevel())),
                "Sleeping guard opened a challenge");
        // Simulate orders saved before the fix, including an archer already drawing a bow.
        guard.setTarget(target);
        guard.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, target);
        guard.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target.blockPosition(), 1, 1));
        guard.startUsingItem(InteractionHand.MAIN_HAND);
        helper.runAtTickTime(40, () -> {
            helper.assertTrue(guard.isSleeping(), "Combat/proximity woke the archer");
            helper.assertTrue(guard.position().distanceToSqr(start) < 0.04, "Sleeping archer glided out of bed");
            helper.assertTrue(guard.getTarget() == null && !guard.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET),
                    "Sleeping archer retained an attack target");
            helper.assertTrue(!guard.isUsingItem(), "Sleeping archer continued drawing its bow");
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(AbstractArrow.class, guard.getBoundingBox().inflate(12),
                    arrow -> arrow.getOwner() == guard).isEmpty(), "Sleeping archer fired an arrow");
            guard.hurt(helper.getLevel().damageSources().mobAttack(target), 1);
            helper.assertTrue(!guard.isSleeping(), "Actual damage no longer wakes the archer");
            helper.assertTrue(McaCompat.setGuardTarget(guard, target), "Awake guard cannot respond after being hurt");
            guard.discard(); target.discard();
            helper.succeed();
        });
    }

    @GameTest(template = "cell_parity")
    public static void physicalRestraintWakesSleepingCaptive(GameTestHelper helper) {
        var captive = sleeper(helper, false, false);
        var captor = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(6, 1, 4));
        helper.assertTrue(McaCompat.leashTo(captive, captor), "Physical restraint failed");
        helper.assertTrue(!captive.isSleeping(), "Restrained captive remained in the sleeping pose");
        captive.discard(); captor.discard(); helper.succeed();
    }
}

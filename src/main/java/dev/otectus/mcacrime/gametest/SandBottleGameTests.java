package dev.otectus.mcacrime.gametest;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.ai.CrimeEntityTags;
import dev.otectus.mcacrime.detect.WitnessResult;
import dev.otectus.mcacrime.effect.CrimeEffects;
import dev.otectus.mcacrime.effect.SandBlindness;
import dev.otectus.mcacrime.effect.SandRecoveryLedger;
import dev.otectus.mcacrime.entity.SandBottleProjectile;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.item.SandThrowService;
import dev.otectus.mcacrime.ledger.CrimeContext;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.commands.arguments.EntityAnchorArgument;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * The Sand Bottle in a running world (0.7.2 §13–§14).
 *
 * <p>Three things can only be asserted here. A real thrown projectile has to fly and strike something,
 * which needs a ticking level; the burst's geometry — direct versus splash, the {@code sand_immune}
 * tag, the occlusion ray — is read off live entities and blocks; and "sand is a disruption tool, not a
 * weapon" is a statement about the victim's health and the thrower's case file after an impact, both
 * of which live in server state.
 *
 * <p>The first test throws the bottle the way a player does, through {@link SandThrowService}. The
 * other two call the burst directly against a constructed hit result, because their subject is which
 * entities the burst chooses and for how long — driving that through flight time would add spread and
 * gravity to a test about neither.
 */
@GameTestHolder(McaCrime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SandBottleGameTests {

    private SandBottleGameTests() {
    }

    /**
     * A thrown bottle blinds the villager it strikes for the configured direct duration, takes no
     * health off them, and writes no damage-based case against the thrower (§13.3, §14.1).
     */
    @GameTest(template = "cell_parity", timeoutTicks = 200)
    public static void aThrownBottleBlindsItsTargetWithoutHurtingOrChargingAnyone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        Mob villager = villager(helper, new BlockPos(4, 1, 4));
        ServerPlayer thrower = player(helper, new BlockPos(8, 1, 4));
        var bottles = McaCrimeConfig.COMMON.enableSandBottles;
        boolean previous = bottles.get();
        int direct = McaCrimeConfig.COMMON.sandDirectDurationTicks.get();
        int splash = McaCrimeConfig.COMMON.sandSplashDurationTicks.get();
        float healthBefore = villager.getHealth();
        CrimeWorldData world = CrimeWorldData.get(level.getServer());
        int casesBefore = world.recordsForOffender(thrower.getUUID()).size();
        bottles.set(true);
        thrower.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(CrimeItems.SAND_BOTTLE.get(), 4));
        thrower.lookAt(EntityAnchorArgument.Anchor.EYES, villager.getEyePosition());

        InteractionResult result = SandThrowService.throwBottle(thrower, InteractionHand.MAIN_HAND);
        helper.assertTrue(result == InteractionResult.CONSUME, "The throw was refused: " + result);
        helper.assertTrue(thrower.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 3,
                "The throw did not spend exactly one bottle");
        helper.assertTrue(thrower.getCooldowns().isOnCooldown(CrimeItems.SAND_BOTTLE.get()),
                "The throw did not start the shared cooldown");

        helper.runAfterDelay(12, () -> {
            try {
                MobEffectInstance sand = villager.getEffect(CrimeEffects.SAND_BLINDED);
                helper.assertTrue(sand != null, "The struck villager was not blinded");
                helper.assertTrue(sand.getDuration() > 0 && sand.getDuration() <= direct,
                        "Blindness ran for " + sand.getDuration() + ", outside (0, " + direct + "]");
                helper.assertTrue(sand.getDuration() > splash,
                        "A directly struck target got the splash duration: " + sand.getDuration());
                helper.assertTrue(SandBlindness.isBlinded(villager), "The shared reader disagrees");
                helper.assertTrue(!SandRecoveryLedger.canApply(villager,
                                SandRecoveryLedger.clock(level.getServer())),
                        "A blinded target would accept a second bottle");

                helper.assertTrue(villager.getHealth() == healthBefore,
                        "Sand took health: " + healthBefore + " -> " + villager.getHealth());
                helper.assertTrue(villager.isAlive(), "Sand killed its target");
                helper.assertTrue(level.getEntitiesOfClass(SandBottleProjectile.class,
                                villager.getBoundingBox().inflate(24)).isEmpty(),
                        "The bottle outlived its impact");
                assertNoDamageCase(helper, world, thrower.getUUID(), casesBefore);
                helper.succeed();
            } finally {
                bottles.set(previous);
                villager.discard();
                thrower.discard();
            }
        });
    }

    /**
     * One burst, three neighbours: the struck entity gets the direct duration, the one inside the
     * radius gets strictly less, and the one in {@code mcacrime:sand_immune} gets nothing (§13.3, §13.7).
     */
    @GameTest(template = "cell_parity")
    public static void splashFallsOffWithDistanceAndImmuneEntitiesAreNeverBlinded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        var struck = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(4, 1, 4));
        var neighbour = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(5, 1, 4));
        var immune = helper.spawnWithNoFreeWill(EntityType.IRON_GOLEM, new BlockPos(5, 1, 5));
        ServerPlayer thrower = player(helper, new BlockPos(9, 1, 9));
        int direct = McaCrimeConfig.COMMON.sandDirectDurationTicks.get();
        int splash = McaCrimeConfig.COMMON.sandSplashDurationTicks.get();
        try {
            helper.assertTrue(immune.getType().is(CrimeEntityTags.SAND_IMMUNE),
                    "The immunity fixture is not in mcacrime:sand_immune");
            helper.assertTrue(!struck.getType().is(CrimeEntityTags.SAND_IMMUNE),
                    "The splash fixture is immune, so the test would pass vacuously");

            burst(level, thrower, struck);

            MobEffectInstance hit = struck.getEffect(CrimeEffects.SAND_BLINDED);
            helper.assertTrue(hit != null && hit.getDuration() == direct,
                    "The struck cow got " + (hit == null ? "nothing" : hit.getDuration() + " ticks"));
            MobEffectInstance nearby = neighbour.getEffect(CrimeEffects.SAND_BLINDED);
            helper.assertTrue(nearby != null, "A neighbour inside the radius was not blinded");
            helper.assertTrue(nearby.getDuration() > 0 && nearby.getDuration() <= splash,
                    "Splash ran for " + nearby.getDuration() + ", outside (0, " + splash + "]");
            helper.assertTrue(nearby.getDuration() < hit.getDuration(),
                    "A splash victim was blinded as long as the struck one");
            helper.assertTrue(!SandBlindness.isBlinded(immune), "An immune entity was blinded");
            helper.assertTrue(struck.getHealth() == struck.getMaxHealth()
                            && neighbour.getHealth() == neighbour.getMaxHealth(),
                    "The burst dealt damage");
        } finally {
            struck.discard();
            neighbour.discard();
            immune.discard();
            thrower.discard();
        }
        helper.succeed();
    }

    /**
     * The anti-stun-lock rule, end to end (§13.6, SAND-09): sand never refreshes an effect that is
     * already running, and after it ends the target is protected from <em>every</em> thrower for the
     * configured recovery window.
     */
    @GameTest(template = "cell_parity", timeoutTicks = 300)
    public static void sandNeverRefreshesItselfOrLandsInsideTheRecoveryWindow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        var target = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(4, 1, 4));
        ServerPlayer first = player(helper, new BlockPos(9, 1, 9));
        ServerPlayer second = player(helper, new BlockPos(9, 1, 10));
        var duration = McaCrimeConfig.COMMON.sandDirectDurationTicks;
        var recovery = McaCrimeConfig.COMMON.sandRecoveryTicks;
        int previousDuration = duration.get();
        int previousRecovery = recovery.get();
        duration.set(20);
        recovery.set(60);

        burst(level, first, target);
        MobEffectInstance applied = target.getEffect(CrimeEffects.SAND_BLINDED);
        boolean opened = applied != null && applied.getDuration() == 20;

        // A second bottle, from a different thrower, on the same tick: it may not extend the first.
        burst(level, second, target);
        MobEffectInstance after = target.getEffect(CrimeEffects.SAND_BLINDED);
        boolean unrefreshed = after != null && after.getDuration() == 20;

        helper.runAfterDelay(30, () -> {
            try {
                helper.assertTrue(opened, "The first bottle did not blind for the configured duration");
                helper.assertTrue(unrefreshed, "A second bottle refreshed an effect already running");
                helper.assertTrue(!SandBlindness.isBlinded(target),
                        "A twenty-tick blindness was still running thirty ticks later");
                helper.assertTrue(!SandRecoveryLedger.canApply(target,
                                SandRecoveryLedger.clock(level.getServer())),
                        "The recovery window closed as soon as sight came back");

                burst(level, second, target);
                helper.assertTrue(!SandBlindness.isBlinded(target),
                        "A bottle landed inside the recovery window");
                helper.assertTrue(target.getHealth() == target.getMaxHealth(),
                        "The refused bursts dealt damage");
                helper.succeed();
            } finally {
                duration.set(previousDuration);
                recovery.set(previousRecovery);
                target.discard();
                first.discard();
                second.discard();
            }
        });
    }

    // ------------------------------------------------------------------ fixture

    /**
     * One impact against a constructed hit result, with the launch provenance a real throw records.
     *
     * <p>The impact point is lifted to the target's eyes so the occlusion ray to a neighbour cannot
     * graze the floor the fixture is standing on; the burst's own distance falloff is unaffected,
     * because it measures to bounding boxes.
     */
    private static void burst(ServerLevel level, ServerPlayer thrower, LivingEntity target) {
        SandBottleProjectile bottle = new SandBottleProjectile(level, thrower);
        bottle.setItem(new ItemStack(CrimeItems.SAND_BOTTLE.get()));
        bottle.captureLaunch(UUID.randomUUID(), level.getGameTime(), false, WitnessResult.none());
        Vec3 impact = target.getEyePosition();
        bottle.setPos(impact);
        dev.otectus.mcacrime.effect.SandExposureService.burst(level, bottle,
                new EntityHitResult(target, impact));
        bottle.discard();
    }

    /** No case the thrower gained may be a damage-based one; sand is never an assault (§14.5). */
    private static void assertNoDamageCase(GameTestHelper helper, CrimeWorldData world, UUID thrower,
                                           int before) {
        List<CrimeRecord> records = world.recordsForOffender(thrower);
        for (CrimeRecord record : records) {
            String attribution = record.context().get(CrimeContext.DAMAGE_ATTRIBUTION);
            helper.assertTrue("non_damaging".equals(attribution),
                    "Sand produced a damage case: " + record.type() + " (" + attribution + ")");
        }
        helper.assertTrue(records.size() <= before + 1,
                "One impact produced " + (records.size() - before) + " cases against one thrower");
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 13; x++) {
            for (int z = 0; z < 13; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    /** An adult MCA villager, resolved by name exactly as the other MCA-backed gametests do. */
    @SuppressWarnings("unchecked")
    private static Mob villager(GameTestHelper helper, BlockPos at) {
        EntityType<? extends Mob> type = (EntityType<? extends Mob>) BuiltInRegistries.ENTITY_TYPE
                .getOptional(ResourceLocation.fromNamespaceAndPath("mca", "male_villager")).orElseThrow();
        Mob mob = helper.spawn(type, at);
        ((Villager) mob).setAge(0);
        mob.setNoAi(true);
        mob.setOnGround(true);
        return mob;
    }

    /**
     * A thrower with a recording connection: the throw path awards a statistic and starts an item
     * cooldown, both of which speak to the client.
     */
    private static ServerPlayer player(GameTestHelper helper, BlockPos at) {
        ServerLevel level = helper.getLevel();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "mcacrime-sand");
        ServerPlayer player = new ServerPlayer(level.getServer(), level, profile,
                ClientInformation.createDefault());
        player.connection = new ServerGamePacketListenerImpl(level.getServer(),
                new Connection(PacketFlow.SERVERBOUND), player,
                CommonListenerCookie.createInitial(profile, false)) {
            @Override
            public void send(Packet<?> packet) {
            }

            @Override
            public void send(Packet<?> packet, PacketSendListener listener) {
            }
        };
        BlockPos pos = helper.absolutePos(at);
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }
}

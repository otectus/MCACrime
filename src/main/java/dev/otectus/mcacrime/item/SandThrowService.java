package dev.otectus.mcacrime.item;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.crime.type.CrimeAwareness;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.crime.type.CrimeType;
import dev.otectus.mcacrime.crime.type.CrimeTypeRegistry;
import dev.otectus.mcacrime.detect.WitnessChecker;
import dev.otectus.mcacrime.detect.WitnessResult;
import dev.otectus.mcacrime.entity.SandBottleProjectile;
import dev.otectus.mcacrime.mask.Masks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * The one place a Sand Bottle is thrown (0.7.2 §13.2).
 *
 * <p>Right-clicking air, right-clicking an entity and the interaction handler that pre-empts MCA's
 * screen all arrive here. That is what makes "one interaction, one throw" true across two hands and
 * any number of stacks: the cooldown is on the <em>item</em>, so a second stack in the off hand is
 * still the same cooldown, and it is checked before anything is spawned or taken.
 *
 * <p>Order matters and is asserted by the failure paths. Nothing is consumed, no cooldown starts, no
 * sound plays and no statistic is awarded until {@code addFreshEntity} has actually returned true —
 * so a spawn another mod cancels costs the player nothing (SAND-03).
 */
public final class SandThrowService {

    /** Launch speed and spread, matched to a vanilla thrown egg. */
    private static final float VELOCITY = 1.5F;
    private static final float INACCURACY = 1.0F;

    private SandThrowService() {
    }

    public static InteractionResult throwBottle(ServerPlayer player, InteractionHand hand) {
        if (!McaCrimeConfig.COMMON.enableSandBottles.get()) {
            return InteractionResult.PASS;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof SandBottleItem) || stack.isEmpty()) {
            return InteractionResult.PASS;
        }
        // The cooldown is the duplicate-throw guard as well as the pacing rule: whichever entry path
        // arrives second in the same click finds it already running.
        if (player.getCooldowns().isOnCooldown(stack.getItem())) {
            return InteractionResult.FAIL;
        }
        ServerLevel level = player.serverLevel();

        SandBottleProjectile bottle = new SandBottleProjectile(level, player);
        bottle.setItem(stack.copyWithCount(1));
        bottle.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, VELOCITY, INACCURACY);
        bottle.captureLaunch(UUID.randomUUID(), level.getGameTime(), masked(player), witnesses(level, player));

        if (!level.addFreshEntity(bottle)) {
            return InteractionResult.FAIL;
        }

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        player.getCooldowns().addCooldown(stack.getItem(), McaCrimeConfig.COMMON.sandCooldownTicks.get());
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SPLASH_POTION_THROW,
                SoundSource.PLAYERS, 0.5F, 0.4F / (level.getRandom().nextFloat() * 0.4F + 0.8F));
        player.awardStat(Stats.ITEM_USED.get(stack.getItem()));
        return InteractionResult.CONSUME;
    }

    /** The mask as it is at release. Whatever is worn three seconds later is a different question. */
    private static boolean masked(ServerPlayer player) {
        return McaCrimeConfig.COMMON.maskEnabled.get() && Masks.isMasked(player);
    }

    /**
     * Who saw the throw.
     *
     * <p>Scanned around the thrower, because at this moment there is no victim — the bottle has not
     * landed and may never land on anybody. This set, and not a fresh one taken at the impact point,
     * is what the incident is eventually committed against (§14.3).
     */
    private static WitnessResult witnesses(ServerLevel level, ServerPlayer player) {
        CrimeAwareness awareness = CrimeTypeRegistry.getOrBuiltin(CrimeIds.HARM_VILLAGER)
                .map(CrimeType::awareness)
                .orElseGet(() -> CrimeAwareness.defaults(CrimeIds.HARM_VILLAGER));
        return WitnessChecker.resolve(level, player, null, awareness);
    }
}

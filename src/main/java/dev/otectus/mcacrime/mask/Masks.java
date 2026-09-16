package dev.otectus.mcacrime.mask;

import dev.otectus.mcacrime.item.RestraintTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * What counts as a mask, who is wearing one, and which crimes a mask can hide (0.7.0).
 *
 * <p>Membership is the {@code mcacrime:masks} item tag rather than {@code instanceof MaskItem}, for the
 * same reason rope is a tag: a pack that wants a carved pumpkin or a modded hood to work as a mask
 * should not need a code change, and the two shipped masks are simply the two entries this mod puts in
 * the tag itself.
 *
 * <p>Common code. The client learns a player is masked from the vanilla-synced HEAD slot, so nothing
 * here is client-side and nothing here may import {@code client/}.
 */
public final class Masks {

    private Masks() {
    }

    /** Whether this stack is a mask, by tag membership. */
    public static boolean isMask(@Nullable ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.is(RestraintTags.MASKS);
    }

    /** Whether this entity is wearing a mask right now. */
    public static boolean isMasked(@Nullable LivingEntity entity) {
        return entity != null && isMask(entity.getItemBySlot(EquipmentSlot.HEAD));
    }

    /**
     * Whether a crime detected this way can have its Heat deferred by a mask.
     *
     * <p>A mask hides a face from witnesses; it does not hide a player who is already in the law's
     * hands. {@code command} is an operator writing the record directly, {@code jailbreak} is a
     * prisoner whose identity the jail already holds, and {@code contraband} is a search a guard is
     * performing on the player in front of them. Deferring any of those would be deferring Heat from
     * somebody nobody had to recognise.
     */
    public static boolean defersHeatFor(@Nullable String detection) {
        return !"command".equals(detection) && !"jailbreak".equals(detection) && !"contraband".equals(detection);
    }
}

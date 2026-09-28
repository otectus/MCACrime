package dev.otectus.mcacrime.mixin;

import dev.otectus.mcacrime.locks.LockAutomationPolicy;
import dev.otectus.mcacrime.locks.LockProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The narrow vanilla hook that makes a padlock mean something to a hopper (M3.6, spec §10.3).
 *
 * <p>"Cancellation of a right-click is not sufficient to protect stored items" is the specification's
 * wording, and it is exactly right: a hopper under a padlocked chest never right-clicks anything.
 * There is no Forge event for a hopper transfer, so these two methods are the hook — and they are the
 * two every hopper and hopper-minecart transfer passes through: {@code suckInItems} pulls from the
 * container above, {@code ejectItems} pushes into the one the hopper faces.
 *
 * <p>Separating the two is what makes {@code locks.automationPolicy = ALLOW_INSERT} expressible — a
 * locked drop box that anything can feed and nothing can empty. A single hook on the shared
 * coordinate helper could not tell the two directions apart.
 *
 * <p><b>Why these two and not the container lookups.</b> Forge patches both methods to try an
 * item-handler <em>capability</em> first — {@code suckInItems} calls
 * {@code VanillaInventoryCodeHooks.extractHook(Level, Hopper)} and {@code ejectItems} calls
 * {@code insertHook(HopperBlockEntity)} — and only falls back to {@code getSourceContainer} /
 * {@code getAttachedContainer} when that returns no answer. Forge's {@code BaseContainerBlockEntity}
 * exposes an item-handler capability for every vanilla container, so the hook route <em>succeeds</em>
 * for a padlocked chest and the two lookups this mixin used to target were never reached: a hopper
 * drained a padlocked chest. These two callers sit above both routes, and returning {@code false} is
 * what vanilla itself returns for "no transfer happened".
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperLockMixin {

    /** Extraction: the container a hopper is pulling from, one block above it. */
    @Inject(method = "suckInItems", at = @At("HEAD"), cancellable = true)
    private static void mcacrime$lockedSourceYieldsNothing(Level level, Hopper hopper,
                                                           CallbackInfoReturnable<Boolean> cir) {
        BlockPos above = BlockPos.containing(hopper.getLevelX(), hopper.getLevelY() + 1.0D,
                hopper.getLevelZ());
        if (LockProtection.blocksAutomation(level, above, LockAutomationPolicy.Operation.EXTRACT)) {
            cir.setReturnValue(Boolean.FALSE);
        }
    }

    /**
     * Insertion: the container a hopper is pushing into, in the direction it faces.
     *
     * <p>The state is the parameter rather than a lookup, because it is the state vanilla has already
     * resolved for this transfer and reading the world again could disagree with it mid-tick.
     *
     * <p>{@code remap = false} is load-bearing and is not a shortcut. Forge's patch changes this
     * method's signature (its last parameter becomes {@code HopperBlockEntity} so it can call
     * {@code insertHook}), so MCPConfig has no mapping for it and it keeps its plain name in the SRG
     * jar — verified against {@code forge-1.20.1-47.4.10-srg.jar}, where {@code suckInItems} is
     * {@code m_155552_} and {@code ejectItems} is still {@code ejectItems}. Remapping it would look
     * for a name that does not exist.
     */
    @Inject(method = "ejectItems", at = @At("HEAD"), cancellable = true, remap = false)
    private static void mcacrime$lockedDestinationAcceptsNothing(Level level, BlockPos pos,
                                                                 BlockState state,
                                                                 HopperBlockEntity hopper,
                                                                 CallbackInfoReturnable<Boolean> cir) {
        if (!state.hasProperty(HopperBlock.FACING)) {
            return;
        }
        BlockPos target = pos.relative(state.getValue(HopperBlock.FACING));
        if (LockProtection.blocksAutomation(level, target, LockAutomationPolicy.Operation.INSERT)) {
            cir.setReturnValue(Boolean.FALSE);
        }
    }
}

package dev.otectus.mcacrime.jail;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.state.CrimeAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * CONTAINMENT-mode block protection (spec §7.3): a jailed player cannot break blocks inside their jail
 * region, so they can't mine out. PHYSICAL mode allows it (a genuine breakout, handled by {@link JailConfine}).
 * Cheap: the not-jailed check short-circuits for essentially every break in the game.
 *
 * <h2>What else decides a prisoner's break (0.7.7)</h2>
 *
 * <p>This handler only ever refuses, and it is one of several that may. The mode is the live setting
 * ({@link JailConfine#mode}), so switching a server to PHYSICAL lets everybody already serving dig, and
 * switching back stops them. Whatever the mode, a break is still refused by worn restraints that forbid
 * mining ({@code enforcement/RestraintHandlers}), by a lock on the block
 * ({@code locks.protectLockedBlocksFromBreaking}), by {@code prison.reinforcedAuthorisedRemovalOnly} for a
 * reinforced block, and by {@code prison.reinforcedBreakingPolicy = HARD_CONTAINMENT} for a reinforced
 * block and anybody in custody. Every one of those cancels on its own, so none of them depends on this.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class ContainmentHandler {

    private ContainmentHandler() {
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        JailState jail = CrimeAttachments.get(player).getJail();
        if (jail == null) {
            return; // not jailed
        }
        ResourceLocation posDim = player.level().dimension().location();
        if (refuses(JailConfine.mode(jail), jail.isCuffEscape(),
                JailRegion.contains(jail.getJailAnchor(), jail.getJailRadius(), jail.getJailDim(),
                        event.getPos(), posDim))) {
            event.setCanceled(true); // can't mine out of a CONTAINMENT/REINFORCED jail
        }
    }

    /**
     * Whether the jail refuses a prisoner's break (pure, 0.7.7).
     *
     * @param mode       the live containment mode
     * @param cuffEscape the prisoner slipped their cuffs and is already out of lawful hands
     * @param inRegion   the block is inside the prisoner's own jail region
     */
    public static boolean refuses(JailContainmentMode mode, boolean cuffEscape, boolean inRegion) {
        return inRegion && !cuffEscape && mode != JailContainmentMode.PHYSICAL;
    }
}

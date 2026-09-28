package dev.otectus.mcacrime.block.prison;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.jail.JailService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Iterator;

/**
 * The reinforced set's two server rules (M5.4).
 *
 * <p>The breaking policy and explosion resistance. Piston immunity is the third and lives on the
 * blocks themselves, because a piston asks the block rather than firing an event anybody can listen
 * to.
 *
 * <p>Nothing here is a protection plugin. The breaking policy is permissive by default, and every
 * refusal names the setting that caused it, because a wall that silently refuses to break is
 * indistinguishable from a bug.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class PrisonBlockHandlers {

    private PrisonBlockHandlers() {
    }

    /** Whether the law is holding this player right now. */
    static boolean inCustody(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        try {
            return CustodyService.isCaptive(player) || JailService.isJailed(player);
        } catch (RuntimeException notReady) {
            return false;
        }
    }

    /**
     * The breaking policy.
     *
     * <p>{@code HARD_CONTAINMENT} stops a subject in custody and nobody else;
     * {@code reinforcedAuthorisedRemovalOnly} stops everybody who is not an operator. Both say so.
     */
    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (!ReinforcedPolicy.isReinforced(event.getState())) {
            return;
        }
        if (ReinforcedPolicy.mayBreak(player, inCustody(player))) {
            return;
        }
        event.setCanceled(true);
        player.displayClientMessage(Component.translatable("mcacrime.msg.prison.break_refused"), true);
    }

    /**
     * Explosion resistance.
     *
     * <p>Late priority so a protection mod that wants the block gone for its own reasons has already
     * had its say; this only ever removes positions from the list, never adds one.
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onDetonate(ExplosionEvent.Detonate event) {
        if (!ReinforcedPolicy.resistsExplosions()) {
            return;
        }
        Level level = event.getLevel();
        Iterator<BlockPos> positions = event.getAffectedBlocks().iterator();
        while (positions.hasNext()) {
            if (ReinforcedPolicy.isReinforced(level.getBlockState(positions.next()))) {
                positions.remove();
            }
        }
    }
}

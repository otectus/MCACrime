package dev.otectus.mcacrime.locks;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.item.creative.CreativeAuthorization;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Server-enforced lock protection on every route a lock can be defeated (M3.6, spec §10.3).
 *
 * <p>Five routes are covered here — opening, breaking, explosions, pistons and the redstone-driven
 * movement that a piston is — and the sixth, automated item transfer, is covered by
 * {@code mixin/HopperLockMixin} plus the safe's own item handler, both of which ask
 * {@link LockProtection} the same question these do.
 *
 * <p>What is honestly <b>not</b> covered, and is reported rather than claimed: a modded container that
 * neither exposes a NeoForge item-handler capability nor routes through vanilla's hopper helpers. The
 * specification
 * asks for a verified adapter or an explicit report, and there is no adapter here, so
 * {@link #reportCoverage} says so once at startup.
 *
 * <p>Ownership is an exemption, not an authority: the player who set a lock may break their own
 * block, and an operator may always act. Everybody else meets the lock.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class LockProtectionHandlers {

    private LockProtectionHandlers() {
    }

    /** Whether this player is allowed past a lock on {@code lock}, for a protection decision. */
    public static boolean exempt(@Nullable Player player, @Nullable LockRecord lock) {
        if (player == null || lock == null) {
            return false;
        }
        if (lock.ownerId().map(owner -> owner.equals(player.getUUID())).orElse(false)) {
            return true;
        }
        return player instanceof ServerPlayer server && CreativeAuthorization.permits(server);
    }

    /**
     * Opening a padlocked vanilla container.
     *
     * <p>Our own blocks are skipped: a cell door and a safe answer for themselves, with their own
     * messages and their own key routing, and cancelling their interaction here would take the key out
     * of the player's hands before the block ever saw it.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        BlockPos pos = event.getPos();
        if (LockInteractions.holderAt(level, pos) != null) {
            return; // one of ours; it handles its own lock
        }
        Optional<LockRecord> lock = LockProtection.lockAt(level, pos);
        if (lock.isEmpty() || !lock.get().locked()) {
            return;
        }
        if (exempt(player, lock.get()) || LockInteractions.mayPass(player, lock.get())) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
        player.displayClientMessage(Component.translatable("mcacrime.lock.locked"), true);
    }

    /** Breaking a locked block, when {@code locks.protectLockedBlocksFromBreaking} says no. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof Level level) || level.isClientSide()
                || !protectFromBreaking()) {
            return;
        }
        Optional<LockRecord> lock = LockProtection.lockAt(level, event.getPos());
        if (lock.isEmpty() || !lock.get().locked() || exempt(event.getPlayer(), lock.get())) {
            return;
        }
        event.setCanceled(true);
        if (event.getPlayer() instanceof ServerPlayer player) {
            player.displayClientMessage(Component.translatable("mcacrime.lock.protected"), true);
        }
    }

    /**
     * Explosions: the locked blocks are removed from the blast list, not the blast from the world.
     *
     * <p>Removing the positions rather than cancelling the explosion is deliberate. A locked chest in a
     * creeper's radius should survive; everything else around it should not, and cancelling would make
     * one padlock a blast shelter for a whole room.
     */
    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        if (level.isClientSide() || !protectFromExplosions()) {
            return;
        }
        List<BlockPos> spared = new ArrayList<>();
        for (BlockPos pos : event.getAffectedBlocks()) {
            if (LockProtection.locked(level, pos)) {
                spared.add(pos);
            }
        }
        event.getAffectedBlocks().removeAll(spared);
    }

    /**
     * Pistons: a locked block does not move.
     *
     * <p>Moving a container away from the lock that protects it is the oldest bypass there is, and it
     * is also how a lock's stored position stops describing anything.
     */
    @SubscribeEvent
    public static void onPiston(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof Level level) || level.isClientSide()
                || !protectFromPistons()) {
            return;
        }
        PistonEvent.PistonMoveType type = event.getPistonMoveType();
        BlockPos face = event.getFaceOffsetPos();
        if (LockProtection.locked(level, face)) {
            event.setCanceled(true);
            return;
        }
        var structure = event.getStructureHelper();
        if (structure == null || !structure.resolve()) {
            return;
        }
        for (BlockPos pos : structure.getToPush()) {
            if (LockProtection.locked(level, pos)) {
                event.setCanceled(true);
                return;
            }
        }
        if (type == PistonEvent.PistonMoveType.RETRACT) {
            for (BlockPos pos : structure.getToDestroy()) {
                if (LockProtection.locked(level, pos)) {
                    event.setCanceled(true);
                    return;
                }
            }
        }
    }

    /**
     * Says, once, exactly what lock protection covers and what it does not.
     *
     * <p>An explicit report rather than a silent gap, because the specification is precise about it:
     * "for modded containers, require a verified adapter or explicitly report the unsupported
     * automation route. Do not promise protection against every mod's private storage implementation
     * based only on an interaction event."
     */
    public static void reportCoverage() {
        McaCrime.LOGGER.info("MCA: Crime lock protection covers interaction, breaking, explosions, "
                + "pistons, this mod's own cell door and safe (including the safe's item handler) and "
                + "vanilla hopper and hopper minecart transfers. Two routes are NOT covered by any "
                + "adapter in this release and a padlock on them protects interaction only: a modded "
                + "container that neither routes through vanilla's hopper helpers nor exposes a "
                + "NeoForge item-handler capability, and a "
                + "redstone signal opening a padlocked vanilla door or trapdoor "
                + "(a cell door ignores redstone and is unaffected).");
    }

    private static boolean protectFromBreaking() {
        try {
            return McaCrimeConfig.COMMON.protectLockedBlocksFromBreaking.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    private static boolean protectFromExplosions() {
        try {
            return McaCrimeConfig.COMMON.protectLockedBlocksFromExplosions.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    private static boolean protectFromPistons() {
        try {
            return McaCrimeConfig.COMMON.protectLockedBlocksFromPistons.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }
}

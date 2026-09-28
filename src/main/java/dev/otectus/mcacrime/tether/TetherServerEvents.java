package dev.otectus.mcacrime.tether;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.block.BunkBlock;
import dev.otectus.mcacrime.block.GuillotineBlock;
import dev.otectus.mcacrime.block.PilloryBlock;
import dev.otectus.mcacrime.detention.BunkRespawnPolicy;
import dev.otectus.mcacrime.detention.DetentionKind;
import dev.otectus.mcacrime.detention.DetentionService;
import dev.otectus.mcacrime.detention.ExecutionAuthorization;
import dev.otectus.mcacrime.restraint.CustodyTransitionService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.jetbrains.annotations.Nullable;

/**
 * The server hooks the transport and detention engines need, in one place (0.7.5 M4.3, M4.5, M4.10).
 *
 * <p>Four jobs. The tick arbitrates every loaded tether and expires nothing but execution windows.
 * Death, logout and dimension change end the holds that named the entity that went away, in both
 * directions — a dead captor releases their captives and a dead captive releases their captor's
 * chain. The dismount gate is where a leg restraint stops somebody climbing out of a boat. And the
 * device sweep releases occupants whose pillory is no longer standing, <b>regardless of the breakout
 * toggle</b>, which is the coupling the source gets wrong.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class TetherServerEvents {

    /** How often the stale-device sweep runs. Once every ten seconds is generous for a whole world. */
    private static final int SWEEP_INTERVAL_TICKS = 200;

    private static int sweepCounter;

    private TetherServerEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // NeoForge splits the old phase flag into two events; Post is this line's END phase.
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        TransportArbiter.tickAll(server);
        // Windows closing kill nobody: expiry removes the authorisation, which returns the subject to
        // condemned-in-custody. This is the only place time touches the capital feature at all.
        ExecutionAuthorization.expire(server.overworld().getGameTime());
        // The walk to a device, and only ever one deliberate step per pass (§3.19, M6.7).
        dev.otectus.mcacrime.enforcement.CondemnedEscortService.tick(server);
        if (++sweepCounter >= SWEEP_INTERVAL_TICKS) {
            sweepCounter = 0;
            DetentionService.sweep(server, CrimeWorldData.get(server), TetherServerEvents::deviceStands);
            // On the same ten-second cadence: is there a condemned prisoner a guard could take?
            dev.otectus.mcacrime.enforcement.CondemnedEscortService.considerStart(server);
        }
    }

    /**
     * Whether a device of this kind is still standing at this position.
     *
     * <p>Passed to the sweep as a predicate so {@code detention/} needs no block imports: the service
     * owns the occupancy rule and this owns the question "is the block still there", and neither has
     * to know the other's package.
     */
    private static boolean deviceStands(Level level, BlockPos pos, DetentionKind kind) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof PilloryBlock)) {
            return kind == DetentionKind.BUNK && BunkBlock.isBunkHead(state);
        }
        if (state.getValue(PilloryBlock.HALF) != DoubleBlockHalf.LOWER) {
            return false; // the canonical position is the lower half; anything else is stale
        }
        if (kind != DetentionKind.GUILLOTINE) {
            return true;
        }
        return level.getBlockState(pos.above(2)).getBlock() instanceof GuillotineBlock;
    }

    // --- things going away ---------------------------------------------------------------------------

    /**
     * Somebody died holding somebody, or held by somebody.
     *
     * <p>Both directions, and both idempotent. A subject who dies releases every tether on them and
     * pays nobody a chain, because their inventory is already being resolved by the death path and a
     * second item would be an unaccounted drop.
     */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead == null || dead.level().isClientSide()) {
            return;
        }
        MinecraftServer server = dead.getServer();
        if (server == null) {
            return;
        }
        TetherService.detachAll(server, dead.getUUID(), TetherService.DetachReason.SUBJECT_DIED);
        TetherService.detachHeldBy(server, dead.getUUID(), TetherService.DetachReason.HOLDER_LOST);
        // A guard who dies mid-ceremony is one of §3.19's clearing rules, in both roles: the condemned
        // subject's own order, and any order this guard gave.
        ExecutionAuthorization.clear(dead.getUUID(), ExecutionAuthorization.ClearReason.CARRIED_OUT);
        ExecutionAuthorization.clearByActor(dead.getUUID(), ExecutionAuthorization.ClearReason.GUARD_DIED);
        // Whichever role they were in, the walk ends: claim and reservation released, the prisoner
        // left condemned and in custody (§3.19, M6.7).
        dev.otectus.mcacrime.enforcement.CondemnedEscortService.cancel(server, dead.getUUID(),
                "the prisoner died");
        dev.otectus.mcacrime.enforcement.CondemnedEscortService.cancelByGuard(server, dead.getUUID(),
                "the guard died");
        CrimeWorldData data = CrimeWorldData.get(server);
        DetentionService.forSubject(data, dead.getUUID()).ifPresent(record ->
                DetentionService.release(server, data, record.id(),
                                DetentionService.ReleaseReason.OCCUPANT_DIED)
                        .ifPresent(ended -> CustodyTransitionService.onDetentionEnded(server, ended,
                                DetentionService.ReleaseReason.OCCUPANT_DIED, null)));
    }

    /** A captor who logs out is not holding anybody. Their chains are paid back where they can be. */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        TetherService.detachHeldBy(server, player.getUUID(), TetherService.DetachReason.HOLDER_LOST);
        ExecutionAuthorization.clearByActor(player.getUUID(),
                ExecutionAuthorization.ClearReason.GUARD_DIED);
        dev.otectus.mcacrime.enforcement.CondemnedEscortService.cancelByGuard(server, player.getUUID(),
                "the guard logged out");
        dev.otectus.mcacrime.enchantment.RestraintEffects.forget(player.getUUID());
        BunkRespawnPolicy.forget(player.getUUID());
    }

    /**
     * A tether never spans dimensions.
     *
     * <p>Ended rather than followed: the far end is in another world, the distance is undefined, and
     * a hold whose physics cannot be evaluated is not a hold.
     */
    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        TetherService.detachHeldBy(server, player.getUUID(), TetherService.DetachReason.HOLDER_LOST);
        TetherService.detachAll(server, player.getUUID(), TetherService.DetachReason.HOLDER_LOST);
    }

    // --- getting out of vehicles ------------------------------------------------------------------------

    /**
     * A leg restraint blocks the ordinary dismount route, and nothing else.
     *
     * <p>Only a voluntary dismount is cancelled. Mounting is untouched, and an emergency release goes
     * through {@link MountTransfer#release}, which does not raise this event path at all — so a
     * restrained passenger can always be got out of a vehicle that is being destroyed.
     */
    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (event.getLevel().isClientSide() || !event.isDismounting()) {
            return;
        }
        Entity subject = event.getEntityMounting();
        Entity vehicle = event.getEntityBeingMounted();
        if (subject == null || vehicle == null || !vehicle.isAlive() || vehicle.isRemoved()) {
            return; // the vehicle is going away: this is the emergency, and it always wins
        }
        if (!MountTransfer.mayDismount(subject, false)) {
            event.setCanceled(true);
        }
    }

    // --- shutdown -----------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        TetherService.invalidate();
        dev.otectus.mcacrime.enforcement.CondemnedEscortService.clearAll();
        dev.otectus.mcacrime.enforcement.ExecutionSiteRegistry.clear();
        dev.otectus.mcacrime.enchantment.RestraintEffects.clearAll();
        dev.otectus.mcacrime.enchantment.ImbueIndex.clear();
        ExecutionAuthorization.clearAll();
        BunkRespawnPolicy.clearAll();
    }

    /** Exposed so a test or a command can force the sweep without waiting ten seconds. */
    public static int sweepNow(@Nullable MinecraftServer server) {
        return server == null ? 0
                : DetentionService.sweep(server, CrimeWorldData.get(server),
                        TetherServerEvents::deviceStands);
    }
}

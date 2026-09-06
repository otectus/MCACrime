package dev.otectus.mcacrime.captivity;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.ActionResult;
import dev.otectus.mcacrime.action.ActionSession;
import dev.otectus.mcacrime.action.ActionSessionManager;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.network.ActionProgressS2CPacket;
import dev.otectus.mcacrime.network.CrimeNetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.jetbrains.annotations.Nullable;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Drives in-progress capture channels (spec §8.2): each server tick it advances every {@link CaptureChannel}
 * and applies the break conditions (kidnapper hit / moved too far / target out of range or line of sight),
 * completing a finished channel into {@link CustodyService#capture}. Bounded by the (small) number of active
 * channels — never a world scan.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CaptureTicker {

    /** How often the capture channel refreshes its bar, matching the action engine's cadence. */
    private static final int PROGRESS_INTERVAL_TICKS = 5;
    /**
     * How often NPC captivities are advanced. One second, batched: the captivity cap is measured in
     * minutes, so crediting twenty ticks once beats crediting one tick twenty times over a table that
     * has to be walked each pass.
     */
    private static final int NPC_CUSTODY_INTERVAL_TICKS = 20;

    private static int npcCustodyCounter;

    private CaptureTicker() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        if (++npcCustodyCounter >= NPC_CUSTODY_INTERVAL_TICKS) {
            npcCustodyCounter = 0;
            CustodyService.tickNpcCaptives(server, NPC_CUSTODY_INTERVAL_TICKS);
        }
        for (UUID kidnapperId : CaptureChannels.kidnappers()) {
            CaptureChannel channel = CaptureChannels.get(kidnapperId);
            if (channel == null) {
                continue;
            }
            ServerPlayer kidnapper = server.getPlayerList().getPlayer(kidnapperId);
            if (kidnapper == null) {
                CaptureChannels.cancel(kidnapperId); // kidnapper logged off
                continue;
            }
            Entity target = resolveTarget(kidnapper, channel.target);
            if (target == null || !target.isAlive() || target.level() != kidnapper.level()) {
                CaptureChannels.cancel(kidnapperId);
                endBar(kidnapper, channel, false, "mcacrime.capture.broken.target_lost");
                continue;
            }
            String breakKey = breakReason(kidnapper, channel, target);
            if (breakKey != null) {
                CaptureChannels.cancel(kidnapperId);
                endBar(kidnapper, channel, false, breakKey);
                continue;
            }
            channel.tick();
            if (channel.isComplete()) {
                // The lease is still held here, deliberately: the commit runs inside it, so nothing
                // else can take the target between the last eligibility check and the record write.
                CaptureCommitResult result = commitCapture(server, kidnapper, target, channel);
                ActionSession session = channel.session();
                if (session != null) {
                    ActionSessionManager.finish(session, result.ok()
                            ? ActionResult.accepted(outcomeKey(result))
                            : ActionResult.rejected(outcomeKey(result)));
                }
                CaptureChannels.cancel(kidnapperId);
                endBar(kidnapper, channel, result.ok(), outcomeKey(result));
            } else if (channel.elapsed() % PROGRESS_INTERVAL_TICKS == 0) {
                progressBar(kidnapper, channel);
            }
        }
    }

    /**
     * The commit sequence, with every side effect behind a seam.
     *
     * <p>The order is the fix. Consuming the restraint before the capture meant that "already held"
     * and "over your allowance" — both of which the custody table only discovers at the moment of
     * writing — cost the captor an item and gave them nothing, and the item was gone whether or not a
     * record was ever written. Here nothing is spent unless {@code capture} says the record stands,
     * and the reservation is simply dropped otherwise.
     *
     * <p>Pure, so the guarantee can be asserted rather than hoped for: on any non-ok result the
     * consume seam is never invoked.
     *
     * @param eligibility the re-check, returning {@link CaptureCommitResult#CAPTURED} when it passes
     */
    public static CaptureCommitResult commit(Supplier<CaptureCommitResult> eligibility,
                                             Supplier<Optional<RestraintReservation>> reserve,
                                             Supplier<CaptureCommitResult> capture,
                                             Consumer<RestraintReservation> consume) {
        CaptureCommitResult gate = eligibility.get();
        if (!gate.ok()) {
            return gate;
        }
        Optional<RestraintReservation> reserved = reserve.get();
        if (reserved.isEmpty()) {
            return CaptureCommitResult.RESTRAINT_MISSING;
        }
        CaptureCommitResult committed = capture.get();
        if (committed.ok()) {
            consume.accept(reserved.get());
        }
        return committed;
    }

    /** {@link #commit} wired to the live server: re-check, reserve, capture, consume. */
    private static CaptureCommitResult commitCapture(MinecraftServer server, ServerPlayer kidnapper,
                                                     Entity target, CaptureChannel channel) {
        return commit(() -> stillEligible(server, kidnapper, target, channel),
                () -> CrimeItems.reserveRestraint(kidnapper, channel.restraint),
                () -> target instanceof LivingEntity living
                        ? CustodyService.capture(kidnapper, living, channel.restraint)
                        : CaptureCommitResult.TARGET_INVALID,
                reservation -> CrimeItems.consumeReserved(kidnapper, reservation));
    }

    /**
     * Everything the start of the channel established that could have stopped being true during it.
     *
     * <p>Not the whole of {@link CaptureService#evaluate}: the vulnerability gate is a condition for
     * beginning, not for finishing, and re-testing it here would mean a target who woke up or healed
     * mid-channel could never be taken however long the captor held on.
     */
    private static CaptureCommitResult stillEligible(MinecraftServer server, ServerPlayer kidnapper,
                                                     Entity target, CaptureChannel channel) {
        if (!(target instanceof LivingEntity) || !target.isAlive() || target.level() != kidnapper.level()) {
            return CaptureCommitResult.TARGET_INVALID;
        }
        if (target instanceof ServerPlayer victim && (victim.isSpectator() || victim.isCreative())) {
            return CaptureCommitResult.TARGET_INVALID;
        }
        if (CustodyRegistry.isCaptive(server, target.getUUID())) {
            return CaptureCommitResult.ALREADY_HELD;
        }
        ActionSession session = channel.session();
        if (session != null && (session.terminal() != null
                || ActionSessionManager.forActor(kidnapper.getUUID()).orElse(null) != session)) {
            return CaptureCommitResult.SESSION_LOST;
        }
        return CaptureCommitResult.CAPTURED;
    }

    /** The line the captor is shown, one per reason the commit could produce. */
    private static String outcomeKey(CaptureCommitResult result) {
        return switch (result) {
            case CAPTURED -> "mcacrime.capture.done";
            case ALREADY_HELD -> "mcacrime.capture.already";
            case QUOTA_FULL -> "mcacrime.capture.capacity";
            case TARGET_INVALID -> "mcacrime.capture.broken.target_lost";
            case RESTRAINT_MISSING -> "mcacrime.capture.broken.restraint";
            case SESSION_LOST -> "mcacrime.action.conflict";
            case GATED -> "mcacrime.capture.invalid";
        };
    }

    /**
     * The capture channel draws the same HUD bar as an action channel.
     *
     * <p>A capture is not an {@code ActionSession} — it predates the action engine and keeps its own
     * transient state — but a player has no reason to care which subsystem owns their progress bar.
     * The channel's own stable identity stands in for a session id, so a restart or a second channel
     * never resurrects the previous one's bar.
     */
    private static void progressBar(ServerPlayer kidnapper, CaptureChannel channel) {
        CrimeNetwork.sendActionProgress(kidnapper, new ActionProgressS2CPacket(channel.barId(),
                "mcacrime.capture.channeling", channel.elapsed(), channel.requiredTicks,
                ActionProgressS2CPacket.Phase.PROGRESS, "", Component.empty()));
    }

    private static void endBar(ServerPlayer kidnapper, CaptureChannel channel, boolean succeeded, String key) {
        CrimeNetwork.sendActionProgress(kidnapper, ActionProgressS2CPacket.ended(channel.barId(),
                "mcacrime.capture.channeling",
                succeeded ? ActionProgressS2CPacket.Phase.FINISHED : ActionProgressS2CPacket.Phase.CANCELLED,
                key, Component.empty()));
    }

    @Nullable
    private static String breakReason(ServerPlayer kidnapper, CaptureChannel channel, Entity target) {
        if (channel.isBroken()) {
            return "mcacrime.capture.broken.hit";
        }
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        if (CaptureChannel.brokeByMove(channel.startPos, kidnapper.position(), c.captureMaxMoveBlocks.get())) {
            return "mcacrime.capture.broken.moved";
        }
        if (CaptureChannel.outOfRange(kidnapper.position(), target.position(), c.captureMaxRangeBlocks.get())) {
            return "mcacrime.capture.broken.range";
        }
        if (c.captureRequireLineOfSight.get() && !kidnapper.hasLineOfSight(target)) {
            return "mcacrime.capture.broken.los";
        }
        return null;
    }

    @Nullable
    private static Entity resolveTarget(ServerPlayer kidnapper, UUID target) {
        return kidnapper.level() instanceof ServerLevel level ? level.getEntity(target) : null;
    }
}

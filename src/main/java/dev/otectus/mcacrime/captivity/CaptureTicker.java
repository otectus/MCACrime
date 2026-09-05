package dev.otectus.mcacrime.captivity;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.network.ActionProgressS2CPacket;
import dev.otectus.mcacrime.network.CrimeNetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Drives in-progress capture channels (spec §8.2): each server tick it advances every {@link CaptureChannel}
 * and applies the break conditions (kidnapper hit / moved too far / target out of range or line of sight),
 * completing a finished channel into {@link CustodyService#capture}. Bounded by the (small) number of active
 * channels — never a world scan.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
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
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
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
                CaptureChannels.cancel(kidnapperId);
                if (target instanceof LivingEntity living) {
                    if (CrimeItems.consumeRestraint(kidnapper, channel.restraint)) {
                        CustodyService.capture(kidnapper, living, channel.restraint);
                        endBar(kidnapper, channel, true, "mcacrime.capture.done");
                    } else {
                        endBar(kidnapper, channel, false, "mcacrime.capture.broken.restraint");
                    }
                }
            } else if (channel.elapsed() % PROGRESS_INTERVAL_TICKS == 0) {
                progressBar(kidnapper, channel);
            }
        }
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
        CrimeNetwork.sendActionProgress(kidnapper, new ActionProgressS2CPacket(channelId(channel),
                "mcacrime.capture.channeling", channel.elapsed(), channel.requiredTicks,
                ActionProgressS2CPacket.Phase.PROGRESS, "", net.minecraft.network.chat.Component.empty()));
    }

    private static void endBar(ServerPlayer kidnapper, CaptureChannel channel, boolean succeeded, String key) {
        CrimeNetwork.sendActionProgress(kidnapper, ActionProgressS2CPacket.ended(channelId(channel),
                "mcacrime.capture.channeling",
                succeeded ? ActionProgressS2CPacket.Phase.FINISHED : ActionProgressS2CPacket.Phase.CANCELLED,
                key));
    }

    /** A stable id for one channel, derived from the pair it binds rather than minted per tick. */
    private static UUID channelId(CaptureChannel channel) {
        return new UUID(channel.kidnapper.getMostSignificantBits() ^ channel.target.getLeastSignificantBits(),
                channel.target.getMostSignificantBits() ^ channel.kidnapper.getLeastSignificantBits());
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

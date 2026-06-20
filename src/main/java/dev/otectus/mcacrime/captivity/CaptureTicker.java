package dev.otectus.mcacrime.captivity;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
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
                kidnapper.displayClientMessage(Component.translatable("mcacrime.capture.broken.target_lost"), true);
                continue;
            }
            String breakKey = breakReason(kidnapper, channel, target);
            if (breakKey != null) {
                CaptureChannels.cancel(kidnapperId);
                kidnapper.displayClientMessage(Component.translatable(breakKey), true);
                continue;
            }
            channel.tick();
            if (channel.isComplete()) {
                CaptureChannels.cancel(kidnapperId);
                if (target instanceof LivingEntity living) {
                    CustodyService.capture(kidnapper, living, channel.restraint);
                }
            } else {
                kidnapper.displayClientMessage(Component.translatable("mcacrime.capture.channeling"), true);
            }
        }
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

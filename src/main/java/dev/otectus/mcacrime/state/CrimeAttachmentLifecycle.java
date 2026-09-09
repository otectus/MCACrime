package dev.otectus.mcacrime.state;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.detect.CrimeDetectionHandlers;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Reconcile terminal damage before copying the original player's final crime state. */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CrimeAttachmentLifecycle {
    private CrimeAttachmentLifecycle() {}

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        if (event.getOriginal() instanceof ServerPlayer player) {
            CrimeDetectionHandlers.reconcileBeforePlayerSave(player.getServer());
            CrimeAttachments.get(event.getEntity()).copyFrom(CrimeAttachments.get(player));
        }
    }
}

package dev.otectus.mcacrime.mask;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.crime.type.CrimeAwareness;
import dev.otectus.mcacrime.detect.WitnessChecker;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.state.CrimeCapabilities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The moment a mask comes off (0.7.0). Forge bus, server side, no packet and no mixin: taking a helmet
 * off is already an event, and the client already knows what is in a player's head slot.
 *
 * <p>Coming off in front of somebody is the only thing that makes a masked spree cost anything, so the
 * test is deliberately the ordinary witness scan rather than a bespoke one — the same line of sight,
 * the same awake-villager filter, the same family loyalty. If nobody is watching, the Heat stays
 * parked and the next unmask is checked the same way.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class MaskEventHandlers {

    private MaskEventHandlers() {
    }

    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (event.getSlot() != EquipmentSlot.HEAD || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        var c = McaCrimeConfig.COMMON;
        if (!c.maskEnabled.get() || !c.maskDefersHeat.get()) {
            return;
        }
        // Only a mask leaving the slot is a candidate — swapping one mask for another is still a
        // covered face, and a mask that breaks counts exactly like one taken off.
        if (!Masks.isMask(event.getFrom()) || Masks.isMask(event.getTo())) {
            return;
        }
        // A spectator is not visible and a corpse is not recognisable. Neither is a flush; the entries
        // stay parked and are checked again at the next unmask.
        if (player.isSpectator() || !player.isAlive() || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (CrimeCapabilities.get(player).map(data -> data.getPendingMaskedHeat().isEmpty()).orElse(true)) {
            return;
        }
        double radius = c.maskRemovalWitnessRadius.get();
        if (radius <= 0.0) {
            return; // an operator who set the radius to 0 has said an unmask is never seen
        }
        CrimeAwareness awareness = new CrimeAwareness(radius, 0.0, 0.6, false, 16);
        if (WitnessChecker.resolve(level, player, null, awareness).witnessed()) {
            CrimeState.flushDeferredHeat(player);
        }
    }
}

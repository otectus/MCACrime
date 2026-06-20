package dev.otectus.mcacrime.state;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge-bus capability lifecycle: attach {@link PlayerCrimeData} to players, and copy it across
 * death/dimension respawns (spec §7.1 "death does not clear"). Copying on {@code PlayerEvent.Clone} is
 * what makes a jail sentence survivable-but-not-escapable-by-suicide in later phases, and keeps
 * Karma/Heat intact through death now.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CrimeCapabilityEvents {

    private CrimeCapabilityEvents() {
    }

    @SubscribeEvent
    public static void onAttachCapabilities(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof Player) {
            PlayerCrimeDataProvider provider = new PlayerCrimeDataProvider();
            event.addCapability(CrimeCapabilities.ID, provider);
            event.addListener(provider::invalidate);
        }
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        // The original player's caps are invalidated on death; revive to read, then re-invalidate.
        event.getOriginal().reviveCaps();
        CrimeCapabilities.get(event.getOriginal()).ifPresent(old ->
                CrimeCapabilities.get(event.getEntity()).ifPresent(fresh -> fresh.copyFrom(old)));
        event.getOriginal().invalidateCaps();
    }
}

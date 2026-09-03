package dev.otectus.mcacrime.state;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;

import java.util.Optional;

/** Holds the {@link PlayerCrimeData} capability token and registration (spec §2). */
public final class CrimeCapabilities {

    public static final Capability<PlayerCrimeData> PLAYER_CRIME =
            CapabilityManager.get(new CapabilityToken<>() {
            });

    public static final ResourceLocation ID = new ResourceLocation(McaCrime.MOD_ID, "player_crime");

    private CrimeCapabilities() {
    }

    /** Registered on the mod event bus. */
    public static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.register(PlayerCrimeData.class);
    }

    public static Optional<PlayerCrimeData> get(Player player) {
        return player.getCapability(PLAYER_CRIME).resolve();
    }
}

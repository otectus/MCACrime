package dev.otectus.mcacrime.crime.type;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.resources.ResourceLocation;

/** The built-in crime-type ids — one source of truth for the classifier, builtins, and shipped JSON. */
public final class CrimeIds {

    public static final ResourceLocation HARM_VILLAGER = new ResourceLocation(McaCrime.MOD_ID, "harm_villager");
    public static final ResourceLocation KILL_VILLAGER = new ResourceLocation(McaCrime.MOD_ID, "kill_villager");
    public static final ResourceLocation ASSAULT_GUARD = new ResourceLocation(McaCrime.MOD_ID, "assault_guard");

    private CrimeIds() {
    }
}

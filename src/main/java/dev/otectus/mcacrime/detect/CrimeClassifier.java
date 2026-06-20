package dev.otectus.mcacrime.detect;

import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/** Maps a detected event + victim to a crime-type id (spec §5.1). */
public final class CrimeClassifier {

    private CrimeClassifier() {
    }

    /** Harming a guard is assault_guard; harming any other MCA villager is harm_villager. */
    public static ResourceLocation classifyHarm(LivingEntity victim) {
        return McaCompat.isGuard(victim) ? CrimeIds.ASSAULT_GUARD : CrimeIds.HARM_VILLAGER;
    }

    /** Killing any MCA villager (guards included) is kill_villager. */
    public static ResourceLocation classifyKill(LivingEntity victim) {
        return CrimeIds.KILL_VILLAGER;
    }
}

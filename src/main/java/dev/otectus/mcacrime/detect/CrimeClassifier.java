package dev.otectus.mcacrime.detect;

import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/** Maps a detected event + victim to a crime-type id (spec §5.1). */
public final class CrimeClassifier {

    private CrimeClassifier() {
    }

    /**
     * Harming a guard is assault_guard; harming another player is assault_player when PvP crime is on;
     * harming any other protected entity is harm_villager.
     *
     * <p>Player victims get their own ids rather than borrowing the villager ones. A ledger row reading
     * "assaulting a villager" for a fight between two players would be wrong on the dossier, wrong in
     * the dialogue that quotes it, and wrong to any companion mod mapping crime types to incidents.
     */
    public static ResourceLocation classifyHarm(LivingEntity victim) {
        if (victim instanceof net.minecraft.server.level.ServerPlayer) {
            return CrimeIds.ASSAULT_PLAYER;
        }
        return McaCompat.isGuard(victim) ? CrimeIds.ASSAULT_GUARD : CrimeIds.HARM_VILLAGER;
    }

    /** Killing any MCA villager (guards included) is kill_villager; killing a player is murder_player. */
    public static ResourceLocation classifyKill(LivingEntity victim) {
        return victim instanceof net.minecraft.server.level.ServerPlayer
                ? CrimeIds.MURDER_PLAYER
                : CrimeIds.KILL_VILLAGER;
    }
}

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

    /**
     * Killing a guard is kill_guard, any other villager is kill_villager, and a player is
     * murder_player.
     *
     * <p>The guard branch is 0.7.5 §3.19's "offence seam", and it is deliberately the only change the
     * capital feature makes to detection. Until it existed, killing a guard was indistinguishable
     * from killing a farmer even though the <em>harm</em> branch above already asked the same
     * question — so the ledger, the dossier, every witness rule and every companion mod read one
     * sentence where there were two crimes.
     *
     * <p>What it does <b>not</b> do is decide a sentence. A capital sentence is produced at arrest by
     * {@code ledger/CapitalSentenceService}, from an unresolved case of this id and a configuration
     * that permits it; a crime id on its own has never sentenced anybody.
     *
     * <p>Precedence is elsewhere on purpose: a mugging that turns lethal is classified by
     * {@code detect/DamageIncidentService} as {@code mcacrime:mugging_murder} and never reaches here,
     * so robbing a guard to death is a mugging murder and is not capital.
     */
    public static ResourceLocation classifyKill(LivingEntity victim) {
        if (victim instanceof net.minecraft.server.level.ServerPlayer) {
            return CrimeIds.MURDER_PLAYER;
        }
        return McaCompat.isGuard(victim) ? CrimeIds.KILL_GUARD : CrimeIds.KILL_VILLAGER;
    }
}

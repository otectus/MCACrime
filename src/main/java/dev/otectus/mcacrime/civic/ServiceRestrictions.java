package dev.otectus.mcacrime.civic;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.McaCrimeApi;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.api.model.CrimePublicView;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.detect.CrimeCommunityResolver;
import dev.otectus.mcacrime.memory.VictimCrimeMemory;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The world-facing half of {@link ServiceRestrictionPolicy}: gathering the five facts the rule needs
 * and asking it.
 *
 * <h2>Where the facts come from, and why each one</h2>
 *
 * <ul>
 *   <li><b>The public view</b> ({@code McaCrimeApi.publicView}) supplies the band, the wanted flag and
 *       the count of open cases. It is the projection with the knowledge rule already applied, which
 *       is the whole reason it is used instead of the ledger: a villager may only refuse over
 *       something the settlement actually knows, and the ledger knows things nobody saw.</li>
 *   <li><b>The villager's own memory</b> supplies the grievance, decayed through the existing
 *       {@code VictimCrimeMemory} clock. Nothing is cached and no separate expiry is kept, so the
 *       refusal ends exactly when the memory fades — reference §11.5's "do not retain a stale cached
 *       ban after pardon or expiry" holds by construction rather than by a sweep.</li>
 * </ul>
 *
 * <p>Off by default and inert when off: {@link #enabled()} is one boolean read, and every entry point
 * returns {@link ServiceRestrictionPolicy.Decision#allowed()} without touching the world when it is
 * false.
 */
public final class ServiceRestrictions {

    private ServiceRestrictions() {
    }

    /** Whether optional service restrictions are switched on. Any throw reads as off. */
    public static boolean enabled() {
        try {
            return McaCrimeConfig.COMMON.townsteadEnabled.get()
                    && McaCrimeConfig.COMMON.townsteadServiceRestrictions.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Whether {@code provider} may refuse {@code subject} this service right now.
     *
     * @param provider the villager or fence being asked; their own memory is what makes a refusal
     *                 personal rather than collective
     * @param subject  who is asking
     */
    public static ServiceRestrictionPolicy.Decision decide(@Nullable ServerLevel level,
                                                           @Nullable Entity provider,
                                                           @Nullable UUID subject,
                                                           @Nullable ServiceKind kind) {
        if (!enabled() || level == null || provider == null || subject == null || kind == null
                || kind.essential()) {
            return ServiceRestrictionPolicy.Decision.allowed();
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return ServiceRestrictionPolicy.Decision.allowed();
        }
        try {
            CrimeCommunityKey community = CrimeCommunityResolver.resolve(provider, level).orElse(null);
            Band band = Band.GREY;
            boolean wanted = false;
            int open = 0;
            if (community != null) {
                CrimePublicView view = McaCrimeApi.publicView(server, community, subject).orElse(null);
                if (view != null) {
                    band = view.band();
                    wanted = view.wanted();
                    open = view.openIncidents();
                }
            }
            return ServiceRestrictionPolicy.decide(kind, band, wanted, open,
                    grievance(server, provider.getUUID(), subject, level.getGameTime()));
        } catch (Throwable t) {
            // A refusal is the optional half of an optional feature. Anything unexpected serves the
            // customer rather than locking them out of a shop for a reason nobody can see.
            McaCrime.LOGGER.debug("MCA: Crime — service restriction check failed; serving anyway", t);
            return ServiceRestrictionPolicy.Decision.allowed();
        }
    }

    /**
     * How badly this villager, personally, still holds it against this person: 0..1.
     *
     * <p>The maximum of the decayed fear and anger over every memory naming them, which is the same
     * pair {@code ApologyStatus} and the reaction layer read. Maximum rather than sum: a villager who
     * was robbed once and witnessed a second robbery is not twice as unwilling, and summing would make
     * a long memory list refuse everything forever.
     */
    public static double grievance(@Nullable MinecraftServer server, @Nullable UUID villager,
                                   @Nullable UUID subject, long now) {
        if (server == null || villager == null || subject == null) {
            return 0.0D;
        }
        double decay = McaCrimeConfig.COMMON.memoryDecayMultiplier.get();
        double worst = 0.0D;
        for (VictimCrimeMemory memory : CrimeWorldData.get(server).villagerProfile(villager)
                .map(profile -> profile.crimeMemories()).orElse(java.util.List.of())) {
            if (!subject.equals(memory.perpetrator())) {
                continue;
            }
            worst = Math.max(worst, Math.max(memory.fearAt(now, decay), memory.angerAt(now, decay)));
        }
        return worst;
    }
}

package dev.otectus.mcacrime.detect;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.compat.McaCompat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Turns "which villager was this, and where" into a {@link CrimeCommunityKey}.
 *
 * <p>The two halves come from different places: the village id from MCA (through {@code McaCompat},
 * the only file that touches MCA), and the dimension from the level. Neither alone identifies a
 * community, because MCA allocates village ids per-dimension.
 *
 * <p>The dimension is taken from <b>the victim's own level</b>, not the offender's. They can differ:
 * an arrow loosed through a nether portal, or a projectile whose owner has already changed dimension
 * by the time the damage resolves. Attributing a Nether villager's assault to the overworld would put
 * the case against a community that does not exist.
 */
public final class CrimeCommunityResolver {

    private CrimeCommunityResolver() {
    }

    /**
     * The pure half, with no entity involved — this is the whole rule, and it is what the tests
     * exercise. An absent village id means the crime happened outside any known community, which is
     * a legitimate outcome and not an error.
     */
    public static Optional<CrimeCommunityKey> resolve(@Nullable ResourceLocation dimension,
                                                      OptionalInt villageId) {
        if (dimension == null || villageId == null || villageId.isEmpty()) {
            return Optional.empty();
        }
        return CrimeCommunityKey.of(dimension, villageId.getAsInt());
    }

    /**
     * The live overload. {@code victim} may be null for a victimless crime such as a jailbreak, which
     * has no community this round — resolving one from the offender's position would be a guess, and
     * a guessed community is worse than none.
     *
     * <p>A victim with no home village is charged to the nearest village whose border, expanded by
     * {@code detection.communitySearchRadius}, contains them (0.7.5). Until then such a case named no
     * community and MCA: Reputation refused it as invalid — which is what happened to every assault
     * on a Guard Villagers guard bridged by MCA: Mob Compatibility, whose hidden stand-in has no
     * residency. The victim's own position is used, never the offender's: it is the victim's village
     * that has a grievance. MCA: Reputation resolves the same case the same way, so both ledgers name
     * one community for one deed.
     */
    public static Optional<CrimeCommunityKey> resolve(@Nullable Entity victim, ServerLevel fallbackLevel) {
        if (victim == null) {
            return Optional.empty();
        }
        ServerLevel victimLevel = victim.level() instanceof ServerLevel level ? level : fallbackLevel;
        ResourceLocation dimension = victimLevel == null ? null : victimLevel.dimension().location();
        OptionalInt home = McaCompat.getHomeVillageId(victim);
        if (home.isEmpty() && victimLevel != null && victim.level() == victimLevel) {
            home = McaCompat.findNearestVillageId(victimLevel, victim.blockPosition(), communitySearchRadius());
        }
        return resolve(dimension, home);
    }

    private static int communitySearchRadius() {
        try {
            return dev.otectus.mcacrime.McaCrimeConfig.COMMON.communitySearchRadius.get();
        } catch (Throwable unloaded) {
            return 0; // no config loaded means no game running; the pre-0.7.5 answer is "home only"
        }
    }
}

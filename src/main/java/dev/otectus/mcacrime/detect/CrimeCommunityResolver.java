package dev.otectus.mcacrime.detect;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.compat.McaCompat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import org.jetbrains.annotations.Nullable;
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
     */
    public static Optional<CrimeCommunityKey> resolve(@Nullable Entity victim, ServerLevel fallbackLevel) {
        if (victim == null) {
            return Optional.empty();
        }
        ResourceLocation dimension = victim.level() instanceof ServerLevel victimLevel
                ? victimLevel.dimension().location()
                : fallbackLevel == null ? null : fallbackLevel.dimension().location();
        return resolve(dimension, McaCompat.getHomeVillageId(victim));
    }
}

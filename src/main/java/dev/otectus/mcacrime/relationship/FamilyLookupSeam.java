package dev.otectus.mcacrime.relationship;

import java.util.List;
import java.util.UUID;

/**
 * One direct family link, asked for by subject and tier.
 *
 * <p>This exists so {@link FamilyGraph#compose} can be a pure function. The live implementation reads
 * MCA through {@code McaCompat}; a test supplies a map. Without the seam the composition rules — in
 * particular the in-law derivation, which asks a <em>second</em> villager for its parents — could only
 * be exercised with a running MCA, which is exactly the dependency this mod refuses to take on.
 *
 * <p>Only the direct tiers are ever asked for: {@link FamilyTier#IN_LAW} is derived, never looked up.
 */
@FunctionalInterface
public interface FamilyLookupSeam {

    /**
     * The UUIDs directly related to {@code subject} in {@code tier}. Never null; empty when unknown,
     * which is also the correct answer when the relationship data is unavailable.
     */
    List<UUID> lookup(UUID subject, FamilyTier tier);
}

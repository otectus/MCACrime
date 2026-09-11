package dev.otectus.mcacrime.relationship;

import dev.otectus.mcacrime.compat.McaCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who somebody's relatives are, flattened into one map of UUID to {@link FamilyTier}.
 *
 * <p>MCA answers five separate questions — partner, parents, children, siblings, close relatives — and
 * every caller that wants "is this villager family?" would otherwise ask all five and merge the
 * answers itself, differently each time. Asking once, here, is what makes the loyalty rule and the
 * config scope mean the same thing everywhere.
 *
 * <p>The composition is a pure function ({@link #compose}) over a {@link FamilyLookupSeam}; only
 * {@link #relativesOf} touches MCA or Minecraft. In-laws are <em>derived</em> rather than looked up:
 * MCA has no in-law accessor, so a spouse's parents and siblings are asked for by asking the spouse.
 * That second hop is the reason the seam is keyed on a subject UUID rather than being a bare supplier.
 *
 * <p>Everything degrades to an empty map: no relationship API, no server level, an unresolvable
 * spouse. An empty map means "no family", which is the answer that makes every caller behave exactly
 * as it did before this class existed.
 */
public final class FamilyGraph {

    /** The order a relative is classified in when they qualify under more than one tier. */
    private static final List<FamilyTier> PRIORITY = List.of(FamilyTier.SPOUSE, FamilyTier.PARENT,
            FamilyTier.CHILD, FamilyTier.SIBLING, FamilyTier.IN_LAW, FamilyTier.EXTENDED);

    private FamilyGraph() {
    }

    /**
     * The relatives of {@code subject} that fall inside {@code scope}, read from the installed MCA.
     *
     * @param generations how far out {@link FamilyTier#EXTENDED} reaches; ignored by the other tiers
     */
    public static Map<UUID, FamilyTier> relativesOf(Entity subject, Set<FamilyTier> scope, int generations) {
        if (subject == null || scope == null || scope.isEmpty() || !McaCompat.isRelationshipApiAvailable()) {
            return Map.of();
        }
        if (!(subject.level() instanceof ServerLevel level)) {
            return Map.of();
        }
        int depth = Math.max(1, generations);
        FamilyLookupSeam seam = (id, tier) -> {
            Entity entity = id.equals(subject.getUUID()) ? subject : level.getEntity(id);
            if (entity == null) {
                return List.of();
            }
            return switch (tier) {
                case SPOUSE -> McaCompat.getSpouseUuid(entity).map(List::of).orElseGet(List::of);
                case PARENT -> McaCompat.getParentUuids(entity);
                case CHILD -> McaCompat.getChildUuids(entity);
                case SIBLING -> McaCompat.getSiblingUuids(entity);
                case EXTENDED -> McaCompat.getCloseRelativeUuids(entity, depth);
                // Derived from the spouse's own parents and siblings; never asked of MCA directly.
                case IN_LAW -> List.of();
            };
        };
        return compose(subject.getUUID(), scope, seam);
    }

    /**
     * The pure half: merges the direct tiers and derives the in-laws, keeping the closest tier when a
     * UUID appears twice and never listing the subject as their own relative.
     */
    public static Map<UUID, FamilyTier> compose(UUID subject, Set<FamilyTier> scope, FamilyLookupSeam seam) {
        if (subject == null || scope == null || scope.isEmpty() || seam == null) {
            return Map.of();
        }
        Set<FamilyTier> wanted = EnumSet.copyOf(scope);
        Map<FamilyTier, List<UUID>> byTier = new LinkedHashMap<>();
        // Asked for once, and only when something needs it: the in-law derivation needs the spouse
        // even when the spouse tier itself is out of scope.
        List<UUID> spouses = wanted.contains(FamilyTier.SPOUSE) || wanted.contains(FamilyTier.IN_LAW)
                ? lookup(seam, subject, FamilyTier.SPOUSE)
                : List.of();

        for (FamilyTier tier : List.of(FamilyTier.SPOUSE, FamilyTier.PARENT, FamilyTier.CHILD,
                FamilyTier.SIBLING, FamilyTier.EXTENDED)) {
            if (wanted.contains(tier)) {
                byTier.put(tier, tier == FamilyTier.SPOUSE ? spouses : lookup(seam, subject, tier));
            }
        }
        if (wanted.contains(FamilyTier.IN_LAW)) {
            List<UUID> inLaws = new ArrayList<>();
            for (UUID spouse : spouses) {
                inLaws.addAll(lookup(seam, spouse, FamilyTier.PARENT));
                inLaws.addAll(lookup(seam, spouse, FamilyTier.SIBLING));
            }
            byTier.put(FamilyTier.IN_LAW, inLaws);
        }

        Map<UUID, FamilyTier> out = new LinkedHashMap<>();
        for (FamilyTier tier : PRIORITY) {
            for (UUID id : byTier.getOrDefault(tier, List.of())) {
                if (id != null && !id.equals(subject)) {
                    out.putIfAbsent(id, tier);
                }
            }
        }
        return Map.copyOf(out);
    }

    private static List<UUID> lookup(FamilyLookupSeam seam, UUID subject, FamilyTier tier) {
        List<UUID> found = seam.lookup(subject, tier);
        return found == null ? List.of() : found;
    }
}

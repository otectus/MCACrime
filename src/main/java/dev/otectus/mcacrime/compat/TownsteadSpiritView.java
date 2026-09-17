package dev.otectus.mcacrime.compat;

import java.util.Map;

/**
 * A village's Townstead spirit: what the settlement is made of, and how strongly.
 *
 * <p>Read because a village's character is context for how crime lands there, never as a stand-in for
 * MCA: Crime's own community standing — those are different quantities with different owners, and
 * folding one into the other would make a village's architecture change its crime record.
 *
 * <p>{@code classification} is Townstead's own readout constant, lowercased at the boundary
 * ({@code settlement}, {@code single}, {@code blend}, {@code mixed}).
 */
public record TownsteadSpiritView(
        int villageId,
        Map<String, Integer> perSpirit,
        int total,
        int contributingBuildings,
        int tierIndex,
        String classification,
        String primarySpiritId,
        String secondarySpiritId) {

    public TownsteadSpiritView {
        perSpirit = perSpirit == null ? Map.of() : Map.copyOf(perSpirit);
        classification = classification == null ? "" : classification;
        primarySpiritId = primarySpiritId == null ? "" : primarySpiritId;
        secondarySpiritId = secondarySpiritId == null ? "" : secondarySpiritId;
    }

    public int pointsFor(String spiritId) {
        Integer points = perSpirit.get(spiritId);
        return points == null ? 0 : points;
    }

    public String describe() {
        return "spirit: village " + villageId + " tier " + tierIndex
                + (classification.isEmpty() ? "" : " " + classification)
                + ", total " + total + " over " + contributingBuildings + " building(s)"
                + (primarySpiritId.isEmpty() ? "" : ", primary " + primarySpiritId)
                + (secondarySpiritId.isEmpty() ? "" : ", secondary " + secondarySpiritId);
    }
}

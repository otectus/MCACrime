package dev.otectus.mcacrime.compat;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;

/**
 * One MCA Capitals capital, as MCA: Crime is allowed to hold it (0.7.8).
 *
 * <p>Copied out of Capitals' own record at read time: no Capitals value is ever stored in it, so it can
 * be compared, logged and kept for one decision without naming a Capitals type.
 *
 * @param capitalId         Capitals' own id for the capital
 * @param villageId         the MCA village the capital is seated in
 * @param dimensionId       that village's dimension as Capitals records it; empty when Capitals did not
 *                          say, in which case the dimension the question was asked in is the answer
 * @param state             Capitals' state name ({@code PENDING}, {@code FOUNDED}, {@code ACTIVE}),
 *                          compared by name so a new state widens the vocabulary instead of breaking it
 * @param playerSovereignId the player on the throne, or null when the throne is a villager's or vacant
 */
public record CapitalView(UUID capitalId, int villageId, String dimensionId, String state,
                          @Nullable UUID playerSovereignId) {

    /** Capitals' name for a capital with a reigning court. */
    public static final String STATE_ACTIVE = "ACTIVE";

    public CapitalView {
        Objects.requireNonNull(capitalId, "capitalId");
        dimensionId = dimensionId == null ? "" : dimensionId;
        state = state == null ? "" : state;
    }

    /** Whether the capital has a reigning court, which every royal act needs. */
    public boolean active() {
        return STATE_ACTIVE.equals(state);
    }

    /** Whether {@code player} is the player sovereign of this capital right now. */
    public boolean ruledBy(@Nullable UUID player) {
        return player != null && player.equals(playerSovereignId);
    }

    /**
     * Whether this record belongs to the dimension a question was asked in. An empty dimension is
     * trusted to the lookup, which already filters by level; a different non-empty one is a mismatch.
     */
    public boolean inDimension(String dimension) {
        return dimensionId.isEmpty() || dimensionId.equals(dimension);
    }
}

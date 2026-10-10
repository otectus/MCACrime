package dev.otectus.mcacrime.compat;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * The single-seat court offices of one capital (0.7.8), copied out of Capitals' record.
 *
 * <p>Used for one thing only: deciding which villagers may receive the sovereign's decree. Holding an
 * office here grants no policing power; a royal decree is still enforced by the village's own guards.
 */
public record CapitalCourtView(@Nullable UUID hand, @Nullable UUID commander, @Nullable UUID masterOfLaws,
                               @Nullable UUID herald) {

    /** Whether {@code villager} holds one of the four offices. */
    public boolean holdsOffice(@Nullable UUID villager) {
        return villager != null && (villager.equals(hand) || villager.equals(commander)
                || villager.equals(masterOfLaws) || villager.equals(herald));
    }
}

package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.crime.Band;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * Everything the law currently says about one person, answered once (0.5.1).
 *
 * <p>Three questions used to be asked separately and could disagree: may they be attacked, may they
 * be killed, and may somebody be paid for it. A hunter acting on the first two and then being charged
 * with assault by a subsystem that only knew the third is exactly the bug this record exists to make
 * impossible. {@code OutlawResolver} produces one of these and every caller reads a field.
 */
public record OutlawStatus(boolean lawfulCombatTarget, boolean lethalForceLawful, boolean bountyEligible,
                           LegalBasis basis, long heat, long karma, Band band,
                           @Nullable UUID warrantId, long warrantRevision) {

    /** Nobody may lawfully be attacked. The state the overwhelming majority of players are in. */
    public static OutlawStatus none(long heat, long karma, Band band) {
        return new OutlawStatus(false, false, false, LegalBasis.NONE, heat, karma, band, null, 0L);
    }
}

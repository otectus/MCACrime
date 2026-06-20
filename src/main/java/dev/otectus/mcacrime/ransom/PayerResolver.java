package dev.otectus.mcacrime.ransom;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure payer-priority resolution (spec §8.5): given the captive's candidate relations (each already tagged
 * with its tier, adult status, and whether it is reachable/online to actually pay), pick the first by the
 * §8.5 order — spouse → parent → adult child → sibling → close relative → close friend — else downgrade to
 * the village-authority fallback (when enabled). No game/MCA deps, so the ordering is unit-testable; the
 * impure candidate-gathering (which calls {@code McaCompat}) lives in {@code RansomService}.
 */
public final class PayerResolver {

    /** A potential payer: its UUID (null for the village authority), tier, adult flag, and reachability. */
    public record Candidate(@Nullable UUID uuid, PayerTier tier, boolean isAdult, boolean reachable) {
    }

    private PayerResolver() {
    }

    public static Optional<Candidate> resolve(List<Candidate> candidates, boolean villageFallbackEnabled) {
        for (PayerTier tier : PayerTier.familyOrder()) {
            for (Candidate c : candidates) {
                if (c.tier() != tier || !c.reachable()) {
                    continue;
                }
                if (tier == PayerTier.ADULT_CHILD && !c.isAdult()) {
                    continue; // only an adult child can be made to pay
                }
                return Optional.of(c);
            }
        }
        return villageFallbackEnabled
                ? Optional.of(new Candidate(null, PayerTier.VILLAGE_AUTHORITY, true, true))
                : Optional.empty();
    }
}

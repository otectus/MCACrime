package dev.otectus.mcacrime.relationship;

import java.util.Locale;
import java.util.Set;

/**
 * Whether one relative who saw a crime keeps quiet about it.
 *
 * <p>Pure, deterministic, and with no RNG on purpose. Two identical crimes minutes apart must produce
 * the same answer, or the feature reads as a bug rather than as a family choosing sides: a player who
 * watched their wife stay silent yesterday and report them today has no way to tell which of the two
 * was the intended behaviour. The same reasoning is why MCA's transient {@code Mood} is deliberately
 * not an input.
 *
 * <p>The score is a sum — hearts, a tier bonus, a personality adjustment — compared against one
 * threshold. Above it, and with no hard exclusion, the relative declines to report. The six hard
 * exclusions are checked first and are absolute: no score, however high, overrides them, because each
 * of them describes somebody who either cannot decline or has an obvious reason not to.
 */
public final class FamilyLoyalty {

    /**
     * Everything the decision is made from.
     *
     * @param tier                   how the witness is related to the offender
     * @param hearts                 MCA relationship hearts the witness holds toward the offender
     * @param personality            the witness's MCA personality name, or null when unreadable
     * @param crimeHeat              the offender's Heat at the moment of the act
     * @param witnessIsVictim        the witness is the person the crime was committed against
     * @param victimIsWitnessRelative the victim is the witness's own family
     * @param witnessIsResponder     the witness is a guard or archer
     * @param witnessIsAdult         the witness is old enough to make the choice
     * @param scopeIncludesTier      the operator allows this tier to decline
     */
    public record Input(FamilyTier tier, int hearts, String personality, long crimeHeat,
                        boolean witnessIsVictim, boolean victimIsWitnessRelative,
                        boolean witnessIsResponder, boolean witnessIsAdult, boolean scopeIncludesTier) {
    }

    /** The operator's half, built from config at the call site so this class never reads config. */
    public record Settings(double heartsWeight, int threshold, int tierBonusSpouse, int tierBonusImmediate,
                           int tierBonusExtended, Set<String> loyalPersonalities,
                           Set<String> lawfulPersonalities, int personalityBonus, int personalityPenalty,
                           long maxCrimeHeat) {

        public Settings {
            loyalPersonalities = normalize(loyalPersonalities);
            lawfulPersonalities = normalize(lawfulPersonalities);
        }

        private static Set<String> normalize(Set<String> names) {
            if (names == null || names.isEmpty()) {
                return Set.of();
            }
            return names.stream()
                    .filter(name -> name != null && !name.isBlank())
                    .map(name -> name.trim().toUpperCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    /**
     * The answer, with the score that produced it and a short reason. The reason is a stable
     * identifier for logs and tests, not a translation key: nothing shows it to a player, and inventing
     * a lang key for "the tier was out of scope" would put operator diagnostics in the chat log.
     */
    public record Decision(boolean loyal, int score, String reasonKey) {
    }

    /** Reason identifiers. One per hard exclusion, plus the two score outcomes. */
    public static final String REASON_WITNESS_IS_VICTIM = "witness_is_victim";
    public static final String REASON_VICTIM_IS_RELATIVE = "victim_is_relative";
    public static final String REASON_RESPONDER = "responder";
    public static final String REASON_NOT_ADULT = "not_adult";
    public static final String REASON_TIER_OUT_OF_SCOPE = "tier_out_of_scope";
    public static final String REASON_HEAT_TOO_HIGH = "heat_too_high";
    public static final String REASON_BELOW_THRESHOLD = "below_threshold";
    public static final String REASON_LOYAL = "loyal";

    private FamilyLoyalty() {
    }

    /** The decision. The score is computed either way, so a "why not" is answerable from one call. */
    public static Decision evaluate(Input input, Settings settings) {
        if (input == null || settings == null || input.tier() == null) {
            return new Decision(false, 0, REASON_TIER_OUT_OF_SCOPE);
        }
        int score = score(input, settings);

        // Hard exclusions, in the order they are most obviously true. Each one describes somebody who
        // is not free to look the other way, so none of them is weighed against the score.
        if (input.witnessIsVictim()) {
            return new Decision(false, score, REASON_WITNESS_IS_VICTIM);
        }
        if (input.victimIsWitnessRelative()) {
            return new Decision(false, score, REASON_VICTIM_IS_RELATIVE);
        }
        if (input.witnessIsResponder()) {
            return new Decision(false, score, REASON_RESPONDER);
        }
        if (!input.witnessIsAdult()) {
            return new Decision(false, score, REASON_NOT_ADULT);
        }
        if (!input.scopeIncludesTier()) {
            return new Decision(false, score, REASON_TIER_OUT_OF_SCOPE);
        }
        if (input.crimeHeat() > settings.maxCrimeHeat()) {
            return new Decision(false, score, REASON_HEAT_TOO_HIGH);
        }
        return score >= settings.threshold()
                ? new Decision(true, score, REASON_LOYAL)
                : new Decision(false, score, REASON_BELOW_THRESHOLD);
    }

    /** {@code heartsWeight * hearts + tierBonus + personalityModifier}, rounded once at the end. */
    public static int score(Input input, Settings settings) {
        double fromHearts = settings.heartsWeight() * input.hearts();
        return (int) Math.round(fromHearts) + tierBonus(input.tier(), settings)
                + personalityModifier(input.personality(), settings);
    }

    private static int tierBonus(FamilyTier tier, Settings settings) {
        return switch (tier) {
            case SPOUSE -> settings.tierBonusSpouse();
            case PARENT, CHILD, SIBLING -> settings.tierBonusImmediate();
            case EXTENDED, IN_LAW -> settings.tierBonusExtended();
        };
    }

    /**
     * A personality named in both lists cancels out rather than picking a winner. The configuration is
     * contradictory, {@code ConfigValidator} says so, and neither half of a contradiction deserves to
     * silently decide the outcome.
     */
    private static int personalityModifier(String personality, Settings settings) {
        if (personality == null || personality.isBlank()) {
            return 0;
        }
        String name = personality.trim().toUpperCase(Locale.ROOT);
        int modifier = 0;
        if (settings.loyalPersonalities().contains(name)) {
            modifier += settings.personalityBonus();
        }
        if (settings.lawfulPersonalities().contains(name)) {
            modifier -= settings.personalityPenalty();
        }
        return modifier;
    }
}

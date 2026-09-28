package dev.otectus.mcacrime.enchantment;

/**
 * How much of a captor's damage Imbue moves onto their prisoners (0.7.5 §3.10, M6.1).
 *
 * <p>Pure arithmetic in a class of its own, because this is where the three confirmed upstream
 * defects live and every one of them is an arithmetic defect ({@code event/ModServerEvents.java:399-446}):
 *
 * <ol>
 *   <li><b>Duplicate recipients.</b> Upstream adds the same prisoner once for their arm restraint and
 *       again for their leg restraint ({@code :411}, {@code :414}), so they are hurt twice <em>and</em>
 *       inflate the divisor. Here the caller passes a recipient <em>count</em> that has already been
 *       de-duplicated by subject, and {@link #distribute} has no way to count anybody twice.</li>
 *   <li><b>The accumulator is used inside the loop.</b> Upstream's running total means the n-th
 *       recipient absorbs the sum of every previous recipient's share, so k recipients take roughly k
 *       times the intended budget while the captor's relief stays clamped. Here the budget is one
 *       number, computed once, and each recipient takes an equal share of it.</li>
 *   <li><b>No level clamp.</b> Upstream computes {@code (level / 3) * 0.8} with no ceiling, so a
 *       command-set level of 40 transfers more than ten times the captor's health. Here the level is
 *       clamped by {@link EnchantmentApplicability#clampLevel} before it arrives and the fraction is
 *       capped again.</li>
 * </ol>
 *
 * <p>Non-finite input is rejected rather than propagated: a NaN or infinite damage amount from
 * another mod's handler produces {@link Distribution#none}, which changes the event by nothing at
 * all. The alternative is a prisoner with NaN health, which is exactly the class of report
 * (upstream #44) this integration is meant to end.
 */
public final class ImbueMath {

    /** The documented default fraction one level moves: upstream's {@code (1 / 3) * 0.8}. */
    public static final double DEFAULT_PER_LEVEL = 0.2666D;

    /** The documented cap on the total fraction, whatever the level. */
    public static final double DEFAULT_MAX_FRACTION = 0.8D;

    /**
     * One resolved distribution.
     *
     * @param retained      what the captor still takes; always finite and never above the original
     * @param transferred   the whole budget moved onto prisoners
     * @param perRecipient  what each prisoner takes; {@code transferred / recipients}
     * @param recipients    how many prisoners take a share
     */
    public record Distribution(float retained, float transferred, float perRecipient, int recipients) {

        /** Nothing moves: the captor takes what they were dealt. */
        public static Distribution none(float original) {
            return new Distribution(original, 0.0F, 0.0F, 0);
        }

        /** Whether anything is actually transferred. */
        public boolean moves() {
            return recipients > 0 && transferred > 0.0F && perRecipient > 0.0F;
        }
    }

    private ImbueMath() {
    }

    /**
     * Splits {@code original} between the captor and {@code recipients} prisoners.
     *
     * @param original      the damage the captor was dealt
     * @param level         the Imbue level, already clamped to the configured maximum
     * @param perLevel      {@code enchantments.imbueTransferPerLevel}
     * @param maxFraction   {@code enchantments.imbueMaxTransferFraction}
     * @param recipients    how many distinct prisoners this captor holds, already de-duplicated
     * @param maxRecipients {@code enchantments.imbueMaxRecipients}; more than this and the excess
     *                      simply do not share, rather than the budget growing
     */
    public static Distribution distribute(float original, int level, double perLevel, double maxFraction,
                                          int recipients, int maxRecipients) {
        if (!Float.isFinite(original) || original <= 0.0F || level <= 0 || recipients <= 0) {
            return Distribution.none(finite(original));
        }
        if (!Double.isFinite(perLevel) || !Double.isFinite(maxFraction)) {
            return Distribution.none(original);
        }
        int sharing = Math.min(recipients, Math.max(1, maxRecipients));
        double cap = Math.max(0.0D, Math.min(1.0D, maxFraction));
        double fraction = Math.max(0.0D, Math.min(cap, perLevel * level));
        float transferred = (float) (original * fraction);
        if (!Float.isFinite(transferred) || transferred <= 0.0F) {
            return Distribution.none(original);
        }
        transferred = Math.min(transferred, original);
        float perRecipient = transferred / sharing;
        if (!Float.isFinite(perRecipient) || perRecipient <= 0.0F) {
            return Distribution.none(original);
        }
        float retained = Math.max(0.0F, Math.min(original, original - transferred));
        return new Distribution(retained, transferred, perRecipient, sharing);
    }

    /** A finite stand-in for a non-finite amount: zero, which cannot hurt anybody. */
    private static float finite(float amount) {
        return Float.isFinite(amount) ? amount : 0.0F;
    }
}

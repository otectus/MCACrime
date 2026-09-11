package dev.otectus.mcacrime.enforcement;

/**
 * What it costs to buy a relative out of the rest of their sentence, and what happens when somebody
 * tries to pay it.
 *
 * <p>Both halves are pure. The price is arithmetic over five config numbers and two facts about the
 * prisoner, and the payment is a four-way decision over a balance, a charge and a release — neither
 * needs a server to be right, and the payment half is the one where being wrong costs a player
 * emeralds for nothing.
 *
 * <p>The price is built on <em>time remaining</em> rather than on the original sentence, matching the
 * self-bail handler: bail buys the future, and a family arriving at the end of a term should not be
 * quoted what they would have paid at the start. The compounding term is the other half of that
 * fairness — a household that keeps bailing out the same cousin pays more every time, so bail is
 * relief rather than a fee schedule.
 */
public final class BailQuote {

    /** Everything the screen shows, in one message. */
    public record Quote(long cost, String relativeName, String offenceKey, long remainingTicks,
                        String releaseConditionKey) {

        public Quote {
            cost = Math.max(0L, cost);
            relativeName = relativeName == null ? "" : relativeName;
            offenceKey = offenceKey == null ? "" : offenceKey;
            remainingTicks = Math.max(0L, remainingTicks);
            releaseConditionKey = releaseConditionKey == null ? "" : releaseConditionKey;
        }
    }

    /** The five pricing keys, snapshotted so the formula can be asserted without a loaded config. */
    public record Settings(int bailBase, double bailPerThousandTicks, int bailMin, int bailMax,
                           double bailRepeatMultiplier) {
    }

    /** How the payment attempt ended. */
    public enum Outcome {
        /** No charge was made; the price was sent for the player to look at. */
        QUOTED,
        /** The sentence ended between the quote and the payment. No charge. */
        ALREADY_RELEASED,
        /** The player cannot afford it. No charge. */
        INSUFFICIENT,
        /** Charged once, released once. */
        PAID
    }

    /**
     * The world-facing side of a payment, so {@link #pay} can be exercised without one.
     *
     * <p>{@code charge} returns whether the money actually moved; a currency that refuses at the last
     * moment must not produce a release, which is the one ordering mistake in this whole feature that
     * would give a sentence away for free.
     */
    public interface Payment {

        /** Whether the relative is still in custody right now, re-read at the moment of payment. */
        boolean inCustody();

        long balance();

        boolean charge(long cost);

        void release();
    }

    private BailQuote() {
    }

    /**
     * {@code clamp(round(base + perThousand * remaining/1000) * repeat^priorArrests, min, max)}.
     *
     * <p>The rounding happens before the compounding, so the multiplier applies to a whole number of
     * emeralds and a family can predict the next price from the last one.
     */
    public static long cost(long remainingTicks, int priorArrests, Settings settings) {
        long remaining = Math.max(0L, remainingTicks);
        int repeats = Math.max(0, priorArrests);
        double base = Math.round(settings.bailBase() + settings.bailPerThousandTicks() * (remaining / 1000.0D));
        double scaled = base * Math.pow(settings.bailRepeatMultiplier(), repeats);
        long rounded = Math.round(Math.min(scaled, (double) Long.MAX_VALUE / 2.0D));
        return Math.max(settings.bailMin(), Math.min(settings.bailMax(), rounded));
    }

    /** The release condition a player reads on the screen: time to serve, or release any moment now. */
    public static String releaseConditionKey(long remainingTicks) {
        return remainingTicks > 0L ? "mcacrime.bail.condition.sentence" : "mcacrime.bail.condition.imminent";
    }

    /**
     * One payment attempt.
     *
     * <p>Order is the whole contract: custody is checked, then the balance, then the charge, and the
     * release happens only after the charge reported success. A duplicate packet arrives after the
     * release has already removed the custody record, finds {@code inCustody() == false}, and charges
     * nothing — which is why this is idempotent without needing a token to make it so.
     *
     * @param confirmed false on the first click, which only produces a price
     */
    public static Outcome pay(Payment payment, long cost, boolean confirmed) {
        if (!payment.inCustody()) {
            return Outcome.ALREADY_RELEASED;
        }
        if (!confirmed) {
            return Outcome.QUOTED;
        }
        if (payment.balance() < cost) {
            return Outcome.INSUFFICIENT;
        }
        if (!payment.charge(cost)) {
            return Outcome.INSUFFICIENT;
        }
        payment.release();
        return Outcome.PAID;
    }
}

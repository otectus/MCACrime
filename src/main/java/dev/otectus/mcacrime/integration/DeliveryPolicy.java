package dev.otectus.mcacrime.integration;

/**
 * When to try a failed delivery again, and when to stop trying — as pure arithmetic, so the retry
 * behaviour can be tested without a server, a companion mod, or a clock.
 */
public final class DeliveryPolicy {

    /** How many doublings the backoff is allowed before it stops growing, to avoid overflow. */
    private static final int MAX_EXPONENT = 20;

    private DeliveryPolicy() {
    }

    /**
     * The game time of the next attempt after {@code attempts} failures.
     *
     * <p>Exponential, so a companion that is missing for an hour is asked once or twice rather than
     * every five seconds, and capped, so it is still asked eventually rather than drifting into never.
     */
    public static long nextAttemptTime(int attempts, long now, long baseDelayTicks, long maxDelayTicks) {
        long base = Math.max(1L, baseDelayTicks);
        long ceiling = Math.max(base, maxDelayTicks);
        int exponent = Math.min(Math.max(0, attempts), MAX_EXPONENT);
        long delay = base << exponent;
        // The shift can still overflow past MAX_EXPONENT on a large base; a negative result means we
        // have long since passed the ceiling anyway.
        if (delay <= 0L || delay > ceiling) {
            delay = ceiling;
        }
        return now + delay;
    }

    /**
     * What to do with an operation given how its delivery went.
     *
     * <p>An unretryable outcome dead-letters immediately rather than burning through the attempt
     * budget: the failure is a configuration problem, and the sooner one clear line reaches the log,
     * the sooner somebody can fix it.
     */
    public static CrimeIntegrationOperation.Status classify(DeliveryOutcome outcome, int attemptsSoFar,
                                                            int maxAttempts) {
        if (outcome == null) {
            return CrimeIntegrationOperation.Status.PENDING;
        }
        if (outcome.successful()) {
            return CrimeIntegrationOperation.Status.COMPLETE;
        }
        if (!outcome.retryable()) {
            return CrimeIntegrationOperation.Status.DEAD_LETTER;
        }
        // attemptsSoFar counts failures before this one, so this attempt is number attemptsSoFar + 1.
        return attemptsSoFar + 1 >= Math.max(1, maxAttempts)
                ? CrimeIntegrationOperation.Status.DEAD_LETTER
                : CrimeIntegrationOperation.Status.PENDING;
    }

    /**
     * Whether an unavailable companion should count against the attempt budget.
     *
     * <p>It should not. A player who uninstalls a mod for a week would otherwise come back to find
     * every pending write dead-lettered by a countdown that measured their absence rather than any
     * real failure.
     */
    public static boolean countsAgainstBudget(DeliveryOutcome outcome) {
        return outcome != DeliveryOutcome.UNAVAILABLE;
    }
}

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

    /**
     * Whether giving up on this operation should charge the player standing locally instead.
     *
     * <p>A pure function rather than an {@code if} inside the pump, because it is the one rule on this
     * queue that can punish somebody, and it now has to be right for two companion mods that want
     * opposite things from a failure.
     *
     * <ul>
     *   <li><b>A civic record that never arrived</b> is a deed that cost the player nothing publicly.
     *       Counting it locally after all is the compensation for the write being lost, and it applies
     *       only to the operation that files the deed — a resolution that fails to deliver has already
     *       had its crime recorded, and penalising that would charge the player twice.</li>
     *   <li><b>A settlement reaction that never played</b> is an animation. The crime was recorded, the
     *       case stands, the Heat stands, the sentence stands. There is nothing to compensate for, and
     *       taking standing because a villager did not wave would be a punishment with no deed behind
     *       it. This is the failure mode {@code R7} names and the reason the routing exists at all.</li>
     * </ul>
     *
     * @param target the operation's target; anything outside MCA: Reputation's namespace is never penalised
     * @param action what the operation was going to do
     * @param status what the pump has decided to do with it
     */
    public static boolean appliesLocalVillagePenalty(net.minecraft.resources.ResourceLocation target,
                                                     net.minecraft.resources.ResourceLocation action,
                                                     CrimeIntegrationOperation.Status status) {
        if (status != CrimeIntegrationOperation.Status.DEAD_LETTER) {
            return false;
        }
        if (!IntegrationTargets.isReputation(target)) {
            return false;
        }
        return IntegrationTargets.ACTION_CREATE.equals(action);
    }
}

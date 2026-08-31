package dev.otectus.mcacrime.economy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Works out which cases a fine settles, what it costs, and how much Heat it clears — as pure
 * arithmetic over plain values, with no player, no server, and no config object.
 *
 * <h2>What changed, and why it matters</h2>
 *
 * <p>The old behaviour was "pay a sum, all Heat clears". That is fine as a mechanic but useless as a
 * legal record: nothing was marked settled, so a player who paid still had every case open against
 * them, and a companion mod had no way to say which offence had been made good. Attributing the
 * payment to exact cases is what lets a village later distinguish "he answered for that" from "he
 * paid something once".
 *
 * <p>Oldest first, deliberately. A player with a long tail of small offences and one recent large one
 * should be clearing the debt they have been carrying, not buying off the freshest complaint.
 */
public final class FineAllocation {

    /** One settleable case, reduced to what pricing actually needs. */
    public record FineCase(UUID id, long heatGenerated, long fineAmount, long committedGameTime) {
    }

    /** The outcome: which cases, what it costs, and how much Heat that removes. */
    public record Allocation(List<UUID> caseIds, long totalCost, long heatCleared, boolean coversAllHeat) {

        public Allocation {
            caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
            totalCost = Math.max(0L, totalCost);
            heatCleared = Math.max(0L, heatCleared);
        }

        public boolean empty() {
            return caseIds.isEmpty() && totalCost == 0L;
        }
    }

    private FineAllocation() {
    }

    /**
     * Allocates a payment across actionable cases.
     *
     * @param actionable    open cases, oldest first
     * @param currentHeat   the player's Heat right now
     * @param fineBase      flat cost per settled case
     * @param finePerHeat   cost per point of Heat the case generated
     * @param bandMultiplier the offender's standing multiplier (a lawful player pays less)
     * @param maxCases      how many cases one payment may settle
     * @param payAll        settle everything and clear all Heat, rather than case by case
     */
    public static Allocation allocate(List<FineCase> actionable, long currentHeat,
                                      long fineBase, long finePerHeat, double bandMultiplier,
                                      int maxCases, boolean payAll) {
        if (actionable == null || actionable.isEmpty()) {
            // No open case, but Heat can still exist -- an unwitnessed spree, or Heat from a source
            // that never wrote a case. Paying it off is still a legitimate transaction.
            long cost = payAll ? price(currentHeat, fineBase, finePerHeat, bandMultiplier) : 0L;
            return new Allocation(List.of(), cost, payAll ? currentHeat : 0L, payAll);
        }

        int limit = payAll ? actionable.size() : Math.max(1, Math.min(maxCases, actionable.size()));
        List<UUID> chosen = new ArrayList<>(limit);
        long attributableHeat = 0L;
        long cost = 0L;
        for (int i = 0; i < limit; i++) {
            FineCase settled = actionable.get(i);
            chosen.add(settled.id());
            attributableHeat += Math.max(0L, settled.heatGenerated());
            // An assessed fine on the record wins over the computed price: a sentence or a quest may
            // have set a specific figure, and recomputing it would quietly discard that decision.
            cost += settled.fineAmount() > 0L
                    ? settled.fineAmount()
                    : price(settled.heatGenerated(), fineBase, finePerHeat, bandMultiplier);
        }

        boolean coversAll = payAll || chosen.size() == actionable.size();
        // Heat cleared is capped at what the player actually has: cases can account for more Heat than
        // remains after decay, and refunding the difference as negative Heat would be nonsense.
        long heatCleared = coversAll ? currentHeat : Math.min(currentHeat, attributableHeat);
        if (payAll) {
            cost = Math.max(cost, price(currentHeat, fineBase, finePerHeat, bandMultiplier));
        }
        return new Allocation(chosen, cost, heatCleared, coversAll);
    }

    /** The cost of answering for a given amount of Heat, rounded up so it is never free. */
    public static long price(long heat, long fineBase, long finePerHeat, double bandMultiplier) {
        long raw = fineBase + Math.max(0L, heat) * finePerHeat;
        long scaled = Math.round(raw * Math.max(0.0D, bandMultiplier));
        return raw > 0L ? Math.max(1L, scaled) : Math.max(0L, scaled);
    }
}

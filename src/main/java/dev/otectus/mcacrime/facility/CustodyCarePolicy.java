package dev.otectus.mcacrime.facility;

import dev.otectus.mcacrime.compat.TownsteadNeedsView;

import javax.annotation.Nullable;
import java.util.List;

/**
 * What should happen to a prisoner whose needs are running down: nothing, a meal, or an end to the
 * confinement itself.
 *
 * <h2>The rule this exists to enforce</h2>
 *
 * <p><b>A lawful sentence must never be the thing that kills somebody.</b> Custody suspends a
 * villager's ordinary life, including the travel that feeds them, so a settlement that tracks hunger
 * and thirst turns a long sentence into a slow execution unless custody carries its own care. The
 * outcome of last resort is therefore {@link Action#CUSTODY_RECOVERY} — confinement stops, the
 * sentence and the case set do not. It is deliberately <em>not</em> "served" and not "pardon": both of
 * those clear liability, and a prisoner who was let out to eat has not finished their sentence and has
 * not been forgiven.
 *
 * <h2>Why it is pure</h2>
 *
 * <p>Nothing here touches a level, an entity or an {@code ItemStack}. Supplies arrive as
 * {@link Supply} records the caller built from the cell's own container, which keeps the decision
 * testable without a game, and keeps the one judgement that matters — critical prisoner, no supplies,
 * therefore recovery — in a place where it can be asserted rather than inferred from behaviour.
 *
 * <p>Zero needs are never treated as starvation on their own: an untracked reading is all zeroes, and
 * {@link TownsteadNeedsView#tracked()} is what tells the two apart.
 */
public final class CustodyCarePolicy {

    private CustodyCarePolicy() {
    }

    /** What custody should do about this prisoner right now. */
    public enum Action {
        /** Nothing to do: needs are untracked, or comfortable enough to leave alone. */
        OK,
        /** Hand the prisoner an accessible supply and let Townstead's own consumption apply it. */
        FEED,
        /**
         * A supply is there and MCA: Crime cannot deliver it, because the consumption capability is not
         * bound. Reported rather than silently skipped: the prisoner is not being fed, and an operator
         * who believes otherwise will not understand the recovery that follows.
         */
        FEED_UNAVAILABLE,
        /**
         * Confinement is suspended so the prisoner can recover. The sentence, the case set and the
         * custody record all survive.
         */
        CUSTODY_RECOVERY
    }

    /**
     * One thing in the cell's supply container that a prisoner could consume.
     *
     * @param slot  which slot it is in, so the caller can find it again
     * @param food  whether it restores hunger
     * @param drink whether it restores thirst
     */
    public record Supply(int slot, boolean food, boolean drink) {
    }

    /**
     * The thresholds the decision is made against.
     *
     * <p>Defaults are Townstead's own emergency lines, so "critical" here means what it means there.
     * The feed lines sit above them: a prisoner is offered a meal before they are in trouble, which is
     * the difference between custody that keeps somebody alive and custody that resuscitates them.
     */
    public record Thresholds(int criticalHunger, int criticalThirst, int feedHunger, int feedThirst) {

        public static Thresholds defaults() {
            return new Thresholds(TownsteadNeedsView.HUNGER_EMERGENCY, TownsteadNeedsView.THIRST_EMERGENCY,
                    TownsteadNeedsView.HUNGER_EMERGENCY * 2, TownsteadNeedsView.THIRST_EMERGENCY * 2);
        }
    }

    /**
     * The decision, with the reason an operator will read in {@code /crime debug custody}.
     *
     * @param action the outcome
     * @param slot   the supply slot to use for {@link Action#FEED}, or -1
     * @param reason a short human-readable explanation; never empty
     */
    public record Decision(Action action, int slot, String reason) {

        public Decision {
            reason = reason == null || reason.isBlank() ? action.name() : reason;
        }

        static Decision of(Action action, String reason) {
            return new Decision(action, -1, reason);
        }
    }

    /**
     * Decides for one prisoner.
     *
     * <p>Order of the questions is the policy:
     *
     * <ol>
     *   <li>An untracked reading decides nothing. Custody must not react to numbers it does not have.</li>
     *   <li>A critical prisoner with no usable supply enters recovery. This is the case the whole class
     *       exists for, and it is answered before anything about feeding, so a cell that happens to
     *       contain a cake cannot talk custody out of an outcome it has already earned.</li>
     *   <li>A critical prisoner with a supply but no way to deliver it also enters recovery: the food
     *       is in the room and is not going to reach them.</li>
     *   <li>Otherwise, a hungry or thirsty prisoner with a matching supply is fed.</li>
     * </ol>
     *
     * @param needs                the prisoner's Townstead needs; an untracked view yields {@link Action#OK}
     * @param supplies             what is reachable inside the cell, already filtered by the caller for
     *                             evidence and restitution containers
     * @param consumptionAvailable whether Townstead's consumption flow is bound
     */
    public static Decision decide(@Nullable TownsteadNeedsView needs, @Nullable List<Supply> supplies,
                                  boolean consumptionAvailable, @Nullable Thresholds thresholds) {
        Thresholds limits = thresholds == null ? Thresholds.defaults() : thresholds;
        if (needs == null || !needs.tracked()) {
            return Decision.of(Action.OK, "needs are not tracked for this prisoner");
        }

        boolean starving = needs.hunger() <= limits.criticalHunger();
        boolean parched = needs.thirst() <= limits.criticalThirst();
        boolean critical = starving || parched || needs.collapsed();

        List<Supply> available = supplies == null ? List.of() : supplies;
        int foodSlot = slotFor(available, true);
        int drinkSlot = slotFor(available, false);
        // What this prisoner actually needs decides which supply counts. A barrel of water does not
        // feed a starving villager, and calling that "supplies present" is how a critical prisoner ends
        // up left in a cell with nothing they can use.
        int usable = starving ? foodSlot : parched ? drinkSlot : foodSlot >= 0 ? foodSlot : drinkSlot;

        if (critical && usable < 0) {
            return Decision.of(Action.CUSTODY_RECOVERY,
                    "prisoner is critically unfit and the cell holds nothing they can use");
        }
        if (critical && !consumptionAvailable) {
            return Decision.of(Action.CUSTODY_RECOVERY,
                    "prisoner is critically unfit and MCA: Crime cannot deliver the supplies present "
                            + "(consumption_in_custody is unavailable)");
        }
        if (critical) {
            return new Decision(Action.FEED, usable, "prisoner is critically unfit; feeding from the cell");
        }

        boolean hungry = needs.hunger() <= limits.feedHunger();
        boolean thirsty = needs.thirst() <= limits.feedThirst();
        if (!hungry && !thirsty) {
            return Decision.of(Action.OK, "prisoner is fed and watered");
        }
        int wanted = hungry && foodSlot >= 0 ? foodSlot : thirsty ? drinkSlot : -1;
        if (wanted < 0) {
            // Short of a meal but not in danger, and nothing to offer. Nothing to do yet; the critical
            // branch above is what acts if it gets worse.
            return Decision.of(Action.OK, "prisoner is hungry or thirsty and the cell has nothing suitable");
        }
        if (!consumptionAvailable) {
            return new Decision(Action.FEED_UNAVAILABLE, wanted,
                    "supplies are present but consumption_in_custody is unavailable");
        }
        return new Decision(Action.FEED, wanted, "feeding the prisoner from the cell supplies");
    }

    /** The first slot holding food (or drink), or -1. */
    private static int slotFor(List<Supply> supplies, boolean food) {
        for (Supply supply : supplies) {
            if (food ? supply.food() : supply.drink()) {
                return supply.slot();
            }
        }
        return -1;
    }
}

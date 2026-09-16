package dev.otectus.mcacrime.memory;

/**
 * The routing rule behind the empty-hand apology gesture (0.7.2 §12.2), kept as a pure function of
 * plain facts so the whole table is testable without a server, a villager, or MCA on the classpath.
 *
 * <p>The interaction handler's only job is to observe the live world, fill in {@link Facts}, and obey
 * the {@link Route} it gets back. Nothing here touches memories: eligibility still belongs to
 * {@link ApologyStatus} and the bounded mutation still belongs to {@code VictimMemoryService}, so the
 * gesture cannot become a second, looser reconciliation path.
 *
 * <p>Invariant 11 — one interaction produces one action — is why this never returns EXECUTE twice for
 * one physical click: the caller claims the click once and consumes the companion hand path itself.
 * Invariant 16 is why a cancelled event is never routed anywhere but {@link Route#NOT_CLAIMED}.
 */
public final class ApologyInteractionPolicy {

    private ApologyInteractionPolicy() {
    }

    /** What the interaction handler should do with this click. */
    public enum Route {
        /** Crime has no business with this interaction; leave the event entirely alone. */
        NOT_CLAIMED,
        /** Crime looked and found nothing to do; let the normal MCA interaction happen. */
        PASS_THROUGH,
        /** Run the apology once through the authoritative action service and consume the click. */
        EXECUTE,
        /** Explain why reconciliation is not happening, without pretending there is no history. */
        REFUSE_WITH_REASON
    }

    /** How the empty-hand gesture behaves, mirrored by COMMON {@code emptyHandApologyMode}. */
    public enum Mode {
        /** Right-click unarmed and unsneaking apologizes directly. */
        CONTEXTUAL_DIRECT,
        /** The gesture is off; the Crime menu, its keybind and the screen button remain. */
        MENU_ONLY
    }

    /**
     * One click, reduced to the facts the routing table actually turns on.
     *
     * @param eventCanceled another mod already claimed or cancelled this interaction
     * @param sneaking the player is sneaking, which stays reserved for deliberate gestures
     * @param mainHandEmpty the hand the gesture is made with carries nothing
     * @param offhandWeapon the off hand holds a weapon; a torch or a sword in the backpack does not count
     * @param targetValid the villager is present, alive, reachable and addressable
     * @param targetAwake the villager is not asleep
     * @param hasGrievance this player-villager pair has remembered offenses at all
     * @param apologyStatus the shared eligibility verdict for this pair
     * @param competingFlow an arrest, custody, rescue or other committed flow owns this interaction
     * @param activeThreat somebody is currently coercing this villager
     * @param mode the configured gesture mode
     */
    public record Facts(boolean eventCanceled, boolean sneaking, boolean mainHandEmpty, boolean offhandWeapon,
                        boolean targetValid, boolean targetAwake, boolean hasGrievance,
                        ApologyStatus apologyStatus, boolean competingFlow, boolean activeThreat, Mode mode) {
    }

    /** A route plus, for a refusal, the translation key that says the true reason. */
    public record Decision(Route route, String reasonKey) {
        public static Decision of(Route route) {
            return new Decision(route, "");
        }

        public static Decision refuse(String reasonKey) {
            return new Decision(Route.REFUSE_WITH_REASON, reasonKey);
        }
    }

    /**
     * The table from spec §12.2, in the order the contexts take precedence over one another.
     *
     * <p>A committed flow outranks everything because an arrest in progress is not a conversation.
     * Sneaking is next and is deliberately <em>not</em> claimed: villager-pickup add-ons bind that
     * gesture, and stealing it back would trade one broken interaction for another.
     */
    public static Decision route(Facts facts) {
        if (facts.eventCanceled()) return Decision.of(Route.NOT_CLAIMED);
        if (facts.competingFlow()) return Decision.of(Route.NOT_CLAIMED);
        if (facts.sneaking()) return Decision.of(Route.NOT_CLAIMED);
        if (!facts.mainHandEmpty()) return Decision.of(Route.NOT_CLAIMED);
        if (facts.mode() != Mode.CONTEXTUAL_DIRECT) return Decision.of(Route.NOT_CLAIMED);
        if (!facts.targetValid() || !facts.targetAwake()) return Decision.of(Route.NOT_CLAIMED);
        if (facts.apologyStatus() == ApologyStatus.DISABLED) return Decision.of(Route.NOT_CLAIMED);
        if (!facts.hasGrievance()) return Decision.of(Route.PASS_THROUGH);
        // Nothing outstanding: one accepted apology must not trap the player in apology feedback on
        // every later click, so an ordinary interaction resumes immediately.
        if (facts.apologyStatus() == ApologyStatus.NOT_NEEDED
                || facts.apologyStatus() == ApologyStatus.ALREADY_APOLOGIZED) return Decision.of(Route.PASS_THROUGH);
        // A weapon in the off hand is still a weapon pointed at them, and a coerced villager cannot
        // hear an apology at all. Both explain themselves through the existing refusal keys.
        if (facts.offhandWeapon()) return Decision.refuse("mcacrime.apologize.lower_weapon");
        if (facts.activeThreat()) return Decision.refuse("mcacrime.apologize.active_threat");
        if (facts.apologyStatus() == ApologyStatus.READY) return Decision.of(Route.EXECUTE);
        // Settling or cooldown: say which one and never "they have no history with you".
        return Decision.refuse(facts.apologyStatus().reason());
    }
}

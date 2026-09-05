package dev.otectus.mcacrime.ai.thief;

/**
 * The transition table of spec §"Thief behavioral state machine", as a pure function.
 *
 * <p>Nothing here touches an entity. {@link ThiefBehaviorService} gathers the signals from the world,
 * asks this what the next state is, and then applies the movement — which is what lets the diagram in
 * the spec be tested line for line with no server running.
 */
public final class ThiefStateMachine {

    /**
     * Everything the machine is allowed to know. All ten are facts about this instant; none of them
     * is a decision.
     *
     * @param timerDone the current phase's own timer elapsed — the threat beat in THREATENING, the
     *                  mug session ending in MUGGING
     */
    public record ThiefSignals(boolean victimFound, boolean inReach, boolean guardRiskHard,
                               boolean victimArmed, boolean victimInvalid, boolean timerDone,
                               boolean guardIntervened, boolean dead, boolean cooldownOver,
                               boolean escapedFarEnough) {
    }

    private ThiefStateMachine() {
    }

    public static ThiefState next(ThiefState current, ThiefSignals s) {
        if (s.dead()) {
            return ThiefState.DEAD;
        }
        if (current == ThiefState.DEAD) {
            return ThiefState.DEAD;
        }
        // A guard reaching the thief outranks every other transition: the point of the whole design is
        // that intervention beats the mug timer rather than racing it.
        if (s.guardIntervened() && current != ThiefState.ARRESTED) {
            return ThiefState.ARRESTED;
        }
        return switch (current) {
            case IDLE -> ThiefState.SCOUTING;
            case SCOUTING -> s.victimFound() && !s.guardRiskHard() ? ThiefState.APPROACHING : ThiefState.SCOUTING;
            case APPROACHING -> {
                if (s.guardRiskHard()) {
                    yield ThiefState.FLEEING;
                }
                if (s.victimInvalid() || s.victimArmed() || !s.victimFound()) {
                    // Nothing has happened yet, so there is nothing to run from: go back to looking.
                    yield ThiefState.SCOUTING;
                }
                yield s.inReach() ? ThiefState.THREATENING : ThiefState.APPROACHING;
            }
            case THREATENING -> {
                if (s.guardRiskHard() || s.victimArmed() || s.victimInvalid()) {
                    yield ThiefState.FLEEING;
                }
                if (!s.inReach()) {
                    yield ThiefState.APPROACHING;
                }
                yield s.timerDone() ? ThiefState.MUGGING : ThiefState.THREATENING;
            }
            // Guard risk alone does not break off a mug in progress; only a guard actually intervening
            // does, and that is handled above. Everything else ends the session and starts the run.
            case MUGGING -> s.victimArmed() || s.victimInvalid() || s.timerDone()
                    ? ThiefState.FLEEING : ThiefState.MUGGING;
            case FLEEING -> s.escapedFarEnough() ? ThiefState.COOLDOWN : ThiefState.FLEEING;
            case COOLDOWN -> s.cooldownOver() ? ThiefState.SCOUTING : ThiefState.COOLDOWN;
            // Custody owns this one; Phase 8's release is what puts the thief back on cooldown.
            case ARRESTED -> ThiefState.ARRESTED;
            case DEAD -> ThiefState.DEAD;
        };
    }
}

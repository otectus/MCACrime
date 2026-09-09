package dev.otectus.mcacrime.ai;

import dev.otectus.mcacrime.item.weapon.WeaponClass;
import java.util.Map;

/** Deterministic threat policy shared by reaction decisions and debug output. */
public final class ThreatEvaluator {
    private ThreatEvaluator() {}
    public record Options(double meleeRange, double rangedRange, boolean panic, boolean stall,
                          boolean resistance, boolean comply) {}
    public record Evaluation(VictimReactionState response, double score, Map<String, Double> factors) {}
    public static Evaluation evaluate(ThreatContext c, Options options) {
        double range = c.weapon() == WeaponClass.MELEE ? options.meleeRange() : options.rangedRange();
        double weapon = c.weapon() == WeaponClass.NONE || !c.aimed() || c.distance() > range ? 0
                : (c.weapon() == WeaponClass.MELEE ? 35 : 55) * Math.max(0.2, 1 - c.distance() / (range * 1.5));
        double vulnerability = (1 - c.healthFraction()) * 25 + c.fear() * 20;
        double violence = c.recentViolence() ? 20 : 0;
        double support = (c.guardNearby() ? 25 : 0) + Math.min(3, c.allies()) * 6;
        double courage = c.bravery() * 15 + c.combat() * 20;
        double score = Math.max(0, Math.min(100, weapon + vulnerability + violence - support - courage + 20));
        var factors = Map.of("weapon", weapon, "vulnerability", vulnerability, "recentViolence", violence,
                "nearbySupport", -support, "courage", -courage);
        VictimReactionState response;
        if (!c.coercive()) {
            if (options.resistance() && c.healthFraction() > 0.4 && (c.armed()
                    || c.protectingFamily() && c.bravery() >= 0.5)) response = VictimReactionState.RESISTING;
            else response = c.guardNearby() ? VictimReactionState.SEEKING_HELP : VictimReactionState.FLEEING;
        }
        else if (weapon == 0) response = VictimReactionState.FLEEING;
        else if (options.resistance() && c.healthFraction() > 0.35 && (c.armed()
                || c.guardNearby() && c.bravery() > 0.55 || c.protectingFamily() && c.anger() > 0.55)) response = VictimReactionState.RESISTING;
        else if (c.guardNearby()) response = VictimReactionState.SEEKING_HELP;
        else if (c.protectingFamily() && c.bravery() >= 0.5 && score < 55) response = VictimReactionState.DEFYING;
        else if (options.panic() && c.bravery() < 0.4 && (c.recentViolence() || score >= 65)) response = VictimReactionState.PANICKING;
        else if (options.stall() && c.bravery() >= 0.48 && c.healthFraction() > 0.45 && score < 50) response = VictimReactionState.STALLING;
        else response = options.comply() ? VictimReactionState.COMPLYING : VictimReactionState.FLEEING;
        return new Evaluation(response, score, factors);
    }

    /** A minimum dwell time prevents a changing crowd or look direction from causing rapid oscillation. */
    public static VictimReactionState stabilize(VictimReactionState current, VictimReactionState next,
                                                long ticksInState, int reevaluationTicks) {
        return current != VictimReactionState.THREATENED && ticksInState < Math.max(20, reevaluationTicks * 2L)
                ? current : next;
    }
}

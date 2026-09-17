package dev.otectus.mcacrime.civic;

import dev.otectus.mcacrime.crime.Band;

import javax.annotation.Nullable;

/**
 * Whether a settlement may refuse one person one service, and what it has to say about it
 * (reference §11.5).
 *
 * <p>Pure: a band, a wanted flag, a count of publicly known open cases, one number standing for how
 * badly this particular villager was hurt, and the kind of service being asked for. No entity, no
 * server, no config, no companion mod — which is what lets the four rules below be pinned by
 * assertions instead of inferred from a villager who happened not to trade.
 *
 * <h2>The four rules, in the order they are applied</h2>
 *
 * <ol>
 *   <li><b>An essential service is never refused.</b> Reference §11.5 in one line. Checked first so no
 *       later rule can reach it.</li>
 *   <li><b>A personal grievance refuses.</b> "Base a personal refusal on that villager's justified
 *       fear/anger" — a villager who watched this person rob them is entitled to say no, and nobody
 *       else is entitled to say no on their behalf. The number is the decayed fear/anger from the
 *       existing memory, so the refusal ends when the memory does: §11.5's "end temporary restrictions
 *       when their condition ends" is satisfied by not caching anything.</li>
 *   <li><b>A valid public local case refuses a non-essential service.</b> "or a valid public local
 *       case" — being wanted here, with something this settlement actually knows about still open.
 *       Both halves are required, which is what stops a warrant from another village closing every
 *       shop in this one.</li>
 *   <li><b>Standing alone never refuses.</b> §11.5's closing sentence: "a low karma band or a genetic
 *       trait should not automatically disable every Townstead service". An outlaw with nothing open
 *       here and nobody with a grudge is served, and the one place band is consulted at all is to
 *       decide whether a <em>luxury</em> may be withheld.</li>
 * </ol>
 *
 * <p>Every refusal carries the reason and the route back, because §11.5 asks for both: a "no" with no
 * explanation and no repair path is the failure mode the whole section exists to prevent.
 */
public final class ServiceRestrictionPolicy {

    /** How much decayed fear/anger it takes before a villager refuses on their own account. */
    public static final double DEFAULT_GRIEVANCE_THRESHOLD = 0.35D;

    /** Why a service was refused, or that it was not. */
    public enum Verdict {
        /** Served. */
        ALLOWED,
        /** Refused because this villager personally remembers being harmed. */
        REFUSED_PERSONAL,
        /** Refused because the settlement knows of an open case against them here. */
        REFUSED_PUBLIC;

        public boolean refused() {
            return this != ALLOWED;
        }
    }

    /**
     * The answer, with the sentence the villager says and the route back.
     *
     * @param verdict   served, or which of the two reasons refused
     * @param reasonKey the translation key for what the villager says
     * @param repairKey the translation key for how to fix it, empty when nothing is broken
     */
    public record Decision(Verdict verdict, String reasonKey, String repairKey) {

        public Decision {
            verdict = verdict == null ? Verdict.ALLOWED : verdict;
            reasonKey = reasonKey == null ? "" : reasonKey;
            repairKey = repairKey == null ? "" : repairKey;
        }

        public boolean refused() {
            return verdict.refused();
        }

        /** The always-safe answer, and what every path returns when the feature is off. */
        public static Decision allowed() {
            return new Decision(Verdict.ALLOWED, "", "");
        }
    }

    /** The thresholds, lifted out so the rule stays pure and a pack can move them later. */
    public record Settings(double grievanceThreshold) {

        public Settings {
            grievanceThreshold = !Double.isFinite(grievanceThreshold)
                    ? DEFAULT_GRIEVANCE_THRESHOLD
                    : Math.max(0.0D, Math.min(1.0D, grievanceThreshold));
        }

        public static Settings defaults() {
            return new Settings(DEFAULT_GRIEVANCE_THRESHOLD);
        }
    }

    private ServiceRestrictionPolicy() {
    }

    /** {@link #decide(ServiceKind, Band, boolean, int, double, Settings)} with the default thresholds. */
    public static Decision decide(@Nullable ServiceKind kind, @Nullable Band band, boolean wanted,
                                  int openPublicIncidents, double grievance) {
        return decide(kind, band, wanted, openPublicIncidents, grievance, Settings.defaults());
    }

    /**
     * Whether this villager may refuse this service to this person.
     *
     * @param kind                what is being asked for
     * @param band                the subject's public standing band; consulted only for a luxury
     * @param wanted              whether the law is after them <em>here</em>
     * @param openPublicIncidents how many cases this settlement publicly knows about and considers open
     * @param grievance           the decayed fear/anger this specific villager holds against them, 0..1
     */
    public static Decision decide(@Nullable ServiceKind kind, @Nullable Band band, boolean wanted,
                                  int openPublicIncidents, double grievance, @Nullable Settings settings) {
        if (kind == null) {
            return Decision.allowed();
        }
        if (kind.essential()) {
            // Rule 1, and it is checked before anything else precisely so that no later condition can
            // be added underneath it by accident.
            return Decision.allowed();
        }
        Settings thresholds = settings == null ? Settings.defaults() : settings;
        double held = !Double.isFinite(grievance) ? 0.0D : Math.max(0.0D, Math.min(1.0D, grievance));
        if (held >= thresholds.grievanceThreshold()) {
            return new Decision(Verdict.REFUSED_PERSONAL, "mcacrime.service.refused.personal",
                    "mcacrime.service.repair.personal");
        }
        if (kind == ServiceKind.FENCE) {
            // Rule 3 does not apply to a fence: its whole trade is with people the law is after, and a
            // fence that turned away wanted customers would have no customers.
            return Decision.allowed();
        }
        int open = Math.max(0, openPublicIncidents);
        if (open <= 0) {
            // Rule 4. Whatever their standing elsewhere, this settlement has nothing open against them.
            return Decision.allowed();
        }
        if (wanted || kind == ServiceKind.LUXURY && band == Band.RED) {
            return new Decision(Verdict.REFUSED_PUBLIC, "mcacrime.service.refused.public",
                    "mcacrime.service.repair.public");
        }
        return Decision.allowed();
    }
}

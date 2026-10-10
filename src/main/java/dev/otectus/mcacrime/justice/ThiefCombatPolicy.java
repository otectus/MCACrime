package dev.otectus.mcacrime.justice;

/** Stable serialized names and explicit world-rule values; never persist ordinals. */
public enum ThiefCombatPolicy {
    NORMAL_LAW(0), EVIDENCE_REQUIRED(1), ALL_THIEVES(2);
    private final int rule;
    ThiefCombatPolicy(int rule) { this.rule = rule; }
    public int ruleValue() { return rule; }
    public static ThiefCombatPolicy fromRule(int value) {
        return switch (Math.max(0, Math.min(2, value))) {
            case 1 -> EVIDENCE_REQUIRED; case 2 -> ALL_THIEVES; default -> NORMAL_LAW;
        };
    }
    /**
     * Why a hit on a villager was or was not exempt.
     *
     * <p>{@link #ACTIVE_THREAT} (0.7.7) is the victim of a mugging defending themselves: the target is
     * holding them up right now, or did within the recovery window. It is the one reason every policy
     * honours, because answering a threat is not a rule about Thieves -- it is self-defence, which
     * {@code NORMAL_LAW} grants against any aggressor. What the policy still decides is lethal force:
     * see {@link ThiefCombatDecision#exemptsKill()}.
     *
     * <p>{@link #OUTLAW} (0.7.7) is MCA's own Outlaw, with {@code outlawCombatLegal} on: anybody may fight
     * and kill one, under every policy, because an outlaw is not protected by the law in the first place.
     * It is not a rule about Thieves either, and it asks for no evidence, no report and no bounty.
     */
    public enum Reason { NORMAL_LAW, PROTECTED_TARGET, NO_EVIDENCE, OCCUPATION, RECENT_EVIDENCE, LOCAL_REPORT,
        ACTIVE_THREAT, OUTLAW }
    public Reason decide(boolean livingAdultThief, boolean reliableRole, boolean responder,
                         boolean restrained, boolean recentEvidence, boolean localReport) {
        return decide(livingAdultThief, reliableRole, responder, restrained, recentEvidence, localReport,
                false, false);
    }
    /**
     * The decision with the mugging threat taken into account (0.7.7).
     *
     * @param threatened  this target opened a mugging on this actor that is still running, or ended
     *                    within {@code thiefDefenseGraceTicks}
     * @param livingAdult the target is a loaded, living, adult MCA villager -- the threat stands without
     *                    a current Thief record, because the record can be retired between the threat
     *                    and the blow, but never for a child
     */
    public Reason decide(boolean livingAdultThief, boolean reliableRole, boolean responder,
                         boolean restrained, boolean recentEvidence, boolean localReport,
                         boolean threatened, boolean livingAdult) {
        return decide(livingAdultThief, reliableRole, responder, restrained, recentEvidence, localReport,
                threatened, livingAdult, false);
    }
    /**
     * The decision with outlaws taken into account (0.7.7).
     *
     * @param outlaw the target is MCA's Outlaw and {@code outlawCombatLegal} is on. It is asked before
     *               everything else because it is the widest grant -- killing included, under every
     *               policy -- and it still yields to the same three protections a threat does: guards,
     *               the restrained and children stay protected whatever their profession says
     */
    public Reason decide(boolean livingAdultThief, boolean reliableRole, boolean responder,
                         boolean restrained, boolean recentEvidence, boolean localReport,
                         boolean threatened, boolean livingAdult, boolean outlaw) {
        // Guards, the restrained and children stay protected whatever they did: a captured mugger is a
        // prisoner, and a threat is never a licence against somebody who can no longer make one. A
        // captured outlaw is a prisoner too.
        if (outlaw && livingAdult && !responder && !restrained) return Reason.OUTLAW;
        if (threatened && livingAdult && !responder && !restrained) return Reason.ACTIVE_THREAT;
        if (this == NORMAL_LAW) return Reason.NORMAL_LAW;
        if (!livingAdultThief || !reliableRole || responder || restrained) return Reason.PROTECTED_TARGET;
        if (this == ALL_THIEVES) return Reason.OCCUPATION;
        return recentEvidence ? Reason.RECENT_EVIDENCE : localReport ? Reason.LOCAL_REPORT : Reason.NO_EVIDENCE;
    }
    public static boolean exempt(Reason reason) {
        return reason == Reason.OCCUPATION || reason == Reason.RECENT_EVIDENCE || reason == Reason.LOCAL_REPORT
                || reason == Reason.ACTIVE_THREAT || reason == Reason.OUTLAW;
    }
    /**
     * Whether killing for this reason is lawful under this policy (0.7.7).
     *
     * <p>The same answer as {@link #exempt} except for {@link Reason#ACTIVE_THREAT} under
     * {@link #NORMAL_LAW}: a server that chose normal law gave Thieves no special status, and normal law
     * lets you fight off an aggressor but counts killing one who only threatened you as excessive force.
     * Under the other two policies a mugger is already somebody the server lets you kill. An
     * {@link Reason#OUTLAW} may be killed under every policy.
     */
    public boolean exemptsKill(Reason reason) {
        return exempt(reason) && !(reason == Reason.ACTIVE_THREAT && this == NORMAL_LAW);
    }
}

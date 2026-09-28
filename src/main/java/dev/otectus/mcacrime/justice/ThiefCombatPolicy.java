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
    public enum Reason { NORMAL_LAW, PROTECTED_TARGET, NO_EVIDENCE, OCCUPATION, RECENT_EVIDENCE, LOCAL_REPORT }
    public Reason decide(boolean livingAdultThief, boolean reliableRole, boolean responder,
                         boolean restrained, boolean recentEvidence, boolean localReport) {
        if (this == NORMAL_LAW) return Reason.NORMAL_LAW;
        if (!livingAdultThief || !reliableRole || responder || restrained) return Reason.PROTECTED_TARGET;
        if (this == ALL_THIEVES) return Reason.OCCUPATION;
        return recentEvidence ? Reason.RECENT_EVIDENCE : localReport ? Reason.LOCAL_REPORT : Reason.NO_EVIDENCE;
    }
    public static boolean exempt(Reason reason) {
        return reason == Reason.OCCUPATION || reason == Reason.RECENT_EVIDENCE || reason == Reason.LOCAL_REPORT;
    }
}

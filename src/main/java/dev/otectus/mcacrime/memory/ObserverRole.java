package dev.otectus.mcacrime.memory;

/**
 * How an observer came to know about a crime (spec §12.1). The role is the single thing that decides
 * how much an observation is worth, so it is captured at the moment of the act and never upgraded
 * later — a villager who only heard a scream does not become an eyewitness by walking over afterwards.
 *
 * <p>The base confidence values are deliberately coarse. They exist so that "a guard saw it" and "a
 * farmer two houses away heard something" produce visibly different law-enforcement behaviour
 * (immediate challenge versus investigation), not so that a server owner can tune a probability curve.
 * Per-observation confidence is derived from this base and then adjusted for distance and obstruction.
 */
public enum ObserverRole {

    /** It happened to them. Identity confidence is total for a face-to-face act. */
    DIRECT_VICTIM("direct_victim", 1.0F, true),
    /** Saw the actor and the act. */
    EYEWITNESS("eyewitness", 0.85F, true),
    /** Heard a struggle or a scream without seeing who caused it. */
    HEARING_WITNESS("hearing_witness", 0.4F, true),
    /** A direct witness told this relative; never equivalent to eyewitness testimony. */
    INFORMED("informed", 0.5F, true),
    /** A law responder who observed it directly; files without needing to reach anybody. */
    GUARD("guard", 1.0F, true),
    /** Physical traces rather than a person — reserved for a later phase; never reports on its own. */
    EVIDENCE_ONLY("evidence_only", 0.3F, false);

    private final String lower;
    private final float baseConfidence;
    private final boolean canReport;

    ObserverRole(String lower, float baseConfidence, boolean canReport) {
        this.lower = lower;
        this.baseConfidence = baseConfidence;
        this.canReport = canReport;
    }

    public String lower() {
        return lower;
    }

    /** Confidence before distance and obstruction are applied. */
    public float baseConfidence() {
        return baseConfidence;
    }

    /** Whether an observer in this role is capable of filing a report at all. */
    public boolean canReport() {
        return canReport;
    }

    /**
     * Whether this role files immediately on observation rather than having to reach an authority.
     * Only a responder who saw it themselves has nobody to walk to (spec §12.3 step 1).
     */
    public boolean authoritative() {
        return this == GUARD;
    }

    public String labelKey() {
        return "mcacrime.observation.role." + lower;
    }

    /** Parses a persisted name, defaulting to {@link #EYEWITNESS} rather than dropping the row. */
    public static ObserverRole byName(String name) {
        for (ObserverRole role : values()) {
            if (role.name().equals(name) || role.lower.equals(name)) {
                return role;
            }
        }
        return EYEWITNESS;
    }
}

package dev.otectus.mcacrime.ransom;

/**
 * Ransom payer priority (spec §8.5): a captive's relations pay in this order, with the village authority as
 * the lower-value fallback when no family payer can be found. ({@code CLOSE_FRIEND} has no MCA edge — it is
 * config-gated off by default and collapses to the village fallback.)
 */
public enum PayerTier {
    SPOUSE,
    PARENT,
    ADULT_CHILD,
    SIBLING,
    CLOSE_RELATIVE,
    CLOSE_FRIEND,
    VILLAGE_AUTHORITY;

    /** The family tiers in priority order (the village fallback is handled separately). */
    public static PayerTier[] familyOrder() {
        return new PayerTier[]{SPOUSE, PARENT, ADULT_CHILD, SIBLING, CLOSE_RELATIVE, CLOSE_FRIEND};
    }

    public static PayerTier parse(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return VILLAGE_AUTHORITY;
        }
    }
}

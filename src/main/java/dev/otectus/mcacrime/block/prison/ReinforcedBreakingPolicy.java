package dev.otectus.mcacrime.block.prison;

import java.util.Locale;
import java.util.Optional;

/**
 * How hard a reinforced block is to take apart (§6.2 "Reinforced blocks", M5.4).
 *
 * <p>Two honest policies, and neither of them is "unbreakable". The specification is explicit that
 * documentation must say what the code does, and what the code does is make a block that needs an
 * iron pickaxe and a long swing. Explosion resistance, piston immunity and authorised-removal-only are
 * three separate settings precisely because they are three separate decisions; rolling them into one
 * "hard" value is how a wall ends up described as something it is not.
 */
public enum ReinforcedBreakingPolicy {

    /**
     * The default. Ordinary qualified mining: an iron pickaxe and a long break time. A cell built
     * from this is escapable, and the jail system is what decides whether escaping it was a
     * jailbreak.
     */
    PICKAXE_QUALIFIED,

    /**
     * A subject in custody cannot break a reinforced block at all. Everybody else still
     * can, under exactly the {@link #PICKAXE_QUALIFIED} rules — this is a containment rule about
     * prisoners, not a material property.
     */
    HARD_CONTAINMENT;

    /** The policy named by {@code raw}, or empty when it names none. */
    public static Optional<ReinforcedBreakingPolicy> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String name = raw.trim().toUpperCase(Locale.ROOT);
        for (ReinforcedBreakingPolicy policy : values()) {
            if (policy.name().equals(name)) {
                return Optional.of(policy);
            }
        }
        return Optional.empty();
    }

    /** Whether a subject the caller has already established is in custody may break one. */
    public boolean allowsPrisonerBreaking() {
        return this == PICKAXE_QUALIFIED;
    }
}

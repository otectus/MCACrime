package dev.otectus.mcacrime.block.prison;

import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

/**
 * What may be done to a reinforced block, and by whom (M5.4, spec §12.1).
 *
 * <p>The decision itself is {@link #mayBreak(ReinforcedBreakingPolicy, boolean, boolean, boolean)} —
 * pure, four booleans in, one out — so the rule can be checked without a world. Everything else here
 * is the configuration reading that surrounds it.
 *
 * <p>The honest summary, which the config comments repeat: a reinforced block is a stone block that
 * needs an iron pickaxe and takes a long time. It is not unbreakable and this class never pretends
 * otherwise. {@code HARD_CONTAINMENT} narrows one case only — a subject who is in custody — and
 * explosion and piston resistance are separate settings because they answer separate questions.
 */
public final class ReinforcedPolicy {

    private ReinforcedPolicy() {
    }

    /** The configured policy, falling back to the honest default when the string names none. */
    public static ReinforcedBreakingPolicy policy() {
        return ReinforcedBreakingPolicy.parse(McaCrimeConfig.COMMON.reinforcedBreakingPolicy.get())
                .orElse(ReinforcedBreakingPolicy.PICKAXE_QUALIFIED);
    }

    public static boolean resistsExplosions() {
        return McaCrimeConfig.COMMON.reinforcedResistsExplosions.get();
    }

    public static boolean resistsPistons() {
        return McaCrimeConfig.COMMON.reinforcedResistsPistons.get();
    }

    public static boolean authorisedRemovalOnly() {
        return McaCrimeConfig.COMMON.reinforcedAuthorisedRemovalOnly.get();
    }

    /** Whether {@code state} is part of the reinforced set. */
    public static boolean isReinforced(@Nullable BlockState state) {
        return state != null && state.is(PrisonTags.REINFORCED_BLOCKS);
    }

    /**
     * The whole breaking rule, with nothing else in it.
     *
     * @param policy      the configured policy
     * @param inCustody   whether the breaker is a subject somebody else is legally holding
     * @param authorised  whether the breaker is an operator or otherwise allowed to dismantle a prison
     * @param authorisedOnly whether the server restricts removal to authorised actors at all
     */
    public static boolean mayBreak(ReinforcedBreakingPolicy policy, boolean inCustody, boolean authorised,
                                   boolean authorisedOnly) {
        if (authorisedOnly && !authorised) {
            return false;
        }
        if (inCustody && !(policy == null ? ReinforcedBreakingPolicy.PICKAXE_QUALIFIED : policy)
                .allowsPrisonerBreaking()) {
            return false;
        }
        return true;
    }

    /** The same rule against a live player. */
    public static boolean mayBreak(@Nullable Player player, boolean inCustody) {
        boolean authorised = player != null
                && (player.isCreative() || player.hasPermissions(2));
        return mayBreak(policy(), inCustody, authorised, authorisedRemovalOnly());
    }
}

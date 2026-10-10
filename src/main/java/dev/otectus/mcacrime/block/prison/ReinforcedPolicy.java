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
     * Whether the block these loot parameters describe stands where the mod built a cell (0.7.7).
     *
     * <p>Such a block drops nothing, however it is broken: a prisoner digging out of a {@code PHYSICAL}
     * cell, a rescuer digging in, a drill from another mod. The cell was made out of nothing for an
     * arrest, and the walls of one must not become the cheapest reinforced stone on the server. Read
     * from the loot parameters rather than a break event so that every breaker is covered and no other
     * mod's break handler has to be second-guessed.
     */
    public static boolean generatedCellBlock(
            @Nullable net.minecraft.world.level.storage.loot.LootParams.Builder params) {
        if (params == null) {
            return false;
        }
        net.minecraft.server.level.ServerLevel level = params.getLevel();
        net.minecraft.world.phys.Vec3 origin = params.getOptionalParameter(
                net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN);
        if (level == null || origin == null || level.getServer() == null) {
            return false;
        }
        return dev.otectus.mcacrime.jail.HoldingCellService.generatedBlockAt(
                dev.otectus.mcacrime.state.world.CrimeWorldData.get(level.getServer()),
                level.dimension().location(), net.minecraft.core.BlockPos.containing(origin));
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

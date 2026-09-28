package dev.otectus.mcacrime.item.creative;

import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;

/**
 * Who may use a creative restraint tool (0.7.5 M2.1).
 *
 * <p>The decision is "is this player allowed to be doing operator things", answered on the server
 * from the connection, and not "is this player holding the operator item". The source's
 * {@code CreativeRestraintCutter.interactLivingEntity} has no check at all, so anybody who obtains
 * the item — from a creative player's death drop, a shared shulker, or a datapack that hands it out
 * — can cut every restraint in the world, including the cuffs on a lawfully arrested prisoner.
 *
 * <p>Two grounds, either of which suffices:
 * <ul>
 *   <li>the player is in creative mode, which is the situation the items exist for; or</li>
 *   <li>the player holds permission level 2, the same bar {@code /crime} administration uses
 *       ({@code engine/CrimeReconciler} checks exactly that).</li>
 * </ul>
 *
 * <p>The pure form is what the test asserts, because the interesting cases — survival player with no
 * permission, spectator, an operator in survival — are all combinations of two booleans and should
 * not need a server to state.
 */
public final class CreativeAuthorization {

    /** The permission level an operator command needs, and the bar these items use. */
    public static final int OPERATOR_LEVEL = 2;

    private CreativeAuthorization() {
    }

    /** The whole rule, as a function of what the server knows about the actor. */
    public static boolean permits(boolean creativeMode, int permissionLevel) {
        return creativeMode || permissionLevel >= OPERATOR_LEVEL;
    }

    /**
     * Whether {@code player} may use a creative restraint tool.
     *
     * <p>Null is a refusal, not a pass: a call with no resolved sender is a call whose identity the
     * connection did not establish.
     */
    public static boolean permits(@Nullable ServerPlayer player) {
        if (player == null) {
            return false;
        }
        return permits(player.getAbilities().instabuild, player.hasPermissions(OPERATOR_LEVEL) ? OPERATOR_LEVEL : 0);
    }
}

package dev.otectus.mcacrime.restraint;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Turns everything active on a subject into one {@link RestrictionPolicy} (§3.4).
 *
 * <p>Recomputed from the whole active set every time rather than patched as pieces come and go, and
 * that is the correctness argument: "removing one source must not restore an action still forbidden
 * by another source" is only true if the answer is derived, and the composed policy is idempotent so
 * two sources imposing the same restriction do not compound it.
 *
 * <p>Pure: no entity, no level, no config. That is what lets the whole §7.1 matrix be asserted cell
 * by cell in a unit test, and what keeps the authorisation decision out of a {@code MobEffect} —
 * upstream packs it into an effect amplifier that loses its packing across save/load.
 */
public final class RestrictionResolver {

    private RestrictionResolver() {
    }

    /** The policy for one definition id, unrestricted when this build does not have it. */
    public static RestrictionPolicy of(@Nullable ResourceLocation definitionId) {
        return RestraintDefinitions.get(definitionId)
                .map(RestraintDefinition::restrictions)
                .orElseGet(RestrictionPolicy::unrestricted);
    }

    /** The composition of several definitions, in any order. */
    public static RestrictionPolicy compose(Collection<ResourceLocation> definitionIds) {
        RestrictionPolicy policy = RestrictionPolicy.unrestricted();
        if (definitionIds == null) {
            return policy;
        }
        for (ResourceLocation id : definitionIds) {
            policy = policy.and(of(id));
        }
        return policy;
    }

    /**
     * The policy for a subject's worn gear plus any device holding them.
     *
     * <p>{@code detentionDefinitionId} is passed separately because a device occupies no body slot:
     * a pillory detains a subject who may also be hooded and cuffed, and its restrictions compose on
     * top of theirs rather than replacing them.
     */
    public static RestrictionPolicy resolve(@Nullable PhysicalRestraintState state,
                                            @Nullable ResourceLocation detentionDefinitionId) {
        List<ResourceLocation> active = new ArrayList<>(4);
        if (state != null) {
            for (RestraintSlot slot : RestraintSlot.values()) {
                state.slot(slot).ifPresent(restraint -> active.add(restraint.definitionId()));
            }
        }
        if (detentionDefinitionId != null) {
            active.add(detentionDefinitionId);
        }
        return compose(active);
    }

    /** The worn-gear-only policy. */
    public static RestrictionPolicy resolve(@Nullable PhysicalRestraintState state) {
        return resolve(state, null);
    }

    /**
     * The same, with the lawful-arrest phase folded in (0.7.5 M2.8).
     *
     * <p>This is the fold that used to live in {@code enforcement/RestraintPolicy}: an arrest phase
     * says "restrained" without saying with what, and a player being walked to a cell is restrained
     * whatever else is or is not on them. It resolves to the arm-handcuff restrictions, which is what
     * an arrest applies and what the reconciler stamps as system-issued gear for an existing world.
     *
     * <p>It composes rather than replaces. An arrested player who is also hooded is both, and dropping
     * either source because the other applies is how one restraint's removal silently lifts another's
     * restrictions.
     */
    public static RestrictionPolicy resolve(@Nullable PhysicalRestraintState state,
                                            @Nullable ResourceLocation detentionDefinitionId,
                                            boolean arrestRestrained) {
        RestrictionPolicy policy = resolve(state, detentionDefinitionId);
        if (!arrestRestrained) {
            return policy;
        }
        return policy.and(of(RestraintDefinitions.HANDCUFFS_ARMS));
    }

    /**
     * Whether {@code action} is permitted under {@code policy}.
     *
     * <p>The one entry point handlers should use, because it is where the protected actions are
     * answered: no policy, however composed, may cancel a struggle input, a configured self-escape,
     * a status screen, chat or care given by somebody else. Cancelling the interaction that <em>is</em>
     * the escape attempt is how a restraint becomes permanent.
     */
    public static boolean allows(@Nullable RestrictionPolicy policy, ProtectedAction action) {
        return action != null;
    }

    /** Whether {@code action} is permitted under {@code policy}. Unrestricted when there is none. */
    public static boolean allows(@Nullable RestrictionPolicy policy, RestraintAction action) {
        if (action == null) {
            return false;
        }
        return policy == null || policy.permits(action);
    }
}

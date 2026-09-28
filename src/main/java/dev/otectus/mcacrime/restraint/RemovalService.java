package dev.otectus.mcacrime.restraint;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.audio.CrimeSounds;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.item.RestraintTags;
import dev.otectus.mcacrime.item.creative.BindBreakerItem;
import dev.otectus.mcacrime.item.creative.CreativeAuthorization;
import dev.otectus.mcacrime.item.creative.CreativeKeyItem;
import dev.otectus.mcacrime.item.creative.CreativeRestraintCutter;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;
import java.util.Optional;

/**
 * Taking one restraint off (0.7.5 M2.7).
 *
 * <p>The invariant everything here is shaped around is in specification §6: <b>removing one restraint
 * is not a release</b>. This class empties one slot and resolves one item. It does not end a custody,
 * close a case, clear a tether or touch the other two slots; a caller who wants any of that asks
 * {@code restraint/CustodyTransitionService} for it.
 *
 * <p>Exactly one item resolution per removal, decided from the instance's own provenance and return
 * policy rather than from the item registry: gear the server minted for an arrest returns nothing, so
 * an upgraded world cannot be farmed for free cuffs, and gear somebody actually supplied comes back
 * as the exact stack it was — damage, custom name, enchantments and all — because the instance stored
 * the stack's own NBT rather than just its id.
 */
public final class RemovalService {

    private RemovalService() {
    }

    /** Why a restraint came off. Chooses the sound and the item resolution, never the legal effect. */
    public enum Reason {
        /** A matching key, held by somebody. */
        KEY,
        /** No key exists for this family and the subject or a helper simply took it off. */
        KEYLESS,
        /** Cut off with a blade or shears. */
        CUTTING_TOOL,
        /** An operator tool. */
        CREATIVE,
        /** Struggled to pieces. */
        BROKEN,
        /**
         * Opened with a lockpick (0.7.5 M3.3).
         *
         * <p>Distinct from {@link #KEY} so the sound, the statistics and any later law rule can tell
         * "somebody had the key" from "somebody defeated the lock", and distinct from {@link #BROKEN}
         * because a picked restraint is intact: the item return follows the ordinary policy.
         */
        PICKED,
        /** A command, or a legal transition that owns this instance. */
        ADMINISTRATIVE
    }

    /** Why nothing came off. */
    public enum Refusal {
        NONE,
        NO_SUBJECT,
        SLOT_EMPTY,
        /** The held key opens a different family. */
        WRONG_KEY,
        /** This definition needs a key and none was offered. */
        KEY_REQUIRED,
        /** A cutting tool was needed and the actor has none. */
        TOOL_REQUIRED,
        /** An operator tool used by somebody with no authority for it. */
        UNAUTHORISED,
        /** The world store refused the write. */
        STORE_REFUSED
    }

    /**
     * What happened.
     *
     * @param removed   true only when a slot was actually emptied
     * @param instance  the instance that came off, so the caller can publish and account for it
     * @param returned  the stack that was given back, empty when nothing was owed
     */
    public record Result(boolean removed, Refusal refusal, @Nullable AppliedRestraint instance,
                         ItemStack returned) {

        public static Result refused(Refusal refusal) {
            return new Result(false, refusal, null, ItemStack.EMPTY);
        }
    }

    // --- authorisation ---------------------------------------------------------------------------

    /**
     * Whether {@code tool} opens what is in {@code slot}, ignoring who is holding it.
     *
     * <p>Pure, and the reason it is: the key-matching rule is the one a player is most likely to
     * report as broken, and it is decidable from two ids. A definition with no key is keyless — tape
     * and the hood come off by hand — and a definition with a key needs that exact family's key, not
     * any key.
     */
    public static Refusal check(@Nullable PhysicalRestraintState state, @Nullable RestraintSlot slot,
                                @Nullable ItemStack tool, boolean creativeAuthorised) {
        if (state == null || slot == null) {
            return Refusal.NO_SUBJECT;
        }
        AppliedRestraint worn = state.slot(slot).orElse(null);
        if (worn == null) {
            return Refusal.SLOT_EMPTY;
        }
        RestraintDefinition definition = worn.definition().orElse(null);
        if (definition == null) {
            // A definition this build does not have. Letting it come off is the safe direction: the
            // alternative is gear nobody can ever remove because nothing knows what it is.
            return Refusal.NONE;
        }
        ItemStack held = tool == null ? ItemStack.EMPTY : tool;
        if (isOperatorTool(held)) {
            return creativeAuthorised ? Refusal.NONE : Refusal.UNAUTHORISED;
        }
        if (definition.keyItem().isPresent()) {
            Optional<RestraintFamily> opens = CrimeItems.keyOpens(held);
            if (opens.isEmpty()) {
                return Refusal.KEY_REQUIRED;
            }
            return definition.family().filter(opens.get()::equals).isPresent()
                    ? Refusal.NONE : Refusal.WRONG_KEY;
        }
        if (definition.escape().removableWithCuttingTool() && !held.isEmpty()
                && held.is(RestraintTags.CUTTING_TOOLS)) {
            return Refusal.NONE;
        }
        // Keyless: tape and the hood come off by hand.
        return Refusal.NONE;
    }

    /** Whether {@code stack} is one of the three operator tools. */
    public static boolean isOperatorTool(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.getItem() instanceof CreativeRestraintCutter
                || stack.getItem() instanceof CreativeKeyItem
                || stack.getItem() instanceof BindBreakerItem;
    }

    // --- removal ---------------------------------------------------------------------------------

    /**
     * Empties one slot and resolves its item.
     *
     * <p>{@code recipient} is who a returned item goes to when the policy says "back to the applier";
     * a null recipient drops it at the subject's feet rather than deleting it, because an item that
     * exists has to end up somewhere.
     */
    public static Result remove(@Nullable LivingEntity subject, @Nullable RestraintSlot slot,
                                Reason reason, @Nullable ServerPlayer recipient) {
        if (subject == null || slot == null || subject.level().isClientSide()) {
            return Result.refused(Refusal.NO_SUBJECT);
        }
        CrimeWorldData data = RestraintService.data(subject);
        if (data == null) {
            return Result.refused(Refusal.NO_SUBJECT);
        }
        PhysicalRestraintState state = data.physicalRestraint(subject.getUUID());
        if (state == null) {
            return Result.refused(Refusal.NO_SUBJECT);
        }
        AppliedRestraint worn = state.slot(slot).orElse(null);
        if (worn == null) {
            return Result.refused(Refusal.SLOT_EMPTY);
        }
        PhysicalRestraintState next = state.without(slot);
        if (!data.putPhysicalRestraint(next)) {
            return Result.refused(Refusal.STORE_REFUSED);
        }
        // Cancel any struggle work against the slot that no longer holds anything, so a late input
        // cannot break a restraint that has already come off.
        SessionRegistry.server().cancelForTarget(subject.getUUID(), SessionCancelCause.TARGET_REMOVED);

        ItemStack returned = resolveItem(subject, worn, reason, recipient);
        if (reason == Reason.BROKEN) {
            CrimeSounds.restraintBroken(subject);
        } else {
            CrimeSounds.restraintRemoved(subject);
        }
        RestraintService.publish(subject, data);
        // Removing one restraint is not a release -- but it may have been the last thing holding
        // somebody, and only the bridge is allowed to decide that (§14.1, M4.8). An administrative or
        // operator removal is an authorised equipment change and never an escape.
        CustodyTransitionService.onRestraintRemoved(subject.getServer(), subject,
                reason == Reason.ADMINISTRATIVE || reason == Reason.CREATIVE);
        // Post-commit (M6.4). The cause is the physical one; whether this was also a legal release is
        // the bridge's answer above and is deliberately not restated here.
        PhysicalApiEvents.removed(subject, recipient == null ? null : recipient.getUUID(),
                worn.definitionId(), slot, cause(reason));
        return new Result(true, Refusal.NONE, worn, returned);
    }

    /** How a removal reads to a companion mod: the public cause behind our own reason (M6.4). */
    private static dev.otectus.mcacrime.api.event.RestraintRemovedEvent.Cause cause(Reason reason) {
        return switch (reason) {
            case BROKEN -> dev.otectus.mcacrime.api.event.RestraintRemovedEvent.Cause.BROKEN;
            case CREATIVE, ADMINISTRATIVE ->
                    dev.otectus.mcacrime.api.event.RestraintRemovedEvent.Cause.ADMINISTRATIVE;
            default -> dev.otectus.mcacrime.api.event.RestraintRemovedEvent.Cause.RELEASED;
        };
    }

    /**
     * Whether a removal owes an item back at all.
     *
     * <p>Pure, because this is the rule that decides whether an upgraded world can be farmed for free
     * restraints, and a rule that important should be assertable without a world to run it in. The
     * live path is {@link #resolveItem}, which asks this first and does nothing else if the answer is
     * no.
     */
    public static boolean returnsItem(AppliedRestraint.Provenance provenance,
                                      AppliedRestraint.ReturnPolicy policy, Reason reason,
                                      boolean dropWhenBroken) {
        if (provenance != AppliedRestraint.Provenance.PLAYER_OWNED) {
            return false; // system-issued and legacy-converted gear was never taken from anybody
        }
        if (policy == AppliedRestraint.ReturnPolicy.NONE) {
            return false;
        }
        return reason != Reason.BROKEN || dropWhenBroken;
    }

    /**
     * Gives back at most one item, exactly once.
     *
     * <p>Every branch that returns nothing is deliberate:
     * <ul>
     *   <li>system-issued and legacy-converted gear was never taken from anybody, so returning it
     *       would mint an item — the failure the specification names as "a migration that creates a
     *       free extra cuff on every login";</li>
     *   <li>a {@link Reason#BROKEN} restraint is destroyed unless
     *       {@code restraints.escape.dropItemWhenBroken} says otherwise, because breaking out of cuffs
     *       that then drop intact makes struggling the cheapest way to keep a spare pair;</li>
     *   <li>a return policy of {@code NONE} means the application already decided release owes
     *       nothing.</li>
     * </ul>
     */
    private static ItemStack resolveItem(LivingEntity subject, AppliedRestraint worn, Reason reason,
                                         @Nullable ServerPlayer recipient) {
        if (!returnsItem(worn.provenance(), worn.returnPolicy(), reason, dropItemWhenBroken())) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = worn.stack(subject.level().registryAccess());
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ServerPlayer target = recipient;
        if (worn.returnPolicy() == AppliedRestraint.ReturnPolicy.RETURN_TO_APPLIER && target == null) {
            target = applierPlayer(subject, worn);
        }
        if (target != null && target.getInventory().add(stack.copy())) {
            return stack;
        }
        subject.spawnAtLocation(stack.copy());
        return stack;
    }

    @Nullable
    private static ServerPlayer applierPlayer(Entity subject, AppliedRestraint worn) {
        if (worn.applier().kind() != RestraintApplier.Kind.PLAYER || subject.getServer() == null) {
            return null;
        }
        return worn.applier().entityId()
                .map(id -> subject.getServer().getPlayerList().getPlayer(id))
                .orElse(null);
    }

    private static boolean dropItemWhenBroken() {
        try {
            return McaCrimeConfig.COMMON.dropItemWhenBroken.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /**
     * Whether this actor may use an operator tool on this subject, as the server sees it.
     *
     * <p>Authority comes from the connection, never from holding the item. The source's
     * {@code CreativeRestraintCutter} has no check at all, so anybody who picks one up can cut every
     * restraint in the world including a lawfully arrested prisoner's.
     */
    public static boolean operatorAuthorised(@Nullable ServerPlayer actor) {
        return CreativeAuthorization.permits(actor);
    }
}

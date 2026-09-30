package dev.otectus.mcacrime.restraint;

import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The six-step commit that puts one restraint on one subject (specification §7.2).
 *
 * <p>Resolve, check, classify, reserve one exact item, commit once, publish afterwards — in that
 * order, and with the commit as the single point of no return. The order is the whole design: every
 * refusal happens before anything is spent, so a rejected application costs nobody an item, and the
 * commit writes exactly one row so a duplicated delivery cannot equip twice.
 *
 * <p>This class owns steps one to five over pure world state. Item movement, range, line of sight,
 * sounds and packets belong to {@code RestraintService}, which is the layer that has entities. The
 * split is what lets the interesting failures — two actors racing for one slot, the same interaction
 * arriving twice from main hand and off hand — be asserted without a running server.
 *
 * <h2>Duplicate delivery</h2>
 * A single right-click can reach the server more than once: Forge fires {@code EntityInteract} and
 * {@code EntityInteractSpecific}, and a two-handed delivery adds another. The dedupe key is
 * (actor, subject, slot, tick), held transiently: a second arrival in the same tick is refused with
 * {@link Refusal#DUPLICATE_DELIVERY} rather than applying a second restraint or consuming a second
 * item. The source has no such guard and relies on the first call's {@code stack.shrink} being seen
 * by the second, which is a race rather than a rule.
 */
public final class ApplicationTransaction {

    /** How many ticks of delivery keys to remember. Two is enough for one interaction's echoes. */
    private static final long DEDUPE_WINDOW_TICKS = 2L;

    /** Transient, server-thread only, and bounded by {@link #prune}. Never persisted. */
    private static final Map<String, Long> RECENT_DELIVERIES = new LinkedHashMap<>();

    private ApplicationTransaction() {
    }

    /** Why an application did not happen. {@link #NONE} accompanies a success. */
    public enum Refusal {
        NONE,
        /** No subject id, or a subject the store will not accept a row for. */
        NO_SUBJECT,
        /** The item names no definition for that slot. */
        NO_DEFINITION,
        /** Something is already worn there. Replacing is a removal followed by an application. */
        SLOT_OCCUPIED,
        /** The subject's rig has no such region. */
        RIG_UNSUPPORTED,
        /** A configured vulnerability gate was not met. */
        NOT_VULNERABLE,
        /** {@code restraints.application.allowSelfApplication} is off and this was self-applied. */
        SELF_APPLICATION_DISABLED,
        /** The same interaction already applied this restraint in this tick. */
        DUPLICATE_DELIVERY,
        /** The world store refused the write: read-only, future schema, or at capacity. */
        STORE_REFUSED,
        /**
         * The donor mod is installed and {@code compatibility.cuffedCoexistence} is REFUSE (§3.17).
         *
         * <p>Application only. Removal, recovery and loading an existing restraint are unaffected,
         * because a disabled mechanic must still let an operator get equipment off somebody.
         */
        COEXISTENCE_REFUSED,
        /**
         * The applier is further away than {@code restraints.application.maxRangeBlocks}, or cannot
         * see the subject while {@code restraints.application.requireLineOfSight} is on.
         *
         * <p>Never produced for a self-application or a device, which have no distance to cover.
         */
        OUT_OF_REACH
    }

    /**
     * Everything the commit needs, already resolved.
     *
     * <p>A value rather than a pile of arguments because it is also what the server layer builds and
     * then re-validates: the request that survives the checks is the request that is committed, with
     * no chance of a field being recomputed differently between the two.
     *
     * @param subject        who is being restrained
     * @param subjectIsPlayer whether the subject is a player, which decides where else state lives
     * @param dimension      the subject's dimension at application time
     * @param slot           the region the restraint goes on
     * @param definitionId   which of the nine definitions
     * @param applier        who is applying it
     * @param actorId        the acting entity, for the duplicate-delivery key; null for a device
     * @param context        under what authority
     * @param provenance     where the item came from
     * @param returnPolicy   what removal owes
     * @param custodyId      the legal custody this belongs to, when it belongs to one
     * @param itemSnapshot   the exact stack that was spent, or null for system-issued gear
     */
    public record Request(UUID subject, boolean subjectIsPlayer, @Nullable ResourceLocation dimension,
                          RestraintSlot slot, ResourceLocation definitionId, RestraintApplier applier,
                          @Nullable UUID actorId, AppliedRestraint.ApplicationContext context,
                          AppliedRestraint.Provenance provenance, AppliedRestraint.ReturnPolicy returnPolicy,
                          @Nullable UUID custodyId, @Nullable CompoundTag itemSnapshot) {

        public Request {
            applier = applier == null ? RestraintApplier.none() : applier;
            context = context == null ? AppliedRestraint.ApplicationContext.UNLAWFUL : context;
            provenance = provenance == null ? AppliedRestraint.Provenance.SYSTEM_ISSUED : provenance;
            returnPolicy = returnPolicy == null ? AppliedRestraint.ReturnPolicy.NONE : returnPolicy;
            itemSnapshot = itemSnapshot == null ? null : itemSnapshot.copy();
        }

        @Override
        public CompoundTag itemSnapshot() {
            return itemSnapshot == null ? null : itemSnapshot.copy();
        }
    }

    /**
     * What the transaction did.
     *
     * @param applied   true only when a row was written
     * @param refusal   why not, when it was not
     * @param restraint the instance that was written, for the caller to publish
     */
    public record Result(boolean applied, Refusal refusal, @Nullable AppliedRestraint restraint,
                         @Nullable RestraintSlot slot) {

        public static Result refused(Refusal refusal) {
            return new Result(false, refusal, null, null);
        }

        public static Result success(AppliedRestraint restraint, RestraintSlot slot) {
            return new Result(true, Refusal.NONE, restraint, slot);
        }
    }

    /**
     * Steps one to three: does this application stand, ignoring the item and the clock?
     *
     * <p>Separated so the server layer can tell a player "that slot is taken" before it starts
     * looking through their inventory for something to spend.
     */
    public static Refusal check(@Nullable CrimeWorldData data, @Nullable Request request,
                                @Nullable RigProfile rig) {
        if (request == null || request.subject() == null || request.slot() == null) {
            return Refusal.NO_SUBJECT;
        }
        RestraintDefinition definition = RestraintDefinitions.get(request.definitionId()).orElse(null);
        if (definition == null || definition.device()
                || definition.slot().filter(request.slot()::equals).isEmpty()) {
            return Refusal.NO_DEFINITION;
        }
        if (rig != null && !definition.fits(rig)) {
            return Refusal.RIG_UNSUPPORTED;
        }
        PhysicalRestraintState state = data == null ? null : data.physicalRestraint(request.subject());
        if (state != null && state.occupied(request.slot())) {
            return Refusal.SLOT_OCCUPIED;
        }
        return Refusal.NONE;
    }

    /**
     * Steps four to five: reserve the delivery key and write the one row.
     *
     * <p>The item is reserved by the caller before this runs and spent by the caller after it
     * succeeds — in that order, so a store refusal at the last moment cannot cost an item. What is
     * reserved <em>here</em> is the delivery key, which is the thing two racing calls contend for.
     *
     * @return a success carrying the instance to publish, or the reason nothing happened
     */
    public static Result commit(@Nullable CrimeWorldData data, @Nullable Request request,
                                @Nullable RigProfile rig, long gameTime) {
        Refusal refusal = check(data, request, rig);
        if (refusal != Refusal.NONE) {
            return Result.refused(refusal);
        }
        if (!claimDelivery(request, gameTime)) {
            return Result.refused(Refusal.DUPLICATE_DELIVERY);
        }
        RestraintDefinition definition = RestraintDefinitions.get(request.definitionId()).orElseThrow();
        PhysicalRestraintState existing = data.physicalRestraint(request.subject());
        PhysicalRestraintState base = existing != null ? existing
                : PhysicalRestraintState.empty(request.subject(), request.subjectIsPlayer(),
                        request.dimension());
        AppliedRestraint applied = AppliedRestraint.of(definition, request.itemSnapshot(),
                request.applier(), request.context(), request.provenance(), request.returnPolicy(),
                request.custodyId(), gameTime);
        if (!data.putPhysicalRestraint(base.with(request.slot(), applied))) {
            // The store refused after the key was claimed. Release it, or one failed write would
            // block the retry that a transient capacity problem is meant to allow.
            releaseDelivery(request, gameTime);
            return Result.refused(Refusal.STORE_REFUSED);
        }
        return Result.success(applied, request.slot());
    }

    /**
     * Claims the (actor, subject, slot, tick) delivery key, or reports that it is already taken.
     *
     * <p>A device application has no actor, so its key is the device's own request identity: two
     * dispensers firing at the same subject in the same tick are two applications, and only the first
     * finds the slot free anyway.
     */
    private static boolean claimDelivery(Request request, long gameTime) {
        prune(gameTime);
        String key = deliveryKey(request, gameTime);
        return RECENT_DELIVERIES.putIfAbsent(key, gameTime) == null;
    }

    private static void releaseDelivery(Request request, long gameTime) {
        RECENT_DELIVERIES.remove(deliveryKey(request, gameTime));
    }

    private static String deliveryKey(Request request, long gameTime) {
        return (request.actorId() == null ? "device" : request.actorId().toString())
                + '/' + request.subject() + '/' + request.slot().id() + '/' + gameTime;
    }

    private static void prune(long gameTime) {
        RECENT_DELIVERIES.entrySet()
                .removeIf(entry -> gameTime - entry.getValue() > DEDUPE_WINDOW_TICKS);
    }

    /** Forgets every remembered delivery. Server shutdown, and the start of each test. */
    public static void clearDeliveries() {
        RECENT_DELIVERIES.clear();
    }

    /**
     * Which definition an item applies on a given slot, or empty when it applies none there.
     *
     * <p>The item-to-definition direction, which only exists here: a definition names its item, so
     * answering "what does this pair of cuffs become on the legs" is a search of the table rather
     * than a field lookup. Bounded by ten entries, and the readable direction is worth more than the
     * lookup map that would invert it.
     */
    public static Optional<ResourceLocation> definitionFor(@Nullable RestraintFamily family,
                                                           @Nullable RestraintSlot slot) {
        if (family == null || slot == null) {
            return Optional.empty();
        }
        for (RestraintDefinition definition : RestraintDefinitions.wearable()) {
            if (definition.family().filter(family::equals).isPresent()
                    && definition.slot().filter(slot::equals).isPresent()) {
                return Optional.of(definition.id());
            }
        }
        return Optional.empty();
    }
}

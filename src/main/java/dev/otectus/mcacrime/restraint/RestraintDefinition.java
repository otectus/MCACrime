package dev.otectus.mcacrime.restraint;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * One kind of restraint, immutably (§3.1, specification §6.2).
 *
 * <p>Definitions are a namespace of their own and are not items: {@code mcacrime:handcuffs_arms} is
 * a definition that <em>names</em> the item {@code mcacrime:restraint_locked_cuffs}. The direction
 * matters — two definitions (arms and legs) share one item, and the protected cuff icons keep their
 * registry ids while changing which family they mean.
 *
 * <p>The item and key are held as {@link ResourceLocation}s rather than {@code Item} instances. An
 * {@code Item} only exists once the registry has been populated, while definitions are needed by
 * migration, persistence and unit tests, none of which have a registry. Resolution to a real item
 * happens on the server, at the one moment an item has to become real.
 *
 * <p>A definition is never handed out as per-subject state. {@link AppliedRestraint} is the
 * instance, and deserialising one constructs a new instance rather than returning the registry
 * object — the upstream defect §6.2 names ({@code RestraintAPI.getNewRestraintByKey} returns the
 * registered singleton, so two players in handcuffs shared one durability counter).
 *
 * @param id               the definition id, e.g. {@code mcacrime:handcuffs_arms}
 * @param slot             the body slot it occupies, empty for a device-held definition (the pillory)
 * @param family           its key family, empty for a device-held definition
 * @param item             the item that applies it, empty for a device-held definition
 * @param keyItem          the item that opens it without work, empty when no key exists
 * @param restrictions     its contribution to the composed {@link RestrictionPolicy}
 * @param escape           durability and the routes out
 * @param pick             lockpicking parity parameters, or {@link PickProfile#unpickable()}
 * @param render           what the client draws
 * @param rigPredicate     which bodies can wear it
 * @param allowedEnchantments enchantment ids this definition accepts
 * @param statistics       the three per-family statistics, where the source defines them
 * @param applySound       the sound played on application, where the source names one
 * @param revision         bumped when the definition's meaning changes, so instances can detect it
 */
public record RestraintDefinition(
        ResourceLocation id,
        Optional<RestraintSlot> slot,
        Optional<RestraintFamily> family,
        Optional<ResourceLocation> item,
        Optional<ResourceLocation> keyItem,
        RestrictionPolicy restrictions,
        EscapeProfile escape,
        PickProfile pick,
        RenderProfile render,
        Predicate<RigProfile> rigPredicate,
        Set<ResourceLocation> allowedEnchantments,
        Statistics statistics,
        Optional<ResourceLocation> applySound,
        int revision) {

    public RestraintDefinition {
        slot = slot == null ? Optional.empty() : slot;
        family = family == null ? Optional.empty() : family;
        item = item == null ? Optional.empty() : item;
        keyItem = keyItem == null ? Optional.empty() : keyItem;
        restrictions = restrictions == null ? RestrictionPolicy.unrestricted() : restrictions;
        escape = escape == null ? EscapeProfile.none() : escape;
        pick = pick == null ? PickProfile.unpickable() : pick;
        render = render == null ? RenderProfile.none() : render;
        rigPredicate = rigPredicate == null ? rig -> true : rigPredicate;
        allowedEnchantments = allowedEnchantments == null ? Set.of() : Set.copyOf(allowedEnchantments);
        statistics = statistics == null ? Statistics.none() : statistics;
        applySound = applySound == null ? Optional.empty() : applySound;
        revision = Math.max(1, revision);
    }

    /**
     * True when this definition is held by a device rather than worn.
     *
     * <p>{@code mcacrime:pillory} is one of the nine definitions but occupies no body slot: the
     * device detains, and its restrictions compose on top of whatever gear the subject is wearing.
     * That is the "separate from head-slot gear" note in the §7.1 matrix.
     */
    public boolean device() {
        return slot.isEmpty();
    }

    /** Whether {@code rig} has the region this definition needs and its predicate accepts it. */
    public boolean fits(RigProfile rig) {
        if (rig == null) {
            return false;
        }
        if (slot.isPresent() && !rig.supports(slot.get())) {
            return false;
        }
        return rigPredicate.test(rig);
    }

    /** Whether {@code keyItemId} is this definition's key. Identity is the item id (§3.7). */
    public boolean openedBy(ResourceLocation keyItemId) {
        return keyItemId != null && keyItem.isPresent() && keyItem.get().equals(keyItemId);
    }

    /**
     * Durability and the ways out.
     *
     * @param durability            configured starting durability; source values are handcuffs 40,
     *                              shackles 15, tape 5 — never the placeholder 999 in the
     *                              upstream item registration
     * @param struggleBreakable     whether struggle work can break it at all
     * @param baseBreakChance       the pre-Unbreaking roll per accepted struggle input
     * @param removableWithCuttingTool whether shears or a blade end it without a key
     */
    public record EscapeProfile(int durability, boolean struggleBreakable, double baseBreakChance,
                                boolean removableWithCuttingTool) {

        public EscapeProfile {
            durability = Math.max(0, durability);
            baseBreakChance = Double.isFinite(baseBreakChance)
                    ? Math.max(0.0D, Math.min(1.0D, baseBreakChance))
                    : 0.0D;
        }

        /** No durability and no self-escape: a device's own escape work lives on its record. */
        public static EscapeProfile none() {
            return new EscapeProfile(0, false, 0.0D, false);
        }
    }

    /**
     * The lockpicking parity parameters for this definition (specification §8).
     *
     * <p>Kept as the source's two numbers rather than a difficulty word, because the meter contract
     * — start 30, win 40, fail 0 — is shared and only these two vary per target.
     *
     * @param pickable         whether a lockpick works on it at all
     * @param progressIncrease meter gain per successful alignment
     * @param speedIncrease    the drain parameter; higher is harder
     */
    public record PickProfile(boolean pickable, int progressIncrease, int speedIncrease) {

        public PickProfile {
            progressIncrease = Math.max(0, progressIncrease);
            speedIncrease = Math.max(0, speedIncrease);
        }

        public static PickProfile unpickable() {
            return new PickProfile(false, 0, 0);
        }

        public static PickProfile of(int progressIncrease, int speedIncrease) {
            return new PickProfile(true, progressIncrease, speedIncrease);
        }
    }

    /**
     * What the client is told to draw. Render data only: no policy, no lock secret.
     *
     * @param pose              the pose the subject is drawn in
     * @param firstPersonOverlay whether the subject's own screen gets an overlay (a hood)
     */
    public record RenderProfile(RestraintPose pose, boolean firstPersonOverlay) {

        public RenderProfile {
            pose = pose == null ? RestraintPose.NONE : pose;
        }

        public static RenderProfile none() {
            return new RenderProfile(RestraintPose.NONE, false);
        }
    }

    /** The three source statistics a family keeps, where the source defines them (Appendix A.3). */
    public record Statistics(Optional<ResourceLocation> timesRestrained,
                             Optional<ResourceLocation> broken,
                             Optional<ResourceLocation> timeSpentRestrained) {

        public Statistics {
            timesRestrained = timesRestrained == null ? Optional.empty() : timesRestrained;
            broken = broken == null ? Optional.empty() : broken;
            timeSpentRestrained = timeSpentRestrained == null ? Optional.empty() : timeSpentRestrained;
        }

        public static Statistics none() {
            return new Statistics(Optional.empty(), Optional.empty(), Optional.empty());
        }

        /** The source's three-statistic set for one family prefix, e.g. {@code handcuffs}. */
        public static Statistics forPrefix(String namespace, String prefix) {
            return new Statistics(
                    Optional.of(new ResourceLocation(namespace, prefix + "_times_restrained")),
                    Optional.of(new ResourceLocation(namespace, prefix + "_broken")),
                    Optional.of(new ResourceLocation(namespace, prefix + "_time_spent_restrained")));
        }
    }

    /** How a restrained subject is drawn. */
    public enum RestraintPose {
        NONE,
        /** Hands bound in front: the strong arm pose. */
        ARMS_BOUND,
        /** Hands loosely bound; the arms still move. */
        ARMS_LOOSE,
        LEGS_BOUND,
        HOODED,
        /** Held by a device: the pose belongs to the device, not to worn gear. */
        DETAINED
    }
}

package dev.otectus.mcacrime.restraint;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Which body region an interaction landed on (0.7.5 M2.4).
 *
 * <p>Normalised, as a fraction of the subject's own bounding-box height, rather than the source's
 * absolute world heights. {@code RestrainableCapability.onInteractedByOther} splits at 1.5 and 0.33
 * blocks, which are the right numbers for exactly one body: a standing 1.8-block vanilla player. A
 * crouching player is 1.5 tall, so every hit on them is "legs or arms" and the head region is
 * unreachable; an MCA child, a Townstead life stage or any scaled rig is wrong in the same way, in
 * the other direction. The fractions below are those two heights divided by 1.8, so a standing
 * player reproduces the source's practical split exactly and every other body works too.
 *
 * <p>Pure: height and offset in, region out. A slot is a <em>request</em> either way — the server
 * rechecks it against the rig and against what is already worn before anything is applied, because
 * the fraction comes from a client-supplied hit vector and a hit vector can be aimed at a region the
 * subject does not have.
 */
public final class BodyRegionResolver {

    /** Above this fraction of the rig's height is the head. 1.5 / 1.8, the source's split. */
    public static final double HEAD_FRACTION = 1.5D / 1.8D;

    /** At or below this fraction is the legs. 0.33 / 1.8, the source's split. */
    public static final double LEGS_FRACTION = 0.33D / 1.8D;

    private BodyRegionResolver() {
    }

    /** Why no region could be named. {@link #NONE} accompanies a resolved slot. */
    public enum Unavailable {
        /** A region was resolved. */
        NONE,
        /** No subject, or a subject with no usable bounding box. */
        NO_RIG,
        /** The region exists on the body but this rig does not carry gear there. */
        REGION_MISSING,
        /** The hit was outside the subject's own height: nothing was aimed at. */
        OUT_OF_BOUNDS
    }

    /**
     * A resolved region, or an explicit reason there is none.
     *
     * <p>Never a defaulted slot. Falling back to the arms when a rig cannot be read is how a
     * restraint ends up on a body that cannot wear it, and then cannot be taken off it.
     */
    public record Region(Optional<RestraintSlot> slot, Unavailable reason) {

        public Region {
            slot = slot == null ? Optional.empty() : slot;
            reason = reason == null ? Unavailable.NO_RIG : reason;
        }

        public static Region of(RestraintSlot slot) {
            return new Region(Optional.of(slot), Unavailable.NONE);
        }

        public static Region unavailable(Unavailable reason) {
            return new Region(Optional.empty(), reason);
        }

        public boolean resolved() {
            return slot.isPresent();
        }
    }

    /**
     * The region a hit {@code heightAboveFeet} up a rig {@code rigHeight} tall landed on.
     *
     * <p>A hit exactly at the feet is legs and a hit at the crown is head; anything outside
     * {@code [0, rigHeight]} is refused rather than clamped, because a hit above somebody's head is
     * a hit at the air behind them, not an attempt to hood them.
     */
    public static Region resolve(double heightAboveFeet, double rigHeight, @Nullable RigProfile rig) {
        if (!Double.isFinite(heightAboveFeet) || !Double.isFinite(rigHeight) || rigHeight <= 0.0D) {
            return Region.unavailable(Unavailable.NO_RIG);
        }
        // A small tolerance at each end: a hit registered a hair outside the box is still aimed at the
        // body, and refusing it would make the crown and the feet unhittable in practice.
        double tolerance = rigHeight * 0.02D;
        if (heightAboveFeet < -tolerance || heightAboveFeet > rigHeight + tolerance) {
            return Region.unavailable(Unavailable.OUT_OF_BOUNDS);
        }
        double fraction = heightAboveFeet / rigHeight;
        RestraintSlot slot;
        if (fraction > HEAD_FRACTION) {
            slot = RestraintSlot.HEAD;
        } else if (fraction <= LEGS_FRACTION) {
            slot = RestraintSlot.LEGS;
        } else {
            slot = RestraintSlot.ARMS;
        }
        if (rig != null && !rig.supports(slot)) {
            return Region.unavailable(Unavailable.REGION_MISSING);
        }
        return Region.of(slot);
    }

    /** The region an interaction at {@code hit} landed on, for a live subject. */
    public static Region resolve(@Nullable Entity subject, @Nullable Vec3 hit, @Nullable RigProfile rig) {
        if (subject == null || hit == null) {
            return Region.unavailable(Unavailable.NO_RIG);
        }
        double height = subject.getBoundingBox().getYsize();
        return resolve(hit.y - subject.getY(), height, rig);
    }

    /**
     * The region for a {@code slot} the subject explicitly asked for, checked against their rig.
     *
     * <p>The self panel and the menu send a slot rather than a hit vector, which is the correction to
     * the source's self-application: it decides the slot from the player's view pitch, and its test
     * compares a pitch in degrees against a bound in radians ({@code mixin/PlayerMixin}), so two of
     * the three regions are unreachable. An explicit request has no such failure mode — but it is
     * still a request, so the rig is still checked here.
     */
    public static Region requested(@Nullable RestraintSlot slot, @Nullable RigProfile rig) {
        if (slot == null) {
            return Region.unavailable(Unavailable.NO_RIG);
        }
        if (rig != null && !rig.supports(slot)) {
            return Region.unavailable(Unavailable.REGION_MISSING);
        }
        return Region.of(slot);
    }
}

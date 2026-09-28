package dev.otectus.mcacrime.tether;

import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The arithmetic of being held to something (0.7.5 M4.1).
 *
 * <p>Pure, finite and bounded, and every one of those three words is a fix. The source replaces the
 * subject's velocity outright with {@code copySign(dx * dx * (distance / 5) * 0.45, dx)} once per
 * tick past the threshold ({@code mixin/LivingEntityMixin.java:92-154}), which is unbounded in the
 * distance, applied on the client as well with no server agreement, and produces a non-finite
 * velocity the moment a coordinate is non-finite. Here the correction is a <em>nudge added to</em>
 * the subject's own motion, capped at {@link #MAX_CORRECTION} blocks per tick, and any non-finite
 * input answers {@link Vec3#ZERO} rather than propagating a NaN into an entity's position.
 *
 * <p>Nothing in this class touches an entity, a level or a config. That is what lets the rules a
 * player would report as broken — "the chain yanked me through a wall", "the chain never pulled at
 * all", "I was hurt before it even tightened" — be asserted in a unit test.
 */
public final class TetherPhysics {

    /** The hardest pull applied in one tick, in blocks. A lead tightens; it does not teleport. */
    public static final double MAX_CORRECTION = 0.28D;

    /** How far a tether may reach before the arithmetic refuses to believe the coordinates. */
    public static final double MAX_SANE_DISTANCE = 4.0E6D;

    /** How deep a holder chain is walked looking for a cycle. Small: a real chain is two or three long. */
    public static final int MAX_CHAIN_DEPTH = 16;

    private TetherPhysics() {
    }

    /** Whether every component of {@code v} is a real number. */
    public static boolean finite(@Nullable Vec3 v) {
        return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    /** A length clamped into the range a tether is allowed to have, with non-finite falling back. */
    public static double boundedLength(double raw, double fallback) {
        if (!Double.isFinite(raw) || raw <= 0.0D) {
            return fallback;
        }
        return Math.max(0.5D, Math.min(TetherRecord.MAX_LENGTH_BLOCKS, raw));
    }

    /**
     * The distance between two points, or {@link Double#NaN} when either is unusable.
     *
     * <p>NaN rather than zero on purpose: zero would read as "they are on top of each other", which
     * is the one answer that makes a caller do nothing at all, and doing nothing about a subject at
     * an impossible coordinate is how a tether becomes permanent.
     */
    public static double distance(@Nullable Vec3 from, @Nullable Vec3 to) {
        if (!finite(from) || !finite(to)) {
            return Double.NaN;
        }
        double distance = from.distanceTo(to);
        return Double.isFinite(distance) && distance <= MAX_SANE_DISTANCE ? distance : Double.NaN;
    }

    /** Whether the tether is taut: far enough out that it should start pulling back. */
    public static boolean taut(double distance, double maxLength) {
        return Double.isFinite(distance) && distance > Math.max(0.5D, maxLength);
    }

    /**
     * Whether the tether is overextended: far enough out that it begins to hurt.
     *
     * <p>Strictly beyond {@code overextensionLength} <em>and</em> beyond the pull length, so a
     * mis-ordered pair of settings cannot make the first taut tick also the first damaging one.
     * {@code ConfigValidator.validateTransport} refuses that ordering; this refuses to act on it.
     */
    public static boolean overextended(double distance, double maxLength, double overextensionLength) {
        return taut(distance, maxLength) && distance > Math.max(maxLength, overextensionLength);
    }

    /**
     * The velocity to <em>add</em> to the subject's own motion this tick.
     *
     * <p>Horizontal only, for the reason the old escort lead gave: a pull with a vertical component
     * lifts a prisoner out of a hole or presses them into the floor, and a lead does neither. The
     * strength ramps from nothing at the pull length to {@link #MAX_CORRECTION} at the overextension
     * length, so the hold tightens rather than snapping.
     */
    public static Vec3 correction(@Nullable Vec3 subject, @Nullable Vec3 anchor, double maxLength,
                                  double overextensionLength) {
        double distance = distance(subject, anchor);
        if (!taut(distance, maxLength)) {
            return Vec3.ZERO;
        }
        Vec3 toAnchor = anchor.subtract(subject);
        Vec3 horizontal = new Vec3(toAnchor.x, 0.0D, toAnchor.z);
        double planar = horizontal.length();
        if (!Double.isFinite(planar) || planar < 1.0E-4D) {
            return Vec3.ZERO;
        }
        double span = Math.max(1.0E-3D, Math.max(maxLength, overextensionLength) - maxLength);
        double progress = Math.max(0.0D, Math.min(1.0D, (distance - maxLength) / span));
        Vec3 pull = horizontal.scale(MAX_CORRECTION * progress / planar);
        return finite(pull) ? pull : Vec3.ZERO;
    }

    /**
     * Whether attaching {@code subject} to {@code holder} would close a loop.
     *
     * <p>Walks the holder chain from the proposed holder looking for the subject, bounded by
     * {@link #MAX_CHAIN_DEPTH} and by a visited set so a loop that already exists cannot make this
     * run forever. A cycle is the one tether shape with no correct physics: two subjects each pulling
     * the other converge on a shared point and stay there, which reads in-world as both being frozen
     * to the spot by nothing.
     *
     * @param holderOf the current holder of a subject, or null when nobody holds them
     */
    public static boolean formsCycle(@Nullable UUID subject, @Nullable UUID holder,
                                     Function<UUID, UUID> holderOf) {
        if (subject == null || holder == null) {
            return false;
        }
        if (subject.equals(holder)) {
            return true; // nobody leads themselves
        }
        Set<UUID> seen = new HashSet<>();
        UUID cursor = holder;
        for (int depth = 0; depth < MAX_CHAIN_DEPTH && cursor != null; depth++) {
            if (!seen.add(cursor)) {
                return true; // the existing chain already loops; do not add to it
            }
            if (subject.equals(cursor)) {
                return true;
            }
            cursor = holderOf.apply(cursor);
        }
        return false;
    }

    /**
     * The suspension damage for one tick, attributed elsewhere.
     *
     * <p>Zero whenever the tether is not overextended, whenever the configured amount is not a real
     * positive number, and whenever the holder is transporting the subject under a custody policy
     * that does not deliberately harm prisoners ({@code transport.guardTransportHarmless}).
     */
    public static float suspensionDamage(double distance, double maxLength, double overextensionLength,
                                         double perTick, boolean harmlessTransport) {
        if (harmlessTransport || !Double.isFinite(perTick) || perTick <= 0.0D) {
            return 0.0F;
        }
        if (!overextended(distance, maxLength, overextensionLength)) {
            return 0.0F;
        }
        return (float) Math.min(20.0D, perTick);
    }
}

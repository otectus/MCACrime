package dev.otectus.mcacrime.tether;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Walking somebody somewhere, without teleporting them (0.7.5 M4.3).
 *
 * <p>The replacement for {@code enforcement/EscortRestraint}'s per-player pull and for the source's
 * escort tick, and the fix for the ordering defect in both. Upstream teleports the escortee to a point
 * beside the escort <em>before</em> evaluating its own break conditions
 * ({@code cap/RestrainableCapability.java:113-126}), so its 3.5-block break can never fire: by the time
 * the distance is measured the subject has already been dragged to within one block of the escort.
 * Here <b>validity is decided first</b>, from the facts as they are, and only a tether that is still
 * valid applies any correction at all.
 *
 * <p>The correction itself is a bounded velocity nudge added to the subject's own motion, plus — for
 * an NPC — a navigation order toward the holder. Never a {@code teleportTo}, so a prisoner is never
 * pulled through a wall, and never a competing movement loop, because the navigation order is issued
 * at the holder's position rather than at a path this class computes for itself.
 */
public final class EscortTransport {

    /** How often an NPC subject is re-pointed at their holder. Re-pathing every tick is churn. */
    private static final int NAVIGATION_INTERVAL_TICKS = 10;

    /** What this tick did, so a caller can log or test it without reading an entity. */
    public enum Step {
        /** The tether is gone, invalid, or across a dimension. The caller should end it. */
        BROKEN,
        /** Inside the pull length: nothing to correct. */
        SLACK,
        /** Past the pull length: a bounded nudge was applied. */
        PULLED,
        /** Past the overextension length: the nudge, and suspension damage. */
        STRAINED
    }

    private EscortTransport() {
    }

    /**
     * Whether this tether should still be acting at all, from facts alone.
     *
     * <p>Pure, and evaluated <em>before</em> any correction, which is the whole point. A subject who
     * is further away than the break distance has got away; deciding that after dragging them back
     * is deciding it about a different world than the one the player is in.
     */
    public static boolean valid(boolean subjectAlive, boolean holderPresent, boolean sameDimension,
                                double distance, double breakDistance) {
        if (!subjectAlive || !holderPresent || !sameDimension) {
            return false;
        }
        if (!Double.isFinite(distance)) {
            return false; // an impossible coordinate is not a hold
        }
        return breakDistance <= 0.0D || distance <= breakDistance;
    }

    /**
     * How far a subject may get before the hold is considered broken.
     *
     * <p>Derived from the overextension length rather than configured separately: the band between
     * "taut" and "broken" is what suspension damage happens in, and a break distance shorter than it
     * would mean a tether that never hurt anybody and one longer would mean one that never let go.
     */
    public static double breakDistance(double overextensionLength) {
        return Math.max(1.0D, overextensionLength) * 2.0D;
    }

    /** One tick of an escort. */
    public static Step tick(@Nullable MinecraftServer server, @Nullable CrimeWorldData data,
                            @Nullable LivingEntity subject, @Nullable TetherRecord tether) {
        return advance(server, data, subject, tether, true);
    }

    /** One tick of a chain or an anchor: the same physics, without the navigation order. */
    public static Step tickChain(@Nullable MinecraftServer server, @Nullable CrimeWorldData data,
                                 @Nullable LivingEntity subject, @Nullable TetherRecord tether) {
        return advance(server, data, subject, tether, false);
    }

    private static Step advance(@Nullable MinecraftServer server, @Nullable CrimeWorldData data,
                                @Nullable LivingEntity subject, @Nullable TetherRecord tether,
                                boolean escort) {
        if (server == null || data == null || subject == null || tether == null) {
            return Step.BROKEN;
        }
        Entity holder = tether.holder() == null ? null : TetherService.find(server, tether.holder());
        Vec3 anchorPoint = anchorPoint(subject, tether, holder);
        double maxLength = Math.max(0.5D, tether.lengthBlocks());
        double overextension = TetherService.overextensionLength();
        double distance = TetherPhysics.distance(subject.position(), anchorPoint);

        boolean sameDimension = anchorPoint != null
                && (holder == null
                        || holder.level().dimension().equals(subject.level().dimension()));
        if (!valid(subject.isAlive(), anchorPoint != null, sameDimension, distance,
                breakDistance(overextension))) {
            TetherService.detach(server, data, tether.id(), TetherService.DetachReason.HOLDER_LOST);
            return Step.BROKEN;
        }

        if (escort && holder != null) {
            navigate(subject, holder);
        }
        Vec3 correction = TetherPhysics.correction(subject.position(), anchorPoint, maxLength,
                overextension);
        if (correction.lengthSqr() <= 0.0D) {
            return Step.SLACK;
        }
        subject.setDeltaMovement(subject.getDeltaMovement().add(correction));
        if (subject instanceof ServerPlayer player) {
            // Load-bearing: without it the server's velocity change is never sent to the owning
            // client and the client's own prediction simply wins, so the player walks away through a
            // pull the server believes it applied.
            player.hurtMarked = true;
        } else {
            subject.hurtMarked = true;
        }
        boolean strained = TetherDamage.applyTick(subject, holder, distance, maxLength, overextension,
                tether.kind());
        return strained ? Step.STRAINED : Step.PULLED;
    }

    /**
     * Where the far end of this tether is.
     *
     * <p>The holder's position when somebody is holding it, the anchor block's centre when a fixed
     * point is, and null when neither exists — which is what makes the validity test above answer
     * "broken" rather than pulling somebody toward the origin.
     */
    @Nullable
    public static Vec3 anchorPoint(LivingEntity subject, TetherRecord tether, @Nullable Entity holder) {
        if (holder != null && holder.isAlive()) {
            return holder.position();
        }
        if (tether.anchorPos() == null) {
            return null;
        }
        if (tether.dimension() != null
                && !tether.dimension().equals(subject.level().dimension().location())) {
            return null;
        }
        return net.minecraft.world.phys.Vec3.atCenterOf(tether.anchorPos());
    }

    /**
     * Points an NPC subject at their holder, on a throttle.
     *
     * <p>Through the compat façade, so no MCA type is named, and only every
     * {@value #NAVIGATION_INTERVAL_TICKS} ticks: a path recomputed every tick is a villager that
     * stutters on the spot, which is the shape of every "escort looks broken" report.
     */
    private static void navigate(LivingEntity subject, Entity holder) {
        if (!(subject instanceof Mob mob) || subject.tickCount % NAVIGATION_INTERVAL_TICKS != 0) {
            return;
        }
        double soft = softLeashBlocks();
        if (subject.distanceToSqr(holder) <= soft * soft) {
            return; // close enough; walking them into the guard's back is not transport
        }
        McaCompat.moveVillagerTo(mob, holder.getX(), holder.getY(), holder.getZ(), 1.0D);
    }

    private static double softLeashBlocks() {
        try {
            return McaCrimeConfig.COMMON.escortLeashBlocks.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return 3.0D;
        }
    }
}

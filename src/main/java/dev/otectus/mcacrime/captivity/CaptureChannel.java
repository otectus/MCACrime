package dev.otectus.mcacrime.captivity;

import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * One in-progress capture cast (spec §8.2): a timed channel that completes into a {@link
 * CustodyService#capture}, or breaks if the kidnapper is hit, moves too far from where they started, or the
 * target leaves range / line of sight. Transient by design (held only in {@link CaptureChannels}, never
 * persisted — a channel must not survive a restart or logout). The break geometry is exposed as pure static
 * predicates so it is unit-testable without a running game.
 */
public final class CaptureChannel {

    public final UUID kidnapper;
    public final UUID target;
    public final boolean targetIsPlayer;
    public final RestraintType restraint;
    /** The kidnapper's position when the channel began (a move beyond the limit breaks it). */
    public final Vec3 startPos;
    public final int requiredTicks;

    private int elapsed;
    private boolean broken;

    public CaptureChannel(UUID kidnapper, UUID target, boolean targetIsPlayer, RestraintType restraint,
                          Vec3 startPos, int requiredTicks) {
        this.kidnapper = kidnapper;
        this.target = target;
        this.targetIsPlayer = targetIsPlayer;
        this.restraint = restraint;
        this.startPos = startPos;
        this.requiredTicks = requiredTicks;
    }

    public void tick() {
        elapsed++;
    }

    public int elapsed() {
        return elapsed;
    }

    public boolean isComplete() {
        return elapsed >= requiredTicks;
    }

    /** Flags the channel broken (the kidnapper was hit); the ticker cancels it next pass. */
    public void markBroken() {
        broken = true;
    }

    public boolean isBroken() {
        return broken;
    }

    // ------------------------------------------------------------------ pure break geometry (testable)

    /** True when the channeler moved more than {@code maxBlocks} from where the channel began. */
    public static boolean brokeByMove(Vec3 start, Vec3 now, double maxBlocks) {
        return start.distanceToSqr(now) > maxBlocks * maxBlocks;
    }

    /** True when the target is more than {@code maxBlocks} from the channeler. */
    public static boolean outOfRange(Vec3 channeler, Vec3 target, double maxBlocks) {
        return channeler.distanceToSqr(target) > maxBlocks * maxBlocks;
    }
}

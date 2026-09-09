package dev.otectus.mcacrime.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.level.pathfinder.Path;

/** Keeps a crime walk order and the villager brain's copy of that order in agreement. */
public final class CrimeNavigation {
    private CrimeNavigation() { }

    /** MCA uses the villager movement attribute; a navigation multiplier of 1 is already a sprint. */
    public static double speed(double requested, double movementAttribute, boolean mca) {
        if (!Double.isFinite(requested) || requested <= 0 || !Double.isFinite(movementAttribute)) return 0;
        double normal = requested * (mca ? 0.5D : 1D);
        return movementAttribute > 0 ? Math.min(normal, 0.30D / movementAttribute) : normal;
    }

    public static boolean start(Mob mob, Path path, BlockPos destination, double requested, boolean mca) {
        if (!NpcAwareness.isAwake(mob) || path == null || !path.canReach()) return false;
        double speed = speed(requested, mob.getAttributeValue(Attributes.MOVEMENT_SPEED), mca);
        if (!mob.getNavigation().moveTo(path, speed)) return false;
        // MoveToTargetSink otherwise resumes a previous panic/work target at its old speed.
        var brain = mob.getBrain();
        brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(destination, (float) speed, 1));
        brain.setMemory(MemoryModuleType.PATH, path);
        brain.eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
        return true;
    }
}

package dev.otectus.mcacrime.ai.thief;

import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.enforcement.ActiveIncidentRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.schedule.Activity;

import javax.annotation.Nullable;

/**
 * Whether a Thief's station work must stand aside right now (0.7.2 §10.3).
 *
 * <p>"Work navigation yields to custody, sleep, panic, a valid enforcement action, and an already
 * running crime action." Five conditions, one function, so that the answer is the same wherever it is
 * asked and can be pinned by a test without a brain, a level or a villager.
 *
 * <p>Note what is <em>not</em> here: whether the thief feels like working. This is a yield, not a
 * schedule — when nothing on this list is true, the villager's ordinary work behaviour runs exactly as
 * MCA and vanilla wrote it.
 */
public final class ThiefWorkGate {

    private ThiefWorkGate() {
    }

    /** The decision itself, from plain facts. */
    public static boolean shouldYield(boolean captive, boolean asleep, boolean panicking,
                                      boolean enforcementActive, boolean crimeActive) {
        return captive || asleep || panicking || enforcementActive || crimeActive;
    }

    /** True when a crime controller currently owns this villager's movement. */
    public static boolean crimeActive(ThiefState state) {
        return state != null && state != ThiefState.IDLE && state != ThiefState.COOLDOWN
                && state != ThiefState.DEAD;
    }

    /** The same question about a live entity. Never throws; an unanswerable condition yields. */
    public static boolean yields(@Nullable MinecraftServer server, @Nullable LivingEntity entity) {
        if (entity == null) {
            return true;
        }
        try {
            boolean captive = server != null && CustodyRegistry.isCaptive(server, entity.getUUID());
            boolean panicking = entity.getBrain().isActive(Activity.PANIC)
                    || entity.getBrain().hasMemoryValue(MemoryModuleType.HURT_BY);
            boolean enforcement = ActiveIncidentRegistry.get(entity.getUUID()).isPresent();
            return shouldYield(captive, entity.isSleeping(), panicking, enforcement,
                    crimeActive(ThiefBehaviorService.stateOf(entity.getUUID())));
        } catch (Throwable t) {
            return true;
        }
    }
}

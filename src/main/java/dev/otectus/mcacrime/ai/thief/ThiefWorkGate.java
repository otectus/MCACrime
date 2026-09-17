package dev.otectus.mcacrime.ai.thief;

import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.enforcement.ActiveIncidentRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.schedule.Activity;

import org.jetbrains.annotations.Nullable;

/**
 * Whether a villager's station work must stand aside right now (0.7.2 §10.3).
 *
 * <p>"Work navigation yields to custody, sleep, panic, a valid enforcement action, and an already
 * running crime action." Five conditions, one function, so that the answer is the same wherever it is
 * asked and can be pinned by a test without a brain, a level or a villager.
 *
 * <p>A sixth was added with the activity registry: a villager MCA: Crime holds a live claim on. That
 * generalises this gate from "employed Thieves" to anybody an enforcement action currently owns, which
 * is what lets an arrest stop a villager walking off to a workbench without MCA: Crime knowing or
 * caring which mod put the work behaviour there.
 *
 * <p>Note what is <em>not</em> here: whether the thief feels like working. This is a yield, not a
 * schedule — when nothing on this list is true, the villager's ordinary work behaviour runs exactly as
 * MCA and vanilla wrote it. And note what it cannot do: this is <b>start-gating</b>. A behaviour
 * already running is closed through its own stop path, and a task that has staged materials of its own
 * finishes them; reconciling somebody else's half-done recipe from the outside is not something MCA:
 * Crime can do correctly, so it does not try.
 */
public final class ThiefWorkGate {

    private ThiefWorkGate() {
    }

    /**
     * The decision itself, from plain facts.
     *
     * @param incapable unable to act at all: asleep, or — with a settlement companion installed and
     *                  {@code townstead.respectIncapacity} on — collapsed or immobile
     */
    public static boolean shouldYield(boolean captive, boolean incapable, boolean panicking,
                                      boolean enforcementActive, boolean crimeActive) {
        return captive || incapable || panicking || enforcementActive || crimeActive;
    }

    /**
     * The same decision with the activity registry folded in.
     *
     * @param activityClaimed whether MCA: Crime holds a live claim that work must yield to; see
     *                        {@link dev.otectus.mcacrime.activity.OperationPolicy}, where every claim
     *                        kind yields {@link dev.otectus.mcacrime.activity.CrimeActivityOperation#WORK_START}
     */
    public static boolean shouldYield(boolean captive, boolean incapable, boolean panicking,
                                      boolean enforcementActive, boolean crimeActive,
                                      boolean activityClaimed) {
        return activityClaimed || shouldYield(captive, incapable, panicking, enforcementActive, crimeActive);
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
            boolean claimed = !dev.otectus.mcacrime.activity.CrimeActivityRegistry
                    .permits(entity.getUUID(), dev.otectus.mcacrime.activity.CrimeActivityOperation.WORK_START);
            return shouldYield(captive,
                    !dev.otectus.mcacrime.ai.NpcAwareness.canPerformCriminalAction(entity),
                    panicking, enforcement,
                    crimeActive(ThiefBehaviorService.stateOf(entity.getUUID())), claimed);
        } catch (Throwable t) {
            return true;
        }
    }
}

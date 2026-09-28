package dev.otectus.mcacrime.locks;

import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;
import java.util.Optional;

/**
 * The one question every protection hook asks: is this block locked, and does that stop this (M3.6)?
 *
 * <p>Separate from the event handlers so the vanilla hopper hook — which is a mixin and cannot carry
 * Forge event code — asks exactly the same question the interaction, break, explosion and piston
 * handlers ask. One answer, five callers.
 */
public final class LockProtection {

    private LockProtection() {
    }

    /** The lock covering {@code pos}, halves resolved, or empty. */
    public static Optional<LockRecord> lockAt(@Nullable Level level, @Nullable BlockPos pos) {
        if (level == null || pos == null || level.isClientSide()) {
            return Optional.empty();
        }
        CrimeWorldData data = LockService.data(level);
        if (data == null) {
            return Optional.empty();
        }
        return LockService.at(data, level, level.dimension().location(), pos);
    }

    /** Whether anything is locked at {@code pos} right now. */
    public static boolean locked(@Nullable Level level, @Nullable BlockPos pos) {
        return lockAt(level, pos).map(LockRecord::locked).orElse(false);
    }

    /**
     * Whether an automated transfer at {@code pos} is refused.
     *
     * <p>Asked per operation and per transfer, never cached: {@code locks.automationPolicy} and the
     * lock's own state can both change between two hopper ticks, and a cached answer is how a locked
     * safe keeps draining.
     */
    public static boolean blocksAutomation(@Nullable Level level, @Nullable BlockPos pos,
                                           LockAutomationPolicy.Operation operation) {
        if (!locked(level, pos)) {
            return false;
        }
        return !LockAutomationPolicy.configured().permits(true, operation);
    }
}

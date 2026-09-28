package dev.otectus.mcacrime.locks;

import org.jetbrains.annotations.Nullable;
import java.util.Locale;

/**
 * What automation may do to a locked container ({@code locks.automationPolicy}, spec §10.3).
 *
 * <p>Cancelling a right-click is not protection: a hopper under a locked safe does not right-click
 * it. Every automated route — hopper, hopper minecart, dropper, any {@code IItemHandler} a pipe mod
 * obtained — asks this enum, and it is asked <em>per operation</em> rather than once when a handler is
 * handed out, because a handler that was fetched while the safe was open must not keep working after
 * it is locked.
 */
public enum LockAutomationPolicy {

    /** A locked container accepts and yields nothing automatically. The default. */
    BLOCK_ALL,
    /** Automation may put things in but never take them out: a locked drop box. */
    ALLOW_INSERT,
    /** The lock governs players only; automation is unaffected. */
    ALLOW_ALL;

    /** What one automated operation is trying to do. */
    public enum Operation {
        INSERT,
        EXTRACT
    }

    /** Whether {@code operation} is allowed right now. An unlocked container allows everything. */
    public boolean permits(boolean locked, @Nullable Operation operation) {
        if (!locked || operation == null) {
            return true;
        }
        return switch (this) {
            case BLOCK_ALL -> false;
            case ALLOW_INSERT -> operation == Operation.INSERT;
            case ALLOW_ALL -> true;
        };
    }

    /** The configured value, or {@link #BLOCK_ALL} for anything unrecognised. */
    public static LockAutomationPolicy parse(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return BLOCK_ALL;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return BLOCK_ALL;
        }
    }

    /** The configured policy, or the safe default when no config is loaded. */
    public static LockAutomationPolicy configured() {
        try {
            return parse(dev.otectus.mcacrime.McaCrimeConfig.COMMON.lockAutomationPolicy.get());
        } catch (IllegalStateException notLoaded) {
            return BLOCK_ALL;
        }
    }
}

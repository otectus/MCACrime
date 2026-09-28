package dev.otectus.mcacrime.locks;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * What to do about a target another lock mod already owns ({@code locks.foreignLockPolicy}, §3.16).
 *
 * <p>Two independent access checks on one chest is the failure the specification names: each mod
 * thinks it is the authority, each answers differently, and the player is locked out of their own
 * container by whichever one ran last. So the default is to decline to be the second lock.
 */
public enum ForeignLockPolicy {

    /** Refuse to place a second lock on a target Locks Reforged already owns. The default. */
    REFUSE,
    /** Place ours anyway, and accept that two checks now govern one block. */
    IGNORE;

    public static ForeignLockPolicy parse(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return REFUSE;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return REFUSE;
        }
    }

    public static ForeignLockPolicy configured() {
        try {
            return parse(dev.otectus.mcacrime.McaCrimeConfig.COMMON.foreignLockPolicy.get());
        } catch (IllegalStateException notLoaded) {
            return REFUSE;
        }
    }
}

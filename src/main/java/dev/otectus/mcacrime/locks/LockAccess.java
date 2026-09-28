package dev.otectus.mcacrime.locks;

import org.jetbrains.annotations.Nullable;

/**
 * The pure access table: may this key open this lock, and if not, why not (§3.7, spec §10.1).
 *
 * <p>Every lock decision in the mod comes through here, and nothing here touches a level, an entity
 * or a registry. That is what makes "an old copy of a rekeyed key does not work" and "somebody else's
 * key does not work" assertable rather than asserted.
 *
 * <p>What is deliberately <b>not</b> an input: any item tag. {@code mcacrime:keys} is a cuff-family
 * tag and is explicitly not a master-key tag for block locks (§3.7); a master key is an authorisation
 * the <em>server</em> grants to an operator, which is why it arrives here as a boolean somebody else
 * already checked rather than as a stack this could inspect.
 */
public final class LockAccess {

    /** Why a lock did or did not open. */
    public enum Decision {
        /** Nothing is locked here. */
        UNLOCKED,
        /** The presented key opens it. */
        ALLOW,
        /** An operator's master key opened it. */
        ALLOW_MASTER,
        /** Locked, and nothing was presented. */
        DENY_NO_KEY,
        /** The key opens some other lock. */
        DENY_WRONG_KEY,
        /** The right lock, an out-of-date key: this lock has been rekeyed. */
        DENY_REKEYED;

        public boolean allowed() {
            return this == UNLOCKED || this == ALLOW || this == ALLOW_MASTER;
        }
    }

    private LockAccess() {
    }

    /**
     * Whether {@code presented} opens {@code lock}, and why.
     *
     * <p>Order matters. An absent lock record is an unlocked target, because a lock nobody recorded is
     * not a lock. An unlocked lock opens for anybody — a locked door is the protection, not the
     * existence of a lock. Master authority is checked before the key so an operator with a broken
     * lock can always get in. Then identity, then revision, in that order, so a player is told "wrong
     * key" rather than "rekeyed" when they are holding somebody else's key entirely.
     */
    public static Decision evaluate(@Nullable LockRecord lock, @Nullable KeyBinding presented,
                                    boolean masterAuthorised) {
        if (lock == null || !lock.locked()) {
            return Decision.UNLOCKED;
        }
        if (masterAuthorised) {
            return Decision.ALLOW_MASTER;
        }
        if (presented == null) {
            return Decision.DENY_NO_KEY;
        }
        if (!presented.lockId().equals(lock.lockId())) {
            return Decision.DENY_WRONG_KEY;
        }
        if (!lock.accepts(presented.bindingRevision())) {
            return Decision.DENY_REKEYED;
        }
        return Decision.ALLOW;
    }

    /** The short form. */
    public static boolean opens(@Nullable LockRecord lock, @Nullable KeyBinding presented) {
        return evaluate(lock, presented, false).allowed();
    }

    /**
     * Whether {@code actor} may <em>toggle</em> this lock rather than merely pass it.
     *
     * <p>A separate question from access on purpose: opening a door you have the key to and
     * re-locking somebody else's door are different acts, and the second one is what makes a key a
     * claim of ownership. Both need a current key; only toggling needs the lock to be bound at all.
     */
    public static boolean mayToggle(@Nullable LockRecord lock, @Nullable KeyBinding presented,
                                    boolean masterAuthorised) {
        if (lock == null) {
            return false;
        }
        if (masterAuthorised) {
            return true;
        }
        return presented != null && presented.opens(lock);
    }

    /** The lang key explaining a refusal to the player it refused. */
    public static String messageKey(Decision decision) {
        return switch (decision) {
            case UNLOCKED, ALLOW, ALLOW_MASTER -> "mcacrime.lock.opened";
            case DENY_NO_KEY -> "mcacrime.lock.locked";
            case DENY_WRONG_KEY -> "mcacrime.lock.wrong_key";
            case DENY_REKEYED -> "mcacrime.lock.rekeyed";
        };
    }
}

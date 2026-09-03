package dev.otectus.mcacrime.api.result;

/**
 * How a requested change to legal state turned out.
 *
 * <p>One enum shared by every mutation result, rather than one per operation. A caller almost always
 * wants the same three branches — it worked, it already had, it did not — and six near-identical
 * enums would make that harder to write and easier to get wrong.
 *
 * <p>{@link #DUPLICATE} is a <b>success</b>. It means the change this request asked for is already
 * reflected in the world, which is exactly what a retry, a replayed packet, or an outbox redelivery
 * should be told. Treating it as a failure is the single easiest way to turn a safe replay into a
 * double charge.
 */
public enum CrimeMutationStatus {

    /** The change was applied. */
    APPLIED,
    /** This exact transaction already landed. Nothing changed, and nothing needed to. */
    DUPLICATE,
    /** The target — a case, a custody record, a sentence — does not exist or did not match. */
    NO_MATCH,
    /** The caller is not permitted to do this, or the transition needs privilege it did not carry. */
    NOT_ALLOWED,
    /** The player could not cover the cost. Nothing was taken. */
    INSUFFICIENT_PAYMENT,
    /** The target exists but is in the wrong state for this change. */
    INVALID_STATE,
    /** The feature or integration is switched off by config. */
    DISABLED,
    /** Something unexpected was caught and contained. Nothing was applied. */
    ERROR;

    /** Whether the world now reflects what the caller wanted, whether or not this call did it. */
    public boolean successful() {
        return this == APPLIED || this == DUPLICATE;
    }

    public static CrimeMutationStatus parse(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return ERROR;
        }
    }
}

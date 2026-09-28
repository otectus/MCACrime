package dev.otectus.mcacrime.frisk;

import java.util.Locale;

/**
 * Why one transfer was not made (M5.2, spec §11.2).
 *
 * <p>One value per validation, so a refusal can be reported precisely instead of as a silent
 * nothing-happened. Every one of them means the same thing about state: nothing moved, nothing was
 * debited, and the searcher still has whatever they had.
 */
public enum FriskRefusal {

    /** The transfer was made. */
    NONE,
    /** No such session, or it expired, or it belongs to somebody else. */
    NO_SESSION,
    /** The menu the packet claims is not the one this player has open. */
    NOT_YOUR_MENU,
    /** Condition 1: the subject is dead, gone, or in another world. */
    SUBJECT_UNAVAILABLE,
    /** Condition 1: the searcher walked away. */
    OUT_OF_REACH,
    /** Condition 2: the subject is no longer restrained enough to be searched. */
    NOT_RESTRAINED,
    /** Condition 3: custody changed underneath the search. */
    CUSTODY_CHANGED,
    /** Condition 5: that slot holds something else now. */
    SLOT_CHANGED,
    /** Condition 6: the searcher's own inventory will not take the whole stack. */
    DESTINATION_REFUSED,
    /** The server-owned search delay has not elapsed. */
    TOO_SOON,
    /** This exact transfer has already been made. */
    ALREADY_DONE,
    /** The escrow could not record the seizure, so nothing was taken. */
    LEDGER_FULL;

    public boolean accepted() {
        return this == NONE;
    }

    /** The lang key explaining this refusal. */
    public String messageKey() {
        return "mcacrime.msg.frisk." + name().toLowerCase(Locale.ROOT);
    }
}

package dev.otectus.mcacrime.state.world;

/**
 * Whether a bounded store accepted an insertion (0.6.0).
 *
 * <p>Every collection in {@link CrimeWorldData} has a ceiling, and until now the ceiling was enforced
 * on the way <em>in from disk</em>: a load loop stopped at the cap and the entries past it were gone.
 * That is the worst possible place to enforce it, because the store had already been paid for — the
 * item was taken, the emeralds were charged, the cell was built — and the only thing lost was the
 * record saying so.
 *
 * <p>So the ceiling moves to insertion, and insertion now answers. A caller that is told {@link #FULL}
 * has not yet taken the item or the payment and can refuse the whole operation, which is the only
 * outcome that leaves the world consistent.
 */
public enum CapacityResult {
    /** Stored (or updated in place, which never needs room). */
    OK,
    /** The table is at its ceiling and this entry is not already in it. Nothing was stored. */
    FULL;

    public boolean stored() {
        return this == OK;
    }
}

package dev.otectus.mcacrime.restraint;

import java.util.Optional;

/**
 * Which input a struggle packet carried (§3.5).
 *
 * <p>Four named kinds rather than a key code. The source compares raw key-binding codes, in a client
 * screen, against a list that names other mods' bindings by string — which cannot be checked on a
 * server, cannot see a rebound key, and is meaningless on a controller. What the server actually
 * needs to know is only whether this input differs from the last one, because alternation is what
 * makes struggling a two-handed effort rather than one held button.
 *
 * <p>The ordinal is what travels on the wire, and the order is therefore fixed. Appending is safe;
 * reordering would make two builds disagree about which input a packet carried.
 */
public enum StruggleInput {

    ATTACK,
    USE,
    LEFT,
    RIGHT;

    /** The wire form: a bounded index, validated on arrival. */
    public int index() {
        return ordinal();
    }

    /** The input an index names, empty when it names none. A refusal, never a default. */
    public static Optional<StruggleInput> byIndex(int index) {
        return index >= 0 && index < values().length ? Optional.of(values()[index]) : Optional.empty();
    }
}

package dev.otectus.mcacrime.recipe;

import java.util.Locale;

/**
 * Why the Mask Station will not hand over an output right now (0.7.2 §6.4, §8.2).
 *
 * <p>One reason per refusal, and every one of them is a sentence the screen can show. That is the
 * point of naming them rather than returning a bare {@code false}: "disable extraction and explain the
 * missing quantity" is a spec requirement, and a boolean cannot explain anything.
 */
public enum MaskCraftRejection {

    /** Nothing is wrong: a craft is payable and deliverable. */
    NONE,
    /** {@code maskStation.enableMaskStationCrafting} is off, or restyling is off for a restyle. */
    DISABLED,
    /** No style is selected, or the selection did not survive the last recipe reload. */
    NO_SELECTION,
    /** The recipe itself is unusable — a nonpositive cost got past validation. */
    INVALID_RECIPE,
    NOT_ENOUGH_MATERIAL,
    NOT_ENOUGH_BINDING,
    NOT_ENOUGH_DYE,
    /** The result, or a remainder, has nowhere to go. */
    NO_ROOM,
    /**
     * The mask in the material slot carries data this mod cannot move onto another item (0.7.2
     * section 5.4, MASK-07).
     *
     * <p>The one restyle refusal a player ever sees. The others -- wrong family, unregistered style,
     * no actual change -- are enforced by the recipe never matching, so those styles are simply not
     * offered; this one is about the particular stack in the slot, and silence about it would look
     * like a bug rather than a rule.
     */
    RESTYLE_UNSUPPORTED;

    /** The suffix of {@code mcacrime.mask_station.reason.*}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}

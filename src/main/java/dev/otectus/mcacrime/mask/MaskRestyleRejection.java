package dev.otectus.mcacrime.mask;

import java.util.Locale;

/**
 * Why one mask may not become another (0.7.2 §5.4, §7.4, MASK-07, MASK-08).
 *
 * <p>Named reasons rather than a bare empty {@code Optional}, because "block that conversion with an
 * explanatory message instead of losing it silently" is the spec's own wording and a boolean explains
 * nothing. Only {@link #UNSUPPORTED_DATA} ever reaches a player's screen: the other refusals are
 * enforced by the recipe never matching in the first place, so the style is simply not offered rather
 * than offered and then denied.
 */
public enum MaskRestyleRejection {

    /** Nothing is wrong: this conversion is supported. */
    NONE,
    /** The material is not a mask at all. */
    NOT_A_MASK,
    /**
     * The material is tagged as a mask but is not one of this mod's registered styles.
     *
     * <p>The tag says "this hides a face". It does not say "this mod may rewrite your item", which is
     * why a third-party helmet a pack added to {@code mcacrime:masks/clay} is concealment-eligible and
     * conversion-ineligible at the same time (§7.4).
     */
    NOT_A_REGISTERED_STYLE,
    /** Source and target are different material families, and therefore different wear budgets. */
    WRONG_FAMILY,
    /** The selected style and colour are the ones the mask already has. */
    NO_CHANGE,
    /** The source carries data this mod cannot move onto another item without losing or forging it. */
    UNSUPPORTED_DATA;

    /** The suffix of {@code mcacrime.mask.restyle.*}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The message shown when this refusal is one a player can see. */
    public String labelKey() {
        return "mcacrime.mask.restyle." + key();
    }

    public boolean allowed() {
        return this == NONE;
    }
}

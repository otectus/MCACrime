package dev.otectus.mcacrime.recipe;

import java.util.Locale;
import java.util.Optional;

/**
 * What a {@code mcacrime:mask_making} recipe does with its material slot (0.7.2 §7.3, §7.4).
 *
 * <p>Two operations and no third: {@code craft} turns raw material into a mask, {@code restyle} turns
 * one mask into another. The distinction is not cosmetic — a restyle consumes an item that may carry a
 * player's damage, name and enchantments, so it is the only operation allowed down the protected
 * transfer path in {@code mask/MaskCustomization}. An unrecognised string is rejected at load rather
 * than defaulted to {@link #CRAFT}, because defaulting would silently turn a typo into a recipe that
 * eats a worn mask and hands back a new one (invariant 8).
 */
public enum MaskOperation {

    CRAFT,
    RESTYLE;

    /** The JSON spelling: lower case, no namespace. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Empty when {@code raw} is not one of the two supported operations. */
    public static Optional<MaskOperation> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        for (MaskOperation operation : values()) {
            if (operation.key().equals(raw)) {
                return Optional.of(operation);
            }
        }
        return Optional.empty();
    }
}

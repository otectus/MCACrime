package dev.otectus.mcacrime.enchantment;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.enchantment.CrimeEnchantKind.Carrier;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which enchantment may go on which item, at what level (0.7.5 §3.10, M6.1).
 *
 * <p>Pure, with no registry, no item and no config object in any signature that decides anything —
 * the config is read only by the convenience overloads at the bottom. That is what lets the whole
 * table be asserted in a unit test, which matters more here than usual: upstream's enchantments
 * are thin {@code Enchantment} subclasses whose applicability is whatever their category happens to
 * accept, and "it compiled" is not evidence that one cannot be put on the wrong item.
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li>The five restraint enchantments go on restraints and on books, and on nothing else.</li>
 *   <li>An enchantment an operator has switched off is applicable nowhere, including on a book.</li>
 *   <li>Levels are clamped to the configured maximum in both directions: a command- or datapack-set
 *       level of 40 is read as the maximum, never obeyed. Upstream has no clamp at all, which is how
 *       its Imbue transfer fraction leaves {@code 0.8} behind.</li>
 * </ul>
 */
public final class EnchantmentApplicability {

    /** The documented default maximum level for every one of the six. */
    public static final int DEFAULT_MAX_LEVEL = 1;

    /** The ceiling an operator may configure. Five matches vanilla's highest ordinary level. */
    public static final int LEVEL_CEILING = 5;

    private EnchantmentApplicability() {
    }

    /**
     * Whether {@code kind} may be applied to a {@code carrier}, given the allowed set.
     *
     * @param allowed the enchantments the operator permits; null means all six
     */
    public static boolean appliesTo(@Nullable CrimeEnchantKind kind, @Nullable Carrier carrier,
                                    @Nullable Set<CrimeEnchantKind> allowed) {
        if (kind == null || carrier == null || !allowedBy(kind, allowed)) {
            return false;
        }
        if (carrier == Carrier.BOOK) {
            return true;
        }
        return carrier == kind.carrier();
    }

    /** Whether an operator has left {@code kind} switched on. */
    public static boolean allowedBy(@Nullable CrimeEnchantKind kind,
                                    @Nullable Set<CrimeEnchantKind> allowed) {
        return kind != null && (allowed == null || allowed.contains(kind));
    }

    /**
     * Whether two of ours may sit on the same item.
     *
     * <p>Only the carrier decides it. Nothing in the set contradicts anything else in it — Famine and
     * Shroud do different things to the same prisoner — and the one thing refused is the same
     * enchantment twice, which is one enchantment.
     */
    public static boolean compatible(@Nullable CrimeEnchantKind a, @Nullable CrimeEnchantKind b) {
        if (a == null || b == null) {
            return true;
        }
        if (a == b) {
            return false; // the same enchantment twice is one enchantment
        }
        return a.carrier() == b.carrier();
    }

    /**
     * The level actually used, clamped into {@code [0, max]}.
     *
     * <p>Zero means "not enchanted", so a negative or absent level is zero rather than one, and a
     * level above the configured maximum is the maximum. Every consumer of a level in this package
     * goes through here, which is the fix for the unbounded Imbue percentage.
     */
    public static int clampLevel(int requested, int configuredMax) {
        int max = Math.max(0, Math.min(LEVEL_CEILING, configuredMax));
        if (requested <= 0) {
            return 0;
        }
        return Math.min(requested, max);
    }

    // --- the config-reading convenience layer -------------------------------------------------------

    /**
     * The allowed set from {@code enchantments.allowed}, or all six when the config is not loaded.
     *
     * <p>Never throws: this is read from an item tooltip and from an enchanting table, both of which
     * run before a unit test has a config and neither of which may take the game down.
     */
    public static Set<CrimeEnchantKind> allowedFromConfig() {
        List<? extends String> names;
        try {
            names = McaCrimeConfig.COMMON.allowedEnchantments.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return EnumSet.allOf(CrimeEnchantKind.class);
        }
        return parseAllowed(names);
    }

    /** The pure half of {@link #allowedFromConfig()}: names in, kinds out, unknown names dropped. */
    public static Set<CrimeEnchantKind> parseAllowed(@Nullable List<? extends String> names) {
        if (names == null) {
            return EnumSet.allOf(CrimeEnchantKind.class);
        }
        Set<CrimeEnchantKind> allowed = EnumSet.noneOf(CrimeEnchantKind.class);
        for (String name : names) {
            if (name == null) {
                continue;
            }
            CrimeEnchantKind.parse(name.trim().toLowerCase(Locale.ROOT)).ifPresent(allowed::add);
        }
        return allowed;
    }

    /** The configured maximum level for one enchantment, defaulting to the documented 1. */
    public static int maxLevel(@Nullable CrimeEnchantKind kind) {
        if (kind == null) {
            return 0;
        }
        try {
            McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
            return switch (kind) {
                case IMBUE -> c.enchantMaxLevelImbue.get();
                case FAMINE -> c.enchantMaxLevelFamine.get();
                case SHROUD -> c.enchantMaxLevelShroud.get();
                case EXHAUST -> c.enchantMaxLevelExhaust.get();
                case SILENCE -> c.enchantMaxLevelSilence.get();
            };
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return DEFAULT_MAX_LEVEL;
        }
    }

    /** {@link #appliesTo} against the live config. */
    public static boolean appliesTo(@Nullable CrimeEnchantKind kind, @Nullable Carrier carrier) {
        return appliesTo(kind, carrier, allowedFromConfig());
    }

    /** {@link #clampLevel} against the live config. */
    public static int effectiveLevel(@Nullable CrimeEnchantKind kind, int requested) {
        if (kind == null || !allowedBy(kind, allowedFromConfig())) {
            return 0;
        }
        return clampLevel(requested, maxLevel(kind));
    }
}

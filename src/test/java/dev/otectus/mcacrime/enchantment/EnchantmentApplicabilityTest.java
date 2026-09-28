package dev.otectus.mcacrime.enchantment;

import dev.otectus.mcacrime.enchantment.CrimeEnchantKind.Carrier;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which of the five goes on what, and at what level (0.7.5 M6.1).
 *
 * <p>Registry-free by construction: {@link EnchantmentApplicability} is the authority and
 * {@code CrimeEnchantments} only delegates to it, so the whole table is assertable without booting
 * Minecraft. That matters because "the category happened to accept it" is exactly how upstream's
 * thin {@code Enchantment} subclasses decide applicability, and a category is not a rule anybody
 * can read.
 */
class EnchantmentApplicabilityTest {

    private static final Set<CrimeEnchantKind> ALL = EnumSet.allOf(CrimeEnchantKind.class);

    @Test
    void theFiveRestraintEnchantmentsGoOnRestraintsAndNothingElse() {
        assertEquals(5, ALL.size(), "Imbue, Famine, Shroud, Exhaust and Silence");
        for (CrimeEnchantKind kind : ALL) {
            assertEquals(Carrier.RESTRAINT, kind.carrier(), kind + " is a restraint enchantment");
            assertTrue(EnchantmentApplicability.appliesTo(kind, Carrier.RESTRAINT, ALL),
                    kind + " belongs on a restraint");
            assertFalse(EnchantmentApplicability.appliesTo(kind, Carrier.OTHER, ALL),
                    kind + " must not go on an ordinary item");
        }
    }

    @Test
    void everyEnchantmentIsAllowedOnABook() {
        for (CrimeEnchantKind kind : ALL) {
            assertTrue(EnchantmentApplicability.appliesTo(kind, Carrier.BOOK, ALL),
                    kind + " should be allowed on a book");
        }
    }

    @Test
    void anEnchantmentAnOperatorSwitchedOffIsApplicableNowhere() {
        Set<CrimeEnchantKind> allowed = EnumSet.complementOf(EnumSet.of(CrimeEnchantKind.SHROUD));
        for (Carrier carrier : Carrier.values()) {
            assertFalse(EnchantmentApplicability.appliesTo(CrimeEnchantKind.SHROUD, carrier, allowed),
                    "a disabled enchantment must not be applicable to " + carrier);
        }
        assertTrue(EnchantmentApplicability.appliesTo(CrimeEnchantKind.FAMINE, Carrier.RESTRAINT, allowed),
                "disabling one must not disable the others");
    }

    @Test
    void twoDifferentRestraintEnchantmentsAreCompatibleAndTheSameOneTwiceIsNot() {
        assertTrue(EnchantmentApplicability.compatible(CrimeEnchantKind.FAMINE, CrimeEnchantKind.SHROUD));
        assertTrue(EnchantmentApplicability.compatible(CrimeEnchantKind.IMBUE, CrimeEnchantKind.SILENCE));
        assertFalse(EnchantmentApplicability.compatible(CrimeEnchantKind.IMBUE, CrimeEnchantKind.IMBUE),
                "the same enchantment twice is one enchantment");
    }

    @Test
    void aLevelAboveTheConfiguredMaximumIsReadAsTheMaximum() {
        assertEquals(1, EnchantmentApplicability.clampLevel(40, 1));
        assertEquals(3, EnchantmentApplicability.clampLevel(40, 3));
        assertEquals(2, EnchantmentApplicability.clampLevel(2, 3));
        assertEquals(0, EnchantmentApplicability.clampLevel(0, 3));
        assertEquals(0, EnchantmentApplicability.clampLevel(-5, 3));
    }

    @Test
    void theLevelCeilingIsNeverExceededHoweverTheConfigIsSet() {
        assertEquals(EnchantmentApplicability.LEVEL_CEILING,
                EnchantmentApplicability.clampLevel(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(0, EnchantmentApplicability.clampLevel(3, 0),
                "a maximum of zero means the enchantment does nothing at all");
    }

    @Test
    void theAllowedListParsesIdsNamesAndNothingElse() {
        Set<CrimeEnchantKind> parsed = EnchantmentApplicability.parseAllowed(
                List.of("imbue", "MCACRIME:FAMINE", "Shroud", "not_an_enchantment", "buoyant", ""));
        assertEquals(EnumSet.of(CrimeEnchantKind.IMBUE, CrimeEnchantKind.FAMINE, CrimeEnchantKind.SHROUD),
                parsed, "a retired name parses to nothing rather than to a neighbour");
    }

    @Test
    void anAbsentAllowedListMeansEveryFive() {
        assertEquals(ALL, EnchantmentApplicability.parseAllowed(null));
    }

    @Test
    void everyKindHasItsOwnStableIdAndDescriptionKey() {
        for (CrimeEnchantKind kind : ALL) {
            assertEquals("mcacrime", kind.id().getNamespace());
            assertEquals(kind.path(), kind.id().getPath());
            assertEquals("enchantment.mcacrime." + kind.path(), kind.descriptionId());
            assertEquals(kind, CrimeEnchantKind.byId(kind.id()).orElseThrow());
        }
    }
}

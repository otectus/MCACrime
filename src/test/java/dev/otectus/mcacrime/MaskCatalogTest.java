package dev.otectus.mcacrime;

import dev.otectus.mcacrime.item.MaskFamily;
import dev.otectus.mcacrime.item.MaskVariant;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launch collection, as arithmetic: sixteen styles, four families of four, and no stat ladder
 * hiding inside them (0.7.2 §4.1, §4.2, MASK-01).
 *
 * <p>No registry is bootstrapped here on purpose. The catalogue is the part that has to be checkable
 * on every commit, and a check that needs a running game is a check nobody runs.
 */
class MaskCatalogTest {

    @Test
    void sixteenStylesWithUniqueIds() {
        assertEquals(16, MaskVariant.values().length);
        Set<String> ids = new HashSet<>();
        for (MaskVariant variant : MaskVariant.values()) {
            assertTrue(ids.add(variant.styleId()), variant.styleId() + " is registered twice");
        }
        assertEquals(16, ids.size());
    }

    @Test
    void fourStylesPerFamilyInARowOfFour() {
        for (MaskFamily family : MaskFamily.values()) {
            List<MaskVariant> styles = MaskVariant.of(family);
            assertEquals(4, styles.size(), family + " should offer exactly four styles");
            for (int i = 0; i < styles.size(); i++) {
                assertEquals(i, styles.get(i).sortOrder(),
                        family + " sort orders must be 0..3 with no gaps or ties");
            }
        }
    }

    /** §4.2: material buys appearance and wear. It does not buy a better mask. */
    @Test
    void everyStyleInAFamilyWearsTheSame() {
        for (MaskFamily family : MaskFamily.values()) {
            for (MaskVariant variant : MaskVariant.of(family)) {
                assertEquals(family.durability(), variant.durability(),
                        variant + " must share its family's wear budget");
                assertSame(family, variant.family());
                assertTrue(variant.tintable(), variant + " must take dye like every other style");
            }
        }
    }

    /**
     * The two shipped 0.7.0 masks keep their ids and their numbers.
     *
     * <p>This is the compatibility assertion, not a style preference: a saved stack, a crafting-table
     * recipe and any datapack that named {@code mcacrime:clay_mask} must all still resolve, and a clay
     * mask that silently became a 256-use item would be a balance change nobody asked for.
     */
    @Test
    void theShippedMasksAreUnchanged() {
        assertEquals("mcacrime:clay_mask", MaskVariant.CLAY.styleId());
        assertEquals(64, MaskVariant.CLAY.durability());
        assertSame(MaskFamily.CLAY, MaskVariant.CLAY.family());
        assertEquals("mcacrime:leather_mask", MaskVariant.LEATHER.styleId());
        assertEquals(192, MaskVariant.LEATHER.durability());
        assertSame(MaskFamily.LEATHER, MaskVariant.LEATHER.family());
    }

    @Test
    void familyWearBudgetsAreTheDocumentedOnes() {
        assertEquals(48, MaskFamily.CLOTH.durability());
        assertEquals(192, MaskFamily.LEATHER.durability());
        assertEquals(64, MaskFamily.CLAY.durability());
        assertEquals(256, MaskFamily.METAL.durability());
    }

    /**
     * §5.1: persisted style identifiers are namespaced ids, never ordinals.
     *
     * <p>Written as a lookup test rather than a comment, because the failure mode of an ordinal is
     * silent: it only shows up as somebody else's mask after a style is inserted in the middle.
     */
    @Test
    void stylesAreFoundByStableIdAndNeverByPosition() {
        for (MaskVariant variant : MaskVariant.values()) {
            assertTrue(variant.styleId().startsWith("mcacrime:"), variant + " must be namespaced");
            assertEquals("mcacrime:" + variant.textureName(), variant.styleId());
            assertSame(variant, MaskVariant.byStyleId(variant.styleId()).orElseThrow());
            assertEquals("item.mcacrime." + variant.textureName(), variant.displayKey());
        }
        assertTrue(MaskVariant.byStyleId("mcacrime:not_a_mask").isEmpty());
        assertTrue(MaskVariant.byStyleId("clay_mask").isEmpty(), "an unnamespaced id is not a style id");
        assertTrue(MaskVariant.byStyleId(null).isEmpty());
    }

    @Test
    void familiesNameTheirOwnGroupsTagsAndLabels() {
        for (MaskFamily family : MaskFamily.values()) {
            assertEquals("mcacrime:" + family.key() + "_masks", family.recipeGroup());
            assertEquals("mcacrime.mask.family." + family.key(), family.displayKey());
            assertSame(family, MaskFamily.parse(family.key()).orElseThrow());
        }
        assertFalse(MaskFamily.parse("gold").isPresent());
        assertFalse(MaskFamily.parse(null).isPresent());
    }
}

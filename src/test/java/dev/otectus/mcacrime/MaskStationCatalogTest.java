package dev.otectus.mcacrime;

import dev.otectus.mcacrime.recipe.MaskStationCatalog;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The style catalogue: one fixed order, and a selection that expires when the recipes do.
 *
 * <p>Both halves matter for the same reason. A grid that reorders itself makes a player click the
 * wrong face; a selection that outlives a reload lets them take a preview the server no longer backs
 * (CRAFT-09).
 */
class MaskStationCatalogTest {

    private static MaskStationCatalog.Entry entry(String id, String group, int sort) {
        return new MaskStationCatalog.Entry(ResourceLocation.fromNamespaceAndPath("mcacrime", id), group, sort);
    }

    private static List<String> ids(MaskStationCatalog catalog) {
        return catalog.entries().stream().map(e -> e.id().getPath()).toList();
    }

    @Test
    void orderIsGroupThenSortOrderThenId() {
        MaskStationCatalog catalog = MaskStationCatalog.of(4, List.of(
                entry("z_clay", "clay", 1),
                entry("a_leather", "leather", 0),
                entry("a_clay", "clay", 1),
                entry("first_clay", "clay", 0)));
        assertEquals(List.of("first_clay", "a_clay", "z_clay", "a_leather"), ids(catalog));
    }

    @Test
    void theSameInputsAlwaysProduceTheSameOrder() {
        List<MaskStationCatalog.Entry> entries = List.of(
                entry("b", "clay", 0), entry("a", "clay", 0), entry("c", "clay", 0));
        List<MaskStationCatalog.Entry> shuffled = new java.util.ArrayList<>(entries);
        java.util.Collections.reverse(shuffled);
        assertEquals(ids(MaskStationCatalog.of(1, entries)), ids(MaskStationCatalog.of(1, shuffled)));
    }

    @Test
    void anAbsentGroupSortsFirstWithoutThrowing() {
        MaskStationCatalog catalog = MaskStationCatalog.of(1, List.of(
                entry("named", "clay", 0),
                new MaskStationCatalog.Entry(ResourceLocation.fromNamespaceAndPath("mcacrime", "unnamed"), null, 0)));
        assertEquals(List.of("unnamed", "named"), ids(catalog));
    }

    @Test
    void aSelectionSurvivesWhileTheGenerationAndTheRecipeDo() {
        MaskStationCatalog catalog = MaskStationCatalog.of(7, List.of(entry("hockey", "clay", 0)));
        ResourceLocation hockey = ResourceLocation.fromNamespaceAndPath("mcacrime", "hockey");
        assertEquals(hockey, catalog.revalidate(hockey, 7).orElse(null));
        assertTrue(catalog.contains(hockey));
    }

    @Test
    void aReloadInvalidatesEverySelectionIncludingASameIdReplacement() {
        ResourceLocation hockey = ResourceLocation.fromNamespaceAndPath("mcacrime", "hockey");
        // Same id, same group, same position — and a new generation, because the file behind it may
        // have been rewritten completely.
        MaskStationCatalog reloaded = MaskStationCatalog.of(8, List.of(entry("hockey", "clay", 0)));
        assertTrue(reloaded.revalidate(hockey, 7).isEmpty(), "a stale generation clears the selection");
        assertEquals(hockey, reloaded.revalidate(hockey, 8).orElse(null));
    }

    @Test
    void aDeletedRecipeClearsThePreviewImmediately() {
        MaskStationCatalog after = MaskStationCatalog.of(9, List.of(entry("plague", "clay", 0)));
        assertTrue(after.revalidate(ResourceLocation.fromNamespaceAndPath("mcacrime", "hockey"), 9).isEmpty());
        assertFalse(after.contains(ResourceLocation.fromNamespaceAndPath("mcacrime", "hockey")));
    }

    @Test
    void nothingSelectedIsNotAnError() {
        MaskStationCatalog catalog = MaskStationCatalog.of(3, List.of(entry("hockey", "clay", 0)));
        assertTrue(catalog.revalidate(null, 3).isEmpty());
    }

    @Test
    void theEmptyCatalogueCanNeverBeMistakenForALiveOne() {
        assertTrue(MaskStationCatalog.EMPTY.isEmpty());
        assertEquals(0, MaskStationCatalog.EMPTY.generation());
        assertTrue(MaskStationCatalog.EMPTY
                .revalidate(ResourceLocation.fromNamespaceAndPath("mcacrime", "hockey"), 0).isEmpty());
    }
}

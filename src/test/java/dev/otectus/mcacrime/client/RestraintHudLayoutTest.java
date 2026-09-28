package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.client.hud.RestraintHudSection;
import dev.otectus.mcacrime.restraint.PhysicalRestraintView;
import dev.otectus.mcacrime.restraint.RestraintDefinitions;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The restraint panel's contents and its height (0.7.5 M2.10).
 *
 * <p>Layout rather than pixels: the assertions worth making are that the panel is empty when nothing
 * is worn — a permanent badge saying "not restrained" is exactly the HUD noise this mod avoids — that
 * it grows by one row per occupied slot and by nothing else, and that a slot naming a definition this
 * build does not have still produces a line.
 *
 * <p>That last one is the interesting case. Dropping an unknown definition would leave a player with
 * something on their arms that the panel says is not there, which is worse than an unhelpful label.
 */
class RestraintHudLayoutTest {

    private static final UUID SUBJECT = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        ClientPhysicalRestraintData.clear();
    }

    private static void wearing(Map<RestraintSlot, ResourceLocation> slots) {
        Map<RestraintSlot, PhysicalRestraintView.SlotView> views = new java.util.EnumMap<>(RestraintSlot.class);
        slots.forEach((slot, definition) ->
                views.put(slot, new PhysicalRestraintView.SlotView(definition, 0.5F, false)));
        ClientPhysicalRestraintData.accept(new PhysicalRestraintView(SUBJECT, 1L, 1L, views,
                PhysicalRestraintView.NO_HOLDER, false));
    }

    @Test
    void nothingWornDrawsNothing() {
        assertTrue(RestraintHudSection.rows(SUBJECT).isEmpty());
        assertEquals(0, RestraintHudSection.height(0));
    }

    @Test
    void onePanelRowPerOccupiedSlot() {
        wearing(Map.of(RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS));
        assertEquals(1, RestraintHudSection.rows(SUBJECT).size());

        wearing(Map.of(
                RestraintSlot.HEAD, RestraintDefinitions.BUNDLE,
                RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS,
                RestraintSlot.LEGS, RestraintDefinitions.SHACKLES_LEGS));
        List<RestraintHudSection.Row> rows = RestraintHudSection.rows(SUBJECT);
        assertEquals(3, rows.size());
        // Slot order, so the panel does not reshuffle itself between frames.
        assertEquals(RestraintDefinitions.BUNDLE.getPath(), pathOf(rows.get(0)));
        assertEquals(RestraintDefinitions.HANDCUFFS_ARMS.getPath(), pathOf(rows.get(1)));
        assertEquals(RestraintDefinitions.SHACKLES_LEGS.getPath(), pathOf(rows.get(2)));
    }

    @Test
    void theHeightGrowsOnlyWithTheRows() {
        int one = RestraintHudSection.height(1);
        int two = RestraintHudSection.height(2);
        int three = RestraintHudSection.height(3);
        assertTrue(one > 0);
        assertEquals(two - one, three - two, "each row costs the same height");
    }

    @Test
    void anUnknownDefinitionStillGetsALine() {
        wearing(Map.of(RestraintSlot.ARMS, new ResourceLocation("someothermod", "zip_ties")));
        assertEquals(1, RestraintHudSection.rows(SUBJECT).size());
    }

    @Test
    void theDurabilityFractionIsBounded() {
        wearing(Map.of(RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS));
        float fraction = RestraintHudSection.rows(SUBJECT).get(0).fraction();
        assertTrue(fraction >= 0.0F && fraction <= 1.0F);
    }

    /** The translation key a row carries, reduced to the definition path it names. */
    private static String pathOf(RestraintHudSection.Row row) {
        String key = ((net.minecraft.network.chat.contents.TranslatableContents)
                row.label().getContents()).getKey();
        String prefix = "mcacrime.restraint.";
        return key.startsWith(prefix) ? key.substring(prefix.length(), key.length() - ".name".length())
                : key;
    }
}

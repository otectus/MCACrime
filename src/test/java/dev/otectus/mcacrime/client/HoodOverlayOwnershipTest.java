package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.restraint.PhysicalRestraintView;
import dev.otectus.mcacrime.restraint.RestraintDefinitions;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hood overlay belongs to exactly one player (0.7.5 M2.10).
 *
 * <p>The failure this guards is a whole room going dark because one person was hooded. The overlay
 * is drawn from a shared client cache keyed by subject, and the cache holds every subject the client
 * can see — so "is anybody hooded" and "am I hooded" are one character apart in the code and a world
 * apart in effect.
 *
 * <p>The converse is asserted too: an observer must still be able to <em>see</em> the hood, which is
 * the worn model's job and not this one. So a hooded subject who is not the local player yields no
 * overlay while still being recorded as hooded in the cache.
 */
class HoodOverlayOwnershipTest {

    private static final UUID ME = UUID.randomUUID();
    private static final UUID SOMEBODY_ELSE = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        ClientPhysicalRestraintData.clear();
    }

    private static void wearing(UUID subject, RestraintSlot slot, ResourceLocation definition) {
        Map<RestraintSlot, PhysicalRestraintView.SlotView> views = new EnumMap<>(RestraintSlot.class);
        views.put(slot, new PhysicalRestraintView.SlotView(definition, 1.0F, false));
        ClientPhysicalRestraintData.accept(new PhysicalRestraintView(subject, 1L, 1L, views,
                PhysicalRestraintView.NO_HOLDER, false));
    }

    @Test
    void anotherPlayersHoodDoesNotBlindMe() {
        wearing(SOMEBODY_ELSE, RestraintSlot.HEAD, RestraintDefinitions.BUNDLE);
        assertTrue(HoodOverlayHandler.hooded(SOMEBODY_ELSE), "they are hooded, and are drawn one");
        assertFalse(HoodOverlayHandler.hooded(ME), "and I am not");
    }

    @Test
    void myOwnHoodDoes() {
        wearing(ME, RestraintSlot.HEAD, RestraintDefinitions.BUNDLE);
        assertTrue(HoodOverlayHandler.hooded(ME));
    }

    @Test
    void onlyDefinitionsThatAskForAnOverlayGetOne() {
        // Head tape covers the mouth, not the eyes. Blacking out the screen for it would be a
        // different mechanic than the one the matrix describes.
        wearing(ME, RestraintSlot.HEAD, RestraintDefinitions.DUCK_TAPE_HEAD);
        assertFalse(HoodOverlayHandler.hooded(ME));
    }

    @Test
    void gearOnOtherSlotsIsNotAHood() {
        wearing(ME, RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS);
        assertFalse(HoodOverlayHandler.hooded(ME));
    }

    @Test
    void anUnknownSubjectIsNotHooded() {
        assertFalse(HoodOverlayHandler.hooded(UUID.randomUUID()));
        assertFalse(HoodOverlayHandler.hooded(null));
    }
}

package dev.otectus.mcacrime;

import dev.otectus.mcacrime.jail.JailContainmentMode;
import dev.otectus.mcacrime.jail.JailState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The Heat a sentence answers for survives a restart, shrinks with Heat that leaves while it runs, and is
 * unknown -- not zero -- on a sentence saved before 0.7.7.
 */
class JailStateSentenceHeatTest {

    private static JailState jail() {
        return new JailState(1200L, new BlockPos(1, 64, 1), ResourceLocation.withDefaultNamespace("overworld"), 4,
                JailContainmentMode.CONTAINMENT);
    }

    @Test
    void theObligationRoundTripsThroughASave() {
        JailState jail = jail();
        jail.setSentenceHeat(75L);
        JailState loaded = JailState.load(jail.save());
        assertEquals(75L, loaded.getSentenceHeat());
        assertEquals(75L, jail.copy().getSentenceHeat());
    }

    @Test
    void aSentenceSavedBeforeTheObligationExistedReadsUnknown() {
        CompoundTag tag = jail().save();
        tag.remove("sentenceHeat");
        assertEquals(JailState.UNKNOWN_HEAT, JailState.load(tag).getSentenceHeat());
        assertFalse(jail().save().contains("sentenceHeat"), "an unknown obligation writes nothing");
    }

    @Test
    void heatThatLeavesComesOffTheObligationFirstAndNeverBelowZero() {
        JailState jail = jail();
        jail.setSentenceHeat(50L);
        jail.consumeSentenceHeat(20L);
        assertEquals(30L, jail.getSentenceHeat());
        jail.consumeSentenceHeat(100L);
        assertEquals(0L, jail.getSentenceHeat());
        JailState unknown = jail();
        unknown.consumeSentenceHeat(10L);
        assertEquals(JailState.UNKNOWN_HEAT, unknown.getSentenceHeat(), "an unknown obligation stays unknown");
    }
}

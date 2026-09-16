package dev.otectus.mcacrime;

import dev.otectus.mcacrime.menu.MaskSelectionOutcome;
import dev.otectus.mcacrime.menu.MaskSelectionPolicy;
import dev.otectus.mcacrime.network.MaskSelectionS2CPacket;
import dev.otectus.mcacrime.network.PacketBounds;
import dev.otectus.mcacrime.network.SelectMaskRecipeC2SPacket;
import dev.otectus.mcacrime.recipe.MaskStationCatalog;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What the server will and will not accept from a client that says "make that one" (0.7.2 §8.1).
 *
 * <p>Two halves, both required. The decoder must refuse an oversized or malformed id outright, because
 * a clamped read leaves the rest of the payload at the wrong offset; the policy must refuse a
 * well-formed request that names the wrong session, a stale generation, or a recipe the server is not
 * currently offering. Neither refusal is a partial action: nothing is selected, nothing is built,
 * nothing is consumed (CRAFT-07, CRAFT-08).
 */
class MaskStationSelectionPacketTest {

    private static final ResourceLocation HOCKEY = new ResourceLocation("mcacrime", "hockey");
    private static final ResourceLocation PLAGUE = new ResourceLocation("mcacrime", "plague");

    private static MaskStationCatalog catalog(int generation) {
        return MaskStationCatalog.of(generation,
                List.of(new MaskStationCatalog.Entry(HOCKEY, "mcacrime:clay_masks", 0)));
    }

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    // ------------------------------------------------------------------ wire

    @Test
    void anHonestRequestRoundTrips() {
        FriendlyByteBuf buf = buffer();
        SelectMaskRecipeC2SPacket.encode(new SelectMaskRecipeC2SPacket(7, HOCKEY, 12), buf);
        SelectMaskRecipeC2SPacket decoded = SelectMaskRecipeC2SPacket.decode(buf);
        assertEquals(7, decoded.containerId());
        assertEquals(HOCKEY, decoded.recipeId());
        assertEquals(12, decoded.generation());
    }

    @Test
    void anOversizedIdIsRejectedRatherThanClamped() {
        FriendlyByteBuf buf = buffer();
        buf.writeVarInt(1);
        buf.writeUtf("mcacrime:" + "x".repeat(PacketBounds.MAX_ID_LENGTH * 2), Short.MAX_VALUE);
        buf.writeVarInt(1);
        assertThrows(DecoderException.class, () -> SelectMaskRecipeC2SPacket.decode(buf));
    }

    @Test
    void aMalformedIdIsRejectedBeforeItBecomesALookupKey() {
        FriendlyByteBuf buf = buffer();
        buf.writeVarInt(1);
        buf.writeUtf("NOT A RESOURCE LOCATION", PacketBounds.MAX_ID_LENGTH);
        buf.writeVarInt(1);
        assertThrows(DecoderException.class, () -> SelectMaskRecipeC2SPacket.decode(buf));
    }

    @Test
    void theServersAnswerCarriesAClearedSelectionWithoutFakingAnId() {
        FriendlyByteBuf buf = buffer();
        MaskSelectionS2CPacket.encode(new MaskSelectionS2CPacket(3, null, 5), buf);
        MaskSelectionS2CPacket decoded = MaskSelectionS2CPacket.decode(buf);
        assertEquals(3, decoded.containerId());
        assertNull(decoded.recipeId());
        assertEquals(5, decoded.generation());

        FriendlyByteBuf second = buffer();
        MaskSelectionS2CPacket.encode(new MaskSelectionS2CPacket(3, HOCKEY, 5), second);
        assertEquals(HOCKEY, MaskSelectionS2CPacket.decode(second).recipeId());
    }

    // ------------------------------------------------------------------ policy

    @Test
    void aMatchingSessionGenerationAndIdIsAccepted() {
        assertEquals(MaskSelectionOutcome.ACCEPTED,
                MaskSelectionPolicy.evaluate(4, 4, catalog(11), HOCKEY, 11));
    }

    @Test
    void aRequestNamingAnotherMenuIsRefused() {
        assertEquals(MaskSelectionOutcome.WRONG_CONTAINER,
                MaskSelectionPolicy.evaluate(4, 5, catalog(11), HOCKEY, 11));
    }

    @Test
    void aStaleGenerationIsRefusedRatherThanReinterpreted() {
        assertEquals(MaskSelectionOutcome.STALE_GENERATION,
                MaskSelectionPolicy.evaluate(4, 4, catalog(12), HOCKEY, 11));
        assertEquals(MaskSelectionOutcome.STALE_GENERATION,
                MaskSelectionPolicy.evaluate(4, 4, catalog(12), HOCKEY, 13));
    }

    @Test
    void anIdTheServerIsNotOfferingIsRefused() {
        assertEquals(MaskSelectionOutcome.UNKNOWN_RECIPE,
                MaskSelectionPolicy.evaluate(4, 4, catalog(11), PLAGUE, 11));
        assertEquals(MaskSelectionOutcome.UNKNOWN_RECIPE,
                MaskSelectionPolicy.evaluate(4, 4, catalog(11), null, 11));
    }

    @Test
    void anEmptyCatalogueOffersNothingAtAll() {
        assertEquals(MaskSelectionOutcome.STALE_GENERATION,
                MaskSelectionPolicy.evaluate(4, 4, MaskStationCatalog.EMPTY, HOCKEY, 11));
        assertEquals(MaskSelectionOutcome.UNKNOWN_RECIPE,
                MaskSelectionPolicy.evaluate(4, 4, MaskStationCatalog.EMPTY, HOCKEY, 0));
        assertEquals(MaskSelectionOutcome.STALE_GENERATION,
                MaskSelectionPolicy.evaluate(4, 4, null, HOCKEY, 0));
    }

    @Test
    void theContainerCheckHappensBeforeAnythingIsLookedUp() {
        // A forged container id must not become a read against somebody else's catalogue.
        assertEquals(MaskSelectionOutcome.WRONG_CONTAINER,
                MaskSelectionPolicy.evaluate(4, 99, null, null, -1));
    }
}

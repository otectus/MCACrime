package dev.otectus.mcacrime;

import dev.otectus.mcacrime.menu.MaskSelectionOutcome;
import dev.otectus.mcacrime.menu.MaskSelectionPolicy;
import dev.otectus.mcacrime.network.MaskSelectionS2CPacket;
import dev.otectus.mcacrime.network.PacketBounds;
import dev.otectus.mcacrime.network.SelectMaskRecipeC2SPacket;
import dev.otectus.mcacrime.recipe.MaskStationCatalog;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
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
 *
 * <p>The 1.20.1 original drove {@code encode}/{@code decode} statics. Under the payload API the same
 * bytes are written by the payload's own {@code StreamCodec}, so that is what this exercises — the
 * wire format is unchanged, only the mechanism that produces it.
 */
class MaskStationSelectionPacketTest {

    private static final ResourceLocation HOCKEY =
            ResourceLocation.fromNamespaceAndPath("mcacrime", "hockey");
    private static final ResourceLocation PLAGUE =
            ResourceLocation.fromNamespaceAndPath("mcacrime", "plague");

    private static MaskStationCatalog catalog(int generation) {
        return MaskStationCatalog.of(generation,
                List.of(new MaskStationCatalog.Entry(HOCKEY, "mcacrime:clay_masks", 0)));
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    // ------------------------------------------------------------------ wire

    @Test
    void anHonestRequestRoundTrips() {
        RegistryFriendlyByteBuf buf = buffer();
        SelectMaskRecipeC2SPacket.STREAM_CODEC.encode(buf, new SelectMaskRecipeC2SPacket(7, HOCKEY, 12));
        SelectMaskRecipeC2SPacket decoded = SelectMaskRecipeC2SPacket.STREAM_CODEC.decode(buf);
        assertEquals(0, buf.readableBytes(), "decoder left bytes unread; the stream would desynchronise");
        assertEquals(7, decoded.containerId());
        assertEquals(HOCKEY, decoded.recipeId());
        assertEquals(12, decoded.generation());
    }

    @Test
    void anOversizedIdIsRejectedRatherThanClamped() {
        RegistryFriendlyByteBuf buf = buffer();
        buf.writeVarInt(1);
        buf.writeUtf("mcacrime:" + "x".repeat(PacketBounds.MAX_ID_LENGTH * 2), Short.MAX_VALUE);
        buf.writeVarInt(1);
        assertThrows(DecoderException.class, () -> SelectMaskRecipeC2SPacket.STREAM_CODEC.decode(buf));
    }

    @Test
    void aMalformedIdIsRejectedBeforeItBecomesALookupKey() {
        RegistryFriendlyByteBuf buf = buffer();
        buf.writeVarInt(1);
        buf.writeUtf("NOT A RESOURCE LOCATION", PacketBounds.MAX_ID_LENGTH);
        buf.writeVarInt(1);
        assertThrows(DecoderException.class, () -> SelectMaskRecipeC2SPacket.STREAM_CODEC.decode(buf));
    }

    @Test
    void theServersAnswerCarriesAClearedSelectionWithoutFakingAnId() {
        RegistryFriendlyByteBuf buf = buffer();
        MaskSelectionS2CPacket.STREAM_CODEC.encode(buf, new MaskSelectionS2CPacket(3, null, 5));
        MaskSelectionS2CPacket decoded = MaskSelectionS2CPacket.STREAM_CODEC.decode(buf);
        assertEquals(0, buf.readableBytes());
        assertEquals(3, decoded.containerId());
        assertNull(decoded.recipeId());
        assertEquals(5, decoded.generation());

        RegistryFriendlyByteBuf second = buffer();
        MaskSelectionS2CPacket.STREAM_CODEC.encode(second, new MaskSelectionS2CPacket(3, HOCKEY, 5));
        assertEquals(HOCKEY, MaskSelectionS2CPacket.STREAM_CODEC.decode(second).recipeId());
    }

    /** The server's answer travels the same bound, so a hostile server cannot widen it either. */
    @Test
    void anOversizedIdInTheServersAnswerIsRejectedToo() {
        RegistryFriendlyByteBuf buf = buffer();
        buf.writeVarInt(3);
        buf.writeBoolean(true);
        buf.writeUtf("mcacrime:" + "x".repeat(PacketBounds.MAX_ID_LENGTH * 2), Short.MAX_VALUE);
        buf.writeVarInt(5);
        assertThrows(DecoderException.class, () -> MaskSelectionS2CPacket.STREAM_CODEC.decode(buf));
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

package dev.otectus.mcacrime;

import dev.otectus.mcacrime.action.ActionResult;
import dev.otectus.mcacrime.network.ActionProgressS2CPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The outcome line, from the result an action returns to the packet that carries it.
 *
 * <p>Worth asserting because the failure is silent: a key with a {@code %s} and no arguments is a
 * perfectly valid translatable, and it reaches the HUD reading "You rob the villager of %s emeralds."
 * Nothing throws, so only the argument itself proves the fix.
 */
class ActionOutcomeMessageTest {

    private static final UUID SESSION = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Test
    void anAcceptedResultCarriesItsArgumentsIntoTheMessage() {
        ActionResult result = ActionResult.accepted("mcacrime.mug.success", 5);
        assertEquals("mcacrime.mug.success", result.code());
        TranslatableContents contents =
                assertInstanceOf(TranslatableContents.class, result.message().getContents());
        assertEquals("mcacrime.mug.success", contents.getKey());
        assertArrayEquals(new Object[]{5}, contents.getArgs());
    }

    @Test
    void aResultWithNoArgumentsStillProducesItsKey() {
        TranslatableContents contents = assertInstanceOf(TranslatableContents.class,
                ActionResult.accepted("mcacrime.mug.empty").message().getContents());
        assertEquals("mcacrime.mug.empty", contents.getKey());
        assertArrayEquals(new Object[0], contents.getArgs());
    }

    @Test
    void theOutcomeTextSurvivesTheWire() {
        // A String argument, not an int: the component serializer writes every non-Component argument
        // as a string, so an int would come back as "5" and the round trip would be a false failure.
        Component text = Component.translatable("mcacrime.mug.success", "5");
        ActionProgressS2CPacket sent = ActionProgressS2CPacket.ended(SESSION, "gui.mcacrime.actions",
                ActionProgressS2CPacket.Phase.FINISHED, "mcacrime.mug.success", text);
        assertEquals(sent, roundTrip(sent));
    }

    @Test
    void anEmptyOutcomeTextRoundTripsToo() {
        ActionProgressS2CPacket sent = ActionProgressS2CPacket.started(SESSION, "gui.mcacrime.actions", 40);
        assertEquals(sent, roundTrip(sent));
    }

    private static ActionProgressS2CPacket roundTrip(ActionProgressS2CPacket sent) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ActionProgressS2CPacket.encode(sent, buf);
        ActionProgressS2CPacket back = ActionProgressS2CPacket.decode(buf);
        assertEquals(0, buf.readableBytes(), "the decoder must consume exactly what the encoder wrote");
        return back;
    }
}

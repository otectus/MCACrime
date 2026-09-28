package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.restraint.EscapeService;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import dev.otectus.mcacrime.restraint.StruggleInput;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * One struggle input, as intent only (§1.6, §3.5).
 *
 * <p>Four bounded numbers and nothing else. There is deliberately no actor field — identity comes
 * from the connection through {@link ServerPacketGuard} — no durability delta, no outcome and no
 * target: a subject can only ever struggle against their own restraints, so the subject <em>is</em>
 * the sender. The source's equivalent carries a client-decided durability value and a client-named
 * victim, which is why a modified client there can free itself instantly or free somebody else.
 *
 * <p>{@code sessionId} may be 0, meaning "open one": the first input is the begin, so there is no
 * separate handshake packet whose loss would leave a client unable to struggle at all.
 *
 * @param sessionId the struggle session this input belongs to, or 0 to start one
 * @param slot      which worn restraint is being strained
 * @param inputKind the {@link StruggleInput} ordinal, validated on arrival
 * @param inputSeq  a monotonically increasing sequence, so a replayed packet cannot count twice
 */
public record RestraintStruggleC2SPacket(long sessionId, RestraintSlot slot, int inputKind, int inputSeq) {

    public RestraintStruggleC2SPacket {
        inputKind = Math.max(0, inputKind);
        inputSeq = Math.max(0, inputSeq);
    }

    public static void encode(RestraintStruggleC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.sessionId);
        PacketBounds.writeEnumOrdinal(buf, msg.slot);
        buf.writeByte(msg.inputKind);
        buf.writeVarInt(msg.inputSeq);
    }

    /**
     * Reads one input, refusing anything outside its declared range.
     *
     * <p>A slot byte outside the enum is a <b>decode failure</b>, not an arms restraint: substituting
     * a default would let a malformed or forged packet pick a slot the player never chose, and the
     * server would then do real work — spend durability, roll a break — against it. The 1.21.1 port's
     * {@code CrimeStreamCodecs.enumCodec} refuses the same way, so the two lines share one rule.
     */
    public static RestraintStruggleC2SPacket decode(FriendlyByteBuf buf) {
        long sessionId = buf.readLong();
        RestraintSlot slot = PacketBounds.readEnumOrdinal(buf, RestraintSlot.class)
                .orElseThrow(() -> new io.netty.handler.codec.DecoderException("Unknown restraint slot"));
        int inputKind = buf.readByte();
        int inputSeq = buf.readVarInt();
        return new RestraintStruggleC2SPacket(sessionId, slot, inputKind, inputSeq);
    }

    public static void handle(RestraintStruggleC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPacketGuard.accept(ctx, RequestBudget.Category.ACTION, sender -> {
            Optional<StruggleInput> input = StruggleInput.byIndex(msg.inputKind);
            if (input.isEmpty()) {
                return; // an input kind this build does not have; refused, not guessed at
            }
            EscapeService.struggle(sender, msg.sessionId, msg.slot, input.get(), msg.inputSeq);
        });
    }
}

package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.restraint.EscapeService;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import dev.otectus.mcacrime.restraint.StruggleInput;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Optional;

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
 * <p>The slot travels through {@link CrimeStreamCodecs#enumCodec}, which <b>refuses</b> an ordinal no
 * build ever wrote rather than substituting the arms. That is this line's settled policy (spec §9.6):
 * a payload decode failure is scoped to the payload here, where under Forge's channel it would have
 * dropped the connection, so the Forge baseline clamps and this refuses. No legitimate client can
 * write such a byte, and neither line lets one through to the escape service.
 *
 * @param sessionId the struggle session this input belongs to, or 0 to start one
 * @param slot      which worn restraint is being strained
 * @param inputKind the {@link StruggleInput} ordinal, validated on arrival
 * @param inputSeq  a monotonically increasing sequence, so a replayed packet cannot count twice
 */
public record RestraintStruggleC2SPacket(long sessionId, RestraintSlot slot, int inputKind, int inputSeq)
        implements CustomPacketPayload {

    public static final Type<RestraintStruggleC2SPacket> TYPE =
            new Type<>(McaCrime.id("restraint_struggle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RestraintStruggleC2SPacket> STREAM_CODEC =
            StreamCodec.composite(
                    CrimeStreamCodecs.LONG, RestraintStruggleC2SPacket::sessionId,
                    CrimeStreamCodecs.enumCodec(RestraintSlot.class, "restraint slot"),
                    RestraintStruggleC2SPacket::slot,
                    ByteBufCodecs.VAR_INT, RestraintStruggleC2SPacket::inputKind,
                    ByteBufCodecs.VAR_INT, RestraintStruggleC2SPacket::inputSeq,
                    RestraintStruggleC2SPacket::new);

    public RestraintStruggleC2SPacket {
        slot = slot == null ? RestraintSlot.ARMS : slot;
        inputKind = Math.max(0, inputKind);
        inputSeq = Math.max(0, inputSeq);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RestraintStruggleC2SPacket msg, IPayloadContext ctx) {
        ServerPacketGuard.accept(ctx, RequestBudget.Category.ACTION, sender -> {
            Optional<StruggleInput> input = StruggleInput.byIndex(msg.inputKind);
            if (input.isEmpty()) {
                return; // an input kind this build does not have; refused, not guessed at
            }
            EscapeService.struggle(sender, msg.sessionId, msg.slot, input.get(), msg.inputSeq);
        });
    }
}

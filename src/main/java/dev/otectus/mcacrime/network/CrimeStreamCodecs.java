package dev.otectus.mcacrime.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * The few codec pieces vanilla does not ship that every payload here needs.
 *
 * <p>Two of them, really: a fixed-width {@code long} (there is a {@code VAR_LONG} but no {@code LONG}
 * in {@code ByteBufCodecs}, and the status packets have always written eight bytes), and an enum
 * codec that range-checks the ordinal.
 *
 * <p>The enum codec <b>throws</b> on an out-of-range ordinal rather than substituting a fallback, as
 * spec §9.6 requires. The Forge build clamped, on the reasoning that a dropped connection costs more
 * than a cosmetic field; under the payload API the decode failure is scoped to the payload and the
 * registrar reports it, and an ordinal no build ever wrote means the peer is not the peer it claims
 * to be. Same policy as the bounded collection codecs, which throw on an over-limit count.
 */
final class CrimeStreamCodecs {

    /** Fixed-width 64-bit, matching what the karma/heat/captivity packets have always written. */
    static final StreamCodec<RegistryFriendlyByteBuf, Long> LONG =
            StreamCodec.of((buf, value) -> buf.writeLong(value), RegistryFriendlyByteBuf::readLong);

    private CrimeStreamCodecs() {
    }

    /**
     * An enum on the wire as its ordinal, refused outright when the ordinal names nothing.
     *
     * @param what a short noun for the error message, so a rejected payload says which field was wrong
     */
    static <E extends Enum<E>> StreamCodec<RegistryFriendlyByteBuf, E> enumCodec(Class<E> type, String what) {
        E[] values = type.getEnumConstants();
        return StreamCodec.of(
                (buf, value) -> buf.writeVarInt(value.ordinal()),
                buf -> {
                    int ordinal = buf.readVarInt();
                    if (ordinal < 0 || ordinal >= values.length) {
                        throw new DecoderException("mcacrime: " + what + " ordinal " + ordinal
                                + " outside [0, " + (values.length - 1) + "]");
                    }
                    return values[ordinal];
                });
    }
}

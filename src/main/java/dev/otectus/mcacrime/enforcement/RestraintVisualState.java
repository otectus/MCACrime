package dev.otectus.mcacrime.enforcement;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Everything a client needs to draw one restrained subject, and nothing else.
 *
 * <p>{@code escortEntityId} is an entity id rather than a UUID because the rope is drawn every frame
 * and an id resolves in constant time; {@code -1} means nobody is holding them, which is also what an
 * escort in another dimension collapses to. NPC captives are led by a real vanilla leash, so they
 * always carry {@code -1} and the mod draws no second rope over the top of it.
 */
public record RestraintVisualState(boolean restrained, RestraintVisualType type, int escortEntityId) {

    /**
     * The visual type on the wire as its ordinal, refused outright when the ordinal names nothing.
     *
     * <p>Spelled out here rather than borrowed from {@code CrimeStreamCodecs}, which is package-private
     * to {@code network} on purpose: this record lives in {@code enforcement} because a dedicated
     * server has to be able to name it, and reaching across for a codec helper would be the first
     * thread pulling that seam apart. Same refuse-don't-guess policy either way (spec §9.6).
     */
    private static final StreamCodec<RegistryFriendlyByteBuf, RestraintVisualType> TYPE_CODEC =
            StreamCodec.of(
                    (buf, value) -> buf.writeVarInt(value.ordinal()),
                    buf -> {
                        int ordinal = buf.readVarInt();
                        RestraintVisualType[] values = RestraintVisualType.values();
                        if (ordinal < 0 || ordinal >= values.length) {
                            throw new DecoderException("mcacrime: restraint visual type ordinal " + ordinal
                                    + " outside [0, " + (values.length - 1) + "]");
                        }
                        return values[ordinal];
                    });

    public static final StreamCodec<RegistryFriendlyByteBuf, RestraintVisualState> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, RestraintVisualState::restrained,
                    TYPE_CODEC, RestraintVisualState::type,
                    ByteBufCodecs.VAR_INT, RestraintVisualState::escortEntityId,
                    RestraintVisualState::new);

    private static final RestraintVisualState NONE =
            new RestraintVisualState(false, RestraintVisualType.NONE, -1);

    public static RestraintVisualState none() {
        return NONE;
    }
}

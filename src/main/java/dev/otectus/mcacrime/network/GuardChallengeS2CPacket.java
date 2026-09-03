package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.enforcement.GuardChallenge;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * A guard challenge opening or closing (spec §13.2).
 *
 * <p>Server-authored and display-only, like every other S2C packet here. The client is told what the
 * charges add up to and which responses to draw; it decides nothing, and a client that draws "Pay
 * fine" when the server did not offer it gains nothing, because {@code GuardChallengeService} rechecks
 * the option before spending anything.
 *
 * <p>The countdown is sent as a remaining tick count rather than an absolute deadline, so the client
 * needs no clock synchronisation to render it and cannot be confused by a differing game time.
 */
public record GuardChallengeS2CPacket(boolean open, UUID encounterId, Component guardName,
                                      Component jurisdiction, int chargeCount, long assessedFine,
                                      boolean canPay, long remainingTicks) implements CustomPacketPayload {

    public static final Type<GuardChallengeS2CPacket> TYPE = new Type<>(McaCrime.id("guard_challenge"));

    /** Eight fields, two past what {@code StreamCodec.composite} carries, so written by hand. */
    public static final StreamCodec<RegistryFriendlyByteBuf, GuardChallengeS2CPacket> STREAM_CODEC =
            StreamCodec.of(GuardChallengeS2CPacket::write, GuardChallengeS2CPacket::read);

    public GuardChallengeS2CPacket {
        encounterId = encounterId == null ? new UUID(0L, 0L) : encounterId;
        guardName = guardName == null ? Component.empty() : guardName;
        jurisdiction = jurisdiction == null ? Component.empty() : jurisdiction;
        chargeCount = Math.max(0, chargeCount);
        assessedFine = Math.max(0L, assessedFine);
        remainingTicks = Math.max(0L, remainingTicks);
    }

    /**
     * Builds the open form from a live encounter.
     *
     * <p>{@code jurisdiction} is a rendered label, not the {@code CrimeCommunityKey}. The key's own
     * string form is {@code minecraft:overworld/0} — right for NBT keys and logs, and what this packet
     * used to send, so a guard announced their authority as a dimension id and an array index. The
     * server resolves the village's name and sends that; a {@link Component} rather than a
     * {@link String} so the "no village here" case can be a translation key that localises on the
     * client like everything else.
     *
     * <p>{@code remainingTicks} is what is actually left, not the width of the whole window. Sending
     * the window meant reopening a challenge with the keybind showed a full timer no matter how long
     * the player had already spent deciding.
     */
    public static GuardChallengeS2CPacket open(GuardChallenge challenge, long now,
                                               Component guardName, Component jurisdiction) {
        return new GuardChallengeS2CPacket(true, challenge.encounterId(), guardName, jurisdiction,
                challenge.chargeCount(), challenge.assessedFine(), challenge.canPay(),
                challenge.remaining(now));
    }

    /** The close form: everything else is ignored by the client when {@code open} is false. */
    public static GuardChallengeS2CPacket closed() {
        return new GuardChallengeS2CPacket(false, null, Component.empty(), Component.empty(),
                0, 0L, false, 0L);
    }

    private static void write(RegistryFriendlyByteBuf buf, GuardChallengeS2CPacket msg) {
        buf.writeBoolean(msg.open());
        buf.writeUUID(msg.encounterId());
        ComponentSerialization.STREAM_CODEC.encode(buf, msg.guardName());
        ComponentSerialization.STREAM_CODEC.encode(buf, msg.jurisdiction());
        buf.writeVarInt(msg.chargeCount());
        buf.writeVarLong(msg.assessedFine());
        buf.writeBoolean(msg.canPay());
        buf.writeVarLong(msg.remainingTicks());
    }

    private static GuardChallengeS2CPacket read(RegistryFriendlyByteBuf buf) {
        return new GuardChallengeS2CPacket(buf.readBoolean(), buf.readUUID(),
                ComponentSerialization.STREAM_CODEC.decode(buf),
                ComponentSerialization.STREAM_CODEC.decode(buf),
                buf.readVarInt(), buf.readVarLong(), buf.readBoolean(), buf.readVarLong());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

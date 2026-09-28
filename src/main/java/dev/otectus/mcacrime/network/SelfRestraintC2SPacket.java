package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import dev.otectus.mcacrime.restraint.SelfApplicationService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * "Put what I am holding on my own {@code slot}" (§3.4 self-application, M2.6).
 *
 * <p>Two bounded values and no identity: the subject is the sender, so there is nothing here that
 * could name somebody else. The slot is an explicit selection from the self panel rather than
 * anything derived from where the player is looking — the source derives it from view pitch and
 * compares degrees against radians, so most of its regions are unreachable.
 *
 * @param slot    which region the player chose
 * @param offHand whether the off hand holds the restraint
 */
public record SelfRestraintC2SPacket(RestraintSlot slot, boolean offHand) implements CustomPacketPayload {

    public static final Type<SelfRestraintC2SPacket> TYPE = new Type<>(McaCrime.id("self_restraint"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SelfRestraintC2SPacket> STREAM_CODEC =
            StreamCodec.composite(
                    CrimeStreamCodecs.enumCodec(RestraintSlot.class, "restraint slot"),
                    SelfRestraintC2SPacket::slot,
                    ByteBufCodecs.BOOL, SelfRestraintC2SPacket::offHand,
                    SelfRestraintC2SPacket::new);

    public SelfRestraintC2SPacket {
        slot = slot == null ? RestraintSlot.ARMS : slot;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Applies it, after the two checks that are not {@code ServerPacketGuard}'s.
     *
     * <p>The guard answers "who sent this and how often"; {@link ActionValidation#actionable} answers
     * "is this player in a state where doing anything to themselves is meaningful" — alive, not
     * removed, not spectating. Everything else the application needs is re-derived server-side by
     * {@link SelfApplicationService}: the item is read from the named hand at commit time, the slot is
     * re-checked against the subject's rig, and {@code allowSelfApplication} is consulted there rather
     * than trusted from here.
     */
    public static void handle(SelfRestraintC2SPacket msg, IPayloadContext ctx) {
        ServerPacketGuard.accept(ctx, RequestBudget.Category.ACTION, sender -> {
            if (!ActionValidation.actionable(sender)) {
                return;
            }
            SelfApplicationService.apply(sender, msg.slot,
                    msg.offHand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        });
    }
}

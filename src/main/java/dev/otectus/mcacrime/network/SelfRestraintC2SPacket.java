package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.restraint.RestraintSlot;
import dev.otectus.mcacrime.restraint.SelfApplicationService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * "Put what I am holding on my own {@code slot}" (§3.4 self-application, M2.6).
 *
 * <p>Two bounded values and no identity: the subject is the sender, so there is nothing here that
 * could name somebody else. The slot is an explicit selection from the self panel rather than
 * anything derived from where the player is looking — the source derives it from view pitch and
 * compares degrees against radians, so most of its regions are unreachable.
 *
 * @param slot   which region the player chose
 * @param offHand whether the off hand holds the restraint
 */
public record SelfRestraintC2SPacket(RestraintSlot slot, boolean offHand) {

    public SelfRestraintC2SPacket {
        if (slot == null) {
            throw new IllegalArgumentException("A self-application must name a slot");
        }
    }

    public static void encode(SelfRestraintC2SPacket msg, FriendlyByteBuf buf) {
        PacketBounds.writeEnumOrdinal(buf, msg.slot);
        buf.writeBoolean(msg.offHand);
    }

    /**
     * Reads one self-application, refusing an unknown slot outright.
     *
     * <p>Refused rather than clamped to the arms: this packet <em>chooses where a restraint goes</em>,
     * and a substituted default would put one somewhere the player never asked for. Same rule as the
     * struggle packet and as the port's enum codec.
     */
    public static SelfRestraintC2SPacket decode(FriendlyByteBuf buf) {
        RestraintSlot slot = PacketBounds.readEnumOrdinal(buf, RestraintSlot.class)
                .orElseThrow(() -> new io.netty.handler.codec.DecoderException("Unknown restraint slot"));
        boolean offHand = buf.readBoolean();
        return new SelfRestraintC2SPacket(slot, offHand);
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
    public static void handle(SelfRestraintC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPacketGuard.accept(ctx, RequestBudget.Category.ACTION, sender -> {
            if (!ActionValidation.actionable(sender)) {
                return;
            }
            SelfApplicationService.apply(sender, msg.slot,
                    msg.offHand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        });
    }
}

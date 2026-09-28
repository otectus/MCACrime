package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.restraint.PhysicalRestraintView;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * The one wire form of a {@link PhysicalRestraintView}, shared by the snapshot and the delta.
 *
 * <p>Shared deliberately. Two copies of a field order is how a reader and a writer end up one byte
 * apart, and a physical-state message that is misread does not throw — it draws the wrong gear on the
 * wrong slot and attaches the lead to whatever entity holds the id it landed on.
 *
 * <p>Written by hand rather than as a {@code StreamCodec.composite} of six components, because the
 * slot map needs the {@link PacketBounds} count discipline (read the count, reject it, and only then
 * allocate) and because {@code composite} tops out at six pairs. {@link #VIEW} wraps the pair so the
 * payloads can still be ordinary {@code StreamCodec}s.
 */
final class PhysicalStateCodec {

    /** The view as a stream codec, for the payloads that carry one or a list of them. */
    static final StreamCodec<RegistryFriendlyByteBuf, PhysicalRestraintView> VIEW =
            StreamCodec.of(PhysicalStateCodec::write, PhysicalStateCodec::read);

    private PhysicalStateCodec() {
    }

    static void write(FriendlyByteBuf buf, PhysicalRestraintView view) {
        buf.writeUUID(view.subject());
        buf.writeLong(view.generation());
        buf.writeLong(view.revision());
        buf.writeVarInt(view.tetherHolderEntityId());
        buf.writeBoolean(view.detained());
        Map<RestraintSlot, PhysicalRestraintView.SlotView> slots = view.slots();
        int count = Math.min(PacketBounds.MAX_RESTRAINT_SLOTS, slots.size());
        buf.writeVarInt(count);
        int written = 0;
        for (Map.Entry<RestraintSlot, PhysicalRestraintView.SlotView> entry : slots.entrySet()) {
            if (written++ == count) {
                return;
            }
            PacketBounds.writeEnum(buf, entry.getKey());
            buf.writeUtf(entry.getValue().definitionId().toString(), PacketBounds.MAX_ID_LENGTH);
            buf.writeFloat(entry.getValue().durabilityFraction());
            buf.writeBoolean(entry.getValue().broken());
        }
    }

    static PhysicalRestraintView read(FriendlyByteBuf buf) {
        UUID subject = buf.readUUID();
        long generation = buf.readLong();
        long revision = buf.readLong();
        int holder = buf.readVarInt();
        boolean detained = buf.readBoolean();
        int count = PacketBounds.readCount(buf, PacketBounds.MAX_RESTRAINT_SLOTS);
        Map<RestraintSlot, PhysicalRestraintView.SlotView> slots = new EnumMap<>(RestraintSlot.class);
        for (int i = 0; i < count; i++) {
            RestraintSlot slot = PacketBounds.readEnum(buf, RestraintSlot.class)
                    .orElseThrow(() -> new DecoderException("Unknown restraint slot"));
            ResourceLocation definitionId = PacketBounds.readResourceLocation(buf);
            float durability = PacketBounds.readUnitFraction(buf);
            boolean broken = buf.readBoolean();
            slots.put(slot, new PhysicalRestraintView.SlotView(definitionId, durability, broken));
        }
        return new PhysicalRestraintView(subject, generation, revision, slots, holder, detained);
    }
}

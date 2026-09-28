package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.restraint.RestraintDefinition;
import dev.otectus.mcacrime.restraint.RestraintFamily;
import dev.otectus.mcacrime.restraint.RestraintProfile;
import dev.otectus.mcacrime.restraint.RestraintProfileOverrides;
import dev.otectus.mcacrime.restraint.RestrictionPolicy;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Server to client: the {@code restraint_profiles} datapack layer, whole (plan §3.13).
 *
 * <h2>Why the client needs it</h2>
 *
 * <p>Most effective values never leave the server: durability reaches the HUD as a fraction the
 * server computed, and a lock's pick numbers arrive inside {@link LockpickBeginS2CPacket} for that
 * one session. The exception is the restriction policy. {@code client/RestraintInputHandler}
 * composes it locally, from definition ids, to cancel input the server is going to refuse anyway —
 * and a pack that loosens {@code sprint} or imposes {@code obscure_vision} would otherwise be
 * predicted against the code table, which is a player fighting their own client.
 *
 * <p>Sent on login and after every {@code /reload}, always, including when the set is empty: leaving
 * a server has to be able to say "those numbers are gone", and silence cannot.
 *
 * <p>Display and prediction only, like every other sync on this channel. Nothing the client does
 * with this can restrain anybody, and the server re-decides every restriction it enforces.
 *
 * <p>The codec is written by hand rather than composed: every field is a validation rule as much as
 * a value — an unknown restriction component, a pick number outside its bounds or a family no build
 * declares is a {@link DecoderException} and not a silently defaulted record.
 */
public record RestraintProfileSyncS2CPacket(List<RestraintProfile> profiles)
        implements CustomPacketPayload {

    public static final Type<RestraintProfileSyncS2CPacket> TYPE =
            new Type<>(McaCrime.id("restraint_profiles"));

    /** Component entries in one profile: the sixteen the matrix has, and no more. */
    public static final int MAX_COMPONENTS = 16;
    /** Rig ids one profile may list. Generous: a settlement mod may describe several life stages. */
    public static final int MAX_RIGS = 32;

    public static final StreamCodec<RegistryFriendlyByteBuf, RestraintProfileSyncS2CPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> {
                int count = Math.min(RestraintProfileOverrides.MAX_PROFILES, packet.profiles.size());
                buf.writeVarInt(count);
                for (int i = 0; i < count; i++) {
                    write(buf, packet.profiles.get(i));
                }
            }, buf -> {
                int count = PacketBounds.readCount(buf, RestraintProfileOverrides.MAX_PROFILES);
                List<RestraintProfile> profiles = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    profiles.add(read(buf));
                }
                return new RestraintProfileSyncS2CPacket(profiles);
            });

    public RestraintProfileSyncS2CPacket {
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
    }

    private static void write(FriendlyByteBuf buf, RestraintProfile profile) {
        buf.writeResourceLocation(profile.definitionId());
        buf.writeVarInt(profile.durability().orElse(0));
        PacketBounds.writeBoundedMap(buf, profile.restrictions(), MAX_COMPONENTS,
                (b, name) -> b.writeUtf(name, PacketBounds.MAX_ID_LENGTH),
                FriendlyByteBuf::writeBoolean);
        Optional<RestraintDefinition.PickProfile> pick = profile.pick();
        buf.writeBoolean(pick.isPresent());
        if (pick.isPresent()) {
            buf.writeBoolean(pick.get().pickable());
            buf.writeVarInt(pick.get().progressIncrease());
            buf.writeVarInt(pick.get().speedIncrease());
        }
        Optional<Optional<RestraintFamily>> keyFamily = profile.keyFamily();
        buf.writeBoolean(keyFamily.isPresent());
        if (keyFamily.isPresent()) {
            buf.writeUtf(keyFamily.get().map(RestraintFamily::id).orElse(RestraintProfile.NO_KEY),
                    PacketBounds.MAX_ID_LENGTH);
        }
        Optional<Set<String>> rigs = profile.supportedRigs();
        buf.writeBoolean(rigs.isPresent());
        if (rigs.isPresent()) {
            List<String> list = List.copyOf(rigs.get());
            int size = Math.min(MAX_RIGS, list.size());
            buf.writeVarInt(size);
            for (int i = 0; i < size; i++) {
                buf.writeUtf(list.get(i), PacketBounds.MAX_ID_LENGTH);
            }
        }
    }

    private static RestraintProfile read(FriendlyByteBuf buf) {
        var definitionId = PacketBounds.readResourceLocation(buf);
        int rawDurability = buf.readVarInt();
        if (rawDurability < 0 || rawDurability > RestraintProfile.MAX_DURABILITY) {
            throw new DecoderException("Durability " + rawDurability + " outside [0, "
                    + RestraintProfile.MAX_DURABILITY + "]");
        }
        OptionalInt durability = rawDurability == 0 ? OptionalInt.empty() : OptionalInt.of(rawDurability);

        Map<String, Boolean> restrictions = new LinkedHashMap<>();
        int components = PacketBounds.readCount(buf, MAX_COMPONENTS);
        for (int i = 0; i < components; i++) {
            String name = PacketBounds.readId(buf);
            boolean value = buf.readBoolean();
            if (!RestrictionPolicy.isComponent(name)) {
                throw new DecoderException("Unknown restriction component '" + name + "'");
            }
            restrictions.put(name, value);
        }

        Optional<RestraintDefinition.PickProfile> pick = Optional.empty();
        if (buf.readBoolean()) {
            boolean pickable = buf.readBoolean();
            int progress = buf.readVarInt();
            int speed = buf.readVarInt();
            if (progress < 0 || progress > RestraintProfile.MAX_PROGRESS_INCREASE
                    || speed < 0 || speed > RestraintProfile.MAX_SPEED_INCREASE) {
                throw new DecoderException("Pick numbers outside their bounds");
            }
            pick = Optional.of(pickable
                    ? RestraintDefinition.PickProfile.of(progress, speed)
                    : RestraintDefinition.PickProfile.unpickable());
        }

        Optional<Optional<RestraintFamily>> keyFamily = Optional.empty();
        if (buf.readBoolean()) {
            String raw = PacketBounds.readId(buf);
            if (RestraintProfile.NO_KEY.equals(raw)) {
                keyFamily = Optional.of(Optional.empty());
            } else {
                Optional<RestraintFamily> family = RestraintFamily.parse(raw);
                if (family.isEmpty()) {
                    throw new DecoderException("Unknown restraint family '" + raw + "'");
                }
                keyFamily = Optional.of(family);
            }
        }

        Optional<Set<String>> rigs = Optional.empty();
        if (buf.readBoolean()) {
            int size = PacketBounds.readCount(buf, MAX_RIGS);
            Set<String> read = new LinkedHashSet<>();
            for (int i = 0; i < size; i++) {
                read.add(PacketBounds.readId(buf));
            }
            rigs = read.isEmpty() ? Optional.empty() : Optional.of(read);
        }

        return new RestraintProfile(definitionId, durability, restrictions, pick, keyFamily, rigs);
    }

    /**
     * Applies the pack's numbers on the receiving client.
     *
     * <p>Straight into the common {@link RestraintProfileOverrides}, with no client type named: the
     * override layer is common code and the client reads effective definitions through the same
     * registry accessor the server does. There is nothing here to hand to a screen, which is why this
     * does not go through {@code CrimeClientPayloadRouter}.
     */
    public static void handle(RestraintProfileSyncS2CPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> RestraintProfileOverrides.replaceAll(payload.profiles()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

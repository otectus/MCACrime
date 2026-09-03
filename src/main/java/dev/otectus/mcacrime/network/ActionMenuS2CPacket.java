package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.action.ActionMenuKind;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;
import java.util.UUID;

/**
 * A server-issued action menu: which rows to draw, how to draw them, and who they are about.
 *
 * <p>Every bound here is deliberate. The Forge decoder ran on the network thread, where a throw cost
 * the whole connection, so it clamped counts and substituted defaults for bad ordinals. A payload
 * decode failure is scoped to the payload, so this refuses instead (spec §9.6); the outbound
 * {@code limit} is kept as defence in depth.
 */
public record ActionMenuS2CPacket(UUID menuId, int revision, UUID targetId, ActionMenuKind kind,
                                  Component targetName,
                                  List<ActionMenuEntry> actions) implements CustomPacketPayload {

    /** Row cap. The grouped four-category menu is comfortably inside this. */
    public static final int MAX_ACTIONS = 32;

    public static final Type<ActionMenuS2CPacket> TYPE = new Type<>(McaCrime.id("action_menu"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ActionMenuS2CPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, ActionMenuS2CPacket::menuId,
                    ByteBufCodecs.VAR_INT, ActionMenuS2CPacket::revision,
                    UUIDUtil.STREAM_CODEC, ActionMenuS2CPacket::targetId,
                    CrimeStreamCodecs.enumCodec(ActionMenuKind.class, "action menu kind"),
                    ActionMenuS2CPacket::kind,
                    ComponentSerialization.STREAM_CODEC, ActionMenuS2CPacket::targetName,
                    ActionMenuEntry.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ACTIONS)),
                    ActionMenuS2CPacket::actions,
                    ActionMenuS2CPacket::new);

    public ActionMenuS2CPacket {
        actions = actions == null ? List.of() : List.copyOf(actions).stream().limit(MAX_ACTIONS).toList();
        kind = kind == null ? ActionMenuKind.VILLAGER : kind;
        targetName = targetName == null ? Component.empty() : targetName;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

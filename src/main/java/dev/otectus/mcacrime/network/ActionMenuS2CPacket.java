package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.action.ActionCategory;
import dev.otectus.mcacrime.action.ActionDuration;
import dev.otectus.mcacrime.action.ActionLegality;
import dev.otectus.mcacrime.action.ActionMenuKind;
import dev.otectus.mcacrime.client.CrimeClientHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Supplier;

/**
 * A server-issued action menu: which rows to draw, how to draw them, and who they are about.
 *
 * <p>Every bound here is deliberate. A decoder runs on the network thread and a throw inside it drops
 * the connection, so this reads defensively and clamps rather than rejecting — the same policy
 * {@code CrimeRecordQuery} follows for the same reason.
 */
public record ActionMenuS2CPacket(UUID menuId, int revision, UUID targetId, ActionMenuKind kind,
                                  Component targetName, List<ActionMenuEntry> actions) {

    /** Row cap. The grouped four-category menu is comfortably inside this. */
    public static final int MAX_ACTIONS = 32;

    private static final ActionMenuKind[] KINDS = ActionMenuKind.values();
    private static final ActionCategory[] CATEGORIES = ActionCategory.values();
    private static final ActionLegality[] LEGALITIES = ActionLegality.values();
    private static final ActionDuration[] DURATIONS = ActionDuration.values();

    public ActionMenuS2CPacket {
        actions = actions == null ? List.of() : List.copyOf(actions);
        kind = kind == null ? ActionMenuKind.VILLAGER : kind;
        targetName = targetName == null ? Component.empty() : targetName;
    }

    public static void encode(ActionMenuS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.menuId);
        buf.writeVarInt(msg.revision);
        buf.writeUUID(msg.targetId);
        buf.writeEnum(msg.kind);
        buf.writeComponent(msg.targetName);
        List<ActionMenuEntry> rows = msg.actions.stream().limit(MAX_ACTIONS).toList();
        buf.writeVarInt(rows.size());
        for (ActionMenuEntry action : rows) {
            buf.writeResourceLocation(action.actionId());
            buf.writeUtf(action.labelKey(), 128);
            buf.writeUtf(action.descriptionKey(), 128);
            buf.writeEnum(action.category());
            buf.writeEnum(action.legality());
            buf.writeEnum(action.duration());
            List<String> requirements = action.requirementKeys().stream()
                    .limit(ActionMenuEntry.MAX_REQUIREMENTS).toList();
            buf.writeVarInt(requirements.size());
            for (String requirement : requirements) buf.writeUtf(requirement, 128);
            buf.writeBoolean(action.hostile());
            buf.writeBoolean(action.available());
            buf.writeUtf(action.reasonKey(), 256);
        }
    }

    public static ActionMenuS2CPacket decode(FriendlyByteBuf buf) {
        UUID menu = buf.readUUID();
        int revision = buf.readVarInt();
        UUID target = buf.readUUID();
        ActionMenuKind kind = readEnum(buf, KINDS, ActionMenuKind.VILLAGER);
        Component targetName = buf.readComponent();
        int count = Math.min(MAX_ACTIONS, Math.max(0, buf.readVarInt()));
        List<ActionMenuEntry> actions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            var actionId = buf.readResourceLocation();
            String label = buf.readUtf(128);
            String description = buf.readUtf(128);
            ActionCategory category = readEnum(buf, CATEGORIES, ActionCategory.SPECIAL);
            ActionLegality legality = readEnum(buf, LEGALITIES, ActionLegality.CONTEXTUAL);
            ActionDuration duration = readEnum(buf, DURATIONS, ActionDuration.INSTANT);
            int requirementCount = Math.min(ActionMenuEntry.MAX_REQUIREMENTS, Math.max(0, buf.readVarInt()));
            List<String> requirements = new ArrayList<>(requirementCount);
            for (int r = 0; r < requirementCount; r++) requirements.add(buf.readUtf(128));
            boolean hostile = buf.readBoolean();
            boolean available = buf.readBoolean();
            String reason = buf.readUtf(256);
            actions.add(new ActionMenuEntry(actionId, label, description, category, legality, duration,
                    requirements, hostile, available, reason));
        }
        return new ActionMenuS2CPacket(menu, revision, target, kind, targetName, actions);
    }

    /**
     * Reads an enum by ordinal, substituting a safe default for an out-of-range value instead of
     * throwing. {@code FriendlyByteBuf#readEnum} would throw on a byte from an older or hostile peer,
     * and a throw here costs the whole connection for what is only a cosmetic field.
     */
    private static <E extends Enum<E>> E readEnum(FriendlyByteBuf buf, E[] values, E fallback) {
        int ordinal = buf.readVarInt();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : fallback;
    }

    public static void handle(ActionMenuS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CrimeClientHandlers.onActionMenu(msg)));
        context.setPacketHandled(true);
    }
}

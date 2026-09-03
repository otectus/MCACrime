package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.action.ActionCategory;
import dev.otectus.mcacrime.action.ActionDuration;
import dev.otectus.mcacrime.action.ActionLegality;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * One presentation row. Everything here is a translation key or an enum -- never a number the server
 * computed about the target, because spec 8.4 forbids exposing exact purse balances, compliance
 * rolls, witness confidence or guard-response odds to the client.
 *
 * <p>{@code available} is a server verdict at menu-build time and is re-derived when the row is
 * clicked, so a modified client that flips it gains nothing.
 */
public record ActionMenuEntry(ResourceLocation actionId,
                              String labelKey,
                              String descriptionKey,
                              ActionCategory category,
                              ActionLegality legality,
                              ActionDuration duration,
                              List<String> requirementKeys,
                              boolean hostile,
                              boolean available,
                              String reasonKey) {

    /** Caps the requirement strip so a malformed or hostile packet cannot make the client draw forever. */
    public static final int MAX_REQUIREMENTS = 8;

    public ActionMenuEntry {
        requirementKeys = List.copyOf(requirementKeys);
    }

    /** A label or requirement key is a translation key; the reason string may carry a suffix. */
    public static final int MAX_KEY_LENGTH = 128;
    public static final int MAX_REASON_LENGTH = 256;

    /**
     * Ten fields, so this is written by hand rather than through {@code StreamCodec.composite}, which
     * stops at six. Every count is checked before anything is allocated (spec §9.6).
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, ActionMenuEntry> STREAM_CODEC =
            StreamCodec.of(ActionMenuEntry::write, ActionMenuEntry::read);

    private static void write(RegistryFriendlyByteBuf buf, ActionMenuEntry entry) {
        buf.writeResourceLocation(entry.actionId());
        buf.writeUtf(entry.labelKey(), MAX_KEY_LENGTH);
        buf.writeUtf(entry.descriptionKey(), MAX_KEY_LENGTH);
        buf.writeVarInt(entry.category().ordinal());
        buf.writeVarInt(entry.legality().ordinal());
        buf.writeVarInt(entry.duration().ordinal());
        List<String> requirements = entry.requirementKeys().stream().limit(MAX_REQUIREMENTS).toList();
        buf.writeVarInt(requirements.size());
        for (String requirement : requirements) {
            buf.writeUtf(requirement, MAX_KEY_LENGTH);
        }
        buf.writeBoolean(entry.hostile());
        buf.writeBoolean(entry.available());
        buf.writeUtf(entry.reasonKey(), MAX_REASON_LENGTH);
    }

    private static ActionMenuEntry read(RegistryFriendlyByteBuf buf) {
        ResourceLocation actionId = buf.readResourceLocation();
        String label = buf.readUtf(MAX_KEY_LENGTH);
        String description = buf.readUtf(MAX_KEY_LENGTH);
        ActionCategory category = readEnum(buf, ActionCategory.values(), "action category");
        ActionLegality legality = readEnum(buf, ActionLegality.values(), "action legality");
        ActionDuration duration = readEnum(buf, ActionDuration.values(), "action duration");
        int requirementCount = buf.readVarInt();
        if (requirementCount < 0 || requirementCount > MAX_REQUIREMENTS) {
            throw new DecoderException("mcacrime: " + requirementCount + " requirement keys, at most "
                    + MAX_REQUIREMENTS + " allowed");
        }
        List<String> requirements = new ArrayList<>(requirementCount);
        for (int i = 0; i < requirementCount; i++) {
            requirements.add(buf.readUtf(MAX_KEY_LENGTH));
        }
        return new ActionMenuEntry(actionId, label, description, category, legality, duration,
                requirements, buf.readBoolean(), buf.readBoolean(), buf.readUtf(MAX_REASON_LENGTH));
    }

    /** Same refuse-don't-guess policy as {@code CrimeStreamCodecs.enumCodec}, inlined for the by-hand codec. */
    private static <E extends Enum<E>> E readEnum(RegistryFriendlyByteBuf buf, E[] values, String what) {
        int ordinal = buf.readVarInt();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new DecoderException("mcacrime: " + what + " ordinal " + ordinal
                    + " outside [0, " + (values.length - 1) + "]");
        }
        return values[ordinal];
    }
}

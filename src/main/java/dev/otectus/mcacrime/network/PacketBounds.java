package dev.otectus.mcacrime.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Every wire bound this channel has, in one place, and the readers that enforce them.
 *
 * <p>The rule these readers exist to impose is "read the count first, reject it, and only then
 * allocate". {@code FriendlyByteBuf#readMap} and {@code #readCollection} trust the count they are
 * given, so a hostile peer's varint is an allocation of that size before any of our code runs.
 *
 * <p>Rejection is a {@link DecoderException} rather than a clamp. Clamp-and-continue was the previous
 * policy here and it is worse than it looks: the rest of the payload is then read at the wrong offset,
 * so a malformed packet becomes a <em>plausible</em> packet with different contents. Forge surfaces a
 * decoder throw as a protocol error on that connection, which is the correct outcome for a peer that
 * is either broken or lying.
 */
public final class PacketBounds {

    /** Entries in a bulk sync map (bands, restraints). Above this the snapshot is truncated, not grown. */
    public static final int MAX_MAP_ENTRIES = 256;
    /** Rows in one action menu. */
    public static final int MAX_MENU_ENTRIES = 64;
    /** Requirement chips on one menu row. */
    public static final int MAX_REQUIREMENT_KEYS = 16;
    /** Rows in one dossier answer. */
    public static final int MAX_DOSSIER_ROWS = 32;
    /** Any identifier-shaped string: a translation key, a community key, an enum name. */
    public static final int MAX_ID_LENGTH = 128;
    /** Any string that exists to be shown to a player rather than looked up. */
    public static final int MAX_DISPLAY_LENGTH = 256;

    private PacketBounds() {
    }

    /** Reads an element count, rejecting a negative one or one over {@code max}. */
    public static int readCount(FriendlyByteBuf buf, int max) {
        int count = buf.readVarInt();
        if (count < 0 || count > max) {
            throw new DecoderException("Element count " + count + " outside [0, " + max + "]");
        }
        return count;
    }

    public static <T> List<T> readBoundedList(FriendlyByteBuf buf, int max, Function<FriendlyByteBuf, T> reader) {
        int count = readCount(buf, max);
        List<T> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(reader.apply(buf));
        }
        return list;
    }

    public static <K, V> Map<K, V> readBoundedMap(FriendlyByteBuf buf, int max,
                                                  Function<FriendlyByteBuf, K> keyReader,
                                                  Function<FriendlyByteBuf, V> valueReader) {
        int count = readCount(buf, max);
        Map<K, V> map = new LinkedHashMap<>(Math.max(4, count));
        for (int i = 0; i < count; i++) {
            K key = keyReader.apply(buf);
            map.put(key, valueReader.apply(buf));
        }
        return map;
    }

    /** Writes at most {@code max} entries, so an oversized snapshot cannot produce an undecodable packet. */
    public static <K, V> void writeBoundedMap(FriendlyByteBuf buf, Map<K, V> map, int max,
                                              BiConsumer<FriendlyByteBuf, K> keyWriter,
                                              BiConsumer<FriendlyByteBuf, V> valueWriter) {
        int count = Math.min(max, map.size());
        buf.writeVarInt(count);
        int written = 0;
        for (Map.Entry<K, V> entry : map.entrySet()) {
            if (written++ == count) {
                return;
            }
            keyWriter.accept(buf, entry.getKey());
            valueWriter.accept(buf, entry.getValue());
        }
    }

    /**
     * A {@link ResourceLocation}, read under {@link #MAX_ID_LENGTH} and rejected if it is not one.
     *
     * <p>{@code FriendlyByteBuf#readResourceLocation} reads an unbounded string first and then throws
     * its own exception type on a malformed id; this keeps both failures inside the decoder contract.
     */
    public static ResourceLocation readResourceLocation(FriendlyByteBuf buf) {
        String raw = readId(buf);
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null) {
            throw new DecoderException("Malformed resource location");
        }
        return id;
    }

    /** An identifier-shaped string. */
    public static String readId(FriendlyByteBuf buf) {
        return buf.readUtf(MAX_ID_LENGTH);
    }

    /** A string that is only ever shown. */
    public static String readDisplay(FriendlyByteBuf buf) {
        return buf.readUtf(MAX_DISPLAY_LENGTH);
    }

    /**
     * Reads an enum <em>by name</em>, empty when the name is not a constant of this type.
     *
     * <p>Names rather than ordinals because an ordinal is only meaningful against the exact build that
     * wrote it: inserting a constant silently reinterprets every value after it. The caller decides
     * what an unknown name means — for a client-to-server packet that is always "reject", never a
     * substituted default, because the substitute would be a decision the player never made.
     */
    public static <E extends Enum<E>> Optional<E> readEnum(FriendlyByteBuf buf, Class<E> type) {
        String name = readId(buf);
        for (E value : type.getEnumConstants()) {
            if (value.name().equals(name)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /** The write side of {@link #readEnum}. */
    public static void writeEnum(FriendlyByteBuf buf, Enum<?> value) {
        buf.writeUtf(value.name(), MAX_ID_LENGTH);
    }
}

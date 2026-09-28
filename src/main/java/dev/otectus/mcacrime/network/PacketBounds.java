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
 * so a malformed packet becomes a <em>plausible</em> packet with different contents. NeoForge surfaces a
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
    /**
     * Occupied slots in one physical-restraint message (0.7.5).
     *
     * <p>Three, because there are three body slots. A peer claiming four is not describing a subject
     * this build can have, so the packet is refused rather than read at the wrong offset.
     */
    public static final int MAX_RESTRAINT_SLOTS = 3;
    /** Subjects in one physical-state snapshot. Sized like {@link #MAX_MAP_ENTRIES}. */
    public static final int MAX_PHYSICAL_SUBJECTS = MAX_MAP_ENTRIES;
    /**
     * The highest lockpick phase a client may claim to be on (0.7.5 M3.3).
     *
     * <p>A ceiling rather than a limit anybody reaches: the meter wins at forty and the smallest
     * progress step is three, so fourteen phases is already a won lock. What this refuses is a client
     * naming phase two billion, whose {@code (phase + 1) * speed} drain would overflow the arithmetic
     * it is fed into.
     */
    public static final int MAX_LOCKPICK_PHASE = 1024;

    /**
     * Slots one search may project (0.7.5 M5.2).
     *
     * <p>A player has forty-one; a villager has fewer; a modded subject with several providers could
     * have more. Fifty-four is a double chest and comfortably above every case this build produces,
     * so a peer claiming more is describing a screen that cannot exist.
     */
    public static final int MAX_FRISK_SLOTS = 54;

    /**
     * The largest count one transfer may ask for.
     *
     * <p>Not 64: modded stacks legitimately exceed it, and the specification says not to hard-code
     * vanilla's limit as a universal maximum. The real bound is the live stack's own count, checked
     * on the server; this is only the ceiling that stops a packet naming two billion.
     */
    public static final int MAX_FRISK_COUNT = 1024;

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

    /**
     * Reads a 0..1 fraction, rejecting anything that is not one.
     *
     * <p>Rejected rather than clamped, and NaN is the reason. A clamp turns {@code NaN} into a number
     * silently, and a durability bar driven by one renders as whatever the comparison happens to do;
     * more importantly, a peer sending it is not a peer whose remaining fields should be trusted.
     */
    public static float readUnitFraction(FriendlyByteBuf buf) {
        float value = buf.readFloat();
        if (!Float.isFinite(value) || value < 0.0F || value > 1.0F) {
            throw new DecoderException("Fraction " + value + " outside [0, 1]");
        }
        return value;
    }

    /** The write side of {@link #readEnum}. */
    public static void writeEnum(FriendlyByteBuf buf, Enum<?> value) {
        buf.writeUtf(value.name(), MAX_ID_LENGTH);
    }
}

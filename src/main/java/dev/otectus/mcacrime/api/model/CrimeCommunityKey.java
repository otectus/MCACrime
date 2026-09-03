package dev.otectus.mcacrime.api.model;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.Optional;

/**
 * The dimension-aware identity of a village, as MCA: Crime records it.
 *
 * <p>MCA allocates village ids from a {@code SavedData} obtained per {@code ServerLevel}, so village
 * {@code 3} in the Nether and village {@code 3} in the overworld are two different places. Every
 * crime record and every fallback standing entry in this mod used to be keyed on the bare integer,
 * which silently merged them. This type is what stops that.
 *
 * <p>It is deliberately a near-copy of MCA: Reputation's {@code CommunityKey}, field for field and
 * NBT key for NBT key, so the optional bridge converts between them by reading two components and
 * the two mods' debug output reads identically. It is <b>not</b> an import of it: MCA: Crime must
 * load and work with MCA: Reputation absent, so the type has to be ours.
 *
 * <p>The canonical constructor throws on a null dimension or a negative id, matching Reputation's
 * contract exactly. That equivalence is the point — it makes the bridge's conversion total, so a key
 * that exists here can never blow up at delivery time, deep inside the outbox pump, on a crime that
 * has already been committed. Every construction site inside this mod goes through {@link #of},
 * {@link #tryParse}, or {@link #load}, all of which return empty rather than throwing, so hand-edited
 * NBT cannot fail a world load.
 */
public record CrimeCommunityKey(ResourceLocation dimension, int villageId)
        implements Comparable<CrimeCommunityKey> {

    /** NBT key for the dimension half. Matches Reputation's {@code CommunityKey.TAG_DIMENSION}. */
    public static final String TAG_DIMENSION = "dim";
    /** NBT key for the village half. Matches Reputation's {@code CommunityKey.TAG_VILLAGE}. */
    public static final String TAG_VILLAGE = "village";

    /** Codec for packets, commands, and any datapack surface. Rejects a negative id at parse time. */
    public static final Codec<CrimeCommunityKey> CODEC = RecordCodecBuilder
            .<CrimeCommunityKey>create(instance -> instance.group(
                    ResourceLocation.CODEC.fieldOf(TAG_DIMENSION).forGetter(CrimeCommunityKey::dimension),
                    Codec.INT.fieldOf(TAG_VILLAGE).forGetter(CrimeCommunityKey::villageId)
            ).apply(instance, CrimeCommunityKey::new))
            .flatXmap(CrimeCommunityKey::validate, CrimeCommunityKey::validate);

    public CrimeCommunityKey {
        if (dimension == null) {
            throw new IllegalArgumentException("CrimeCommunityKey dimension must not be null");
        }
        if (villageId < 0) {
            throw new IllegalArgumentException("CrimeCommunityKey villageId must be non-negative, got " + villageId);
        }
    }

    private static DataResult<CrimeCommunityKey> validate(CrimeCommunityKey key) {
        return key.villageId < 0
                ? DataResult.error(() -> "Negative village id: " + key.villageId)
                : DataResult.success(key);
    }

    /** Guarded constructor: empty instead of throwing when the dimension is null or the id negative. */
    public static Optional<CrimeCommunityKey> of(ResourceLocation dimension, int villageId) {
        if (dimension == null || villageId < 0) {
            return Optional.empty();
        }
        return Optional.of(new CrimeCommunityKey(dimension, villageId));
    }

    /** Guarded constructor from a dimension {@link ResourceKey}, as levels expose it. */
    public static Optional<CrimeCommunityKey> of(ResourceKey<Level> dimension, int villageId) {
        return dimension == null ? Optional.empty() : of(dimension.location(), villageId);
    }

    /**
     * Parses the command/log form {@code <namespace>:<path>/<villageId>}, e.g.
     * {@code minecraft:overworld/3}. Empty for anything malformed — never throws.
     */
    public static Optional<CrimeCommunityKey> tryParse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        int split = raw.lastIndexOf('/');
        if (split <= 0 || split == raw.length() - 1) {
            return Optional.empty();
        }
        ResourceLocation dimension = ResourceLocation.tryParse(raw.substring(0, split));
        if (dimension == null) {
            return Optional.empty();
        }
        try {
            return of(dimension, Integer.parseInt(raw.substring(split + 1)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Reads a key from its own compound. Empty when absent, unparseable, or negative — the house rule
     * is that one malformed entry is skipped, never that a load aborts.
     */
    public static Optional<CrimeCommunityKey> load(CompoundTag tag) {
        if (tag == null || !tag.contains(TAG_DIMENSION) || !tag.contains(TAG_VILLAGE)) {
            return Optional.empty();
        }
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString(TAG_DIMENSION));
        return dimension == null ? Optional.empty() : of(dimension, tag.getInt(TAG_VILLAGE));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_DIMENSION, dimension.toString());
        tag.putInt(TAG_VILLAGE, villageId);
        return tag;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension);
        buf.writeVarInt(villageId);
    }

    /**
     * Network read. A corrupt or hostile packet can carry a negative id, so this clamps rather than
     * throwing inside the netty pipeline; the server re-validates before the key is used.
     */
    public static CrimeCommunityKey read(FriendlyByteBuf buf) {
        ResourceLocation dimension = buf.readResourceLocation();
        return new CrimeCommunityKey(dimension, Math.max(0, buf.readVarInt()));
    }

    /** The stable string form used for NBT map keys, command suggestions, and logs. */
    public String asString() {
        return dimension + "/" + villageId;
    }

    /** Deterministic ordering (dimension, then id) so lists never depend on hash order. */
    @Override
    public int compareTo(CrimeCommunityKey other) {
        int byDimension = dimension.compareTo(other.dimension);
        return byDimension != 0 ? byDimension : Integer.compare(villageId, other.villageId);
    }

    @Override
    public String toString() {
        return asString();
    }
}

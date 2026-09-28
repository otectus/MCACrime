package dev.otectus.mcacrime.tether;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * One physical hold: this subject is attached to that holder or that anchor.
 *
 * <p>Exactly one of {@code holder} and {@code anchorPos} is meaningful. A tether with neither holds
 * nothing and is dropped by the sweep rather than silently pinning somebody to the origin.
 *
 * <p>{@code suspended} is the arbitration flag from §3.6: when a higher-priority authority takes
 * over — the subject is locked in a pillory, or put in a boat — the chain is marked suspended rather
 * than deleted, so releasing the device restores the chain instead of dropping it on the floor.
 *
 * <p>{@code chainOwner} and {@code returnOnRelease} are the item-ownership half. A chain is a real
 * item somebody paid for; ending the tether owes it back to whoever supplied it, and a migrated
 * legacy hold owes nobody anything because no item was ever taken.
 *
 * @param id              this tether's identity
 * @param subject         who is held
 * @param kind            why
 * @param holder          the entity holding them, when a holder holds them
 * @param dimension       where both ends are; a tether never spans dimensions
 * @param anchorPos       the fixed point, when a fixed point holds them
 * @param lengthBlocks    how far the subject may get before tension applies
 * @param suspended       true when a higher-priority authority has taken over
 * @param chainOwner      who supplied the chain item, when one was supplied
 * @param returnOnRelease whether release owes that owner the item back
 * @param revision        bumped on every change, so a stale action can be refused
 */
public record TetherRecord(
        UUID id,
        UUID subject,
        TetherKind kind,
        @Nullable UUID holder,
        @Nullable ResourceLocation dimension,
        @Nullable BlockPos anchorPos,
        double lengthBlocks,
        boolean suspended,
        @Nullable UUID chainOwner,
        boolean returnOnRelease,
        long revision) {

    /** The longest a tether may be, so a hostile or corrupt value cannot make one unbreakable. */
    public static final double MAX_LENGTH_BLOCKS = 64.0D;

    public TetherRecord {
        kind = kind == null ? TetherKind.LEGACY_HOLD : kind;
        lengthBlocks = Double.isFinite(lengthBlocks)
                ? Math.max(0.5D, Math.min(MAX_LENGTH_BLOCKS, lengthBlocks))
                : 1.0D;
        revision = Math.max(0L, revision);
    }

    /** A guard or kidnapper holding a subject directly. */
    public static TetherRecord toHolder(UUID id, UUID subject, TetherKind kind, UUID holder,
                                        @Nullable ResourceLocation dimension, double lengthBlocks,
                                        @Nullable UUID chainOwner, boolean returnOnRelease) {
        return new TetherRecord(id, subject, kind, holder, dimension, null, lengthBlocks, false,
                chainOwner, returnOnRelease, 1L);
    }

    /** A subject tied to a fixed point. */
    public static TetherRecord toAnchor(UUID id, UUID subject, TetherKind kind,
                                        @Nullable ResourceLocation dimension, BlockPos anchorPos,
                                        double lengthBlocks, @Nullable UUID chainOwner,
                                        boolean returnOnRelease) {
        return new TetherRecord(id, subject, kind, null, dimension, anchorPos, lengthBlocks, false,
                chainOwner, returnOnRelease, 1L);
    }

    public Optional<UUID> holderId() {
        return Optional.ofNullable(holder);
    }

    public Optional<BlockPos> anchor() {
        return Optional.ofNullable(anchorPos);
    }

    /** True when this tether names something to be held to. */
    public boolean valid() {
        return subject != null && (holder != null || anchorPos != null);
    }

    /** This tether suspended or resumed, with its revision advanced. */
    public TetherRecord suspended(boolean value) {
        if (value == suspended) {
            return this;
        }
        return new TetherRecord(id, subject, kind, holder, dimension, anchorPos, lengthBlocks, value,
                chainOwner, returnOnRelease, revision + 1L);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("subject", subject);
        tag.putString("kind", kind.name());
        if (holder != null) tag.putUUID("holder", holder);
        if (dimension != null) tag.putString("dim", dimension.toString());
        if (anchorPos != null) {
            tag.putInt("ax", anchorPos.getX());
            tag.putInt("ay", anchorPos.getY());
            tag.putInt("az", anchorPos.getZ());
        }
        tag.putDouble("length", lengthBlocks);
        tag.putBoolean("suspended", suspended);
        if (chainOwner != null) tag.putUUID("chainOwner", chainOwner);
        tag.putBoolean("returnOnRelease", returnOnRelease);
        tag.putLong("revision", revision);
        return tag;
    }

    /** Empty when the row names no tether id or no subject; the caller quarantines it. */
    public static Optional<TetherRecord> load(@Nullable CompoundTag tag) {
        if (tag == null || !tag.hasUUID("id") || !tag.hasUUID("subject")) {
            return Optional.empty();
        }
        BlockPos anchor = tag.contains("ax") && tag.contains("ay") && tag.contains("az")
                ? new BlockPos(tag.getInt("ax"), tag.getInt("ay"), tag.getInt("az"))
                : null;
        return Optional.of(new TetherRecord(
                tag.getUUID("id"),
                tag.getUUID("subject"),
                TetherKind.parse(tag.getString("kind")),
                tag.hasUUID("holder") ? tag.getUUID("holder") : null,
                tag.contains("dim") ? ResourceLocation.tryParse(tag.getString("dim")) : null,
                anchor,
                tag.getDouble("length"),
                tag.getBoolean("suspended"),
                tag.hasUUID("chainOwner") ? tag.getUUID("chainOwner") : null,
                tag.getBoolean("returnOnRelease"),
                tag.getLong("revision")));
    }
}

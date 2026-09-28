package dev.otectus.mcacrime.locks;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * What a lock is on: a block at a position, or an entity.
 *
 * <p>A union rather than two record types because the lock table is one table and a padlock may be
 * moved from a door to a chest without becoming a different lock. The position is the
 * <em>canonical</em> one for its group — one lock for both halves of a door and both halves of a
 * double chest — which is normalised before a target is ever constructed.
 *
 * <p>Coordinates are not identity. A lock is identified by its {@code lockId}; this only says where
 * it currently is, which is why a stale pick session cannot unlock a replacement placed at the same
 * position (§3.5).
 */
public record LockTarget(Kind kind, @Nullable ResourceLocation dimension, @Nullable BlockPos pos,
                         @Nullable UUID entityId) {

    public enum Kind {
        BLOCK,
        ENTITY,
        /** A lock whose target has been removed but whose identity is still referenced by keys. */
        NONE
    }

    public LockTarget {
        kind = kind == null ? Kind.NONE : kind;
    }

    public static LockTarget block(ResourceLocation dimension, BlockPos pos) {
        return new LockTarget(Kind.BLOCK, dimension, pos, null);
    }

    public static LockTarget entity(UUID entityId) {
        return new LockTarget(Kind.ENTITY, null, null, entityId);
    }

    public static LockTarget none() {
        return new LockTarget(Kind.NONE, null, null, null);
    }

    public Optional<BlockPos> blockPos() {
        return kind == Kind.BLOCK ? Optional.ofNullable(pos) : Optional.empty();
    }

    public Optional<UUID> entity() {
        return kind == Kind.ENTITY ? Optional.ofNullable(entityId) : Optional.empty();
    }

    /** True when this target still names something that can be locked. */
    public boolean present() {
        return switch (kind) {
            case BLOCK -> dimension != null && pos != null;
            case ENTITY -> entityId != null;
            case NONE -> false;
        };
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", kind.name());
        if (dimension != null) tag.putString("dim", dimension.toString());
        if (pos != null) {
            tag.putInt("x", pos.getX());
            tag.putInt("y", pos.getY());
            tag.putInt("z", pos.getZ());
        }
        if (entityId != null) tag.putUUID("entity", entityId);
        return tag;
    }

    public static LockTarget load(@Nullable CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return none();
        }
        Kind kind;
        try {
            kind = Kind.valueOf(tag.getString("kind"));
        } catch (IllegalArgumentException e) {
            return none();
        }
        BlockPos pos = tag.contains("x") && tag.contains("y") && tag.contains("z")
                ? new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"))
                : null;
        return new LockTarget(kind,
                tag.contains("dim") ? ResourceLocation.tryParse(tag.getString("dim")) : null,
                pos,
                tag.hasUUID("entity") ? tag.getUUID("entity") : null);
    }
}

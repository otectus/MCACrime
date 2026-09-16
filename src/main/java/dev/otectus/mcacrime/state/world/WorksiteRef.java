package dev.otectus.mcacrime.state.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

/**
 * A workstation, as dimension plus position (0.7.2 §10.2).
 *
 * <p>Spec asks for "dimension plus position, such as a {@code GlobalPos}". This is the "plus
 * position" half written out, and it exists rather than storing a {@link GlobalPos} for one concrete
 * reason: building a {@code GlobalPos} means building a {@code ResourceKey<Level>}, which reaches
 * {@code BuiltInRegistries} and throws "Not bootstrapped" outside a running game. A saved record has
 * to be readable, writable and testable without a Minecraft bootstrap, and a dimension is an id in NBT
 * anyway.
 *
 * <p>Conversion in both directions is free and needs no registry: a {@link ServerLevel} already
 * carries the key, so {@link #matches} compares ids and {@link #toGlobalPos} borrows the level's own.
 * A reference to a dimension that is not loaded — a datapack dimension somebody removed — therefore
 * reads back intact and simply never matches, instead of failing to decode and taking the record with
 * it.
 */
public record WorksiteRef(ResourceLocation dimension, BlockPos pos) {

    public WorksiteRef {
        pos = pos.immutable();
    }

    public static WorksiteRef of(ResourceLocation dimension, BlockPos pos) {
        return new WorksiteRef(dimension, pos);
    }

    public static WorksiteRef of(Level level, BlockPos pos) {
        return new WorksiteRef(level.dimension().location(), pos);
    }

    @Nullable
    public static WorksiteRef of(@Nullable GlobalPos global) {
        return global == null ? null : new WorksiteRef(global.dimension().location(), global.pos());
    }

    /** True when this reference belongs to the level in hand. */
    public boolean matches(@Nullable ServerLevel level) {
        return level != null && level.dimension().location().equals(dimension);
    }

    /** The {@link GlobalPos} for a matching level, or {@code null}. The level supplies the key. */
    @Nullable
    public GlobalPos toGlobalPos(@Nullable ServerLevel level) {
        return matches(level) ? GlobalPos.of(level.dimension(), pos) : null;
    }

    /** True when a live job-site memory points at exactly this workstation. */
    public boolean sameAs(@Nullable GlobalPos global) {
        return global != null && global.dimension().location().equals(dimension) && global.pos().equals(pos);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("dimension", dimension.toString());
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        return tag;
    }

    @Nullable
    public static WorksiteRef load(@Nullable CompoundTag tag) {
        if (tag == null) {
            return null;
        }
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("dimension"));
        return dimension == null
                ? null
                : new WorksiteRef(dimension, new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")));
    }
}

package dev.otectus.mcacrime.jail;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * An assigned jail location (spec §7.4 command-based assignment): a center {@code pos} + {@code dim} +
 * containment {@code radius}. Persisted in {@link dev.otectus.mcacrime.state.world.CrimeWorldData}. NBT
 * round-trip in the project house style.
 */
public record JailAnchor(BlockPos pos, ResourceLocation dim, int radius) {

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        tag.putString("dim", dim.toString());
        tag.putInt("radius", radius);
        return tag;
    }

    /** Loads an anchor, or null if the stored dimension id is malformed (caller skips it). */
    public static JailAnchor load(CompoundTag tag) {
        ResourceLocation dim = ResourceLocation.tryParse(tag.getString("dim"));
        if (dim == null) {
            return null;
        }
        BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
        return new JailAnchor(pos, dim, tag.getInt("radius"));
    }
}

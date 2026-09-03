package dev.otectus.mcacrime.state;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Attaches and serialises a {@link PlayerCrimeData} on a player entity (spec §2, §18). */
public final class PlayerCrimeDataProvider implements ICapabilitySerializable<CompoundTag> {

    private final PlayerCrimeData data = new PlayerCrimeData();
    private final LazyOptional<PlayerCrimeData> holder = LazyOptional.of(() -> data);

    @Nonnull
    @Override
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side) {
        return CrimeCapabilities.PLAYER_CRIME.orEmpty(cap, holder);
    }

    @Override
    public CompoundTag serializeNBT() {
        return data.save();
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        data.load(tag);
    }

    public void invalidate() {
        holder.invalidate();
    }
}

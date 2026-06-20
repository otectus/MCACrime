package dev.otectus.mcacrime.captivity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Who currently holds a captive (spec §2.3): a {@link CustodyOwnerType} plus the payload that type needs —
 * an owner UUID (KIDNAPPER/GUARD), a village id (JAIL/AUTHORITY), and a jail position+dimension (JAIL).
 * Immutable; built through the static factories. NBT round-trip in the project house style, and {@link
 * #load} of a partial/empty tag never throws (collapses to {@link CustodyOwnerType#NONE}).
 */
public final class CustodyOwner {

    private static final CustodyOwner NONE = new CustodyOwner(CustodyOwnerType.NONE, null, -1, null, null);

    private final CustodyOwnerType type;
    @Nullable
    private final UUID ownerUuid;
    private final int villageId;
    @Nullable
    private final BlockPos jailPos;
    @Nullable
    private final ResourceLocation jailDim;

    private CustodyOwner(CustodyOwnerType type, @Nullable UUID ownerUuid, int villageId,
                         @Nullable BlockPos jailPos, @Nullable ResourceLocation jailDim) {
        this.type = type;
        this.ownerUuid = ownerUuid;
        this.villageId = villageId;
        this.jailPos = jailPos;
        this.jailDim = jailDim;
    }

    public static CustodyOwner kidnapper(UUID uuid) {
        return new CustodyOwner(CustodyOwnerType.KIDNAPPER, uuid, -1, null, null);
    }

    public static CustodyOwner guard(UUID uuid) {
        return new CustodyOwner(CustodyOwnerType.GUARD, uuid, -1, null, null);
    }

    public static CustodyOwner jail(int villageId, @Nullable BlockPos pos, @Nullable ResourceLocation dim) {
        return new CustodyOwner(CustodyOwnerType.JAIL, null, villageId, pos, dim);
    }

    public static CustodyOwner authority(int villageId) {
        return new CustodyOwner(CustodyOwnerType.AUTHORITY, null, villageId, null, null);
    }

    public static CustodyOwner none() {
        return NONE;
    }

    public CustodyOwnerType type() {
        return type;
    }

    /** The holder's entity UUID for KIDNAPPER/GUARD owners; empty otherwise. */
    public Optional<UUID> ownerUuid() {
        return Optional.ofNullable(ownerUuid);
    }

    public OptionalInt villageId() {
        return villageId >= 0 ? OptionalInt.of(villageId) : OptionalInt.empty();
    }

    @Nullable
    public BlockPos jailPos() {
        return jailPos;
    }

    @Nullable
    public ResourceLocation jailDim() {
        return jailDim;
    }

    /** True when this owner is an unlawful kidnapper held by {@code uuid} (drives Legal Target / theft-of-captive). */
    public boolean isKidnapper(UUID uuid) {
        return type == CustodyOwnerType.KIDNAPPER && uuid.equals(ownerUuid);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", type.name());
        if (ownerUuid != null) {
            tag.putUUID("owner", ownerUuid);
        }
        if (villageId >= 0) {
            tag.putInt("village", villageId);
        }
        if (jailPos != null) {
            tag.putInt("jx", jailPos.getX());
            tag.putInt("jy", jailPos.getY());
            tag.putInt("jz", jailPos.getZ());
        }
        if (jailDim != null) {
            tag.putString("jdim", jailDim.toString());
        }
        return tag;
    }

    public static CustodyOwner load(CompoundTag tag) {
        CustodyOwnerType type = CustodyOwnerType.parse(tag.getString("type"));
        UUID owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        int village = tag.contains("village") ? tag.getInt("village") : -1;
        BlockPos pos = tag.contains("jx") && tag.contains("jy") && tag.contains("jz")
                ? new BlockPos(tag.getInt("jx"), tag.getInt("jy"), tag.getInt("jz"))
                : null;
        ResourceLocation dim = tag.contains("jdim") ? ResourceLocation.tryParse(tag.getString("jdim")) : null;
        return new CustodyOwner(type, owner, village, pos, dim);
    }
}

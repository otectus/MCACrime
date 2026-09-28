package dev.otectus.mcacrime.restraint;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Who put a restraint on: a player, an NPC, a device, or the server itself.
 *
 * <p>A union rather than a bare UUID because "the system did it" and "a pillory did it" are real
 * answers that a UUID cannot express, and because the return policy at removal depends on which one
 * it was — gear the server issued is not returned to anybody.
 *
 * <p>Shaped after {@code captivity/CustodyOwner}, deliberately: the two are read side by side and a
 * second serialisation style would be one more thing to get wrong.
 */
public record RestraintApplier(Kind kind, Optional<UUID> entityId, Optional<ResourceLocation> deviceDimension,
                               Optional<BlockPos> devicePos) {

    public enum Kind {
        /** Nobody recorded. A pre-0.7.5 record converted by migration is not attributed to anyone. */
        NONE,
        PLAYER,
        NPC,
        DEVICE,
        /** The server: an arrest with no explicit gear, an operator command. */
        SYSTEM;

        public static Kind parse(String raw) {
            if (raw == null) {
                return NONE;
            }
            String name = raw.trim().toUpperCase(Locale.ROOT);
            for (Kind kind : values()) {
                if (kind.name().equals(name)) {
                    return kind;
                }
            }
            return NONE;
        }
    }

    public RestraintApplier {
        entityId = entityId == null ? Optional.empty() : entityId;
        deviceDimension = deviceDimension == null ? Optional.empty() : deviceDimension;
        devicePos = devicePos == null ? Optional.empty() : devicePos;
        kind = kind == null ? Kind.NONE : kind;
    }

    public static RestraintApplier none() {
        return new RestraintApplier(Kind.NONE, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static RestraintApplier player(UUID id) {
        return new RestraintApplier(Kind.PLAYER, Optional.ofNullable(id), Optional.empty(), Optional.empty());
    }

    public static RestraintApplier npc(UUID id) {
        return new RestraintApplier(Kind.NPC, Optional.ofNullable(id), Optional.empty(), Optional.empty());
    }

    public static RestraintApplier device(ResourceLocation dimension, BlockPos pos) {
        return new RestraintApplier(Kind.DEVICE, Optional.empty(), Optional.ofNullable(dimension),
                Optional.ofNullable(pos));
    }

    public static RestraintApplier system() {
        return new RestraintApplier(Kind.SYSTEM, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", kind.name());
        entityId.ifPresent(id -> tag.putUUID("entity", id));
        deviceDimension.ifPresent(dim -> tag.putString("dim", dim.toString()));
        devicePos.ifPresent(pos -> {
            tag.putInt("x", pos.getX());
            tag.putInt("y", pos.getY());
            tag.putInt("z", pos.getZ());
        });
        return tag;
    }

    /** Never throws: an unreadable applier is an unattributed restraint, not a lost one. */
    public static RestraintApplier load(@Nullable CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return none();
        }
        Optional<UUID> entity = tag.hasUUID("entity") ? Optional.of(tag.getUUID("entity")) : Optional.empty();
        Optional<ResourceLocation> dim = tag.contains("dim")
                ? Optional.ofNullable(ResourceLocation.tryParse(tag.getString("dim")))
                : Optional.empty();
        Optional<BlockPos> pos = tag.contains("x") && tag.contains("y") && tag.contains("z")
                ? Optional.of(new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")))
                : Optional.empty();
        return new RestraintApplier(Kind.parse(tag.getString("kind")), entity, dim, pos);
    }
}

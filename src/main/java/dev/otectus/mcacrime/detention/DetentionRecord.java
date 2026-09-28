package dev.otectus.mcacrime.detention;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * One subject held by one device, at one place.
 *
 * <p>The device's occupancy is authoritative here rather than in the block entity, for the same
 * reason restraints do not live on the entity: a chunk unloads, a block is broken, a world is
 * reloaded, and the question "is this prisoner still in the pillory" has to have an answer that
 * outlives all three. The block entity keeps only enough to draw itself.
 *
 * <p>{@code occupantGeneration} pins the physical-state generation this occupancy belongs to, so a
 * release that arrives after the subject was taken out and put back in does not release the new
 * occupancy.
 *
 * @param id                 this detention's identity
 * @param subject            who is held
 * @param kind               which device
 * @param dimension          where the device is
 * @param devicePos          the device's canonical position (one position per device, not per half)
 * @param occupantGeneration the subject's physical generation at the moment they were put in
 * @param pose               how the subject is drawn while held
 * @param escapeWork         accumulated server-owned escape work
 * @param revision           bumped on every change
 */
public record DetentionRecord(
        UUID id,
        UUID subject,
        DetentionKind kind,
        @Nullable ResourceLocation dimension,
        @Nullable BlockPos devicePos,
        long occupantGeneration,
        String pose,
        int escapeWork,
        long revision) {

    /** How long a pose id may be. Bounded metadata: it is written to disk and sent to clients. */
    public static final int MAX_POSE_LENGTH = 32;

    public DetentionRecord {
        kind = kind == null ? DetentionKind.PILLORY : kind;
        pose = pose == null ? "" : pose.length() > MAX_POSE_LENGTH ? pose.substring(0, MAX_POSE_LENGTH) : pose;
        occupantGeneration = Math.max(1L, occupantGeneration);
        escapeWork = Math.max(0, escapeWork);
        revision = Math.max(0L, revision);
    }

    public static DetentionRecord of(UUID id, UUID subject, DetentionKind kind,
                                     @Nullable ResourceLocation dimension, @Nullable BlockPos devicePos,
                                     long occupantGeneration, String pose) {
        return new DetentionRecord(id, subject, kind, dimension, devicePos, occupantGeneration, pose,
                0, 1L);
    }

    public Optional<BlockPos> device() {
        return Optional.ofNullable(devicePos);
    }

    /** True when this record names a device position to hold the subject at. */
    public boolean valid() {
        return subject != null && devicePos != null && dimension != null;
    }

    /** This detention with {@code amount} more escape work, revision advanced. */
    public DetentionRecord withEscapeWork(int amount) {
        return new DetentionRecord(id, subject, kind, dimension, devicePos, occupantGeneration, pose,
                Math.max(0, amount), revision + 1L);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("subject", subject);
        tag.putString("kind", kind.name());
        if (dimension != null) tag.putString("dim", dimension.toString());
        if (devicePos != null) {
            tag.putInt("dx", devicePos.getX());
            tag.putInt("dy", devicePos.getY());
            tag.putInt("dz", devicePos.getZ());
        }
        tag.putLong("occupantGeneration", occupantGeneration);
        if (!pose.isEmpty()) tag.putString("pose", pose);
        tag.putInt("escapeWork", escapeWork);
        tag.putLong("revision", revision);
        return tag;
    }

    public static Optional<DetentionRecord> load(@Nullable CompoundTag tag) {
        if (tag == null || !tag.hasUUID("id") || !tag.hasUUID("subject")) {
            return Optional.empty();
        }
        BlockPos pos = tag.contains("dx") && tag.contains("dy") && tag.contains("dz")
                ? new BlockPos(tag.getInt("dx"), tag.getInt("dy"), tag.getInt("dz"))
                : null;
        return Optional.of(new DetentionRecord(
                tag.getUUID("id"),
                tag.getUUID("subject"),
                DetentionKind.parse(tag.getString("kind")),
                tag.contains("dim") ? ResourceLocation.tryParse(tag.getString("dim")) : null,
                pos,
                tag.getLong("occupantGeneration"),
                tag.getString("pose"),
                tag.getInt("escapeWork"),
                tag.getLong("revision")));
    }
}

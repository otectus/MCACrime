package dev.otectus.mcacrime.report;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/** Immutable server-authored evidence that an NPC mugging reached its visible-threat phase. */
public record ThreatReceipt(UUID transactionId, UUID victimId, UUID offenderId,
                            @Nullable UUID perceivedSuspectId, String perceivedName, UUID observationId,
                            ResourceLocation dimension, BlockPos location,
                            @Nullable CrimeCommunityKey caseAuthority, long observedAt,
                            long expiresAt, Outcome outcome) {
    public enum Outcome { ACTIVE, ATTEMPTED, COMPLETED }

    public ThreatReceipt {
        if (transactionId == null || victimId == null || offenderId == null || observationId == null
                || dimension == null) throw new IllegalArgumentException("threat receipt identity is required");
        location = location == null ? BlockPos.ZERO : location.immutable();
        perceivedName = perceivedSuspectId == null || perceivedName == null ? ""
                : perceivedName.substring(0, Math.min(64, perceivedName.length()));
        outcome = outcome == null ? Outcome.ACTIVE : outcome;
        expiresAt = Math.max(observedAt, expiresAt);
    }

    public ThreatReceipt finish(Outcome terminal) {
        if (outcome != Outcome.ACTIVE || terminal == null || terminal == Outcome.ACTIVE) return this;
        return new ThreatReceipt(transactionId, victimId, offenderId, perceivedSuspectId, perceivedName, observationId,
                dimension, location, caseAuthority, observedAt, expiresAt, terminal);
    }

    public boolean expired(long now) { return now >= expiresAt; }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("transaction", transactionId);
        tag.putUUID("victim", victimId);
        tag.putUUID("offender", offenderId);
        if (perceivedSuspectId != null) tag.putUUID("suspect", perceivedSuspectId);
        if (!perceivedName.isEmpty()) tag.putString("name", perceivedName);
        tag.putUUID("observation", observationId);
        tag.putString("dimension", dimension.toString());
        tag.put("location", writePos(location));
        if (caseAuthority != null) tag.put("authority", caseAuthority.save());
        tag.putLong("observedAt", observedAt);
        tag.putLong("expiresAt", expiresAt);
        tag.putString("outcome", outcome.name());
        return tag;
    }

    public static ThreatReceipt load(CompoundTag tag) {
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("dimension"));
        if (!tag.hasUUID("transaction") || !tag.hasUUID("victim") || !tag.hasUUID("offender")
                || !tag.hasUUID("observation") || dimension == null) {
            throw new IllegalArgumentException("malformed threat receipt");
        }
        Outcome outcome;
        try { outcome = Outcome.valueOf(tag.getString("outcome")); }
        catch (IllegalArgumentException ignored) { outcome = Outcome.ACTIVE; }
        CrimeCommunityKey authority = tag.contains("authority", Tag.TAG_COMPOUND)
                ? CrimeCommunityKey.load(tag.getCompound("authority")).orElse(null) : null;
        return new ThreatReceipt(tag.getUUID("transaction"), tag.getUUID("victim"), tag.getUUID("offender"),
                tag.hasUUID("suspect") ? tag.getUUID("suspect") : null, tag.getString("name"),
                tag.getUUID("observation"),
                dimension, readPos(tag),
                authority, tag.getLong("observedAt"), tag.getLong("expiresAt"), outcome);
    }

    /**
     * The 1.20.1 compound {@code X/Y/Z} shape, written by hand as {@code CrimeObservation} does.
     *
     * <p>1.21.1's {@code NbtUtils.writeBlockPos} writes an int array instead; keeping the Forge
     * baseline's shape means the receipt table reads the same on both lines.
     */
    private static CompoundTag writePos(BlockPos pos) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("X", pos.getX());
        tag.putInt("Y", pos.getY());
        tag.putInt("Z", pos.getZ());
        return tag;
    }

    /** Reads the compound shape, and the int-array shape 1.21.1's own helper would have written. */
    private static BlockPos readPos(CompoundTag tag) {
        if (tag.contains("location", Tag.TAG_COMPOUND)) {
            CompoundTag pos = tag.getCompound("location");
            return new BlockPos(pos.getInt("X"), pos.getInt("Y"), pos.getInt("Z"));
        }
        return net.minecraft.nbt.NbtUtils.readBlockPos(tag, "location").orElse(BlockPos.ZERO);
    }
}

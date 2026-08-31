package dev.otectus.mcacrime.ledger;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One step in a case's disposition history: who changed it, to what, when, and under which
 * transaction.
 *
 * <p>A case's current {@link Resolution} says where it ended up; this says how it got there. That
 * matters because the same final state means different things — a murder marked {@code PARDONED} by
 * an operator correcting a mis-detection is not the same event as one pardoned as clemency, and a
 * companion mod resolving the linked civic incident needs to tell them apart.
 *
 * <p>{@code revision} is monotonic per record. It is what makes a replayed cross-mod resolution
 * idempotent: the same revision is the same transaction, no matter how many times the outbox
 * retries it.
 */
public record CrimeResolutionEntry(long revision, Resolution resolution, ResourceLocation source,
                                   String dedupeKey, long gameTime, @Nullable UUID actorId,
                                   Map<String, String> context) {

    /** Longest dedupe key accepted. Anything longer is truncated rather than rejected. */
    public static final int MAX_DEDUPE_KEY_LENGTH = 128;
    /** Bounds on the context map, mirroring {@code CrimeRecord}'s. */
    public static final int MAX_CONTEXT_ENTRIES = 8;
    public static final int MAX_CONTEXT_KEY_LENGTH = 64;
    public static final int MAX_CONTEXT_VALUE_LENGTH = 256;

    public CrimeResolutionEntry {
        resolution = resolution == null ? Resolution.UNRESOLVED : resolution;
        dedupeKey = truncate(dedupeKey == null ? "" : dedupeKey, MAX_DEDUPE_KEY_LENGTH);
        context = CrimeContext.bound(context, MAX_CONTEXT_ENTRIES,
                MAX_CONTEXT_KEY_LENGTH, MAX_CONTEXT_VALUE_LENGTH);
        revision = Math.max(0L, revision);
    }

    private static String truncate(String raw, int max) {
        return raw.length() <= max ? raw : raw.substring(0, max);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("revision", revision);
        tag.putString("resolution", resolution.name());
        if (source != null) {
            tag.putString("source", source.toString());
        }
        if (!dedupeKey.isEmpty()) {
            tag.putString("dedupeKey", dedupeKey);
        }
        tag.putLong("gameTime", gameTime);
        if (actorId != null) {
            tag.putUUID("actor", actorId);
        }
        if (!context.isEmpty()) {
            tag.put("context", CrimeContext.save(context));
        }
        return tag;
    }

    /** Absent-key tolerant, in the house style: a partial entry loads with sensible blanks. */
    public static CrimeResolutionEntry load(CompoundTag tag) {
        ResourceLocation source = tag.contains("source")
                ? ResourceLocation.tryParse(tag.getString("source"))
                : null;
        UUID actor = tag.hasUUID("actor") ? tag.getUUID("actor") : null;
        Map<String, String> context = tag.contains("context")
                ? CrimeContext.load(tag.getCompound("context"))
                : new LinkedHashMap<>();
        return new CrimeResolutionEntry(
                tag.getLong("revision"),
                Resolution.parse(tag.getString("resolution")),
                source,
                tag.getString("dedupeKey"),
                tag.getLong("gameTime"),
                actor,
                context);
    }
}

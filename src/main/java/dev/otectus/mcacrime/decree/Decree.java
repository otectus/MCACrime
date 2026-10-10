package dev.otectus.mcacrime.decree;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import net.minecraft.nbt.CompoundTag;

import org.jetbrains.annotations.Nullable;
import java.util.Objects;
import java.util.UUID;

/**
 * One royal decree (0.7.8): a ruler's order that one player is wanted in one village.
 *
 * <p>It is <b>not</b> a crime record. It names no victim, carries no evidence, generates no Heat or
 * Karma and opens no bounty warrant; the case ledger never sees it. What it does is give that village's
 * guards a lawful reason to challenge the target there, with a fine and a custody term as its two ends.
 *
 * <p>Immutable, copy-on-write like {@code CrimeRecord}: every change goes through a {@code with*} method
 * that bumps {@link #revision}, and is written back with {@code CrimeWorldData.putDecree}.
 *
 * @param id            this decree's id
 * @param offender      who is wanted
 * @param offenderName  their name when the decree was issued, for lists while they are offline
 * @param community     the village they are wanted in
 * @param capitalId     the Capitals capital that issued it; a different capital in that village later
 *                      does not inherit it
 * @param issuedBy      the ruler who issued it
 * @param issuedByName  their name at the time
 * @param reason        the Crown's stated reason
 * @param issuedAt      overworld game time of issue
 * @param expiresAt     overworld game time at which it lapses, unless a running sentence holds it
 * @param state         where it is in its life
 * @param resolvedAt    when it ended, or 0
 * @param resolvedBy    who ended it, or null for the system
 * @param note          a short machine-readable note on how it ended ({@code target_ennobled} ...)
 * @param provenance    how it was issued
 * @param sentenceId    the custody sentence answering it, while one is running
 * @param revision      bumped on every change
 */
public record Decree(UUID id, UUID offender, String offenderName, CrimeCommunityKey community, UUID capitalId,
                     UUID issuedBy, String issuedByName, DecreeReason reason, long issuedAt, long expiresAt,
                     DecreeState state, long resolvedAt, @Nullable UUID resolvedBy, String note,
                     DecreeProvenance provenance, @Nullable UUID sentenceId, long revision) {

    /** Bounds on the free-text fields. Player names are at most 16 characters. */
    public static final int MAX_NAME_LENGTH = 32;
    public static final int MAX_NOTE_LENGTH = 64;

    public Decree {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(offender, "offender");
        Objects.requireNonNull(community, "community");
        Objects.requireNonNull(capitalId, "capitalId");
        Objects.requireNonNull(issuedBy, "issuedBy");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(provenance, "provenance");
        offenderName = bounded(offenderName, MAX_NAME_LENGTH);
        issuedByName = bounded(issuedByName, MAX_NAME_LENGTH);
        note = bounded(note, MAX_NOTE_LENGTH);
        revision = Math.max(0L, revision);
    }

    private static String bounded(@Nullable String value, int limit) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), limit));
        value.codePoints().filter(cp -> !Character.isISOControl(cp)).limit(limit).forEach(out::appendCodePoint);
        return out.toString();
    }

    /** A new open decree. */
    public static Decree issue(UUID id, UUID offender, String offenderName, CrimeCommunityKey community,
                               UUID capitalId, UUID issuedBy, String issuedByName, DecreeReason reason,
                               long now, long durationTicks, DecreeProvenance provenance) {
        long expires = dev.otectus.mcacrime.util.SafeMath.addSat(now, Math.max(1L, durationTicks));
        return new Decree(id, offender, offenderName, community, capitalId, issuedBy, issuedByName, reason,
                now, expires, DecreeState.OPEN, 0L, null, "", provenance, null, 1L);
    }

    public boolean open() {
        return state == DecreeState.OPEN;
    }

    /** Whether its time is up. A decree a running sentence answers does not lapse under it. */
    public boolean expired(long now) {
        return open() && sentenceId == null && now >= expiresAt;
    }

    /** Game ticks left, never negative. */
    public long remaining(long now) {
        return Math.max(0L, expiresAt - now);
    }

    /**
     * This decree, ended, with a short note saying how ({@code withdrawn}, {@code paid},
     * {@code served:sentence_served}, {@code target_ennobled} ...). Ending one that has already ended is
     * refused by the service, not here.
     */
    public Decree ended(DecreeState next, long now, @Nullable UUID by, String why) {
        return new Decree(id, offender, offenderName, community, capitalId, issuedBy, issuedByName, reason,
                issuedAt, expiresAt, next, now, by, why, provenance, null, revision + 1L);
    }

    /** This decree, held by a custody sentence. */
    public Decree bound(UUID sentence) {
        return new Decree(id, offender, offenderName, community, capitalId, issuedBy, issuedByName, reason,
                issuedAt, expiresAt, state, resolvedAt, resolvedBy, note, provenance, sentence, revision + 1L);
    }

    /** This decree, released from a sentence that ended without answering it. */
    public Decree unbound() {
        return new Decree(id, offender, offenderName, community, capitalId, issuedBy, issuedByName, reason,
                issuedAt, expiresAt, state, resolvedAt, resolvedBy, note, provenance, null, revision + 1L);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("offender", offender);
        tag.putString("offenderName", offenderName);
        tag.put("community", community.save());
        tag.putUUID("capital", capitalId);
        tag.putUUID("issuedBy", issuedBy);
        tag.putString("issuedByName", issuedByName);
        tag.putString("reason", reason.id());
        tag.putLong("issuedAt", issuedAt);
        tag.putLong("expiresAt", expiresAt);
        tag.putString("state", state.id());
        tag.putLong("resolvedAt", resolvedAt);
        if (resolvedBy != null) {
            tag.putUUID("resolvedBy", resolvedBy);
        }
        tag.putString("note", note);
        tag.putString("provenance", provenance.id());
        if (sentenceId != null) {
            tag.putUUID("sentence", sentenceId);
        }
        tag.putLong("revision", revision);
        return tag;
    }

    /** A decree read back, or null for a malformed row, which the store quarantines rather than guesses at. */
    @Nullable
    public static Decree load(CompoundTag tag) {
        try {
            if (tag == null || !tag.hasUUID("id") || !tag.hasUUID("offender") || !tag.hasUUID("capital")
                    || !tag.hasUUID("issuedBy")) {
                return null;
            }
            CrimeCommunityKey community = CrimeCommunityKey.load(tag.getCompound("community")).orElse(null);
            DecreeReason reason = DecreeReason.parse(tag.getString("reason")).orElse(null);
            DecreeState state = DecreeState.parse(tag.getString("state")).orElse(null);
            DecreeProvenance provenance = DecreeProvenance.parse(tag.getString("provenance"))
                    .orElse(DecreeProvenance.SYSTEM);
            if (community == null || reason == null || state == null) {
                return null;
            }
            return new Decree(tag.getUUID("id"), tag.getUUID("offender"), tag.getString("offenderName"), community,
                    tag.getUUID("capital"), tag.getUUID("issuedBy"), tag.getString("issuedByName"), reason,
                    tag.getLong("issuedAt"), tag.getLong("expiresAt"), state, tag.getLong("resolvedAt"),
                    tag.hasUUID("resolvedBy") ? tag.getUUID("resolvedBy") : null, tag.getString("note"),
                    provenance, tag.hasUUID("sentence") ? tag.getUUID("sentence") : null, tag.getLong("revision"));
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}

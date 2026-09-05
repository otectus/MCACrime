package dev.otectus.mcacrime.ledger;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One offender's open warrant, and the thing a bounty claim is keyed on (0.5.1).
 *
 * <p>Warrant identity did not exist before this release, and a bounty cannot be paid safely without
 * it. {@code CrimeRecord.resolutionRevision} is per <em>case</em>: two offences produce two records
 * with independent revisions, so neither can answer "is this the same outstanding wanted state I was
 * paid for last time". A warrant is per offender, opens when they become Wanted, takes a revision
 * bump for each qualifying crime committed while it is open, and closes when they stop being Wanted.
 * A claim key of {@code (target, warrantId, revision)} is then unforgeable by dying and re-offending:
 * a new offence produces a new revision, and the old key stays claimed forever.
 *
 * <p>{@code recordIds} is capped at {@link #MAX_RECORD_IDS}. A career criminal's warrant would
 * otherwise grow without bound in a file that is written on every autosave, and the ids past the cap
 * are already in the ledger — this list is a convenience index, not the record of record.
 */
public record Warrant(UUID id, UUID offender, long revision, long openedAt, long lastRevisedAt,
                      ResourceLocation topOffense, List<UUID> recordIds, boolean open, long closedAt) {

    /** Ceiling on the linked-record index. Oldest entries are dropped first. */
    public static final int MAX_RECORD_IDS = 64;

    public Warrant {
        recordIds = List.copyOf(recordIds);
    }

    /** A fresh warrant opened now for one offence. */
    public static Warrant open(UUID id, UUID offender, ResourceLocation topOffense, UUID recordId, long now) {
        return new Warrant(id, offender, 1L, now, now, topOffense,
                recordId == null ? List.of() : List.of(recordId), true, 0L);
    }

    /**
     * A new revision of this warrant covering one more offence. The revision bump is what invalidates
     * any bounty claim already paid against the previous state.
     */
    public Warrant revised(UUID recordId, ResourceLocation offense, long now) {
        List<UUID> ids = new ArrayList<>(recordIds);
        if (recordId != null && !ids.contains(recordId)) {
            ids.add(recordId);
        }
        while (ids.size() > MAX_RECORD_IDS) {
            ids.remove(0);
        }
        return new Warrant(id, offender, revision + 1L, openedAt, now,
                offense == null ? topOffense : offense, ids, open, closedAt);
    }

    /** This warrant, closed. Kept rather than deleted so a stale claim key still resolves. */
    public Warrant closed(long now) {
        return new Warrant(id, offender, revision, openedAt, lastRevisedAt, topOffense, recordIds, false, now);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("offender", offender);
        tag.putLong("revision", revision);
        tag.putLong("openedAt", openedAt);
        tag.putLong("lastRevisedAt", lastRevisedAt);
        if (topOffense != null) {
            tag.putString("topOffense", topOffense.toString());
        }
        ListTag records = new ListTag();
        for (UUID recordId : recordIds) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", recordId);
            records.add(entry);
        }
        tag.put("records", records);
        tag.putBoolean("open", open);
        tag.putLong("closedAt", closedAt);
        return tag;
    }

    /** Throws on a tag with no warrant or offender id; the caller skips that one entry. */
    public static Warrant load(CompoundTag tag) {
        UUID id = tag.getUUID("id");
        UUID offender = tag.getUUID("offender");
        ResourceLocation offense = tag.contains("topOffense")
                ? ResourceLocation.tryParse(tag.getString("topOffense"))
                : null;
        List<UUID> records = new ArrayList<>();
        ListTag list = tag.getList("records", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && records.size() < MAX_RECORD_IDS; i++) {
            CompoundTag entry = list.getCompound(i);
            if (entry.hasUUID("id")) {
                records.add(entry.getUUID("id"));
            }
        }
        return new Warrant(id, offender, tag.getLong("revision"), tag.getLong("openedAt"),
                tag.getLong("lastRevisedAt"), offense, records, tag.getBoolean("open"), tag.getLong("closedAt"));
    }
}

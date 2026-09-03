package dev.otectus.mcacrime.ledger;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.api.model.CrimeRecordView;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * One ledger entry for a serious crime (spec §2.2) — the backbone for bounties, repeat-offender
 * scaling, NPC memory, and admin debugging. Persisted in {@code mcacrime.dat} via
 * {@code CrimeWorldData} so a record outlives Karma recovery until explicitly resolved. NBT save/load
 * follows the project house style (absent-key tolerant).
 *
 * <p><b>This stays an immutable record</b> even though cases now have a lifecycle. Three reasons:
 * {@code CrimeLedger.forOffender} hands these out to callers, and a mutable one would be an escaping
 * reference into the store; value equality is what the round-trip tests assert and what makes replay
 * detection trivial; and a case changes state once or twice in its life, so copy-on-write costs
 * nothing. The mutable classes in this mod ({@code CustodyRecord}, {@code JailState},
 * {@code RansomState}) are the ones that tick every tick — a ledger fact is not one of them.
 * Lifecycle changes go through the {@code with*} methods and {@code CrimeWorldData.replaceRecord}.
 *
 * <h2>Two village fields, on purpose</h2>
 *
 * <p>{@code villageId} (the legacy bare integer) and {@code community} (dimension-aware) are both
 * written. The bare id is ambiguous — MCA allocates village ids per dimension — so {@code community}
 * is the one to read. The legacy field is still emitted for one release so a player who rolls back to
 * an older jar does not lose the village on every record.
 */
public record CrimeRecord(UUID id, UUID offender, @Nullable UUID victim, ResourceLocation type,
                          OptionalInt villageId, @Nullable CrimeCommunityKey community,
                          boolean witnessed, Set<UUID> witnessIds,
                          long timeCommitted, long heatGenerated, long karmaDelta,
                          long fineAmount, long jailTicks,
                          Resolution resolution, long resolutionRevision,
                          List<CrimeResolutionEntry> resolutionHistory,
                          @Nullable UUID linkedReputationIncidentId,
                          Map<String, String> context) {

    /** How many resolution steps are kept. A case that moved eight times has bigger problems. */
    public static final int MAX_RESOLUTION_HISTORY = 8;
    /** Bounds on the context map. */
    public static final int MAX_CONTEXT_ENTRIES = 12;
    public static final int MAX_CONTEXT_KEY_LENGTH = 64;
    public static final int MAX_CONTEXT_VALUE_LENGTH = 256;
    /** Hard ceiling on stored witness identities, independent of the config cap. */
    public static final int MAX_WITNESS_IDS = 64;

    public CrimeRecord {
        villageId = villageId == null ? OptionalInt.empty() : villageId;
        resolution = resolution == null ? Resolution.UNRESOLVED : resolution;
        resolutionRevision = Math.max(0L, resolutionRevision);
        witnessIds = boundedWitnesses(witnessIds);
        resolutionHistory = boundedHistory(resolutionHistory);
        context = CrimeContext.bound(context, MAX_CONTEXT_ENTRIES,
                MAX_CONTEXT_KEY_LENGTH, MAX_CONTEXT_VALUE_LENGTH);
    }

    /**
     * The pre-lifecycle constructor, kept so existing call sites and tests compile unchanged. The
     * arity differs from the canonical one, so the two never collide.
     */
    public CrimeRecord(UUID id, UUID offender, @Nullable UUID victim, ResourceLocation type,
                       OptionalInt villageId, boolean witnessed, long timeCommitted,
                       long heatGenerated, long karmaDelta, long fineAmount, long jailTicks,
                       Resolution resolution) {
        this(id, offender, victim, type, villageId, null, witnessed, Set.of(), timeCommitted,
                heatGenerated, karmaDelta, fineAmount, jailTicks, resolution, 0L, List.of(), null,
                Map.of());
    }

    private static Set<UUID> boundedWitnesses(Set<UUID> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        // TreeSet: deterministic ascending order, so the same crime always serialises identically.
        Set<UUID> sorted = new TreeSet<>(raw);
        if (sorted.size() <= MAX_WITNESS_IDS) {
            return Set.copyOf(sorted);
        }
        Set<UUID> trimmed = new TreeSet<>();
        for (UUID uuid : sorted) {
            if (trimmed.size() == MAX_WITNESS_IDS) {
                break;
            }
            trimmed.add(uuid);
        }
        return Set.copyOf(trimmed);
    }

    private static List<CrimeResolutionEntry> boundedHistory(List<CrimeResolutionEntry> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        // Keep the most recent steps: the tail is what a resolution replay needs to recognise.
        return raw.size() <= MAX_RESOLUTION_HISTORY
                ? List.copyOf(raw)
                : List.copyOf(raw.subList(raw.size() - MAX_RESOLUTION_HISTORY, raw.size()));
    }

    // ------------------------------------------------------------------ derived

    public Optional<CrimeCommunityKey> communityKey() {
        return Optional.ofNullable(community);
    }

    public Optional<UUID> reputationIncident() {
        return Optional.ofNullable(linkedReputationIncidentId);
    }

    /** Still legally actionable — open, or escaped from (which is not forgiveness). */
    public boolean actionable() {
        return CaseTransitions.isActionable(resolution);
    }

    /** The immutable public projection. Never hand a {@code CrimeRecord} across the API boundary. */
    public CrimeRecordView view() {
        return new CrimeRecordView(id, offender, Optional.ofNullable(victim), type,
                Optional.ofNullable(community), witnessIds, witnessed, timeCommitted,
                heatGenerated, karmaDelta, fineAmount, jailTicks, resolution, resolutionRevision,
                Optional.ofNullable(linkedReputationIncidentId), context);
    }

    // ------------------------------------------------------------------ withers

    /** A copy at the next revision, with {@code entry} appended to the history. */
    public CrimeRecord withResolution(Resolution next, CrimeResolutionEntry entry) {
        List<CrimeResolutionEntry> history = new ArrayList<>(resolutionHistory);
        history.add(entry);
        return new CrimeRecord(id, offender, victim, type, villageId, community, witnessed, witnessIds,
                timeCommitted, heatGenerated, karmaDelta, fineAmount, jailTicks, next,
                resolutionRevision + 1L, history, linkedReputationIncidentId, context);
    }

    /** A copy carrying the civic incident this case produced in a companion mod. */
    public CrimeRecord withReputationIncident(@Nullable UUID incidentId) {
        return new CrimeRecord(id, offender, victim, type, villageId, community, witnessed, witnessIds,
                timeCommitted, heatGenerated, karmaDelta, fineAmount, jailTicks, resolution,
                resolutionRevision, resolutionHistory, incidentId, context);
    }

    /** A copy with the assessed fine and sentence filled in. */
    public CrimeRecord withPenalty(long newFineAmount, long newJailTicks) {
        return new CrimeRecord(id, offender, victim, type, villageId, community, witnessed, witnessIds,
                timeCommitted, heatGenerated, karmaDelta, newFineAmount, newJailTicks, resolution,
                resolutionRevision, resolutionHistory, linkedReputationIncidentId, context);
    }

    /** A copy with one context entry added or replaced. */
    public CrimeRecord withContext(String key, String value) {
        Map<String, String> merged = new LinkedHashMap<>(context);
        merged.put(key, value);
        return new CrimeRecord(id, offender, victim, type, villageId, community, witnessed, witnessIds,
                timeCommitted, heatGenerated, karmaDelta, fineAmount, jailTicks, resolution,
                resolutionRevision, resolutionHistory, linkedReputationIncidentId, merged);
    }

    /** A copy under a different record id, used only by the duplicate-id repair on load. */
    public CrimeRecord withId(UUID newId) {
        return new CrimeRecord(newId, offender, victim, type, villageId, community, witnessed, witnessIds,
                timeCommitted, heatGenerated, karmaDelta, fineAmount, jailTicks, resolution,
                resolutionRevision, resolutionHistory, linkedReputationIncidentId, context);
    }

    // ------------------------------------------------------------------ persistence

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("offender", offender);
        if (victim != null) {
            tag.putUUID("victim", victim);
        }
        tag.putString("type", type.toString());
        villageId.ifPresent(v -> tag.putInt("villageId", v));
        if (community != null) {
            tag.put("community", community.save());
        }
        tag.putBoolean("witnessed", witnessed);
        if (!witnessIds.isEmpty()) {
            ListTag witnesses = new ListTag();
            // Already TreeSet-ordered by the constructor, so this list is stable across saves.
            witnessIds.forEach(uuid -> witnesses.add(NbtUtils.createUUID(uuid)));
            tag.put("witnesses", witnesses);
        }
        tag.putLong("timeCommitted", timeCommitted);
        tag.putLong("heatGenerated", heatGenerated);
        tag.putLong("karmaDelta", karmaDelta);
        tag.putLong("fineAmount", fineAmount);
        tag.putLong("jailTicks", jailTicks);
        tag.putString("resolution", resolution.name());
        if (resolutionRevision != 0L) {
            tag.putLong("resolutionRevision", resolutionRevision);
        }
        if (!resolutionHistory.isEmpty()) {
            ListTag history = new ListTag();
            resolutionHistory.forEach(entry -> history.add(entry.save()));
            tag.put("resolutionHistory", history);
        }
        if (linkedReputationIncidentId != null) {
            tag.putUUID("repIncident", linkedReputationIncidentId);
        }
        if (!context.isEmpty()) {
            tag.put("context", CrimeContext.save(context));
        }
        return tag;
    }

    public static CrimeRecord load(CompoundTag tag) {
        UUID victim = tag.hasUUID("victim") ? tag.getUUID("victim") : null;
        OptionalInt villageId = tag.contains("villageId")
                ? OptionalInt.of(tag.getInt("villageId"))
                : OptionalInt.empty();
        ResourceLocation type = ResourceLocation.tryParse(tag.getString("type"));
        CrimeCommunityKey community = tag.contains("community", Tag.TAG_COMPOUND)
                ? CrimeCommunityKey.load(tag.getCompound("community")).orElse(null)
                : null;

        Set<UUID> witnesses = new TreeSet<>();
        if (tag.contains("witnesses", Tag.TAG_LIST)) {
            ListTag list = tag.getList("witnesses", Tag.TAG_INT_ARRAY);
            for (int i = 0; i < list.size(); i++) {
                try {
                    witnesses.add(NbtUtils.loadUUID(list.get(i)));
                } catch (RuntimeException e) {
                    // Skip one malformed witness rather than dropping the record it belongs to.
                }
            }
        }

        List<CrimeResolutionEntry> history = new ArrayList<>();
        if (tag.contains("resolutionHistory", Tag.TAG_LIST)) {
            ListTag list = tag.getList("resolutionHistory", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                try {
                    history.add(CrimeResolutionEntry.load(list.getCompound(i)));
                } catch (RuntimeException e) {
                    // Same rule: one bad step must not cost the whole case.
                }
            }
        }

        UUID incident = tag.hasUUID("repIncident") ? tag.getUUID("repIncident") : null;
        Map<String, String> context = tag.contains("context", Tag.TAG_COMPOUND)
                ? CrimeContext.load(tag.getCompound("context"))
                : Map.of();

        return new CrimeRecord(
                tag.getUUID("id"),
                tag.getUUID("offender"),
                victim,
                type,
                villageId,
                community,
                tag.getBoolean("witnessed"),
                witnesses,
                tag.getLong("timeCommitted"),
                tag.getLong("heatGenerated"),
                tag.getLong("karmaDelta"),
                tag.getLong("fineAmount"),
                tag.getLong("jailTicks"),
                Resolution.parse(tag.getString("resolution")),
                tag.getLong("resolutionRevision"),
                history,
                incident,
                context);
    }
}

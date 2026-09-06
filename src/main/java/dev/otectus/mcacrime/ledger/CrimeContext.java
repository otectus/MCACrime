package dev.otectus.mcacrime.ledger;

import net.minecraft.nbt.CompoundTag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The bounded string→string context map carried by a crime record and by each resolution step.
 *
 * <p>This is deliberately small and deliberately dull. It exists to hold a handful of stable facts a
 * later UI or companion mod would otherwise have to reconstruct — the victim's name at the time, the
 * detection path, the linked sentence id. It is <b>not</b> a place to serialise entity NBT, chat
 * text, player-supplied strings, or display components: those grow without limit, carry data the
 * player never consented to storing, and turn a save file into an audit liability.
 *
 * <p>Every bound is enforced here rather than at each call site, and overflow truncates rather than
 * throwing. A context entry is never worth failing a crime commit over.
 */
public final class CrimeContext {

    // --- allowlisted keys (spec §6.8). Anything outside this list is a design smell, not an error. ---

    /** The victim's display name as it read at the time, sanitised. */
    public static final String VICTIM_NAME = "victim_name";
    /** villager / guard / child — what the victim was, for later phrasing. */
    public static final String VICTIM_ROLE = "victim_role";
    /** direct / custody / jailbreak / command — how the crime was detected. */
    public static final String DETECTION = "detection";
    /** The real crowd size when the stored witness set was capped. */
    public static final String WITNESS_COUNT_TOTAL = "witness_count_total";
    /** Linked lifecycle ids, when the case has them. */
    public static final String SENTENCE_ID = "sentence_id";
    public static final String CUSTODY_ID = "custody_id";
    public static final String RANSOM_ID = "ransom_id";
    /** Stamped by the schema migration when a legacy village id was assumed to be overworld. */
    public static final String LEGACY_MIGRATION = "legacy_migration";
    /** Stamped when a migrated record was witnessed but its witness identities were never stored. */
    public static final String LEGACY_WITNESS_IDENTITY_MISSING = "legacy_witness_identity_missing";
    /**
     * Stamped on a case bound to a sentence by inference rather than by the jailing that created it,
     * with the tick the inference ran as its value. A case carrying it was never charged under that
     * sentence by the code that jailed the player — it was assumed into it, once, and saying so in
     * the record is the difference between a fact and a guess nobody can later tell apart.
     */
    public static final String LEGACY_SENTENCE_INFERRED = "legacy_sentence_inferred";
    /** Stamped when a duplicate record id was repaired on load; the value is the original id. */
    public static final String DUPLICATE_ID_REPAIRED = "duplicate_id_repaired";
    /** {@code npc} / {@code player} — what kind of offender the record names (0.5.1). */
    public static final String OFFENDER_KIND = "offender_kind";
    /** The encoded {@link CrimeFlag} set. The same string as {@link CrimeFlag#CONTEXT_KEY}. */
    public static final String FLAGS = CrimeFlag.CONTEXT_KEY;
    /** The fine transaction that settled this case. */
    public static final String FINE_TRANSACTION = "fine_txn";

    private CrimeContext() {
    }

    /**
     * A bounded, insertion-ordered, immutable copy. Entries beyond {@code maxEntries} are dropped and
     * over-long keys/values are truncated — never an exception, because losing a context note must
     * never cost the record it describes.
     */
    public static Map<String, String> bound(Map<String, String> raw, int maxEntries,
                                            int maxKeyLength, int maxValueLength) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            if (out.size() >= maxEntries) {
                break;
            }
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            out.put(truncate(entry.getKey(), maxKeyLength), truncate(entry.getValue(), maxValueLength));
        }
        return Map.copyOf(out);
    }

    public static CompoundTag save(Map<String, String> context) {
        CompoundTag tag = new CompoundTag();
        context.forEach(tag::putString);
        return tag;
    }

    /** Reads a context compound, skipping any non-string value rather than failing the load. */
    public static Map<String, String> load(CompoundTag tag) {
        Map<String, String> out = new LinkedHashMap<>();
        if (tag == null) {
            return out;
        }
        for (String key : tag.getAllKeys()) {
            String value = tag.getString(key);
            if (!value.isEmpty()) {
                out.put(key, value);
            }
        }
        return out;
    }

    private static String truncate(String raw, int max) {
        return raw.length() <= max ? raw : raw.substring(0, max);
    }
}

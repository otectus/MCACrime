package dev.otectus.mcacrime.state.world;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.ledger.CrimeContext;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * Steps a saved {@code mcacrime.dat} up to the current schema, as pure tag-to-tag work.
 *
 * <h2>Why this is a separate class</h2>
 *
 * <p>{@code CrimeWorldData.load} is static and reached from {@code computeIfAbsent}, so migration
 * cannot ask for a {@code MinecraftServer} even if it wanted to. Keeping it here, operating only on
 * {@link CompoundTag}, means the whole upgrade path is exercisable in a unit test with no server, no
 * {@code SavedData}, and no config — which matters, because the one thing that must never fail is the
 * path that runs on somebody's five-year-old world exactly once.
 *
 * <h2>Schema versus the reserved passthrough</h2>
 *
 * <p>These are two different mechanisms and they must stay separate. {@code schema} is
 * <b>backward</b> compatibility: an old file read by a new jar, upgraded in memory. The
 * {@code reserved} tag in {@code CrimeWorldData} is <b>forward</b> compatibility: a new file read by
 * an old jar, whose unrecognised parts are handed back untouched. A missing {@code schema} key reads
 * as 0, so every world written before this release migrates without any special case.
 *
 * <h2>What migration is not allowed to do</h2>
 *
 * <p>It never invents information. A legacy record knows a village integer but not a dimension, so it
 * is interpreted as the overworld — the only dimension the old key could have meant in practice — and
 * stamped as an assumption rather than presented as fact. A legacy record knows it was witnessed but
 * not by whom, so its witness set stays empty and is flagged; fabricating nearby villagers would
 * hand strangers knowledge of a crime they never saw.
 */
public final class CrimeDataMigrations {

    /** The schema 0.5.0 wrote: everything up to and including the holding-cell roster. */
    public static final int SCHEMA_0_5_0 = 6;

    /**
     * The schema 0.5.1 writes. It adds six collections -- criminal villagers, stolen goods, warrants,
     * bounty claims, bounty contracts and fence restock stamps -- and nothing else. Every one of them
     * defaults to empty, which is why {@link #v6to7} writes no lists: absent already reads as empty
     * everywhere, and emitting six empty lists into every existing world would grow the file to say
     * nothing.
     */
    public static final int SCHEMA_0_5_1 = 7;

    /**
     * The schema 0.6.0 writes. Like {@link #SCHEMA_0_5_1} it adds only optional fields and empty
     * collections, so {@link #v7to8} writes nothing at all; what the number buys is that every field
     * added across this release is behind one version rather than eight half-versions.
     */
    public static final int SCHEMA_0_6_0 = 8;

    /** The expanded 0.6.0 witness/memory schema; schema 8 remains a supported development save. */
    public static final int SCHEMA_WITNESS_MEMORY = 9;
    /** Queued bounty delivery and durable operator decisions; old builds must not erase the audit trail. */
    public static final int SCHEMA_RECONCILIATION = 10;
    public static final int CURRENT_SCHEMA = SCHEMA_RECONCILIATION;

    /** Root NBT key holding the schema integer. Absent means 0. */
    public static final String TAG_SCHEMA = "schema";

    /** The dimension a pre-schema village id is assumed to have belonged to. */
    public static final String ASSUMED_DIMENSION = "minecraft:overworld";

    private CrimeDataMigrations() {
    }

    /** The schema a saved tag claims. Absent key means the original, unversioned format. */
    public static int schemaOf(CompoundTag tag) {
        return tag == null ? CURRENT_SCHEMA : tag.getInt(TAG_SCHEMA);
    }

    /**
     * Runs every step needed to bring {@code tag} up to {@link #CURRENT_SCHEMA}, in order, and stamps
     * the result. A tag already at or beyond the current schema is returned untouched — the
     * from-the-future case is handled by the caller, not here.
     */
    public static CompoundTag migrate(CompoundTag tag) {
        if (tag == null) {
            return new CompoundTag();
        }
        int schema = schemaOf(tag);
        if (schema >= CURRENT_SCHEMA) {
            return tag;
        }
        CompoundTag working = tag.copy();
        if (schema < 1) {
            working = v0to1(working);
        }
        if (schema < 2) {
            working = v1to2(working);
        }
        if (schema < 3) {
            working = v2to3(working);
        }
        if (schema < 4) {
            working = v3to4(working);
        }
        if (schema < 5) {
            working = v4to5(working);
        }
        if (schema < 6) {
            working = v5to6(working);
        }
        if (schema < 7) {
            working = v6to7(working);
        }
        if (schema < 8) {
            working = v7to8(working);
        }
        working.putInt(TAG_SCHEMA, CURRENT_SCHEMA);
        return working;
    }

    // ------------------------------------------------------------------ 0 -> 1

    /**
     * Dimension-aware community identity. Every legacy village integer becomes
     * {@code minecraft:overworld/<id>}, on both crime records and the fallback standing store.
     *
     * <p>Two ids that merely happen to match are never merged after this point — that is the whole
     * reason the key exists. And the dimension is never guessed from where a player is standing at
     * load time; that would produce a different answer depending on who logged in first.
     */
    public static CompoundTag v0to1(CompoundTag tag) {
        CompoundTag out = tag.copy();
        int migratedRecords = 0;

        ListTag ledger = out.getList("ledger", Tag.TAG_COMPOUND);
        for (int i = 0; i < ledger.size(); i++) {
            CompoundTag record = ledger.getCompound(i);
            if (record.contains("community", Tag.TAG_COMPOUND) || !record.contains("villageId")) {
                continue;
            }
            int villageId = record.getInt("villageId");
            if (villageId < 0) {
                continue; // an impossible id cannot be given a community
            }
            record.put("community", overworld(villageId).save());
            stampContext(record, CrimeContext.LEGACY_MIGRATION, "assumed_overworld");
            migratedRecords++;
        }

        CompoundTag villages = out.getCompound("villageReputation");
        CompoundTag rekeyed = new CompoundTag();
        int migratedCommunities = 0;
        for (String key : villages.getAllKeys()) {
            CompoundTag perPlayer = villages.getCompound(key);
            String newKey = key;
            if (CrimeCommunityKey.tryParse(key).isEmpty()) {
                try {
                    int villageId = Integer.parseInt(key);
                    if (villageId < 0) {
                        continue; // drop an impossible legacy id rather than carry it forward
                    }
                    newKey = overworld(villageId).asString();
                    migratedCommunities++;
                } catch (NumberFormatException e) {
                    continue; // not an integer and not a key: it was already malformed
                }
            }
            rekeyed.put(newKey, perPlayer);
        }
        out.put("villageReputation", rekeyed);

        if (migratedRecords > 0 || migratedCommunities > 0) {
            // One aggregate line, not one per record: a long-running world can hold thousands.
            McaCrime.LOGGER.warn("MCA: Crime data migrated to dimension-aware communities: {} crime record(s) "
                            + "and {} village standing entr(ies) had no dimension and were assumed to be {}. "
                            + "Records committed in other dimensions before this version cannot be distinguished.",
                    migratedRecords, migratedCommunities, ASSUMED_DIMENSION);
        }
        out.putInt(TAG_SCHEMA, 1);
        return out;
    }

    // ------------------------------------------------------------------ 1 -> 2

    /**
     * Case lifecycle fields. Existing dispositions are preserved exactly; only the machinery around
     * them is initialised, so nothing a player already earned or owes changes.
     */
    public static CompoundTag v1to2(CompoundTag tag) {
        CompoundTag out = tag.copy();
        int witnessedWithoutIdentities = 0;

        ListTag ledger = out.getList("ledger", Tag.TAG_COMPOUND);
        for (int i = 0; i < ledger.size(); i++) {
            CompoundTag record = ledger.getCompound(i);
            if (!record.contains("resolutionRevision")) {
                record.putLong("resolutionRevision", 0L);
            }
            // No `witnesses` list and no `resolutionHistory` list is written: absent already means
            // empty everywhere that reads them, and writing empties would only grow the file.
            if (record.getBoolean("witnessed") && !record.contains("witnesses", Tag.TAG_LIST)) {
                stampContext(record, CrimeContext.LEGACY_WITNESS_IDENTITY_MISSING, "true");
                witnessedWithoutIdentities++;
            }
        }

        if (witnessedWithoutIdentities > 0) {
            McaCrime.LOGGER.warn("MCA: Crime migrated {} witnessed crime record(s) that predate witness identities. "
                            + "They stay marked as witnessed, but with no witnesses named — villagers will not be "
                            + "given knowledge of crimes they were never recorded as having seen.",
                    witnessedWithoutIdentities);
        }
        out.putInt(TAG_SCHEMA, 2);
        return out;
    }

    // ------------------------------------------------------------------ 2 -> 3

    /**
     * The integration outbox and dedupe store. Both start empty; a world that existed before any
     * companion mod has, by definition, nothing pending.
     */
    public static CompoundTag v2to3(CompoundTag tag) {
        CompoundTag out = tag.copy();
        if (!out.contains("outbox", Tag.TAG_LIST)) {
            out.put("outbox", new ListTag());
        }
        if (!out.contains("deadLetters", Tag.TAG_LIST)) {
            out.put("deadLetters", new ListTag());
        }
        if (!out.contains("dedupe", Tag.TAG_COMPOUND)) {
            out.put("dedupe", new CompoundTag());
        }
        out.putInt(TAG_SCHEMA, 3);
        return out;
    }

    /** Adds the finite action economy, villager profiles, and transaction receipts. */
    public static CompoundTag v3to4(CompoundTag tag) {
        CompoundTag out = tag.copy();
        if (!out.contains("villagerProfiles", Tag.TAG_COMPOUND)) out.put("villagerProfiles", new CompoundTag());
        if (!out.contains("actionCounters", Tag.TAG_COMPOUND)) out.put("actionCounters", new CompoundTag());
        if (!out.contains("villageTreasuries", Tag.TAG_COMPOUND)) out.put("villageTreasuries", new CompoundTag());
        if (!out.contains("transactionReceipts", Tag.TAG_LIST)) out.put("transactionReceipts", new ListTag());
        out.putInt(TAG_SCHEMA, 4);
        return out;
    }

    /**
     * Adds the observation and report sections (§12). Both start empty, and that is the honest answer
     * rather than a shortcut.
     *
     * <p>It is tempting to synthesise observations from the witness identities already stored on old
     * crime records — the identities are right there, and the offender, the crime type and the time
     * are all known. The reason not to is that an observation asserts more than a witness id does. It
     * claims a role, a confidence, whether the observer saw the actor or only heard the act, and a
     * place. None of that was ever recorded, so every one of those fields would have to be invented,
     * and the invented values would then drive AI behaviour and dialogue as if they were observed
     * fact. The witness sets stay exactly where they are and stay readable; what they do not do is
     * grow into evidence nobody ever gathered.
     */
    public static CompoundTag v4to5(CompoundTag tag) {
        CompoundTag out = tag.copy();
        if (!out.contains("observations", Tag.TAG_LIST)) {
            out.put("observations", new ListTag());
        }
        if (!out.contains("reports", Tag.TAG_LIST)) {
            out.put("reports", new ListTag());
        }
        out.putInt(TAG_SCHEMA, 5);
        return out;
    }

    /**
     * Adds the holding-cell roster. It starts empty, and that is the only correct answer.
     *
     * <p>It is tempting to seed it from the existing {@code jailRoster}: those are jails, they have
     * positions, and a release could then tidy them up. The reason not to is that a cell record asserts
     * something a jail anchor never did — that <em>this mod</em> placed every block inside that box and
     * therefore owns the right to delete them. Every anchor in an existing world was pointed at a
     * structure a player built by hand and registered with {@code /crime assignjail}. Synthesising
     * records for them would mean the first release after this update demolished somebody's jail and
     * "restored" whatever the mod guessed had been there before it.
     */
    public static CompoundTag v5to6(CompoundTag tag) {
        CompoundTag out = tag.copy();
        if (!out.contains("holdingCells", Tag.TAG_LIST)) {
            out.put("holdingCells", new ListTag());
        }
        out.putInt(TAG_SCHEMA, 6);
        return out;
    }

    /**
     * Adds the 0.5.1 social-crime collections. All six start empty and none of them is written here.
     *
     * <p>The temptation is to seed {@code criminalVillagers} from villagers who already have crime
     * records against them, or {@code warrants} from players who are currently Wanted. Neither is
     * defensible. A criminal job is an assignment this mod makes deliberately and can un-make; deriving
     * one from history would make a villager a thief because a player once hit them. A warrant carries
     * an id and a revision that bounty claims are keyed on forever, and synthesising one would mint a
     * claim key for a wanted state nobody was ever charged under.
     */
    public static CompoundTag v6to7(CompoundTag tag) {
        CompoundTag out = tag.copy();
        out.putInt(TAG_SCHEMA, 7);
        return out;
    }

    /**
     * Stamps the 0.6.0 schema and writes nothing whatsoever.
     *
     * <p>Every field this release adds is optional, and every one of them reads its absence as the
     * legacy answer, which is what makes an empty step the correct step rather than a lazy one:
     * {@code sentenceId} on a case is null (the case belongs to no sentence), {@code surrenderCredited}
     * and {@code legacyBound} on a jail state are false (nobody has claimed the discount, and the
     * legacy case binding has not been attempted), {@code legacyBound} on a holding cell is false for
     * the same reason on the NPC side, {@code paidAmount} on a bounty claim is absent (the claim is
     * treated as fully consumed, which is the conservative reading), and the {@code fenceStock},
     * {@code transactions}, {@code propertyEscrow}, {@code pendingCellRestorations} and
     * {@code quarantine} lists are all empty.
     *
     * <p>The temptation this step exists to refuse is seeding those defaults with something plausible.
     * Binding a live sentence to the cases open when it started would be a guess about which charges a
     * sentence was for, made from a start tick no 0.5.1 world recorded; giving a legacy bounty claim a
     * {@code paidAmount} of zero would say the warrant had never been paid, which is precisely the
     * double-pay this release closes. Nothing here knows those answers, so nothing here writes them —
     * the fields are filled in by the code that genuinely learns the value, at the moment it learns it.
     */
    public static CompoundTag v7to8(CompoundTag tag) {
        CompoundTag out = tag.copy();
        out.putInt(TAG_SCHEMA, SCHEMA_0_6_0);
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static CrimeCommunityKey overworld(int villageId) {
        return new CrimeCommunityKey(ResourceLocation.parse(ASSUMED_DIMENSION), villageId);
    }

    /** Adds one context entry to a record tag, creating the compound if needed. */
    private static void stampContext(CompoundTag record, String key, String value) {
        CompoundTag context = record.contains("context", Tag.TAG_COMPOUND)
                ? record.getCompound("context")
                : new CompoundTag();
        context.putString(key, value);
        record.put("context", context);
    }
}

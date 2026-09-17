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
    /** Family loyalty: withheld observations and the optional tags around them, all absent-as-empty. */
    public static final int SCHEMA_FAMILY = 11;
    /**
     * The 0.7.2 exclusive-Thief occupation schema: lifecycle status, origin, establishment timing, a
     * dimension-qualified worksite, reservation/grace clocks and an explicit historical-profession
     * kind on every criminal record.
     */
    public static final int SCHEMA_OCCUPATION = 12;
    /**
     * The 0.7.3 civic-facility schema: facility assignments and the cell reservations against them.
     *
     * <p>Additive only. Both collections are absent in every world written before this release, and
     * absent already reads as empty, so {@link #v12to13} writes nothing at all -- see its javadoc for
     * why seeding either one from the existing jail roster would be a fabrication rather than a
     * convenience.
     */
    public static final int SCHEMA_TOWNSTEAD_FACILITIES = 13;
    /**
     * The 0.7.4 schema: property policies, the loss receipts written against them, and civic service
     * contracts.
     *
     * <p>Additive only, and every one of the three is behind a switch that is off by default. All
     * three collections are absent in every world written before this release, absent already reads as
     * empty, and neither property law nor community service does anything at all until an operator
     * turns it on -- so {@link #v13to14} writes nothing but the version, and a schema-13 world loads
     * and plays exactly as it did.
     *
     * <p>The name is the one this schema was introduced under and is kept: renaming a released
     * constant buys nothing and breaks every reference to it.
     */
    public static final int SCHEMA_PROPERTY_LAW = 14;
    public static final int CURRENT_SCHEMA = SCHEMA_PROPERTY_LAW;

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
        if (schema < 11) {
            working = v10to11(working);
        }
        if (schema < 12) {
            working = v11to12(working);
        }
        if (schema < 13) {
            working = v12to13(working);
        }
        if (schema < 14) {
            working = v13to14(working);
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
        out.putInt(TAG_SCHEMA, 8);
        return out;
    }

    /**
     * Stamps the 0.7.0 family schema and writes nothing, for the reason {@link #v7to8} writes nothing.
     *
     * <p>Everything family loyalty adds is optional and reads its absence as the legacy answer: an
     * observation with no {@code report} tag is {@code PENDING}, and a world written before this
     * release simply has no withheld observations in it, which is the truth — nobody declined to
     * report anything before the code that lets them existed.
     *
     * <p>The temptation this step refuses is re-reading old crime records and marking the offender's
     * relatives among their witnesses as having withheld. Those villagers <em>did</em> report: the
     * crime was committed as witnessed and everything downstream already charged for it. Rewriting
     * that afterwards would change a Heat total somebody already served for.
     */
    public static CompoundTag v10to11(CompoundTag tag) {
        CompoundTag out = tag.copy();
        out.putInt(TAG_SCHEMA, SCHEMA_FAMILY);
        return out;
    }

    // ------------------------------------------------------------------ 11 -> 12

    /**
     * Gives every existing criminal record the 0.7.2 occupational fields, structurally and only
     * structurally (spec §"Migration performs structural changes only").
     *
     * <p>Three decisions are made here and no more.
     *
     * <p><b>Every existing Thief becomes established and unbound.</b> Spec §10.4 says migrated thieves
     * are established by default so that upgrading cannot quietly remove them from the world, and
     * §10.5 says existing wandering thieves are grandfathered as employed but unbound. Unbound is the
     * honest state: no pre-0.7.2 world contains a Mask Station, so inventing a worksite would be
     * inventing a block, which §10.5 forbids outright.
     *
     * <p><b>The historical profession keeps its exact meaning.</b> A blank legacy string meant "there
     * was a profession and it could not be read" and becomes {@code UNREADABLE}; an absent key meant
     * "nothing was displaced" and becomes {@code NONE}. Neither is upgraded into a guess.
     *
     * <p><b>Nothing entity-sensitive happens.</b> Whether a migrated Thief is actually a guard now,
     * whether its record contradicts its profession, and whether it can even be classified are all
     * questions about an entity, and migration runs from {@code computeIfAbsent} with no server. Those
     * are reconciled on legitimate load instead, which is also the only place they can be answered
     * correctly for a villager whose chunk is asleep.
     *
     * <p>A fence is left at {@code status=none}: occupational exclusivity is a Thief rule (§9.5).
     */
    public static CompoundTag v11to12(CompoundTag tag) {
        CompoundTag out = tag.copy();
        ListTag criminals = out.getList("criminalVillagers", Tag.TAG_COMPOUND);
        int thieves = 0;
        for (int i = 0; i < criminals.size(); i++) {
            CompoundTag record = criminals.getCompound(i);
            if (!record.contains("previousProfessionKind")) {
                record.putString("previousProfessionKind", record.contains("previousProfessionId")
                        ? (record.getString("previousProfessionId").isBlank() ? "unreadable" : "id")
                        : "none");
            }
            if (record.contains("status")) {
                continue; // already migrated: running this step twice must change nothing
            }
            boolean thief = "thief".equals(record.getString("job"));
            record.putString("status", thief ? "established_unbound" : "none");
            record.putString("source", thief
                    ? (record.getBoolean("wildOrigin") ? "wild" : "migration")
                    : "unknown");
            record.putLong("establishedAt", 0L);
            record.putLong("lastVisitAt", 0L);
            record.putLong("employedTicks", 0L);
            record.putLong("reservationAt", 0L);
            record.putLong("unboundSince", 0L);
            if (thief) {
                thieves++;
            }
        }
        if (thieves > 0) {
            McaCrime.LOGGER.info("MCA: Crime migrated {} existing thief record(s) to the 0.7.2 exclusive "
                    + "profession. They are established and unbound until they claim a Mask Station; no "
                    + "station was placed and no previous profession was invented.", thieves);
        }
        out.putInt(TAG_SCHEMA, SCHEMA_OCCUPATION);
        return out;
    }

    // ------------------------------------------------------------------ 12 -> 13

    /**
     * Stamps the 0.7.3 civic-facility schema and writes nothing whatsoever.
     *
     * <p>Two collections arrive with this version -- {@code facilities} and {@code cellReservations} --
     * and both read their absence as empty, which makes an empty step the correct step rather than a
     * lazy one. A schema-12 world therefore loads with no facilities and no reservations, and behaves
     * exactly as it did before the update until an operator assigns one.
     *
     * <p>The temptation this step refuses is seeding {@code facilities} from the existing
     * {@code jailRoster}. Those anchors are real, they are in the right places, and turning each one
     * into a {@code JAIL_CELL} assignment would give every existing world a working facility layer for
     * free. It would also be a fabrication in two directions at once. A facility assignment asserts a
     * <em>building</em> -- a village, a building id and the revision it was read at -- and a jail
     * anchor knows none of those, so every reference would have to be minted unbound and would then
     * claim to have been validated when nothing had looked at it. And an assignment carries a capacity
     * that arrests are reserved against, so inventing one would start routing arrests into a structure
     * whose suitability nobody ever assessed; the anchor ladder already handles those anchors correctly
     * and does not need a facility record to do it.
     *
     * <p>Reservations are emptier still, and for a simpler reason: a reservation is a lease held by a
     * live escort, and no escort survives the restart that runs this migration.
     */
    public static CompoundTag v12to13(CompoundTag tag) {
        CompoundTag out = tag.copy();
        out.putInt(TAG_SCHEMA, SCHEMA_TOWNSTEAD_FACILITIES);
        return out;
    }

    // ------------------------------------------------------------------ 13 -> 14

    /**
     * Stamps the 0.7.4 schema and writes nothing whatsoever.
     *
     * <p>Three collections arrive with this version -- {@code propertyPolicies},
     * {@code propertyReceipts} and {@code serviceContracts} -- and all three read their absence as
     * empty. A schema-13 world therefore loads with no property claimed, no losses recorded and no
     * civic work outstanding, which is not merely the convenient answer but the only true one:
     * property law and community service both ship off, and nothing in an older save was ever played
     * under either.
     *
     * <p>The temptation this step refuses is seeding {@code propertyPolicies} from the facilities of
     * schema 13. Evidence storage and jail cells are exactly the containers §10.1 wants protected, they
     * are already recorded, and minting a policy for each would give an upgrading world working
     * property protection with no further action. It would also make a claim nobody made. A policy
     * decides whether taking from a container is a crime with a named victim, and turning an operator's
     * facility assignment into one would start charging players for a rule that was introduced
     * underneath them -- retroactively, on a save that has been played for a year, at a container they
     * have been using since before this release existed. The automatic sweep can write exactly those
     * policies, on a server whose operator switched {@code autoProtectGeneratedProperty} on and thereby
     * asked for them.
     *
     * <p>Receipts are emptier still, and for the plainer reason: a receipt is the record of a loss under
     * a law that did not exist in a schema-13 world.
     */
    public static CompoundTag v13to14(CompoundTag tag) {
        CompoundTag out = tag.copy();
        out.putInt(TAG_SCHEMA, SCHEMA_PROPERTY_LAW);
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static CrimeCommunityKey overworld(int villageId) {
        return new CrimeCommunityKey(new ResourceLocation(ASSUMED_DIMENSION), villageId);
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

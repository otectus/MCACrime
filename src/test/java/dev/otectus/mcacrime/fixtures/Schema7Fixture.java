package dev.otectus.mcacrime.fixtures;

import dev.otectus.mcacrime.ledger.CrimeFlag;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.state.world.CrimeDataMigrations;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.EnumSet;
import java.util.UUID;

/**
 * One saved world as 0.5.1 wrote it, built in code.
 *
 * <p>Every migration test in 0.6.0 upgrades the same store, and it is built here rather than checked
 * in as a {@code .dat} under {@code src/test/resources} for two reasons. The first is that the suite
 * runs under ModDevGradle's NeoForge runner from {@code build/minecraft-junit}, so a file fixture has
 * to be found through the {@code mcacrime.projectRoot} property while a code fixture simply is on the
 * classpath — and a binary blob would mean nobody could read the input to a failing assertion. The
 * second is more important: the tags below are written <b>by hand</b>, not by calling the mod's own
 * {@code save} methods. A fixture that serialised live objects would silently acquire every field
 * 0.6.0 adds the moment it was added — {@code sentenceId} on a case, {@code surrenderCredited} on a
 * jail state, {@code paidAmount} on a bounty claim — and would then be asserting that the new code
 * can read its own output, which is the one thing nobody doubts. What needs proving is that it can
 * read what the <em>old</em> jar produced, so the old shapes are frozen here and only change when
 * somebody decides they should.
 *
 * <p>What the store contains, and why each piece is in it:
 *
 * <ul>
 *   <li>Three cases against {@link #OFFENDER}: two committed before {@link #SENTENCE_START} and one
 *       after it. Schema 7 has nowhere to record which sentence a case belongs to, so this is the
 *       whole legacy-binding problem in three rows — a build that binds by commit time must take the
 *       first two and leave the third open.</li>
 *   <li>A fourth case carrying {@link CrimeFlag#MANDATORY_CUSTODY} in its context, so a settlement
 *       policy can be shown refusing to price it.</li>
 *   <li>A fifth case whose {@code type} is not a parseable {@link net.minecraft.resources.ResourceLocation},
 *       for the quarantine path: the other four must still load.</li>
 *   <li>A custody record, a fence restock stamp, a warrant with a paid claim at revision 1, and a
 *       holding cell — one row in each collection that 0.6.0 adds a field to.</li>
 * </ul>
 *
 * <p>The cell's block list is deliberately empty. Restoring one needs {@code BuiltInRegistries.BLOCK},
 * and an empty list is a cell whose record is real and whose restoration journal has nothing in it,
 * which is exactly the case worth exercising anyway.
 */
public final class Schema7Fixture {

    /** The offender every case in the store is against. */
    public static final UUID OFFENDER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    /** The prisoner holding the cell, and the captive in the custody record. */
    public static final UUID PRISONER = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    /** The guard holding the custody record and the hunter who claimed the bounty. */
    public static final UUID CAPTOR = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    /** The fence whose restock stamp is in the store. */
    public static final UUID FENCE = UUID.fromString("00000000-0000-0000-0000-0000000000a4");

    /** The sentence the cell was raised for. Nothing in schema 7 links it to a case. */
    public static final UUID SENTENCE = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    /** The warrant the claim below was paid against. */
    public static final UUID WARRANT = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    /** The two cases before this tick are the ones a sentence could plausibly have been for. */
    public static final long SENTENCE_START = 2_000L;

    public static final UUID CASE_EARLY = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    public static final UUID CASE_ALSO_EARLY = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    public static final UUID CASE_LATER = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    public static final UUID CASE_MANDATORY_CUSTODY = UUID.fromString("00000000-0000-0000-0000-0000000000c4");
    public static final UUID CASE_UNPARSABLE_TYPE = UUID.fromString("00000000-0000-0000-0000-0000000000c5");

    /** The claim key 0.5.1 derived when an entry carried no explicit {@code claimKey}. */
    public static final String CLAIM_KEY = OFFENDER + "/" + WARRANT + "/1";

    private Schema7Fixture() {
    }

    /** The whole store, stamped schema 7. Each call returns a fresh tag, so tests may mutate it. */
    public static CompoundTag store() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.SCHEMA_0_5_1);

        ListTag ledger = new ListTag();
        ledger.add(caseRecord(CASE_EARLY, "mcacrime:theft", SENTENCE_START - 500L, null));
        ledger.add(caseRecord(CASE_ALSO_EARLY, "mcacrime:assault", SENTENCE_START - 100L, null));
        ledger.add(caseRecord(CASE_LATER, "mcacrime:theft", SENTENCE_START + 900L, null));
        ledger.add(caseRecord(CASE_MANDATORY_CUSTODY, "mcacrime:murder", SENTENCE_START - 300L,
                CrimeFlag.encode(EnumSet.of(CrimeFlag.MANDATORY_CUSTODY))));
        ledger.add(caseRecord(CASE_UNPARSABLE_TYPE, "Not A Crime Id", SENTENCE_START - 50L, null));
        tag.put("ledger", ledger);

        tag.put("custody", custody());
        tag.put("holdingCells", holdingCells());
        tag.put("warrants", warrants());
        tag.put("bountyClaims", bountyClaims());
        tag.put("fenceRestockDay", fenceRestock());
        return tag;
    }

    /** One ledger row in the 0.5.1 shape: no {@code sentenceId}, flags in the context map. */
    public static CompoundTag caseRecord(UUID id, String type, long timeCommitted, String flags) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("offender", OFFENDER);
        tag.putString("type", type);
        tag.putBoolean("witnessed", true);
        tag.putLong("timeCommitted", timeCommitted);
        tag.putLong("heatGenerated", 20L);
        tag.putLong("karmaDelta", -15L);
        tag.putLong("fineAmount", 30L);
        tag.putLong("jailTicks", 600L);
        tag.putString("resolution", Resolution.UNRESOLVED.name());
        if (flags != null) {
            CompoundTag context = new CompoundTag();
            context.putString(CrimeFlag.CONTEXT_KEY, flags);
            tag.put("context", context);
        }
        return tag;
    }

    /** A lawful custody record held by a guard, keyed by captive as 0.5.1 keyed it. */
    private static CompoundTag custody() {
        CompoundTag owner = new CompoundTag();
        owner.putString("type", "GUARD");
        owner.putUUID("owner", CAPTOR);

        CompoundTag record = new CompoundTag();
        record.putUUID("captive", PRISONER);
        record.putBoolean("player", true);
        record.putBoolean("lawful", true);
        record.put("owner", owner);
        record.putString("restraint", "NONE");
        record.putLong("start", SENTENCE_START);
        record.putLong("remaining", 1_200L);
        record.putLong("held", 0L);
        record.putInt("hx", 8);
        record.putInt("hy", 64);
        record.putInt("hz", 8);
        record.putString("hdim", "minecraft:overworld");

        CompoundTag table = new CompoundTag();
        table.put(PRISONER.toString(), record);
        return table;
    }

    /** One cell, with an empty restoration journal — see the class note. */
    private static ListTag holdingCells() {
        CompoundTag cell = new CompoundTag();
        cell.putUUID("prisoner", PRISONER);
        cell.putUUID("sentence", SENTENCE);
        cell.putInt("x", 16);
        cell.putInt("y", 64);
        cell.putInt("z", 16);
        cell.putString("dim", "minecraft:overworld");
        cell.putInt("radius", 3);
        cell.putLong("created", SENTENCE_START);
        cell.put("blocks", new ListTag());

        ListTag cells = new ListTag();
        cells.add(cell);
        return cells;
    }

    private static ListTag warrants() {
        CompoundTag warrant = new CompoundTag();
        warrant.putUUID("id", WARRANT);
        warrant.putUUID("offender", OFFENDER);
        warrant.putLong("revision", 1L);
        warrant.putLong("openedAt", SENTENCE_START - 500L);
        warrant.putLong("lastRevisedAt", SENTENCE_START - 100L);
        warrant.putString("topOffense", "mcacrime:assault");
        ListTag records = new ListTag();
        CompoundTag linked = new CompoundTag();
        linked.putUUID("id", CASE_EARLY);
        records.add(linked);
        warrant.put("records", records);
        warrant.putBoolean("open", true);
        warrant.putLong("closedAt", 0L);

        ListTag list = new ListTag();
        list.add(warrant);
        return list;
    }

    /** A claim at revision 1 with no {@code paidAmount}: the legacy shape 0.6.0 has to interpret. */
    private static ListTag bountyClaims() {
        CompoundTag claim = new CompoundTag();
        claim.putUUID("target", OFFENDER);
        claim.putUUID("warrantId", WARRANT);
        claim.putLong("revision", 1L);
        claim.putUUID("claimant", CAPTOR);
        claim.putLong("reward", 250L);
        claim.putLong("claimedAt", SENTENCE_START);
        claim.putString("type", "CAPTURED_ALIVE");

        ListTag list = new ListTag();
        list.add(claim);
        return list;
    }

    private static CompoundTag fenceRestock() {
        CompoundTag stamps = new CompoundTag();
        stamps.putLong(FENCE.toString(), 9L);
        return stamps;
    }
}

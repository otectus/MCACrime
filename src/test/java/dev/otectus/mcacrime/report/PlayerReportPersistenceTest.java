package dev.otectus.mcacrime.report;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerReportPersistenceTest {
    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");

    @Test
    void threatRoundTripRetainsUnknownIdentityAndOriginalTransaction() {
        UUID transaction = UUID.randomUUID();
        ThreatReceipt receipt = new ThreatReceipt(transaction, UUID.randomUUID(), UUID.randomUUID(), null, "",
                UUID.randomUUID(), OVERWORLD, new BlockPos(4, 70, -9), null, 100L, 500L,
                ThreatReceipt.Outcome.ACTIVE);
        ThreatReceipt loaded = ThreatReceipt.load(receipt.save());
        assertEquals(receipt, loaded);
        assertNull(loaded.perceivedSuspectId());
        assertEquals(transaction, loaded.transactionId());
        assertEquals(ThreatReceipt.Outcome.ATTEMPTED,
                loaded.finish(ThreatReceipt.Outcome.ATTEMPTED).outcome());
        assertEquals(ThreatReceipt.Outcome.ATTEMPTED,
                loaded.finish(ThreatReceipt.Outcome.ATTEMPTED).finish(ThreatReceipt.Outcome.COMPLETED).outcome(),
                "one terminal outcome cannot be rewritten as another");
    }

    @Test
    void reportRoundTripRetainsReceivingAuthorityAndDistinctDispatch() {
        CrimeCommunityKey authority = new CrimeCommunityKey(OVERWORLD, 12);
        PlayerReportReceipt receipt = new PlayerReportReceipt(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), authority,
                200L, PlayerReportReceipt.Dispatch.AWAITING_GUARD, 240L);
        assertEquals(receipt, PlayerReportReceipt.load(receipt.save()));
        assertEquals(PlayerReportReceipt.Dispatch.SEARCHING,
                receipt.withDispatch(PlayerReportReceipt.Dispatch.SEARCHING, 300L).dispatch());
    }

    @Test
    void identifiedThreatRetainsPerceivedNameWhileUnknownThreatRetainsNone() {
        UUID suspect = UUID.randomUUID();
        ThreatReceipt identified = new ThreatReceipt(UUID.randomUUID(), UUID.randomUUID(), suspect, suspect,
                "Mara", UUID.randomUUID(), OVERWORLD, BlockPos.ZERO, null, 1L, 100L,
                ThreatReceipt.Outcome.ACTIVE);
        assertEquals("Mara", ThreatReceipt.load(identified.save()).perceivedName());
        ThreatReceipt unknown = new ThreatReceipt(UUID.randomUUID(), UUID.randomUUID(), suspect, null,
                "must disappear", UUID.randomUUID(), OVERWORLD, BlockPos.ZERO, null, 1L, 100L,
                ThreatReceipt.Outcome.ACTIVE);
        assertEquals("", unknown.perceivedName());
    }

    @Test
    void storeRoundTripKeepsBothTablesAndFutureSchemaIsPreservedReadOnly() {
        ThreatReceipt threat = new ThreatReceipt(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, "",
                UUID.randomUUID(), OVERWORLD, BlockPos.ZERO, null, 10L, 100L,
                ThreatReceipt.Outcome.ATTEMPTED);
        PlayerReportReceipt report = new PlayerReportReceipt(UUID.randomUUID(), threat.victimId(),
                threat.observationId(), threat.transactionId(), null, UUID.randomUUID(), null, 20L,
                PlayerReportReceipt.Dispatch.RECORDED, 20L);
        CompoundTag source = new CompoundTag();
        source.putInt("schema", 1);
        ListTag threats = new ListTag(); threats.add(threat.save()); source.put("threats", threats);
        ListTag reports = new ListTag(); reports.add(report.save()); source.put("reports", reports);

        PlayerReportData loaded = PlayerReportData.load(source);
        assertEquals(threat, loaded.threat(threat.transactionId()).orElseThrow());
        assertEquals(report, loaded.reportsBy(threat.victimId()).get(0));
        CompoundTag saved = loaded.save(new CompoundTag());
        assertEquals(1, saved.getList("threats", 10).size());
        assertEquals(1, saved.getList("reports", 10).size());

        CompoundTag future = new CompoundTag();
        future.putInt("schema", 99); future.putString("unknown", "keep-me");
        PlayerReportData futureData = PlayerReportData.load(future);
        assertTrue(futureData.readOnlyFutureData());
        assertEquals("keep-me", futureData.save(new CompoundTag()).getString("unknown"));
    }

    @Test
    void malformedRowsAreSkippedWithoutDroppingValidRows() {
        ThreatReceipt valid = new ThreatReceipt(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, "",
                UUID.randomUUID(), OVERWORLD, BlockPos.ZERO, null, 1L, 2L,
                ThreatReceipt.Outcome.ACTIVE);
        CompoundTag source = new CompoundTag();
        source.putInt("schema", 1);
        ListTag rows = new ListTag(); rows.add(new CompoundTag()); rows.add(valid.save());
        source.put("threats", rows);
        PlayerReportData loaded = PlayerReportData.load(source);
        assertEquals(valid, loaded.threat(valid.transactionId()).orElseThrow());
    }

    @Test
    void oversizedThreatTableDropsOldestAndKeepsNewest() {
        CompoundTag source = new CompoundTag();
        source.putInt("schema", 1);
        ListTag rows = new ListTag();
        UUID first = null;
        UUID last = null;
        for (int i = 0; i < PlayerReportData.MAX_THREATS + 3; i++) {
            UUID transaction = UUID.randomUUID();
            if (i == 0) first = transaction;
            last = transaction;
            rows.add(new ThreatReceipt(transaction, UUID.randomUUID(), UUID.randomUUID(), null, "",
                    UUID.randomUUID(), OVERWORLD, BlockPos.ZERO, null, i, i + 1000L,
                    ThreatReceipt.Outcome.ACTIVE).save());
        }
        source.put("threats", rows);
        PlayerReportData loaded = PlayerReportData.load(source);
        assertTrue(loaded.threat(first).isEmpty());
        assertTrue(loaded.threat(last).isPresent());
        assertEquals(PlayerReportData.MAX_THREATS,
                loaded.save(new CompoundTag()).getList("threats", 10).size());
    }

    @Test
    void fullPlayerCapacityRejectsAnotherReceiptWithoutEvictingAcceptedRows() {
        UUID reporter = UUID.randomUUID();
        CompoundTag source = reportStore(reporter, PlayerReportData.MAX_REPORTS_PER_PLAYER,
                PlayerReportReceipt.Dispatch.AWAITING_GUARD, 0L);
        PlayerReportData loaded = PlayerReportData.load(source);
        assertFalse(loaded.capacityAvailable(reporter, UUID.randomUUID()));
        assertEquals(PlayerReportData.MAX_REPORTS_PER_PLAYER, loaded.reportsBy(reporter).size());
        assertEquals(PlayerReportData.MAX_REPORTS_PER_PLAYER,
                loaded.save(new CompoundTag()).getList("reports", 10).size());
    }

    @Test
    void dispatchScanRotatesPastFirstBudgetAndRetriesSearchingOnlyWhenDue() {
        UUID reporter = UUID.randomUUID();
        PlayerReportData loaded = PlayerReportData.load(reportStore(reporter, 17,
                PlayerReportReceipt.Dispatch.AWAITING_GUARD, 0L));
        var first = loaded.pendingDispatches(100L, 8);
        var second = loaded.pendingDispatches(100L, 8);
        assertEquals(8, first.size());
        assertEquals(8, second.size());
        assertTrue(first.stream().map(PlayerReportReceipt::reportId).noneMatch(
                id -> second.stream().anyMatch(row -> row.reportId().equals(id))),
                "an unreachable first page must not starve later receipts");

        PlayerReportData searching = PlayerReportData.load(reportStore(reporter, 1,
                PlayerReportReceipt.Dispatch.SEARCHING, 500L));
        assertTrue(searching.pendingDispatches(499L, 8).isEmpty());
        assertEquals(1, searching.pendingDispatches(500L, 8).size());
    }

    private static CompoundTag reportStore(UUID reporter, int count,
                                           PlayerReportReceipt.Dispatch dispatch, long nextAttempt) {
        CompoundTag source = new CompoundTag();
        source.putInt("schema", 1);
        ListTag rows = new ListTag();
        for (int i = 0; i < count; i++) {
            rows.add(new PlayerReportReceipt(UUID.randomUUID(), reporter, UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, i, dispatch,
                    nextAttempt).save());
        }
        source.put("reports", rows);
        return source;
    }
}

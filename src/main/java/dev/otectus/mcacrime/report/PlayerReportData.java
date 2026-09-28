package dev.otectus.mcacrime.report;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/** Bounded overworld-owned persistence for player reports and visible mugging threats. */
public final class PlayerReportData extends SavedData {
    public static final String DATA_NAME = "mcacrime_player_reports";
    public static final int MAX_THREATS = 1024;
    public static final int MAX_REPORTS = 2048;
    public static final int MAX_REPORTS_PER_PLAYER = 32;

    private final Map<UUID, ThreatReceipt> threats = new LinkedHashMap<>();
    private final Map<UUID, PlayerReportReceipt> reports = new LinkedHashMap<>();
    private final Map<UUID, ReportReservation> reservations = new LinkedHashMap<>();
    private CompoundTag futureData;
    private int dispatchCursor;

    /** Transient admission claim held across the cancellable pre-report event. */
    public record ReportReservation(UUID token, UUID reporterId, UUID evidenceId) { }

    public static PlayerReportData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(PlayerReportData::load, PlayerReportData::new, DATA_NAME);
    }

    public Optional<ThreatReceipt> threat(UUID transactionId) {
        return Optional.ofNullable(transactionId == null ? null : threats.get(transactionId));
    }

    public boolean putThreat(MinecraftServer server, ThreatReceipt receipt) {
        if (!writable(server)
                || receipt == null || threats.containsKey(receipt.transactionId())) return false;
        threats.put(receipt.transactionId(), receipt);
        trimOldest(threats, MAX_THREATS);
        setDirty();
        return true;
    }

    public boolean finishThreat(MinecraftServer server, UUID transactionId, ThreatReceipt.Outcome outcome) {
        if (!writable(server)) return false;
        ThreatReceipt current = threats.get(transactionId);
        if (current == null || current.outcome() != ThreatReceipt.Outcome.ACTIVE) return false;
        threats.put(transactionId, current.finish(outcome));
        setDirty();
        return true;
    }

    /** Current or recent server-authored threat basis used by strict thief-defense policy. */
    public boolean hasThreatBasis(UUID victim, UUID offender, long now, long graceTicks) {
        if (victim == null || offender == null) return false;
        long earliest = now - Math.max(0L, graceTicks);
        return threats.values().stream().anyMatch(receipt -> receipt.victimId().equals(victim)
                && receipt.offenderId().equals(offender) && receipt.observedAt() >= earliest
                && !receipt.expired(now));
    }

    /**
     * Reserves capacity before any live threat is interrupted or cancellable filing hook is posted.
     * Reservations count toward both limits, so a reentrant event listener cannot take the final slot.
     */
    public synchronized Optional<ReportReservation> reserveReport(MinecraftServer server, UUID reporter,
            UUID evidence, Predicate<PlayerReportReceipt> reclaimable) {
        if (!writable(server) || reporter == null || evidence == null) return Optional.empty();
        if (reclaimable != null) {
            int before = reports.size();
            reports.values().removeIf(reclaimable);
            if (before != reports.size()) setDirty();
        }
        if (!capacityAvailable(reporter, evidence)) return Optional.empty();
        ReportReservation reservation = new ReportReservation(UUID.randomUUID(), reporter, evidence);
        reservations.put(reservation.token(), reservation);
        return Optional.of(reservation);
    }

    public synchronized void cancelReservation(ReportReservation reservation) {
        if (reservation != null) reservations.remove(reservation.token(), reservation);
    }

    public synchronized boolean addReport(MinecraftServer server, PlayerReportReceipt receipt,
                                          ReportReservation reservation) {
        if (!writable(server) || receipt == null || reservation == null
                || !reservation.reporterId().equals(receipt.reporterId())
                || !reservation.evidenceId().equals(receipt.evidenceId())
                || !reservations.remove(reservation.token(), reservation)
                || reports.containsKey(receipt.reportId()) || reported(receipt.reporterId(), receipt.evidenceId())) {
            return false;
        }
        reports.put(receipt.reportId(), receipt);
        setDirty();
        return true;
    }

    public boolean replaceReport(MinecraftServer server, PlayerReportReceipt receipt) {
        if (!writable(server)
                || receipt == null || !reports.containsKey(receipt.reportId())) return false;
        reports.put(receipt.reportId(), receipt);
        setDirty();
        return true;
    }

    public boolean reported(UUID reporter, UUID evidence) {
        return reports.values().stream().anyMatch(row -> row.reporterId().equals(reporter)
                && row.evidenceId().equals(evidence));
    }

    public List<PlayerReportReceipt> reportsBy(UUID reporter) {
        return reports.values().stream().filter(row -> row.reporterId().equals(reporter))
                .sorted(Comparator.comparingLong(PlayerReportReceipt::acceptedAt).reversed()).toList();
    }

    public int reportCount() { return reports.size(); }
    public synchronized long pendingDispatchCount() {
        return reports.values().stream().filter(PlayerReportData::pendingDispatch).count();
    }

    /** Bounded fair scan; SEARCHING rows become eligible after their pursuit retry time. */
    public synchronized List<PlayerReportReceipt> pendingDispatches(long now, int limit) {
        List<PlayerReportReceipt> eligible = reports.values().stream()
                .filter(PlayerReportData::pendingDispatch)
                .filter(row -> row.nextDispatchAt() <= now).toList();
        int count = Math.min(Math.max(0, limit), eligible.size());
        if (count == 0) {
            if (eligible.isEmpty()) dispatchCursor = 0;
            return List.of();
        }
        int start = Math.floorMod(dispatchCursor, eligible.size());
        List<PlayerReportReceipt> selected = new ArrayList<>(count);
        for (int i = 0; i < count; i++) selected.add(eligible.get((start + i) % eligible.size()));
        dispatchCursor = (start + count) % eligible.size();
        return List.copyOf(selected);
    }

    public int prune(MinecraftServer server, long now) {
        if (!writable(server)) return 0;
        int before = threats.size();
        threats.values().removeIf(row -> row.expired(now));
        if (before != threats.size()) setDirty();
        return before - threats.size();
    }

    synchronized boolean capacityAvailable(UUID reporter, UUID evidence) {
        if (reporter == null || evidence == null || reported(reporter, evidence)
                || reservations.values().stream().anyMatch(row -> row.reporterId().equals(reporter)
                && row.evidenceId().equals(evidence))) return false;
        if (reports.size() + reservations.size() >= MAX_REPORTS) return false;
        long playerReports = reports.values().stream().filter(row -> row.reporterId().equals(reporter)).count();
        long playerReservations = reservations.values().stream()
                .filter(row -> row.reporterId().equals(reporter)).count();
        return playerReports + playerReservations < MAX_REPORTS_PER_PLAYER;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (futureData != null) return futureData.copy();
        tag.putInt("schema", 1);
        ListTag threatList = new ListTag();
        threats.values().forEach(row -> threatList.add(row.save()));
        tag.put("threats", threatList);
        ListTag reportList = new ListTag();
        reports.values().forEach(row -> reportList.add(row.save()));
        tag.put("reports", reportList);
        return tag;
    }

    public static PlayerReportData load(CompoundTag tag) {
        PlayerReportData data = new PlayerReportData();
        if (tag.getInt("schema") > 1) {
            data.futureData = tag.copy();
            McaCrime.LOGGER.warn("Player-report data uses future schema {}; preserving it read-only",
                    tag.getInt("schema"));
            return data;
        }
        readRows(tag.getList("threats", Tag.TAG_COMPOUND), row -> {
            ThreatReceipt receipt = ThreatReceipt.load(row);
            data.threats.putIfAbsent(receipt.transactionId(), receipt);
        });
        readRows(tag.getList("reports", Tag.TAG_COMPOUND), row -> {
            PlayerReportReceipt receipt = PlayerReportReceipt.load(row);
            data.reports.putIfAbsent(receipt.reportId(), receipt);
        });
        trimOldest(data.threats, MAX_THREATS);
        trimOldest(data.reports, MAX_REPORTS);
        return data;
    }

    private static <T> void trimOldest(Map<UUID, T> map, int limit) {
        while (map.size() > limit) map.remove(map.keySet().iterator().next());
    }

    private static void readRows(ListTag rows, java.util.function.Consumer<CompoundTag> reader) {
        for (int i = 0; i < rows.size(); i++) {
            try { reader.accept(rows.getCompound(i)); }
            catch (RuntimeException ex) { McaCrime.LOGGER.warn("Skipped malformed player-report row", ex); }
        }
    }

    public boolean readOnlyFutureData() { return futureData != null; }

    public boolean allowsMutations(MinecraftServer server) { return writable(server); }

    private static boolean pendingDispatch(PlayerReportReceipt row) {
        return row.dispatch() == PlayerReportReceipt.Dispatch.AWAITING_GUARD
                || row.dispatch() == PlayerReportReceipt.Dispatch.SEARCHING;
    }

    private boolean writable(MinecraftServer server) {
        return futureData == null && dev.otectus.mcacrime.state.world.ServerMutationGate.allows(server);
    }
}

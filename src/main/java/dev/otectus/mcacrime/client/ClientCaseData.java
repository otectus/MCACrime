package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.network.CaseLedgerS2CPacket;

import java.util.List;

/**
 * Client-side cache of the player's own case file, filled by an explicit request and read by the
 * dossier screen. Display-only; never classloaded on a dedicated server.
 *
 * <p>Held rather than re-requested per frame, and deliberately allowed to go stale: the dossier is a
 * record of what has already happened, so showing the snapshot from when the screen opened is correct.
 * Reopening it asks again.
 */
public final class ClientCaseData {

    private static volatile List<CaseLedgerS2CPacket.Row> rows = List.of();
    private static volatile int totalOpen;
    private static volatile long totalDue;
    private static volatile boolean received;
    private static volatile int revision;

    private ClientCaseData() {
    }

    public static void update(CaseLedgerS2CPacket packet) {
        rows = packet.rows();
        totalOpen = packet.totalOpen();
        totalDue = packet.totalDue();
        received = true;
        revision++;
    }

    public static void clear() {
        rows = List.of();
        totalOpen = 0;
        totalDue = 0L;
        received = false;
        revision++;
    }

    public static List<CaseLedgerS2CPacket.Row> rows() {
        return rows;
    }

    public static int totalOpen() {
        return totalOpen;
    }

    public static long totalDue() {
        return totalDue;
    }

    /**
     * Bumped whenever the cache changes, so an open screen can notice the answer arriving.
     *
     * <p>The dossier's rows are widgets, and rebuilding widgets from inside {@code render()} would
     * mutate the screen's renderable list while it is being iterated. Comparing this counter in
     * {@code tick()} is how the screen rebuilds at a moment when that is safe.
     */
    public static int revision() {
        return revision;
    }

    /** False until the first answer arrives, so the screen can say "loading" rather than "no crimes". */
    public static boolean received() {
        return received;
    }
}

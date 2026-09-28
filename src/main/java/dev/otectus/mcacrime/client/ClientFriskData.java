package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.frisk.SeizureKind;
import dev.otectus.mcacrime.network.FriskSnapshotS2CPacket;

import java.util.List;

/**
 * What the open frisking screen was last told (M5.2).
 *
 * <p>Client-only and decides nothing. It holds the session id the transfer packet must quote, the
 * per-slot revisions it must echo and which of the two legal events the search is, so the screen
 * can label itself honestly. Every one of those is a value the server sent and will check again when
 * the transfer arrives; changing any of them here only produces a refusal.
 */
public final class ClientFriskData {

    private static long sessionId;
    private static SeizureKind kind = SeizureKind.CRIMINAL_SEIZURE;
    private static List<Integer> revisions = List.of();

    private ClientFriskData() {
    }

    public static void accept(FriskSnapshotS2CPacket packet) {
        if (packet == null) {
            return;
        }
        sessionId = packet.sessionId();
        kind = packet.kind();
        revisions = packet.revisions();
    }

    public static long sessionId() {
        return sessionId;
    }

    public static SeizureKind kind() {
        return kind;
    }

    /** The revision of one slot, or 0 when the screen has not been told about it. */
    public static int revision(int viewIndex) {
        return viewIndex >= 0 && viewIndex < revisions.size() ? revisions.get(viewIndex) : 0;
    }

    public static int size() {
        return revisions.size();
    }

    public static void clear() {
        sessionId = 0L;
        kind = SeizureKind.CRIMINAL_SEIZURE;
        revisions = List.of();
    }
}

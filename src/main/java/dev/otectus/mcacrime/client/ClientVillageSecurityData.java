package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.network.VillageSecurityS2CPacket;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * The last settlement-safety answer this client asked for.
 *
 * <p>One value, not a map, and that is the information rule rather than a simplification: the server
 * answers about the settlement the player is standing in and nowhere else, so a client that kept a
 * table of villages would be accumulating something it was never told in one go. Walking somewhere
 * else and asking again replaces it.
 *
 * <p>Display only. Nothing here is consulted by anything that decides anything; the server re-derives
 * the view for every question that matters.
 *
 * <p>Cleared on disconnect beside the other client caches, because a reading from one server must
 * never be shown on another.
 */
public final class ClientVillageSecurityData {

    @Nullable
    private static volatile VillageSecurityS2CPacket current;

    private ClientVillageSecurityData() {
    }

    /** Replaces the answer. A packet for no settlement is still an answer and is stored as one. */
    public static void update(@Nullable VillageSecurityS2CPacket packet) {
        current = packet;
    }

    /** The current answer, or empty when none has arrived or the player is in no settlement. */
    public static Optional<VillageSecurityS2CPacket> current() {
        VillageSecurityS2CPacket packet = current;
        return packet == null || !packet.present() ? Optional.empty() : Optional.of(packet);
    }

    /** Whether the server has answered at all, however it answered. */
    public static boolean answered() {
        return current != null;
    }

    public static void clear() {
        current = null;
    }
}
